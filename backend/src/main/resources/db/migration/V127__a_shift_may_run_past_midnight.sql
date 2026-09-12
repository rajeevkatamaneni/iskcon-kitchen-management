-- =====================================================================
-- V127 — A shift may run past midnight (T-146)
--
-- Posting a shift from 20:00 to 02:00 was refused with KMS-500001,
-- "Something went wrong at our end. Try again in a moment." — for a
-- temple whose largest festival is at midnight. Found while seeding
-- Janmashtami.
--
-- Two separate defects sit behind that one screen, and only the first of
-- them is this file's business:
--
--   1. The product did not support an overnight shift at all. V34 wrote
--      CHECK (end_time > start_time), which is the rule "a shift ends
--      later in the same day", and a temple that cooks through the night
--      cannot say what it does. That is what this migration changes.
--
--   2. The refusal blamed us and gave advice that cannot work. A CHECK
--      violation is a DataIntegrityViolationException, nothing catches
--      it, and GlobalExceptionHandler.handleUnexpected answers every
--      unanticipated exception with KMS-500001 and an incident id. So a
--      caller asking for something reasonable was told the fault was
--      ours and to try again in a moment — which would have failed
--      identically for ever. That half is fixed in the application, by
--      refusing the one remaining nonsense (below) as a named validation
--      failure before it ever reaches the database.
--
-- ---------------------------------------------------------------------
-- 1. What shift_date means, said once
--
-- Everything here follows from one sentence, and it is written down so
-- that no reader has to decide it for itself:
--
--     shift_date is the date the shift STARTS. A shift ends on
--     shift_date + 1 whenever end_time <= start_time, and on shift_date
--     otherwise.
--
-- That reading is not a new invention — it is what every existing reader
-- of the column already assumes. The attendance gate, the release guard
-- and the reminder scheduler all build the shift's start instant as
-- (shift_date, start_time) and are correct as written. Nothing in the
-- tree computes an end instant at all, which is precisely why nothing
-- had to be wrong before today and why several things would have become
-- wrong the moment the constraint was merely dropped.
--
-- This project's most expensive recurring defect is the same arithmetic
-- implemented three times and disagreeing (V74 was written for exactly
-- that reason and says so). So the rule gets one implementation per
-- language and each names the others:
--
--     SQL   shift_ends_at(), below
--     Java  org.iskcon.kms.shift.ShiftWindow
--     TS    crossesMidnight()/shiftWindow() in frontend/lib/format.ts
--
-- ---------------------------------------------------------------------
-- 2. The constraint is changed, not dropped
--
-- end_time = start_time stays refused, and deliberately. 20:00 to 20:00
-- is ambiguous between a shift of no length and a shift of twenty-four
-- hours, and there is no reading of it a roster could act on: a
-- coordinator means one of the two and the row cannot say which. Under
-- the new meaning of the column it would also be the one input that
-- makes shift_ends_at() disagree with itself — end_time <= start_time
-- puts the end on the next day, so a "zero-length" shift would silently
-- become a twenty-four-hour one.
--
-- So the rule becomes end_time <> start_time, which is the same
-- constraint name carrying what we now mean by it. The application
-- refuses the same pairing first, as a Bean Validation failure naming
-- the field (KMS-400001), because a constraint violation is not a
-- sentence anybody should have to read — the same division of labour
-- V122 and V126 use, with the database as the backstop for a caller that
-- bypasses the service.
--
-- ---------------------------------------------------------------------
-- 3. Every existing row already satisfies the new constraint
--
-- The old rule is strictly stronger than the new one: end_time >
-- start_time implies end_time <> start_time, for every pair of times
-- there is. So no row in any temple can fail this, and the ADD
-- CONSTRAINT below proves it rather than taking it on trust — PostgreSQL
-- validates an added CHECK against every existing row and raises if one
-- fails, and it does so as the table owner, so it genuinely sees every
-- tenant's rows and not just one temple's.
--
-- ---------------------------------------------------------------------
-- 4. No tenant loop, because no rows are touched
--
-- shifts carries enable_tenant_rls() (V34), and migrations run as the
-- unprivileged role with app.tenant_id unset — so any UPDATE here would
-- match nothing through the policy's NULLIF and report success, which is
-- why every migration in this project that touches rows adopts each
-- tenant in turn.
--
-- This one touches none. DROP CONSTRAINT, ADD CONSTRAINT, CREATE
-- FUNCTION and COMMENT are all DDL and run as the owner, which the
-- policy does not apply to. The absence of a loop is deliberate, exactly
-- as it was in V118 and V122.
--
-- ---------------------------------------------------------------------
-- 5. staff_schedule_days is left alone
--
-- V33 has the same CHECK (end_time > start_time) on a staff member's
-- working day and on a per-date override. A night shift for salaried
-- staff is the same question asked about a different table, and it is
-- not this task: nobody has reported it, the staff screens have their
-- own arithmetic in ScheduleResolver, and changing a constraint nobody
-- asked about in order to be symmetrical is how a small fix becomes an
-- unreviewed one. Noted here so the next person finds it named rather
-- than discovering it as a surprise.
-- =====================================================================

