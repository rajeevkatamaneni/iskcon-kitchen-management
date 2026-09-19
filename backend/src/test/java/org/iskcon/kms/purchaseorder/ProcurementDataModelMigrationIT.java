package org.iskcon.kms.purchaseorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V144, the procurement data model, run against temples that already buy, receive, get billed and
 * pay (T-248, PROCUREMENT-REQUIREMENTS §3, AC-3.2).
 *
 * <p><b>Why this exists.</b> Everything the temple has already recorded about money and stock —
 * orders, deliveries, bills, payments, what is on the shelf — has to come through the migration
 * exactly as it was. The suite otherwise migrates an empty database, where a migration that
 * quietly rewrote a price or dropped a payment would look exactly like one that worked. Following
 * {@link org.iskcon.kms.meal.CateringMigrationIT}, this builds a throwaway database, migrates it to
 * V143, puts two temples with real procurement history into it, snapshots every row of every table
 * the migration touches or sits beside, runs V144, and compares.
 *
 * <p>It also checks the one piece of V144 that moves data — the alias backfill, which has to adopt
 * each temple in turn because the migration role is subject to row-level security — and the rules
 * the new schema holds for itself, exercised as the unprivileged application role.
 *
 * <p>Pinned to V144 rather than "latest", so a later migration that legitimately changes one of
 * these rows cannot make this test fail for a reason that has nothing to do with V144.
 *
 * <p><b>V146 (T-260)</b> is checked last, on the same database, by the same method: snapshot the
 * order lines and what hangs off them, migrate to V146, compare. It widens
 * {@code purchase_order_lines.expected_price} to four places, which changes how every existing price
 * prints as text (58.00 becomes 58.0000), so that one column is compared by value rather than by
 * its text; everything else is compared byte for byte, as above.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// The rules test writes into the migrated database, so it runs last: the three before it read the
