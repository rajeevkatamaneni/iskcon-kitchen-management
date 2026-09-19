package org.iskcon.kms.ingredient.merge;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The temples the merge tests work on, seeded as the superuser so that one statement can lay out a
 * whole temple's history. Nothing here is asserted; the assertions go through the application.
 */
final class MergeFixtures {

	static final String ADMIN_UID = "uid-merge-admin";
	static final String MANAGER_UID = "uid-merge-manager";

	private MergeFixtures() {
	}

	static UUID tenant(JdbcTemplate admin, String slug) {
		return admin.queryForObject("SELECT id FROM tenants WHERE slug = ?", UUID.class, slug);
	}

	static UUID ingredient(JdbcTemplate admin, UUID tenant, String name) {
		return admin.queryForObject(
				"SELECT id FROM ingredients WHERE tenant_id = ? AND name = ?", UUID.class, tenant, name);
	}

	static UUID vendor(JdbcTemplate admin, UUID tenant, String name) {
		return admin.queryForObject(
				"SELECT id FROM vendors WHERE tenant_id = ? AND name = ?", UUID.class, tenant, name);
	}

	/** Removes a test temple the way a temple leaving the platform is removed, ledgers included. */
	static void purge(JdbcTemplate admin, String slug) {
		admin.queryForList("SELECT id FROM tenants WHERE slug = ?", UUID.class, slug)
				.forEach(id -> admin.execute("SELECT delete_tenant_cascade('" + id + "')"));
	}

	/** A temple with its Temple Admin and a Kitchen Manager, and nothing else. */
	static UUID temple(JdbcTemplate admin, String slug, String adminUid, String managerUid) {
		UUID tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.97, 77.59, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, slug);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Temple Admin', ?, '+919876500301', 'TEMPLE_ADMIN', 'ACTIVE')
				""", tenant, adminUid, adminUid + "@example.com");
		if (managerUid != null) {
			admin.update("""
					INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
					VALUES (?, ?, 'Kitchen Manager', ?, '+919876500302', 'KITCHEN_MANAGER', 'ACTIVE')
					""", tenant, managerUid, managerUid + "@example.com");
		}
		return tenant;
	}

	/**
	 * The curd group from R-DUP-3's acceptance criteria, with something in every table that points at
	 * an ingredient. "Curd, sour" is counted in gm and the rest in Kg, so the merge's conversion is
	 * exercised by the AC itself.
	 *
	 * <ul>
	 *   <li>Stock: Curd +10 Kg; Curd, fresh +2 Kg −0.5 Kg; Curd, sour +1,500 gm (a delivery);
	 *       Curd, whisked +1 Kg. On hand across the group: 14,000 gm.</li>
	 *   <li>Recipes: Kadhi uses Curd, sour (500 gm) and Curd (1 Kg); Raita uses Curd, whisked with its
	 *       own note "chilled"; Mosaru anna uses Curd, fresh.</li>
	 *   <li>Supplies: Nandini sells Curd at ₹60/Kg (preferred) and Curd, sour at ₹0.065/gm, a
	 *       different price, in a Tub = 5,000 gm; Akshaya sells Curd, fresh and Curd, whisked at ₹58/Kg.</li>
	 *   <li>Packs: Curd has Tub = 5 Kg; Curd, sour has Tub = 5,000 gm (the same size) and Pouch = 500 gm.</li>
	 *   <li>A received order for Curd, sour in the tub, its delivery, and the bill for it.</li>
	 *   <li>Price history and market-rate history on Curd and Curd, sour; inventory items on both;
	 *       hand-added shopping-list lines on Curd, sour (3,000 gm) and Curd, whisked (1 Kg); a store
	 *       request for Curd, fresh; alias rows Dahi on Curd and Huli mosaru on Curd, sour.</li>
	 * </ul>
	 */
	static void curdGroup(JdbcTemplate admin, UUID tenant) {
		admin.execute("""
				DO $$
				DECLARE
				  v_t uuid := '%1$s';
				  v_admin uuid; v_curd uuid; v_fresh uuid; v_sour uuid; v_whisk uuid; v_rice uuid;
				  v_tub uuid; v_stub uuid; v_pouch uuid; v_nandini uuid; v_akshaya uuid;
				  v_cat uuid; v_kadhi uuid; v_raita uuid; v_anna uuid;
				  v_po uuid; v_pol uuid; v_move uuid; v_gr uuid; v_grl uuid; v_inv uuid; v_line uuid;
				  v_kitchen uuid; v_req uuid;
				BEGIN
				  SELECT id INTO v_admin FROM users WHERE tenant_id = v_t AND role = 'TEMPLE_ADMIN';

