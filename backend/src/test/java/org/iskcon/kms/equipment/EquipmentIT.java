package org.iskcon.kms.equipment;

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
 * Equipment inventory (E3-S4) through the full stack: registration seeds a history origin, condition
 * moves only through the recorded state-change flow, SCRAPPED is terminal and hidden by default, and
 * RLS scopes it to the tenant.
 */
@AutoConfigureMockMvc
@Import(EquipmentIT.StubVerifierConfiguration.class)
class EquipmentIT extends AbstractIntegrationTest {

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
		insertUser(templeA, "uid-vol-a", "vol-a@example.com", "VOLUNTEER");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM equipment_state_changes");
		admin.execute("DELETE FROM equipment_items");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a new item defaults to GOOD and its history records the registration")
	void registrationSeedsHistory() throws Exception {
		UUID id = create("{\"name\":\"Wet Grinder\",\"category\":\"MACHINE\",\"source\":\"PURCHASED\"}");

		mvc.perform(authed(get("/api/v1/equipment/{id}", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.equipment.condition").value("GOOD"))
				.andExpect(jsonPath("$.history.length()").value(1))
				.andExpect(jsonPath("$.history[0].toCondition").value("GOOD"))
				.andExpect(jsonPath("$.history[0].fromCondition").doesNotExist());

		assertThat(auditCount("EQUIPMENT_ADDED")).isEqualTo(1);
	}

	@Test
	@DisplayName("condition moves only through the state-change flow, appending history with a reason")
	void conditionChangeIsRecorded() throws Exception {
		UUID id = create("{\"name\":\"Steam Boiler\",\"category\":\"MACHINE\"}");

		mvc.perform(changeCondition(id, "NEEDS_REPAIR", "Pressure valve leaking"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/equipment/{id}", id)))
				.andExpect(jsonPath("$.equipment.condition").value("NEEDS_REPAIR"))
				.andExpect(jsonPath("$.history.length()").value(2))
				.andExpect(jsonPath("$.history[0].toCondition").value("NEEDS_REPAIR"))
				.andExpect(jsonPath("$.history[0].fromCondition").value("GOOD"))
				.andExpect(jsonPath("$.history[0].reason").value("Pressure valve leaking"));

		assertThat(auditCount("EQUIPMENT_CONDITION_CHANGED")).isEqualTo(1);
	}

	@Test
	@DisplayName("a state change with no reason is rejected")
	void reasonIsRequired() throws Exception {
		UUID id = create("{\"name\":\"Ladle\",\"category\":\"TOOL\"}");
		mvc.perform(authed(post("/api/v1/equipment/{id}/condition", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"condition\":\"NEEDS_REPAIR\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("scrapped is terminal and the item drops out of the default list")
	void scrappedIsTerminalAndHidden() throws Exception {
		UUID id = create("{\"name\":\"Old Mixer\",\"category\":\"MACHINE\"}");
		mvc.perform(changeCondition(id, "SCRAPPED", "Motor burnt out")).andExpect(status().isNoContent());

		// Hidden by default, visible when asked for.
		mvc.perform(authed(get("/api/v1/equipment")))
				.andExpect(jsonPath("$.length()").value(0));
		mvc.perform(authed(get("/api/v1/equipment")).param("includeScrapped", "true"))
				.andExpect(jsonPath("$.length()").value(1));

		// No coming back from scrapped.
		mvc.perform(changeCondition(id, "GOOD", "changed my mind"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4912"));
	}

	@Test
	@DisplayName("changing to the same condition is rejected")
	void noOpChangeRejected() throws Exception {
		UUID id = create("{\"name\":\"Scale\",\"category\":\"TOOL\"}");
		mvc.perform(changeCondition(id, "GOOD", "already good"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("equipment can be filtered by category")
	void filterByCategory() throws Exception {
		create("{\"name\":\"Wet Grinder\",\"category\":\"MACHINE\"}");
		create("{\"name\":\"Trestle Table\",\"category\":\"FURNITURE\"}");

		mvc.perform(authed(get("/api/v1/equipment")).param("category", "FURNITURE"))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("Trestle Table"));
	}

	@Test
	@DisplayName("another temple's equipment is not found")
	void rlsScopesToTenant() throws Exception {
		UUID foreign = admin.queryForObject("""
				INSERT INTO equipment_items (tenant_id, name, category) VALUES (?, 'Foreign Oven', 'MACHINE')
				RETURNING id
				""", UUID.class, templeB);

		mvc.perform(authed(get("/api/v1/equipment/{id}", foreign)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-4402"));
	}

	@Test
	@DisplayName("a volunteer cannot see equipment")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol-a");
		mvc.perform(authed(get("/api/v1/equipment"))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private UUID create(String json) throws Exception {
		String body = mvc.perform(authed(post("/api/v1/equipment"))
						.contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private MockHttpServletRequestBuilder changeCondition(UUID id, String condition, String reason) {
		return authed(post("/api/v1/equipment/{id}/condition", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"condition\":\"" + condition + "\",\"reason\":\"" + reason + "\"}");
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
