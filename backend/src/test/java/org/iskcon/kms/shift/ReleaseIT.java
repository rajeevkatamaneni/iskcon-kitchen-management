package org.iskcon.kms.shift;

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
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Signup release (E6-S4): a released spot frees capacity, leaves My Shifts, shows on the poster's
 * roster with its release time, and is refused once the shift has started.
 */
@AutoConfigureMockMvc
@Import(ReleaseIT.StubVerifierConfiguration.class)
class ReleaseIT extends AbstractIntegrationTest {

	private static final String FUTURE = "2026-12-01";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;
	private UUID vol1;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staffId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff', 'Staff', 'staff@example.com', '+919876500001', 'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		vol1 = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-vol-1', 'Vol One', 'vol1@example.com', '+919876500091', 'VOLUNTEER', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		admin.update("UPDATE users SET contact_consent_at = now() WHERE role = 'VOLUNTEER'");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM shift_waitlist");
		admin.execute("DELETE FROM shift_signups");
		admin.execute("DELETE FROM shifts");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("releasing frees the spot, leaves My Shifts, and shows on the roster")
	void releaseFreesSpot() throws Exception {
		UUID shift = shift("Prep", FUTURE, "08:00", "12:00", 3);
		signIn("uid-vol-1");
		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", shift))).andExpect(status().isCreated());

		mvc.perform(authed(post("/api/v1/shifts/{id}/release", shift))).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/my-shifts"))).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(authed(get("/api/v1/available-shifts")))
				.andExpect(jsonPath("$[0].signedUpCount").value(0))
				.andExpect(jsonPath("$[0].callerState").value("AVAILABLE"));

		// Roster (poster view) shows the released signup with a release time.
		signIn("uid-staff");
		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups.length()").value(1))
				.andExpect(jsonPath("$.signups[0].releasedAt").exists());
	}

	@Test
	@DisplayName("releasing a shift you're not on is refused")
	void releaseWithoutSignup() throws Exception {
		UUID shift = shift("Prep", FUTURE, "08:00", "12:00", 3);
		signIn("uid-vol-1");
		mvc.perform(authed(post("/api/v1/shifts/{id}/release", shift)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4932"));
	}

	@Test
	@DisplayName("releasing after the shift has started is refused")
	void releaseAfterStart() throws Exception {
		UUID shift = shift("Old shift", "2020-01-01", "08:00", "12:00", 3);
		admin.update("INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?, ?)",
				tenant, shift, vol1);
		signIn("uid-vol-1");
		mvc.perform(authed(post("/api/v1/shifts/{id}/release", shift)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4929"));
	}

	// ---------------------------------------------------------------------

	private UUID shift(String title, String date, String start, String end, int capacity) {
		return admin.queryForObject("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by)
				VALUES (?, ?, ?::date, ?::time, ?::time, ?, ?) RETURNING id
				""", UUID.class, tenant, title, date, start, end, capacity, staffId);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
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
