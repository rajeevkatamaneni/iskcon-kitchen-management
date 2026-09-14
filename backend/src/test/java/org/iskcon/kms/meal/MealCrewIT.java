package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
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
		// Shifts before meals: shifts.meal_id is RESTRICT (V136).
		admin.execute("DELETE FROM shift_signups");
		admin.execute("DELETE FROM shifts");
		admin.execute("DELETE FROM meal_card_sequence");
		admin.execute("DELETE FROM meal_dishes");
		admin.execute("DELETE FROM meals");
		admin.execute("DELETE FROM meal_plan_days");
		admin.execute("DELETE FROM meal_kinds");
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
		UUID breakfast = plan("Breakfast", 200, null);
		UUID lunch = plan("Lunch", 400, null);
		UUID dinner = plan("Dinner", 200, null);

		// Both cooks are in all day by the day-grain reckoning, and that is exactly the figure that
		// cannot answer the question.
		mvc.perform(authed(get("/api/v1/workforce").param("from", DATE).param("to", DATE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].staffIn").value(2));

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				// Each readout names its meal by id (D-27), so the planner and Today can open the meal
				// rather than find it again by its date and kind.
				.andExpect(jsonPath("$[0].mealId").value(breakfast.toString()))
				.andExpect(jsonPath("$[1].mealId").value(lunch.toString()))
				.andExpect(jsonPath("$[2].mealId").value(dinner.toString()))
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
	@DisplayName("a crew short of hands is read as short and nothing more — a meal is planned weeks before anybody is rostered")
	void beingShortNeverBlocksSaving() throws Exception {
		// Twelve people for a lunch with one cook rostered. The roster for September is not written in
		// August, and a planner refused here would stop using the field. Since D-27 the meal is saved
		// by the planner's own endpoint, whose refusals are MealPlanIT's to prove; what this class owns
		// is that the crew readout reports the gap as a warning and refuses nothing on reading it.
		plan("Lunch", 400, 12);
		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].crewRequired").value(12))
				.andExpect(jsonPath("$[0].rostered").value(1))
				.andExpect(jsonPath("$[0].shortOfCrew").value(true));
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
		// Since D-27 the day type is a fact about the day, on meal_plan_days.
		admin.update("UPDATE meal_plan_days SET day_type = 'FESTIVAL' WHERE plan_date = '2026-08-10'");

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
		meal(today, "Dinner", null, null, 5);

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

	// ---- A meal not saved yet (T-215) ------------------------------------

	@Test
	@DisplayName("a meal not saved yet is counted at its date and ready-by, reads the same once saved, and asking writes nothing")
	void anUnsavedMealIsCountedAsItWillBeOnceSaved() throws Exception {
		// A midday cook on 10:00–15:00, so two staff are in over 12:00: the morning cook and this one.
		// The evening cook starts at 14:00 and is not.
		insertUser("uid-midday", "KITCHEN_STAFF", "+919876500005");
		hire("uid-midday", "Midday Cook", "10:00", "15:00");

		// Another temple with a cook in all day, every day. The count is read through the signed-in
		// temple's connection, so Row-Level Security must leave this one out — three here would mean it
		// did not.
		otherTempleWithACookAllDay();

		int mealsBefore = rows("meals");
		int daysBefore = rows("meal_plan_days");
		int shiftsBefore = rows("shifts");
		int dishesBefore = rows("meal_dishes");

		mvc.perform(authed(get("/api/v1/meal-crew/at").param("date", DATE).param("readyBy", "12:00")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.planDate").value(DATE))
				.andExpect(jsonPath("$.readyBy").value("12:00:00"))
				.andExpect(jsonPath("$.staffIn").value(2))
				.andExpect(jsonPath("$.volunteers").value(0))
				.andExpect(jsonPath("$.rostered").value(2));

		// Asking wrote nothing: no meal, no day, no dish, no shift. Nothing in the planner is saved
		// until the meal is (D-27 answer 7).
		assertThat(rows("meals")).isEqualTo(mealsBefore);
		assertThat(rows("meal_plan_days")).isEqualTo(daysBefore);
		assertThat(rows("meal_dishes")).isEqualTo(dishesBefore);
		assertThat(rows("shifts")).isEqualTo(shiftsBefore);

		// Now saved at that date and ready-by, through the planner's own endpoint, as a new event — the
		// case the browser test found reading "Not counted yet" before the save and "2 of 5" after it.
		UUID eventKind = MealFixture.kindId(admin, tenant, "Event");
		String body = mvc.perform(authed(post("/api/v1/meals")).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"%s","mealKindId":"%s","readyBy":"12:00","eventName":"Bhajan prasadam",
								 "adults":100,"crewRequired":5,"dishes":[{"recipeId":"%s","targetYield":100}]}
								""".formatted(DATE, eventKind, khichdi)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		UUID saved = MealRequests.idOf(body);

		// The saved meal's own crew row gives the figure the count gave before it existed.
		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].mealId").value(saved.toString()))
				.andExpect(jsonPath("$[0].staffIn").value(2))
				.andExpect(jsonPath("$[0].volunteers").value(0))
				.andExpect(jsonPath("$[0].rostered").value(2))
				.andExpect(jsonPath("$[0].crewRequired").value(5));
	}

	@Test
	@DisplayName("before the first save a volunteer counts through a shift not for a meal that covers the ready-by, never through another meal's shift")
	void anUnsavedMealCountsOnlyShiftsNotForAMeal() throws Exception {
		// Lunch is saved already; the meal being planned is something else due at 12:00 the same day.
		UUID lunch = plan("Lunch", 400, 4);
		UUID volunteer = admin.queryForObject("SELECT id FROM users WHERE firebase_uid = 'uid-vol'", UUID.class);

		// Not for a meal, 11:00–14:00: covers 12:00, so it counts, exactly as it would for any meal.
		UUID general = shift("Hall seva", "11:00", "14:00", null);
		// For Lunch, and covering 12:00 too. It counts toward Lunch and no other (D-14), so a meal with no
		// id yet cannot have it.
		UUID forLunch = shift("Lunch prep", "09:00", "13:00", lunch);
		// Not for a meal, but over by 11:30: the clock places it before 12:00, so it does not count.
		UUID early = shift("Garlands", "08:00", "11:30", null);
		for (UUID shift : new UUID[] {general, forLunch, early}) {
			admin.update("INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?, ?)",
					tenant, shift, volunteer);
		}

		mvc.perform(authed(get("/api/v1/meal-crew/at").param("date", DATE).param("readyBy", "12:00")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.staffIn").value(1))
				.andExpect(jsonPath("$.volunteers").value(1))
				.andExpect(jsonPath("$.rostered").value(2));

		// Lunch's own row still has both of the shifts that are its to have.
		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].mealId").value(lunch.toString()))
				.andExpect(jsonPath("$[0].volunteers").value(2));
	}

	@Test
	@DisplayName("a volunteer, who cannot plan meals, is refused the count for a meal not saved yet")
	void theCountIsBehindPlanningMeals() throws Exception {
		signIn("uid-vol");
		mvc.perform(authed(get("/api/v1/meal-crew/at").param("date", DATE).param("readyBy", "12:00")))
				.andExpect(status().isForbidden());
	}

	// ---- helpers ----------------------------------------------------------

	/** Rows in a table across every temple, read as the owner — for "nothing was written". */
	private int rows(String table) {
		Integer n = admin.queryForObject("SELECT count(*) FROM " + table, Integer.class);
		return n == null ? 0 : n;
	}

	/** A volunteer shift on {@link #DATE}, for a meal where {@code mealId} is given. */
	private UUID shift(String title, String start, String end, UUID mealId) {
		return admin.queryForObject("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by, meal_id)
				VALUES (?, ?, ?::date, ?::time, ?::time, 4, (SELECT id FROM users WHERE firebase_uid = 'uid-admin'), ?)
				RETURNING id
				""", UUID.class, tenant, title, DATE, start, end, mealId);
	}

	/** A second temple whose one cook is in from 00:00 to 23:59 every day. */
	private void otherTempleWithACookAllDay() {
		UUID other = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('other-temple', 'Mysuru Temple', 12.2958, 76.6394, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-other-cook', 'Other Cook', 'other-cook@example.com', '+919876500099', 'KITCHEN_STAFF', 'ACTIVE')
				""", other);
		UUID profile = admin.queryForObject("""
				INSERT INTO staff_profiles (
					tenant_id, user_id, full_name, job_title, employment_type, date_of_joining)
				VALUES (?, (SELECT id FROM users WHERE firebase_uid = 'uid-other-cook'), 'Other Cook', 'COOK',
						'FULL_TIME', '2026-01-01')
				RETURNING id
				""", UUID.class, other);
		for (int day = 1; day <= 7; day++) {
			admin.update("""
					INSERT INTO staff_schedule_template (
						tenant_id, staff_profile_id, day_of_week, working, start_time, end_time)
					VALUES (?, ?, ?, true, '00:00'::time, '23:59'::time)
					""", other, profile, day);
		}
	}

	private UUID plan(String kind, int servings, Integer crew) {
		return meal(DATE, kind, null, null, crew);
	}

	private void planOn(String date, String kind, Integer crew) {
		meal(date, kind, null, null, crew);
	}

	/**
	 * A meal written straight into the D-27 tables: its day, the meal row with its kind by id, and
	 * one dish. SQL rather than the planner's API so this class tests the crew count and nothing about
	 * how a meal is saved, which is MealPlanIT's question. The ready-by is the kind's default unless
	 * given, which is what the planner does too.
	 */
	private UUID meal(String date, String kind, String eventName, String readyBy, Integer crew) {
		UUID day = admin.queryForObject("""
				INSERT INTO meal_plan_days (tenant_id, plan_date, day_type) VALUES (?, ?::date, 'REGULAR')
				ON CONFLICT (tenant_id, plan_date) DO UPDATE SET updated_at = now()
				RETURNING id
				""", UUID.class, tenant, date);
		UUID kindId = admin.queryForObject(
				"SELECT id FROM meal_kinds WHERE tenant_id = ? AND lower(name) = lower(?)", UUID.class, tenant, kind);
		UUID meal = admin.queryForObject("""
				INSERT INTO meals (tenant_id, meal_plan_day_id, meal_kind_id, event_name, ready_by, adults,
						crew_required)
				VALUES (?, ?, ?, ?, COALESCE(?::time, (SELECT default_ready_time FROM meal_kinds WHERE id = ?)),
						200, ?)
				RETURNING id
				""", UUID.class, tenant, day, kindId, eventName, readyBy, kindId, crew);
		admin.update("""
				INSERT INTO meal_dishes (tenant_id, meal_id, recipe_id, target_yield, status, created_by)
				VALUES (?, ?, ?, 200, 'PLANNED', (SELECT id FROM users WHERE firebase_uid = 'uid-admin'))
				""", tenant, meal, khichdi);
		return meal;
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

}
