-- =====================================================================
-- V35 — Shift reminders (E6-S6)
--
-- One row per reminder actually sent for a (signup × offset). The unique key is
-- what makes the send idempotent — a job that fires more than once (retry,
-- recovery) claims the row first and only sends if it won the claim. The link to
-- the notification is how the poster's roster shows per-volunteer delivery status
-- (sent / delivered / failed), since delivery is recorded on the notification by
-- the provider webhook.
-- =====================================================================

CREATE TABLE shift_reminders (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    shift_id        UUID        NOT NULL REFERENCES shifts(id) ON DELETE CASCADE,
    signup_id       UUID        NOT NULL REFERENCES shift_signups(id) ON DELETE CASCADE,
    offset_minutes  INTEGER     NOT NULL,

    -- The message this reminder produced; null only in the brief window between claiming
    -- the row and queuing the notification. Roster delivery status joins through this.
    notification_id UUID        REFERENCES notifications(id) ON DELETE SET NULL,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX shift_reminders_once ON shift_reminders (signup_id, offset_minutes);
CREATE INDEX shift_reminders_by_shift ON shift_reminders (tenant_id, shift_id);
SELECT enable_tenant_rls('shift_reminders');
