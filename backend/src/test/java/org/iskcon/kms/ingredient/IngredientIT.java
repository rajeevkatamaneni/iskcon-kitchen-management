package org.iskcon.kms.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Ingredient master (E2-S1) through the full stack: RLS scoping, the descriptive-vs-compliance
 * permission split, duplicate handling, and typeahead all really in play.
 */
@AutoConfigureMockMvc
@Import(IngredientIT.StubVerifierConfiguration.class)
class IngredientIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("an ingredient is created and listed, and the creation is audited")
	void createsAndLists() throws Exception {
		mvc.perform(createRequest("{\"name\":\"Toor Dal\",\"category\":\"Pulses\",\"unit\":\"KG\","
						+ "\"sattvicProhibited\":false,\"aliases\":[\"Arhar Dal\"]}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").exists());

		mvc.perform(authed(get("/api/v1/ingredients")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name=='Toor Dal')]").exists());

		assertThat(auditCount("INGREDIENT_ADDED")).isEqualTo(1);
	}

	@Test
	@DisplayName("kitchen staff may add an ordinary ingredient but not mark one prohibited")
	void staffCannotMarkProhibited() throws Exception {
		signIn("uid-staff-a");

		mvc.perform(createRequest("{\"name\":\"Rice\",\"category\":\"Grains\",\"unit\":\"KG\"}"))
				.andExpect(status().isCreated());

		mvc.perform(createRequest("{\"name\":\"Leek\",\"category\":\"Vegetables\",\"unit\":\"KG\","
						+ "\"sattvicProhibited\":true}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4301"));
	}

	@Test
	@DisplayName("only a Temple Admin can change the sattvic-prohibited flag, and it is audited")
	void onlyAdminChangesSattvicFlag() throws Exception {
		UUID rice = createIngredientAsAdmin("Rice", "Grains", "KG");

		// Kitchen staff is refused the flag endpoint outright.
		signIn("uid-staff-a");
		mvc.perform(sattvicRequest(rice, true)).andExpect(status().isForbidden());

		// The admin can, and it lands on the audit trail.
		signIn("uid-admin-a");
		mvc.perform(sattvicRequest(rice, true)).andExpect(status().isNoContent());
		assertThat(admin.queryForObject(
				"SELECT is_sattvic_prohibited FROM ingredients WHERE id = ?", Boolean.class, rice)).isTrue();
		assertThat(auditCount("INGREDIENT_SATTVIC_FLAG_CHANGED")).isEqualTo(1);
	}

	@Test
	@DisplayName("a duplicate name at the same temple is refused with a quotable code")
	void refusesDuplicateName() throws Exception {
		createIngredientAsAdmin("Rice", "Grains", "KG");

		mvc.perform(createRequest("{\"name\":\"rice\",\"category\":\"Grains\",\"unit\":\"KG\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4903"));
	}

	@Test
	@DisplayName("typeahead matches on name and on alias")
	void typeaheadMatchesNameAndAlias() throws Exception {
		mvc.perform(createRequest("{\"name\":\"Toor Dal\",\"category\":\"Pulses\",\"unit\":\"KG\","
				+ "\"aliases\":[\"Arhar Dal\"]}")).andExpect(status().isCreated());

		mvc.perform(authed(get("/api/v1/ingredients/search").param("q", "toor")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name=='Toor Dal')]").exists());

		mvc.perform(authed(get("/api/v1/ingredients/search").param("q", "arhar")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name=='Toor Dal')]").exists());
	}

	@Test
	@DisplayName("an ingredient in another temple is simply not found")
	void rlsScopesToTenant() throws Exception {
		UUID other = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Payasam Base', 'Sweets', 'L') RETURNING id
				""", UUID.class, templeB);

		mvc.perform(authed(get("/api/v1/ingredients/{id}", other)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-4402"));
	}

	@Test
	@DisplayName("an ingredient with stock history is refused deletion in plain language, not an internal error")
	void refusesDeleteWhenHeldByAnAppendOnlyLedger() throws Exception {
		UUID rice = createIngredientAsAdmin("Rice", "Grains", "KG");
		// A stock movement is append-only: the application role has no DELETE on it, so the FK's own
		// check used to fail as "permission denied" and surfaced as KMS-5001.
		UUID actor = admin.queryForObject(
				"SELECT id FROM users WHERE firebase_uid = 'uid-admin-a'", UUID.class);
		admin.update("""
				INSERT INTO stock_movements
					(tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, gen_random_uuid(), 5, 'KG', 'DONATION_IN_KIND', ?)
				""", templeA, rice, actor);

		mvc.perform(authed(delete("/api/v1/ingredients/{id}", rice)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4904"));

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE id = ?", Integer.class, rice)).isEqualTo(1);
	}

	@Test
	@DisplayName("an unused ingredient is deleted, and the deletion is audited")
	void deletesWhenNothingHoldsIt() throws Exception {
		UUID leek = createIngredientAsAdmin("Sorrel", "Vegetables", "KG");

		mvc.perform(authed(delete("/api/v1/ingredients/{id}", leek)))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE id = ?", Integer.class, leek)).isZero();
		assertThat(auditCount("INGREDIENT_DELETED")).isEqualTo(1);
	}

	// ---------------------------------------------------------------------

	private UUID createIngredientAsAdmin(String name, String category, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, ?, ?) RETURNING id
				""", UUID.class, templeA, name, category, unit);
	}

	private MockHttpServletRequestBuilder createRequest(String json) {
		return authed(post("/api/v1/ingredients")).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder sattvicRequest(UUID id, boolean prohibited) {
		return authed(patch("/api/v1/ingredients/{id}/sattvic-flag", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"sattvicProhibited\":" + prohibited + "}");
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
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private void insertUser(UUID tenantId, String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenantId, uid, email, role);
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