				  INSERT INTO ingredients (tenant_id, name, category, canonical_unit, aliases, created_at)
				    VALUES (v_t, 'Curd', 'Dairy', 'KG', ARRAY['Dahi'], TIMESTAMPTZ '2025-01-01') RETURNING id INTO v_curd;
				  INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				    VALUES (v_t, 'Curd, fresh', 'Dairy', 'KG') RETURNING id INTO v_fresh;
				  INSERT INTO ingredients (tenant_id, name, category, canonical_unit, aliases)
				    VALUES (v_t, 'Curd, sour', 'Dairy', 'GM', ARRAY['Huli mosaru']) RETURNING id INTO v_sour;
				  INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				    VALUES (v_t, 'Curd, whisked', 'Dairy', 'KG') RETURNING id INTO v_whisk;
				  INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				    VALUES (v_t, 'Rice', 'Grains', 'KG') RETURNING id INTO v_rice;

				  INSERT INTO ingredient_aliases (tenant_id, ingredient_id, alias, normalised_alias)
				    VALUES (v_t, v_curd, 'Dahi', 'dahi'), (v_t, v_sour, 'Huli mosaru', 'huli mosaru');

				  INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
				    VALUES (v_t, v_curd, 'Tub', 5, 'KG') RETURNING id INTO v_tub;
				  INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
				    VALUES (v_t, v_sour, 'Tub', 5000, 'GM') RETURNING id INTO v_stub;
				  INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
				    VALUES (v_t, v_sour, 'Pouch', 500, 'GM') RETURNING id INTO v_pouch;

