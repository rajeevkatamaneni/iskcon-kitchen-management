-- =====================================================================
-- V84 — a dated, attributed, permanent note about somebody's conduct (E6-S16)
--
-- The reviewers asked for "remarks, to record behaviour". The obvious answer
-- was staff_profiles.notes, and it is the wrong one.
--
-- notes has existed since V57 as an unlabelled TEXT column, shown as a
-- single-line input called "Notes". It carries no author and no date, anybody
-- holding MANAGE_STAFF may overwrite it, and the next edit destroys what was
-- there without trace. Used to record conduct that is worse than recording
-- nothing: it is an employment record about a real person, and on the day it
-- matters — a dispute, a dismissal, a question from outside — nobody can say
-- who wrote it, when, or what it said before somebody edited it.
--
-- So conduct gets its own table, and notes stays exactly as it is, for
-- everything else. Nothing here reads it, writes it or migrates it.
--
-- ---------------------------------------------------------------------
-- Three columns, and the refusal to add a fourth
-- ---------------------------------------------------------------------
-- Author, timestamp, body. That is the whole record.
--
-- "Behaviour" slides into appraisal machinery very fast, and each step looks
-- reasonable on its own: a severity, so the serious ones stand out; a category,
-- so they can be counted; a type, so a formal warning is marked as one; an
-- acknowledgement, so the person has signed it. Every one of those is a
-- structured claim about a person that some future screen will sort, filter or
-- total — and nobody has yet named the reader who would act on the total. A
-- rating nobody reads is a permanent judgement about somebody's character kept
-- for no purpose, which is precisely the harm this table is trying to avoid.
--
-- If an enum of note types ever appears here, that decision is being reopened,
-- and it should be reopened out loud rather than in a migration.
--
-- ---------------------------------------------------------------------
-- Append-only, and why it is not fussiness
-- ---------------------------------------------------------------------
-- A note that can be edited afterwards is worth nothing on the day it counts.
-- The whole value of "written on the 3rd of March by the head cook" is that it
-- was written on the 3rd of March by the head cook and has not moved since.
-- An editable note is not evidence of anything; it is the current opinion of
-- whoever edited it last, which is what notes already is.
--
-- make_append_only() is this schema's existing mechanism (V49/V50), used by the
-- stock ledger, the audit log, equipment state changes and — as of V83 —
-- vendor status changes. It grants UPDATE and DELETE back to the owner and the
-- application role, because PostgreSQL's own foreign-key checks need them to
-- take a FOR KEY SHARE lock, and then refuses both with a BEFORE trigger. The
-- integration test proves the refusal against the unprivileged application role
-- itself rather than trusting the annotation.
--
-- A note written in error is corrected the way every other append-only record
-- in this system is corrected: by adding another one that says so. There is
-- deliberately no retraction flag — a retraction is just a later note, and a
-- flag would be a fourth column arriving through the back door.
--
-- ---------------------------------------------------------------------
-- What this is deliberately NOT connected to: the ban record (V65)
-- ---------------------------------------------------------------------
-- This schema holds a cross-temple employment ban (employment_bans, V65 / E9-S2)
-- — the one record a temple writes and other temples read. The two are kept
-- apart on purpose, in both directions, and the separation is a design decision
-- rather than an oversight:
--
--   * **Nothing reads a conduct note into the ban path.** A ban is raised at a
--     dismissal, from words the administrator writes at that moment and signs
--     their name to (employment_bans.account, mandatory in the database as well
--     as the service). It must never be assembled out of remarks other people
--     wrote about somebody months earlier for a different purpose. There is no
--     foreign key from employment_bans to this table and no column here that a
--     ban could read.
--
--   * **Nothing in the ban flow surfaces a conduct note.** BL-6 and the V65
--     header record why: a ban is an accusation about a private individual, its
--     subject is never shown it in the app, and it travels to another temple
--     only as an answer to one specific question at one specific hire. Widening
--     what travels — from "one sentence an administrator stands behind" to "the
--     internal remarks file" — would change what this platform publishes about
--     a person, and that is not a change to make as a side effect of adding a
--     notes panel.
--
--   * **Different readers, different permissions.** A ban is MANAGE_STAFF; a
--     conduct note is MANAGE_STAFF_CONDUCT_NOTES. Holding one has never implied
--     holding the other and nothing here makes it so.
--
-- Connecting them may one day be the right thing. It would be its own decision,
-- with its own story, and it is not this one.
-- =====================================================================

CREATE TABLE staff_conduct_notes (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,

    staff_profile_id UUID        NOT NULL REFERENCES staff_profiles(id) ON DELETE RESTRICT,

    -- What was written. Never edited, so this is what it said.
    body             TEXT        NOT NULL,

    -- Who wrote it, and when. Both are the point of the table: a remark with
    -- neither is the column this replaces.
    author_user_id   UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT staff_conduct_note_body_present CHECK (length(btrim(body)) > 0),

    -- A ceiling, so one note cannot become a filing cabinet. Long enough for an
    -- account of what happened; short enough that the panel stays readable.
    CONSTRAINT staff_conduct_note_body_bounded CHECK (length(body) <= 4000)
);

COMMENT ON TABLE staff_conduct_notes IS
    'Dated, attributed, append-only notes about a staff member''s conduct (E6-S16). Read and written behind MANAGE_STAFF_CONDUCT_NOTES alone. Deliberately unconnected to employment_bans (V65).';
COMMENT ON COLUMN staff_conduct_notes.body IS
    'What was written, as it was written. Never edited — a correction is a later note.';
COMMENT ON COLUMN staff_conduct_notes.author_user_id IS
    'The person who wrote it. Not the person it is about; that is staff_profile_id.';

-- Newest first, per person: the only query this table has.
CREATE INDEX staff_conduct_notes_by_person
    ON staff_conduct_notes (tenant_id, staff_profile_id, created_at DESC);

SELECT enable_tenant_rls('staff_conduct_notes');
SELECT make_append_only('staff_conduct_notes');
