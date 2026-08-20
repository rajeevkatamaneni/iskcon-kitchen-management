package org.iskcon.kms.staff;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Staff profiles and weekly schedule (E6-S1): the template/exception model, the resolved week grid
 * across a month boundary, and the change notification reaching only the affected staff member.
 */
@AutoConfigureMockMvc
@Import(StaffScheduleIT.StubVerifierConfiguration.class)
class StaffScheduleIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler; // no-op enqueue so a change notification is recorded, not sent

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffA;
	private UUID staffB;

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
		staffA = staff("uid-staff-a", "Head Cook A", "+919876500081");
		staffB = staff("uid-staff-b", "Prep B", "+919876500082");
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-vol-a', 'Vol A', 'vol-a@example.com', '+919876500091', 'VOLUNTEER', 'ACTIVE')
				""", tenant);
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
	@DisplayName("one employment record per person — hiring the same person twice is refused")
	void oneRecordPerPerson() throws Exception {
		// A devotee is exactly who a temple hires: promoting one is the point, not an error.
		UUID volId = admin.queryForObject("SELECT id FROM users WHERE firebase_uid = 'uid-vol-a'", UUID.class);
		mvc.perform(hire(volId, "COOK")).andExpect(status().isCreated());

		mvc.perform(hire(staffA, "HEAD_COOK")).andExpect(status().isCreated());
		mvc.perform(hire(staffA, "HEAD_COOK"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4926"));
	}

	@Test
	@DisplayName("an override changes one date without touching the template, across a month boundary")
	void exceptionOverridesOneDate() throws Exception {
		UUID profile = createProfileId(staffA, "HEAD_COOK");
		// Monday–Friday 09:00–17:00, weekend off.
		mvc.perform(authed(put("/api/v1/staff/profiles/{id}/template", profile))
						.contentType(MediaType.APPLICATION_JSON)
						.content(weekdayTemplate()))
				.andExpect(status().isNoContent());

		// Different hours on Tue 1 Sep 2026 (the week starting Mon 31 Aug crosses into September).
		mvc.perform(authed(put("/api/v1/staff/profiles/{id}/exceptions", profile))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"exceptionDate\":\"2026-09-01\",\"working\":true,"
								+ "\"startTime\":\"06:00\",\"endTime\":\"12:00\"}"))
				.andExpect(status().isNoContent());

		// Template is intact: Tuesday is still nine to five in the pattern.
		mvc.perform(authed(get("/api/v1/staff/profiles/{id}", profile)))
				.andExpect(jsonPath("$.template[?(@.dayOfWeek==2)].startTime").value("09:00:00"));

		// Week grid: Mon 31 Aug from the template, Tue 1 Sep from the override.
		mvc.perform(authed(get("/api/v1/staff/schedule/week").param("weekStart", "2026-08-31")))
				.andExpect(jsonPath("$.staff[0].days[0].date").value("2026-08-31"))
				.andExpect(jsonPath("$.staff[0].days[0].startTime").value("09:00:00"))
				.andExpect(jsonPath("$.staff[0].days[1].date").value("2026-09-01"))
				.andExpect(jsonPath("$.staff[0].days[1].startTime").value("06:00:00"))
				.andExpect(jsonPath("$.staff[0].days[1].fromException").value(true))
				// Both days still count as somebody in the kitchen: changed hours, not an absence.
				.andExpect(jsonPath("$.counts[0].staffIn").value(1))
				.andExpect(jsonPath("$.counts[1].staffIn").value(1));
	}

	@Test
	@DisplayName("a day off cannot be written as an override — an absence is a leave record")
	void markingOffIsNotAnOverride() throws Exception {
		UUID profile = createProfileId(staffA, "HEAD_COOK");
		mvc.perform(authed(put("/api/v1/staff/profiles/{id}/template", profile))
				.contentType(MediaType.APPLICATION_JSON).content(weekdayTemplate()));

		// The whole point of B7 §4: one answer to "why is this person not in on Thursday". Quietly
		// turning this into leave would be a different act from the one the caller asked for, so it
		// is refused instead.
		mvc.perform(authed(put("/api/v1/staff/profiles/{id}/exceptions", profile))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"exceptionDate\":\"2026-09-01\",\"working\":false}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("a swap writes both halves together, and undoing either undoes both")
	void swapIsWrittenAndUndoneAsAWhole() throws Exception {
		UUID profile = createProfileId(staffA, "HEAD_COOK");
		mvc.perform(authed(put("/api/v1/staff/profiles/{id}/template", profile))
				.contentType(MediaType.APPLICATION_JSON).content(weekdayTemplate()));

		// Tuesday's shift moves to Saturday, which they do not normally work.
		mvc.perform(authed(post("/api/v1/staff/profiles/{id}/exceptions/swap", profile))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromDate\":\"2026-09-01\",\"toDate\":\"2026-09-05\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/staff/schedule/week").param("weekStart", "2026-08-31")))
				.andExpect(jsonPath("$.staff[0].days[1].working").value(false))
				.andExpect(jsonPath("$.staff[0].days[1].swapLinkId").isNotEmpty())
				// The hours travel with them rather than being asked for a second time.
				.andExpect(jsonPath("$.staff[0].days[5].working").value(true))
				.andExpect(jsonPath("$.staff[0].days[5].startTime").value("09:00:00"))
				.andExpect(jsonPath("$.staff[0].days[5].swapLinkId").isNotEmpty())
				// Five working days before, five after: a swap moves a day, it does not add one.
				.andExpect(jsonPath("$.counts[1].staffIn").value(0))
				.andExpect(jsonPath("$.counts[5].staffIn").value(1));

		// Undo the half they were added to. The half they were taken off must go with it, or the
		// grid is left showing a cook who simply vanished from Tuesday.
		UUID saturdayHalf = admin.queryForObject(
				"SELECT id FROM staff_schedule_exceptions WHERE exception_date = '2026-09-05'", UUID.class);
		mvc.perform(authed(delete("/api/v1/staff/profiles/{id}/exceptions/{exceptionId}", profile, saturdayHalf)))
				.andExpect(status().isNoContent());

		Integer left = admin.queryForObject(
				"SELECT count(*) FROM staff_schedule_exceptions", Integer.class);
		assert left == 0 : "undoing one half of a swap should remove both, " + left + " left";

		mvc.perform(authed(get("/api/v1/staff/schedule/week").param("weekStart", "2026-08-31")))
				.andExpect(jsonPath("$.staff[0].days[1].working").value(true))
				.andExpect(jsonPath("$.staff[0].days[5].working").value(false));
	}

	@Test
	@DisplayName("a swap needs two different days, and something to move")
	void swapShapesRefused() throws Exception {
		UUID profile = createProfileId(staffA, "HEAD_COOK");
		mvc.perform(authed(put("/api/v1/staff/profiles/{id}/template", profile))
				.contentType(MediaType.APPLICATION_JSON).content(weekdayTemplate()));

		mvc.perform(authed(post("/api/v1/staff/profiles/{id}/exceptions/swap", profile))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromDate\":\"2026-09-01\",\"toDate\":\"2026-09-01\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4957"));

		// Saturday is already a day off, so there is no shift on it to move anywhere.
		mvc.perform(authed(post("/api/v1/staff/profiles/{id}/exceptions/swap", profile))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromDate\":\"2026-09-05\",\"toDate\":\"2026-09-02\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("the grid refuses to schedule over approved leave; the manager revokes it first")
	void cannotScheduleOverApprovedLeave() throws Exception {
		UUID profile = createProfileId(staffA, "HEAD_COOK");
		mvc.perform(authed(put("/api/v1/staff/profiles/{id}/template", profile))
				.contentType(MediaType.APPLICATION_JSON).content(weekdayTemplate()));

		// Saturday off, recorded and approved in one act by the admin who wrote it.
		String created = mvc.perform(authed(post("/api/v1/leave"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"staffProfileId":"%s","leaveType":"TIME_OFF","fromDate":"2026-09-05",
								 "toDate":"2026-09-05","halfDay":false}
								""".formatted(profile)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		UUID leave = UUID.fromString(JSON.readTree(created).get("id").asText());

		mvc.perform(authed(put("/api/v1/staff/profiles/{id}/exceptions", profile))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"exceptionDate\":\"2026-09-05\",\"working\":true,"
								+ "\"startTime\":\"09:00\",\"endTime\":\"17:00\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4956"));

		mvc.perform(authed(post("/api/v1/staff/profiles/{id}/exceptions/swap", profile))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromDate\":\"2026-09-01\",\"toDate\":\"2026-09-05\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4956"));

		// Revoked, and now the day is the roster's again.
		mvc.perform(authed(post("/api/v1/leave/{id}/revoke", leave))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNoContent());
		mvc.perform(authed(put("/api/v1/staff/profiles/{id}/exceptions", profile))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"exceptionDate\":\"2026-09-05\",\"working\":true,"
								+ "\"startTime\":\"09:00\",\"endTime\":\"17:00\"}"))
				.andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("a schedule change notifies the affected staff member and no one else")
	void changeNotifiesAffectedOnly() throws Exception {
		// Both staff need a consent timestamp for a notification to be sent rather than suppressed.
		admin.update("UPDATE users SET contact_consent_at = now() WHERE role = 'KITCHEN_STAFF'");
		UUID profileA = createProfileId(staffA, "HEAD_COOK");
		createProfileId(staffB, "ASSISTANT_COOK");

		mvc.perform(authed(put("/api/v1/staff/profiles/{id}/template", profileA))
						.contentType(MediaType.APPLICATION_JSON).content(weekdayTemplate()))
				.andExpect(status().isNoContent());

		Integer forA = admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND template = 'STAFF_SCHEDULE_UPDATED'",
				Integer.class, staffA);
		Integer forB = admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND template = 'STAFF_SCHEDULE_UPDATED'",
				Integer.class, staffB);
		assert forA == 1 : "affected staff A should be notified once, was " + forA;
		assert forB == 0 : "unaffected staff B should not be notified, was " + forB;
	}

	@Test
	@DisplayName("a staff member reads their own schedule; a volunteer cannot manage schedules")
	void selfReadAndAuthorization() throws Exception {
		createProfileId(staffA, "HEAD_COOK");

		signIn("uid-staff-a");
		mvc.perform(authed(get("/api/v1/staff/schedule/me")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.profile.fullName").value("Hired Person"))
				.andExpect(jsonPath("$.template.length()").value(7));

		signIn("uid-vol-a");
		mvc.perform(authed(get("/api/v1/staff/register"))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private String weekdayTemplate() {
		StringBuilder days = new StringBuilder();
		for (int d = 1; d <= 7; d++) {
			if (days.length() > 0) days.append(",");
			if (d <= 5) {
				days.append("{\"dayOfWeek\":").append(d)
						.append(",\"working\":true,\"startTime\":\"09:00\",\"endTime\":\"17:00\"}");
			} else {
				days.append("{\"dayOfWeek\":").append(d).append(",\"working\":false}");
			}
		}
		return "{\"days\":[" + days + "]}";
	}

	/** A schedule needs somebody to hold it, and hiring is now the only way anyone comes to. */
	private MockHttpServletRequestBuilder hire(UUID userId, String jobTitle) {
		return authed(post("/api/v1/staff/members"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"existingUserId\":\"" + userId + "\",\"fullName\":\"Hired Person\","
						+ "\"jobTitle\":\"" + jobTitle + "\",\"employmentType\":\"FULL_TIME\","
						+ "\"dateOfJoining\":\"2026-01-05\",\"systemAccess\":\"KITCHEN_STAFF\"}");
	}

	private UUID createProfileId(UUID userId, String jobTitle) throws Exception {
		String body = mvc.perform(hire(userId, jobTitle))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JSON.readTree(body).get("id").asText());
	}

	private UUID staff(String uid, String name, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, 'KITCHEN_STAFF', 'ACTIVE') RETURNING id
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
