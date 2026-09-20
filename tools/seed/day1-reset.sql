-- =====================================================================
-- Day-1 reset — puts one temple back to its first morning.
--
-- Approved by Rajeev in the terminal on 2026-09-19 after being told exactly what it clears and
-- keeps. The plan it implements is docs/work/CATALOGUE-AND-DAY1-PLAN.md step 5.
--
-- What survives: the people and their roles, the temple's settings including WhatsApp and email,
-- the wish list, the kitchens, the meal kinds, the calendar and the festival occasions, the staff
-- and their schedules, and the vendors themselves.
--
-- What goes: everything the temple has *done* — ingredients and supplies, its recipes, meal plans
-- and meals, the shopping list, purchase orders, deliveries, invoices, payments, receipts,
-- inventory and every stock movement, ingredient requests, and every price and supply link hanging
-- off the vendors that survive.
--
--   docker exec -i kms-postgres psql -U kms_migration -d kms_seed \
--     -v tenant=f935450b-1b7c-4b2c-a7e3-73e40c7e31e3 -f - < tools/seed/day1-reset.sql
--
-- Run tools/seed/day1-counts.sql before and after to see what moved.
--
-- ---------------------------------------------------------------------
-- Why this is SQL at all, when everything else in tools/seed/ goes through the API
--
-- Because the application has no path for it, deliberately. A temple cannot un-buy a delivery or
-- un-record a stock movement: history is append-only and corrections are compensating entries, not
-- deletions. The only code in the tree that crosses that line is delete_tenant_cascade (V44), and
-- it deletes the temple as well, which is the opposite of what is wanted here. So this is the one
-- clearly-named script that does what no endpoint does, and every guard it lifts is named below.
--
-- ---------------------------------------------------------------------
-- The four safety properties, and how each is actually obtained
--
-- 1. ONE TEMPLE. Row-level security does the confining, not the WHERE clause. The script runs as
--    kms_migration, which owns the tables but is still subject to them: they carry FORCE ROW LEVEL
--    SECURITY, so `app.tenant_id` decides what the role can even see. Measured — with the setting
--    absent, a count over all 78 tenant-owned tables returned exactly one row (employment_bans,
--    which is the cross-tenant ban list and has no policy). The explicit `WHERE tenant_id = $1` is
--    a second belt, the same pairing V44 uses and for the same stated reason.
--
-- 2. NOT AS A SUPERUSER. A superuser bypasses RLS completely, so the one real guard would be off
--    and a mistake in the WHERE would reach every temple in the database. The script refuses to
--    run as one rather than trusting itself.
--
-- 3. ALL OR NOTHING. One transaction. A failure anywhere leaves the temple exactly as it was, and
--    that includes the lifted append-only guard, which is transaction-local.
--
-- 4. NOTHING MISSED IN SILENCE. Every tenant-owned table must be named in the clear list or the
--    keep list below. A table in neither aborts the script before it deletes anything. This is the
--    part that matters in six months: the failure mode of a hardcoded list is not an error, it is
--    a table that quietly keeps its rows, and nobody finds out until a seeded temple behaves oddly.
-- =====================================================================

\set ON_ERROR_STOP on

BEGIN;

-- Transaction-local, both of them: no other connection sees either, and a rollback undoes both.
--
-- app.tenant_id      is what the RLS policies read. It is property 1 above.
-- app.purging_tenant is what reject_append_only_change() looks for. Seventeen tables carry the
--                    append-only trigger — stock_movements, goods_receipts, po_events,
--                    invoice_payments, vendor_price_history and the rest — and a DELETE on any of
--                    them is refused without it. This is the same flag delete_tenant_cascade sets
--                    and the same escape the trigger was written to allow. Reading the trigger
--                    body: it permits the change when the caller is not kms_app OR the flag is on,
--                    so as kms_migration it is already permitted; the flag is set anyway so the
--                    script behaves identically whichever role runs it.
SET LOCAL app.tenant_id = :'tenant';
SET LOCAL app.purging_tenant = 'on';

