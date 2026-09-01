package org.iskcon.kms.library;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Taking a temple's own copy of a library recipe (E2-S12).
 *
 * <p>The copy is a full, independent row set in {@code recipes} and {@code recipe_ingredients} —
 * not a pointer, not a view, not a subscription. Everything downstream already reads those two
 * tables: scaling, meal planning, sufficiency, order generation, the printed card, translation. A
 * second kind of recipe that only half of them understood is how a bug reaches the shopping list six
 * weeks later.
 *
 * <p>{@code master_recipe_id} records where the copy came from and constrains nothing. The temple
 * may rename it, rewrite its method, change its per-head portion; a later correction in the library
 * never reaches it, and an operator deleting the library row leaves it standing.
 *
 * <h2>Tenant isolation</h2>
 *
 * <p>Both target tables call {@code enable_tenant_rls()}. {@code tenant_id} is taken from the
 * verified token by way of {@code app.tenant_id} and never from anything in the request — the
 * master row does not carry one to borrow. The application connects as a role holding neither DDL
 * nor {@code BYPASSRLS}, so a temple cannot see, read, edit or count another temple's copies for
 * the same reason it cannot see their donations: the database refuses, not the code.
 *
 * <h2>What it does in one transaction, and why that matters</h2>
 *
 * <ol>
 *   <li>Resolves the category, creating it on first use.</li>
 *   <li>Resolves every ingredient by name, creating what is missing.</li>
 *   <li>Runs sattvic enforcement on the resolved rows.</li>
 *   <li>Writes the recipe and its lines.</li>
 * </ol>
 *
 * <p>Step 3 refuses rather than overriding: an override needs a Temple Admin and a written reason,
 * and the place to give one is the recipe form, not a plus icon. Because the whole thing is one
 * transaction, a refusal at step 3 leaves nothing behind — no half-created category, no orphan
 * ingredients from a recipe the temple never got.
 */
@Service
public class RecipeImportService {

	private final JdbcTemplate jdbc;
	private final MasterRecipeService library;
	private final AuditService audit;

	public RecipeImportService(JdbcTemplate jdbc, MasterRecipeService library, AuditService audit) {
		this.jdbc = jdbc;
		this.library = library;
		this.audit = audit;
	}

	/** What an import created, so the response can say more than "done". */
	public record Imported(UUID recipeId, String name, int ingredientsCreated, boolean categoryCreated) {
	}

