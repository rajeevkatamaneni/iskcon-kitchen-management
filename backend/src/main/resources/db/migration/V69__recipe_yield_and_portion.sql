-- =====================================================================
-- V69 — What a recipe makes, and what one person eats (E2-S11)
--
-- Two changes that belong together, and one defect they expose.
--
-- ---------------------------------------------------------------------
-- 1. A recipe may now yield in kilograms and pieces
--
-- base_yield_unit has admitted SERVINGS and LITRES since V11, taken from
-- RM 2019 where both occur. The library does not fit: of its 5,376 recipes,
-- 2,918 yield in litres, 1,619 in kilograms and 839 in pieces, and not one
-- in servings. Forcing a 12 Kg pickle or 300 idlis into LITRES would be a
-- lie written into a column that the printed recipe card then repeats.
--
-- Widening a CHECK is the whole change. The unit was never arithmetic:
-- RecipeScaler is a ratio of target to base and never inspects or converts
-- it, so scaling, sufficiency (E4) and order generation (E5) are untouched.
--
-- yield_note carries what the book actually said — "300 idlis (3 per
-- devotee)", "~10 Kg finished (100 gm per devotee)" — because 839 rows
-- reading "pieces" would tell a cook nothing, and the count noun is the
-- only thing that makes the number mean something.
--
-- ---------------------------------------------------------------------
-- 2. A recipe may now say what one person eats
--
-- This is the load-bearing half, and it fixes a defect that is live today.
--
-- The planner asks how many people are expected and then writes that number
-- into each dish's target — a *copy*. That is only right when the recipe
-- happens to be measured in servings. Plan a 20 L rasam for 300 people and
-- it scales fifteen-fold: three hundred litres, a litre a head. Nobody has
-- hit it yet because almost every recipe entered so far says SERVINGS,
-- where copying happens to give the right answer. The library makes that
-- assumption false for every recipe it holds.
--
-- With a per-head portion the planner multiplies instead of copying:
--
--     target = head count x per head
--
--     Rasam       300 x 200 ml = 60 L      (not 300 L)
--     Rave Idli   100 x 3      = 300 idlis (not 100)
--
-- Nullable, and honestly so: 344 of the library's recipes have no per-head
-- portion because nobody serves a per-head portion of lime pickle. For
-- those the planner asks rather than guessing, and E2-S17 refuses to save a
-- meal where it was never answered.
--
-- Note what is deliberately *not* here: no "this recipe's base yield serves
-- N devotees". It is never needed, because nothing divides. That is what
-- lets a pickle whose 12 Kg implies six hundred devotees work with no
-- special case at all.
--
-- ---------------------------------------------------------------------
-- 3. meal_plans.target_servings becomes target_yield
--
-- The column has not held servings since the head count arrived in V51, and
-- after the change above it holds litres and kilograms too. Renaming is not
-- decoration: a column whose name contradicts its contents is how the copy
-- defect above survived review in the first place.
--
-- Every existing row keeps its meaning. Values written before today belong
-- to recipes whose unit is SERVINGS or LITRES, both still legal, so the
-- rename moves no data and invents nothing.
--
-- ---------------------------------------------------------------------
-- 4. The rest of what a book knows
--
-- The remaining columns carry the fields the library holds so that an
-- imported recipe and a hand-written one are the same kind of thing. Named
-- individually rather than gathered into a `data JSONB`, because a bag of
-- fourteen things nobody has named is the abstraction we agreed not to
-- build.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1 & 2 — yield and portion
-- ---------------------------------------------------------------------

ALTER TABLE recipes DROP CONSTRAINT recipes_yield_unit_valid;

ALTER TABLE recipes ADD CONSTRAINT recipes_yield_unit_valid
    CHECK (base_yield_unit IN ('SERVINGS', 'LITRES', 'KG', 'PIECES'));

ALTER TABLE recipes
    ADD COLUMN yield_note    TEXT,
    ADD COLUMN per_head_qty  NUMERIC(12, 3),
    ADD COLUMN per_head_unit TEXT;

