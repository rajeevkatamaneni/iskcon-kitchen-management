-- =====================================================================
-- V95 — A shift says which meal it is for
--
-- D-14, ruled by Rajeev on 2026-09-07 against a recommendation to leave
-- the clock rule alone:
--
--     "Having enough raw ingredients and enough people at the right time
--      are the two main things the Kitchen Management App must ACE. ...
--      When it comes to the 2 main core things it has to ace, we cant
--      leave ANYTHING on the table no matter how hard it is. So explicit
--      link it is."
--
-- Until now a shift fell to a meal by overlapping its ready-by time, and
-- that is wrong in both directions at once. A devotee signed up 06:00 to
-- 10:00 to cut vegetables for lunch counts toward BREAKFAST, because
-- breakfast is what is due at 08:00 — so the clock misses the lunch it
-- was meant for AND inflates breakfast with hands committed elsewhere.
-- The second half is the worse one: a crew figure that is quietly too
-- high is never questioned, because nobody goes looking for hands they
-- think they already have.
--
-- ---------------------------------------------------------------------
-- Why this is a natural key and not a foreign key
--
-- Because there is nothing to point at. There is no meal table: one
-- `meal_plans` row is one dish, and a lunch of three dishes is three rows
-- carrying the same date, kind, head count and ready-by. "A meal" is an
-- inference the application assembles by grouping those rows on
-- (plan_date, meal_kind, event_name).
--
-- `meal_services` does have a row per meal and it is tempting, but it is
-- null exactly when this link needs it: that row appears when the meal is
-- carded or recorded, and a shift is posted while PLANNING — weeks
-- earlier. A foreign key to a row that does not exist yet is not a
-- constraint, it is a reason the planner cannot save.
--
-- So the link is the meal's own natural key, carried here:
--
--     meal_date        the day the meal is cooked on
--     meal_kind        Breakfast / Lunch / Dinner / an event kind
--     meal_event_name  what the event is called, where the meal is one
--
-- No FK is possible for the date or the event name. None is possible for
-- the kind either, and that was checked rather than assumed: `meal_kinds`
-- is unique per tenant on `(tenant_id, lower(name))`, an EXPRESSION
-- index, and PostgreSQL will not accept an expression index as the target
-- of a foreign key. `meal_plans.meal_kind` is plain TEXT for the same
-- reason, so this column is consistent with the one it has to match.
--
-- ---------------------------------------------------------------------
-- The CHECK, and what it does not police
--
-- Date and kind move together: both or neither. Half a link is not a
-- weaker link, it is a link to nothing, and a shift carrying only a date
-- would silently count toward no meal at all while looking deliberate on
-- the screen. The event name is allowed only alongside them, for the same
-- reason — a name with no date and no kind names nothing.
--
-- What the constraint deliberately does NOT do is require the meal to
-- exist. A shift is very often posted before the dishes are planned, and
-- a database that refused that would make the feature unusable in exactly
-- the order a temple actually works: find the hands first, decide the
-- menu later. An unmatched link counts toward nothing until the meal is
-- planned, and then starts counting.
--
-- The CHECK is the backstop. The caller-facing refusal is KMS-400125,
-- raised in ShiftService before the statement is ever sent.
--
-- ---------------------------------------------------------------------
-- Nullable, and therefore no backfill
--
-- Every shift that exists today is unlinked, and unlinked is the correct
-- reading of every one of them: nobody has ever been asked which meal
-- they meant, so the honest record is that they did not say. Three
-- nullable columns default to NULL and that IS the backfill. Which is
-- just as well, because `shifts` is tenant-owned and carries FORCE ROW
-- LEVEL SECURITY, so a cross-tenant UPDATE here would not fail loudly —
-- it would match nothing and report success.
--
-- No index either, and on purpose. The crew count loads a date range of
-- shifts and matches them in the application; `shifts_tenant_date` already
-- serves the shift_date half, and the meal_date half is an OR over the
-- same small per-tenant range. An index on a column that is NULL on
-- essentially every row would be paid for on every write and read on
-- none.
-- =====================================================================

ALTER TABLE shifts
    ADD COLUMN meal_date       DATE,
    ADD COLUMN meal_kind       TEXT,
    ADD COLUMN meal_event_name TEXT;

ALTER TABLE shifts ADD CONSTRAINT shifts_meal_link_complete CHECK (
    (meal_date IS NULL AND meal_kind IS NULL AND meal_event_name IS NULL)
    OR (meal_date IS NOT NULL AND meal_kind IS NOT NULL)
);

COMMENT ON COLUMN shifts.meal_date IS
    'D-14: the date of the meal this shift was posted for, or NULL where the shift is a general offer of hands matched to meals by its hours.';
COMMENT ON COLUMN shifts.meal_kind IS
    'D-14: the kind of the meal this shift was posted for. Matched case-insensitively against meal_plans.meal_kind; no FK is possible because meal_kinds is unique on an expression.';
COMMENT ON COLUMN shifts.meal_event_name IS
    'D-14: the name of the event whose meal this shift was posted for, where the meal is one. Stored as typed and folded to lower case when matched, exactly as V89 folds the event name it keys on.';
