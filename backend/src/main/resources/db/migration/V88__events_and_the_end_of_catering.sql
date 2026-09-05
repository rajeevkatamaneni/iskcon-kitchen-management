-- =====================================================================
-- V88 — Events, and the end of catering (E4-S15)
--
-- There are three main meals a day. Breakfast, Lunch and Dinner, cooked 365
-- days a year for a small army. Everything else the temple cooks — a Bhajan
-- Prasadam, a Saturday Bhagavad-gita reading for the children, food going to a
-- school, a delivery to a community programme — is an EVENT, and until now had
-- nowhere to go. It was folded into whichever main meal it sat nearest, which
-- destroys the information three ways over: thirty children averaged into two
-- hundred residents makes the head count meaningless, the stock the event drew
-- becomes invisible because it was consumed as *breakfast*, and afterwards
-- nobody can answer what the Saturday readings cost. Adding them together does
-- not muddy the data. It destroys it.
--
-- So this migration does four things.
--
--   1. meal_kinds gains is_event and loses needs_client, needs_venue and
--      needs_purpose. The three flags each described one corner of the
--      outside-event shape; the Event kind owns that shape whole. needs_occasion
--      stays — the Festival feast still uses it (V67).
--
--      KEEPING needs_client AND SETTING IT TRUE FOR EVENT WAS REJECTED. An
--      in-house Bhajan Prasadam has no client, and a form that asked for a
--      contact for one would be asking a question with no answer — which then
--      either gets a made-up answer or blocks the save.
--
--   2. meal_plans gains the event's own fields, and the three catering columns
--      are RENAMED rather than dropped and recreated (E4-S15 D7). A rename
--      carries every row with it and cannot lose one; a drop-and-add is two
--      statements with a temple's history in the gap between them. Those rows
--      are meals the temple actually cooked, for people it actually cooked for.
--
--   3. *Catering order* and *Outside event* fold into a single Event kind per
--      tenant, and their plans migrate onto it marked as going outside. A temple
--      that does catering now plans a catering EVENT and gains six fields by it.
--      DELETING THE CATERING ROWS WAS REJECTED OUTRIGHT.
--
--   4. day_type loses CATERING. It was written by the application and read by
--      nothing: no frontend caller ever passed dayType, MealCrewService only
--      ever excluded it, and the *Upcoming catering* table it was meant to feed
--      was never built. Its replacement, *Upcoming outside commitments*, keys
--      off is_outside instead — so the school delivery and the community
--      programme are in it too, and those are exactly as easy to forget on the
--      morning as a wedding.
--
-- The data work runs one tenant at a time. meal_kinds and meal_plans are both
-- tenant-owned and carry FORCE ROW LEVEL SECURITY, so this migration's own
-- statements are filtered by the isolation policy exactly as the application's
-- are: a cross-tenant INSERT is refused outright, and a cross-tenant UPDATE is
-- worse — it silently matches nothing and reports success. V48 and V67 adopt
-- each tenant in turn for the same reason, and this follows them.
-- =====================================================================

-- --- 1. meal_kinds: one flag where there were three --------------------------
--
-- Added before the fold, because the fold sets it; the three old flags are
-- dropped after it, because the fold reads them to decide which kinds go.
ALTER TABLE meal_kinds ADD COLUMN is_event BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN meal_kinds.is_event IS
    'Meals of this kind are events (E4-S15): an occasion with its own name, its own dishes and its own quantities, quantified by how much to make rather than by a head count. Reveals the event name and "is this going outside?" on the planner, and nothing else until the answer is yes.';

-- --- 2. meal_plans: what an event is, and where it is going -------------------
--
-- Renamed, never dropped. The words change because the concept did: an event
-- has a CONTACT — somebody to ring — rather than a client, which implied
-- somebody paying, and it has a DELIVERY ADDRESS rather than a venue, which
-- implied a hall. Nothing about the values changes, and no row moves.
ALTER TABLE meal_plans RENAME COLUMN client_name    TO contact_name;
ALTER TABLE meal_plans RENAME COLUMN client_contact TO contact_phone;
ALTER TABLE meal_plans RENAME COLUMN venue          TO delivery_address;

COMMENT ON COLUMN meal_plans.contact_name IS
    'Who to ring about an event that is going outside the temple (E4-S15). Was client_name: an event has somebody to ring, not necessarily somebody paying.';
COMMENT ON COLUMN meal_plans.contact_phone IS
    'The contact''s number. Required with the name for an outside event — a contact you cannot ring is not a contact.';
COMMENT ON COLUMN meal_plans.delivery_address IS
    'Where a delivered event''s food is going. Was venue. Asked for only on a delivery: somebody collecting their own food does not need us to know where they are taking it.';

