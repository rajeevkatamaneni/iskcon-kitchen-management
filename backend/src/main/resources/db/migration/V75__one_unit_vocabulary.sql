-- =====================================================================
-- V75 — One unit vocabulary (E11-S2)
--
-- Two enums have been naming the same things differently since V11.
-- Unit (ingredients, recipe lines, stock) says KG, GM, L, ML, PIECES.
-- YieldUnit (what a recipe makes) said SERVINGS, LITRES, KG, PIECES —
-- so a litre was 'L' in the store room and 'LITRES' on the recipe that
-- drew from it, and a recipe could not be measured in grams or
-- millilitres at all. A chutney made by the 200 ml and a spice mix made
-- by the 20 gm had to be written as fractions of a litre or a kilo,
-- which is a lie the printed card then repeats.
--
-- After this migration there is one vocabulary. YieldUnit is gone from
-- the Java side in the same commit.
--
-- ---------------------------------------------------------------------
-- SERVINGS survives, and is not a matter of taste
--
-- It is not a physical measure and an ingredient can never be counted in
-- it, so it is admitted on a yield and nowhere else. It cannot simply be
-- dropped: all 57 seeded recipes yield in servings and not one has a
-- per-head portion, and MealComposer's only path from a head count to a
-- cooking target for such a recipe is `baseYieldUnit === 'SERVINGS'`.
-- Removing it would leave every seeded recipe unplannable, and would take
-- the plate count off the public giving page with it. Migrating those
-- rows off it means inventing a per-head portion for 57 recipes nobody
-- has ever recorded.
--
-- ---------------------------------------------------------------------
-- Why the data moves before the constraints come back
--
-- The old CHECKs admit 'LITRES' and reject 'L'; the new ones do the
-- reverse. There is no ordering of ALTER and UPDATE that keeps a valid
-- constraint in place throughout, so the constraints come off, the data
-- moves, and they go back on.
--
-- Putting them back is the tripwire. Both UPDATEs run under RLS — recipes
-- per tenant, master_recipes through the library-load escape V68 built
-- for exactly this — and a plain cross-tenant UPDATE does not fail, it
-- silently matches nothing and reports success (V48). ADD CONSTRAINT is
-- DDL and is not filtered, so a row the loop failed to reach fails the
-- deploy here rather than shipping a vocabulary that disagrees with
-- itself.
-- =====================================================================

ALTER TABLE recipes DROP CONSTRAINT recipes_yield_unit_valid;
ALTER TABLE recipes DROP CONSTRAINT recipes_per_head_unit_valid;
ALTER TABLE master_recipes DROP CONSTRAINT master_recipes_yield_unit_valid;
ALTER TABLE master_recipes DROP CONSTRAINT master_recipes_per_head_unit_valid;

-- ---------------------------------------------------------------------
-- The temples' own recipes — tenant-owned, so one tenant at a time.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        UPDATE recipes SET base_yield_unit = 'L' WHERE base_yield_unit = 'LITRES';
        UPDATE recipes SET per_head_unit   = 'L' WHERE per_head_unit   = 'LITRES';
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);
END $$;

-- ---------------------------------------------------------------------
-- The shared library — no tenant_id; its RLS is keyed on identity, and
-- V68 left `app.library_load` as the way in for machinery rather than a
-- person. This is machinery.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    PERFORM set_config('app.library_load', 'true', true);

    UPDATE master_recipes SET yield_unit    = 'L' WHERE yield_unit    = 'LITRES';
    UPDATE master_recipes SET per_head_unit = 'L' WHERE per_head_unit = 'LITRES';

    PERFORM set_config('app.library_load', '', true);
END $$;

-- ---------------------------------------------------------------------
-- The vocabulary, going back on.
--
-- A yield may be servings; what one person eats may not. A portion is a
-- quantity of food, and "0.5 servings per head" says nothing a cook can
-- weigh. That distinction predates this migration (V69) and is kept.
-- ---------------------------------------------------------------------

ALTER TABLE recipes ADD CONSTRAINT recipes_yield_unit_valid
    CHECK (base_yield_unit IN ('KG', 'GM', 'L', 'ML', 'PIECES', 'SERVINGS'));

ALTER TABLE recipes ADD CONSTRAINT recipes_per_head_unit_valid
    CHECK (per_head_unit IS NULL OR per_head_unit IN ('KG', 'GM', 'L', 'ML', 'PIECES'));

ALTER TABLE master_recipes ADD CONSTRAINT master_recipes_yield_unit_valid
    CHECK (yield_unit IN ('KG', 'GM', 'L', 'ML', 'PIECES', 'SERVINGS'));

ALTER TABLE master_recipes ADD CONSTRAINT master_recipes_per_head_unit_valid
    CHECK (per_head_unit IS NULL OR per_head_unit IN ('KG', 'GM', 'L', 'ML', 'PIECES'));
