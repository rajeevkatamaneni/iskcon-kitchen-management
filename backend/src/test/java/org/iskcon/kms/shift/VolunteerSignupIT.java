package org.iskcon.kms.shift;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.error.ApplicationException;
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
 * Volunteer signup (E6-S3): atomic capacity claim (no oversubscription under concurrency), signup
 * confirmation, the My Shifts view, the overlap warning, and caller state on available shifts.
 */
@AutoConfigureMockMvc
@Import(VolunteerSignupIT.StubVerifierConfiguration.class)
class VolunteerSignupIT extends AbstractIntegrationTest {

	private static final String FUTURE = "2026-12-01";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SignupService signupService;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID vol1;
	private UUID vol2;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		UUID staff = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff', 'Staff', 'staff@example.com', '+919876500001', 'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		vol1 = volunteer("uid-vol-1", "Vol One", "+919876500091");
		vol2 = volunteer("uid-vol-2", "Vol Two", "+919876500092");
		admin.update("UPDATE users SET contact_consent_at = now() WHERE role = 'VOLUNTEER'");
		this.staffId = staff;
	}

	private UUID staffId;

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM shift_waitlist");
		admin.execute("DELETE FROM shift_signups");
		admin.execute("DELETE FROM shifts");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("signing up confirms, appears in My Shifts, and reflects on available shifts")
	void signupConfirmsAndListsl() throws Exception {
		UUID shift = shift("Sunday prep", FUTURE, "08:00", "12:00", 5);
		signIn("uid-vol-1");

		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", shift)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.overlapWarning").value(false));

		Integer confirmations = admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND template = 'SHIFT_SIGNUP_CONFIRMED'",
				Integer.class, vol1);
		assert confirmations == 1 : "a confirmation should be queued";

		mvc.perform(authed(get("/api/v1/my-shifts")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].title").value("Sunday prep"));

		mvc.perform(authed(get("/api/v1/available-shifts")))
				.andExpect(jsonPath("$[0].callerState").value("SIGNED_UP"))
				.andExpect(jsonPath("$[0].signedUpCount").value(1));
	}

	@Test
	@DisplayName("a full shift reads as FULL to another volunteer and refuses a direct signup")
	void fullShiftRefusesSignup() throws Exception {
		UUID shift = shift("Tiny shift", FUTURE, "08:00", "12:00", 1);
		admin.update("INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?, ?)",
				tenant, shift, vol2);

		signIn("uid-vol-1");
		mvc.perform(authed(get("/api/v1/available-shifts")))
				.andExpect(jsonPath("$[0].callerState").value("FULL"));
		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", shift)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4931"));
	}

	@Test
	@DisplayName("signing up for a time-overlapping shift warns but is allowed")
	void overlapWarns() throws Exception {
		UUID a = shift("Morning", FUTURE, "08:00", "12:00", 5);
		UUID b = shift("Late morning", FUTURE, "10:00", "14:00", 5);
		signIn("uid-vol-1");

		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", a)))
				.andExpect(jsonPath("$.overlapWarning").value(false));
		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", b)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.overlapWarning").value(true));
	}

	@Test
	@DisplayName("signing up twice for the same shift is refused")
	void doubleSignupRefused() throws Exception {
		UUID shift = shift("Prep", FUTURE, "08:00", "12:00", 5);
		signIn("uid-vol-1");
		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", shift))).andExpect(status().isCreated());
		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", shift)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4930"));
	}

	@Test
	@DisplayName("two simultaneous signups for the last spot: exactly one wins")
	void concurrentSignupOnlyOneWins() throws Exception {
		UUID shift = shift("Last spot", FUTURE, "08:00", "12:00", 1);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger successes = new AtomicInteger();
		AtomicReference<String> loserCode = new AtomicReference<>();

		for (UUID vol : new UUID[] {vol1, vol2}) {
			pool.submit(() -> {
				TenantContext.set(tenant);
				ready.countDown();
				try {
					go.await();
					signupService.signUp(vol, shift);
					successes.incrementAndGet();
				} catch (ApplicationException e) {
					loserCode.set(e.errorCode().reference());
				} catch (Exception ignored) {
					// counted as neither success nor the expected loser
				} finally {
					TenantContext.clear();
				}
			});
		}
		ready.await(5, TimeUnit.SECONDS);
		go.countDown();
		pool.shutdown();
		pool.awaitTermination(10, TimeUnit.SECONDS);

		assert successes.get() == 1 : "exactly one signup should win, got " + successes.get();
		assert "KMS-4931".equals(loserCode.get()) : "loser should get SHIFT_FULL, got " + loserCode.get();
		Integer active = admin.queryForObject(
				"SELECT count(*) FROM shift_signups WHERE shift_id = ? AND released_at IS NULL",
				Integer.class, shift);
		assert active == 1 : "capacity must not be oversubscribed, was " + active;
	}

	@Test
	@DisplayName("a staff member cannot sign up as a volunteer")
	void staffCannotSignup() throws Exception {
		UUID shift = shift("Prep", FUTURE, "08:00", "12:00", 5);
		signIn("uid-staff");
		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", shift))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private UUID shift(String title, String date, String start, String end, int capacity) {
		return admin.queryForObject("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by)
				VALUES (?, ?, ?::date, ?::time, ?::time, ?, ?) RETURNING id
				""", UUID.class, tenant, title, date, start, end, capacity, staffId);
	}

	private UUID volunteer(String uid, String name, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, 'VOLUNTEER', 'ACTIVE') RETURNING id
				""", UUID.class, tenant, uid, name, uid + "@example.com", phone);
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