DO $reset$
DECLARE
    v_tenant       uuid := current_setting('app.tenant_id')::uuid;
    v_table        text;
    v_rows         bigint;
    v_progress     boolean;
    v_passes       int := 0;
    v_unclassified text[];
    v_missing      text[];
    v_total        bigint := 0;

    -- ---------------------------------------------------------------
    -- CLEARED. Everything the temple has done.
    -- ---------------------------------------------------------------
    c_clear text[] := ARRAY[
        -- the catalogue, and everything hanging off it
        'ingredients', 'ingredient_aliases', 'ingredient_pack_sizes',
        'ingredient_market_rate_history',
        -- the temple's own recipes (the shared library in master_recipes is not tenant-owned
        -- and is replaced separately, by the catalogue load)
        'recipes', 'recipe_ingredients', 'recipe_translations',
        -- planning
        'meals', 'meal_dishes', 'meal_kitchens', 'meal_plan_days', 'meal_series',
        'meal_card_sequence',
        -- buying
        'shopping_list_lines',
        'purchase_orders', 'purchase_order_lines', 'po_events', 'po_label_translations',
        'po_sequence',
        -- receiving
        'goods_receipts', 'goods_receipt_lines', 'goods_returns',
        -- billing
        'vendor_invoices', 'vendor_invoice_lines', 'vendor_invoice_deliveries', 'invoice_payments',
        -- stock
        'inventory_items', 'stock_movements', 'low_stock_digest_runs',
        -- sister kitchens asking for things
        'ingredient_requests', 'ingredient_request_lines', 'ingredient_request_dishes',
        'ingredient_request_events', 'ingredient_request_sequence',
        -- the vendors stay; every price and supply link on them goes (Rajeev's words)
        'vendor_supplies', 'vendor_price_history',
        -- uploaded receipts and generated job cards, which belong to the orders above
        'attachments', 'documents', 'document_label_translations',
        -- volunteer shifts hang off meals by foreign key and cannot outlive them
        'shifts', 'shift_signups', 'shift_waitlist', 'shift_reminders',
        'shift_broadcasts', 'shift_broadcast_recipients',

        -- ---------------------------------------------------------------
        -- Moved here from the keep list by the conductor, 2026-09-19, after reading this
        -- script. All four were things this script had kept as a judgement; all four are
        -- now deliberate.
        -- ---------------------------------------------------------------

        -- The audit trail. Kept at first because deleting one is a bigger act than clearing
        -- operational data — but after the reset it describes orders and invoices that no
        -- longer exist, and the audit screen is shown in demos. The conductor's reasoning:
        -- this is a test temple being returned to day one, not a real temple's history being
        -- destroyed. On a real temple this line does not belong in this list.
        'audit_events',

        -- Donations and their receipt counter. The simulation raises its own in phase 14, and
        -- clearing the counter means the first receipt is number 1 rather than continuing a
        -- sequence from test data nobody remembers.
        'donations', 'donation_receipt_sequence',

        -- The notification log: messages about orders and meals that are now gone.
        -- notification_attempts must go first — its FK to notifications is RESTRICT — which
        -- the retry loop below sorts out without being told.
        'notification_attempts', 'notifications'
    ];

    -- ---------------------------------------------------------------
    -- KEPT. See the note at the end of this file about the five of these
    -- that are a judgement rather than an instruction.
    -- ---------------------------------------------------------------
    c_keep text[] := ARRAY[
        -- people and the temple itself
        'users', 'tenant_settings', 'communication_preferences',
        -- Real outreach to devotees, so it survives the reset. One consequence, accepted by the
        -- conductor 2026-09-19: communication_recipients.notification_id is ON DELETE SET NULL,
        -- so clearing the notification log above leaves these rows standing but drops their link
        -- to the delivery attempt. The record of who was written to is what matters here.
        'communications', 'communication_recipients',
        -- staff and their schedules
        'staff_profiles', 'staff_schedule_template', 'staff_schedule_exceptions', 'staff_leave',
        'staff_advances', 'staff_payments', 'staff_payment_deductions', 'staff_conduct_notes',
        'employment_bans',
        -- the shape of the operation
        'kitchens', 'meal_kinds', 'recipe_categories', 'occasions',
        -- the calendar, which is expensive to recompute and reaches back no further than it does
        'calendar_days', 'calendar_overrides', 'calendar_precompute_state',
        -- the vendors themselves, without their prices
        'vendors', 'vendor_status_changes',
        -- The wish list. Kept, but see "the wish list needs putting back" below — keeping the
        -- items while clearing the donations leaves a fulfilled item with no money behind it.
        'wishlist_items',
        -- Equipment: phase 15 raises service requests against these four items, so they are what
        -- it has to raise them against. Kept by the conductor, 2026-09-19.
        'equipment_items', 'equipment_services', 'equipment_state_changes',
        -- settings-shaped odds and ends
        'translation_glossary', 'whatsapp_template_status_copy', 'platform_notice_dismissals',
        -- The Razorpay webhook inbox. Not the temple's data: an event arrives before anyone knows
        -- which temple it belongs to, so its tenant_id is nullable and V37 gave it no RLS policy
        -- on purpose (V65's comment calls this "the payment_events answer"). A tenant-scoped
        -- delete is the wrong shape for it, and it feeds online donations, which are kept.
        'payment_events'
    ];
BEGIN
    -- --- property 2: never as a superuser --------------------------------
    IF (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION
            'Refusing to run as the superuser %. A superuser bypasses row-level security, so the '
            'one guard that confines this to a single temple would be off. Run it as kms_migration.',
            current_user;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM tenants WHERE id = v_tenant) THEN
        RAISE EXCEPTION 'No temple with id % in this database.', v_tenant;
    END IF;

    -- --- property 4: every tenant-owned table is classified ---------------
    SELECT array_agg(c.relname ORDER BY c.relname) INTO v_unclassified
    FROM pg_class c
    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
    WHERE c.relkind = 'r' AND c.relnamespace = 'public'::regnamespace
      AND NOT (c.relname = ANY (c_clear))
      AND NOT (c.relname = ANY (c_keep));

    IF v_unclassified IS NOT NULL THEN
        RAISE EXCEPTION
            'These tenant-owned tables are in neither the clear list nor the keep list: %. '
            'Nothing has been deleted. Decide what each one is and add it to a list — a table '
            'left out would quietly keep its rows.',
            array_to_string(v_unclassified, ', ');
    END IF;

    -- A table in BOTH lists. Only the clear list drives the deletes, so an overlap does not
    -- change what happens — which is exactly why it has to be caught here. It happened while this
    -- script was being written: four tables were moved from keep to clear and left in both, and
    -- the run looked perfectly correct. The lists are the document of what the reset does, and a
    -- table that reads as kept while being cleared makes that document a lie.
    SELECT array_agg(name ORDER BY name) INTO v_unclassified
    FROM unnest(c_clear) AS name
    WHERE name = ANY (c_keep);

    IF v_unclassified IS NOT NULL THEN
        RAISE EXCEPTION
            'These tables are in the clear list AND the keep list: %. Nothing has been deleted. '
            'They would be cleared, whatever the keep list suggests — remove them from one side.',
            array_to_string(v_unclassified, ', ');
    END IF;

    -- The mirror of the first check: a name in a list that no longer exists means the schema moved
    -- under this script, and the list is describing a table that is gone.
    SELECT array_agg(name ORDER BY name) INTO v_missing
    FROM unnest(c_clear || c_keep) AS name
    WHERE NOT EXISTS (
        SELECT 1 FROM pg_class c
        WHERE c.relname = name AND c.relkind = 'r' AND c.relnamespace = 'public'::regnamespace);

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'These tables are named in a list but do not exist: %. Nothing has been deleted.',
            array_to_string(v_missing, ', ');
    END IF;

    RAISE NOTICE 'Day-1 reset of temple % — % tables to clear, % to keep.',
        v_tenant, array_length(c_clear, 1), array_length(c_keep, 1);

    -- --- the deletes ------------------------------------------------------
    -- Order is not hardcoded. Foreign keys between these tables run in several directions and a
    -- fixed order would need re-deriving every time one is added, so instead: try them all, let a
    -- row whose parent has not gone yet fail, and go round again until a whole pass deletes
    -- nothing. This is how delete_tenant_cascade does it, for the same reason.
    LOOP
        v_progress := false;
        v_passes := v_passes + 1;

        FOREACH v_table IN ARRAY c_clear LOOP
            BEGIN
                EXECUTE format('DELETE FROM public.%I WHERE tenant_id = $1', v_table)
                    USING v_tenant;
                GET DIAGNOSTICS v_rows = ROW_COUNT;
                IF v_rows > 0 THEN
                    v_progress := true;
                    v_total := v_total + v_rows;
                    -- RAISE has no width specifiers, so the padding is done with rpad.
                    RAISE NOTICE '  pass %  % %', v_passes, rpad(v_table, 32), v_rows;
                END IF;
            EXCEPTION WHEN foreign_key_violation THEN
                NULL;  -- a parent is still standing; a later pass will get it
            END;
        END LOOP;

        EXIT WHEN NOT v_progress;

        IF v_passes > 50 THEN
            RAISE EXCEPTION
                'Did not converge after % passes — a dependency cycle, or a row referenced by '
                'something on the keep list.', v_passes;
        END IF;
    END LOOP;

    RAISE NOTICE 'Cleared % row(s) in % pass(es).', v_total, v_passes;

    -- --- the test vendors --------------------------------------------------
    -- The only place this script deletes *some* rows of a table rather than all of them, so it is
    -- kept apart from the loop above and names every vendor it removes.
    --
    -- Thirteen of the temple's vendors are artefacts of earlier testing — VERIFY-A, VERIFY2-B Veg,
    -- UAT-test Vendor Phase B and the like. They are not the temple's vendors and they would
    -- appear in the list at the demo. Removed by the conductor's decision, 2026-09-19: "they are
    -- test artefacts, not the temple's vendors, and his brief is a clean system."
    --
    -- The eight real ones are kept, without their prices, which is what Rajeev asked for:
    -- "leave the vendors we already have but every price and supply link on them goes."
    --
    -- Matched on a plain prefix rather than a hardcoded list of ids, because the same names exist
    -- on staging with different ids. A deliberately simple pattern: VERIFY, VERIFY2, VERIFY-A,
    -- UAT-test and E2E all begin with one of three strings. A real vendor whose name happened to
    -- start that way would be caught too, which is why every one is PRINTED BY NAME rather than
    -- counted — read the list before believing the number.
    FOR v_table IN
        SELECT name FROM vendors
        WHERE tenant_id = v_tenant
          AND name ~* '^(VERIFY|UAT-test|E2E)'
        ORDER BY name
    LOOP
        RAISE NOTICE '  test vendor  %', v_table;
    END LOOP;

    -- Their status history first: it holds a foreign key to the vendor and is append-only, so it
    -- cannot be left behind and cannot be removed without the purge flag set above.
    DELETE FROM vendor_status_changes
    WHERE tenant_id = v_tenant
      AND vendor_id IN (SELECT id FROM vendors
                        WHERE tenant_id = v_tenant AND name ~* '^(VERIFY|UAT-test|E2E)');
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_total := v_total + v_rows;

    DELETE FROM vendors
    WHERE tenant_id = v_tenant AND name ~* '^(VERIFY|UAT-test|E2E)';
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_total := v_total + v_rows;
    RAISE NOTICE 'Removed % test vendor(s).', v_rows;

    -- --- the wish list needs putting back, not just keeping ------------------
    --
    -- A wish-list item survives the reset; the gifts that paid for it do not. And the two halves
    -- of "is it paid for" live in different places: **`status` and `fulfilled_at` are stored
    -- columns, while the paid figure is computed from the donations**. Clear the donations and a
    -- fulfilled item comes back reading "Rs 0 of Rs 78,500 — FULFILLED", which is the first thing
    -- a reader queries. It happened on staging on 2026-09-20 and it will happen to every temple
    -- that is ever reset.
    --
    -- There is **no way to repair it through the application**: `UpdateWishlistItemRequest`
    -- carries the title, price, category, quantity and note, and no status, so an operator's only
    -- option through the UI is to archive the item and lose it. So the reset does it here, where
    -- the column is reachable — an item whose funding has just been deleted is an item nobody has
    -- paid for yet, and ACTIVE is what that means.
    UPDATE wishlist_items
    SET status = 'ACTIVE', fulfilled_at = NULL, updated_at = now()
    WHERE tenant_id = v_tenant AND status = 'FULFILLED';
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows > 0 THEN
        v_total := v_total + v_rows;
        RAISE NOTICE 'Wish-list items put back to ACTIVE (their gifts were cleared): %', v_rows;
    END IF;

    -- --- what a reset temple must look like afterwards ---------------------
    -- Asserted rather than assumed: if one of these is not empty the delete loop skipped it, and a
    -- skip that nobody notices is the whole failure mode this script is written against.
    FOREACH v_table IN ARRAY c_clear LOOP
        EXECUTE format('SELECT count(*) FROM public.%I WHERE tenant_id = $1', v_table)
            INTO v_rows USING v_tenant;
        IF v_rows > 0 THEN
            RAISE EXCEPTION
                'After the reset, % still holds % row(s) for this temple. Rolled back.',
                v_table, v_rows;
        END IF;
    END LOOP;

    -- And the things that had to survive really did. A reset that quietly took the staff or the
    -- calendar with it would be discovered three phases later.
    IF NOT EXISTS (SELECT 1 FROM users WHERE tenant_id = v_tenant) THEN
        RAISE EXCEPTION 'The reset removed every user. Rolled back.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM kitchens WHERE tenant_id = v_tenant) THEN
        RAISE EXCEPTION 'The reset removed every kitchen. Rolled back.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM meal_kinds WHERE tenant_id = v_tenant) THEN
        RAISE EXCEPTION 'The reset removed every meal kind. Rolled back.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM vendors WHERE tenant_id = v_tenant) THEN
        RAISE EXCEPTION 'The reset removed every vendor. Rolled back.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM calendar_days WHERE tenant_id = v_tenant) THEN
        RAISE EXCEPTION 'The reset removed the calendar. Rolled back.';
    END IF;

    PERFORM 1 FROM wishlist_items
    WHERE tenant_id = v_tenant AND status = 'FULFILLED' LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION
            'A wish-list item is still marked fulfilled after its gifts were cleared, so it would '
            'read "Rs 0 of Rs n — FULFILLED". Rolled back.';
    END IF;

    RAISE NOTICE 'Kept: % user(s), % kitchen(s), % meal kind(s), % vendor(s), % calendar day(s), % wish-list item(s), % staff.',
        (SELECT count(*) FROM users WHERE tenant_id = v_tenant),
        (SELECT count(*) FROM kitchens WHERE tenant_id = v_tenant),
        (SELECT count(*) FROM meal_kinds WHERE tenant_id = v_tenant),
        (SELECT count(*) FROM vendors WHERE tenant_id = v_tenant),
        (SELECT count(*) FROM calendar_days WHERE tenant_id = v_tenant),
        (SELECT count(*) FROM wishlist_items WHERE tenant_id = v_tenant),
        (SELECT count(*) FROM staff_profiles WHERE tenant_id = v_tenant);
END;
$reset$;

COMMIT;

-- =====================================================================
-- Five things kept that are a judgement, not an instruction
--
-- None of these appeared in what Rajeev was told the reset would clear, so they stay. Each is
-- one line to move into c_clear if he says so.
--
-- 1. audit_events (1,243 rows locally). The record of who did what. After the reset most of it
--    refers to ingredients, orders and meals that no longer exist, which is odd for a temple on
--    its first morning — but deleting an audit trail is a bigger act than clearing operational
--    data, and nobody asked for it.
-- 2. donations (29) and their receipt counter. Phase 14 adds more. Keeping these means the
--    simulation's first receipt number continues an old sequence rather than starting at 1.
-- 3. equipment_items (4), equipment_services (3), equipment_state_changes (7). Phase 15 adds
--    service requests and payments; these four items are what it would add them to.
-- 4. notifications (268), notification_attempts (404), communications (1). The log of messages
--    actually sent. Locally these are test noise, and after the reset they reference orders that
--    are gone.
-- 5. low_stock_digest_runs is in the CLEAR list rather than here, because it is derived from
--    stock levels that are about to be deleted and would otherwise describe a store that no
--    longer exists.
--
-- And one thing worth saying plainly: the reset does NOT touch master_recipes. That table is not
-- tenant-owned — it is the shared library — and replacing it is the catalogue load's job, not
-- this script's.
-- =====================================================================
