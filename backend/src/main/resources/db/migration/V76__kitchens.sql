-- =====================================================================
-- V76 — The kitchens a temple runs (E10-S2)
--
-- A temple is not one kitchen. It is three to five, and sometimes ten or
-- more, under one roof: the Deity kitchen, the prasadam kitchen, a
-- restaurant, a Food-for-Life kitchen, a guest-house kitchen. They share
-- one store room, and most of them will never open this application —
-- they want ingredients, not software.
--
-- ---------------------------------------------------------------------
-- Flat, and only flat
--
-- Every kitchen hangs off the tenant. There is no parent_kitchen_id and
-- no kitchen inside another kitchen: the ones a temple runs are peers,
-- and a tree nobody can name a use for is a decision every screen and
-- every query would have to keep answering. Settled with Rajeev on
-- 2026-08-30: "there is no further hierarchy like Temple -> Main Kitchen
-- -> Child Kitchens. It is just temple and a bunch of child kitchens
-- underneath it and only one of those children is marked as the main
-- kitchen."
--
-- ---------------------------------------------------------------------
-- is_main is a label; uses_meal_planner is the behaviour
--
-- They will usually agree, and they answer different questions. is_main
-- says which kitchen is the principal one, which is worth recording
-- because people ask and which changes nothing the system computes.
--
-- uses_meal_planner is the one that does work. There is one store and two
-- doors out of it — a meal recorded in the planner draws CONSUMPTION, and
-- an issued request draws ISSUE — so a kitchen doing both would take the
-- same rice off the books twice. The temple was told about the risk and
-- said it would be careful, which is not a guarantee. So the kitchen
-- declares which door it uses and the other one closes. One kitchen, one
-- door, and the double-count has nowhere to happen.
--
-- Tying the two together was the alternative and is wrong: it would
-- forbid a child kitchen that plans its own meals, and a main kitchen
-- that only draws stock, and a temple is entitled to both.
-- =====================================================================

CREATE TABLE kitchens (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    name              TEXT        NOT NULL,
    description       TEXT,
    -- Where in the temple it is, in the words the temple uses for it —
    -- "behind the Deity hall", "second floor, east wing". Free text on
    -- purpose: nobody is going to maintain a map, and a person reading a
    -- work order needs to know where to carry the sack.
    location          TEXT,

    is_main           BOOLEAN     NOT NULL DEFAULT false,
    uses_meal_planner BOOLEAN     NOT NULL DEFAULT false,

    -- Who runs it. Nullable: a temple may record the kitchen before it has
    -- decided, and a person's account may be disabled while the kitchen
    -- stays. RESTRICT rather than SET NULL so that removing a user has to
    -- reckon with the kitchens they run rather than silently orphaning them.
    in_charge_user_id UUID        REFERENCES users(id) ON DELETE RESTRICT,
    contact_phone     TEXT,

    status            TEXT        NOT NULL DEFAULT 'ACTIVE',

    created_by        UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT kitchens_name_present CHECK (length(name) > 0),
    CONSTRAINT kitchens_status_valid CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

COMMENT ON TABLE kitchens IS
    'The kitchens one temple runs (E10-S2). Flat under the tenant; exactly one may be marked main.';

COMMENT ON COLUMN kitchens.uses_meal_planner IS
    'True where this kitchen plans its meals here, so its stock leaves as CONSUMPTION and it may not raise ingredient requests. False where the ingredient request is its only door. One kitchen, one door — see V76''s header.';

CREATE UNIQUE INDEX kitchens_name_per_tenant ON kitchens (tenant_id, lower(name));

-- At most one main kitchen per temple, enforced by the database rather than
-- by hope. Partial, so the many child kitchens do not collide with each other
-- on a shared `false`. At most rather than exactly one, because a freshly
-- onboarded temple has no kitchens at all and there is no row to carry it.
CREATE UNIQUE INDEX kitchens_one_main_per_tenant ON kitchens (tenant_id) WHERE is_main;

CREATE INDEX kitchens_tenant_status ON kitchens (tenant_id, status);

SELECT enable_tenant_rls('kitchens');
