package org.iskcon.kms.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Sattvic enforcement on recipes (E2-S4): a prohibited ingredient hard-blocks the save, and the
 * only escape is a Temple Admin overriding with a reason. Exercised through the full stack, so the
 * rule holds against direct API calls, not just the UI.
 */
@AutoConfigureMockMvc
@Import(SattvicEnforcementIT.StubVerifierConfiguration.class)
class SattvicEnforcementIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID category;
	private UUID rice;
	private UUID garlic;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		insertUser(templeA, "uid-admin", "admin@example.com", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-staff", "staff@example.com", "KITCHEN_STAFF");
		category = insertCategory(templeA, "Rice");
		rice = insertIngredient(templeA, "Rice", false);
		garlic = insertIngredient(templeA, "Garlic", true);
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
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
	@DisplayName("kitchen staff cannot save a recipe containing a prohibited ingredient, even via the API")
	void staffBlockedByProhibited() throws Exception {
		signIn("uid-staff");
		mvc.perform(recipeRequest(recipeWithGarlic(null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4906"));
	}

	@Test
	@DisplayName("a Temple Admin without a reason is still blocked")
	void adminWithoutReasonBlocked() throws Exception {
		signIn("uid-admin");
		mvc.perform(recipeRequest(recipeWithGarlic(null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4906"));
	}

	@Test
	@DisplayName("a Temple Admin overrides with a reason: the recipe saves, is badged, and is audited")
	void adminOverrideSucceeds() throws Exception {
		signIn("uid-admin");
		String id = create(recipeWithGarlic("Prasadam recipe traditionally uses it; approved by temple head."));

		mvc.perform(authed(get("/api/v1/recipes/{id}", id)))
				.andExpect(jsonPath("$.sattvicOverrideReason").value(
						"Prasadam recipe traditionally uses it; approved by temple head."));
		mvc.perform(authed(get("/api/v1/recipes")))
				.andExpect(jsonPath("$[?(@.name=='Garlic Rice' && @.sattvicOverridden==true)]").exists());
		assertThat(auditCount("RECIPE_SATTVIC_OVERRIDDEN")).isEqualTo(1);
	}

	@Test
	@DisplayName("removing the prohibited ingredient clears the override badge on next save")
	void removingProhibitedClearsBadge() throws Exception {
		signIn("uid-admin");
		String id = create(recipeWithGarlic("Approved for a festival."));

		// Re-save with only rice, no reason — allowed, and the badge clears.
		String update = ("{\"name\":\"Garlic Rice\",\"categoryId\":\"%s\",\"baseYieldQty\":100,"
				+ "\"baseYieldUnit\":\"SERVINGS\",\"ingredients\":"
				+ "[{\"ingredientId\":\"%s\",\"quantity\":5,\"unit\":\"KG\"}]}").formatted(category, rice);
		mvc.perform(authed(put("/api/v1/recipes/{id}", id))
				.contentType(MediaType.APPLICATION_JSON).content(update))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/recipes/{id}", id)))
				.andExpect(jsonPath("$.sattvicOverrideReason").doesNotExist());
	}

	// ---------------------------------------------------------------------

	private String recipeWithGarlic(String overrideReason) {
		String reasonField = overrideReason == null ? "" : ",\"sattvicOverrideReason\":\"" + overrideReason + "\"";
		return ("{\"name\":\"Garlic Rice\",\"categoryId\":\"%s\",\"baseYieldQty\":100,"
				+ "\"baseYieldUnit\":\"SERVINGS\",\"ingredients\":"
				+ "[{\"ingredientId\":\"%s\",\"quantity\":5,\"unit\":\"KG\"},"
				+ "{\"ingredientId\":\"%s\",\"quantity\":1,\"unit\":\"KG\"}]" + reasonField + "}")
				.formatted(category, rice, garlic);
	}

	private String create(String body) throws Exception {
		String response = mvc.perform(recipeRequest(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return response.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");
	}

	private MockHttpServletRequestBuilder recipeRequest(String json) {
		return authed(post("/api/v1/recipes")).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private int auditCount(String action) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return c == null ? 0 : c;
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private void insertUser(UUID tenantId, String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenantId, uid, email, role);
	}

	private UUID insertCategory(UUID tenantId, String name) {
		return admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name, fasting_compatible)
				VALUES (?, ?, false) RETURNING id
				""", UUID.class, tenantId, name);
	}

	private UUID insertIngredient(UUID tenantId, String name, boolean prohibited) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit, is_sattvic_prohibited)
				VALUES (?, ?, 'Test', 'KG', ?) RETURNING id
				""", UUID.class, tenantId, name, prohibited);
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
			VerifiedSubject subject = accepted.get(idToken);
			if (subject == null) {
				throw new InvalidTokenException("Unrecognised token");
			}
			return subject;
		}
	}
}
