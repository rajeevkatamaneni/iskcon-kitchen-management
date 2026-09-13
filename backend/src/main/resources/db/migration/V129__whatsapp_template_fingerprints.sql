-- =====================================================================
-- V129 — what each temple last sent Meta, per template, and to which
--        account (T-169a)
--
-- ---------------------------------------------------------------------
-- 1. Why
--
-- Rajeev, 2026-09-13, on Settings: the screen is read-only until Edit,
-- and WhatsApp gets a Reload WhatsApp Templates button that is always
-- there. When nothing is waiting it reads "Templates last sent to Meta on
-- <date>"; when something is, it becomes the primary button and says what,
-- e.g. "3 templates changed since they were last sent". After the first
-- connection, Save only saves and templates go only through Reload.
--
-- "Changed since they were last sent" is a comparison with something, and
-- until now nothing was kept to compare with. whatsapp_templates_submitted_at
-- (V55) is one date for the whole set, and whatsapp_refused_templates (V128)
-- lists only what Meta did not take. Neither says which wording Meta holds.
--
-- ---------------------------------------------------------------------
-- 2. The shape
--
-- whatsapp_template_fingerprints: a JSON object, template name -> value.
--
--   "sha256:<hex>"  Meta holds this template, on the account below, with
--                   exactly this wording: the SHA-256 of its name,
--                   category, language and body text, as computed by
--                   NotificationTemplate.whatsappFingerprint.
--   null            The app sent this template on its last send but cannot
--                   say what Meta holds: Meta refused it, could not be
--                   reached, or already held a copy nobody compared.
--   key absent      The app did not have this template when it last sent,
--                   so it is new since.
--
-- '{}' means the temple has never had fingerprints recorded. That is every
-- temple that sent templates before this migration, and it is read as
-- "unknown": nothing is counted as changed until the next Reload records
-- what Meta holds. South Bengaluru on staging is one: Meta holds all twenty,
-- the six reworded ones already carry the new wording, and a screen saying
-- "20 changed" right after deploy would be false.
--
-- whatsapp_templates_sent_waba_id and _phone_number_id: the account the
-- templates were last sent to. The screen's "account changed" is these two
-- against whatsapp_waba_id and whatsapp_phone_number_id. NULL means never
-- sent since this migration, which reads as "not changed", for the same
-- reason as above: nothing recorded, so nothing to claim.
--
-- ---------------------------------------------------------------------
-- 3. Columns, not a table
--
-- The same reasoning as V128, and the same row. The fingerprints are read
-- and written whole, for one temple, next to the other WhatsApp facts, and
-- never queried per template: the screen needs three counts, and a send
-- replaces the whole object. There are twenty entries. A table would add a
-- policy, a tenant loop in every future backfill and a join, for nothing
-- that is ever asked of one row on its own.
--
-- ---------------------------------------------------------------------
-- 4. RLS, and why there is no tenant loop
--
-- tenant_settings has carried enable_tenant_rls() since V36. Migrations run
-- subject to RLS, so an UPDATE here would match nothing, and this migration
-- has none: ADD COLUMN is DDL, and the constant default rewrites no rows.
-- No backfill is wanted anyway: '{}' and NULL are the true statements about
-- every existing row (section 2).
--
-- The V55 webhook-lookup SELECT policy lets an unauthenticated Meta callback
-- that holds a temple's opaque token read this row, so it can now also read
-- these columns. They hold hashes of the app's own template wording, which
-- is the same for every temple, and two Meta ids already on the same row.
-- Nothing that could send a message or identify a person.
-- =====================================================================

ALTER TABLE tenant_settings
    ADD COLUMN whatsapp_template_fingerprints JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN whatsapp_templates_sent_waba_id TEXT,
    ADD COLUMN whatsapp_templates_sent_phone_number_id TEXT,
    ADD CONSTRAINT tenant_settings_whatsapp_template_fingerprints_is_object
        CHECK (jsonb_typeof(whatsapp_template_fingerprints) = 'object');

COMMENT ON COLUMN tenant_settings.whatsapp_template_fingerprints IS
    'Per WhatsApp template, what this temple last sent Meta (T-169a): name -> "sha256:<hex>" of name, category, language and body when Meta holds exactly that wording; null when sent but what Meta holds is unknown; absent when the template is new since. {} means never recorded, read as unknown.';
COMMENT ON COLUMN tenant_settings.whatsapp_templates_sent_waba_id IS
    'The WhatsApp Business Account templates were last sent to (T-169a). NULL means never sent since V129.';
COMMENT ON COLUMN tenant_settings.whatsapp_templates_sent_phone_number_id IS
    'The phone number id in use when templates were last sent (T-169a). NULL means never sent since V129.';
