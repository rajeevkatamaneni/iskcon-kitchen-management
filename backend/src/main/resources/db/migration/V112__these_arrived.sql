-- =====================================================================
-- V112 — "These arrived": a described line can finally be accounted for
--        (T-066, Rajeev's ruling 4 of the 2026-09-08 review)
--
-- Four plastic stools. V100 (T-024) made them orderable and payable
-- without inventing an `ingredients` row for them, and deliberately kept
-- them un-receivable: goods_receipt_lines.ingredient_id and
-- stock_movements.ingredient_id are NOT NULL and stay that way, because
-- the store room counts things and a stool is not one of them.
--
-- What V100 did not give them was any way to be finished with. An order
-- of nothing but described lines could never take a goods receipt, so it
-- never left SENT: it sat in the vendor scorecard's open-orders aging
-- bucket for ever, ageing past 31 days, reading as a supplier who had
-- been sitting on an order since last year — and it scored late for
-- ever, because on-time is measured at an order's first receipt and this
-- order can never have one.
--
-- Worse, KMS-400129 — the refusal a storekeeper meets when they try to
-- receive the stools — told them to "record it as delivered on the
-- order". No such action existed anywhere. This column is that action.
--
-- ---------------------------------------------------------------------
-- A status transition, not a stock movement
--
-- Rajeev's ruling was explicit about the shape: described lines get a
-- "these arrived" acknowledgement that CLOSES THE ORDER WITHOUT TOUCHING
-- STOCK. So there is no batch, no expiry, no movement and no receipt
-- row here — nothing but a date on the line saying the thing turned up,
-- and the two columns that say who recorded it and when.
--
-- The rejected alternative was to exclude such orders from on-time
-- judgement entirely. It is cheaper and it is quieter, and it has a real
-- cost: a vendor who genuinely never delivered the stools would then
-- score nothing at all, indistinguishable from one who delivered them on
-- the day. An order whose stools nobody has recorded as arrived still
-- reads as undelivered here, and that is now a true statement about the
-- vendor rather than a structural impossibility in our schema.
--
-- ---------------------------------------------------------------------
-- Why arrived_on is a DATE and not just the timestamp beside it
--
-- arrived_recorded_at is when somebody pressed the button. arrived_on is
-- the temple's day the goods turned up, and it is the one the vendor
-- scorecard compares against needed_by. They are written with the same
-- value today — the application has no backdating field yet — but they
-- are not the same fact, and collapsing them would mean that adding
-- "they actually came on Tuesday" later had to re-interpret a column
-- that already meant something else. A day computed in the temple's own
-- zone also cannot be recovered from a timestamptz alone without knowing
-- which tenant's zone to read it in.
--
-- ---------------------------------------------------------------------
-- Only a described line arrives, and it arrives whole
--
-- po_lines_only_a_described_line_arrives: a catalogue line is accounted
-- for by a goods receipt and by nothing else. Allowing a date here on a
-- line that names an ingredient would give "is this line covered" two
-- competing answers — the ledger's and this column's — and the first
-- time they disagreed the order's status would depend on which query ran.
--
-- po_lines_arrival_is_recorded_whole: the three columns are one fact.
-- A date with nobody's name against it is a record of an arrival that
-- nobody is accountable for, which is the state the constraint exists to
-- make impossible rather than merely unlikely.
--
-- ---------------------------------------------------------------------
-- RLS, and why there is no per-tenant loop in this file
--
-- purchase_order_lines carries enable_tenant_rls() (V26:67) and the
-- migration role is unprivileged, so any SELECT or UPDATE here would run
-- with app.tenant_id unset and see nothing at all (the policy fails
-- closed through NULLIF). Migrations in this project therefore adopt each
-- tenant in turn when they touch rows.
--
-- This one touches no rows. Every existing line is un-arrived, which is
-- exactly what a NULL in a newly added column says, and both CHECKs are
-- satisfied by three NULLs. ADD COLUMN and ADD CONSTRAINT are DDL and run
-- as the table owner, which is not subject to the policy — and ADD
-- CONSTRAINT genuinely validates every tenant's existing rows for the
-- same reason, rather than silently validating none of them. So the
-- absence of a tenant loop here is a decision, not an oversight.
--
-- The FK on arrived_recorded_by is ON DELETE RESTRICT like every other
-- actor column in this schema; delete_tenant_cascade (V44) retries until
-- a pass deletes nothing new, so purchase_order_lines simply goes before
-- users rather than needing an order hardcoded anywhere.
--
-- No new index. The only two readers filter by po_id — the order detail
-- query and the scorecard's first-arrival subquery — and po_lines_po
-- (V26:63) already serves that; a partial index on the un-arrived
-- described lines would be a second thing to keep true for a table that
-- holds a handful of rows per order.
-- =====================================================================

ALTER TABLE purchase_order_lines
    ADD COLUMN arrived_on          DATE,
    ADD COLUMN arrived_recorded_at TIMESTAMPTZ,
    ADD COLUMN arrived_recorded_by UUID REFERENCES users(id) ON DELETE RESTRICT;

COMMENT ON COLUMN purchase_order_lines.arrived_on IS
    'The temple''s day a described line''s goods turned up, recorded as an acknowledgement rather than as a goods receipt because the store room does not track them (T-066). NULL on every catalogue line, and on a described line nobody has accounted for yet. This is the date the vendor scorecard judges on-time against when an order has no goods receipt.';

COMMENT ON COLUMN purchase_order_lines.arrived_recorded_at IS
    'When the arrival was recorded, which is not the same fact as arrived_on: somebody may be recording on Thursday that the stools came on Tuesday.';

COMMENT ON COLUMN purchase_order_lines.arrived_recorded_by IS
    'Who recorded the arrival. An arrival with nobody against it is not a record anybody can stand behind — see po_lines_arrival_is_recorded_whole.';

-- A catalogue line is accounted for by the ledger and by nothing else.
ALTER TABLE purchase_order_lines
    ADD CONSTRAINT po_lines_only_a_described_line_arrives
    CHECK (arrived_on IS NULL OR ingredient_id IS NULL);

-- The three columns are one fact: all three, or none of them.
ALTER TABLE purchase_order_lines
    ADD CONSTRAINT po_lines_arrival_is_recorded_whole
    CHECK (num_nonnulls(arrived_on, arrived_recorded_at, arrived_recorded_by) IN (0, 3));
