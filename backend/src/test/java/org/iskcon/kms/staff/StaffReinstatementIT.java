package org.iskcon.kms.staff;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Taking somebody back on (T-014), through the full stack.
 *
 * <p>Ending an employment was irreversible <em>and</em> locked the record in the same instant —
 * {@code requireStillEmployed} guards {@code endEmployment} and {@code update} alike — so a misclick
 * on the termination form could not even be corrected. Everything worth proving here needs a real
 * database: that the guard on editing genuinely lifts, that a disabled account really can sign in
 * again afterwards, and that a refusal's audit row survives the rollback that follows it.
 *
 * <p>Held apart from {@code StaffEmploymentIT} rather than added to it because that class is about
 * the door closing and this one is about the single way back through it, and because five other
 * tests in two other classes drive {@code /end-employment} purely as setup. Nothing here changes
 * what that endpoint does.
 */
@AutoConfigureMockMvc
@Import(StaffReinstatementIT.StubVerifierConfiguration.class)
class StaffReinstatementIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID devotee;

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
		devotee = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-devotee', 'Gopal Das', 'gopal@example.com', '+919876500071', 'VOLUNTEER', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM employment_bans");
		admin.execute("DELETE FROM staff_schedule_exceptions");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	/**
	 * Acceptance criterion 1, both halves, and the reason the feature exists.
	 *
	 * <p>The edit at the end is the point. It is the same PUT that {@code requireStillEmployed}
	 * refuses for a former employee, sent with an ordinary correction, and a 204 from it is the only
	 * honest proof that the record is genuinely editable again rather than merely showing ACTIVE.
	 */
	@Test
	@DisplayName("somebody taken back on can be edited again and can sign in again")
	void reinstatedStaffAreEditableAndCanSignInAgain() throws Exception {
		String id = hireId("""
				{"existingUserId":"%s","fullName":"Gopal Das","jobTitle":"HEAD_COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-02-01","systemAccess":"KITCHEN_STAFF"}
				""".formatted(devotee));

		// Dismissed with their sign-in taken away, which is the worst of the two endings to undo.
		mvc.perform(authed(post("/api/v1/staff/members/{id}/end-employment", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"status":"TERMINATED","lastWorkingDay":"2026-06-30",
						 "reason":"Wrong person clicked","revokeSignIn":true}
						"""))
				.andExpect(status().isNoContent());
		assertThat(admin.queryForObject("SELECT status FROM users WHERE id = ?", String.class, devotee))
				.as("the ending really did take their sign-in away")
				.isEqualTo("DISABLED");

		mvc.perform(authed(post("/api/v1/staff/members/{id}/reinstate", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"dateOfRejoining":"2026-07-15","systemAccess":"KITCHEN_MANAGER",
						 "reason":"Terminated by mistake"}
						"""))
				.andExpect(status().isNoContent());

		Map<String, Object> profile = admin.queryForMap(
				"SELECT employment_status, last_working_day, end_reason FROM staff_profiles WHERE id = ?::uuid", id);
		assertThat(profile.get("employment_status")).isEqualTo("ACTIVE");
		assertThat(profile.get("last_working_day"))
				.as("an active employment with a last working day on it would contradict itself")
				.isNull();
		assertThat(profile.get("end_reason")).isNull();

		Map<String, Object> account = admin.queryForMap("SELECT role, status FROM users WHERE id = ?", devotee);
		assertThat(account.get("status"))
				.as("a disabled account is what stops somebody signing in, so reinstatement has to clear it")
				.isEqualTo("ACTIVE");
		assertThat(account.get("role"))
				.as("they come back as what the admin asked for, because nothing stored what they were")
				.isEqualTo("KITCHEN_MANAGER");

		// The guard on update has lifted. This exact request would have been refused with
		// KMS-400085 a moment ago, which is the whole defect this task was raised for.
		mvc.perform(authed(put("/api/v1/staff/members/{id}", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"fullName":"Gopal Das","jobTitle":"HEAD_COOK","employmentType":"FULL_TIME",
						 "dateOfJoining":"2026-02-01","systemAccess":"KITCHEN_MANAGER",
						 "phone":"+919876500071","email":"gopal@example.com","notes":"Back on the roster"}
						"""))
				.andExpect(status().isNoContent());
		assertThat(admin.queryForObject(
				"SELECT notes FROM staff_profiles WHERE id = ?::uuid", String.class, id))
				.isEqualTo("Back on the roster");
	}

	/**
	 * The other half of "can sign in if they could before": coming back without one.
	 *
	 * <p>The role assertion is the one that matters and it is not obvious. {@code endEmployment}
	 * with {@code revokeSignIn} disables the account but leaves {@code role} alone, so a dismissed
	 * administrator still carries TEMPLE_ADMIN on their users row. Reinstating them with no login
	 * has to take that off, or the temple would hold a row that is one status change away from
	 * being an administrator nobody granted.
	 */
	@Test
	@DisplayName("somebody taken back on without a login gets neither the login nor the temple role")
	void comingBackWithoutALoginRestoresNeither() throws Exception {
		String id = hireId("""
				{"existingUserId":"%s","fullName":"Gopal Das","jobTitle":"TEMPLE_ADMINISTRATOR",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-02-01","systemAccess":"TEMPLE_ADMIN"}
				""".formatted(devotee));
		mvc.perform(authed(post("/api/v1/staff/members/{id}/end-employment", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"status":"TERMINATED","lastWorkingDay":"2026-06-30","revokeSignIn":true}
						"""))
				.andExpect(status().isNoContent());

		mvc.perform(authed(post("/api/v1/staff/members/{id}/reinstate", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"dateOfRejoining":"2026-07-15","systemAccess":null}
						"""))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT employment_status FROM staff_profiles WHERE id = ?::uuid", String.class, id))
				.as("they are employed again either way — access is a separate question")
				.isEqualTo("ACTIVE");

		Map<String, Object> account = admin.queryForMap("SELECT role, status FROM users WHERE id = ?", devotee);
		assertThat(account.get("status"))
				.as("no login was asked for, so no login is what they get")
				.isEqualTo("DISABLED");
		assertThat(account.get("role"))
				.as("a dismissed administrator keeps TEMPLE_ADMIN on their row, and this has to strip it")
				.isEqualTo("VOLUNTEER");
	}

	/** Acceptance criterion 2. */
	@Test
	@DisplayName("reinstating somebody who never left says so rather than quietly succeeding")
	void reinstatingSomebodyStillEmployedIsRefused() throws Exception {
		String id = hireId("""
				{"fullName":"Radha Devi","jobTitle":"COOK","employmentType":"FULL_TIME",
				 "dateOfJoining":"2026-02-01"}
				""");

		mvc.perform(authed(post("/api/v1/staff/members/{id}/reinstate", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"dateOfRejoining":"2026-07-15","systemAccess":null}
						"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400135"));

		assertThat(countOf("STAFF_EMPLOYMENT_REINSTATED"))
				.as("nobody was reinstated, so nothing may be filed as though somebody was")
				.isZero();
	}

	/**
	 * Acceptance criteria 3 and 4 together, and the pair of counts is the proof rather than either
	 * one alone.
	 *
	 * <p>Zero {@code STAFF_EMPLOYMENT_REINSTATED} says the request's own transaction really did roll
	 * back. One {@code STAFF_REINSTATEMENT_REJECTED} says the refusal survived it, which is only true
	 * because that write goes out on a transaction of its own. Through the endpoint rather than the
	 * service for exactly that reason: a direct service call would have no committed transaction to
	 * roll back and would prove nothing.
	 */
	@Test
	@DisplayName("a live record against somebody refuses the reinstatement, and the refusal outlives it")
	void aLiveRecordRefusesTheReinstatementAndIsRecorded() throws Exception {
		String id = dismissedWithARecordRaised();

		mvc.perform(authed(post("/api/v1/staff/members/{id}/reinstate", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"dateOfRejoining":"2026-07-15","systemAccess":"KITCHEN_STAFF",
						 "reason":"We need the help"}
						"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400136"));

		assertThat(admin.queryForObject(
				"SELECT employment_status FROM staff_profiles WHERE id = ?::uuid", String.class, id))
				.as("refused means refused — the employment must not have come back")
				.isEqualTo("TERMINATED");
		assertThat(admin.queryForObject("SELECT status FROM users WHERE id = ?", String.class, devotee))
				.as("and no sign-in came back with it")
				.isEqualTo("DISABLED");

		assertThat(countOf("STAFF_EMPLOYMENT_REINSTATED"))
				.as("no reinstatement happened, so nothing may be filed as one that did")
				.isZero();
		assertThat(countOf("STAFF_REINSTATEMENT_REJECTED"))
				.as("trying to bring back somebody the temple deliberately barred must not leave the log empty")
				.isEqualTo(1);

		Map<String, Object> event = admin.queryForMap("""
				SELECT entity_type, entity_id, reason,
				       before_state->>'employmentStatus' AS before_status,
				       after_state->>'employmentStatus' AS after_status,
				       after_state->>'dateOfRejoining' AS after_rejoined,
				       after_state->>'systemAccess' AS after_access
				FROM audit_events WHERE action = 'STAFF_REINSTATEMENT_REJECTED'
				""");
		assertThat(event.get("entity_type")).isEqualTo("STAFF_MEMBER");
		assertThat(event.get("entity_id")).hasToString(id);
		assertThat(event.get("before_status")).isEqualTo("TERMINATED");
		assertThat(event.get("after_status"))
				.as("what was asked for, not what happened — the point of recording a refusal")
				.isEqualTo("ACTIVE");
		assertThat(event.get("after_rejoined")).isEqualTo("2026-07-15");
		assertThat(event.get("after_access"))
				.as("what they were to have come back holding is part of what was attempted")
				.isEqualTo("KITCHEN_STAFF");
		assertThat(event.get("reason").toString()).contains("raised a record against");
	}

	/**
	 * The predicate, tested from the side that could have been got wrong.
	 *
	 * <p>{@code staffProfilesWithARecord()} selects {@code WHERE retracted_at IS NULL}. Had it
	 * counted retracted records too, this reinstatement would be refused — and a retraction is
	 * precisely the temple deciding the person may come back, so refusing afterwards would leave
	 * them barred by a record the platform has already stopped showing to anybody.
	 */
	@Test
	@DisplayName("a record already taken back does not stand in the way of taking the person back")
	void aRetractedRecordDoesNotRefuseTheReinstatement() throws Exception {
		String id = dismissedWithARecordRaised();
		UUID banId = admin.queryForObject(
				"SELECT id FROM employment_bans WHERE staff_profile_id = ?::uuid", UUID.class, id);

		mvc.perform(authed(post("/api/v1/staff/bans/{id}/retraction", banId))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"The money was found."}
						"""))
				.andExpect(status().isNoContent());

		mvc.perform(authed(post("/api/v1/staff/members/{id}/reinstate", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"dateOfRejoining":"2026-07-15","systemAccess":"KITCHEN_STAFF"}
						"""))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT employment_status FROM staff_profiles WHERE id = ?::uuid", String.class, id))
				.isEqualTo("ACTIVE");
		assertThat(countOf("STAFF_REINSTATEMENT_REJECTED"))
				.as("a record the temple has taken back is not a reason to refuse")
				.isZero();
	}

	/** Acceptance criterion 4, the act rather than the refusal. */
	@Test
	@DisplayName("the act is on the audit trail, with the day they came back and whether sign-in returned")
	void theActIsAudited() throws Exception {
		String id = hireId("""
				{"existingUserId":"%s","fullName":"Gopal Das","jobTitle":"HEAD_COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-02-01","systemAccess":"KITCHEN_STAFF"}
				""".formatted(devotee));
		mvc.perform(authed(post("/api/v1/staff/members/{id}/end-employment", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"status":"RESIGNED","lastWorkingDay":"2026-06-30","revokeSignIn":false}
						"""))
				.andExpect(status().isNoContent());

		mvc.perform(authed(post("/api/v1/staff/members/{id}/reinstate", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"dateOfRejoining":"2026-07-15","systemAccess":"KITCHEN_STAFF",
						 "reason":"Came back after the monsoon"}
						"""))
				.andExpect(status().isNoContent());

		assertThat(countOf("STAFF_EMPLOYMENT_REINSTATED")).isEqualTo(1);
		Map<String, Object> event = admin.queryForMap("""
				SELECT entity_type, entity_id, reason,
				       before_state->>'employmentStatus' AS before_status,
				       before_state->>'lastWorkingDay' AS before_last_day,
				       after_state->>'employmentStatus' AS after_status,
				       after_state->>'dateOfRejoining' AS after_rejoined,
				       after_state->>'systemAccess' AS after_access,
				       after_state->>'signInRestored' AS after_sign_in
				FROM audit_events WHERE action = 'STAFF_EMPLOYMENT_REINSTATED'
				""");
		assertThat(event.get("entity_type")).isEqualTo("STAFF_MEMBER");
		assertThat(event.get("entity_id")).hasToString(id);
		assertThat(event.get("before_status")).isEqualTo("RESIGNED");
		assertThat(event.get("before_last_day"))
				.as("the ending is cleared off the row, so the trail is the only place it survives")
				.isEqualTo("2026-06-30");
		assertThat(event.get("after_status")).isEqualTo("ACTIVE");
		// The whole of the dateOfRejoining decision rests on this row: no column holds it, and this
		// is what a temple asking "when did they come back?" reads a year later.
		assertThat(event.get("after_rejoined")).isEqualTo("2026-07-15");
		assertThat(event.get("after_access")).isEqualTo("KITCHEN_STAFF");
		assertThat(event.get("after_sign_in")).isEqualTo("true");
		assertThat(event.get("reason")).isEqualTo("Came back after the monsoon");
	}

	// ---------------------------------------------------------------------

	/** Dismissed with a B9 record raised against them, which is the only way one comes to exist. */
	private String dismissedWithARecordRaised() throws Exception {
		String id = hireId("""
				{"existingUserId":"%s","fullName":"Gopal Das","jobTitle":"HEAD_COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-02-01","systemAccess":"KITCHEN_STAFF"}
				""".formatted(devotee));
		mvc.perform(authed(post("/api/v1/staff/members/{id}/end-employment", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"status":"TERMINATED","lastWorkingDay":"2026-06-30","revokeSignIn":true,
						 "ban":{"category":"THEFT","account":"Took money from the donation box."}}
						"""))
				.andExpect(status().isNoContent());
		return id;
	}

	private MockHttpServletRequestBuilder hire(String json) {
		return authed(post("/api/v1/staff/members")).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private String hireId(String json) throws Exception {
		String body = mvc.perform(hire(json)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("id").asText();
	}

	/** Read with the owning connection, so what is asserted is what is actually in the table. */
	private int countOf(String action) {
		Integer count = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return count == null ? 0 : count;
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
