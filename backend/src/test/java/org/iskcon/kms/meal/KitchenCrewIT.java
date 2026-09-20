package org.iskcon.kms.meal;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * People needed, and the people there are, per kitchen (Epic 12, T-358), through the full stack.
 *
 * <p>Rajeev, 2026-09-19: <em>"'People needed' is answered per kitchen; the rostered staff shown are
 * that kitchen's staff."</em> The case every test here starts from is one lunch at 11:30 cooked by two
 * kitchens:
 *
 * <ul>
 *   <li><strong>Main kitchen</strong>, needs 6: Anand (06:00–14:00) and Bhima (10:00–15:00), both in
 *       at 11:30.
 *   <li><strong>Sweets kitchen</strong>, needs 2: Chaitanya (09:00–13:00), in at 11:30, and Damodar
 *       (14:00–22:00), who is not.
 *   <li>One volunteer signed up to a shift <em>for this lunch</em>. Volunteers belong to no kitchen, so
 *       the meal's volunteers are counted once, in the main kitchen's section (the rule flagged to
 *       Rajeev as an assumption), and nowhere else.
 * </ul>
 *
 * <p>So Main reads 2 staff + 1 volunteer = 3 of 6, Sweets 1 of 2, and the lunch as a whole 4 of 8 —
 * the kitchens added up. Before Epic 12 all three cooks would have counted toward one figure of 6.
 *
 * <p>The meal is written by SQL rather than through the planner's save, which is T-354's to teach
 * about kitchens; what this class owns is how the crew is counted once the kitchens are there.
 */
@AutoConfigureMockMvc
class KitchenCrewIT extends AbstractIntegrationTest {

