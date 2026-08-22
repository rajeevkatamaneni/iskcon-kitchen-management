-- =====================================================================
-- V68 — The shared recipe library (E2-S9)
--
-- 5,376 recipes from 32 state books, readable by every temple on the
-- platform and writable only by a platform operator. A temple never cooks
-- from this table: it takes a copy into its own `recipes` (E2-S12), and the
-- copy is thereafter entirely its own.
--
-- ---------------------------------------------------------------------
-- Why master_recipes is not tenant-owned
--
-- The same reasoning as platform_notices (V66), and the second and last
-- table in this schema to cross tenant isolation on purpose. A recipe
-- written for Vijayawada has to be readable in Bangalore — that is the
-- entire feature. Copying 5,376 rows per temple was the alternative and is
-- worse in every direction: two hundred copies of every correction, and a
-- fan-out job standing between an operator's edit and the kitchens.
--
-- So the table carries no tenant_id and gets RLS keyed on *identity*
-- rather than tenant:
--
--   * SELECT — any connection whose verified identity resolves to an
--     ACTIVE user, whatever their temple or role. Deliberately not narrowed
--     to admins: the person who needs a recipe is the cook.
--   * INSERT / UPDATE / DELETE — a platform operator, and nobody else.
--     The service checks MANAGE_RECIPE_LIBRARY; this policy is what makes
--     that check more than a convention.
--
-- Note what the absence of tenant_id does for delete_tenant_cascade (V44):
-- it finds the tables it must purge by looking for that column, so deleting
-- a temple leaves the library untouched. That is correct, and it is worth
-- saying out loud because the reverse would be unrecoverable.
--
-- ---------------------------------------------------------------------
-- Why display_name exists beside name
--
-- 1,272 of the 5,376 recipes share a name with a recipe from another state.
-- Sabudana Khichdi appears in seventeen books, and Bihar's, Maharashtra's
-- and Uttar Pradesh's are three different dishes — 5 Kg of sago against 7,
-- one of them sweetened, one yielding kilos rather than litres. A temple
-- may hold only one active recipe of a given name, so the library
-- disambiguates itself at ingestion (design doc Q3):
--
--     name alone              3,504 recipes   Majjige
--     + state                 1,870 recipes   Sabudana Khichdi (Maharashtra)
--     + state and category        2 recipes   Alugadde Palya (Karnataka, Ekadashi)
--
-- The third rung is not hypothetical. Alugadde Palya appears twice in the
-- Karnataka book — once under Ekadashi with rock salt and no mustard, once
-- under Sabji's Dry with a full tempering. Keying on name alone would give
-- a cook the fasting method on an ordinary Tuesday, or worse, the other way
-- round.
--
-- disambiguated_by records which rung was used, so a search row can decide
-- whether to print the state a second time without sniffing a string for a
-- bracket.
-- =====================================================================

