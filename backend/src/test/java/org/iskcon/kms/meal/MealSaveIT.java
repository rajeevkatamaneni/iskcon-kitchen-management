package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A meal is a row of its own, saved with its dishes and its volunteer shift in one press (D-27).
 *
 * <p>Three rulings are proved here, each in Rajeev's words:
 *
 * <ul>
 *   <li><strong>One transaction.</strong> <em>"The Sift when saved shoudl be left uncommited until the
 *       meal is saved. Once the meal is saved, we take the ID of the meal and update the Volenteer
 *       reruest with that ID and then commit everything."</em> So a shift that cannot be saved takes
 *       the meal and its dishes with it, and one that can is saved only by saving the meal.</li>
 *   <li><strong>One meal per day, kind and event name.</strong> <em>"identifying things by text is a
 *       terrible idea"</em> — so the text is compared once, by the unique index, and from then on the
 *       meal is its id. A name retyped in another case, and a meal that was cancelled and planned
 *       again, are the same meal.</li>
 *   <li><strong>Cancelling a meal cancels its shift.</strong> <em>"Yes, warn then cancel both."</em>
 *       The warning's counts are on the meal the planner already holds, and the volunteers are told
 *       with the existing cancellation message.</li>
 * </ul>
 *
 * <p><strong>The forced failure is a trigger, not a mock.</strong> A mocked shift service that throws
 * would prove the meal service catches nothing; it would not prove the database undid anything,
 * because nothing real would have been written. So the failing half is PostgreSQL refusing the shift
 * row, in the middle of the real transaction, after the meal and its dishes have been written. The
 * trigger exists only for the length of the test that needs it and is dropped in a {@code finally}.
 *
 * <p>{@code NotificationService} is mocked, and only that, so this class shares the cached context of
 * the other classes that mock exactly that bean rather than starting a scheduler context of its own.
 * The signed-in person is Kitchen Staff throughout: saving a meal's volunteer shift asks for no
 * permission beyond planning meals.
 */
@AutoConfigureMockMvc
class MealSaveIT extends AbstractIntegrationTest {

