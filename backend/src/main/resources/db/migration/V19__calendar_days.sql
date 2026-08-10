-- =====================================================================
-- V19 — Vaishnava calendar (E4-S1)
--
-- The astronomical calendar, precomputed per tenant and read-only on the
-- request path. Each row is one day at the tenant's own location: its tithi at
-- local sunrise, paksa, lunar month (masa), Gaurabda year, whether it is a
-- fasting Ekadashi (after the Maha-Dvadashi postponement rule), and the day's
-- festivals. The planner and every consumer read these rows; nothing computes
-- astronomy on a request (SYSTEM_DESIGN: compute nightly, serve from rows).
--
-- Values are produced by porting the ISKCON GCAL algorithm to Java and are
-- gated in tests against GCAL's own output for Bengaluru.
-- =====================================================================

CREATE TABLE calendar_days (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    cal_date        DATE        NOT NULL,

    -- Tithi at local sunrise: 0..29 (0..14 waxing / Gaura paksa, 15..29 waning /
    -- Krsna paksa). paksa is derived but stored for cheap filtering.
    tithi           SMALLINT    NOT NULL,
    paksa           SMALLINT    NOT NULL,

    -- Lunar month 0..11; 12 marks an adhika (leap) masa.
    masa            SMALLINT    NOT NULL,
    gaurabda_year   INTEGER,
    naksatra        SMALLINT,

    -- The fasting Ekadashi flag AFTER postponement — the day a devotee actually
    -- fasts, which the Ekadashi violation check (E4-S6) keys on. Not merely
    -- "tithi is Ekadashi".
    is_ekadashi     BOOLEAN     NOT NULL DEFAULT false,
    ekadashi_name   TEXT,
    -- The Maha-Dvadashi variant, when the fast was moved to Dvadashi; null otherwise.
    mahadvadashi    TEXT,
    -- The kind of fast, when any (Ekadashi, or a festival fast like Janmastami/Gaura Purnima).
    fast_type       TEXT,

    sunrise         TIME,
    sunset          TIME,

    -- The day's named festivals, as a JSON array of { text, priority }. Empty on an
    -- ordinary day. The occasion catalog (E4-S2) links named occasions to these.
    festivals       JSONB       NOT NULL DEFAULT '[]',

    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT calendar_days_tithi_range CHECK (tithi BETWEEN 0 AND 29),
    CONSTRAINT calendar_days_paksa_range CHECK (paksa IN (0, 1)),
    CONSTRAINT calendar_days_masa_range CHECK (masa BETWEEN 0 AND 12)
);

COMMENT ON TABLE calendar_days IS
    'Per-tenant astronomical Vaishnava calendar, precomputed nightly; read-only on the request path (E4-S1).';

-- One row per tenant per date; also the lookup index the planner uses.
CREATE UNIQUE INDEX calendar_days_tenant_date ON calendar_days (tenant_id, cal_date);
CREATE INDEX calendar_days_tenant_ekadashi ON calendar_days (tenant_id, cal_date) WHERE is_ekadashi;

SELECT enable_tenant_rls('calendar_days');

-- The precompute watermark per tenant, for the ops page (E1-S11): when the
-- nightly job last succeeded and how far ahead the calendar now reaches.
CREATE TABLE calendar_precompute_state (
    tenant_id       UUID        PRIMARY KEY REFERENCES tenants(id) ON DELETE RESTRICT,
    last_run_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    computed_through DATE       NOT NULL,
    days_computed   INTEGER     NOT NULL DEFAULT 0
);

SELECT enable_tenant_rls('calendar_precompute_state');
