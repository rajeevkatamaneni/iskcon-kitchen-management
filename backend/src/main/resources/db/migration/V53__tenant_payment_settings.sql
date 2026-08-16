-- =====================================================================
-- V53 — A temple's own payment gateway (E7)
--
-- Each temple collects its own donations through its own provider account, so
-- the gateway stops being one platform-wide choice and becomes a property of
-- the temple. What lands here is deliberately only the part that is not secret:
--
--   * the provider, chosen from the ones we have written an adapter for;
--   * the key id, which is not a secret at all — it is handed to the browser
--     as the checkout's public key;
--   * an opaque webhook token, which identifies the temple in the webhook URL
--     and is an identifier rather than a credential: the signature is the
--     security control, never the path;
--   * when the credentials were last proven to work, and when a correctly
--     signed webhook was last actually received, which are different facts and
--     fail in different ways.
--
-- The key secret and the webhook secret are NOT here and never will be. They
-- live in Secret Manager under a name derived from the tenant id, so a dump of
-- this database leaks nothing an attacker can spend, IAM decides who may read
-- them, and every read is logged somewhere this application cannot reach.
-- =====================================================================

ALTER TABLE tenant_settings
    ADD COLUMN payment_provider          TEXT,
    ADD COLUMN payment_key_id            TEXT,
    ADD COLUMN payment_webhook_token     TEXT,
    ADD COLUMN payment_verified_at       TIMESTAMPTZ,
    ADD COLUMN payment_webhook_seen_at   TIMESTAMPTZ,

    -- Only providers with an adapter. A temple cannot be pointed at an arbitrary
    -- endpoint: every gateway differs in auth, order model and webhook signature,
    -- so offering a free-text URL would promise something that fails at the first
    -- real donation.
    ADD CONSTRAINT tenant_settings_payment_provider_valid CHECK (
        payment_provider IS NULL OR payment_provider IN ('RAZORPAY')),

    -- Configured means all three: who, which account, and where their webhooks land.
    ADD CONSTRAINT tenant_settings_payment_shape CHECK (
        payment_provider IS NULL
        OR (payment_key_id IS NOT NULL AND payment_webhook_token IS NOT NULL));

-- One temple per token: the webhook URL must resolve to exactly one temple.
CREATE UNIQUE INDEX tenant_settings_payment_webhook_token
    ON tenant_settings (payment_webhook_token)
    WHERE payment_webhook_token IS NOT NULL;

COMMENT ON COLUMN tenant_settings.payment_key_id IS
    'The provider''s public key id, sent to the browser to open checkout. Not a secret.';
COMMENT ON COLUMN tenant_settings.payment_webhook_token IS
    'Opaque id in this temple''s webhook URL. Identifies which temple to verify against; is not itself trusted.';
COMMENT ON COLUMN tenant_settings.payment_verified_at IS
    'When the stored credentials last answered the provider. Says nothing about whether webhooks arrive.';
COMMENT ON COLUMN tenant_settings.payment_webhook_seen_at IS
    'When a correctly signed webhook was last received. The only proof the return path actually works.';

-- ---------------------------------------------------------------------
-- The webhook's lookup, before any tenant is known
--
-- A payment webhook arrives unauthenticated and cannot be verified until we hold
-- the temple's webhook secret — so unlike the V39 escape, this one necessarily
-- runs BEFORE the signature is checked. That is safe here only because the row
-- holds nothing worth having: the provider, a public key id, the token the
-- caller just presented, and two timestamps. The secret it is used to fetch is
-- in Secret Manager, behind IAM, and is never returned by this query.
--
-- Its own session variable rather than the generic app.webhook_message_id, so a
-- token can never satisfy a policy meant for an order id, or the reverse.
-- ---------------------------------------------------------------------
CREATE POLICY tenant_settings_payment_webhook_lookup ON tenant_settings
    FOR SELECT
    USING (
        payment_webhook_token IS NOT NULL
        AND payment_webhook_token = NULLIF(current_setting('app.payment_webhook_token', true), ''));
