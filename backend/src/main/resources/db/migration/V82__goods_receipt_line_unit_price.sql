-- =====================================================================
-- V82 — The price where the goods arrive (INV1, 2026-08-31)
--
-- vendor_supplies.last_price has had exactly one writer since V24:
-- VendorService.setSupply, a person typing into a box on the vendor
-- screen. Receiving wrote no price, invoicing wrote none back, and
-- nothing anywhere noticed that the figure had stopped being true. It
-- ages from the moment somebody stops typing it, silently, and every
-- costing figure and every shopping list reads it as current.
--
-- That matters because the temple takes money for catering. An external
-- cooking order priced off a hand-typed number nobody has revisited in a
-- year is how a temple loses money without ever seeing it happen.
--
-- The delivery is where the truth arrives. The lorry brings a bill, and
-- the storekeeper standing in front of it is the one person in the
-- building who knows what was actually paid. So the receipt line carries
-- the price, and receiving writes it back.
--
-- ---------------------------------------------------------------------
-- Nullable, and it must stay nullable
--
-- Three ordinary things arrive without a price. A delivery can turn up
-- ahead of its bill. A great deal of what this store room holds was
-- *donated* and has no purchase price at all. And a line may be entirely
-- rejected, in which case nothing was bought.
--
-- NULL is therefore "no figure", never zero, and it is handled the way
-- MaterialsCostService already handles a missing price: the gap is
-- reported rather than absorbed. A null here must never write back over
-- a last_price that somebody did type, and must never be summed as a
-- zero downstream. Nothing in this migration lets it.
--
-- ---------------------------------------------------------------------
-- What the price is per
--
-- Rupees per one of the line's own `unit` — the same reading as
-- purchase_order_lines.expected_price, which sits beside a quantity in
-- the same unit and is printed by DocumentGenerationService as
-- "₹45 / Kg". A receipt line's unit is copied from the PO line it
-- receives, and V74 constrains it to the one vocabulary, so this column
-- is never per an unknown unit.
--
-- The write-back target is expressed differently, and deliberately not
-- changed here: vendor_supplies.last_price is rupees per one of the
-- *ingredient's canonical unit* (MaterialsCostService documents this).
-- Every path that creates a PO line today copies the ingredient's
-- canonical unit onto it, so in practice the two agree — but manual PO
-- creation accepts any of the five units, so they are not guaranteed to.
-- ReceivingService converts through Unit.baseFactor() where the two
-- units are in one family, and declines to write back at all where they
-- are not, because ₹/L means nothing about an ingredient held in Kg and
-- a wrong price is worse than a stale one.
--
-- ---------------------------------------------------------------------
-- Append-only, and why that decides the design
--
-- goods_receipt_lines is append-only (V27, re-applied by V49): the
-- trigger rejects every UPDATE. Adding the column is DDL and is fine,
-- but no later pass can come back and fill it in. The price is part of
-- the INSERT or it is absent for ever — which is the right shape anyway,
-- since the receipt is a record of one moment and the price is a fact
-- about that moment.
--
-- Who priced it and when are already on the parent row:
-- goods_receipts.received_by and .received_at. Nothing is duplicated
-- here.
--
-- ---------------------------------------------------------------------
-- Scale, currency, and RLS
--
-- NUMERIC(12, 2) matches vendor_supplies.last_price and
-- purchase_order_lines.expected_price exactly, so a figure can travel
-- expected → received → last_price and back without a rounding step
-- appearing anywhere in the chain.
--
-- No currency column: every amount in this application is INR (B8), and
-- one nullable price is not the place to start a second convention.
--
-- No RLS work: this adds a column to a table that already calls
-- enable_tenant_rls() and make_append_only(). Both hang off the table,
-- not its columns, and neither needs re-applying for an ADD COLUMN.
-- =====================================================================

ALTER TABLE goods_receipt_lines
    ADD COLUMN unit_price NUMERIC(12, 2);

COMMENT ON COLUMN goods_receipt_lines.unit_price IS
    'What was actually paid, in rupees per one of this line''s unit. NULL means no figure was given — a delivery ahead of its bill, or donated goods — and is never read as zero.';

-- A price of zero is a real answer (goods given free with an order); a negative
-- one is not. Same shape as vendor_supplies_price_nonnegative.
ALTER TABLE goods_receipt_lines
    ADD CONSTRAINT gr_lines_unit_price_nonnegative
    CHECK (unit_price IS NULL OR unit_price >= 0);
