-- =====================================================================
-- Clear one temple's operational data, keeping who it is and who works there
--
-- Run in Cloud SQL Studio. See docs/OUTSTANDING_BUILD_LIST.md item D1 for why
-- this exists and what is meant to be seeded afterwards.
--
--   Connect as : kms_migration   (owns the schema; kms_app cannot see every table)
--   Password   : Secret Manager → kms-staging-db-migration-password
--                gcloud secrets versions access latest \
--                  --secret=kms-staging-db-migration-password --project=iskcon-kms-2026
--   Database   : kms
--
-- KEPT:    the temple itself, its settings (Razorpay, email), users (staff and
--          devotee profiles), staff profiles and schedules, vendors and what
--          they supply, the Vaishnava calendar, the shared recipe library, meal
--          kinds, occasions, recipe categories and the translation glossary.
-- CLEARED: everything else this temple owns — recipes, ingredients, inventory,
--          the stock ledger, meal plans and services, purchase orders, receipts,
--          invoices and payments, donations, wish list, shifts and signups,
--          leave, staff payments and advances, notices, communications,
--          documents, and the audit trail.
--
-- Two settings make it possible, both transaction-local, exactly as
-- delete_tenant_cascade (V49) does it:
--   app.tenant_id      — satisfies row-level security, and scopes every delete
--   app.purging_tenant — tells the append-only trigger this is a purge, so the
--                        audit trail and the stock ledger can be cleared
-- Neither leaks past COMMIT, and ROLLBACK puts both back.
-- =====================================================================

-- 1. Which temples exist, so you can see the name the block below will match.
SELECT id, name, slug FROM tenants ORDER BY name;

-- 2. The reset. Nothing to paste — it finds the temple by name and refuses if that
--    name does not match exactly one. Wrapped in a transaction: read the NOTICEs,
--    and COMMIT only if they look right.
--
--    Run this block on its own. Running the whole file at once fails on step 3,
--    which cannot resolve the temple until the block above has run.
BEGIN;

DO $$
DECLARE
    -- The temple, by name rather than by a pasted id: an id typed by hand into a
    -- statement that deletes an audit trail is one keystroke from being the wrong
    -- temple's. Change the pattern if you are resetting a different one.
    v_name    text := '%bengaluru%';
    v_tenant  uuid;
    v_matches int;

    -- Tenant-owned tables that survive. Everything else with a tenant_id column
    -- is cleared.
    v_keep    text[] := ARRAY[
        'tenants', 'tenant_settings', 'users', 'communication_preferences',
        'staff_profiles', 'staff_schedule_template', 'staff_schedule_exceptions',
        'vendors', 'vendor_supplies',
        'recipe_categories', 'occasions', 'meal_slots', 'translation_glossary',
        'calendar_days', 'calendar_overrides', 'calendar_precompute_state',
        'meal_card_sequence', 'po_sequence'
    ];

    v_table    text;
    v_progress boolean;
    v_passes   int := 0;
    v_rows     bigint;
    v_total    bigint := 0;
BEGIN
    SELECT count(*) INTO v_matches FROM tenants WHERE name ILIKE v_name;
    IF v_matches <> 1 THEN
        RAISE EXCEPTION '% temples match %, expected exactly one — narrow the name',
            v_matches, v_name;
    END IF;
    SELECT id INTO v_tenant FROM tenants WHERE name ILIKE v_name;
    RAISE NOTICE 'resetting %', (SELECT name FROM tenants WHERE id = v_tenant);

    PERFORM set_config('app.tenant_id', v_tenant::text, true);
    PERFORM set_config('app.purging_tenant', 'on', true);

    -- Rather than hardcode a dependency order across dozens of interlocking
    -- tables, retry until a full pass deletes nothing new: a row whose parent
    -- has not gone yet simply waits for a later pass.
    LOOP
        v_progress := false;
        v_passes := v_passes + 1;

        FOR v_table IN
            SELECT c.relname
            FROM pg_class c
            JOIN pg_attribute a ON a.attrelid = c.oid
                               AND a.attname = 'tenant_id'
                               AND NOT a.attisdropped
            WHERE c.relkind = 'r'
              AND c.relnamespace = 'public'::regnamespace
              AND NOT (c.relname = ANY (v_keep))
            ORDER BY c.relname
        LOOP
            BEGIN
                EXECUTE format('DELETE FROM public.%I WHERE tenant_id = $1', v_table)
                    USING v_tenant;
                GET DIAGNOSTICS v_rows = ROW_COUNT;
                IF v_rows > 0 THEN
                    v_progress := true;
                    v_total := v_total + v_rows;
                    RAISE NOTICE 'pass %: % — % rows', v_passes, v_table, v_rows;
                END IF;
            EXCEPTION WHEN foreign_key_violation THEN
                -- Its children have not gone yet. A later pass will reach it.
                NULL;
            END;
        END LOOP;

        EXIT WHEN NOT v_progress OR v_passes > 20;
    END LOOP;

    RAISE NOTICE 'cleared % rows in % passes', v_total, v_passes;
END $$;

-- 3. Check before committing. Same lookup, so there is still nothing to paste.
--    RLS is on, and app.tenant_id is set by the block above for this transaction,
--    so these counts are this temple's by definition.
SELECT
    (SELECT count(*) FROM users)           AS users_kept,
    (SELECT count(*) FROM staff_profiles)  AS staff_kept,
    (SELECT count(*) FROM vendors)         AS vendors_kept,
    (SELECT count(*) FROM recipes)         AS recipes_left,
    (SELECT count(*) FROM stock_movements) AS ledger_left,
    (SELECT count(*) FROM audit_events)    AS audit_left;

-- COMMIT;    -- only when the counts above read the way you expect
-- ROLLBACK;  -- otherwise, and nothing happened at all
