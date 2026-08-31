-- =====================================================================
-- V79 — The work order is a fourth kind of document (E10-S11)
--
-- An approved request produces a sheet the storekeeper carries round the
-- store room: what to pick, which lot to pick it from, why, for whom, and
-- two boxes for the two people who sign. The documents pipeline (V12,
-- extended by V29 and V64) already does everything it needs — PENDING to
-- READY, object storage, an authorised download, a language on the row —
-- and both of V29's CHECKs are exhaustive by design, so a fourth kind has
-- to be admitted explicitly rather than slipping in.
--
-- ---------------------------------------------------------------------
-- Versioned, like the job card and the purchase-order sheet
--
-- Not overwritten like a recipe card. A sheet reprinted after a lot was
-- spoilt names different batches from the one somebody is already holding,
-- and both were true when they were printed. The number on the paper is
-- how you tell which is which.
--
-- Note what this does *not* freeze: the batches themselves. The sheet is a
-- picking list computed when it is rendered, not a snapshot taken at
-- approval — an afternoon's cooking can empty the lot a sheet printed this
-- morning would have named, and a work order that sends a storekeeper to a
-- shelf that is bare is worse than no work order. Approval decides that
-- the kitchen may have the food; the sheet says where today's is.
-- =====================================================================

ALTER TABLE documents
    ADD COLUMN ingredient_request_id UUID REFERENCES ingredient_requests(id) ON DELETE CASCADE;

ALTER TABLE documents DROP CONSTRAINT documents_kind_valid;
ALTER TABLE documents ADD CONSTRAINT documents_kind_valid
    CHECK (kind IN ('RECIPE_PDF', 'PURCHASE_ORDER_PDF', 'JOB_CARD_PDF', 'WORK_ORDER_PDF'));

ALTER TABLE documents DROP CONSTRAINT documents_target_shape;
ALTER TABLE documents ADD CONSTRAINT documents_target_shape CHECK (
    (kind = 'RECIPE_PDF' AND recipe_id IS NOT NULL AND po_id IS NULL
        AND meal_service_id IS NULL AND ingredient_request_id IS NULL)
    OR (kind = 'PURCHASE_ORDER_PDF' AND po_id IS NOT NULL AND recipe_id IS NULL
        AND meal_service_id IS NULL AND ingredient_request_id IS NULL)
    OR (kind = 'JOB_CARD_PDF' AND meal_service_id IS NOT NULL AND recipe_id IS NULL
        AND po_id IS NULL AND ingredient_request_id IS NULL)
    OR (kind = 'WORK_ORDER_PDF' AND ingredient_request_id IS NOT NULL AND recipe_id IS NULL
        AND po_id IS NULL AND meal_service_id IS NULL));

CREATE INDEX documents_tenant_ingredient_request
    ON documents (tenant_id, ingredient_request_id, version DESC);

COMMENT ON COLUMN documents.ingredient_request_id IS
    'The approved request this work order was printed for (E10-S11). Null on every other kind of document.';
