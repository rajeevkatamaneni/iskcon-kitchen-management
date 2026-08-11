-- =====================================================================
-- V40 — Vendor invoice payments (E7-S8)
--
-- The temple pays vendors outside the app (bank/UPI/cheque/cash) and records it
-- here — this system never moves money out. Each payment is an immutable,
-- append-only entry (a correction is a compensating negative entry, like the
-- stock ledger); an invoice tracks its paid-to-date as the sum and flips PAID
-- when that reaches the invoiced amount. Overpayment is refused.
-- =====================================================================

CREATE TABLE invoice_payments (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    invoice_id   UUID        NOT NULL REFERENCES vendor_invoices(id) ON DELETE RESTRICT,
    paid_on      DATE        NOT NULL,
    -- Positive for a payment; negative for a compensating correction of an earlier one.
    amount       NUMERIC(12, 2) NOT NULL,
    method       TEXT        NOT NULL,
    reference    TEXT,
    note         TEXT,

    recorded_by  UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT invoice_payments_amount_nonzero CHECK (amount <> 0),
    CONSTRAINT invoice_payments_method_valid
        CHECK (method IN ('BANK_TRANSFER', 'UPI', 'CHEQUE', 'CASH'))
);

CREATE INDEX invoice_payments_by_invoice ON invoice_payments (tenant_id, invoice_id, paid_on);
SELECT enable_tenant_rls('invoice_payments');
SELECT make_append_only('invoice_payments');
