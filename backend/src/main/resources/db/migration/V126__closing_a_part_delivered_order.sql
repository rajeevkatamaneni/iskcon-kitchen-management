-- =====================================================================
-- V126 — closing a part-delivered order, and the outcome the admin names (T-142, D-26)
--
-- Numbered V126 because V125 is the highest version applied to staging. As V125 says, `ls` on
-- this directory cannot tell you that — V105, V108 and V109 are absent from it — so the number
-- comes from the deployed schema history rather than from the file listing.
--
-- ---------------------------------------------------------------------
-- THE SCENARIO THIS FILE EXISTS FOR, in Rajeev's own words (D-26, 2026-09-10)
--
-- 500 kg of rice ordered. The vendor has 300 on hand and sends it immediately so the kitchen can
-- cook, expecting stock in two days for the remaining 200.
--
--     "The 200 KG should still be tied to the PO that raised and sent the 500KG rice order and it
--      should sit in a partially delivered state and the clock keeps ticking."
--
-- So the remainder does NOT go back to the shopping list when the lorry arrives short. The vendor
-- still owes it, the order is still live, and it is released to the list when the admin CLOSES the
-- purchase order — the third door beside D-24a's creation and cancellation.
--
-- What was missing was never the arithmetic. T-124 already scores on-time by quantity, so 300 kg
-- of 500 inside the window is 60%, computed with nothing new. What was missing was the ways an
-- order can END: until now a part-delivered order had no ending at all except a cancellation,
-- which is a lie about an order 300 kg of real rice arrived against.
--
-- ---------------------------------------------------------------------
-- WHY 'CLOSED' IS A NEW STATUS VALUE HERE, WHERE V125 ARGUED AGAINST ONE
--
-- V125 refused to make auto_cancelled a sixth status and gave the reason: "Every query in this
-- application that groups, filters or sums on status would silently stop seeing those orders."
-- That argument is right, and it is an argument for a COLUMN when the new state means the same
-- thing to every reader as the state it replaces. An auto-cancellation IS a cancellation; it must
-- behave like one everywhere, and only one screen needed to say a different word.
--
-- A closed order is the opposite case. It must STOP being what it was to almost every reader:
--
--   * it must stop being receivable — the remainder has been released and re-ordered elsewhere,
--     so a receipt against it would book the same rice twice (ReceivingService already refuses
--     anything that is not SENT or PARTIALLY_RECEIVED, so a new status is correct with no edit);
--   * it must leave the vendor scorecard's open and aging columns, because it is no longer open
--     and nobody is waiting for it (countOpenOrders reads IN ('SENT', 'PARTIALLY_RECEIVED'));
--   * it must stop suppressing its ingredients on the shopping list, which is the release itself;
--   * and it must go on being scored and go on being invoiceable, because 300 kg arrived and is
--     owed for.
--
-- The two predicates that must keep seeing it already do: the scorecard's LIVE_ORDER and
-- SCORED_ORDER are written as NOT IN ('DRAFT', 'CANCELLED'), so a new terminal status joins them
-- by construction. The one query that would have gone quiet is the invoice screen's open list,
-- which names its statuses positively, and T-142 adds 'CLOSED' to it in the same change.
--
-- The alternative — keep status PARTIALLY_RECEIVED and add closed_at — was measured against the
-- same list and loses on the first line: ReceivingService would have gone on accepting receipts
-- against a closed order, and that file is not this task's to edit. A design whose correctness
-- depends on editing a file nobody is editing is the one to reject.
--
-- ---------------------------------------------------------------------
-- THE OUTCOME, AND WHY IT IS A NAME RATHER THAN A NUMBER
--
-- Rajeev first proposed letting the admin adjust the computed score up or down at closing time —
-- "there is SO MUCH human interaction that no machine or app can capture" — and then ruled
-- against his own proposal when the costs were put to him:
--
--     "Let us not let the admin adjust the score. Just show it to them."
--
-- The four costs, kept here because this idea will occur to somebody again: an adjustable score
-- stops meaning what a vendor did and starts meaning what somebody felt about it; nobody ever
-- adjusts downward, so kindness accumulates in one direction until the scorecard says nothing;
-- "an admin changed it" is not an answer to a vendor disputing their score; and it reopens
-- everything D-25 and T-129 closed the same day to make the number defensible.
--
-- So there is no adjustment column here and there must never be one. The admin's influence is
-- close_outcome, which is one of three names:
--
--   VENDOR_LET_US_DOWN  the one who went silent and never rang back. Scored exactly as computed —
--                       the missing 200 kg is already in the percentage — and recorded, so a
--                       reader of a 60% can tell one the temple blames from one it accepts.
--   SHORTFALL_EXCUSED   the one who apologised, blamed the weather, offered a discount next time
--                       and said buy it elsewhere. The order leaves their score entirely, with
--                       the reason recorded beside it.
--   AS_COMPUTED         neither. The figures stand as the receipts made them.
--
-- Anything other than AS_COMPUTED requires a sentence, which is the CHECK below. A permanent
-- statement about somebody else's business with no explanation beside it is exactly the record
-- somebody will want to read back in a year and be unable to — the same reason V118 kept the
-- cancellation reason required beside its tick box.
--
-- ---------------------------------------------------------------------
-- THREE COLUMNS AND NOT A DERIVED BOOLEAN
--
-- The scorecard reads close_outcome directly rather than a waived BOOLEAN stamped beside it.
-- V125 stamped sent_after_lead_time because that verdict is not recoverable from the stored
-- numbers without redoing arithmetic that exists exactly once in Java. This one is not arithmetic
-- at all: it is a word a person chose, stored as the word they chose. A second column saying the
-- same thing would be two spellings of one fact and a way for them to disagree.
--
-- closed_at rather than reusing cancelled_at, because a closed order is not cancelled and every
-- screen and query that reasons about cancellations must go on reading a clean NULL there.
--
-- ---------------------------------------------------------------------
-- RLS, and why there is no per-tenant loop in this file
--
-- purchase_orders carries enable_tenant_rls() (V26:53) and the migration role is unprivileged, so
-- any UPDATE here would run with app.tenant_id unset and touch nothing at all — the policy fails
-- closed through NULLIF. Migrations in this project therefore adopt each tenant in turn WHENEVER
-- THEY TOUCH ROWS (see V120, which does).
--
-- This one touches none, and that is a decision. No order that already exists has been closed by
-- anybody, so NULL in all three columns is the truthful reading for every row in every tenant,
-- and it is what ADD COLUMN writes. There is nothing to back-fill and no tenant to adopt.
--
-- ADD COLUMN, DROP CONSTRAINT and ADD CONSTRAINT are DDL and run as the table owner, which is not
-- subject to the policy — and ADD CONSTRAINT genuinely validates every tenant's existing rows
-- rather than silently validating none of them.
--
-- No new index. The scorecard reads close_outcome alongside status and order_date on a table it
-- already scans per tenant and period, and the shopping list reads closed_at on the handful of
-- rows a tenant has closed. A column that is NULL on almost every row is the classic index that
-- never gets used.
-- =====================================================================

