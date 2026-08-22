package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.calendar.CalendarService;
import org.iskcon.kms.tenancy.TenantContext;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Recording a whole meal from the returned job card (B5, brief §2), through the full stack.
 *
 * <p>The three things worth proving are the three the brief argues for: that one call records every
 * dish of a meal, that stock is drawn against what actually went out rather than what was planned,
 * and that a dish nobody made draws nothing at all. The refusals matter as much — what has been
 * cooked cannot be recorded twice, and a meal that was called off never went to the kitchen.
 */
@AutoConfigureMockMvc
@Import(MealRecordingIT.StubVerifierConfiguration.class)
class MealRecordingIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private CalendarService calendarService;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID ghee;
	private UUID khichdi;
	private UUID halwa;

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
		insertUser("uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");

		rice = ingredient("Rice");
		ghee = ingredient("Ghee");
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Mains') RETURNING id
				""", UUID.class, tenant);

		// 1 KG of rice per 100 servings, 1 KG of ghee per 100 servings of halwa — small numbers so the
		// arithmetic in the assertions is readable rather than merely correct.
		khichdi = recipe("Khichdi", category);
		line(khichdi, rice, "1");
		halwa = recipe("Halwa", category);
		line(halwa, ghee, "1");

		stock(rice, "10");
		stock(ghee, "10");

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
			calendarService.precomputeForCurrentTenant(LocalDate.of(2025, 1, 1), 100);
		} finally {
			TenantContext.clear();
		}
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM meal_services");
		admin.execute("DELETE FROM meal_card_sequence");
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM calendar_days");
		admin.execute("DELETE FROM calendar_precompute_state");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("one call records every dish of a meal, and draws the actual figure rather than the planned one")
	void recordsTheWholeMealAtTheActualFigure() throws Exception {
		UUID first = plan("Lunch", khichdi, 300);
		UUID second = plan("Lunch", halwa, 300);

		// The hall was smaller than expected. That gap is the whole reason the office types this in.
		mvc.perform(record("""
				{"planDate":"2025-03-17","mealKind":"Lunch","note":"Fewer than expected",
				 "dishes":[{"mealPlanId":"%s","actualServings":220,"notMade":false},
						   {"mealPlanId":"%s","actualServings":250,"notMade":false}]}
				""".formatted(first, second)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recorded").value(true))
				.andExpect(jsonPath("$.recordingNote").value("Fewer than expected"))
				.andExpect(jsonPath("$.dishes.length()").value(2));

		// 2.2 KG of rice and 2.5 KG of ghee — the actual figures, not the 3 KG each that was planned.
		assertThat(consumed(rice)).isEqualByComparingTo("2200");
		assertThat(consumed(ghee)).isEqualByComparingTo("2500");

		mvc.perform(get("/api/v1/meal-plans/{id}", first).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.status").value("COOKED"))
				.andExpect(jsonPath("$.actualServings").value(220.0))
				.andExpect(jsonPath("$.notMade").value(false));
	}

	@Test
	@DisplayName("a dish nobody made draws nothing, and says so rather than disappearing")
	void aDishNotMadeDrawsNothing() throws Exception {
		UUID first = plan("Lunch", khichdi, 200);
		UUID second = plan("Lunch", halwa, 200);

		mvc.perform(record("""
				{"planDate":"2025-03-17","mealKind":"Lunch",
				 "dishes":[{"mealPlanId":"%s","actualServings":200,"notMade":false},
						   {"mealPlanId":"%s","notMade":true}]}
				""".formatted(first, second)))
				.andExpect(status().isOk());

		assertThat(consumed(rice)).isEqualByComparingTo("2000");
		assertThat(consumed(ghee)).isEqualByComparingTo("0");

		mvc.perform(get("/api/v1/meal-plans/{id}", second).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.status").value("CANCELLED"))
				.andExpect(jsonPath("$.notMade").value(true))
				.andExpect(jsonPath("$.actualServings").value(0.0));
	}

	@Test
	@DisplayName("what was cooked can't be recorded again")
	void recordingTwiceIsRefused() throws Exception {
		UUID id = plan("Lunch", khichdi, 100);
		String body = """
				{"planDate":"2025-03-17","mealKind":"Lunch",
				 "dishes":[{"mealPlanId":"%s","actualServings":100,"notMade":false}]}
				""".formatted(id);

		mvc.perform(record(body)).andExpect(status().isOk());
		mvc.perform(record(body))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4962"));

		// And only once against stock, which is the failure the refusal is really preventing.
		assertThat(consumed(rice)).isEqualByComparingTo("1000");
	}

	@Test
	@DisplayName("a meal that was called off never went to the kitchen, so there is nothing to record")
	void recordingACancelledMealIsRefused() throws Exception {
		UUID id = plan("Dinner", khichdi, 100);
		mvc.perform(post("/api/v1/meal-plans/{id}/cancel", id)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isNoContent());

		mvc.perform(record("""
				{"planDate":"2025-03-17","mealKind":"Dinner",
				 "dishes":[{"mealPlanId":"%s","actualServings":100,"notMade":false}]}
				""".formatted(id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4963"));
	}

	@Test
	@DisplayName("a dish the form left out is refused rather than decided on the office's behalf")
	void everyDishMustBeAccountedFor() throws Exception {
		UUID first = plan("Lunch", khichdi, 100);
		plan("Lunch", halwa, 100);

		mvc.perform(record("""
				{"planDate":"2025-03-17","mealKind":"Lunch",
				 "dishes":[{"mealPlanId":"%s","actualServings":100,"notMade":false}]}
				""".formatted(first)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4009"));

		assertThat(consumed(rice)).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("a figure that isn't a number of servings is refused, naming the dish")
	void servingsMustBeAFigure() throws Exception {
		UUID id = plan("Lunch", khichdi, 100);

		mvc.perform(record("""
				{"planDate":"2025-03-17","mealKind":"Lunch",
				 "dishes":[{"mealPlanId":"%s","actualServings":0,"notMade":false}]}
				""".formatted(id)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4009"));
	}

	@Test
	@DisplayName("plates are counted per meal kind from the head count, and never summed across dishes")
	void platesArePerMealKind() throws Exception {
		// Three dishes at 250 each is 250 plates, not 750 — the arithmetic the brief calls out by name.
		plan("Lunch", khichdi, 250, 200, 40, 30);
		plan("Lunch", halwa, 250, 200, 40, 30);
		plan("Breakfast", khichdi, 100, 100, 0, 0);

		mvc.perform(get("/api/v1/meal-services/summary")
						.param("from", "2025-03-17").param("to", "2025-03-17")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				// 200 + 0.6 × 40 + 0.8 × 30 = 248.
				.andExpect(jsonPath("$.platesByMealKind.Lunch").value(248))
				.andExpect(jsonPath("$.platesByMealKind.Breakfast").value(100))
				.andExpect(jsonPath("$.unrecorded").value(2));
	}

	@Test
	@DisplayName("the nudge counts meals that went out and were never written down, and stops counting them once they were")
	void unrecordedCountFallsAsMealsAreRecorded() throws Exception {
		UUID id = plan("Lunch", khichdi, 100);
		plan("Dinner", khichdi, 100);

		mvc.perform(get("/api/v1/meal-services/summary")
						.param("from", "2025-03-17").param("to", "2025-03-17")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.unrecorded").value(2));

		mvc.perform(record("""
				{"planDate":"2025-03-17","mealKind":"Lunch",
				 "dishes":[{"mealPlanId":"%s","actualServings":100,"notMade":false}]}
				""".formatted(id)))
				.andExpect(status().isOk());

		mvc.perform(get("/api/v1/meal-services/summary")
						.param("from", "2025-03-17").param("to", "2025-03-17")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.unrecorded").value(1));
	}

	// ---------------------------------------------------------------------

	private UUID plan(String kind, UUID recipe, int servings) {
		return plan(kind, recipe, servings, null, null, null);
	}

	private UUID plan(String kind, UUID recipe, int servings,
			Integer adults, Integer children, Integer seniors) {
		return admin.queryForObject("""
				INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, ready_by, recipe_id,
						target_yield, day_type, status, adults, children, seniors, created_by)
				VALUES (?, DATE '2025-03-17', ?, TIME '12:00', ?, ?, 'REGULAR', 'PLANNED', ?, ?, ?,
						(SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'))
				RETURNING id
				""", UUID.class, tenant, kind, recipe, BigDecimal.valueOf(servings),
				adults, children, seniors);
	}

	private BigDecimal consumed(UUID ingredient) {
		return admin.queryForObject("""
				SELECT COALESCE(-SUM(quantity * CASE unit WHEN 'KG' THEN 1000 ELSE 1 END), 0)
				FROM stock_movements
				WHERE ingredient_id = ? AND movement_type = 'CONSUMPTION'
				""", BigDecimal.class, ingredient);
	}

	private UUID ingredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Staples', 'KG') RETURNING id
				""", UUID.class, tenant, name);
	}

	private UUID recipe(String name, UUID category) {
		return admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, ?, ?, 100, 'SERVINGS') RETURNING id
				""", UUID.class, tenant, name, category);
	}

	private void line(UUID recipe, UUID ingredient, String quantity) {
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, CAST(? AS numeric), 'KG', 0)
				""", tenant, recipe, ingredient, quantity);
	}

	private void stock(UUID ingredient, String kilos) {
		admin.update("""
				INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit,
						movement_type, actor_user_id)
				VALUES (?, ?, ?, CAST(? AS numeric), 'KG', 'PO_RECEIPT',
						(SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'))
				""", tenant, ingredient, UUID.randomUUID(), kilos);
	}

	private MockHttpServletRequestBuilder record(String json) {
		return post("/api/v1/meal-services/record").header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private void insertUser(String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenant, uid, email, role);
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
