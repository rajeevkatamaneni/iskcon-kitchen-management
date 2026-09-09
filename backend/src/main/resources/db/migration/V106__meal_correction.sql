-- =====================================================================
-- V106 — A recorded meal can be corrected, and what it first said survives
--        (T-007; docket S5, and M2 which is the same item read twice)
--
-- Recording a meal was a one-way door. `ServedMealService.record` refused a
-- second recording with MEAL_ALREADY_RECORDED, whose next step told the office
-- in as many words that "what was cooked can't be changed afterwards". So a
-- lunch typed in as 400 when 640 went out stayed 400 for ever — and the store
-- room stayed drawn down against 400, understating what the temple actually
-- ate for as long as anybody cared to look. That sentence becomes false the
-- day this ships, and it has been changed with it.
--
-- ---------------------------------------------------------------------
-- Not a reopening. A correction.
-- ---------------------------------------------------------------------
-- Rajeev's ruling of 2026-09-07 settles the shape, and it is the shape V103
-- and V104 took one story earlier: the mistake is not erased, it is answered.
--
--   * The stock ledger is APPEND-ONLY, so it is *compensated*. Every draw the
--     meal made stays exactly where it is and gains a reverse entry beside it,
--     which `StockMovementService.compensate` already knows how to write. The
--     new figure is then drawn afresh, so the ledger nets to what actually
--     went out while still showing, in order, what was believed and when.
--
--   * The meal is *marked*. Its dishes are read one row at a time — by the
--     planner, by Today, by the job card — so a screen has to be able to say
--
--         "640 cooked, corrected from 400 by Anand on 8 September"
--
--     rather than silently showing a different number than it showed
--     yesterday. That sentence is what the columns below are for, and the
--     audit trail is then a consequence of the shape rather than something
--     bolted onto it.
--
-- ---------------------------------------------------------------------
-- Two grains, because a meal has two
-- ---------------------------------------------------------------------
-- There is still no meal-line table — V64's header argues that at length and
-- nothing here changes it. One `meal_plans` row is one dish; `meal_services`
-- is the meal. The correction splits along exactly that seam:
--
--   * The FIGURES are per dish, so the two `original_*` columns go on
--     `meal_plans`, beside the columns a correction overwrites in place. A
--     correction may move one dish of a three-dish lunch and leave the other
--     two untouched, and a meal-level "original figure" would have to invent a
--     way of adding three preparations together — the same arithmetic that
--     produced the 750-plate lunch.
--
--   * WHO corrected it, WHEN and WHY are facts about the whole meal: one act,
--     one form, one press. They go on `meal_services`, mirroring the
--     `recorded_at` / `recorded_by` / `recording_note` trio directly above
--     them. The mirroring is deliberate — a reader who understands the first
--     three understands these three without being told.
--
-- ---------------------------------------------------------------------
-- Why the original figures are stored rather than derived
-- ---------------------------------------------------------------------
-- They look derivable: the meal's first draw is still in `stock_movements`,
-- and dividing it back through the recipe would recover the yield it was
-- scaled from. Three reasons not to.
--
-- It is arithmetic over thousands of ledger rows to answer a question one line
-- of a planner screen asks about every corrected meal. It is lossy — each draw
-- is rounded to three decimals per batch (`InventoryConsumptionService:86`),
-- so the number that came back would not reliably be the number that was
-- typed. And it is IMPOSSIBLE for a dish recorded as "not made", which drew
-- nothing at all and so left nothing to divide.
--
-- Two columns, written once, at the moment the fact is still in hand.
--
-- ---------------------------------------------------------------------
-- Why "original" is an honest name
-- ---------------------------------------------------------------------
-- Because a meal is correctable exactly ONCE. A second attempt is KMS-400137
-- (MEAL_ALREADY_CORRECTED), refused in the service under a `FOR UPDATE` on the
-- meal_services row so that two tabs cannot both pass the check and reverse
-- the same movements twice. That is what lets these columns mean "the first
-- figure": with unlimited corrections they would have to mean "the figure
-- before the most recent one", which is a different and much weaker fact.
--
-- Correcting a correction is deliberately not offered. If a temple ever needs
-- it, that is a chain of corrections and it wants a table, not a second column
-- — the same reasoning MOVEMENT_ALREADY_CORRECTED applies one grain down,
-- where the answer is to correct the correction rather than to widen the row.
--
-- ---------------------------------------------------------------------
-- Row-level security, and why there is no backfill
-- ---------------------------------------------------------------------
-- Both tables are tenant-owned and both already carry the policy:
-- `enable_tenant_rls('meal_services')` is V64:70 and meal_plans has had it
-- since its own migration. Nothing here adds a table, so nothing here needs a
-- new `enable_tenant_rls` call.
--
-- And there is no backfill, which is worth saying out loud rather than leaving
-- a reader to notice an absence. NULL already means what every existing row
-- means — "this was never corrected" — so there is nothing to write. That
-- matters because migrations in this project run SUBJECT to row-level
-- security (V45/V46/V48 were bought at the price of learning it): a backfill
-- written as one cross-tenant UPDATE matches no rows and reports success. Any
-- later migration that does have to write these columns must adopt each tenant
-- in turn with `set_config('app.tenant_id', ...)`, exactly as V64:133 does.
--
-- No index, either. Both tables are reached by primary key or by the
-- tenant-and-date indexes they already carry, and a correction is a rare act
-- on a row the planner has already found.
-- =====================================================================

