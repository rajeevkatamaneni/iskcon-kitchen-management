package org.iskcon.kms.meal;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A festival meal as a kind of meal, and the menu it carries (items 26 and 26b).
 *
 * <p>The feast is planned as a <em>kind</em> — the one flagged {@code needsOccasion} — and not as a
 * day type, so that a temple can serve an ordinary breakfast and a feast on the same Janmashtami.
 * What that buys is the read tested here: naming the occasion is what lets the composer offer back
 * the eighteen preparations somebody spent an hour assembling last year.
 */
@AutoConfigureMockMvc
class MenuHistoryIT extends AbstractIntegrationTest {

	private static final String LAST_YEAR = "2025-08-26";
	private static final String THIS_YEAR = "2026-08-14";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID khichdi;
	private UUID payasam;
	private UUID halwa;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Temple Admin', 'admin@example.com', '+919876500001',
						'TEMPLE_ADMIN', 'ACTIVE')
				""", tenant);

		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Feast') RETURNING id
				""", UUID.class, tenant);
		khichdi = recipe("Khichdi", category);
		payasam = recipe("Payasam", category);
		halwa = recipe("Halwa", category);

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
		signIn("uid-admin");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a feast is a kind of meal, and it insists on naming the festival it is for")
	void theFeastIsAKindThatNamesItsOccasion() throws Exception {
		// Ordered by sort_order: Breakfast, Lunch, Dinner, then the feast, then the kinds that are not
		// a sitting at all — the deity offering and, since E4-S15 folded the other two into it, the
		// event.
		mvc.perform(authed(get("/api/v1/meal-kinds")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[3].name").value("Festival feast"))
				.andExpect(jsonPath("$[3].needsOccasion").value(true))
				// Never at the same hour twice, so it always asks — V48's rule for occasional kinds.
				.andExpect(jsonPath("$[3].defaultReadyTime").doesNotExist())
				// Lunch is untouched. A feast being a kind does not make every meal one.
				.andExpect(jsonPath("$[1].name").value("Lunch"))
				.andExpect(jsonPath("$[1].needsOccasion").value(false));

		// The calendar here knows nothing of 14 August, so there is nothing to fall back on and the
		// planner must say which festival this is.
		mvc.perform(createRequest("""
				{"planDate":"%s","mealKind":"Festival feast","recipeId":"%s","targetYield":800,"adults":800,
				 "readyBy":"12:30"}
				""".formatted(THIS_YEAR, khichdi)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));

		// Named, it plans — including a name no calendar carries, which is the point of the field
		// being pickable at all.
		mvc.perform(createRequest("""
				{"planDate":"%s","mealKind":"Festival feast","recipeId":"%s","targetYield":800,"adults":800,
				 "readyBy":"12:30","occasionName":"Temple anniversary"}
				""".formatted(THIS_YEAR, khichdi)))
				.andExpect(status().isCreated());

		mvc.perform(authed(get("/api/v1/meals").param("from", THIS_YEAR).param("to", THIS_YEAR)))
				.andExpect(jsonPath("$[0].occasionName").value("Temple anniversary"));
	}

	@Test
	@DisplayName("last year's menu comes back, and the preparations that are gone are counted out loud")
	void lastYearsMenuCarriesAndSaysWhatIsMissing() throws Exception {
		feast(LAST_YEAR, khichdi);
		feast(LAST_YEAR, payasam);
		feast(LAST_YEAR, halwa);

		// Recipes became removable in cf629fe, so a menu can genuinely come back shorter than it went
		// in. Two of last year's three survive.
		admin.update("UPDATE recipes SET status = 'ARCHIVED' WHERE id = ?", halwa);

		mvc.perform(authed(get("/api/v1/meal-plans/menu-history")
						.param("occasionName", "Janmashtami").param("before", THIS_YEAR)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.occasionName").value("Janmashtami"))
				.andExpect(jsonPath("$.lastCookedOn").value(LAST_YEAR))
				.andExpect(jsonPath("$.mealKind").value("Festival feast"))
				.andExpect(jsonPath("$.preparationCount").value(3))
				.andExpect(jsonPath("$.missingCount").value(1))
				.andExpect(jsonPath("$.preparations.length()").value(2))
				.andExpect(jsonPath("$.preparations[0].recipeName").value("Khichdi"))
				.andExpect(jsonPath("$.preparations[1].recipeName").value("Payasam"))
				// The meal that was cooked, by its own id (D-27): the three dishes above are its dishes
				// and nobody else's.
				.andExpect(jsonPath("$.mealId").value(admin.queryForObject(
						"SELECT DISTINCT meal_id FROM meal_dishes", UUID.class).toString()));
	}

	@Test
	@DisplayName("the occasion is matched on its name, whatever case it was typed in")
	void matchedOnTheNameAndNotAnId() throws Exception {
		// V48's choice, for V22's reason: occasion_name is denormalized so that removing an occasion
		// never orphans the plan, and the feasts cooked under it keep reading as what they were.
		feast(LAST_YEAR, khichdi);

		mvc.perform(authed(get("/api/v1/meal-plans/menu-history")
						.param("occasionName", "  janmashtami ").param("before", THIS_YEAR)))
				.andExpect(status().isOk())
				// Answered with the spelling on the meal that was cooked, not the one that was typed.
				.andExpect(jsonPath("$.occasionName").value("Janmashtami"))
				.andExpect(jsonPath("$.preparations.length()").value(1));
	}

	@Test
	@DisplayName("the first ever Janmashtami has nothing to offer, and says so rather than refusing")
	void theFirstOneOffersNothing() throws Exception {
		mvc.perform(authed(get("/api/v1/meal-plans/menu-history")
						.param("occasionName", "Radhastami").param("before", THIS_YEAR)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.lastCookedOn").doesNotExist())
				.andExpect(jsonPath("$.preparationCount").value(0))
				.andExpect(jsonPath("$.preparations.length()").value(0));
	}

	@Test
	@DisplayName("the meal being composed is never offered back to itself")
	void thisYearsHalfBuiltMenuIsNotTheAnswer() throws Exception {
		feast(LAST_YEAR, khichdi);
		feast(LAST_YEAR, payasam);
		// This year's feast, one preparation in. It carries the same occasion name from the moment it
		// is saved, and without the date guard it would be the most recent match.
		feast(THIS_YEAR, halwa);

		mvc.perform(authed(get("/api/v1/meal-plans/menu-history")
						.param("occasionName", "Janmashtami").param("before", THIS_YEAR)))
				.andExpect(jsonPath("$.lastCookedOn").value(LAST_YEAR))
				.andExpect(jsonPath("$.preparationCount").value(2));
	}

	@Test
	@DisplayName("on a festival day of several meals, the feast is the menu that comes back")
	void theBiggestMealOfTheDayWins() throws Exception {
		feast(LAST_YEAR, khichdi);
		feast(LAST_YEAR, payasam);

		// The state a real Janmashtami leaves behind: the day is a festival, so the derivation writes
		// the occasion onto every meal of it — the ordinary breakfast and the ordinary dinner included.
		mvc.perform(createRequest("""
				{"planDate":"%s","mealKind":"Dinner","recipeId":"%s","targetYield":200,"adults":200}
				""".formatted(LAST_YEAR, halwa)))
				.andExpect(status().isCreated());
		admin.update("""
				UPDATE meals m SET occasion_name = 'Janmashtami'
				FROM meal_plan_days pd, meal_kinds k
				WHERE pd.id = m.meal_plan_day_id AND k.id = m.meal_kind_id
				  AND pd.plan_date = ?::date AND k.name = 'Dinner'
				""", LAST_YEAR);

		// Dinner is later in the day and would win on the clock. It loses on the only measure that
		// matters here: nobody spends an hour reassembling a one-preparation dinner.
		mvc.perform(authed(get("/api/v1/meal-plans/menu-history")
						.param("occasionName", "Janmashtami").param("before", THIS_YEAR)))
				.andExpect(jsonPath("$.mealKind").value("Festival feast"))
				.andExpect(jsonPath("$.preparationCount").value(2));
	}

	@Test
	@DisplayName("a preparation called off is not part of what was cooked")
	void cancelledPreparationsAreLeftOut() throws Exception {
		feast(LAST_YEAR, khichdi);
		feast(LAST_YEAR, payasam);
		admin.update("UPDATE meal_dishes SET status = 'CANCELLED' WHERE recipe_id = ?", payasam);

		mvc.perform(authed(get("/api/v1/meal-plans/menu-history")
						.param("occasionName", "Janmashtami").param("before", THIS_YEAR)))
				.andExpect(jsonPath("$.preparationCount").value(1))
				.andExpect(jsonPath("$.preparations[0].recipeName").value("Khichdi"));
	}

	// ---- helpers ----------------------------------------------------------

	private void feast(String date, UUID recipeId) throws Exception {
		mvc.perform(createRequest("""
				{"planDate":"%s","mealKind":"Festival feast","recipeId":"%s","targetYield":800,"adults":800,
				 "readyBy":"12:30","occasionName":"Janmashtami"}
				""".formatted(date, recipeId)))
				.andExpect(status().isCreated());
	}

	private UUID recipe(String name, UUID category) {
		return admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, ?, ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, name, category);
	}

	private MockHttpServletRequestBuilder createRequest(String json) {
		return authed(post("/api/v1/meals")).contentType(MediaType.APPLICATION_JSON)
				.content(MealRequests.save(json, admin, tenant));
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

}
