package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Document generation and download (E2-S5), the worker-side core plus the HTTP surface — without a
 * scheduler (the async request→job path is {@link RecipeDocumentE2EIT}). Uses the default stub
 * renderer + local storage, so it is fully hermetic.
 */
@AutoConfigureMockMvc
@Import(DocumentGenerationIT.StubVerifierConfiguration.class)
class DocumentGenerationIT extends AbstractIntegrationTest {

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
		temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Admin', 'admin@govinda.example', '+919876500090', 'TEMPLE_ADMIN', 'ACTIVE')
				""", temple);
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name, fasting_compatible)
				VALUES (?, 'Rice', false) RETURNING id
				""", UUID.class, temple);
		UUID rice = insertIngredient("Rice");
		UUID dal = insertIngredient("Toor Dal");
		recipe = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit, method)
				VALUES (?, 'Khichdi', ?, 100, 'KG', 'Wash the rice.
				Cook rice and dal together until soft.') RETURNING id
				""", UUID.class, temple, category);
		insertLine(rice, "2", "KG", 0);
		insertLine(dal, "1", "KG", 1);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a pending document is rendered, stored, marked READY, and downloadable as a PDF")
	void generatesAndDownloads() throws Exception {
		UUID doc = insertPendingDocument(null);

		generateWithin(doc);

		Map<String, Object> row = admin.queryForMap("SELECT status, storage_key FROM documents WHERE id = ?", doc);
		assertThat(row.get("status")).isEqualTo("READY");
		assertThat(row.get("storage_key")).isNotNull();

		stubVerifier.accept("uid-admin");
		mvc.perform(get("/api/v1/documents/{id}", doc).header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("READY"));

		byte[] pdf = mvc.perform(get("/api/v1/documents/{id}/download", doc)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
						.header().string("Content-Type", "application/pdf"))
				.andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(pdf, 0, 8)).isEqualTo("%PDF-1.4");
	}

	@Test
	@DisplayName("a scaled document renders at the requested yield")
	void generatesScaled() {
		UUID doc = insertPendingDocument(new java.math.BigDecimal("300"));
		generateWithin(doc);
		assertThat(admin.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, doc))
				.isEqualTo("READY");
	}

	@Test
	@DisplayName("a bad request is recorded as FAILED with a reason, not left pending")
	void recordsFailure() {
		// A target yield beyond the cap makes scaling reject it; the failure lands on the row.
		UUID doc = insertPendingDocument(new java.math.BigDecimal("99999"));
		generateWithin(doc);
		Map<String, Object> row = admin.queryForMap("SELECT status, error FROM documents WHERE id = ?", doc);
		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(row.get("error")).isNotNull();
	}

	@Test
	@DisplayName("download is refused until the document is READY")
	void downloadPendingIsNotFound() throws Exception {
		UUID doc = insertPendingDocument(null);
		stubVerifier.accept("uid-admin");
		mvc.perform(get("/api/v1/documents/{id}/download", doc).header("Authorization", "Bearer valid-token"))
				.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------------

	private void generateWithin(UUID doc) {
		TenantContext.set(temple);
		try {
			generationService.generate(doc);
		} finally {
			TenantContext.clear();
		}
	}

	private UUID insertPendingDocument(java.math.BigDecimal targetYield) {
		return admin.queryForObject("""
				INSERT INTO documents (tenant_id, kind, recipe_id, language, target_yield, status)
				VALUES (?, 'RECIPE_PDF', ?, 'en', ?, 'PENDING') RETURNING id
				""", UUID.class, temple, recipe, targetYield);
	}

	private UUID insertIngredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Test', 'KG') RETURNING id
				""", UUID.class, temple, name);
	}

	private void insertLine(UUID ingredient, String qty, String unit, int order) {
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, ?::numeric, ?, ?)
				""", temple, recipe, ingredient, qty, unit, order);
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
