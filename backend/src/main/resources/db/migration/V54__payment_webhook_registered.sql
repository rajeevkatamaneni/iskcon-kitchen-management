-- =====================================================================
-- V54 — Whether we registered this temple's webhook ourselves (E7)
--
-- Setting up payments has one step a temple administrator must perform outside
-- this application: telling their provider where to call back, and with which
-- secret. It is the step most likely to be got wrong, and getting it wrong is
-- silent — the temple takes money and never records it, because the callback
-- that confirms a donation was never configured.
--
-- Where a provider lets us register the webhook ourselves we now do, and this
-- records that we did. It is not the same fact as payment_webhook_seen_at:
--
--   * registered_at says we asked the provider to call us and it agreed;
--   * seen_at says one actually arrived and was correctly signed.
--
-- Registration can succeed and delivery still fail — a provider outage, a
-- webhook an administrator later deleted — so the screen keeps reporting both.
-- Null means nobody registered it for this temple, which is the ordinary case
-- for Razorpay, whose webhook API is available only to partners.
-- =====================================================================

ALTER TABLE tenant_settings
    ADD COLUMN payment_webhook_registered_at TIMESTAMPTZ;

COMMENT ON COLUMN tenant_settings.payment_webhook_registered_at IS
    'When we registered this temple''s webhook with its provider ourselves. Null when it was done by hand, or not at all.';
