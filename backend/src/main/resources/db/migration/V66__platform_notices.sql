-- =====================================================================
-- V66 — The platform notice board (E9-S1)
--
-- A carrier for the rare message that has to leave one temple and reach every
-- other: a supplier recall, a contaminated batch, a platform outage, a festival
-- advisory. Build brief 2026-08-20 §11.
--
-- This is the one place in the product that crosses tenant isolation on
-- purpose, so the reasoning is written down here rather than left to be
-- reconstructed later.
--
-- ---------------------------------------------------------------------
-- Why platform_notices is not tenant-owned
--
-- Every other table in this schema answers "whose row is this?" with a
-- tenant_id and enable_tenant_rls(). A notice cannot: its whole value is that
-- a warning raised in Bengaluru is read in Mayapur. Making it tenant-owned and
-- copying a row per temple was the alternative, and it is worse in every
-- direction — two hundred copies of one recall, two hundred withdrawals to
-- push when it is retracted, and a fan-out job standing between an urgent
-- message and the people who need it.
--
-- So the table carries no tenant_id, and sits beside platform_audit_events
-- (V9) and payment_events (V37) as platform-scoped rather than tenant-scoped.
-- Unlike payment_events, though, tenant-facing endpoints read this one every
-- morning, so "no RLS at all, because only ops touches it" is not available
-- here. It gets RLS whose key is *identity*, not tenant:
--
--   * SELECT — any connection whose verified identity resolves to an active
--     user. Deliberately not narrowed to admins: the notices that matter most
--     are about food, and the cook is the person who needs to read them. An
--     unauthenticated connection resolves to nobody and sees nothing.
--   * INSERT — a person may post only as themselves, and only for their own
--     temple. The application checks the RAISE_PLATFORM_NOTICE permission;
--     this policy makes forged attribution impossible underneath it, which is
--     what "every notice carries the raising temple's name in the open" is
--     worth exactly nothing without.
--   * UPDATE — the raising temple, or a platform operator. That is the
--     withdrawal rule from §11, expressed a second time in the database.
--   * DELETE — no policy at all. A notice is never removed, only withdrawn.
--
-- Note what the INSERT policy does with an *unauthenticated* connection: it
-- admits exactly one shape, the notice attributed to no person and no temple —
-- the automation case (scheduled maintenance, degraded performance). A signed-in
-- temple admin therefore cannot post anonymously even if application code had a
-- bug, because their auth_uid forces their own id onto the row.
--
-- ---------------------------------------------------------------------
-- Why the dismissal table is the one that carries tenant-shaped RLS
--
-- Dismissal is per person, not per temple (§11, settled 2026-08-20): a temple
-- with three admins where the first clears a recall before the other two have
-- read it is a temple where two people never saw it. So the policy on
-- platform_notice_dismissals is *stricter* than the standard tenant one — it
-- admits only the caller's own rows. enable_tenant_rls() would have let one
-- admin see, and clear, another's dismissal, which is the exact behaviour the
-- rule exists to prevent.
--
-- It still carries tenant_id, and not as decoration: delete_tenant_cascade
-- (V44) finds the tables it must purge by looking for that column name, so a
-- tenant-owned table without it is a table a temple deletion silently leaves
-- behind. The second policy below is what lets that purge through.
-- =====================================================================

CREATE TABLE platform_notices (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Who raised it. Both null for a notice raised by automation, which belongs
    -- to no temple and no person.
    --
    -- ON DELETE SET NULL, not RESTRICT: a notice outlives the temple that
    -- raised it, because it was read by every other temple and their record of
    -- what they were told must not depend on that temple still existing. The
    -- attribution survives the deletion in raised_by_label, captured at write
    -- time the way audit_events captures actor_label.
    raised_by_tenant_id  UUID        REFERENCES tenants(id) ON DELETE SET NULL,
    raised_by_user_id    UUID        REFERENCES users(id)   ON DELETE SET NULL,

    -- The temple's name, or the platform's, as shown on every screen this
    -- notice reaches. Denormalised so a reader in another temple needs no join
    -- into a registry, and so it stays legible after the temple is gone.
    raised_by_label      TEXT        NOT NULL,

    -- Three, and only urgent is loud on the screen. A board where everything
    -- shouts is a board nobody reads.
    severity             TEXT        NOT NULL,

    subject              TEXT        NOT NULL,

    -- Plain text. No rich text and no HTML, deliberately: this crosses tenants,
    -- so markup posted by one temple would render inside every other temple's
    -- page, and there is no formatting a recall needs that a line break cannot
    -- give it.
    body                 TEXT        NOT NULL,

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The withdrawal. A reason is not optional: a retraction without one leaves
    -- every temple guessing whether the original was wrong or merely over.
    withdrawn_at         TIMESTAMPTZ,
    withdrawn_by_user_id UUID        REFERENCES users(id) ON DELETE SET NULL,
    withdrawn_by_label   TEXT,
    withdrawn_reason     TEXT,

    CONSTRAINT platform_notices_severity_valid
        CHECK (severity IN ('INFORMATION', 'IMPORTANT', 'URGENT')),

    CONSTRAINT platform_notices_subject_length
        CHECK (length(subject) BETWEEN 1 AND 120),

    -- Long enough for a recall with batch numbers and a phone number; short
    -- enough that nobody posts a newsletter to two hundred temples.
    CONSTRAINT platform_notices_body_length
        CHECK (length(body) BETWEEN 1 AND 4000),

    CONSTRAINT platform_notices_withdrawal_complete
        CHECK (
            (withdrawn_at IS NULL AND withdrawn_reason IS NULL AND withdrawn_by_label IS NULL)
            OR (withdrawn_at IS NOT NULL AND length(withdrawn_reason) BETWEEN 1 AND 500
                AND withdrawn_by_label IS NOT NULL)
        )

    -- There is deliberately no constraint tying the two raiser columns together.
    -- "A temple-attributed notice must have a person behind it" is true when the
    -- notice is written — and it is the insert policy below, not a CHECK, that
    -- enforces it, since a CHECK cannot tell the writer from the caller anyway.
    -- It stops being true afterwards, on purpose: deleting a temple nulls the
    -- user first and the temple second, and a CHECK would fail in between and
    -- take the whole purge with it. The label is what carries the attribution
    -- once the pointers have gone.
);

