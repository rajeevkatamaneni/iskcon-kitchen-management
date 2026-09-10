package org.iskcon.kms.meal;

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
 * Ingredient sufficiency and the shortfall feed (E4-S5): the double-booking guard (two meals can't
 * both claim one sack), badge transitions when stock arrives, and the aggregate shortfall contract.
 */
@AutoConfigureMockMvc
@Import(SufficiencyIT.StubVerifierConfiguration.class)
class SufficiencyIT extends AbstractIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID khichdi;
	private final LocalDate d1 = LocalDate.now(IST).plusDays(2);
	private final LocalDate d2 = LocalDate.now(IST).plusDays(3);

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
		UUID cat = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id
				""", UUID.class, tenant);
		khichdi = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichdi', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, cat);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 5, 'KG', 0)
				""", tenant, khichdi, rice);
		seedReceipt("7"); // 7 KG — enough for one 100-serving Khichdi (5 KG), not two

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
		} finally {
			TenantContext.clear();
		}
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("two meals can't both claim one sack; stock arriving clears the shortfall")
	void doubleBookingGuardAndTransition() throws Exception {
		plan(d1);
		plan(d2);

		String from = LocalDate.now(IST).toString();
		String to = LocalDate.now(IST).plusDays(5).toString();

		// First meal covered, second short by 3 KG (needs 5, only 2 left).
		mvc.perform(get("/api/v1/meal-plans/sufficiency").param("from", from).param("to", to)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].status").value("SUFFICIENT"))
				.andExpect(jsonPath("$[1].status").value("SHORT"))
				.andExpect(jsonPath("$[1].shortfalls[0].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].shortfalls[0].shortBy").value(3))
				.andExpect(jsonPath("$[1].shortfalls[0].available").value(2));

		// A 10 KG delivery arrives — now both are covered.
		seedReceipt("10");
		mvc.perform(get("/api/v1/meal-plans/sufficiency").param("from", from).param("to", to)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$[0].status").value("SUFFICIENT"))
				.andExpect(jsonPath("$[1].status").value("SUFFICIENT"));
	}

	@Test
	@DisplayName("the shortfall feed aggregates the exact quantity the ordering pipeline needs")
	void shortfallFeedContract() throws Exception {
		plan(d1);
		plan(d2);

		mvc.perform(get("/api/v1/meal-plans/shortfall").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[0].shortBy").value(3))
				// Both are data rather than display: the figure is the exact one the ordering
				// pipeline buys against, and the unit is the stored name, as every `unit` field in
				// this API is. Formatting happens where a person reads it (E11-S5).
				.andExpect(jsonPath("$[0].unit").value("KG"));
	}

	/**
	 * The defect T-088 exists for, in the shape it was reported in: six days, ten kilos each, fifty
	 * kilos in the store. The badge used to allocate only across the range it was asked about, and
	 * the day screen asks about one day — so every one of the six, opened on its own, saw the whole
	 * sack and read "Ingredients ready". The store then ran out on a Friday nobody had been warned
	 * about.
	 *
	 * <p>The second half of this test is the one that matters. The first half — asking about all six
	 * at once — passed before the fix as well.
	 */
	@Test
	@DisplayName("six days of 10 KG against a 50 KG sack: the sixth is short, whichever day you open")
	void theSackRunsOutOnTheSixthDayWhicheverDayIsOpened() throws Exception {
		seedReceipt("43"); // 7 from setUp + 43 = 50 KG
		LocalDate[] days = new LocalDate[6];
		for (int i = 0; i < 6; i++) {
			days[i] = LocalDate.now(IST).plusDays(i + 1L);
			plan(days[i], "200"); // 200 of a 100-KG recipe drawing 5 KG of rice = 10 KG each
		}

		mvc.perform(get("/api/v1/meal-plans/sufficiency")
						.param("from", LocalDate.now(IST).toString())
						.param("to", days[5].toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(6))
				.andExpect(jsonPath("$[0].status").value("SUFFICIENT"))
				.andExpect(jsonPath("$[4].status").value("SUFFICIENT"))
				.andExpect(jsonPath("$[5].status").value("SHORT"))
				.andExpect(jsonPath("$[5].shortfalls[0].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[5].shortfalls[0].shortBy").value(10));

		// One day at a time — `api.mealSufficiency(date, date)`, which is what DayView asks.
		for (int i = 0; i < 5; i++) {
			day(days[i]).andExpect(jsonPath("$[0].status").value("SUFFICIENT"));
		}
		day(days[5])
				.andExpect(jsonPath("$[0].status").value("SHORT"))
				.andExpect(jsonPath("$[0].shortfalls[0].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[0].shortfalls[0].shortBy").value(10))
				.andExpect(jsonPath("$[0].shortfalls[0].available").value(0));
	}

	/**
	 * The trap in judging against <em>available</em>: available is on hand minus what the plan has
	 * claimed, and a planned meal is one of those claims. Subtract it and then measure the meal
	 * against what is left and every meal in the temple reports short by exactly its own size — an
	 * answer that looks entirely reasonable on screen and is wrong everywhere.
	 */
	@Test
	@DisplayName("a meal is not reported short because of its own claim")
	void aMealIsNotChargedForItself() throws Exception {
		seedReceipt("43"); // 50 KG for a single meal that draws 5
		plan(d1);

		day(d1)
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].status").value("SUFFICIENT"))
				.andExpect(jsonPath("$[0].shortfalls.length()").value(0));
	}

	/**
	 * The same dish on two days is two claims, not one, and neither is charged for itself. The
	 * numbers here are chosen so that a meal charged for its twin would be wrong in both directions:
	 * 15 KG covers the first 10 KG Khichdi and leaves the second 5 KG short.
	 */
	@Test
	@DisplayName("the same recipe planned on two days excludes only itself")
	void theSameRecipeOnTwoDaysExcludesOnlyItself() throws Exception {
		seedReceipt("8"); // 7 from setUp + 8 = 15 KG
		plan(d1, "200");
		plan(d2, "200");

		// The first is covered — it is not charged for the identical dish behind it.
		day(d1).andExpect(jsonPath("$[0].status").value("SUFFICIENT"));
		// The second is charged for the first, and for the first only: 15 − 10 = 5, needs 10.
		day(d2)
				.andExpect(jsonPath("$[0].status").value("SHORT"))
				.andExpect(jsonPath("$[0].shortfalls[0].shortBy").value(5))
				.andExpect(jsonPath("$[0].shortfalls[0].available").value(5));
	}

	/**
	 * A meal beyond the window the temple is buying for is claimed by nobody, including itself, and
	 * the badge says nothing about stock rather than inventing a comparison: the rice it will be
	 * cooked from has not been bought yet, and marking a Janmashtami plan red for three months is
	 * noise a person cannot act on. It stays out of the ordering feed for the same reason.
	 */
	@Test
	@DisplayName("a meal beyond the buying window is not judged, and does not claim today's stock")
	void aMealBeyondTheHorizonIsNotJudged() throws Exception {
		LocalDate far = LocalDate.now(IST).plusDays(90);
		plan(far, "200"); // 10 KG, against the 7 KG in the store
		plan(d1, "200"); // 10 KG, inside the window

		day(far)
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].status").value("PLANNING"))
				.andExpect(jsonPath("$[0].shortfalls.length()").value(0));

		// The in-window meal sees the whole 7 KG: the far one took nothing from it.
		day(d1)
				.andExpect(jsonPath("$[0].status").value("SHORT"))
				.andExpect(jsonPath("$[0].shortfalls[0].shortBy").value(3));

		// And the ordering feed buys for the fortnight, not for the festival three months out.
		mvc.perform(get("/api/v1/meal-plans/shortfall").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].shortBy").value(3));
	}

	/**
	 * Recording a meal moves its stock through the ledger, so the plan stops being a claim on the
	 * shelf at that moment. The badge stops speaking for it — the planner badges a cooked dish from
	 * its own status — and the rice it was holding is released to the meals behind it, because the
	 * on-hand figure has already paid for it.
	 */
	@Test
	@DisplayName("the badge stops speaking for a meal once it is recorded, and its claim is released")
	void aRecordedMealIsNeitherJudgedNorCounted() throws Exception {
		plan(d1);
		plan(d2);
		day(d2).andExpect(jsonPath("$[0].status").value("SHORT"));

		admin.update("UPDATE meal_plans SET status = 'COOKED' WHERE plan_date = ?", d1);

		day(d1).andExpect(jsonPath("$.length()").value(0));
		day(d2).andExpect(jsonPath("$[0].status").value("SUFFICIENT"));
	}

	@Test
	@DisplayName("a volunteer cannot read sufficiency")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol-a");
		mvc.perform(get("/api/v1/meal-plans/sufficiency")
						.param("from", d1.toString()).param("to", d2.toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private void plan(LocalDate date) throws Exception {
		plan(date, "100");
	}

	/**
	 * {@code targetYield} against a 100 KG recipe drawing 5 KG of rice: 100 plans a 5 KG draw, 200 a
	 * 10 KG one. {@code ekadashiAcknowledged} because these tests plan grains on whichever dates the
	 * calendar hands them, and one of them will eventually be an Ekadashi.
	 */
	private void plan(LocalDate date, String targetYield) throws Exception {
		mvc.perform(post("/api/v1/meal-plans").header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"planDate\":\"" + date + "\",\"mealKind\":\"Lunch\",\"recipeId\":\"" + khichdi
								+ "\",\"targetYield\":" + targetYield
								+ ",\"adults\":100,\"dayType\":\"REGULAR\",\"ekadashiAcknowledged\":true}"))
				.andExpect(status().isCreated());
	}

	/** One day asked about on its own — the question {@code DayView} puts, from=to=the day. */
	private org.springframework.test.web.servlet.ResultActions day(LocalDate date) throws Exception {
		return mvc.perform(get("/api/v1/meal-plans/sufficiency")
						.param("from", date.toString()).param("to", date.toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk());
	}

	private void seedReceipt(String qtyKg) {
		admin.update("""
				INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit,
						movement_type, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, 'KG', 'PO_RECEIPT',
						(SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'))
				""", tenant, rice, UUID.randomUUID(), qtyKg);
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