	private static final String DAY = "2025-03-18";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@MockBean
	private NotificationService notificationService;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID lunch;
	private UUID event;
	private UUID khichdi;
	private UUID payasam;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('meal-save-temple', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		insertUser("uid-cook", "cook@example.com", "KITCHEN_STAFF", "+919876500301");
		insertUser("uid-vol-a", "vol-a@example.com", "VOLUNTEER", "+919876500302");
		insertUser("uid-vol-b", "vol-b@example.com", "VOLUNTEER", "+919876500303");

		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Mains') RETURNING id
				""", UUID.class, tenant);
		khichdi = recipe("Khichdi", category);
		payasam = recipe("Payasam", category);

		// Every meal is cooked by one of the temple's kitchens (Epic 12), and saving one no longer makes a
		// kitchen: a real temple is given its main kitchen when it is provisioned. This temple is made by
		// hand, so it is given one here, as provisioning would (T-354).
		MealFixture.plannerKitchen(admin, tenant, null);
		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
		} finally {
			TenantContext.clear();
		}
		lunch = MealFixture.kindId(admin, tenant, "Lunch");
		event = MealFixture.kindId(admin, tenant, "Event");
		reset(notificationService);
		stubVerifier.accept("uid-cook");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		dropForcedFailure();
		admin.execute("DELETE FROM shift_reminders");
		admin.execute("DELETE FROM shift_waitlist");
		admin.execute("DELETE FROM shift_signups");
		admin.execute("DELETE FROM shifts");
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- One transaction ----------------------------------------------------

	@Test
	@DisplayName("a meal, its dishes and its volunteer shift are saved in one press, and the shift points at the meal")
	void mealDishesAndShiftAreSavedTogether() throws Exception {
		UUID meal = save(lunchWithShift(6));

		assertThat(count("meals")).isEqualTo(1);
		assertThat(count("meal_dishes")).isEqualTo(2);
		assertThat(admin.queryForObject("SELECT meal_id FROM shifts", UUID.class)).isEqualTo(meal);
		// The shift's date is the meal's, read from the meal row — the draft carries none.
		assertThat(admin.queryForObject("SELECT shift_date::text FROM shifts", String.class)).isEqualTo(DAY);

		mvc.perform(authed(get("/api/v1/meals/{id}", meal)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mealId").value(meal.toString()))
				.andExpect(jsonPath("$.mealKindId").value(lunch.toString()))
				.andExpect(jsonPath("$.mealKind").value("Lunch"))
				.andExpect(jsonPath("$.dishes.length()").value(2))
				.andExpect(jsonPath("$.volunteerShift.title").value("Kitchen help for Lunch"))
				.andExpect(jsonPath("$.volunteerShift.capacity").value(6))
				.andExpect(jsonPath("$.volunteerShift.mealId").value(meal.toString()))
				.andExpect(jsonPath("$.volunteerShift.signedUpCount").value(0));

		// The range read carries it too, which is what the planner draws "View volunteer shift" from.
		mvc.perform(authed(get("/api/v1/meals")).param("from", DAY).param("to", DAY))
				.andExpect(jsonPath("$[0].volunteerShift.capacity").value(6));

		// Filed against the meal, which is what the planner saved.
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM audit_events
				WHERE entity_type = 'MEAL' AND entity_id = ? AND action = 'MEAL_PLANNED'
				""", Integer.class, meal)).isEqualTo(1);
	}

	@Test
	@DisplayName("a shift the database refuses takes the meal and its dishes with it: nothing is saved")
	void aShiftThatFailsLeavesNothingBehind() throws Exception {
		forceShiftWritesToFail();

		mvc.perform(authed(post("/api/v1/meals")).contentType(MediaType.APPLICATION_JSON)
						.content(lunchWithShift(6)))
				.andExpect(status().is5xxServerError());

		// The meal row and both dishes were written before the shift was attempted. All three are gone,
		// and so is the day the save created for them.
		assertThat(count("meals")).isZero();
		assertThat(count("meal_dishes")).isZero();
		assertThat(count("meal_plan_days")).isZero();
		assertThat(count("shifts")).isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isZero();
	}

	@Test
	@DisplayName("a shift draft that is not a shift is refused before anything is written")
	void anInvalidDraftSavesNothing() throws Exception {
		mvc.perform(authed(post("/api/v1/meals")).contentType(MediaType.APPLICATION_JSON)
						.content(lunchWithShift(0)))
				.andExpect(status().isBadRequest());

		assertThat(count("meals")).isZero();
		assertThat(count("meal_dishes")).isZero();
		assertThat(count("shifts")).isZero();
	}

	@Test
	@DisplayName("a meal's shift changes only when the meal is saved, and a save without a draft leaves it alone")
	void theShiftChangesOnlyThroughTheMealSave() throws Exception {
		UUID meal = save(lunchWithShift(4));
		UUID dish = admin.queryForObject(
				"SELECT id FROM meal_dishes WHERE recipe_id = ?", UUID.class, khichdi);
		UUID other = admin.queryForObject(
				"SELECT id FROM meal_dishes WHERE recipe_id = ?", UUID.class, payasam);

		// Update this meal, with the layer's changes in it.
		mvc.perform(authed(put("/api/v1/meals/{id}", meal)).contentType(MediaType.APPLICATION_JSON)
						.content(update(dish, other, "Less salt", """
								,"volunteerShift":{"title":"Kitchen help for Lunch","startTime":"08:00",
								 "endTime":"11:30","capacity":7,"location":"Main kitchen"}""")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(meal.toString()));
		assertThat(admin.queryForObject("SELECT capacity FROM shifts", Integer.class)).isEqualTo(7);
		assertThat(admin.queryForObject("SELECT end_time::text FROM shifts", String.class)).isEqualTo("11:30:00");
		assertThat(count("shifts")).as("changed in place, not raised again").isEqualTo(1);

		// Update this meal with no draft: the shift is not the planner's to touch this time.
		mvc.perform(authed(put("/api/v1/meals/{id}", meal)).contentType(MediaType.APPLICATION_JSON)
						.content(update(dish, other, "Less salt still", "")))
				.andExpect(status().isOk());
		assertThat(admin.queryForObject("SELECT capacity FROM shifts", Integer.class)).isEqualTo(7);
		assertThat(admin.queryForObject("SELECT location FROM shifts", String.class)).isEqualTo("Main kitchen");

		// And a change the shift refuses takes the meal's change back with it.
		forceShiftWritesToFail();
		mvc.perform(authed(put("/api/v1/meals/{id}", meal)).contentType(MediaType.APPLICATION_JSON)
						.content(update(dish, other, "A note that must not survive", """
								,"volunteerShift":{"title":"Kitchen help for Lunch","startTime":"08:00",
								 "endTime":"12:00","capacity":9}""")))
				.andExpect(status().is5xxServerError());
		dropForcedFailure();

		assertThat(admin.queryForObject("SELECT kitchen_notes FROM meals WHERE id = ?", String.class, meal))
				.isEqualTo("Less salt still");
		assertThat(admin.queryForObject("SELECT capacity FROM shifts", Integer.class)).isEqualTo(7);
	}

	// ---- One meal per day, kind and event name -------------------------------

	@Test
	@DisplayName("the same event planned again with its name in another case is the same meal")
	void theSameEventInAnotherCaseIsOneMeal() throws Exception {
		UUID first = save("""
				{"planDate":"%s","mealKindId":"%s","readyBy":"18:00","eventName":"Bhajan Prasadam",
				 "dishes":[{"recipeId":"%s","targetYield":40}]}
				""".formatted(DAY, event, khichdi));
		UUID second = save("""
				{"planDate":"%s","mealKindId":"%s","readyBy":"18:00","eventName":"bhajan PRASADAM",
				 "dishes":[{"recipeId":"%s","targetYield":20}]}
				""".formatted(DAY, event, payasam));

		assertThat(second).isEqualTo(first);
		assertThat(count("meals")).isEqualTo(1);
		assertThat(count("meal_dishes")).isEqualTo(2);

		// A different event that day is a different meal.
		UUID reading = save("""
				{"planDate":"%s","mealKindId":"%s","readyBy":"10:00","eventName":"Children's reading",
				 "dishes":[{"recipeId":"%s","targetYield":30}]}
				""".formatted(DAY, event, khichdi));
		assertThat(reading).isNotEqualTo(first);

		// And so is a Lunch planned twice: one Lunch, with both dishes.
		UUID lunchOne = save(lunchWithShift(3));
		UUID lunchTwo = save("""
				{"planDate":"%s","mealKindId":"%s","adults":120,"dishes":[{"recipeId":"%s","targetYield":10}]}
				""".formatted(DAY, lunch, payasam));
		assertThat(lunchTwo).isEqualTo(lunchOne);
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM meal_dishes WHERE meal_id = ?", Integer.class, lunchOne)).isEqualTo(3);
	}

	@Test
	@DisplayName("a cancelled meal planned again is the same meal, with the same id")
	void aCancelledMealPlannedAgainReusesItsId() throws Exception {
		UUID first = save("""
				{"planDate":"%s","mealKindId":"%s","adults":100,"dishes":[{"recipeId":"%s","targetYield":100}]}
				""".formatted(DAY, lunch, khichdi));

		mvc.perform(authed(post("/api/v1/meals/{id}/cancel", first)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.volunteersTold").value(0));
		mvc.perform(authed(get("/api/v1/meals/{id}", first)))
				.andExpect(jsonPath("$.status").value("CANCELLED"));

		UUID again = save("""
				{"planDate":"%s","mealKindId":"%s","adults":90,"dishes":[{"recipeId":"%s","targetYield":90}]}
				""".formatted(DAY, lunch, payasam));

		assertThat(again).isEqualTo(first);
		assertThat(count("meals")).isEqualTo(1);
		mvc.perform(authed(get("/api/v1/meals/{id}", again)))
				.andExpect(jsonPath("$.status").value("PLANNED"))
				.andExpect(jsonPath("$.adults").value(90))
				// The cancelled dish is part of the record of what was decided; the new one is live.
				.andExpect(jsonPath("$.dishes.length()").value(2))
				.andExpect(jsonPath("$.dishes[0].status").value("CANCELLED"))
				.andExpect(jsonPath("$.dishes[1].status").value("PLANNED"));
	}

	// ---- Cancelling a meal with a shift --------------------------------------

	@Test
	@DisplayName("cancelling a meal cancels its shift too, after the meal showed how many would be told")
	void cancellingAMealCancelsItsShift() throws Exception {
		UUID meal = save(lunchWithShift(1));
		UUID shift = admin.queryForObject("SELECT id FROM shifts WHERE meal_id = ?", UUID.class, meal);
		admin.update("""
				INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id)
				VALUES (?, ?, (SELECT id FROM users WHERE firebase_uid = 'uid-vol-a'))
				""", tenant, shift);
		admin.update("""
				INSERT INTO shift_waitlist (tenant_id, shift_id, volunteer_user_id)
				VALUES (?, ?, (SELECT id FROM users WHERE firebase_uid = 'uid-vol-b'))
				""", tenant, shift);

		// What the planner's warning reads before anybody presses Cancel.
		mvc.perform(authed(get("/api/v1/meals/{id}", meal)))
				.andExpect(jsonPath("$.volunteerShift.id").value(shift.toString()))
				.andExpect(jsonPath("$.volunteerShift.signedUpCount").value(1))
				.andExpect(jsonPath("$.volunteerShift.waitlistCount").value(1));

		mvc.perform(authed(post("/api/v1/meals/{id}/cancel", meal))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"The hall is flooded\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.volunteersTold").value(2));

		assertThat(admin.queryForObject("SELECT status FROM shifts WHERE id = ?", String.class, shift))
				.isEqualTo("CANCELLED");
		assertThat(admin.queryForObject("SELECT cancel_reason FROM shifts WHERE id = ?", String.class, shift))
				.isEqualTo("The hall is flooded");
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM meal_dishes WHERE meal_id = ? AND status <> 'CANCELLED'
				""", Integer.class, meal)).isZero();

		// Both of them were told, with the message a cancellation from the shifts page sends.
		verify(notificationService, times(2)).notify(
				any(NotificationRecipient.class), eq(NotificationTemplate.SHIFT_CANCELLED), anyMap(), any());

		// And the meal no longer offers a live shift.
		mvc.perform(authed(get("/api/v1/meals/{id}", meal)))
				.andExpect(jsonPath("$.status").value("CANCELLED"))
				.andExpect(jsonPath("$.volunteerShift").doesNotExist());
	}

	@Test
	@DisplayName("a cooked meal cannot be cancelled, and its shift is left as it was")
	void aCookedMealKeepsItsShift() throws Exception {
		UUID meal = save(lunchWithShift(2));
		admin.update("UPDATE meal_dishes SET status = 'COOKED' WHERE meal_id = ?", meal);

		mvc.perform(authed(post("/api/v1/meals/{id}/cancel", meal)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400045"));

		assertThat(admin.queryForObject("SELECT status FROM shifts WHERE meal_id = ?", String.class, meal))
				.isEqualTo("OPEN");
		verify(notificationService, never()).notify(
				any(NotificationRecipient.class), eq(NotificationTemplate.SHIFT_CANCELLED), anyMap(), any());
	}

	// ---- The ceiling on a dish's amount (T-217) -------------------------------

	/**
	 * The bug this was written for, found on staging: 600 people at a 350 ml portion saved as 210,000
	 * on a recipe measured in litres. The meal saved; the Today screen, which scales every planned dish
	 * and refuses anything past 50,000, then failed for the whole kitchen. The save is where it is
	 * refused now, against the one dish's amount, so the planner sees which box to fix.
	 */
	@Test
	@DisplayName("a dish amount over 50,000 is refused on that dish's amount, and nothing is saved")
	void aDishAmountOverTheCeilingIsRefused() throws Exception {
		String body = """
				{"planDate":"%s","mealKindId":"%s","adults":600,
				 "dishes":[{"recipeId":"%s","targetYield":100},{"recipeId":"%s","targetYield":210000}]}
				""".formatted(DAY, lunch, khichdi, payasam);

		mvc.perform(authed(post("/api/v1/meals")).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(1))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("dishes[1].targetYield"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("Amount can be at most 50,000."));

		assertThat(count("meals")).isZero();
		assertThat(count("meal_dishes")).isZero();
	}

	@Test
	@DisplayName("a dish amount of exactly 50,000 saves, because that is what the Today screen can still scale")
	void aDishAmountAtTheCeilingSaves() throws Exception {
		save("""
				{"planDate":"%s","mealKindId":"%s","adults":600,
				 "dishes":[{"recipeId":"%s","targetYield":50000}]}
				""".formatted(DAY, lunch, khichdi));

		assertThat(count("meal_dishes")).isEqualTo(1);
	}

	@Test
	@DisplayName("editing a meal refuses a dish amount over 50,000 in the same words")
	void anEditOverTheCeilingIsRefused() throws Exception {
		UUID meal = save(lunchWithShift(6));
		UUID dish = admin.queryForObject(
				"SELECT id FROM meal_dishes WHERE meal_id = ? AND recipe_id = ?", UUID.class, meal, khichdi);
		UUID other = admin.queryForObject(
				"SELECT id FROM meal_dishes WHERE meal_id = ? AND recipe_id = ?", UUID.class, meal, payasam);

		mvc.perform(authed(put("/api/v1/meals/{id}", meal)).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"adults":100,
								 "dishes":[{"id":"%s","recipeId":"%s","targetYield":50000.01},{"id":"%s","recipeId":"%s","targetYield":50}]}
								""".formatted(dish, khichdi, other, payasam)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("dishes[0].targetYield"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("Amount can be at most 50,000."));

		assertThat(admin.queryForObject("SELECT target_yield FROM meal_dishes WHERE id = ?",
				java.math.BigDecimal.class, dish)).isEqualByComparingTo("100");
	}

	// ---------------------------------------------------------------------

	/** Lunch on the day, two dishes, a hundred adults and a shift asking for this many volunteers. */
	private String lunchWithShift(int volunteersRequested) {
		return """
				{"planDate":"%s","mealKindId":"%s","adults":100,"crewRequired":8,
				 "dishes":[{"recipeId":"%s","targetYield":100},{"recipeId":"%s","targetYield":50}],
				 "volunteerShift":{"title":"Kitchen help for Lunch","description":"Cutting vegetables",
				  "startTime":"08:00","endTime":"11:00","location":"Kitchen","capacity":%d,
				  "reminderOffsetsMinutes":[1440]}}
				""".formatted(DAY, lunch, khichdi, payasam, volunteersRequested);
	}

	/** "Update this meal" keeping both dishes, with a kitchen note and whatever else is appended. */
	private String update(UUID dish, UUID other, String kitchenNotes, String more) {
		return """
				{"adults":100,"kitchenNotes":"%s",
				 "dishes":[{"id":"%s","recipeId":"%s","targetYield":100},{"id":"%s","recipeId":"%s","targetYield":50}]%s}
				""".formatted(kitchenNotes, dish, khichdi, other, payasam, more);
	}

	private UUID save(String json) throws Exception {
		String body = mvc.perform(authed(post("/api/v1/meals")).contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return MealRequests.idOf(body);
	}

	/**
	 * PostgreSQL refuses every shift write until {@link #dropForcedFailure} — the failure arrives in
	 * the middle of the save's own transaction, after the meal and its dishes are written.
	 */
	private void forceShiftWritesToFail() {
		admin.execute("""
				CREATE OR REPLACE FUNCTION t196_refuse_shift() RETURNS trigger AS $$
				BEGIN
					RAISE EXCEPTION 'T-196 forced failure: shift writes are refused for this test';
				END
				$$ LANGUAGE plpgsql
				""");
		admin.execute("""
				CREATE TRIGGER t196_refuse_shift BEFORE INSERT OR UPDATE ON shifts
				FOR EACH ROW EXECUTE FUNCTION t196_refuse_shift()
				""");
	}

	private void dropForcedFailure() {
		admin.execute("DROP TRIGGER IF EXISTS t196_refuse_shift ON shifts");
		admin.execute("DROP FUNCTION IF EXISTS t196_refuse_shift()");
	}

	private int count(String table) {
		Integer n = admin.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?",
				Integer.class, tenant);
		return n == null ? 0 : n;
	}

	private UUID recipe(String name, UUID category) {
		return admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, ?, ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, name, category);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private void insertUser(String uid, String email, String role, String phone) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE')
				""", tenant, uid, email, phone, role);
	}
}
