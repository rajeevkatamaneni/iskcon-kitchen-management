-- =====================================================================
-- V29 — Purchase order documents (E5-S4)
--
-- Extends the E2-S5 documents pipeline to a second kind: a printable PO sheet for
-- the vendor. Reuses the same PENDING -> READY lifecycle, object storage, and
-- authorized download. Two differences from a recipe card:
--   * it points at a purchase_order, not a recipe;
--   * it is versioned — a re-render after a rare post-SENT correction produces a
--     new version rather than overwriting, so prior sheets stay retrievable.
-- =====================================================================

ALTER TABLE documents ADD COLUMN po_id UUID REFERENCES purchase_orders(id) ON DELETE CASCADE;

-- Version within a document's target. 1 for every recipe card (they overwrite in
-- place); increments per PO sheet re-render so old vendor sheets are retained.
ALTER TABLE documents ADD COLUMN version INTEGER NOT NULL DEFAULT 1;

ALTER TABLE documents DROP CONSTRAINT documents_kind_valid;
ALTER TABLE documents ADD CONSTRAINT documents_kind_valid
    CHECK (kind IN ('RECIPE_PDF', 'PURCHASE_ORDER_PDF'));

-- Each kind points at exactly its own target and nothing else.
ALTER TABLE documents ADD CONSTRAINT documents_target_shape CHECK (
    (kind = 'RECIPE_PDF' AND recipe_id IS NOT NULL AND po_id IS NULL)
    OR (kind = 'PURCHASE_ORDER_PDF' AND po_id IS NOT NULL AND recipe_id IS NULL));

CREATE INDEX documents_tenant_po ON documents (tenant_id, po_id, version DESC);
