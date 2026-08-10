-- =====================================================================
-- V22 — Meal planning (E4-S4)
--
-- The week's cooking, by date and slot, with a day-type context (regular /
-- weekend / festival / outside catering) that scales it right and makes it
-- visible. A planned meal references a recipe and a target; marking it cooked
-- draws the ingredients down through the stock ledger (E3-S6), so the plan is
-- the bridge between the calendar and inventory.
-- =====================================================================

-- The tenant's meal slots (Lunch, Dinner, Deity Offering…) — a configurable list,
-- seeded on provisioning and editable by the temple.
CREATE TABLE meal_slots (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    name        TEXT        NOT NULL,
    sort_order  INTEGER     NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT meal_slots_name_present CHECK (length(name) > 0)
);

CREATE UNIQUE INDEX meal_slots_name_per_tenant ON meal_slots (tenant_id, lower(name));

SELECT enable_tenant_rls('meal_slots');

CREATE TABLE meal_plans (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    plan_date       DATE        NOT NULL,
    slot            TEXT        NOT NULL,

    recipe_id       UUID        NOT NULL REFERENCES recipes(id) ON DELETE RESTRICT,
    -- Target yield in the recipe's own yield unit (usually servings). Scales the recipe (E2-S3)
    -- and the consumption (E3-S6).
    target_servings NUMERIC(12, 3) NOT NULL,

    -- regular / weekend / festival / catering. Auto-suggested at creation, overridable.
    day_type        TEXT        NOT NULL,
    -- The festival this plan is for, denormalized so removing an occasion never orphans the plan.
    occasion_name   TEXT,

    status          TEXT        NOT NULL DEFAULT 'PLANNED',

    -- Catering context (day_type = CATERING): who it's for and where it goes.
    client_name     TEXT,
    client_contact  TEXT,
    venue           TEXT,
    delivery_time   TIMESTAMPTZ,

    cooked_at       TIMESTAMPTZ,
    created_by      UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT meal_plans_servings_positive CHECK (target_servings > 0),
    CONSTRAINT meal_plans_daytype_valid CHECK (
        day_type IN ('REGULAR', 'WEEKEND', 'FESTIVAL', 'CATERING')),
    CONSTRAINT meal_plans_status_valid CHECK (status IN ('PLANNED', 'COOKED', 'CANCELLED')),
    -- A catering commitment must name its client.
    CONSTRAINT meal_plans_catering_has_client CHECK (
        day_type <> 'CATERING' OR client_name IS NOT NULL)
);

COMMENT ON TABLE meal_plans IS
    'Planned meals by date and slot (E4-S4). Marking one cooked draws stock via the ledger (E3-S6).';

CREATE INDEX meal_plans_tenant_date ON meal_plans (tenant_id, plan_date);
CREATE INDEX meal_plans_tenant_status ON meal_plans (tenant_id, status);
CREATE INDEX meal_plans_tenant_catering ON meal_plans (tenant_id, delivery_time) WHERE day_type = 'CATERING';

SELECT enable_tenant_rls('meal_plans');
