package org.iskcon.kms.shift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.Permission;
import org.iskcon.kms.auth.RolePermissions;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.iskcon.kms.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * "Past shifts" on My Shifts (T-429): a volunteer can see what she has already done, with whether
 * she was recorded as having turned up, and can see nobody else's.
 *
 * <p><strong>Every absence here is asserted beside a presence in the same response</strong>, the
 * rule {@link MyReleasedShiftsIT} sets out: "another volunteer's history is not listed", "a
 * released signup is not listed" and "today's shift is not listed" would every one of them pass
 * against an endpoint that returns nothing at all, so each of those tests also sets up a row that
 * <em>must</em> appear and pins the exact count.
 *
 * <p><strong>The unmarked case is pinned on the wire, not in the mapper.</strong> {@code attended}
 * is three-valued and the third value is the load-bearing one (V107): a shift nobody marked must
 * never read as an absence. {@code ResultSet.getBoolean} returns {@code false} for SQL NULL, so the
 * difference between right and wrong here is one method call in the row mapper and both versions
 * compile. The test therefore reads the JSON and asserts the token is {@code null} rather than
 * asserting on a deserialised Java value, which would have hidden the collapse.
 *
 * <p><strong>The shape is pinned whole.</strong> {@code released_note} is the coordinator's
 * internal sentence (V124) and must not reach a volunteer. This list is of shifts she was still on,
 * so a removal cannot be in it by construction — but "cannot by construction" is what every leak
 * was before it happened, so the exact JSON field set of a real row is asserted, the way
 * MyReleasedShiftsIT does it: a check for one field not being there cannot see a field that has
 * been renamed.
 *
 * <p>Attendance is marked through the coordinator's real endpoint rather than written in with
 * fixtures, so the rows this reads are the rows the application actually stores. The shifts
 * themselves are inserted directly, because a shift in the past is a state the create endpoint
 * correctly refuses to produce and it is the only state this list is about.
 *
 * <p>Declares the same single {@code @MockBean} as {@link ReleaseIT} and {@link MyReleasedShiftsIT}
 * and no stub configuration of its own: both are part of Spring's context cache key, so this class
 * shares their application context instead of starting another one.
 */
