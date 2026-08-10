-- =====================================================================
-- V9 — Platform-level audit log (E1-S14)
--
-- audit_events (V3) is tenant-owned: tenant_id is NOT NULL, and every read is
-- scoped to a temple. Platform-operator actions belong to no temple — the first
-- being a super-admin binding their Firebase identity on first sign-in (E1-S13) —
-- so they have nowhere to go there. This is their home: the same shape as
-- audit_events minus tenant_id, append-only, readable and writable only by a
-- verified super-admin.
--
-- Isolation here is by role, not tenant. A temple user must never see platform
-- audit; only a super-admin may. The policy answers "is the caller a super-admin"
-- from their own users row, reached through the app.auth_uid read escape (V2/V4)
-- — the same verified identity RLS already trusts at sign-in. No BYPASSRLS and no
-- cross-tenant feed, consistent with why a super-admin reads a temple's own audit
-- only by drilling into that tenant (E1-S7).
-- =====================================================================

CREATE TABLE platform_audit_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Who did it. RESTRICT because users are disabled, never deleted (E1-S12).
    actor_user_id   UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,

    -- Denormalised actor identity captured at write time, as in audit_events.
    actor_label     TEXT        NOT NULL,

    -- Vocabulary lives in AuditAction.java / AuditEntityType.java, not a CHECK.
    action          TEXT        NOT NULL,
    entity_type     TEXT        NOT NULL,
    entity_id       UUID        NOT NULL,

    before_state    JSONB,
    after_state     JSONB,
    reason          TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT platform_audit_events_action_present CHECK (length(action) > 0)
);

COMMENT ON TABLE platform_audit_events IS
    'Append-only record of platform-operator (tenantless) actions. Written only by the shared audit kernel; readable only by a super-admin.';

ALTER TABLE platform_audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_audit_events FORCE ROW LEVEL SECURITY;

-- Only a verified super-admin may read or append. "Is the caller a super-admin"
-- is answered from their own users row, visible through the app.auth_uid escape;
-- a temple user's row fails the role test, and an unauthenticated connection has
-- no auth_uid and matches nothing. FORCE ROW LEVEL SECURITY makes this bind even
-- for the table owner.
CREATE POLICY platform_audit_superadmin_read ON platform_audit_events
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.role = 'SUPER_ADMIN'
        )
    );

CREATE POLICY platform_audit_superadmin_insert ON platform_audit_events
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.role = 'SUPER_ADMIN'
        )
    );

-- Immutable once written, like audit_events: the application may read and append,
-- never amend. The default privileges from V1 grant the app SELECT/INSERT on this
-- new table; this takes UPDATE/DELETE back.
SELECT make_append_only('platform_audit_events');

CREATE INDEX platform_audit_events_time
    ON platform_audit_events (created_at DESC, id DESC);