COMMENT ON TABLE platform_notices IS
    'Notices that cross every temple on the platform (E9-S1). Not tenant-owned; readable by any verified user, writable only as oneself, withdrawable by the raising temple or a platform operator, and never deletable.';

COMMENT ON COLUMN platform_notices.raised_by_label IS
    'The raising temple''s name, or the platform''s, captured at write time so attribution survives the temple.';

ALTER TABLE platform_notices ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_notices FORCE ROW LEVEL SECURITY;

-- World-readable *within the platform*: any connection carrying a verified
-- identity that resolves to an active user, whatever their temple or role. The
-- users row is reached through the app.auth_uid read escape (V2), the same
-- verified identity RLS already trusts at sign-in. NULLIF because RESET leaves
-- a custom setting as the empty string rather than unset, and a control that
-- raises instead of denying is a control somebody eventually works around.
CREATE POLICY platform_notices_read ON platform_notices
    FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.status = 'ACTIVE'
        )
    );

-- A person posts as themselves, for their own temple — or automation posts as
-- the platform, which is the only shape a connection with no identity may
-- write. Since a person's auth_uid may now span several memberships (V52), the
-- row's temple must match the *membership* being written, not merely the person.
CREATE POLICY platform_notices_raise ON platform_notices
    FOR INSERT
    WITH CHECK (
        (
            raised_by_user_id IS NOT NULL
            AND EXISTS (
                SELECT 1 FROM users u
                WHERE u.id = raised_by_user_id
                  AND u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
                  AND u.status = 'ACTIVE'
                  AND u.tenant_id IS NOT DISTINCT FROM raised_by_tenant_id
            )
        )
        OR (
            raised_by_user_id IS NULL
            AND raised_by_tenant_id IS NULL
            AND NULLIF(current_setting('app.auth_uid', true), '') IS NULL
        )
    );

-- Withdrawal, and nothing else: the raising temple may take down its own, a
-- platform operator may take down anyone's. This is the same rule the service
-- enforces with permissions, kept here too because it is the only thing
-- standing between a board anybody may post to and a board anybody may edit.
CREATE POLICY platform_notices_withdraw ON platform_notices
    FOR UPDATE
    USING (
        EXISTS (
            SELECT 1 FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.status = 'ACTIVE'
              AND (u.role = 'SUPER_ADMIN' OR u.tenant_id = platform_notices.raised_by_tenant_id)
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.status = 'ACTIVE'
              AND (u.role = 'SUPER_ADMIN' OR u.tenant_id = platform_notices.raised_by_tenant_id)
        )
    );

-- No DELETE policy, on purpose. make_append_only() would have been the usual
-- way to say "history is never edited", but a withdrawal *is* an edit of this
-- row — the retraction has to travel the same rails as the original, which
-- means the same id — so the guard has to allow UPDATE and refuse DELETE. The
-- absence of a policy does exactly that, quietly.

CREATE INDEX platform_notices_recent ON platform_notices (created_at DESC, id DESC);

-- The Today feed's window is measured from the withdrawal where there is one,
-- so a retraction is news on the day it happens whatever the age of the thing
-- it retracts.
CREATE INDEX platform_notices_window
    ON platform_notices (COALESCE(withdrawn_at, created_at) DESC);


-- ---------------------------------------------------------------------
-- Dismissals — one row per person per notice.
-- ---------------------------------------------------------------------

