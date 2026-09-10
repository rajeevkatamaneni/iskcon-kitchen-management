-- =====================================================================
-- V117 — A donation gets its receipt (T-110)
--
-- The product captures PAN, enforces the 80G shape with a CHECK (V38), keeps a
-- Form 10BD-shaped dataset by construction, and strikes a wrong gift without
-- losing it (V104). The one document a donor actually asks for — the piece of
-- paper they file with their return — did not exist. This migration is what the
-- documents pipeline needs before it can make one.
--
-- ---------------------------------------------------------------------
-- A fifth kind of document, not a second pipeline
--
-- V12 built the PENDING -> READY lifecycle, object storage and the authorised
-- download; V29, V64 and V79 each admitted one more kind through it. Both of
-- V29's CHECKs are exhaustive by design — a kind that is not named cannot be
-- inserted, and a row pointing at the wrong target cannot either — so a fifth
-- kind has to be admitted explicitly rather than slipping in. That is the whole
-- reason a new kind of document is a migration.
--
-- ---------------------------------------------------------------------
-- Overwritten in place like a recipe card, and *issued once* besides
--
-- The PO sheet, the job card and the work order are versioned: each of them
-- describes a world that moves under it, and a reprint after the world moved is
-- a genuinely different sheet that somebody may already be holding. A receipt is
-- the opposite kind of paper. It reports one payment that has already happened,
-- and the copy in the donor's file and the copy in the temple's must be the same
-- document or the temple has issued two receipts for one gift — which under 80G
-- is the failure the whole feature exists to avoid.
--
-- So: one receipt row per donation, enforced by a unique index rather than by
-- the application remembering to check. Re-sending finds the row that is already
-- there. Nothing here can produce a second.
--
-- ---------------------------------------------------------------------
-- The number on it
--
-- A receipt with no number is not a receipt — it is a letter about a donation.
-- The number is issued once, stored on the donation, and never reissued, so the
-- copy the donor filed last April still names the same receipt when the temple
-- is asked about it next year. The counter is per tenant and works exactly as
-- `po_sequence` and `meal_card_sequence` do: the atomic INSERT ... ON CONFLICT
-- DO UPDATE ... RETURNING row-locks per tenant, so two people issuing at once
-- never get one number, and a rolled-back issue leaves a gap. Gaps are fine —
-- the number identifies a receipt, it does not count them.
-- =====================================================================

-- --- The document ----------------------------------------------------

ALTER TABLE documents
    ADD COLUMN donation_id UUID REFERENCES donations(id) ON DELETE CASCADE;

ALTER TABLE documents DROP CONSTRAINT documents_kind_valid;
ALTER TABLE documents ADD CONSTRAINT documents_kind_valid
    CHECK (kind IN ('RECIPE_PDF', 'PURCHASE_ORDER_PDF', 'JOB_CARD_PDF', 'WORK_ORDER_PDF',
                    'DONATION_RECEIPT_PDF'));

-- Each kind points at exactly its own target and nothing else. Every arm names
-- every column, including the four that were already exhaustive, because a new
-- column left out of the older arms would let a recipe card carry a donation id.
ALTER TABLE documents DROP CONSTRAINT documents_target_shape;
ALTER TABLE documents ADD CONSTRAINT documents_target_shape CHECK (
    (kind = 'RECIPE_PDF' AND recipe_id IS NOT NULL AND po_id IS NULL
        AND meal_service_id IS NULL AND ingredient_request_id IS NULL AND donation_id IS NULL)
    OR (kind = 'PURCHASE_ORDER_PDF' AND po_id IS NOT NULL AND recipe_id IS NULL
        AND meal_service_id IS NULL AND ingredient_request_id IS NULL AND donation_id IS NULL)
    OR (kind = 'JOB_CARD_PDF' AND meal_service_id IS NOT NULL AND recipe_id IS NULL
        AND po_id IS NULL AND ingredient_request_id IS NULL AND donation_id IS NULL)
    OR (kind = 'WORK_ORDER_PDF' AND ingredient_request_id IS NOT NULL AND recipe_id IS NULL
        AND po_id IS NULL AND meal_service_id IS NULL AND donation_id IS NULL)
    OR (kind = 'DONATION_RECEIPT_PDF' AND donation_id IS NOT NULL AND recipe_id IS NULL
        AND po_id IS NULL AND meal_service_id IS NULL AND ingredient_request_id IS NULL));

-- One receipt per gift, in the database rather than in a service method. This is
-- the constraint behind "re-sending does not create a second document": the
-- application looks for the existing row first, and if that check were ever lost
-- the insert behind it still cannot succeed.
--
-- No tenant_id in the key, deliberately. A donation id belongs to exactly one
-- temple, so the narrower key is the stronger one and a conflict can only ever
-- be raised within the tenant that owns the gift.
CREATE UNIQUE INDEX documents_one_receipt_per_donation
    ON documents (donation_id) WHERE kind = 'DONATION_RECEIPT_PDF';

COMMENT ON COLUMN documents.donation_id IS
    'The gift this 80G receipt was issued for (T-110). Null on every other kind of document. Unique among receipts: one payment, one receipt.';

-- --- The number the donor quotes back --------------------------------

ALTER TABLE donations
    ADD COLUMN receipt_number    TEXT,
    ADD COLUMN receipt_issued_at TIMESTAMPTZ;

-- Both or neither, the same shape `meal_services` uses for its card number. A
-- number with no issue date cannot be dated by anyone reading the row later, and
-- an issue date with no number describes a receipt nobody can find.
ALTER TABLE donations ADD CONSTRAINT donations_receipt_number_shape CHECK (
    (receipt_number IS NULL AND receipt_issued_at IS NULL)
    OR (receipt_number IS NOT NULL AND receipt_issued_at IS NOT NULL));

CREATE UNIQUE INDEX donations_receipt_number
    ON donations (tenant_id, receipt_number) WHERE receipt_number IS NOT NULL;

COMMENT ON COLUMN donations.receipt_number IS
    'The permanent number printed on this gift''s 80G receipt (T-110). Issued once and never reissued — a donor may quote it from a copy filed years ago. Null until a receipt is issued.';
COMMENT ON COLUMN donations.receipt_issued_at IS
    'When the receipt number was issued. Not when the PDF was last rendered: the document may be re-rendered, the number never changes.';

CREATE TABLE donation_receipt_sequence (
    tenant_id   UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE RESTRICT,
    last_number INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE donation_receipt_sequence IS
    'Per-tenant receipt counter (T-110). One row per temple, incremented atomically as each receipt number is issued. Not reset each year — the year is stamped into the printed number for a person reading it, not derived from the counter.';

SELECT enable_tenant_rls('donation_receipt_sequence');
