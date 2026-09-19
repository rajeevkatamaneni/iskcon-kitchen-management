package org.iskcon.kms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tenant isolation on the seven tables V144 adds for procurement (T-248, PROCUREMENT-REQUIREMENTS
 * §3, AC-3.1).
 *
 * <p>{@link RowLevelSecurityIT#everyTenantOwnedTableIsProtected} already fails the build if any
 * table with a {@code tenant_id} is not behind forced row-level security. That proves the policy is
 * attached. This proves it <em>works</em> on these tables, the way the application actually uses
 * them: every assertion runs through the application's own tenant-aware DataSource, connected as
 * the unprivileged {@code kms_app} role, with a temple adopted through {@link TenantContext}. Rows
 * are seeded through the superuser only because a fixture needs two temples at once.
 *
 * <p>Each temple gets the whole chain the tables exist to hold — an ingredient with a pack size and
 * an alias, a vendor selling it by the bag, an order, its delivery, the invoice for that delivery
 * with its line, the price the line set on the vendor and on the market rate, the cash payment, and
 * the bill and signed note uploaded against them — so every new table has a row in each temple and
 * every foreign key between them is exercised with real parents.
 */
class ProcurementRowLevelSecurityIT extends AbstractIntegrationTest {

	/** Every tenant-owned table V144 creates. A new one belongs here as well as in the migration. */
	private static final List<String> NEW_TABLES = List.of(
			"ingredient_pack_sizes",
			"ingredient_market_rate_history",
			"ingredient_aliases",
			"vendor_invoice_lines",
			"vendor_invoice_deliveries",
			"vendor_price_history",
			"attachments");

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;
	private JdbcTemplate admin;

	private UUID templeA;
	private UUID templeB;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		jdbc = new JdbcTemplate(dataSource);
		admin = new JdbcTemplate(adminDataSource());

		templeA = seedTemple("procurement-rls-a");
		templeB = seedTemple("procurement-rls-b");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		// The real purge, not a hand-written DELETE: it is what a temple leaving the platform runs,
		// and two of these tables are append-only, so a purge that could not clear them would be a
		// temple that could never be deleted.
		for (String slug : List.of("procurement-rls-a", "procurement-rls-b")) {
			admin.queryForList("SELECT id FROM tenants WHERE slug = ?", UUID.class, slug)
					.forEach(id -> admin.execute("SELECT delete_tenant_cascade('" + id + "')"));
		}
	}

	@Test
	@DisplayName("every new procurement table has row-level security enabled, forced, and the standard policy")
	void everyNewTableIsBehindTheStandardPolicy() {
		for (String table : NEW_TABLES) {
			Map<String, Object> row = admin.queryForMap("""
					SELECT c.relrowsecurity AS enabled,
						   c.relforcerowsecurity AS forced,
						   EXISTS (SELECT 1 FROM pg_policies p
								   WHERE p.schemaname = 'public' AND p.tablename = c.relname
									 AND p.policyname = 'tenant_isolation') AS has_policy
					FROM pg_class c
					WHERE c.relname = ? AND c.relnamespace = 'public'::regnamespace
					""", table);
			assertThat(row)
					.as("%s must be enabled, forced (the migration role owns it) and carry tenant_isolation", table)
					.containsEntry("enabled", true)
					.containsEntry("forced", true)
					.containsEntry("has_policy", true);
		}
	}

	@Test
	@DisplayName("each temple reads exactly its own rows in every new table, and with no temple nothing is read")
	void eachTempleSeesOnlyItsOwnRows() {
		for (String table : NEW_TABLES) {
			List<String> ownOfA = idsAsSuperuser(table, templeA);
			List<String> ownOfB = idsAsSuperuser(table, templeB);
			assertThat(ownOfA).as("the fixture seeds %s for temple A", table).isNotEmpty();
			assertThat(ownOfB).as("the fixture seeds %s for temple B", table).isNotEmpty();

			// No WHERE clause: the database alone decides what comes back.
			TenantContext.set(templeA);
			assertThat(idsAsApplication(table))
					.as("temple A reading %s with no filter", table)
					.containsExactlyElementsOf(ownOfA);

			TenantContext.set(templeB);
			assertThat(idsAsApplication(table))
					.as("temple B reading %s with no filter", table)
					.containsExactlyElementsOf(ownOfB);

			TenantContext.clear();
			assertThat(idsAsApplication(table))
					.as("no temple at all reading %s must fail closed, quietly", table)
					.isEmpty();
		}
	}

	@Test
	@DisplayName("a temple cannot write a row into any new table on another temple's behalf")
	void insertsForAnotherTempleAreRefused() {
		// Temple A's own parents, so that the only thing wrong with each row is whose it claims to be.
		// Were a parent missing, the insert would fail on a foreign key instead and prove nothing.
		Map<String, Object> a = admin.queryForMap("""
				SELECT u.id AS admin_id, i.id AS rice, p.id AS pack, v.id AS vendor, inv.id AS invoice,
					   gr.id AS receipt, l.id AS line, pay.id AS payment
				FROM tenants t
				JOIN users u ON u.tenant_id = t.id
				JOIN ingredients i ON i.tenant_id = t.id
				JOIN ingredient_pack_sizes p ON p.ingredient_id = i.id
				JOIN vendors v ON v.tenant_id = t.id
				JOIN vendor_invoices inv ON inv.tenant_id = t.id
				JOIN goods_receipts gr ON gr.tenant_id = t.id
				JOIN vendor_invoice_lines l ON l.invoice_id = inv.id
				JOIN invoice_payments pay ON pay.invoice_id = inv.id
				WHERE t.id = ?
				""", templeA);

		Map<String, String> smuggled = Map.of(
				"ingredient_pack_sizes", """
						INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
						VALUES ('%s', '%s', 'Sack', 50, 'KG')""".formatted(templeA, a.get("rice")),
				"ingredient_market_rate_history", """
						INSERT INTO ingredient_market_rate_history (tenant_id, ingredient_id, rate, effective_on,
							source, set_by)
						VALUES ('%s', '%s', 58, CURRENT_DATE, 'MANUAL', '%s')""".formatted(
						templeA, a.get("rice"), a.get("admin_id")),
				"ingredient_aliases", """
						INSERT INTO ingredient_aliases (tenant_id, ingredient_id, alias, normalised_alias)
						VALUES ('%s', '%s', 'Chawal', 'chawal')""".formatted(templeA, a.get("rice")),
				"vendor_invoice_lines", """
						INSERT INTO vendor_invoice_lines (tenant_id, invoice_id, ingredient_id, billed_qty, unit,
							line_amount)
						VALUES ('%s', '%s', '%s', 1, 'KG', 60)""".formatted(templeA, a.get("invoice"), a.get("rice")),
				"vendor_invoice_deliveries", """
						INSERT INTO vendor_invoice_deliveries (tenant_id, invoice_id, receipt_id)
						VALUES ('%s', '%s', '%s')""".formatted(templeA, a.get("invoice"), a.get("receipt")),
				"vendor_price_history", """
						INSERT INTO vendor_price_history (tenant_id, vendor_id, ingredient_id, price_per_unit,
							effective_on, source, set_by)
						VALUES ('%s', '%s', '%s', 61, CURRENT_DATE, 'MANUAL', '%s')""".formatted(
						templeA, a.get("vendor"), a.get("rice"), a.get("admin_id")),
				"attachments", """
						INSERT INTO attachments (tenant_id, kind, storage_key, content_type, size_bytes,
							payment_id, uploaded_by)
						VALUES ('%s', 'PAYMENT_PROOF', 'smuggled/%s', 'image/png', 10, '%s', '%s')""".formatted(
						templeA, UUID.randomUUID(), a.get("payment"), a.get("admin_id")));

		assertThat(smuggled.keySet()).as("every new table is tried").containsExactlyInAnyOrderElementsOf(NEW_TABLES);

		TenantContext.set(templeB);
		smuggled.forEach((table, sql) -> assertThatThrownBy(() -> jdbc.update(sql))
				.as("temple B writing a row into %s for temple A", table)
				.hasStackTraceContaining("row-level security"));

		// And nothing arrived in temple A.
		TenantContext.set(templeA);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ingredient_aliases WHERE normalised_alias = 'chawal'", Long.class)).isZero();
	}

	@Test
	@DisplayName("an update or delete aimed at another temple's rows touches nothing")
	void crossTempleUpdatesAndDeletesTouchNothing() {
		TenantContext.set(templeB);
		for (String table : NEW_TABLES) {
			assertThat(jdbc.update("UPDATE " + table + " SET tenant_id = tenant_id WHERE tenant_id = ?", templeA))
					.as("temple B updating temple A's %s", table).isZero();
			assertThat(jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", templeA))
					.as("temple B deleting temple A's %s", table).isZero();
		}
		for (String table : NEW_TABLES) {
			assertThat(idsAsSuperuser(table, templeA)).as("temple A's %s is intact", table).isNotEmpty();
		}
	}

	@Test
	@DisplayName("the two new ledgers are append-only for the application, even for their own temple")
	void theNewLedgersAreAppendOnly() {
		TenantContext.set(templeA);
		for (String table : List.of("vendor_price_history", "ingredient_market_rate_history")) {
			assertThatThrownBy(() -> jdbc.update("UPDATE " + table + " SET effective_on = effective_on - 1"))
					.as("editing a price in %s", table)
					.hasStackTraceContaining("append-only");
			assertThatThrownBy(() -> jdbc.update("DELETE FROM " + table))
					.as("deleting a price from %s", table)
					.hasStackTraceContaining("append-only");
		}
	}

	@Test
	@DisplayName("purging one temple clears it from every new table, append-only ones included, and spares the other")
	void purgingATempleClearsItsProcurementRows() {
		admin.execute("SELECT delete_tenant_cascade('" + templeA + "')");

		for (String table : NEW_TABLES) {
			assertThat(idsAsSuperuser(table, templeA)).as("%s after temple A was purged", table).isEmpty();
			assertThat(idsAsSuperuser(table, templeB)).as("temple B's %s must survive A's purge", table).isNotEmpty();
		}
	}

	// ---------------------------------------------------------------------

	private List<String> idsAsApplication(String table) {
		return jdbc.queryForList("SELECT id::text FROM " + table + " ORDER BY id", String.class);
	}

	private List<String> idsAsSuperuser(String table, UUID temple) {
		return admin.queryForList(
				"SELECT id::text FROM " + table + " WHERE tenant_id = ? ORDER BY id", String.class, temple);
	}

	/**
	 * One temple with the full order -> delivery -> invoice -> payment chain, in one statement so
	 * the whole thing is one transaction on one connection. Returns the temple's id.
	 */
	private UUID seedTemple(String slug) {
		admin.execute("""
				DO $$
				DECLARE
				  v_t uuid; v_admin uuid; v_rice uuid; v_pack uuid; v_vendor uuid; v_po uuid; v_pol uuid;
				  v_gr uuid; v_grl uuid; v_inv uuid; v_line uuid; v_pay uuid;
				BEGIN
				  INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				    VALUES ('%1$s', '%1$s', 12.97, 77.59, 'Asia/Kolkata') RETURNING id INTO v_t;
				  INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role)
				    VALUES (v_t, '%1$s-admin', 'Admin', '%1$s-admin@example.com', '+919876500111', 'TEMPLE_ADMIN')
				    RETURNING id INTO v_admin;
				  INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				    VALUES (v_t, 'Rice', 'Grains', 'KG') RETURNING id INTO v_rice;
				  INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
				    VALUES (v_t, v_rice, 'Bag', 25, 'KG') RETURNING id INTO v_pack;
				  INSERT INTO ingredient_aliases (tenant_id, ingredient_id, alias, normalised_alias)
				    VALUES (v_t, v_rice, 'Raw Rice', 'raw rice');
				  INSERT INTO vendors (tenant_id, name, phone)
				    VALUES (v_t, 'Kalasipalya', '+919876500200') RETURNING id INTO v_vendor;
				  INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, pack_size_id, price_per_pack)
				    VALUES (v_t, v_vendor, v_rice, 60, v_pack, 1500);
				  INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by, sent_at)
				    VALUES (v_t, 'PO-1', v_vendor, 'RECEIVED', v_admin, now()) RETURNING id INTO v_po;
				  INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)
				    VALUES (v_t, v_po, v_rice, 100, 'KG') RETURNING id INTO v_pol;
				  INSERT INTO goods_receipts (tenant_id, po_id, idempotency_key, received_by)
				    VALUES (v_t, v_po, 'receipt-1', v_admin) RETURNING id INTO v_gr;
				  INSERT INTO goods_receipt_lines (tenant_id, receipt_id, po_line_id, ingredient_id, received_qty, unit)
				    VALUES (v_t, v_gr, v_pol, v_rice, 100, 'KG') RETURNING id INTO v_grl;
				  INSERT INTO vendor_invoices (tenant_id, vendor_id, po_id, invoice_number, invoice_date, amount,
				                               created_by, sub_total, gst_amount, other_charges, discount, grand_total)
				    VALUES (v_t, v_vendor, v_po, 'INV-1', CURRENT_DATE, 6000, v_admin, 6000, 0, 0, 0, 6000)
				    RETURNING id INTO v_inv;
				  INSERT INTO vendor_invoice_deliveries (tenant_id, invoice_id, receipt_id) VALUES (v_t, v_inv, v_gr);
				  INSERT INTO vendor_invoice_lines (tenant_id, invoice_id, goods_receipt_line_id, ingredient_id,
				                                    billed_qty, unit, pack_size_id, pack_count, line_amount)
				    VALUES (v_t, v_inv, v_grl, v_rice, 100, 'KG', v_pack, 4, 6000) RETURNING id INTO v_line;
				  INSERT INTO vendor_price_history (tenant_id, vendor_id, ingredient_id, price_per_unit, price_per_pack,
				                                    pack_description, effective_on, source, vendor_invoice_line_id, set_by)
				    VALUES (v_t, v_vendor, v_rice, 60, 1500, 'Bag = 25 Kg', CURRENT_DATE, 'INVOICE', v_line, v_admin);
				  INSERT INTO ingredient_market_rate_history (tenant_id, ingredient_id, rate, effective_on, source,
				                                              vendor_invoice_line_id, set_by)
				    VALUES (v_t, v_rice, 60, CURRENT_DATE, 'INVOICE', v_line, v_admin);
				  UPDATE ingredients SET market_rate = 60, market_rate_on = CURRENT_DATE, market_rate_source = 'INVOICE'
				    WHERE id = v_rice;
				  INSERT INTO invoice_payments (tenant_id, invoice_id, paid_on, amount, method, recorded_by, received_by_name)
				    VALUES (v_t, v_inv, CURRENT_DATE, 6000, 'CASH', v_admin, 'Govinda Das') RETURNING id INTO v_pay;
				  INSERT INTO attachments (tenant_id, kind, storage_key, content_type, size_bytes, original_name,
				                           invoice_id, uploaded_by)
				    VALUES (v_t, 'INVOICE_BILL', '%1$s/bill.jpg', 'image/jpeg', 2048, 'bill.jpg', v_inv, v_admin);
				  INSERT INTO attachments (tenant_id, kind, storage_key, content_type, size_bytes, original_name,
				                           payment_id, uploaded_by)
				    VALUES (v_t, 'CASH_SIGNED_NOTE', '%1$s/note.jpg', 'image/jpeg', 1024, 'note.jpg', v_pay, v_admin);
				END $$
				""".formatted(slug));
		return admin.queryForObject("SELECT id FROM tenants WHERE slug = ?", UUID.class, slug);
	}
}
