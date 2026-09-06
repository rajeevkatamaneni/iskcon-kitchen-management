-- =====================================================================
-- V94 — Clear the recipe translation cache, once
--
-- Rajeev reported from the 2026-09-04 demo that translating "Hot water" into
-- Kannada came back reading "Water Hot". Probing the real Cloud Translation API
-- with both spellings shows the translator was innocent:
--
--     "Hot water"   ->  ಬಿಸಿ ನೀರು    ->  "Hot water"
--     "Water, hot"  ->  ನೀರು, ಬಿಸಿ   ->  "Water, hot"
--
-- The recipe library files a name the way a reference book does, noun first, so
-- that everything about water sorts together — 882 of its 6,333 ingredient names
-- are written that way. Inversion is an English cataloguing device with no
-- meaning in Kannada, so a faithful translation of it reads backwards.
--
-- RecipeTranslationService now un-inverts a name before handing it to the
-- machine (IngredientNames.readable). Nothing about what is *stored* changes:
-- the ingredient master keeps the filed name, so the picker still sorts the
-- three waters together and every lookup that matches a line to an ingredient by
-- name still matches.
--
-- That leaves the cache. It is keyed on (recipe, recipe_version, language,
-- provider), and none of those change when the source wording does — so every
-- translation made before today would go on serving the inverted words until
-- somebody happened to edit the recipe. Emptying it is safe by construction:
-- recipe_translations is a cache, and a miss costs one call to the provider.
--
-- Per tenant, because the table carries RLS (V13) and a migration is subject to
-- it: a bare DELETE runs with app.tenant_id unset, the policy fails closed, and
-- it would remove nothing at all while reporting success.
-- =====================================================================

DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);
        DELETE FROM recipe_translations WHERE tenant_id = t.id;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;
