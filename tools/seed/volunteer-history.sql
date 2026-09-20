-- =====================================================================
-- Volunteer history — who put their name down for the shifts that have already happened,
-- and who actually turned up.
--
--   docker exec -i kms-postgres psql -U kms_migration -d kms_seed \
--     -v tenant=f935450b-1b7c-4b2c-a7e3-73e40c7e31e3 -f - < tools/seed/volunteer-history.sql
--
-- ---------------------------------------------------------------------
-- Why this cannot be done through the application, and why that is the application being right
--
-- A volunteer signs themselves up: `POST /api/v1/shifts/{id}/signup` takes no body and acts on
-- the caller. It refuses a shift that has already started — `KMS-400059` — and it should. Nobody
-- volunteers for last Tuesday, and an endpoint that allowed it would let a coordinator invent
-- attendance for somebody who was never there.
--
-- The simulation's window is four weeks, and all but the last few days of it are in the past. So
-- every festival service, every Sunday lunch, every shift the temple has actually worked is
-- closed to sign-up, and the volunteer screens show a month of empty rotas and no history of
-- anyone ever having turned up. That is not what the feature does; it is an artefact of building
-- a month of history in an afternoon.
--
-- This is the third and last piece of SQL in tools/seed/, and it is here for the same reason the
-- other two are: the act is real, the application has no path for it, and it should not have one.
--
-- ---------------------------------------------------------------------
-- What it writes
--
-- One `shift_signups` row per person per past shift, carrying:
--
--   signed_up_at            an evening before the shift, never after it started
--   source                  SIGNUP — these people put their own names down
--   attended                true, false, or left null where nobody marked the register
--   attendance_recorded_at  after the shift ended, never in the future
--
-- It writes nothing else. It does not create shifts, does not touch the ones still to come, and
-- does not invent volunteers: the people are the temple's real volunteer accounts.
--
-- ---------------------------------------------------------------------
-- Why the roster is uneven on purpose
--
-- A screen where every shift is full teaches a coordinator nothing, and neither does one where
-- every shift is a third full. What that screen is *for* is recognising names and spotting the
-- gaps, so the history is shaped:
--
--   * **The feast fills.** It is what a festival day is for, so it asks first and takes its
--     twelve, and the lunch running in the same hours takes whoever is left.
--   * **The early service is thinner.** Fewer people are in the kitchen at five in the morning
--     than at midday, and a breakfast service reading twelve of twelve would be the kind of
--     number that makes a screen unbelievable.
--   * **Ordinary Sundays vary**, and one of them is nearly empty, because that happens.
--   * **A few regulars appear on almost everything.** Each volunteer gets a keenness, and the
--     keen ones are picked first for every shift, so the same names recur down the page. That is
--     what makes a coordinator's screen useful rather than a list of strangers.
--   * **Somebody did not turn up**, and one whole service was never marked at all. Attendance is
--     three-valued — came, did not come, nobody said — and a history with only the first of those
--     does not exercise the screen.
--
-- **The ceiling is the volunteer roll, not this script.** A festival day is four services of
-- twelve places; a temple with seven volunteers on its books cannot fill them however the rota is
-- written, and the answer is more volunteers rather than a larger number here. Ten more joined
-- through the application (`04a-more-volunteers.py`) before this script was first run for that
-- reason.
--
-- ---------------------------------------------------------------------
-- The guards, and what each of them is actually protecting against
--
-- Written after the lesson recorded in backdate.sql: a guard only checks the pair you were
-- thinking about. These check every relationship this script creates or depends on.
--
--   1. Nobody signed up after the shift had started. The thing the API refuses; if this script
--      produced it, it would be doing the exact harm the refusal exists to prevent.
--   2. Nobody is marked as attending a shift they never signed up for. Structurally impossible
--      here — attendance lives on the sign-up row — but asserted because a later edit could add
--      an attendance-only path and nothing else would notice.
--   3. No shift has more people on it than it has places.
--   4. Nobody is in two places at once: no volunteer holds two shifts whose times overlap.
--   5. Nothing is dated in the future: no sign-up, no attendance mark.
--   6. Attendance is never recorded before the shift ended.
--   7. No shift that has not finished has been given attendance — including one running right
--      now. Those are the ones volunteers are still signing up for through the app.
--
-- Any of them fires and the whole transaction rolls back.
-- =====================================================================

