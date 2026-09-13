-- =====================================================================
-- V131 — each temple's stored copy of Meta's status for its WhatsApp
--        templates (T-178)
--
-- ---------------------------------------------------------------------
-- 1. Why
--
-- Rajeev, 2026-09-13: "The super admin's SHOULD have a way to see all of
-- the message templates , date created, updated and Meta approval status."
-- And: "Option 2 it is, build it in two steps." Step 1 (T-177, V130) was
-- the catalogue of wording. This is step 2: what Meta says about each
-- template, per temple.
--
-- Meta is only asked through a temple's own business account and access
-- token, one template at a time (WhatsAppTemplateComparison, T-173): twenty
-- GETs per temple. An operator's screen that asked Meta on every visit
-- would make twenty calls per temple per page load, and the counts on the
-- catalogue would make that for every temple at once. So the answer is
-- stored, with the time it was taken, and the screens read the copy.
--
-- The copy is taken at two moments only:
--
--   * at the end of a temple's own Reload, which already talks to Meta and
--     is the moment the wording there changes;
--   * when the platform operator presses Refresh on that temple's page.
--
-- ---------------------------------------------------------------------
-- 2. Why this is tenant data, under enable_tenant_rls()
--
-- Unlike V130's table, which holds the app's own wording and belongs to no
-- temple, every row here is a statement about one temple's WhatsApp
-- Business account: what Meta approved, refused or holds as marketing for
-- that temple, read with that temple's secret. Two temples with the same
-- template name can have different answers, and one temple's refusals are
-- nobody else's business. So it carries tenant_id and the standard policy,
-- and the operator reads it the way OpsService reads notifications: by
-- adopting one temple's context at a time. Nothing reads across temples
-- in one query; the counts on the catalogue are sums of per-temple reads.
--
-- Migrations run as an unprivileged role subject to RLS, and this one only
-- creates an empty table, so there is nothing to seed or backfill per
-- temple. The first copy of each temple appears on its next Reload or
-- Refresh, and until then the screens say it has not been taken.
--
-- ---------------------------------------------------------------------
-- 3. Why a table, not columns on tenant_settings
--
-- V128 and V129 put the refused list and the fingerprints on
-- tenant_settings, because those are facts the Settings screen reads with
-- the rest of the row. This one is different in two ways:
--
--   * It has its own clock. It is replaced whole by a Refresh that changes
--     nothing else about the temple, and it says when it was taken.
--   * tenant_settings is readable by the webhook-lookup policy (V58) so an
--     unauthenticated callback can find its temple. That is fine for a hash
--     and two ids; it is no reason to put Meta's refusals within its reach.
--
-- ---------------------------------------------------------------------
-- 4. Replaced whole, so not append-only
--
-- A copy is a cache of Meta's current answer, not a record of the past.
-- Each refresh deletes the temple's rows and inserts twenty new ones in one
-- transaction, so a reader sees either the old copy or the new one, never
-- half of each. History of who pressed Refresh lives in audit_events as
-- WHATSAPP_TEMPLATE_STATUS_REFRESHED, which is append-only already.
--
-- ---------------------------------------------------------------------
-- 5. Deleting a temple
--
-- delete_tenant_cascade (V44, restated in V86) finds what to purge by the
-- tenant_id column, so this table is purged with no change to it. Nothing
-- references these rows, and they reference nothing but the temple, so the
-- purge's retry loop never waits on them.
--
-- The foreign key is ON DELETE CASCADE rather than RESTRICT, which most
-- tenant tables use. RESTRICT exists to stop a temple disappearing from
-- under a record somebody made. Nobody made these rows: they are Meta's
-- answer, re-fetched on demand, and a copy outliving its temple would mean
-- nothing. The purge deletes them before the temple row either way.
-- =====================================================================

CREATE TABLE whatsapp_template_status_copy (
    tenant_id            UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,

    -- Meta's template name, e.g. 'shift_reminder'. Not a reference: the
    -- templates live in NotificationTemplate.
    template_name        TEXT        NOT NULL,

    -- The category the app registered it under when the copy was taken.
    our_category         TEXT        NOT NULL,

    -- Meta's review status as Meta spelled it: APPROVED, PENDING, REJECTED,
    -- PAUSED and so on. Stored as given, never translated, so a status Meta
    -- adds later is kept rather than lost. Null when not held or not answered.
    meta_status          TEXT,

    -- The category Meta holds it under, which may not be ours (T-168).
    meta_category        TEXT,

    -- Whether Meta holds a template of exactly this name and language. Null
    -- when Meta did not answer for it, because then nobody knows.
    held                 BOOLEAN,

    -- Meta's wording equals ours once outer whitespace is stripped, the test
    -- Reload makes before it edits. Known only for a template Meta holds.
    wording_matches      BOOLEAN,

    -- Meta's rejected_reason as Meta spelled it, e.g. INVALID_FORMAT. Kept
    -- because INVALID_FORMAT is a refusal of the template's formatting, which
    -- is the same in every temple; see TemplateStatusCopy.
    meta_rejected_reason TEXT,

    -- A plain sentence when Meta could not be asked for this template.
    lookup_problem       TEXT,

    -- When this copy was taken, on the database clock. Every row of one copy
    -- carries the same time, because one statement writes them.
    taken_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, template_name),

    CONSTRAINT whatsapp_template_status_copy_name_present
        CHECK (length(template_name) > 0),

    -- A template Meta did not answer for says why, and claims nothing else.
    CONSTRAINT whatsapp_template_status_copy_unanswered_claims_nothing
        CHECK (held IS NOT NULL
               OR (lookup_problem IS NOT NULL AND meta_status IS NULL AND meta_category IS NULL
                   AND wording_matches IS NULL AND meta_rejected_reason IS NULL)),

    -- Wording can only be compared with something Meta holds.
    CONSTRAINT whatsapp_template_status_copy_wording_only_when_held
        CHECK ((held IS TRUE) = (wording_matches IS NOT NULL))
);

COMMENT ON TABLE whatsapp_template_status_copy IS
    'One temple''s stored copy of Meta''s answer per WhatsApp template (T-178): status, category, whether the wording matches, and when it was taken. Tenant-owned under RLS. Replaced whole on the temple''s Reload or the operator''s Refresh, so not append-only.';

COMMENT ON COLUMN whatsapp_template_status_copy.meta_status IS
    'Meta''s status exactly as Meta returned it. Null when Meta does not hold the template or did not answer.';

COMMENT ON COLUMN whatsapp_template_status_copy.taken_at IS
    'When Meta was asked, on the database clock. The screens show it as "as of".';

SELECT enable_tenant_rls('whatsapp_template_status_copy');
