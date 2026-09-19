-- =====================================================================
-- V143 — The recipe category "Catering sabjis" is called "Bulk sabjis"
--
-- T-230 (Rajeev, 2026-09-18). Catering was taken out of the product
-- (meals are three a day plus named events), and a category still named
-- after it reads as a feature that is not there. What the category holds is
-- the library's FHC sheet: sabjis made by the kilogram for bulk
-- distribution. "Bulk sabjis" says that.
--
-- The name was never typed by a temple. CategoryMapping created it the first
-- time a temple imported a library recipe from the "fhc-sabjis" book
-- category. From this release the mapping creates "Bulk sabjis" instead,
-- and this renames the rows it already created, so a temple that imported
-- before and after does not end up with both.
--
-- ---------------------------------------------------------------------
-- A rename, not a new category
--
-- The row keeps its id, so every recipe filed under it stays filed under it
-- with nothing else touched: no recipe's category_id changes, no recipe's
-- version bumps, and no translation cache is invalidated by a change to a
-- recipe that did not change.
--
-- The match is on lower(name), the same key the per-temple unique index
-- (recipe_categories_name_per_tenant, V11) uses, so a row the importer
-- created and one a person later retyped in a different case are the same
-- row here, as they are to the database.
--
-- ---------------------------------------------------------------------
-- A temple that already has a "Bulk sabjis": LEFT ALONE, and counted
--
-- The unique index means a rename there would fail and take the whole
-- deployment down with it. Two ways round that were considered:
--
--   1. Merge: move the old category's recipes onto the existing "Bulk sabjis"
--      and delete the old row. This changes recipes' category_id and deletes
--      a row, on the strength of a guess that two categories with those
--      names mean the same thing. A category named "Bulk sabjis" that a
--      person made by hand is theirs, and what they put in it may not be
--      what the library put in the other one.
--   2. Skip: leave that temple exactly as it is and say so.
--
-- This takes the second. It changes nothing that cannot be seen and nothing
-- that cannot be undone, and the case needs a temple to have created that
-- exact name by hand before this release, which no temple on staging has.
-- Such a temple keeps a "Catering sabjis" category until an admin merges it
-- by hand, and the count below says how many there were.
--
-- ---------------------------------------------------------------------
-- Per tenant, because migrations here are subject to RLS
--
-- recipe_categories is tenant-owned under FORCE ROW LEVEL SECURITY (V11) and
-- the migration role is unprivileged, so a blanket UPDATE with app.tenant_id
-- unset matches nothing and reports success. So this adopts each tenant in
-- turn, as V97 does. The counts are raised as a NOTICE because afterwards
-- nothing can say how many rows were renamed or skipped.
-- =====================================================================

DO $$
DECLARE
    t        RECORD;
    renamed  INTEGER;
    total    INTEGER := 0;
    skipped  INTEGER := 0;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        IF EXISTS (
            SELECT 1 FROM recipe_categories
            WHERE tenant_id = t.id AND lower(name) = 'bulk sabjis'
        ) THEN
            -- Only worth counting where there is something that was not renamed.
            IF EXISTS (
                SELECT 1 FROM recipe_categories
                WHERE tenant_id = t.id AND lower(name) = 'catering sabjis'
            ) THEN
                skipped := skipped + 1;
            END IF;
            CONTINUE;
        END IF;

        UPDATE recipe_categories
        SET name = 'Bulk sabjis'
        WHERE tenant_id = t.id
          AND lower(name) = 'catering sabjis';

        GET DIAGNOSTICS renamed = ROW_COUNT;
        total := total + renamed;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);

    RAISE NOTICE 'V143: renamed % "Catering sabjis" categor(y/ies) to "Bulk sabjis"; '
        '% temple(s) already had a "Bulk sabjis" and were left alone.', total, skipped;
END $$;
