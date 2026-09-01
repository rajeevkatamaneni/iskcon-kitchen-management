package org.iskcon.kms.meal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
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
 * How many people a meal takes and how many it has (items 19 and 24), through the full stack.
 *
 * <p>The kitchen here is deliberately split across the day: a morning cook on 06:00–14:00 and an
 * evening cook on 14:00–22:00. Two people are in every day, and no meal ever has both of them. That
 * is the whole case for asking the question per meal rather than per day — <em>Working today · 2</em>
 * is true and useless, and dinner still only has one pair of hands.
 */
@AutoConfigureMockMvc
@Import(MealCrewIT.StubVerifierConfiguration.class)
class MealCrewIT extends AbstractIntegrationTest {

	/** A Monday. The templates below cover all seven days, so nothing turns on which day it is. */
	private static final String DATE = "2026-09-07";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@MockBean
	private Scheduler scheduler; // no-op enqueue, so a leave decision notice is recorded rather than sent

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID khichdi;
	private UUID morningCook;

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

		morningCook = hire("uid-morning", "Morning Cook", "06:00", "14:00");
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
		// A leave decision queues a notice at the person it was about, and that row holds a foreign
		// key into users. Left behind, the next class inherits a temple it did not create.
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a person counts for a meal only if their working window covers its ready-by time")
	void theCountHasAMealGrain() throws Exception {
		plan("Breakfast", 200, null);
		plan("Lunch", 400, null);
		plan("Dinner", 200, null);

		// Both cooks are in all day by the day-grain reckoning, and that is exactly the figure that
		// cannot answer the question.
		mvc.perform(authed(get("/api/v1/workforce").param("from", DATE).param("to", DATE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].staffIn").value(2));

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				// 07:30 — the morning cook, and only her.
				.andExpect(jsonPath("$[0].mealKind").value("Breakfast"))
				.andExpect(jsonPath("$[0].staffIn").value(1))
				// 12:00 — still hers. The evening cook does not start for two hours.
				.andExpect(jsonPath("$[1].mealKind").value("Lunch"))
				.andExpect(jsonPath("$[1].staffIn").value(1))
				// 19:30 — his, and the morning cook went home five hours ago.
				.andExpect(jsonPath("$[2].mealKind").value("Dinner"))
				.andExpect(jsonPath("$[2].staffIn").value(1));
	}

	@Test
	@DisplayName("a volunteer shift falls to the meal it covers, without anybody linking it to one")
	void volunteersAreJudgedByTheirShiftWindow() throws Exception {
		plan("Breakfast", 200, 2);
		plan("Lunch", 400, 4);

		// Posted 11:00–14:00 and never associated with a meal. It lands on lunch because that is when
		// the volunteer is standing in the kitchen.
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

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].mealKind").value("Breakfast"))
				.andExpect(jsonPath("$[0].volunteers").value(0))
				.andExpect(jsonPath("$[0].rostered").value(1))
				.andExpect(jsonPath("$[0].crewRequired").value(2))
				.andExpect(jsonPath("$[0].shortOfCrew").value(true))
				// One cook and one volunteer against a plan of four: 2 of 4.
				.andExpect(jsonPath("$[1].mealKind").value("Lunch"))
				.andExpect(jsonPath("$[1].staffIn").value(1))
				.andExpect(jsonPath("$[1].volunteers").value(1))
				.andExpect(jsonPath("$[1].rostered").value(2))
				.andExpect(jsonPath("$[1].crewRequired").value(4))
				.andExpect(jsonPath("$[1].shortOfCrew").value(true));
	}

	@Test
	@DisplayName("a meal nobody has crewed is not short of anything — null is not zero")
	void noPlannedCrewIsNeverAShortfall() throws Exception {
		plan("Lunch", 400, null);

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].crewRequired").doesNotExist())
				.andExpect(jsonPath("$[0].rostered").value(1))
				.andExpect(jsonPath("$[0].shortOfCrew").value(false));
	}

