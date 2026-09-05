-- =====================================================================
-- V89 — A job card per event (E4-S15 D1)
--
-- V88 gave the temple events. It did not give them separate identities, and
-- that undoes most of what the split was for.
--
-- A meal has been identified by (plan_date, meal_kind) since V64, and that pair
-- is exactly right for the three main meals: there is one Lunch on a Tuesday,
-- and two Lunch services on one date would be two answers to the question "what
-- went out at lunch". But every event now carries the same kind name — Event —
-- so two events on one Saturday, a morning children's reading and an evening
-- Bhajan Prasadam, are the same pair. They collapse into one meal_services row:
-- ONE recording covering both, and ONE job card number for two sheets that go to
-- two different kitchens at two different hours.
--
-- That breaks the central promise of E4-S15 D1 — each event is its own
-- preparation with its own job card — and it breaks it in the two ways D1 named
-- as the reason for splitting events out at all: the head count stops meaning
-- anything, and afterwards nobody can answer what the Saturday readings cost,
-- because the reading and the Bhajan Prasadam were recorded as one thing.
--
-- So the identity gains the event's own name:
--
--     (tenant_id, plan_date, meal_kind, event_name)
--
-- and the three main meals are untouched, because their event_name is null and
-- always will be — MealPlanService drops every event field on the way in for a
-- kind that is not an event, so a Lunch cannot carry a name even if a caller
-- sends one.
--
-- ---------------------------------------------------------------------------
-- WHAT IDENTIFIES AN EVENT WHOSE NAME IS EMPTY
-- ---------------------------------------------------------------------------
--
-- V88 leaves event_name null on a migrated plan that had no purpose to promote —
-- deliberately, because it would not invent a name nobody wrote. So the schema
-- has to answer: are two unnamed events on one date one meal or two?
--
-- THEY ARE ONE. All the unnamed events of one kind on one date group together,
-- share one recording and share one card — which is exactly what they do today,
-- before this migration. Three reasons, in the order they matter:
--
--   1. It is the only answer that does not invent a fact. The alternative is to
--      key an unnamed event on something else — its plan id, its ready-by, a
--      generated name — and each of those asserts a distinction the temple never
--      stated. V88 refused to invent the name; inventing an identity one layer
--      down would be the same mistake wearing a different hat.
--
--   2. The failure modes are not symmetrical. Splitting two rows that are really
--      one event gives the kitchen two cards for one pot and a recording it
--      cannot complete, and no screen offers a way to merge them back. Joining
--      two rows that are really two events gives one card listing both
--      preparations — untidy, visible immediately, and fixed by typing a name.
--      When the schema has to guess, it should guess the way that is cheap to
--      correct.
--
--   3. It cannot last. EVENT_NAME_REQUIRED (KMS-4990) refuses to save an event
--      without a name, so the only unnamed events that can exist are the ones
--      V88 carried across, and the first edit of one asks for a name and splits
--      it off by itself. Nothing new can ever land in this case.
--
-- The rule is written as COALESCE(event_name, '') rather than as PostgreSQL 15's
-- NULLS NOT DISTINCT. The application's ON CONFLICT has to name the same
-- expression, so writing it out means the index and the upsert cannot drift
-- apart silently — and the sentence "no name and no name are the same meal" is
-- on the page instead of in a keyword. The column keeps a CHECK that a name is
-- either absent or real, so '' can never be stored and the two can never mean
-- different things.
--
-- And the name is folded to lower case in the key, so "Bhajan Prasadam" and
-- "bhajan prasadam" are one event. THE CASE-SENSITIVE KEY WAS WRITTEN FIRST AND
-- REJECTED, on the asymmetry argued above: a kitchen that types the same event
-- twice with a different capital gets two cards for one pot and a recording it
-- cannot finish, and there is no screen anywhere that merges them back. Nothing
-- is lost by folding — the name the planner typed is on the plan rows, and every
-- screen reads it from there.
--
-- ---------------------------------------------------------------------------
-- AND A ROW V88 LEFT BEHIND
-- ---------------------------------------------------------------------------
--
-- V88 renamed the kind on the PLANS of a folded catering order — 'Catering
-- order' became 'Event' — and did not rename it on the meal_services row those
-- plans were recorded under. A temple that had recorded a catering meal would
-- come out of V88 with a service row nothing matches: the recording, the card
-- number and the name of whoever typed it in would all read as missing, on a
-- meal it can plainly see was cooked. Section 3 below carries those rows across.
-- V88 is applied and is not edited; this is the fix forward.
-- =====================================================================

-- --- 1. The event's own name, on the meal --------------------------------
ALTER TABLE meal_services
    ADD COLUMN event_name TEXT
        CHECK (event_name IS NULL
               OR (length(btrim(event_name)) > 0 AND length(event_name) <= 200));

