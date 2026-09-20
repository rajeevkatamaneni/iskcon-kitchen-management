package org.iskcon.kms.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.meal.MealFixture;
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

/**
 * A countable thing is whole wherever the application <em>produces</em> a number (T-425) — the other
 * half of {@code PartOfACountedThingIT}, which covers every door a <em>person</em> types one at.
 *
 * <p>Seeding staging left the stock screen reading Banana 16.78, Coconut 400.98 and Lemon 170.74 on
 * hand, and nobody had typed any of them. A dish for 140 people scaled from a recipe written for 200
 * drew 0.78 of a banana out of the store. Refusing the fractions a person enters does nothing about
 * that: a real temple would see exactly the same thing on its first day, because the figures are the
 * scaler's own.
 *
 * <p><strong>Everything below asks the same recipe the same question through a different screen.</strong>
 * A scaled counted requirement is produced in exactly one place —
 * {@link RecipeScaler#scale} — and every consumer reads {@code ScaledLine.rawQuantity} from it: the
 * recipe scale preview, the job card, the cooking draw, the committed-stock claim behind the
 * planner's badge, and the materials-cost basket. So the assertion this file exists to make is not
 * that each of them rounds, but that <strong>each of them says the same whole number as the others,
 * and as the money</strong>. Five screens disagreeing about one coconut is the defect; five screens
 * agreeing on two coconuts is the fix.
 *
 * <p>Three things it also has to prove, because none of them follows from the others:
 *
 * <ul>
 *   <li><strong>Scale first, round once.</strong> A quarter of a coconut a head across 800 heads is
 *       200 coconuts. {@code RecipeScalerTest.scaleFirstThenRoundOnce} pins the arithmetic; this file
 *       pins it through the endpoint a cook actually opens.
 *   <li><strong>A fractional kilo is still fractional.</strong> The rule is keyed on
 *       {@link org.iskcon.kms.ingredient.Unit.Family#COUNT}, and a leak into mass would round every
 *       scaled kilo of rice up to a whole one on every screen at once.
 *   <li><strong>The rows already written still work.</strong> There is no migration. A lot holding
 *       2.5 of a counted thing — which is what staging holds — is still summed, still shown and still
 *       drawable, and a FEFO draw still splits a whole requirement across it as a fraction, because
 *       that is the application's own arithmetic and not a figure anybody entered.
 * </ul>
 */
