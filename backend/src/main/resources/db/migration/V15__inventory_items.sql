-- =====================================================================
-- V15 — Consumable inventory items (E3-S1)
--
-- A consumable a temple actively tracks in its store — one row per ingredient
-- (1:1). The row is management metadata only: where it lives, and the level at
-- which to reorder it. It deliberately holds NO stock quantity. Stock is
-- DERIVED from the stock_movements ledger (V14); a level stored here could
-- drift from the ledger, and there would be two answers to "how much do we
-- have". There is only ever one: the sum of the movements.
--
-- Deferred to E5 (vendors): a preferred_vendor reference. Vendors do not exist
-- until Epic 5, and a foreign key to a table that isn't there — or a bare UUID
-- nothing can populate — would be a dead column for a whole epic. It arrives in
-- a one-line migration alongside the vendor master, where reorder suggestions
-- (E5-S2) actually consume it.
-- =====================================================================

CREATE TABLE inventory_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- The consumable this item tracks. 1:1 — a second item for the same
    -- ingredient would split its stock into two half-answers.
    ingredient_id   UUID        NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,

    -- Where it lives (main kitchen, Deity kitchen store, cold room…). A simple
    -- tenant label, not a warehouse hierarchy. Null = the tenant's default store.
    storage_location TEXT,

    -- Reorder below this, expressed in the ingredient's canonical unit. Null =
    -- no threshold set, so no low-stock alert (E3-S3) until the temple sets one.
    reorder_threshold NUMERIC(14, 3),

    notes           TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT inventory_items_threshold_nonnegative CHECK (
        reorder_threshold IS NULL OR reorder_threshold >= 0)
);

COMMENT ON TABLE  inventory_items IS
    'Consumables a temple tracks. Metadata only — stock is the sum of stock_movements, never stored here.';
COMMENT ON COLUMN inventory_items.reorder_threshold IS
    'In the ingredient canonical unit. Null means no low-stock alert until set (E3-S3).';

-- One tracked item per ingredient per temple.
CREATE UNIQUE INDEX inventory_items_ingredient_per_tenant ON inventory_items (tenant_id, ingredient_id);

CREATE INDEX inventory_items_tenant_location ON inventory_items (tenant_id, storage_location);

SELECT enable_tenant_rls('inventory_items');
