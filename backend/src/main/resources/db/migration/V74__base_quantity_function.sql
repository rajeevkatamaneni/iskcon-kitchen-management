-- =====================================================================
-- V74 — A conversion that cannot fail quietly (E11-S1)
--
-- Stock is held in the ingredient's own unit and summed in the family's
-- base unit, and the arithmetic that does the converting was written out
-- by hand in seven places:
--
--     SUM(quantity * CASE unit WHEN 'KG' THEN 1000
--                              WHEN 'L'  THEN 1000
--                              ELSE 1 END)
--
-- ELSE 1 is the defect. A row carrying a unit the CASE has never heard of
-- is counted as one gram — so a figure can be wrong by a factor of a
-- thousand with nothing raised, nothing logged and no test failing. Two
-- of the four copies living in the test tree had already drifted, omitting
-- WHEN 'L' entirely, which means the tests and the application were
-- computing different things about litres and neither said so.
--
-- Nothing has gone wrong yet only because every writer happens to pass
-- through Unit.valueOf on the way in. That is a property of today's call
-- sites, not of the schema, and three columns did not even have that much:
-- order_list_lines.unit, purchase_order_lines.unit and
-- goods_receipt_lines.unit carried no CHECK at all.
--
-- So: one function, and it raises.
--
-- Returning NULL was the first attempt and it is wrong, which the test for
-- it caught: SQL's SUM *skips* NULLs. SUM over two kilos and one unreadable
-- row returns 2000, not NULL — the bad row is silently dropped rather than
-- silently miscounted, which is a different shade of the same fault. The
-- only answer that cannot be ignored by an aggregate is an exception.
--
-- A raise here is unreachable in normal running: every unit column in the
-- schema now carries a CHECK admitting exactly these five names, so a row
-- the function cannot read is a row that should not exist. If one ever
-- does, a failed query naming the offending unit is a far better morning
-- than a stock figure that is quietly wrong by a factor of a thousand.
--
-- Deliberately shipped on its own, ahead of the vocabulary change in
-- E11-S2. Renaming a unit while the failure mode is still silent is how a
-- thousand-fold error reaches a store room.
-- =====================================================================

CREATE OR REPLACE FUNCTION to_base_qty(qty NUMERIC, unit TEXT)
RETURNS NUMERIC AS $$
BEGIN
    -- `unit IS NULL` is spelled out because `NULL NOT IN (...)` is NULL rather
    -- than true, so a null unit would slip past the check and return null.
    IF unit IS NULL OR unit NOT IN ('KG', 'GM', 'L', 'ML', 'PIECES') THEN
        RAISE EXCEPTION 'Unknown unit of measure: %', COALESCE(unit, '(null)')
            USING ERRCODE = 'data_exception',
                  HINT = 'Units are KG, GM, L, ML or PIECES. A row holding anything else is a defect.';
    END IF;

    -- A null quantity stays null. Not knowing how much of something there is
    -- is an ordinary fact; not knowing what it is measured in is not.
    RETURN qty * CASE unit
        WHEN 'KG'     THEN 1000
        WHEN 'GM'     THEN 1
        WHEN 'L'      THEN 1000
        WHEN 'ML'     THEN 1
        WHEN 'PIECES' THEN 1
    END;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

COMMENT ON FUNCTION to_base_qty(NUMERIC, TEXT) IS
    'A quantity in its family''s base unit — grams, millilitres or pieces. Mirrors Unit.baseFactor() in Java. Raises on a unit it does not recognise rather than returning NULL, because SUM skips NULLs and would drop the row without a word.';

-- ---------------------------------------------------------------------
-- The three unit columns that never had a CHECK.
--
-- All three are written from ingredients.canonical_unit, which has been
-- constrained since V10, so existing rows are already clean and these
-- validate without a rewrite. The constraint is not for today's callers —
-- it is so that the next writer cannot quietly introduce a token the
-- function above would turn into a NULL.
-- ---------------------------------------------------------------------

ALTER TABLE order_list_lines ADD CONSTRAINT order_list_lines_unit_valid
    CHECK (unit IN ('KG', 'GM', 'L', 'ML', 'PIECES'));

ALTER TABLE purchase_order_lines ADD CONSTRAINT purchase_order_lines_unit_valid
    CHECK (unit IN ('KG', 'GM', 'L', 'ML', 'PIECES'));

ALTER TABLE goods_receipt_lines ADD CONSTRAINT goods_receipt_lines_unit_valid
    CHECK (unit IN ('KG', 'GM', 'L', 'ML', 'PIECES'));
