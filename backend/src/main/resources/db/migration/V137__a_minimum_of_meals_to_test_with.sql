-- =====================================================================
-- V137 — A minimum of meals and shifts to test with, after the reset (D-27 answer 9, T-195)
--
-- WHY THIS EXISTS
--
-- V135 emptied every meal and every volunteer shift so that V136 could give a meal a row of its own.
-- Rajeev asked for that in the same breath as asking for enough to be put back to test on:
--
--     "it does not matter if we have to nuke all data from Isckon south bangalore and do this change
--      and then seed a minimum data set back to it to make it usable for my UAT testing."
--
-- and approved exactly this list (D-27, answer 9): the next 7 days of Breakfast, Lunch and Dinner with
-- 3–4 dishes each from the temple's own recipes; one named event that week; one meal with a volunteer
-- shift a test volunteer account has signed up for; two plain shifts; and the previous 2 days cooked
-- and recorded, so job cards, cost per serving and menu history have something to show.
--
-- ---------------------------------------------------------------------
-- WHO GETS IT
--
-- Only a temple that has recipes of its own — rows in its recipes table, including copies it took
-- from the library, which V69 records as "wholly the temple's". The shared library (master_recipes)
-- is never planned from here. A temple with no recipes gets nothing at all: a meal is its dishes, and
-- a meal with none is not a plan anybody made. That is the whole of production today, and it is the
-- temple TenantLoopMigrationIT puts in the database; on staging it is ISKCON South Bengaluru.
--
-- A temple also needs a person to have planned it, because meal_dishes.created_by and
-- shifts.created_by are NOT NULL. The Temple Admin is used, or a Kitchen Manager where there is no
-- admin; a temple with neither is skipped with a notice rather than attributed to a volunteer.
--
-- Every row is written with app.tenant_id set to its temple, one temple at a time, because every
-- table here is under FORCE ROW LEVEL SECURITY and the migration role is not a superuser: an insert
-- without it is refused by the policy's WITH CHECK.
--
-- ---------------------------------------------------------------------
-- DATES ARE THE TEMPLE'S OWN
--
-- "Today" is the migration's run date on the temple's wall clock (tenants.timezone), the same rule
-- TempleClock applies in Java — a migration applied at 01:00 in Bengaluru is 19:30 the previous day
-- in UTC, and seeding "yesterday" from the server's clock would land the cooked meals on the wrong
-- days. The seven days are today and the six after it; the two cooked days are the two before today.
--
-- The day type is worked out the way MealPlanService.dayContext works it out: FESTIVAL where one of
-- the temple's occasions resolves on the date (OccasionService.resolve: a MANUAL occasion on its fixed
-- month and day, a COMPUTED one whose match text appears in that day's calendar festivals, the first
-- by name winning), otherwise WEEKEND on a Saturday or Sunday and REGULAR on any other day. The
-- occasion's name is written on each meal of that day, as the planner prefills it.
--
-- On an Ekadashi the planner asks for an acknowledgement before a recipe with a grain in it is saved
-- (EkadashiPolicy: an ingredient with is_ekadashi_prohibited). A migration cannot acknowledge for a
-- person, so on such a day the recipes it picks from leave those out, and nothing seeded carries an
-- acknowledgement nobody gave. A day with no permitted recipe gets no meals and no day row.
--
-- ---------------------------------------------------------------------
-- WHAT IS NOT WRITTEN, ON PURPOSE
--
--   * No stock movements. The two cooked days are recorded with no draws. Recording a meal draws its
--     recipes' ingredients through FEFO and a unit conversion per line, and the only honest way to do
--     that is the application's own code; writing it here in SQL would be the hand-written unit
--     arithmetic BaseQuantityIT exists to forbid, and it would move every on-hand figure that V135
--     took care to keep exactly still. So on hand across V135, V136 and V137 is identical, and the
--     cooked days read as meals the store room was not asked to account for. This was the work
--     manager's call in the build brief.
--   * No job card PDFs. A card NUMBER is issued for each recorded meal, from the counter V135 reset,
--     because that is what a printed-and-returned sheet has; card_version stays 0 and the fingerprint
--     empty, which is exactly the state ServedMealService leaves between issuing a number and the first
--     print, so the first print of a seeded card is version 1.
--   * No reminders. A reminder is a Quartz job holding a serialized JobDataMap, scheduled by
--     ShiftReminderScheduler at signup; SQL cannot write one honestly. The seeded signup therefore
--     sends no reminder unless it is touched through the application.
--   * No audit entries. Nobody did these things; an audit row saying somebody did would be the one
--     false record in a table whose whole value is that it is not.
--
-- ---------------------------------------------------------------------
-- WHAT IS WRITTEN
--
--   meal_plan_days  today-2 .. today+6, where the day has a recipe it may use
--   meals           Breakfast, Lunch and Dinner on each of those days (by meal_kinds.id, looked up by
--                   name, and skipped where the temple has no kind of that name); and one named event,
--                   "Children's Bhagavad-gita Reading", three days out, on the temple's first event kind
--   meal_dishes     3 or 4 per meal, alternating, from the temple's active recipes in name order,
--                   rotating so neighbouring meals differ; no recipe twice in one meal. Target is the
--                   recipe's own base yield, in its own unit — no conversion, and always positive
--   cooked days     each dish COOKED with actual and consumed equal to the target, cooked at the meal's
--                   ready-by; each meal recorded two hours later and carrying a card number
--   shifts          "Kitchen help for Lunch" tomorrow, three hours up to Lunch's ready-by, linked to
--                   that meal, with ikms.volunteer.1@trading4good.org signed up where that account is
--                   one of this temple's; "Garland making" and "Crowd control", not for a meal
-- =====================================================================


