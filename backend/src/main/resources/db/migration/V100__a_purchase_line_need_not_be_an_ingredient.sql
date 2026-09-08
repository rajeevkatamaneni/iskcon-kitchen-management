-- =====================================================================
-- V100 — A purchase-order line need not name an ingredient (T-024, D-1)
--
-- Four plastic stools from a furniture shop and two extension cords from
-- an electrical one. The temple buys them, on a purchase order, from a
-- vendor, and pays a bill for them. What it does not want is the thing
-- the schema forced on it until now: an `ingredients` row invented so
-- that a PO line had something to point at. That row would then be
-- offered in the recipe picker, counted by the low-stock job, shown on
-- the ingredients screen, and asked for a canonical unit and a category
-- it has no business having.
--
-- So a line carries EITHER an ingredient OR a description, never both
-- and never neither.
--
-- ---------------------------------------------------------------------
-- Why the CHECK is exclusive rather than merely permissive
--
-- The obvious alternative — "description is optional, and may sit beside
-- an ingredient as a note" — was rejected. `ingredient_id IS NULL` is
-- the discriminator that six separate consumers now branch on: the PO
-- detail query, the unit-family check, the shopping list's outstanding
-- map, the giving page's spend breakdown, the printed vendor sheet, and
-- the WhatsApp summary. A column that is sometimes a subject and
-- sometimes a footnote gives none of them a question they can ask. A
-- strict XOR gives all six the same one.
--
-- The description is additionally required to be non-blank. An empty
-- string is not a description, and NULLIF-ing it in the application
-- would leave the row satisfying the constraint while naming nothing —
-- exactly the silent state this constraint exists to make impossible.
-- Trailing space is trimmed by the application before it arrives; the
-- btrim() here is the guard, not the trimmer.
--
-- ---------------------------------------------------------------------
-- What deliberately does NOT change, and this is the load-bearing half
--
-- A described line is ORDERABLE and PAYABLE. It is never RECEIVABLE.
--
--   goods_receipt_lines.ingredient_id  NOT NULL   (V27:48)
--   stock_movements.ingredient_id      NOT NULL   (V14:19)
--   vendor_supplies.ingredient_id      NOT NULL   (V24:49)
--
-- All three stay NOT NULL. The store room counts things; a stool is not
-- one of them, and there is no batch, no expiry, no on-hand quantity and
-- no reorder point that would mean anything about it. Relaxing any of
-- those three columns to let a stool through would put a permanent null
-- into the ledger — the one table in this application that is meant to
-- be arithmetic — in order to record a fact the purchase order already
-- records perfectly well.
--
-- ReceivingService therefore refuses a receipt line that points at a
-- described PO line (KMS-400129) and leaves described lines out of the
-- "is this order fully received" arithmetic, so an order carrying one
-- can still reach RECEIVED rather than sticking at PARTIALLY_RECEIVED
-- for ever.
--
-- ---------------------------------------------------------------------
-- `unit` stays NOT NULL, and stays constrained to the same five
--
-- V74:83-84 constrains unit to KG/GM/L/ML/PIECES. Four stools is
-- 4 PIECES, and two extension cords is 2 PIECES. A described line names
-- a quantity in a real unit like any other line — that is what makes it
-- printable on a vendor sheet somebody buys against. Nothing here
-- touches that column.
--
-- ---------------------------------------------------------------------
-- RLS, and why there is no per-tenant loop in this file
--
-- purchase_order_lines carries enable_tenant_rls() (V26:67) and the
-- migration role is unprivileged, so any SELECT or UPDATE here would run
-- with app.tenant_id unset and see nothing (the policy fails closed via
-- NULLIF). Migrations in this project therefore adopt each tenant in
-- turn when they touch rows — V97 and V98 both do.
--
-- This one touches no rows. Every existing line has an ingredient_id
-- (it was NOT NULL until this statement) and therefore already satisfies
-- the new constraint with description NULL; there is nothing to seed and
-- nothing to backfill. DROP NOT NULL, ADD COLUMN and ADD CONSTRAINT are
-- all DDL and run as the table owner, which is not subject to the
-- policy. So the absence of a tenant loop here is a decision, not an
-- oversight.
--
-- ADD CONSTRAINT does validate the existing rows, and does so as the
-- owner — it is not a policy-filtered read, so it genuinely checks every
-- tenant's rows rather than silently checking none of them.
-- =====================================================================

ALTER TABLE purchase_order_lines
    ALTER COLUMN ingredient_id DROP NOT NULL;

ALTER TABLE purchase_order_lines
    ADD COLUMN description TEXT;

COMMENT ON COLUMN purchase_order_lines.ingredient_id IS
    'The catalogue ingredient this line is for, or NULL when the line is described instead (T-024). NULL here is the discriminator for a line that is orderable and payable but never receivable into stock.';

COMMENT ON COLUMN purchase_order_lines.description IS
    'What is being bought when the catalogue has never heard of it — "Plastic stool", "Extension cord" (T-024). Set exactly when ingredient_id is NULL. Never a note beside an ingredient: see po_lines_has_exactly_one_subject.';

-- Exactly one subject per line. Spelled out as two branches rather than
-- as `(ingredient_id IS NULL) <> (description IS NULL)` because the
-- non-blank rule only belongs on one of them, and because a constraint
-- somebody reads in a psql \d output should say what it means.
ALTER TABLE purchase_order_lines
    ADD CONSTRAINT po_lines_has_exactly_one_subject
    CHECK (
        (ingredient_id IS NOT NULL AND description IS NULL)
        OR
        (ingredient_id IS NULL AND description IS NOT NULL AND btrim(description) <> '')
    );
