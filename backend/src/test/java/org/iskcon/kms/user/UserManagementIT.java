package org.iskcon.kms.user;

import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * Temple user management (E1-S12): adding people, listing them, and disabling/restoring them —
 * through the full stack, so RLS, the guards, and the audit trail are all really in play.
 */
@AutoConfigureMockMvc
@Import(UserManagementIT.StubVerifierConfiguration.class)
class UserManagementIT extends AbstractIntegrationTest {

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
		adminA = insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN", "ACTIVE");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("nothing here creates a person — the endpoint is gone, not merely hidden")
	void noWayToCreateAPerson() throws Exception {
		// Devotees register themselves (E1-S17) and staff are hired (E6-S8). An admin typing
		// somebody else's details made an account that had consented to nothing, so the road is
		// closed at the API and not only on the screen. Those rules now live in StaffEmploymentIT:
		// the duplicate email, the pending uid, and that a platform operator can never be minted.
		mvc.perform(authed(post("/api/v1/users"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"Sneaky\",\"email\":\"s@govinda.example\","
								+ "\"phone\":\"+919876500080\",\"role\":\"SUPER_ADMIN\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-4402"));
	}

	@Test
	@DisplayName("the list shows the temple's own people")
	void listsUsers() throws Exception {
		insertUser(templeA, "uid-cook", "cook@govinda.example", "KITCHEN_STAFF", "ACTIVE");
		insertUser(templeB, "uid-other", "other@krishna.example", "KITCHEN_STAFF", "ACTIVE");

		mvc.perform(authed(get("/api/v1/users")))
				.andExpect(status().isOk())
				// The admin and the cook of temple A — not temple B's user.
				.andExpect(jsonPath("$[?(@.email=='cook@govinda.example')]").exists())
				.andExpect(jsonPath("$[?(@.email=='other@krishna.example')]").doesNotExist());
	}

	@Test
	@DisplayName("the list narrows to one role, which is how the devotee register reads it")
	void listsOneRole() throws Exception {
		insertUser(templeA, "uid-cook", "cook@govinda.example", "KITCHEN_STAFF", "ACTIVE");
		insertUser(templeA, "uid-devotee", "devotee@govinda.example", "VOLUNTEER", "ACTIVE");
		insertUser(templeB, "uid-other-devotee", "other@krishna.example", "VOLUNTEER", "ACTIVE");

		mvc.perform(authed(get("/api/v1/users").param("role", "VOLUNTEER")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				// Their own temple's devotee, and neither the cook nor the other temple's devotee.
				.andExpect(jsonPath("$[0].email").value("devotee@govinda.example"));
	}

	@Test
	@DisplayName("a role that is not a role is a bad request, not a silently empty list")
	void unknownRoleRefused() throws Exception {
		mvc.perform(authed(get("/api/v1/users").param("role", "ARCHBISHOP")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("disabling then re-enabling a user flips status and records both")
	void disablesAndReenables() throws Exception {
		UUID cook = insertUser(templeA, "uid-cook2", "cook2@govinda.example", "KITCHEN_STAFF", "ACTIVE");

		mvc.perform(statusRequest(cook, "DISABLED")).andExpect(status().isNoContent());
		assertThat(statusOf(cook)).isEqualTo("DISABLED");

		mvc.perform(statusRequest(cook, "ACTIVE")).andExpect(status().isNoContent());
		assertThat(statusOf(cook)).isEqualTo("ACTIVE");

		assertThat(auditCount("USER_DISABLED")).isEqualTo(1);
		assertThat(auditCount("USER_ENABLED")).isEqualTo(1);
	}

	@Test
	@DisplayName("an admin cannot disable their own account")
	void cannotDisableSelf() throws Exception {
		mvc.perform(statusRequest(adminA, "DISABLED"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4304"));

		assertThat(statusOf(adminA)).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("an admin cannot disable a user in another temple")
	void cannotDisableAcrossTenants() throws Exception {
		UUID foreigner = insertUser(templeB, "uid-foreign", "foreign@krishna.example", "KITCHEN_STAFF", "ACTIVE");

		mvc.perform(statusRequest(foreigner, "DISABLED"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-4402"));

		assertThat(statusOf(foreigner)).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("a role without MANAGE_USERS is refused user management")
	void nonAdminForbidden() throws Exception {
		insertUser(templeA, "uid-staff", "staff@govinda.example", "KITCHEN_STAFF", "ACTIVE");
		signIn("uid-staff");

		mvc.perform(authed(get("/api/v1/users"))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder statusRequest(
			UUID id, String status) {
		return authed(patch("/api/v1/users/{id}/status", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"" + status + "\"}");
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
			org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private String statusOf(UUID id) {
		return admin.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id);
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

	private UUID insertUser(UUID tenantId, String uid, String email, String role, String status) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, ?)
				RETURNING id
				""", UUID.class, tenantId, uid, email, role, status);
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