-- ---------------------------------------------------------------------
-- The card prefix, as ServedMealService.cardPrefix makes it: the first letter of each word, up to two;
-- a one-word kind gets C for card after its initial; a name with no letters is MC. A reading aid on the
-- sheet, never an identity. Dropped at the end of this file.
-- ---------------------------------------------------------------------
CREATE FUNCTION meal_reseed_card_prefix(p_kind_name text)
RETURNS text
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE length(i.initials)
               WHEN 0 THEN 'MC'
               WHEN 1 THEN i.initials || 'C'
               ELSE i.initials
           END
    FROM (
        SELECT left(COALESCE(string_agg(upper(substring(w.word FROM '[[:alpha:]]')), '' ORDER BY w.n), ''), 2)
                   AS initials
        FROM regexp_split_to_table(btrim(p_kind_name), '\s+') WITH ORDINALITY AS w(word, n)
    ) i;
$$;


-- ---------------------------------------------------------------------
-- One meal and its dishes. Returns the meal's id. Dropped at the end of this file.
-- ---------------------------------------------------------------------
CREATE FUNCTION meal_reseed_meal(
    p_tenant      uuid,
    p_day_id      uuid,
    p_date        date,
    p_timezone    text,
    p_kind_id     uuid,
    p_kind_name   text,
    p_event_name  text,
    p_occasion    text,
    p_ready_by    time,
    p_adults      int,
    p_children    int,
    p_seniors     int,
    p_crew        int,
    p_pool        uuid[],
    p_dishes      int,
    p_rotation    int,
    p_cooked      boolean,
    p_actor       uuid)
RETURNS uuid
LANGUAGE plpgsql
AS $$
DECLARE
    v_meal      uuid;
    v_size      int := array_length(p_pool, 1);
    v_count     int := least(p_dishes, array_length(p_pool, 1));
    v_ready_at  timestamptz := (p_date + p_ready_by) AT TIME ZONE p_timezone;
    v_seq       int;
    j           int;
BEGIN
    INSERT INTO meals (tenant_id, meal_plan_day_id, meal_kind_id, event_name, occasion_name, ready_by,
                       adults, children, seniors, crew_required)
    VALUES (p_tenant, p_day_id, p_kind_id, p_event_name, p_occasion, p_ready_by,
            p_adults, p_children, p_seniors, p_crew)
    RETURNING id INTO v_meal;

    -- j < v_count <= v_size, so the indexes are distinct and no recipe appears twice in one meal.
    FOR j IN 0 .. v_count - 1 LOOP
        INSERT INTO meal_dishes (tenant_id, meal_id, recipe_id, target_yield, status,
                                 actual_servings, consumed_quantity, cooked_at, created_by)
        SELECT p_tenant, v_meal, r.id, r.base_yield_qty,
               CASE WHEN p_cooked THEN 'COOKED' ELSE 'PLANNED' END,
               CASE WHEN p_cooked THEN r.base_yield_qty END,
               CASE WHEN p_cooked THEN r.base_yield_qty END,
               CASE WHEN p_cooked THEN v_ready_at END,
               p_actor
        FROM recipes r
        WHERE r.id = p_pool[1 + ((p_rotation + j) % v_size)];
    END LOOP;

    IF p_cooked THEN
        -- The same atomic increment ServedMealService.nextCardNumber uses, so the counter and the
        -- numbers agree exactly and the application's next card follows on from these.
        INSERT INTO meal_card_sequence (tenant_id, last_number)
        VALUES (p_tenant, 1)
        ON CONFLICT (tenant_id) DO UPDATE SET last_number = meal_card_sequence.last_number + 1
        RETURNING last_number INTO v_seq;

        UPDATE meals
        SET card_number    = meal_reseed_card_prefix(p_kind_name) || '-' || extract(year FROM p_date)::int
                                 || '-' || lpad(v_seq::text, 4, '0'),
            card_issued_at = v_ready_at - interval '3 hours',
            recorded_at    = v_ready_at + interval '2 hours',
            recorded_by    = p_actor,
            updated_at     = now()
        WHERE id = v_meal;
    END IF;

    RETURN v_meal;
