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
	/** The morning after {@link #FUTURE}, for the shifts that run into it (T-146). */
	private static final String NEXT_DAY = "2026-12-02";

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
				.andExpect(jsonPath("$.code").value("KMS-400061"));
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
	@DisplayName("a spot held from 23:00 to 01:00 clashes with a 20:00–02:00 shift that night (T-146)")
	void midnightShiftClashesWithTheSpotAlreadyHeld() throws Exception {
		// Rajeev's first acceptance criterion, and the case the old overlap query got wrong. It
		// compared clock times within one calendar date — "s2.start_time < 02:00" — so 23:00 read as
		// later than 02:00 and the clash was never reported. A volunteer was double-booked through
		// the busiest night of the temple's year and nobody was told.
		UUID held = shift("Late offering", FUTURE, "23:00", "01:00", 5);   // 23:00 → 01:00 next day
		UUID claimed = shift("Midnight offering", FUTURE, "20:00", "02:00", 5); // 20:00 → 02:00 next day
		signIn("uid-vol-1");

		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", held)))
				.andExpect(jsonPath("$.overlapWarning").value(false));
		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", claimed)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.overlapWarning").value(true));
	}

	@Test
	@DisplayName("an overnight shift is found as the neighbour too, across the date boundary (T-146)")
	void anOvernightNeighbourIsFound() throws Exception {
		// The same defect with the two shifts swapped, and the reason the old query's
		// `s2.shift_date = ?` equality had to go rather than merely be widened. The spot already
		// held runs 22:00 on the 1st to 06:00 on the 2nd; the shift being claimed is 05:00–09:00 on
		// the 2nd. They genuinely overlap by an hour, and they are stored under different dates, so
		// a check that only ever looked at one date could not have seen it at all.
		UUID held = shift("Night watch", FUTURE, "22:00", "06:00", 5);
		UUID claimed = shift("Early breakfast", NEXT_DAY, "05:00", "09:00", 5);
		signIn("uid-vol-1");

		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", held)))
				.andExpect(jsonPath("$.overlapWarning").value(false));
		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", claimed)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.overlapWarning").value(true));
	}

	@Test
	@DisplayName("two shifts that meet at 02:00 across midnight are back to back, not a clash (T-146)")
	void backToBackAcrossMidnightDoesNotWarn() throws Exception {
		// The negative control, and it has to be a genuinely overnight one — a fixture whose shifts
		// all end on their own day proves nothing about any of this. A devotee finishing the
		// midnight offering at 02:00 and starting the early cooking at 02:00 is doing two shifts one
		// after the other, and warning them would teach them to ignore the warning. Touching ends do
		// not overlap, which is the same rule the ordinary same-day case has always followed.
		UUID held = shift("Midnight offering", FUTURE, "20:00", "02:00", 5);
		UUID claimed = shift("Early cooking", NEXT_DAY, "02:00", "06:00", 5);
		signIn("uid-vol-1");

		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", held)))
				.andExpect(jsonPath("$.overlapWarning").value(false));
		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", claimed)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.overlapWarning").value(false));
	}

	@Test
	@DisplayName("an overnight shift and a morning shift the day before do not clash (T-146)")
	void anEarlierDayDoesNotClash() throws Exception {
		// The other half of the negative control: the overnight shift as the *neighbour*, against a
		// shift that is nowhere near it. Worth stating because the new query no longer narrows by
		// date at all, so "every shift this volunteer holds" is now genuinely compared — and a
		// comparison that answered true here would warn about everything.
		UUID held = shift("Midnight offering", FUTURE, "20:00", "02:00", 5);
		UUID claimed = shift("Morning prep", FUTURE, "08:00", "12:00", 5);
		signIn("uid-vol-1");

		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", held)))
				.andExpect(jsonPath("$.overlapWarning").value(false));
		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", claimed)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.overlapWarning").value(false));
	}

	@Test
	@DisplayName("signing up twice for the same shift is refused")
	void doubleSignupRefused() throws Exception {
		UUID shift = shift("Prep", FUTURE, "08:00", "12:00", 5);
		signIn("uid-vol-1");
		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", shift))).andExpect(status().isCreated());
		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", shift)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400060"));
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
		assert "KMS-400061".equals(loserCode.get()) : "loser should get SHIFT_FULL, got " + loserCode.get();
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
