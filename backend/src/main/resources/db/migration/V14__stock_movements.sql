-- =====================================================================
-- V14 — Stock movements ledger (E3-S2)
--
-- Inventory is DERIVED from this table, never edited directly (SYSTEM_DESIGN.md
-- §5). Every stock change — a receipt, a donation, cooking a meal, an
-- adjustment — is one immutable, signed row. Current stock is the sum; batch
-- stock is the sum per batch. This gives audit-friendly inventory by
-- construction and is the base the Phase 2 food-safety log layers onto (batch,
-- expiry, and received-date fields exist from day one, per the locked decision).
--
-- Append-only, like audit_events: a mistake is corrected by a compensating
-- movement that references the original, never by editing history.
-- =====================================================================

CREATE TABLE stock_movements (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    ingredient_id   UUID        NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,

    -- Which store-room (Deity kitchen, main kitchen, catering…). A simple
    -- tenant-scoped label in release 1, not a warehouse hierarchy. Null = default.
    storage_location TEXT,

    -- Groups the movements of one physical batch. A receipt/donation/positive
    -- adjustment mints a new batch_id; consumption and corrections reference an
    -- existing one. Batch stock = sum(quantity) for a batch_id.
    batch_id        UUID        NOT NULL,

    -- Signed: positive adds stock, negative removes it. Never zero.
    quantity        NUMERIC(14, 3) NOT NULL,
    unit            TEXT        NOT NULL,

    movement_type   TEXT        NOT NULL,

    -- Set on the batch-establishing (positive) movement; carried for the
    -- food-safety log and FEFO ordering.
    expiry_date     DATE,
    received_date   DATE,

    -- Adjustments only: why (E3-S7). Powers the Phase 2 waste report.
    reason_category TEXT,

    -- What this movement is about: a purchase order (E5), a meal plan (E3-S6/E4),
    -- a donation (E3-S5), or the original movement it corrects (CORRECTION).
    reference_type  TEXT,
    reference_id    UUID,

    note            TEXT,

    actor_user_id   UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT stock_movements_quantity_nonzero CHECK (quantity <> 0),
    CONSTRAINT stock_movements_unit_valid CHECK (unit IN ('KG', 'GM', 'L', 'ML', 'PIECES')),
    CONSTRAINT stock_movements_type_valid CHECK (
        movement_type IN ('PO_RECEIPT', 'DONATION_IN_KIND', 'CONSUMPTION', 'ADJUSTMENT')),
    CONSTRAINT stock_movements_reason_valid CHECK (
        reason_category IS NULL OR reason_category IN (
            'SPOILAGE', 'DAMAGE', 'COUNT_CORRECTION', 'WASTE', 'OTHER')),
    CONSTRAINT stock_movements_reference_valid CHECK (
        reference_type IS NULL OR reference_type IN (
            'PURCHASE_ORDER', 'MEAL_PLAN', 'DONATION', 'CORRECTION'))
);

COMMENT ON TABLE stock_movements IS
    'Append-only ledger. Inventory is the sum of these rows; nothing sets stock directly.';

CREATE INDEX stock_movements_tenant_ingredient ON stock_movements (tenant_id, ingredient_id, created_at DESC);
CREATE INDEX stock_movements_tenant_batch ON stock_movements (tenant_id, batch_id);
CREATE INDEX stock_movements_tenant_type ON stock_movements (tenant_id, movement_type, created_at DESC);
CREATE INDEX stock_movements_reference ON stock_movements (reference_type, reference_id);

SELECT enable_tenant_rls('stock_movements');
SELECT make_append_only('stock_movements');