// database exactly as V144 left it.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProcurementDataModelMigrationIT extends AbstractIntegrationTest {

	private static final String BEFORE = "143";
	private static final String UNDER_TEST = "144";

	private static final String DATABASE = "kms_procurement_model_check";

	/**
	 * The tables whose rows must survive, and the columns V144 adds to each (stripped before
	 * comparing, and asserted empty separately). The five §3 names — orders, receipts, invoices,
	 * payments, on-hand stock — plus everything V144 alters or hangs a foreign key off.
	 */
	private static final Map<String, List<String>> SURVIVORS = new LinkedHashMap<>();

	static {
		SURVIVORS.put("purchase_orders", List.of());
		SURVIVORS.put("purchase_order_lines", List.of());
		SURVIVORS.put("goods_receipts", List.of());
		SURVIVORS.put("goods_receipt_lines", List.of());
		SURVIVORS.put("vendor_invoices", List.of(
				"sub_total", "gst_amount", "other_charges", "other_charges_note", "discount", "grand_total"));
		SURVIVORS.put("invoice_payments", List.of("received_by_name"));
		SURVIVORS.put("stock_movements", List.of());
		SURVIVORS.put("inventory_items", List.of());
		SURVIVORS.put("vendors", List.of());
		SURVIVORS.put("vendor_supplies", List.of("pack_size_id", "price_per_pack"));
		SURVIVORS.put("ingredients", List.of("market_rate", "market_rate_on", "market_rate_source"));
		SURVIVORS.put("recipe_ingredients", List.of("preparation_note"));
	}

	private final Map<String, String> before = new LinkedHashMap<>();
	private String onHandBefore;

	@BeforeAll
	void migrateATempleThroughV144() throws SQLException {
		recreateDatabase();
		migrateTo(BEFORE);
		seedTemples();

		for (String table : SURVIVORS.keySet()) {
			before.put(table, snapshot(table, List.of()));
		}
		onHandBefore = onHand();

		migrateTo(UNDER_TEST);
	}

	@Order(1)
	@Test
	@DisplayName("every order, delivery, bill, payment, stock movement, supply and recipe line survives unchanged")
	void existingDataSurvivesUnchanged() throws SQLException {
		for (Map.Entry<String, List<String>> table : SURVIVORS.entrySet()) {
			assertThat(before.get(table.getKey()))
					.as("the fixture must have put rows in %s, or this compares nothing", table.getKey())
					.isNotBlank();
			assertThat(snapshot(table.getKey(), table.getValue()))
					.as("every row of %s, every column that existed before V144, byte for byte", table.getKey())
					.isEqualTo(before.get(table.getKey()));
		}

		// On-hand stock is derived (the sum of the ledger), so it gets its own check in the form a
		// person would read it: per temple, per ingredient, per unit.
		assertThat(onHand()).as("what is on the shelf").isEqualTo(onHandBefore);
		assertThat(onHandBefore).contains("rice-temple-a|Rice|KG|98.000");
	}

	@Order(2)
	@Test
	@DisplayName("the new columns on existing rows are empty: nothing is invented for rows recorded before V144")
	void newColumnsAreEmptyOnExistingRows() throws SQLException {
		for (Map.Entry<String, List<String>> table : SURVIVORS.entrySet()) {
			for (String column : table.getValue()) {
				assertThat(count("SELECT count(*) FROM " + table.getKey() + " WHERE " + column + " IS NOT NULL"))
						.as("%s.%s on a row recorded before V144", table.getKey(), column)
						.isZero();
			}
		}
		assertThat(count("SELECT count(*) FROM vendor_price_history")).isZero();
		assertThat(count("SELECT count(*) FROM ingredient_market_rate_history")).isZero();
		assertThat(count("SELECT count(*) FROM ingredient_pack_sizes")).isZero();
		assertThat(count("SELECT count(*) FROM vendor_invoice_lines")).isZero();
	}

	@Order(3)
	@Test
	@DisplayName("aliases are backfilled per temple: trimmed, blanks skipped, a shared alias kept on the older ingredient")
	void aliasesAreBackfilledPerTemple() throws SQLException {
		// The loop body ran for both temples, which is the point: a single INSERT ... SELECT under
		// row-level security would have read no ingredients and written nothing.
		assertThat(strings("""
				SELECT t.slug || '|' || i.name || '|' || a.alias || '|' || a.normalised_alias
				FROM ingredient_aliases a
				JOIN ingredients i ON i.id = a.ingredient_id
				JOIN tenants t ON t.id = a.tenant_id
				ORDER BY t.slug, a.normalised_alias
				"""))
				.containsExactly(
						// "Ponni" is only on Sona Masoori, so it goes there.
						"rice-temple-a|Sona Masoori|Ponni|ponni",
						// "Raw Rice", " raw rice " (on Rice) and "RAW RICE" (on Sona Masoori) are one
						// name. Rice was created first, so it keeps it; Sona Masoori's copy is dropped
						// and reported, not an error.
						"rice-temple-a|Rice|Raw Rice|raw rice",
						"rice-temple-b|Sona Masoori|Ponni|ponni",
						// The same alias in another temple is that temple's own.
						"rice-temple-b|Rice|Raw Rice|raw rice");

		// Every alias belongs to an ingredient of its own temple.
		assertThat(count("""
				SELECT count(*) FROM ingredient_aliases a JOIN ingredients i ON i.id = a.ingredient_id
				WHERE i.tenant_id <> a.tenant_id
				""")).isZero();

		// And the array the application still reads is left exactly as it was (the snapshot above
		// covers ingredients whole; this names the point).
		assertThat(strings("SELECT array_to_string(aliases, ',') FROM ingredients WHERE name = 'Rice' ORDER BY 1"))
				.containsExactly("Raw Rice, raw rice ,", "Raw Rice, raw rice ,");
	}

	@Order(4)
	@Test
	@DisplayName("the new schema holds its own rules against the application role")
	void theSchemaHoldsItsRules() throws SQLException {
		try (Connection app = appConnection()) {
			String temple = strings("SELECT id::text FROM tenants WHERE slug = 'rice-temple-a'").get(0);
			String rice = strings("SELECT i.id::text FROM ingredients i JOIN tenants t ON t.id = i.tenant_id"
					+ " WHERE t.slug = 'rice-temple-a' AND i.name = 'Rice'").get(0);
			String ghee = strings("SELECT i.id::text FROM ingredients i JOIN tenants t ON t.id = i.tenant_id"
					+ " WHERE t.slug = 'rice-temple-a' AND i.name = 'Ghee'").get(0);
			String riceOfTempleB = strings("SELECT i.id::text FROM ingredients i JOIN tenants t ON t.id = i.tenant_id"
					+ " WHERE t.slug = 'rice-temple-b' AND i.name = 'Rice'").get(0);
			String admin = strings("SELECT u.id::text FROM users u JOIN tenants t ON t.id = u.tenant_id"
					+ " WHERE t.slug = 'rice-temple-a'").get(0);
			String invoice = strings("SELECT v.id::text FROM vendor_invoices v JOIN tenants t ON t.id = v.tenant_id"
					+ " WHERE t.slug = 'rice-temple-a' AND NOT v.direct").get(0);
			String payment = strings("SELECT p.id::text FROM invoice_payments p JOIN tenants t ON t.id = p.tenant_id"
					+ " WHERE t.slug = 'rice-temple-a' AND p.amount > 0 ORDER BY p.amount DESC LIMIT 1").get(0);
			adopt(app, temple);

			// --- pack sizes (R-ING-1) ---------------------------------------------------------
			String bag = one(app, "INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)"
					+ " VALUES ('" + temple + "', '" + rice + "', 'Bag', 25, 'KG') RETURNING id::text");
			one(app, "INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, quantity, unit)"
					+ " VALUES ('" + temple + "', '" + rice + "', 500, 'GM') RETURNING id::text");
			assertThat(one(app, "SELECT base_quantity || '|' || unit_family FROM ingredient_pack_sizes WHERE id = '"
					+ bag + "'")).isEqualTo("25000.000|MASS");

			refused(app, "a litre size on rice, which is counted in Kg", "same family",
					"INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)"
							+ " VALUES ('" + temple + "', '" + rice + "', 'Tin', 15, 'L')");
			refused(app, "0.5 Kg when 500 gm exists (PROVISIONAL: same size whatever the name)",
					"ingredient_pack_sizes_no_duplicate",
					"INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)"
							+ " VALUES ('" + temple + "', '" + rice + "', 'Pack', 0.5, 'KG')");
			refused(app, "a size of nothing", "ingredient_pack_sizes_quantity_positive",
					"INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, quantity, unit)"
							+ " VALUES ('" + temple + "', '" + rice + "', 0, 'KG')");
			refused(app, "a pack size on another temple's ingredient", "not found for this temple",
					"INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, quantity, unit)"
							+ " VALUES ('" + temple + "', '" + riceOfTempleB + "', 1, 'KG')");

			// --- "sells it as" (R-VEN-1) --------------------------------------------------------
			assertThat(app.createStatement().executeUpdate(
					"UPDATE vendor_supplies SET pack_size_id = '" + bag + "', price_per_pack = 1500"
							+ " WHERE ingredient_id = '" + rice + "'")).isEqualTo(1);
			refused(app, "ghee sold in rice's bag", "vendor_supplies_pack_of_this_ingredient",
					"UPDATE vendor_supplies SET pack_size_id = '" + bag + "' WHERE ingredient_id = '" + ghee + "'");
			refused(app, "a pack price with no pack", "vendor_supplies_pack_price_needs_pack",
					"UPDATE vendor_supplies SET price_per_pack = 10 WHERE ingredient_id = '" + ghee + "'");

			// --- invoice lines (R-INV-4) --------------------------------------------------------
			String line = one(app, "INSERT INTO vendor_invoice_lines (tenant_id, invoice_id, ingredient_id, billed_qty,"
					+ " unit, pack_size_id, pack_count, line_amount) VALUES ('" + temple + "', '" + invoice + "', '"
					+ rice + "', 100, 'KG', '" + bag + "', 4, 6000) RETURNING id::text");
			assertThat(one(app, "SELECT rate::text FROM vendor_invoice_lines WHERE id = '" + line + "'"))
					.as("4 × Bag (25 Kg) for ₹6,000 is ₹60 / Kg, derived").isEqualTo("60.0000");
			assertThat(one(app, "INSERT INTO vendor_invoice_lines (tenant_id, invoice_id, ingredient_id, billed_qty,"
					+ " unit, line_amount) VALUES ('" + temple + "', '" + invoice + "', '" + ghee + "', 0, 'L', 0)"
					+ " RETURNING coalesce(rate::text, 'none')"))
					.as("an item not billed has no rate, not a division by zero").isEqualTo("none");
			refused(app, "a line that is neither an ingredient nor described", "invoice_lines_has_exactly_one_subject",
					"INSERT INTO vendor_invoice_lines (tenant_id, invoice_id, billed_qty, unit, line_amount)"
							+ " VALUES ('" + temple + "', '" + invoice + "', 1, 'PIECES', 50)");
			refused(app, "ghee billed in rice's bag", "invoice_lines_pack_of_this_ingredient",
					"INSERT INTO vendor_invoice_lines (tenant_id, invoice_id, ingredient_id, billed_qty, unit,"
							+ " pack_size_id, pack_count, line_amount) VALUES ('" + temple + "', '" + invoice + "', '"
							+ ghee + "', 25, 'L', '" + bag + "', 1, 100)");
			one(app, "INSERT INTO vendor_invoice_lines (tenant_id, invoice_id, description, billed_qty, unit,"
					+ " line_amount) VALUES ('" + temple + "', '" + invoice + "', 'Plastic stool', 4, 'PIECES', 800)"
					+ " RETURNING id::text");

			// --- price history (R-VEN-3, R-VEN-4) and market rate (R-ING-3) -------------------
			String vendor = strings("SELECT v.id::text FROM vendors v JOIN tenants t ON t.id = v.tenant_id"
					+ " WHERE t.slug = 'rice-temple-a'").get(0);
			refused(app, "an INVOICE price that names no invoice line", "vendor_price_history_invoice_shape",
					"INSERT INTO vendor_price_history (tenant_id, vendor_id, ingredient_id, price_per_unit,"
							+ " effective_on, source, set_by) VALUES ('" + temple + "', '" + vendor + "', '" + rice
							+ "', 60, CURRENT_DATE, 'INVOICE', '" + admin + "')");
			one(app, "INSERT INTO vendor_price_history (tenant_id, vendor_id, ingredient_id, price_per_unit,"
					+ " price_per_pack, pack_description, effective_on, source, vendor_invoice_line_id, set_by)"
					+ " VALUES ('" + temple + "', '" + vendor + "', '" + rice + "', 60, 1500, 'Bag = 25 Kg',"
					+ " CURRENT_DATE, 'INVOICE', '" + line + "', '" + admin + "') RETURNING id::text");
			refused(app, "editing a price already in the history", "append-only",
					"UPDATE vendor_price_history SET price_per_unit = 1");

			// PROVISIONAL: history survives the supply being removed, keeping its vendor and ingredient.
			assertThat(app.createStatement().executeUpdate(
					"DELETE FROM vendor_supplies WHERE ingredient_id = '" + rice + "'")).isEqualTo(1);
			assertThat(one(app, "SELECT count(*)::text FROM vendor_price_history WHERE vendor_id = '" + vendor
					+ "' AND ingredient_id = '" + rice + "'")).isEqualTo("1");

			refused(app, "a market rate of ₹0", "ingredients_market_rate_positive",
					"UPDATE ingredients SET market_rate = 0, market_rate_on = CURRENT_DATE,"
							+ " market_rate_source = 'STOCK_TAKE' WHERE id = '" + rice + "'");
			refused(app, "a market rate with no date or source", "ingredients_market_rate_shape",
					"UPDATE ingredients SET market_rate = 60 WHERE id = '" + rice + "'");
			assertThat(app.createStatement().executeUpdate(
					"UPDATE ingredients SET market_rate = 60, market_rate_on = CURRENT_DATE,"
							+ " market_rate_source = 'STOCK_TAKE' WHERE id = '" + rice + "'")).isEqualTo(1);

			// --- attachments (R-INV-2, R-PAY-2) -------------------------------------------------
			refused(app, "a bill filed as the proof of a payment", "attachments_parent_matches_kind",
					"INSERT INTO attachments (tenant_id, kind, storage_key, content_type, size_bytes, payment_id,"
							+ " uploaded_by) VALUES ('" + temple + "', 'INVOICE_BILL', 'k/1', 'image/jpeg', 10, '"
							+ payment + "', '" + admin + "')");
			one(app, "INSERT INTO attachments (tenant_id, kind, storage_key, content_type, size_bytes, payment_id,"
					+ " uploaded_by) VALUES ('" + temple + "', 'CASH_RECEIVER_PHOTO', 'k/2', 'image/jpeg', 10, '"
					+ payment + "', '" + admin + "') RETURNING id::text");

			// --- a preparation note (R-DUP-1) and a cash receiver's name (R-PAY-2) ---------------
			refused(app, "a blank preparation note", "recipe_ingredients_preparation_not_blank",
					"UPDATE recipe_ingredients SET preparation_note = '  '");
			refused(app, "a blank receiver name", "invoice_payments_received_by_not_blank",
					"INSERT INTO invoice_payments (tenant_id, invoice_id, paid_on, amount, method, recorded_by,"
							+ " received_by_name) VALUES ('" + temple + "', '" + invoice + "', CURRENT_DATE, 1, 'CASH', '"
							+ admin + "', '')");

			// --- aliases (R-DUP-3) ----------------------------------------------------------------
			refused(app, "a second ingredient answering to 'raw rice'", "ingredient_aliases_one_per_name",
					"INSERT INTO ingredient_aliases (tenant_id, ingredient_id, alias, normalised_alias)"
							+ " VALUES ('" + temple + "', '" + ghee + "', 'Raw rice', 'raw rice')");
		}
	}

	@Order(5)
	@Test
	@DisplayName("V146: every order line survives with its price's value unchanged, and the pack columns hold their rules")
	void v146KeepsOrderLinesAndHoldsThePackRules() throws SQLException {
		// Taken now, after the rules test above, which writes pack sizes and invoice lines but no order
		// line: this is the database exactly as V146 will find it.
		List<String> v146Tables = List.of("purchase_orders", "purchase_order_lines", "goods_receipt_lines");
		Map<String, String> beforeV146 = new LinkedHashMap<>();
		for (String table : v146Tables) {
			beforeV146.put(table, snapshot(table, table.equals("purchase_order_lines")
					? List.of("expected_price") : List.of()));
		}
		String pricesBefore = linePrices();
		assertThat(pricesBefore).as("the fixture has priced order lines to compare").contains("|58");

		migrateTo("146");

		for (String table : v146Tables) {
			assertThat(snapshot(table, table.equals("purchase_order_lines")
					? List.of("expected_price", "pack_size_id", "pack_count") : List.of()))
					.as("every row of %s, every other column, byte for byte across V146", table)
					.isEqualTo(beforeV146.get(table));
		}
		assertThat(linePrices()).as("every expected price keeps its value").isEqualTo(pricesBefore);
		assertThat(count("SELECT count(*) FROM purchase_order_lines WHERE pack_size_id IS NOT NULL"
				+ " OR pack_count IS NOT NULL")).as("no existing line is given a pack").isZero();
		assertThat(strings("SELECT numeric_precision || ',' || numeric_scale FROM information_schema.columns"
				+ " WHERE table_name = 'purchase_order_lines' AND column_name = 'expected_price'"))
				.containsExactly("14,4");

		try (Connection app = appConnection()) {
			String temple = strings("SELECT id::text FROM tenants WHERE slug = 'rice-temple-a'").get(0);
			String rice = strings("SELECT i.id::text FROM ingredients i JOIN tenants t ON t.id = i.tenant_id"
					+ " WHERE t.slug = 'rice-temple-a' AND i.name = 'Rice'").get(0);
			String ghee = strings("SELECT i.id::text FROM ingredients i JOIN tenants t ON t.id = i.tenant_id"
					+ " WHERE t.slug = 'rice-temple-a' AND i.name = 'Ghee'").get(0);
			String po = strings("SELECT p.id::text FROM purchase_orders p JOIN tenants t ON t.id = p.tenant_id"
					+ " WHERE t.slug = 'rice-temple-a'").get(0);
			String bag = strings("SELECT p.id::text FROM ingredient_pack_sizes p JOIN tenants t ON t.id = p.tenant_id"
					+ " WHERE t.slug = 'rice-temple-a' AND p.name = 'Bag'").get(0);
			adopt(app, temple);

			// 4 × Bag (25 Kg), kept as 100 Kg, at a per-gram-sized price that two places would round.
			String line = one(app, "INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit,"
					+ " expected_price, pack_size_id, pack_count) VALUES ('" + temple + "', '" + po + "', '" + rice
					+ "', 100, 'KG', 0.0712, '" + bag + "', 4) RETURNING id::text");
			assertThat(one(app, "SELECT expected_price::text FROM purchase_order_lines WHERE id = '" + line + "'"))
					.as("₹0.0712 is kept, not rounded to ₹0.07").isEqualTo("0.0712");

			refused(app, "ghee ordered in rice's bag", "po_lines_pack_of_this_ingredient",
					"INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, pack_size_id,"
							+ " pack_count) VALUES ('" + temple + "', '" + po + "', '" + ghee + "', 25, 'L', '" + bag + "', 1)");
			refused(app, "a one-off line in a pack", "po_lines_pack_shape",
					"INSERT INTO purchase_order_lines (tenant_id, po_id, description, quantity, unit, pack_size_id,"
							+ " pack_count) VALUES ('" + temple + "', '" + po + "', 'Plastic stool', 4, 'PIECES', '"
							+ bag + "', 1)");
			refused(app, "a pack with no count", "po_lines_pack_shape",
					"INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, pack_size_id)"
							+ " VALUES ('" + temple + "', '" + po + "', '" + rice + "', 25, 'KG', '" + bag + "')");
			refused(app, "a count with no pack", "po_lines_pack_shape",
					"INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, pack_count)"
							+ " VALUES ('" + temple + "', '" + po + "', '" + rice + "', 25, 'KG', 1)");
			refused(app, "no bags at all", "po_lines_pack_shape",
					"INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, pack_size_id,"
							+ " pack_count) VALUES ('" + temple + "', '" + po + "', '" + rice + "', 25, 'KG', '" + bag + "', 0)");
		}
	}

	@Order(6)
	@Test
	@DisplayName("V148: invoices, lines and links survive; a voided bill's links are released per temple; a pack needs a count; one standing bill per delivery")
	void v148HoldsTheInvoiceRules() throws SQLException {
		// Links V148 must find: temple A's delivery-1 on a bill that was struck, and temple B's
		// delivery-1 on one that stands. Seeded as the superuser (what is under test is the migration);
		// the backfill itself runs as the unprivileged migration role under row-level security, so it
		// only releases temple A's link if it really adopts each temple in turn.
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {
			statement.execute("""
					UPDATE vendor_invoices v SET status = 'VOIDED', voided_at = TIMESTAMPTZ '2026-09-15 10:00+05:30',
						voided_by = v.created_by, void_reason = 'Keyed wrong'
					FROM tenants t WHERE t.id = v.tenant_id AND t.slug = 'rice-temple-a' AND v.invoice_number = 'KAL-881'
					""");
			statement.execute("""
					INSERT INTO vendor_invoice_deliveries (tenant_id, invoice_id, receipt_id)
					SELECT v.tenant_id, v.id, r.id
					FROM vendor_invoices v
					JOIN goods_receipts r ON r.tenant_id = v.tenant_id AND r.idempotency_key = 'delivery-1'
					WHERE v.invoice_number = 'KAL-881'
					""");
		}
		List<String> v148Tables = List.of("vendor_invoices", "vendor_invoice_lines", "vendor_invoice_deliveries");
		Map<String, String> beforeV148 = new LinkedHashMap<>();
		for (String table : v148Tables) {
			beforeV148.put(table, snapshot(table, List.of()));
		}

		migrateTo("148");

		assertThat(snapshot("vendor_invoices", List.of())).as("every invoice, byte for byte across V148")
				.isEqualTo(beforeV148.get("vendor_invoices"));
		assertThat(snapshot("vendor_invoice_lines", List.of("line_order")))
				.as("every invoice line, every other column, byte for byte").isEqualTo(beforeV148.get("vendor_invoice_lines"));
		assertThat(snapshot("vendor_invoice_deliveries", List.of("released_at")))
				.isEqualTo(beforeV148.get("vendor_invoice_deliveries"));
		assertThat(strings("SELECT t.slug || '|' || CASE WHEN d.released_at IS NULL THEN 'standing'"
				+ " WHEN d.released_at = TIMESTAMPTZ '2026-09-15 10:00+05:30' THEN 'released at the void' ELSE 'released' END"
				+ " FROM vendor_invoice_deliveries d JOIN tenants t ON t.id = d.tenant_id ORDER BY t.slug"))
				.as("the struck bill's link is released at its void time; the standing one is not")
				.containsExactly("rice-temple-a|released at the void", "rice-temple-b|standing");

		try (Connection app = appConnection()) {
			String temple = strings("SELECT id::text FROM tenants WHERE slug = 'rice-temple-b'").get(0);
			String rice = strings("SELECT i.id::text FROM ingredients i JOIN tenants t ON t.id = i.tenant_id"
					+ " WHERE t.slug = 'rice-temple-b' AND i.name = 'Rice'").get(0);
			String vendor = strings("SELECT v.id::text FROM vendors v JOIN tenants t ON t.id = v.tenant_id"
					+ " WHERE t.slug = 'rice-temple-b'").get(0);
			String admin = strings("SELECT u.id::text FROM users u JOIN tenants t ON t.id = u.tenant_id"
					+ " WHERE t.slug = 'rice-temple-b'").get(0);
			String invoice = strings("SELECT v.id::text FROM vendor_invoices v JOIN tenants t ON t.id = v.tenant_id"
					+ " WHERE t.slug = 'rice-temple-b' AND v.invoice_number = 'KAL-881'").get(0);
			String delivery1 = strings("SELECT r.id::text FROM goods_receipts r JOIN tenants t ON t.id = r.tenant_id"
					+ " WHERE t.slug = 'rice-temple-b' AND r.idempotency_key = 'delivery-1'").get(0);
			adopt(app, temple);
			String bag = one(app, "INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)"
					+ " VALUES ('" + temple + "', '" + rice + "', 'Bag', 25, 'KG') RETURNING id::text");

			// V144's hole, closed: a pack with no count used to evaluate to NULL and pass.
			refused(app, "a bill line in a pack with no count", "invoice_lines_pack_shape",
					"INSERT INTO vendor_invoice_lines (tenant_id, invoice_id, ingredient_id, billed_qty, unit,"
							+ " pack_size_id, line_amount) VALUES ('" + temple + "', '" + invoice + "', '" + rice
							+ "', 25, 'KG', '" + bag + "', 1500)");
			one(app, "INSERT INTO vendor_invoice_lines (tenant_id, invoice_id, ingredient_id, billed_qty, unit,"
					+ " pack_size_id, pack_count, line_amount) VALUES ('" + temple + "', '" + invoice + "', '" + rice
					+ "', 100, 'KG', '" + bag + "', 4, 6000) RETURNING id::text");

			// One standing bill per delivery, held by the database: a second standing link is refused.
			String second = one(app, "INSERT INTO vendor_invoices (tenant_id, vendor_id, invoice_number, invoice_date,"
					+ " amount, created_by, sub_total, gst_amount, other_charges, discount, grand_total) VALUES ('"
					+ temple + "', '" + vendor + "', 'KAL-900', CURRENT_DATE, 100, '" + admin
					+ "', 100, 0, 0, 0, 100) RETURNING id::text");
			refused(app, "a delivery on two standing bills", "invoice_deliveries_billed_once",
					"INSERT INTO vendor_invoice_deliveries (tenant_id, invoice_id, receipt_id) VALUES ('" + temple
							+ "', '" + second + "', '" + delivery1 + "')");
			// Released, it can be billed again.
			try (Statement statement = app.createStatement()) {
				statement.execute("UPDATE vendor_invoice_deliveries SET released_at = now() WHERE receipt_id = '"
						+ delivery1 + "'");
				statement.execute("INSERT INTO vendor_invoice_deliveries (tenant_id, invoice_id, receipt_id) VALUES ('"
						+ temple + "', '" + second + "', '" + delivery1 + "')");
			}

			// The relaxed shape: an itemised bill may name no single order, and a direct one no description.
			// That bill above ('KAL-900', not direct, no order, itemised) was itself accepted. Without a
			// sub total — an old-shape bill — both are still refused, exactly as V28 said.
			one(app, "INSERT INTO vendor_invoices (tenant_id, vendor_id, direct, invoice_number, invoice_date, amount,"
					+ " created_by, sub_total, gst_amount, other_charges, discount, grand_total) VALUES ('" + temple
					+ "', '" + vendor + "', true, 'CASH-2', CURRENT_DATE, 50, '" + admin
					+ "', 50, 0, 0, 0, 50) RETURNING id::text");
			refused(app, "an old-shape bill with no order that is not direct", "vendor_invoices_direct_shape",
					"INSERT INTO vendor_invoices (tenant_id, vendor_id, invoice_number, invoice_date, amount, created_by)"
							+ " VALUES ('" + temple + "', '" + vendor + "', 'OLD-1', CURRENT_DATE, 10, '" + admin + "')");
			refused(app, "an old-shape direct bill with no description", "vendor_invoices_direct_shape",
					"INSERT INTO vendor_invoices (tenant_id, vendor_id, direct, invoice_number, invoice_date, amount,"
							+ " created_by) VALUES ('" + temple + "', '" + vendor + "', true, 'OLD-2', CURRENT_DATE, 10, '"
							+ admin + "')");
		}
	}

	/** Each order line's expected price by value — trim_scale, so 58.00 and 58.0000 read the same. */
	private String linePrices() throws SQLException {
		return String.join("\n", strings(
				"SELECT id::text || '|' || coalesce(trim_scale(expected_price)::text, 'none')"
						+ " FROM purchase_order_lines ORDER BY id"));
	}

	// ---- The fixture ------------------------------------------------------------------------------

	/**
	 * Two temples with the procurement history V144 must not disturb: a sent order received in two
	 * deliveries (one with a rejection), the bill for it and a direct cash-market bill, a bank
	 * payment that was reversed and paid again, a cash payment, a donation in kind, a recipe using
	 * the stock, and ingredient aliases shaped to test every branch of the backfill.
	 *
	 * <p>Seeded through the superuser: what is under test is the migration, not the seed.
	 */
	private void seedTemples() throws SQLException {
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE FUNCTION pg_temp.seed_temple(p_slug text, p_phone text) RETURNS void AS $$
					DECLARE
					  v_t uuid; v_admin uuid; v_rice uuid; v_sona uuid; v_ghee uuid; v_cat uuid; v_recipe uuid;
					  v_vendor uuid; v_po uuid; v_pol uuid; v_move1 uuid; v_move2 uuid; v_gr1 uuid; v_gr2 uuid;
					  v_inv uuid; v_direct uuid; v_pay uuid;
					  v_batch uuid := gen_random_uuid(); v_batch2 uuid := gen_random_uuid(); v_gbatch uuid := gen_random_uuid();
					BEGIN
					  INSERT INTO tenants (slug, name, latitude, longitude, timezone)
					    VALUES (p_slug, p_slug, 12.97, 77.59, 'Asia/Kolkata') RETURNING id INTO v_t;
					  INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role)
					    VALUES (v_t, p_slug || '-admin', 'Admin', p_slug || '-admin@example.com', p_phone, 'TEMPLE_ADMIN')
					    RETURNING id INTO v_admin;

					  -- Aliases shaped for the backfill: the same name three ways across two ingredients,
					  -- a blank, and one only the newer ingredient has.
					  INSERT INTO ingredients (tenant_id, name, category, canonical_unit, aliases, created_at)
					    VALUES (v_t, 'Rice', 'Grains', 'KG', ARRAY['Raw Rice', ' raw rice ', ''], TIMESTAMPTZ '2025-01-01')
					    RETURNING id INTO v_rice;
					  INSERT INTO ingredients (tenant_id, name, category, canonical_unit, aliases, created_at)
					    VALUES (v_t, 'Sona Masoori', 'Grains', 'KG', ARRAY['RAW RICE', 'Ponni'], TIMESTAMPTZ '2025-06-01')
					    RETURNING id INTO v_sona;
					  INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
					    VALUES (v_t, 'Ghee', 'Dairy', 'L') RETURNING id INTO v_ghee;
					  INSERT INTO inventory_items (tenant_id, ingredient_id, storage_location, reorder_threshold)
					    VALUES (v_t, v_rice, 'Main store', 20);

					  INSERT INTO recipe_categories (tenant_id, name) VALUES (v_t, 'Rice') RETURNING id INTO v_cat;
					  INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
					    VALUES (v_t, 'Ghee rice', v_cat, 10, 'KG') RETURNING id INTO v_recipe;
					  INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
					    VALUES (v_t, v_recipe, v_rice, 5, 'KG', 1), (v_t, v_recipe, v_ghee, 250, 'ML', 2);

					  INSERT INTO vendors (tenant_id, name, phone)
					    VALUES (v_t, 'Kalasipalya', '+919876500200') RETURNING id INTO v_vendor;
					  INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred)
					    VALUES (v_t, v_vendor, v_rice, 60, true), (v_t, v_vendor, v_ghee, NULL, false);

					  -- An order for 100 Kg, delivered as 60 (2 rejected, spoiled) and then 40.
					  INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by, sent_at)
					    VALUES (v_t, 'PO-2026-0044', v_vendor, 'RECEIVED', v_admin, now()) RETURNING id INTO v_po;
					  INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, expected_price)
					    VALUES (v_t, v_po, v_rice, 100, 'KG', 58) RETURNING id INTO v_pol;
					  INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					                               reference_type, reference_id, actor_user_id, expiry_date)
					    VALUES (v_t, v_rice, v_batch, 58, 'KG', 'PO_RECEIPT', 'PURCHASE_ORDER', v_po, v_admin, DATE '2027-01-01')
					    RETURNING id INTO v_move1;
					  INSERT INTO goods_receipts (tenant_id, po_id, idempotency_key, received_by, note)
					    VALUES (v_t, v_po, 'delivery-1', v_admin, 'Lorry late') RETURNING id INTO v_gr1;
					  INSERT INTO goods_receipt_lines (tenant_id, receipt_id, po_line_id, ingredient_id, received_qty,
					                                   rejected_qty, reject_reason, unit, batch_id, expiry_date,
					                                   stock_movement_id, unit_price)
					    VALUES (v_t, v_gr1, v_pol, v_rice, 58, 2, 'SPOILED', 'KG', v_batch, DATE '2027-01-01', v_move1, 59);
					  INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					                               reference_type, reference_id, actor_user_id)
					    VALUES (v_t, v_rice, v_batch2, 40, 'KG', 'PO_RECEIPT', 'PURCHASE_ORDER', v_po, v_admin)
					    RETURNING id INTO v_move2;
					  INSERT INTO goods_receipts (tenant_id, po_id, idempotency_key, received_by)
					    VALUES (v_t, v_po, 'delivery-2', v_admin) RETURNING id INTO v_gr2;
					  INSERT INTO goods_receipt_lines (tenant_id, receipt_id, po_line_id, ingredient_id, received_qty,
					                                   unit, batch_id, stock_movement_id)
					    VALUES (v_t, v_gr2, v_pol, v_rice, 40, 'KG', v_batch2, v_move2);
					  INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
					    VALUES (v_t, v_ghee, v_gbatch, 4, 'L', 'DONATION_IN_KIND', v_admin);

					  -- The bill for the order, and a cash-market bill with no order.
					  INSERT INTO vendor_invoices (tenant_id, vendor_id, po_id, invoice_number, invoice_date, amount,
					                               due_date, scan_ref, created_by)
					    VALUES (v_t, v_vendor, v_po, 'KAL-881', DATE '2026-09-12', 5880, DATE '2026-10-12', 'scans/kal-881',
					            v_admin) RETURNING id INTO v_inv;
					  INSERT INTO vendor_invoices (tenant_id, vendor_id, direct, description, invoice_number, invoice_date,
					                               amount, created_by)
					    VALUES (v_t, v_vendor, true, 'Cash market: banana leaves', 'CM-1', DATE '2026-09-13', 300, v_admin)
					    RETURNING id INTO v_direct;

					  -- Paid by bank, the transfer bounced and was reversed, paid again; the market bill in cash.
					  INSERT INTO invoice_payments (tenant_id, invoice_id, paid_on, amount, method, reference, recorded_by)
					    VALUES (v_t, v_inv, DATE '2026-09-14', 3000, 'BANK_TRANSFER', 'UTR-1', v_admin) RETURNING id INTO v_pay;
					  INSERT INTO invoice_payments (tenant_id, invoice_id, paid_on, amount, method, recorded_by, reverses,
					                                reverse_reason)
					    VALUES (v_t, v_inv, DATE '2026-09-15', -3000, 'BANK_TRANSFER', v_admin, v_pay, 'Transfer bounced');
					  INSERT INTO invoice_payments (tenant_id, invoice_id, paid_on, amount, method, reference, recorded_by)
					    VALUES (v_t, v_inv, DATE '2026-09-16', 3000, 'UPI', 'UPI-77', v_admin);
					  INSERT INTO invoice_payments (tenant_id, invoice_id, paid_on, amount, method, note, recorded_by)
					    VALUES (v_t, v_direct, DATE '2026-09-13', 300, 'CASH', 'Paid at the stall', v_admin);
					END $$ LANGUAGE plpgsql
					""");
			statement.execute("SELECT pg_temp.seed_temple('rice-temple-a', '+919876500301')");
			statement.execute("SELECT pg_temp.seed_temple('rice-temple-b', '+919876500302')");
		}
	}

	// ---- Helpers ----------------------------------------------------------------------------------

	/**
	 * Every row of a table as JSON, in id order, with the named columns removed. jsonb orders its
	 * keys itself, so two snapshots of the same row compare equal as text however the columns were
	 * laid out.
	 */
	private String snapshot(String table, List<String> addedColumns) throws SQLException {
		String strip = addedColumns.isEmpty() ? ""
				: " - ARRAY[" + String.join(", ", addedColumns.stream().map(c -> "'" + c + "'").toList()) + "]::text[]";
		return strings("SELECT coalesce(string_agg((to_jsonb(x)" + strip + ")::text, E'\\n' ORDER BY x.id), '')"
				+ " FROM " + table + " x").get(0);
	}

	private String onHand() throws SQLException {
		return String.join("\n", strings("""
				SELECT t.slug || '|' || i.name || '|' || m.unit || '|' || sum(m.quantity)
				FROM stock_movements m
				JOIN ingredients i ON i.id = m.ingredient_id
				JOIN tenants t ON t.id = m.tenant_id
				GROUP BY t.slug, i.name, m.unit
				ORDER BY 1
				"""));
	}

	private static void refused(Connection app, String what, String expected, String sql) {
		assertThatThrownBy(() -> {
			try (Statement statement = app.createStatement()) {
				statement.execute(sql);
			}
		}).as(what).isInstanceOf(SQLException.class).hasMessageContaining(expected);
	}

	private static String one(Connection app, String sql) throws SQLException {
		try (Statement statement = app.createStatement(); var rs = statement.executeQuery(sql)) {
			assertThat(rs.next()).as("no row came back for: %s", sql).isTrue();
			return rs.getString(1);
		}
	}

	/** Session-level, because the connection autocommits and a transaction-local setting would not last. */
	private static void adopt(Connection app, String temple) throws SQLException {
		try (PreparedStatement ps = app.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
			ps.setString(1, temple);
			ps.executeQuery().close();
		}
	}

	private void migrateTo(String version) {
		Flyway.configure()
				.dataSource(urlFor(DATABASE), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration")
				.target(version)
				.load()
				.migrate();
	}

	private void recreateDatabase() throws SQLException {
		try (Connection connection = adminConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
			statement.execute("CREATE DATABASE " + DATABASE);
		}
		// The migration role owns the schema, as in a real deployment, so V144's backfill runs subject
		// to row-level security exactly as it will there.
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {
			statement.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);
		}
	}

	private List<String> strings(String sql) throws SQLException {
		List<String> out = new ArrayList<>();
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement();
				var rs = statement.executeQuery(sql)) {
			while (rs.next()) {
				out.add(rs.getString(1));
			}
		}
		return out;
	}

	private long count(String sql) throws SQLException {
		return Long.parseLong(strings(sql).get(0));
	}

	private static Connection appConnection() throws SQLException {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(urlFor(DATABASE));
		dataSource.setUsername(APP_ROLE);
		dataSource.setPassword(APP_PASSWORD);
		return dataSource.getConnection();
	}

	private static Connection superuserConnectionTo(String database) throws SQLException {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(urlFor(database));
		dataSource.setUsername(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		return dataSource.getConnection();
	}

	private static String urlFor(String database) {
		return "jdbc:postgresql://%s:%d/%s".formatted(
				POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), database);
	}
}