COMMENT ON COLUMN meal_services.event_name IS
    'Which event this meal is, where the kind is an event (E4-S15 D1): part of the meal''s identity alongside its date and kind, so a morning children''s reading and an evening Bhajan Prasadam on one Saturday get their own recording and their own job card. Null for Breakfast, Lunch, Dinner and every other kind that is not an event — and null, too, for the unnamed events V88 carried across, which group together as one meal exactly as they did before.';

-- --- 2. The name each existing meal was recorded under ---------------------
--
-- Read back off the plans the row was assembled from, so a recording that exists
-- keeps pointing at the same food. A main meal's plans carry no event name, so
-- this writes null and leaves those rows exactly as they were — which is most of
-- what any temple has.
--
-- Where one date and kind already hold several named events, only one service row
-- exists and only one of them can keep it. It goes to the event that was due
-- first, tie-broken by name; the others come out with no service row, which is
-- the truthful reading — a single card was printed that day and it belonged to
-- one of them. Nothing is deleted and nothing is duplicated: a card number
-- identifies one sheet, and copying it onto a second meal would break the one
-- thing the number is for. Any event left without a row gets one, and a number of
-- its own, the next time somebody prints or records it.
--
-- meal_services and meal_plans both carry FORCE ROW LEVEL SECURITY, so a
-- cross-tenant UPDATE here would match nothing and report success. Each tenant is
-- adopted in turn, as V48, V64, V67 and V88 all do.
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        UPDATE meal_services ms
        SET event_name = (
                SELECT mp.event_name
                FROM meal_plans mp
                WHERE mp.plan_date = ms.plan_date
                  AND lower(mp.meal_kind) = lower(ms.meal_kind)
                  AND mp.event_name IS NOT NULL
                ORDER BY mp.ready_by NULLS LAST, lower(mp.event_name)
                LIMIT 1);

        -- --- 3. The catering recordings V88 orphaned -----------------------
        --
        -- A service row whose kind matches no plan on its own date is one whose
        -- plans were folded into Event underneath it. It is re-pointed at that
        -- date's event — but only where the date leaves no room for doubt: one
        -- event, one stranded row, and nothing already sitting where it would
        -- move to. Anything ambiguous is left exactly as it is. A recording
        -- pointing at nothing can be put right by hand; a recording pointing at
        -- the wrong meal is a lie the temple has no way to notice.
        UPDATE meal_services ms
        SET meal_kind  = repair.meal_kind,
            event_name = repair.event_name,
            updated_at = now()
        FROM (
            SELECT d.plan_date, d.meal_kind, d.event_name
            FROM (
                -- The kind is taken from the plans rather than written as the
                -- literal 'Event': a temple may have had a kind of its own by that
                -- name in its own capitalisation, and the row has to match the
                -- plans it describes, not the word this migration would have used.
                SELECT mp.plan_date,
                       min(mp.meal_kind)  AS meal_kind,
                       min(mp.event_name) AS event_name,
                       count(DISTINCT coalesce(mp.event_name, '')) AS events,
                       count(DISTINCT lower(mp.meal_kind))         AS kinds
                FROM meal_plans mp
                JOIN meal_kinds mk ON lower(mk.name) = lower(mp.meal_kind) AND mk.is_event
                GROUP BY mp.plan_date
            ) AS d
            WHERE d.events = 1 AND d.kinds = 1
              -- Exactly one row on that date has lost the plans it described ...
              AND (SELECT count(*) FROM meal_services o
                   WHERE o.plan_date = d.plan_date
                     AND NOT EXISTS (
                           SELECT 1 FROM meal_plans p
                           WHERE p.plan_date = o.plan_date
                             AND lower(p.meal_kind) = lower(o.meal_kind))) = 1
              -- ... and the event it would move onto has no row of its own.
              AND NOT EXISTS (
                    SELECT 1 FROM meal_services o
                    WHERE o.plan_date = d.plan_date
                      AND lower(o.meal_kind) = lower(d.meal_kind)
                      AND coalesce(o.event_name, '') = coalesce(d.event_name, ''))
        ) AS repair
        WHERE repair.plan_date = ms.plan_date
          AND NOT EXISTS (
                SELECT 1 FROM meal_plans p
                WHERE p.plan_date = ms.plan_date
                  AND lower(p.meal_kind) = lower(ms.meal_kind));
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);
END $$;

-- --- 4. The new identity ---------------------------------------------------
--
-- Dropped and recreated rather than added beside the old one: keeping
-- (tenant_id, plan_date, meal_kind) unique is precisely the constraint that says
-- two events on a Saturday are the same meal, which is the thing being undone.
DROP INDEX meal_services_one_per_meal;

CREATE UNIQUE INDEX meal_services_one_per_meal
    ON meal_services (tenant_id, plan_date, meal_kind, lower(COALESCE(event_name, '')));

COMMENT ON INDEX meal_services_one_per_meal IS
    'One meal per date, kind and event name. Two Lunch services on one date are still two answers to "what went out at lunch" and are still refused; two named events on one Saturday are two preparations with two job cards, which is the whole of E4-S15 D1. Two unnamed events are one meal, because nothing in the record says they are not.';