	/** A Monday. The rosters below cover all seven days, so nothing turns on which day it is. */
	private static final String DATE = "2026-09-07";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@MockBean
	private Scheduler scheduler; // the same context as MealCrewIT, so the two share it

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID recipe;
	private UUID mainKitchen;
	private UUID sweetsKitchen;
	private UUID anand;
	private UUID chaitanya;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('kitchen-crew', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		insertUser("uid-admin", "TEMPLE_ADMIN", "+919876510001");
		insertUser("uid-anand", "KITCHEN_STAFF", "+919876510002");
		insertUser("uid-bhima", "KITCHEN_STAFF", "+919876510003");
		insertUser("uid-chaitanya", "KITCHEN_STAFF", "+919876510004");
		insertUser("uid-damodar", "KITCHEN_STAFF", "+919876510005");
		insertUser("uid-vol", "VOLUNTEER", "+919876510006");

		mainKitchen = MealFixture.plannerKitchen(admin, tenant, userId("uid-admin"));
		sweetsKitchen = admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, is_main, uses_meal_planner, status, created_by)
				VALUES (?, 'Sweets kitchen', false, true, 'ACTIVE', ?) RETURNING id
				""", UUID.class, tenant, userId("uid-admin"));

		UUID category = admin.queryForObject(
				"INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id", UUID.class, tenant);
		recipe = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichdi', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, category);

		anand = hire("uid-anand", "Anand", mainKitchen, "06:00", "14:00");
		hire("uid-bhima", "Bhima", mainKitchen, "10:00", "15:00");
		chaitanya = hire("uid-chaitanya", "Chaitanya", sweetsKitchen, "09:00", "13:00");
		hire("uid-damodar", "Damodar", sweetsKitchen, "14:00", "22:00");

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
		admin.execute("DELETE FROM shift_signups");
		admin.execute("DELETE FROM shifts");
		admin.execute("DELETE FROM meal_card_sequence");
		admin.execute("DELETE FROM meal_dishes");
		admin.execute("DELETE FROM meal_kitchens");
		admin.execute("DELETE FROM meals");
		admin.execute("DELETE FROM meal_plan_days");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM staff_leave");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM kitchens");
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
	@DisplayName("each kitchen is held against its own staff, the volunteers fall to the main kitchen, and the meal is the kitchens added up")
	void eachKitchenCountsItsOwnStaff() throws Exception {
		UUID lunch = lunch(DATE, 6, 2);
		volunteerFor(lunch);

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].mealId").value(lunch.toString()))
				// The Temple Admin has no kitchen of their own, so the main kitchen reads first.
				.andExpect(jsonPath("$[0].kitchens.length()").value(2))
				.andExpect(jsonPath("$[0].kitchens[0].kitchenId").value(mainKitchen.toString()))
				.andExpect(jsonPath("$[0].kitchens[0].crewRequired").value(6))
				.andExpect(jsonPath("$[0].kitchens[0].staffIn").value(2))
				.andExpect(jsonPath("$[0].kitchens[0].staffNames", contains("Anand", "Bhima")))
				.andExpect(jsonPath("$[0].kitchens[0].volunteers").value(1))
				.andExpect(jsonPath("$[0].kitchens[0].rostered").value(3))
				.andExpect(jsonPath("$[0].kitchens[0].shortOfCrew").value(true))
				// Sweets: Chaitanya is in at 11:30; Damodar starts at 14:00 and is not. The volunteer is
				// Main's, so not here as well.
				.andExpect(jsonPath("$[0].kitchens[1].kitchenId").value(sweetsKitchen.toString()))
				.andExpect(jsonPath("$[0].kitchens[1].kitchenName").value("Sweets kitchen"))
				.andExpect(jsonPath("$[0].kitchens[1].crewRequired").value(2))
				.andExpect(jsonPath("$[0].kitchens[1].staffIn").value(1))
				.andExpect(jsonPath("$[0].kitchens[1].staffNames", contains("Chaitanya")))
				.andExpect(jsonPath("$[0].kitchens[1].volunteers").value(0))
				.andExpect(jsonPath("$[0].kitchens[1].rostered").value(1))
				.andExpect(jsonPath("$[0].kitchens[1].shortOfCrew").value(true))
				// The meal: the kitchens added up. 4 of 8, short.
				.andExpect(jsonPath("$[0].crewRequired").value(8))
				.andExpect(jsonPath("$[0].staffIn").value(3))
				.andExpect(jsonPath("$[0].volunteers").value(1))
				.andExpect(jsonPath("$[0].rostered").value(4))
				.andExpect(jsonPath("$[0].shortOfCrew").value(true));
	}

	@Test
	@DisplayName("a meal is short when one kitchen is, even where the sums would say it is covered")
	void aKitchenShortMakesTheMealShort() throws Exception {
		// Main needs 2 and has 3 (Anand, Bhima, the volunteer); Sweets needs 2 and has 1. Added up it is
		// 4 of 4, which reads covered, and the Sweets kitchen is still a pair of hands short.
		UUID lunch = lunch(DATE, 2, 2);
		volunteerFor(lunch);

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].crewRequired").value(4))
				.andExpect(jsonPath("$[0].rostered").value(4))
				.andExpect(jsonPath("$[0].kitchens[0].shortOfCrew").value(false))
				.andExpect(jsonPath("$[0].kitchens[1].shortOfCrew").value(true))
				.andExpect(jsonPath("$[0].shortOfCrew").value(true));

		// And the day's coverage names the kitchen that is short, at its own figures.
		mvc.perform(authed(get("/api/v1/crew-coverage").param("from", DATE).param("to", DATE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].state").value("SHORT"))
				.andExpect(jsonPath("$[0].shortBy").value(1))
				.andExpect(jsonPath("$[0].shortAt").value("Lunch (Sweets kitchen)"))
				.andExpect(jsonPath("$[0].shortAtRequired").value(2))
				.andExpect(jsonPath("$[0].shortAtRostered").value(1))
				.andExpect(jsonPath("$[0].shortAtMealId").value(lunch.toString()));
	}

	@Test
	@DisplayName("with no main kitchen on the meal, the volunteers fall to the first kitchen the person sees")
	void withoutTheMainKitchenVolunteersGoFirst() throws Exception {
		UUID bakery = admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, is_main, uses_meal_planner, status, created_by)
				VALUES (?, 'Bakery', false, true, 'ACTIVE', ?) RETURNING id
				""", UUID.class, tenant, userId("uid-admin"));
		UUID meal = meal(DATE, "11:30");
		section(meal, sweetsKitchen, 2);
		section(meal, bakery, 1);
		volunteerFor(meal);

		// No main kitchen on the meal and none of the admin's own: Settings order, which is by name.
		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].kitchens[0].kitchenName").value("Bakery"))
				.andExpect(jsonPath("$[0].kitchens[0].staffIn").value(0))
				.andExpect(jsonPath("$[0].kitchens[0].volunteers").value(1))
				.andExpect(jsonPath("$[0].kitchens[1].kitchenName").value("Sweets kitchen"))
				.andExpect(jsonPath("$[0].kitchens[1].volunteers").value(0))
				.andExpect(jsonPath("$[0].volunteers").value(1));
	}

	@Test
	@DisplayName("a Sweets cook sees the Sweets kitchen first, on the crew figures and on Today, and the volunteers stay with Main")
	void aPersonSeesTheirOwnKitchenFirst() throws Exception {
		// Today is whatever today is, so the meal goes on today's date.
		String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
		UUID lunch = lunch(today, 6, 2);
		volunteerFor(lunch);

		signIn("uid-chaitanya");
		mvc.perform(authed(get("/api/v1/today")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meals[0].kitchenNames", contains("Sweets kitchen", "Main kitchen")))
				.andExpect(jsonPath("$.workforce.meals[0].kitchens[0].kitchenName").value("Sweets kitchen"))
				.andExpect(jsonPath("$.workforce.meals[0].kitchens[0].volunteers").value(0))
				.andExpect(jsonPath("$.workforce.meals[0].kitchens[1].kitchenName").value("Main kitchen"))
				.andExpect(jsonPath("$.workforce.meals[0].kitchens[1].volunteers").value(1));

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", today).param("to", today)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].kitchens[0].kitchenName").value("Sweets kitchen"))
				.andExpect(jsonPath("$[0].kitchens[0].staffNames", contains("Chaitanya")));

		// The Temple Admin, with no kitchen of their own, sees the main kitchen first.
		signIn("uid-admin");
		mvc.perform(authed(get("/api/v1/today")))
				.andExpect(jsonPath("$.meals[0].kitchenNames", contains("Main kitchen", "Sweets kitchen")));
	}

	@Test
	@DisplayName("asked for one kitchen, the count before a save is that kitchen's staff, and its volunteers only when the caller says so")
	void theCountBeforeASaveTakesAKitchen() throws Exception {
		// A shift not for a meal, over 11:30: before a save it is the only kind of volunteer that counts.
		UUID general = shift("Hall seva", "11:00", "12:00", null);
		admin.update("INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?, ?)",
				tenant, general, userId("uid-vol"));

		// No kitchen: every member of staff, as before Epic 12, by name.
		mvc.perform(authed(get("/api/v1/meal-crew/at").param("date", DATE).param("readyBy", "11:30")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.staffIn").value(3))
				.andExpect(jsonPath("$.staffNames", contains("Anand", "Bhima", "Chaitanya")))
				.andExpect(jsonPath("$.volunteers").value(1))
				.andExpect(jsonPath("$.rostered").value(4));

		// Main, carrying the volunteers (the default).
		mvc.perform(authed(get("/api/v1/meal-crew/at").param("date", DATE).param("readyBy", "11:30")
						.param("kitchenId", mainKitchen.toString())))
				.andExpect(jsonPath("$.staffIn").value(2))
				.andExpect(jsonPath("$.staffNames", contains("Anand", "Bhima")))
				.andExpect(jsonPath("$.volunteers").value(1))
				.andExpect(jsonPath("$.rostered").value(3));

		// Sweets, told the volunteers are another section's.
		mvc.perform(authed(get("/api/v1/meal-crew/at").param("date", DATE).param("readyBy", "11:30")
						.param("kitchenId", sweetsKitchen.toString()).param("countVolunteers", "false")))
				.andExpect(jsonPath("$.staffIn").value(1))
				.andExpect(jsonPath("$.staffNames", contains("Chaitanya")))
				.andExpect(jsonPath("$.volunteers").value(0))
				.andExpect(jsonPath("$.rostered").value(1));
	}

	@Test
	@DisplayName("a day off costs only the kitchen the person works in")
	void leaveCostsOnlyTheirOwnKitchen() throws Exception {
		UUID lunch = lunch(DATE, 6, 2);
		volunteerFor(lunch);

		// Chaitanya is Sweets: approving her day leaves Sweets at 0 of 2 and Main untouched.
		mvc.perform(authed(get("/api/v1/leave/{id}/impact", leave(chaitanya))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].mealKind").value("Lunch"))
				.andExpect(jsonPath("$[0].kitchens.length()").value(1))
				.andExpect(jsonPath("$[0].kitchens[0].kitchenName").value("Sweets kitchen"))
				.andExpect(jsonPath("$[0].kitchens[0].staffIn").value(0))
				.andExpect(jsonPath("$[0].rostered").value(0))
				.andExpect(jsonPath("$[0].crewRequired").value(2));

		// Anand is Main: Main at Bhima and the volunteer, 2 of 6.
		mvc.perform(authed(get("/api/v1/leave/{id}/impact", leave(anand))))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].kitchens[0].kitchenName").value("Main kitchen"))
				.andExpect(jsonPath("$[0].kitchens[0].staffNames", contains("Bhima")))
				.andExpect(jsonPath("$[0].rostered").value(2))
				.andExpect(jsonPath("$[0].crewRequired").value(6));
	}

	@Test
	@DisplayName("another temple's staff are never counted, even with a cook in all day")
	void anotherTemplesStaffNeverCount() throws Exception {
		lunch(DATE, 6, 2);
		otherTempleWithACookAllDay();

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].staffIn").value(3))
				.andExpect(jsonPath("$[0].kitchens[0].staffNames", contains("Anand", "Bhima")))
				.andExpect(jsonPath("$[0].kitchens[1].staffNames", contains("Chaitanya")));

		// Without a kitchen the count reads every staff record it can see, so this is row-level
		// security's doing and not the kitchen filter's: four here would mean it failed.
		mvc.perform(authed(get("/api/v1/meal-crew/at").param("date", DATE).param("readyBy", "11:30")))
				.andExpect(jsonPath("$.staffIn").value(3))
				.andExpect(jsonPath("$.staffNames", contains("Anand", "Bhima", "Chaitanya")));
	}

	// ---- helpers ----------------------------------------------------------

	/** Lunch at 11:30 on the date, cooked by Main (needing {@code main}) and Sweets (needing {@code sweets}). */
	private UUID lunch(String date, int main, int sweets) {
		UUID meal = meal(date, "11:30");
		section(meal, mainKitchen, main);
		section(meal, sweetsKitchen, sweets);
		return meal;
	}

	/** A Lunch row on the date, with no kitchen yet. People needed goes on its kitchens, never here. */
	private UUID meal(String date, String readyBy) {
		UUID day = admin.queryForObject("""
				INSERT INTO meal_plan_days (tenant_id, plan_date, day_type) VALUES (?, ?::date, 'REGULAR')
				ON CONFLICT (tenant_id, plan_date) DO UPDATE SET updated_at = now()
				RETURNING id
				""", UUID.class, tenant, date);
		UUID kind = MealFixture.kindId(admin, tenant, "Lunch");
		return admin.queryForObject("""
				INSERT INTO meals (tenant_id, meal_plan_day_id, meal_kind_id, ready_by, adults)
				VALUES (?, ?, ?, ?::time, 200) RETURNING id
				""", UUID.class, tenant, day, kind, readyBy);
	}

	/** One kitchen on the meal with its People needed, and one dish of its own. */
	private void section(UUID meal, UUID kitchen, int crew) {
		admin.update("""
				INSERT INTO meal_kitchens (tenant_id, meal_id, kitchen_id, crew_required) VALUES (?, ?, ?, ?)
				""", tenant, meal, kitchen, crew);
		admin.update("""
				INSERT INTO meal_dishes (tenant_id, meal_id, recipe_id, target_yield, status, created_by, kitchen_id)
				VALUES (?, ?, ?, 100, 'PLANNED', ?, ?)
				""", tenant, meal, recipe, userId("uid-admin"), kitchen);
	}

	/** A shift for this meal with the one volunteer signed up. */
	private void volunteerFor(UUID meal) {
		UUID shift = admin.queryForObject("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by, meal_id)
				SELECT ?, 'Lunch prep', d.plan_date, '09:00', '11:00', 4, ?, m.id
				FROM meals m JOIN meal_plan_days d ON d.id = m.meal_plan_day_id WHERE m.id = ?
				RETURNING id
				""", UUID.class, tenant, userId("uid-admin"), meal);
		admin.update("INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id) VALUES (?, ?, ?)",
				tenant, shift, userId("uid-vol"));
	}

	/** A volunteer shift on {@link #DATE}, for a meal where {@code mealId} is given. */
	private UUID shift(String title, String start, String end, UUID mealId) {
		return admin.queryForObject("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by, meal_id)
				VALUES (?, ?, ?::date, ?::time, ?::time, 4, ?, ?)
				RETURNING id
				""", UUID.class, tenant, title, DATE, start, end, userId("uid-admin"), mealId);
	}

	/** A pending day off on {@link #DATE} for this staff record. */
	private UUID leave(UUID staffProfile) {
		return admin.queryForObject("""
				INSERT INTO staff_leave (
					tenant_id, staff_profile_id, leave_type, from_date, to_date, half_day, status, requested_by)
				VALUES (?, ?, 'TIME_OFF', ?::date, ?::date, false, 'PENDING',
						(SELECT user_id FROM staff_profiles WHERE id = ?))
				RETURNING id
				""", UUID.class, tenant, staffProfile, DATE, DATE, staffProfile);
	}

	/** A second temple whose one cook is in from 00:00 to 23:59 every day, in its own kitchen. */
	private void otherTempleWithACookAllDay() {
		UUID other = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('kitchen-crew-other', 'Mysuru Temple', 12.2958, 76.6394, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-other-cook', 'Other Cook', 'other-cook@example.com', '+919876510099', 'KITCHEN_STAFF', 'ACTIVE')
				""", other);
		UUID profile = admin.queryForObject("""
				INSERT INTO staff_profiles (
					tenant_id, user_id, full_name, job_title, employment_type, date_of_joining, kitchen_id)
				VALUES (?, (SELECT id FROM users WHERE firebase_uid = 'uid-other-cook'), 'Other Cook', 'COOK',
						'FULL_TIME', '2026-01-01', ?)
				RETURNING id
				""", UUID.class, other, MealFixture.plannerKitchen(admin, other, null));
		for (int day = 1; day <= 7; day++) {
			admin.update("""
					INSERT INTO staff_schedule_template (
						tenant_id, staff_profile_id, day_of_week, working, start_time, end_time)
					VALUES (?, ?, ?, true, '00:00'::time, '23:59'::time)
					""", other, profile, day);
		}
	}

	/** A cook in this kitchen with the same hours every day of the week. */
	private UUID hire(String uid, String name, UUID kitchen, String start, String end) {
		UUID profile = admin.queryForObject("""
				INSERT INTO staff_profiles (
					tenant_id, user_id, full_name, job_title, employment_type, date_of_joining, kitchen_id)
				VALUES (?, ?, ?, 'COOK', 'FULL_TIME', '2026-01-01', ?)
				RETURNING id
				""", UUID.class, tenant, userId(uid), name, kitchen);
		for (int day = 1; day <= 7; day++) {
			admin.update("""
					INSERT INTO staff_schedule_template (
						tenant_id, staff_profile_id, day_of_week, working, start_time, end_time)
					VALUES (?, ?, ?, true, ?::time, ?::time)
					""", tenant, profile, day, start, end);
		}
		return profile;
	}

	private UUID userId(String uid) {
		return admin.queryForObject("SELECT id FROM users WHERE firebase_uid = ?", UUID.class, uid);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
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
