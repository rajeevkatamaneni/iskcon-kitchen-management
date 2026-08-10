-- =====================================================================
-- V28 — Vendor invoices (E5-S8)
--
-- Staff record a vendor's invoice so Payments (E7-S9) has a clean queue of what
-- the temple owes. Most invoices reference a PO; a cash-market purchase with no PO
-- is the same entity with a "direct" flag and a free-text description instead.
-- Payment execution is E7 — here the status only ever starts PENDING.
--
-- Amount vs received-quantity expectation is surfaced as an informational variance
-- at read time (never stored, never blocking): the temple negotiates in the real
-- world, so a mismatch is shown, not enforced.
-- =====================================================================

CREATE TABLE vendor_invoices (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    vendor_id         UUID        NOT NULL REFERENCES vendors(id) ON DELETE RESTRICT,
    -- Null for a direct (no-PO) invoice; the CHECK below ties this to the flag.
    po_id             UUID        REFERENCES purchase_orders(id) ON DELETE RESTRICT,
    direct            BOOLEAN     NOT NULL DEFAULT false,
    -- Required for a direct invoice, describing what was bought; null otherwise.
    description       TEXT,

    invoice_number    TEXT        NOT NULL,
    invoice_date      DATE        NOT NULL,
    amount            NUMERIC(12, 2) NOT NULL,
    due_date          DATE,

    -- Scanned invoice copy in object storage (GCS object name); optional.
    scan_ref          TEXT,

    status            TEXT        NOT NULL DEFAULT 'PENDING',

    created_by        UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT vendor_invoices_amount_positive CHECK (amount > 0),
    CONSTRAINT vendor_invoices_status_valid CHECK (status IN ('PENDING', 'PAID')),
    -- A direct invoice has no PO and must describe itself; a PO invoice has a PO.
    CONSTRAINT vendor_invoices_direct_shape CHECK (
        (direct AND po_id IS NULL AND description IS NOT NULL)
        OR (NOT direct AND po_id IS NOT NULL))
);

COMMENT ON TABLE vendor_invoices IS
    'Vendor invoices captured against a PO (or direct, no-PO) for the payment queue (E5-S8).';

CREATE INDEX vendor_invoices_tenant_status ON vendor_invoices (tenant_id, status, due_date);
CREATE INDEX vendor_invoices_tenant_vendor ON vendor_invoices (tenant_id, vendor_id);
CREATE INDEX vendor_invoices_po ON vendor_invoices (po_id);
-- Powers the soft duplicate-number warning (vendors reuse numbering imperfectly, so this
-- is an index for the lookup, not a unique constraint).
CREATE INDEX vendor_invoices_vendor_number ON vendor_invoices (tenant_id, vendor_id, invoice_number);

SELECT enable_tenant_rls('vendor_invoices');