ALTER TABLE meal_plans
    ADD COLUMN event_name    TEXT
        CHECK (event_name IS NULL OR length(event_name) <= 200),
    ADD COLUMN is_outside    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN handover      TEXT
        CHECK (handover IS NULL OR handover IN ('PICKUP', 'DELIVERY')),
    ADD COLUMN guests_eat_at TIME;

COMMENT ON COLUMN meal_plans.event_name IS
    'What this event is called — "Children''s Bhagavad-gita Reading" (E4-S15). The name is the whole point of splitting events out of the main meals: it is what makes the Saturday reading a thing the kitchen can see, cost and record.';
COMMENT ON COLUMN meal_plans.is_outside IS
    'This food leaves the temple. What *Upcoming outside commitments* is keyed off, deliberately rather than off "is this catering" — a school delivery is as easy to forget on the morning as a wedding.';
COMMENT ON COLUMN meal_plans.handover IS
    'PICKUP (somebody collects it) or DELIVERY (we take it). NULL on an in-house event, and NULL on the outside plans this migration carried across, which predate the question — the migration does not invent an answer nobody gave.';
COMMENT ON COLUMN meal_plans.guests_eat_at IS
    'The local time the guests actually sit down to eat, on a delivery (E4-S15 D6). Not the ready-by: E4-S16 works backwards from THIS to say when to leave the temple, and a driver can act on "leave by 11:15" where nobody can act on "37 minutes".';

-- --- 2b. Where the delivery address is, and when we last asked ----------------
--
-- Coordinates only. NO TRAVEL DURATION IS STORED HERE OR ANYWHERE (E4-S16 D4):
-- Maps Platform ToS §3.2.3(b) is "no caching except as expressly permitted", and
-- the Routes clause (§19.3) expressly permits caching latitude and longitude
-- ONLY, conspicuously omitting the duration values the Navigation Connect clause
-- (§11.8) does permit. A travel-time cache table was designed and abandoned on
-- that reading. The estimate is computed when the event is shown and held no
-- longer than the delivery.
--
-- geocoded_at is what makes the 30-day life (§6.3.1) enforceable: coordinates
-- older than that are looked up again rather than reused. §6.3.2's indefinite
-- permission is deliberately not relied on — it requires the cache to be
-- isolated to one end user, and ours is read by everyone at the temple.
ALTER TABLE meal_plans
    ADD COLUMN delivery_latitude  NUMERIC(9, 6)
        CHECK (delivery_latitude  IS NULL OR delivery_latitude  BETWEEN -90  AND 90),
    ADD COLUMN delivery_longitude NUMERIC(9, 6)
        CHECK (delivery_longitude IS NULL OR delivery_longitude BETWEEN -180 AND 180),
    ADD COLUMN geocoded_at        TIMESTAMPTZ;

COMMENT ON COLUMN meal_plans.geocoded_at IS
    'When the delivery address was last turned into coordinates (E4-S16 D4). Older than 30 days and it is looked up again — the licence to keep coordinates runs out, and a street may have moved on anyway.';

