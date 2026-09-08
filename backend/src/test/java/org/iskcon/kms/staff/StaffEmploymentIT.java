package org.iskcon.kms.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.nullValue;
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
 * Hiring and employment (E6-S8), through the full stack.
 *
 * <p>The things worth proving here are the ones that only break in a real database: that hiring is
 * the only door into a temple role, that an employment record can exist without an account at all,
 * that a PAN is unreadable in the table and audited when read, and that a temple cannot be locked
 * out of itself by its last administrator resigning.
 */
@AutoConfigureMockMvc
@Import(StaffEmploymentIT.StubVerifierConfiguration.class)
class StaffEmploymentIT extends AbstractIntegrationTest {

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
		// A hire now runs the cross-temple ban check (B9), which lands on the platform log, and that
		// log holds a foreign key to the actor. Without this the users below refuse to go.
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM staff_schedule_exceptions");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("hiring a devotee promotes the account they already have rather than making a second one")
	void hiringPromotesTheExistingAccount() throws Exception {
		mvc.perform(hire("""
				{"existingUserId":"%s","fullName":"Gopal Das","jobTitle":"HEAD_COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-02-01","systemAccess":"KITCHEN_STAFF"}
				""".formatted(devotee))).andExpect(status().isCreated());

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM users WHERE email = 'gopal@example.com'", Integer.class))
				.as("one person, one account — a second would split their shift history in two")
				.isEqualTo(1);
		assertThat(admin.queryForObject(
				"SELECT role FROM users WHERE id = ?", String.class, devotee)).isEqualTo("KITCHEN_STAFF");

		mvc.perform(authed(get("/api/v1/staff/register")))
				.andExpect(jsonPath("$.current.length()").value(1))
				.andExpect(jsonPath("$.current[0].jobTitleLabel").value("Head Cook"))
				.andExpect(jsonPath("$.current[0].systemAccess").value("KITCHEN_STAFF"))
				.andExpect(jsonPath("$.former.length()").value(0));
	}

	@Test
	@DisplayName("a janitor is employed without any account at all")
	void hiredWithoutALogin() throws Exception {
		String id = hireId("""
				{"fullName":"Ramesh Kumar","phone":"+919876500061","jobTitle":"HOUSEKEEPING",
				 "employmentType":"PART_TIME","dateOfJoining":"2026-03-01"}
				""");

		assertThat(admin.queryForObject(
				"SELECT user_id FROM staff_profiles WHERE id = ?::uuid", UUID.class, id)).isNull();
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM users WHERE full_name = 'Ramesh Kumar'", Integer.class)).isZero();

		mvc.perform(authed(get("/api/v1/staff/register")))
				.andExpect(jsonPath("$.current[0].fullName").value("Ramesh Kumar"))
				.andExpect(jsonPath("$.current[0].systemAccess").doesNotExist());
	}

