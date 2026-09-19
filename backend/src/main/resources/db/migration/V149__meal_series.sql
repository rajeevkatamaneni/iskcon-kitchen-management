-- =====================================================================
-- V149 — A repeating event is a series (T-307, Rajeev 2026-09-19)
--
-- WHAT WAS THERE
--
-- "Repeat" copied an event forward a fixed number of weeks, and each copy was
-- an ordinary meal that knew nothing of the others (E4-S15 D8, "copies, not a
-- series"). That was deliberate: no screen ever had to ask "this one or all of
-- them?". Rajeev has now asked for exactly that question, in his words:
--
--     "Let us change for many weeks to 'until' a date and also give them the
--      option to pick the duration between repeats, and a cancel of this
--      repeating event should ask JUST this event OR all events from this
--      point onwards."
--
-- To cancel "this and every later one" the app has to know which meals are
-- one repeating event. Text will not do it — the event's name is editable,
-- and matching by name is what D-27 ruled out ("identifying things by text is
-- a terrible idea and one that WILL fail eventually"). So the series gets a
-- row of its own and each member points at it by id.
--
-- WHAT A SERIES IS, AND IS NOT
--
-- It is a label on a set of ordinary meals, plus the rule they were made
-- with. It is NOT a recurrence rule that generates meals on the fly: every
-- occurrence is still a real meals row, made at the moment of repeating,
-- editable and cancellable on its own exactly as before. So nothing that
-- reads meals — the planner, Today, the job card, costing, the shopping list
-- — has to learn anything; a meal in a series is a meal.
--
--   meal_series        one row per repeating event: how often (every_weeks)
--                      and until when (until_date).
--   meals.series_id    which series a meal belongs to, or NULL.
--   meals.series_edited_at
--                      when this occurrence was changed on its own after it
--                      joined the series. The cancel confirmation shows it
--                      ("edited"), so a planner cancelling "this and later"
--                      sees that one of the later ones had been hand-tuned.
--                      A timestamp rather than a boolean so it says when.
--
-- WHY THE FOREIGN KEY RUNS ONE WAY ONLY
--
-- meals -> meal_series, never back. A series does not name its "first" meal:
-- the first meal can be cancelled, and a series is extended by repeating from
-- any member. A pointer in both directions would also be a cycle, which the
-- tenant purge (delete_tenant_cascade, V86) cannot resolve: it deletes every
-- table with a tenant_id, retrying until a pass deletes nothing, so meals go
-- on one pass and meal_series on the next. TenantDeletionIT covers it.
--
-- WHY THE FOREIGN KEY INCLUDES tenant_id
--
-- (tenant_id, series_id) -> meal_series (tenant_id, id), not series_id alone.
-- A foreign key is checked by the table owner, which row-level security does
-- not constrain, so a plain series_id key would accept a series belonging to
-- another temple if one ever reached an UPDATE. The service never lets one
-- (it only ever copies series_id from a meal it has just read under the
-- policy), but "the database says no" is this project's rule for tenancy, not
-- "the service is careful". The composite key makes a cross-temple link
-- impossible to store. MATCH SIMPLE: a meal with no series is unaffected.
--
-- ROW-LEVEL SECURITY
--
-- meal_series carries tenant_id and calls enable_tenant_rls(), like every
-- tenant-owned table (RowLevelSecurityIT.everyTenantOwnedTableIsProtected).
-- Everything here is DDL, run as the owner. There is nothing to backfill:
-- every existing meal is correctly "in no series", and the two new columns
-- are nullable with no default, so no statement reads or writes tenant rows
-- and no per-tenant loop is needed.
-- =====================================================================

CREATE TABLE meal_series (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- "once every [N] week / weeks". The screen offers 1 to 12; the service
    -- refuses anything else with KMS-400175, and this is the floor under both.
    every_weeks  INTEGER     NOT NULL,

    -- "... until 31 Dec 2026". Extending a series keeps the later of the old
    -- and new dates; cancelling "this and later" brings it back to the last
    -- occurrence still standing. Never NULL: a series without an end is the
    -- open-ended recurrence this deliberately is not.
    until_date   DATE        NOT NULL,

    -- Who first repeated it. SET NULL, as meals.recorded_by: the fact must
    -- outlive the person's account.
    created_by   UUID        REFERENCES users(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT meal_series_every_weeks_range CHECK (every_weeks BETWEEN 1 AND 12),

    -- The target of the composite key from meals (see the header).
    CONSTRAINT meal_series_tenant_id_id UNIQUE (tenant_id, id)
);

SELECT enable_tenant_rls('meal_series');


ALTER TABLE meals
    ADD COLUMN series_id        UUID,
    ADD COLUMN series_edited_at TIMESTAMPTZ;

-- RESTRICT: a series with members cannot be deleted out from under them.
-- Nothing deletes a series today; the purge removes the meals first.
ALTER TABLE meals
    ADD CONSTRAINT meals_series_same_tenant
    FOREIGN KEY (tenant_id, series_id) REFERENCES meal_series (tenant_id, id) ON DELETE RESTRICT;

-- An occurrence can only have been edited "on its own" if it is in a series.
ALTER TABLE meals
    ADD CONSTRAINT meals_series_edited_needs_a_series
    CHECK (series_edited_at IS NULL OR series_id IS NOT NULL);

-- "The members of this series": the series summary on every meal read, the
-- later-in-series list, and the foreign key check on a series delete.
CREATE INDEX meals_by_series ON meals (series_id) WHERE series_id IS NOT NULL;

COMMENT ON TABLE meal_series IS
    'A repeating event (T-307): the rule its occurrences were made with. Each occurrence is an ordinary meals row pointing here by series_id.';
COMMENT ON COLUMN meals.series_id IS
    'The repeating event this meal is one occurrence of (T-307), or NULL.';
COMMENT ON COLUMN meals.series_edited_at IS
    'When this occurrence was changed on its own after joining its series (T-307). NULL = as repeated.';
