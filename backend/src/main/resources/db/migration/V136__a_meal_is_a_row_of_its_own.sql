-- =====================================================================
-- V136 — A meal is a row of its own, and a volunteer shift points at it by id (D-27, T-195)
--
-- WHAT WAS THERE
--
-- A meal had no row. meal_plans held one row per DISH (V22), and "Lunch on 14 September" existed
-- only as a GROUP BY over date, meal kind text and event name, with every whole-meal fact — the head
-- count, ready-by, notes, the event's address — copied onto each dish row and read back from
-- whichever came first. The job card number and the recording lived on a second row, meal_services
-- (V64), keyed on the same three texts. A volunteer shift remembered its meal by copying those texts
-- again (V95), with no foreign key, because meal_kinds is unique only on an expression (lower(name))
-- and there was no meal id to point at.
--
-- Rajeev, on why that has to go: "identifying things by text is a terrible idea and one that WILL
-- fail eventually." His design, in his words:
--
--     "Meal Plan for each day wil be saved in a dedicated table with its own unique ID and related
--      info. Then each Meal for that day (breakfast, Lunch, Dinner ....etc) will be created in a
--      dedicated Meal table. Each meal gets its own unique ID and related infomration and a Foreign
--      Key relation to the Meal Plan's ID. ... When a Volenteer shift is raised, it should have it sown
--      unique ID and a foreign key relation to a Meal ID. That way a shift is unambiguisloy linked to
--      ONE and ONLY one meal."
--
-- WHAT THIS BUILDS
--
--   meal_plan_days   one row per temple per date, carrying the day type
--   meals            one row per meal on a day: which kind (by meal_kinds.id), which event, and
--                    every whole-meal fact, including everything meal_services held
--   meal_dishes      meal_plans RENAMED, so every dish keeps its id; it gains meal_id and keeps only
--                    what belongs to one dish
--   shifts.meal_id   nullable; null means "not for a meal"
--   documents.meal_id  the job card points at the meal
--
-- and removes meal_services, the text columns on shifts, and every per-meal column on the dish rows.
--
-- The names are fixed by the build ledger (T-195): other builders are writing SQL against them.
--
-- ---------------------------------------------------------------------
-- WHY THE DISH TABLE IS RENAMED RATHER THAN REBUILT
--
-- Dish ids are referenced without a foreign key. A cooking draw is a stock movement with
-- reference_type MEAL_PLAN and reference_id the dish id (InventoryConsumptionService), and correcting
-- a meal finds its draws by that pair (StockMovementService.compensateAllFor). V135 emptied the
-- table, so today no id is at stake — but a rename is what guarantees the rule for every dish written
-- from here on without anyone having to remember it, and it carries the grants, the row-level
-- security flags and the tenant_isolation policy with it, because those hang off the table's OID
-- (V81's header says the same). What a rename does not carry is the names of constraints and
-- indexes, so those are renamed to match below — V81 set that convention, and BaseQuantityIT and
-- others assert on constraint names when they catch a violation.
--
-- ---------------------------------------------------------------------
-- THE IDENTITY OF A MEAL
--
-- One meal per day, per meal kind, per event name compared case-insensitively, with no event name
-- being one meal. That is exactly today's meal_services_one_per_meal (V89), moved onto a kind id. Two
-- events on one Saturday must therefore have different names, which the planner already demands
-- (MealPlanService.requireEventFields). The index is deliberately NOT partial: a meal that was
-- cancelled and is planned again is the same meal, and reuses its row, its card number and its shift
-- link rather than growing a second row nobody can tell apart from the first.
--
-- The kind is a foreign key to meal_kinds(id). Not the name: PostgreSQL refuses an expression index
-- as a foreign-key target (V96 has the long version), and a name is exactly the kind of text Rajeev
-- ruled out. So the rename cascade and the "kind in use" text search in MealKindService (T-038) are
-- replaced by the database itself: a kind with a meal cannot be deleted (RESTRICT), and renaming a
-- kind changes one row and nothing else. V96 altered nothing but a column comment on
-- meal_services.meal_kind; that column and its comment go with the table, and there is no function,
-- trigger or view of V96's to redefine — the machinery was Java, and the service rewrite removes it.
--
-- ---------------------------------------------------------------------
-- WHAT WAS CHECKED BEFORE DROPPING ANYTHING
--
-- Against the V134 schema: no function, view, trigger or policy names meal_plans, meal_services or
-- any column dropped here (pg_proc.prosrc, pg_views, pg_trigger). The only foreign key into
-- meal_services is documents.meal_service_id; nothing references meal_plans or the three text columns
-- on shifts. The indexes over the dropped columns are dropped with them by PostgreSQL. Every CHECK on
-- a column that moves is restated on its new home, word for word, under the new table's name.
--
-- Tenant deletion (delete_tenant_cascade, V86) finds its work by looking for a tenant_id column and
-- retries until a pass deletes nothing, so the new tables need no entry of their own there; the
-- RESTRICT foreign keys between them simply resolve over two passes, as meal_plans -> recipes always
-- has. The tenant export (TenantExportIT) is driven by the same catalogue query.
-- =====================================================================


-- ---------------------------------------------------------------------
-- The day
--
-- day_type is what kind of day the meals were cooked on: REGULAR, WEEKEND or FESTIVAL. Derived from
-- the date and the calendar by the planner (MealPlanService.dayContext), never chosen by a person —
-- and it was always a fact about the day that happened to be copied onto every dish (V22, V88), which
-- is why it lives here rather than on a meal.
-- ---------------------------------------------------------------------
CREATE TABLE meal_plan_days (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    plan_date   DATE        NOT NULL,
    day_type    TEXT        NOT NULL,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Carried from meal_plans_daytype_valid as V88 left it: CATERING is gone, because catering is an
    -- event and an event is a kind of meal, not a kind of day.
    CONSTRAINT meal_plan_days_daytype_valid CHECK (day_type IN ('REGULAR', 'WEEKEND', 'FESTIVAL')),

    -- One plan per temple per date. This is also the index every date-range read uses.
    CONSTRAINT meal_plan_days_one_per_date UNIQUE (tenant_id, plan_date)
);

SELECT enable_tenant_rls('meal_plan_days');

COMMENT ON TABLE meal_plan_days IS
    'A temple''s meal plan for one date (D-27). Its meals point at it; the day type is a fact about the day, not about any one meal.';
COMMENT ON COLUMN meal_plan_days.day_type IS
    'REGULAR, WEEKEND or FESTIVAL. Derived from the date and the calendar, never chosen by a person. A record of what was true on the day — not re-read from the calendar afterwards.';


-- ---------------------------------------------------------------------
-- The meal
--
-- Every column below except the keys was already in the schema, either copied onto every dish row of
-- meal_plans or held on meal_services, and each carries the CHECK it had there. The comments on the
-- old columns are restated where the meaning is not obvious from the name.
-- ---------------------------------------------------------------------
CREATE TABLE meals (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    meal_plan_day_id       UUID        NOT NULL REFERENCES meal_plan_days(id) ON DELETE RESTRICT,

    -- By id, never by name (see the header). RESTRICT: a kind that has been cooked under cannot be
    -- deleted, which is what T-038 enforced in Java and now needs no Java at all.
    meal_kind_id           UUID        NOT NULL REFERENCES meal_kinds(id) ON DELETE RESTRICT,

    -- Which event this meal is, where the kind is an event; part of the meal's identity. Null for
    -- Breakfast, Lunch, Dinner and every kind that is not an event. The rule is meal_services' (V89),
    -- the stricter of the two tables': a name that is only spaces names nothing.
    event_name             TEXT,

    -- The festival or occasion this meal was planned for, as it was named on the day (V48).
    occasion_name          TEXT,

    -- --- Timing and head count (V48, V51) ---------------------------------------------------------
    -- Local time the food must be ready. Not a start time: it is what a cook works backwards from.
    ready_by               TIME        NOT NULL,
    adults                 INTEGER,
    children               INTEGER,
    seniors                INTEGER,
    kitchen_notes          TEXT,

    -- How many people it takes to execute this meal, any mix of staff and volunteers (V67). NULL
    -- where nobody has said.
    crew_required          INTEGER,

    -- What the people serving need to know; the serving sheet of the job card (V92).
    server_notes           TEXT,

    -- What this food is for, in the planner's own words (V64). Nothing computes on it.
    purpose                TEXT,

    -- --- Events that leave the temple (V88, V93) --------------------------------------------------
    is_outside             BOOLEAN     NOT NULL DEFAULT false,
    handover               TEXT,
    contact_name           TEXT,
    contact_phone          TEXT,
    delivery_address       TEXT,
    delivery_latitude      NUMERIC(9, 6),
    delivery_longitude     NUMERIC(9, 6),
    geocoded_at            TIMESTAMPTZ,
    delivery_place_id      TEXT,
    delivery_sub_location  TEXT,
    guests_eat_at          TIME,
    travel_minutes         INTEGER,
    travel_minutes_source  TEXT,

    -- --- The job card (V64, V92) --------------------------------------------------------------------
    -- Issued once, on the first print, and never re-issued: a signed sheet in a folder must trace back
    -- to this row months later.
    card_number            TEXT,
    card_issued_at         TIMESTAMPTZ,
    card_version           INTEGER     NOT NULL DEFAULT 0,
    card_fingerprint       TEXT,

    -- --- What came back from the kitchen (V64, V106) ------------------------------------------------
    recorded_at            TIMESTAMPTZ,
    recorded_by            UUID        REFERENCES users(id) ON DELETE SET NULL,
    recording_note         TEXT,
    corrected_at           TIMESTAMPTZ,
    corrected_by           UUID        REFERENCES users(id) ON DELETE SET NULL,
    correction_note        TEXT,

    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- From meal_services.
    CONSTRAINT meals_event_name_check CHECK (
        event_name IS NULL OR (length(btrim(event_name)) > 0 AND length(event_name) <= 200)),
    CONSTRAINT meals_card_shape CHECK (
        (card_number IS NULL AND card_issued_at IS NULL)
        OR (card_number IS NOT NULL AND card_issued_at IS NOT NULL)),
    CONSTRAINT meals_card_version_non_negative CHECK (card_version >= 0),
    CONSTRAINT meals_note_length CHECK (recording_note IS NULL OR length(recording_note) <= 2000),
    CONSTRAINT meals_correction_follows_recording CHECK (corrected_at IS NULL OR recorded_at IS NOT NULL),
    CONSTRAINT meals_correction_note_length CHECK (correction_note IS NULL OR length(correction_note) <= 2000),
    CONSTRAINT meals_correction_shape CHECK (
        (corrected_at IS NULL AND correction_note IS NULL)
        OR (corrected_at IS NOT NULL AND btrim(correction_note) <> '')),

    -- From meal_plans.
    CONSTRAINT meals_adults_check CHECK (adults IS NULL OR adults >= 0),
    CONSTRAINT meals_children_check CHECK (children IS NULL OR children >= 0),
    CONSTRAINT meals_seniors_check CHECK (seniors IS NULL OR seniors >= 0),
    CONSTRAINT meals_kitchen_notes_check CHECK (kitchen_notes IS NULL OR length(kitchen_notes) <= 2000),
    CONSTRAINT meals_crew_positive CHECK (crew_required IS NULL OR crew_required > 0),
    CONSTRAINT meals_server_notes_check CHECK (server_notes IS NULL OR length(server_notes) <= 2000),
    CONSTRAINT meals_purpose_check CHECK (purpose IS NULL OR length(purpose) <= 300),
    CONSTRAINT meals_handover_check CHECK (handover IS NULL OR handover IN ('PICKUP', 'DELIVERY')),
    CONSTRAINT meals_delivery_latitude_check CHECK (
        delivery_latitude IS NULL OR (delivery_latitude >= -90 AND delivery_latitude <= 90)),
    CONSTRAINT meals_delivery_longitude_check CHECK (
        delivery_longitude IS NULL OR (delivery_longitude >= -180 AND delivery_longitude <= 180)),
    CONSTRAINT meals_delivery_place_id_check CHECK (delivery_place_id IS NULL OR length(delivery_place_id) <= 300),
    CONSTRAINT meals_delivery_sub_location_check CHECK (
        delivery_sub_location IS NULL OR length(delivery_sub_location) <= 200),
    CONSTRAINT meals_travel_minutes_check CHECK (
        travel_minutes IS NULL OR (travel_minutes >= 1 AND travel_minutes <= 600)),
    CONSTRAINT meals_travel_minutes_source_check CHECK (
        travel_minutes_source IS NULL OR travel_minutes_source IN ('ESTIMATED', 'MANUAL')),
    -- Without a source, the refresh at print time would silently overrule a person's override (V93).
    CONSTRAINT meals_travel_minutes_has_a_source CHECK ((travel_minutes IS NULL) = (travel_minutes_source IS NULL))
);

-- One meal per day, kind and event name, compared case-insensitively; no event name is one meal.
-- Not partial, on purpose (see the header). An upsert's ON CONFLICT has to name this expression
-- exactly — (meal_plan_day_id, meal_kind_id, lower(COALESCE(event_name, ''))) — or it will raise
-- rather than find the row it meant. It also serves every "the meals of this day" lookup.
CREATE UNIQUE INDEX meals_one_per_meal
    ON meals (meal_plan_day_id, meal_kind_id, lower(COALESCE(event_name, '')));

-- The foreign key to meal_kinds is checked on every kind delete; without an index that is a scan.
CREATE INDEX meals_by_kind ON meals (tenant_id, meal_kind_id);

-- Carried from the dish-row indexes they replace (V48, V88): the event history, the occasion history,
-- and "Upcoming outside commitments".
CREATE INDEX meals_tenant_event_name ON meals (tenant_id, lower(event_name)) WHERE event_name IS NOT NULL;
CREATE INDEX meals_tenant_occasion ON meals (tenant_id, lower(occasion_name)) WHERE occasion_name IS NOT NULL;
CREATE INDEX meals_tenant_outside ON meals (tenant_id) WHERE is_outside;

SELECT enable_tenant_rls('meals');

COMMENT ON TABLE meals IS
    'One meal on one day — Breakfast, Lunch, Dinner or a named event — with its own id (D-27). Carries every whole-meal fact, the job card number and the record of what came back from the kitchen. Its dishes are meal_dishes; a volunteer shift for it points here by id.';
COMMENT ON COLUMN meals.meal_kind_id IS
    'Which kind of meal, by id. Never by name: a name is text, and a kind in use cannot be deleted (RESTRICT) while a rename touches only meal_kinds.';
COMMENT ON COLUMN meals.event_name IS
    'What the event is called, where the kind is an event; part of the meal''s identity with its day and kind, compared case-insensitively. Null for every kind that is not an event.';
COMMENT ON COLUMN meals.card_version IS
    'Which version of this job card has been printed (V92). Zero until the first print; goes up only when the printed content differs from card_fingerprint.';
COMMENT ON COLUMN meals.card_fingerprint IS
    'A hash of everything the last printed card said, taken from the print model, compared on every print to decide whether card_version moves (V92).';
COMMENT ON COLUMN meals.corrected_at IS
    'When this meal''s recorded figures were corrected (T-007). Non-null is also the guard: a second correction is refused, because it would compensate already-compensated movements.';
COMMENT ON COLUMN meals.travel_minutes_source IS
    'ESTIMATED (Google''s figure, refreshed when the job card is printed) or MANUAL (a person set it — left alone and printed as they set it).';


-- ---------------------------------------------------------------------
-- The dishes: meal_plans, renamed, keeping only what belongs to one dish
-- ---------------------------------------------------------------------
ALTER TABLE meal_plans RENAME TO meal_dishes;

-- NOT NULL with no default is safe only because V135 emptied the table — and it is also the guard
-- that V135 did. ADD COLUMN ... NOT NULL checks every row regardless of row-level security, so a dish
-- the purge missed stops this migration here, loudly, instead of leaving a dish that belongs to no
-- meal.
ALTER TABLE meal_dishes
    ADD COLUMN meal_id UUID NOT NULL REFERENCES meals(id) ON DELETE RESTRICT;

CREATE INDEX meal_dishes_by_meal ON meal_dishes (meal_id);

-- Everything that described the meal rather than the dish. PostgreSQL drops the CHECKs and indexes
-- over these columns with them (meal_plans_tenant_date, _event_name, _kind_crew, _occasion, _outside);
-- each was restated on meals or meal_plan_days above.
ALTER TABLE meal_dishes
    DROP COLUMN plan_date,
    DROP COLUMN meal_kind,
    DROP COLUMN event_name,
    DROP COLUMN day_type,
    DROP COLUMN occasion_name,
    DROP COLUMN ready_by,
    DROP COLUMN adults,
    DROP COLUMN children,
    DROP COLUMN seniors,
    DROP COLUMN kitchen_notes,
    DROP COLUMN crew_required,
    DROP COLUMN server_notes,
    DROP COLUMN purpose,
    DROP COLUMN is_outside,
    DROP COLUMN handover,
    DROP COLUMN contact_name,
    DROP COLUMN contact_phone,
    DROP COLUMN delivery_address,
    DROP COLUMN delivery_latitude,
    DROP COLUMN delivery_longitude,
    DROP COLUMN geocoded_at,
    DROP COLUMN delivery_place_id,
    DROP COLUMN delivery_sub_location,
    DROP COLUMN guests_eat_at,
    DROP COLUMN travel_minutes,
    DROP COLUMN travel_minutes_source;

-- The surviving constraint and index names still say meal_plans. Renamed by rule rather than one by
-- one, because what survives is exactly what was not dropped above and a hand-written list would be
-- a second copy of that fact to drift. A primary key's rename renames its index too, so indexes are
-- renamed second and only where the name still carries the old prefix.
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'public.meal_dishes'::regclass AND conname LIKE 'meal\_plans\_%'
    LOOP
        EXECUTE format('ALTER TABLE meal_dishes RENAME CONSTRAINT %I TO %I',
                       r.conname, 'meal_dishes_' || substr(r.conname, length('meal_plans_') + 1));
    END LOOP;

    FOR r IN
        SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE i.indrelid = 'public.meal_dishes'::regclass AND c.relname LIKE 'meal\_plans\_%'
    LOOP
        EXECUTE format('ALTER INDEX %I RENAME TO %I',
                       r.relname, 'meal_dishes_' || substr(r.relname, length('meal_plans_') + 1));
    END LOOP;
END $$;

COMMENT ON TABLE meal_dishes IS
    'One dish of one meal (D-27): the recipe, how much to make, and what was actually cooked and served. Was meal_plans, renamed so every dish keeps its id — stock movements with reference_type MEAL_PLAN point at these ids without a foreign key. Marking one cooked draws stock via the ledger (E3-S6).';
COMMENT ON COLUMN meal_dishes.meal_id IS
    'The meal this dish is part of. Every whole-meal fact — date, kind, event, head count, ready-by, notes, the job card — is on that row and nowhere else.';


-- ---------------------------------------------------------------------
-- The job card points at the meal
--
-- documents_target_shape is restated whole from V117, with meal_service_id read as meal_id and
-- nothing else changed. The old column is dropped rather than renamed because its foreign key pointed
-- at meal_services; V135 removed every JOB_CARD_PDF, so it holds no values.
-- ---------------------------------------------------------------------
ALTER TABLE documents DROP CONSTRAINT documents_target_shape;
DROP INDEX documents_tenant_meal_service;
ALTER TABLE documents DROP COLUMN meal_service_id;

ALTER TABLE documents ADD COLUMN meal_id UUID REFERENCES meals(id) ON DELETE CASCADE;

ALTER TABLE documents ADD CONSTRAINT documents_target_shape CHECK (
    (kind = 'RECIPE_PDF' AND recipe_id IS NOT NULL
        AND po_id IS NULL AND meal_id IS NULL AND ingredient_request_id IS NULL AND donation_id IS NULL)
    OR (kind = 'PURCHASE_ORDER_PDF' AND po_id IS NOT NULL
        AND recipe_id IS NULL AND meal_id IS NULL AND ingredient_request_id IS NULL AND donation_id IS NULL)
    OR (kind = 'JOB_CARD_PDF' AND meal_id IS NOT NULL
        AND recipe_id IS NULL AND po_id IS NULL AND ingredient_request_id IS NULL AND donation_id IS NULL)
    OR (kind = 'WORK_ORDER_PDF' AND ingredient_request_id IS NOT NULL
        AND recipe_id IS NULL AND po_id IS NULL AND meal_id IS NULL AND donation_id IS NULL)
    OR (kind = 'DONATION_RECEIPT_PDF' AND donation_id IS NOT NULL
        AND recipe_id IS NULL AND po_id IS NULL AND meal_id IS NULL AND ingredient_request_id IS NULL));

-- Versioned like a PO sheet: a card reprinted after a dish was swapped is a different sheet (V64).
CREATE INDEX documents_tenant_meal ON documents (tenant_id, meal_id, version DESC);


-- ---------------------------------------------------------------------
-- A volunteer shift points at one meal, or at none
--
-- V95's three text columns and the CHECK that kept them whole go. meal_id NULL means the shift is
-- not for a meal — garlands, crowd control, decorating — which Rajeev placed on the Post a shift
-- screen; a shift for a meal is raised only from the planner (D-27, answers 3 and 4).
--
-- RESTRICT rather than CASCADE: a meal is cancelled, not deleted, and cancelling it cancels its shift
-- and tells the volunteers (answer 5). A delete that silently took a staffed shift with it would do
-- neither.
-- ---------------------------------------------------------------------
ALTER TABLE shifts DROP CONSTRAINT shifts_meal_link_complete;
ALTER TABLE shifts
    DROP COLUMN meal_date,
    DROP COLUMN meal_kind,
    DROP COLUMN meal_event_name;

ALTER TABLE shifts ADD COLUMN meal_id UUID REFERENCES meals(id) ON DELETE RESTRICT;

-- One shift per meal among shifts not cancelled (answer 2: "we dont need to haev more than one
-- Volenteer shift request Per Meal"). A cancelled shift does not count, so a meal whose shift was
-- cancelled can ask again. shifts_status_valid admits OPEN and CANCELLED; the predicate is written as
-- "not CANCELLED" so that a status added later is counted as live unless somebody decides otherwise.
CREATE UNIQUE INDEX shifts_one_per_meal ON shifts (meal_id)
    WHERE meal_id IS NOT NULL AND status <> 'CANCELLED';

COMMENT ON COLUMN shifts.meal_id IS
    'The one meal this shift is for (D-27), or NULL for a shift that is not for a meal. At most one shift that is not cancelled per meal (shifts_one_per_meal). The shift''s date always comes from its meal.';


-- ---------------------------------------------------------------------
-- meal_services is folded into meals
-- ---------------------------------------------------------------------
DROP TABLE meal_services;
