-- =====================================================================
-- V150 — Which kitchen is cooking (Epic 12, T-350)
--
-- WHAT WAS THERE
--
-- A meal (V136) said what was cooked, when and for how many, and nothing about where. A temple runs
-- several kitchens (V76) and more than one of them plans its meals here — the local data already has
-- the Main Kitchen and a Health Kitchen both using the planner — so "Lunch on Saturday" could not say
-- that the dal comes from one kitchen and the sabji from another, nor print each kitchen its own card.
-- Staff had no kitchen either, so nothing could put a cook's own kitchen first on the screen or ask
-- whether the person planning a meal works in a kitchen that plans meals at all.
--
-- WHAT THIS BUILDS (the data model decided for Epic 12; docs/work/DISPATCH.md, "The data model")
--
--   meal_kitchens              one row per kitchen cooking a meal: the meal's sections. Each carries
--                              that kitchen's People needed and its own job-card version, because each
--                              kitchen prints its own card. It exists even for a section with no
--                              dishes yet — a kitchen can be on a meal before anybody has chosen
--                              what it will cook.
--   meal_dishes.kitchen_id     which section a dish is in. A composite foreign key onto
--                              meal_kitchens (meal_id, kitchen_id), so a dish can only sit under a
--                              kitchen that is on its own meal — the database refuses the other case
--                              rather than trusting every writer to check it.
--   staff_profiles.kitchen_id  every staff member belongs to exactly one kitchen.
--   staff_profiles.kitchen_needs_check
--                              true where the kitchen was filled in by this migration rather than
--                              chosen by a person; the Temple Admin's "Check these kitchen
--                              assignments" list reads it, and saving or confirming the record clears
--                              it. A guess is recorded as a guess.
--
-- meals.crew_required, card_version and card_fingerprint are COPIED here and deliberately left where
-- they are: V151 (T-354) and V152 (T-356) drop them once the code that reads them has moved. Dropping
-- them now would break every reader in the same deploy as the one that is supposed to replace them.
--
-- ---------------------------------------------------------------------
-- THE BACKFILL, AND WHY IT LOOPS
--
-- Every existing meal, dish and staff member needs a kitchen, and there is only one honest answer
-- for the past: the kitchen the temple plans its meals in. Per temple:
--
--   1. the main kitchen, if it is ACTIVE and uses the meal planner;
--   2. else the first ACTIVE kitchen that uses the meal planner, in the order Settings lists them
--      (is_main DESC, lower(name)) — which rule 1 is simply the first case of;
--   3. else a new kitchen, 'Main kitchen' (or 'Main kitchen (meals)' where that name is taken, since
--      kitchens_name_per_tenant compares case-insensitively), using the meal planner, marked main only
--      if the temple has no main kitchen at all — kitchens_one_main_per_tenant allows at most one, and
--      quietly moving the title off a kitchen the temple chose is not this migration's decision.
--      It is created by the temple's earliest Temple Admin, else its earliest user, because
--      kitchens.created_by is NOT NULL and a migration has no signed-in person. A temple with no users
--      and nothing to backfill is skipped: there is nobody to attribute the kitchen to and nothing
--      that needs it.
--
-- Why not flip uses_meal_planner on a kitchen that has it off: turning the planner on for a kitchen
-- settles its in-flight ingredient requests (MealPlannerAdoption — drafts deleted, open requests
-- denied), and a migration must not deny somebody's request as a side effect. A new kitchen has no
-- requests to settle.
--
-- The loop adopts each temple in turn because a data statement in a migration is subject to the
-- isolation policy the schema declares (V48, V57): a cross-tenant UPDATE silently matches nothing and
-- reports success, and a cross-tenant INSERT is refused. Under Testcontainers-as-superuser neither
-- shows; WhichKitchenMigrationIT runs Flyway as the unprivileged migration role so that it does.
--
-- The SET NOT NULLs at the end are outside the loop on purpose, and are the tripwire (V48, V57): DDL
-- is not filtered by a row policy, so a temple the loop missed fails this migration — and the deploy —
-- here, instead of shipping a column that is filled for one temple and empty for the next.
--
-- ---------------------------------------------------------------------
-- DELETION
--
-- meal_kitchens.meal_id is ON DELETE CASCADE: a section is part of its meal and means nothing without
-- it. That is safe because a meal with dishes still cannot be deleted — meal_dishes.meal_id stays
-- RESTRICT, and so does the composite key — and meals are cancelled, never deleted, by the
-- application. kitchen_id is RESTRICT everywhere: a kitchen that cooked a meal or employs somebody is
-- part of the record, and is archived rather than removed (KitchenService.delete). Tenant deletion
-- (delete_tenant_cascade, V86) and docs/reset-temple-data.sql find tables by their tenant_id column
-- and retry until a pass deletes nothing, so they need no entry of their own for the new table.
-- =====================================================================


