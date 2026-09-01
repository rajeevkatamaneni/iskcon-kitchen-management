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
import org.iskcon.kms.meal.MealKindService;
import org.iskcon.kms.tenancy.TenantContext;
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
 * Where the schedule is short of hands (E6-S15), through the full stack.
 *
 * <p>The kitchen is the same one {@code MealCrewIT} uses and for the same reason: a morning cook on
 * 06:00–14:00 and an evening cook on 14:00–22:00. Two people are in every day and no meal ever has
 * both, which is exactly the case a day-grain comparison gets wrong — <em>2 in</em> against a dinner
 * that needs 4 looks like a gap of two, and the gap is three.
 */
@AutoConfigureMockMvc
@Import(CrewCoverageIT.StubVerifierConfiguration.class)
class CrewCoverageIT extends AbstractIntegrationTest {

	/** A Monday. The templates below cover all seven days, so nothing turns on which day it is. */
	private static final String DATE = "2026-09-07";
	private static final String NEXT_DAY = "2026-09-08";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID khichdi;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		insertUser("uid-admin", "TEMPLE_ADMIN", "+919876500001");
		insertUser("uid-morning", "KITCHEN_STAFF", "+919876500002");
		insertUser("uid-evening", "KITCHEN_STAFF", "+919876500003");
		insertUser("uid-vol", "VOLUNTEER", "+919876500004");

		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id
				""", UUID.class, tenant);
		khichdi = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichdi', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, category);

		hire("uid-morning", "Morning Cook", "06:00", "14:00");
		hire("uid-evening", "Evening Cook", "14:00", "22:00");

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
		} finally {
			TenantContext.clear();
		}
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM meal_services");
		admin.execute("DELETE FROM meal_card_sequence");
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM shift_signups");
		admin.execute("DELETE FROM shifts");
		admin.execute("DELETE FROM staff_leave");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a day with nothing planned owes nobody, and is not drawn as covered either")
	void nothingPlannedIsItsOwnState() throws Exception {
		mvc.perform(authed(get("/api/v1/crew-coverage").param("from", DATE).param("to", DATE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].state").value("NOTHING_PLANNED"))
				.andExpect(jsonPath("$[0].shortBy").value(0))
				.andExpect(jsonPath("$[0].shortAt").doesNotExist())
				// The roster is still reported: two cooks are in whether or not anything is cooked.
				.andExpect(jsonPath("$[0].staffIn").value(2))
				.andExpect(jsonPath("$[0].volunteers").value(0));
	}

	@Test
	@DisplayName("a meal nobody has crewed reads 'not set', never 'covered' — null is not zero")
	void aMealWithNoCrewFigureIsNotCovered() throws Exception {
		plan(DATE, "Lunch", null);

		mvc.perform(authed(get("/api/v1/crew-coverage").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].state").value("CREW_NOT_SET"))
				.andExpect(jsonPath("$[0].shortBy").value(0));
	}

	@Test
	@DisplayName("the shortfall is the worst meal's, at that meal's grain — not the day's head count")
	void theShortfallIsPerMealAndNamesTheMeal() throws Exception {
		// Breakfast at 07:30 has the morning cook and asks for two: one short.
		plan(DATE, "Breakfast", 2);
		// Dinner at 19:30 has the evening cook and asks for four: three short, and the deeper gap.
		plan(DATE, "Dinner", 4);

		mvc.perform(authed(get("/api/v1/crew-coverage").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].state").value("SHORT"))
				// Two people are in that day. Neither meal has both, and the day is three short.
				.andExpect(jsonPath("$[0].staffIn").value(2))
				.andExpect(jsonPath("$[0].shortBy").value(3))
				.andExpect(jsonPath("$[0].shortAt").value("Dinner"))
				.andExpect(jsonPath("$[0].shortAtRequired").value(4))
				.andExpect(jsonPath("$[0].shortAtRostered").value(1));
	}

	@Test
	@DisplayName("a meal with the hands it asked for is covered, and never reads as surplus")
	void enoughHandsIsCoveredAndNotNegative() throws Exception {
		plan(DATE, "Lunch", 1);

		mvc.perform(authed(get("/api/v1/crew-coverage").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].state").value("COVERED"))
				.andExpect(jsonPath("$[0].shortBy").value(0))
				.andExpect(jsonPath("$[0].shortAt").doesNotExist());
	}

	@Test
	@DisplayName("a volunteer closes the gap the same way a cook does")
	void volunteersCountTowardsTheShortfall() throws Exception {
		plan(DATE, "Lunch", 2);

		UUID shift = admin.queryForObject("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by)
				VALUES (?, 'Lunch seva', ?::date, '11:00', '14:00', 4,
						(SELECT id FROM users WHERE firebase_uid = 'uid-admin'))
				RETURNING id
				""", UUID.class, tenant, DATE);
		admin.update("""
				INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id)
				VALUES (?, ?, (SELECT id FROM users WHERE firebase_uid = 'uid-vol'))
				""", tenant, shift);

		mvc.perform(authed(get("/api/v1/crew-coverage").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].state").value("COVERED"))
				.andExpect(jsonPath("$[0].shortBy").value(0))
				// Added for the shortfall, reported apart beside it: the manager still sees which
				// kind of person is standing in the kitchen.
				.andExpect(jsonPath("$[0].staffIn").value(2))
				.andExpect(jsonPath("$[0].volunteers").value(1));
	}

	@Test
	@DisplayName("every date in the range comes back, in order, including the empty ones")
	void everyDateComesBack() throws Exception {
		plan(NEXT_DAY, "Lunch", 4);

		mvc.perform(authed(get("/api/v1/crew-coverage").param("from", DATE).param("to", NEXT_DAY)))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].date").value(DATE))
				.andExpect(jsonPath("$[0].state").value("NOTHING_PLANNED"))
				.andExpect(jsonPath("$[1].date").value(NEXT_DAY))
				.andExpect(jsonPath("$[1].state").value("SHORT"))
				.andExpect(jsonPath("$[1].shortBy").value(3));
	}

	@Test
	@DisplayName("the day count agrees with /api/v1/workforce, because it is the same figure")
	void theRosterIsTheSameFigureTheGridFootShows() throws Exception {
		plan(DATE, "Lunch", 4);

		mvc.perform(authed(get("/api/v1/workforce").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].staffIn").value(2))
				.andExpect(jsonPath("$[0].volunteers").value(0));
		mvc.perform(authed(get("/api/v1/crew-coverage").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].staffIn").value(2))
				.andExpect(jsonPath("$[0].volunteers").value(0));
	}

	@Test
	@DisplayName("a range wider than a month is refused rather than answered slowly")
	void theRangeIsBounded() throws Exception {
		mvc.perform(authed(get("/api/v1/crew-coverage").param("from", DATE).param("to", "2027-09-07")))
				.andExpect(status().isBadRequest());
		mvc.perform(authed(get("/api/v1/crew-coverage").param("from", NEXT_DAY).param("to", DATE)))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("a cook cannot read the coverage — it is the roster's screen")
	void itIsBehindTheRosterPermission() throws Exception {
		// KITCHEN_STAFF plans meals and holds MANAGE_MEAL_PLANS, but not MANAGE_STAFF_SCHEDULE.
		signIn("uid-morning");
		mvc.perform(authed(get("/api/v1/crew-coverage").param("from", DATE).param("to", DATE)))
				.andExpect(status().isForbidden());
	}

	// ---- helpers ----------------------------------------------------------

	private void plan(String date, String kind, Integer crew) throws Exception {
		mvc.perform(authed(post("/api/v1/meal-plans"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"%s","mealKind":"%s","recipeId":"%s","targetYield":200,"adults":200,"crewRequired":%s}
								""".formatted(date, kind, khichdi, crew == null ? "null" : crew)))
				.andExpect(status().isCreated());
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	/** A cook with an employment record and the same hours every day of the week. */
	private UUID hire(String uid, String name, String start, String end) {
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
					VALUES (?, ?, ?, true, ?::time, ?::time)
					""", tenant, profile, day, start, end);
		}
		return profile;
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
