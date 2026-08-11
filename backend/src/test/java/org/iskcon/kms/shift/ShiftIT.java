package org.iskcon.kms.shift;

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
 * Volunteer shift posting (E6-S2): creation is publication with live capacity counts, reminder
 * offsets stored (default 24h), duplication carries settings, and cancellation notifies everyone on
 * the roster and closes the shift.
 */
@AutoConfigureMockMvc
@Import(ShiftIT.StubVerifierConfiguration.class)
class ShiftIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID vol1;
	private UUID vol2;

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
				VALUES (?, 'uid-staff', 'Kitchen Staff', 'staff@example.com', '+919876500001', 'KITCHEN_STAFF', 'ACTIVE')
				""", tenant);
		vol1 = volunteer("uid-vol-1", "Vol One", "+919876500091");
		vol2 = volunteer("uid-vol-2", "Vol Two", "+919876500092");
		// Consent so cancellation notices are sent, not suppressed.
		admin.update("UPDATE users SET contact_consent_at = now() WHERE role = 'VOLUNTEER'");
		signIn("uid-staff");
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

	@Test
	@DisplayName("a posted shift is listed with capacity, zero signups, and the default 24h reminder")
	void postedShiftListed() throws Exception {
		mvc.perform(create("{\"title\":\"Sunday prep\",\"shiftDate\":\"2026-09-06\",\"startTime\":\"08:00\","
						+ "\"endTime\":\"12:00\",\"location\":\"Main kitchen\",\"capacity\":5}"))
				.andExpect(status().isCreated());

		mvc.perform(authed(get("/api/v1/shifts")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].title").value("Sunday prep"))
				.andExpect(jsonPath("$[0].capacity").value(5))
				.andExpect(jsonPath("$[0].signedUpCount").value(0))
				.andExpect(jsonPath("$[0].reminderOffsetsMinutes[0]").value(1440));
	}

	@Test
	@DisplayName("custom reminder offsets are stored, sorted and de-duplicated")
	void customOffsetsStored() throws Exception {
		String id = createId("{\"title\":\"Festival cooking\",\"shiftDate\":\"2026-09-06\",\"startTime\":\"06:00\","
				+ "\"endTime\":\"10:00\",\"capacity\":10,\"reminderOffsetsMinutes\":[1440,2880,1440]}");
		mvc.perform(authed(get("/api/v1/shifts/{id}", id)))
				.andExpect(jsonPath("$.reminderOffsetsMinutes[0]").value(1440))
				.andExpect(jsonPath("$.reminderOffsetsMinutes[1]").value(2880))
				.andExpect(jsonPath("$.reminderOffsetsMinutes.length()").value(2));
	}

	@Test
	@DisplayName("duplicating a shift carries every setting to the new date")
	void duplicateCarriesSettings() throws Exception {
		String id = createId("{\"title\":\"Daily prep\",\"description\":\"chop veg\",\"shiftDate\":\"2026-09-06\","
				+ "\"startTime\":\"08:00\",\"endTime\":\"12:00\",\"location\":\"Prep area\",\"capacity\":4,"
				+ "\"reminderOffsetsMinutes\":[2880,1440]}");
		String body = mvc.perform(authed(post("/api/v1/shifts/{id}/duplicate", id))
						.contentType(MediaType.APPLICATION_JSON).content("{\"shiftDate\":\"2026-09-07\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String newId = JSON.readTree(body).get("id").asText();

		mvc.perform(authed(get("/api/v1/shifts/{id}", newId)))
				.andExpect(jsonPath("$.title").value("Daily prep"))
				.andExpect(jsonPath("$.description").value("chop veg"))
				.andExpect(jsonPath("$.shiftDate").value("2026-09-07"))
				.andExpect(jsonPath("$.location").value("Prep area"))
				.andExpect(jsonPath("$.capacity").value(4))
				.andExpect(jsonPath("$.reminderOffsetsMinutes.length()").value(2));
	}

	@Test
	@DisplayName("cancelling notifies everyone on the roster and closes the shift")
	void cancelNotifiesAndCloses() throws Exception {
		String id = createId("{\"title\":\"Sunday prep\",\"shiftDate\":\"2026-09-06\",\"startTime\":\"08:00\","
				+ "\"endTime\":\"12:00\",\"capacity\":1}");
		// One signed up, one waitlisted (seeded directly; the claim logic is E6-S3/S5).
		admin.update("INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?::uuid, ?)",
				tenant, id, vol1);
		admin.update("INSERT INTO shift_waitlist (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?::uuid, ?)",
				tenant, id, vol2);

		mvc.perform(authed(post("/api/v1/shifts/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"festival postponed\"}"))
				.andExpect(status().isNoContent());

		for (UUID v : new UUID[] {vol1, vol2}) {
			Integer n = admin.queryForObject(
					"SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND template = 'SHIFT_CANCELLED'",
					Integer.class, v);
			assert n == 1 : "each roster member should be notified once, was " + n + " for " + v;
		}

		// Closed: a second cancel (or any edit) is refused.
		mvc.perform(authed(post("/api/v1/shifts/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4928"));
	}

	@Test
	@DisplayName("a volunteer cannot post shifts")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol-1");
		mvc.perform(authed(get("/api/v1/shifts"))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private MockHttpServletRequestBuilder create(String json) {
		return authed(post("/api/v1/shifts")).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private String createId(String json) throws Exception {
		String body = mvc.perform(create(json)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("id").asText();
	}

	private UUID volunteer(String uid, String name, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, 'VOLUNTEER', 'ACTIVE') RETURNING id
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
