-- =====================================================================
-- V2 — Users, roles, and communication preferences (E1-S4, E1-S5, E1-S8)
--
-- Identity lives in Firebase; this table is the application's own record of
-- who a verified Firebase user is *here* — which temple they belong to,
-- what they may do, and how they want to be contacted.
--
-- Firebase is deliberately not the source of truth for authorisation. A
-- valid Firebase token proves someone controls an email or phone number;
-- it says nothing about whether they are a Temple Admin. Keeping role and
-- tenant here means access can be revoked by us, immediately, without
-- depending on an external service.
-- =====================================================================

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Null for the platform super-admin, who belongs to no single temple.
    -- Every other user belongs to exactly one (REQUIREMENTS.md §2).
    tenant_id       UUID REFERENCES tenants(id) ON DELETE RESTRICT,

    firebase_uid    TEXT        NOT NULL UNIQUE,

    full_name       TEXT        NOT NULL,

    -- Both are required at account creation per the locked requirement: the
    -- user picks a preferred channel, and we cannot offer a choice we have
    -- no address for. Phone is E.164; email lowercased for stable matching.
    email           TEXT        NOT NULL,
    phone           TEXT        NOT NULL,

    role            TEXT        NOT NULL,

    -- WhatsApp rides the phone number. Default reflects the India-first
    -- decision in REQUIREMENTS.md, but every user may change it.
    preferred_channel TEXT      NOT NULL DEFAULT 'WHATSAPP',

    -- DPDP: consent is recorded at the moment contact details are collected,
    -- not assumed. Null means not yet given.
    contact_consent_at TIMESTAMPTZ,

    status          TEXT        NOT NULL DEFAULT 'ACTIVE',

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT users_role_valid CHECK (
        role IN ('SUPER_ADMIN', 'TEMPLE_ADMIN', 'KITCHEN_STAFF', 'VOLUNTEER')),

    CONSTRAINT users_status_valid CHECK (
        status IN ('ACTIVE', 'DISABLED')),

    CONSTRAINT users_channel_valid CHECK (
        preferred_channel IN ('WHATSAPP', 'SMS', 'EMAIL')),

    CONSTRAINT users_email_format CHECK (email = lower(email) AND email LIKE '%@%'),

    -- E.164: leading +, country code, up to 15 digits total.
    CONSTRAINT users_phone_format CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),

    -- The super-admin is the only tenantless user; everyone else must have a
    -- temple. Enforced here rather than in application code so it cannot be
    -- bypassed by a future code path that forgets to check.
    CONSTRAINT users_tenant_matches_role CHECK (
        (role = 'SUPER_ADMIN' AND tenant_id IS NULL)
        OR (role <> 'SUPER_ADMIN' AND tenant_id IS NOT NULL))
);

-- A person may hold an account at more than one temple (a devotee who
-- volunteers at two), but only one per temple.
CREATE UNIQUE INDEX users_email_per_tenant ON users (tenant_id, email)
    WHERE tenant_id IS NOT NULL;

CREATE INDEX users_tenant_role ON users (tenant_id, role);

COMMENT ON TABLE  users IS 'Application-side identity. Firebase authenticates; this table authorises.';
COMMENT ON COLUMN users.firebase_uid IS 'Subject of the verified Firebase ID token.';
COMMENT ON COLUMN users.status IS 'DISABLED blocks access regardless of a still-valid Firebase token.';

-- ---------------------------------------------------------------------
-- Users are tenant-owned data, with one deliberate exception: the platform
-- super-admin has no tenant, so RLS as written would hide them from
-- themselves. Super-admin operations run through a dedicated audited code
-- path (SYSTEM_DESIGN.md §3) rather than ordinary tenant-scoped queries.
-- ---------------------------------------------------------------------
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;

-- Reading a user is subject to normal tenant isolation, with one narrowly
-- drawn exception.
--
-- The exception exists to break a genuine chicken-and-egg: at sign-in we hold
-- a verified Firebase UID but do not yet know which temple the person belongs
-- to — and the record that would tell us is itself tenant-scoped. Without an
-- escape, nobody could ever log in.
--
-- The escape matches on the UID rather than disabling isolation, so it exposes
-- exactly one row, and only to a caller who already holds a token that Google
-- verified for that precise UID. It cannot enumerate users, cannot widen to
-- other rows, and applies to no other table. app.auth_uid is set by the
-- authentication filter alone and cleared when the connection is returned.
CREATE POLICY tenant_isolation ON users
    FOR SELECT
    USING (
        tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
    );

-- Writes get no such exception: a user may only be created or modified within
-- an established tenant context.
CREATE POLICY tenant_isolation_write ON users
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kms_app') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON users TO kms_app';
    END IF;
END
$$;
