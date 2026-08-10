-- =====================================================================
-- V13 — Recipe translation (E2-S6)
--
-- Two tables. A per-tenant glossary of preferred translations for culinary
-- terms — consulted before machine translation, because MT mangles ingredient
-- names ("Toor Dal" -> a transliteration, not तूर दाल). And a cache of translated
-- recipes, keyed on the recipe's version so an edit (which bumps the version)
-- silently invalidates stale translations.
-- =====================================================================

CREATE TABLE translation_glossary (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- BCP-47-ish language code the override is for (e.g. 'hi', 'kn', 'te').
    language      TEXT        NOT NULL,
    source_term   TEXT        NOT NULL,
    target_term   TEXT        NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT translation_glossary_terms_present CHECK (
        length(source_term) > 0 AND length(target_term) > 0)
);

-- One override per (language, term) per temple; also the lookup index.
CREATE UNIQUE INDEX translation_glossary_term
    ON translation_glossary (tenant_id, language, lower(source_term));

SELECT enable_tenant_rls('translation_glossary');

-- ---------------------------------------------------------------------

CREATE TABLE recipe_translations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    recipe_id       UUID        NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    -- The recipe version this translation was made from; a later edit bumps the
    -- recipe's version, so its old translations are simply never looked up again.
    recipe_version  INTEGER     NOT NULL,
    language        TEXT        NOT NULL,

    -- Structured translated content: name, category, ingredient names (in line
    -- order — quantities and units are never translated), and method steps.
    content         JSONB       NOT NULL,

    -- Which engine produced it: 'google', 'bhashini', 'stub', or 'glossary' when
    -- every field came from the glossary. Provenance for the outage/fallback case.
    provider        TEXT        NOT NULL,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT recipe_translations_language_present CHECK (length(language) > 0)
);

CREATE UNIQUE INDEX recipe_translations_key
    ON recipe_translations (tenant_id, recipe_id, recipe_version, language);

SELECT enable_tenant_rls('recipe_translations');
