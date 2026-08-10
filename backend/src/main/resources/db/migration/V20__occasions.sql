-- =====================================================================
-- V20 — Festival occasion catalog (E4-S2)
--
-- Named festival occasions, so planning "Janmashtami" carries meaning — an
-- expected scale, a name on the calendar — not just a date with a generic flag.
--
-- Two kinds. A COMPUTED occasion is astronomical: it resolves to whatever dates
-- the calendar engine (V19) marks with a matching festival, via match_text. A
-- MANUAL occasion is local — a temple anniversary — recurring on a fixed month
-- and day the temple sets. Occasions are seeded on provisioning and extended by
-- the temple; they surface on the planner and drive the festival day-type on
-- meal plans (E4-S4).
-- =====================================================================

CREATE TABLE occasions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    name            TEXT        NOT NULL,
    type            TEXT        NOT NULL,

    -- COMPUTED: a substring matched against a calendar day's festival texts.
    match_text      TEXT,
    -- MANUAL: the recurring month/day the occasion falls on each year.
    fixed_month     SMALLINT,
    fixed_day       SMALLINT,

    -- The temple's default expected servings for this occasion (tenant-editable;
    -- learns nothing automatically in release 1). Pre-fills a festival meal plan.
    default_servings INTEGER,
    notes           TEXT,

    -- Seeded on provisioning vs created by the temple. Seeded rows may be edited
    -- but are recreated for new temples; this just records provenance.
    seeded          BOOLEAN     NOT NULL DEFAULT false,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT occasions_name_present CHECK (length(name) > 0),
    CONSTRAINT occasions_type_valid CHECK (type IN ('COMPUTED', 'MANUAL')),
    CONSTRAINT occasions_computed_has_match CHECK (type <> 'COMPUTED' OR match_text IS NOT NULL),
    CONSTRAINT occasions_manual_has_date CHECK (
        type <> 'MANUAL' OR (fixed_month BETWEEN 1 AND 12 AND fixed_day BETWEEN 1 AND 31)),
    CONSTRAINT occasions_servings_nonneg CHECK (default_servings IS NULL OR default_servings >= 0)
);

COMMENT ON TABLE occasions IS
    'Named festival occasions (E4-S2): COMPUTED ones resolve via the calendar engine, MANUAL ones by fixed date.';

CREATE UNIQUE INDEX occasions_name_per_tenant ON occasions (tenant_id, lower(name));

SELECT enable_tenant_rls('occasions');
