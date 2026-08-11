package org.iskcon.kms.shift;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.tenancy.TenantContext;
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
 * Scheduled shift reminders (E6-S6): the idempotent send, skipping a released spot, roster delivery
 * status, and scheduling only the offsets whose fire time is still ahead.
 */
@AutoConfigureMockMvc
@Import(ShiftReminderIT.StubVerifierConfiguration.class)
class ShiftReminderIT extends AbstractIntegrationTest {

	private static final String FUTURE = "2026-12-01";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ShiftReminderService reminderService;

	@Autowired
	private ShiftReminderScheduler reminderScheduler;

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
		signIn("uid-staff");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM shift_reminders");
		admin.execute("DELETE FROM shift_waitlist");
		admin.execute("DELETE FROM shift_signups");
		admin.execute("DELETE FROM shifts");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("sending a reminder is idempotent and shows on the roster with delivery status")
	void reminderSendsOnceAndShowsOnRoster() throws Exception {
		UUID shift = shift("[1440]");
		UUID signup = signup(shift, vol1);

		within(() -> {
			reminderService.sendReminder(signup, 1440);
			reminderService.sendReminder(signup, 1440); // a retry must not send twice
		});

		Integer notifications = admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND template = 'VOLUNTEER_SHIFT_REMINDER'",
				Integer.class, vol1);
		assert notifications == 1 : "reminder should send exactly once, was " + notifications;
		Integer rows = admin.queryForObject(
				"SELECT count(*) FROM shift_reminders WHERE signup_id = ?", Integer.class, signup);
		assert rows == 1 : "one reminder record, was " + rows;

		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].reminders.length()").value(1))
				.andExpect(jsonPath("$.signups[0].reminders[0].offsetMinutes").value(1440))
				.andExpect(jsonPath("$.signups[0].reminders[0].status").value("PENDING"));
	}

	@Test
	@DisplayName("a reminder for a released spot is not sent")
	void releasedSpotGetsNoReminder() {
		UUID shift = shift("[1440]");
		UUID signup = signup(shift, vol1);
		admin.update("UPDATE shift_signups SET released_at = now() WHERE id = ?", signup);

		within(() -> reminderService.sendReminder(signup, 1440));

		Integer notifications = admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE recipient_user_id = ?", Integer.class, vol1);
		assert notifications == 0 : "no reminder for a released spot, was " + notifications;
	}

	@Test
	@DisplayName("scheduling skips offsets whose fire time has already passed")
	void schedulesOnlyFutureOffsets() {
		// 1440 min (24h) before a December shift is still ahead; a ~190-year offset fires in the past.
		UUID shift = shift("[1440, 100000000]");
		UUID signup = signup(shift, vol1);

		List<Integer> scheduled = within(() -> reminderScheduler.scheduleForSignup(signup));

		assert scheduled.equals(List.of(1440)) : "only the future offset should schedule, was " + scheduled;
	}

	// ---------------------------------------------------------------------

	private void within(Runnable action) {
		TenantContext.set(tenant);
		try {
			action.run();
		} finally {
			TenantContext.clear();
		}
	}

	private <T> T within(java.util.function.Supplier<T> action) {
		TenantContext.set(tenant);
		try {
			return action.get();
		} finally {
			TenantContext.clear();
		}
	}

	private UUID shift(String offsetsJson) {
		return admin.queryForObject("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity,
					reminder_offsets_minutes, created_by)
				VALUES (?, 'Prep', ?::date, '08:00', '12:00', 5, ?::jsonb, ?) RETURNING id
				""", UUID.class, tenant, FUTURE, offsetsJson, staffId);
	}

	private UUID signup(UUID shift, UUID volunteer) {
		return admin.queryForObject("""
				INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?, ?) RETURNING id
				""", UUID.class, tenant, shift, volunteer);
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
