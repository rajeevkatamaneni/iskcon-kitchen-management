package org.iskcon.kms.kitchen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A kitchen starts planning its own meals, and the requests already in flight are settled (E10-S4).
 *
 * <p>This is the one act in Epic 10 that destroys somebody's work without asking twice, so every row
 * of the design's table gets its own test, including the two states Rajeev's own sentence did not
 * name — a submitted request, which is answered like an approved one, and an issued request, which
 * is left alone because the food has already gone.
 *
 * <p>The dates are the whole rule, so they are set explicitly rather than defaulted: the temple's
 * today in Asia/Kolkata is the line, and one day either side of it decides whether a request is
 * history or in flight.
 */
@AutoConfigureMockMvc
@Import(MealPlannerAdoptionIT.StubVerifierConfiguration.class)
class MealPlannerAdoptionIT extends AbstractIntegrationTest {

	private static final ZoneId TEMPLE_TIME = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID temple;
	private UUID adminUser;
	private UUID staffUser;
	private UUID kitchen;
	private LocalDate today;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		today = LocalDate.now(TEMPLE_TIME);

		temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('adoption', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		adminUser = insertUser("uid-admin", "admin@example.com", "TEMPLE_ADMIN");
		staffUser = insertUser("uid-staff", "staff@example.com", "KITCHEN_STAFF");

		kitchen = admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, is_main, uses_meal_planner, created_by)
				VALUES (?, 'Sweets kitchen', false, false, ?) RETURNING id
				""", UUID.class, temple, adminUser);

		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredient_request_events");
		admin.execute("DELETE FROM ingredient_requests");
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("the screen is told what it is about to destroy before it destroys it")
	void previewCountsBeforeAnythingMoves() throws Exception {
		request("IR-1", "DRAFT", today.plusDays(1));
		request("IR-2", "DRAFT", today);
		request("IR-3", "SUBMITTED", today.plusDays(2));
		request("IR-4", "APPROVED", today.plusDays(3));
		request("IR-5", "DRAFT", today.minusDays(1));

		// Three drafts, including the past-dated one: what the screen warns about has to be what
		// actually happens, or the confirmation is a lie the first time somebody reads it.
		mvc.perform(authed(get("/api/v1/kitchens/{id}/meal-planner-impact", kitchen)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.draftsDeleted").value(3))
				.andExpect(jsonPath("$.requestsDenied").value(2));

		// Asking must not answer. Everything is still exactly where it was.
		assertThat(statusOf("IR-1")).isEqualTo("DRAFT");
		assertThat(statusOf("IR-3")).isEqualTo("SUBMITTED");
		assertThat(statusOf("IR-4")).isEqualTo("APPROVED");
	}

	@Test
	@DisplayName("every draft goes, whatever date it carries — a draft holds no history")
	void everyDraftIsDeleted() throws Exception {
		request("IR-TOMORROW", "DRAFT", today.plusDays(1));
		request("IR-TODAY", "DRAFT", today);
		request("IR-YESTERDAY", "DRAFT", today.minusDays(1));
		request("IR-LAST-MONTH", "DRAFT", today.minusDays(40));

		turnTheMealPlannerOn();

		// The first version of this kept the past-dated ones, on the same reasoning that protects a
		// submitted or approved request. It does not hold for a draft: nobody has answered it,
		// nothing was issued against it, and its date is not a fact about the past but a field its
		// author can still edit — so filtering on that date filters on something they can change.
		assertThat(exists("IR-TOMORROW")).isFalse();
		assertThat(exists("IR-TODAY")).isFalse();
		assertThat(exists("IR-YESTERDAY")).isFalse();
		assertThat(exists("IR-LAST-MONTH")).isFalse();
	}

	@Test
	@DisplayName("a submitted or approved request from before today is still left alone")
	void answeredWorkFromThePastIsHistory() throws Exception {
		// The date still decides for these two, and must: somebody asked, somebody answered, and
		// what happened last week happened.
		request("IR-OLD-SUBMITTED", "SUBMITTED", today.minusDays(3));
		request("IR-OLD-APPROVED", "APPROVED", today.minusDays(5));

		turnTheMealPlannerOn();

		assertThat(statusOf("IR-OLD-SUBMITTED")).isEqualTo("SUBMITTED");
		assertThat(statusOf("IR-OLD-APPROVED")).isEqualTo("APPROVED");
	}

	@Test
	@DisplayName("a submitted request is denied — the answer to a question nobody can now grant is no")
	void submittedIsDenied() throws Exception {
		request("IR-SUBMITTED", "SUBMITTED", today.plusDays(1));

		turnTheMealPlannerOn();

		assertThat(statusOf("IR-SUBMITTED")).isEqualTo("DENIED");
	}

	@Test
	@DisplayName("an approved request is denied, in the name of whoever turned the planner on")
	void approvedIsDeniedAndCarriesAName() throws Exception {
		request("IR-APPROVED", "APPROVED", today.plusDays(1));

		turnTheMealPlannerOn();

		Map<String, Object> row = row("IR-APPROVED");
		assertThat(row.get("status")).isEqualTo("DENIED");
		assertThat(row.get("decided_by")).isEqualTo(adminUser);
		assertThat(row.get("decided_at")).isNotNull();
		// An automatic denial nobody's name is on is one nobody can be asked about.
		assertThat(String.valueOf(row.get("decision_note")))
				.contains("Sweets kitchen")
				.contains("meal planner");
	}

	@Test
	@DisplayName("a request already denied is left exactly as it was, note and all")
	void deniedIsUntouched() throws Exception {
		request("IR-DENIED", "DENIED", today.plusDays(1));
		admin.update("UPDATE ingredient_requests SET decision_note = 'Too much ghee' WHERE reference = 'IR-DENIED'");

		turnTheMealPlannerOn();

		Map<String, Object> row = row("IR-DENIED");
		assertThat(row.get("status")).isEqualTo("DENIED");
		assertThat(row.get("decision_note")).isEqualTo("Too much ghee");
	}

	@Test
	@DisplayName("an issued request is untouched — the food has left the shelf and cannot be unissued")
	void issuedIsUntouched() throws Exception {
		request("IR-ISSUED", "ISSUED", today.plusDays(1));

		turnTheMealPlannerOn();

		// Not in Rajeev's sentence, and deliberately so: reversing this would mean writing
		// compensating stock movements for food that is already cooked.
		assertThat(statusOf("IR-ISSUED")).isEqualTo("ISSUED");
	}

	@Test
	@DisplayName("every row it touches is audited, and every row it does not is not")
	void everythingItTouchesIsAudited() throws Exception {
		request("IR-DRAFT", "DRAFT", today.plusDays(1));
		request("IR-SUBMITTED", "SUBMITTED", today.plusDays(1));
		request("IR-OLD", "DRAFT", today.minusDays(2));
		request("IR-ISSUED", "ISSUED", today.plusDays(1));

		turnTheMealPlannerOn();

		// A permanent delete that leaves no trace is what the audit log exists to prevent, and this
		// one is not even the author's own doing. Two drafts now, since the past-dated one goes too.
		assertThat(auditCount("INGREDIENT_REQUEST_DELETED")).isEqualTo(2);
		assertThat(auditCount("INGREDIENT_REQUEST_DENIED")).isEqualTo(1);
		assertThat(auditCount("KITCHEN_JOINED_MEAL_PLANNER")).isEqualTo(1);
	}

	@Test
	@DisplayName("the kitchen can no longer be asked for anything once it plans its own meals")
	void theDoorCloses() throws Exception {
		turnTheMealPlannerOn();

		signIn("uid-staff");
		mvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post("/api/v1/ingredient-requests")
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("""
						{"kitchenId":"%s","neededOn":"%s","purpose":"Sunday feast",
						 "lines":[],"dishes":[]}
						""".formatted(kitchen, today.plusDays(1)))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4976"));
	}

	@Test
	@DisplayName("turning it off again opens the door and un-denies nothing")
	void leavingRestoresTheDoorButNotThePast() throws Exception {
		request("IR-APPROVED", "APPROVED", today.plusDays(1));
		turnTheMealPlannerOn();
		assertThat(statusOf("IR-APPROVED")).isEqualTo("DENIED");

		mvc.perform(authed(put("/api/v1/kitchens/{id}", kitchen))
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("""
						{"name":"Sweets kitchen","isMain":false,"usesMealPlanner":false}
						"""))
				.andExpect(status().isNoContent());

		// A denial was an answer somebody gave. Reopening the door does not unsay it.
		assertThat(statusOf("IR-APPROVED")).isEqualTo("DENIED");
		assertThat(auditCount("KITCHEN_LEFT_MEAL_PLANNER")).isEqualTo(1);
	}

	// ---------------------------------------------------------------------

	private void turnTheMealPlannerOn() throws Exception {
		mvc.perform(authed(put("/api/v1/kitchens/{id}", kitchen))
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content("""
						{"name":"Sweets kitchen","isMain":false,"usesMealPlanner":true}
						"""))
				.andExpect(status().isNoContent());
	}

	private void request(String reference, String status, LocalDate neededOn) {
		admin.update("""
				INSERT INTO ingredient_requests
					(tenant_id, reference, kitchen_id, needed_on, purpose, status, requested_by)
				VALUES (?, ?, ?, ?, 'Festival sweets', ?, ?)
				""", temple, reference, kitchen, neededOn, status, staffUser);
	}

	private Map<String, Object> row(String reference) {
		return admin.queryForMap(
				"SELECT * FROM ingredient_requests WHERE reference = ?", reference);
	}

	private String statusOf(String reference) {
		return admin.queryForObject(
				"SELECT status FROM ingredient_requests WHERE reference = ?", String.class, reference);
	}

	private boolean exists(String reference) {
		Integer count = admin.queryForObject(
				"SELECT count(*) FROM ingredient_requests WHERE reference = ?", Integer.class, reference);
		return count != null && count > 0;
	}

	private int auditCount(String action) {
		Integer count = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return count == null ? 0 : count;
	}

	private UUID insertUser(String uid, String email, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE') RETURNING id
				""", UUID.class, temple, uid, email, role);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	@TestConfiguration
	static class StubVerifierConfiguration {

		@Bean
		@Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
		}
	}

	static class StubTokenVerifier implements TokenVerifier {

		private final Map<String, TokenVerifier.VerifiedSubject> accepted = new HashMap<>();

		void accept(String uid) {
			accepted.put("valid-token", new TokenVerifier.VerifiedSubject(uid, uid + "@example.com", "+919000000000"));
		}

		void reset() {
			accepted.clear();
		}

		@Override
		public TokenVerifier.VerifiedSubject verify(String idToken) throws TokenVerifier.InvalidTokenException {
			TokenVerifier.VerifiedSubject subject = accepted.get(idToken);
			if (subject == null) {
				throw new TokenVerifier.InvalidTokenException("Unrecognised token");
			}
			return subject;
		}
	}
}
