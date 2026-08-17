-- =====================================================================
-- V55 — A temple's own WhatsApp Business account (E1, E5)
--
-- The same division of what lives where as the payment gateway in V53, for the
-- same reasons, and deliberately the same shape so there is one pattern to learn
-- rather than two:
--
--   * the phone number id and the WhatsApp Business Account id, which are not
--     secrets — the first addresses the send, the second owns the templates;
--   * an opaque webhook token, identifying the temple in the callback URL,
--     because Meta signs with that temple's own app secret and we cannot know
--     whose secret to fetch until we know whose temple it is;
--   * when the credentials last reached Meta, and when a correctly signed
--     callback last arrived, which are different facts that fail differently.
--
-- The access token, the app secret and the webhook verify token are NOT here.
-- They live in Secret Manager under a name derived from the tenant id, so a dump
-- of this database leaks nothing that can send a message in a temple's name.
--
-- Why per temple at all, when one platform number would be less work: a message
-- from the temple is from the temple. A volunteer reading "your shift is
-- tomorrow" should see the name of the temple they serve at, not ours, and a
-- devotee replying should reach the people who sent it. It also keeps a temple's
-- WhatsApp account, its message limits and its reputation its own.
-- =====================================================================

ALTER TABLE tenant_settings
    ADD COLUMN whatsapp_phone_number_id  TEXT,
    ADD COLUMN whatsapp_waba_id          TEXT,
    ADD COLUMN whatsapp_webhook_token    TEXT,
    ADD COLUMN whatsapp_verified_at      TIMESTAMPTZ,
    ADD COLUMN whatsapp_webhook_seen_at  TIMESTAMPTZ,

    -- When the message templates were last submitted to Meta. Approval is Meta's and takes its own
    -- time, so this records that we asked, never that they said yes.
    ADD COLUMN whatsapp_templates_submitted_at TIMESTAMPTZ,

    -- The number as Meta describes it, cached from the last check purely so the screen can show an
    -- administrator which number they actually connected. Never used to address anything.
    ADD COLUMN whatsapp_display_number   TEXT,

    -- Configured means all three: who to send as, whose templates, and where the
    -- callbacks land. A partial row would let a temple believe it was set up.
    ADD CONSTRAINT tenant_settings_whatsapp_shape CHECK (
        whatsapp_phone_number_id IS NULL
        OR (whatsapp_waba_id IS NOT NULL AND whatsapp_webhook_token IS NOT NULL));

-- One temple per token: the callback URL must resolve to exactly one temple.
CREATE UNIQUE INDEX tenant_settings_whatsapp_webhook_token
    ON tenant_settings (whatsapp_webhook_token)
    WHERE whatsapp_webhook_token IS NOT NULL;

-- A delivery receipt names the phone number it was sent from, so this is also how
-- a callback finds its temple once the signature has been checked.
CREATE UNIQUE INDEX tenant_settings_whatsapp_phone_number
    ON tenant_settings (whatsapp_phone_number_id)
    WHERE whatsapp_phone_number_id IS NOT NULL;

COMMENT ON COLUMN tenant_settings.whatsapp_phone_number_id IS
    'Meta''s id for the temple''s business phone number. Addresses the send; not a secret.';
COMMENT ON COLUMN tenant_settings.whatsapp_waba_id IS
    'The temple''s WhatsApp Business Account, which owns its approved message templates.';
COMMENT ON COLUMN tenant_settings.whatsapp_webhook_token IS
    'Opaque id in this temple''s callback URL. Says which temple to verify against; is not itself trusted.';
COMMENT ON COLUMN tenant_settings.whatsapp_verified_at IS
    'When the stored credentials last reached Meta. Says nothing about whether callbacks arrive.';
COMMENT ON COLUMN tenant_settings.whatsapp_webhook_seen_at IS
    'When a correctly signed callback was last received. The only proof the return path works.';

-- ---------------------------------------------------------------------
-- The callback's lookup, before any tenant is known
--
-- Exactly the V53 payment-webhook escape, for exactly the same reason: a Meta
-- callback arrives unauthenticated and cannot be verified until we hold that
-- temple's app secret. Safe only because the row it exposes holds nothing worth
-- having — two Meta ids, the token the caller just presented, and two timestamps.
-- Its own session variable, so a WhatsApp token can never satisfy a policy meant
-- for a payment token.
-- ---------------------------------------------------------------------
CREATE POLICY tenant_settings_whatsapp_webhook_lookup ON tenant_settings
    FOR SELECT
    USING (
        whatsapp_webhook_token IS NOT NULL
        AND whatsapp_webhook_token = NULLIF(current_setting('app.whatsapp_webhook_token', true), ''));
