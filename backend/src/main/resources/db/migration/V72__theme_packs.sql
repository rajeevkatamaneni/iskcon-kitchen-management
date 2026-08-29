-- =====================================================================
-- V72 — Theme packs
--
-- A curated catalogue of colour palettes. A temple administrator picks one
-- and it applies to every person who serves at that temple; a platform
-- operator is the only one who can add, change or withdraw a pack.
--
-- This exists because of what happened at the 2026-08-22 demo. The
-- terracotta was loved by some of the room and disliked by others, and no
-- single palette was going to satisfy both. The conclusion was not to pick
-- a better colour — it was that colour is the one part of this interface
-- where the temple's own taste should win.
--
-- ---------------------------------------------------------------------
-- What a pack actually is
--
-- Twenty-three colours, and nothing else. No fonts, no spacing, no radii,
-- no component variants. The rest of the design system is a set of
-- decisions about legibility and rhythm that a temple has no reason to want
-- to change and every reason not to be able to.
--
-- Twenty-two of the twenty-three are the semantic roles already named in
-- docs/DESIGN_SYSTEM.md §2 — three surfaces, two hairlines, four inks,
-- five accent weights, and four status pairs. A pack supplies a value for
-- every one of them and for nothing else. That is enforced here by
-- `theme_palette_complete`: a pack missing a token would leave one surface
-- on the previous palette while the rest of the screen moved, which is
-- worse to look at than either palette on its own and much harder to
-- diagnose than a refused write.
--
-- ---------------------------------------------------------------------
-- The twenty-third: focus-ring
--
-- New here, and it is a fix rather than a feature. The focus ring is the
-- only shadow this design system allows and the only thing a keyboard user
-- has to tell them where they are. It had been drawing its colour from
-- `accent-border`, which has a different job — the quiet hairline on a
-- secondary button, where nothing is asked of its contrast.
--
-- Measured on 2026-08-28 while building this table: accent-border #ECD9CF
-- against the page is **1.36:1**. WCAG 2.2 SC 1.4.11 asks 3:1 of a focus
-- indicator. The ring every keyboard user in every temple has been relying
-- on has been, in practice, invisible.
--
-- Giving it its own token is what makes it fixable at all: as long as the
-- two shared a value, raising the ring to 3:1 would have put a dark line
-- around every secondary button on every screen. The default pack's ring
-- is #BE775E, which measures 3.51 on canvas, 3.31 on raised and 3.01 on
-- sunken — the lightest terracotta that clears the floor on all three, so
-- it is the smallest change that is still a correct one.
--
-- This is a change to a locked document (DESIGN_SYSTEM.md §2 and §4) and
-- is recorded in docs/CHANGELOG.md.
--
-- Values are stored as `#RRGGBB` and converted to channel triples on the
-- way to the browser, because the interface uses Tailwind's opacity
-- modifier in forty-six places — `hover:bg-raised/60` on every table row —
-- and that compiles to `rgb(var(--token) / 0.6)`, which a hex string
-- cannot satisfy. The hex form is what a person reads and writes; the
-- channel form is a rendering detail and does not belong in the database.
--
-- ---------------------------------------------------------------------
-- Why theme_packs is not tenant-owned
--
-- The same argument as master_recipes (V68) and platform_notices (V66),
-- and this is the fourth and last table to cross tenant isolation on
-- purpose. A pack is a design artifact that ships with the product. Every
-- temple sees the same fifteen, one operator maintains them, and a
-- correction to a contrast failure has to reach every temple wearing that
-- pack at once. Copying the catalogue per tenant would mean two hundred
-- copies of every fix.
--
-- So the table carries no tenant_id, and delete_tenant_cascade (V44) —
-- which finds what to purge by looking for that column — leaves it alone.
-- What *is* tenant-owned is the choice, and that lives where every other
-- per-temple preference already lives: one more column on tenant_settings.
--
-- ---------------------------------------------------------------------
-- Why SELECT is open to every connection
--
-- V68 deliberately refused to widen the library's read policy past an
-- authenticated identity, and said why: the public donation and
-- communication endpoints run unauthenticated against this database and
-- have no business reading five thousand recipes. That reasoning does not
-- transfer, and it is worth being explicit about the difference rather
-- than copying the shape.
--
-- A pack is twenty-three hex values and a name somebody chose. There is no
-- temple in it, no person, no quantity and no money. Knowing that a
-- palette called "Marigold" exists tells an attacker nothing they could
-- not learn by looking at a screenshot. Against that, the pages that most
-- need a palette before an identity exists are the ones a temple points
-- devotees at — the public giving page under /t/{slug} — and a read policy
-- keyed on identity would leave exactly those pages wearing a palette the
-- temple did not choose.
--
-- So: USING (true) on SELECT, and the three write policies carry the whole
-- weight. Writing is a platform operator's act, and today it is enforced
-- *only* here: there is no write endpoint yet, because packs arrive by
-- migration. If one is ever built it will carry its own permission, and
-- this policy is what will make that permission more than a convention.
--
-- ---------------------------------------------------------------------
-- Why there is a seed escape
--
-- Packs arrive by migration, not by anybody typing them into a form: they
-- are design work, produced and contrast-checked outside the application
-- and shipped with it. But a migration runs as the schema owner, FORCE ROW
-- LEVEL SECURITY subjects the owner to the same policies as everyone else,
-- and the write policies ask for an authenticated platform operator — a
-- thing no migration has or should have.
--
-- The escape is `app.theme_load`, modelled exactly on V68's
-- `app.library_load`: it opens INSERT and UPDATE only when it is set *and*
-- there is no authenticated identity on the connection, so it can never
-- widen a real request. It does not open DELETE. The default pack below is
-- seeded before RLS is enabled and needs none of it; the escape is for the
-- migrations that follow this one.
-- =====================================================================

