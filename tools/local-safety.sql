-- Run as the local `kms` superuser straight after loading a staging copy.
-- Local must never message anyone or reach a paid service. Credentials themselves (Meta access
-- token, app secret, Razorpay key secret) live in Secret Manager, not the database, and local runs
-- SECRETS_STORE=memory, so a copy holds none. This removes what is left: the identifiers that say
-- "this temple is connected", so nothing locally believes it can send or take payment.
BEGIN;
UPDATE tenant_settings SET
    whatsapp_phone_number_id = NULL, whatsapp_waba_id = NULL, whatsapp_webhook_token = NULL,
    whatsapp_verified_at = NULL, whatsapp_webhook_seen_at = NULL, whatsapp_display_number = NULL,
    whatsapp_templates_submitted_at = NULL,
    payment_provider = NULL, payment_key_id = NULL, payment_webhook_token = NULL,
    payment_verified_at = NULL, payment_webhook_seen_at = NULL, payment_webhook_registered_at = NULL;

-- Staging's scheduled jobs and triggers. qrtz_locks stays: it is seed data from V6.
DELETE FROM qrtz_fired_triggers;
DELETE FROM qrtz_simple_triggers;
DELETE FROM qrtz_cron_triggers;
DELETE FROM qrtz_simprop_triggers;
DELETE FROM qrtz_blob_triggers;
DELETE FROM qrtz_triggers;
DELETE FROM qrtz_paused_trigger_grps;
DELETE FROM qrtz_job_details;
DELETE FROM qrtz_scheduler_state;
DELETE FROM qrtz_calendars;
COMMIT;
