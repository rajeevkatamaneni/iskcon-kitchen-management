-- =====================================================================
-- V65 — The ban record on termination, and the check at hire (E9 / B9)
--
-- This is the only table in the schema a temple writes and other temples read.
-- It exists because somebody dismissed for theft in Bengaluru turning up in
-- Mayapur three weeks later is the case the whole of E9 was raised for, and no
-- amount of within-temple bookkeeping can catch it.
--
-- What the design settled on, and why the shape below looks as it does (build
-- brief 2026-08-20 §10):
--
--   * **No broadcast.** An earlier design pushed a notice to every temple. An
--     unnamed one ("an employee has been blacklisted") is a rumour with no
--     handle on it; a named one publishes an accusation about a private
--     individual to two hundred organisations on one administrator's say-so.
--     Both were dropped. The identity never travels. It is *asked for*, once,
--     by the one temple with a reason to ask, at the moment it is hiring.
--
--   * **The raising temple owns the record.** Only it may amend or retract.
--     Another temple attempting either is refused (KMS-4307).
--
--   * **The subject is never shown the reason in the app.** They lose access at
--     termination anyway, and disclosure at the moment of firing invites
--     retaliation — a real risk in India, borne by the people this product is
--     for. The DPDP Act's right here is to information *on request*, satisfied
--     by a documented out-of-band process, not by proactive disclosure. The
--     consequence is written into the rest of this table on purpose: because
--     the subject is no longer a check on a wrong entry, retraction, the
--     ten-year fade and naming the raising temple carry the whole of the error
--     correction between them. There is no subject-facing surface anywhere.
--
-- ---------------------------------------------------------------------
-- Row-Level Security: enabled, and deliberately NOT forced
-- ---------------------------------------------------------------------
-- Every other tenant-owned table in this schema calls enable_tenant_rls(), which
-- sets both ENABLE and FORCE. This one cannot, and the difference is the whole
-- security design, so read it before changing anything here.
--
-- The table has two readers who need opposite things:
--
--   1. The raising temple, which must see, amend and retract its own records.
--   2. The hiring temple, which must be told that *this one person* is on
--      somebody else's record — and must never be able to read the list.
--
-- A tenant_isolation policy alone serves (1) and makes (2) impossible. Dropping
-- RLS altogether — the payment_events answer (V37) — serves (2) and makes the
-- table browsable by any bug in a WHERE clause. Neither is acceptable, and the
-- reason V37 could go without a policy does not hold here: a tenant-facing
-- endpoint *does* touch this table.
--
-- So: ENABLE ROW LEVEL SECURITY confines the application role to its own
-- temple's rows, exactly as everywhere else — and FORCE is withheld so that the
-- table's owner remains exempt, because the owner is what match_employment_bans
-- below runs as. That function is the only way to read another temple's row, it
-- takes a specific person's identifying details as its arguments, and there is
-- no argument to it that returns the table. The absence of FORCE is therefore
-- not an oversight; it is the mechanism. The owner is otherwise only ever
-- Flyway.
-- =====================================================================

