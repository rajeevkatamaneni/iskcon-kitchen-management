-- =====================================================================
-- V67 — How many people it takes to cook the meal, and a feast as a kind
--       of meal (build brief 2026-08-21, items 24 and 26)
--
-- Two things a planner could not say before:
--
--   1. how many pairs of hands the meal takes to execute. A meal knew how
--      many it would FEED and nothing about how many it takes to MAKE.
--   2. that this meal, of all the meals on a festival day, is the feast.
-- =====================================================================

-- --- 1. The crew a meal takes ------------------------------------------------
--
-- On meal_plans, beside head_count, ready_by, adults, children and seniors. All
-- of those are already whole-meal facts carried on each dish row, and V64
-- defended that shape as load-bearing: a meal_services row exists only once a
-- card has been printed or the meal recorded, so a fact the planner sets weeks
-- earlier has nowhere else to live.
--
-- If meal_services is ever promoted to exist from planning time, crew_required
-- moves there with the other four, as one migration. Moving one of the five on
-- its own would leave a meal's facts in two places, which is how two screens
-- come to disagree about the same lunch.
ALTER TABLE meal_plans ADD COLUMN crew_required INTEGER;

ALTER TABLE meal_plans ADD CONSTRAINT meal_plans_crew_positive
    CHECK (crew_required IS NULL OR crew_required > 0);

COMMENT ON COLUMN meal_plans.crew_required IS
    'How many people it takes to execute this meal, any mix of staff and volunteers (item 24). Satisfied when staff + volunteers >= this. NULL where nobody has said — a made-up number would be worse. A whole-meal fact carried on each dish row, like head_count and ready_by.';

-- --- 2. A correction to what V22 says about day_type -------------------------
--
-- V22's inline comment calls day_type "auto-suggested at creation, overridable".
-- It is not overridable and has not been since E4-S7: deriveDayType runs on
-- update as well as create and overwrites whatever was there. The comment cannot
-- be edited in place — V22 is applied, and rewriting an applied migration breaks
-- its checksum — so the correct sentence is recorded here, on the column itself,
-- where psql \d+ and every schema dump will show it.
--
-- Recorded at the same time, so it is not undone later: day_type is a RECORD of
-- what was true on the day, not a lookup. calendar_overrides lets a temple mark
-- a day differently and the precompute runner refreshes days, so re-deriving at
-- read time would let a lunch that was ordinary when it was cooked become a
-- festival lunch months later — moving the crew default underneath the planner
-- for no visible reason. The column is a record. The calendar is a current
-- opinion.
COMMENT ON COLUMN meal_plans.day_type IS
    'What kind of day this meal was cooked on: REGULAR, WEEKEND, FESTIVAL or CATERING. Derived from the date, the calendar and the meal kind, never chosen by a person, and re-derived on every update. A record of what was true on the day — not re-read from the calendar afterwards.';

-- --- 3. A kind of meal may have to name its occasion --------------------------
--
-- meal_kinds already says what a kind needs beyond a recipe with flags rather
-- than hardcoded names, so the application never has to recognise a kind by its
-- name: needs_client for food someone outside asked for and is paying for,
-- needs_venue for food that leaves the temple, needs_purpose for what the food
-- is for. needs_occasion joins them: the plan must name which festival it is for.
--
-- Why this is a KIND and not another day type. V48 separated the two ideas on
-- purpose. A kind says when in the day a meal happens and what it needs; a day
-- type says what sort of day it is, derived, never chosen. Catering was moved
-- out of day type into kinds precisely because catering is a property of the
-- MEAL rather than of the day — a temple must be able to cater on a festival.
-- A feast is the same shape: on Janmashtami the temple serves an ordinary
-- breakfast and then a feast. One day, two meals, one of them the big one. Only
-- a per-meal fact can say which.
ALTER TABLE meal_kinds ADD COLUMN needs_occasion BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN meal_kinds.needs_occasion IS
    'Meals of this kind must name the festival they are for (item 26). Defaults to whatever the calendar says for the date and is pickable, so a temple anniversary or a local festival the calendar does not carry can still be planned as a feast.';

-- --- 4. Seed the feast, one temple at a time ---------------------------------
--
-- meal_kinds is tenant-owned and carries FORCE ROW LEVEL SECURITY: even the role
-- that owns it — the role running this migration — is subject to the isolation
-- policy. A plain cross-tenant INSERT is refused outright, and a plain
-- cross-tenant UPDATE is worse, silently matching nothing and reporting success.
-- So do what the application does and what V48 did: adopt each tenant in turn and
-- work inside its own scope, set locally so it dies with this transaction.
--
-- default_ready_time is left NULL deliberately. A feast is never at the same hour
-- twice, and V48's rule is that the occasional kinds always ask rather than being
-- given a guessed time.
--
-- sort_order 35 puts it after Dinner and before the kinds that are not a sitting
-- at all, so the picker reads: the three everyday meals, the feast, then the
-- deity offering, the outside event and the catering order.
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        INSERT INTO meal_kinds (
            tenant_id, name, sort_order, default_ready_time,
            needs_client, needs_venue, needs_purpose, needs_occasion)
        SELECT t.id, 'Festival feast', 35, NULL::time, false, false, false, true
        WHERE NOT EXISTS (
            SELECT 1 FROM meal_kinds mk WHERE lower(mk.name) = 'festival feast');
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);
END $$;

-- --- 5. Reading a menu back, and reading the crew default --------------------
--
-- Both new lookups walk backwards through meal_plans: the menu history finds the
-- most recent meal carrying an occasion name, and the crew default finds the last
-- three ordinary meals of a kind. Both are per tenant, per name, newest first.
CREATE INDEX meal_plans_tenant_occasion
    ON meal_plans (tenant_id, lower(occasion_name), plan_date DESC)
    WHERE occasion_name IS NOT NULL;

CREATE INDEX meal_plans_tenant_kind_crew
    ON meal_plans (tenant_id, meal_kind, plan_date DESC)
    WHERE crew_required IS NOT NULL;
