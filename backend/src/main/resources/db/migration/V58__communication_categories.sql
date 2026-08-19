-- =====================================================================
-- V58 — Communication categories, and a devotee's say in them (E8-S1)
--
-- Until now a temple could send a devotee anything it had a template for, and
-- the only control anyone had was consent: contacted, or not at all. That is a
-- switch with one setting too few. Somebody who does not want the newsletter
-- still wants to be told their shift moved, and forcing that choice teaches
-- people to withdraw consent entirely — which then silences the reminders the
-- kitchen actually depends on.
--
-- So every message now carries a category, and the categories split in two:
--
--   * **Optional** — the newsletter, festival announcements, seva appeals,
--     fundraising, temple notices. A devotee may decline any of them.
--   * **Operational** — shift reminders, cancellations, schedule changes,
--     donation receipts, failed payments. The consequence of something the
--     person already did, and never opt-out-able. Nothing composed by hand is
--     ever operational.
--
-- Two gates, both named, because they are two different statements:
--
--   1. `users.optional_communications_opt_out_at` — "nothing optional, ever".
--      Deliberately a fact of its own rather than shorthand for a row per
--      category, so that adding a category later does not quietly re-subscribe
--      somebody who already said no to all of it.
--   2. a `communication_preferences` row — "not this kind". Presence means
--      opted out; the table records only the exceptions, since being subscribed
--      is the default and writing five rows for every devotee who wants
--      everything would be a table full of nothing.
--
-- The vocabulary itself lives in Java (CommunicationCategory), not a CHECK, for
-- the same reason as AuditAction: a new kind of message should not be a
-- migration.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN optional_communications_opt_out_at TIMESTAMPTZ;

COMMENT ON COLUMN users.optional_communications_opt_out_at IS
    'Set means: send nothing optional, whatever the per-category rows say. Survives new categories on purpose.';

CREATE TABLE communication_preferences (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,

    -- A CommunicationCategory name. Only optional ones ever appear here.
    category     TEXT        NOT NULL,

    opted_out_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Where they said it. An unsubscribe link is a decision made without signing
    -- in, and it is worth being able to tell the two apart when someone asks why
    -- they stopped hearing from the temple.
    source       TEXT        NOT NULL DEFAULT 'PROFILE',

    CONSTRAINT comm_pref_source_valid CHECK (source IN ('PROFILE', 'UNSUBSCRIBE_LINK'))
);

CREATE UNIQUE INDEX comm_pref_one_per_category
    ON communication_preferences (tenant_id, user_id, category);

SELECT enable_tenant_rls('communication_preferences');

COMMENT ON TABLE communication_preferences IS
    'One row per kind of message a devotee has declined. Presence means opted out; absence means subscribed (E8-S1).';

-- ---------------------------------------------------------------------
-- What a message was, and — when it was not sent — which of the two reasons
-- stopped it.
--
-- The Operations screen already shows a suppressed count, and its help text
-- says suppression means the recipient never consented. That was true when
-- consent was the only gate. With a second one it would be a screen stating
-- something false, so the reason is recorded rather than inferred.
-- ---------------------------------------------------------------------
-- The default backfills every existing row as DDL rather than as an UPDATE, which
-- matters: a data statement here runs under the isolation policy this schema
-- declares, and a cross-tenant UPDATE matches nothing while reporting success.
-- DDL cannot be filtered by a row policy, so this reaches every tenant by
-- construction. And the value is right: before this migration there was no other
-- kind of message to send — everything was the consequence of something the
-- person had already done.
ALTER TABLE notifications
    ADD COLUMN category          TEXT NOT NULL DEFAULT 'OPERATIONAL',
    ADD COLUMN suppressed_reason TEXT;

ALTER TABLE notifications
    ADD CONSTRAINT notifications_suppressed_reason_valid CHECK (
        suppressed_reason IS NULL OR suppressed_reason IN ('NO_CONSENT', 'OPTED_OUT'));

-- Every suppression that already exists was a consent one; there was no other
-- gate. This one cannot ride a column default — it depends on the row's status —
-- so it is the tenant loop, adopting each temple in turn as the application does.
DO $$
DECLARE tenant_row RECORD;
BEGIN
    FOR tenant_row IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', tenant_row.id::text, true);
        UPDATE notifications SET suppressed_reason = 'NO_CONSENT'
        WHERE status = 'SUPPRESSED' AND suppressed_reason IS NULL;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;

COMMENT ON COLUMN notifications.category IS
    'CommunicationCategory of this message. OPERATIONAL is never suppressed by a preference (E8-S1).';
