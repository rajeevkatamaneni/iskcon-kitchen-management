-- =====================================================================
-- V36 — One-off shift broadcasts + tenant settings (E6-S7)
--
-- A shift poster can blast an immediate free-text update to everyone signed up
-- (optionally the waitlist too). Each broadcast is recorded with its author and
-- content, and one row per recipient links to the notification so the roster
-- shows per-person delivery status. A per-tenant daily cap (default 3) protects
-- volunteers from a panicking poster; it lives in tenant_settings, the first of a
-- small per-tenant configuration store.
-- =====================================================================

CREATE TABLE tenant_settings (
    tenant_id                       UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE RESTRICT,
    volunteer_broadcast_daily_limit INTEGER     NOT NULL DEFAULT 3,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT tenant_settings_broadcast_limit_positive CHECK (volunteer_broadcast_daily_limit > 0)
);
SELECT enable_tenant_rls('tenant_settings');

CREATE TABLE shift_broadcasts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    shift_id         UUID        NOT NULL REFERENCES shifts(id) ON DELETE CASCADE,
    message          TEXT        NOT NULL,
    include_waitlist BOOLEAN     NOT NULL DEFAULT false,

    sent_by          UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX shift_broadcasts_by_shift ON shift_broadcasts (tenant_id, shift_id, created_at);
SELECT enable_tenant_rls('shift_broadcasts');
SELECT make_append_only('shift_broadcasts'); -- a broadcast is a fact, never edited

CREATE TABLE shift_broadcast_recipients (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    broadcast_id      UUID        NOT NULL REFERENCES shift_broadcasts(id) ON DELETE CASCADE,
    recipient_user_id UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    notification_id   UUID        REFERENCES notifications(id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX shift_broadcast_recipients_by_broadcast ON shift_broadcast_recipients (broadcast_id);
SELECT enable_tenant_rls('shift_broadcast_recipients');
