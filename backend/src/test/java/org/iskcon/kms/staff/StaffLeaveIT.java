package org.iskcon.kms.staff;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Leave (B7): asking, answering, and the two things that answer depends on — the week grid and the
 * head count both dropping the person once the leave is approved.
 *
 * <p>The week used here is Monday 31 August to Sunday 6 September 2026, so the resolution is
 * exercised across a month boundary as well.
 */
@AutoConfigureMockMvc
@Import(StaffLeaveIT.StubVerifierConfiguration.class)
class StaffLeaveIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

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
	private UUID cook;
	private UUID otherCook;
	private UUID manager;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Temple Admin', 'admin@example.com', '+919876500001', 'TEMPLE_ADMIN', 'ACTIVE')
				""", tenant);
		cook = user("uid-cook", "Head Cook A", "+919876500081", "KITCHEN_STAFF");
		otherCook = user("uid-cook-b", "Prep B", "+919876500082", "KITCHEN_STAFF");
		manager = user("uid-manager", "Kitchen Manager", "+919876500083", "KITCHEN_MANAGER");
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		// Hiring runs a ban check, and that check records itself on the platform log with the acting
		// admin as its actor. Leaving those rows behind makes DELETE FROM users below fail on a
		// foreign key, and every later class inherit a temple it did not create.
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM staff_leave");
		admin.execute("DELETE FROM staff_schedule_exceptions");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("approved leave drops the person out of the grid and out of the day's head count")
	void approvedLeaveClearsTheDay() throws Exception {
		weekdayCook(cook);

		signIn("uid-cook");
		UUID leave = ask("SICK", WEDNESDAY, WEDNESDAY, false);

		// Until it is answered, they are still expected in. A grid that emptied itself the moment
		// somebody asked would let anyone take a day off by requesting one.
		signIn("uid-admin");
		mvc.perform(authed(get("/api/v1/staff/schedule/week").param("weekStart", WEEK_START)))
				.andExpect(jsonPath("$.staff[0].days[2].working").value(true))
				.andExpect(jsonPath("$.counts[2].staffIn").value(1));

		mvc.perform(authed(post("/api/v1/leave/{id}/approve", leave))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/staff/schedule/week").param("weekStart", WEEK_START)))
				.andExpect(jsonPath("$.staff[0].days[2].working").value(false))
				.andExpect(jsonPath("$.staff[0].days[2].leaveId").value(leave.toString()))
				.andExpect(jsonPath("$.staff[0].days[2].leaveLabel").value("Sick leave"))
				// Monday is untouched: leave takes one day, not the pattern.
				.andExpect(jsonPath("$.staff[0].days[0].working").value(true))
				.andExpect(jsonPath("$.counts[0].staffIn").value(1))
				.andExpect(jsonPath("$.counts[2].staffIn").value(0));

		// The same figure the Today tile and the planner pebbles read, from the same code.
		mvc.perform(authed(get("/api/v1/workforce").param("from", WEDNESDAY).param("to", WEDNESDAY)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].staffIn").value(0))
				.andExpect(jsonPath("$[0].volunteers").value(0));
	}

	@Test
	@DisplayName("revoking approved leave puts them back on the grid")
	void revokingRestoresTheDay() throws Exception {
		weekdayCook(cook);
		signIn("uid-cook");
		UUID leave = ask("TIME_OFF", WEDNESDAY, WEDNESDAY, false);

		signIn("uid-admin");
		mvc.perform(authed(post("/api/v1/leave/{id}/approve", leave))
				.contentType(MediaType.APPLICATION_JSON).content("{}"));
		mvc.perform(authed(post("/api/v1/leave/{id}/revoke", leave))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"note\":\"We need them after all.\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/staff/schedule/week").param("weekStart", WEEK_START)))
				.andExpect(jsonPath("$.staff[0].days[2].working").value(true))
				.andExpect(jsonPath("$.staff[0].days[2].leaveId").doesNotExist())
				.andExpect(jsonPath("$.counts[2].staffIn").value(1));

		// A record still waiting, or one already refused, has nothing to take back.
		mvc.perform(authed(post("/api/v1/leave/{id}/revoke", leave))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4955"));
	}

	@Test
	@DisplayName("a decision reaches the person who asked, and nobody else")
	void decisionNotifiesTheRequester() throws Exception {
		// A consent timestamp is what makes a notification sent rather than suppressed.
		admin.update("UPDATE users SET contact_consent_at = now() WHERE role = 'KITCHEN_STAFF'");
		weekdayCook(cook);
		weekdayCook(otherCook);

		signIn("uid-cook");
		UUID leave = ask("UNPAID", WEDNESDAY, WEDNESDAY, false);

		signIn("uid-admin");
		mvc.perform(authed(post("/api/v1/leave/{id}/decline", leave))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"note\":\"Janmashtami week — we need everybody.\"}"))
				.andExpect(status().isNoContent());

		Integer toCook = admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND template = 'LEAVE_DECLINED'",
				Integer.class, cook);
		// Anything about leave, not anything at all: saving their template told them about that, and
		// rightly so — what must not reach them is somebody else's leave.
		Integer toOther = admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND template LIKE 'LEAVE\\_%'",
				Integer.class, otherCook);
		assert toCook == 1 : "the person who asked should be told once, was " + toCook;
		assert toOther == 0 : "nobody else should hear about it, but " + toOther + " did";

		// Answered once, and never answered twice.
		mvc.perform(authed(post("/api/v1/leave/{id}/approve", leave))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4954"));
	}

	@Test
	@DisplayName("leave can be back-dated — sick leave arrives after the fact")
	void backDatingIsAllowed() throws Exception {
		weekdayCook(cook);
		signIn("uid-cook");

		mvc.perform(authed(post("/api/v1/leave/mine"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("SICK", "2019-04-11", "2019-04-11", false)))
				.andExpect(status().isCreated());

		mvc.perform(authed(get("/api/v1/leave/mine")))
				.andExpect(jsonPath("$[0].fromDate").value("2019-04-11"))
				.andExpect(jsonPath("$[0].status").value("PENDING"));
	}

	@Test
	@DisplayName("overlapping leave for the same person is refused, and a half day covers one date")
	void shapesThatCannotBeTrue() throws Exception {
		weekdayCook(cook);
		signIn("uid-cook");
		ask("TIME_OFF", "2026-09-01", "2026-09-04", false);

		mvc.perform(authed(post("/api/v1/leave/mine"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("SICK", "2026-09-03", "2026-09-07", false)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4953"));

		mvc.perform(authed(post("/api/v1/leave/mine"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("TIME_OFF", "2026-09-14", "2026-09-18", true)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4006"));

		mvc.perform(authed(post("/api/v1/leave/mine"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("TIME_OFF", "2026-09-18", "2026-09-14", false)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4005"));
	}

	@Test
	@DisplayName("a kitchen manager may answer leave; a cook may not, and may only withdraw their own")
	void whoMayAnswer() throws Exception {
		weekdayCook(cook);
		weekdayCook(otherCook);
		signIn("uid-cook");
		UUID leave = ask("SICK", WEDNESDAY, WEDNESDAY, false);

		// The role exists for exactly this: the roster is theirs, and leave is what it bends around.
		signIn("uid-manager");
		mvc.perform(authed(get("/api/v1/leave"))).andExpect(status().isOk());

		signIn("uid-cook-b");
		mvc.perform(authed(get("/api/v1/leave"))).andExpect(status().isForbidden());
		mvc.perform(authed(post("/api/v1/leave/{id}/approve", leave))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden());
		// Withdrawing is for the person who asked, and this is not them.
		mvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.delete("/api/v1/leave/mine/{id}", leave)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4306"));

		signIn("uid-manager");
		mvc.perform(authed(post("/api/v1/leave/{id}/approve", leave))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("a volunteer has no staff record here, and is told so rather than shown an empty list")
	void noStaffRecord() throws Exception {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-vol', 'Vol A', 'vol@example.com', '+919876500091', 'VOLUNTEER', 'ACTIVE')
				""", tenant);
		signIn("uid-vol");
		mvc.perform(authed(get("/api/v1/leave/mine")))
				.andExpect(status().isForbidden()); // VOLUNTEER holds no REQUEST_OWN_LEAVE at all

		// Somebody who does hold it but has no employment record — an admin at a temple that has not
		// hired them onto its own register.
		signIn("uid-admin");
		mvc.perform(authed(get("/api/v1/leave/mine")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-4403"));
	}

	@Test
	@DisplayName("leave recorded for somebody with no login lands already approved, with no requester")
	void recordedOnBehalfIsApproved() throws Exception {
		UUID janitor = hireWithoutLogin();

		mvc.perform(authed(post("/api/v1/leave"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"staffProfileId":"%s","leaveType":"SICK","fromDate":"%s","toDate":"%s",
								 "halfDay":false,"reason":"Rang in with fever"}
								""".formatted(janitor, WEDNESDAY, WEDNESDAY)))
				.andExpect(status().isCreated());

		mvc.perform(authed(get("/api/v1/leave")))
				.andExpect(jsonPath("$[0].status").value("APPROVED"))
				// Nobody asked: the person recording it and the person approving it are the same.
				.andExpect(jsonPath("$[0].requestedByName").doesNotExist())
				.andExpect(jsonPath("$[0].decidedByName").value("Temple Admin"));
	}

	// ---------------------------------------------------------------------

	/** Hires somebody with a login, and gives them Monday to Friday, nine to five. */
	private UUID weekdayCook(UUID userId) throws Exception {
		String created = mvc.perform(authed(post("/api/v1/staff/members"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"existingUserId\":\"" + userId + "\",\"fullName\":\"Hired Person\","
								+ "\"jobTitle\":\"COOK\",\"employmentType\":\"FULL_TIME\","
								+ "\"dateOfJoining\":\"2026-01-05\",\"systemAccess\":\"KITCHEN_STAFF\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		UUID profile = UUID.fromString(JSON.readTree(created).get("id").asText());

		StringBuilder days = new StringBuilder();
		for (int d = 1; d <= 7; d++) {
			if (days.length() > 0) {
				days.append(",");
			}
			days.append(d <= 5
					? "{\"dayOfWeek\":" + d + ",\"working\":true,\"startTime\":\"09:00\",\"endTime\":\"17:00\"}"
					: "{\"dayOfWeek\":" + d + ",\"working\":false}");
		}
		mvc.perform(authed(put("/api/v1/staff/profiles/{id}/template", profile))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"days\":[" + days + "]}"))
				.andExpect(status().isNoContent());
		return profile;
	}

	/** The janitor of the brief: employed here, and holding no app account at all. */
	private UUID hireWithoutLogin() throws Exception {
		String created = mvc.perform(authed(post("/api/v1/staff/members"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"Ganesh the janitor\",\"jobTitle\":\"HOUSEKEEPING\","
								+ "\"employmentType\":\"FULL_TIME\",\"dateOfJoining\":\"2026-02-01\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JSON.readTree(created).get("id").asText());
	}

	private UUID ask(String type, String from, String to, boolean halfDay) throws Exception {
		String created = mvc.perform(authed(post("/api/v1/leave/mine"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(type, from, to, halfDay)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JSON.readTree(created).get("id").asText());
	}

	private static String body(String type, String from, String to, boolean halfDay) {
		return "{\"leaveType\":\"%s\",\"fromDate\":\"%s\",\"toDate\":\"%s\",\"halfDay\":%s}"
				.formatted(type, from, to, halfDay);
	}

	private UUID user(String uid, String name, String phone, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenant, uid, name, uid + "@example.com", phone, role);
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