@AutoConfigureMockMvc
class MyPastShiftsIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper json;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;
	private UUID vol1;
	private UUID vol2;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staffId = user(tenant, "uid-staff", "Staff", "+919876500001", "KITCHEN_STAFF");
		vol1 = user(tenant, "uid-vol-1", "Vol One", "+919876500091", "VOLUNTEER");
		vol2 = user(tenant, "uid-vol-2", "Vol Two", "+919876500092", "VOLUNTEER");
		admin.update("UPDATE users SET contact_consent_at = now() WHERE role = 'VOLUNTEER'");
	}

	@AfterEach
	void tearDown() {
		// First, because audit_events.actor_user_id is ON DELETE RESTRICT (T-080).
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM shift_waitlist");
		admin.execute("DELETE FROM shift_signups");
		admin.execute("DELETE FROM shifts");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("past shifts come back newest first, with the attendance mark and nothing of the coordinator's")
	void pastShiftsNewestFirstWithTheMark() throws Exception {
		UUID tenDaysAgo = pastShift("Janmastami feast service", 10, "10:00", "14:30");
		UUID yesterday = pastShift("Vegetable cutting", 1, "06:30", "09:30");
		UUID threeDaysAgo = pastShift("Morning prasadam service", 3, "10:00", "14:00");
		signup(tenDaysAgo, vol1);
		signup(yesterday, vol1);
		signup(threeDaysAgo, vol1);
		mark(tenDaysAgo, vol1, true);
		mark(yesterday, vol1, false);
		mark(threeDaysAgo, vol1, true);

		signIn("uid-vol-1");
		String body = mvc.perform(authed(get("/api/v1/my-shifts/past")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				// Newest first: yesterday, then three days ago, then ten.
				.andExpect(jsonPath("$[0].title").value("Vegetable cutting"))
				.andExpect(jsonPath("$[1].title").value("Morning prasadam service"))
				.andExpect(jsonPath("$[2].title").value("Janmastami feast service"))
				.andExpect(jsonPath("$[0].attended").value(false))
				.andExpect(jsonPath("$[1].attended").value(true))
				.andExpect(jsonPath("$[2].attended").value(true))
				.andExpect(jsonPath("$[0].location").value("Main kitchen"))
				.andExpect(jsonPath("$[0].source").value("SIGNUP"))
				.andReturn().getResponse().getContentAsString();

		// The exact field set, which is the wire contract with MyPastShiftView in api.ts. Pinned
		// whole rather than as "no releasedNote", because the coordinator's internal note leaking
		// under any other name would sail past a check for that one name.
		JsonNode row = json.readTree(body).get(0);
		Set<String> fields = new HashSet<>();
		row.fieldNames().forEachRemaining(fields::add);
		assertThat(fields).containsExactlyInAnyOrder(
				"signupId", "shiftId", "title", "shiftDate", "startTime", "endTime", "location",
				"source", "signedUpAt", "attended", "attendanceRecordedAt");
		assertThat(body).doesNotContain("releasedNote", "releasedReason", "released_note");

		// attendanceRecordedAt is the mark's own moment, read back from the row.
		OffsetDateTime stored = admin.queryForObject(
				"SELECT attendance_recorded_at FROM shift_signups WHERE shift_id = ? AND volunteer_user_id = ?",
				OffsetDateTime.class, yesterday, vol1);
		assertThat(Instant.parse(row.get("attendanceRecordedAt").asText())).isEqualTo(stored.toInstant());
	}

	@Test
	@DisplayName("a shift nobody marked comes back as null, not as false, beside one marked absent")
	void unmarkedIsNullAndNeverFalse() throws Exception {
		UUID marked = pastShift("Marked absent", 2, "06:00", "09:00");
		UUID unmarked = pastShift("Nobody ever marked this one", 4, "06:00", "09:00");
		signup(marked, vol1);
		signup(unmarked, vol1);
		mark(marked, vol1, false);

		signIn("uid-vol-1");
		String body = mvc.perform(authed(get("/api/v1/my-shifts/past")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andReturn().getResponse().getContentAsString();

		JsonNode rows = json.readTree(body);
		JsonNode absent = rows.get(0);
		JsonNode never = rows.get(1);
		assertThat(absent.get("title").asText()).isEqualTo("Marked absent");
		assertThat(never.get("title").asText()).isEqualTo("Nobody ever marked this one");

		// The whole point of T-429's row mapper, asserted on the JSON token rather than on a
		// deserialised value: getBoolean would have made this `false` and the two rows identical,
		// and the screen would then have accused her of missing a shift nobody has spoken about.
		assertThat(never.get("attended").isNull())
				.as("an unmarked shift must arrive as null, never as false")
				.isTrue();
		assertThat(never.get("attendanceRecordedAt").isNull()).isTrue();
		// And the row beside it is a real `false`, so this test cannot pass by everything being null.
		assertThat(absent.get("attended").isNull()).isFalse();
		assertThat(absent.get("attended").asBoolean()).isFalse();
		assertThat(absent.get("attendanceRecordedAt").isNull()).isFalse();
	}

	@Test
	@DisplayName("today and the future stay on the upcoming list, and the two lists never share a shift")
	void todayAndTheFutureAreNotHistory() throws Exception {
		UUID yesterday = pastShift("Already served", 1, "06:00", "09:00");
		UUID today = pastShift("Serving today", 0, "10:00", "14:00");
		UUID tomorrow = pastShift("Serving tomorrow", -1, "10:00", "14:00");
		signup(yesterday, vol1);
		signup(today, vol1);
		signup(tomorrow, vol1);

		signIn("uid-vol-1");
		mvc.perform(authed(get("/api/v1/my-shifts/past")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].shiftId").value(yesterday.toString()));

		// The other half of the partition: the same three signups read forwards. Both predicates are
		// the database's CURRENT_DATE and exact negations of each other, so nothing may appear twice
		// and nothing may fall between them.
		mvc.perform(authed(get("/api/v1/my-shifts")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].shiftId").value(today.toString()))
				.andExpect(jsonPath("$[1].shiftId").value(tomorrow.toString()));
	}

	@Test
	@DisplayName("a released signup and a cancelled shift are not history, beside a served shift that is")
	void releasedAndCancelledAreNotListed() throws Exception {
		UUID served = pastShift("Served it", 2, "06:00", "09:00");
		UUID steppedOff = pastShift("Stepped off before it happened", 3, "06:00", "09:00");
		UUID calledOff = pastShift("Temple called it off", 4, "06:00", "09:00");
		signup(served, vol1);
		signup(steppedOff, vol1);
		signup(calledOff, vol1);

		// Both states are written directly: /shifts/{id}/release and /shifts/{id}/cancel both refuse
		// a shift that has already started, and rightly — nobody steps off or calls off yesterday.
		// The rows they leave behind are what this list has to be right about.
		admin.update("UPDATE shift_signups SET released_at = now() WHERE shift_id = ?", steppedOff);
		admin.update("UPDATE shifts SET status = 'CANCELLED', cancelled_at = now(), cancel_reason = 'Rain' WHERE id = ?", calledOff);

		signIn("uid-vol-1");
		mvc.perform(authed(get("/api/v1/my-shifts/past")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].title").value("Served it"));
	}

	@Test
	@DisplayName("another volunteer's history is never listed, and each volunteer sees their own")
	void anotherVolunteersHistoryIsNotListed() throws Exception {
		UUID mine = pastShift("My past shift", 2, "06:00", "09:00");
		UUID theirs = pastShift("Their past shift", 2, "14:00", "18:00");
		signup(mine, vol1);
		signup(theirs, vol2);
		mark(mine, vol1, true);
		mark(theirs, vol2, false);

		signIn("uid-vol-1");
		mvc.perform(authed(get("/api/v1/my-shifts/past")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].title").value("My past shift"))
				.andExpect(jsonPath("$[0].attended").value(true));

		signIn("uid-vol-2");
		mvc.perform(authed(get("/api/v1/my-shifts/past")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].title").value("Their past shift"))
				.andExpect(jsonPath("$[0].attended").value(false));
	}

	@Test
	@DisplayName("the list is capped at fifty, and the fifty are the newest")
	void cappedAtFiftyNewestFirst() throws Exception {
		// Fifty-five days of service, one a day, so the cap has five rows to leave out and the five
		// it leaves out are the oldest. Without a bound this is the scan a volunteer of three years
		// would pay for on every page load.
		for (int daysAgo = 1; daysAgo <= 55; daysAgo++) {
			signup(pastShift("Service %d days ago".formatted(daysAgo), daysAgo, "06:00", "09:00"), vol1);
		}

		signIn("uid-vol-1");
		String body = mvc.perform(authed(get("/api/v1/my-shifts/past")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(SignupService.PAST_SHIFTS_LIMIT))
				.andExpect(jsonPath("$[0].title").value("Service 1 days ago"))
				.andExpect(jsonPath("$[49].title").value("Service 50 days ago"))
				.andReturn().getResponse().getContentAsString();

		// The five it dropped are the oldest five and not five at random.
		assertThat(body).doesNotContain("Service 51 days ago", "Service 55 days ago");
	}

	@Test
	@DisplayName("every role without VIEW_OWN_SHIFTS is refused, and every role with it is let in")
	void refusedWithoutViewOwnShifts() throws Exception {
		// The roles are read from the policy rather than written down here, so this does not drift
		// when RolePermissions changes; it asserts only that the endpoint obeys whatever the policy
		// says. It must find at least one refused role, or its refusal half proves nothing.
		List<User.Role> refused = java.util.Arrays.stream(User.Role.values())
				.filter(r -> !RolePermissions.has(r, Permission.VIEW_OWN_SHIFTS)).toList();
		assertThat(refused).as("some role must lack VIEW_OWN_SHIFTS, or nothing here is refused").isNotEmpty();

		for (User.Role role : User.Role.values()) {
			String uid = "uid-role-" + role.name().toLowerCase();
			// The operator belongs to no temple (V1's users row with a NULL tenant), like every
			// SUPER_ADMIN fixture in this suite.
			UUID roleTenant = role == User.Role.SUPER_ADMIN ? null : tenant;
			String phone = "+91980000%04d".formatted(role.ordinal());
			admin.update("""
					INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status, contact_consent_at)
					VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', now())
					""", roleTenant, uid, role.name(), uid + "@example.com", phone, role.name());

			signIn(uid);
			if (refused.contains(role)) {
				mvc.perform(authed(get("/api/v1/my-shifts/past")))
						.andExpect(status().isForbidden());
			} else {
				// 200 and an empty list, not a refusal and not an error. Kitchen Staff hold
				// VIEW_OWN_SHIFTS and have never signed up for seva in their lives; "you have served
				// nothing" is the true answer for them, and the screen must be able to render it.
				mvc.perform(authed(get("/api/v1/my-shifts/past")))
						.andExpect(status().isOk())
						.andExpect(jsonPath("$.length()").value(0));
			}
		}
	}

	// ---------------------------------------------------------------------

	private UUID user(UUID tenantId, String uid, String name, String phone, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
				RETURNING id
				""", UUID.class, tenantId, uid, name, uid + "@example.com", phone, role);
	}

	/**
	 * A shift {@code daysAgo} days before today, inserted directly. The create endpoint refuses a
	 * shift in the past, correctly, and a shift in the past is the only thing this list is about;
	 * {@code current_date} is the database's, which is the same clock the query under test reads.
	 * A negative {@code daysAgo} puts it in the future, for the partition test.
	 */
	private UUID pastShift(String title, int daysAgo, String start, String end) {
		return admin.queryForObject("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, location, created_by)
				VALUES (?, ?, current_date - ?::int, ?::time, ?::time, 3, 'Main kitchen', ?) RETURNING id
				""", UUID.class, tenant, title, daysAgo, start, end, staffId);
	}

	private void signup(UUID shift, UUID volunteer) {
		admin.update("INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?, ?)",
				tenant, shift, volunteer);
	}

	/** The coordinator's marking through its real endpoint, once per shift as the server requires. */
	private void mark(UUID shift, UUID volunteer, boolean attended) throws Exception {
		signIn("uid-staff");
		mvc.perform(authed(post("/api/v1/shifts/{id}/attendance", shift))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"marks\":[{\"userId\":\"%s\",\"attended\":%s}]}".formatted(volunteer, attended)))
				.andExpect(status().isNoContent());
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}
}