CREATE TABLE theme_packs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Stable, and never renamed once shipped: it is what a temple's stored
    -- choice points at in the seed data, and what appears in a support
    -- conversation about "the blue one".
    slug        TEXT        NOT NULL UNIQUE,
    name        TEXT        NOT NULL,

    -- How loud the pack is, in the three words the choice was asked for.
    -- The catalogue is grouped by this, so somebody who knows they want
    -- something quiet never has to look at the bright ones.
    family      TEXT        NOT NULL,

    -- One sentence under the name saying what it feels like. A palette
    -- cannot be judged from a swatch grid alone.
    description TEXT        NOT NULL,

    -- token -> "#RRGGBB", the twenty-three roles the interface asks for.
    palette     JSONB       NOT NULL,

    -- Where it sits within its family. Deliberate rather than
    -- alphabetical: the order the packs were designed in is the order they
    -- read best in.
    sort_order  INTEGER     NOT NULL DEFAULT 0,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT theme_family_valid CHECK (family IN ('VIBRANT', 'BALANCED', 'MUTED')),

    -- Every role, or the write is refused. `?&` asks jsonb for the whole
    -- set of keys at once and is immutable, so it is legal in a CHECK
    -- where a jsonb_each_text subquery would not be. The *shape* of each
    -- value — that it is six hex digits behind a hash — cannot be asserted
    -- without a subquery and is validated in the service instead.
    CONSTRAINT theme_palette_complete CHECK (
        jsonb_typeof(palette) = 'object'
        AND palette ?& ARRAY[
            'canvas', 'raised', 'sunken',
            'hairline', 'hairline-strong',
            'ink', 'ink-secondary', 'ink-muted', 'ink-inverse',
            'accent-bg', 'accent-border', 'accent', 'accent-hover', 'accent-text',
            'focus-ring',
            'danger-bg', 'danger',
            'info-bg', 'info',
            'warning-bg', 'warning',
            'success-bg', 'success'
        ]
    )
);

-- The catalogue is read whole, grouped by family, on one screen. There is
-- no query that filters it and never will be at sixteen rows, so this
-- index exists to give the read a stable order rather than to make it
-- fast.
CREATE INDEX theme_packs_family_order ON theme_packs (family, sort_order, name);

