-- =====================================================================
-- V56 — Where a devotee's reply should go (E1)
--
-- Email is sent from one platform address, because the signature that keeps it
-- out of a spam folder belongs to the domain we control, and asking every temple
-- to verify a sending domain would be a worse burden than the WhatsApp setup.
-- What varies per temple is the name on the envelope and where a reply lands:
--
--   From:     ISKCON South Bengaluru via ISKCON Kitchen <noreply@ours>
--   Reply-To: whatever the temple puts here
--
-- So this is the temple's own address, and the only thing an administrator has
-- to supply for email to work properly. Null is allowed and is not a failure:
-- the message still sends, a reply simply comes back to the platform rather than
-- to the temple, which is worth avoiding but not worth refusing to send over.
-- =====================================================================

ALTER TABLE tenant_settings
    ADD COLUMN contact_email TEXT;

COMMENT ON COLUMN tenant_settings.contact_email IS
    'The temple''s own address, used as Reply-To. Sending is always from the platform address, whose domain carries the SPF and DKIM records.';
