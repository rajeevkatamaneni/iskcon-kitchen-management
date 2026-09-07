package org.iskcon.kms.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * A cook's own schedule shows the leave they were given (T-032, D-16).
 *
 * <p>Until this, {@code GET /staff/schedule/me} answered with the template and the per-date
 * overrides and no leave at all, so somebody approved for Thursday off read Thursday's hours on the
 * one screen written for them — while their manager's grid, two clicks away, showed the leave. The
 * screen said so in muted text rather than being quietly wrong, which is the sentence this test
 * exists to make untrue.
 *
 * <p><strong>The point of the last test is that there is one answer, not two.</strong> The obvious
 * cheaper fix was to hand the browser the leave records and let it map spans onto dates. That would
 * have put a second resolution order beside {@link ScheduleResolver}'s, in another language, and the
 * two would have disagreed the first time either moved. So the grid and the cook's own list are
 * asked about the <em>same date for the same person</em> here, and compared — the half-day case
 * especially, because a reimplementation gets that one wrong: a half day leaves them in for part of
 * it, so the hours still stand and only a full day replaces them.
 *
 * <p>Everything is relative to the temple's own today, because that is what the endpoint resolves
 * from ({@link org.iskcon.kms.tenancy.TempleClock}) — a fixed date in the test would pass until it
 * fell out of the window and then fail for a reason that has nothing to do with the code.
 */
@AutoConfigureMockMvc
@Import(OwnScheduleLeaveIT.StubVerifierConfiguration.class)
class OwnScheduleLeaveIT extends AbstractIntegrationTest {

	/** The temple's zone, and so the day the window opens on. Same one the tenant row below carries. */
	private static final ZoneId TEMPLE = ZoneId.of("Asia/Kolkata");

	/** Must match {@code StaffScheduleService.LEAVE_HORIZON_DAYS}: the window the endpoint answers. */
	private static final int HORIZON_DAYS = 28;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler; // no-op enqueue, so a leave notice is recorded rather than sent

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID cookProfile;

	private LocalDate today;
	private LocalDate fullDayOff;
	private LocalDate halfDayOff;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		insertUser("uid-admin", "TEMPLE_ADMIN", "+919876500001");
		insertUser("uid-cook", "KITCHEN_STAFF", "+919876500002");
		cookProfile = hire("uid-cook", "Head Cook A");

