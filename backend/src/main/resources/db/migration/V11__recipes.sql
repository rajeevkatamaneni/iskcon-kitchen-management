-- =====================================================================
-- V11 — Recipes (E2-S2)
--
-- Recipes, their ingredient lines, and the category list they hang off.
-- Modelled on the temple's real RM 2019 workbook: a recipe has a base yield
-- (servings or litres — both occur in the workbook), ingredient rows with a
-- quantity and unit, method steps, and a category (its sheets: Beverages,
-- Breakfast, Rice, Dal, Sweets, Ekadashi…). All tenant-owned with RLS.
--
-- Recipes archive rather than delete: a meal plan (E4) may reference one, and
-- history must stay renderable. `version` bumps on edit so translation caches
-- (E2-S6) can invalidate. The sattvic-override columns are filled by E2-S4.
-- =====================================================================

CREATE TABLE recipe_categories (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    name            TEXT        NOT NULL,

    -- The Ekadashi category is fasting-compatible; E4 consumes this to know a
    -- recipe is allowed on a fasting day.
    fasting_compatible BOOLEAN  NOT NULL DEFAULT false,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT recipe_categories_name_present CHECK (length(name) > 0)
);

CREATE UNIQUE INDEX recipe_categories_name_per_tenant
    ON recipe_categories (tenant_id, lower(name));

SELECT enable_tenant_rls('recipe_categories');

-- ---------------------------------------------------------------------

CREATE TABLE recipes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    name            TEXT        NOT NULL,
    category_id     UUID        NOT NULL REFERENCES recipe_categories(id) ON DELETE RESTRICT,

    -- The recipe's own yield, the denominator for scaling (E2-S3).
    base_yield_qty  NUMERIC(12, 3) NOT NULL,
    base_yield_unit TEXT        NOT NULL,

    method          TEXT,
    notes           TEXT,
    region_tag      TEXT,

    status          TEXT        NOT NULL DEFAULT 'ACTIVE',

    -- Filled by E2-S4 when a Temple Admin overrides sattvic enforcement to save a
    -- recipe containing a prohibited ingredient. Null means no override.
    sattvic_override_reason TEXT,

    -- Bumped on every edit; translation caches key on (recipe, version) so an edit
    -- invalidates them (E2-S6).
    version         INTEGER     NOT NULL DEFAULT 1,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT recipes_name_present CHECK (length(name) > 0),
    CONSTRAINT recipes_yield_positive CHECK (base_yield_qty > 0),
    CONSTRAINT recipes_yield_unit_valid CHECK (base_yield_unit IN ('SERVINGS', 'LITRES')),
    CONSTRAINT recipes_status_valid CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

-- Name unique among a temple's *active* recipes; an archived one may share a name
-- with a new active recipe of the same dish.
CREATE UNIQUE INDEX recipes_active_name_per_tenant
    ON recipes (tenant_id, lower(name)) WHERE status = 'ACTIVE';

CREATE INDEX recipes_tenant_category ON recipes (tenant_id, category_id);

SELECT enable_tenant_rls('recipes');

-- ---------------------------------------------------------------------

CREATE TABLE recipe_ingredients (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Carried for RLS, like every tenant-owned table; it always equals the
    -- recipe's and the ingredient's tenant.
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- Deleting a recipe removes its lines; an ingredient in use cannot be deleted
    -- (RESTRICT), which is what keeps the catalogue honest.
    recipe_id       UUID        NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    ingredient_id   UUID        NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,

    quantity        NUMERIC(12, 3) NOT NULL,
    unit            TEXT        NOT NULL,

    -- Preserves the cook's ordering of the ingredient list.
    line_order      INTEGER     NOT NULL,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT recipe_ingredients_quantity_positive CHECK (quantity > 0),
    CONSTRAINT recipe_ingredients_unit_valid CHECK (unit IN ('KG', 'GM', 'L', 'ML', 'PIECES'))
);

CREATE INDEX recipe_ingredients_recipe ON recipe_ingredients (recipe_id, line_order);

-- "What can we make with X" (E2-S2) reads this the other way, by ingredient.
CREATE INDEX recipe_ingredients_ingredient ON recipe_ingredients (tenant_id, ingredient_id);

SELECT enable_tenant_rls('recipe_ingredients');
