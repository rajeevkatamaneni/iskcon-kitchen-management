package org.iskcon.kms.shift;

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
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * One-off shift broadcasts (E6-S7): reach the signed-up (optionally the waitlist), record
 * per-recipient status on the roster, are audited, and are capped per day with an admin-raisable limit.
 */
@AutoConfigureMockMvc
@Import(BroadcastIT.StubVerifierConfiguration.class)
class BroadcastIT extends AbstractIntegrationTest {

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
	private UUID vol2;
	private UUID vol3;
	private UUID shift;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Temple Admin', 'admin@example.com', '+919876500000', 'TEMPLE_ADMIN', 'ACTIVE')
				""", tenant);
		staffId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff', 'Staff', 'staff@example.com', '+919876500001', 'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		vol1 = volunteer("uid-vol-1", "+919876500091");
		vol2 = volunteer("uid-vol-2", "+919876500092");
		vol3 = volunteer("uid-vol-3", "+919876500093");
		admin.update("UPDATE users SET contact_consent_at = now() WHERE role = 'VOLUNTEER'");

		shift = admin.queryForObject("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by)
				VALUES (?, 'Sunday prep', ?::date, '08:00', '12:00', 2, ?) RETURNING id
				""", UUID.class, tenant, FUTURE, staffId);
		admin.update("INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?, ?)", tenant, shift, vol1);
		admin.update("INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?, ?)", tenant, shift, vol2);
		admin.update("INSERT INTO shift_waitlist (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?, ?)", tenant, shift, vol3);
		signIn("uid-staff");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM shift_broadcast_recipients");
		admin.execute("DELETE FROM shift_broadcasts");
		admin.execute("DELETE FROM shift_reminders");
		admin.execute("DELETE FROM shift_waitlist");
		admin.execute("DELETE FROM shift_signups");
		admin.execute("DELETE FROM shifts");
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a broadcast reaches the signed-up (not the waitlist by default), is recorded and audited")
	void broadcastReachesSignedUp() throws Exception {
		mvc.perform(broadcast("{\"message\":\"Gate B today, not A\",\"includeWaitlist\":false}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.recipients").value(2));

		for (UUID v : new UUID[] {vol1, vol2}) {
			assert broadcastsTo(v) == 1 : "signed-up volunteer should be messaged";
		}
		assert broadcastsTo(vol3) == 0 : "waitlist excluded by default";

		// Recorded on the roster with per-recipient status, and audited with the content.
		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.broadcasts.length()").value(1))
				.andExpect(jsonPath("$.broadcasts[0].message").value("Gate B today, not A"))
				.andExpect(jsonPath("$.broadcasts[0].recipients.length()").value(2));

		Integer audits = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'SHIFT_BROADCAST_SENT' AND entity_id = ? AND actor_user_id = ?",
				Integer.class, shift, staffId);
		assert audits == 1 : "the broadcast should be audited with its actor";
	}

	@Test
	@DisplayName("including the waitlist adds them to the recipients")
	void includeWaitlist() throws Exception {
		mvc.perform(broadcast("{\"message\":\"Bring aprons\",\"includeWaitlist\":true}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.recipients").value(3));
		assert broadcastsTo(vol3) == 1 : "waitlist included when asked";
	}

	@Test
	@DisplayName("the daily cap blocks further broadcasts until an admin raises it")
	void dailyCapEnforcedAndRaisable() throws Exception {
		// Lower the cap to 1 for a quick test.
		signIn("uid-admin");
		mvc.perform(authed(put("/api/v1/settings/volunteer-broadcast-limit"))
						.contentType(MediaType.APPLICATION_JSON).content("{\"limit\":1}"))
				.andExpect(status().isNoContent());

		signIn("uid-staff");
		mvc.perform(broadcast("{\"message\":\"first\"}")).andExpect(status().isCreated());
		mvc.perform(broadcast("{\"message\":\"second\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4935"));

		// Admin raises the cap; the next one goes through.
		signIn("uid-admin");
		mvc.perform(authed(put("/api/v1/settings/volunteer-broadcast-limit"))
						.contentType(MediaType.APPLICATION_JSON).content("{\"limit\":2}"))
				.andExpect(status().isNoContent());
		signIn("uid-staff");
		mvc.perform(broadcast("{\"message\":\"second, allowed now\"}")).andExpect(status().isCreated());
	}

	// ---------------------------------------------------------------------

	private int broadcastsTo(UUID userId) {
		Integer n = admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND template = 'SHIFT_BROADCAST'",
				Integer.class, userId);
		return n == null ? 0 : n;
	}

	private MockHttpServletRequestBuilder broadcast(String json) {
		return authed(post("/api/v1/shifts/{id}/broadcast", shift))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private UUID volunteer(String uid, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, 'VOLUNTEER', 'ACTIVE') RETURNING id
				""", UUID.class, tenant, uid, uid, uid + "@example.com", phone);
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
