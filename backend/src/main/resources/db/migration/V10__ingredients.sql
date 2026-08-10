-- =====================================================================
-- V10 — Ingredient master (E2-S1)
--
-- A single per-tenant catalogue of ingredients. Recipes (E2), inventory (E3),
-- and orders (E5) all reference it, so they speak one ingredient language.
--
-- Tenant-owned like everything else: each temple curates its own catalogue
-- (no shared global list in release 1). The sattvic-prohibited flag carries
-- religious weight — onion, garlic, mushroom, egg and the like — and only a
-- Temple Admin may set it (enforced in the service, MANAGE_SATTVIC_POLICY);
-- the flag is seeded pre-set for the common cases on provisioning.
-- =====================================================================

CREATE TABLE ingredients (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    name            TEXT        NOT NULL,

    -- Free text, tenant-curated (Grains, Pulses, Vegetables, Dairy, Spices…).
    -- Not a CHECK: temples add their own, and a constraint would make each a migration.
    category        TEXT        NOT NULL,

    -- The unit this ingredient is measured in everywhere. Fixed vocabulary, per
    -- RM 2019: kilograms, grams, litres, millilitres, pieces.
    canonical_unit  TEXT        NOT NULL,

    -- Religious compliance. Default false; set true only by a Temple Admin.
    is_sattvic_prohibited BOOLEAN NOT NULL DEFAULT false,

    -- Alternate names ("Raw Rice" for "Rice"), so typeahead and later dedup can
    -- match what a cook actually types. Searched alongside name.
    aliases         TEXT[]      NOT NULL DEFAULT '{}',

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ingredients_unit_valid CHECK (
        canonical_unit IN ('KG', 'GM', 'L', 'ML', 'PIECES')),
    CONSTRAINT ingredients_name_present CHECK (length(name) > 0)
);

COMMENT ON TABLE  ingredients IS 'Per-tenant ingredient catalogue. The shared vocabulary for recipes, inventory, and orders.';
COMMENT ON COLUMN ingredients.is_sattvic_prohibited IS 'Religious compliance flag; only a Temple Admin may change it (MANAGE_SATTVIC_POLICY), and the change is audited.';

-- One name per temple. lower() so "Rice" and "rice" collide; this is also the
-- index a name prefix typeahead uses.
CREATE UNIQUE INDEX ingredients_name_per_tenant ON ingredients (tenant_id, lower(name));

CREATE INDEX ingredients_tenant_category ON ingredients (tenant_id, category);

-- Same tenant isolation as every other tenant-owned table.
SELECT enable_tenant_rls('ingredients');
