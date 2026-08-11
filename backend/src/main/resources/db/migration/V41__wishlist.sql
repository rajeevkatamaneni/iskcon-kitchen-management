-- =====================================================================
-- V41 — Wish list (E7-S5)
--
-- Concrete needs a devotee can fund: a title, image, price, category, and a
-- wanted quantity (multi-quantity items like "rice sack ×10" are supported). A
-- sponsorship (E7-S6) is a donation that references the item and carries the
-- number of units sponsored; when the sponsored units reach the wanted quantity
-- the item flips FULFILLED, stays visible briefly (tenant-config days), then
-- auto-archives. Manual sort controls the public order.
-- =====================================================================

CREATE TABLE wishlist_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    title           TEXT        NOT NULL,
    description     TEXT,
    image_ref       TEXT,
    price_inr       NUMERIC(12, 2) NOT NULL,
    category        TEXT        NOT NULL,
    quantity_wanted INTEGER     NOT NULL DEFAULT 1,
    sort_order      INTEGER     NOT NULL DEFAULT 0,
    status          TEXT        NOT NULL DEFAULT 'ACTIVE',
    note            TEXT,
    fulfilled_at    TIMESTAMPTZ,

    created_by      UUID        REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT wishlist_price_positive CHECK (price_inr > 0),
    CONSTRAINT wishlist_quantity_positive CHECK (quantity_wanted > 0),
    CONSTRAINT wishlist_category_valid CHECK (category IN ('CONSUMABLE', 'EQUIPMENT', 'OTHER')),
    CONSTRAINT wishlist_status_valid CHECK (status IN ('ACTIVE', 'FULFILLED', 'ARCHIVED'))
);

CREATE INDEX wishlist_tenant_status ON wishlist_items (tenant_id, status, sort_order);
SELECT enable_tenant_rls('wishlist_items');

-- The units a wish-list sponsorship covered (E7-S6); null for non-wishlist donations.
ALTER TABLE donations ADD COLUMN wishlist_quantity INTEGER;
ALTER TABLE donations ADD CONSTRAINT donations_wishlist_item_fk
    FOREIGN KEY (wishlist_item_id) REFERENCES wishlist_items(id) ON DELETE SET NULL;

-- How long a FULFILLED item stays visible before it auto-archives (E7-S5).
ALTER TABLE tenant_settings ADD COLUMN wishlist_fulfilled_visible_days INTEGER NOT NULL DEFAULT 7;