-- The lifecycle gains one terminal state. Dropped and re-added rather than edited in place,
-- because a CHECK constraint has no ALTER: the re-add re-validates every existing row, which is
-- the behaviour wanted — no stored order can be carrying a status this list does not name.
ALTER TABLE purchase_orders
    DROP CONSTRAINT purchase_orders_status_valid;

ALTER TABLE purchase_orders
    ADD CONSTRAINT purchase_orders_status_valid CHECK (
        status IN ('DRAFT', 'SENT', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED'));

ALTER TABLE purchase_orders
    ADD COLUMN closed_at     TIMESTAMPTZ,
    ADD COLUMN close_outcome TEXT,
    ADD COLUMN close_note    TEXT;

-- The three names, and no fourth. Spelt out rather than left to the application so that a future
-- writer cannot invent a fourth ending for a vendor's record without a migration and a decision.
ALTER TABLE purchase_orders
    ADD CONSTRAINT purchase_orders_close_outcome_valid CHECK (
        close_outcome IS NULL
        OR close_outcome IN ('VENDOR_LET_US_DOWN', 'SHORTFALL_EXCUSED', 'AS_COMPUTED'));

-- Closed and the closure's facts are one act, written by one UPDATE, so the pairing is atomically
-- coherent — which is the test V120 set for whether a rule belongs in a CHECK or in a service.
-- Written as an equality between two booleans so it fails in both directions: a CLOSED order with
-- no outcome, and an outcome sitting on an order that is still live.
ALTER TABLE purchase_orders
    ADD CONSTRAINT purchase_orders_closure_is_a_closed_order CHECK (
        (status = 'CLOSED') = (closed_at IS NOT NULL AND close_outcome IS NOT NULL));

-- Anything other than "as computed" requires a sentence (D-26). The NULL outcome makes the IN
-- test NULL and a CHECK passes on NULL, which is the reading wanted: an order nobody has closed
-- owes no explanation.
ALTER TABLE purchase_orders
    ADD CONSTRAINT purchase_orders_named_outcome_has_a_sentence CHECK (
        close_outcome NOT IN ('VENDOR_LET_US_DOWN', 'SHORTFALL_EXCUSED')
        OR close_note IS NOT NULL);

COMMENT ON COLUMN purchase_orders.closed_at IS
    'When a person ended this part-delivered order, releasing its undelivered remainder back to the shopping list (T-142, D-26). NULL on every order nobody has closed. Deliberately not cancelled_at: a closed order is not a cancellation - 300 kg of real rice arrived against it - and everything that reasons about cancellations must go on reading a clean NULL there. The nightly auto-cancel sweep can never set this: it matches status = DRAFT only, because an order with goods and a vendor relationship behind it needs the human decision this column records.';

COMMENT ON COLUMN purchase_orders.close_outcome IS
    'Which of the three endings the admin named when closing this order (D-26). VENDOR_LET_US_DOWN is the supplier who went silent - scored exactly as computed, because the missing quantity is already in the percentage, and recorded so a reader can tell a 60% the temple blames from one it accepts. SHORTFALL_EXCUSED is the supplier who apologised and said buy it elsewhere - the order leaves their on-time figure and their fill rate entirely, and the count of such orders sits beside both percentages on the scorecard because an exclusion that cannot be seen cannot be checked. AS_COMPUTED is neither. There is no column anywhere that lets an admin move the number itself, and there must never be one: Rajeev, having proposed it, ruled against it - "Let us not let the admin adjust the score. Just show it to them."';

COMMENT ON COLUMN purchase_orders.close_note IS
    'Why this order was closed the way it was, in the words of the person who closed it. Required whenever close_outcome is VENDOR_LET_US_DOWN or SHORTFALL_EXCUSED and enforced by purchase_orders_named_outcome_has_a_sentence: a permanent statement about somebody else s business with no explanation beside it is the record somebody wants to read back in a year and cannot. Optional on AS_COMPUTED, which asserts nothing about anybody.';