ALTER TABLE shifts DROP CONSTRAINT shifts_time_window;

ALTER TABLE shifts ADD CONSTRAINT shifts_time_window CHECK (end_time <> start_time);

COMMENT ON COLUMN shifts.shift_date IS
    'The date the shift STARTS (T-146). A shift whose end_time is at or before its start_time runs through midnight and ends on shift_date + 1; shift_ends_at() is the one place that arithmetic is written in SQL, and org.iskcon.kms.shift.ShiftWindow is the one place it is written in Java. Everything that places a shift in time — the attendance gate, the release guard, the reminder fire times, the volunteer''s own list — is anchored on the start, so this column keeps meaning exactly what it meant before overnight shifts were allowed.';

COMMENT ON COLUMN shifts.end_time IS
    'When the shift ends, on shift_date or on the next day (T-146). At or before start_time means the shift runs through midnight — 20:00 to 02:00 is the Janmashtami midnight offering, and it is six hours long, not minus eighteen. Equal to start_time is refused by shifts_time_window because it cannot be told apart from a twenty-four-hour shift.';

-- ---------------------------------------------------------------------
-- The end instant, in SQL, once.
--
-- Written as a function for the reason V74 wrote to_base_qty as one: the
-- alternative is the same CASE expression copied into every query that
-- needs it, where two copies drift and nothing says so. Today it has one
-- caller — the overlap check that warns a volunteer about a double
-- booking — and that is the caller it was written for, because that
-- query has to compute the end instant of a shift it is comparing
-- against rather than one it is holding in Java.
--
-- IMMUTABLE: given the same three values it always answers the same, it
-- reads nothing outside its arguments, and it depends on no session
-- setting — notably not on TimeZone, because it returns a plain
-- TIMESTAMP WITHOUT TIME ZONE. A shift's hours are the temple's own wall
-- clock, and converting them to an instant is the application's job
-- (TempleClock), not this function's. Marking it IMMUTABLE is also what
-- lets a planner use it in an index or a constant-fold it.
--
-- Language SQL rather than plpgsql: there is nothing to raise about. Any
-- pair of times is a legitimate shift now, which is exactly the change
-- this migration makes, so unlike to_base_qty there is no input it must
-- refuse. A null argument answers null, which is SQL's own convention
-- for "no answer" and is unreachable in any case — all three columns are
-- NOT NULL.
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION shift_ends_at(starts_on DATE, starts_at TIME, ends_at TIME)
RETURNS TIMESTAMP AS $$
    SELECT starts_on + ends_at
        + CASE WHEN ends_at <= starts_at THEN INTERVAL '1 day' ELSE INTERVAL '0 day' END;
$$ LANGUAGE SQL IMMUTABLE;

COMMENT ON FUNCTION shift_ends_at(DATE, TIME, TIME) IS
    'The moment a shift ends, on the temple''s own wall clock — the next day when end_time is at or before start_time (T-146). Mirrors ShiftWindow.endsAt() in Java and crossesMidnight() in frontend/lib/format.ts. The matching start instant is just shift_date + start_time and needs no function: shift_date is the date the shift starts.';
