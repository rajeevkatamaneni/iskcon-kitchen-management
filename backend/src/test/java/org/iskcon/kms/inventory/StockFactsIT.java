package org.iskcon.kms.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.meal.MealFixture;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The three facts the stock screen gained in T-432 — last counted, on order, and roughly how long it
 * lasts (see {@link StockFactsService}).
 *
 * <p>Rajeev, 2026-09-20, of the inventory screen: it "is very confusing and not up to the standard
 * of other pages in our app". Three of the seven things he named were facts the product simply did
 * not hold, and each of the three carries a rule that decides what is true rather than what is
 * shown. This is where those rules are held to.
 *
 * <p>No MockMvc and no signed-in person. The subject is three aggregate queries and the arithmetic
 * over them, and reaching them through a controller would test the controller —
 * {@code BaseQuantityIT} makes the same choice for the same reason. {@link InventoryItemService} is
 * called rather than {@link StockFactsService} directly wherever the assembly matters, because a map
 * computed correctly and then read with the wrong key is a defect no test of the query would find.
 *
 * <p>The tenant is set on the thread rather than carried by a token: every read goes through the
 * application's own unprivileged connection, so without it RLS hides the whole fixture and every
 * assertion passes against an empty answer.
 */
class StockFactsIT extends AbstractIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Autowired
	private InventoryItemService inventory;

	@Autowired
	private StockFactsService facts;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staff;
	private UUID vendor;
	private UUID rice;
	private UUID apron;
	private UUID riceItem;
	private UUID recipe;

	private LocalDate today;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		today = LocalDate.now(IST);
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('stock-facts', 'Stock Facts Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staff = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-facts', 'Bhakta Shyam', 'facts@example.com', '+919876500071',
					'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Govind Wholesale', '+919812345678')
				RETURNING id
				""", UUID.class, tenant);

		rice = ingredient("Rice", "KG");
		apron = ingredient("Apron", "PIECES");
		riceItem = item(rice);
		item(apron);

		UUID category = admin.queryForObject(
				"INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id",
				UUID.class, tenant);
		recipe = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichdi', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, category);

		TenantContext.set(tenant);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM goods_receipt_lines");
		admin.execute("DELETE FROM goods_receipts");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// =====================================================================
	@Nested
	@DisplayName("Last counted")
	class LastCounted {

		@Test
		@DisplayName("a stock-take dates it; an item nobody ever counted says so with a null")
		void aStockTakeDatesIt() {
			countedOn(rice, today.minusDays(3));

			assertThat(row("Rice").lastCounted()).isEqualTo(today.minusDays(3));
			assertThat(row("Apron").lastCounted())
					.as("nothing has ever been counted, and null is how the screen knows to say so")
					.isNull();
		}

		@Test
		@DisplayName("the most recent stock-take wins, not the first")
		void theMostRecentWins() {
			countedOn(rice, today.minusDays(30));
			countedOn(rice, today.minusDays(2));

			assertThat(row("Rice").lastCounted()).isEqualTo(today.minusDays(2));
		}

		@Test
		@DisplayName("a compensating correction is not somebody counting the shelf")
		void aCompensationIsNotACount() {
			// The trap this whole method exists around: StockMovementService.compensate writes an
			// ADJUSTMENT carrying COUNT_CORRECTION too, marked reference_type = 'CORRECTION'. Nobody
			// counted anything — the application put back what a mistaken movement took — and
			// counting it would reset the date to the day somebody fixed a typo.
			countedOn(rice, today.minusDays(9));
			UUID original = movement(rice, "-4", "KG", "CONSUMPTION", null, null, today.minusDays(1));
			admin.update("""
					INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit,
							movement_type, reason_category, reference_type, reference_id, actor_user_id,
							created_at)
					VALUES (?, ?, ?, 4, 'KG', 'ADJUSTMENT', 'COUNT_CORRECTION', 'CORRECTION', ?, ?, ?)
					""", tenant, rice, UUID.randomUUID(), original, staff, at(today));

			assertThat(row("Rice").lastCounted())
					.as("the real stock-take nine days ago, not today's reversal")
					.isEqualTo(today.minusDays(9));
		}

		@Test
		@DisplayName("writing off spoilage is not counting the shelf either")
		void spoilageIsNotACount() {
			movement(rice, "-2", "KG", "ADJUSTMENT", "SPOILAGE", null, today);

			assertThat(row("Rice").lastCounted())
					.as("something happened to the stock; nobody counted it")
					.isNull();
		}

		@Test
		@DisplayName("the opening count taken when an item is added counts, because somebody counted")
		void theOpeningCountCounts() {
			// It is written by InventoryItemService.create as an ADJUSTMENT/COUNT_CORRECTION with its
			// own note. Excluding it would tell every temple that has just started tracking that it
			// has never counted anything, on the very day it counted everything.
			admin.update("""
					INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit,
							movement_type, reason_category, note, actor_user_id, created_at)
					VALUES (?, ?, ?, 40, 'KG', 'ADJUSTMENT', 'COUNT_CORRECTION', ?, ?, ?)
					""", tenant, rice, UUID.randomUUID(), InventoryItemService.OPENING_COUNT_NOTE,
					staff, at(today.minusDays(5)));

			assertThat(row("Rice").lastCounted()).isEqualTo(today.minusDays(5));
		}
	}

	// =====================================================================
	@Nested
	@DisplayName("On order")
	class OnOrder {

		@Test
		@DisplayName("a sent order that has not arrived is on order, in the ingredient's own unit")
		void aSentOrderIsOnOrder() {
			order("SENT", rice, "200", "KG", null);

			assertThat(row("Rice").onOrder()).isEqualByComparingTo("200");
		}

		@Test
		@DisplayName("a draft order is NOT on order — nothing has been sent to anybody")
		void aDraftIsNotOnOrder() {
			order("DRAFT", rice, "200", "KG", null);

			assertThat(row("Rice").onOrder())
					.as("a column that says 200 Kg is coming because somebody started typing this "
							+ "morning is what stops a temple buying rice it needs")
					.isNull();
		}

		@Test
		@DisplayName("nothing on order is null, not zero — a quantity nobody has is not a zero")
		void nothingOnOrderIsNull() {
			assertThat(row("Rice").onOrder()).isNull();
		}

		@Test
		@DisplayName("what a part-delivered order still owes is what is on order")
		void aPartDeliveryLeavesTheBalance() {
			order("PARTIALLY_RECEIVED", rice, "200", "KG", "150");

			assertThat(row("Rice").onOrder()).isEqualByComparingTo("50");
		}

		@Test
		@DisplayName("an order delivered in full leaves nothing on order")
		void aFullDeliveryLeavesNothing() {
			order("RECEIVED", rice, "200", "KG", "200");

			assertThat(row("Rice").onOrder()).isNull();
		}

		@Test
		@DisplayName("a closed order is not on order — it has ended, and its remainder is the shopping list's")
		void aClosedOrderIsNotOnOrder() {
			order("CLOSED", rice, "200", "KG", "150");

			assertThat(row("Rice").onOrder()).isNull();
		}

		@Test
		@DisplayName("a line naming something the catalogue never heard of is not added to any ingredient")
		void aDescribedLineIsExcluded() {
			// ingredient_id is null on a one-off line, and a null is a perfectly valid map key: every
			// described line on every live order would otherwise collapse into one bucket and be
			// handed to whichever consumable asked the map about itself (T-024's lesson, one query
			// along in ShoppingListService).
			order("SENT", null, "4", "PIECES", null);
			order("SENT", rice, "10", "KG", null);

			assertThat(row("Rice").onOrder()).isEqualByComparingTo("10");
		}

		@Test
		@DisplayName("a kilo ordered against an ingredient kept in kilos, and grams against the same, both land right")
		void unitsAreConvertedThroughTheOneFunction() {
			order("SENT", rice, "2", "KG", null);
			order("SENT", rice, "500", "GM", null);

			assertThat(row("Rice").onOrder()).isEqualByComparingTo("2.5");
		}
	}

	// =====================================================================
	@Nested
	@DisplayName("How long it lasts")
	class HowLongItLasts {

		@Test
		@DisplayName("six days of cooking over a fortnight gives an approximate figure")
		void enoughHistoryGivesAFigure() {
			// 160 Kg delivered and 10 Kg cooked on each of six days, so 100 Kg is left on the shelf.
			// The rate is measured over the elapsed period — first draw to today inclusive — not over
			// the days that had a draw, because a day nobody cooked with rice is still a day it
			// lasted. First draw 14 days ago, so the period is 15 days: 60 Kg over 15 days is 4 a
			// day, and the 100 Kg still there lasts 25 of them.
			received(rice, "160", "KG");
			for (int d : new int[] { 14, 12, 10, 8, 4, 0 }) {
				cooked(rice, "-10", today.minusDays(d));
			}

			StockCover cover = row("Rice").lastsFor();
			assertThat(cover).isNotNull();
			assertThat(onHand("Rice")).as("160 delivered less the 60 cooked").isEqualByComparingTo("100");
			assertThat(cover.perDay()).as("60 Kg over the 15 days observed").isEqualByComparingTo("4");
			assertThat(cover.days()).isEqualTo(25);
			assertThat(cover.beyondWindow()).isFalse();
			assertThat(cover.runsOutOn()).isEqualTo(today.plusDays(25));
		}

		@Test
		@DisplayName("five days of cooking is not a trend, and the answer is nothing at all")
		void fiveDaysIsNotEnough() {
			received(rice, "100", "KG");
			for (int d : new int[] { 14, 12, 10, 8, 4 }) {
				cooked(rice, "-10", today.minusDays(d));
			}

			assertThat(row("Rice").lastsFor())
					.as("null is the honest answer; the screen says 'Not enough history' rather than a figure")
					.isNull();
		}

		@Test
		@DisplayName("six days inside one week is a festival, not a rate")
		void aBusyWeekIsNotEnough() {
			received(rice, "100", "KG");
			for (int d : new int[] { 6, 5, 4, 3, 2, 1 }) {
				cooked(rice, "-10", today.minusDays(d));
			}

			assertThat(row("Rice").lastsFor())
					.as("six days over seven clears the day count and fails the fortnight")
					.isNull();
		}

		@Test
		@DisplayName("several lots drawn for one meal are one day's evidence, not three")
		void drawsAreCountedByDayAndNotByRow() {
			// One Sunday feast emptying three lots of rice is three rows in the ledger. Counting rows
			// would let it masquerade as a habit, which is exactly what MIN_DAYS_USED is written in
			// days to prevent.
			received(rice, "100", "KG");
			for (int i = 0; i < 6; i++) {
				cooked(rice, "-10", today.minusDays(14));
			}

			assertThat(row("Rice").lastsFor()).isNull();
		}

		@Test
		@DisplayName("a draw is dated by the day its meal belongs to, not by the day it was keyed in")
		void aDrawIsDatedByItsMealsDay() {
			// Every fixture in this class writes created_at as the day it means, so this is the one
			// test that separates the two: three weeks of meals, all keyed in today. Dating by
			// created_at would compress them into one day and report the store as emptying this
			// afternoon. It is not hypothetical — every row in the seeded temple's ledger has a
			// created_at of the day the seed ran, over meals spanning three weeks.
			received(rice, "100", "KG");
			for (int d : new int[] { 14, 12, 10, 8, 4, 0 }) {
				UUID dish = MealFixture.plan(admin, tenant, today.minusDays(d), "Lunch", LocalTime.NOON,
						recipe, BigDecimal.valueOf(100), "COOKED", staff);
				movement(rice, "-10", "KG", "CONSUMPTION", null, dish, today);
			}

			StockCover cover = row("Rice").lastsFor();
			assertThat(cover).as("six meal-days across a fortnight, however late the paperwork was")
					.isNotNull();
			assertThat(cover.perDay()).isEqualByComparingTo("4");
		}

		@Test
		@DisplayName("food the kitchen used beyond the books is not counted towards the rate")
		void usedBeyondTheBooksIsNotCountedTowardsTheRate() {
			// A judgement whose reasoning is set out in full on StockFactsService.DRAW_TYPES, and it
			// is worth repeating here because the arithmetic points the other way: a shortfall is
			// written for the part of a requirement the batches could not cover, IN ADDITION to the
			// draws that covered the rest, so counting it would double-count nothing and would
			// describe what the kitchen actually got through.
			//
			// What settles it is the rule this codebase keeps about summing this one table. Counting
			// a shortfall means summing the ledger some way other than through to_on_hand_qty, and
			// StockMovementLedgerIT forbids exactly that — T-122 was six readers of one ledger coming
			// to different answers about how much rice there is. A slightly conservative rate is the
			// smaller cost.
			for (int d : new int[] { 14, 12, 10, 8, 4, 0 }) {
				shortfall(rice, "-10", today.minusDays(d));
			}

			assertThat(onHand("Rice")).as("a shortfall subtracts nothing from the shelf")
					.isEqualByComparingTo("0");
			assertThat(row("Rice").lastsFor())
					.as("six days of cooking past the books is still no evidence of a rate")
					.isNull();
		}

		@Test
		@DisplayName("a shortfall beside a real draw neither adds to the rate nor takes the day away")
		void aShortfallBesideADrawDoesNotDisturbTheRate() {
			// The case the exclusion has to get right rather than merely not crash on: the same six
			// days carry a real draw AND a shortfall each. The rate must be the draws' rate, and the
			// days must still count, because the draws are what happened on them.
			received(rice, "160", "KG");
			for (int d : new int[] { 14, 12, 10, 8, 4, 0 }) {
				cooked(rice, "-10", today.minusDays(d));
				shortfall(rice, "-90", today.minusDays(d));
			}

			StockCover cover = row("Rice").lastsFor();
			assertThat(cover).isNotNull();
			assertThat(cover.perDay()).as("60 Kg drawn over 15 days, and not 600").isEqualByComparingTo("4");
		}

		@Test
		@DisplayName("a draw somebody has since reversed is not counted")
		void aCorrectedDrawIsNotCounted() {
			received(rice, "100", "KG");
			for (int d : new int[] { 14, 12, 10, 8, 4 }) {
				cooked(rice, "-10", today.minusDays(d));
			}
			UUID wrong = movement(rice, "-10", "KG", "CONSUMPTION", null, null, today);
			admin.update("""
					INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit,
							movement_type, reason_category, reference_type, reference_id, actor_user_id,
							created_at)
					VALUES (?, ?, ?, 10, 'KG', 'ADJUSTMENT', 'COUNT_CORRECTION', 'CORRECTION', ?, ?, ?)
					""", tenant, rice, UUID.randomUUID(), wrong, staff, at(today));

			assertThat(row("Rice").lastsFor())
					.as("the sixth day was undone, so there are five, and five is not a trend")
					.isNull();
		}

		@Test
		@DisplayName("a shelf that outlasts the evidence says 'more than' rather than a number")
		void aVeryFullShelfIsCappedAtTheWindow() {
			received(rice, "100000", "KG");
			for (int d : new int[] { 14, 12, 10, 8, 4, 0 }) {
				cooked(rice, "-10", today.minusDays(d));
			}

			StockCover cover = row("Rice").lastsFor();
			assertThat(cover).isNotNull();
			assertThat(cover.beyondWindow()).isTrue();
			assertThat(cover.days()).isEqualTo(StockFactsService.COVER_CAP_DAYS);
			assertThat(cover.runsOutOn()).as("there is no honest date past the end of the evidence")
					.isNull();
		}

		@Test
		@DisplayName("an empty shelf with a known rate is nought days, not an absent answer")
		void anEmptyShelfIsNoughtDays() {
			for (int d : new int[] { 14, 12, 10, 8, 4, 0 }) {
				cooked(rice, "-10", today.minusDays(d));
			}

			StockCover cover = row("Rice").lastsFor();
			assertThat(cover).as("we know the rate; the shelf is simply empty").isNotNull();
			assertThat(cover.days()).isZero();
		}

		@Test
		@DisplayName("cooking longer ago than the window is not counted")
		void historyOlderThanTheWindowIsIgnored() {
			received(rice, "100", "KG");
			for (int d : new int[] { 200, 190, 180, 170, 160, 150 }) {
				cooked(rice, "-10", today.minusDays(d));
			}

			assertThat(row("Rice").lastsFor())
					.as("bounded like IssuedFromStoreService's period, and a quarter because a "
							+ "temple's cooking changes with the season")
					.isNull();
		}
	}

	// =====================================================================

	/**
	 * Every fact asked once for the whole screen, whatever the screen holds.
	 *
	 * <p>The bar {@code ShoppingListStatementCountIT} exists to hold, asserted here on the three
	 * queries themselves: a hundred consumables must cost the same three statements as one, or the
	 * per-row read this package has already had once has quietly come back.
	 */
	@Test
	@DisplayName("each fact is one statement for the whole temple, however many consumables there are")
	void everyFactIsOneStatementForTheWholeList() {
		for (int i = 0; i < 40; i++) {
			UUID extra = ingredient("Spice " + i, "KG");
			item(extra);
			received(extra, "5", "KG");
		}

		assertThat(facts.lastCountedByIngredient(null)).isNotNull();
		assertThat(facts.onOrderBaseByIngredient(null)).isNotNull();
		assertThat(facts.dailyUseBaseByIngredient(null)).isNotNull();
		assertThat(inventory.list(null, null, null))
				.as("the fixture above plus rice and the apron")
				.hasSize(42);
	}

	@Test
	@DisplayName("the item's own screen gets the same three facts as its row on the list")
	void theItemScreenAgreesWithTheList() {
		countedOn(rice, today.minusDays(3));
		order("SENT", rice, "200", "KG", null);

		StockItemView onTheList = row("Rice");
		StockItemView onItsOwnPage = inventory.get(riceItem, null).item();

		assertThat(onItsOwnPage.lastCounted()).isEqualTo(onTheList.lastCounted());
		assertThat(onItsOwnPage.onOrder()).isEqualByComparingTo(onTheList.onOrder());
	}

	// ---- Fixtures --------------------------------------------------------

	private StockItemView row(String name) {
		Map<String, StockItemView> byName = inventory.list(null, null, null).stream()
				.collect(Collectors.toMap(StockItemView::ingredientName, Function.identity()));
		return byName.get(name);
	}

	private BigDecimal onHand(String name) {
		return row(name).onHand();
	}

	private UUID ingredient(String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', ?) RETURNING id
				""", UUID.class, tenant, name, unit);
	}

	private UUID item(UUID ingredientId) {
		return admin.queryForObject("""
				INSERT INTO inventory_items (tenant_id, ingredient_id) VALUES (?, ?) RETURNING id
				""", UUID.class, tenant, ingredientId);
	}

	private void received(UUID ingredientId, String quantity, String unit) {
		movement(ingredientId, quantity, unit, "PO_RECEIPT", null, null, today.minusDays(20));
	}

	private void cooked(UUID ingredientId, String quantity, LocalDate on) {
		movement(ingredientId, quantity, "KG", "CONSUMPTION", null, null, on);
	}

	private void shortfall(UUID ingredientId, String quantity, LocalDate on) {
		movement(ingredientId, quantity, "KG", "USED_BEYOND_RECORDED_STOCK", null, null, on);
	}

	/** One ledger row, dated by the temple's own day, with an optional dish it was drawn for. */
	private UUID movement(UUID ingredientId, String quantity, String unit, String type,
			String reason, UUID dishId, LocalDate on) {
		return admin.queryForObject("""
				INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit,
						movement_type, reason_category, reference_type, reference_id, actor_user_id,
						created_at)
				VALUES (?, ?, ?, CAST(? AS numeric), ?, ?, ?, ?, ?, ?, ?) RETURNING id
				""", UUID.class, tenant, ingredientId, UUID.randomUUID(), quantity, unit, type, reason,
				dishId == null ? null : "MEAL_PLAN", dishId, staff, at(on));
	}

	private void countedOn(UUID ingredientId, LocalDate on) {
		movement(ingredientId, "1", "KG", "ADJUSTMENT", "COUNT_CORRECTION", null, on);
	}

	/**
	 * One purchase order of one line, in the given status, with an optional delivery against it.
	 *
	 * <p><strong>The status is set explicitly on every one of these, and that is not tidiness.</strong>
	 * The donor-facing page once summed purchase-order lines with no status filter at all, so drafts
	 * counted as money spent, and no test caught it because the fixture helper inserted no status and
	 * took {@code DRAFT} from the column default — the fixture was wrong in exactly the way the query
	 * was. A helper that cannot be called without saying which status it means cannot repeat it.
	 *
	 * @param ingredientId null for a line describing something the catalogue has never heard of
	 * @param received how much of it has arrived, or null for nothing
	 */
	private void order(String status, UUID ingredientId, String quantity, String unit, String received) {
		String poNumber = "PO-" + UUID.randomUUID().toString().substring(0, 8);
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by,
						closed_at, close_outcome)
				VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
				""", UUID.class, tenant, poNumber, vendor, status, staff,
				"CLOSED".equals(status) ? at(today) : null,
				"CLOSED".equals(status) ? "AS_COMPUTED" : null);
		UUID lineId = admin.queryForObject("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, description, quantity,
						unit, line_order)
				VALUES (?, ?, ?, ?, CAST(? AS numeric), ?, 0) RETURNING id
				""", UUID.class, tenant, poId, ingredientId,
				ingredientId == null ? "Four plastic stools" : null, quantity, unit);

		if (received != null) {
			UUID receiptId = admin.queryForObject("""
					INSERT INTO goods_receipts (tenant_id, po_id, received_by, idempotency_key)
					VALUES (?, ?, ?, ?) RETURNING id
					""", UUID.class, tenant, poId, staff, UUID.randomUUID().toString());
			admin.update("""
					INSERT INTO goods_receipt_lines (tenant_id, receipt_id, po_line_id, ingredient_id,
							received_qty, rejected_qty, unit)
					VALUES (?, ?, ?, ?, CAST(? AS numeric), 0, ?)
					""", tenant, receiptId, lineId, ingredientId, received, unit);
		}
	}

	/** A temple day as the instant the ledger stores, at noon so no time zone can move the date. */
	private java.time.OffsetDateTime at(LocalDate day) {
		return day.atTime(12, 0).atZone(IST).toOffsetDateTime();
	}
}
