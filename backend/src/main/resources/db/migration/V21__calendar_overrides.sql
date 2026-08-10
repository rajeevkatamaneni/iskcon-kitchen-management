-- =====================================================================
-- V21 — Admin calendar override (E4-S3)
--
-- A safety net over the computed calendar (V19): a Temple Admin can correct an
-- individual date for an astronomical edge case (adhika/ksaya masa) or a local
-- GBC ruling, without anyone having to work around the system.
--
-- An override is a per-tenant, per-date row that SHADOWS the computed row for
-- all consumers. It lives in its own table, so the nightly recompute — which
-- only writes calendar_days — never touches it: overrides survive recomputes by
-- construction. Removing the row reverts to computed truth. Every override
-- carries a mandatory reason and is audited.
-- =====================================================================

CREATE TABLE calendar_overrides (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    cal_date        DATE        NOT NULL,

    -- The corrected fields that shadow the computed day. is_ekadashi is the primary
    -- one (does the temple fast this day); tithi and a festival note are optional.
    is_ekadashi     BOOLEAN     NOT NULL,
    ekadashi_name   TEXT,
    tithi           SMALLINT,
    festival_note   TEXT,

    reason          TEXT        NOT NULL,

    created_by      UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT calendar_overrides_reason_present CHECK (length(reason) > 0),
    CONSTRAINT calendar_overrides_tithi_range CHECK (tithi IS NULL OR tithi BETWEEN 0 AND 29)
);

COMMENT ON TABLE calendar_overrides IS
    'Per-date admin corrections shadowing calendar_days (E4-S3). Survive recompute; removing reverts to computed.';

CREATE UNIQUE INDEX calendar_overrides_tenant_date ON calendar_overrides (tenant_id, cal_date);

SELECT enable_tenant_rls('calendar_overrides');
