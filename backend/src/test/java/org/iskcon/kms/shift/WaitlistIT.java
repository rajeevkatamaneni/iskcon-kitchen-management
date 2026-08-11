package org.iskcon.kms.shift;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/** Waitlist with auto-promotion (E6-S5): FIFO promotion on release, leave, and the concurrency case. */
@AutoConfigureMockMvc
@Import(WaitlistIT.StubVerifierConfiguration.class)
class WaitlistIT extends AbstractIntegrationTest {

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
	private UUID staffId;
	private UUID vol1;
	private UUID vol2;
	private UUID vol3;
	private UUID vol4;

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
		vol1 = volunteer("uid-vol-1", "+919876500091");
		vol2 = volunteer("uid-vol-2", "+919876500092");
		vol3 = volunteer("uid-vol-3", "+919876500093");
		vol4 = volunteer("uid-vol-4", "+919876500094");
		admin.update("UPDATE users SET contact_consent_at = now() WHERE role = 'VOLUNTEER'");
	}

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
	@DisplayName("release on a full shift promotes the first waitlister and shifts positions")
	void releasePromotesHead() throws Exception {
		UUID shift = shift(1);
		signIn("uid-vol-1");
		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", shift))).andExpect(status().isCreated());
		signIn("uid-vol-2");
		mvc.perform(authed(post("/api/v1/shifts/{id}/waitlist", shift))).andExpect(status().isCreated());
		signIn("uid-vol-3");
		mvc.perform(authed(post("/api/v1/shifts/{id}/waitlist", shift))).andExpect(status().isCreated());

		// Full shift: joining when there's room is refused earlier; here signup would be SHIFT_FULL.
		signIn("uid-vol-1");
		mvc.perform(authed(post("/api/v1/shifts/{id}/release", shift))).andExpect(status().isNoContent());

		// vol2 (first in) is promoted and notified; vol3 moves to position 1.
		assert promotions(vol2) == 1 : "vol2 should be promoted once";
		assert promotions(vol3) == 0 : "vol3 should not be promoted";
		signIn("uid-vol-2");
		mvc.perform(authed(get("/api/v1/my-shifts")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].source").value("PROMOTION"));
		signIn("uid-vol-3");
		mvc.perform(authed(get("/api/v1/my-waitlist")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].position").value(1));
	}

	@Test
	@DisplayName("joining the waitlist of a shift with room is refused")
	void joinWhenRoomRefused() throws Exception {
		UUID shift = shift(3);
		signIn("uid-vol-1");
		mvc.perform(authed(post("/api/v1/shifts/{id}/waitlist", shift)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4934"));
	}

	@Test
	@DisplayName("leaving the waitlist removes promotion eligibility")
	void leaveRemovesEligibility() throws Exception {
		UUID shift = shift(1);
		signIn("uid-vol-1");
		mvc.perform(authed(post("/api/v1/shifts/{id}/signup", shift))).andExpect(status().isCreated());
		signIn("uid-vol-2");
		mvc.perform(authed(post("/api/v1/shifts/{id}/waitlist", shift))).andExpect(status().isCreated());
		signIn("uid-vol-3");
		mvc.perform(authed(post("/api/v1/shifts/{id}/waitlist", shift))).andExpect(status().isCreated());

		// vol2 leaves, then vol1 releases → vol3 is promoted, not vol2.
		signIn("uid-vol-2");
		mvc.perform(authed(delete("/api/v1/shifts/{id}/waitlist", shift))).andExpect(status().isNoContent());
		signIn("uid-vol-1");
		mvc.perform(authed(post("/api/v1/shifts/{id}/release", shift))).andExpect(status().isNoContent());

		assert promotions(vol2) == 0 : "vol2 left, must not be promoted";
		assert promotions(vol3) == 1 : "vol3 should be promoted";
	}

	@Test
	@DisplayName("simultaneous release and a new signup on the last spot never oversubscribe or double-promote")
	void concurrentReleaseAndSignup() throws Exception {
		UUID shift = shift(1);
		// vol1 holds the only spot, vol2 waits.
		admin.update("INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?, ?)",
				tenant, shift, vol1);
		admin.update("INSERT INTO shift_waitlist (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?, ?)",
				tenant, shift, vol2);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch go = new CountDownLatch(1);
		pool.submit(() -> race(ready, go, () -> signupService.release(vol1, shift)));
		pool.submit(() -> race(ready, go, () -> signupService.signUp(vol4, shift)));
		ready.await(5, TimeUnit.SECONDS);
		go.countDown();
		pool.shutdown();
		pool.awaitTermination(10, TimeUnit.SECONDS);

		Integer active = admin.queryForObject(
				"SELECT count(*) FROM shift_signups WHERE shift_id = ? AND released_at IS NULL",
				Integer.class, shift);
		assert active == 1 : "exactly one active signup, was " + active;
		// vol2 (waitlist) is promoted; vol4 (racing signup) is not oversubscribed in.
		Integer vol2Active = admin.queryForObject(
				"SELECT count(*) FROM shift_signups WHERE shift_id = ? AND volunteer_user_id = ? AND released_at IS NULL",
				Integer.class, shift, vol2);
		assert vol2Active == 1 : "vol2 should be the one promoted into the freed spot";
	}

	// ---------------------------------------------------------------------

	private void race(CountDownLatch ready, CountDownLatch go, Runnable action) {
		TenantContext.set(tenant);
		ready.countDown();
		try {
			go.await();
			action.run();
		} catch (RuntimeException | InterruptedException ignored) {
			// A losing signup throws SHIFT_FULL; that's expected in this race.
		} finally {
			TenantContext.clear();
		}
	}

	private int promotions(UUID userId) {
		Integer n = admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND template = 'WAITLIST_PROMOTED'",
				Integer.class, userId);
		return n == null ? 0 : n;
	}

	private UUID shift(int capacity) {
		return admin.queryForObject("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by)
				VALUES (?, 'Prep', ?::date, '08:00', '12:00', ?, ?) RETURNING id
				""", UUID.class, tenant, FUTURE, capacity, staffId);
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
