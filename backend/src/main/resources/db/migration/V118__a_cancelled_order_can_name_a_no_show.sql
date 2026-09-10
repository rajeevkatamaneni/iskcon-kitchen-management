-- =====================================================================
-- V118 — A cancellation can say the vendor never turned up (T-124)
--
-- From Rajeev's five delivery scenarios, 2026-09-09. His fifth is the one
-- the application had no way to record at all:
--
--     "nothing ever came; we cancelled and went elsewhere"
--
-- Until now that order left the vendor scorecard entirely. The report's
-- own rule — LIVE_ORDER = status NOT IN ('DRAFT', 'CANCELLED') — was
-- written on a true premise, that a cancellation is usually the temple's
-- own decision and cannot be held against a supplier. What it missed is
-- that a cancellation is sometimes the *only* record of a supplier's
-- worst possible performance, and the report then made the vendor who
-- never came invisible while the one who was merely late was not.
--
-- ---------------------------------------------------------------------
-- Why a column and not an inference
--
-- Nothing already stored can distinguish the two cases. A cancelled order
-- with no goods receipt is equally consistent with "they never came" and
-- with "the festival was called off the day after we ordered". cancel_reason
-- is free text a human wrote for another human; reading a percentage off
-- it would be guessing, and the guess would be a permanent statement about
-- somebody else's business.
--
-- So the fact is captured, once, by the person who knows it — a tick box
-- on the cancel dialog reading "Vendor Never Delivered this Order", which
-- is Rajeev's own wording.
--
-- ---------------------------------------------------------------------
-- DEFAULT FALSE, and the default is the whole safety of it
--
-- Unticked by default is not a style choice. Two defects this week came
-- from boxes that were already ticked — the arrivals panel in T-107 and
-- the attendance roster in T-085 — and both recorded things nobody meant
-- to say. The same shape here would enrol every ordinary cancellation
-- against a supplier's record silently.
--
-- FALSE is also the direction that fails safe in the report: a cancellation
-- without the tick scores nothing at all, neither on-time nor abandoned.
-- Silence blames nobody, which is the point.
--
-- ---------------------------------------------------------------------
-- The CHECK, which makes the invariant structural rather than remembered
--
-- The flag only ever means something on a cancelled order. CANCELLED is
-- terminal in PurchaseOrderService — nothing transitions out of it — so
-- there is no path by which a ticked order could later become SENT again
-- and carry a claim about a delivery that then happened. Saying so in the
-- schema costs nothing and stops a future writer setting the flag on a
-- live order, where it would score a vendor 0% for an order still in
-- progress.
--
-- ---------------------------------------------------------------------
-- Who ticked it, and when
--
-- Not columns here. cancelled_at already says when, the PO_CANCELLED audit
-- record already says who, and T-124 puts the flag into that record's
-- after-state so the trail reads as one act rather than two facts that
-- have to be joined by hand. A second pair of actor columns would be a
-- second answer to a question already answered.
--
-- ---------------------------------------------------------------------
-- RLS, and why there is no per-tenant loop in this file
--
-- purchase_orders carries enable_tenant_rls() (V26:53) and the migration
-- role is unprivileged, so any UPDATE here would run with app.tenant_id
-- unset and touch nothing at all (the policy fails closed through NULLIF).
-- Migrations in this project therefore adopt each tenant in turn when they
-- touch rows.
--
-- This one touches none. Every existing order is un-abandoned, which is
-- exactly what the DEFAULT writes, and the CHECK is satisfied by FALSE on
-- every row whatever its status. ADD COLUMN and ADD CONSTRAINT are DDL and
-- run as the table owner, which is not subject to the policy — and ADD
-- CONSTRAINT genuinely validates every tenant's existing rows for the same
-- reason, rather than silently validating none of them. So the absence of
-- a tenant loop is a decision, not an oversight.
--
-- No new index. The scorecard reads this column alongside status and
-- order_date on a table it already scans per tenant and period, and a
-- boolean that is false on almost every row is the classic index that
-- never gets used.
-- =====================================================================

ALTER TABLE purchase_orders
    ADD COLUMN vendor_abandoned BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN purchase_orders.vendor_abandoned IS
    'TRUE when this order was cancelled because the vendor never delivered it (T-124). Recorded by the person cancelling, from the tick box "Vendor Never Delivered this Order"; FALSE means the temple cancelled for its own reasons, which is never held against a supplier. The vendor scorecard scores a TRUE order 0% on-time and counts it as abandoned; a FALSE cancellation is counted nowhere at all.';

ALTER TABLE purchase_orders
    ADD CONSTRAINT purchase_orders_abandoned_is_a_cancellation
    CHECK (vendor_abandoned = FALSE OR status = 'CANCELLED');
