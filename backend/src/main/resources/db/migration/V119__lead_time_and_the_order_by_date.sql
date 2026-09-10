-- =====================================================================
-- V119 — How long a vendor takes, and the date that follows from it (T-090)
--
-- Numbered V119 because V118 is the highest version applied to staging as of tonight's deploy.
-- `ls` on this directory cannot tell you that — V105, V108 and V109 are absent from it and V111 was
-- renumbered out of V108 this week — so the number comes from the deployed schema history and not
-- from the file listing.
--
-- ---------------------------------------------------------------------
-- WHAT WAS MISSING, and it was the whole prerequisite
--
-- Rajeev's rule, from his review of 2026-09-08: "order-by date = the date it is needed minus the
-- lead time. Amber while there is still slack; red the day you hit the order-by date; and past that
-- it is not a warning any more but a fact."
--
-- None of that could be computed, because THERE WAS NO LEAD TIME ANYWHERE IN THE PRODUCT. Not on
-- the ingredient, not on the vendor. The application had exactly one number in this area — a global
-- assumption of two days, hardcoded twice (ShoppingListService.LEAD_BUFFER_DAYS and
-- frontend/lib/format.ts) — and that number answers a different question. See the note at the
-- bottom of this file, because two numbers that look alike and mean different things is how a
-- schema starts lying.
--
-- ---------------------------------------------------------------------
-- WHY IT GOES ON vendor_supplies AND NOT ON THE INGREDIENT OR THE VENDOR
--
-- Ruled per ingredient-and-vendor rather than globally, and the table already exists for exactly
-- this shape of fact: vendor_supplies (V24:44) is the (vendor, ingredient) pair, unique on the two
-- of them, already carrying last_price and preferred — both of which are facts about THIS vendor
-- supplying THIS ingredient rather than about either alone.
--
-- Lead time is the same kind of fact and for the same reason. The rice merchant may deliver rice
-- next morning and take a week over a sack of jaggery he has to fetch. The hardware shop delivers
-- brooms the same day and orders a gas regulator in. Putting the number on the vendor would average
-- those into a figure that is wrong for both; putting it on the ingredient would say that rice takes
-- four days no matter who is asked, which is a statement about a commodity rather than about a
-- supplier.
--
-- ---------------------------------------------------------------------
-- NULLABLE, AND NULL IS NOT ZERO — this is the trap the column exists to avoid
--
-- A supply row with no recorded lead time means NOBODY HAS SAID how long this vendor takes. It does
-- not mean the goods arrive the same day, and a screen that scored it that way would quietly tell a
-- cook there was time when there was none. So the column is nullable, has no DEFAULT, and every
-- reader is expected to fall back rather than to arithmetic on a null.
--
-- The fallback is the two days the product has always assumed (LeadTimes.ASSUMED_LEAD_TIME_DAYS),
-- because inventing a second guess so that the first one could be superseded would be worse than
-- keeping one. A recorded lead time supersedes the assumption; an absent one leaves it in place.
--
-- A DEFAULT of 2 was considered and rejected. It would have made "unknown" and "the vendor told us
-- two days" the same row, and the second is worth acting on while the first is worth chasing.
--
-- ---------------------------------------------------------------------
-- THE UPPER BOUND, and why there is one at all
--
-- 365. Not because a vendor might take a year, but because this number is subtracted from a date
-- and a typo of 3650 would put an order-by date ten years into the past, which renders as the "too
-- late" state on every screen at once and looks like a system-wide failure rather than a slip in one
-- field. A bound that is obviously beyond any real answer costs a person nothing and stops the one
-- mistake that is expensive to diagnose.
--
-- Zero is allowed and means something real: cash-and-carry, the shop somebody walks into, where the
-- goods come back in the same van as the person.
--
-- ---------------------------------------------------------------------
-- THE TWO COLUMNS ON shopping_list_lines
--
-- order_by is the date Rajeev's rule produces: the earliest meal that demands the ingredient, minus
-- the effective lead time. It is a computed snapshot alongside needed_by and current_stock, both of
-- which are already snapshots on this row, and it refreshes on every regeneration for the same
-- reason they do.
--
-- lead_time_days is the RECORDED figure that produced it, or NULL where none was recorded and the
-- assumption was used. It is stored rather than derived so the screen can say whether the date in
-- front of somebody came from their vendor or from us. Without it the two cases are indistinguishable
-- on screen, which is precisely the confusion the nullable column above exists to prevent.
--
-- ---------------------------------------------------------------------
-- WHAT THIS IS NOT: shopping_list_lines.needed_by is untouched, deliberately
--
-- needed_by on a shopping list line is NOT the date the meal is cooked. Since E5-S2 it has held
-- (earliest demand − 2 days), and PurchaseOrderService.generate copies the minimum of it onto the
-- purchase order as the date the VENDOR IS ASKED TO DELIVER BY. So the existing two days are a
-- DELIVERY BUFFER — deliver two days before we cook — and not a lead time at all, even though the
-- constant behind them is called LEAD_BUFFER_DAYS.
--
-- Those are different quantities answering different questions:
--
--   lead time      how long the vendor takes between being asked and delivering.
--                  Answers "when is the last day we can ask?"  ->  order_by
--
--   delivery buffer how much earlier than the meal we want the goods on the shelf.
--                  Answers "what date do we write on the order?" -> needed_by
--
-- Changing needed_by would change what every generated purchase order asks a supplier for, which is
-- a decision about the product and not a side effect of adding a field. It is left exactly as it
-- was, and the double-count it produces with the order screen's own warning is written up in
-- docs/work/proof/T-090.md for Rajeev rather than fixed here.
--
-- ---------------------------------------------------------------------
-- RLS
--
-- Both tables carry enable_tenant_rls() (vendor_supplies at V24:66, shopping_list_lines at V25:46, under its old name) and
-- the migration role is unprivileged, so any SELECT or UPDATE in this file would run with
-- app.tenant_id unset and see no rows at all — the policy fails closed through NULLIF. That is why
-- migrations in this project that touch rows adopt each tenant in turn.
--
-- This one touches no rows. Three nullable columns with no DEFAULT are added and nothing is
-- backfilled: an existing supply row correctly reads "nobody has said", and an existing shopping
-- list line gets its order_by at the next regeneration, which runs nightly. ADD COLUMN and ADD
-- CONSTRAINT are DDL and run as the table owner, which is not subject to the policy, so the checks
-- below genuinely validate every tenant's rows rather than silently validating none of them. The
-- absence of a tenant loop is a decision, not an oversight.
-- =====================================================================

