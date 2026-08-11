-- =====================================================================
-- V37 — Payment webhook event store (E7-S9)
--
-- Every Razorpay webhook lands here first, deduplicated by the provider's event
-- id, before any donation state moves. This is the spine the donation stories
-- (E7-S2/S3/S6) hang their handlers on: signature-verified at the edge, stored
-- once, dispatched to handlers, and parked as a dead letter if a handler can't
-- process it — never lost, always replayable.
--
-- Platform-scoped, not tenant-scoped: a webhook arrives unauthenticated and
-- account-global, before any tenant is known (the handler resolves the tenant
-- from the local record the event references). Like platform_audit_events, it
-- carries no RLS; only the webhook handler writes it and only super-admin ops
-- reads it — no tenant-facing endpoint touches this table.
-- =====================================================================

CREATE TABLE payment_events (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider           TEXT        NOT NULL DEFAULT 'razorpay',

    -- The provider's own event id — the dedup key. Razorpay retries deliver the
    -- same id, so the unique index below turns a replay into a no-op.
    provider_event_id  TEXT        NOT NULL,
    event_type         TEXT        NOT NULL,
    payload            JSONB       NOT NULL,

    status             TEXT        NOT NULL DEFAULT 'RECEIVED',
    -- The tenant the event resolved to, once a handler figured it out; null for
    -- an unrecognised or not-yet-processed event.
    tenant_id          UUID        REFERENCES tenants(id) ON DELETE RESTRICT,
    error              TEXT,
    attempts           INTEGER     NOT NULL DEFAULT 0,

    received_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at       TIMESTAMPTZ,

    CONSTRAINT payment_events_status_valid
        CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'DEAD_LETTER'))
);

CREATE UNIQUE INDEX payment_events_provider_event ON payment_events (provider, provider_event_id);
CREATE INDEX payment_events_status ON payment_events (status, received_at);
