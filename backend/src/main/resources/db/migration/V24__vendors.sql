-- =====================================================================
-- V24 — Vendor management (E5-S1)
--
-- Who the temple buys from, and who sells what. Staff-managed only (no vendor
-- logins). The phone is the WhatsApp destination for purchase orders (E5-S7),
-- so it's E.164 and flagged if a send later fails. Each vendor supplies a set
-- of ingredients, optionally with a last-known price (one current price;
-- history is Phase 2), and one vendor may be marked preferred per ingredient —
-- which the order-list suggestions (E5-S2) and stock view (E3-S1) consume.
-- =====================================================================

CREATE TABLE vendors (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    name            TEXT        NOT NULL,
    contact_person  TEXT,
    phone           TEXT        NOT NULL,
    email           TEXT,
    address         TEXT,
    gstin           TEXT,
    -- Language for this vendor's PO documents (E5-S5). A code from the tenant's list; 'en' default.
    preferred_language TEXT     NOT NULL DEFAULT 'en',
    notes           TEXT,

    active          BOOLEAN     NOT NULL DEFAULT true,
    -- Cleared when a WhatsApp PO send fails, prompting a phone recheck (E5-S7).
    whatsapp_reachable BOOLEAN  NOT NULL DEFAULT true,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT vendors_name_present CHECK (length(name) > 0),
    CONSTRAINT vendors_phone_e164 CHECK (phone ~ '^\+[1-9][0-9]{7,14}$')
);

COMMENT ON TABLE vendors IS 'Suppliers the temple buys from (E5-S1). Phone is the WhatsApp PO destination.';

CREATE UNIQUE INDEX vendors_name_per_tenant ON vendors (tenant_id, lower(name));
CREATE INDEX vendors_tenant_active ON vendors (tenant_id, active);

SELECT enable_tenant_rls('vendors');

CREATE TABLE vendor_supplies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    vendor_id       UUID        NOT NULL REFERENCES vendors(id) ON DELETE CASCADE,
    ingredient_id   UUID        NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,

    last_price      NUMERIC(12, 2),
    preferred       BOOLEAN     NOT NULL DEFAULT false,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT vendor_supplies_price_nonnegative CHECK (last_price IS NULL OR last_price >= 0)
);

-- One supply row per (vendor, ingredient).
CREATE UNIQUE INDEX vendor_supplies_vendor_ingredient ON vendor_supplies (vendor_id, ingredient_id);
-- At most one preferred vendor per ingredient per tenant.
CREATE UNIQUE INDEX vendor_supplies_one_preferred ON vendor_supplies (tenant_id, ingredient_id) WHERE preferred;
CREATE INDEX vendor_supplies_ingredient ON vendor_supplies (tenant_id, ingredient_id);

SELECT enable_tenant_rls('vendor_supplies');