				  INSERT INTO vendors (tenant_id, name, phone) VALUES (v_t, 'Nandini', '+919876500401') RETURNING id INTO v_nandini;
				  INSERT INTO vendors (tenant_id, name, phone) VALUES (v_t, 'Akshaya', '+919876500402') RETURNING id INTO v_akshaya;
				  INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred)
				    VALUES (v_t, v_nandini, v_curd, 60, true);
				  INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, pack_size_id, price_per_pack, lead_time_days)
				    VALUES (v_t, v_nandini, v_sour, 0.065, v_stub, 325, 2);
				  INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred, lead_time_days)
				    VALUES (v_t, v_akshaya, v_fresh, 58, true, 1), (v_t, v_akshaya, v_whisk, 58, false, NULL);

				  INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				    VALUES (v_t, v_curd, gen_random_uuid(), 10, 'KG', 'ADJUSTMENT', v_admin),
				           (v_t, v_fresh, gen_random_uuid(), 2, 'KG', 'DONATION_IN_KIND', v_admin),
				           (v_t, v_whisk, gen_random_uuid(), 1, 'KG', 'DONATION_IN_KIND', v_admin);
				  INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				    SELECT v_t, v_fresh, batch_id, -0.5, 'KG', 'CONSUMPTION', v_admin
				    FROM stock_movements WHERE ingredient_id = v_fresh;

				  INSERT INTO recipe_categories (tenant_id, name) VALUES (v_t, 'Curries') RETURNING id INTO v_cat;
				  INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				    VALUES (v_t, 'Kadhi', v_cat, 10, 'L') RETURNING id INTO v_kadhi;
				  INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				    VALUES (v_t, 'Raita', v_cat, 5, 'KG') RETURNING id INTO v_raita;
				  INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				    VALUES (v_t, 'Mosaru anna', v_cat, 10, 'KG') RETURNING id INTO v_anna;
				  INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				    VALUES (v_t, v_kadhi, v_sour, 500, 'GM', 1), (v_t, v_kadhi, v_curd, 1, 'KG', 2),
				           (v_t, v_anna, v_fresh, 2, 'KG', 1);
				  INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order, preparation_note)
				    VALUES (v_t, v_raita, v_whisk, 1, 'KG', 1, 'chilled');

				  INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by, sent_at)
				    VALUES (v_t, 'PO-CURD-1', v_nandini, 'RECEIVED', v_admin, now()) RETURNING id INTO v_po;
				  INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, expected_price, pack_size_id, pack_count)
				    VALUES (v_t, v_po, v_sour, 5000, 'GM', 0.065, v_stub, 1) RETURNING id INTO v_pol;
				  INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
				                               reference_type, reference_id, actor_user_id)
				    VALUES (v_t, v_sour, gen_random_uuid(), 1500, 'GM', 'PO_RECEIPT', 'PURCHASE_ORDER', v_po, v_admin)
				    RETURNING id INTO v_move;
				  INSERT INTO goods_receipts (tenant_id, po_id, idempotency_key, received_by)
				    VALUES (v_t, v_po, 'curd-delivery-1', v_admin) RETURNING id INTO v_gr;
				  INSERT INTO goods_receipt_lines (tenant_id, receipt_id, po_line_id, ingredient_id, received_qty, unit,
				                                   stock_movement_id)
				    VALUES (v_t, v_gr, v_pol, v_sour, 1500, 'GM', v_move) RETURNING id INTO v_grl;
				  INSERT INTO vendor_invoices (tenant_id, vendor_id, po_id, invoice_number, invoice_date, amount, created_by)
				    VALUES (v_t, v_nandini, v_po, 'NAN-1', DATE '2026-09-14', 97.50, v_admin) RETURNING id INTO v_inv;
				  INSERT INTO vendor_invoice_deliveries (tenant_id, invoice_id, receipt_id) VALUES (v_t, v_inv, v_gr);
				  INSERT INTO vendor_invoice_lines (tenant_id, invoice_id, goods_receipt_line_id, ingredient_id,
				                                    billed_qty, unit, line_amount)
				    VALUES (v_t, v_inv, v_grl, v_sour, 1500, 'GM', 97.50) RETURNING id INTO v_line;

				  INSERT INTO vendor_price_history (tenant_id, vendor_id, ingredient_id, price_per_unit, effective_on, source, set_by)
				    VALUES (v_t, v_nandini, v_curd, 60, DATE '2026-09-01', 'MANUAL', v_admin),
				           (v_t, v_nandini, v_sour, 0.065, DATE '2026-09-01', 'MANUAL', v_admin);
				  INSERT INTO vendor_price_history (tenant_id, vendor_id, ingredient_id, price_per_unit, effective_on,
				                                    source, vendor_invoice_line_id, set_by)
				    VALUES (v_t, v_nandini, v_sour, 0.065, DATE '2026-09-14', 'INVOICE', v_line, v_admin);

				  INSERT INTO ingredient_market_rate_history (tenant_id, ingredient_id, rate, effective_on, source, set_by)
				    VALUES (v_t, v_curd, 62, DATE '2026-09-10', 'MANUAL', v_admin),
				           (v_t, v_sour, 0.07, DATE '2026-09-15', 'MANUAL', v_admin);
				  UPDATE ingredients SET market_rate = 62, market_rate_on = DATE '2026-09-10', market_rate_source = 'MANUAL'
				    WHERE id = v_curd;
				  UPDATE ingredients SET market_rate = 0.07, market_rate_on = DATE '2026-09-15', market_rate_source = 'MANUAL'
				    WHERE id = v_sour;

				  INSERT INTO inventory_items (tenant_id, ingredient_id, storage_location, reorder_threshold)
				    VALUES (v_t, v_curd, 'Cold room', 5);
				  INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold, notes)
				    VALUES (v_t, v_sour, 2000, 'Keep covered');

				  INSERT INTO shopping_list_lines (tenant_id, ingredient_id, suggested_qty, unit, included, hand_added)
				    VALUES (v_t, v_sour, 3000, 'GM', true, true), (v_t, v_whisk, 1, 'KG', true, true);

				  INSERT INTO kitchens (tenant_id, name, is_main, created_by)
				    VALUES (v_t, 'Main kitchen', true, v_admin) RETURNING id INTO v_kitchen;
				  INSERT INTO ingredient_requests (tenant_id, reference, kitchen_id, needed_on, requested_by)
				    VALUES (v_t, 'REQ-1', v_kitchen, DATE '2026-09-20', v_admin) RETURNING id INTO v_req;
				  INSERT INTO ingredient_request_lines (tenant_id, request_id, line_no, ingredient_id, quantity, unit)
				    VALUES (v_t, v_req, 1, v_fresh, 2, 'KG');
				END $$
				""".formatted(tenant));
	}
}
