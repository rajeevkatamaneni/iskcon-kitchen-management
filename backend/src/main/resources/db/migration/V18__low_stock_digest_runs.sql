-- =====================================================================
-- V18 — Low-stock digest run log (E3-S3)
--
-- One row per tenant per day the low-stock digest was sent. It exists purely to
-- make the nightly job idempotent: the job claims the day here before sending,
-- so a retry — or a second sweep on the same day — finds the day already taken
-- and sends nothing rather than a duplicate digest. (KmsJob requires every job
-- to be safe to run more than once; this is how this one keeps that promise.)
-- =====================================================================

CREATE TABLE low_stock_digest_runs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    digest_date DATE        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT low_stock_digest_once_per_day UNIQUE (tenant_id, digest_date)
);

SELECT enable_tenant_rls('low_stock_digest_runs');
