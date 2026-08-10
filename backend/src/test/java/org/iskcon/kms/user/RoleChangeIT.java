package org.iskcon.kms.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/**
 * The role-change endpoint and its four guards, exercised through the full request stack so that
 * RLS, the audit kernel, and the permission model are all really in play.
 *
 * <p>Driven through MockMvc rather than TestRestTemplate for one prosaic reason: the JDK's default
 * HTTP client cannot issue a PATCH, and PATCH is the honest verb for a partial update to a user.
 */
@AutoConfigureMockMvc
@Import(RoleChangeIT.StubVerifierConfiguration.class)
class RoleChangeIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;

	private UUID templeA;
	private UUID templeB;
	private UUID adminA;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();

		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");

		// The acting administrator. Every test but the permission test signs in as this person.
		adminA = insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("an admin changes a volunteer's role, and the change is recorded with before and after")
	void changesRoleAndRecordsBeforeAfter() throws Exception {
		UUID volunteer = insertUser(templeA, "uid-vol", "vol@example.com", "VOLUNTEER");

		patchRole(volunteer, "KITCHEN_STAFF").andExpect(status().isNoContent());

		assertThat(roleOf(volunteer)).isEqualTo("KITCHEN_STAFF");

		Map<String, Object> event = latestEvent("ROLE_CHANGED");
		assertThat(event.get("entity_id")).hasToString(volunteer.toString());
		assertThat(event.get("before_state").toString()).contains("VOLUNTEER");
		assertThat(event.get("after_state").toString()).contains("KITCHEN_STAFF");
	}

	@Test
	@DisplayName("guard 1: an admin cannot change their own role, and the attempt is recorded")
	void cannotChangeOwnRole() throws Exception {
		patchRole(adminA, "VOLUNTEER")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4302"));

		assertThat(roleOf(adminA)).as("the admin is still an admin").isEqualTo("TEMPLE_ADMIN");
		assertThat(rejectedCount())
				.as("a refused escalation is exactly what the log should keep")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("guard 2: no one can be promoted to super-admin through this endpoint")
	void cannotPromoteToSuperAdmin() throws Exception {
		UUID staff = insertUser(templeA, "uid-staff", "staff@example.com", "KITCHEN_STAFF");

		patchRole(staff, "SUPER_ADMIN")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4303"));

		assertThat(roleOf(staff)).isEqualTo("KITCHEN_STAFF");
		assertThat(rejectedCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("guard 3: an admin cannot change a user in another temple; RLS makes them not found")
	void cannotChangeUserInAnotherTemple() throws Exception {
		UUID foreigner = insertUser(templeB, "uid-foreign", "foreign@example.com", "VOLUNTEER");

		patchRole(foreigner, "KITCHEN_STAFF")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-4402"));

		assertThat(roleOf(foreigner)).as("the other temple's user is untouched").isEqualTo("VOLUNTEER");

		// The refused cross-tenant attempt is logged in the acting admin's own temple.
		Map<String, Object> event = latestEvent("ROLE_CHANGE_REJECTED");
		assertThat(event.get("tenant_id")).hasToString(templeA.toString());
	}

	@Test
	@DisplayName("an unrecognised role is refused as ordinary validation, not recorded as an attempt")
	void rejectsUnknownRole() throws Exception {
		UUID volunteer = insertUser(templeA, "uid-vol2", "vol2@example.com", "VOLUNTEER");

		patchRole(volunteer, "PONTIFF")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));

		assertThat(roleOf(volunteer)).isEqualTo("VOLUNTEER");
		assertThat(rejectedCount()).as("a malformed request is not a refused escalation").isZero();
	}

	@Test
	@DisplayName("a role without MANAGE_USERS is refused before the endpoint runs")
	void kitchenStaffCannotChangeRoles() throws Exception {
		UUID staff = insertUser(templeA, "uid-staff2", "staff2@example.com", "KITCHEN_STAFF");
		UUID volunteer = insertUser(templeA, "uid-vol3", "vol3@example.com", "VOLUNTEER");
		signIn("uid-staff2");

		patchRole(volunteer, "KITCHEN_STAFF")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4301"));

		assertThat(roleOf(volunteer)).isEqualTo("VOLUNTEER");
	}

	// ---------------------------------------------------------------------

	private org.springframework.test.web.servlet.ResultActions patchRole(UUID targetId, String role)
			throws Exception {
		return mvc.perform(patch("/api/v1/users/{id}/role", targetId)
				.header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"role\":\"" + role + "\"}"));
	}

	private String roleOf(UUID userId) {
		return admin.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId);
	}

	private int rejectedCount() {
		Integer count = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'ROLE_CHANGE_REJECTED'", Integer.class);
		return count == null ? 0 : count;
	}

	private Map<String, Object> latestEvent(String action) {
		return admin.queryForMap(
				"SELECT * FROM audit_events WHERE action = ? ORDER BY created_at DESC LIMIT 1", action);
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

	private UUID insertUser(UUID tenantId, String uid, String email, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919000000001', ?, 'ACTIVE')
				RETURNING id
				""", UUID.class, tenantId, uid, email, role);
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
