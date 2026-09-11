-- =====================================================================
-- V125 — the lead time an order actually went out under, stamped on the order (T-137, D-25)
--
-- Numbered V125 because V124 is the highest version applied to staging. `ls` on this directory
-- cannot tell you that — V105, V108 and V109 are absent from it — so the number comes from the
-- deployed schema history and not from the file listing.
--
-- ---------------------------------------------------------------------
-- THE RULE THIS FILE EXISTS FOR, and it is the reason there is a column at all
--
-- Rajeev, 2026-09-10: "Any SLA Adjustments made to a vendor's profile will take effect for the
-- Orders after the change. No retroactive change here."
--
-- A lead time is the vendor's own number, agreed at onboarding and padded by them on purpose
-- ("we are good with 1 day lead time BUT we want to be safe than sorry so we need 3 days notice").
-- It lives on vendor_supplies.lead_time_days (V119), per vendor AND per ingredient, and it is
-- editable — a vendor rings up and says they need four days now, and somebody changes it.
--
-- If the vendor scorecard read that column live, that one edit would silently re-judge every order
-- already placed: deliveries that were on time become late, and orders we placed too late stop
-- being excused. A percentage somebody has already read off a screen would change with no act
-- anywhere near it. So the figure that applied is stamped onto the order at the moment it is sent,
-- and everything afterwards reads it from here.
--
-- The product already had this exact pattern and its reasoning: shopping_list_lines
-- .suggested_vendor_id was a snapshot taken at generation time, kept precisely so a later edit
-- could not change what an order had already asked for.
--
-- ---------------------------------------------------------------------
-- WHY THE VERDICT IS STORED AS WELL AS THE NUMBER
--
-- sent_after_lead_time is not derivable from lead_time_days and needed_by without redoing the
-- arithmetic — needed_by minus the lead time, compared against the day the order went out, in the
-- temple's own zone. That arithmetic exists once, in LeadTimes/OrderUrgency (T-090), and it is the
-- whole point of T-137 that it exists once: the planner badge, the Mark sent gate and the two
-- dashboards must never be able to give three answers about one order.
--
-- Writing the same subtraction into SQL here so the scorecard could recompute it would be the
-- second copy. So the verdict is decided in Java, once, at the moment of sending, and stored.
-- The scorecard then reads a boolean and does no date arithmetic at all.
--
-- lead_time_days is stored beside it because a boolean cannot explain itself. The screen says
-- "Heritage Fresh Dairy asked for 2 days' notice", which needs the number, and so does anybody
-- reading back why an order was excluded from a supplier's figures.
--
-- NULL lead_time_days means NOBODY HAS SAID — the vendor has no recorded lead time for anything on
-- this order. Rajeev ruled that case is silence: no nudge, no warning, no exclusion, nothing held
-- against anybody. It is emphatically not zero, for the same reason V119 refused a DEFAULT of 2:
-- "unknown" and "the vendor told us" are different facts and only one of them is worth acting on.
--
-- ---------------------------------------------------------------------
-- WHY auto_cancelled IS A COLUMN AND NOT A NEW STATUS VALUE
--
-- D-24a: a draft whose needed-by date has passed is abandoned, and is auto-cancelled — "mark it as
-- Auto Cancelled. Reason: Past need by date." The obvious reading is a sixth value in
-- purchase_orders_status_valid, and it would be a mistake. Every query in this application that
-- groups, filters or sums on status would silently stop seeing those orders: the scorecard's
-- LIVE_ORDER predicate, the invoice screen's open list, the order list's filter. A new state added
-- to a summed column breaks every existing sum, quietly, and the breakage looks like a smaller
-- number rather than an error.
--
-- So an auto-cancellation IS a cancellation — status CANCELLED, cancel_reason 'Past need by date',
-- cancelled_at set — and this column says who did it. Everything that already treats a cancellation
-- correctly goes on doing so, and the one screen that needs to say "Auto Cancelled" reads one more
-- boolean. The CHECK ties it to the status for the same reason V118 tied vendor_abandoned to it:
-- both are written by a single UPDATE, and no product decision could make an auto-cancelled live
-- order mean anything.
--
-- ---------------------------------------------------------------------
-- RLS, and why there is no per-tenant loop in this file
--
-- purchase_orders carries enable_tenant_rls() (V26:53) and the migration role is unprivileged, so
-- any UPDATE here would run with app.tenant_id unset and touch nothing at all — the policy fails
-- closed through NULLIF. Migrations in this project therefore adopt each tenant in turn WHENEVER
-- THEY TOUCH ROWS (see V120, which does).
--
-- This one touches none, and that is a decision rather than an oversight. Every order already
-- placed goes out under no recorded lead time (NULL), is not late (FALSE) and was not cancelled by
-- a machine (FALSE) — which is exactly what the DEFAULTs write and exactly the reading that holds
-- nothing against anybody. Back-filling lead_time_days from today's vendor_supplies would be the
-- precise thing the no-retroactive rule forbids: judging orders that are already in the past
-- against numbers nobody had agreed when they went out.
--
-- ADD COLUMN and ADD CONSTRAINT are DDL and run as the table owner, which is not subject to the
-- policy — and ADD CONSTRAINT genuinely validates every tenant's existing rows rather than silently
-- validating none of them.
--
-- No new index. The scorecard reads sent_after_lead_time alongside status and order_date on a table
-- it already scans per tenant and period, and a boolean that is false on almost every row is the
-- classic index that never gets used.
-- =====================================================================