CREATE TABLE master_recipes (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Which book, and the language it was written in. The language is kept
    -- for provenance only: nothing translated is stored here, because a
    -- book's language follows the state the recipe came from and never the
    -- cook reading it (design doc Q5).
    state_slug         TEXT        NOT NULL,
    state              TEXT        NOT NULL,
    book_language      TEXT        NOT NULL,

    recipe_slug        TEXT        NOT NULL,

    -- As the book wrote it, and as the library shows it. See the note above.
    name               TEXT        NOT NULL,
    display_name       TEXT        NOT NULL,
    disambiguated_by   SMALLINT    NOT NULL DEFAULT 0,

    subtitle           TEXT,

    -- The book's own category key and its English name. Mapped onto the
    -- temple's own recipe_categories at import; not a foreign key, because
    -- the library belongs to no tenant and recipe_categories do.
    category_key       TEXT        NOT NULL,
    category_name      TEXT        NOT NULL,

    badge              TEXT        NOT NULL,

    -- The yield, three ways. yield_text is what the book said, verbatim,
    -- including the count noun and any parenthetical — "300 idlis (3 per
    -- devotee)" — because "300 pieces" tells a cook nothing. The parsed
    -- pair is what arithmetic uses.
    yield_text         TEXT        NOT NULL,
    yield_qty          NUMERIC(12, 3) NOT NULL,
    yield_unit         TEXT        NOT NULL,

    -- What one person eats. Present on 5,032 of the 5,376: 4,563 in the
    -- book's own `per` field, 359 only inside the yield's "(3 per devotee)"
    -- parenthetical, and 110 in both, where every one of the 110 agrees.
    -- Null on the 344 nobody serves by the head — masalas, pickles, the
    -- sustainable sweets.
    per_head_text      TEXT,
    per_head_qty       NUMERIC(12, 3),
    per_head_unit      TEXT,

    -- Rupees, for the whole yield. Indicative bulk rates that will drift;
    -- for comparing dishes against each other, never for accounting.
    indicative_cost    NUMERIC(10, 2),

    region             TEXT,
    why                TEXT        NOT NULL,
    catering_note      TEXT,

    -- The book's three working notes: what to start the night before, what
    -- vessel and how many cooks, and which season the dish belongs to.
    note_start         TEXT,
    note_vessel        TEXT,
    note_season        TEXT,

    tags               TEXT[]      NOT NULL DEFAULT '{}',
    serve_with         TEXT[]      NOT NULL DEFAULT '{}',

    -- Denormalised out of `ingredients` so the generated search vector can
    -- reach them: a tsvector expression has to be immutable, and digging
    -- names out of JSONB inside one is neither immutable nor readable.
    ingredient_names   TEXT[]      NOT NULL DEFAULT '{}',

    -- Ordered lists whose only consumer is this table, so JSONB rather than
    -- side tables that nothing would ever join to.
    --   ingredients: [{name, qty, qtyValue, qtyUnit, scaled}]
    --   method:      ["step", "step", ...]
    ingredients        JSONB       NOT NULL,
    method             JSONB       NOT NULL,

    -- Where this row came from, down to the commit. The vendored books
    -- carry the same reference in their README; this repeats it per row so
    -- one recipe can be traced without consulting a note.
    source_ref         TEXT        NOT NULL,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Who last edited it, where an operator has. Null for a row that is
    -- still exactly as the loader wrote it.
    updated_by_user_id UUID        REFERENCES users(id) ON DELETE SET NULL,

    CONSTRAINT master_recipes_name_present    CHECK (length(name) > 0 AND length(display_name) > 0),
    CONSTRAINT master_recipes_yield_positive  CHECK (yield_qty > 0),
    CONSTRAINT master_recipes_per_head_sane   CHECK (per_head_qty IS NULL OR per_head_qty > 0),

    -- The same four the recipes table admits after V69. A book that yields
    -- in servings does not exist — every one is litres, kilos or pieces —
    -- but SERVINGS is admitted anyway so an operator writing a recipe by
    -- hand is not forced into a unit that suits a printed book.
    CONSTRAINT master_recipes_yield_unit_valid CHECK (
        yield_unit IN ('SERVINGS', 'LITRES', 'KG', 'PIECES')),

    CONSTRAINT master_recipes_per_head_unit_valid CHECK (
        per_head_unit IS NULL OR per_head_unit IN ('LITRES', 'KG', 'PIECES')),

    CONSTRAINT master_recipes_per_head_complete CHECK (
        (per_head_qty IS NULL AND per_head_unit IS NULL)
        OR (per_head_qty IS NOT NULL AND per_head_unit IS NOT NULL)),

    CONSTRAINT master_recipes_badge_valid CHECK (
        badge IN ('Everyday', 'Moderate', 'Festival', 'Sustainable', 'Economical')),

    CONSTRAINT master_recipes_rung_valid CHECK (disambiguated_by BETWEEN 0 AND 2)
);

COMMENT ON TABLE master_recipes IS
    'The shared recipe library (E2-S9). Not tenant-owned; readable by any verified active user, writable only by a platform operator. A temple takes copies, never cooks from this table.';

COMMENT ON COLUMN master_recipes.display_name IS
    'The name after ingestion-time disambiguation: bare where unique library-wide, + state where a name repeats, + state and category where a state repeats it twice.';

COMMENT ON COLUMN master_recipes.per_head_qty IS
    'What one person eats. The planner multiplies a head count by this to reach a target in the recipe''s own unit; null where nobody serves the dish by the head.';

-- The reload key. The loader upserts on it, so re-running leaves 5,376 rows
-- rather than 10,752.
CREATE UNIQUE INDEX master_recipes_book_key ON master_recipes (state_slug, recipe_slug);

-- A temple's search matches this and shows what it finds, so it has to be
-- unique for the same reason a temple's own recipe names are.
CREATE UNIQUE INDEX master_recipes_display_name ON master_recipes (lower(display_name));

CREATE INDEX master_recipes_category ON master_recipes (category_key);
CREATE INDEX master_recipes_state    ON master_recipes (state_slug);