\set ON_ERROR_STOP on

BEGIN;

SET LOCAL app.tenant_id = :'tenant';

DO $volunteers$
DECLARE
    v_tenant uuid := current_setting('app.tenant_id')::uuid;
    v_rows   bigint;
    v_total  bigint := 0;
    v_people int;
    v_shifts int;
    v_taken  int;
    v_unmarked boolean;
    v_shift  record;
    v_person record;
BEGIN
    -- --- never as a superuser ---------------------------------------------
    IF (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION
            'Refusing to run as the superuser %. A superuser bypasses row-level security, so '
            'nothing would confine this to one temple. Run it as kms_migration.', current_user;
    END IF;

    SELECT count(*) INTO v_people
    FROM users WHERE tenant_id = v_tenant AND role = 'VOLUNTEER' AND status = 'ACTIVE';
    IF v_people = 0 THEN
        RAISE EXCEPTION 'This temple has no volunteers, so it can have no volunteer history.';
    END IF;

    SELECT count(*) INTO v_shifts
    FROM shifts
    WHERE tenant_id = v_tenant AND (shift_date::timestamptz + start_time) < now();
    RAISE NOTICE 'Volunteers: %. Shifts already past: %.', v_people, v_shifts;

    -- ---------------------------------------------------------------
    -- Who signed up for what.
    --
    -- Shift by shift, in the order they happened, because whether somebody can take this one
    -- depends on what they already took. A single set-based INSERT cannot see its own rows, and
    -- the first version of this script did exactly that: it put all seven volunteers on all four
    -- Janmastami services and guard 4 rightly threw the lot away. Fifteen shifts and seven people
    -- is a small enough loop to be worth the clarity.
    --
    -- **Keenness** is each volunteer's place in the queue, derived from their own account id so
    -- the script names nobody and works on a temple whose volunteers are different people. The
    -- keen ones get asked first for every shift, so the same names recur down the page — which is
    -- what makes a coordinator's screen useful rather than a list of strangers. The order is
    -- rotated by the shift's place in its day so the three morning services do not all draw the
    -- same three people.
    --
    -- **How many** a shift draws is decided from the shift itself, never from a counter, so a
    -- re-run makes exactly the same choices.
    -- ---------------------------------------------------------------
    FOR v_shift IN
        SELECT s.id, s.shift_date, s.start_time, s.end_time, s.capacity, s.title,
               (row_number() OVER (PARTITION BY s.shift_date
                                   ORDER BY COALESCE(mk.sort_order, 0) DESC,
                                            s.start_time, s.title, s.id))::int AS seq,
               -- How many a service draws.
               --
               -- A festival service takes as many as will fit, except an early one: fewer people
               -- are in the temple kitchen at five in the morning than at midday, and pretending
               -- otherwise is the kind of number that makes a screen unbelievable. Whoever is left
               -- over after the day's main service has taken its fill is what the services running
               -- in the same hours get, which is why the order below matters.
               --
               -- A Sunday lunch varies with the date: some weeks four people come, one week only
               -- one does, and both of those are true of a real Sunday.
               CASE
                   WHEN s.capacity >= 10 AND s.start_time < TIME '07:00' THEN
                        GREATEST(1, LEAST(s.capacity, CEIL(v_people::numeric / 2)::int))
                   WHEN s.capacity >= 10 THEN
                        GREATEST(1, LEAST(s.capacity, v_people))
                   ELSE GREATEST(1, LEAST(s.capacity, EXTRACT(DOY FROM s.shift_date)::int % 5))
               END AS wanted
        FROM shifts s
        LEFT JOIN meals m ON m.id = s.meal_id
        LEFT JOIN meal_kinds mk ON mk.id = m.meal_kind_id
        WHERE s.tenant_id = v_tenant
          -- Already started, which is not the same as "before today". Today's Sunday lunch began
          -- at half past nine and is as closed to sign-up as last week's; leaving it out put a
          -- "volunteers: 0" against today on the Temple Admin's own landing screen while six
          -- staff were in.
          AND (s.shift_date::timestamptz + s.start_time) < now()
          AND s.status <> 'CANCELLED'
          AND NOT EXISTS (SELECT 1 FROM shift_signups g
                           WHERE g.shift_id = s.id AND g.released_at IS NULL)
        -- Within a day, the biggest service asks first. `meal_kinds.sort_order` runs breakfast,
        -- lunch, dinner, festival feast, so descending puts the feast at the head of the queue —
        -- which is right, because the feast is what the day is for, and the lunch running in the
        -- same hours takes whoever the feast did not.
        ORDER BY s.shift_date, COALESCE(mk.sort_order, 0) DESC, s.start_time, s.title, s.id
    LOOP
        v_taken := 0;
        -- One service in the month goes unmarked: the coordinator meant to do the register and
        -- never did. Keyed on the shift rather than the day, because a whole festival with no
        -- attendance at all reads as a bug rather than as somebody forgetting.
        -- A shift still running has no register yet, and guard 6 would rightly refuse one dated
        -- before it ended. Today's lunch service is in this state every time this runs.
        v_unmarked := ((EXTRACT(DAY FROM v_shift.shift_date)::int % 7) = 4 AND v_shift.seq = 2)
                      OR (v_shift.shift_date::timestamptz + v_shift.end_time) > now();

        FOR v_person IN
            SELECT u.id,
                   (row_number() OVER (ORDER BY ('x' || substr(md5(u.id::text), 1, 8))::bit(32)::int,
                                                u.id))::int AS keenness
            FROM users u
            WHERE u.tenant_id = v_tenant AND u.role = 'VOLUNTEER' AND u.status = 'ACTIVE'
            ORDER BY (((row_number() OVER (ORDER BY ('x' || substr(md5(u.id::text), 1, 8))::bit(32)::int,
                                                    u.id))::int + v_shift.seq) % v_people),
                     ('x' || substr(md5(u.id::text), 1, 8))::bit(32)::int
        LOOP
            EXIT WHEN v_taken >= v_shift.wanted;

            -- Nobody is in two places at once. The application only *warns* about an overlap
            -- (`signup` answers with `overlapWarning`) and lets a person decide; a script has
            -- nobody to ask, so it declines for them.
            CONTINUE WHEN EXISTS (
                SELECT 1 FROM shift_signups g JOIN shifts o ON o.id = g.shift_id
                WHERE g.tenant_id = v_tenant
                  AND g.volunteer_user_id = v_person.id
                  AND g.released_at IS NULL
                  AND o.shift_date = v_shift.shift_date
                  AND o.start_time < v_shift.end_time
                  AND v_shift.start_time < o.end_time);

            INSERT INTO shift_signups (
                tenant_id, shift_id, volunteer_user_id, signed_up_at, source,
                attended, attendance_recorded_at)
            VALUES (
                v_tenant, v_shift.id, v_person.id,
                -- Put your name down a few evenings before, at the hour people actually do it.
                -- The keener you are the earlier you commit, and the minutes keep two sign-ups
                -- from sharing a timestamp.
                ((v_shift.shift_date - (2 + (v_person.keenness % 4)))::timestamptz + TIME '20:40')
                    - (v_person.keenness || ' minutes')::interval,
                'SIGNUP',
                -- The register is three-valued — came, did not come, nobody said — and a history
                -- with only the first of those does not exercise the screen. So one shift a month
                -- goes unmarked, and one regular misses one shift.
                CASE
                    WHEN v_unmarked THEN NULL
                    WHEN v_person.keenness = 3
                         AND (EXTRACT(DAY FROM v_shift.shift_date)::int % 11) = 5 THEN false
                    ELSE true
                END,
                CASE
                    WHEN v_unmarked THEN NULL
                    ELSE LEAST(v_shift.shift_date::timestamptz + v_shift.end_time
                                   + INTERVAL '25 minutes',
                               now())
                END);
            v_taken := v_taken + 1;
            v_total := v_total + 1;
        END LOOP;
    END LOOP;
    RAISE NOTICE 'Sign-ups written for past shifts: %', v_total;

    -- =================================================================
    -- The guards. Any one of them rolls the whole thing back.
    -- =================================================================

    -- 1. nobody signed up after the shift had started
    PERFORM 1
    FROM shift_signups g JOIN shifts s ON s.id = g.shift_id
    WHERE g.tenant_id = v_tenant
      AND g.signed_up_at >= (s.shift_date::timestamptz + s.start_time)
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'Somebody signed up for a shift that had already started. Rolled back.';
    END IF;

    -- 2. nobody is marked as attending a shift they never signed up for
    PERFORM 1
    FROM shift_signups g
    WHERE g.tenant_id = v_tenant AND g.attended IS NOT NULL AND g.released_at IS NOT NULL
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION
            'Somebody is marked present on a shift they had been released from. Rolled back.';
    END IF;

    -- 3. no shift has more people on it than it has places
    PERFORM 1
    FROM shifts s
    WHERE s.tenant_id = v_tenant
      AND (SELECT count(*) FROM shift_signups g
            WHERE g.shift_id = s.id AND g.released_at IS NULL) > s.capacity
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'A shift has more volunteers on it than it has places. Rolled back.';
    END IF;

    -- 4. nobody is in two places at once
    PERFORM 1
    FROM shift_signups a
    JOIN shifts sa ON sa.id = a.shift_id
    JOIN shift_signups b ON b.volunteer_user_id = a.volunteer_user_id AND b.id <> a.id
    JOIN shifts sb ON sb.id = b.shift_id
    WHERE a.tenant_id = v_tenant
      AND a.released_at IS NULL AND b.released_at IS NULL
      AND sa.shift_date = sb.shift_date
      AND sa.start_time < sb.end_time
      AND sb.start_time < sa.end_time
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION
            'One volunteer is on two shifts whose times overlap. Rolled back.';
    END IF;

    -- 5. nothing is dated in the future
    PERFORM 1 FROM shift_signups
    WHERE tenant_id = v_tenant
      AND (signed_up_at > now() OR attendance_recorded_at > now())
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'A sign-up or an attendance mark is dated in the future. Rolled back.';
    END IF;

    -- 6. attendance is never recorded before the shift ended
    PERFORM 1
    FROM shift_signups g JOIN shifts s ON s.id = g.shift_id
    WHERE g.tenant_id = v_tenant
      AND g.attendance_recorded_at IS NOT NULL
      AND g.attendance_recorded_at < (s.shift_date::timestamptz + s.end_time)
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION
            'Attendance was recorded before the shift had finished. Rolled back.';
    END IF;

    -- 7. no shift still to come has been given attendance
    PERFORM 1
    FROM shift_signups g JOIN shifts s ON s.id = g.shift_id
    WHERE g.tenant_id = v_tenant
      AND (s.shift_date::timestamptz + s.end_time) > now()
      AND g.attended IS NOT NULL
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION
            'A shift that has not finished has attendance against it. Rolled back.';
    END IF;

    RAISE NOTICE 'Volunteer history written: % row(s).', v_total;
END
$volunteers$;

COMMIT;

-- ---------------------------------------------------------------------
-- What a coordinator would now see.
--
-- The SET is not a repeat of the one above: that one was SET LOCAL and went out with the
-- transaction, so without this the reporting queries run with no tenant and row-level security
-- correctly shows them nothing.
-- ---------------------------------------------------------------------
SET app.tenant_id = :'tenant';
\pset border 2

SELECT s.shift_date, s.title, s.capacity AS places,
       count(g.id) FILTER (WHERE g.released_at IS NULL)  AS signed_up,
       count(g.id) FILTER (WHERE g.attended IS TRUE)     AS turned_up,
       count(g.id) FILTER (WHERE g.attended IS FALSE)    AS did_not,
       count(g.id) FILTER (WHERE g.attended IS NULL)     AS not_marked
FROM shifts s
LEFT JOIN shift_signups g ON g.shift_id = s.id
WHERE s.tenant_id = :'tenant'
GROUP BY s.shift_date, s.title, s.capacity, s.start_time
ORDER BY s.shift_date, s.start_time;

SELECT u.full_name AS volunteer,
       count(*) FILTER (WHERE g.released_at IS NULL) AS shifts_taken,
       count(*) FILTER (WHERE g.attended IS TRUE)    AS turned_up,
       count(*) FILTER (WHERE g.attended IS FALSE)   AS did_not
FROM shift_signups g
JOIN users u ON u.id = g.volunteer_user_id
WHERE g.tenant_id = :'tenant'
GROUP BY u.full_name
ORDER BY 2 DESC, u.full_name;
