-- =====================================================================
-- V147 — The one door through the append-only ledgers for merging duplicate
--        ingredients (T-270, PROCUREMENT-REQUIREMENTS.md §9A R-DUP-3)
--
-- WHAT THE MERGE HAS TO DO TO A LEDGER
--
-- Merging "Curd, sour" into Curd re-points every row that names "Curd, sour"
-- onto Curd, and "stock movements keep their history, re-pointed, never
-- deleted" (R-DUP-3 step 3). Four of the tables that point at ingredients are
-- append-only — make_append_only() (V49/V50) puts a BEFORE UPDATE OR DELETE
-- trigger on them that refuses the application role outright:
--
--   stock_movements, goods_receipt_lines            (V14, V27)
--   vendor_price_history, ingredient_market_rate_history   (V144)
--
-- So the merge cannot do its job as kms_app, which is exactly right for every
-- other caller and wrong for this one.
--
-- WHY NOT A SETTING THE TRIGGER HONOURS (the purge's way)
--
-- The whole-tenant purge (V49) gets through by announcing itself with
-- app.purging_tenant = 'on'. Copying that — an app.merging_ingredient flag —
-- would work, and would be wider than it needs to be: while the flag is on,
-- the trigger lets through ANY update or delete on ANY append-only table,
-- including audit_events, for whatever statement happens to run next. The
-- purge can afford that because it runs inside one SECURITY DEFINER function
-- that sets and clears the flag around its own loop; a merge driven from Java
-- would hold it across many statements the database cannot see the shape of.
--
-- WHAT THIS DOES INSTEAD
--
-- One SECURITY DEFINER function, merge_ingredient_ledger_rows(table, from,
-- to), which performs the re-point itself. It runs as the function's owner
-- (the migration role), and reject_append_only_change() already lets the
-- owner through — it stops the application role only, "exactly who the
-- revoke used to stop, and no one else" (V49). So:
--
--   * reject_append_only_change() is NOT changed. An ordinary UPDATE or
--     DELETE from kms_app on any of these tables is refused exactly as before
--     (IngredientMergeAppendOnlyIT proves it for each table, after V147).
--   * The only write the function can make is: on one of the four named
--     tables, set ingredient_id from one ingredient to another, for every row
--     of the first. No other column can be chosen, no other value can be
--     written, no row can be deleted, and no other table can be named — an
--     unknown table raises.
--   * The one value it changes besides ingredient_id is a rate that is stored
--     per ONE canonical unit with no unit column beside it (conductor's
--     ruling 2026-09-19: within one unit family the merge converts prices and
--     history into the kept ingredient's unit). ₹0.065 per gm on "Curd, sour"
--     is ₹65 per Kg on Curd. The factor is worked out HERE, from the two
--     ingredients' own canonical units through to_base_qty() (V74, the
--     mirror of Unit.baseFactor()), never taken from the caller, so a caller
--     cannot scale a price by a number of its choosing. Rows that carry
--     their own unit (a movement of 500 GM, a receipt line in KG) are not
--     converted at all: they are summed through to_base_qty already, and
--     re-pointing them leaves every on-hand figure exactly as it was.
--   * Across unit families it refuses (Kg against pieces has no factor);
--     the service refuses first with KMS-400171, this is the backstop.
--   * Tenancy: the function reads app.tenant_id like every policy does and
--     refuses without one. Both ingredients are looked up under row-level
--     security AND by that tenant explicitly, and every UPDATE is limited to
--     that tenant — FORCE ROW LEVEL SECURITY applies to the owner too (V1),
--     so the policy is still in force inside the function; the explicit
--     predicate is a second lock on the same door, because a SECURITY
--     DEFINER function is where a missing one would matter most.
--
-- The kept ingredient's own rows (from = to) are refused: re-pointing a row
-- onto itself is not a merge, and "convert Curd's prices by 1" is not a
-- thing anybody should be able to ask this function to do.
--
-- search_path is pinned, as for every SECURITY DEFINER function here, so a
-- caller cannot shadow ingredients or to_base_qty with objects of its own.
--
-- No table is created, so there is no enable_tenant_rls() call, and no row
-- is read or written by the migration itself, so there is no per-tenant loop.
-- =====================================================================

CREATE OR REPLACE FUNCTION merge_ingredient_ledger_rows(p_table TEXT, p_from UUID, p_to UUID)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_tenant    UUID := NULLIF(current_setting('app.tenant_id', true), '')::uuid;
    v_from_unit TEXT;
    v_to_unit   TEXT;
    v_factor    NUMERIC;
    v_rows      BIGINT;
BEGIN
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION 'merge_ingredient_ledger_rows: no temple in this session'
            USING ERRCODE = '42501';
    END IF;

    IF p_from IS NULL OR p_to IS NULL OR p_from = p_to THEN
        RAISE EXCEPTION 'merge_ingredient_ledger_rows: two different ingredients are needed'
            USING ERRCODE = '22023';
    END IF;

    SELECT canonical_unit INTO v_from_unit FROM ingredients WHERE id = p_from AND tenant_id = v_tenant;
    SELECT canonical_unit INTO v_to_unit   FROM ingredients WHERE id = p_to   AND tenant_id = v_tenant;
    IF v_from_unit IS NULL OR v_to_unit IS NULL THEN
        -- Another temple's ingredient, or none: not found, the same answer RLS gives everywhere.
        RAISE EXCEPTION 'merge_ingredient_ledger_rows: ingredient not found for this temple'
            USING ERRCODE = '23503';
    END IF;

    -- The same three families as Unit.Family and V144's pack-size trigger.
    IF (CASE WHEN v_from_unit IN ('KG', 'GM') THEN 'MASS'
             WHEN v_from_unit IN ('L', 'ML')  THEN 'VOLUME' ELSE 'COUNT' END)
       IS DISTINCT FROM
       (CASE WHEN v_to_unit IN ('KG', 'GM') THEN 'MASS'
             WHEN v_to_unit IN ('L', 'ML')  THEN 'VOLUME' ELSE 'COUNT' END) THEN
        RAISE EXCEPTION 'merge_ingredient_ledger_rows: % and % are different kinds of unit', v_from_unit, v_to_unit
            USING ERRCODE = '22023';
    END IF;

    -- A price per one FROM unit, as a price per one TO unit. ₹ per gm × 1000 = ₹ per Kg.
    v_factor := to_base_qty(1, v_to_unit) / to_base_qty(1, v_from_unit);

    CASE p_table
        WHEN 'stock_movements' THEN
            UPDATE stock_movements SET ingredient_id = p_to
            WHERE ingredient_id = p_from AND tenant_id = v_tenant;
        WHEN 'goods_receipt_lines' THEN
            UPDATE goods_receipt_lines SET ingredient_id = p_to
            WHERE ingredient_id = p_from AND tenant_id = v_tenant;
        WHEN 'vendor_price_history' THEN
            -- price_per_pack and pack_description stay: a pack price is a price for the pack as it
            -- read that day, whatever the ingredient is counted in.
            UPDATE vendor_price_history
            SET ingredient_id = p_to, price_per_unit = round(price_per_unit * v_factor, 4)
            WHERE ingredient_id = p_from AND tenant_id = v_tenant;
        WHEN 'ingredient_market_rate_history' THEN
            UPDATE ingredient_market_rate_history
            SET ingredient_id = p_to, rate = round(rate * v_factor, 4)
            WHERE ingredient_id = p_from AND tenant_id = v_tenant;
        ELSE
            -- A new append-only table pointing at ingredients must be added here deliberately,
            -- with a decision about whether it holds a per-unit rate. IngredientMergeCatalogueIT
            -- fails until it is.
            RAISE EXCEPTION 'merge_ingredient_ledger_rows: % is not a ledger the merge knows', p_table
                USING ERRCODE = '22023';
    END CASE;

    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RETURN v_rows;
END;
$$;

COMMENT ON FUNCTION merge_ingredient_ledger_rows(TEXT, UUID, UUID) IS
    'R-DUP-3: re-points one append-only ledger''s rows from a merged-away ingredient to the kept one, in the acting temple only, converting per-unit rates within one unit family. The only way through the append-only trigger other than the tenant purge. Refuses unknown tables, other temples, the same ingredient twice and different unit families.';

REVOKE ALL ON FUNCTION merge_ingredient_ledger_rows(TEXT, UUID, UUID) FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kms_app') THEN
        GRANT EXECUTE ON FUNCTION merge_ingredient_ledger_rows(TEXT, UUID, UUID) TO kms_app;
    END IF;
END $$;