ALTER TABLE vendor_supplies
    ADD COLUMN lead_time_days INTEGER;

ALTER TABLE vendor_supplies
    ADD CONSTRAINT vendor_supplies_lead_time_sane
    CHECK (lead_time_days IS NULL OR (lead_time_days >= 0 AND lead_time_days <= 365));

COMMENT ON COLUMN vendor_supplies.lead_time_days IS
    'How many days this vendor takes to deliver this ingredient once asked (T-090). NULL means nobody has recorded it — it does NOT mean same-day, and readers fall back to LeadTimes.ASSUMED_LEAD_TIME_DAYS rather than treating it as zero. 0 is a real answer and means cash-and-carry.';

ALTER TABLE shopping_list_lines
    ADD COLUMN order_by DATE;

ALTER TABLE shopping_list_lines
    ADD COLUMN lead_time_days INTEGER;

COMMENT ON COLUMN shopping_list_lines.order_by IS
    'The last day this can be ordered and still arrive in time (T-090): the earliest meal that demands it, minus the effective lead time. NULL on a hand-added line, which no meal demanded and which therefore has no such date. Distinct from needed_by, which is the delivery date written on the purchase order.';

COMMENT ON COLUMN shopping_list_lines.lead_time_days IS
    'The lead time recorded against the preferred vendor for this ingredient, which produced order_by. NULL means none was recorded and the two-day assumption was used — kept so a screen can say whether the date came from the vendor or from us.';
