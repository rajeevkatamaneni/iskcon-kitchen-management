-- =====================================================================
-- V128 — which WhatsApp templates Meta refused, kept where an
--        administrator can be shown them (T-159)
--
-- ---------------------------------------------------------------------
-- 1. Why
--
-- On 2026-09-12 South Bengaluru connected WhatsApp on staging. Saving
-- Settings → WhatsApp registers every message this application can send
-- as a Meta template, and Meta refused six of the twenty on the spot. The
-- only record of that was a WARN line in the API's log. The screen said
-- nothing, and each of those six messages would have gone on falling back
-- to SMS for as long as nobody read the log.
--
-- whatsapp_templates_submitted_at (V55) cannot carry this: it is one date
-- for the whole set, and by V55's own comment "records that we asked,
-- never that they said yes".
--
-- ---------------------------------------------------------------------
-- 2. The shape
--
-- A JSON array of {"name": ..., "reason": ...}, one element per template
-- that was not registered at the last save:
--
--   name    Meta's template name, e.g. "shift_reminder".
--   reason  A short sentence an administrator can read, written by
--           TenantWhatsAppSettingsService. Never Meta's own error text,
--           which is written for a developer and goes to the log.
--
-- Replaced whole on every save, so a later save where Meta accepts
-- everything leaves '[]'. It is a snapshot of the last attempt, not a
-- history; the audit log and the API log hold the history.
--
-- A column rather than a table because it is always read and written as
-- one value, for one temple, next to the other WhatsApp facts on this row,
-- and it has no life of its own: nothing queries one refusal, joins to it
-- or keeps it once the next save has replaced it.
--
-- ---------------------------------------------------------------------
-- 3. NOT NULL DEFAULT '[]', and no backfill
--
-- '[]' is the true statement about every existing row: nothing has ever
-- recorded a refusal, so there are none to show, and the next save fills
-- it in. A constant default is a catalogue change in PostgreSQL 11+, so
-- this rewrites no rows.
--
-- NOT NULL rather than nullable so a reader has one empty value to handle,
-- not two that mean the same thing.
--
-- ---------------------------------------------------------------------
-- 4. RLS, and why there is no tenant loop
--
-- tenant_settings has carried enable_tenant_rls() since V36. Migrations
-- run subject to RLS, so an UPDATE here would match nothing — which is
-- why this migration has none. ADD COLUMN is DDL, run as the table owner,
-- and touches no rows. Reads and writes of the new column go through the
-- table's ordinary tenant policy.
--
-- The V55 webhook-lookup SELECT policy lets an unauthenticated Meta
-- callback find its temple by the opaque token in its URL, and so now
-- also exposes this column to a caller already holding that token. It
-- holds template names and our own sentences about them: nothing that
-- could send a message or identify a person.
-- =====================================================================

ALTER TABLE tenant_settings
    ADD COLUMN whatsapp_refused_templates JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT tenant_settings_whatsapp_refused_templates_is_array
        CHECK (jsonb_typeof(whatsapp_refused_templates) = 'array');

COMMENT ON COLUMN tenant_settings.whatsapp_refused_templates IS
    'The WhatsApp templates Meta did not register at this temple''s last Settings save (T-159): a JSON array of {"name", "reason"}, where reason is a plain sentence for an administrator and never Meta''s developer text. Replaced whole on every save, so [] means the last save registered everything.';
