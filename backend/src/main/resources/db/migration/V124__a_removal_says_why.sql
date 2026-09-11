-- =====================================================================
-- V124 — Taking a volunteer off a roster has to say why
--        (T-080; Rajeev's review of 2026-09-08)
--
-- Until now a coordinator's removal wrote `released_at` and nothing
-- else, which is the same row a volunteer stepping off their own shift
-- writes. Two consequences, and the second is the defect this exists
-- to close. The roster could not tell "she withdrew" from "we took her
-- off", and — because nothing recorded a reason — there was nothing
-- safe to put in a message, so the person who LOST the shift was told
-- nothing at all while the waitlisted volunteer promoted into their
-- place got a cheerful "a spot opened, you're in".
--
-- Rajeev's ruling is two fields, not one, and the whole design is in
-- one sentence of it: "the coordinator must say why either way; the
-- volunteer gets the version that does not sting."
--
--   `released_reason` — one of four the coordinator picks. Safe to
--       show, and it is what goes in the message to the volunteer.
--   `released_note`   — mandatory free text, INTERNAL. It reaches the
--       audit trail and this roster and goes nowhere else.
--
-- ---------------------------------------------------------------------
-- Why these are columns on `shift_signups` and not a table of their own
--
-- A removal is a fact about one person's place on one shift, and this
-- row already carries the other half of it in `released_at`. Keeping
-- the pair beside the timestamp they qualify is the same reasoning
-- V110 gives for `attendance_corrected_at`: the two cannot drift apart
-- if they cannot be written apart, and the roster reads them without a
-- join.
--
-- A `shift_signup_removals` table was the alternative and is worse
-- here. A signup is released at most once — the unique index
-- `shift_signups_one_active` (V34:62) is partial on `released_at IS
-- NULL`, so signing up again after a removal makes a NEW row rather
-- than reviving this one — so such a table would be strictly 1:0..1,
-- joined on every roster read, to carry two columns.
--
-- ---------------------------------------------------------------------
-- Nullable, because the volunteer's own release has no reason to give
--
-- `POST /shifts/{id}/release` is the volunteer stepping off their own
-- shift, and it neither has nor should have a reason: a devotee is not
-- asked to justify withdrawing, and "no reason recorded" is the honest
-- state of that row. So NOT NULL is impossible here, and the presence
-- of a reason is itself the fact that distinguishes the two acts —
-- which is what the roster now reads to say "removed by the temple"
-- rather than "released".
--
-- The CHECKs carry what NOT NULL cannot:
--
--   `..._removal_is_whole` — the two arrive together or not at all.
--     Rajeev asked for BOTH fields, and a row carrying a reason with no
--     note is a coordinator who did not have to think about it, which
--     is exactly what the note exists to make them do. The service
--     refuses it first by name (Bean Validation, KMS-400001, with the
--     field named); this is here so no later write path — a fixture, a
--     support script, a migration — can produce the state at all.
--
--   `..._removal_follows_release` — a reason on a row nobody released
--     is nonsense, in the shape V110's `..._correction_follows_recording`
--     refuses for a correction of a mark that was never made.
--
--   `..._released_reason_valid` — the four Rajeev named, and no fifth.
--     A free-text code would let a coordinator's typo reach a volunteer
--     through the message template, since the reason is the ONE half of
--     this that is sent. The vocabulary is CHECKed rather than made an
--     enum type for the reason every other status column here is
--     (`shift_signups_source_valid`, V34:59): adding a value to a CHECK
--     is one migration, adding one to a PostgreSQL enum is a migration
--     that cannot run inside a transaction.
--
-- No CHECK on the note's length. The service caps it (@Size) so a
-- coordinator gets a field-named refusal rather than a database error,
-- and TEXT has no storage reason to care.
--
-- ---------------------------------------------------------------------
-- No backfill, and therefore nothing here that RLS can silently skip
--
-- `shift_signups` is tenant-owned and carries FORCE ROW LEVEL SECURITY
-- (V34:65), and Flyway runs unprivileged in this project so that a
-- migration which only works as a superuser fails in the suite rather
-- than on a deployment. NULL already means what every existing released
-- row means — nobody recorded a reason, because there was no way to —
-- and inventing one for rows written before the feature existed would
-- be the audit trail's cardinal sin, a record of something nobody said.
-- So there is nothing to write per tenant, and the cross-tenant UPDATE
-- that would have matched no rows and reported success is not here to be
-- got wrong. ALTER TABLE is DDL and runs as the table's owner, which RLS
-- does not constrain.
--
-- No index either, for V107's and V110's reason unchanged: these are
-- read off a row the roster has already found by `shift_signups_by_shift`,
-- and nothing queries for removals by reason.
-- =====================================================================

ALTER TABLE shift_signups
    ADD COLUMN released_reason TEXT,
    ADD COLUMN released_note   TEXT,

    ADD CONSTRAINT shift_signups_released_reason_valid CHECK (
        released_reason IS NULL
        OR released_reason IN ('SHIFT_CANCELLED', 'NO_LONGER_NEEDED', 'ROTA_CHANGED', 'OTHER')),

    ADD CONSTRAINT shift_signups_removal_is_whole CHECK (
        (released_reason IS NULL) = (released_note IS NULL)),

    ADD CONSTRAINT shift_signups_removal_follows_release CHECK (
        released_reason IS NULL OR released_at IS NOT NULL);

COMMENT ON COLUMN shift_signups.released_reason IS
    'T-080: why a coordinator took this volunteer off the roster, from the four the product offers. This is the half the volunteer is told, rendered into a plain sentence by the REMOVED_FROM_SHIFT message. Null on a volunteer''s own release, which has no reason to give and is not asked for one — so a non-null value here is also how the roster tells the temple''s act from the devotee''s.';
COMMENT ON COLUMN shift_signups.released_note IS
    'T-080: the coordinator''s own note on why, mandatory beside a reason and INTERNAL. It reaches the temple''s audit trail and this shift''s roster and is never sent to anybody: the point of the pair is that a coordinator who must write a private note has thought about it, while a volunteer who reads "the rota changed" is not told they were unreliable.';
