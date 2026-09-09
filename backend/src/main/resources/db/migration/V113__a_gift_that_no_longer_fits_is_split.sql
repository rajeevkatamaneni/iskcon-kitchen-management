-- =====================================================================
-- V113 — A wish-list gift that no longer fully fits is split, not diverted
--        whole (T-081)
--
-- Two devotees give towards the same grinder. The second's card is captured
-- *before* the item is re-checked, because that is the only safe order — you
-- cannot promise a donor an item and then find out whether the money can be
-- taken. If the first gift lands in between, the second no longer fits, and
-- until now the *entire* second gift was re-pointed at general funds: a grinder
-- needing ₹14,000, given ₹10,000 and then ₹14,000, stayed ₹4,000 short while
-- ₹14,000 that meant to finish it sat in the general fund.
--
-- Rajeev's ruling, 2026-09-10: apply exactly what is owed — which by definition
-- completes the item — and send the remainder to general funds.
--
-- ---------------------------------------------------------------------
-- One row, not two, and the 80G receipt is the reason
-- ---------------------------------------------------------------------
-- The obvious alternative was two donation rows sharing one payment: one
-- earmarked, one general. It was rejected, and on a ground that has nothing to
-- do with convenience — **one card payment must produce one 80G receipt.** The
-- receipt is what a human keeps and what the tax authority reads, and an
-- accountant reconciling a bank statement against this ledger would find two
-- rows answering to one payment reference and have to know to pair them up. The
-- same argument V104 made for marking a struck gift rather than compensating it:
-- a ledger somebody reads one row of at a time must have one row per act.
--
-- So the split lives *inside* the row, as one column.
--
-- ---------------------------------------------------------------------
-- What the column means, and what it deliberately does not
-- ---------------------------------------------------------------------
-- `amount_inr` keeps its meaning exactly: what the donor paid, in one payment.
-- Nothing that reads a donation *as a payment* — the 80G dataset, the period
-- tiles, the ledger's amount, and T-020's receipt when it is built — needs to
-- know a split happened, and none of them changes. That is the point of putting
-- the split in a second column rather than reducing `amount_inr` to the applied
-- part and inventing a row for the rest.
--
-- `wishlist_applied_inr` is how much of that payment went to the item named by
-- `wishlist_item_id`. NULL means "all of it", which is what every row written
-- before today means and what every gift that fits will go on meaning. Only a
-- split ever sets it. Three states, one representation each:
--
--   wishlist_item_id  wishlist_applied_inr   what the row says
--   ----------------  --------------------   ---------------------------------
--   NULL              NULL                   a general gift (or one wholly
--                                            converted because the item was
--                                            already paid for)
--   set               NULL                   the whole gift went to that item
--   set               X, 0 < X < amount_inr  X went to that item, and
--                                            amount_inr - X to general funds
--
-- The CHECK below enforces exactly those, and the strict bounds are what make
-- the representation unique: X = amount_inr is not a split, it is the second
-- row of the table above and must be written as NULL; X = 0 is not a split
-- either, it is the first row. A shape that could say the same fact two ways is
-- a shape whose sums disagree eventually.
--
-- ---------------------------------------------------------------------
-- Why the constraint does NOT require an item to be present
-- ---------------------------------------------------------------------
-- It is tempting to add `AND wishlist_item_id IS NOT NULL`, and it would be
-- wrong. V41:43 declares the FK `ON DELETE SET NULL`, so deleting a wish-list
-- item nulls the earmark on every gift towards it — and with that clause the
-- delete would fail on a constraint about donations, which is a very confusing
-- way for a temple to be told it may not delete an item. Left off, an orphaned
-- applied amount is simply inert: every sum below is keyed on
-- `wishlist_item_id = i.id`, so a row with no earmark counts towards no item,
-- which is exactly right.
--
-- ---------------------------------------------------------------------
-- No backfill, and no index
-- ---------------------------------------------------------------------
-- Migrations here run subject to RLS (V45/V46/V48 were bought at the price of
-- learning that), so a cross-tenant UPDATE silently matches nothing and reports
-- success. There is nothing to backfill: NULL already means "the whole gift went
-- to the item", which is true of every existing row by construction, because
-- until this migration no other outcome could be recorded. That is the same
-- argument V104 made for `voided_at`, and it is why the column is nullable
-- rather than NOT NULL with a default.
--
-- No index: the column is read only through sums already narrowed to one item
-- by `donations (wishlist_item_id)`, and a split is the rarest thing this table
-- records.
--
-- ---------------------------------------------------------------------
-- Who sums this table, asked before writing rather than after
-- ---------------------------------------------------------------------
-- README lesson 1: `SUM(amount_inr)` goes on compiling perfectly when the
-- meaning of a row changes underneath it, and this migration changes the meaning
-- of a row. Three sums treat `amount_inr` as "money towards the item" and all
-- three become `COALESCE(wishlist_applied_inr, amount_inr)` in this task:
--
--   * WishlistService's `paid_inr` — the progress bar and the remaining figure;
--   * WishlistService.markFulfilledIfComplete — whether the item is bought;
--   * MonetaryDonationService.completedAmount — the checkout cap and the
--     capture-time decision this migration exists for.
--
-- Everything else that sums this table sums whole payments — the period tiles,
-- the Form 10BD dataset, the ledger — and those are correct unchanged, because
-- `amount_inr` still means what the donor paid.
-- =====================================================================

ALTER TABLE donations
    ADD COLUMN wishlist_applied_inr NUMERIC(12, 2),

    ADD CONSTRAINT donations_wishlist_split_shape CHECK (
        wishlist_applied_inr IS NULL
        OR (amount_inr IS NOT NULL
            AND wishlist_applied_inr > 0
            AND wishlist_applied_inr < amount_inr));

COMMENT ON COLUMN donations.wishlist_applied_inr IS
    'How much of this payment went to the wish-list item it names, when only part of it could (T-081). NULL means all of it, which is every gift that fitted. The rest — amount_inr less this — went to general funds. amount_inr is untouched: it is still the one payment the donor made and the one figure an 80G receipt reports.';
