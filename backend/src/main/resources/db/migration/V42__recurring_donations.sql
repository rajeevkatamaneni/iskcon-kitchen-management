-- =====================================================================
-- V42 — Recurring donations (E7-S3)
--
-- A donor-chosen frequency mapped to a provider subscription/mandate. Recurring
-- requires an account — a mandate needs a persistent identity — so a plan links
-- to a user. Each cycle's charge webhook creates a COMPLETED donation of type
-- RECURRING attached to the plan, carrying the donor snapshot the plan captured
-- (so each cycle is 80G-shaped on its own). The plan's status mirrors the
-- provider's (ACTIVE / HALTED / CANCELLED).
-- =====================================================================

CREATE TABLE recurring_plans (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    donor_account_user_id  UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    frequency              TEXT        NOT NULL,
    amount_inr             NUMERIC(12, 2) NOT NULL,

    provider               TEXT        NOT NULL,
    provider_subscription_id TEXT,
    status                 TEXT        NOT NULL DEFAULT 'ACTIVE',

    -- Donor snapshot copied onto each cycle's donation.
    donor_name             TEXT,
    donor_phone            TEXT,
    donor_email            TEXT,
    donor_address          TEXT,
    donor_pan_ciphertext   BYTEA,
    wants_80g              BOOLEAN     NOT NULL DEFAULT false,
    section                TEXT,
    consent_at             TIMESTAMPTZ,

    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT recurring_frequency_valid CHECK (frequency IN ('WEEKLY', 'MONTHLY', 'QUARTERLY', 'ANNUALLY')),
    CONSTRAINT recurring_status_valid CHECK (status IN ('ACTIVE', 'HALTED', 'CANCELLED')),
    CONSTRAINT recurring_amount_positive CHECK (amount_inr > 0)
);

CREATE INDEX recurring_plans_by_donor ON recurring_plans (tenant_id, donor_account_user_id);
CREATE UNIQUE INDEX recurring_plans_subscription
    ON recurring_plans (provider_subscription_id) WHERE provider_subscription_id IS NOT NULL;
SELECT enable_tenant_rls('recurring_plans');

ALTER TABLE donations ADD CONSTRAINT donations_recurring_plan_fk
    FOREIGN KEY (recurring_plan_id) REFERENCES recurring_plans(id) ON DELETE SET NULL;

-- The same webhook lookup escape (V7/V39): a subscription event carries the subscription id, and the
-- handler finds the plan by it before any tenant is known, via the app.webhook_message_id session var.
CREATE POLICY recurring_plan_webhook_lookup ON recurring_plans
    FOR SELECT
    USING (
        provider_subscription_id IS NOT NULL
        AND provider_subscription_id = NULLIF(current_setting('app.webhook_message_id', true), ''));