-- ---------------------------------------------------------------------
-- The sections of a meal
-- ---------------------------------------------------------------------
CREATE TABLE meal_kitchens (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    meal_id           UUID        NOT NULL REFERENCES meals(id) ON DELETE CASCADE,
    kitchen_id        UUID        NOT NULL REFERENCES kitchens(id) ON DELETE RESTRICT,

    -- That kitchen's People needed. NULL where nobody has said, exactly as meals.crew_required (V67)
    -- has always meant; the meal-level figure a screen shows is the sum of these.
    crew_required     INTEGER,

    -- Which version of this kitchen's job card has been printed, and a hash of what it said (V92's
    -- rules, per kitchen). The card NUMBER stays one per meal, on meals.card_number, and is printed on
    -- every kitchen's card beside the kitchen's name.
    card_version      INTEGER     NOT NULL DEFAULT 0,
    card_fingerprint  TEXT,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT meal_kitchens_crew_positive CHECK (crew_required IS NULL OR crew_required > 0),
    CONSTRAINT meal_kitchens_card_version_non_negative CHECK (card_version >= 0),

    -- A kitchen is on a meal once. Also the target of meal_dishes' composite foreign key below, which
    -- is why it is a constraint and not merely an index.
    CONSTRAINT meal_kitchens_one_per_kitchen UNIQUE (meal_id, kitchen_id)
);

-- Every "which meals is this kitchen cooking" read, and the RESTRICT check when a kitchen is deleted.
CREATE INDEX meal_kitchens_by_kitchen ON meal_kitchens (tenant_id, kitchen_id);

SELECT enable_tenant_rls('meal_kitchens');

COMMENT ON TABLE meal_kitchens IS
    'The kitchens cooking one meal, one row each — the meal''s sections (Epic 12). Carries that kitchen''s People needed and its own job-card version. Exists even for a section with no dishes yet.';
COMMENT ON COLUMN meal_kitchens.crew_required IS
    'How many people this kitchen needs for this meal, staff and volunteers together. NULL where nobody has said. The meal''s figure is the sum over its kitchens.';
COMMENT ON COLUMN meal_kitchens.card_version IS
    'Which version of this kitchen''s job card has been printed. Zero until its first print; goes up only when what it prints differs from card_fingerprint (V92''s rule, per kitchen).';
COMMENT ON COLUMN meal_kitchens.card_fingerprint IS
    'A hash of everything this kitchen''s last printed card said, compared on every print to decide whether card_version moves.';


-- ---------------------------------------------------------------------
-- A dish is in one of its meal's sections
--
-- Nullable until the loop below has filled it, then NOT NULL at the end.
-- ---------------------------------------------------------------------
ALTER TABLE meal_dishes ADD COLUMN kitchen_id UUID;

-- Composite, so a dish cannot be under a kitchen that is not on its own meal. A plain key onto
-- kitchens(id) would accept any kitchen of the temple; this one accepts only the meal's. RESTRICT:
-- taking a kitchen off a meal while it still has dishes there is a decision about those dishes, and
-- the application makes it (T-354), not a cascade.
ALTER TABLE meal_dishes
    ADD CONSTRAINT meal_dishes_kitchen_on_meal
    FOREIGN KEY (meal_id, kitchen_id) REFERENCES meal_kitchens (meal_id, kitchen_id) ON DELETE RESTRICT;

-- The composite key is checked from meal_kitchens' side on every delete or key change there.
CREATE INDEX meal_dishes_by_meal_kitchen ON meal_dishes (meal_id, kitchen_id);

COMMENT ON COLUMN meal_dishes.kitchen_id IS
    'Which of its meal''s kitchens cooks this dish. Always a kitchen on the same meal: the foreign key is onto meal_kitchens (meal_id, kitchen_id), not onto kitchens.';


-- ---------------------------------------------------------------------
-- Every staff member belongs to one kitchen
-- ---------------------------------------------------------------------
ALTER TABLE staff_profiles
    ADD COLUMN kitchen_id          UUID REFERENCES kitchens(id) ON DELETE RESTRICT,
    ADD COLUMN kitchen_needs_check BOOLEAN NOT NULL DEFAULT false;

-- How many people a kitchen employs (the archive guard and the Settings count, T-357), and the RESTRICT
-- check when a kitchen is deleted.
CREATE INDEX staff_profiles_by_kitchen ON staff_profiles (tenant_id, kitchen_id);

COMMENT ON COLUMN staff_profiles.kitchen_id IS
    'The one kitchen this person works in (Epic 12). Puts their own kitchen first on a meal, and decides whether they may plan meals at all.';
COMMENT ON COLUMN staff_profiles.kitchen_needs_check IS
    'True where the kitchen was filled in for this person rather than chosen — by V150 for everyone employed before kitchens were recorded. Feeds the Temple Admin''s "Check these kitchen assignments" list; cleared when the record is saved or confirmed.';