@AutoConfigureMockMvc
class WholeCountedRequirementIT extends AbstractIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID actor;
	private UUID category;

	/** Counted one by one, and priced, so the money can be checked against the quantity. */
	private UUID coconut;

	/** Measured, not counted. Every "the rule did not leak" assertion is about this one. */
	private UUID rice;

	/** Coconut Rice: 100 Kg from 3 coconuts and 10 Kg of rice. */
	private UUID coconutRice;

	private final LocalDate day = LocalDate.now(IST).plusDays(1);

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		actor = insertUser("uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");

		coconut = ingredient("Coconut", "PIECES");
		rice = ingredient("Rice", "KG");

		// ₹35 a coconut and ₹45 a kilo of rice, from the vendor the temple has named. Round figures
		// on purpose: every rupee in this file is meant to be checkable by hand.
		UUID vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Govind Wholesale', '+919812345678')
				RETURNING id
				""", UUID.class, tenant);
		supply(vendor, coconut, "35.00");
		supply(vendor, rice, "45.00");

		category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id
				""", UUID.class, tenant);
		coconutRice = recipe("Coconut Rice", 100);
		line(coconutRice, coconut, "3", "PIECES", 0);
		line(coconutRice, rice, "10", "KG", 1);

		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	/**
	 * <strong>The headline, through the screen a cook opens.</strong>
	 *
	 * <p>A quarter of a coconut a head is an ordinary line. Scaled to 800 heads it is 200 coconuts.
	 * It is 800 if the quarter is made whole before the multiply, which is four times what the temple
	 * needs — the exact mistake "round where the number is produced" invites if it is read as "round
	 * early". The ratio is applied to the line first and the count is made whole once, last.
	 *
	 * <p>Both fields are asserted because the whole defect was that they could differ: the page
	 * printed {@code displayQuantity} while the stock draw took {@code rawQuantity}.
	 */
	@Test
	@DisplayName("a quarter of a coconut a head, scaled to 800 heads, is 200 coconuts — not 800")
	void scaleFirstAndRoundOnce() throws Exception {
		UUID perHead = recipe("Coconut Chutney", 1);
		line(perHead, coconut, "0.25", "PIECES", 0);

		mvc.perform(get("/api/v1/recipes/{id}/scaled", perHead)
						.param("targetYield", "800")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ingredients[0].rawQuantity").value(200))
				.andExpect(jsonPath("$.ingredients[0].displayQuantity").value(200))
				.andExpect(jsonPath("$.ingredients[0].displayUnit").value("pieces"));
	}

	/**
	 * <strong>Five screens, one coconut requirement, and they have to agree.</strong>
	 *
	 * <p>Coconut Rice is 3 coconuts per 100 Kg. Cooked at 40 Kg the recipe needs 1.2 of them, which
	 * is the shape of every figure on the staging stock screen. The temple takes two coconuts off the
	 * shelf, and every screen that mentions the dish says two.
	 *
	 * <p>The job card is asserted on its printed text rather than on a field, because that sheet is
	 * the one a cook is holding at the stove: it is generated through {@code Quantities.cooks}, which
	 * rounds a count to nearest for display, and a raw 1.2 printed there as "1 piece" while the store
	 * was charged 1.2 was the two-stories defect at its most visible.
	 */
	@Test
	@DisplayName("the preview, the job card, the draw, the badge and the money all say two coconuts")
	void everyScreenSaysTheSameWholeNumber() throws Exception {
		UUID dish = plan(coconutRice, "40", 40);

		// 1. The recipe scale preview.
		mvc.perform(get("/api/v1/recipes/{id}/scaled", coconutRice)
						.param("targetYield", "40")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ingredients[?(@.ingredientName=='Coconut')].rawQuantity").value(2))
				.andExpect(jsonPath("$.ingredients[?(@.ingredientName=='Coconut')].displayQuantity").value(2))
				// The control, on the same recipe and the same request: 10 Kg of rice at 0.4 is 4 Kg,
				// which divides and is left alone.
				.andExpect(jsonPath("$.ingredients[?(@.ingredientName=='Rice')].rawQuantity").value(4.0));

		// 2. The job card the cook is handed.
		String card = mvc.perform(get("/api/v1/job-cards/print")
						.param("mealId", MealFixture.mealOf(admin, dish).toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(card).as("the sheet at the stove asks for whole coconuts").contains("2 pieces");
		assertThat(card).as("and it never asks for part of one").doesNotContain("1.2 pieces");

		// 3. The planner's badge, which reads the committed-stock claim.
		mvc.perform(get("/api/v1/meal-plans/sufficiency")
						.param("from", day.toString())
						.param("to", day.toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].status").value("SHORT"))
				.andExpect(jsonPath("$[0].shortfalls[?(@.ingredientName=='Coconut')].required").value(2));

		// 4. The money, at ₹35 a coconut and ₹45 a kilo: 2 x 35 + 4 x 45 = ₹250.00. On the
		//    unrounded 1.2 coconuts it was 1.2 x 35 + 180 = ₹222.00, so the estimate rises by ₹28
		//    and now matches the two coconuts the kitchen is actually told to take. A cost worked out
		//    from a quantity nobody is allowed to draw is the worse of the two answers.
		mvc.perform(get("/api/v1/materials-cost")
						.param("date", day.toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estimatedTotal").value(250.00))
				.andExpect(jsonPath("$.ingredientsPriced").value(2));

		// ...and per serving, across the 40 people the planner counted: ₹250.00 / 40 = ₹6.25.
		// It was ₹222.00 / 40 = ₹5.55.
		mvc.perform(get("/api/v1/materials-cost/by-meal-kind")
						.param("from", day.toString())
						.param("to", day.toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kinds[0].costPerServing").value(6.25));

		// 5. The draw itself. Two whole coconuts leave the store, as one movement of two.
		seedReceipt(coconut, UUID.randomUUID(), "10", "PIECES", day.plusDays(30));
		seedReceipt(rice, UUID.randomUUID(), "50", "KG", day.plusDays(30));

		mvc.perform(post("/api/v1/inventory/consumption")
						.header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"recipeId\":\"" + coconutRice + "\",\"targetYield\":40}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.lines[?(@.ingredientName=='Coconut')].required").value(2));

		assertThat(onHand(coconut)).as("10 coconuts less the 2 the dish took").isEqualByComparingTo("8");
		assertThat(admin.queryForObject("""
				SELECT quantity FROM stock_movements
				WHERE ingredient_id = ? AND movement_type = 'CONSUMPTION'
				""", BigDecimal.class, coconut))
				.as("the ledger row is a whole number too, not 1.2")
				.isEqualByComparingTo("-2");
	}

	/**
	 * <strong>The negative assertion, and the one most worth having.</strong>
	 *
	 * <p>Most of what a temple cooks with is weighed. If the rule leaked out of
	 * {@link org.iskcon.kms.ingredient.Unit.Family#COUNT} it would round 2.4 Kg of rice to 3 on every
	 * screen and buy a third more of everything, quietly.
	 *
	 * <p>It asserts an absence, so the vectors are chosen so that each of them <em>would</em> move if
	 * the rule leaked — every scaled figure here is fractional in its own unit. Three of them, in
	 * both convertible families, because one case only proves the family it is in.
	 */
	@Test
	@DisplayName("a fractional Kg or litre requirement is untouched")
	void aMeasuredRequirementIsStillFractional() throws Exception {
		UUID milk = ingredient("Milk", "L");
		UUID payasam = recipe("Payasam", 100);
		line(payasam, rice, "6", "KG", 0);
		line(payasam, milk, "1", "L", 1);

		mvc.perform(get("/api/v1/recipes/{id}/scaled", payasam)
						.param("targetYield", "40")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ingredients[?(@.ingredientName=='Rice')].rawQuantity").value(2.4))
				.andExpect(jsonPath("$.ingredients[?(@.ingredientName=='Milk')].rawQuantity").value(0.4))
				// And the display half still promotes rather than rounding: 0.4 L is 400 ml.
				.andExpect(jsonPath("$.ingredients[?(@.ingredientName=='Milk')].displayQuantity").value(400.0))
				.andExpect(jsonPath("$.ingredients[?(@.ingredientName=='Milk')].displayUnit").value("ml"));

		// The third vector, on the same request shape, in grammes against an ingredient held in Kg:
		// same family, converts, and 250 gm must not become a kilo.
		UUID jaggery = ingredient("Jaggery", "KG");
		line(payasam, jaggery, "500", "GM", 2);
		mvc.perform(get("/api/v1/recipes/{id}/scaled", payasam)
						.param("targetYield", "50")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ingredients[?(@.ingredientName=='Jaggery')].rawQuantity").value(250.0));
	}

	/**
	 * <strong>The rows staging already holds, and the FEFO split that must go on working.</strong>
	 *
	 * <p>There is no migration and there was never going to be one: 2.5 aprons records what somebody
	 * entered, and that is honest. So a lot holding 2.5 coconuts is written here exactly as staging
	 * holds one, and the application is made to go on reading it, summing it and drawing from it.
	 *
	 * <p>The draw is the part that would break most quietly. A whole requirement of 3 coconuts is met
	 * first-expiry-first from a lot of 2.5 and then a lot of 10, so the ledger takes a
	 * <strong>2.5</strong> movement — a fraction, written by the application, against a counted
	 * ingredient, and correct. {@code StockMovementService.ENTERED_BY_A_PERSON} leaves CONSUMPTION
	 * ungated precisely for this, and rounding at the draw instead of at the requirement would have
	 * broken it.
	 */
	@Test
	@DisplayName("a lot holding 2.5 still reads back, still sums, and a whole draw still splits across it")
	void theFractionalRowsAlreadyWrittenStillWork() throws Exception {
		UUID soonest = UUID.randomUUID();
		seedReceipt(coconut, soonest, "2.5", "PIECES", day.plusDays(2));
		seedReceipt(coconut, UUID.randomUUID(), "10", "PIECES", day.plusDays(60));
		seedReceipt(rice, UUID.randomUUID(), "50", "KG", day.plusDays(60));

		assertThat(onHand(coconut)).as("12.5 on the shelf, read back exactly as entered")
				.isEqualByComparingTo("12.5");
		mvc.perform(get("/api/v1/inventory/movements")
						.header("Authorization", "Bearer valid-token")
						.param("ingredientId", coconut.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.quantity == 2.5)].ingredientName").value("Coconut"));

		// 100 Kg of Coconut Rice needs 3 coconuts. FEFO takes the 2.5 lot out first, then half of one
		// from the next — a whole requirement, split.
		mvc.perform(post("/api/v1/inventory/consumption")
						.header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"recipeId\":\"" + coconutRice + "\",\"targetYield\":100}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.lines[?(@.ingredientName=='Coconut')].required").value(3));

		assertThat(admin.queryForObject("""
				SELECT quantity FROM stock_movements
				WHERE batch_id = ? AND movement_type = 'CONSUMPTION'
				""", BigDecimal.class, soonest))
				.as("the application's own arithmetic may still post a fraction of a counted thing")
				.isEqualByComparingTo("-2.5");
		assertThat(onHand(coconut)).as("12.5 less the 3 the dish took").isEqualByComparingTo("9.5");
	}

	// ---------------------------------------------------------------------

	/** What the shelf holds, in the ingredient's own unit — PIECES is its family's base unit. */
	private BigDecimal onHand(UUID ingredientId) {
		return admin.queryForObject("""
				SELECT COALESCE(SUM(to_on_hand_qty(quantity, unit, movement_type)), 0)
				FROM stock_movements WHERE ingredient_id = ?
				""", BigDecimal.class, ingredientId);
	}

	/** One dish of one Lunch, with a head count so the per-serving figure has a denominator. */
	private UUID plan(UUID recipeId, String yield, int adults) {
		UUID meal = MealFixture.meal(admin, tenant, day, "Lunch", LocalTime.NOON);
		MealFixture.headCount(admin, meal, adults, null, null);
		return MealFixture.dish(admin, tenant, meal, recipeId, new BigDecimal(yield), "PLANNED", actor);
	}

	private void seedReceipt(UUID ingredient, UUID batch, String qty, String unit, LocalDate expiry) {
		admin.update("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					expiry_date, received_date, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, ?, 'PO_RECEIPT', ?, ?, ?)
				""", tenant, ingredient, batch, qty, unit, expiry, expiry, actor);
	}

	private UUID recipe(String name, int baseYield) {
		return admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, ?, ?, ?, 'KG') RETURNING id
				""", UUID.class, tenant, name, category, baseYield);
	}

	private void line(UUID recipeId, UUID ingredientId, String quantity, String unit, int order) {
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, ?::numeric, ?, ?)
				""", tenant, recipeId, ingredientId, quantity, unit, order);
	}

	private UUID ingredient(String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', ?) RETURNING id
				""", UUID.class, tenant, name, unit);
	}

	private void supply(UUID vendorId, UUID ingredientId, String lastPrice) {
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred)
				VALUES (?, ?, ?, ?::numeric, true)
				""", tenant, vendorId, ingredientId, lastPrice);
	}

	private UUID insertUser(String uid, String email, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant, uid, email, role);
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}
}
