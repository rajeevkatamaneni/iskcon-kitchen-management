-- =====================================================================
-- V130 — when a running app first saw each WhatsApp template's wording
--        (T-177)
--
-- ---------------------------------------------------------------------
-- 1. Why
--
-- Rajeev, 2026-09-13: "We have no way of seeing the Text in each of these
-- templates. I feel like it is black box no one can see into. The super
-- admin's SHOULD have a way to see all of the message templates , date
-- created, updated and Meta approval status."
--
-- The text lives in NotificationTemplate and can be shown as it is. The
-- dates cannot, because nothing stored carries a date per template:
--
--   * tenant_settings.whatsapp_template_fingerprints (V129) holds a hash per
--     template per temple, with no time in it;
--   * whatsapp_templates_submitted_at (V55) is one time per temple for the
--     whole set;
--   * notifications.created_at is when one message was sent.
--
-- Git history is not available to a running app. So this table starts
-- recording, from the release that adds it: at every application start,
-- each template's current wording fingerprint, and the first time any
-- running process saw it. Nothing before that release can be recovered,
-- and the screen says when tracking began rather than pretending otherwise.
--
-- ---------------------------------------------------------------------
-- 2. The name
--
-- whatsapp_template_wording_seen. Each row is one wording of one template,
-- and the moment a running app first saw it. Three nearby names were worse:
--
--   * "..._versions" suggests a release somebody authored and numbered.
--     Nobody numbers these; a wording is identified by its hash.
--   * "..._history" suggests a log of edits. Rows here are never edited,
--     and a template changing back to an earlier wording adds nothing.
--   * "..._created" is the word the screen must not use. The app did not
--     create the wording at that moment; it first noticed it then.
--
-- "Seen" is the same word the screen uses ("Wording first seen by the app"),
-- so the column and the label cannot drift into meaning different things.
--
-- ---------------------------------------------------------------------
-- 3. Why this is not tenant-owned, and has no RLS
--
-- Every tenant-owned table carries tenant_id and calls enable_tenant_rls().
-- This one holds neither a temple's data nor anything about a temple: the
-- template wording is the app's own, identical for every temple, and the
-- fingerprint is NotificationTemplate.whatsappFingerprint("en") — the same
-- SHA-256 V129 stores per temple, computed from the name, category,
-- language and body in the source code. There is no row a temple could own
-- and no row one temple could leak to another.
--
-- So the table carries no tenant_id, sits beside platform_audit_events (V9)
-- and platform_notices (V66) as platform-scoped, and has no policy. Unlike
-- V9, it does not need an identity policy either: V9 hides who did what on
-- the platform, whereas everything here is already in the source code of
-- the application reading it. The write happens at application start, when
-- no request, no verified identity and no temple exist, so an identity
-- policy would refuse the only writer there is.
--
-- It also means delete_tenant_cascade (V44), which finds the tables it
-- purges by their tenant_id column, rightly never touches it.
--
-- ---------------------------------------------------------------------
-- 4. Append-only
--
-- A first-seen time is a statement about the past, and it is never edited.
-- A later start with the same wording adds nothing (the primary key below
-- makes that an insert-if-absent). A start with new wording adds a row. A
-- start never updates or deletes one, so make_append_only() (V49) refuses
-- both to the application role, which is the only role that writes here.
--
-- ---------------------------------------------------------------------
-- 5. Many processes start at once
--
-- The api, the worker and several instances of each start together on a
-- deploy, and each records the same twenty wordings. The writer is one
-- INSERT ... ON CONFLICT (template_name, fingerprint) DO NOTHING, so two
-- processes cannot both decide a wording is new: the primary key settles it
-- inside PostgreSQL, the first insert to reach the table keeps its time, and
-- the other does nothing and succeeds. See TemplateWordingRecorder.
--
-- first_seen_at defaults to the database's now(), not an application clock,
-- so every process writes on one clock and the earliest row is comparable
-- with the rest.
-- =====================================================================

CREATE TABLE whatsapp_template_wording_seen (
    -- Meta's template name, e.g. 'shift_reminder'. Not a reference to anything
    -- in the database: the templates live in NotificationTemplate.
    template_name TEXT        NOT NULL,

    -- 'sha256:<hex>' of name, category, language and body, exactly as V129.
    fingerprint   TEXT        NOT NULL,

    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (template_name, fingerprint),

    CONSTRAINT whatsapp_template_wording_seen_name_present
        CHECK (length(template_name) > 0),

    -- The prefix is what V129's fingerprint says about itself, so a later
    -- change of algorithm reads as a new kind of value rather than as a
    -- change of wording nobody made.
    CONSTRAINT whatsapp_template_wording_seen_fingerprint_shape
        CHECK (fingerprint LIKE 'sha256:%')
);

COMMENT ON TABLE whatsapp_template_wording_seen IS
    'Each WhatsApp template wording a running app has seen, and when it first saw it (T-177). Platform-scoped: no tenant_id and no RLS, because the wording is the app''s own and the same for every temple. Append-only.';

COMMENT ON COLUMN whatsapp_template_wording_seen.fingerprint IS
    'sha256:<hex> of the template''s name, category, language and body, as NotificationTemplate.whatsappFingerprint computes it and V129 stores it per temple.';

COMMENT ON COLUMN whatsapp_template_wording_seen.first_seen_at IS
    'When a running app first recorded this wording, on the database clock. Not when anybody wrote it.';

SELECT make_append_only('whatsapp_template_wording_seen');
