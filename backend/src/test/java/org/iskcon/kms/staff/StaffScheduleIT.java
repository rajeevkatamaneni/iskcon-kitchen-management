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
	@DisplayName("an exception overrides one date without touching the template, across a month boundary")
	void exceptionOverridesOneDate() throws Exception {
		UUID profile = createProfileId(staffA, "HEAD_COOK");
		// Monday–Friday 09:00–17:00, weekend off.
		mvc.perform(authed(put("/api/v1/staff/profiles/{id}/template", profile))
						.contentType(MediaType.APPLICATION_JSON)
						.content(weekdayTemplate()))
				.andExpect(status().isNoContent());

		// One-off day off on Tue 1 Sep 2026 (the week starting Mon 31 Aug 2026 crosses into September).
		mvc.perform(authed(put("/api/v1/staff/profiles/{id}/exceptions", profile))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"exceptionDate\":\"2026-09-01\",\"working\":false}"))
				.andExpect(status().isNoContent());

		// Template is intact: Tuesday is still a working day in the template.
		mvc.perform(authed(get("/api/v1/staff/profiles/{id}", profile)))
				.andExpect(jsonPath("$.template[?(@.dayOfWeek==2)].working").value(true));

		// Week grid: Mon 31 Aug works (template), Tue 1 Sep is off (exception).
		mvc.perform(authed(get("/api/v1/staff/schedule/week").param("weekStart", "2026-08-31")))
				.andExpect(jsonPath("$.staff[0].days[0].date").value("2026-08-31"))
				.andExpect(jsonPath("$.staff[0].days[0].working").value(true))     // Mon, template
				.andExpect(jsonPath("$.staff[0].days[1].date").value("2026-09-01"))
				.andExpect(jsonPath("$.staff[0].days[1].working").value(false))    // Tue, exception
				.andExpect(jsonPath("$.staff[0].days[1].fromException").value(true));
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