CREATE TABLE employment_bans (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- The temple that raised it, and the only one that may amend or retract it.
    --
    -- Named tenant_id like every other tenant-owned table, not raising_tenant_id
    -- as it reads in Java, and the reason is not cosmetic. Two pieces of
    -- machinery in this schema find a temple's data by looking for a column of
    -- that exact name — delete_tenant_cascade (V44) and the whole-tenant export
    -- — and both are right to include this table. A temple's own export should
    -- contain the records it raised, and a temple that leaves the platform
    -- should take them with it: there would be nobody left to answer the
    -- telephone call a finding is meant to start, nobody left to retract a wrong
    -- entry, and a temple's name on a record that no longer exists. The error
    -- correction this design leans on would be a dead letter. RESTRICT for the
    -- same reason as everywhere else: a temple is purged through that one
    -- audited path or not at all.
    tenant_id             UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    -- The employment it was raised from. A ban is a decision made at a
    -- dismissal, not a free-standing entry about a stranger, and this column is
    -- what makes that structurally true.
    staff_profile_id      UUID        NOT NULL REFERENCES staff_profiles(id) ON DELETE RESTRICT,

    raised_by_user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    raised_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The reason is two things, and both are mandatory (KMS-4010). The category
    -- is what another temple can compare; the account is what carries the truth
    -- of it and is what turns a finding into a phone call between two
    -- administrators. Vocabulary in Java (BanCategory), not a CHECK constraint
    -- — same reasoning as AuditAction and JobTitle: adding a category a temple
    -- genuinely needs should be a line of code, not a migration.
    category              TEXT        NOT NULL,
    account               TEXT        NOT NULL,

    -- ---- The match signals -------------------------------------------
    -- Copied onto the record at the moment it is raised, rather than read
    -- through staff_profile_id when a check runs. Deliberate: a temple editing
    -- a former employee's phone number two years later must not silently
    -- rewrite what a ban raised in 2026 was about, and the staff row may in any
    -- case be purged with the temple while this record outlives it.

    -- PanCipher.fingerprint: HMAC-SHA256 of the PAN under the column key. It
    -- takes only the PAN — no tenant id, no per-tenant salt — so the same PAN
    -- yields the identical fingerprint at every temple. That is what makes an
    -- exact cross-temple match possible while revealing nothing: the value here
    -- is unusable to anyone without the key and cannot be reversed to a PAN.
    pan_fingerprint       TEXT,

    -- Kept in clear because the hiring admin is shown who they are looking at.
    full_name             TEXT        NOT NULL,
    -- Lowercased, punctuation stripped, whitespace collapsed. The fuzzy layer
    -- scores against this, never against full_name as typed.
    name_normalised       TEXT        NOT NULL,
    -- The same name split into tokens of three characters or more. Not a
    -- signal — a blocking key, so the matcher reads a handful of candidate rows
    -- instead of the table. Indexed GIN below.
    name_tokens           TEXT[]      NOT NULL,

    -- The last ten digits, so +919876543210 and 09876543210 are one number.
    phone_digits          TEXT,
    address_normalised    TEXT,

    -- ---- Aadhaar, matched without ever storing the number -------------
    -- The offline eKYC QR on an Aadhaar card is signed by UIDAI and yields the
    -- holder's name, date of birth and the last four digits of the number. That
    -- triple beats a typed Aadhaar number outright, because it cannot be
    -- fabricated — and it is not the number, so this table never holds one.
    -- The last-four CHECK below is not decoration: it makes storing a full
    -- twelve-digit number here structurally impossible rather than merely
    -- discouraged.
    --
    -- The QR capture itself is NOT in this build. These columns are the seam it
    -- lands on: a future AadhaarQrVerifier reading the signed payload would
    -- populate RaiseEmploymentBanRequest.aadhaar() and the hire check's
    -- AadhaarIdentity, and this arm of BanMatcher starts firing with no other
    -- change. Until then they are null and the arm is inert.
    aadhaar_name_normalised TEXT,
    aadhaar_date_of_birth   DATE,
    aadhaar_last4           TEXT,

    -- ---- Retraction ---------------------------------------------------
    -- A retracted record stays on file: the audit trail of a wrong entry is
    -- exactly what makes the error correctable rather than deniable. It simply
    -- stops appearing at a hire.
    retracted_at          TIMESTAMPTZ,
    retracted_by_user_id  UUID        REFERENCES users(id) ON DELETE RESTRICT,
    retraction_reason     TEXT,

    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT employment_bans_category_present CHECK (length(btrim(category)) > 0),

    -- The free text is mandatory in the database as well as in the service. A
    -- category on its own is an allegation with no account behind it, and it is
    -- the account that another temple's administrator actually acts on.
    CONSTRAINT employment_bans_account_present CHECK (length(btrim(account)) > 0),

    CONSTRAINT employment_bans_name_present CHECK (length(btrim(name_normalised)) > 0),

    -- Four digits. Never twelve.
    CONSTRAINT employment_bans_aadhaar_last4_shape CHECK (
        aadhaar_last4 IS NULL OR aadhaar_last4 ~ '^[0-9]{4}$'),

    -- The triple matches as a triple or not at all; two thirds of it is a false
    -- confidence, not a weaker signal.
    CONSTRAINT employment_bans_aadhaar_triple CHECK (
        (aadhaar_name_normalised IS NULL AND aadhaar_date_of_birth IS NULL AND aadhaar_last4 IS NULL)
     OR (aadhaar_name_normalised IS NOT NULL AND aadhaar_date_of_birth IS NOT NULL AND aadhaar_last4 IS NOT NULL)),

    CONSTRAINT employment_bans_retraction_whole CHECK (
        (retracted_at IS NULL) = (retracted_by_user_id IS NULL))
);

