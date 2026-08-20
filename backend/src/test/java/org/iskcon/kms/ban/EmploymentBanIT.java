package org.iskcon.kms.ban;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The ban record and the check at hire, across two temples and a real database (B9).
 *
 * <p>Everything here needs both temples and the row policies to be genuinely in force, which is why
 * it runs through the whole stack as the unprivileged application role. A superuser bypasses RLS
 * entirely — {@code FORCE ROW LEVEL SECURITY} constrains a table's owner and nothing constrains a
 * superuser — so a version of this test that ran as one would pass while proving nothing at all.
 *
 * <p>The absence of a search endpoint is asserted deliberately, in its own test. It is the control
 * the whole design rests on, it is invisible in the code, and the way it will one day be lost is
 * somebody adding a convenient list endpoint and every other test still passing.
 */
@AutoConfigureMockMvc
@Import(EmploymentBanIT.StubVerifierConfiguration.class)
class EmploymentBanIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final String BENGALURU = "ISKCON South Bengaluru";
	private static final String MAYAPUR = "ISKCON Mayapur";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID bengaluru;
	private UUID mayapur;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		bengaluru = seedTemple("south-bengaluru", BENGALURU, "uid-bengaluru");
		mayapur = seedTemple("mayapur", MAYAPUR, "uid-mayapur");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM employment_bans");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM staff_schedule_exceptions");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---------------------------------------------------------------------

	@Test
	@DisplayName("a dismissal in one temple is found by the PAN when the person applies to another")
	void panFingerprintCrossesTempleBoundaries() throws Exception {
		signIn("uid-bengaluru");
		String dismissed = hireId("""
				{"fullName":"Ramesh Kumar","phone":"+919876500011","address":"12 MG Road, Bengaluru",
				 "jobTitle":"COOK","employmentType":"FULL_TIME","dateOfJoining":"2024-02-01",
				 "pan":"ABCDE1234F"}
				""");
		dismissWithBan(dismissed, "THEFT", "Took ₹18,000 from the donation box over three weeks.");

		// The same person at a different temple, giving a different name and a different number.
		// Only the PAN is the same, and only the PAN needs to be.
		signIn("uid-mayapur");
		JsonNode findings = expectFindings("""
				{"fullName":"R K Sharma","phone":"+919812300099","address":"Nabadwip",
				 "jobTitle":"COOK","employmentType":"FULL_TIME","dateOfJoining":"2026-08-01",
				 "pan":"ABCDE1234F"}
				""");

		assertThat(findings.get("findings").size()).isEqualTo(1);
		JsonNode finding = findings.get("findings").get(0);
		assertThat(finding.get("raisingTempleName").asText())
				.as("the raising temple is named, so this becomes a telephone call and not a verdict")
				.isEqualTo(BENGALURU);
		assertThat(finding.get("categoryLabel").asText()).isEqualTo("Theft or misappropriation");
		assertThat(finding.get("account").asText()).contains("donation box");
		assertThat(finding.get("bannedName").asText()).isEqualTo("Ramesh Kumar");
		assertThat(signalLabels(finding)).containsExactly("PAN");
		assertThat(finding.get("exact").asBoolean()).isTrue();

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM staff_profiles WHERE tenant_id = ?", Integer.class, mayapur))
				.as("findings come back before the hire, not after it")
				.isZero();
	}

	@Test
	@DisplayName("a changed phone and address are still flagged, by the fuzzy layer and not the exact one")
	void fuzzyLayerCatchesChangedDetails() throws Exception {
		signIn("uid-bengaluru");
		String dismissed = hireId("""
				{"fullName":"Ramesh Kumar","phone":"+919876500011","address":"12 MG Road, Bengaluru",
				 "jobTitle":"COOK","employmentType":"FULL_TIME","dateOfJoining":"2024-02-01"}
				""");
		dismissWithBan(dismissed, "FINANCIAL_IRREGULARITY", "Invoices raised for deliveries that never came.");

		signIn("uid-mayapur");
		JsonNode finding = expectFindings("""
				{"fullName":"Ramesh Kumar Singh","phone":"+919812300099","address":"44 Temple Street, Mayapur",
				 "jobTitle":"COOK","employmentType":"FULL_TIME","dateOfJoining":"2026-08-01"}
				""").get("findings").get(0);

		assertThat(signalLabels(finding))
				.as("nothing exact was available, so the name is the only thing that matched")
				.containsExactly("Name");
		assertThat(finding.get("exact").asBoolean())
				.as("a fuzzy finding must not be dressed up as a certainty")
				.isFalse();

		// And the person who merely shares a common surname is not flagged at all.
		mvc.perform(hire("""
				{"fullName":"Suresh Kumar","phone":"+919812300055",
				 "jobTitle":"COOK","employmentType":"FULL_TIME","dateOfJoining":"2026-08-01"}
				""")).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("a match never blocks: the hire completes, and what the admin decided is on the record")
	void aMatchNeverBlocks() throws Exception {
		signIn("uid-bengaluru");
		String dismissed = hireId("""
				{"fullName":"Ramesh Kumar","phone":"+919876500011","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2024-02-01","pan":"ABCDE1234F"}
				""");
		dismissWithBan(dismissed, "HARASSMENT", "Repeated abuse of a colleague, two written warnings.");

		signIn("uid-mayapur");
		String checkId = expectFindings("""
				{"fullName":"Ramesh Kumar","phone":"+919876500011","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-08-01","pan":"ABCDE1234F"}
				""").get("checkId").asText();

		// The admin has read the findings, telephoned Bengaluru, and decided to take the person on.
		String hired = hireId("""
				{"fullName":"Ramesh Kumar","phone":"+919876500011","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-08-01","pan":"ABCDE1234F",
				 "acknowledgedBanCheckId":"%s"}
				""".formatted(checkId));

		Map<String, Object> row = admin.queryForMap(
				"SELECT ban_check_decision, ban_check_findings, ban_check_id FROM staff_profiles WHERE id = ?::uuid",
				hired);
		assertThat(row.get("ban_check_decision"))
				.as("hired anyway is a legitimate answer, and it is recorded as one")
				.isEqualTo("PROCEEDED");
		assertThat(String.valueOf(row.get("ban_check_findings")))
				.as("frozen as it was shown — the record itself may be amended later")
				.contains(BENGALURU);
		assertThat(row.get("ban_check_id")).isNotNull();
	}

	@Test
	@DisplayName("a hire that found nothing still says so, against the hire and on the platform log")
	void nothingFoundIsStillRecorded() throws Exception {
		signIn("uid-mayapur");
		String hired = hireId("""
				{"fullName":"Priya Sharma","phone":"+919812300077","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-08-01"}
				""");

		assertThat(admin.queryForObject(
				"SELECT ban_check_decision FROM staff_profiles WHERE id = ?::uuid", String.class, hired))
				.isEqualTo("NO_FINDINGS");

		List<Map<String, Object>> checks = platformEvents("BAN_CHECK_RUN");
		assertThat(checks)
				.as("the query that found nothing is exactly the query somebody fishing would run")
				.hasSize(1);
		assertThat(String.valueOf(checks.get(0).get("after_state")))
				.contains("\"findings\": 0").contains("Priya Sharma");
	}

	@Test
	@DisplayName("temple B cannot retract temple A's record, and is told whose it is")
	void onlyTheRaisingTempleMayRetract() throws Exception {
		signIn("uid-bengaluru");
		String dismissed = hireId("""
				{"fullName":"Ramesh Kumar","phone":"+919876500011","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2024-02-01","pan":"ABCDE1234F"}
				""");
		dismissWithBan(dismissed, "THEFT", "Took stock from the store room.");
		UUID banId = onlyBan();

		signIn("uid-mayapur");
		mvc.perform(authed(post("/api/v1/staff/bans/{id}/retraction", banId)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-4307"));

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM employment_bans WHERE retracted_at IS NULL", Integer.class))
				.isEqualTo(1);

		// And the list a temple can see is its own, which for Mayapur is nothing.
		mvc.perform(authed(get("/api/v1/staff/bans")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("a retracted record stops appearing at a hire and stays on file")
	void retractionRemovesItFromHiresButNotFromHistory() throws Exception {
		signIn("uid-bengaluru");
		String dismissed = hireId("""
				{"fullName":"Ramesh Kumar","phone":"+919876500011","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2024-02-01","pan":"ABCDE1234F"}
				""");
		dismissWithBan(dismissed, "THEFT", "Money missing from the box.");
		UUID banId = onlyBan();

		mvc.perform(authed(post("/api/v1/staff/bans/{id}/retraction", banId))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"The money was found. We were wrong.\"}"))
				.andExpect(status().isNoContent());

		// Still there, with the retraction on it: the trail of a wrong entry is what makes it
		// correctable rather than deniable.
		Map<String, Object> row = admin.queryForMap(
				"SELECT retracted_at, retraction_reason FROM employment_bans WHERE id = ?", banId);
		assertThat(row.get("retracted_at")).isNotNull();
		assertThat(row.get("retraction_reason")).asString().contains("We were wrong");

		mvc.perform(authed(get("/api/v1/staff/bans")))
				.andExpect(jsonPath("$[0].retracted").value(true))
				.andExpect(jsonPath("$[0].account").value("Money missing from the box."));

		// And it is invisible to the next hire anywhere.
		signIn("uid-mayapur");
		mvc.perform(hire("""
				{"fullName":"Ramesh Kumar","phone":"+919876500011","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-08-01","pan":"ABCDE1234F"}
				""")).andExpect(status().isCreated());

		signIn("uid-bengaluru");
		mvc.perform(authed(post("/api/v1/staff/bans/{id}/retraction", banId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4965"));
	}

	@Test
	@DisplayName("a record older than the fade no longer appears at a hire")
	void bansFadeWithTime() throws Exception {
		signIn("uid-bengaluru");
		String dismissed = hireId("""
				{"fullName":"Ramesh Kumar","phone":"+919876500011","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2010-02-01","pan":"ABCDE1234F"}
				""");
		dismissWithBan(dismissed, "THEFT", "Took money from the box.");

		admin.update("UPDATE employment_bans SET raised_at = now() - interval '11 years'");

		signIn("uid-mayapur");
		mvc.perform(hire("""
				{"fullName":"Ramesh Kumar","phone":"+919876500011","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-08-01","pan":"ABCDE1234F"}
				"""))
				.andExpect(status().isCreated());

		// Not deleted. It simply stops being shown, and the raising temple can still see its own.
		signIn("uid-bengaluru");
		mvc.perform(authed(get("/api/v1/staff/bans"))).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	@DisplayName("there is no endpoint anywhere that lists or searches another temple's records")
	void thereIsNoLookupService() throws Exception {
		signIn("uid-bengaluru");
		String dismissed = hireId("""
				{"fullName":"Ramesh Kumar","phone":"+919876500011","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2024-02-01","pan":"ABCDE1234F"}
				""");
		dismissWithBan(dismissed, "THEFT", "Took money from the box.");
		UUID banId = onlyBan();

		signIn("uid-mayapur");

		// The obvious URLs somebody would reach for. None of them exists, and none of them should:
		// a query has to have a hire attempt behind it, or this becomes a background-check service
		// that any temple can run against anybody who ever worked in a kitchen.
		for (String url : List.of(
				"/api/v1/staff/bans/search?name=Ramesh",
				"/api/v1/staff/bans/all",
				"/api/v1/staff/ban-list",
				"/api/v1/bans",
				"/api/v1/staff/bans/" + banId,
				"/api/v1/staff/bans/" + banId + "/detail")) {
			int statusCode = mvc.perform(authed(get(url))).andReturn().getResponse().getStatus();
			assertThat(statusCode)
					.as("%s must not serve another temple's record", url)
					.isIn(403, 404, 405);
		}

		// The one list that does exist returns this temple's own records, which is none.
		mvc.perform(authed(get("/api/v1/staff/bans")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		// And the application role cannot read across the boundary even in raw SQL: the row policy,
		// not the endpoint list, is what makes that true.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM employment_bans WHERE tenant_id = ?", Integer.class, bengaluru))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("editing an existing staff record runs no check at all")
	void editingRunsNoCheck() throws Exception {
		signIn("uid-mayapur");
		String hired = hireId("""
				{"fullName":"Priya Sharma","phone":"+919812300077","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2026-08-01"}
				""");
		assertThat(platformEvents("BAN_CHECK_RUN")).hasSize(1);

		mvc.perform(authed(put("/api/v1/staff/members/{id}", hired))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"fullName":"Priya Sharma","phone":"+919812300088","jobTitle":"HEAD_COOK",
						 "employmentType":"FULL_TIME","dateOfJoining":"2026-08-01"}
						"""))
				.andExpect(status().isNoContent());

		assertThat(platformEvents("BAN_CHECK_RUN"))
				.as("correcting a phone number is not a hire and must not query the ban list")
				.hasSize(1);
	}

	@Test
	@DisplayName("a ban needs both a category and an account of what happened")
	void bothHalvesOfTheReasonAreRequired() throws Exception {
		signIn("uid-bengaluru");
		String dismissed = hireId("""
				{"fullName":"Ramesh Kumar","phone":"+919876500011","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2024-02-01"}
				""");

		mvc.perform(authed(post("/api/v1/staff/members/{id}/end-employment", dismissed))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"status":"TERMINATED","lastWorkingDay":"2026-08-15","revokeSignIn":true,
						 "ban":{"category":"THEFT","account":"   "}}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4010"));

		// And the other half of the same rule answers with the same code, in the same words.
		mvc.perform(authed(post("/api/v1/staff/members/{id}/end-employment", dismissed))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"status":"TERMINATED","lastWorkingDay":"2026-08-15","revokeSignIn":true,
						 "ban":{"account":"They took money from the box."}}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4010"));

		assertThat(admin.queryForObject("SELECT count(*) FROM employment_bans", Integer.class))
				.as("the whole dismissal rolls back — the two are one decision")
				.isZero();
		assertThat(admin.queryForObject(
				"SELECT employment_status FROM staff_profiles WHERE id = ?::uuid", String.class, dismissed))
				.isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("raising the record is on both audit logs, and the temple can never read the platform one")
	void raisingIsAuditedInBothPlaces() throws Exception {
		signIn("uid-bengaluru");
		String dismissed = hireId("""
				{"fullName":"Ramesh Kumar","phone":"+919876500011","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2024-02-01"}
				""");
		dismissWithBan(dismissed, "CHILD_SAFETY", "Reported to the local authorities on 3 August.");

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'EMPLOYMENT_BAN_RAISED'", Integer.class))
				.isEqualTo(1);
		assertThat(platformEvents("EMPLOYMENT_BAN_RAISED")).hasSize(1);

		// A temple admin writes to the platform log and can never read it back. That asymmetry is
		// the point of keeping a record intended to catch the person writing it.
		mvc.perform(authed(get("/api/v1/platform/audit"))).andExpect(status().is4xxClientError());
	}

	@Test
	@DisplayName("a second live record against the same person is refused; the retracted one is not in the way")
	void oneLiveRecordPerPersonPerTemple() throws Exception {
		signIn("uid-bengaluru");
		String dismissed = hireId("""
				{"fullName":"Ramesh Kumar","phone":"+919876500011","jobTitle":"COOK",
				 "employmentType":"FULL_TIME","dateOfJoining":"2024-02-01"}
				""");
		dismissWithBan(dismissed, "THEFT", "Money from the box.");

		// Ending an employment that has already ended is refused before the ban is even reached, so
		// the duplicate is provoked where a duplicate could actually arise: two rows, one person.
		assertThat(admin.queryForObject("SELECT count(*) FROM employment_bans", Integer.class)).isEqualTo(1);
		org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
				admin.update("""
						INSERT INTO employment_bans (tenant_id, staff_profile_id, raised_by_user_id,
							category, account, full_name, name_normalised, name_tokens)
						VALUES (?, ?::uuid, (SELECT id FROM users WHERE firebase_uid = 'uid-bengaluru'),
							'THEFT', 'again', 'Ramesh Kumar', 'ramesh kumar', ARRAY['ramesh','kumar'])
						""", bengaluru, dismissed));

		admin.update("UPDATE employment_bans SET retracted_at = now(), "
				+ "retracted_by_user_id = (SELECT id FROM users WHERE firebase_uid = 'uid-bengaluru')");

		admin.update("""
				INSERT INTO employment_bans (tenant_id, staff_profile_id, raised_by_user_id,
					category, account, full_name, name_normalised, name_tokens)
				VALUES (?, ?::uuid, (SELECT id FROM users WHERE firebase_uid = 'uid-bengaluru'),
					'THEFT', 'raised again after a retraction we regretted', 'Ramesh Kumar',
					'ramesh kumar', ARRAY['ramesh','kumar'])
				""", bengaluru, dismissed);

		assertThat(admin.queryForObject("SELECT count(*) FROM employment_bans", Integer.class))
				.as("both stay on file — that is what makes the earlier mistake visible")
				.isEqualTo(2);
	}

	// ---------------------------------------------------------------------

	private UUID seedTemple(String slug, String name, String uid) {
		UUID id = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Temple Admin', ?, '+919876500001', 'TEMPLE_ADMIN', 'ACTIVE')
				""", id, uid, uid + "@example.com");
		return id;
	}

	/** Ends an employment and raises a ban in the same act, as the termination panel does. */
	private void dismissWithBan(String staffId, String category, String account) throws Exception {
		mvc.perform(authed(post("/api/v1/staff/members/{id}/end-employment", staffId))
						.contentType(MediaType.APPLICATION_JSON).content("""
						{"status":"TERMINATED","lastWorkingDay":"2026-08-15","revokeSignIn":true,
						 "reason":"Dismissed.",
						 "ban":{"category":"%s","account":"%s"}}
						""".formatted(category, account)))
				.andExpect(status().isNoContent());
	}

	private JsonNode expectFindings(String json) throws Exception {
		MvcResult result = mvc.perform(hire(json)).andExpect(status().isOk()).andReturn();
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	private List<String> signalLabels(JsonNode finding) {
		return JSON.convertValue(finding.get("signalLabels"),
				JSON.getTypeFactory().constructCollectionType(List.class, String.class));
	}

	private UUID onlyBan() {
		return admin.queryForObject("SELECT id FROM employment_bans", UUID.class);
	}

	private List<Map<String, Object>> platformEvents(String action) {
		return admin.queryForList(
				"SELECT after_state, actor_label FROM platform_audit_events WHERE action = ?", action);
	}

	private MockHttpServletRequestBuilder hire(String json) {
		return authed(post("/api/v1/staff/members")).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private String hireId(String json) throws Exception {
		String body = mvc.perform(hire(json)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("id").asText();
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
