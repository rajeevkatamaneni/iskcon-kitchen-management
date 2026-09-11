package org.iskcon.kms.shoppinglist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.perf.StatementRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
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
 * The shopping list (E5-S2), which since T-132 is <strong>computed on every read and never
 * stored</strong>: merged shortfall and threshold streams with their provenance, the preferred
 * vendor, the order-by date (T-090), and the human decisions laid over the top.
 *
 * <p>There is no regeneration in this file and no {@code POST /shopping-list/regenerate} to call.
 * That is the change, and every test below is written as a {@code GET} against the data rather than
 * as an assertion about what a button last wrote — which is what makes the two central facts
 * testable at all: that an untouched line goes on recomputing, and that a line a purchase order
 * covers is simply not there.
 */
@AutoConfigureMockMvc
@Import(ShoppingListIT.StubVerifierConfiguration.class)
class ShoppingListIT extends AbstractIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID staffId;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staffId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff-a', 'Staff A', 'staff-a@example.com', '+919876500081', 'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-vol-a', 'Vol A', 'vol-a@example.com', '+919876500082', 'VOLUNTEER', 'ACTIVE')
				""", tenant);

		rice = ingredient("Rice");
		UUID garlic = ingredient("Garlic");
		item(rice, "10");    // reorder threshold 10 KG
		item(garlic, "5");   // reorder threshold 5 KG
		receipt(rice, "3");  // 3 KG on hand -> below threshold; topUp = 12 - 3 = 9
		receipt(garlic, "1"); // below threshold; topUp = 6 - 1 = 5

		// A planned meal drives a rice shortfall: 200-serving Khichdi needs 10 KG, only 3 in stock.
		UUID cat = admin.queryForObject("INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id", UUID.class, tenant);
		UUID khichdi = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichdi', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, cat);
		admin.update("INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order) VALUES (?, ?, ?, 5, 'KG', 0)", tenant, khichdi, rice);
		LocalDate soon = LocalDate.now(IST).plusDays(2);
		admin.update("""
				INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, ready_by, recipe_id, target_yield, day_type, status, created_by)
				VALUES (?, ?, 'Lunch', TIME '12:00', ?, 200, 'REGULAR', 'PLANNED', ?)
				""", tenant, soon, khichdi, staffId);

		// Preferred vendor for rice.
		UUID vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Govind Wholesale', '+919812345678') RETURNING id
				""", UUID.class, tenant);
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, preferred) VALUES (?, ?, ?, true)
				""", tenant, vendor, rice);

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
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("the list merges shortfall + threshold with provenance and suggests the preferred vendor")
	void mergesStreamsWithProvenance() throws Exception {
		// One GET, and nothing was pressed to make this happen. Before T-132 this test had to run a
		// regeneration first, and what it then asserted was the contents of a table somebody had
		// written — which is a weaker statement than this one, because a stale table says the same
		// thing whether or not the computation behind it is still right.
		mvc.perform(authed(get("/api/v1/shopping-list")))
				// Two lines, ordered by ingredient name: Garlic then Rice. Garlic is here at all only
				// because D-18 removed the exclusion that used to keep it out — see
				// garlicNoLongerExcluded below, which is where that is asserted deliberately.
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].ingredientName").value("Garlic"))
				.andExpect(jsonPath("$[1].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].suggestedQty").value(9))       // max(shortfall 7, topUp 9)
				.andExpect(jsonPath("$[1].shortfall").value(7))          // E4-S5 contract
				.andExpect(jsonPath("$[1].thresholdTopUp").value(9))
				.andExpect(jsonPath("$[1].suggestedVendorName").value("Govind Wholesale"))
				.andExpect(jsonPath("$[1].neededBy").exists());
	}

	/**
	 * The order-by date on a line (T-090): the earliest meal that wants the ingredient, minus the
	 * lead time recorded against the vendor the order would go to.
	 *
	 * <p>Three readings off one row, in the order a temple actually arrives at them.
	 *
	 * <ol>
	 *   <li><strong>Nothing recorded.</strong> The two-day assumption stands in, and the line says so
	 *       — {@code leadTimeDays} comes back absent rather than as a 2, so the screen can print
	 *       "assumed" and somebody can go and record the real answer. This is the case that must not
	 *       be confused with a lead time of zero: the meal is two days out, so a zero would say the
	 *       order can wait until the morning it is cooked.</li>
	 *   <li><strong>Five days recorded.</strong> The date moves three days into the past and the line
	 *       reads "too late" — the state whose sentence has to be different, because there is no
	 *       longer an order that arrives in time.</li>
	 *   <li><strong>Zero recorded.</strong> Cash-and-carry: the date is the meal's own day, and the
	 *       line has slack again. Note it is a different answer from case 1, which is the whole point
	 *       of the column being nullable.</li>
	 * </ol>
	 *
	 * <p>Garlic is on this list through the threshold stream alone — no meal demanded it — so it has
	 * no demand date to count back from and gets no order-by date. An em dash on the screen, and
	 * deliberately not today: nobody is late for a top-up nothing has asked for by a date.
	 */
	@Test
	@DisplayName("the order-by date counts back from the meal by the vendor's lead time, and says when it was assumed")
	void orderByCountsBackFromTheDemand() throws Exception {
		LocalDate today = LocalDate.now(IST);
		LocalDate meal = today.plusDays(2);

		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[1].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].orderBy").value(today.toString()))
				.andExpect(jsonPath("$[1].orderUrgency").value("ORDER_TODAY"))
				.andExpect(jsonPath("$[1].leadTimeDays").doesNotExist())
				// T-130. The delivery date written on the purchase order is now the day of the meal
				// itself. It used to be the meal less a two-day delivery buffer — so on this fixture
				// it read as today, two days before the food was wanted — and the order screen then
				// warned that the same date gave the vendor too little notice. Applied on the write,
				// complained about on the read. The buffer was a guess standing in for the fact
				// T-090 recorded per vendor and ingredient, and that fact is applied on the other
				// side of the question, in orderBy above.
				.andExpect(jsonPath("$[1].neededBy").value(meal.toString()))
				// Nothing demanded the garlic by a date, so there is no deadline to invent.
				.andExpect(jsonPath("$[0].ingredientName").value("Garlic"))
				.andExpect(jsonPath("$[0].orderBy").doesNotExist())
				.andExpect(jsonPath("$[0].orderUrgency").doesNotExist());

		// No regeneration between these three readings, and that is the point of them now: the
		// answer changes because the recorded lead time changed, on the very next read.
		admin.update("UPDATE vendor_supplies SET lead_time_days = 5 WHERE ingredient_id = ?", rice);
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[1].orderBy").value(today.minusDays(3).toString()))
				.andExpect(jsonPath("$[1].orderUrgency").value("TOO_LATE"))
				.andExpect(jsonPath("$[1].leadTimeDays").value(5));

		admin.update("UPDATE vendor_supplies SET lead_time_days = 0 WHERE ingredient_id = ?", rice);
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[1].orderBy").value(meal.toString()))
				.andExpect(jsonPath("$[1].orderUrgency").value("IN_TIME"))
				.andExpect(jsonPath("$[1].leadTimeDays").value(0));
	}

	@Test
	@DisplayName("what used to be a sattvic-prohibited ingredient now enters via the threshold stream")
	void garlicNoLongerExcluded() throws Exception {
		// The inverse of the test that stood here until 2026-09-08, which asserted that garlic could
		// NEVER reach the list this way. D-18 deleted the flag the exclusion read, and this is the
		// accepted consequence stated in as many words: a temple that keeps an inventory row for
		// garlic, with a reorder threshold on it, is now topped up like any other stock. Asserted
		// deliberately rather than left for somebody to find later and report as a defect.
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[?(@.ingredientName=='Garlic')]").exists())
				.andExpect(jsonPath("$[0].ingredientName").value("Garlic"))
				.andExpect(jsonPath("$[0].thresholdTopUp").value(5))   // 5 × 1.2 = 6, less 1 on hand
				.andExpect(jsonPath("$[0].shortfall").value(0));       // no meal plan asks for it
	}

	/**
	 * A decision survives, and everything around it goes on being recomputed. That is the whole
	 * bargain T-132 struck: only what a person chose is stored, and a choice is never overwritten by
	 * a computation — but nothing else is frozen alongside it either.
	 */
	@Test
	@DisplayName("an edited quantity and an untick both survive, and the rest of the line still moves")
	void decisionsSurviveAndTheRestKeepsMoving() throws Exception {
		mvc.perform(authed(patch("/api/v1/shopping-list/{id}", rice))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"suggestedQty\":20,\"included\":false}"))
				.andExpect(status().isNoContent());

		// Index 1: the list is ordered by name, and Garlic sits ahead of Rice.
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[1].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].suggestedQty").value(20))
				.andExpect(jsonPath("$[1].included").value(false))
				.andExpect(jsonPath("$[1].edited").value(true))
				// The mitigation for an untick that persists, and the reason it is legible rather
				// than expiring: a line that comes back months later says when somebody decided
				// against it. Off updated_at, so it needed no new column.
				.andExpect(jsonPath("$[1].excludedSince").value(LocalDate.now(IST).toString()));

		// The provenance beside that hand-typed 20 is today's, not the day it was typed — and it
		// disagrees with it, which is the information. Somebody overrode the suggestion; here is
		// what the system thinks now.
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[1].shortfall").value(7))
				.andExpect(jsonPath("$[1].thresholdTopUp").value(9));
	}

	/**
	 * The trap this had to avoid. Both callers on the screen send a quantity whatever they are doing
	 * — the tick box sends the figure it can see — so writing it through unconditionally would freeze
	 * that number on the line for ever the first time anybody unticked it. The list would then stop
	 * recomputing exactly where the person had least intended to say anything about quantity.
	 */
	@Test
	@DisplayName("unticking a line without changing the number does not freeze that number")
	void untickingDoesNotFreezeTheQuantity() throws Exception {
		// 9 is what the derivation says today: max(shortfall 7, top-up 9).
		mvc.perform(authed(patch("/api/v1/shopping-list/{id}", rice))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"suggestedQty\":9,\"included\":false}"))
				.andExpect(status().isNoContent());
		Integer stored = admin.queryForObject(
				"SELECT count(*) FROM shopping_list_lines WHERE ingredient_id = ? AND suggested_qty IS NOT NULL",
				Integer.class, rice);
		assert stored == 0 : "agreeing with the suggestion is not an override and must not be stored";

		// Raise the reorder level and the untouched quantity moves with it: 20 × 1.2 = 24, less the
		// 3 Kg on the shelf. A frozen 9 would still say 9.
		admin.update("UPDATE inventory_items SET reorder_threshold = 20 WHERE ingredient_id = ?", rice);
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[1].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].suggestedQty").value(21))
				.andExpect(jsonPath("$[1].included").value(false));
	}

	@Test
	@DisplayName("the preferred vendor is derived on every read, and an edit cannot disturb it")
	void theVendorIsDerivedRatherThanStored() throws Exception {
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[1].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].suggestedVendorName").value("Govind Wholesale"));

		// Exactly what both callers on the shopping-list screen send. There is no vendor in the
		// request any more and no column to null: shopping_list_lines.suggested_vendor_id went in
		// V121, because it was pure derivation for the whole of its life and updateLine accepted a
		// value no screen ever sent. The defect this replaces was real — writing an absent id
		// straight through nulled the vendor and silently dropped the line out of ordering — and it
		// is now unreachable rather than guarded against.
		mvc.perform(authed(patch("/api/v1/shopping-list/{id}", rice))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"suggestedQty\":15,\"included\":true}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[1].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].suggestedQty").value(15))
				.andExpect(jsonPath("$[1].suggestedVendorId").isNotEmpty())
				.andExpect(jsonPath("$[1].suggestedVendorName").value("Govind Wholesale"));

		// And the vendor follows the catalogue rather than a snapshot: move the preference and the
		// next read says so, on a line somebody has already edited.
		UUID other = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Sri Traders', '+919812345679')
				RETURNING id
				""", UUID.class, tenant);
		admin.update("UPDATE vendor_supplies SET vendor_id = ? WHERE ingredient_id = ?", other, rice);
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[1].suggestedVendorName").value("Sri Traders"));
	}

	/**
	 * D-24a, and the single most important thing in T-132: <strong>an ingredient a live purchase
	 * order covers is not on the shopping list, and cancelling that order puts it back.</strong>
	 *
	 * <p>Rajeev's reason for taking the line off at creation rather than at sending: <em>"IF we take
	 * it off on send, they will be there in the shopping list begging to be ordered, someone else
	 * will take pity and generate another PO. Same ingredients, 2 PO's. We don't need that
	 * confusion."</em> The order raised below is a <strong>draft</strong> — nobody has sent it — and
	 * the rice is gone from the list all the same.
	 *
	 * <p>There is no restore path anywhere in this feature and none is needed. The list is a function
	 * of current state; cancelling changes that state, and the next read says so.
	 */
	@Test
	@DisplayName("a draft purchase order takes its ingredients off the list, and cancelling gives them back")
	void aLiveOrderTakesTheLineOffTheList() throws Exception {
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[?(@.ingredientName=='Rice')]").exists());

		String body = mvc.perform(authed(post("/api/v1/purchase-orders/generate")))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String poId = body.substring(body.indexOf("[\"") + 2, body.indexOf("\"]"));
		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", poId)))
				.andExpect(jsonPath("$.order.status").value("DRAFT"));

		// Garlic has no preferred vendor, so no order was raised for it and it is still here. Rice
		// is on a draft nobody has sent, and it is gone.
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].ingredientName").value("Garlic"));

		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", poId))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Ordered from the wrong merchant\"}"))
				.andExpect(status().isNoContent());

		// Back, at the quantity the data says today rather than the one the order was raised for —
		// because nothing was restored. It was recomputed.
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[1].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].suggestedQty").value(9));
	}

	/**
	 * The other half of the same predicate, and the reason the two rules do not fight. A part-
	 * delivered order is not a pending promise: the truck came, and what it did not bring is
	 * evidence of a shortfall. So {@code PARTIALLY_RECEIVED} re-feeds (E5-S6, asserted end-to-end in
	 * {@code ReceivingIT}) while {@code DRAFT} and {@code SENT} suppress. Asserted here from the
	 * suppressing side, on a sent order, because that status changed behaviour in T-132 and nothing
	 * else in this suite would have noticed.
	 */
	@Test
	@DisplayName("a sent order suppresses its ingredients too, not just a draft")
	void aSentOrderAlsoTakesTheLineOff() throws Exception {
		String body = mvc.perform(authed(post("/api/v1/purchase-orders/generate")))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String poId = body.substring(body.indexOf("[\"") + 2, body.indexOf("\"]"));
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", poId)))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].ingredientName").value("Garlic"));
	}

	@Test
	@DisplayName("a volunteer cannot see the shopping list")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol-a");
		mvc.perform(authed(get("/api/v1/shopping-list"))).andExpect(status().isForbidden());
	}

	/**
	 * The endpoint that built the list is gone, and its absence is asserted rather than assumed.
	 * Removing a write is not proved by the tests that no longer call it — a route left registered
	 * would go on mutating shared rows for anybody who still had the URL, and nothing else here
	 * would fail.
	 */
	@Test
	@DisplayName("there is no regenerate endpoint any more")
	void regenerateIsGone() throws Exception {
		mvc.perform(authed(post("/api/v1/shopping-list/regenerate")))
				.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------------

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private UUID ingredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant, name);
	}

	private void item(UUID ingredient, String threshold) {
		admin.update("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold)
				VALUES (?, ?, ?::numeric)
				""", tenant, ingredient, threshold);
	}

	private void receipt(UUID ingredient, String qtyKg) {
		admin.update("""
				INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, 'KG', 'PO_RECEIPT', ?)
				""", tenant, ingredient, UUID.randomUUID(), qtyKg, staffId);
	}

	/**
	 * A consumable with no ledger rows at all is still topped up — T-140's one behavioural risk.
	 *
	 * <p>The threshold stream used to get its on-hand figure from a per-batch aggregate over
	 * {@code stock_movements} and now gets it from the per-ingredient one the list has already read.
	 * The two are the same arithmetic with one edge between them: <strong>an ingredient nobody has
	 * ever recorded a movement for is not a key in either result.</strong> Absent has to go on meaning
	 * zero and not "skip this line", because a consumable a temple has set a reorder level for and
	 * never received is precisely the one the shopping list exists to put in front of somebody.
	 */
	@Test
	@DisplayName("a consumable with no movements at all is still topped up from zero")
	void aConsumableWithNoLedgerRowsIsStillSuggested() throws Exception {
		UUID jaggery = ingredient("Jaggery");
		item(jaggery, "5");   // a reorder level of 5 KG, and not one movement ever recorded

		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[1].ingredientName").value("Jaggery"))
				.andExpect(jsonPath("$[1].currentStock").value(0))
				.andExpect(jsonPath("$[1].thresholdTopUp").value(6))   // 5 × 1.2 − 0
				.andExpect(jsonPath("$[1].suggestedQty").value(6));
	}

	/**
	 * T-140: one page load sums the stock ledger once, whatever else it does.
	 *
	 * <p><strong>This is a correctness test wearing a performance test's clothes.</strong> The three
	 * sums it replaces all ran inside one {@code @Transactional(readOnly = true)}, which buys less
	 * than it reads like: PostgreSQL's default isolation gives each <em>statement</em> its own
	 * snapshot, so a receipt recorded while the page was loading could be counted by the sum behind
	 * the shortfall and missed by the sum behind the <em>current stock</em> column — one line, two
	 * different quantities of rice, and nothing on the screen to say so. That defect cannot be
	 * written as an assertion about output, because it needs a write to land between two statements
	 * of one request. What it can be written as is this: <em>there is only one statement, so there is
	 * no between.</em>
	 *
	 * <p>It counts statements at the JDBC boundary rather than index scans in
	 * {@code pg_stat_all_tables}, for two reasons T-139's own numbers demonstrate. Index scans count
	 * plan executions, so one statement driving a nested loop over 26 rows registers 26 of them; and
	 * the counters flush about once a second, so a delta bracketing one request can carry the tail of
	 * the requests before it — which is how three statements were first reported as nine scans.
	 *
	 * <p>The fixture here holds two ingredients and a handful of movements, so this test would pass
	 * just as happily if every statement read the whole table. That is deliberate: what is being
	 * guarded is the <em>number of times the question is asked</em>, which is the thing a later change
	 * would quietly put back, and it is the same number at two rows as at a hundred and fifty thousand.
	 */
	@Test
	@DisplayName("one page load sums the stock ledger once, not once per demand stream")
	void theLedgerIsSummedOncePerPageLoad() throws Exception {
		StatementRecorder.start();
		mvc.perform(authed(get("/api/v1/shopping-list"))).andExpect(status().isOk());
		List<StatementRecorder.Executed> statements = StatementRecorder.stop();

		assertThat(statements.stream().filter(s -> s.touches("stock_movements")).toList())
				.as("statements against stock_movements for one GET /api/v1/shopping-list, of which"
						+ " there were three until T-140: this service, SufficiencyService's allocation"
						+ " walk, and InventoryItemService's batch read behind lowStock()")
				.hasSize(1);
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	// ---------------------------------------------------------------------

	@TestConfiguration
	static class StubVerifierConfiguration {

		@Bean
		@Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
		}

		/**
		 * Records the SQL a request issues, so {@link #theLedgerIsSummedOncePerPageLoad()} can count
		 * it (T-140). Declared here rather than in a configuration of its own because an imported
		 * configuration class is part of the TestContext cache key, and a second one would ask for a
		 * second Spring context to run one assertion.
		 */
		@Bean
		static BeanPostProcessor shoppingListStatementRecorder() {
			return StatementRecorder.wrapTheDataSource();
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
