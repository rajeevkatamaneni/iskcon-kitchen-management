-- =====================================================================
-- V48 — Meal kinds, and a ready-by time on every planned meal (E4-S7)
--
-- The planner had two overlapping ideas: a "slot" (Lunch, Dinner, Deity
-- Offering) describing when in the day a meal happens, and a "day type"
-- (regular / weekend / festival / CATERING) describing what kind of day it is.
-- Catering sat in the second, which made it a property of the *day* rather than
-- of the meal — so a temple could not cater on a festival, and a person planning
-- lunch was asked what sort of day it was.
--
-- E4-S7 collapses that: there is one list of MEAL KINDS, and the day type is
-- derived from the date and the calendar rather than chosen by anyone. A kind
-- carries the time its food must be ready — pre-filled for the everyday meals,
-- deliberately absent for the occasional ones, because guessing when a deity
-- offering or a catering order is due is worse than asking.
--
-- Kept: day_type on meal_plans. Nobody picks it now, but it still records what
-- kind of day the meal was cooked on, which reporting and the festival
-- serving-count pre-fill both rely on.
-- =====================================================================

-- --- meal_slots becomes meal_kinds -----------------------------------------
ALTER TABLE meal_slots RENAME TO meal_kinds;
ALTER INDEX meal_slots_name_per_tenant RENAME TO meal_kinds_name_per_tenant;
ALTER TABLE meal_kinds RENAME CONSTRAINT meal_slots_name_present TO meal_kinds_name_present;

-- The time the food must be ready. NULL means "always ask" — see the seed below.
ALTER TABLE meal_kinds ADD COLUMN default_ready_time TIME;

-- What a kind needs beyond a recipe. Flags rather than hardcoded names, so a temple
-- can add its own kinds without the application having to recognise them.
--   needs_client — someone outside the temple asked for this food and is paying for it
--                  (catering), so the plan must name them.
--   needs_venue  — the food leaves the temple, so the plan must say where it is going.
ALTER TABLE meal_kinds ADD COLUMN needs_client BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE meal_kinds ADD COLUMN needs_venue  BOOLEAN NOT NULL DEFAULT false;

COMMENT ON TABLE meal_kinds IS
    'The kinds of meal a temple cooks (E4-S7): the everyday ones with a default ready-by time, and the occasional ones that must always be given one.';

-- --- meal_plans: the kind it is, and when it must be ready -------------------
ALTER TABLE meal_plans RENAME COLUMN slot TO meal_kind;

ALTER TABLE meal_plans ADD COLUMN ready_by TIME;

-- --- Seed the kinds, and backfill the times, one temple at a time ------------
--
-- Both tables are tenant-owned, so they carry FORCE ROW LEVEL SECURITY: even the
-- role that owns them — the role running this migration — is subject to the
-- isolation policy. A plain cross-tenant INSERT is refused outright, and a plain
-- cross-tenant UPDATE is worse: it silently matches nothing and reports success.
-- (Neither shows up under Testcontainers, where migrations run as a superuser and
-- superusers bypass RLS entirely. This one only appeared on a real deployment.)
--
-- So do what the application does: adopt each tenant in turn and work inside its
-- own scope. Set locally, so it dies with this transaction.
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        -- Times are the temple-wide defaults a Temple Admin can change.
        UPDATE meal_kinds SET default_ready_time = TIME '12:00', sort_order = 20 WHERE lower(name) = 'lunch';
        UPDATE meal_kinds SET default_ready_time = TIME '19:30', sort_order = 30 WHERE lower(name) = 'dinner';
        UPDATE meal_kinds SET sort_order = 40 WHERE lower(name) = 'deity offering';

        -- Breakfast did not exist before; Catering and Outside event were previously
        -- day types, not meals.
        INSERT INTO meal_kinds (tenant_id, name, sort_order, default_ready_time, needs_client, needs_venue)
        SELECT t.id, v.name, v.sort_order, v.ready_time, v.needs_client, v.needs_venue
        FROM (VALUES
                -- Everyday meals: a known time, changeable per temple.
                ('Breakfast',      10, TIME '07:30', false, false),
                -- Occasional: no default, so the planner is always asked.
                ('Catering order', 50, NULL::time,   true,  true),
                ('Outside event',  60, NULL::time,   false, true)
            ) AS v(name, sort_order, ready_time, needs_client, needs_venue)
        WHERE NOT EXISTS (
            SELECT 1 FROM meal_kinds mk WHERE lower(mk.name) = lower(v.name));

        -- Backfill: the kind's own default where there is one, else midday. Existing
        -- rows predate the field and had no time recorded anywhere.
        UPDATE meal_plans mp
        SET ready_by = COALESCE(
                (SELECT mk.default_ready_time FROM meal_kinds mk
                 WHERE lower(mk.name) = lower(mp.meal_kind)),
                TIME '12:00')
        WHERE mp.ready_by IS NULL;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);
END $$;

-- Every row must have been reached. DDL is not filtered by RLS, so a tenant the loop
-- somehow missed fails here rather than shipping a half-filled column.
ALTER TABLE meal_plans ALTER COLUMN ready_by SET NOT NULL;

COMMENT ON COLUMN meal_plans.ready_by IS
    'Local time the food must be ready (E4-S7). Not a start time: it is what a cook works backwards from, and what orders a day''s plan.';

-- delivery_time was a catering-only "when does it leave" that nothing ever set —
-- ready_by now covers the question for every kind of meal.
ALTER TABLE meal_plans DROP COLUMN delivery_time;

-- A client is now required by the *kind* (needs_client), not by the day type, and is
-- enforced in MealPlanService where the kind is known. A CHECK cannot reach across to
-- meal_kinds to see the flag, so the constraint goes rather than sit here half-true.
ALTER TABLE meal_plans DROP CONSTRAINT meal_plans_catering_has_client;
