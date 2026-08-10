-- =====================================================================
-- V25 — Auto-generated order list (E5-S2)
--
-- A per-tenant draft procurement list, one line per ingredient, merged from two
-- demand streams: the meal-plan shortfall feed (E4-S5) and below-threshold
-- inventory topped up to its reorder level × a safety factor (E3-S3). It is
-- regenerated nightly and on demand, but human edits survive: a line the staff
-- has touched (its quantity, vendor, or inclusion) is preserved across a
-- regeneration rather than overwritten.
-- =====================================================================

CREATE TABLE order_list_lines (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    ingredient_id   UUID        NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,

    -- The suggested purchase quantity, in the ingredient's canonical unit, rounded up.
    suggested_qty   NUMERIC(14, 3) NOT NULL,
    unit            TEXT        NOT NULL,
    -- Current stock at generation time, for the reviewer's context.
    current_stock   NUMERIC(14, 3) NOT NULL DEFAULT 0,
    -- Earliest demanding meal minus the lead buffer; null if only a threshold drove the line.
    needed_by       DATE,

    suggested_vendor_id UUID    REFERENCES vendors(id) ON DELETE SET NULL,

    -- Which streams drove it: { "shortfall": n, "thresholdTopUp": n } in canonical units.
    provenance      JSONB       NOT NULL DEFAULT '{}',

    included        BOOLEAN     NOT NULL DEFAULT true,
    -- True once a human edits the line; a regeneration then leaves it untouched.
    edited          BOOLEAN     NOT NULL DEFAULT false,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT order_list_lines_qty_positive CHECK (suggested_qty > 0)
);

COMMENT ON TABLE order_list_lines IS
    'Draft order list merged from shortfall + thresholds (E5-S2); regenerated but edit-preserving.';

CREATE UNIQUE INDEX order_list_lines_ingredient_per_tenant ON order_list_lines (tenant_id, ingredient_id);

SELECT enable_tenant_rls('order_list_lines');
