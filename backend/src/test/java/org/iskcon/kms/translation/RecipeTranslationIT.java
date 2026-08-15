package org.iskcon.kms.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.document.DocumentGenerationService;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Recipe translation (E2-S6) with the deterministic stub provider — glossary-first, cached per
 * recipe version, and fed into the translated PDF. Real Google translation is verified separately
 * (env-gated) so this stays hermetic.
 */
@AutoConfigureMockMvc
@Import(RecipeTranslationIT.StubVerifierConfiguration.class)
class RecipeTranslationIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DocumentGenerationService generationService;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID temple;
	private UUID recipe;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		stubVerifier.accept("uid-admin");
		temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Admin', 'admin@govinda.example', '+919876500092', 'TEMPLE_ADMIN', 'ACTIVE')
				""", temple);
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name, fasting_compatible)
				VALUES (?, 'Rice', false) RETURNING id
				""", UUID.class, temple);
		UUID rice = insertIngredient("Rice");
		UUID dal = insertIngredient("Toor Dal");
		recipe = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit, method)
				VALUES (?, 'Khichdi', ?, 100, 'SERVINGS', 'Wash the rice.
				Cook until soft.') RETURNING id
				""", UUID.class, temple, category);
		insertLine(rice, 0);
		insertLine(dal, 1);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM recipe_translations");
		admin.execute("DELETE FROM translation_glossary");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("translates a recipe, records provenance, and caches it")
	void translatesAndCaches() throws Exception {
		mvc.perform(authed(get("/api/v1/recipes/{id}/translations/hi", recipe)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.provider").value("stub"))
				.andExpect(jsonPath("$.name").value("[hi] Khichdi"))
				.andExpect(jsonPath("$.ingredients[0].name").value("[hi] Rice"))
				.andExpect(jsonPath("$.ingredients[0].quantity").value(2))
				.andExpect(jsonPath("$.method[0]").value("[hi] Wash the rice."));

		// Fetch again — served from cache, still exactly one stored row.
		mvc.perform(authed(get("/api/v1/recipes/{id}/translations/hi", recipe))).andExpect(status().isOk());
		assertThat(translationRows("hi")).isEqualTo(1);
	}

	@Test
	@DisplayName("a glossary override beats machine translation for a term")
	void glossaryOverrideBeatsMt() throws Exception {
		mvc.perform(authed(post("/api/v1/translation-glossary"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"language\":\"hi\",\"sourceTerm\":\"Toor Dal\",\"targetTerm\":\"तूर दाल\"}"))
				.andExpect(status().isCreated());

		mvc.perform(authed(get("/api/v1/recipes/{id}/translations/hi", recipe)))
				.andExpect(status().isOk())
				// Toor Dal uses the glossary; Rice still goes through MT.
				.andExpect(jsonPath("$.ingredients[?(@.name=='तूर दाल')]").exists())
				.andExpect(jsonPath("$.ingredients[?(@.name=='[hi] Toor Dal')]").doesNotExist())
				.andExpect(jsonPath("$.ingredients[?(@.name=='[hi] Rice')]").exists());
	}

	@Test
	@DisplayName("editing the recipe (version bump) invalidates the cached translation")
	void editInvalidatesCache() throws Exception {
		mvc.perform(authed(get("/api/v1/recipes/{id}/translations/hi", recipe))).andExpect(status().isOk());
		assertThat(translationRows("hi")).isEqualTo(1);

		// An edit bumps the recipe version; the next translation is a fresh one for the new version.
		admin.update("UPDATE recipes SET version = version + 1 WHERE id = ?", recipe);

		mvc.perform(authed(get("/api/v1/recipes/{id}/translations/hi", recipe))).andExpect(status().isOk());
		assertThat(translationRows("hi"))
				.as("the new version is translated afresh; the old cache is simply never looked up")
				.isEqualTo(2);
	}

	@Test
	@DisplayName("a translation cached by another provider is re-translated, not served")
	void otherProvidersCacheIsIgnored() throws Exception {
		// What a previous engine left behind — same recipe, same version, same language.
		admin.update("""
				INSERT INTO recipe_translations (tenant_id, recipe_id, recipe_version, language, content, provider)
				VALUES (?, ?, (SELECT version FROM recipes WHERE id = ?), 'hi', CAST(? AS jsonb), 'google')
				""", temple, recipe, recipe,
				"{\"name\":\"स्टेल\",\"categoryName\":\"स्टेल\",\"ingredientNames\":[],"
						+ "\"method\":[],\"provider\":\"google\"}");

		mvc.perform(authed(get("/api/v1/recipes/{id}/translations/hi", recipe)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.provider").value("stub"))
				.andExpect(jsonPath("$.name").value("[hi] Khichdi"));

		assertThat(translationRows("hi")).as("the stale row is replaced, not duplicated").isEqualTo(1);
		assertThat(admin.queryForObject(
				"SELECT provider FROM recipe_translations WHERE recipe_id = ? AND language = 'hi'",
				String.class, recipe)).isEqualTo("stub");
	}

	@Test
	@DisplayName("a translated PDF triggers translation during generation")
	void translatedPdfGenerates() {
		UUID doc = admin.queryForObject("""
				INSERT INTO documents (tenant_id, kind, recipe_id, language, status)
				VALUES (?, 'RECIPE_PDF', ?, 'hi', 'PENDING') RETURNING id
				""", UUID.class, temple, recipe);

		TenantContext.set(temple);
		try {
			generationService.generate(doc);
		} finally {
			TenantContext.clear();
		}

		assertThat(admin.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, doc))
				.isEqualTo("READY");
		assertThat(translationRows("hi"))
				.as("generating a Hindi card produced (and cached) the translation")
				.isEqualTo(1);
	}

	// ---------------------------------------------------------------------

	private int translationRows(String language) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM recipe_translations WHERE recipe_id = ? AND language = ?",
				Integer.class, recipe, language);
		return c == null ? 0 : c;
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
			org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private UUID insertIngredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Test', 'KG') RETURNING id
				""", UUID.class, temple, name);
	}

	private void insertLine(UUID ingredient, int order) {
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 2, 'KG', ?)
				""", temple, recipe, ingredient, order);
	}

	// ---------------------------------------------------------------------

	@TestConfiguration
	static class StubVerifierConfiguration {
		@Bean
		@Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
		}
	}

	static class StubTokenVerifier implements TokenVerifier {
		private final Map<String, VerifiedSubject> accepted = new HashMap<>();

		void accept(String uid) {
			accepted.put("valid-token", new VerifiedSubject(uid, uid + "@example.com", "+919000000000"));
		}

		void reset() {
			accepted.clear();
		}

		@Override
		public VerifiedSubject verify(String idToken) throws InvalidTokenException {
			VerifiedSubject s = accepted.get(idToken);
			if (s == null) {
				throw new InvalidTokenException("Unrecognised token");
			}
			return s;
		}
	}
}
