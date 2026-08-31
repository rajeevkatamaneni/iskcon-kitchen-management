-- =====================================================================
-- V80 — Servings is not a measure of food (2026-08-31)
--
-- Rajeev, seeing "Kheer · 40 servings" on an ingredient request: "I MUST
-- be in whatever measure the kitchen cooks in. In this case Kheer is a
-- liquid so it will be in litres. […] We don't use the word servings
-- anywhere else."
--
-- He is right, and the confusion is worth naming precisely. A serving is
-- a count of *people fed*. Every other member of this vocabulary measures
-- *food*: kilograms, grams, litres, millilitres, pieces. Offering them in
-- one dropdown invited exactly the row he found — a quantity of kheer
-- recorded in a unit that says nothing about how much kheer it is, and
-- which no storekeeper can weigh, pour or hand over.
--
-- ---------------------------------------------------------------------
-- The one place the idea survives, and why it is not a unit there
--
-- The meal planner asks how many adults, children and seniors are
-- expected and turns that into a rough plate count. That is a head count
-- being *displayed*, never a unit anybody selects and never a column
-- anybody stores a measure in. It stays, as a word on a screen.
--
-- ---------------------------------------------------------------------
-- What this costs, which is less than it was going to
--
-- E11's design argued SERVINGS could not be removed, on the grounds that
-- all 57 recipes in reference/recipes/seed_bangalore.sql yield in it and
-- none carries a per-head portion. That was an argument from a file
-- rather than from the database. In the data: 19 recipes yield in litres
-- and 7 in pieces, 24 of those 26 carry a per-head portion, and *no* row
-- in recipes or master_recipes yields in servings at all.
--
-- So the conversion below is a safety net rather than a migration of
-- anything. Where a straggler does exist — an older environment, a
-- restored dump — it becomes PIECES: a serving is a countable portion,
-- and pieces is this vocabulary's count. The number survives and stops
-- claiming to be a weight.
--
-- Note reference/recipes/seed_bangalore.sql is NOT updated. infra/README
-- already records that it predates several migrations and nothing runs
-- it; giving 57 temple recipes invented units to keep a stale file alive
-- would be worse than leaving it stale.
-- =====================================================================

ALTER TABLE recipes DROP CONSTRAINT recipes_yield_unit_valid;
ALTER TABLE master_recipes DROP CONSTRAINT master_recipes_yield_unit_valid;
ALTER TABLE ingredient_request_dishes DROP CONSTRAINT ingredient_request_dishes_unit_valid;

-- The temples' own rows: one tenant at a time, because a plain cross-tenant
-- UPDATE does not fail under RLS — it silently matches nothing and reports
-- success (V48).
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        UPDATE recipes SET base_yield_unit = 'PIECES' WHERE base_yield_unit = 'SERVINGS';
        UPDATE ingredient_request_dishes SET unit = 'PIECES' WHERE unit = 'SERVINGS';
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);
END $$;

-- The shared library has no tenant_id; V68 left `app.library_load` as the way
-- in for machinery rather than a person.
DO $$
BEGIN
    PERFORM set_config('app.library_load', 'true', true);
    UPDATE master_recipes SET yield_unit = 'PIECES' WHERE yield_unit = 'SERVINGS';
    PERFORM set_config('app.library_load', '', true);
END $$;

-- Putting the constraints back is the tripwire: a row the loop never reached
-- fails the deploy here rather than sitting in a column whose vocabulary no
-- longer admits it. DDL is not filtered by RLS, so it sees everything.
ALTER TABLE recipes ADD CONSTRAINT recipes_yield_unit_valid
    CHECK (base_yield_unit IN ('KG', 'GM', 'L', 'ML', 'PIECES'));

ALTER TABLE master_recipes ADD CONSTRAINT master_recipes_yield_unit_valid
    CHECK (yield_unit IN ('KG', 'GM', 'L', 'ML', 'PIECES'));

ALTER TABLE ingredient_request_dishes ADD CONSTRAINT ingredient_request_dishes_unit_valid
    CHECK (unit IN ('KG', 'GM', 'L', 'ML', 'PIECES'));