ALTER TABLE purchase_orders
    ADD COLUMN lead_time_days       INTEGER,
    ADD COLUMN sent_after_lead_time BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN auto_cancelled       BOOLEAN NOT NULL DEFAULT FALSE;

-- The same bound V119 put on vendor_supplies.lead_time_days, for the same reason: this number is
-- subtracted from a date, and a typo of 3650 would put the order-by date ten years into the past
-- and read as a system-wide failure rather than as one slip in one field.
ALTER TABLE purchase_orders
    ADD CONSTRAINT purchase_orders_lead_time_is_plausible
    CHECK (lead_time_days IS NULL OR (lead_time_days >= 0 AND lead_time_days <= 365));

-- Lateness is a fact about SENDING, so it cannot be true of an order nobody sent. Both columns are
-- written by the single UPDATE in PurchaseOrderService.send, so the pairing is atomically coherent
-- — which is the test V120 set for whether a rule belongs in a CHECK or in a service.
ALTER TABLE purchase_orders
    ADD CONSTRAINT purchase_orders_lateness_is_a_sending
    CHECK (sent_after_lead_time = FALSE OR sent_at IS NOT NULL);

ALTER TABLE purchase_orders
    ADD CONSTRAINT purchase_orders_auto_cancel_is_a_cancellation
    CHECK (auto_cancelled = FALSE OR status = 'CANCELLED');

COMMENT ON COLUMN purchase_orders.lead_time_days IS
    'The lead time this order actually went out under, in days: the LONGEST recorded against its vendor for the ingredients on it (D-25 - an order is only deliverable when its slowest item is), stamped by PurchaseOrderService.send. NULL means no lead time was recorded for anything on this order, which is silence: no warning on the way out and no exclusion afterwards. Never read vendor_supplies.lead_time_days for an order that has been sent - editing a vendor profile must not re-judge orders already placed (Rajeev, 2026-09-10: "No retroactive change here").';

COMMENT ON COLUMN purchase_orders.sent_after_lead_time IS
    'TRUE when this order was submitted after the last day it could have been placed - needed_by minus lead_time_days - so we asked the vendor for something their agreed notice period could not deliver (D-25). The person sending it saw KMS-400148 and pressed on anyway. The vendor scorecard leaves such an order out of the on-time figure entirely and counts it in its own column instead, because "that is a FAVOR we are asking" and a delay on it cannot fairly be counted towards their performance. Decided once, in Java, at the moment of sending; never recomputed.';

COMMENT ON COLUMN purchase_orders.auto_cancelled IS
    'TRUE when the nightly sweep cancelled this draft because its needed-by date had passed (D-24a, "mark it as Auto Cancelled. Reason: Past need by date"). A draft holds its ingredients off the shopping list from the moment it is created, so one nobody ever sends holds them hostage; cancelling it hands them back. Only ever TRUE on a DRAFT that was swept - the job never touches a part-delivered order (D-26), which needs a human decision. The screens read this to say "Auto Cancelled" instead of "Cancelled"; everything that reasons about cancellations reads status, which is why this is a column and not a sixth status value.';
