package org.iskcon.kms.translation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.recipe.RecipeIngredientView;
import org.iskcon.kms.recipe.RecipeService;
import org.iskcon.kms.recipe.RecipeView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Translates a recipe into another language (E2-S6). Glossary first for ingredient names (MT mangles
 * culinary terms), machine translation for everything else, cached per (recipe, version, language)
 * so an edit — which bumps the recipe's version — silently invalidates the old translation. The MT
 * engine actually used is recorded as provenance.
 *
 * <p>Numbers are never sent to translation: quantities and units come straight from the recipe.
 */
@Service
public class RecipeTranslationService {

	private final JdbcTemplate jdbc;
	private final RecipeService recipeService;
	private final GlossaryService glossaryService;
	private final TranslationProvider provider;
	private final ObjectMapper objectMapper;

	public RecipeTranslationService(
			JdbcTemplate jdbc, RecipeService recipeService, GlossaryService glossaryService,
			TranslationProvider provider, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.recipeService = recipeService;
		this.glossaryService = glossaryService;
		this.provider = provider;
		this.objectMapper = objectMapper;
	}

	/** The translatable content, from cache or freshly translated (and then cached). */
	@Transactional
	public TranslatedRecipe translate(UUID recipeId, String language) {
		RecipeView recipe = recipeService.get(recipeId);

		TranslatedRecipe cached = readCache(recipeId, recipe.version(), language);
		if (cached != null) {
			return cached;
		}

		TranslatedRecipe translated = doTranslate(recipe, language);
		writeCache(recipeId, recipe.version(), language, translated);
		return translated;
	}

	/** The API view: translated content zipped back with the recipe's untranslated amounts. */
	@Transactional
	public TranslatedRecipeView view(UUID recipeId, String language) {
		RecipeView recipe = recipeService.get(recipeId);
		TranslatedRecipe t = translate(recipeId, language);

		List<TranslatedLine> lines = new ArrayList<>();
		List<RecipeIngredientView> base = recipe.ingredients();
		for (int i = 0; i < base.size(); i++) {
			String name = i < t.ingredientNames().size() ? t.ingredientNames().get(i) : base.get(i).ingredientName();
			lines.add(new TranslatedLine(name, base.get(i).quantity(), base.get(i).unit()));
		}
		return new TranslatedRecipeView(recipeId, language, t.provider(), t.name(), t.categoryName(),
				lines, t.method());
	}

	// ---------------------------------------------------------------------

	private TranslatedRecipe doTranslate(RecipeView recipe, String language) {
		Map<String, String> glossary = glossaryService.lookup(language);

		// One MT batch for everything that isn't a glossary hit. Order is fixed so we can map back.
		List<String> mt = new ArrayList<>();
		mt.add(recipe.name());            // 0
		mt.add(recipe.categoryName());    // 1
		List<String> method = splitMethod(recipe.method());
		int methodStart = mt.size();
		mt.addAll(method);

		List<RecipeIngredientView> lines = recipe.ingredients();
		String[] ingredientNames = new String[lines.size()];
		int[] ingredientMtIndex = new int[lines.size()];
		for (int i = 0; i < lines.size(); i++) {
			String name = lines.get(i).ingredientName();
			String override = glossary.get(name.toLowerCase());
			if (override != null) {
				ingredientNames[i] = override;
				ingredientMtIndex[i] = -1;
			} else {
				ingredientMtIndex[i] = mt.size();
				mt.add(name);
			}
		}

		List<String> out = provider.translate(mt, "en", language);

		String name = out.get(0);
		String categoryName = out.get(1);
		List<String> translatedMethod = new ArrayList<>(out.subList(methodStart, methodStart + method.size()));
		for (int i = 0; i < lines.size(); i++) {
			if (ingredientMtIndex[i] >= 0) {
				ingredientNames[i] = out.get(ingredientMtIndex[i]);
			}
		}

		return new TranslatedRecipe(name, categoryName, List.of(ingredientNames), translatedMethod, provider.name());
	}

	private TranslatedRecipe readCache(UUID recipeId, int version, String language) {
		// Matched on the provider too: a translation is only a hit for the engine that produced it, so
		// switching engines (the stub to a real one, or between real ones) re-translates rather than
		// serving the old engine's words forever. The write upserts, so the stale row is replaced.
		List<String> rows = jdbc.query("""
				SELECT content FROM recipe_translations
				WHERE recipe_id = ? AND recipe_version = ? AND language = ? AND provider = ?
				""", (rs, n) -> rs.getString("content"), recipeId, version, language, provider.name());
		if (rows.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.readValue(rows.get(0), TranslatedRecipe.class);
		} catch (JsonProcessingException e) {
			throw new ApplicationException(ErrorCode.UNEXPECTED_FAILURE, Map.of(), e);
		}
	}

	private void writeCache(UUID recipeId, int version, String language, TranslatedRecipe translated) {
		String json;
		try {
			json = objectMapper.writeValueAsString(translated);
		} catch (JsonProcessingException e) {
			throw new ApplicationException(ErrorCode.UNEXPECTED_FAILURE, Map.of(), e);
		}
		jdbc.update("""
				INSERT INTO recipe_translations (tenant_id, recipe_id, recipe_version, language, content, provider)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, CAST(? AS jsonb), ?)
				ON CONFLICT (tenant_id, recipe_id, recipe_version, language)
				DO UPDATE SET content = EXCLUDED.content, provider = EXCLUDED.provider
				""", recipeId, version, language, json, translated.provider());
	}

	private static List<String> splitMethod(String method) {
		if (method == null || method.isBlank()) {
			return List.of();
		}
		List<String> steps = new ArrayList<>();
		for (String line : method.split("\\R")) {
			if (!line.isBlank()) {
				steps.add(line.trim());
			}
		}
		return steps;
	}
}
