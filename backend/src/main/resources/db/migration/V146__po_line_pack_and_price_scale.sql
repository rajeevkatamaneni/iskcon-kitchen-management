-- =====================================================================
-- V146 — A purchase-order line can be ordered in a pack, and its expected
--        price keeps four decimal places (T-260, PROCUREMENT-REQUIREMENTS.md
--        R-SL-3, R-SL-1, §3)
--
-- Two changes to purchase_order_lines, both schema only. No row is read or
-- written, so there is no per-tenant loop here, and that is a decision rather
-- than an oversight: ALTER COLUMN TYPE, ADD COLUMN and ADD CONSTRAINT are DDL
-- and run as the table owner, which the row-level policy does not filter. The
-- CHECK and the foreign key added below are validated against every tenant's
-- existing rows for the same reason, and every existing row passes them
-- trivially, because both new columns are null on all of them.
--
-- ---------------------------------------------------------------------
-- 1. expected_price to NUMERIC(14, 4)
--
-- V145 widened vendor_supplies.last_price to four places, because a list
-- price per ONE canonical unit is a fraction of a paisa for anything counted
-- in gm or ml: ₹71.20 per Kg is ₹0.0712 per gm. A line raised without a price
-- is given that list price (PurchaseOrderService.withLastKnownPrices), and V26
-- stored it in NUMERIC(12, 2), so ₹0.0712 became ₹0.07 on the order: 1.7% out
-- on the line total, and a different figure from the one on the vendor page.
-- The order would then disagree with the list price it was copied from, and
-- the invoice variance (VendorInvoiceService) would compare the bill against
-- the rounded figure.
--
-- Widening only: every existing value is kept exactly (45.00 becomes
-- 45.0000). The CHECK-free column has no view, function or index depending on
-- its type (checked 2026-09-19: GivingPageController and VendorInvoiceService
-- read it in plain SQL, which is type-agnostic).
--
-- The other price columns on the order's path, looked at for the same fault:
--   goods_receipt_lines.unit_price  NUMERIC(12, 2) (V82) — the same rounding
--       would apply to a per-gram price typed at a delivery, but R-DEL-5
--       removes price entry at delivery altogether, so it is left to that
--       task rather than widened here for a figure nobody will be asked for.
--   vendor_invoices amounts, vendor_invoice_lines.line_amount — rupee
--       totals, where two places is right; the per-unit rate on an invoice
--       line is already NUMERIC(14, 4) (V144).
-- ---------------------------------------------------------------------

ALTER TABLE purchase_order_lines
    ALTER COLUMN expected_price TYPE NUMERIC(14, 4);

COMMENT ON COLUMN purchase_order_lines.expected_price IS
    'The price the temple expects to pay, in rupees per one of this line''s `unit`. Four places since V146, because a per-gram or per-ml list price is a fraction of a paisa.';


-- ---------------------------------------------------------------------
-- 2. Ordered in a pack: pack_size_id and pack_count (R-SL-3)
--
-- "100 Kg short, sold as Bag = 25 Kg -> 4 bags, and the PO line, the PDF and
-- the WhatsApp text read '4 × Bag (25 Kg)'. The line also keeps its
-- stock-unit quantity (100 Kg) for stock and costing."
--
-- So quantity and unit stay exactly what they are — a real amount in one of
-- the five units, which receiving, the shopping list's outstanding map,
-- costing and the invoice variance all read without knowing packs exist —
-- and the pack rides beside them: which pack, and how many. The service
-- writes quantity = pack_count × the pack's size, in the pack's unit, so the
-- two cannot disagree on any row it writes. That agreement needs the pack's
-- size from another table, so it cannot be a CHECK; it is the service's, the
-- same split V144 made for vendor_invoice_lines.billed_qty and pack_count.
--
-- The shape is vendor_invoice_lines' (V144) word for word, for the same
-- reasons:
--   * a pair — which pack and how many — or neither;
--   * pack_count > 0: an order for no bags is not an order line;
--   * only on a catalogue line. A described line (four plastic stools, V100)
--     has no ingredient, so no pack sizes to name.
-- NUMERIC(14, 3) like the invoice line's pack_count, so half a bag is
-- expressible if a vendor sells one; the screen asks for whole packs.
--
-- The composite foreign key is what stops a line naming a pack of a
-- different ingredient ("Rice ordered as Tin = 15 L of ghee"): it targets
-- ingredient_pack_sizes (id, ingredient_id), the pair V144 made unique for
-- exactly this. MATCH SIMPLE, the default, so a line with no pack (a null
-- pack_size_id) is not checked at all. The pack belongs to the same tenant as
-- the line by construction: the ingredient is the line's own, and a pack is
-- always its own ingredient's temple's (V144's same-family trigger reads the
-- ingredient under RLS and refuses anything else).
--
-- RESTRICT, so removing a pack an order was placed in is refused — the
-- sheet's "4 × Bag (25 Kg)" must go on meaning what it said to the vendor.
-- PackSizeService translates the refusal to KMS-400159 (PACK_SIZE_IN_USE).
-- DEFERRABLE INITIALLY IMMEDIATE, like the other two pack foreign keys, so
-- the duplicate-ingredient merge (R-DUP-3) can re-point a pack and every row
-- that uses it inside one transaction; every ordinary statement is still
-- checked at once.
-- ---------------------------------------------------------------------

ALTER TABLE purchase_order_lines
    ADD COLUMN pack_size_id UUID,
    ADD COLUMN pack_count   NUMERIC(14, 3),

    -- pack_count IS NOT NULL is spelled out, and it is not redundant beside
    -- pack_count > 0. A CHECK passes when it is NULL, not only when it is
    -- true: with a pack and no count, the second branch is TRUE AND NULL,
    -- which is NULL, the whole expression is FALSE OR NULL, which is NULL,
    -- and the row would be let in. Found by this migration's own test
    -- (ProcurementDataModelMigrationIT, "a pack with no count"). V144's
    -- invoice_lines_pack_shape is written the way this was, and has the same
    -- hole; that is reported to the conductor rather than changed here.
    ADD CONSTRAINT po_lines_pack_shape CHECK (
        (pack_size_id IS NULL AND pack_count IS NULL)
        OR (pack_size_id IS NOT NULL AND pack_count IS NOT NULL AND pack_count > 0
            AND ingredient_id IS NOT NULL)),

    ADD CONSTRAINT po_lines_pack_of_this_ingredient
        FOREIGN KEY (pack_size_id, ingredient_id)
        REFERENCES ingredient_pack_sizes (id, ingredient_id)
        ON DELETE RESTRICT
        DEFERRABLE INITIALLY IMMEDIATE;

COMMENT ON COLUMN purchase_order_lines.pack_size_id IS
    'The pack this line was ordered in (R-SL-3), one of the line''s own ingredient''s packs. Null = ordered in `unit` itself.';
COMMENT ON COLUMN purchase_order_lines.pack_count IS
    'How many of that pack: the 4 of "4 × Bag (25 Kg)". quantity stays the same amount in a real unit (100 KG), written by the service as pack_count × the pack''s size.';

-- For the foreign key's RESTRICT check when a pack is removed, which would
-- otherwise scan every order line in the temple.
CREATE INDEX po_lines_pack_size ON purchase_order_lines (pack_size_id) WHERE pack_size_id IS NOT NULL;
