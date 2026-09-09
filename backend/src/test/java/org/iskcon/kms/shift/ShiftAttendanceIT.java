package org.iskcon.kms.shift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
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
 * <p>T-079 adds the other half of it: a mark can be changed. The blanket marking is still once per
 * shift — {@link #secondMarkingRefused} is unchanged and is meant to stay that way — and what is new
 * is a separate, per-volunteer door beside it. Two of the tests below are about that door not being
 * a hole in something else: {@link #correctingAShiftThatHasNotRunIsRefused} carries T-085's guard
 * through it, and {@link #volunteerCannotCorrectAMark} keeps the act the coordinator's.
 *
 * <p>The unmarked case gets a test of its own on purpose. An unmarked signup reading as an absence
 * is the failure this feature would be worth nothing with, and it is invisible in a green run of
 * everything else: {@code attended} would simply be {@code false} everywhere and every other
 * assertion here would still pass.
 */
@AutoConfigureMockMvc
@Import(ShiftAttendanceIT.StubVerifierConfiguration.class)
class ShiftAttendanceIT extends AbstractIntegrationTest {

	private static final String PAST = "2020-01-01";

	/** The zone the tenant below is seeded with — the one TempleClock resolves for these requests. */
	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

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
		// Before the users, and deliberately: audit_events.actor_user_id is ON DELETE RESTRICT
		// (V3:71), because a trail that can lose its actor is not a trail. A correction files one of
		// these (T-079), so without this line the first correcting test leaves every later test in
		// the class failing on a foreign key in the teardown of the one before it.
		admin.execute("DELETE FROM audit_events");
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

	@Test
	@DisplayName("marking a shift that has not run yet is refused with KMS-400144")
	void markingAShiftThatHasNotRunIsRefused() throws Exception {
		// T-085. The route in is not a malformed call, it is the screen as built: every tick starts
		// ticked, so a coordinator opening tomorrow's roster and pressing Save marked the whole crew
		// as having come to a shift that had not happened. Marking is once per shift, so that was
		// permanent — the retry is KMS-400139 forever after.
		//
		// Tomorrow is computed rather than written down, because a shift date fixed in a constant is
		// in the future only until the day it is not, and this test would then assert the opposite of
		// what it says.
		UUID shift = shift("Sunday prep", tomorrowAtTheTemple(), 3);
		signup(shift, vol1);
		signup(shift, vol2);

		signIn("uid-staff");
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":true},{"userId":"%s","attended":true}]}
				""".formatted(vol1, vol2)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400144"));

		// And nobody was marked on the way to the refusal. Asserted through the roster rather than
		// only through the status: a guard placed after the UPDATE loop would return the same 409
		// while having already written the marks that lock the shift out of every later correction.
		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].attended").doesNotExist())
				.andExpect(jsonPath("$.signups[0].attendanceRecordedAt").doesNotExist())
				.andExpect(jsonPath("$.signups[1].attended").doesNotExist());
	}

	@Test
	@DisplayName("a shift that started an hour ago can be marked")
	void markingAShiftThatStartedAnHourAgoIsAllowed() throws Exception {
		// The positive control for the test above, and near the boundary rather than in 2020: a guard
		// written the wrong way round, or against the wrong end of the shift, refuses this while
		// {@link #marksPersist} on a 2020 shift stays green.
		ZonedDateTime startedAnHourAgo = ZonedDateTime.now(TEMPLE_ZONE).minusHours(1);
		LocalTime start = startedAnHourAgo.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
		LocalTime end;
		if (start.isAfter(LocalTime.of(22, 0))) {
			// The table's shifts_time_window CHECK wants end_time > start_time, so a window that
			// would cross midnight is pulled back to 22:00 instead. Earlier than an hour ago is
			// still started, which is all this test is about, and it keeps the run at 00:30 honest.
			start = LocalTime.of(22, 0);
			end = LocalTime.of(23, 59);
		} else {
			end = start.plusHours(1).plusMinutes(30);
		}
		UUID shift = shift("Evening prasadam", startedAnHourAgo.toLocalDate().toString(),
				start.toString(), end.toString(), 3);
		signup(shift, vol1);

		signIn("uid-staff");
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":true}]}
				""".formatted(vol1))).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].attended").value(true))
				.andExpect(jsonPath("$.signups[0].attendanceRecordedAt").exists());
	}

	// ---- correcting a mark (T-079) --------------------------------------

	@Test
	@DisplayName("a wrong mark is changed, and the trail says who changed it, when, and from what")
	void aWrongMarkIsChanged() throws Exception {
		UUID shift = shift("Sunday prep", PAST, 3);
		signup(shift, vol1);
		signup(shift, vol2);

		signIn("uid-staff");
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":true},{"userId":"%s","attended":true}]}
				""".formatted(vol1, vol2))).andExpect(status().isNoContent());

		mvc.perform(correction(shift, vol2, false)).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].attended").value(true))
				.andExpect(jsonPath("$.signups[1].fullName").value("Vol Two"))
				.andExpect(jsonPath("$.signups[1].attended").value(false))
				// The marking time is NOT rewritten by a correction. It says when this shift was
				// marked — which is what the screen prints under the table — and that is a different
				// fact from what any one row now answers.
				.andExpect(jsonPath("$.signups[1].attendanceRecordedAt").exists());

		// Who and when, on the temple's own readable log. Asserted through the stored row rather than
		// the audit API because the coordinator marking a shift is KITCHEN_STAFF, who does not hold
		// VIEW_AUDIT_LOG — the entry is written for the Temple Admin who will read it later.
		List<Map<String, Object>> events = correctionEvents();
		assertThat(events).hasSize(1);
		assertThat((String) events.get(0).get("actor_label")).contains("Staff").contains("KITCHEN_STAFF");
		assertThat(events.get(0).get("created_at")).isNotNull();
		assertThat((String) events.get(0).get("before_state")).contains("Vol Two").contains("true");
		assertThat((String) events.get(0).get("after_state")).contains("Vol Two").contains("false");

		// And the row carries its own provenance beside the mark it qualifies (V110).
		Map<String, Object> row = correctionColumns(shift, vol2);
		Object correctedAt = row.get("attendance_corrected_at");
		assertThat(correctedAt).isNotNull();
		assertThat(row.get("attendance_corrected_by")).isEqualTo(staffId);

		// Asking again for the answer the row already gives writes nothing: no second entry on the
		// log, and no second correction time. A double press on a slow connection is not a correction
		// and must not read as one to anybody counting them.
		mvc.perform(correction(shift, vol2, false)).andExpect(status().isNoContent());
		assertThat(correctionEvents()).hasSize(1);
		assertThat(correctionColumns(shift, vol2).get("attendance_corrected_at")).isEqualTo(correctedAt);
	}

	@Test
	@DisplayName("somebody a partial marking left out can still be marked, and that is not a correction")
	void aPartialMarkingIsRecoverable() throws Exception {
		// The second route to an unrecoverable state, which T-085 deliberately left for this task:
		// a `marks` list naming only some of the roster leaves the rest unmarked, and any mark at all
		// then makes every later blanket marking KMS-400139 — so before this existed, the omitted
		// could never be marked by anybody, ever.
		UUID shift = shift("Sunday prep", PAST, 3);
		signup(shift, vol1);
		signup(shift, vol2);

		signIn("uid-staff");
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":true}]}
				""".formatted(vol1))).andExpect(status().isNoContent());
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":true}]}
				""".formatted(vol2)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400139"));

		mvc.perform(correction(shift, vol2, true)).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[1].fullName").value("Vol Two"))
				.andExpect(jsonPath("$.signups[1].attended").value(true))
				.andExpect(jsonPath("$.signups[1].attendanceRecordedAt").exists());

		// A first answer is not a correction of one. The act is on the log — somebody said something
		// about somebody, after the fact, and that is worth recording — but the row's correction
		// columns stay null, so counting corrected marks never counts late first marks among them.
		Map<String, Object> row = correctionColumns(shift, vol2);
		assertThat(row.get("attendance_corrected_at")).isNull();
		assertThat(row.get("attendance_corrected_by")).isNull();
		assertThat(correctionEvents()).hasSize(1);
		assertThat((String) correctionEvents().get(0).get("before_state")).contains("not marked");
	}

	@Test
	@DisplayName("correcting a mark on a shift that has not run is refused with KMS-400144")
	void correctingAShiftThatHasNotRunIsRefused() throws Exception {
		// T-085's guard, carried through the new door. Without this the correction path would be a
		// hole in it exactly one call wide: a coordinator could not blanket-mark tomorrow's roster,
		// but could set every name on it one at a time.
		UUID shift = shift("Sunday prep", tomorrowAtTheTemple(), 3);
		signup(shift, vol1);

		signIn("uid-staff");
		mvc.perform(correction(shift, vol1, true))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400144"));

		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].attended").doesNotExist())
				.andExpect(jsonPath("$.signups[0].attendanceRecordedAt").doesNotExist());
	}

	@Test
	@DisplayName("correcting a mark for somebody who is not on the roster is refused")
	void correctingSomebodyNotOnTheRoster() throws Exception {
		UUID shift = shift("Sunday prep", PAST, 3);
		signup(shift, vol1);

		signIn("uid-staff");
		mvc.perform(correction(shift, vol3, true))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400062"));
	}

	@Test
	@DisplayName("a correction with no answer in it is refused rather than read as absent")
	void correctionWithoutAnAnswerIsRefused() throws Exception {
		UUID shift = shift("Sunday prep", PAST, 3);
		signup(shift, vol1);

		signIn("uid-staff");
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":true}]}
				""".formatted(vol1))).andExpect(status().isNoContent());

		// The boxed-and-required Boolean, for the reason the marking payload uses one: a primitive
		// would deserialise `{}` to false, and here that would overwrite an answer somebody had
		// already considered with an accusation nobody made.
		mvc.perform(correction(shift, vol1, "{}")).andExpect(status().isBadRequest());

		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].attended").value(true));
	}

	@Test
	@DisplayName("a volunteer cannot change an attendance mark")
	void volunteerCannotCorrectAMark() throws Exception {
		UUID shift = shift("Sunday prep", PAST, 3);
		signup(shift, vol1);

		signIn("uid-staff");
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":false}]}
				""".formatted(vol1))).andExpect(status().isNoContent());

		// Marking is the coordinator's act and so is revising it. A volunteer able to reach this
		// would be able to overturn a no-show recorded against them, which is the one correction
		// nobody would ever hear about.
		signIn("uid-vol-1");
		mvc.perform(correction(shift, vol1, true)).andExpect(status().isForbidden());

		signIn("uid-staff");
		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].attended").value(false));
	}

	// ---- the coordinator's release --------------------------------------

	@Test
	@DisplayName("a coordinator takes a named volunteer off the roster, and the waitlist head takes the spot")
	void coordinatorReleasesNamedVolunteer() throws Exception {
		UUID shift = shift("Sunday prep", tomorrowAtTheTemple(), 1);
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
		UUID shift = shift("Sunday prep", tomorrowAtTheTemple(), 3);

		signIn("uid-staff");
		mvc.perform(authed(delete("/api/v1/shifts/{id}/signups/{userId}", shift, vol1)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400062"));
	}

	@Test
	@DisplayName("a volunteer cannot take somebody else off a roster")
	void volunteerCannotReleaseSomebodyElse() throws Exception {
		UUID shift = shift("Sunday prep", tomorrowAtTheTemple(), 3);
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
		return shift(title, date, "08:00", "12:00", capacity);
	}

	/** A shift with its window spelled out, for the tests that turn on when it started. */
	private UUID shift(String title, String date, String startTime, String endTime, int capacity) {
		return admin.queryForObject("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by)
				VALUES (?, ?, ?::date, ?::time, ?::time, ?, ?) RETURNING id
				""", UUID.class, tenant, title, date, startTime, endTime, capacity, staffId);
	}

	/**
	 * Tomorrow where the food is cooked, which is the clock the guard reads — not the JVM's. A test
	 * machine set to UTC is five and a half hours behind the tenant seeded above, so "tomorrow"
	 * worked out locally is the same day at the temple for part of every evening.
	 */
	private String tomorrowAtTheTemple() {
		return ZonedDateTime.now(TEMPLE_ZONE).toLocalDate().plusDays(1).toString();
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

	/** One person's mark, set to the answer given (T-079). */
	private MockHttpServletRequestBuilder correction(UUID shift, UUID userId, boolean attended) {
		return correction(shift, userId, "{\"attended\":%s}".formatted(attended));
	}

	/** The same, with the body spelled out — for the payload that leaves the answer out. */
	private MockHttpServletRequestBuilder correction(UUID shift, UUID userId, String body) {
		return authed(put("/api/v1/shifts/{id}/attendance/{userId}", shift, userId))
				.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	/**
	 * The correction entries on the temple's log, oldest first.
	 *
	 * <p>The JSONB columns are cast to text so the assertions can read them without a JSON parser:
	 * what they are checking is that the volunteer's name and both answers are in the entry at all,
	 * which is what makes it legible to somebody reading the log a year later.
	 */
	private List<Map<String, Object>> correctionEvents() {
		return admin.queryForList("""
				SELECT actor_label, created_at, before_state::text AS before_state,
					   after_state::text AS after_state
				FROM audit_events WHERE action = 'ATTENDANCE_CORRECTED' ORDER BY created_at
				""");
	}

	/** The row's own record of having been changed (V110). */
	private Map<String, Object> correctionColumns(UUID shift, UUID volunteerId) {
		return admin.queryForMap("""
				SELECT attendance_corrected_at, attendance_corrected_by FROM shift_signups
				WHERE shift_id = ? AND volunteer_user_id = ?
				""", shift, volunteerId);
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