COMMENT ON TABLE employment_bans IS
    'Cross-temple record raised at a dismissal (B9). Owned by the raising temple; read by other temples only through match_employment_bans, only during a hire, and never as a list.';
COMMENT ON COLUMN employment_bans.pan_fingerprint IS
    'PanCipher.fingerprint of the PAN — identical at every temple, reversible by nobody. The exact cross-temple signal.';
COMMENT ON COLUMN employment_bans.name_tokens IS
    'Blocking key, not a signal. Narrows the candidate rows the matcher scores; the score decides what is a finding.';
COMMENT ON COLUMN employment_bans.aadhaar_last4 IS
    'From the signed UIDAI QR, never a typed number. Four digits by constraint, so a full Aadhaar number cannot be stored here.';

-- One live record per person per raising temple. Partial on retraction so that a
-- temple which retracted in error may raise a fresh record — both stay on file,
-- which is the point — while a second live one is refused as KMS-4964.
CREATE UNIQUE INDEX employment_bans_one_live_per_person
    ON employment_bans (tenant_id, staff_profile_id)
    WHERE retracted_at IS NULL;

-- The exact arm. Partial, because most rows will have no PAN on file.
CREATE INDEX employment_bans_pan_fingerprint
    ON employment_bans (pan_fingerprint) WHERE pan_fingerprint IS NOT NULL AND retracted_at IS NULL;

CREATE INDEX employment_bans_phone
    ON employment_bans (phone_digits) WHERE phone_digits IS NOT NULL AND retracted_at IS NULL;

-- The blocking key. array_ops is built in; no extension, which matters because
-- the migration role is not a superuser and holds no right to create one.
CREATE INDEX employment_bans_name_tokens ON employment_bans USING GIN (name_tokens);

-- The raising temple's own list.
CREATE INDEX employment_bans_by_temple
    ON employment_bans (tenant_id, raised_at DESC);

-- ---------------------------------------------------------------------
-- The policy: a temple sees the records it raised, and nothing else.
-- ---------------------------------------------------------------------
-- ENABLE without FORCE, for the reason set out in the header. Written out
-- longhand rather than through enable_tenant_rls() both because the column is
-- named tenant_id and because FORCE must not be applied — and a reader
-- who sees enable_tenant_rls() here would reasonably assume it had been.
ALTER TABLE employment_bans ENABLE ROW LEVEL SECURITY;

CREATE POLICY employment_bans_raising_temple ON employment_bans
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------
-- match_employment_bans — the only cross-temple read there is
-- ---------------------------------------------------------------------
-- SECURITY DEFINER, so it runs as the table's owner and is exempt from the
-- policy above (the table is not FORCEd). The application role is granted
-- EXECUTE and nothing more.
--
-- Every arm requires the caller to already know something specific about one
-- named person. There is no combination of arguments that returns the table:
-- all-null arguments match nothing, and an empty token array overlaps nothing.
-- The widest arm — a shared name token — is a *blocking* key, not a verdict;
-- the rows it returns are then scored in Java (BanMatcher) and only those over
-- the threshold are ever shown to anybody. So probing with a common surname
-- returns candidates the caller never sees.
--
-- Three further things stand between this and a lookup service, and they are
-- not in this function because they cannot be: the only caller is the hire
-- path, so a query cannot exist without a hire attempt behind it; a hire
-- attempt creates a staff record at the prober's own temple; and every call
-- lands on platform_audit_events, including the calls that find nothing —
-- which is exactly the call somebody fishing would make.
--
-- p_raised_after is a parameter rather than a hardcoded interval because the
-- ten-year fade is provisional (Rajeev to confirm the figure with the temple)
-- and the rest of that policy lives in Java, beside the constant that sets it.
CREATE OR REPLACE FUNCTION match_employment_bans(
    p_pan_fingerprint text,
    p_name_tokens     text[],
    p_phone_digits    text,
    p_aadhaar_name    text,
    p_aadhaar_dob     date,
    p_aadhaar_last4   text,
    p_raised_after    timestamptz)
