-- =====================================================================
-- V49 — append-only enforced by a rule, not by a missing privilege
--
-- Since V3, a table was made append-only by revoking UPDATE and DELETE from
-- the application role. That works, and it is exactly why an ingredient could
-- not be deleted on a real deployment:
--
--   PostgreSQL's own ON DELETE RESTRICT check runs
--       SELECT 1 FROM ONLY stock_movements WHERE ingredient_id = $1 FOR KEY SHARE
--   and a FOR KEY SHARE lock requires UPDATE or DELETE on that table. The
--   check runs with the privileges of whoever issued the delete, so the
--   application refused its own integrity check: "permission denied for table
--   stock_movements" — reaching the user as KMS-5001 on a catalogue screen that
--   has nothing to do with the stock ledger.
--
-- The invariant is unchanged and still the database's to keep; only the
-- mechanism moves. The privileges go back, and a BEFORE trigger refuses the
-- operations with the same SQLSTATE the revoke produced (42501), so anything
-- that used to catch "insufficient privilege" still does.
--
-- The one path allowed through is the whole-tenant purge (V44), which announces
-- itself with a transaction-local setting the trigger honours — the same trust
-- model as app.tenant_id, and set only inside that SECURITY DEFINER function.
-- =====================================================================

CREATE OR REPLACE FUNCTION reject_append_only_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- Exactly who the revoke used to stop, and no one else: the application role. The schema owner
    -- still maintains its own tables, and the whole-tenant purge announces itself.
    IF current_user <> 'kms_app'
       OR current_setting('app.purging_tenant', true) = 'on' THEN
        RETURN CASE TG_OP WHEN 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    RAISE EXCEPTION 'permission denied for table %: it is append-only', TG_TABLE_NAME
        USING ERRCODE = '42501',
              HINT = 'Correct an entry by adding a compensating one; history is never edited.';
END;
$$;

COMMENT ON FUNCTION reject_append_only_change() IS
    'Refuses UPDATE and DELETE on an append-only table, except inside delete_tenant_cascade.';

CREATE OR REPLACE FUNCTION make_append_only(target_table TEXT)
RETURNS VOID AS $$
BEGIN
    -- The grant is deliberate: the privilege is what PostgreSQL needs to enforce foreign keys
    -- that point at this table. The trigger below is what stops the application using it.
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kms_app') THEN
        EXECUTE format('GRANT UPDATE, DELETE ON %I TO kms_app', target_table);
    END IF;

    EXECUTE format('DROP TRIGGER IF EXISTS %I ON %I',
                   target_table || '_append_only', target_table);
    EXECUTE format(
        'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I '
        'FOR EACH ROW EXECUTE FUNCTION reject_append_only_change()',
        target_table || '_append_only', target_table);
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION make_append_only(TEXT) IS
    'Makes a table append-only with a BEFORE UPDATE OR DELETE trigger. Every append-only table must call this. Not a privilege revoke: foreign keys pointing at the table need UPDATE/DELETE to take their row lock.';

-- Re-apply to every table that was made append-only under the old mechanism.
SELECT make_append_only('audit_events');
SELECT make_append_only('platform_audit_events');
SELECT make_append_only('stock_movements');
SELECT make_append_only('equipment_state_changes');
SELECT make_append_only('po_events');
SELECT make_append_only('goods_receipts');
SELECT make_append_only('goods_receipt_lines');
SELECT make_append_only('invoice_payments');
SELECT make_append_only('shift_broadcasts');

-- ---------------------------------------------------------------------
-- The purge announces itself instead of granting itself privileges.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION delete_tenant_cascade(p_tenant uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_table    text;
    v_progress boolean;
    v_passes   int := 0;
    v_rows     bigint;
BEGIN
    -- Scope every delete to this one tenant. Transaction-local, so it cannot leak past commit.
    PERFORM set_config('app.tenant_id', p_tenant::text, true);

    -- Announce the purge to the append-only trigger. Transaction-local for the same reason:
    -- no other connection ever observes the guard down, and a rollback puts it back.
    PERFORM set_config('app.purging_tenant', 'on', true);

    -- Delete the tenant's rows from every tenant-owned table. Rather than hardcode a
    -- dependency order across dozens of interlocking tables, retry until a full pass
    -- deletes nothing new: a row whose FK parent has not gone yet simply waits for a
    -- later pass. A per-statement block turns a not-yet-deletable table into a skip.
    LOOP
        v_progress := false;
        v_passes := v_passes + 1;
        FOR v_table IN
            SELECT c.relname
            FROM pg_class c
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
            WHERE c.relkind = 'r' AND c.relnamespace = 'public'::regnamespace
        LOOP
            BEGIN
                EXECUTE format('DELETE FROM public.%I WHERE tenant_id = $1', v_table) USING p_tenant;
                GET DIAGNOSTICS v_rows = ROW_COUNT;
                IF v_rows > 0 THEN
                    v_progress := true;
                END IF;
            EXCEPTION WHEN foreign_key_violation THEN
                NULL; -- FK parents remain; retry on a later pass
            END;
        END LOOP;
        EXIT WHEN NOT v_progress;
        IF v_passes > 100 THEN
            RAISE EXCEPTION 'delete_tenant_cascade(%): did not converge — a dependency cycle or an un-purgeable reference', p_tenant;
        END IF;
    END LOOP;

    PERFORM set_config('app.purging_tenant', 'off', true);

    -- Finally the temple row itself (not tenant-owned, not RLS-scoped).
    DELETE FROM public.tenants WHERE id = p_tenant;
END;
$$;
