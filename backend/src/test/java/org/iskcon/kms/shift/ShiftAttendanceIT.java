package org.iskcon.kms.shift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
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
import org.iskcon.kms.notification.NotificationTemplate;
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
 * <p>T-106 narrows that door again, and the three tests it adds are a set rather than three
 * separate checks: a Temple Admin may correct, a Kitchen Manager may (which is what every correcting
 * test in this class now signs in as), and Kitchen Staff may not — while
 * {@link #kitchenStaffCanStillRecordAttendance} holds the other half up, because a refusal test on
 * its own would read the same against a policy that took attendance away from cooks altogether.
 *
 * <p>The unmarked case gets a test of its own on purpose. An unmarked signup reading as an absence
 * is the failure this feature would be worth nothing with, and it is invisible in a green run of
 * everything else: {@code attended} would simply be {@code false} everywhere and every other
 * assertion here would still pass.
 *
 * <p>T-080 makes the removal say why, and its block at the foot of this class is mostly about one
 * thing being in a message and one thing not. Those tests are written against the trap that comes
 * with asserting an absence: "the note is not in what was sent" passes perfectly well against a
 * product that sends the removed volunteer nothing at all, which is the defect T-080 exists to fix.
 * So {@link #removedVolunteerIsToldTheReasonAndNeverTheNote} asserts the message exists first, and
 * checks the absence three ways afterwards — the raw JSON, the exact key set, and the rendered
 * body — because a key lookup can only refute the spelling somebody thought of.
 */
@AutoConfigureMockMvc
@Import(ShiftAttendanceIT.StubVerifierConfiguration.class)
class ShiftAttendanceIT extends AbstractIntegrationTest {

	private static final String PAST = "2020-01-01";

	/**
	 * The coordinator's internal note, written to be the kind of sentence that must never reach the
	 * person it is about — which is what makes it a useful needle. Every T-080 assertion that the
	 * note did not leak greps for this exact string, so it is deliberately unlike anything the
	 * product's own copy would ever produce.
	 */
	private static final String NOTE = "She has missed three Sundays without telling anyone.";

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
	private UUID managerId;
	private UUID adminId;
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
		// T-106 splits the correcting of a mark away from the making of one, so this class now needs
		// all three kitchen roles rather than the one coordinator it used to: a cook who may mark and
		// may not correct, and the two who may do both.
		managerId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-manager', 'Manager', 'manager@example.com', '+919876500002', 'KITCHEN_MANAGER', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		adminId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Admin', 'admin@example.com', '+919876500003', 'TEMPLE_ADMIN', 'ACTIVE')
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
				// A first, never-changed mark says nothing extra (T-099): the roster's "state 2" —
				// distinct from an uncorrected row's silence being an accident of the JSON below rather
				// than a property the fixture happens not to poke at.
				.andExpect(jsonPath("$.signups[0].attendanceCorrectedAt").doesNotExist())
				.andExpect(jsonPath("$.signups[0].attendanceCorrectedByName").doesNotExist())
				.andExpect(jsonPath("$.signups[1].fullName").value("Vol Two"))
				.andExpect(jsonPath("$.signups[1].attended").value(false))
				.andExpect(jsonPath("$.signups[1].attendanceRecordedAt").exists())
				.andExpect(jsonPath("$.signups[1].attendanceCorrectedAt").doesNotExist())
				.andExpect(jsonPath("$.signups[1].attendanceCorrectedByName").doesNotExist());
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

		// The cook marked the roster; the manager corrects it (T-106). Two different signed-in users
		// across one test on purpose — that is the split, and asserting the trail below names the
		// corrector rather than the marker is what proves the two acts are told apart.
		signIn("uid-manager");
		mvc.perform(correction(shift, vol2, false)).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].attended").value(true))
				// vol1 was marked once and never corrected: state 2, and the roster says nothing extra
				// about it even though this same request just corrected somebody else's row (T-099).
				.andExpect(jsonPath("$.signups[0].attendanceCorrectedAt").doesNotExist())
				.andExpect(jsonPath("$.signups[1].fullName").value("Vol Two"))
				.andExpect(jsonPath("$.signups[1].attended").value(false))
				// The marking time is NOT rewritten by a correction. It says when this shift was
				// marked — which is what the screen prints under the table — and that is a different
				// fact from what any one row now answers.
				.andExpect(jsonPath("$.signups[1].attendanceRecordedAt").exists())
				// State 3, on the roster itself rather than only on the audit trail (T-099, finishing
				// T-079): who changed it, and when.
				.andExpect(jsonPath("$.signups[1].attendanceCorrectedAt").exists())
				.andExpect(jsonPath("$.signups[1].attendanceCorrectedByName").value("Manager"));

		// Who and when, on the temple's own readable log. Asserted through the stored row rather than
		// the audit API because the coordinator correcting a mark is a KITCHEN_MANAGER, who does not
		// hold VIEW_AUDIT_LOG — the entry is written for the Temple Admin who will read it later.
		List<Map<String, Object>> events = correctionEvents();
		assertThat(events).hasSize(1);
		assertThat((String) events.get(0).get("actor_label")).contains("Manager").contains("KITCHEN_MANAGER");
		assertThat(events.get(0).get("created_at")).isNotNull();
		assertThat((String) events.get(0).get("before_state")).contains("Vol Two").contains("true");
		assertThat((String) events.get(0).get("after_state")).contains("Vol Two").contains("false");

		// And the row carries its own provenance beside the mark it qualifies (V110).
		Map<String, Object> row = correctionColumns(shift, vol2);
		Object correctedAt = row.get("attendance_corrected_at");
		assertThat(correctedAt).isNotNull();
		assertThat(row.get("attendance_corrected_by")).isEqualTo(managerId);

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

		signIn("uid-manager");
		mvc.perform(correction(shift, vol2, true)).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[1].fullName").value("Vol Two"))
				.andExpect(jsonPath("$.signups[1].attended").value(true))
				.andExpect(jsonPath("$.signups[1].attendanceRecordedAt").exists())
				// The acceptance criterion T-099 exists to enforce: a first answer given late through
				// the correction door must NOT read as a correction on the roster, or a coordinator
				// would see "changed by X" beside a name nobody had ever marked before this.
				.andExpect(jsonPath("$.signups[1].attendanceCorrectedAt").doesNotExist())
				.andExpect(jsonPath("$.signups[1].attendanceCorrectedByName").doesNotExist());

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

		signIn("uid-manager");
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

		signIn("uid-manager");
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
		signIn("uid-manager");
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

	@Test
	@DisplayName("a temple admin can change an attendance mark")
	void templeAdminCanCorrectAMark() throws Exception {
		// T-106. The grant is two roles, so both are asserted; a policy edit that dropped either one
		// would otherwise be caught by only half of this pair.
		UUID shift = shift("Sunday prep", PAST, 3);
		signup(shift, vol1);

		signIn("uid-staff");
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":true}]}
				""".formatted(vol1))).andExpect(status().isNoContent());

		signIn("uid-admin");
		mvc.perform(correction(shift, vol1, false)).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].attended").value(false))
				.andExpect(jsonPath("$.signups[0].attendanceCorrectedByName").value("Admin"));
		assertThat(correctionColumns(shift, vol1).get("attendance_corrected_by")).isEqualTo(adminId);
	}

	@Test
	@DisplayName("kitchen staff cannot change an attendance mark, and are refused rather than failing")
	void kitchenStaffCannotCorrectAMark() throws Exception {
		// T-106, and the point of the whole task. Correcting used to ride on MANAGE_VOLUNTEER_SHIFTS,
		// which every cook holds, so every cook could revise a record about a colleague they work
		// beside. Rajeev's ruling: the person running the shift knows who turned up and must be able
		// to put a mark right, and that person is the manager, not the cook.
		UUID shift = shift("Sunday prep", PAST, 3);
		signup(shift, vol1);

		signIn("uid-staff");
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":true}]}
				""".formatted(vol1))).andExpect(status().isNoContent());

		// 403 specifically, not merely "not 204": a permission that does not exist, or an annotation
		// naming a permission nobody holds, would fail some other way — and a 500 reaching a
		// coordinator mid-shift is a different defect wearing this test's green.
		mvc.perform(correction(shift, vol1, false))
				.andExpect(status().isForbidden());

		// And nothing moved on the way to the refusal.
		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].attended").value(true))
				.andExpect(jsonPath("$.signups[0].attendanceCorrectedAt").doesNotExist());
		assertThat(correctionEvents()).isEmpty();
	}

	@Test
	@DisplayName("kitchen staff can still record attendance in the first place")
	void kitchenStaffCanStillRecordAttendance() throws Exception {
		// The assertion that makes T-106 a split rather than a narrowing of attendance. The refusal
		// above passes just as happily against a policy that took the whole of MANAGE_VOLUNTEER_SHIFTS
		// off kitchen staff — which would leave the cook who ran the shift unable to say who came to
		// it, and is the failure this change must not be.
		UUID shift = shift("Sunday prep", PAST, 3);
		signup(shift, vol1);
		signup(shift, vol2);

		signIn("uid-staff");
		mvc.perform(attendance(shift, """
				{"marks":[{"userId":"%s","attended":true},{"userId":"%s","attended":false}]}
				""".formatted(vol1, vol2))).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].attended").value(true))
				.andExpect(jsonPath("$.signups[0].attendanceRecordedAt").exists())
				.andExpect(jsonPath("$.signups[1].attended").value(false));
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
		mvc.perform(removal(shift, vol1, "ROTA_CHANGED", NOTE)).andExpect(status().isNoContent());

		// vol1 shows as a release, now with the reason and the note on it; vol2 has been promoted
		// into the freed spot.
		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups.length()").value(2))
				.andExpect(jsonPath("$.signups[0].fullName").value("Vol One"))
				.andExpect(jsonPath("$.signups[0].releasedAt").exists())
				.andExpect(jsonPath("$.signups[0].releasedReason").value("ROTA_CHANGED"))
				.andExpect(jsonPath("$.signups[0].releasedNote").value(NOTE))
				.andExpect(jsonPath("$.signups[1].fullName").value("Vol Two"))
				.andExpect(jsonPath("$.signups[1].source").value("PROMOTION"))
				.andExpect(jsonPath("$.signups[1].releasedAt").doesNotExist())
				// The promoted volunteer's own row carries neither, which is the other half of what
				// makes the pair readable: a non-null reason means the temple removed this person.
				.andExpect(jsonPath("$.signups[1].releasedReason").doesNotExist())
				.andExpect(jsonPath("$.signups[1].releasedNote").doesNotExist())
				.andExpect(jsonPath("$.waitlist.length()").value(0));

		signIn("uid-vol-1");
		mvc.perform(authed(get("/api/v1/my-shifts"))).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("taking off somebody who is not on the shift is refused")
	void releasingSomebodyNotOnTheShift() throws Exception {
		UUID shift = shift("Sunday prep", tomorrowAtTheTemple(), 3);

		signIn("uid-staff");
		mvc.perform(removal(shift, vol1, "ROTA_CHANGED", NOTE))
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
		// T-080 moved this door from DELETE to POST and it has to stay shut on the new verb too — a
		// permission annotation lost in a rewrite is exactly the kind of thing that goes unnoticed.
		signIn("uid-vol-1");
		mvc.perform(removal(shift, vol2, "ROTA_CHANGED", NOTE)).andExpect(status().isForbidden());

		// vol2 is still on the roster — asserted as a presence, so that a widened endpoint fails
		// here rather than passing vacuously.
		signIn("uid-vol-2");
		mvc.perform(authed(get("/api/v1/my-shifts")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].title").value("Sunday prep"));
	}

	// ---- T-080: a removal has to say why, twice over --------------------

	@Test
	@DisplayName("a removal with no reason, or no note, is refused with the field named")
	void bothFieldsAreRequired() throws Exception {
		UUID shift = shift("Sunday prep", tomorrowAtTheTemple(), 3);
		signup(shift, vol1);
		signIn("uid-staff");

		// No reason.
		mvc.perform(removalBody(shift, vol1, "{\"internalNote\":\"%s\"}".formatted(NOTE)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("reason")));

		// No note. This is the half a coordinator in a hurry would drop, and the half Rajeev's ruling
		// turns on: "a coordinator who has to write a private note is a coordinator who has thought
		// about it."
		mvc.perform(removalBody(shift, vol1, "{\"reason\":\"ROTA_CHANGED\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("internalNote")));

		// A note of spaces is not a note. @NotBlank rather than @NotEmpty, and this is the assertion
		// that says which was used.
		mvc.perform(removal(shift, vol1, "ROTA_CHANGED", "   "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("internalNote")));

		// And after three refusals the volunteer is still on the shift. Without this, all three pass
		// against a controller that refused the body and removed them anyway.
		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].releasedAt").doesNotExist());
	}

	@Test
	@DisplayName("the removed volunteer is told, and told the reason and not the note")
	void removedVolunteerIsToldTheReasonAndNeverTheNote() throws Exception {
		UUID shift = shift("Sunday prep", tomorrowAtTheTemple(), 3);
		signup(shift, vol1);

		signIn("uid-staff");
		mvc.perform(removal(shift, vol1, "ROTA_CHANGED", NOTE)).andExpect(status().isNoContent());

		// The message exists at all. This assertion is load-bearing and comes first on purpose: every
		// "the note is not in it" check below passes vacuously against a product that sends the
		// removed volunteer nothing — which is precisely the defect T-080 exists to fix, so a test
		// suite that could not tell the fix from the defect would be worth nothing here.
		List<Map<String, Object>> sent = admin.queryForList("""
				SELECT params::text AS params FROM notifications
				WHERE recipient_user_id = ? AND template = 'REMOVED_FROM_SHIFT'
				""", vol1);
		assertThat(sent).hasSize(1);

		// The reason, in the words the volunteer actually reads.
		assertThat((String) sent.get(0).get("params")).contains("the rota changed");

		// And the note is not in the row anywhere — not under `internalNote`, not under `note`, not
		// smuggled into some other parameter. Asserted on the raw JSON text rather than on a named
		// key, because a key test only refutes the spelling somebody thought of.
		assertThat((String) sent.get(0).get("params")).doesNotContain(NOTE);

		// The same absence stated the other way round, since the two fail differently: the parameter
		// map is exactly these six keys and there is no seventh for a note to arrive in. A map with
		// an extra key fails here even if its value happened to be empty, which is the case a
		// contains-check cannot see.
		assertThat(notificationParamKeys(vol1, "REMOVED_FROM_SHIFT"))
				.containsExactlyInAnyOrder("title", "date", "time", "location", "temple", "reason");

		// Rendering it is the last place the note could appear, and the only one a devotee sees.
		Map<String, Object> params = Map.of("title", "Sunday prep", "date", "6 December",
				"time", "08:00–12:00", "location", "Main kitchen", "temple", "Bengaluru Temple",
				"reason", "the rota changed");
		String body = NotificationTemplate.REMOVED_FROM_SHIFT.render(params).body();
		assertThat(body).contains("Sunday prep").contains("the rota changed").doesNotContain(NOTE);
	}

	@Test
	@DisplayName("the waitlist promotion message is untouched by any of this")
	void waitlistPromotionMessageIsUnchanged() throws Exception {
		UUID shift = shift("Sunday prep", tomorrowAtTheTemple(), 1);
		signup(shift, vol1);
		admin.update("""
				INSERT INTO shift_waitlist (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?, ?)
				""", tenant, shift, vol2);

		signIn("uid-staff");
		mvc.perform(removal(shift, vol1, "NO_LONGER_NEEDED", NOTE)).andExpect(status().isNoContent());

		// Rajeev's acceptance says this message does not change, so the test says what it is rather
		// than only that one was sent: same template, same six-parameter map as every other shift
		// message, and no reason and no note added to it. The promoted volunteer is not a party to
		// why somebody else came off the roster.
		assertThat(notificationParamKeys(vol2, "WAITLIST_PROMOTED"))
				.containsExactlyInAnyOrder("title", "date", "time", "location", "temple");
		List<Map<String, Object>> promoted = admin.queryForList("""
				SELECT params::text AS params FROM notifications
				WHERE recipient_user_id = ? AND template = 'WAITLIST_PROMOTED'
				""", vol2);
		assertThat(promoted).hasSize(1);
		assertThat((String) promoted.get(0).get("params")).doesNotContain(NOTE);
	}

	@Test
	@DisplayName("a volunteer's own release still records no reason and no note")
	void ownReleaseCarriesNoReason() throws Exception {
		UUID shift = shift("Sunday prep", tomorrowAtTheTemple(), 3);
		signup(shift, vol1);

		// The other half of what makes the pair meaningful. A devotee stepping off their own shift is
		// not asked to justify it, so both columns stay null — and that is how the roster tells this
		// act from a coordinator's removal.
		signIn("uid-vol-1");
		mvc.perform(authed(post("/api/v1/shifts/{id}/release", shift))).andExpect(status().isNoContent());

		signIn("uid-staff");
		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].releasedAt").exists())
				.andExpect(jsonPath("$.signups[0].releasedReason").doesNotExist())
				.andExpect(jsonPath("$.signups[0].releasedNote").doesNotExist());

		// And nothing was sent to them about it: a removal message to somebody who removed themselves
		// would be the new feature leaking into the old path.
		Integer told = admin.queryForObject("""
				SELECT count(*) FROM notifications
				WHERE recipient_user_id = ? AND template = 'REMOVED_FROM_SHIFT'
				""", Integer.class, vol1);
		assertThat(told).isZero();
	}

	@Test
	@DisplayName("a reason outside the four the product offers is refused")
	void unknownReasonIsRefused() throws Exception {
		UUID shift = shift("Sunday prep", tomorrowAtTheTemple(), 3);
		signup(shift, vol1);

		// The vocabulary is closed because this half is SENT. Anything a caller could invent here
		// would reach a devotee's phone through the message template unreviewed.
		signIn("uid-staff");
		mvc.perform(removal(shift, vol1, "SHE_KEPT_MISSING_SHIFTS", NOTE))
				.andExpect(status().isBadRequest());

		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].releasedAt").doesNotExist());
	}

	@Test
	@DisplayName("the old DELETE door is gone, not left open beside the new one")
	void theDeleteVerbIsWithdrawn() throws Exception {
		UUID shift = shift("Sunday prep", tomorrowAtTheTemple(), 3);
		signup(shift, vol1);

		// T-080 moved the removal to a POST because two required fields cannot travel on a DELETE.
		// Leaving the DELETE in place "for compatibility" would leave a door through which a
		// volunteer is still removed with no reason and no note, which is the whole feature's width.
		// KMS-400030 and a 404 rather than a 405, which is this project's own settled answer to the
		// right address with the wrong verb (GlobalExceptionHandler#handleWrongMethod): what the
		// caller asked for is not there. Asserted on the code and not only the status, so that a
		// route quietly reappearing under some other handler cannot pass this.
		signIn("uid-staff");
		mvc.perform(authed(delete("/api/v1/shifts/{id}/signups/{userId}", shift, vol1)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400030"));

		mvc.perform(authed(get("/api/v1/shifts/{id}/roster", shift)))
				.andExpect(jsonPath("$.signups[0].releasedAt").doesNotExist());
	}

	@Test
	@DisplayName("the internal note is on the temple's audit trail, with the volunteer named")
	void removalIsOnTheAuditTrailWithTheNote() throws Exception {
		UUID shift = shift("Sunday prep", tomorrowAtTheTemple(), 3);
		signup(shift, vol1);

		signIn("uid-staff");
		mvc.perform(removal(shift, vol1, "ROTA_CHANGED", NOTE)).andExpect(status().isNoContent());

		List<Map<String, Object>> events = admin.queryForList("""
				SELECT actor_label, reason, before_state::text AS before_state,
					   after_state::text AS after_state
				FROM audit_events WHERE action = 'VOLUNTEER_REMOVED_FROM_SHIFT'
				""");
		assertThat(events).hasSize(1);
		// The note is the point of the entry. It is the one durable account of why, and the only
		// place a temple admin can read what the coordinator actually thought.
		assertThat((String) events.get(0).get("reason")).isEqualTo(NOTE);
		// Legible without resolving anybody's id, like ATTENDANCE_CORRECTED beside it.
		assertThat((String) events.get(0).get("before_state")).contains("Vol One");
		assertThat((String) events.get(0).get("after_state"))
				.contains("Vol One").contains("ROTA_CHANGED");
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

	/** A coordinator's removal, with the two things T-080 makes it say (POST, not DELETE). */
	private MockHttpServletRequestBuilder removal(UUID shift, UUID userId, String reason, String note) {
		return removalBody(shift, userId,
				"{\"reason\":\"%s\",\"internalNote\":\"%s\"}".formatted(reason, note));
	}

	/** The same, with the body spelled out — for the payloads that leave a field out. */
	private MockHttpServletRequestBuilder removalBody(UUID shift, UUID userId, String body) {
		return authed(post("/api/v1/shifts/{id}/signups/{userId}/release", shift, userId))
				.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	/**
	 * The parameter names a queued message actually carries.
	 *
	 * <p>Read as a key set rather than by looking one key up, because what these tests assert is an
	 * <em>absence</em> — that the coordinator's internal note is not in the message — and a lookup
	 * can only refute the spelling the test author happened to think of. {@code jsonb_object_keys}
	 * expands every key there is, so a note arriving under any name at all fails here.
	 */
	private List<String> notificationParamKeys(UUID recipient, String template) {
		return admin.queryForList("""
				SELECT jsonb_object_keys(params) AS key FROM notifications
				WHERE recipient_user_id = ? AND template = ?
				""", String.class, recipient, template);
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
