-- =====================================================================
-- V103 — A bill can be withdrawn or reduced, and a payment undone (T-010)
--
-- Until now a vendor invoice could go PENDING -> PAID and nowhere else, and a
-- payment could only ever be added. So a bill raised in error stayed in the
-- payables queue for ever, a bill the vendor later reduced could never be
-- settled (its status is decided by comparing payments against the full
-- amount, which nobody would ever pay), and a bounced cheque was permanent.
-- Three acts are added, and they are deliberately three rather than one:
--
--   VOID    — the bill was never owed. Terminal. It leaves the pay cycle
--             entirely and every figure that sums invoices must skip it.
--   CREDIT  — the bill was owed and is now owed less. It stays in the cycle,
--             which is exactly why a credit is NOT a status: an invoice can be
--             credited and then paid, and both facts have to survive.
--   REVERSE — a payment did not happen after all.
--
-- A temple arguing with a vendor a year later needs "never owed" and "owed
-- less" to be different answers rather than one word, which is the whole
-- reason the void mark and credited_amount live in different columns.
--
-- Nothing here deletes anything, and nothing here backfills anything: every
-- column added is either nullable or carries a DEFAULT, so there is no DML for
-- row-level security to scope. That is worth saying out loud, because a
-- migration in this project runs subject to RLS and a cross-tenant UPDATE
-- would match no rows and fail silently rather than loudly.
-- =====================================================================

-- ---------------------------------------------------------------------
-- The third status.
--
-- Replaced rather than dropped: a status column with no CHECK on it is a
-- column that will eventually hold a typo, and the constraint is the only
-- thing standing between 'VOIDED' and 'VOID' meaning two different things.
-- ---------------------------------------------------------------------
ALTER TABLE vendor_invoices DROP CONSTRAINT vendor_invoices_status_valid;

ALTER TABLE vendor_invoices
    ADD CONSTRAINT vendor_invoices_status_valid
        CHECK (status IN ('PENDING', 'PAID', 'VOIDED'));

-- ---------------------------------------------------------------------
-- The void mark, and the credit total.
--
-- vendor_invoices is not append-only (it has carried updated_at and a mutable
-- status since V28), so a void is a mark on the row in the way
-- StaffPayController's void is — the row stays, stamped with who struck it and
-- why. That is the opposite decision to invoice_payments below, and the two
-- are different on purpose rather than by accident: see V63's own long note on
-- when a mark beats a compensating entry. The test is who reads the table. An
-- invoice is read one row at a time by somebody asking "what did we owe on
-- this bill", so a struck bill must say so on its face; the payment ledger is
-- read as a sum, and a sum corrects itself with a negative entry.
--
-- credited_amount is a running total rather than a table of credit notes. Each
-- individual credit — its amount, its reason, who recorded it and when — is
-- kept by the audit trail under INVOICE_CREDITED, which is this project's
-- record of who did what and why. A second table would carry the same facts
-- one layer further from where anybody reads them, and nothing in the product
-- lists credit notes individually.
-- ---------------------------------------------------------------------
ALTER TABLE vendor_invoices
    ADD COLUMN voided_at       TIMESTAMPTZ,
    ADD COLUMN voided_by       UUID REFERENCES users(id) ON DELETE RESTRICT,
    ADD COLUMN void_reason     TEXT,

    -- Never null. "No credits" and "not told" would otherwise read alike on a
    -- screen that subtracts this from the amount.
    ADD COLUMN credited_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,

    -- A credit reduces a bill; it cannot exceed it, and it cannot be negative
    -- (that would be a second invoice wearing a credit note's clothes).
    ADD CONSTRAINT vendor_invoices_credited_range
        CHECK (credited_amount >= 0 AND credited_amount <= amount),

    -- The status and the mark say the same thing or the row is wrong. A
    -- VOIDED invoice that cannot say who struck it or why is exactly the
    -- record this feature exists to prevent.
    ADD CONSTRAINT vendor_invoices_void_shape CHECK (
        (status = 'VOIDED' AND voided_at IS NOT NULL AND voided_by IS NOT NULL
             AND void_reason IS NOT NULL)
        OR (status <> 'VOIDED' AND voided_at IS NULL AND voided_by IS NULL
             AND void_reason IS NULL));

COMMENT ON COLUMN vendor_invoices.voided_at IS
    'When the bill was struck as never owed. Null on every invoice that still stands.';
COMMENT ON COLUMN vendor_invoices.void_reason IS
    'Why it was struck, in the words of whoever struck it. Required with the mark, never overwritten.';
COMMENT ON COLUMN vendor_invoices.credited_amount IS
    'Total of the credit notes recorded against this bill. What is owed is amount - credited_amount; each individual credit is in the audit trail under INVOICE_CREDITED.';

-- ---------------------------------------------------------------------
-- Reversing a payment.
--
-- No mark here, and no new "voided" column, because there could not be one:
-- invoice_payments is append-only (V40:33, re-registered V49:75 and V50:57),
-- which since V49 is a BEFORE UPDATE OR DELETE trigger raising 42501 with the
-- hint "Correct an entry by adding a compensating one; history is never
-- edited." An UPDATE marking a payment struck is precisely what that refuses.
--
-- V40 designed for this in 2025 and needs no new column to record the money:
-- amount is signed, its comment already reads "Positive for a payment;
-- negative for a compensating correction of an earlier one", and
-- invoice_payments_amount_nonzero permits negatives. So the two columns added
-- here carry only what the ledger could not already say — WHICH payment this
-- row undoes, and why.
-- ---------------------------------------------------------------------
ALTER TABLE invoice_payments
    ADD COLUMN reverses       UUID REFERENCES invoice_payments(id) ON DELETE RESTRICT,
    ADD COLUMN reverse_reason TEXT,

    -- Three facts in one constraint, because they are one fact: a row either
    -- is a reversal — negative, naming the entry it undoes and why, and not
    -- itself — or it is an ordinary payment and says none of those things.
    -- Note what this does not forbid: the hand-entered compensating negative
    -- that has been legal since V40 (InvoicePaymentIT's compensatingReopens)
    -- names no payment and stays legal.
    ADD CONSTRAINT invoice_payments_reversal_shape CHECK (
        (reverses IS NULL AND reverse_reason IS NULL)
        OR (reverses IS NOT NULL AND reverse_reason IS NOT NULL
                AND amount < 0 AND reverses <> id));

-- One reversal per payment, enforced here and not only in the service. The
-- application refuses a second reversal with KMS-400133, but that guard reads
-- the table and then writes it, and two administrators pressing at once would
-- both read "not yet reversed". Nothing about the original row changes when it
-- is reversed — that is the point of an append-only ledger — so this index is
-- the only thing that can make the race lose.
CREATE UNIQUE INDEX invoice_payments_one_reversal_each
    ON invoice_payments (reverses) WHERE reverses IS NOT NULL;

COMMENT ON COLUMN invoice_payments.reverses IS
    'On a compensating row, the payment it undoes. Null on an ordinary payment. The other end (which row undid this one) is derived at read time — an append-only table cannot be told after the fact.';
COMMENT ON COLUMN invoice_payments.reverse_reason IS
    'Why the payment was undone — a bounced cheque, a mistyped amount, a payment against the wrong bill.';
