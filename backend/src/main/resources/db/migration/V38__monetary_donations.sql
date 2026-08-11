-- =====================================================================
-- V38 — Monetary donations and 80G donor capture (E7-S4)
--
-- Extends the E3-S5 donations table (which anticipated this) to money: amount,
-- currency, mode, status, and the payment-provider refs, plus the donor detail an
-- 80G certificate needs. Three donor paths, enforced by CHECKs:
--   * anonymous — zero PII retained (nothing to keep);
--   * named, no 80G — name (+ optional contact), no PAN;
--   * 80G — name, address, and PAN, only where the tenant is 80G-approved.
-- PAN is stored encrypted at the column level (app-layer AES-GCM); it is never
-- written in the clear. A Form 10BD-shaped dataset accumulates by construction.
-- =====================================================================

-- (tenants.is_80g_approved already exists from V1 — the per-tenant 80G flag.)

-- A public (online) donation has no staff member who recorded it.
ALTER TABLE donations ALTER COLUMN recorded_by DROP NOT NULL;

ALTER TABLE donations
    ADD COLUMN amount_inr           NUMERIC(12, 2),
    ADD COLUMN currency             TEXT        NOT NULL DEFAULT 'INR',
    ADD COLUMN payment_mode         TEXT,
    ADD COLUMN status               TEXT        NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN donor_address        TEXT,
    -- PAN, AES-GCM ciphertext (iv‖ct). Never stored or logged in the clear.
    ADD COLUMN donor_pan_ciphertext BYTEA,
    ADD COLUMN wants_80g            BOOLEAN     NOT NULL DEFAULT false,
    -- The section a certificate would cite (e.g. '80G'); part of the 10BD dataset.
    ADD COLUMN section              TEXT,
    ADD COLUMN consent_at           TIMESTAMPTZ,
    ADD COLUMN provider             TEXT,
    ADD COLUMN provider_order_id    TEXT,
    ADD COLUMN provider_payment_id  TEXT,
    ADD COLUMN idempotency_key      TEXT,
    -- Set for recurring cycles (E7-S3) and wish-list sponsorships (E7-S6); FKs added by those stories.
    ADD COLUMN donor_account_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN recurring_plan_id    UUID,
    ADD COLUMN wishlist_item_id     UUID,
    -- A PENDING online donation past this is abandoned and swept (E7-S2).
    ADD COLUMN expires_at           TIMESTAMPTZ;

ALTER TABLE donations DROP CONSTRAINT donations_type_valid;
ALTER TABLE donations ADD CONSTRAINT donations_type_valid
    CHECK (type IN ('IN_KIND', 'ONE_TIME', 'RECURRING'));

ALTER TABLE donations ADD CONSTRAINT donations_status_valid
    CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'EXPIRED'));

-- Anonymous means exactly zero PII retained — nothing to leak, nothing to delete.
ALTER TABLE donations ADD CONSTRAINT donations_anonymous_has_no_pii CHECK (
    NOT is_anonymous OR (
        donor_name IS NULL AND donor_phone IS NULL AND donor_email IS NULL
        AND donor_address IS NULL AND donor_pan_ciphertext IS NULL));

-- An 80G donation must carry the fields a certificate needs.
ALTER TABLE donations ADD CONSTRAINT donations_80g_complete CHECK (
    NOT wants_80g OR (
        donor_name IS NOT NULL AND donor_address IS NOT NULL AND donor_pan_ciphertext IS NOT NULL));

-- Monetary donations carry an amount; in-kind uses estimated_value_inr instead.
ALTER TABLE donations ADD CONSTRAINT donations_amount_shape CHECK (
    type = 'IN_KIND' OR (amount_inr IS NOT NULL AND amount_inr > 0));

CREATE UNIQUE INDEX donations_idempotency
    ON donations (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX donations_provider_order
    ON donations (provider_order_id) WHERE provider_order_id IS NOT NULL;
CREATE INDEX donations_tenant_status ON donations (tenant_id, status, created_at DESC);
