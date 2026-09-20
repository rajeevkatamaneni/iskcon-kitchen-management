package org.iskcon.kms.shoppinglist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.meal.MealFixture;
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
 * An ingredient the temple never buys, and what that does and does not change (T-402).
 *
 * <p>Rajeev on 2026-09-19: <em>"water and the like must never reach a shopping list."</em> Until
 * this, the only way to say so was {@code PATCH /shopping-list/{ingredientId}} with
 * {@code included: false}, which is a decision about <strong>one</strong> list and has to be made
 * again on the next one.
 *
 * <p><strong>The fixture is built so that water would otherwise be impossible to miss.</strong> It
 * is in a planned recipe (a shortfall), it has an inventory row below its reorder threshold (a
 * top-up), it has a preferred vendor with a price, and it is held in litres against a rice held in
 * kilograms. Every one of those is a reason for it to be on the list. Rice sits beside it with the
 * same shape and no mark, so what every test here shows is the difference between the two rows
 * rather than an empty screen.
 *
 * <p><strong>Absence is asserted by reading the ingredient ids off the response</strong>, in
 * {@link #ids}, and never by a matcher over a field. {@code jsonPath("$[?(@.x=='y')]")} answers the
 * same for a line that is missing and a line that is there saying something else, which is the exact
 * failure a test about an absence must not have.
 */
@AutoConfigureMockMvc
class NotBoughtShoppingListIT extends AbstractIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;
	private UUID rice;
	private UUID water;
	private UUID khichdi;
	private UUID dishId;
	private final LocalDate day = LocalDate.now(IST).plusDays(2);

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staffId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff-a', 'Staff A', 'staff-a@example.com', '+919876500081',
					'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin-a', 'Admin A', 'admin-a@example.com', '+919876500082',
					'TEMPLE_ADMIN', 'ACTIVE')
				""", tenant);

		rice = ingredient("Rice", "KG");
		water = ingredient("Water", "L");

		// Both below their reorder level, so the threshold stream wants both.
		item(rice, "10");
		item(water, "50");
		receipt(rice, "3", "KG");
		receipt(water, "20", "L");

		// And both in a planned meal, so the shortfall stream wants both too. 100-serving recipe
		// cooked for 200: 10 Kg of rice and 60 L of water against 3 Kg and 20 L on the shelf.
		UUID category = admin.queryForObject(
				"INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id",
				UUID.class, tenant);
		khichdi = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichdi', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, category);
		line(khichdi, rice, "5", "KG", 0);
		line(khichdi, water, "30", "L", 1);
		UUID meal = MealFixture.meal(admin, tenant, day, "Lunch", LocalTime.NOON);
		dishId = MealFixture.dish(
				admin, tenant, meal, khichdi, BigDecimal.valueOf(200), "PLANNED", staffId);

		// A vendor for both, priced, so "still costed" has a number to be wrong about.
		UUID vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Govind Wholesale', '+919812345678')
				RETURNING id
				""", UUID.class, tenant);
		supply(vendor, rice, "45.00");
		supply(vendor, water, "2.00");

		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM shopping_list_lines");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	/**
	 * The whole of Rajeev's instruction, in one assertion pair: before the mark water is on the list,
	 * after it there is no water line at all — and rice, which is on the list for exactly the same two
	 * reasons, is untouched.
	 */
	@Test
	@DisplayName("a recipe planned with water produces a shopping list with no water line at all")
	void waterIsNotOnTheListAtAll() throws Exception {
		// Both streams reach both ingredients, so the unmarked list is the control for the marked one.
		assertThat(ids()).containsExactlyInAnyOrder(rice, water);

		markNotBought(water);

		List<UUID> after = ids();
		assertThat(after)
				.as("the ids on the list, read off the response rather than matched on a field")
				.containsExactly(rice);
		assertThat(after)
				.as("KMS never suggests buying something the temple has said it does not buy")
				.doesNotContain(water);

		// The stated alternative, refused by name. If the implementation had put water on the list
		// unticked instead of leaving it off, `after` would hold two ids and this would say so.
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
	}

	/**
	 * The other half, and the one a {@code SUM} would go on compiling through: a marked ingredient
	 * still consumes stock and still costs money.
	 *
	 * <p>Recording the meal is the real endpoint, not a written-in ledger row, so what is asserted is
	 * what recording actually leaves behind. 200 servings of a 100-serving recipe draws 60 L of water
	 * and 10 Kg of rice; the water is half the day's bill (60 × ₹2 = ₹120 against 10 × ₹45 = ₹450),
	 * which is a figure that would move visibly if the flag had leaked into costing.
	 */
	@Test
	@DisplayName("a marked ingredient still draws stock and is still costed, exactly as before")
	void stockAndCostingAreUntouched() throws Exception {
		markNotBought(water);

		// Enough on the shelf to cook the meal out of, so the ledger records a plain CONSUMPTION and
		// not the USED_BEYOND_RECORDED_STOCK row a short store leaves (T-122). What is under test is
		// that a marked ingredient is drawn down at all, not how the ledger handles a shortfall.
		receipt(rice, "50", "KG");
		receipt(water, "100", "L");

		// Costed before anybody cooks: the plan's own bill, with the water in it.
		mvc.perform(authed(get("/api/v1/materials-cost")).param("date", day.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estimatedTotal").value(570.00))
				.andExpect(jsonPath("$.ingredientsPriced").value(2))
				.andExpect(jsonPath("$.ingredientsWithoutPrice").value(0));

		mvc.perform(authed(post("/api/v1/meals/{id}/record", MealFixture.mealOf(admin, dishId)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"note":"Cooked as planned",
								 "dishes":[{"dishId":"%s","actualServings":200,"notMade":false}]}
								""".formatted(dishId)))
				.andExpect(status().isOk());

		// The ledger drew the water down exactly as it drew the rice down. Both figures are in the
		// family's base unit, which is how the allocator writes a consumption row: 60 L is 60,000 ml
		// and 10 Kg is 10,000 gm.
		assertThat(consumed(water)).isEqualByComparingTo("60000");
		assertThat(consumed(rice)).isEqualByComparingTo("10000");

		// And the store's balance counts it: 120 L received, 60 L cooked with, 60 L left — in
		// millilitres, because on-hand is summed in the family's base unit. This is the same reading
		// the shopping list's own threshold stream takes, so it is the one that would have gone
		// quiet if the flag had leaked out of the suggestion loop.
		assertThat(onHand(water)).isEqualByComparingTo("60000");
		assertThat(onHand(rice)).isEqualByComparingTo("43000");

		// Costed again after recording, off what the job card says was cooked. Same figure, because
		// the meal was cooked as planned — what matters is that the water is still in it.
		mvc.perform(authed(get("/api/v1/materials-cost")).param("date", day.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estimatedTotal").value(570.00))
				.andExpect(jsonPath("$.mealsCostedAsCooked").value(1));

		// And the list is still without it, after all of that. Rice has left the list too, for an
		// ordinary reason that has nothing to do with this flag — the receipt above put 53 Kg on a
		// shelf whose reorder level is 10, and the meal that wanted it has been cooked — so the
		// assertion is that water is absent rather than that rice is present.
		assertThat(ids()).doesNotContain(water);
	}

	/**
	 * A marked ingredient cannot be typed onto the list either. The screen leaves it out of the
	 * picker, and this is the raw POST that never met the picker — "a picker is not a guard", the
	 * rule {@code RecipeService} states for the supply flag.
	 *
	 * <p><strong>KMS-400188, and the code is asserted rather than just the status.</strong> This
	 * answered {@code KMS-400030 RESOURCE_NOT_FOUND} on the first pass and the conductor overruled
	 * it: "we couldn't find it" is not what happened, because the ingredient is in the catalogue and
	 * the person picked it from a list. A 409 alone would pass just as happily against the old code,
	 * so what is checked is the number somebody may one day quote off a screenshot.
	 *
	 * <p>The second assertion is the one that would cost something if it broke: nothing was written,
	 * so a refused hand-add leaves no row to render later or to trip the duplicate check.
	 */
	@Test
	@DisplayName("a hand-added line for a marked ingredient is refused, and nothing is written")
	void aHandAddedLineIsRefused() throws Exception {
		markNotBought(water);

		mvc.perform(authed(post("/api/v1/shopping-list"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ingredientId\":\"%s\",\"suggestedQty\":5}".formatted(water)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400188"));

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM shopping_list_lines WHERE ingredient_id = ?", Integer.class, water))
				.as("a refused hand-add must not leave a row behind")
				.isZero();
		assertThat(ids()).containsExactly(rice);
	}

	/**
	 * A line typed in BEFORE the mark was set. The decision row stays in the table and renders
	 * nothing, which is the same thing a live purchase order already does to a hand-added line — so
	 * clearing the mark brings it back rather than needing a restore path.
	 */
	@Test
	@DisplayName("a hand-added line already on the list stops rendering when the mark is set, and returns when it is cleared")
	void anExistingHandAddedLineDropsOutAndComesBack() throws Exception {
		// Jaggery: no inventory row, no recipe, no meal. Nothing can suggest it, so if it is on the
		// list somebody typed it there.
		UUID jaggery = ingredient("Jaggery", "KG");
		mvc.perform(authed(post("/api/v1/shopping-list"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ingredientId\":\"%s\",\"suggestedQty\":4}".formatted(jaggery)))
				.andExpect(status().isCreated());
		assertThat(ids()).contains(jaggery);

		markNotBought(jaggery);
		assertThat(ids()).doesNotContain(jaggery);
		assertThat(admin.queryForObject(
				"SELECT hand_added FROM shopping_list_lines WHERE ingredient_id = ?", Boolean.class, jaggery))
				.as("the row is still there; it simply renders nothing")
				.isTrue();

		setNotBought(jaggery, false);
		assertThat(ids()).contains(jaggery);
	}

	/**
	 * The balance a closed order never delivered is the third demand stream, and it is dropped too.
	 *
	 * <p>This is the case where money has already changed hands: the order was raised, sent and
	 * part-delivered before anybody decided the temple does not buy the thing. The order keeps its
	 * lines and its history — asserted below — and what does not happen is the remainder coming round
	 * again as something to buy.
	 */
	@Test
	@DisplayName("the undelivered balance of a closed order is not re-fed for a marked ingredient")
	void aClosedOrdersBalanceIsNotReFed() throws Exception {
		UUID vendorId = admin.queryForObject("SELECT id FROM vendors LIMIT 1", UUID.class);
		UUID poId = UUID.randomUUID();
		admin.update("""
				INSERT INTO purchase_orders (id, tenant_id, po_number, vendor_id, status, created_by,
						created_at, closed_at, close_outcome, close_note)
				VALUES (?, ?, 'PO-0001', ?, 'CLOSED', ?, now() - interval '3 days',
						now() - interval '1 day', 'VENDOR_LET_US_DOWN', 'Never delivered the balance')
				""", poId, tenant, vendorId, staffId);
		admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 100, 'L', 0)
				""", tenant, poId, water);

		// Nothing was received against it, so the whole 100 L is outstanding and the stream fires.
		assertThat(ids()).as("the balance is a real demand while the flag is off").contains(water);

		markNotBought(water);

		assertThat(ids()).containsExactly(rice);
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM purchase_order_lines WHERE po_id = ?", Integer.class, poId))
				.as("the order itself is untouched — the flag changes the list, not the history")
				.isEqualTo(1);
	}

	/** Clearing the mark needs no restore path: the list is derived, so the next read computes it back. */
	@Test
	@DisplayName("clearing the mark puts the line back, recomputed rather than restored")
	void clearingTheMarkPutsItBack() throws Exception {
		markNotBought(water);
		assertThat(ids()).containsExactly(rice);

		setNotBought(water, false);

		assertThat(ids()).containsExactlyInAnyOrder(rice, water);
	}

	// ---------------------------------------------------------------------

	/**
	 * The ingredient ids on the shopping list, in the order the list renders them.
	 *
	 * <p>Read off the response body rather than asserted with a JSONPath filter, which is the whole
	 * discipline of this class: a filter that finds nothing and a filter that finds a line with a
	 * different value both come back empty, so a test written that way passes just as well when the
	 * feature is gone.
	 */
	private List<UUID> ids() throws Exception {
		String body = mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<UUID> ids = new ArrayList<>();
		for (JsonNode line : JSON.readTree(body)) {
			ids.add(UUID.fromString(line.get("ingredientId").asText()));
		}
		return ids;
	}

	/** Marks it through the real endpoint, as the Temple Admin, then signs back in as the cook. */
	private void markNotBought(UUID ingredientId) throws Exception {
		setNotBought(ingredientId, true);
	}

	private void setNotBought(UUID ingredientId, boolean notBought) throws Exception {
		signIn("uid-admin-a");
		mvc.perform(authed(patch("/api/v1/ingredients/{id}/not-bought", ingredientId))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"notBought\":%s}".formatted(notBought)))
				.andExpect(status().isNoContent());
		signIn("uid-staff-a");
	}

	/** What the ledger says was cooked with, summed as written — in the family's base unit. */
	private BigDecimal consumed(UUID ingredientId) {
		BigDecimal sum = admin.queryForObject("""
				SELECT COALESCE(SUM(quantity), 0) FROM stock_movements
				WHERE ingredient_id = ? AND movement_type = 'CONSUMPTION'
				""", BigDecimal.class, ingredientId);
		return sum == null ? BigDecimal.ZERO : sum.abs();
	}

	/** The store's own balance, the figure the shopping list and the low-stock alert both read. */
	private BigDecimal onHand(UUID ingredientId) {
		BigDecimal sum = admin.queryForObject("""
				SELECT COALESCE(SUM(to_on_hand_qty(quantity, unit, movement_type)), 0)
				FROM stock_movements WHERE ingredient_id = ?
				""", BigDecimal.class, ingredientId);
		return sum == null ? BigDecimal.ZERO : sum;
	}

	private UUID ingredient(String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', ?) RETURNING id
				""", UUID.class, tenant, name, unit);
	}

	private void item(UUID ingredientId, String threshold) {
		admin.update("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold)
				VALUES (?, ?, ?::numeric)
				""", tenant, ingredientId, threshold);
	}

	private void receipt(UUID ingredientId, String quantity, String unit) {
		admin.update("""
				INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit,
						movement_type, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, ?, 'PO_RECEIPT', ?)
				""", tenant, ingredientId, UUID.randomUUID(), quantity, unit, staffId);
	}

	private void line(UUID recipeId, UUID ingredientId, String quantity, String unit, int order) {
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, ?::numeric, ?, ?)
				""", tenant, recipeId, ingredientId, quantity, unit, order);
	}

	private void supply(UUID vendorId, UUID ingredientId, String lastPrice) {
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred)
				VALUES (?, ?, ?, ?::numeric, true)
				""", tenant, vendorId, ingredientId, lastPrice);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}
}
