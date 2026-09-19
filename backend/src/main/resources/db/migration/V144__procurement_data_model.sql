-- =====================================================================
-- V144 — The procurement data model: order -> delivery -> invoice -> payment
--        (T-248, PROCUREMENT-REQUIREMENTS.md §3, approved by Rajeev 2026-09-19)
--
-- Why this exists. The "Issued from the temple store" report showed the rice
-- the Deity Kitchen received at ₹0. Tracing it: the app held one price per
-- vendor per ingredient (vendor_supplies.last_price, V24), typed by hand on a
-- page nobody finds; 4 of 232 ingredients had any price; an invoice held only
-- a total, with no items; and nothing tied an invoice to what was delivered.
-- This migration lays down the tables and columns that let a price come from
-- the bill the temple actually pays, item by item, and tie order -> delivery
-- -> invoice -> payment together with real foreign keys.
--
-- Schema only. No Java reads or writes any of this yet; the services, screens
-- and rules arrive in later tasks, and several rules below are deliberately
-- left to them (each one says so where it is decided).
--
-- What it adds, in the order below:
--
--   1. ingredient_pack_sizes          the ingredient's alternate units (R-ING-1)
--   2. ingredients.market_rate_*      what it would cost to buy today (R-ING-3)
--      ingredient_market_rate_history  and every change to it, append-only
--   3. vendor_supplies "sells it as"  a pack and a price per pack (R-VEN-1)
--   4. vendor_invoices totals         sub total, GST, other charges, discount,
--                                     grand total (R-INV-5)
--   5. vendor_invoice_lines           an invoice's items (R-INV-4)
--   6. vendor_invoice_deliveries      which deliveries an invoice bills (R-INV-3)
--   7. vendor_price_history           every list price change, append-only
--                                     (R-VEN-3, R-VEN-4)
--   8. invoice_payments.received_by_name   who took the cash (R-PAY-2)
--   9. attachments                    bill uploads and payment proof
--                                     (R-INV-2, R-PAY-2)
--  10. recipe_ingredients.preparation_note  "halved", "slit" (R-DUP-1)
--  11. ingredient_aliases             searchable alternate names, backfilled
--                                     from ingredients.aliases (R-DUP-3)
--
-- What it keeps. Every existing table, column and foreign key is untouched
-- (§3: "Keep all existing links"). In particular vendor_supplies.last_price is
-- NOT renamed: the spec renames it to "List price" in the UI only and leaves
-- the column to the builder, and renaming it here would break every Java class
-- that reads it today in the same release that adds nothing to replace them.
-- ingredients.aliases (TEXT[]) is left in place too; see section 11.
--
-- ---------------------------------------------------------------------
-- Row-level security, and why only one statement here needs a tenant loop
--
-- Every new table carries tenant_id and calls enable_tenant_rls(), and
-- RowLevelSecurityIT.everyTenantOwnedTableIsProtected fails the build if one
-- does not. Everything here is DDL, which runs as the table owner and is not
-- subject to the policy, EXCEPT the alias backfill in section 11: that reads
-- and writes tenant rows, so it adopts each tenant in turn the way V97 and V98
-- do. Every other added column is nullable with no default to fill, so there
-- is nothing to backfill and nothing for a policy to hide.
--
-- ---------------------------------------------------------------------
-- Money and rates: two scales, on purpose
--
-- Amounts somebody reads off a bill or types (a line amount, a pack price, an
-- invoice's totals) are NUMERIC(12, 2), exactly as vendor_invoices.amount,
-- vendor_supplies.last_price and purchase_order_lines.expected_price already
-- are, so a figure can move between them with no rounding step.
--
-- Rates DERIVED per one canonical unit (a market rate, a price-history row, an
-- invoice line's rate) are NUMERIC(14, 4). An ingredient whose stock unit is
-- grams or millilitres is priced in fractions of a paisa: ₹65 / Kg is
-- ₹0.065 / gm, which two decimals would store as ₹0.07 — an 8% error on every
-- costed figure downstream. Four decimals keep ₹0.0650.
--
-- ---------------------------------------------------------------------
-- Decisions taken for this migration (also recorded in docs/work/proof/T-248.md)
--
--   * Market-rate history is its own table on the ingredient, not rows in
--     vendor_price_history with a null vendor. Conductor's ruling, 2026-09-19,
--     final.
--   * Price history survives a vendor stopping supplying an ingredient.
--     PROVISIONAL — conductor's ruling pending Rajeev's confirmation.
--   * A duplicate pack size is the same quantity in canonical units, whatever
--     it is called. PROVISIONAL — conductor's ruling pending Rajeev.
--   Each provisional rule is a single statement below, marked PROVISIONAL, so
--   reversing it is a one-line change.
-- =====================================================================


-- =====================================================================
-- 1. ingredient_pack_sizes — the ingredient's alternate units (R-ING-1)
--
-- The standard pattern (Tally's alternate units, Odoo's units of measure,
-- SAP's alternative units, as Rajeev chose it on 2026-09-19): ONE stock unit
-- per ingredient, which is what is counted and cooked, plus conversions
-- defined once — "Bag = 25 Kg", "Tin = 15 L", "Bundle = 10 pieces", or a
-- plain "500 g". Everything downstream (stock, costing, price history) runs
-- in the stock unit; a pack is how a thing is bought and billed, never how it
-- is counted.
--
-- Per ingredient, not per vendor (Rajeev): the vendor chooses which of these
-- it sells in (vendor_supplies.pack_size_id, section 3).
--
-- What is stored is what was entered: a number and one of the five units
-- (quantity + unit), not a figure already converted. Two reasons. The chip
-- the temple sees must read back exactly what it typed ("500 gm", not
-- "0.5 Kg"); and an ingredient's canonical unit can be edited
-- (IngredientService updates canonical_unit), so a number pre-converted into
-- the old unit would silently become wrong.
--
-- Two generated columns make the rules checkable without a join:
--
--   base_quantity  the size in the family's base unit (gm, ml or pieces),
--                  using the same factors as Unit.baseFactor() — 1,000 for
--                  Kg and L, 1 for the rest. The duplicate rule compares this.
--   unit_family    MASS, VOLUME or COUNT, the same families as Unit.Family.
--
-- What SQL enforces, and what it leaves to the service:
--
--   positive           CHECK, here.
--   one of five units  CHECK, here.
--   no duplicates      UNIQUE index, here (PROVISIONAL, see below).
--   same unit family   a trigger, here: it compares the pack's family with
--                      its ingredient's canonical unit. A CHECK cannot look at
--                      another table, and this is the rule most worth not
--                      getting wrong — a "Tin = 15 L" on an ingredient counted
--                      in Kg would make every figure derived from it nonsense.
--                      It fires on the pack side only. Changing an
--                      ingredient's canonical unit to another family while it
--                      has pack sizes must be refused by the service that
--                      edits the ingredient.
--   at most 8          the service. A count in a trigger is a read-then-write
--                      that two people saving at once would both pass; a
--                      limit that is not reliable in SQL is better stated
--                      once, in the service, than twice with one of them wrong.
-- =====================================================================

CREATE TABLE ingredient_pack_sizes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- A pack size belongs to its ingredient and goes with it. An ingredient
    -- that a vendor supply, invoice line or stock movement points at cannot be
    -- deleted anyway (all RESTRICT), so the cascade only ever removes the
    -- sizes of an ingredient nothing else uses.
    ingredient_id   UUID        NOT NULL REFERENCES ingredients(id) ON DELETE CASCADE,

    -- Optional: "Bag", "Tin", "Bundle", "Pack". Null for a plain size ("500 g").
    name            TEXT,

    -- What was entered: 25 and KG for "Bag = 25 Kg".
    quantity        NUMERIC(14, 3) NOT NULL,
    unit            TEXT        NOT NULL,

    base_quantity   NUMERIC(20, 3) GENERATED ALWAYS AS (
                        quantity * CASE unit WHEN 'KG' THEN 1000 WHEN 'L' THEN 1000 ELSE 1 END
                    ) STORED,
    unit_family     TEXT        GENERATED ALWAYS AS (
                        CASE unit
                            WHEN 'KG' THEN 'MASS'   WHEN 'GM' THEN 'MASS'
                            WHEN 'L'  THEN 'VOLUME' WHEN 'ML' THEN 'VOLUME'
                            ELSE 'COUNT'
                        END
                    ) STORED,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ingredient_pack_sizes_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ingredient_pack_sizes_unit_valid CHECK (unit IN ('KG', 'GM', 'L', 'ML', 'PIECES')),
    -- A blank name is not a name; null says "no name" and says it once.
    CONSTRAINT ingredient_pack_sizes_name_not_blank CHECK (name IS NULL OR btrim(name) <> ''),

    -- The target of the composite foreign keys below (vendor_supplies,
    -- vendor_invoice_lines). id alone is already unique; this pairs it with
    -- the ingredient so that a supply or an invoice line can only ever name a
    -- pack of ITS OWN ingredient — "Rice sold as Tin = 15 L (of ghee)" is
    -- refused by the database rather than trusted to every caller.
    CONSTRAINT ingredient_pack_sizes_id_ingredient UNIQUE (id, ingredient_id)
);

COMMENT ON TABLE ingredient_pack_sizes IS
    'An ingredient''s alternate units (R-ING-1): "Bag = 25 Kg", "500 gm". Stock and costing stay in the canonical unit; a pack is how the thing is bought and billed.';
COMMENT ON COLUMN ingredient_pack_sizes.quantity IS
    'The size as entered, in this row''s unit (25 for "Bag = 25 Kg"). Not pre-converted: the canonical unit can be edited.';
COMMENT ON COLUMN ingredient_pack_sizes.base_quantity IS
    'The size in the family''s base unit (gm, ml or pieces), generated with Unit.baseFactor()''s factors. What the duplicate rule compares.';

-- PROVISIONAL (conductor's ruling 2026-09-19, awaiting Rajeev): a duplicate is
-- the same size in canonical units whatever it is called, so "500 gm" and
-- "Pack = 0.5 Kg" collide, and so do "Bag = 25 Kg" and "Sack = 25 Kg". Two
-- names for one size would give the shopping list two answers to "how many
-- packs". If Rajeev rules that only the same name AND size is a duplicate,
-- this one index becomes
--     (ingredient_id, lower(coalesce(name, '')), base_quantity).
CREATE UNIQUE INDEX ingredient_pack_sizes_no_duplicate
    ON ingredient_pack_sizes (ingredient_id, base_quantity);

CREATE INDEX ingredient_pack_sizes_tenant_ingredient ON ingredient_pack_sizes (tenant_id, ingredient_id);

SELECT enable_tenant_rls('ingredient_pack_sizes');

-- Same unit family as the ingredient's canonical unit.
--
-- The ingredient is read under the caller's own row-level security, which
-- is exactly right: a pack size naming another temple's ingredient finds no
-- row and is refused, so this is also the one place a cross-tenant parent
-- cannot slip in through a foreign key (FK checks bypass RLS).
--
-- check_violation (23514) because that is what a CHECK would have raised had
-- PostgreSQL let a CHECK read another table; anything that already maps
-- constraint failures handles this the same way.
CREATE OR REPLACE FUNCTION ingredient_pack_size_same_family()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_canonical TEXT;
    v_family    TEXT;
BEGIN
    SELECT canonical_unit INTO v_canonical FROM ingredients WHERE id = NEW.ingredient_id;

    IF v_canonical IS NULL THEN
        RAISE EXCEPTION 'ingredient % not found for this temple', NEW.ingredient_id
            USING ERRCODE = '23503';
    END IF;

    v_family := CASE v_canonical
                    WHEN 'KG' THEN 'MASS'   WHEN 'GM' THEN 'MASS'
                    WHEN 'L'  THEN 'VOLUME' WHEN 'ML' THEN 'VOLUME'
                    ELSE 'COUNT'
                END;

    IF NEW.unit_family IS DISTINCT FROM v_family THEN
        RAISE EXCEPTION 'pack size unit % is not in the same family as the ingredient''s unit %',
                        NEW.unit, v_canonical
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ingredient_pack_sizes_same_family';
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION ingredient_pack_size_same_family() IS
    'Refuses a pack size whose unit is not in the same family (mass, volume, count) as its ingredient''s canonical unit (R-ING-1).';

-- AFTER, not BEFORE: PostgreSQL computes generated columns after BEFORE
-- triggers run, so a BEFORE trigger would see unit_family still null.
-- Raising in an AFTER trigger rolls the statement back just the same.
CREATE TRIGGER ingredient_pack_sizes_same_family
    AFTER INSERT OR UPDATE OF ingredient_id, unit ON ingredient_pack_sizes
    FOR EACH ROW EXECUTE FUNCTION ingredient_pack_size_same_family();


-- =====================================================================
-- 2. Market rate (R-ING-3)
--
-- What an ingredient would cost to buy today, per stock unit. It is the last
-- line of the costing fallback — preferred vendor's list price, then any
-- vendor's, then this — and the reason ₹0 stops being a possible answer for
-- stock the temple is actually holding: stock-take asks for it and "can't be
-- blank or 0". Every saved invoice line refreshes it too.
--
-- Three columns that are one fact: a rate without its date or its source
-- is a number nobody can judge, so they are all set or all null. Null on
-- every existing ingredient — nothing is invented, and "n ingredients without
-- a price" keeps counting them until someone gives one.
--
-- Strictly positive, unlike a list price (which V24 lets be 0, for goods given
-- free with an order). A market rate of ₹0 is the exact defect this work
-- exists to remove.
-- =====================================================================

ALTER TABLE ingredients
    ADD COLUMN market_rate        NUMERIC(14, 4),
    ADD COLUMN market_rate_on     DATE,
    ADD COLUMN market_rate_source TEXT,

    ADD CONSTRAINT ingredients_market_rate_positive CHECK (market_rate IS NULL OR market_rate > 0),
    ADD CONSTRAINT ingredients_market_rate_source_valid CHECK (
        market_rate_source IS NULL OR market_rate_source IN ('STOCK_TAKE', 'INVOICE', 'MANUAL')),
    ADD CONSTRAINT ingredients_market_rate_shape CHECK (
        (market_rate IS NULL AND market_rate_on IS NULL AND market_rate_source IS NULL)
        OR (market_rate IS NOT NULL AND market_rate_on IS NOT NULL AND market_rate_source IS NOT NULL));

COMMENT ON COLUMN ingredients.market_rate IS
    'What it would cost to buy today, in rupees per one canonical unit (R-ING-3). The costing fallback after vendor list prices. Null = no market rate yet, never read as zero.';
COMMENT ON COLUMN ingredients.market_rate_on IS 'The day the market rate was set.';
COMMENT ON COLUMN ingredients.market_rate_source IS
    'Where the market rate came from: STOCK_TAKE (the "what would it cost to buy today" box), INVOICE (a saved invoice line) or MANUAL (typed on the ingredient page).';

-- The history of the market rate. "Recorded in the price history as well"
-- (§3). Its own table, not vendor_price_history with a null vendor
-- (conductor's ruling 2026-09-19): a market rate is a fact about an
-- ingredient, not about a vendor's offer, its sources are different
-- (STOCK_TAKE has no vendor meaning at all), and a nullable vendor would make
-- every "previous price from this vendor" query that R-VEN-3's arrows run
-- have to remember to exclude it.
--
-- The ingredients columns above are the current value; this is how it got
-- there. Append-only like every other ledger here: a wrong rate is corrected
-- by setting a new one, and the old row stays as what was believed then.
CREATE TABLE ingredient_market_rate_history (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    ingredient_id           UUID        NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,

    rate                    NUMERIC(14, 4) NOT NULL,
    effective_on            DATE        NOT NULL,
    source                  TEXT        NOT NULL,

    -- The invoice line that set it, when the source is INVOICE (R-ING-3:
    -- "every saved invoice line also refreshes the market rate"). The FK is
    -- added after vendor_invoice_lines exists, in section 5.
    vendor_invoice_line_id  UUID,

    set_by                  UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT market_rate_history_rate_positive CHECK (rate > 0),
    CONSTRAINT market_rate_history_source_valid CHECK (source IN ('STOCK_TAKE', 'INVOICE', 'MANUAL')),
    -- An INVOICE row says which line; no other row claims one.
    CONSTRAINT market_rate_history_invoice_shape CHECK (
        (source = 'INVOICE') = (vendor_invoice_line_id IS NOT NULL))
);

COMMENT ON TABLE ingredient_market_rate_history IS
    'Every market rate an ingredient has had (R-ING-3), append-only. ingredients.market_rate is the latest; this is how it got there.';

CREATE INDEX market_rate_history_ingredient
    ON ingredient_market_rate_history (tenant_id, ingredient_id, effective_on DESC, created_at DESC);

SELECT enable_tenant_rls('ingredient_market_rate_history');
SELECT make_append_only('ingredient_market_rate_history');


-- =====================================================================
-- 3. vendor_supplies: "Sells it as" (R-VEN-1)
--
-- The pack this vendor sells the ingredient in, and the price per that pack.
-- Null pack = sold in the stock unit itself, which is every existing row.
--
-- A price per pack only means something beside a pack, so a pack price
-- without a pack is refused. A pack without a price is allowed: list prices
-- are optional at onboarding (R-VEN-1, "List price is optional").
--
-- The per-canonical-unit list price stays in last_price and is derived from
-- the pack price by the service, "never typed twice" (§3). That derivation
-- needs the pack's size, which is in another table, so it cannot be a
-- generated column; it is the service's job to keep the two in step.
--
-- The composite foreign key is what stops a supply naming a pack of a
-- different ingredient. DEFERRABLE so the duplicate-ingredient merge
-- (R-DUP-3) can re-point a pack and the supplies that use it inside one
-- transaction with SET CONSTRAINTS ... DEFERRED; INITIALLY IMMEDIATE so every
-- ordinary statement is checked at once, as it always was. RESTRICT because
-- a pack a vendor sells in must not vanish from under it: removing it is a
-- decision the service surfaces ("in use by Kalasipalya"), not a silent null.
-- =====================================================================

ALTER TABLE vendor_supplies
    ADD COLUMN pack_size_id   UUID,
    ADD COLUMN price_per_pack NUMERIC(12, 2),

    ADD CONSTRAINT vendor_supplies_pack_of_this_ingredient
        FOREIGN KEY (pack_size_id, ingredient_id)
        REFERENCES ingredient_pack_sizes (id, ingredient_id)
        ON DELETE RESTRICT
        DEFERRABLE INITIALLY IMMEDIATE,
    ADD CONSTRAINT vendor_supplies_pack_price_nonnegative CHECK (price_per_pack IS NULL OR price_per_pack >= 0),
    ADD CONSTRAINT vendor_supplies_pack_price_needs_pack CHECK (price_per_pack IS NULL OR pack_size_id IS NOT NULL);

COMMENT ON COLUMN vendor_supplies.pack_size_id IS
    '"Sells it as" (R-VEN-1): the ingredient pack this vendor sells in. Null = sold in the stock unit itself.';
COMMENT ON COLUMN vendor_supplies.price_per_pack IS
    'The list price per that pack, in rupees. last_price (shown as "List price") stays per canonical unit and is derived from this by the service.';

CREATE INDEX vendor_supplies_pack_size ON vendor_supplies (pack_size_id) WHERE pack_size_id IS NOT NULL;


-- =====================================================================
-- 4. vendor_invoices: the totals block (R-INV-5)
--
-- Sub total · GST · Other charges (+ note) · Discount · Grand total, as the
-- bill prints them. All nullable: every existing invoice has only `amount`
-- and nobody can say now what its GST was. The rules for a NEW invoice —
-- all present, the upload required, and Sub total + GST + Other charges −
-- Discount = Grand total with saving blocked otherwise — are the invoice
-- service's (R-INV-5), and come with it.
--
-- `amount` stays the figure everything already reads (payables, the
-- void/credit checks of V103). How the grand total and `amount` relate for a
-- new invoice is the invoice service's decision, not this migration's.
--
-- Whether a line's rate is stored before or after GST (open question Q-5)
-- changes nothing here: gst_amount holds the bill's GST figure either way.
-- =====================================================================

ALTER TABLE vendor_invoices
    ADD COLUMN sub_total           NUMERIC(12, 2),
    ADD COLUMN gst_amount          NUMERIC(12, 2),
    ADD COLUMN other_charges       NUMERIC(12, 2),
    ADD COLUMN other_charges_note  TEXT,
    ADD COLUMN discount            NUMERIC(12, 2),
    ADD COLUMN grand_total         NUMERIC(12, 2),

    ADD CONSTRAINT vendor_invoices_totals_nonnegative CHECK (
        (sub_total     IS NULL OR sub_total     >= 0) AND
        (gst_amount    IS NULL OR gst_amount    >= 0) AND
        (other_charges IS NULL OR other_charges >= 0) AND
        (discount      IS NULL OR discount      >= 0) AND
        (grand_total   IS NULL OR grand_total   >= 0));

COMMENT ON COLUMN vendor_invoices.sub_total IS 'Sum of the line amounts (R-INV-5). Null on invoices recorded before V144.';
COMMENT ON COLUMN vendor_invoices.gst_amount IS 'GST as the bill states it (R-INV-5).';
COMMENT ON COLUMN vendor_invoices.other_charges IS 'Anything billed that was not on the delivery — transport, loading — explained by other_charges_note (R-INV-5).';
COMMENT ON COLUMN vendor_invoices.grand_total IS 'The grand total typed from the bill. Sub total + GST + Other charges − Discount must equal it; the invoice service enforces that for new invoices.';


-- =====================================================================
-- 5. vendor_invoice_lines — an invoice's items (R-INV-4)
--
-- One row per item billed. Three shapes, by what the line points at:
--
--   a delivered item    goods_receipt_line_id and ingredient_id both set:
--                       pulled from the delivery and locked (R-INV-3).
--   a direct item       ingredient_id set, no receipt line: a direct invoice
--                       with no order behind it, typed by hand.
--   a one-off item      no ingredient, a description instead ("Plastic stool",
--                       "Transport"): payable but never stock, like V100's
--                       described PO line.
--
-- The subject rule is the same exclusive either/or that V100 put on
-- purchase_order_lines, for the same reason: "is there an ingredient" is the
-- question every consumer will branch on, and a column that is sometimes a
-- subject and sometimes a footnote gives none of them an answer.
--
-- Quantity. billed_qty is always in a real unit (`unit`, one of the five), so
-- every line can be summed and converted without knowing about packs. When
-- the bill is in packs ("4 × Bag (25 Kg)"), pack_size_id and pack_count say
-- so as well — 4 bags — and billed_qty holds the same amount as 100 KG. That
-- the two agree (pack_count × the pack's size = billed_qty) needs the pack's
-- size from another table, so the invoice service keeps them in step.
--
-- billed_qty may be 0: "An item not billed stays at 0" (R-INV-4), and its
-- amount is then 0 as well. The amount is required (never null — "Amount (₹,
-- required)"); 0 is allowed because goods given free are billed at nothing.
--
-- rate is GENERATED, per one of the line's `unit`: amount ÷ billed qty, the
-- "₹100 / Kg" of R-INV-4. It is derived, so it cannot disagree with the
-- figures it comes from, and it is null where nothing was billed rather than
-- a division by zero. The per-pack and per-stock-unit rates of a line billed
-- in packs are derived at read time from the same two figures.
-- =====================================================================

CREATE TABLE vendor_invoice_lines (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- A line is part of its invoice. Invoices are voided, never deleted by
    -- the application, so this cascade is only ever the tenant purge.
    invoice_id              UUID        NOT NULL REFERENCES vendor_invoices(id) ON DELETE CASCADE,

    -- The delivered line this bills. Null for direct and one-off lines.
    -- RESTRICT: receipt lines are append-only and never go anyway.
    goods_receipt_line_id   UUID        REFERENCES goods_receipt_lines(id) ON DELETE RESTRICT,

    ingredient_id           UUID        REFERENCES ingredients(id) ON DELETE RESTRICT,
    description             TEXT,

    billed_qty              NUMERIC(14, 3) NOT NULL,
    unit                    TEXT        NOT NULL,

    pack_size_id            UUID,
    pack_count              NUMERIC(14, 3),

    line_amount             NUMERIC(12, 2) NOT NULL,

    rate                    NUMERIC(14, 4) GENERATED ALWAYS AS (
                                CASE WHEN billed_qty > 0 THEN round(line_amount / billed_qty, 4) END
                            ) STORED,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT invoice_lines_qty_nonnegative CHECK (billed_qty >= 0),
    CONSTRAINT invoice_lines_amount_nonnegative CHECK (line_amount >= 0),
    CONSTRAINT invoice_lines_unit_valid CHECK (unit IN ('KG', 'GM', 'L', 'ML', 'PIECES')),
    CONSTRAINT invoice_lines_has_exactly_one_subject CHECK (
        (ingredient_id IS NOT NULL AND description IS NULL)
        OR (ingredient_id IS NULL AND description IS NOT NULL AND btrim(description) <> '')),
    -- A receipt line always names an ingredient (V27, V100), so a line billing
    -- one must too.
    CONSTRAINT invoice_lines_receipt_needs_ingredient CHECK (
        goods_receipt_line_id IS NULL OR ingredient_id IS NOT NULL),
    -- Packs come in pairs — which pack and how many — and only on an
    -- ingredient, since a one-off item has no pack sizes to name.
    CONSTRAINT invoice_lines_pack_shape CHECK (
        (pack_size_id IS NULL AND pack_count IS NULL)
        OR (pack_size_id IS NOT NULL AND pack_count > 0 AND ingredient_id IS NOT NULL)),
    -- The pack must be one of this line's own ingredient's. Deferrable for the
    -- merge, exactly as vendor_supplies_pack_of_this_ingredient.
    CONSTRAINT invoice_lines_pack_of_this_ingredient
        FOREIGN KEY (pack_size_id, ingredient_id)
        REFERENCES ingredient_pack_sizes (id, ingredient_id)
        ON DELETE RESTRICT
        DEFERRABLE INITIALLY IMMEDIATE
);

COMMENT ON TABLE vendor_invoice_lines IS
    'The items on a vendor invoice (R-INV-4): a delivered line, a direct item, or a one-off described item. Each saved line sets the vendor''s list price and the market rate (R-VEN-4, R-ING-3).';
COMMENT ON COLUMN vendor_invoice_lines.billed_qty IS
    'How much was billed, in `unit` — always a real unit, even when billed in packs (then pack_count says how many packs).';
COMMENT ON COLUMN vendor_invoice_lines.rate IS
    'Generated: line_amount ÷ billed_qty, in rupees per one of `unit`. Null when nothing was billed.';

CREATE INDEX invoice_lines_invoice ON vendor_invoice_lines (invoice_id);
CREATE INDEX invoice_lines_receipt_line ON vendor_invoice_lines (goods_receipt_line_id)
    WHERE goods_receipt_line_id IS NOT NULL;
CREATE INDEX invoice_lines_ingredient ON vendor_invoice_lines (tenant_id, ingredient_id)
    WHERE ingredient_id IS NOT NULL;

-- No unique index on goods_receipt_line_id, although a delivered line is
-- billed once: invoices are VOIDED rather than deleted (V103), and a voided
-- invoice's lines must not stop the same delivery being billed correctly.
-- "Only deliveries not yet billed are offered" (R-INV-3) is the invoice
-- service's rule, reading non-voided invoices.

SELECT enable_tenant_rls('vendor_invoice_lines');

-- The market-rate history's link to the line that set it (section 2).
ALTER TABLE ingredient_market_rate_history
    ADD CONSTRAINT market_rate_history_invoice_line
        FOREIGN KEY (vendor_invoice_line_id) REFERENCES vendor_invoice_lines(id) ON DELETE RESTRICT;


-- =====================================================================
-- 6. vendor_invoice_deliveries — which deliveries an invoice bills (R-INV-3)
--
-- Vendors usually bill per delivery (Rajeev), and one bill may cover several.
-- A join rather than a column on either side: a delivery is billed by one
-- invoice at a time but may be re-billed after a void, and an invoice may
-- cover many deliveries. Nothing about the old one-PO-per-invoice link
-- (vendor_invoices.po_id) changes.
-- =====================================================================

CREATE TABLE vendor_invoice_deliveries (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    invoice_id   UUID        NOT NULL REFERENCES vendor_invoices(id) ON DELETE CASCADE,
    -- goods_receipts is append-only and never deleted; RESTRICT says so.
    receipt_id   UUID        NOT NULL REFERENCES goods_receipts(id) ON DELETE RESTRICT,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT invoice_deliveries_once_each UNIQUE (invoice_id, receipt_id)
);

COMMENT ON TABLE vendor_invoice_deliveries IS
    'The deliveries (goods receipts) a vendor invoice bills (R-INV-3). An invoice may bill several.';

CREATE INDEX invoice_deliveries_receipt ON vendor_invoice_deliveries (tenant_id, receipt_id);

SELECT enable_tenant_rls('vendor_invoice_deliveries');


-- =====================================================================
-- 7. vendor_price_history — every change to a vendor's list price
--    (R-VEN-3, R-VEN-4)
--
-- One row per change, from a person typing it (ONBOARDING when a vendor's list
-- is first entered, MANUAL afterwards) or from a saved invoice line (INVOICE).
-- The arrows beside a list price compare the latest row with the one before
-- it, and the tooltip shows that previous row's price and date.
--
-- The price is always stored per canonical unit ("always stored in the history
-- per stock unit too", R-VEN-4), so history stays comparable when a vendor
-- changes the pack it sells in. When the price was set per pack, the pack
-- price and a description of the pack are kept beside it. Those are a
-- SNAPSHOT, not a foreign key, on purpose: this table is append-only, so a
-- pointer to a pack could never be cleared, and a pack size removed from the
-- ingredient next year would either be blocked for ever by an old price or
-- take the history with it. What the pack was on the day is a fact of the
-- day; text says it permanently.
--
-- Append-only (make_append_only): a wrong price is corrected by a new price.
-- =====================================================================

CREATE TABLE vendor_price_history (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- PROVISIONAL (conductor's ruling 2026-09-19, awaiting Rajeev): the
    -- history of a vendor+ingredient pair SURVIVES the vendor stopping
    -- supplying it. §3 draws the link "→ vendor_supplies (vendor+ingredient)",
    -- but VendorService.removeSupply deletes that row, and this table is
    -- append-only: a cascade would be refused by the append-only trigger, a
    -- SET NULL likewise (and would erase which vendor it was), and a RESTRICT
    -- would change today's behaviour by refusing to remove a supply that had
    -- ever been priced. So the pair is held as two foreign keys to the
    -- things it names — the vendor and the ingredient — which never go while
    -- this row exists, and re-adding the supply picks its history back up.
    --
    -- If Rajeev rules that removing a priced supply should be REFUSED instead,
    -- that is one statement:
    --     ALTER TABLE vendor_price_history ADD CONSTRAINT vendor_price_history_supply
    --         FOREIGN KEY (vendor_id, ingredient_id)
    --         REFERENCES vendor_supplies (vendor_id, ingredient_id) ON DELETE RESTRICT;
    -- (vendor_supplies_vendor_ingredient, V24, is the unique index it needs.)
    vendor_id               UUID        NOT NULL REFERENCES vendors(id) ON DELETE RESTRICT,
    ingredient_id           UUID        NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,

    -- Rupees per one canonical unit of the ingredient.
    price_per_unit          NUMERIC(14, 4) NOT NULL,

    -- When the price was set per pack: the pack price, and the pack as it
    -- read that day ("Bag = 25 Kg"). Both or neither.
    price_per_pack          NUMERIC(12, 2),
    pack_description        TEXT,

    effective_on            DATE        NOT NULL,
    source                  TEXT        NOT NULL,

    -- The invoice line that set it, for source INVOICE. Null for a price typed
    -- by hand (ONBOARDING, MANUAL).
    vendor_invoice_line_id  UUID        REFERENCES vendor_invoice_lines(id) ON DELETE RESTRICT,

    set_by                  UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 0 is allowed, as on vendor_supplies.last_price (V24): goods given free
    -- with an order are a real price. Negative is not.
    CONSTRAINT vendor_price_history_price_nonnegative CHECK (price_per_unit >= 0),
    CONSTRAINT vendor_price_history_pack_price_nonnegative CHECK (price_per_pack IS NULL OR price_per_pack >= 0),
    CONSTRAINT vendor_price_history_pack_shape CHECK (
        (price_per_pack IS NULL AND pack_description IS NULL)
        OR (price_per_pack IS NOT NULL AND pack_description IS NOT NULL AND btrim(pack_description) <> '')),
    CONSTRAINT vendor_price_history_source_valid CHECK (source IN ('ONBOARDING', 'MANUAL', 'INVOICE')),
    -- An INVOICE row names its line; a typed price names none.
    CONSTRAINT vendor_price_history_invoice_shape CHECK (
        (source = 'INVOICE') = (vendor_invoice_line_id IS NOT NULL))
);

COMMENT ON TABLE vendor_price_history IS
    'Every change to a vendor''s list price for an ingredient (R-VEN-3, R-VEN-4), append-only. The arrows compare the latest row with the one before.';
COMMENT ON COLUMN vendor_price_history.price_per_unit IS
    'Rupees per one canonical unit of the ingredient, whatever unit or pack the price was given in.';
COMMENT ON COLUMN vendor_price_history.pack_description IS
    'The pack as it read when the price was set ("Bag = 25 Kg"). Text, not a foreign key: this table is append-only and must outlive the pack.';

-- "The latest price and the one before it", per vendor and ingredient.
CREATE INDEX vendor_price_history_pair
    ON vendor_price_history (tenant_id, vendor_id, ingredient_id, effective_on DESC, created_at DESC);
CREATE INDEX vendor_price_history_invoice_line ON vendor_price_history (vendor_invoice_line_id)
    WHERE vendor_invoice_line_id IS NOT NULL;

SELECT enable_tenant_rls('vendor_price_history');
SELECT make_append_only('vendor_price_history');


-- =====================================================================
-- 8. invoice_payments.received_by_name (R-PAY-2)
--
-- For a cash payment, the name of the person who took the money. Required
-- for cash, but only for NEW payments — that rule is the payment service's,
-- because every cash payment already recorded has no name and nobody can
-- supply one now. So nullable here.
--
-- invoice_payments is append-only (V40, V49, V50). Adding a column is DDL and
-- fine; no later pass could fill it in, which is correct — who took the cash
-- is a fact of the moment it was paid.
-- =====================================================================

ALTER TABLE invoice_payments
    ADD COLUMN received_by_name TEXT,
    ADD CONSTRAINT invoice_payments_received_by_not_blank CHECK (
        received_by_name IS NULL OR btrim(received_by_name) <> '');

COMMENT ON COLUMN invoice_payments.received_by_name IS
    'For cash: the name of the person who received the money (R-PAY-2). Required for new cash payments by the payment service; null on payments recorded before V144.';


-- =====================================================================
-- 9. attachments — bill uploads and payment proof (R-INV-2, R-PAY-2)
--
-- There is no upload mechanism in the app today. The bytes will go through
-- the existing DocumentStorage (store/open by key, GCS in a deployment, a
-- local directory in development) as R-INV-2 asks; this table is the record
-- of each file: its storage key, what it is, and what it belongs to.
--
-- Why not the V12 `documents` table. It is the record of a GENERATED
-- artifact: a PENDING -> READY -> FAILED lifecycle, a render language and
-- target yield, an error column, a recipe or meal it was rendered from, and
-- regeneration in place. An upload has none of that — it arrives complete,
-- is never re-rendered, and has an original filename, a content type, a size
-- and a person who uploaded it. Folding uploads in would make half of every
-- row meaningless for either kind and widen documents_kind_valid into two
-- unrelated vocabularies. The storage service is shared; the table is not.
--
-- Kinds, one per upload R-INV-2 and R-PAY-2 require:
--   INVOICE_BILL          the copy of the bill            -> an invoice
--   PAYMENT_PROOF         receipt / UPI screenshot / bank confirmation
--                                                          -> a payment
--   CASH_SIGNED_NOTE      the note signed by who took cash -> a payment
--   CASH_RECEIVER_PHOTO   their ID card or photo           -> a payment
--
-- Both parent keys are nullable and at most one is set. Neither being set is
-- allowed, so that a file can be uploaded while the form is still open and
-- linked when it is saved; whether the services work that way or upload with
-- the save is their choice, and this table serves either. When a parent IS
-- set, it must be the right kind of parent — a bill is never proof of a
-- payment.
--
-- Not append-only: linking an upload to the invoice it was attached to is an
-- update. Payment proof is protected by the payment it belongs to, which is.
-- =====================================================================

CREATE TABLE attachments (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    kind           TEXT        NOT NULL,

    -- The DocumentStorage key. Unique: two rows claiming one object would
    -- mean deleting either deletes the other's file.
    storage_key    TEXT        NOT NULL,
    content_type   TEXT        NOT NULL,
    size_bytes     BIGINT      NOT NULL,
    -- As the uploader's device named it ("IMG_2041.jpg"), for display only.
    original_name  TEXT,

    invoice_id     UUID        REFERENCES vendor_invoices(id) ON DELETE RESTRICT,
    payment_id     UUID        REFERENCES invoice_payments(id) ON DELETE RESTRICT,

    uploaded_by    UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    uploaded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT attachments_kind_valid CHECK (
        kind IN ('INVOICE_BILL', 'PAYMENT_PROOF', 'CASH_SIGNED_NOTE', 'CASH_RECEIVER_PHOTO')),
    CONSTRAINT attachments_storage_key_unique UNIQUE (storage_key),
    CONSTRAINT attachments_storage_key_present CHECK (btrim(storage_key) <> ''),
    CONSTRAINT attachments_content_type_present CHECK (btrim(content_type) <> ''),
    CONSTRAINT attachments_size_positive CHECK (size_bytes > 0),
    CONSTRAINT attachments_one_parent CHECK (invoice_id IS NULL OR payment_id IS NULL),
    -- The right parent for the kind, when there is one.
    CONSTRAINT attachments_parent_matches_kind CHECK (
        (kind = 'INVOICE_BILL' AND payment_id IS NULL)
        OR (kind <> 'INVOICE_BILL' AND invoice_id IS NULL))
);

COMMENT ON TABLE attachments IS
    'Uploaded files (R-INV-2, R-PAY-2): a bill on an invoice, proof on a payment. The bytes live in DocumentStorage under storage_key; not the V12 documents table, which records generated artifacts.';

CREATE INDEX attachments_invoice ON attachments (tenant_id, invoice_id) WHERE invoice_id IS NOT NULL;
CREATE INDEX attachments_payment ON attachments (tenant_id, payment_id) WHERE payment_id IS NOT NULL;

SELECT enable_tenant_rls('attachments');


-- =====================================================================
-- 10. recipe_ingredients.preparation_note (R-DUP-1)
--
-- "halved", "slit", "paste", "fresh grated", "sour". The library import
-- turned preparations into separate ingredients ("Cashew, halved", "Green
-- chilli, slit"), which split stock, prices and shopping-list lines. The
-- preparation belongs on the recipe line; stock, price and ordering sit on
-- the base ingredient. Null on every existing line; the merge tool (R-DUP-3)
-- is what moves text onto them.
-- =====================================================================

ALTER TABLE recipe_ingredients
    ADD COLUMN preparation_note TEXT,
    ADD CONSTRAINT recipe_ingredients_preparation_not_blank CHECK (
        preparation_note IS NULL OR btrim(preparation_note) <> '');

COMMENT ON COLUMN recipe_ingredients.preparation_note IS
    'How the ingredient is prepared for this recipe — "halved", "slit", "paste" (R-DUP-1). Printed wherever the line prints. Never a separate ingredient.';


-- =====================================================================
-- 11. ingredient_aliases (R-DUP-3)
--
-- The names an ingredient is also known by, one row each, so that a name
-- merged away ("Curd, sour") still finds the kept ingredient when someone
-- searches for it or re-imports it, and so that no two ingredients in a
-- temple can answer to the same name.
--
-- A table rather than the existing ingredients.aliases TEXT[] because the
-- rule that matters — an alias names ONE ingredient per temple — is a
-- uniqueness across rows, which a UNIQUE constraint can hold and an array
-- column cannot. The array is left in place: IngredientService still reads
-- and writes it, and dropping it belongs to the task that moves those reads.
--
-- normalised_alias is what uniqueness and lookup use. The proper normaliser
-- (lower case, trimmed, punctuation, plurals and preparation words removed —
-- R-DUP-2) is being written in Java by another task and will fill this column
-- for every new alias. The backfill below uses lower(btrim(...)), which is a
-- strict subset of that: anything it says is equal, the full normaliser also
-- says is equal. The reverse is not true, so the new normaliser may find
-- collisions among backfilled rows; they are left for the merge tool, which
-- is where near-duplicates are resolved by a person.
-- =====================================================================

CREATE TABLE ingredient_aliases (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- An alias goes with its ingredient. The merge re-points it to the kept
    -- ingredient before the other is removed; it is never orphaned.
    ingredient_id     UUID        NOT NULL REFERENCES ingredients(id) ON DELETE CASCADE,

    alias             TEXT        NOT NULL,
    normalised_alias  TEXT        NOT NULL,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ingredient_aliases_alias_present CHECK (btrim(alias) <> ''),
    CONSTRAINT ingredient_aliases_normalised_present CHECK (btrim(normalised_alias) <> ''),
    -- One ingredient per name per temple. Per temple, not global: two temples
    -- may both call something "Raw Rice".
    CONSTRAINT ingredient_aliases_one_per_name UNIQUE (tenant_id, normalised_alias)
);

COMMENT ON TABLE ingredient_aliases IS
    'Other names an ingredient answers to (R-DUP-3), unique per temple by normalised_alias. Backfilled from ingredients.aliases in V144; that array is left in place for the code that still reads it.';

CREATE INDEX ingredient_aliases_ingredient ON ingredient_aliases (tenant_id, ingredient_id);

SELECT enable_tenant_rls('ingredient_aliases');

-- The backfill, one temple at a time.
--
-- ingredients and ingredient_aliases both carry FORCE ROW LEVEL SECURITY and
-- the migration role is unprivileged, so a single INSERT ... SELECT here would
-- read no ingredients (the policy fails closed with app.tenant_id unset),
-- write no aliases and report success. So each tenant is adopted in turn, as
-- V97 and V98 do. TenantLoopMigrationIT plans this loop body against a real
-- temple, and ProcurementDataModelMigrationIT checks what it wrote.
--
-- Blank entries are skipped. Where two ingredients of one temple share an
-- alias (after lower/trim), the older ingredient keeps it — the first one the
-- temple created is the likelier to be the one people mean — and the other
-- is counted and reported rather than failing the migration: a deployment
-- must not crash-loop over a data quirk that the merge tool exists to settle.
-- The same alias twice on one ingredient is one row. Each temple is separate,
-- so the same alias in two temples is two rows.
--
-- The loop variable is v_tenant, not t: V57 crash-looped a deployment when a
-- table alias inside a loop resolved to a record variable of the same name.
DO $$
DECLARE
    v_tenant   RECORD;
    v_offered  INTEGER;
    v_written  INTEGER;
    v_total_offered INTEGER := 0;
    v_total_written INTEGER := 0;
BEGIN
    FOR v_tenant IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', v_tenant.id::text, true);

        SELECT count(DISTINCT (ing.id, lower(btrim(entry.alias)))) INTO v_offered
        FROM ingredients ing
        CROSS JOIN LATERAL unnest(ing.aliases) AS entry(alias)
        WHERE ing.tenant_id = v_tenant.id
          AND btrim(entry.alias) <> '';

        INSERT INTO ingredient_aliases (tenant_id, ingredient_id, alias, normalised_alias)
        SELECT DISTINCT ON (lower(btrim(entry.alias)))
               ing.tenant_id, ing.id, btrim(entry.alias), lower(btrim(entry.alias))
        FROM ingredients ing
        CROSS JOIN LATERAL unnest(ing.aliases) AS entry(alias)
        WHERE ing.tenant_id = v_tenant.id
          AND btrim(entry.alias) <> ''
        ORDER BY lower(btrim(entry.alias)), ing.created_at, ing.id
        ON CONFLICT ON CONSTRAINT ingredient_aliases_one_per_name DO NOTHING;

        GET DIAGNOSTICS v_written = ROW_COUNT;
        v_total_offered := v_total_offered + v_offered;
        v_total_written := v_total_written + v_written;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);

    RAISE NOTICE 'V144: % ingredient alias(es) backfilled; % shared by two ingredients of one temple '
        'and kept on the older one only.', v_total_written, v_total_offered - v_total_written;
END $$;
