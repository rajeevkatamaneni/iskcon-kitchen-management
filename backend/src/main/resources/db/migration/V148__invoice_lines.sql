-- =====================================================================
-- V148 — What an itemised invoice needs from the schema (T-271,
--        PROCUREMENT-REQUIREMENTS.md R-INV-3, R-INV-4)
--
-- V144 laid down vendor_invoice_lines and vendor_invoice_deliveries with no
-- Java behind them. T-271 is the invoice service that writes them, and four
-- things it needs are the database's to say:
--
--   1. invoice_lines_pack_shape passed a pack with no count. A CHECK passes
--      when it evaluates to NULL, and with pack_count NULL the V144 test
--      "pack_size_id IS NOT NULL AND pack_count > 0 AND ..." is NULL, not
--      false. T-260 found the same hole in its own po_lines_pack_shape (V146)
--      and reported this one. Rewritten with pack_count IS NOT NULL spelled
--      out.
--
--   2. A delivery is billed by one standing invoice at a time (R-INV-3).
--      V144 deliberately put no unique index on the join, because a voided
--      invoice must release its deliveries to be billed again. That left the
--      rule to a read-then-write in the service, which two people saving
--      bills for the same delivery at the same moment would both pass. So the
--      join gains released_at — set by the void, in the void's transaction —
--      and a UNIQUE index on (tenant_id, receipt_id) over the rows not yet
--      released. The second of two concurrent saves waits on the first's
--      index entry and, when the first commits, fails with a unique violation
--      the service turns into KMS-400169. The rule is then the database's,
--      not a race the service hopes to win. The same partial index is the
--      "not yet billed" lookup the billable-deliveries list runs.
--
--   3. vendor_invoices_direct_shape (V28) said a bill that is not direct has
--      an order, and a direct one a description. Neither holds for an
--      itemised bill: a bill for deliveries on two different orders is not
--      direct and has no single order (po_id is set only when every billed
--      delivery is on one order), and a direct bill's description is
--      optional now that its lines say what was bought (conductor's ruling
--      for T-271, 2026-09-19). Both relaxations apply only to an itemised
--      bill (sub_total set, which every invoice saved from here on has), so
--      every older row satisfies the constraint exactly as before.
--
--   4. vendor_invoice_lines.line_order: a bill's items read back in the
--      order they were entered, as purchase_order_lines.line_order (V26)
--      does for an order. Every line of one invoice is inserted in one
--      transaction, so created_at (now(), the transaction's start) is the
--      same for all of them and cannot order them.
--
-- Row-level security. Everything here is DDL, which runs as the owner,
-- EXCEPT the released_at backfill in section 2: that reads and writes tenant
-- rows, so it adopts each temple in turn as V144 and V145 do. No Java wrote
-- vendor_invoice_deliveries before this release, so on every real database
-- the loop finds nothing; it is written properly anyway, because "the table
-- is empty" is a claim about deployments this file cannot see.
-- =====================================================================


-- =====================================================================
-- 1. A pack on a bill line has a count (fixes V144's NULL hole)
-- =====================================================================

ALTER TABLE vendor_invoice_lines DROP CONSTRAINT invoice_lines_pack_shape;
ALTER TABLE vendor_invoice_lines ADD CONSTRAINT invoice_lines_pack_shape CHECK (
    (pack_size_id IS NULL AND pack_count IS NULL)
    OR (pack_size_id IS NOT NULL AND pack_count IS NOT NULL AND pack_count > 0
        AND ingredient_id IS NOT NULL));


-- =====================================================================
-- 2. One standing invoice per delivery
-- =====================================================================

ALTER TABLE vendor_invoice_deliveries ADD COLUMN released_at TIMESTAMPTZ;

COMMENT ON COLUMN vendor_invoice_deliveries.released_at IS
    'Set when the invoice is voided: the delivery may then be billed again (R-INV-3). Null while the invoice stands.';

-- The loop variable is v_tenant, not t: V57 crash-looped a deployment when a
-- table alias inside a loop resolved to a record variable of the same name.
DO $$
DECLARE
    v_tenant   RECORD;
    v_released INTEGER;
    v_total    INTEGER := 0;
BEGIN
    FOR v_tenant IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', v_tenant.id::text, true);

        UPDATE vendor_invoice_deliveries d
        SET released_at = COALESCE(vi.voided_at, now())
        FROM vendor_invoices vi
        WHERE vi.id = d.invoice_id
          AND vi.status = 'VOIDED'
          AND d.released_at IS NULL
          AND d.tenant_id = v_tenant.id;

        GET DIAGNOSTICS v_released = ROW_COUNT;
        v_total := v_total + v_released;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);

    RAISE NOTICE 'V148: % delivery link(s) of voided invoices released.', v_total;
END $$;

CREATE UNIQUE INDEX invoice_deliveries_billed_once
    ON vendor_invoice_deliveries (tenant_id, receipt_id)
    WHERE released_at IS NULL;


-- =====================================================================
-- 3. An itemised bill may span orders, and a direct one need not describe
--    itself
-- =====================================================================

ALTER TABLE vendor_invoices DROP CONSTRAINT vendor_invoices_direct_shape;
ALTER TABLE vendor_invoices ADD CONSTRAINT vendor_invoices_direct_shape CHECK (
    (direct AND po_id IS NULL AND (description IS NOT NULL OR sub_total IS NOT NULL))
    OR (NOT direct AND (po_id IS NOT NULL OR sub_total IS NOT NULL)));


-- =====================================================================
-- 4. A bill's items keep the order they were entered in
-- =====================================================================

ALTER TABLE vendor_invoice_lines ADD COLUMN line_order INTEGER NOT NULL DEFAULT 0;
