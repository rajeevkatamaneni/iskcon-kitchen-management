-- =====================================================================
-- V64 — The meal as a thing, what actually went out, and the job card
--       (B4, B5, B6, A7)
--
-- There is no meal-line table in this schema and this migration does not add
-- one: one meal_plans row is one dish, and a lunch of three dishes is three
-- rows sharing a plan_date, a meal_kind, a head count and a ready_by. That
-- shape is load-bearing — sufficiency, the order list and Today all read it —
-- and splitting it into a parent and its children would ripple through every
-- one of them to buy nothing the build brief asks for.
--
-- What the brief does ask for is that "a meal" stops being implicit. It speaks
-- of one job card per meal kind, of recording per meal rather than per dish, of
-- plates per meal kind. So the pair (plan_date, meal_kind) is given a row of its
-- own — meal_services — which carries the facts that belong to the whole meal
-- and to nothing smaller: the card number printed on the sheet, when the sheet
-- came back, and who typed it in. The dishes stay where they are. A row is
-- created on demand, the first time a card is printed or a meal is recorded, so
-- planning a meal costs nothing extra and a temple that never prints a card
-- never accumulates empty rows.
--
-- The per-dish facts — what actually went out, and whether the dish was made at
-- all — stay on meal_plans, because that is the grain they belong to.
-- =====================================================================

-- --- The meal, as one thing ------------------------------------------------
CREATE TABLE meal_services (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    plan_date       DATE        NOT NULL,

    -- The kind by name, exactly as meal_plans records it. MealPlanService writes
    -- the canonical name off meal_kinds rather than whatever the caller typed, so
    -- the two always agree; matching on the text rather than on a meal_kinds id is
    -- the same choice V48 made, and for the same reason — a temple may delete a
    -- kind, and the meals cooked under it must keep reading as what they were.
    meal_kind       TEXT        NOT NULL,

    -- Printed on the sheet: "Lunch · 21 Aug 2026 · LC-2026-0142". Issued once, on
    -- the first print, and never re-issued — the whole point of it is that a signed
    -- sheet in a folder can be traced back to this row six months later, which a
    -- number that changed between reprints could not do.
    card_number     TEXT,
    card_issued_at  TIMESTAMPTZ,

    -- When the sheet came back and somebody in the office typed it in. Null means
    -- not yet recorded, which is what Today says out loud rather than badging.
    recorded_at     TIMESTAMPTZ,
    recorded_by     UUID        REFERENCES users(id) ON DELETE SET NULL,
    recording_note  TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT meal_services_kind_present CHECK (length(meal_kind) > 0),
    CONSTRAINT meal_services_note_length  CHECK (recording_note IS NULL OR length(recording_note) <= 2000),
    -- A card number without an issue date, or a recording without a recorder, would
    -- be a half-written fact. Both halves or neither.
    CONSTRAINT meal_services_card_shape CHECK (
        (card_number IS NULL AND card_issued_at IS NULL)
        OR (card_number IS NOT NULL AND card_issued_at IS NOT NULL))
);

-- One meal per kind per day. This is the constraint that makes the pair a thing:
-- two Lunch services on one date would be two answers to "what went out at lunch".
CREATE UNIQUE INDEX meal_services_one_per_meal ON meal_services (tenant_id, plan_date, meal_kind);
CREATE INDEX meal_services_by_date ON meal_services (tenant_id, plan_date);

SELECT enable_tenant_rls('meal_services');

COMMENT ON TABLE meal_services IS
    'One meal — a date and a kind — assembled from the meal_plans rows that share them (B5). Carries the job-card number and the record of what came back from the kitchen.';

-- --- Card numbers ----------------------------------------------------------
-- Per-tenant counter, exactly as po_sequence works: the atomic INSERT ... ON
-- CONFLICT DO UPDATE ... RETURNING row-locks per tenant, so two people printing
-- two cards at once never get the same number, and a rolled-back print simply
-- leaves a gap. Gaps are fine — the number identifies a sheet, it does not count
-- them, and a temple that needs to know how many meals it cooked counts meals.
--
-- Like the PO counter this one does not reset each year; the year is stamped into
-- the printed number for a person reading it, not derived from the sequence.
CREATE TABLE meal_card_sequence (
    tenant_id   UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE RESTRICT,
    last_number INTEGER NOT NULL DEFAULT 0
);
SELECT enable_tenant_rls('meal_card_sequence');

-- --- What actually went out ------------------------------------------------
--
-- The planned figure and the served figure are two different facts and the temple
-- needs both: over a month the gap between them tells them their head counts are
-- wrong, in which direction and by how much. So target_servings is never
-- overwritten by the recording — actual_servings sits beside it.
ALTER TABLE meal_plans
    ADD COLUMN actual_servings NUMERIC(12, 3)
        CHECK (actual_servings IS NULL OR actual_servings >= 0),
    ADD COLUMN not_made        BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN meal_plans.actual_servings IS
    'How many servings this dish actually went out at, from the returned job card (B5). Null until the meal is recorded; 0 for a dish that was not made.';
COMMENT ON COLUMN meal_plans.not_made IS
    'The dish never went into a pot. It draws nothing from stock, and it is why the row reads CANCELLED — it was called off at the stove rather than in the plan.';