	@Test
	@DisplayName("access cannot be granted to someone we have no way of reaching")
	void accessNeedsAnAddress() throws Exception {
		mvc.perform(hire("""
				{"fullName":"No Contact","jobTitle":"COOK","employmentType":"FULL_TIME",
				 "dateOfJoining":"2026-03-01","systemAccess":"KITCHEN_STAFF"}
				"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400088"));
	}

	@Test
	@DisplayName("a title of OTHER without the temple's own words for it is refused")
	void otherTitleMustBeNamed() throws Exception {
		mvc.perform(hire("""
				{"fullName":"Someone","jobTitle":"OTHER","employmentType":"FULL_TIME","dateOfJoining":"2026-03-01"}
				"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));
	}

	@Test
	@DisplayName("a promotion grants the login the person never had, and is on the audit trail")
	void promotionCreatesTheLogin() throws Exception {
		String id = hireId("""
				{"fullName":"Lakshmi Devi","phone":"+919876500062","email":"lakshmi@example.com",
				 "jobTitle":"PRASADAM_SERVER","employmentType":"PART_TIME","dateOfJoining":"2026-01-10"}
				""");
		assertThat(admin.queryForObject("SELECT count(*) FROM users WHERE email = 'lakshmi@example.com'",
				Integer.class)).isZero();

		mvc.perform(authed(put("/api/v1/staff/members/{id}", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"fullName":"Lakshmi Devi","phone":"+919876500062","email":"lakshmi@example.com",
						 "jobTitle":"KITCHEN_MANAGER","employmentType":"FULL_TIME","dateOfJoining":"2026-01-10",
						 "systemAccess":"KITCHEN_STAFF"}
						"""))
				.andExpect(status().isNoContent());

		Map<String, Object> user = admin.queryForMap(
				"SELECT firebase_uid, role FROM users WHERE email = 'lakshmi@example.com'");
		assertThat(user.get("role")).isEqualTo("KITCHEN_STAFF");
		assertThat(user.get("firebase_uid").toString())
				.as("pending until they first sign in and claim it, like any pre-made account")
				.startsWith("pending:");

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'STAFF_UPDATED'", Integer.class))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("employment ends: the record survives, and the person goes back to being a devotee")
	void endingEmploymentKeepsThePerson() throws Exception {
		String id = hireId("""
				{"existingUserId":"%s","fullName":"Gopal Das","jobTitle":"COOK","employmentType":"FULL_TIME",
				 "dateOfJoining":"2026-02-01","systemAccess":"KITCHEN_STAFF"}
				""".formatted(devotee));

		mvc.perform(authed(post("/api/v1/staff/members/{id}/end-employment", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"status":"RESIGNED","lastWorkingDay":"2026-06-30",
						 "reason":"Moved to Mayapur","revokeSignIn":false}
						"""))
				.andExpect(status().isNoContent());

		assertThat(countOf("STAFF_EMPLOYMENT_ENDED"))
				.as("ending somebody else's employment is the ordinary act, and is filed as one")
				.isEqualTo(1);
		assertThat(countOf("STAFF_EMPLOYMENT_END_REJECTED"))
				.as("nothing was refused here, and a refusal beside a completed act would be a lie")
				.isZero();

		Map<String, Object> user = admin.queryForMap("SELECT role, status FROM users WHERE id = ?", devotee);
		assertThat(user.get("role")).as("still a devotee of this temple").isEqualTo("VOLUNTEER");
		assertThat(user.get("status")).isEqualTo("ACTIVE");

		mvc.perform(authed(get("/api/v1/staff/register")))
				.andExpect(jsonPath("$.current.length()").value(0))
				.andExpect(jsonPath("$.former.length()").value(1))
				// A former row wraps the profile, because it carries one thing a current row does not:
				// whether this temple raised a ban record about the person (B9).
				.andExpect(jsonPath("$.former[0].profile.employmentStatus").value("RESIGNED"))
				.andExpect(jsonPath("$.former[0].profile.endReason").value("Moved to Mayapur"))
				.andExpect(jsonPath("$.former[0].banned").value(false));

		// A past record is readable and not editable.
		mvc.perform(authed(put("/api/v1/staff/members/{id}", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"fullName":"Gopal Das","jobTitle":"HEAD_COOK","employmentType":"FULL_TIME",
						 "dateOfJoining":"2026-02-01"}
						"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400085"));
	}

	@Test
	@DisplayName("a dismissal can take the sign-in away outright")
	void dismissalRevokesSignIn() throws Exception {
		String id = hireId("""
				{"existingUserId":"%s","fullName":"Gopal Das","jobTitle":"COOK","employmentType":"FULL_TIME",
				 "dateOfJoining":"2026-02-01","systemAccess":"KITCHEN_STAFF"}
				""".formatted(devotee));

		mvc.perform(authed(post("/api/v1/staff/members/{id}/end-employment", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"status":"TERMINATED","lastWorkingDay":"2026-06-30","revokeSignIn":true}
						"""))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject("SELECT status FROM users WHERE id = ?", String.class, devotee))
				.isEqualTo("DISABLED");
	}

	@Test
	@DisplayName("an administrator cannot end their own employment and lock the temple out of itself")
	void cannotEndYourOwnEmployment() throws Exception {
		UUID adminId = admin.queryForObject(
				"SELECT id FROM users WHERE firebase_uid = 'uid-admin'", UUID.class);
		String ownRecord = hireId("""
				{"existingUserId":"%s","fullName":"Temple Admin","jobTitle":"TEMPLE_ADMINISTRATOR",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-01-01","systemAccess":"TEMPLE_ADMIN"}
				""".formatted(adminId));

		mvc.perform(authed(post("/api/v1/staff/members/{id}/end-employment", ownRecord))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"status":"RESIGNED","lastWorkingDay":"2026-06-30","revokeSignIn":true}
						"""))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-400024"));
	}

	/**
	 * The same refusal as the test above, and the word this one is about is <em>after</em>. The
	 * guard throws, the request's transaction rolls back, and the record of the attempt has to still
	 * be in the table — which is only true because that write goes out on a transaction of its own.
	 * An audit written inside the rolled-back one would leave an administrator's attempt to end
	 * their own employment with no trace anywhere, which is the state this door was in until T-046.
	 *
	 * <p>Through the endpoint rather than the service for exactly that reason: a direct service call
	 * would have no committed transaction to roll back and would prove nothing.
	 *
	 * <p>The pair of counts is the proof, not either one alone. Zero {@code STAFF_EMPLOYMENT_ENDED}
	 * says the request's own transaction really did roll back; one
	 * {@code STAFF_EMPLOYMENT_END_REJECTED} says the refusal survived it. One write gone and one
	 * write kept, out of the same request.
	 */
	@Test
	@DisplayName("a refused attempt to end one's own employment is on the audit trail after the 403")
	void refusedSelfEmploymentEndIsRecorded() throws Exception {
		UUID adminId = admin.queryForObject(
				"SELECT id FROM users WHERE firebase_uid = 'uid-admin'", UUID.class);
		String ownRecord = hireId("""
				{"existingUserId":"%s","fullName":"Temple Admin","jobTitle":"TEMPLE_ADMINISTRATOR",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-01-01","systemAccess":"TEMPLE_ADMIN"}
				""".formatted(adminId));

		// The whole locked-out-of-itself case: the last administrator resigning and taking their own
		// sign-in away with them.
		mvc.perform(authed(post("/api/v1/staff/members/{id}/end-employment", ownRecord))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"status":"RESIGNED","lastWorkingDay":"2026-06-30",
						 "reason":"Moving to Mayapur","revokeSignIn":true}
						"""))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-400024"));

		Map<String, Object> self = admin.queryForMap(
				"SELECT role, status FROM users WHERE id = ?", adminId);
		assertThat(self.get("role")).as("the temple still has an administrator").isEqualTo("TEMPLE_ADMIN");
		assertThat(self.get("status")).as("and they can still sign in").isEqualTo("ACTIVE");
		assertThat(admin.queryForObject(
				"SELECT employment_status FROM staff_profiles WHERE id = ?::uuid", String.class, ownRecord))
				.as("the employment did not end")
				.isEqualTo("ACTIVE");

		assertThat(countOf("STAFF_EMPLOYMENT_ENDED"))
				.as("no employment ended, so nothing may be filed as one that did")
				.isZero();
		assertThat(countOf("STAFF_EMPLOYMENT_END_REJECTED"))
				.as("an administrator trying to end their own employment must not leave the log empty")
				.isEqualTo(1);

		Map<String, Object> event = admin.queryForMap("""
				SELECT actor_user_id, entity_type, entity_id, reason,
				       before_state->>'employmentStatus' AS before_status,
				       after_state->>'employmentStatus' AS after_status,
				       after_state->>'signInRevoked' AS after_revoked
				FROM audit_events WHERE action = 'STAFF_EMPLOYMENT_END_REJECTED'
				""");
		assertThat(event.get("actor_user_id")).hasToString(adminId.toString());
		assertThat(event.get("entity_type"))
				.as("filed against the employment record, exactly as the act it refused would have been")
				.isEqualTo("STAFF_MEMBER");
		assertThat(event.get("entity_id")).hasToString(ownRecord);
		assertThat(event.get("before_status")).isEqualTo("ACTIVE");
		assertThat(event.get("after_status"))
				.as("what was asked for, not what happened — the point of recording a refusal")
				.isEqualTo("RESIGNED");
		assertThat(event.get("after_revoked"))
				.as("an attempt to take one's own sign-in away is not the same attempt as resigning")
				.isEqualTo("true");
		assertThat(event.get("reason").toString())
				.as("a reader has to see what was attempted, not merely that something was")
				.contains("their own employment").contains(ownRecord);
	}

	/**
	 * The point of this one is the word <em>after</em>. The refusal throws, the request's transaction
	 * rolls back, and the record of the attempt has to still be there — which is only true because
	 * the write goes out on its own transaction. An audit written inside the rolled-back one would
	 * leave a blocked escalation attempt with no trace at all, which is the state this door was in.
	 *
	 * <p>Through the endpoint rather than the service for exactly that reason: a service call would
	 * not have a transaction to roll back and would prove nothing.
	 */
	@Test
	@DisplayName("a refused self role change is on the audit trail after the 403, not rolled back with it")
	void refusedSelfAccessChangeIsRecorded() throws Exception {
		UUID adminId = admin.queryForObject(
				"SELECT id FROM users WHERE firebase_uid = 'uid-admin'", UUID.class);
		String ownRecord = hireId("""
				{"existingUserId":"%s","fullName":"Temple Admin","phone":"+919876500001",
				 "email":"admin@example.com","jobTitle":"TEMPLE_ADMINISTRATOR","employmentType":"FULL_TIME",
				 "dateOfJoining":"2026-01-01","systemAccess":"TEMPLE_ADMIN"}
				""".formatted(adminId));

		// systemAccess omitted — the admin withdrawing their own access, which would leave the temple
		// with nobody holding MANAGE_STAFF to put it back.
		mvc.perform(authed(put("/api/v1/staff/members/{id}", ownRecord))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"fullName":"Temple Admin","phone":"+919876500001","email":"admin@example.com",
						 "jobTitle":"TEMPLE_ADMINISTRATOR","employmentType":"FULL_TIME",
						 "dateOfJoining":"2026-01-01"}
						"""))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-400022"));

		assertThat(admin.queryForObject("SELECT role FROM users WHERE id = ?", String.class, adminId))
				.as("the refusal left the role alone")
				.isEqualTo("TEMPLE_ADMIN");
		assertThat(countOf("STAFF_UPDATED"))
				.as("nothing was edited, so nothing is recorded as edited")
				.isZero();

		assertThat(countOf("ROLE_CHANGE_REJECTED"))
				.as("somebody trying to change their own access must not leave the log empty")
				.isEqualTo(1);

		Map<String, Object> event = admin.queryForMap("""
				SELECT actor_user_id, entity_type, entity_id, reason,
				       before_state->>'role' AS before_role, after_state->>'role' AS after_role
				FROM audit_events WHERE action = 'ROLE_CHANGE_REJECTED'
				""");
		assertThat(event.get("actor_user_id")).hasToString(adminId.toString());
		assertThat(event.get("entity_type")).isEqualTo("USER");
		assertThat(event.get("entity_id")).hasToString(adminId.toString());
		assertThat(event.get("before_role")).isEqualTo("TEMPLE_ADMIN");
		assertThat(event.get("after_role"))
				.as("what they were reaching for — no access at all, recorded rather than left blank")
				.isEqualTo("NONE");
		assertThat(event.get("reason").toString())
				.as("a reader has to be able to see what was attempted, not just that something was")
				.contains("their own access").contains(ownRecord);
	}

	/**
	 * The staff form is the only door a temple role actually changes through, so it has to be the
	 * thing that writes ROLE_CHANGED. Two events, deliberately: the profile edit and, beside it, the
	 * privilege change on its own — otherwise a promotion is filed under STAFF_UPDATED alongside a
	 * corrected phone number, and the audit log's "Role changed" filter matches nothing for ever.
	 */
	@Test
	@DisplayName("a promotion writes a role change of its own, beside the record of the profile edit")
	void anAccessChangeIsAuditedAsARoleChange() throws Exception {
		String id = hireId("""
				{"existingUserId":"%s","fullName":"Gopal Das","phone":"+919876500071",
				 "email":"gopal@example.com","jobTitle":"COOK","employmentType":"FULL_TIME",
				 "dateOfJoining":"2026-02-01","systemAccess":"KITCHEN_STAFF"}
				""".formatted(devotee));

		mvc.perform(authed(put("/api/v1/staff/members/{id}", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"fullName":"Gopal Das","phone":"+919876500071","email":"gopal@example.com",
						 "jobTitle":"KITCHEN_MANAGER","employmentType":"FULL_TIME","dateOfJoining":"2026-02-01",
						 "systemAccess":"KITCHEN_MANAGER"}
						"""))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject("SELECT role FROM users WHERE id = ?", String.class, devotee))
				.isEqualTo("KITCHEN_MANAGER");
		assertThat(countOf("STAFF_UPDATED"))
				.as("the whole-profile edit is still recorded, unchanged")
				.isEqualTo(1);
		assertThat(countOf("ROLE_CHANGED"))
				.as("and the privilege change is findable on its own")
				.isEqualTo(1);

		Map<String, Object> event = admin.queryForMap("""
				SELECT entity_type, entity_id,
				       before_state->>'role' AS before_role, after_state->>'role' AS after_role
				FROM audit_events WHERE action = 'ROLE_CHANGED'
				""");
		assertThat(event.get("entity_type"))
				.as("filed against the account whose privileges moved, not the employment record")
				.isEqualTo("USER");
		assertThat(event.get("entity_id")).hasToString(devotee.toString());
		assertThat(event.get("before_role")).isEqualTo("KITCHEN_STAFF");
		assertThat(event.get("after_role")).isEqualTo("KITCHEN_MANAGER");
	}

	@Test
	@DisplayName("correcting a phone number is not a role change")
	void anEditThatLeavesAccessAloneWritesNoRoleChange() throws Exception {
		String id = hireId("""
				{"existingUserId":"%s","fullName":"Gopal Das","phone":"+919876500071",
				 "email":"gopal@example.com","jobTitle":"COOK","employmentType":"FULL_TIME",
				 "dateOfJoining":"2026-02-01","systemAccess":"KITCHEN_STAFF"}
				""".formatted(devotee));

		mvc.perform(authed(put("/api/v1/staff/members/{id}", id))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"fullName":"Gopal Das","phone":"+919876500072","email":"gopal@example.com",
						 "jobTitle":"COOK","employmentType":"FULL_TIME","dateOfJoining":"2026-02-01",
						 "systemAccess":"KITCHEN_STAFF"}
						"""))
				.andExpect(status().isNoContent());

		assertThat(countOf("STAFF_UPDATED")).isEqualTo(1);
		assertThat(countOf("ROLE_CHANGED"))
				.as("nobody's privileges moved, so the role-change filter must not show this edit")
				.isZero();
	}

	@Test
	@DisplayName("a PAN is unreadable in the table, and reading it is recorded")
	void panIsEncryptedAndItsReadingAudited() throws Exception {
		String id = hireId("""
				{"fullName":"Ravi Das","phone":"+919876500063","jobTitle":"ACCOUNTANT",
				 "employmentType":"CONTRACT","dateOfJoining":"2026-04-01","pan":"abcde1234f"}
				""");

		byte[] stored = admin.queryForObject(
				"SELECT pan_ciphertext FROM staff_profiles WHERE id = ?::uuid", byte[].class, id);
		assertThat(new String(stored))
				.as("the database must never hold the clear value")
				.doesNotContain("ABCDE1234F").doesNotContain("abcde1234f");

		mvc.perform(authed(get("/api/v1/staff/register")))
				.andExpect(jsonPath("$.current[0].panLast4").value("234F"));

		mvc.perform(authed(get("/api/v1/staff/members/{id}/pan", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pan").value("ABCDE1234F"));

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'STAFF_PAN_VIEWED'", Integer.class))
				.as("reading somebody's tax number is never silent")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("a malformed PAN never reaches the database")
	void malformedPanRefused() throws Exception {
		mvc.perform(hire("""
				{"fullName":"Ravi Das","jobTitle":"ACCOUNTANT","employmentType":"CONTRACT",
				 "dateOfJoining":"2026-04-01","pan":"NOTAPAN"}
				"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));
	}

	@Test
	@DisplayName("the job-title picklist is served with the access each title suggests")
	void jobTitlesAreServed() throws Exception {
		mvc.perform(authed(get("/api/v1/staff/job-titles")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.value=='HEAD_COOK')].suggestedAccess").value("KITCHEN_STAFF"))
				.andExpect(jsonPath("$[?(@.value=='TEMPLE_ADMINISTRATOR')].suggestedAccess").value("TEMPLE_ADMIN"))
				// A driver needs no app account, and the form should not pre-select one for them.
				.andExpect(jsonPath("$[?(@.value=='DRIVER')].suggestedAccess")
						.value(everyItem(nullValue())))
				.andExpect(jsonPath("$[?(@.value=='UNRECORDED')]").doesNotExist());
	}

	@Test
	@DisplayName("kitchen staff cannot see or change the register")
	void registerIsAdminOnly() throws Exception {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-cook', 'A Cook', 'cook@example.com', '+919876500064', 'KITCHEN_STAFF', 'ACTIVE')
				""", tenant);
		signIn("uid-cook");

		mvc.perform(authed(get("/api/v1/staff/register"))).andExpect(status().isForbidden());
		mvc.perform(hire("""
				{"fullName":"Someone","jobTitle":"COOK","employmentType":"FULL_TIME","dateOfJoining":"2026-03-01"}
				""")).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

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