-- ---------------------------------------------------------------------
-- What a search matches.
--
-- Weighted, and deliberately not "every key in the recipe". Method steps
-- and the "why" are a paragraph each and nearly every recipe boils
-- something; indexing them means "boil" returns three thousand rows and the
-- filter stops filtering exactly when the list is longest.
--
--   A  the name, both forms, and the subtitle
--   B  the ingredients, and the tags — "Jain-safe", "Gluten-free"
--   C  where it is from, what it is, and when it is cooked
--
-- 'simple' rather than 'english' throughout: this feeds a type-ahead, where
-- prefix matching has to be predictable, and stemming a Kannada dish name
-- through an English stemmer is a guess dressed as a rule.
-- ---------------------------------------------------------------------
-- array_to_string is STABLE rather than IMMUTABLE — the array output function it
-- reaches through is generic — so PostgreSQL refuses it inside a generated column.
-- For text[] the operation genuinely is immutable, and this says so once rather
-- than pushing the join out into the loader where the weights could not follow it.
CREATE OR REPLACE FUNCTION kms_join_text_array(words TEXT[])
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$ SELECT coalesce(array_to_string(words, ' '), '') $$;

COMMENT ON FUNCTION kms_join_text_array(TEXT[]) IS
    'Joins a text array for indexing. Declared IMMUTABLE so a generated tsvector column may call it; true for text[], which is the only type it takes.';

ALTER TABLE master_recipes
    ADD COLUMN search_doc tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(display_name, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(name, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(subtitle, '')), 'A') ||
        setweight(to_tsvector('simple', kms_join_text_array(ingredient_names)), 'B') ||
        setweight(to_tsvector('simple', kms_join_text_array(tags)), 'B') ||
        setweight(to_tsvector('simple', coalesce(category_name, '')), 'C') ||
        setweight(to_tsvector('simple', coalesce(state, '')), 'C') ||
        setweight(to_tsvector('simple', coalesce(badge, '')), 'C')
    ) STORED;

CREATE INDEX master_recipes_search ON master_recipes USING GIN (search_doc);

-- ---------------------------------------------------------------------
-- Isolation. Identity, not tenant — see the header.
--
-- There is a third writer besides "an operator" and "nobody": the loader,
-- which runs as a Cloud Run job off the same image with no signed-in person
-- behind it. V66 met the same problem with notices raised by automation and
-- solved it by admitting exactly one shape for a connection carrying no
-- identity. This does the same, with a narrower key.
--
-- app.library_load is set only by LibraryLoader, on a thread that is not a
-- request thread, and cleared when the connection returns to the pool like
-- every other setting. Every policy branch that honours it *also* requires
-- app.auth_uid to be absent — so even if the flag ever leaked onto a request
-- thread, a signed-in caller could not use it, and an unauthenticated public
-- endpoint sets no flag and so gains nothing.
-- ---------------------------------------------------------------------
ALTER TABLE master_recipes ENABLE ROW LEVEL SECURITY;
ALTER TABLE master_recipes FORCE ROW LEVEL SECURITY;

-- Readable by anybody the platform knows and has not disabled. The users
-- row is reached through the app.auth_uid read escape (V2), the same
-- verified identity RLS already trusts at sign-in. NULLIF because RESET
-- leaves a custom setting as the empty string rather than unset, and a
-- control that raises instead of denying is one somebody works around.
--
-- Deliberately not widened to "any connection at all": the public donation
-- and communication endpoints run unauthenticated and reach this database,
-- and none of them has any business reading the library.
CREATE POLICY master_recipes_read ON master_recipes
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.status = 'ACTIVE'
        )
        OR (
            NULLIF(current_setting('app.library_load', true), '') = 'true'
            AND NULLIF(current_setting('app.auth_uid', true), '') IS NULL
        )
    );

-- Written by a platform operator, or by the loader. Three separate policies
-- rather than one FOR ALL, so a later change to one cannot widen the others
-- by accident.
CREATE POLICY master_recipes_insert ON master_recipes
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.status = 'ACTIVE'
              AND u.role = 'SUPER_ADMIN'
        )
        OR (
            NULLIF(current_setting('app.library_load', true), '') = 'true'
            AND NULLIF(current_setting('app.auth_uid', true), '') IS NULL
        )
    );

CREATE POLICY master_recipes_update ON master_recipes
    FOR UPDATE
    USING (
        EXISTS (
            SELECT 1 FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.status = 'ACTIVE'
              AND u.role = 'SUPER_ADMIN'
        )
        OR (
            NULLIF(current_setting('app.library_load', true), '') = 'true'
            AND NULLIF(current_setting('app.auth_uid', true), '') IS NULL
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.status = 'ACTIVE'
              AND u.role = 'SUPER_ADMIN'
        )
        OR (
            NULLIF(current_setting('app.library_load', true), '') = 'true'
            AND NULLIF(current_setting('app.auth_uid', true), '') IS NULL
        )
    );

-- Deleting is an operator's act alone. The loader adds and corrects; it
-- never removes, so a book losing a recipe upstream leaves the row here
-- until somebody decides to take it out.
CREATE POLICY master_recipes_delete ON master_recipes
    FOR DELETE
    USING (
        EXISTS (
            SELECT 1 FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.status = 'ACTIVE'
              AND u.role = 'SUPER_ADMIN'
        )
    );
