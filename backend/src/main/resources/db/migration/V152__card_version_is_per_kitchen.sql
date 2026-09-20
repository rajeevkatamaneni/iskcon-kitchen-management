-- =====================================================================
-- V152 — One job card per kitchen (Epic 12, T-356)
--
-- WHAT WAS THERE
--
-- A meal printed one job card, and its version lived on the meal: meals.card_version and
-- meals.card_fingerprint (V92, moved onto meals by V136). The card listed every dish of the meal.
--
-- Epic 12 lets two kitchens cook one meal, and Rajeev decided on 2026-09-19 that each kitchen gets
-- its own card, downloaded from its own section of the meal, listing only that kitchen's dishes. A
-- sheet for the sweets kitchen listing the main kitchen's rice is worse than no sheet.
--
-- WHAT THIS CHANGES
--
--   meals.card_version, meals.card_fingerprint
--                     dropped. V150 already copied both onto every meal_kitchens row, and the
--                     job card reads and writes them there from this release on. Each kitchen's
--                     card is its own sheet and so has its own version history: changing the
--                     halva moves the sweets kitchen's card to v2 and leaves the main kitchen's
--                     v1 standing, because nothing on the main kitchen's sheet changed.
--
--                     The card NUMBER is not touched. It stays one per meal, on meals.card_number,
--                     and is printed on every kitchen's card beside the kitchen's name: it is a
--                     filing reference for the meal, and "LC-2026-0013, Sweets kitchen, v2" is a
--                     complete description of one sheet.
--
--   documents.kitchen_id
--                     which kitchen a queued job-card PDF is for. The card is rendered later by a
--                     worker that is handed only the document's id, so the kitchen has to travel
--                     on the document row the way the appendix's language already does
--                     (documents.language). A new request always stores the kitchen it resolved,
--                     named or not. NULL is left only on cards requested before this release, and
--                     means "the meal's only kitchen": the worker resolves it at render time by the
--                     same rule the request endpoint uses, and fails the document with that rule's
--                     code if the meal has gained a second kitchen since.
--
-- NO BACKFILL, ON PURPOSE
--
-- Nothing here writes data, so nothing here is subject to the isolation policy (V48, V57) and no
-- per-temple loop is needed. Existing job-card documents keep kitchen_id NULL, which is exactly
-- right for them: every one was requested when each meal had one kitchen. A READY document is never
-- re-rendered; a PENDING one resolves its meal's only kitchen when the worker picks it up.
--
-- WHY ON DELETE SET NULL, WHEN V150 CHOSE RESTRICT EVERYWHERE
--
-- V150's kitchen keys are RESTRICT because a kitchen that cooked a meal is part of the record. A
-- generated card is not a record of that kind — the meal_kitchens row is — and a RESTRICT key here
-- would make a document row the thing that stops a kitchen being deleted. KitchenService.delete
-- decides deletability by looking at what references a kitchen and refuses with a code; a key it
-- does not know about would surface as an unexplained failure in somebody else's screen instead.
-- A document whose kitchen has gone keeps its PDF and loses only the pointer.
-- =====================================================================

ALTER TABLE meals DROP CONSTRAINT meals_card_version_non_negative;
ALTER TABLE meals DROP COLUMN card_version;
ALTER TABLE meals DROP COLUMN card_fingerprint;

ALTER TABLE documents ADD COLUMN kitchen_id UUID REFERENCES kitchens(id) ON DELETE SET NULL;

-- Only a job card is ever for a kitchen. The other kinds each have their own target column and
-- documents_target_shape (V136) keeps them apart; this keeps the new column out of them too.
ALTER TABLE documents ADD CONSTRAINT documents_kitchen_only_on_job_cards
    CHECK (kitchen_id IS NULL OR kind = 'JOB_CARD_PDF');

COMMENT ON COLUMN documents.kitchen_id IS
    'For a JOB_CARD_PDF, the kitchen whose card this is (Epic 12: one card per kitchen). NULL only on cards requested before V152, meaning the meal''s only kitchen, resolved when the card is rendered. Always NULL for every other kind.';