CREATE TABLE platform_notice_dismissals (
    notice_id    UUID        NOT NULL REFERENCES platform_notices(id) ON DELETE RESTRICT,

    -- ON DELETE CASCADE, unlike almost everything else that points at users:
    -- accounts are disabled rather than deleted, so the only thing that removes
    -- one is the whole-tenant purge, and a dismissal without the person who
    -- made it means nothing to anybody.
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Not redundant with user_id: delete_tenant_cascade (V44) enumerates the
    -- tables it must purge by looking for a column of this name.
    tenant_id    UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- Last dismissed at, not first: dismissing a notice that is later withdrawn
    -- and shown again as a retraction moves this forward, and the feed compares
    -- it against withdrawn_at to decide whether the person has seen the
    -- retraction as well as the original.
    dismissed_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (notice_id, user_id)
);

COMMENT ON TABLE platform_notice_dismissals IS
    'Who has cleared which notice from their own Today screen. Per person, never per temple: one admin clearing a recall must not clear it for colleagues who have not read it.';

ALTER TABLE platform_notice_dismissals ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_notice_dismissals FORCE ROW LEVEL SECURITY;

-- Stricter than tenant isolation, and that is the point: a person sees and
-- writes their own dismissals and nobody else's, so "dismissed" can never mean
-- "dismissed by a colleague". The membership is matched on both the verified
-- identity and the temple the request is speaking for, since one person may now
-- hold several (V52).
CREATE POLICY platform_notice_dismissal_own ON platform_notice_dismissals
    FOR ALL
    USING (
        user_id IN (
            SELECT u.id FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
              AND u.status = 'ACTIVE'
        )
    )
    WITH CHECK (
        tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        AND user_id IN (
            SELECT u.id FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
              AND u.status = 'ACTIVE'
        )
    );

-- The whole-tenant purge, and only it. delete_tenant_cascade runs as the schema
-- owner under FORCE ROW LEVEL SECURITY with the tenant set transaction-locally,
-- and it carries no auth_uid, so the policy above matches none of the temple's
-- rows and the purge would stall against the users foreign key. Keyed on the
-- role rather than on a new setting, because "not the application role" is
-- already how the append-only trigger (V49) tells the purge apart from the app.
CREATE POLICY platform_notice_dismissal_purge ON platform_notice_dismissals
    FOR DELETE
    USING (
        current_user <> 'kms_app'
        AND tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
    );

-- The feed's anti-join reads a person's dismissals; the primary key is ordered
-- the other way round.
CREATE INDEX platform_notice_dismissals_person ON platform_notice_dismissals (user_id);


-- ---------------------------------------------------------------------
-- The platform audit log admits one act by a non-operator.
-- ---------------------------------------------------------------------
--
-- V9 admits writes to platform_audit_events only from a super-admin, which was
-- right while every tenantless act was an operator's. Raising a notice is the
-- first one that is not: a temple admin posting to every temple on the platform
-- has performed a cross-tenant act, and §11 makes "the raiser is on the
-- platform audit log" one of the three things standing in for pre-moderation.
--
-- So the insert side widens, narrowly: any verified active user may append, and
-- only for a notice, and only attributed to themselves. Reading does not widen
-- at all — a temple admin writes to the platform log and still cannot read it.
CREATE POLICY platform_audit_notice_insert ON platform_audit_events
    FOR INSERT
    WITH CHECK (
        entity_type = 'PLATFORM_NOTICE'
        AND EXISTS (
            SELECT 1 FROM users u
            WHERE u.id = actor_user_id
              AND u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.status = 'ACTIVE'
        )
    );

-- ---------------------------------------------------------------------
-- And that widening has a consequence, which is worth stating plainly.
--
-- Until now every actor on the platform log was an operator, whose users row
-- belongs to no temple and is never purged. A temple admin's row is, and
-- actor_user_id was NOT NULL REFERENCES users ON DELETE RESTRICT — so the first
-- temple to raise a notice would have become a temple that could no longer be
-- deleted. delete_tenant_cascade (V44) does not touch this table, deliberately:
-- the platform log is the durable proof of a cross-tenant act and erasing it
-- with the temple would defeat the whole point of recording it there.
--
-- So the reference gives way rather than the record. The row keeps everything
-- that makes it legible — action, entity, reason, timestamp, and actor_label,
-- which V9 already captured at write time precisely so the log reads without
-- resolving a user row. What it loses when a temple leaves is a pointer to a
-- row that no longer exists. The same reasoning as raised_by_tenant_id above,
-- for the same reason: an act belonging to no temple must outlive the temple.
--
-- Written defensively because another migration in this build (V65, the ban
-- record) reaches the same conclusion from the other direction, and whichever
-- of the two runs first should leave the second with nothing to do.
DO $$
BEGIN
    ALTER TABLE platform_audit_events ALTER COLUMN actor_user_id DROP NOT NULL;

    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'platform_audit_events_actor_user_id_fkey') THEN
        ALTER TABLE platform_audit_events
            DROP CONSTRAINT platform_audit_events_actor_user_id_fkey;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'platform_audit_events_actor') THEN
        ALTER TABLE platform_audit_events
            ADD CONSTRAINT platform_audit_events_actor
            FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL;
    END IF;
END
$$;

COMMENT ON COLUMN platform_audit_events.actor_user_id IS
    'The acting user, where they still exist. Null once their temple has been deleted; actor_label is the durable identity.';
