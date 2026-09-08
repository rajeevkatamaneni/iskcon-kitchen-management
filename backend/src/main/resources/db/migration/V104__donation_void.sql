-- =====================================================================
-- V104 — A gift recorded wrongly can be struck (T-012)
--
-- `DonationController` was POST-only for hand-recorded gifts and the ledger
-- read-only throughout, so a gift entered twice, or against the wrong donor,
-- permanently inflated the figures the temple reports under 80G — and where it
-- was in kind it had inflated the store-room in the same transaction, which
-- could not be undone either.
--
-- ---------------------------------------------------------------------
-- Two tables, two different corrections, and the difference is the point
-- ---------------------------------------------------------------------
-- Striking one gift touches two records, and they are corrected in opposite
-- ways on purpose:
--
--   * `donations` is **marked**. It is not append-only (deliberately — no
--     make_append_only('donations') has ever been called on it), and it is read
--     one row at a time by an administrator answering "what did Govind Das give
--     us in August". A gift of ₹5,000 sitting next to a gift of -₹5,000 answers
--     that question badly twice over, and an accountant reconciling against a
--     receipt book would have to know to pair them up. So the row stays, stamped
--     with who struck it, when, and why, and every 80G figure ignores it. This
--     is the argument V63 made for `staff_payments`, and it holds for the same
--     reason: a ledger somebody reads a single row of is corrected by a mark.
--
--   * `stock_movements` is **compensated**, never marked, because it is
--     append-only (V14:75, V49:70) and its only consumer is a sum — current
--     stock is the sum of a consumable's rows (SYSTEM_DESIGN §5). Nothing there
--     is read one row at a time to answer what somebody gave, so the reverse
--     entry that nets a batch back to where it was is exactly right, and
--     `StockMovementService.compensate` already writes it.
--
-- Both halves of a void commit in one transaction, so the two records can never
-- drift apart: the mark below and the compensating movements land together or
-- not at all.
--
-- ---------------------------------------------------------------------
-- No backfill, and that is worth saying rather than leaving to inference
-- ---------------------------------------------------------------------
-- Migrations in this project run subject to RLS (V45/V46/V48 were bought at the
-- price of learning that), so a backfill here would have to adopt each tenant in
-- turn — a cross-tenant UPDATE matches nothing and says nothing about it. There
-- is no backfill to run: NULL already means "not voided", which is what every
-- existing row is, so the columns are added and nothing is written.
--
-- No index either. The period summary gains `AND d.voided_at IS NULL` to an
-- existing scan already narrowed by tenant, status and date
-- (donations_tenant_date); a void is a rare correction, so the predicate removes
-- a handful of rows from a set the planner has already found, and a partial
-- index would be a second thing to maintain for no measurable gain.
-- =====================================================================

ALTER TABLE donations
    ADD COLUMN voided_at   TIMESTAMPTZ,
    ADD COLUMN voided_by   UUID REFERENCES users(id) ON DELETE RESTRICT,
    ADD COLUMN void_reason TEXT,

    -- The three facts are one act. A struck row that cannot say who struck it is
    -- worth less than no mark at all, because it looks like a record and answers
    -- nothing.
    ADD CONSTRAINT donations_void_attributed CHECK (
        (voided_at IS NULL) = (voided_by IS NULL)),

    -- And a reason is demanded rather than offered. This is the one correction in
    -- the product that changes what the temple tells the tax authority it
    -- received; the next person to read the row is entitled to know why it does
    -- not count. Blank is refused here as well as at the API, so a reason cannot
    -- be reduced to a space bar by some later write path.
    ADD CONSTRAINT donations_void_explained CHECK (
        (voided_at IS NULL AND void_reason IS NULL)
        OR (voided_at IS NOT NULL AND btrim(void_reason) <> ''));

COMMENT ON COLUMN donations.voided_at IS
    'When this gift was struck as wrongly recorded (T-012). Null for a gift that stands. A voided gift stays in the ledger, marked, and is excluded from every 80G figure.';
COMMENT ON COLUMN donations.voided_by IS
    'Who struck it — a Temple Admin, the only role holding VOID_DONATION (D-4).';
COMMENT ON COLUMN donations.void_reason IS
    'Why it was struck, in the words of whoever struck it. Required, and never blank: it is the only account of why an 80G figure moved.';
