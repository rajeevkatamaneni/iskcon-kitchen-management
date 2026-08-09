-- =====================================================================
-- V1 — Tenancy foundation (E1-S3)
--
-- Establishes the multi-tenancy model from SYSTEM_DESIGN.md §3: a shared
-- schema where every tenant-owned table carries tenant_id and is protected
-- by PostgreSQL Row-Level Security.
--
-- The security property this buys: a bug in application code — a forgotten
-- WHERE clause, a wrong parameter — cannot leak another temple's data,
-- because the database itself refuses to return the rows. Isolation does
-- not depend on developers remembering to filter.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Tenants. Deliberately NOT row-level-secured: this is the registry that
-- defines tenants, read by the platform super-admin and by tenant
-- resolution before any tenant context exists. Access is controlled at
-- the application layer (SUPER_ADMIN only).
-- ---------------------------------------------------------------------
CREATE TABLE tenants (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug                TEXT        NOT NULL UNIQUE,
    name                TEXT        NOT NULL,
    address             TEXT,

    -- Required by the Vaishnava calendar engine (E4-S1): tithi is computed
    -- at local sunrise, so each tenant needs its own coordinates. Not optional.
    latitude            NUMERIC(9, 6)  NOT NULL,
    longitude           NUMERIC(9, 6)  NOT NULL,
    timezone            TEXT           NOT NULL,

    currency            CHAR(3)     NOT NULL DEFAULT 'INR',
    locale              TEXT        NOT NULL DEFAULT 'en-IN',

    -- Drives whether the 80G donor-data capture path is offered (E7-S4).
    is_80g_approved     BOOLEAN     NOT NULL DEFAULT FALSE,

    status              TEXT        NOT NULL DEFAULT 'ACTIVE',

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT tenants_slug_format     CHECK (slug ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'),
    CONSTRAINT tenants_status_valid    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT tenants_latitude_range  CHECK (latitude  BETWEEN -90  AND 90),
    CONSTRAINT tenants_longitude_range CHECK (longitude BETWEEN -180 AND 180)
);

COMMENT ON TABLE  tenants IS 'Temple tenants. Not RLS-protected: this is the tenant registry itself.';
COMMENT ON COLUMN tenants.latitude  IS 'Required for location-accurate Vaishnava calendar computation.';
COMMENT ON COLUMN tenants.longitude IS 'Required for location-accurate Vaishnava calendar computation.';

-- ---------------------------------------------------------------------
-- Convention helper.
--
-- Every future tenant-owned table calls enable_tenant_rls('table_name').
-- Centralising it means the policy is identical everywhere and a new
-- table cannot accidentally get a subtly different rule.
--
-- current_setting(..., true) returns NULL rather than erroring when the
-- GUC is unset. NULL = tenant_id evaluates to NULL, so the row is filtered
-- out — the system fails CLOSED. An unconfigured connection sees nothing,
-- which is the correct default for a security control.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION enable_tenant_rls(target_table TEXT)
RETURNS VOID AS $$
BEGIN
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', target_table);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', target_table);

    EXECUTE format(
        'CREATE POLICY tenant_isolation ON %I
             USING (tenant_id = current_setting(''app.tenant_id'', true)::uuid)
             WITH CHECK (tenant_id = current_setting(''app.tenant_id'', true)::uuid)',
        target_table);
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION enable_tenant_rls(TEXT) IS
    'Applies the standard tenant isolation policy. Every tenant-owned table must call this.';

-- ---------------------------------------------------------------------
-- Application role privileges.
--
-- SYSTEM_DESIGN.md §3 requires the application role to hold neither DDL
-- nor BYPASSRLS. FORCE ROW LEVEL SECURITY above matters because a table's
-- owner is otherwise exempt from its own policies — without FORCE, an
-- owning role would silently see every tenant's rows.
--
-- Guarded so the migration also runs against a Testcontainers database
-- where the kms_app role does not exist.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kms_app') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA public TO kms_app';
        EXECUTE 'GRANT EXECUTE ON FUNCTION enable_tenant_rls(TEXT) TO kms_app';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO kms_app';
        EXECUTE 'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO kms_app';

        -- Future tables created by the migration role inherit the same grants.
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public
                     GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO kms_app';
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public
                     GRANT USAGE, SELECT ON SEQUENCES TO kms_app';

        -- Explicitly withhold schema modification rights.
        EXECUTE 'REVOKE CREATE ON SCHEMA public FROM kms_app';
    END IF;
END
$$;