-- ---------------------------------------------------------------------
-- The temple's choice
--
-- ON DELETE RESTRICT, not SET NULL. Withdrawing a pack that temples are
-- currently wearing should stop and say so — silently returning three
-- kitchens to the default overnight is the kind of change nobody connects
-- to the button that caused it. The service turns the refusal into
-- KMS-4973, which names how many temples are on it.
--
-- NULL means "the temple has never chosen", which is not the same as
-- choosing the default pack, and both render identically. The distinction
-- is worth keeping: it is the difference between a preference and an
-- absence, and it is what tells us whether this feature is being used.
-- ---------------------------------------------------------------------
ALTER TABLE tenant_settings
    ADD COLUMN selected_theme_pack_id UUID REFERENCES theme_packs(id) ON DELETE RESTRICT;

-- ---------------------------------------------------------------------
-- The default pack
--
-- The terracotta the application was designed in, seeded so that a temple
-- which tries three others can come back to it by name rather than by
-- clearing a setting. Inserted here, before the policies exist, because a
-- migration has no authenticated operator to satisfy them with.
-- ---------------------------------------------------------------------
INSERT INTO theme_packs (slug, name, family, description, palette, sort_order)
VALUES (
    'temple-terracotta',
    'Temple terracotta',
    'MUTED',
    'Softened terracotta on warm grey, drawn from ISKCON''s own saffron. The palette this application was designed in.',
    '{
        "canvas": "#FFFFFF",
        "raised": "#FAF8F7",
        "sunken": "#F1EDEB",
        "hairline": "#E7E1DD",
        "hairline-strong": "#DAD1CB",
        "ink": "#2B2621",
        "ink-secondary": "#6E6660",
        "ink-muted": "#716B65",
        "ink-inverse": "#FCF8F5",
        "accent-bg": "#F6EBE4",
        "accent-border": "#ECD9CF",
        "accent": "#AE5838",
        "accent-hover": "#94482D",
        "accent-text": "#8A4A2F",
        "focus-ring": "#BE775E",
        "danger-bg": "#F7E7E3",
        "danger": "#9B2C1F",
        "info-bg": "#EDF7FC",
        "info": "#356780",
        "warning-bg": "#F4EAD1",
        "warning": "#87641A",
        "success-bg": "#E7EFE8",
        "success": "#3E6B48"
    }'::jsonb,
    0
);

-- ---------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------

ALTER TABLE theme_packs ENABLE ROW LEVEL SECURITY;
ALTER TABLE theme_packs FORCE ROW LEVEL SECURITY;

-- Readable by anything that can reach the database. See the header for why
-- this is the one table where that is the right answer: the content is
-- twenty-three hex values, and the pages that need it earliest are the ones
-- with no identity yet.
CREATE POLICY theme_packs_read ON theme_packs
    FOR SELECT
    USING (true);

-- Written by a platform operator, or by a migration carrying the seed
-- escape. Three separate policies rather than one FOR ALL, so a later
-- change to one cannot widen the others by accident (V68).
--
-- NULLIF because RESET leaves a custom setting as the empty string rather
-- than unset, and a control that raises instead of denying is one somebody
-- works around.
CREATE POLICY theme_packs_insert ON theme_packs
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.status = 'ACTIVE'
              AND u.role = 'SUPER_ADMIN'
        )
        OR (
            NULLIF(current_setting('app.theme_load', true), '') = 'true'
            AND NULLIF(current_setting('app.auth_uid', true), '') IS NULL
        )
    );

CREATE POLICY theme_packs_update ON theme_packs
    FOR UPDATE
    USING (
        EXISTS (
            SELECT 1 FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.status = 'ACTIVE'
              AND u.role = 'SUPER_ADMIN'
        )
        OR (
            NULLIF(current_setting('app.theme_load', true), '') = 'true'
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
            NULLIF(current_setting('app.theme_load', true), '') = 'true'
            AND NULLIF(current_setting('app.auth_uid', true), '') IS NULL
        )
    );

-- Withdrawing a pack is an operator's act alone, and the seed escape does
-- not open it: a migration that ships a new pack has no business removing
-- one a temple may be wearing.
CREATE POLICY theme_packs_delete ON theme_packs
    FOR DELETE
    USING (
        EXISTS (
            SELECT 1 FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.status = 'ACTIVE'
              AND u.role = 'SUPER_ADMIN'
        )
    );