ALTER TABLE recipes ADD CONSTRAINT recipes_per_head_sane
    CHECK (per_head_qty IS NULL OR per_head_qty > 0);

ALTER TABLE recipes ADD CONSTRAINT recipes_per_head_unit_valid
    CHECK (per_head_unit IS NULL OR per_head_unit IN ('LITRES', 'KG', 'PIECES'));

-- Both or neither. A quantity with no unit is a number nobody can act on.
ALTER TABLE recipes ADD CONSTRAINT recipes_per_head_complete
    CHECK ((per_head_qty IS NULL AND per_head_unit IS NULL)
        OR (per_head_qty IS NOT NULL AND per_head_unit IS NOT NULL));

COMMENT ON COLUMN recipes.yield_note IS
    'What the source said the yield was, verbatim, including the count noun — "300 idlis (3 per devotee)". The number and unit do the arithmetic; this is what a cook reads.';

COMMENT ON COLUMN recipes.per_head_qty IS
    'What one person eats. The planner multiplies the head count by this to reach a target in the recipe''s own unit. Null where nobody serves the dish by the head — masalas, pickles.';

-- ---------------------------------------------------------------------
-- 4 — the rest of what a book knows
-- ---------------------------------------------------------------------

ALTER TABLE recipes
    ADD COLUMN subtitle         TEXT,
    ADD COLUMN badge            TEXT,
    ADD COLUMN indicative_cost  NUMERIC(10, 2),
    ADD COLUMN why              TEXT,
    ADD COLUMN catering_note    TEXT,
    ADD COLUMN sub_region       TEXT,
    ADD COLUMN note_start       TEXT,
    ADD COLUMN note_vessel      TEXT,
    ADD COLUMN note_season      TEXT,
    ADD COLUMN tags             TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN serve_with       TEXT[] NOT NULL DEFAULT '{}',

    -- Where this copy came from, where it came from anywhere. Records the
    -- provenance and nothing else: it constrains no edit, and the copy is
    -- the temple's to change entirely, including its per-head portion. A
    -- later correction to the library never reaches a copy already taken.
    --
    -- ON DELETE SET NULL, not RESTRICT: an operator removing a recipe from
    -- the library must not be blocked by, or destroy, the copies temples
    -- have already made and cooked from.
    ADD COLUMN master_recipe_id UUID REFERENCES master_recipes(id) ON DELETE SET NULL;

ALTER TABLE recipes ADD CONSTRAINT recipes_badge_valid
    CHECK (badge IS NULL OR badge IN ('Everyday', 'Moderate', 'Festival', 'Sustainable', 'Economical'));

-- "Have we already taken this one?" — asked once per row of every search
-- result, so it is an index rather than a scan.
CREATE INDEX recipes_master_source ON recipes (tenant_id, master_recipe_id)
    WHERE master_recipe_id IS NOT NULL;

COMMENT ON COLUMN recipes.master_recipe_id IS
    'The library recipe this was copied from, if any. Provenance only — the copy is wholly the temple''s, and a later library edit never reaches it.';

-- ---------------------------------------------------------------------
-- Ingredients created by an import
--
-- Importing one recipe can add ten rows to a catalogue E2-S1 deliberately
-- made the temple's own to curate. The decision is to create them silently
-- rather than stand a review step in front of every import — but marked, so
-- the ingredients page can later offer to set aside the ones nobody has
-- ever used.
-- ---------------------------------------------------------------------

ALTER TABLE ingredients
    ADD COLUMN library_derived BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN ingredients.library_derived IS
    'True where this row was created by importing a library recipe rather than typed by the temple. Nothing behaves differently; it is there so a catalogue can be tidied later.';

-- ---------------------------------------------------------------------
-- 3 — the rename
-- ---------------------------------------------------------------------

ALTER TABLE meal_plans RENAME COLUMN target_servings TO target_yield;

COMMENT ON COLUMN meal_plans.target_yield IS
    'How much to make, in the recipe''s own yield unit — litres of rasam, kilos of podi, idlis. Derived at planning time from the head count and the recipe''s per-head portion, and overridable per dish. Never a head count: that is adults/children/seniors on this same row.';
