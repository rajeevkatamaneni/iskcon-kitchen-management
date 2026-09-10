-- =====================================================================
-- V120 — an order nobody sent cannot be a vendor's no-show (T-129)
--
-- Rajeev ruled on 2026-09-10, from three options he was given, that the
-- tick box "Vendor Never Delivered this Order" is only offered once an
-- order has been marked sent.
--
-- The occasion was a real order on staging. The coordinator raised
-- PO-2026-0036 as a draft, never pressed "Mark sent", cancelled it with
-- the box ticked — and Heritage Fresh Dairy's scorecard then read
-- "0% on time, 1 order never delivered" for an order the vendor had
-- never heard of.
--
-- ---------------------------------------------------------------------
-- What this file does, and the one thing it deliberately does not
--
-- It clears vendor_abandoned on every cancelled order that was never
-- sent. That is a data correction, not a schema change: those rows are
-- the ruling's own definition of a claim nobody was entitled to make,
-- and the vendor scorecard reads the column directly, so leaving them
-- would keep scoring a supplier 0% for an order they were never told
-- about. There is no in-app route to undo the tick — CANCELLED is
-- terminal in PurchaseOrderService and nothing transitions out of it —
-- so a migration is the only place the correction can happen.
--
-- It adds NO CHECK constraint, and that is a decision rather than an
-- omission. V118 added purchase_orders_abandoned_is_a_cancellation, and
-- that one belongs in the schema: it ties two columns written by a single
-- UPDATE, and no decision about the product could ever make a no-show on
-- a live order meaningful. This rule is a different kind of thing. It is
-- a policy about what a person is allowed to assert, chosen on one day
-- from three defensible options and against a recommendation to leave it
-- alone — the losing option has a real case behind it, an order placed
-- over the telephone and never marked sent. Policy that may be revisited
-- belongs where reverting it costs an edit to one method, not a second
-- migration and a second rewrite of stored rows. The guard is in
-- PurchaseOrderService.cancel and returns KMS-400147.
--
-- A second, smaller reason: a CHECK here would constrain a row's history
-- rather than its state. sent_at is written by "Mark sent" and
-- vendor_abandoned by "Cancel", minutes or days apart and possibly by
-- different people, so the pairing is not atomically coherent the way
-- V118's is.
--
-- ---------------------------------------------------------------------
-- The activity trail is left exactly as it is
--
-- PO-2026-0036's trail carries "... - recorded as never delivered by the
-- vendor." That line is the honest record of what the coordinator did on
-- 10 September, and the trail is append-only by design. What was wrong
-- was never that the act happened; it was that the act scored somebody.
-- So the scoring input is corrected and the history of the act is not
-- rewritten. cancel_reason is untouched too: it holds only the operator's
-- own words, never the appended sentence.
--
-- ---------------------------------------------------------------------
-- RLS, and why there IS a tenant loop in this file
--
-- purchase_orders carries enable_tenant_rls() (V26:53) and the migration
-- role is unprivileged, so a bare UPDATE here would run with app.tenant_id
-- unset, match nothing through the policy's NULLIF, and report success
-- having corrected no row in any temple. That failure is silent, which is
-- the reason this project's migrations adopt each tenant in turn whenever
-- they touch rows rather than define them.
--
-- The count is raised as a notice so the rollout log says how many rows
-- were actually corrected. On staging the expected answer is 1.
-- =====================================================================

DO $$
DECLARE
    t         RECORD;
    corrected INTEGER;
    total     INTEGER := 0;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        UPDATE purchase_orders
        SET vendor_abandoned = FALSE,
            updated_at       = now()
        WHERE tenant_id = t.id
          AND vendor_abandoned = TRUE
          AND sent_at IS NULL;

        GET DIAGNOSTICS corrected = ROW_COUNT;
        total := total + corrected;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);

    RAISE NOTICE 'V120: % cancelled order(s) claimed a vendor no-show without ever having been '
        'sent; the claim is cleared and the activity trail is left alone (T-129).', total;
END $$;

COMMENT ON COLUMN purchase_orders.vendor_abandoned IS
    'TRUE when this order was cancelled because the vendor never delivered it (T-124). Recorded by the person cancelling, from the tick box "Vendor Never Delivered this Order"; FALSE means the temple cancelled for its own reasons, which is never held against a supplier. Only ever TRUE on an order that was marked sent (T-129, Rajeev 2026-09-10) - nothing was asked of a vendor who was never sent the order, so nothing counts against them; the rule is enforced by PurchaseOrderService.cancel (KMS-400147) rather than by a CHECK, because it is a policy that may be revisited. The vendor scorecard scores a TRUE order 0% on-time and counts it as abandoned; a FALSE cancellation is counted nowhere at all.';
