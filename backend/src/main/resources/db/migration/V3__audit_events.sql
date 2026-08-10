-- =====================================================================
-- V3 — Audit log framework (E1-S7)
--
-- An append-only record of the actions that have to be explainable later:
-- role changes, tenant provisioning, and — as later epics wire in — sattvic
-- and calendar overrides, inventory adjustments, and payments.
--
-- Two properties define this table, and both are enforced by the database
-- rather than by the code that writes to it:
--
--   1. Tenant-owned. It carries tenant_id and gets the same RLS policy as
--      every other tenant table (enable_tenant_rls). A Temple Admin sees
--      only their own temple's history; the platform super-admin, holding no
--      BYPASSRLS, reads a temple's log only by drilling into that tenant's
--      context — never a cross-tenant feed. That uniformity is deliberate:
--      audit before/after values carry donation amounts and payment records,
--      so a cross-tenant feed would be a side door around the RBAC that
--      denies the super-admin those very permissions (E1-S5).
--
--   2. Append-only. Once written, a row cannot be changed or removed by the
--      application. See make_append_only below for why this needs an explicit
--      revoke rather than being the default.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Convention helper: append-only tables.
--
-- Companion to enable_tenant_rls. A table declares itself append-only by
-- calling this, exactly as it declares itself tenant-owned by calling that.
--
-- Why a revoke is needed at all: V1 runs
--     ALTER DEFAULT PRIVILEGES ... GRANT SELECT, INSERT, UPDATE, DELETE
--         ON TABLES TO kms_app
-- so every new table hands the application UPDATE and DELETE automatically.
-- Append-only is therefore not something a table gets by saying nothing — it
-- is something it must take back. Making it a named function means the
-- guarantee is declared where the table is defined, not left to a developer
-- to remember. audit_events (here) and stock_movements (E3-S2) both need it.
--
-- Guarded on the role's existence so the migration also runs against a
-- Testcontainers database created before kms_app exists, matching the grant
-- blocks in V1/V2.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION make_append_only(target_table TEXT)
RETURNS VOID AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kms_app') THEN
        EXECUTE format('REVOKE UPDATE, DELETE ON %I FROM kms_app', target_table);
    END IF;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION make_append_only(TEXT) IS
    'Revokes UPDATE and DELETE from the application role. Every append-only table must call this; the default privileges in V1 grant them back otherwise.';

-- ---------------------------------------------------------------------
-- The audit table.
-- ---------------------------------------------------------------------
CREATE TABLE audit_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Whose history this belongs to. For provisioning the actor is the
    -- super-admin, but the event belongs to the temple being created — the
    -- actor's tenancy does not decide whose event it is.
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- Who did it. RESTRICT because users are disabled, never deleted
    -- (E1-S12) — an audit trail that can lose its actor is not a trail.
    -- The FK resolves across tenants (the provisioning super-admin has no
    -- tenant); FK checks run as the table owner and are not subject to RLS.
    actor_user_id   UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,

    -- Who the actor was, captured at write time. Redundant with actor_user_id
    -- on purpose: it keeps the row legible on its own and survives a later
    -- rename, so reading the log never has to resolve a foreign key that RLS
    -- would in any case hide when a super-admin actor sits in no tenant.
    actor_label     TEXT        NOT NULL,

    -- The vocabulary lives in AuditAction.java, not in a CHECK constraint:
    -- every epic adds actions, and a constraint here would turn each addition
    -- into a migration. Kept as free text, validated in the writing kernel.
    action          TEXT        NOT NULL,

    entity_type     TEXT        NOT NULL,
    entity_id       UUID        NOT NULL,

    -- Null before on a creation; null after on a pure access record (a
    -- super-admin viewing a log changes no state). Both null is legitimate.
    before_state    JSONB,
    after_state     JSONB,

    -- Optional human context: why an override was made, or why a role change
    -- was refused.
    reason          TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT audit_events_action_present CHECK (length(action) > 0)
);

COMMENT ON TABLE  audit_events IS 'Append-only, tenant-owned record of sensitive actions. Written only by the shared audit kernel.';
COMMENT ON COLUMN audit_events.actor_label IS 'Denormalised actor identity captured at write time; keeps the row legible without resolving a possibly-RLS-hidden user row.';
COMMENT ON COLUMN audit_events.before_state IS 'State prior to the action, JSONB. Null for a creation.';
COMMENT ON COLUMN audit_events.after_state  IS 'State after the action, JSONB. Null for a pure access record.';

-- Same tenant isolation as every other tenant-owned table.
SELECT enable_tenant_rls('audit_events');

-- Immutable once written: the application may read and append, never amend.
SELECT make_append_only('audit_events');

-- ---------------------------------------------------------------------
-- Viewer indexes. Every query is tenant-scoped (RLS), ordered newest-first,
-- and optionally narrowed by action or actor — the filters the E1-S7 viewer
-- offers. Leading with tenant_id matches how RLS shapes every read.
--
-- (created_at DESC, id DESC) is the sort and the keyset-pagination cursor:
-- id breaks ties so two events in the same instant paginate deterministically.
-- ---------------------------------------------------------------------
CREATE INDEX audit_events_tenant_time
    ON audit_events (tenant_id, created_at DESC, id DESC);

CREATE INDEX audit_events_tenant_action_time
    ON audit_events (tenant_id, action, created_at DESC, id DESC);

CREATE INDEX audit_events_tenant_actor_time
    ON audit_events (tenant_id, actor_user_id, created_at DESC, id DESC);
