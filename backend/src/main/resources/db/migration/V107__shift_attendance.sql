-- =====================================================================
-- V107 — Who actually turned up
--
-- B7. `shift_signups` has recorded, since V34, that somebody claimed a
-- spot and whether they later gave it back. It has never recorded
-- whether they came. A no-show could not be written down at all, and so
-- no reliability figure and no hours-contributed figure could ever be
-- computed for anybody — not because the sums were wrong, but because
-- the fact they would sum does not exist.
--
-- ---------------------------------------------------------------------
-- Why `attended` is a NULLABLE boolean, and why that is the whole point
--
-- Three states, not two:
--
--     TRUE   they came
--     FALSE  they did not come
--     NULL   nobody has said
--
-- A plain `BOOLEAN NOT NULL DEFAULT false` would have been simpler and
-- would have been wrong in the most expensive way available: every shift
-- nobody got round to marking would read as a roster of no-shows, and
-- the first thing built on top of this column — "this devotee turns up
-- four times in five" — would quietly libel every volunteer at a temple
-- that has not started marking attendance yet. "Did not come" and "was
-- never asked" are different facts and every figure downstream has to be
-- able to tell them apart, so the column says so.
--
-- The same reasoning is carried through to the client: `attended` is
-- `boolean | null` on RosterSignup, required-and-nullable rather than
-- optional, so a reader cannot forget the third case.
--
-- ---------------------------------------------------------------------
-- The CHECK
--
-- `attended` and `attendance_recorded_at` move together, exactly as
-- V95's meal link does. A mark with no time is a statement with no
-- provenance, and a time with no mark is a record that somebody marked
-- something and lost it. Neither is a state worth being able to reach,
-- and the application-facing guard for a second marking (KMS-400139)
-- reads `attendance_recorded_at`, so a row that carried one without the
-- other would make that guard answer nonsense.
--
-- Deliberately NOT added: `attendance_recorded_by`. It is the obvious
-- third column and nothing would read it. The roster shows the mark, not
-- the marker; there is no correction endpoint yet to attribute a change
-- to; and this project's own habit is to refuse a column that nothing
-- reads (IngredientRequestStatus.java:29-32, in those words: "would add
-- a state that nothing reads"). When corrections arrive — the next step
-- on KMS-400139 says "Change it on the shift's roster", so they will —
-- the marker and the correction history should be designed together
-- rather than half-guessed here.
--
-- ---------------------------------------------------------------------
-- Nullable, and therefore no backfill — which matters here
--
-- `shift_signups` is tenant-owned and carries FORCE ROW LEVEL SECURITY
-- (V34:65), and Flyway runs unprivileged in this project precisely so
-- that a migration which only works as a superuser fails in the suite
-- instead of on a deployment. A cross-tenant UPDATE here would not fail
-- loudly: it would match nothing and report success. So there is no
-- backfill statement at all, and none is needed — every signup that
-- exists today is unmarked, and NULL is the honest reading of every one
-- of them. Two nullable columns default to NULL, and that IS the
-- backfill.
--
-- ALTER TABLE is DDL and runs as the table's owner (the migration role),
-- which RLS does not constrain; the policy governs the DML this
-- migration does not do.
--
-- ---------------------------------------------------------------------
-- No index
--
-- Attendance is read one shift at a time, through the roster, which
-- already loads that shift's signups by `shift_signups_by_shift`. The
-- reliability and hours figures this column exists to make possible do
-- not exist yet, and an index guessed for a query nobody has written is
-- paid for on every write and read by nothing.
-- =====================================================================

ALTER TABLE shift_signups
    ADD COLUMN attended               BOOLEAN,
    ADD COLUMN attendance_recorded_at TIMESTAMPTZ;

ALTER TABLE shift_signups ADD CONSTRAINT shift_signups_attendance_whole CHECK (
    (attended IS NULL AND attendance_recorded_at IS NULL)
    OR (attended IS NOT NULL AND attendance_recorded_at IS NOT NULL)
);

COMMENT ON COLUMN shift_signups.attended IS
    'B7: TRUE they came, FALSE they did not, NULL nobody has said yet. The third state is load-bearing — an unmarked shift must never read as a roster of no-shows.';
COMMENT ON COLUMN shift_signups.attendance_recorded_at IS
    'B7: when attendance was marked for this signup. Its presence anywhere on a shift is what makes a second blanket marking KMS-400139.';