-- ---------------------------------------------------------------------
-- The backfill, one temple at a time
--
-- The loop variable is never reused as a table alias (V57's header: a table aliased like a declared
-- RECORD resolves to the record, and only when the loop actually runs).
-- ---------------------------------------------------------------------
DO $$
DECLARE
    tenant_row  RECORD;
    v_kitchen   UUID;
    v_name      TEXT;
    v_how       TEXT;
    v_creator   UUID;
    v_has_main  BOOLEAN;
    v_attempt   INTEGER;
    v_meals     BIGINT;
    v_dishes    BIGINT;
    v_staff     BIGINT;
BEGIN
    FOR tenant_row IN SELECT id, slug FROM tenants ORDER BY slug LOOP
        PERFORM set_config('app.tenant_id', tenant_row.id::text, true);

        -- Rules 1 and 2 together: the main kitchen sorts first when it qualifies.
        SELECT k.id, k.name INTO v_kitchen, v_name
        FROM kitchens k
        WHERE k.tenant_id = tenant_row.id AND k.status = 'ACTIVE' AND k.uses_meal_planner
        ORDER BY k.is_main DESC, lower(k.name), k.id
        LIMIT 1;
        v_how := 'existing';

        IF v_kitchen IS NULL THEN
            SELECT u.id INTO v_creator
            FROM users u
            WHERE u.tenant_id = tenant_row.id
            ORDER BY (u.role = 'TEMPLE_ADMIN') DESC, u.created_at, u.id
            LIMIT 1;

            IF v_creator IS NULL
               AND NOT EXISTS (SELECT 1 FROM meals WHERE tenant_id = tenant_row.id)
               AND NOT EXISTS (SELECT 1 FROM staff_profiles WHERE tenant_id = tenant_row.id) THEN
                RAISE NOTICE 'V150: temple % has no users and nothing to fill in; skipped', tenant_row.slug;
                CONTINUE;
            END IF;
            -- A temple with meals or staff but no user cannot happen (a dish names its creator), and
            -- if it ever did the INSERT below fails on created_by, which is the loud outcome wanted.

            v_name := 'Main kitchen';
            v_attempt := 1;
            WHILE EXISTS (SELECT 1 FROM kitchens k
                          WHERE k.tenant_id = tenant_row.id AND lower(k.name) = lower(v_name)) LOOP
                v_name := CASE WHEN v_attempt = 1 THEN 'Main kitchen (meals)'
                               ELSE format('Main kitchen (meals %s)', v_attempt) END;
                v_attempt := v_attempt + 1;
            END LOOP;

            -- Archived rows count: the partial unique index covers every row, archived or not.
            v_has_main := EXISTS (SELECT 1 FROM kitchens k WHERE k.tenant_id = tenant_row.id AND k.is_main);

            INSERT INTO kitchens (tenant_id, name, description, is_main, uses_meal_planner, status, created_by)
            VALUES (tenant_row.id, v_name,
                    'Added when meals and staff were given a kitchen, because no kitchen here was using the meal planner.',
                    NOT v_has_main, true, 'ACTIVE', v_creator)
            RETURNING id INTO v_kitchen;
            v_how := CASE WHEN v_has_main THEN 'created' ELSE 'created as main' END;
        END IF;

        INSERT INTO meal_kitchens (tenant_id, meal_id, kitchen_id, crew_required, card_version, card_fingerprint)
        SELECT m.tenant_id, m.id, v_kitchen, m.crew_required, m.card_version, m.card_fingerprint
        FROM meals m
        WHERE m.tenant_id = tenant_row.id
          AND NOT EXISTS (SELECT 1 FROM meal_kitchens mk WHERE mk.meal_id = m.id);
        GET DIAGNOSTICS v_meals = ROW_COUNT;

        -- Not updated_at on either table: nobody edited these rows, and a date that moved would say so.
        UPDATE meal_dishes SET kitchen_id = v_kitchen
        WHERE tenant_id = tenant_row.id AND kitchen_id IS NULL;
        GET DIAGNOSTICS v_dishes = ROW_COUNT;

        UPDATE staff_profiles SET kitchen_id = v_kitchen, kitchen_needs_check = true
        WHERE tenant_id = tenant_row.id AND kitchen_id IS NULL;
        GET DIAGNOSTICS v_staff = ROW_COUNT;

        RAISE NOTICE 'V150: temple % — kitchen "%" (%): % meals, % dishes, % staff to check',
            tenant_row.slug, v_name, v_how, v_meals, v_dishes, v_staff;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;

-- The tripwire. A temple the loop missed stops the migration here rather than shipping half-filled.
ALTER TABLE meal_dishes    ALTER COLUMN kitchen_id SET NOT NULL;
ALTER TABLE staff_profiles ALTER COLUMN kitchen_id SET NOT NULL;