	@Test
	@DisplayName("a crew short of hands still saves — a meal is planned weeks before anybody is rostered")
	void beingShortNeverBlocksSaving() throws Exception {
		// Twelve people for a lunch with one cook rostered. Accepted without comment: the roster for
		// September is not written in August, and a planner refused here would stop using the field.
		mvc.perform(createRequest("""
				{"planDate":"%s","mealKind":"Lunch","recipeId":"%s","targetYield":400,"adults":400,
				 "crewRequired":12}
				""".formatted(DATE, khichdi)))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("the default is the median of the last three ordinary meals of that kind")
	void theDefaultIsTheMedianOfThree() throws Exception {
		// Nothing cooked yet: the field opens empty rather than at a guess.
		mvc.perform(authed(get("/api/v1/meal-crew/suggested").param("mealKind", "Lunch")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.crewRequired").doesNotExist());

		// One ordinary lunch: it is the default on its own.
		planOn("2026-08-03", "Lunch", 6);
		mvc.perform(authed(get("/api/v1/meal-crew/suggested").param("mealKind", "Lunch")))
				.andExpect(jsonPath("$.crewRequired").value(6));

		// Two: the mean, rounded up. 6 and 9 make 8, not 7.
		planOn("2026-08-04", "Lunch", 9);
		mvc.perform(authed(get("/api/v1/meal-crew/suggested").param("mealKind", "Lunch")))
				.andExpect(jsonPath("$.crewRequired").value(8));

		// Three, one of them an unusual ordinary day — a wedding party that took twenty. The middle
		// value throws it out where the most recent meal would have enshrined it.
		planOn("2026-08-05", "Lunch", 20);
		mvc.perform(authed(get("/api/v1/meal-crew/suggested").param("mealKind", "Lunch")))
				.andExpect(jsonPath("$.crewRequired").value(9));

		// A kind the temple has cooked, but never with a crew figure, is still empty.
		mvc.perform(authed(get("/api/v1/meal-crew/suggested").param("mealKind", "Dinner")))
				.andExpect(jsonPath("$.crewRequired").doesNotExist());
	}

	@Test
	@DisplayName("a festival lunch never sets the default for an ordinary one")
	void festivalsAreLeftOutOfTheDefault() throws Exception {
		planOn("2026-08-03", "Lunch", 6);
		// Stored FESTIVAL rather than chosen: written straight onto the row, which is the state a
		// festival day's meal is actually in.
		planOn("2026-08-10", "Lunch", 40);
		admin.update("UPDATE meal_plans SET day_type = 'FESTIVAL' WHERE plan_date = '2026-08-10'");

		mvc.perform(authed(get("/api/v1/meal-crew/suggested").param("mealKind", "Lunch")))
				.andExpect(jsonPath("$.crewRequired").value(6));
	}

	@Test
	@DisplayName("an approver is told what a day off costs each meal, and is not stopped")
	void leaveSaysWhatItCosts() throws Exception {
		plan("Breakfast", 200, 2);
		plan("Lunch", 400, 4);
		plan("Dinner", 200, 2);

		UUID leave = admin.queryForObject("""
				INSERT INTO staff_leave (
					tenant_id, staff_profile_id, leave_type, from_date, to_date, half_day, status,
					requested_by)
				VALUES (?, ?, 'TIME_OFF', ?::date, ?::date, false, 'PENDING',
						(SELECT id FROM users WHERE firebase_uid = 'uid-morning'))
				RETURNING id
				""", UUID.class, tenant, morningCook, DATE, DATE);

		mvc.perform(authed(get("/api/v1/leave/{id}/impact", leave)))
				.andExpect(status().isOk())
				// Dinner is not her meal and is left out. Listing it unchanged would bury the two
				// lines that matter under one that does not.
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].mealKind").value("Breakfast"))
				.andExpect(jsonPath("$[0].rostered").value(0))
				.andExpect(jsonPath("$[0].crewRequired").value(2))
				.andExpect(jsonPath("$[1].mealKind").value("Lunch"))
				.andExpect(jsonPath("$[1].rostered").value(0))
				.andExpect(jsonPath("$[1].crewRequired").value(4));

		// Told, not stopped. The approval goes through exactly as it would have.
		mvc.perform(authed(post("/api/v1/leave/{id}/approve", leave))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("Today reads the meals apart, not one figure for the whole day")
	void todayReadsPerMeal() throws Exception {
		// Today is whatever today is, so the meals go on today's date rather than the fixed one.
		String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
		mvc.perform(createRequest("""
				{"planDate":"%s","mealKind":"Dinner","recipeId":"%s","targetYield":200,"adults":200,
				 "crewRequired":5}
				""".formatted(today, khichdi))).andExpect(status().isCreated());

		mvc.perform(authed(get("/api/v1/today")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workforce.staffIn").value(2))
				.andExpect(jsonPath("$.workforce.meals.length()").value(1))
				.andExpect(jsonPath("$.workforce.meals[0].mealKind").value("Dinner"))
				// One of the two cooks is there at 19:30, against a plan of five.
				.andExpect(jsonPath("$.workforce.meals[0].rostered").value(1))
				.andExpect(jsonPath("$.workforce.meals[0].crewRequired").value(5))
				.andExpect(jsonPath("$.workforce.meals[0].shortOfCrew").value(true));
	}

	// ---- helpers ----------------------------------------------------------

	private void plan(String kind, int servings, Integer crew) throws Exception {
		planOn(DATE, kind, servings, crew);
	}

	private void planOn(String date, String kind, Integer crew) throws Exception {
		planOn(date, kind, 200, crew);
	}

	private void planOn(String date, String kind, int servings, Integer crew) throws Exception {
		mvc.perform(createRequest("""
				{"planDate":"%s","mealKind":"%s","recipeId":"%s","targetYield":%d,"adults":%d,"crewRequired":%s}
				""".formatted(date, kind, khichdi, servings, servings, crew == null ? "null" : crew)))
				.andExpect(status().isCreated());
	}

	private MockHttpServletRequestBuilder createRequest(String json) {
		return authed(post("/api/v1/meal-plans")).contentType(MediaType.APPLICATION_JSON).content(json);
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
