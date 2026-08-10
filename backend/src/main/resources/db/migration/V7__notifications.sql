-- =====================================================================
-- V7 — Notifications (E1-S10)
--
-- One record per message the system tries to send, and one row per channel it
-- was attempted on. A message is aimed at a user (whose preferred channel and
-- contact we resolve) or at a raw contact with no account (a vendor phone), and
-- is delivered by a background job through the fallback cascade, its status
-- updated by a provider delivery webhook.
--
-- Tenant-owned: a notification belongs to the temple that sent it, so both
-- tables get the standard RLS. One narrow exception, below, mirrors the
-- app.auth_uid / app.claim_contact escapes: the delivery webhook is
-- unauthenticated and identifies a message by the provider's id before any
-- tenant is known, so it needs a way to see that one row.
-- =====================================================================

CREATE TABLE notifications (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- The user this is for, when there is one. Null for a raw send (a vendor).
    recipient_user_id   UUID        REFERENCES users(id) ON DELETE RESTRICT,
    -- Who it was for, captured legibly at send time (a name, or "Vendor +91…").
    recipient_label     TEXT        NOT NULL,

    -- Resolved delivery addresses. For a user these come from their account; for
    -- a raw send they are supplied directly.
    to_phone            TEXT,
    to_email            TEXT,

    template            TEXT        NOT NULL,
    params              JSONB,

    -- The channel to try first (user preference or an explicit override).
    preferred_channel   TEXT        NOT NULL,

    status              TEXT        NOT NULL DEFAULT 'PENDING',
    -- Which channel actually delivered it, once one did.
    final_channel       TEXT,
    -- The id the sending provider returned; what a delivery webhook is keyed on.
    provider_message_id TEXT,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT notifications_preferred_channel_valid
        CHECK (preferred_channel IN ('WHATSAPP', 'SMS', 'EMAIL')),
    CONSTRAINT notifications_final_channel_valid
        CHECK (final_channel IS NULL OR final_channel IN ('WHATSAPP', 'SMS', 'EMAIL')),
    CONSTRAINT notifications_status_valid
        CHECK (status IN ('PENDING', 'SENT', 'DELIVERED', 'FAILED', 'SUPPRESSED')),
    -- A message must have somewhere to go.
    CONSTRAINT notifications_has_an_address
        CHECK (to_phone IS NOT NULL OR to_email IS NOT NULL)
);

COMMENT ON TABLE notifications IS 'One record per message the system attempts to send. Tenant-owned.';
COMMENT ON COLUMN notifications.status IS 'PENDING → SENT → DELIVERED, or FAILED (all channels failed) / SUPPRESSED (recipient had not consented).';

CREATE TABLE notification_attempts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id     UUID        NOT NULL REFERENCES notifications(id) ON DELETE RESTRICT,
    -- Denormalised so this table carries the standard tenant policy on its own.
    tenant_id           UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    channel             TEXT        NOT NULL,
    outcome             TEXT        NOT NULL,
    provider_message_id TEXT,
    detail              TEXT,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT notification_attempts_channel_valid
        CHECK (channel IN ('WHATSAPP', 'SMS', 'EMAIL')),
    CONSTRAINT notification_attempts_outcome_valid
        CHECK (outcome IN ('SENT', 'FAILED', 'SKIPPED'))
);

COMMENT ON TABLE notification_attempts IS 'One row per channel a notification was tried on — the record of the fallback cascade.';

-- Viewer / webhook / ops indexes.
CREATE INDEX notifications_tenant_time ON notifications (tenant_id, created_at DESC, id DESC);
CREATE INDEX notifications_provider_message_id
    ON notifications (provider_message_id) WHERE provider_message_id IS NOT NULL;
CREATE INDEX notification_attempts_notification ON notification_attempts (notification_id);

-- Standard tenant isolation on both tables.
SELECT enable_tenant_rls('notifications');
SELECT enable_tenant_rls('notification_attempts');

-- The one exception: the delivery webhook runs unauthenticated, before any tenant
-- is known, and must find the single message a provider id belongs to. This adds
-- a SELECT-only escape keyed on app.webhook_message_id — set by the webhook
-- handler alone, only to the id from a signature-verified payload. It exposes at
-- most the one matching row; the handler reads that row's tenant, establishes it
-- as context, and performs the status UPDATE through ordinary tenant isolation
-- (the FOR ALL policy above is unchanged — no write escape).
CREATE POLICY notification_webhook_lookup ON notifications
    FOR SELECT
    USING (
        provider_message_id IS NOT NULL
        AND provider_message_id = NULLIF(current_setting('app.webhook_message_id', true), '')
    );
