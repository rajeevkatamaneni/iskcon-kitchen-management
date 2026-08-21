package org.iskcon.kms.staff;

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
 * A half-day person stops counting, and stays on the grid (item 19).
 *
 * <p>This reverses a deliberate decision, so it is worth writing down what it reverses.
 * {@code ScheduleResolver} used to keep a half-day person in the head count on the grounds that half
 * a cook is more use to a count than none. Two things say otherwise, and they hold independently.
 * An extra pair of hands does not hurt; being short when you need more does. And the record does not
 * say <em>which</em> half — {@code half_day} is a boolean with no time beside it — so counting them
 * claims a certainty the record does not hold. They may be gone by noon, and lunch is the meal that
 * needed them.
 *
 * <p>What did <em>not</em> change is the grid. Somebody looking for who is around today must still
 * find the name, still marked half day. Only the count moved.
 */
@AutoConfigureMockMvc
@Import(HalfDayLeaveIT.StubVerifierConfiguration.class)
class HalfDayLeaveIT extends AbstractIntegrationTest {

	private static final String WEEK_START = "2026-08-31";
	private static final String WEDNESDAY = "2026-09-02";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler; // no-op enqueue, so a decision notice is recorded rather than sent

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID cookProfile;

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
		insertUser("uid-prep", "KITCHEN_STAFF", "+919876500003");

		cookProfile = hire("uid-cook", "Head Cook A");
		hire("uid-prep", "Prep B");
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
	@DisplayName("a half day drops the person out of the count and leaves them on the grid")
	void halfDayCountsAsZeroButStillShows() throws Exception {
		// Both cooks are in on Wednesday to begin with.
		mvc.perform(authed(get("/api/v1/staff/schedule/week").param("weekStart", WEEK_START)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.counts[2].staffIn").value(2));

		mvc.perform(authed(post("/api/v1/leave"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"staffProfileId":"%s","leaveType":"TIME_OFF","fromDate":"%s","toDate":"%s",
								 "halfDay":true,"reason":"A hospital appointment"}
								""".formatted(cookProfile, WEDNESDAY, WEDNESDAY)))
				.andExpect(status().isCreated());

		mvc.perform(authed(get("/api/v1/staff/schedule/week").param("weekStart", WEEK_START)))
				// Still on the grid, still drawn as a working day, and marked as the half day it is.
				.andExpect(jsonPath("$.staff[0].fullName").value("Head Cook A"))
				.andExpect(jsonPath("$.staff[0].days[2].working").value(true))
				.andExpect(jsonPath("$.staff[0].days[2].halfDayLeave").value(true))
				.andExpect(jsonPath("$.staff[0].days[2].leaveLabel").value("Time off"))
				.andExpect(jsonPath("$.staff[0].days[2].startTime").value("09:00:00"))
				// And out of the count. Zero is the only number the record supports.
				.andExpect(jsonPath("$.counts[2].staffIn").value(1))
				// Monday is untouched: a half day takes one day, not the pattern.
				.andExpect(jsonPath("$.counts[0].staffIn").value(2));

		// The same figure the Today tile and the planner pebbles read, from the same code.
		mvc.perform(authed(get("/api/v1/workforce").param("from", WEDNESDAY).param("to", WEDNESDAY)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].staffIn").value(1));
	}

	@Test
	@DisplayName("leave still waiting to be answered changes nothing")
	void pendingLeaveIsNotYetAnAbsence() throws Exception {
		signIn("uid-cook");
		mvc.perform(authed(post("/api/v1/leave/mine"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"leaveType":"TIME_OFF","fromDate":"%s","toDate":"%s","halfDay":true}
								""".formatted(WEDNESDAY, WEDNESDAY)))
				.andExpect(status().isCreated());

		// A grid that emptied itself the moment somebody asked would let anyone take a day off by
		// requesting one. The half-day rule applies to leave that has been granted, not to a question.
		signIn("uid-admin");
		mvc.perform(authed(get("/api/v1/workforce").param("from", WEDNESDAY).param("to", WEDNESDAY)))
				.andExpect(jsonPath("$[0].staffIn").value(2));
	}

	// ---- helpers ----------------------------------------------------------

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
