-- =====================================================================
-- V34 — Volunteer shifts, signups, and waitlist (E6-S2 … S6)
--
-- A shift is a seva need for a date and time window with a capacity and its own
-- reminder offsets. Creation is publication — volunteers see it at once; there is
-- no draft step at this scale. This migration lays down the shift plus the two
-- rosters it accumulates: signups (E6-S3) and a waitlist (E6-S5). The behaviour —
-- atomic capacity claim, release, FIFO promotion, reminders — is added by the
-- later stories; the shape is here so capacity counts read correctly from the start.
-- =====================================================================

CREATE TABLE shifts (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    title                   TEXT        NOT NULL,
    description             TEXT,
    shift_date              DATE        NOT NULL,
    start_time              TIME        NOT NULL,
    end_time                TIME        NOT NULL,
    location                TEXT,
    capacity                INTEGER     NOT NULL,

    -- Minutes-before-start at which to remind (E6-S2/S6). Default a single 24h reminder.
    reminder_offsets_minutes JSONB      NOT NULL DEFAULT '[1440]',

    status                  TEXT        NOT NULL DEFAULT 'OPEN',
    cancel_reason           TEXT,
    cancelled_at            TIMESTAMPTZ,

    created_by              UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT shifts_capacity_positive CHECK (capacity > 0),
    CONSTRAINT shifts_time_window CHECK (end_time > start_time),
    CONSTRAINT shifts_status_valid CHECK (status IN ('OPEN', 'CANCELLED'))
);

CREATE INDEX shifts_tenant_date ON shifts (tenant_id, shift_date, start_time);
SELECT enable_tenant_rls('shifts');

-- One row per volunteer signed up for a shift. A release sets released_at rather
-- than deleting, so the roster keeps release history (E6-S4); the partial unique
-- index below allows re-signing up after a release while forbidding a double
-- active signup.
CREATE TABLE shift_signups (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    shift_id          UUID        NOT NULL REFERENCES shifts(id) ON DELETE CASCADE,
    volunteer_user_id UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,

    signed_up_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at       TIMESTAMPTZ,
    -- How the signup came to be: a direct signup, or an auto-promotion from the waitlist (E6-S5).
    source            TEXT        NOT NULL DEFAULT 'SIGNUP',

    CONSTRAINT shift_signups_source_valid CHECK (source IN ('SIGNUP', 'PROMOTION'))
);

CREATE UNIQUE INDEX shift_signups_one_active
    ON shift_signups (tenant_id, shift_id, volunteer_user_id) WHERE released_at IS NULL;
CREATE INDEX shift_signups_by_shift ON shift_signups (tenant_id, shift_id);
SELECT enable_tenant_rls('shift_signups');

-- The FIFO waitlist for a full shift (E6-S5). Active = not yet promoted and not left;
-- position is derived from joined_at order over the active rows, so nothing has to be
-- renumbered when one leaves or is promoted.
CREATE TABLE shift_waitlist (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    shift_id          UUID        NOT NULL REFERENCES shifts(id) ON DELETE CASCADE,
    volunteer_user_id UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,

    joined_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    promoted_at       TIMESTAMPTZ,
    left_at           TIMESTAMPTZ
);

CREATE UNIQUE INDEX shift_waitlist_one_active
    ON shift_waitlist (tenant_id, shift_id, volunteer_user_id)
    WHERE promoted_at IS NULL AND left_at IS NULL;
CREATE INDEX shift_waitlist_by_shift ON shift_waitlist (tenant_id, shift_id, joined_at);
SELECT enable_tenant_rls('shift_waitlist');