		// Both dates hang off the next Monday, so they are weekdays, they are ahead of the temple's
		// today, they are inside the endpoint's window whatever day this test runs on, and — the part
		// that matters for the last test — they are in one week of the manager's grid.
		today = LocalDate.now(TEMPLE);
		LocalDate nextMonday = today.plusDays(1);
		while (nextMonday.getDayOfWeek() != DayOfWeek.MONDAY) {
			nextMonday = nextMonday.plusDays(1);
		}
		fullDayOff = nextMonday;
		halfDayOff = nextMonday.plusDays(2);
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM staff_leave");
		admin.execute("DELETE FROM staff_schedule_exceptions");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a cook reads the leave they were given on their own schedule, and the window it was resolved across")
	void approvedLeaveReachesTheirOwnSchedule() throws Exception {
		// Nothing yet: an empty list inside a stated window, which is the only thing that means a
		// clear fortnight. A missing window would mean nobody looked.
		signIn("uid-cook");
		mvc.perform(authed(get("/api/v1/staff/schedule/me")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.leaveFrom").value(today.toString()))
				.andExpect(jsonPath("$.leaveTo").value(today.plusDays(HORIZON_DAYS - 1L).toString()))
				.andExpect(jsonPath("$.leaveDays.length()").value(0));

		markOff(fullDayOff, fullDayOff, "TIME_OFF", false, "A family wedding");

		signIn("uid-cook");
		mvc.perform(authed(get("/api/v1/staff/schedule/me")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.leaveDays.length()").value(1))
				.andExpect(jsonPath("$.leaveDays[0].date").value(fullDayOff.toString()))
				.andExpect(jsonPath("$.leaveDays[0].leaveType").value("TIME_OFF"))
				// Printed by the server. The browser keeps no copy of the vocabulary to drift from.
				.andExpect(jsonPath("$.leaveDays[0].leaveLabel").value("Time off"))
				.andExpect(jsonPath("$.leaveDays[0].halfDayLeave").value(false))
				.andExpect(jsonPath("$.leaveDays[0].leaveId").isNotEmpty())
				// The template is untouched by leave — it is the ordinary week, not this fortnight.
				.andExpect(jsonPath("$.template.length()").value(7));
	}

	@Test
	@DisplayName("a half day comes back marked as one, and the hours for that day are still there")
	void halfDayKeepsTheHours() throws Exception {
		markOff(halfDayOff, halfDayOff, "SICK", true, "A hospital appointment");

		signIn("uid-cook");
		String body = mvc.perform(authed(get("/api/v1/staff/schedule/me")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.leaveDays.length()").value(1))
				.andExpect(jsonPath("$.leaveDays[0].date").value(halfDayOff.toString()))
				.andExpect(jsonPath("$.leaveDays[0].halfDayLeave").value(true))
				.andExpect(jsonPath("$.leaveDays[0].leaveLabel").value("Sick leave"))
				.andReturn().getResponse().getContentAsString();

		// The hours that day are still the hours they work, and they are still in the payload the
		// screen draws them from. A half day takes half of a day, not the day.
		int dayOfWeek = halfDayOff.getDayOfWeek().getValue();
		Map<String, Object> templateDay = templateDay(body, dayOfWeek);
		assertThat(templateDay.get("working")).isEqualTo(true);
		assertThat(templateDay.get("startTime")).isEqualTo("09:00:00");
		assertThat(templateDay.get("endTime")).isEqualTo("17:00:00");
	}

	@Test
	@DisplayName("the manager's grid and the cook's own schedule agree about the same date")
	void theGridAndTheOwnScheduleCannotDisagree() throws Exception {
		markOff(fullDayOff, fullDayOff, "TIME_OFF", false, "A family wedding");
		markOff(halfDayOff, halfDayOff, "SICK", true, "A hospital appointment");

		// What the manager sees on the grid.
		LocalDate weekStart = fullDayOff;
		signIn("uid-admin");
		String grid = mvc.perform(authed(get("/api/v1/staff/schedule/week")
						.param("weekStart", weekStart.toString())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.staff[0].fullName").value("Head Cook A"))
				.andReturn().getResponse().getContentAsString();

		// What the cook sees on their own screen.
		signIn("uid-cook");
		String mine = mvc.perform(authed(get("/api/v1/staff/schedule/me")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertSameDate(grid, weekStart, mine, fullDayOff);
		assertSameDate(grid, weekStart, mine, halfDayOff);

		// And the difference between the two days survives the journey: the full day replaces the
		// hours, the half day does not. This is the case a browser-side reimplementation gets wrong.
		assertThat(gridDay(grid, weekStart, fullDayOff).get("working")).isEqualTo(false);
		assertThat(gridDay(grid, weekStart, halfDayOff).get("working")).isEqualTo(true);
		assertThat(gridDay(grid, weekStart, halfDayOff).get("startTime")).isEqualTo("09:00:00");
	}

	@Test
	@DisplayName("leave still waiting to be answered is not on their schedule")
	void pendingLeaveIsNotYetAnAbsence() throws Exception {
		signIn("uid-cook");
		mvc.perform(authed(post("/api/v1/leave/mine"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"leaveType":"TIME_OFF","fromDate":"%s","toDate":"%s","halfDay":false}
								""".formatted(fullDayOff, fullDayOff)))
				.andExpect(status().isCreated());

		// The cook is expected in until somebody says otherwise. A screen that emptied itself the
		// moment they asked would tell them they had a day off nobody has granted.
		mvc.perform(authed(get("/api/v1/staff/schedule/me")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.leaveDays.length()").value(0));
	}

	// ---- helpers ----------------------------------------------------------

	/**
	 * Both endpoints' answer for one date, compared field by field.
	 *
	 * <p>The grid names the leave on the resolved day; the cook's payload names it in the leave list.
	 * Different shapes, one source, so the id, the kind, the printed label and the half-day flag must
	 * be identical — that is what "one answer, not two" means when it is written down.
	 */
	private void assertSameDate(String grid, LocalDate weekStart, String mine, LocalDate date) {
		Map<String, Object> onTheGrid = gridDay(grid, weekStart, date);
		Map<String, Object> onMine = mineDay(mine, date);

		assertThat(onMine)
				.as("the cook's own schedule says nothing about %s that the grid shows leave on", date)
				.isNotNull();
		assertThat(onMine.get("leaveId")).isEqualTo(onTheGrid.get("leaveId"));
		assertThat(onMine.get("leaveType")).isEqualTo(onTheGrid.get("leaveType"));
		assertThat(onMine.get("leaveLabel")).isEqualTo(onTheGrid.get("leaveLabel"));
		assertThat(onMine.get("halfDayLeave")).isEqualTo(onTheGrid.get("halfDayLeave"));
	}

	private Map<String, Object> gridDay(String grid, LocalDate weekStart, LocalDate date) {
		int column = (int) (date.toEpochDay() - weekStart.toEpochDay());
		return JsonPath.read(grid, "$.staff[0].days[" + column + "]");
	}

	private Map<String, Object> mineDay(String mine, LocalDate date) {
		java.util.List<Map<String, Object>> days =
				JsonPath.read(mine, "$.leaveDays[?(@.date == '" + date + "')]");
		return days.isEmpty() ? null : days.get(0);
	}

	private Map<String, Object> templateDay(String mine, int dayOfWeek) {
		java.util.List<Map<String, Object>> days =
				JsonPath.read(mine, "$.template[?(@.dayOfWeek == " + dayOfWeek + ")]");
		return days.get(0);
	}

	/** The temple marking somebody off: recorded and approved by whoever did it (B7 §6). */
	private void markOff(LocalDate from, LocalDate to, String type, boolean halfDay, String reason)
			throws Exception {
		signIn("uid-admin");
		mvc.perform(authed(post("/api/v1/leave"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"staffProfileId":"%s","leaveType":"%s","fromDate":"%s","toDate":"%s",
								 "halfDay":%s,"reason":"%s"}
								""".formatted(cookProfile, type, from, to, halfDay, reason)))
				.andExpect(status().isCreated());
	}

	/** A cook on 09:00–17:00 every day of the week, so nothing turns on which weekday it is. */
	private UUID hire(String uid, String name) {
		UUID profile = admin.queryForObject("""
				INSERT INTO staff_profiles (
					tenant_id, user_id, full_name, job_title, employment_type, date_of_joining)
				VALUES (?, (SELECT id FROM users WHERE firebase_uid = ?), ?, 'COOK', 'FULL_TIME', '2026-01-01')
				RETURNING id
				""", UUID.class, tenant, uid, name);
		for (int day = 1; day <= 7; day++) {
			admin.update("""
					INSERT INTO staff_schedule_template (
						tenant_id, staff_profile_id, day_of_week, working, start_time, end_time)
					VALUES (?, ?, ?, true, '09:00', '17:00')
					""", tenant, profile, day);
		}
		return profile;
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private void insertUser(String uid, String role, String phone) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE')
				""", tenant, uid, uid + "@example.com", phone, role);
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