RETURNS SETOF employment_bans
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT b.*
    FROM public.employment_bans b
    WHERE b.retracted_at IS NULL
      AND b.raised_at >= p_raised_after
      AND (
            (p_pan_fingerprint IS NOT NULL AND b.pan_fingerprint = p_pan_fingerprint)
         OR (p_phone_digits    IS NOT NULL AND b.phone_digits    = p_phone_digits)
         OR (p_aadhaar_last4 IS NOT NULL
             AND b.aadhaar_last4           = p_aadhaar_last4
             AND b.aadhaar_date_of_birth   = p_aadhaar_dob
             AND b.aadhaar_name_normalised = p_aadhaar_name)
         OR (p_name_tokens IS NOT NULL AND b.name_tokens && p_name_tokens)
      );
$$;

COMMENT ON FUNCTION match_employment_bans(text, text[], text, text, date, text, timestamptz) IS
    'The single cross-temple read of employment_bans. Takes one person''s identifying details and returns only rows that could be them; there is no argument that returns the table. Called solely by the check at hire (B9).';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kms_app') THEN
        GRANT EXECUTE ON FUNCTION match_employment_bans(text, text[], text, text, date, text, timestamptz) TO kms_app;
    END IF;
END;
$$;

-- ---------------------------------------------------------------------
-- staff_profiles: the PAN fingerprint, and what the check decided
-- ---------------------------------------------------------------------
-- **Why a fingerprint here at all, given the ban row carries its own copy.**
-- Without it, raising a ban would have to decrypt the employee's PAN to compute
-- one, and this codebase holds that every decryption of a PAN is a deliberate,
-- audited act by a named person (STAFF_PAN_VIEWED). Raising a ban is not a
-- request to see somebody's tax number, and it should not quietly become one.
-- With the fingerprint already on the row, it never does.
--
-- **The backfill, and why there is not one.** A SQL migration cannot compute
-- this column: the value is an HMAC under a key the application holds and the
-- database has never seen, over a plaintext the database has never seen either.
-- Three honest answers were available and the third was taken.
--
--   (a) Sweep at boot — decrypt every PAN in the estate on startup, per tenant,
--       and write the fingerprints. Rejected: it decrypts hundreds of PANs to
--       serve a handful that will ever be banned, in a process whose whole
--       posture is that decryption is rare and deliberate.
--   (b) Make the admin re-enter the PAN. Rejected: it puts the cost of our
--       migration on the person, on a screen that has nothing to do with it.
--   (c) **Nullable, filled forward, and filled for one row on demand.** Every
--       hire and every edit from now on computes it. A record predating this
--       migration gets its fingerprint computed at the one moment it is
--       actually needed — when a ban is raised against it — by decrypting that
--       single value inside the transaction, where it is never returned to
--       anybody and so is not a PAN read. Bounded, lazy, and correct for the
--       only population that matters.
--
-- No index: nothing looks a staff member up by fingerprint. The matching is on
-- employment_bans, which has one.
ALTER TABLE staff_profiles
    ADD COLUMN pan_fingerprint TEXT;

COMMENT ON COLUMN staff_profiles.pan_fingerprint IS
    'Blind index over the PAN (PanCipher.fingerprint), so raising a ban never has to decrypt one. Null on records predating V65; filled on the next write, or when a ban is raised.';

-- The Aadhaar triple, mirroring employment_bans. Same constraints, same reason:
-- never the number. Nothing writes these in this build — the signed-QR capture
-- is not built — and they exist so the ban record has somewhere to copy the
-- triple from the day it is.
ALTER TABLE staff_profiles
    ADD COLUMN aadhaar_name         TEXT,
    ADD COLUMN aadhaar_date_of_birth DATE,
    ADD COLUMN aadhaar_last4        TEXT,

    ADD CONSTRAINT staff_aadhaar_last4_shape CHECK (
        aadhaar_last4 IS NULL OR aadhaar_last4 ~ '^[0-9]{4}$'),

    ADD CONSTRAINT staff_aadhaar_triple CHECK (
        (aadhaar_name IS NULL AND aadhaar_date_of_birth IS NULL AND aadhaar_last4 IS NULL)
     OR (aadhaar_name IS NOT NULL AND aadhaar_date_of_birth IS NOT NULL AND aadhaar_last4 IS NOT NULL));

