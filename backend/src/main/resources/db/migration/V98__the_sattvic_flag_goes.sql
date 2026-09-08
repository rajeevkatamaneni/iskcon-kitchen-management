-- =====================================================================
-- V98 — The sattvic-prohibited flag goes, and the override chain with it
--
-- D-18, ruled by Rajeev on 2026-09-08. T-050 removed the application half;
-- this removes the two columns, which is the half that does not remove
-- itself.
--
-- ---------------------------------------------------------------------
-- Why the flag went, since a dropped column leaves no argument behind
--
-- There are three ways an ingredient reaches a temple's catalogue, and only
-- one of them ever set a dietary flag:
--
--   1. Provisioning seeded eleven rows — onion, garlic, mushroom and egg
--      flagged sattvic-prohibited; rice, wheat flour, semolina and four dals
--      flagged Ekadashi-prohibited.
--   2. A person adding one by hand, who sets the flags explicitly.
--   3. Recipe import, which creates whatever a recipe names and the temple
--      lacks — with both flags false, deliberately.
--
-- Path 3 is the bulk path. So most of a real catalogue arrived unclassified,
-- and the sattvic flag's entire live population was path 1: rows that
-- provisioning inserted SO THAT it could tick them forbidden. A temple
-- kitchen does not stock onion or garlic, so they arrive by no other route.
-- The flag existed to mark rows that existed because of the flag. Deleting
-- the seed (T-050, same task) therefore leaves the column guarding nothing,
-- and the two are one change rather than two.
--
-- ---------------------------------------------------------------------
-- Why sattvic_override_reason cannot be kept "just in case"
--
-- It was written by exactly one code path: RecipeService.applySattvicEnforcement,
-- which returned a reason only when some ingredient on the recipe carried
-- is_sattvic_prohibited. With no ingredient able to carry the flag that
-- method returns null on every path, so the column can never be written
-- again. Keeping it would not be a smaller change; it would be a broken one
-- — a recipe badge permanently reading false and a job-card warning that can
-- never fire, both of which look like answers.
--
-- ---------------------------------------------------------------------
-- Per tenant, because migrations here are subject to RLS
--
-- DROP COLUMN is DDL and runs as the table owner, so the drops themselves
-- need no tenant context. The COUNT does. `recipes` carries FORCE ROW LEVEL
-- SECURITY and the migration role is unprivileged, so a plain
-- `SELECT count(*) FROM recipes` runs with app.tenant_id unset, the policy
-- fails closed via NULLIF, and it would report ZERO however many rows there
-- are — a number that looks like an answer and is an artefact of the
-- connection. So this adopts each tenant in turn, exactly as V97 does.
--
-- The count is raised as a NOTICE because it is the one figure nobody can
-- recover afterwards: once the column is dropped there is no query that says
-- how many recipes had been saved past the block. It is expected to be zero
-- or close to it — the override needed a Temple Admin, a written reason, and
-- an ingredient somebody had flagged — but "expected" is not "checked", and
-- the release agent reads the real figure out of the rollout log. Where the
-- notice ends up depends on the environment's client_min_messages and on
-- what the caller does with client notices, so this is a best effort rather
-- than a guarantee.
-- =====================================================================

DO $$
DECLARE
    t          RECORD;
    overridden INTEGER;
    total      INTEGER := 0;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        SELECT count(*) INTO overridden
        FROM recipes
        WHERE tenant_id = t.id
          AND sattvic_override_reason IS NOT NULL;

        total := total + overridden;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);

    RAISE NOTICE 'V98: % recipe(s) carried a sattvic override reason; the reason text goes with '
        'the column, and each recipe is otherwise untouched.', total;
END $$;

-- The ingredient flag. Everything that read it is gone (T-050); the Ekadashi
-- flag beside it is deliberately NOT touched — it is the surviving dietary
-- rule and the planner still filters a fasting day on it.
ALTER TABLE ingredients DROP COLUMN is_sattvic_prohibited;

-- The recipe's half of the same feature.
ALTER TABLE recipes DROP COLUMN sattvic_override_reason;

COMMENT ON COLUMN ingredients.is_ekadashi_prohibited IS
    'Grain/bean/flour not eaten on Ekadashi (E4-S6). Set by a Temple Admin, and now the only '
    'dietary flag an ingredient carries: the sattvic-prohibited flag it was modelled on was '
    'dropped in V98 (D-18). Import creates ingredients with this false, so a catalogue built by '
    'import is unclassified until somebody sets them — the warning on the Recipes page says so.';