-- --- Per meal: who corrected it, when, and why ------------------------------
ALTER TABLE meal_services
    ADD COLUMN corrected_at    TIMESTAMPTZ,

    -- ON DELETE SET NULL, matching `recorded_by` a few lines above it in V64
    -- rather than the ON DELETE RESTRICT that V104 gave `donations.voided_by`.
    -- The two are answering different questions. A struck donation is an 80G
    -- record, and who struck it is part of what a tax authority may later be
    -- shown; a corrected meal is an operational record, and a temple removing
    -- a departed admin must not be blocked by a lunch from last March. That
    -- the correction happened, when, and why all outlive the loss of the name,
    -- and the audit event under MEAL_CORRECTED holds the fuller account.
    ADD COLUMN corrected_by    UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN correction_note TEXT,

    -- All of it or none of it, the way `meal_services_card_shape` holds the
    -- card's two halves together. Note what is NOT in this constraint:
    -- `corrected_by`. It cannot be, precisely because of the SET NULL above —
    -- pairing it with `corrected_at` would make the foreign key's own UPDATE
    -- violate this CHECK, so deleting a user would fail at a baffling
    -- distance, in a constraint whose name says nothing about users.
    --
    -- The note IS in it, and is required rather than offered. A correction
    -- moves a figure the temple has already acted on — stock was drawn against
    -- the old one — so the next person to read the row is entitled to know why
    -- it moved. `recording_note` beside it is optional, and that asymmetry is
    -- the point: recording says what happened, correcting says why what we
    -- said was wrong, and only the second is unreadable without a sentence.
    -- Blank is refused here as well as at the API, so it cannot be reduced to
    -- a space bar by some later write path.
    ADD CONSTRAINT meal_services_correction_shape CHECK (
        (corrected_at IS NULL AND correction_note IS NULL)
        OR (corrected_at IS NOT NULL AND btrim(correction_note) <> '')),

    ADD CONSTRAINT meal_services_correction_note_length CHECK (
        correction_note IS NULL OR length(correction_note) <= 2000),

    -- There is nothing to correct until something was recorded. The service
    -- refuses this first and by name; the constraint is here so that no later
    -- write path can produce a row claiming a correction to a meal that never
    -- came back from the kitchen.
    ADD CONSTRAINT meal_services_correction_follows_recording CHECK (
        corrected_at IS NULL OR recorded_at IS NOT NULL);

COMMENT ON COLUMN meal_services.corrected_at IS
    'When this meal''s recorded figures were corrected (T-007). Null on a meal that stands as it was recorded, which is nearly all of them. Non-null is also the guard: a second correction is refused with KMS-400137, because it would compensate an already-compensated set of movements and draw the store down twice for food cooked once.';
COMMENT ON COLUMN meal_services.corrected_by IS
    'Who corrected it — a Temple Admin, the only role holding CORRECT_RECORDED_MEAL (D-4), unlike the recording it corrects, which kitchen staff may do. SET NULL on user deletion like recorded_by: the fact must outlive the name.';
COMMENT ON COLUMN meal_services.correction_note IS
    'Why the figures were changed, in the words of whoever changed them. Required and never blank, unlike recording_note: it is the only account of why a recorded meal now says something else.';

-- --- Per dish: the figure the first recording gave it ------------------------
--
-- NUMERIC(12, 3) and the non-negative CHECK are copied from the columns they
-- shadow (V64:97 and V71:24) rather than chosen afresh, so a figure that was
-- storable while it was current stays storable once it is history.
ALTER TABLE meal_plans
    ADD COLUMN original_actual_servings   NUMERIC(12, 3)
        CHECK (original_actual_servings IS NULL OR original_actual_servings >= 0),
    ADD COLUMN original_consumed_quantity NUMERIC(12, 3)
        CHECK (original_consumed_quantity IS NULL OR original_consumed_quantity >= 0);

COMMENT ON COLUMN meal_plans.original_actual_servings IS
    'What this dish was FIRST recorded as having cooked, before a correction replaced actual_servings in place (T-007). Null on every dish of a meal nobody has corrected. Never rewritten, because a meal is correctable once — that is what makes "original" mean the first figure rather than the previous one.';
COMMENT ON COLUMN meal_plans.original_consumed_quantity IS
    'The companion of original_actual_servings: what this dish was first recorded as having served. Null where nothing was corrected, and null too where the first card did not say — the same absence, because both mean there is no earlier figure to show. meal_services.corrected_at is what tells the two cases apart.';