-- ---------------------------------------------------------------------
-- What the check found, and what the admin did about it, against the hire
-- ---------------------------------------------------------------------
-- A match never blocks a hire. The admin may proceed, and *hired anyway* is a
-- legitimate answer and often the right one — a hard block would move the
-- judgement from the person in the room to a matching algorithm, which is
-- precisely the failure this whole feature was designed against. So the check
-- returns findings, the hire proceeds, and the admin's answer is recorded here
-- against the hire itself, either way.
ALTER TABLE staff_profiles
    ADD COLUMN ban_check_id       UUID,
    ADD COLUMN ban_check_at       TIMESTAMPTZ,
    -- The findings as they were shown, frozen. The ban rows themselves may be
    -- retracted or amended later by their owner; what this admin was looking at
    -- when they decided must not change under them.
    ADD COLUMN ban_check_findings JSONB,
    ADD COLUMN ban_check_decision TEXT,

    ADD CONSTRAINT staff_ban_check_decision_valid CHECK (
        ban_check_decision IS NULL OR ban_check_decision IN ('NO_FINDINGS', 'PROCEEDED')),

    -- Recorded whole or not at all. Records hired before V65 have none of it.
    ADD CONSTRAINT staff_ban_check_whole CHECK (
        (ban_check_id IS NULL) = (ban_check_decision IS NULL));

COMMENT ON COLUMN staff_profiles.ban_check_decision IS
    'NO_FINDINGS, or PROCEEDED where the admin saw findings and hired anyway. A decision to stop leaves no staff row, so it is recorded on the platform audit log instead.';

-- ---------------------------------------------------------------------
-- Letting a temple admin append to the platform audit log — for this alone
-- ---------------------------------------------------------------------
-- Every check lands on platform_audit_events (V9), including the ones that find
-- nothing, because a query that found nothing is exactly the query somebody
-- fishing would run. But V9's policies admit only a verified super-admin, and
-- the actor here is a temple administrator hiring a cook.
--
-- The check cannot go on the temple's own audit log instead, and it is worth
-- being explicit about why: audit_events is readable by the temple, so writing
-- the findings there would hand every temple a permanent, searchable cache of
-- what the ban list says about people it did not hire. That is the lookup
-- service this design exists to prevent, rebuilt inside the audit viewer.
--
-- So this is a narrow, append-only escape, on the same shape as V9's own
-- policies: a signed-in user of any role may INSERT, but only rows about a ban
-- or a ban check, and only attributed to themselves. Reading remains
-- super-admin only — a temple can write to this log and can never read it,
-- which is the correct asymmetry for a record kept to catch the writer.
CREATE POLICY platform_audit_ban_events_insert ON platform_audit_events
    FOR INSERT
    WITH CHECK (
        entity_type IN ('EMPLOYMENT_BAN', 'EMPLOYMENT_BAN_CHECK')
        AND EXISTS (
            SELECT 1 FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.id = platform_audit_events.actor_user_id
        )
    );

-- ---------------------------------------------------------------------
-- employment_ban_raising_tenant — who owns a record, and nothing else
-- ---------------------------------------------------------------------
-- The row policy already hides another temple's records, so an attempt to
-- retract one could simply have come back as not-found. It does not, and the
-- case that decides it is a real one: a hiring temple shown a finding knows
-- that record's id, and may quite reasonably try to take it down. "Not found"
-- would leave them hunting a bug; KMS-4307 tells them whose record it is and
-- that the raising temple's name is on it — which is the telephone call this
-- whole design is trying to bring about.
--
-- So this returns the owning tenant's id and nothing else. No category, no
-- account, no name, no date. It confirms that a ban id somebody was already
-- given belongs to somebody else, and that is the entire disclosure.
CREATE OR REPLACE FUNCTION employment_ban_raising_tenant(p_ban uuid)
RETURNS uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT b.tenant_id FROM public.employment_bans b WHERE b.id = p_ban;
$$;

COMMENT ON FUNCTION employment_ban_raising_tenant(uuid) IS
    'The temple that raised one ban record, and nothing else about it. Exists so that another temple attempting to retract it is refused with KMS-4307 rather than a misleading not-found.';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kms_app') THEN
        GRANT EXECUTE ON FUNCTION employment_ban_raising_tenant(uuid) TO kms_app;
    END IF;
END;
$$;