	@Transactional
	public Imported importRecipe(AuthenticatedUser actor, UUID masterRecipeId) {
		MasterRecipeView master = library.get(masterRecipeId);

		// Already taken, by this exact library recipe.
		Integer existing = jdbc.queryForObject(
				"SELECT count(*) FROM recipes WHERE master_recipe_id = ? AND status = 'ACTIVE'",
				Integer.class, masterRecipeId);
		if (existing != null && existing > 0) {
			throw new ApplicationException(ErrorCode.RECIPE_ALREADY_ADDED,
					Map.of("name", master.displayName()));
		}

		// Or a recipe of the same name arrived some other way — typed by hand last year, or taken
		// from a different state's book. Refused here rather than at the unique index, so the
		// message names the recipe instead of naming a constraint.
		Integer sameName = jdbc.queryForObject(
				"SELECT count(*) FROM recipes WHERE lower(name) = lower(?) AND status = 'ACTIVE'",
				Integer.class, master.displayName());
		if (sameName != null && sameName > 0) {
			throw new ApplicationException(ErrorCode.RECIPE_ALREADY_EXISTS,
					Map.of("name", master.displayName()));
		}

		CategoryResolution category = resolveCategory(master);
		List<ResolvedIngredient> ingredients = resolveIngredients(master);

		refuseProhibited(ingredients, master);

		UUID recipeId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO recipes (
					id, tenant_id, name, category_id, base_yield_qty, base_yield_unit, yield_note,
					per_head_qty, per_head_unit, method, notes, region_tag, sub_region,
					subtitle, badge, indicative_cost, why, catering_note,
					note_start, note_vessel, note_season,
					tags, serve_with, master_recipe_id, status, version)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
					CAST(? AS text[]), CAST(? AS text[]), ?, 'ACTIVE', 1)
				""",
				recipeId, master.displayName(), category.id(),
				master.yieldQty(), master.yieldUnit(), master.yieldText(),
				master.perHeadQty(), master.perHeadUnit(),
				String.join("\n", master.method()),
				master.state(), master.region(),
				master.subtitle(), master.badge(), master.indicativeCost(),
				master.why(), master.cateringNote(),
				master.noteStart(), master.noteVessel(), master.noteSeason(),
				pgArray(master.tags()), pgArray(master.serveWith()), masterRecipeId);

		int order = 1;
		for (ResolvedIngredient ingredient : ingredients) {
			jdbc.update("""
					INSERT INTO recipe_ingredients (
						tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
					""", recipeId, ingredient.id(), ingredient.quantity(), ingredient.unit(), order++);
		}

		int created = (int) ingredients.stream().filter(ResolvedIngredient::created).count();

		Map<String, Object> after = new LinkedHashMap<>();
		after.put("name", master.displayName());
		after.put("from", master.state());
		after.put("masterRecipeId", masterRecipeId.toString());
		after.put("ingredientsCreated", created);
		audit.record(actor, AuditAction.RECIPE_IMPORTED, AuditEntityType.RECIPE, recipeId,
				null, after, null);

		return new Imported(recipeId, master.displayName(), created, category.created());
	}

	// ------------------------------------------------------------------ resolution

	private record CategoryResolution(UUID id, boolean created) {
	}

	private CategoryResolution resolveCategory(MasterRecipeView master) {
		String name = CategoryMapping.nameFor(master.categoryKey(), master.categoryName());

		List<UUID> found = jdbc.queryForList(
				"SELECT id FROM recipe_categories WHERE lower(name) = lower(?)", UUID.class, name);
		if (!found.isEmpty()) {
			return new CategoryResolution(found.get(0), false);
		}

		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO recipe_categories (id, tenant_id, name, fasting_compatible)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
				""", id, name, CategoryMapping.fastingCompatible(master.categoryKey()));
		return new CategoryResolution(id, true);
	}

	private record ResolvedIngredient(
			UUID id, String name, BigDecimal quantity, String unit, boolean prohibited, boolean created) {
	}

	/**
	 * Every ingredient the recipe needs, as a row in this temple's own catalogue.
	 *
	 * <p>Matched on {@code lower(name)} first, so an existing Rice is reused rather than duplicated.
	 * What is missing is created — silently, and marked {@code library_derived} so a catalogue can
	 * be tidied later. Standing a review step in front of every import was the alternative, and it
	 * is the kind of friction that stops a feature being used at all.
	 *
	 * <p>The unit comes from the book's own quantity, which is why it can: all 46,337 ingredient
	 * lines in the library parse, into five units the catalogue already knows.
	 */
	private List<ResolvedIngredient> resolveIngredients(MasterRecipeView master) {
		List<ResolvedIngredient> resolved = new ArrayList<>();

		for (MasterRecipeView.MasterRecipeIngredient line : master.ingredients()) {
			String name = line.name().trim();

			List<Map<String, Object>> found = jdbc.queryForList(
					"SELECT id, is_sattvic_prohibited FROM ingredients WHERE lower(name) = lower(?)", name);

			if (!found.isEmpty()) {
				resolved.add(new ResolvedIngredient(
						(UUID) found.get(0).get("id"), name, line.qtyValue(), line.qtyUnit(),
						(Boolean) found.get(0).get("is_sattvic_prohibited"), false));
				continue;
			}

			// The catalogue unit is the one the recipe asked in: an ingredient first met as "200 gm"
			// is catalogued in grams, and every later recipe and stock movement speaks that unit.
			UUID id = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO ingredients (
						id, tenant_id, name, category, canonical_unit, library_derived)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, true)
					""", id, name, IngredientCategories.forName(name), line.qtyUnit());

			// Newly created, so not prohibited: the flag is a Temple Admin's to set, and nothing the
			// import creates arrives pre-flagged.
			resolved.add(new ResolvedIngredient(id, name, line.qtyValue(), line.qtyUnit(), false, true));
		}
		return resolved;
	}

	/**
	 * Refuses a recipe needing something this temple has flagged.
	 *
	 * <p>Matched on the resolved ingredient <em>row</em>, never on the letters in a name. The
	 * library holds exactly two ingredients whose names contain a prohibited word — "Onion-free
	 * chaat masala" and "Garlic-free panch phoron" — and both are sattvic. A substring check would
	 * refuse precisely the two recipes most careful about the rule.
	 *
	 * <p>No override path. An override needs a Temple Admin and a written reason, and a plus icon is
	 * not where either belongs; the temple writes its own version of the recipe instead.
	 */
	private void refuseProhibited(List<ResolvedIngredient> ingredients, MasterRecipeView master) {
		for (ResolvedIngredient ingredient : ingredients) {
			if (ingredient.prohibited()) {
				throw new ApplicationException(ErrorCode.RECIPE_NEEDS_PROHIBITED_INGREDIENT,
						Map.of("ingredient", ingredient.name(), "recipe", master.displayName()));
			}
		}
	}

	/** See {@code MasterRecipeService.pgArray} — same reason, same escaping. */
	private static String pgArray(List<String> values) {
		if (values == null || values.isEmpty()) {
			return "{}";
		}
		StringBuilder out = new StringBuilder("{");
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			out.append('"')
					.append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
					.append('"');
		}
		return out.append('}').toString();
	}
}
