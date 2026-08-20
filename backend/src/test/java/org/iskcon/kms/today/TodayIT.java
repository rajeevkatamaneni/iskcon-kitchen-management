package org.iskcon.kms.today;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.meal.MealKindService;
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

/**
 * The Today screen's read model (E4-S8), end to end.
 *
 * <p>Dated against the real today on purpose: the endpoint takes no date, because "today" is the
 * whole point of it, and a screen that silently reads yesterday is the failure worth catching.
 */
@AutoConfigureMockMvc
@Import(TodayIT.StubVerifierConfiguration.class)
class TodayIT extends AbstractIntegrationTest {

	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private OccasionService occasionService;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID khichdi;
	private LocalDate today;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		today = LocalDate.now(TEMPLE_ZONE);

		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('today-temple', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		insertUser("uid-admin", "admin@example.com", "TEMPLE_ADMIN");
		insertUser("uid-staff", "staff@example.com", "KITCHEN_STAFF");
		insertUser("uid-vol", "vol@example.com", "VOLUNTEER");

		UUID rice = admin.queryForObject("""
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

		// Tracked, with a threshold, and nothing received — so it is below it.
		admin.update("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold)
				VALUES (?, ?, 25)
				""", tenant, rice);

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
			occasionService.seedForCurrentTenant();
		} finally {
			TenantContext.clear();
		}
		signIn("uid-staff");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM shift_signups");
		admin.execute("DELETE FROM shifts");
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM occasions");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("today's meals come back in ready-by order, with the plates they add up to")
	void mealsInReadyByOrder() throws Exception {
		planMeal("Dinner", 400);
		planMeal("Lunch", 800);

		mvc.perform(get("/api/v1/today").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.date").value(today.toString()))
				.andExpect(jsonPath("$.meals.length()").value(2))
				.andExpect(jsonPath("$.meals[0].mealKind").value("Lunch"))
				.andExpect(jsonPath("$.meals[0].readyBy").value("12:00:00"))
				.andExpect(jsonPath("$.meals[0].recorded").value(false))
				.andExpect(jsonPath("$.meals[0].dishes.length()").value(1))
				.andExpect(jsonPath("$.meals[1].mealKind").value("Dinner"))
				.andExpect(jsonPath("$.platesToday").value(1200));
	}

	@Test
	@DisplayName("a meal of several dishes is one meal, and its plates are not their sum")
	void platesAreCountedPerMealNotPerDish() throws Exception {
		// The bug this replaces: a lunch of three dishes at 250 servings each reported 750 plates,
		// because Today summed the dish rows rather than reading the head count once (§1d).
		planMeal("Lunch", 250);
		planMeal("Lunch", 250);
		planMeal("Lunch", 250);

		mvc.perform(get("/api/v1/today").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meals.length()").value(1))
				.andExpect(jsonPath("$.meals[0].mealKind").value("Lunch"))
				.andExpect(jsonPath("$.meals[0].dishes.length()").value(3))
				.andExpect(jsonPath("$.meals[0].plates").value(250))
				.andExpect(jsonPath("$.platesToday").value(250));
	}

	@Test
	@DisplayName("a meal planned for another day is not today's work")
	void onlyToday() throws Exception {
		planMealOn(today.plusDays(1), "Lunch", 500);

		mvc.perform(get("/api/v1/today").header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.meals.length()").value(0))
				.andExpect(jsonPath("$.platesToday").value(0));
	}

	@Test
	@DisplayName("counts what is about to run out, and who is actually in today")
	void stockAndWorkforce() throws Exception {
		admin.update("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity, created_by)
				VALUES (?, 'Morning prep', ?, '07:00', '11:00', 6,
						(SELECT id FROM users WHERE firebase_uid = 'uid-admin'))
				""", tenant, today);

		mvc.perform(get("/api/v1/today").header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.itemsBelowThreshold").value(1))
				.andExpect(jsonPath("$.itemsTracked").value(1))
				// Nobody has signed up for that shift, so the volunteer count is honestly zero —
				// a posted shift is not a person in the kitchen (B1).
				.andExpect(jsonPath("$.workforce.volunteers").value(0))
				.andExpect(jsonPath("$.workforce.staffIn").isNumber());
	}

	@Test
	@DisplayName("the cost of today's food is an estimate that admits what it could not price")
	void materialsCostNamesTheGap() throws Exception {
		// The only ingredient this temple tracks has no vendor supplying it, so the basket is
		// entirely unpriced — and the screen has to say so rather than report a confident zero.
		planMeal("Lunch", 100);

		mvc.perform(get("/api/v1/today").header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.materialsCost.estimatedTotal").isNumber())
				.andExpect(jsonPath("$.materialsCost.withoutPrice").isNumber());
	}

	@Test
	@DisplayName("an order due today is awaited; one due next week is not today's problem")
	void deliveriesDueToday() throws Exception {
		UUID vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Govind Wholesale', '+919876500001')
				RETURNING id
				""", UUID.class, tenant);
		insertSentOrder(vendor, "PO-2026-0001", today);
		insertSentOrder(vendor, "PO-2026-0002", today.plusDays(7));

		mvc.perform(get("/api/v1/today").header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.deliveries.length()").value(1))
				.andExpect(jsonPath("$.deliveries[0].poNumber").value("PO-2026-0001"))
				.andExpect(jsonPath("$.deliveries[0].vendorName").value("Govind Wholesale"))
				.andExpect(jsonPath("$.deliveries[0].state").value("AWAITED"));
	}

	@Test
	@DisplayName("giving has left this screen for the one somebody opens to look at money")
	void givingIsNoLongerHere() throws Exception {
		admin.update("""
				INSERT INTO donations (tenant_id, type, donor_name, amount_inr, donated_on, status, recorded_by)
				VALUES (?, 'ONE_TIME', 'A Devotee', 5000, ?, 'COMPLETED',
						(SELECT id FROM users WHERE firebase_uid = 'uid-admin'))
				""", tenant, today);

		// Money coming in lives on the donations ledger, where it has a period control and a
		// year-on-year comparison; a month-to-date figure on a morning screen was neither (§8).
		signIn("uid-admin");
		mvc.perform(get("/api/v1/today").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.giving").doesNotExist());
	}

	@Test
	@DisplayName("a volunteer has no temple day to run")
	void volunteerRefused() throws Exception {
		signIn("uid-vol");
		mvc.perform(get("/api/v1/today").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("a temple with nothing in it yet answers with empties, not an error")
	void emptyTemple() throws Exception {
		admin.execute("DELETE FROM inventory_items");

		mvc.perform(get("/api/v1/today").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meals.length()").value(0))
				// Nothing below par *because nothing is tracked* — the screen tells the two apart.
				.andExpect(jsonPath("$.itemsBelowThreshold").value(0))
				.andExpect(jsonPath("$.itemsTracked").value(0))
				.andExpect(jsonPath("$.workforce.staffIn").value(0))
				.andExpect(jsonPath("$.workforce.volunteers").value(0))
				.andExpect(jsonPath("$.materialsCost.estimatedTotal").value(0))
				.andExpect(jsonPath("$.unrecordedMeals").value(0))
				.andExpect(jsonPath("$.deliveries.length()").value(0))
				// No calendar computed for this temple, so the screen says nothing about fasting
				// rather than asserting there is none.
				.andExpect(jsonPath("$.calendar").doesNotExist());
	}

	// ---------------------------------------------------------------------

	private void planMeal(String kind, int servings) throws Exception {
		planMealOn(today, kind, servings);
	}

	private void planMealOn(LocalDate date, String kind, int servings) throws Exception {
		mvc.perform(post("/api/v1/meal-plans").header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"%s","mealKind":"%s","recipeId":"%s","targetServings":%d}
								""".formatted(date, kind, khichdi, servings)))
				.andExpect(status().isCreated());
	}

	private void insertSentOrder(UUID vendor, String poNumber, LocalDate neededBy) {
		admin.update("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, order_date,
						needed_by, created_by)
				VALUES (?, ?, ?, 'SENT', ?, ?,
						(SELECT id FROM users WHERE firebase_uid = 'uid-admin'))
				""", tenant, poNumber, vendor, today, neededBy);
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private void insertUser(String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500082', ?, 'ACTIVE')
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
				throw new InvalidTokenException("no such token");
			}
			return subject;
		}
	}
}
