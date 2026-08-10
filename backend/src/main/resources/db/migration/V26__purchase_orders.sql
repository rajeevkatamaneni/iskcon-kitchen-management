-- =====================================================================
-- V26 — Purchase orders (E5-S3)
--
-- Approved order lines become per-vendor purchase orders with a tracked
-- lifecycle: DRAFT → SENT → PARTIALLY_RECEIVED → RECEIVED / CANCELLED. What was
-- asked for, from whom, by when is always unambiguous. Each PO has a per-tenant
-- sequential number and an append-only activity trail.
-- =====================================================================

-- Per-tenant PO counter. The atomic increment (INSERT ... ON CONFLICT DO UPDATE
-- ... RETURNING) row-locks per tenant, so numbers are never duplicated even under
-- concurrent creation; a rolled-back creation simply leaves a gap (gap-tolerant).
CREATE TABLE po_sequence (
    tenant_id   UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE RESTRICT,
    last_number INTEGER NOT NULL DEFAULT 0
);
SELECT enable_tenant_rls('po_sequence');

CREATE TABLE purchase_orders (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    po_number         TEXT        NOT NULL,
    vendor_id         UUID        NOT NULL REFERENCES vendors(id) ON DELETE RESTRICT,

    status            TEXT        NOT NULL DEFAULT 'DRAFT',
    order_date        DATE        NOT NULL DEFAULT CURRENT_DATE,
    needed_by         DATE,
    delivery_location TEXT,
    notes             TEXT,
    cancel_reason     TEXT,

    sent_at           TIMESTAMPTZ,
    cancelled_at      TIMESTAMPTZ,

    created_by        UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT purchase_orders_status_valid CHECK (
        status IN ('DRAFT', 'SENT', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED'))
);

COMMENT ON TABLE purchase_orders IS 'Per-vendor purchase orders with a tracked lifecycle (E5-S3).';

CREATE UNIQUE INDEX purchase_orders_number_per_tenant ON purchase_orders (tenant_id, po_number);
CREATE INDEX purchase_orders_tenant_status ON purchase_orders (tenant_id, status);
CREATE INDEX purchase_orders_tenant_vendor ON purchase_orders (tenant_id, vendor_id);

SELECT enable_tenant_rls('purchase_orders');

CREATE TABLE purchase_order_lines (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    po_id           UUID        NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,

    ingredient_id   UUID        NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,
    quantity        NUMERIC(14, 3) NOT NULL,
    unit            TEXT        NOT NULL,
    expected_price  NUMERIC(12, 2),
    line_order      INTEGER     NOT NULL DEFAULT 0,

    CONSTRAINT po_lines_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX po_lines_po ON purchase_order_lines (po_id);
SELECT enable_tenant_rls('purchase_order_lines');

-- The append-only activity trail per PO, readable by kitchen staff (MANAGE_PURCHASE_ORDERS) —
-- created, edited, sent, cancelled, received, delivery attempts.
CREATE TABLE po_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    po_id           UUID        NOT NULL REFERENCES purchase_orders(id) ON DELETE RESTRICT,

    event_type      TEXT        NOT NULL,
    detail          TEXT,
    actor_user_id   UUID        REFERENCES users(id) ON DELETE RESTRICT,
    actor_name      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX po_events_po ON po_events (tenant_id, po_id, created_at);
SELECT enable_tenant_rls('po_events');
SELECT make_append_only('po_events');