-- --- Outside event: what is it for? (B6) -----------------------------------
--
-- Free text, and deliberately not a picklist: the reasons a temple cooks for an
-- outside event are open-ended — a Bhagavad-gita reading, book distribution, a
-- school event — and a list of five would be wrong by the sixth. Nothing in the
-- system reasons about this value. It is a label for the kitchen and for the job
-- card, and it is stored as the person wrote it.
--
-- Modelled the way needs_client and needs_venue already are: a flag on the kind
-- rather than a name the application recognises, so a temple that also wants a
-- purpose on its catering orders sets the flag and no code changes.
ALTER TABLE meal_kinds ADD COLUMN needs_purpose BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE meal_plans ADD COLUMN purpose TEXT
    CHECK (purpose IS NULL OR length(purpose) <= 300);

COMMENT ON COLUMN meal_kinds.needs_purpose IS
    'The plan must say what the food is for — a reading, a school event (B6). Free text, never a list.';
COMMENT ON COLUMN meal_plans.purpose IS
    'What this food is for, in the planner''s own words. Printed on the job card; nothing computes on it.';

-- --- Per-tenant backfill ---------------------------------------------------
--
-- Both meal_kinds and meal_plans carry FORCE ROW LEVEL SECURITY, so this
-- migration's own UPDATEs are filtered by the isolation policy exactly as the
-- application's are: a plain cross-tenant UPDATE matches nothing and reports
-- success, which is the quiet failure V48's header describes. So adopt each
-- tenant in turn, as the application does.
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        -- B6: the kind that takes a purpose today. A temple may set it on others.
        UPDATE meal_kinds SET needs_purpose = true WHERE lower(name) = 'outside event';

        -- A7: Outside event and Catering order swap places. They are ordered by
        -- sort_order alone, so this is the whole change — the seed in
        -- MealKindService is changed to match, for temples provisioned after this.
        UPDATE meal_kinds SET sort_order = 50 WHERE lower(name) = 'outside event';
        UPDATE meal_kinds SET sort_order = 60 WHERE lower(name) = 'catering order';

        -- Meals already marked cooked under the old per-dish button went out at the
        -- planned figure, because that is the only figure anyone ever gave and the
        -- only one their stock was drawn against. Recording it as the actual figure
        -- states what happened rather than leaving a gap that reads as "unknown".
        UPDATE meal_plans SET actual_servings = target_servings
        WHERE status = 'COOKED' AND actual_servings IS NULL;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);
END $$;

-- --- The job card is a third kind of document ------------------------------
--
-- The documents pipeline (V12, extended by V29) already does everything a job
-- card needs: PENDING -> READY, object storage, an authorised download, and a
-- language on the row. Both of V29's CHECKs are exhaustive by design, so a third
-- kind has to be admitted explicitly rather than slipping in.
ALTER TABLE documents ADD COLUMN meal_service_id UUID REFERENCES meal_services(id) ON DELETE CASCADE;

ALTER TABLE documents DROP CONSTRAINT documents_kind_valid;
ALTER TABLE documents ADD CONSTRAINT documents_kind_valid
    CHECK (kind IN ('RECIPE_PDF', 'PURCHASE_ORDER_PDF', 'JOB_CARD_PDF'));

ALTER TABLE documents DROP CONSTRAINT documents_target_shape;
ALTER TABLE documents ADD CONSTRAINT documents_target_shape CHECK (
    (kind = 'RECIPE_PDF' AND recipe_id IS NOT NULL AND po_id IS NULL AND meal_service_id IS NULL)
    OR (kind = 'PURCHASE_ORDER_PDF' AND po_id IS NOT NULL AND recipe_id IS NULL AND meal_service_id IS NULL)
    OR (kind = 'JOB_CARD_PDF' AND meal_service_id IS NOT NULL AND recipe_id IS NULL AND po_id IS NULL));

-- Versioned like a PO sheet rather than overwritten like a recipe card: a card
-- reprinted after a dish was swapped is a different sheet, and the kitchen may be
-- holding the earlier one.
CREATE INDEX documents_tenant_meal_service ON documents (tenant_id, meal_service_id, version DESC);

-- --- A general cache for translated document labels ------------------------
--
-- The PO sheet caches its translated labels per (tenant, language, label-set
-- version) and matches on the provider, so switching engines re-translates
-- rather than printing the old engine's words (V32). The job card wants exactly
-- that behaviour, and a second set of labels.
--
-- This table is that cache with a label_set discriminator, which is what
-- po_label_translations would have had if it had been written second. It is a new
-- table rather than a column added to that one deliberately: widening the shipped
-- table means changing its unique key and the ON CONFLICT that targets it, in the
-- middle of a build where other work is in flight, for behaviour no temple can
-- see. The honest cost is two tables doing one job until somebody next opens
-- PurchaseOrderLabelTranslator and folds it in; that is noted here so the second
-- table is a decision on the record and not an accident.
CREATE TABLE document_label_translations (
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- Which document's labels these are. Free text rather than a CHECK: the set is
    -- decided by whichever template asks, and a stale row simply never matches.
    label_set         TEXT        NOT NULL,
    language          TEXT        NOT NULL,

    -- Bumped by the template when its English labels change, which invalidates
    -- every cached translation of them without a delete.
    label_set_version INTEGER     NOT NULL,

    -- The engine that produced these words. Part of the key, not just a note.
    provider          TEXT        NOT NULL,

    content           JSONB       NOT NULL,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, label_set, language, label_set_version)
);
SELECT enable_tenant_rls('document_label_translations');

COMMENT ON TABLE document_label_translations IS
    'Translated fixed labels for printed documents, cached per tenant, label set, language and label-set version, and matched on the translation engine that produced them.';
