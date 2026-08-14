package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.calendar.CalendarService;
import org.iskcon.kms.occasion.OccasionService;
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
 * Meal planning (E4-S4) through the full stack: day-type auto-suggestion from the calendar, catering
 * client capture, and the mark-cooked → consumption → status flow with its guard rails.
 */
@AutoConfigureMockMvc
@Import(MealPlanIT.StubVerifierConfiguration.class)
class MealPlanIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private OccasionService occasionService;

	@Autowired
	private CalendarService calendarService;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
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
		insertUser("uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser("uid-vol-a", "vol-a@example.com", "VOLUNTEER");

		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant);
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id
				""", UUID.class, tenant);
		khichdi = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichdi', ?, 100, 'SERVINGS') RETURNING id
				""", UUID.class, tenant, category);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 5, 'KG', 0)
				""", tenant, khichdi, rice);
		// 10 KG rice in stock.
		admin.update("""
				INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit,
						movement_type, actor_user_id)
				VALUES (?, ?, ?, 10, 'KG', 'PO_RECEIPT',
						(SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'))
				""", tenant, rice, UUID.randomUUID());

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
			occasionService.seedForCurrentTenant();
			calendarService.precomputeForCurrentTenant(LocalDate.of(2025, 1, 1), 100);
		} finally {
			TenantContext.clear();
		}
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM occasions");
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
	@DisplayName("day-context suggests festival on Gaura Purnima and regular on a weekday")
	void dayContextSuggestsFromCalendar() throws Exception {
		mvc.perform(get("/api/v1/meal-plans/day-context").param("date", "2025-03-14")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.suggestedDayType").value("FESTIVAL"))
				.andExpect(jsonPath("$.occasionName").value("Gaura Purnima"))
				.andExpect(jsonPath("$.suggestedServings").value(1000));

		mvc.perform(get("/api/v1/meal-plans/day-context").param("date", "2025-03-17") // a Monday
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.suggestedDayType").value("REGULAR"));
	}

	@Test
	@DisplayName("planning on a festival date auto-tags the day-type and records the occasion")
	void planningFestivalAutoTags() throws Exception {
		UUID id = create("""
				{"planDate":"2025-03-14","mealKind":"Lunch","recipeId":"%s","targetServings":800}
				""".formatted(khichdi));

		mvc.perform(get("/api/v1/meal-plans/{id}", id).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.dayType").value("FESTIVAL"))
				.andExpect(jsonPath("$.occasionName").value("Gaura Purnima"));
	}

	@Test
	@DisplayName("catering is a kind of meal: it needs a client, a venue and a time, and nobody picks a day type")
	void cateringCapturesClient() throws Exception {
		// No client — refused by the kind's own rule, not by anything about the date.
		mvc.perform(createRequest("""
				{"planDate":"2025-03-20","mealKind":"Catering order","recipeId":"%s","targetServings":200,
				 "readyBy":"11:00","venue":"Community Hall"}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4944"));

		// No venue — food leaving the temple has to say where it is going.
		mvc.perform(createRequest("""
				{"planDate":"2025-03-20","mealKind":"Catering order","recipeId":"%s","targetServings":200,
				 "readyBy":"11:00","clientName":"Sharma Wedding"}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4945"));

		create("""
				{"planDate":"2025-03-20","mealKind":"Catering order","recipeId":"%s","targetServings":200,
				 "readyBy":"11:00","clientName":"Sharma Wedding","venue":"Community Hall"}
				""".formatted(khichdi));

		// The day type was derived from the kind, never sent by the client.
		mvc.perform(get("/api/v1/meal-plans").param("dayType", "CATERING")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].clientName").value("Sharma Wedding"))
				.andExpect(jsonPath("$[0].readyBy").value("11:00:00"));
	}

	@Test
	@DisplayName("an everyday meal takes the temple's time; an occasional one insists on being given one")
	void readyByComesFromTheKindOrIsRequired() throws Exception {
		// Lunch has a temple default, so planning one need not state a time.
		UUID lunch = create("""
				{"planDate":"2025-03-17","mealKind":"Lunch","recipeId":"%s","targetServings":100}
				""".formatted(khichdi));
		mvc.perform(get("/api/v1/meal-plans/{id}", lunch).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.readyBy").value("12:00:00"));

		// A deity offering has none — guessing would be worse than asking.
		mvc.perform(createRequest("""
				{"planDate":"2025-03-17","mealKind":"Deity Offering","recipeId":"%s","targetServings":20}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4943"));

		UUID offering = create("""
				{"planDate":"2025-03-17","mealKind":"Deity Offering","recipeId":"%s","targetServings":20,
				 "readyBy":"05:30"}
				""".formatted(khichdi));
		mvc.perform(get("/api/v1/meal-plans/{id}", offering).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.readyBy").value("05:30:00"));
	}

	@Test
	@DisplayName("marking cooked draws stock and flips status; a cooked meal can't be cancelled")
	void markCookedDrawsStockAndLocks() throws Exception {
		UUID id = create("""
				{"planDate":"2025-03-17","mealKind":"Lunch","recipeId":"%s","targetServings":100}
				""".formatted(khichdi));

		mvc.perform(post("/api/v1/meal-plans/{id}/cooked", id).header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sufficient").value(true));

		// 10 KG - 5 KG drawn = 5 KG left.
		assertThat(admin.queryForObject("""
				SELECT COALESCE(SUM(quantity * CASE unit WHEN 'KG' THEN 1000 ELSE 1 END),0)
				FROM stock_movements WHERE ingredient_id = ?
				""", java.math.BigDecimal.class, rice)).isEqualByComparingTo("5000");

		mvc.perform(get("/api/v1/meal-plans/{id}", id).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.status").value("COOKED"));

		mvc.perform(post("/api/v1/meal-plans/{id}/cancel", id).header("Authorization", "Bearer valid-token"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4914"));
	}

	@Test
	@DisplayName("marking cooked is refused, all-or-nothing, when stock is short")
	void markCookedShortIsRefused() throws Exception {
		UUID id = create("""
				{"planDate":"2025-03-17","mealKind":"Dinner","recipeId":"%s","targetServings":1000}
				""".formatted(khichdi)); // needs 50 KG, only 10 available

		mvc.perform(post("/api/v1/meal-plans/{id}/cooked", id).header("Authorization", "Bearer valid-token"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4911"));

		// Nothing drawn, status unchanged.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM stock_movements WHERE movement_type = 'CONSUMPTION'", Integer.class))
				.isZero();
		mvc.perform(get("/api/v1/meal-plans/{id}", id).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.status").value("PLANNED"));
	}

	@Test
	@DisplayName("a kind the temple doesn't have is refused, and a volunteer cannot plan")
	void slotValidationAndPermission() throws Exception {
		mvc.perform(createRequest("""
				{"planDate":"2025-03-17","mealKind":"Brunch","recipeId":"%s","targetServings":50}
				""".formatted(khichdi)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4942"));

		signIn("uid-vol-a");
		mvc.perform(createRequest("""
				{"planDate":"2025-03-17","mealKind":"Lunch","recipeId":"%s","targetServings":50}
				""".formatted(khichdi)))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private UUID create(String json) throws Exception {
		String body = mvc.perform(createRequest(json)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private MockHttpServletRequestBuilder createRequest(String json) {
		return post("/api/v1/meal-plans").header("Authorization", "Bearer valid-token")
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
