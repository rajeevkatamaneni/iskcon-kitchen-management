-- =====================================================================
-- V50 — the owner of an append-only table keeps UPDATE and DELETE
--
-- V49 gave the application role its privileges back so that foreign keys
-- pointing at an append-only table could take their row lock. It was still not
-- enough on the deployment, for a reason worth writing down:
--
--   PostgreSQL runs a foreign key's own check as the **owner** of the table it
--   is querying, not as the caller. On this deployment the owner of
--   stock_movements had no UPDATE or DELETE either — first because the
--   application role owned the schema and V3 had revoked them from it, and then
--   because REASSIGN OWNED carries the old owner's ACL entry across to the new
--   one. So the check was refused whoever asked, and no ingredient could be
--   deleted while the ledger referenced the table at all.
--
-- The trigger from V49 is what enforces append-only, and it stops the
-- application role only. The owner holding the privileges costs nothing —
-- an owner can grant them to itself at any time — and is what lets PostgreSQL
-- enforce its own referential integrity.
-- =====================================================================

CREATE OR REPLACE FUNCTION make_append_only(target_table TEXT)
RETURNS VOID AS $$
DECLARE
    v_owner text;
BEGIN
    SELECT pg_get_userbyid(relowner) INTO v_owner
    FROM pg_class WHERE oid = format('public.%I', target_table)::regclass;

    -- Both roles need the privileges PostgreSQL's own foreign key checks use: the owner because
    -- the check runs as the owner, the application role because the lock is taken in its
    -- transaction. Neither may actually change history — that is the trigger's job.
    EXECUTE format('GRANT UPDATE, DELETE ON %I TO %I', target_table, v_owner);
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
    'Makes a table append-only with a BEFORE UPDATE OR DELETE trigger. Not a privilege revoke: PostgreSQL runs a foreign key''s check as the table owner and takes a row lock that needs UPDATE or DELETE, so revoking them stops deletes of the referenced rows entirely.';

SELECT make_append_only('audit_events');
SELECT make_append_only('platform_audit_events');
SELECT make_append_only('stock_movements');
SELECT make_append_only('equipment_state_changes');
SELECT make_append_only('po_events');
SELECT make_append_only('goods_receipts');
SELECT make_append_only('goods_receipt_lines');
SELECT make_append_only('invoice_payments');
SELECT make_append_only('shift_broadcasts');
