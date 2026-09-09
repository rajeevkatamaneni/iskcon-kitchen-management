package org.iskcon.kms.shift;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Attendance, and the coordinator's release (B7).
 *
 * <p>Two things that could not be done at all before this: recording that somebody did not turn up,
 * and taking a named volunteer off a roster. The second is the one with a trap in it — the
 * volunteer's own release must stay scoped to the caller's id, so the coordinator's is a separate
 * endpoint behind a separate permission, and {@link #volunteerCannotReleaseSomebodyElse} asserts a
 * volunteer cannot reach it.
 *
 * <p>The unmarked case gets a test of its own on purpose. An unmarked signup reading as an absence
 * is the failure this feature would be worth nothing with, and it is invisible in a green run of
 * everything else: {@code attended} would simply be {@code false} everywhere and every other
 * assertion here would still pass.
 */
@AutoConfigureMockMvc
@Import(ShiftAttendanceIT.StubVerifierConfiguration.class)
class ShiftAttendanceIT extends AbstractIntegrationTest {

	private static final String FUTURE = "2026-12-01";
	private static final String PAST = "2020-01-01";

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

	/** Keeps seeded signups in a known order — see {@link #signup}. */
	private int signupSequence;

	@BeforeEach
	void setUp() {
		signupSequence = 0;
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
		vol1 = volunteer("uid-vol-1", "Vol One", "+919876500091");
		vol2 = volunteer("uid-vol-2", "Vol Two", "+919876500092");
		vol3 = volunteer("uid-vol-3", "Vol Three", "+919876500093");
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

	// ---- attendance -----------------------------------------------------

	@Test
	@DisplayName("marks persist and show on the roster, present and absent alike")
	void marksPersist() throws Exception {
		UUID shift = shift("Sunday prep", PAST, 3);
		signup(shift, vol1);
		signup(shift, vol2);

		signIn("uid-staff");
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":true},{"userId":"%s","attended":false}]}
				""".formatted(vol1, vol2))).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups.length()").value(2))
				.andExpect(jsonPath("$.signups[0].fullName").value("Vol One"))
				.andExpect(jsonPath("$.signups[0].attended").value(true))
				.andExpect(jsonPath("$.signups[0].attendanceRecordedAt").exists())
				.andExpect(jsonPath("$.signups[1].fullName").value("Vol Two"))
				.andExpect(jsonPath("$.signups[1].attended").value(false))
				.andExpect(jsonPath("$.signups[1].attendanceRecordedAt").exists());
	}

	@Test
	@DisplayName("a signup nobody has marked reads as unmarked, not as absent")
	void unmarkedIsNotAbsent() throws Exception {
		UUID shift = shift("Sunday prep", PAST, 3);
		signup(shift, vol1);

		// Nothing has been recorded at all: `attended` must be null on the wire. An explicit false
		// and a missing key both read as `false` to a client, which is why this asserts the JSON
		// value is null rather than merely "not true".
		signIn("uid-staff");
		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].attended").doesNotExist())
				.andExpect(jsonPath("$.signups[0].attendanceRecordedAt").doesNotExist());

		// And after a marking that leaves somebody out: vol2 signs up later and stays unmarked while
		// vol1 carries a real answer beside them.
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":true}]}
				""".formatted(vol1))).andExpect(status().isNoContent());
		signup(shift, vol2);

		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].attended").value(true))
				.andExpect(jsonPath("$.signups[1].fullName").value("Vol Two"))
				.andExpect(jsonPath("$.signups[1].attended").doesNotExist());
	}

	@Test
	@DisplayName("marking a shift's attendance twice is refused with KMS-400139")
	void secondMarkingRefused() throws Exception {
		UUID shift = shift("Sunday prep", PAST, 3);
		signup(shift, vol1);

		signIn("uid-staff");
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":true}]}
				""".formatted(vol1))).andExpect(status().isNoContent());

		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":false}]}
				""".formatted(vol1)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400139"));

		// And the first answer stands rather than being half-overwritten by the refused one.
		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].attended").value(true));
	}

	@Test
	@DisplayName("marking somebody who is not on the roster is refused, and marks nobody")
	void markingSomebodyNotOnTheRoster() throws Exception {
		UUID shift = shift("Sunday prep", PAST, 3);
		signup(shift, vol1);

		signIn("uid-staff");
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":true},{"userId":"%s","attended":true}]}
				""".formatted(vol1, vol3)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400062"));

		// The whole call is one transaction: vol1's mark went in before vol3's was refused, and it
		// must have been rolled back with it. Otherwise the shift is now half-marked AND locked,
		// since any attendance_recorded_at at all makes the retry KMS-400139.
		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].attended").doesNotExist());
	}

	@Test
	@DisplayName("a mark with no answer in it is refused rather than read as absent")
	void markWithoutAnAnswerIsRefused() throws Exception {
		UUID shift = shift("Sunday prep", PAST, 3);
		signup(shift, vol1);

		// `attended` is a boxed Boolean with @NotNull for exactly this: a primitive would have
		// deserialised the absent key to false and recorded a no-show against somebody who came.
		signIn("uid-staff");
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s"}]}
				""".formatted(vol1))).andExpect(status().isBadRequest());

		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].attended").doesNotExist());
	}

	@Test
	@DisplayName("a volunteer cannot mark attendance")
	void volunteerCannotMarkAttendance() throws Exception {
		UUID shift = shift("Sunday prep", PAST, 3);
		signup(shift, vol1);

		signIn("uid-vol-1");
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":true}]}
				""".formatted(vol1))).andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("the database refuses half an attendance mark")
	void halfAMarkIsRefusedByTheDatabase() {
		UUID shift = shift("Sunday prep", PAST, 3);
		signup(shift, vol1);

		// V107's CHECK, exercised the way ShiftMealLinkIT exercises V95's: as raw SQL, because that
		// is the route that bypasses every guard the application makes — a migration, a fixture, a
		// support script. A time with no answer beside it would also make KMS-400139 fire on a shift
		// that carries no mark at all, since the already-recorded check reads that column.
		try {
			admin.update("UPDATE shift_signups SET attendance_recorded_at = now() WHERE shift_id = ?", shift);
			throw new AssertionError("the shift_signups_attendance_whole CHECK should have refused this");
		} catch (org.springframework.dao.DataIntegrityViolationException expected) {
			assert expected.getMessage().contains("shift_signups_attendance_whole")
					: "refused by the wrong constraint: " + expected.getMessage();
		}
	}

	// ---- the coordinator's release --------------------------------------

	@Test
	@DisplayName("a coordinator takes a named volunteer off the roster, and the waitlist head takes the spot")
	void coordinatorReleasesNamedVolunteer() throws Exception {
		UUID shift = shift("Sunday prep", FUTURE, 1);
		signup(shift, vol1);
		admin.update("""
				INSERT INTO shift_waitlist (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?, ?)
				""", tenant, shift, vol2);

		signIn("uid-staff");
		mvc.perform(authed(delete("/api/v1/shifts/{id}/signups/{userId}", shift, vol1)))
				.andExpect(status().isNoContent());

		// vol1 shows as a release; vol2 has been promoted into the freed spot.
		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups.length()").value(2))
				.andExpect(jsonPath("$.signups[0].fullName").value("Vol One"))
				.andExpect(jsonPath("$.signups[0].releasedAt").exists())
				.andExpect(jsonPath("$.signups[1].fullName").value("Vol Two"))
				.andExpect(jsonPath("$.signups[1].source").value("PROMOTION"))
				.andExpect(jsonPath("$.signups[1].releasedAt").doesNotExist())
				.andExpect(jsonPath("$.waitlist.length()").value(0));

		signIn("uid-vol-1");
		mvc.perform(authed(get("/api/v1/my-shifts"))).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("taking off somebody who is not on the shift is refused")
	void releasingSomebodyNotOnTheShift() throws Exception {
		UUID shift = shift("Sunday prep", FUTURE, 3);

		signIn("uid-staff");
		mvc.perform(authed(delete("/api/v1/shifts/{id}/signups/{userId}", shift, vol1)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400062"));
	}

	@Test
	@DisplayName("a volunteer cannot take somebody else off a roster")
	void volunteerCannotReleaseSomebodyElse() throws Exception {
		UUID shift = shift("Sunday prep", FUTURE, 3);
		signup(shift, vol1);
		signup(shift, vol2);

		// The coordinator's endpoint is gated on MANAGE_VOLUNTEER_SHIFTS, which a volunteer does not
		// hold. This is the guard that keeps the two releases apart: the volunteer's own endpoint
		// takes no "whose spot" at all, so this is the only door that could ever have been widened.
		signIn("uid-vol-1");
		mvc.perform(authed(delete("/api/v1/shifts/{id}/signups/{userId}", shift, vol2)))
				.andExpect(status().isForbidden());

		// vol2 is still on the roster — asserted as a presence, so that a widened endpoint fails
		// here rather than passing vacuously.
		signIn("uid-vol-2");
		mvc.perform(authed(get("/api/v1/my-shifts")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].title").value("Sunday prep"));
	}

	// ---------------------------------------------------------------------

	private UUID volunteer(String uid, String name, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, 'VOLUNTEER', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant, uid, name, uid + "@example.com", phone);
	}

	private UUID shift(String title, String date, int capacity) {
		return admin.queryForObject("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by)
				VALUES (?, ?, ?::date, '08:00'::time, '12:00'::time, ?, ?) RETURNING id
				""", UUID.class, tenant, title, date, capacity, staffId);
	}

	/**
	 * Seeded rather than claimed through the API, because half these shifts are in the past and the
	 * signup endpoint rightly refuses those — which is the very case attendance exists for.
	 *
	 * <p>{@code signed_up_at} is spaced a minute apart rather than left to {@code now()}: the roster
	 * orders by it, and every assertion here reads {@code signups[0]} and {@code signups[1]} by
	 * position. Two rows written a microsecond apart would order correctly almost always, which is
	 * the worst kind of test.
	 */
	private void signup(UUID shift, UUID volunteerId) {
		admin.update("""
				INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id, signed_up_at)
				VALUES (?, ?, ?, now() - interval '1 hour' + (? * interval '1 minute'))
				""", tenant, shift, volunteerId, signupSequence++);
	}

	private MockHttpServletRequestBuilder attendance(UUID shift, String body) {
		return authed(post("/api/v1/shifts/{id}/attendance", shift))
				.contentType(MediaType.APPLICATION_JSON).content(body);
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