-- --- 3. Fold the two kinds into one Event, and carry their plans across -------
--
-- Which kinds go: those carrying needs_client or needs_venue. That is *Catering
-- order* and *Outside event* on every temple provisioned by V48, and it also
-- catches a temple's own kind of the same shape — one named "Wedding catering"
-- with needs_venue set, say.
--
-- MATCHING ON THE TWO SEEDED NAMES ALONE WAS REJECTED. The three flags are
-- dropped at the end of this migration, so a temple-made kind carrying them
-- would come out the other side asking for nothing at all: a kind that used to
-- insist on a venue quietly stops insisting, and nobody is told. Folding it into
-- Event keeps the shape it was built for. A kind with none of the flags is not
-- touched, whatever it is called.
--
-- The plans keep everything they had — contact, phone, address, head count,
-- dishes, quantities, ready-by — and gain is_outside, because every one of them
-- was food leaving the temple. handover stays NULL: nobody was ever asked
-- whether it was collected or delivered, and a guess here would be a fact the
-- temple never stated.
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        -- The Event kind itself. sort_order 50 puts it last, after the deity
        -- offering: the picker reads Breakfast, Lunch, Dinner, Festival feast,
        -- Deity Offering, Event. No default ready time — an event is never at the
        -- same hour twice, so it always asks, which is V48's rule for the
        -- occasional kinds and item 26's for the feast.
        INSERT INTO meal_kinds (
            tenant_id, name, sort_order, default_ready_time,
            needs_client, needs_venue, needs_purpose, needs_occasion, is_event)
        SELECT t.id, 'Event', 50, NULL::time, false, false, false, false, true
        WHERE NOT EXISTS (
            SELECT 1 FROM meal_kinds mk WHERE lower(mk.name) = 'event');

        -- A temple that already had a kind of its own called Event keeps its own
        -- sort order and its own hour; all it gains is the flag.
        UPDATE meal_kinds SET is_event = true WHERE lower(name) = 'event';

        -- The plans of the outgoing kinds become Events going outside. Matched on
        -- the kind NAME because that is how a plan records its kind — by name, not
        -- by reference (V48), so a kind can be renamed or removed without
        -- orphaning a meal.
        UPDATE meal_plans mp
        SET meal_kind  = 'Event',
            is_outside = true,
            -- The old free-text purpose becomes the name, where the name is empty.
            -- It is the only sentence anybody ever wrote about what that food was
            -- for, and it is exactly what the name field now holds. Where there
            -- was no purpose the name stays empty rather than being invented: the
            -- planner will be asked for one the next time the plan is edited.
            event_name = COALESCE(
                NULLIF(btrim(mp.event_name), ''),
                NULLIF(btrim(mp.purpose), ''))
        WHERE lower(mp.meal_kind) IN (
                SELECT lower(mk.name) FROM meal_kinds mk
                WHERE (mk.needs_client OR mk.needs_venue) AND lower(mk.name) <> 'event');

        DELETE FROM meal_kinds
        WHERE (needs_client OR needs_venue) AND lower(name) <> 'event';

        -- 4. And the day type they were stamped with. A catering day was never a
        -- kind of day at all — it was a fact about the meal, which is why V48
        -- moved catering into the kinds and left this behind. What sort of day it
        -- actually was is the weekday, and that is recoverable from the date
        -- exactly, so nothing is guessed: Saturday and Sunday are the weekend,
        -- everything else is regular. A festival day is left alone — FESTIVAL
        -- outranked CATERING when the row was written, so a CATERING row cannot
        -- be one.
        UPDATE meal_plans
        SET day_type = CASE
                WHEN EXTRACT(ISODOW FROM plan_date) IN (6, 7) THEN 'WEEKEND'
                ELSE 'REGULAR'
            END
        WHERE day_type = 'CATERING';
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);
END $$;

-- --- 4b. CATERING out of the vocabulary --------------------------------------
--
-- DDL is not filtered by RLS, so this is also the check on the loop above: a
-- tenant it somehow missed leaves a CATERING row behind, and the constraint
-- refuses to validate rather than the value quietly surviving in a column
-- nothing looks at any more. V48's `SET NOT NULL` guards its own backfill the
-- same way.
ALTER TABLE meal_plans DROP CONSTRAINT meal_plans_daytype_valid;
ALTER TABLE meal_plans ADD CONSTRAINT meal_plans_daytype_valid
    CHECK (day_type IN ('REGULAR', 'WEEKEND', 'FESTIVAL'));

-- V67 recorded the corrected sentence for this column on the column itself,
-- because V22's inline comment could not be edited once applied. Same again: the
-- vocabulary is now three values, not four.
COMMENT ON COLUMN meal_plans.day_type IS
    'What kind of day this meal was cooked on: REGULAR, WEEKEND or FESTIVAL. Derived from the date and the calendar, never chosen by a person, and re-derived on every update. A record of what was true on the day — not re-read from the calendar afterwards. CATERING was removed by E4-S15: catering is an event, and an event is a kind of meal, not a kind of day.';

-- --- 5. The three flags go ---------------------------------------------------
--
-- Last, because the fold above reads two of them. needs_purpose goes with them:
-- it existed for the Outside event kind alone (V64), and that kind no longer
-- exists. The purpose COLUMN on meal_plans stays — it is still printed on the
-- job card ("What it is for") and still carried onto the served-meal record, and
-- dropping a column two screens read would be a different story than this one.
ALTER TABLE meal_kinds
    DROP COLUMN needs_client,
    DROP COLUMN needs_venue,
    DROP COLUMN needs_purpose;

-- --- 6. Reading the commitments back -----------------------------------------
--
-- *Upcoming outside commitments* asks one question — what is leaving this temple
-- from today onwards, soonest first — and it is asked every time the planner is
-- opened. Partial on is_outside, because the answer is a handful of rows out of
-- every meal the temple has ever cooked.
CREATE INDEX meal_plans_tenant_outside
    ON meal_plans (tenant_id, plan_date)
    WHERE is_outside;

-- The event-name autocomplete: the names this temple has used before, most
-- recent first, so the second School Bhagavad-gita Reading is one keystroke.
-- That is not a convenience (E4-S15 D9) — the temple has no event register of
-- any kind today, so this is a practice being introduced rather than digitised,
-- and if entering a Saturday reading costs three minutes it will stop being
-- entered.
CREATE INDEX meal_plans_tenant_event_name
    ON meal_plans (tenant_id, lower(event_name), plan_date DESC)
    WHERE event_name IS NOT NULL;