END;
$$;


DO $$
DECLARE
    t              RECORD;
    k              RECORD;
    v_today        date;
    v_date         date;
    v_offset       int;
    v_actor        uuid;
    v_volunteer    uuid;
    v_occasion     text;
    v_day_type     text;
    v_ekadashi     boolean;
    v_pool         uuid[];
    v_day_id       uuid;
    v_meal_no      int;
    v_meals        int;
    v_shift_meal   uuid;
    v_shift_kind   text;
    v_shift_date   date;
    v_shift_ready  time;
    v_shift        uuid;
    v_event_kind   RECORD;
BEGIN
    FOR t IN SELECT id, timezone FROM tenants ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        IF NOT EXISTS (SELECT 1 FROM recipes WHERE status = 'ACTIVE') THEN
            CONTINUE;
        END IF;

        SELECT id INTO v_actor
        FROM users
        WHERE status = 'ACTIVE' AND role IN ('TEMPLE_ADMIN', 'KITCHEN_MANAGER')
        ORDER BY CASE role WHEN 'TEMPLE_ADMIN' THEN 0 ELSE 1 END, created_at, id
        LIMIT 1;
        IF v_actor IS NULL THEN
            RAISE NOTICE 'V137 reseed, temple %: skipped, no active Temple Admin or Kitchen Manager to plan it', t.id;
            CONTINUE;
        END IF;

        -- users.email is stored lower-case (users_email_format), so this is an exact match.
        SELECT id INTO v_volunteer
        FROM users
        WHERE email = 'ikms.volunteer.1@trading4good.org' AND status = 'ACTIVE';

        v_today   := (now() AT TIME ZONE t.timezone)::date;
        v_meal_no := 0;

        FOR v_offset IN -2 .. 6 LOOP
            v_date := v_today + v_offset;

            SELECT o.name INTO v_occasion
            FROM occasions o
            WHERE (o.type = 'MANUAL'
                   AND o.fixed_month = extract(month FROM v_date)
                   AND o.fixed_day = extract(day FROM v_date))
               OR (o.type = 'COMPUTED'
                   AND EXISTS (
                       SELECT 1 FROM calendar_days c
                       WHERE c.cal_date = v_date
                         AND lower(c.festivals::text) LIKE
                             '%' || replace(replace(replace(lower(o.match_text), '\', '\\'), '%', '\%'), '_', '\_')
                             || '%' ESCAPE '\'))
            ORDER BY o.name
            LIMIT 1;

            v_day_type := CASE
                WHEN v_occasion IS NOT NULL THEN 'FESTIVAL'
                WHEN extract(isodow FROM v_date) IN (6, 7) THEN 'WEEKEND'
                ELSE 'REGULAR'
            END;

            v_ekadashi := COALESCE((SELECT c.is_ekadashi FROM calendar_days c WHERE c.cal_date = v_date), false);

            SELECT array_agg(r.id ORDER BY r.name, r.id) INTO v_pool
            FROM recipes r
            WHERE r.status = 'ACTIVE'
              AND (NOT v_ekadashi OR NOT EXISTS (
                      SELECT 1 FROM recipe_ingredients ri
                      JOIN ingredients i ON i.id = ri.ingredient_id
                      WHERE ri.recipe_id = r.id AND i.is_ekadashi_prohibited));

            IF v_pool IS NULL THEN
                CONTINUE;
            END IF;

            INSERT INTO meal_plan_days (tenant_id, plan_date, day_type)
            VALUES (t.id, v_date, v_day_type)
            RETURNING id INTO v_day_id;

            FOR k IN
                SELECT mk.id, mk.name, mk.default_ready_time
                FROM meal_kinds mk
                WHERE lower(mk.name) IN ('breakfast', 'lunch', 'dinner')
                ORDER BY mk.sort_order, mk.id
            LOOP
                PERFORM meal_reseed_meal(
                    t.id, v_day_id, v_date, t.timezone, k.id, k.name, NULL, v_occasion,
                    COALESCE(k.default_ready_time, time '12:00'),
                    CASE lower(k.name) WHEN 'breakfast' THEN 80 WHEN 'lunch' THEN 150 ELSE 100 END,
                    CASE lower(k.name) WHEN 'breakfast' THEN 15 WHEN 'lunch' THEN 30 ELSE 20 END,
                    CASE lower(k.name) WHEN 'breakfast' THEN 10 WHEN 'lunch' THEN 20 ELSE 15 END,
                    CASE lower(k.name) WHEN 'lunch' THEN 6 END,
                    v_pool, 3 + (v_meal_no % 2), v_meal_no * 3, v_offset < 0, v_actor);
                v_meal_no := v_meal_no + 1;
            END LOOP;

            -- The week's named event, three days out, on the temple's first event kind if it has one.
            IF v_offset = 3 THEN
                SELECT mk.id, mk.name, mk.default_ready_time INTO v_event_kind
                FROM meal_kinds mk
                WHERE mk.is_event
                ORDER BY mk.sort_order, mk.id
                LIMIT 1;
                IF FOUND THEN
                    PERFORM meal_reseed_meal(
                        t.id, v_day_id, v_date, t.timezone, v_event_kind.id, v_event_kind.name,
                        'Children''s Bhagavad-gita Reading', v_occasion,
                        COALESCE(v_event_kind.default_ready_time, time '17:00'),
                        30, 40, 5, NULL, v_pool, 3, v_meal_no * 3, false, v_actor);
                    v_meal_no := v_meal_no + 1;
                END IF;
            END IF;
        END LOOP;

        -- --- The meal shift: tomorrow's Lunch, or the earliest planned everyday meal after today ------
        SELECT m.id, mk.name, d.plan_date, m.ready_by
          INTO v_shift_meal, v_shift_kind, v_shift_date, v_shift_ready
        FROM meals m
        JOIN meal_plan_days d ON d.id = m.meal_plan_day_id
        JOIN meal_kinds mk ON mk.id = m.meal_kind_id
        WHERE d.plan_date > v_today AND m.event_name IS NULL
        ORDER BY (d.plan_date = v_today + 1 AND lower(mk.name) = 'lunch') DESC, d.plan_date, mk.sort_order
        LIMIT 1;

        IF v_shift_meal IS NOT NULL THEN
            -- Three hours up to the ready-by. Time arithmetic wraps at midnight, and a shift that runs
            -- through midnight is valid since V127, so an early ready-by still makes a real shift.
            INSERT INTO shifts (tenant_id, title, description, shift_date, start_time, end_time, location,
                                capacity, created_by, meal_id)
            VALUES (t.id, 'Kitchen help for ' || v_shift_kind,
                    'Cutting vegetables before cooking, and washing up afterwards.',
                    v_shift_date, v_shift_ready - interval '3 hours', v_shift_ready, 'Temple kitchen',
                    4, v_actor, v_shift_meal)
            RETURNING id INTO v_shift;

            IF v_volunteer IS NOT NULL THEN
                INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id)
                VALUES (t.id, v_shift, v_volunteer);
            END IF;
        END IF;

        -- --- Two shifts that are not for a meal -------------------------------------------------------
        INSERT INTO shifts (tenant_id, title, description, shift_date, start_time, end_time, location,
                            capacity, created_by)
        VALUES
            (t.id, 'Garland making', 'Stringing flower garlands for the deities.',
             v_today + 2, time '06:00', time '09:00', 'Temple hall', 6, v_actor),
            (t.id, 'Crowd control', 'Guiding visitors during the evening programme.',
             v_today + 4, time '17:00', time '20:00', 'Temple entrance', 10, v_actor);

        SELECT count(*) INTO v_meals FROM meals;
        RAISE NOTICE 'V137 reseed, temple %: % meals from % to %, meal shift %, volunteer signed up: %',
            t.id, v_meals, v_today - 2, v_today + 6, v_shift, v_volunteer IS NOT NULL AND v_shift IS NOT NULL;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);
END $$;

DROP FUNCTION meal_reseed_meal(uuid, uuid, date, text, uuid, text, text, text, time, int, int, int, int,
                               uuid[], int, int, boolean, uuid);
DROP FUNCTION meal_reseed_card_prefix(text);
