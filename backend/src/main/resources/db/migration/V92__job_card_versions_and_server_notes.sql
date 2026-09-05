-- =====================================================================
-- V92 — A job card knows which version of itself it is
--
-- Rajeev, 2026-09-05, describing the failure this exists to prevent:
--
--     "if some one prints a job card and there were last minute changes
--      done and a new job card is re printed, and different people have
--      differnt version od the job card in the kitchen and how do we tell
--      which one id correct? The lastest version number must be the
--      correct one."
--
-- So every sheet carries `v3` in its footer, on every page, beside the
-- card number it has always had.
--
-- ---------------------------------------------------------------------
-- Why a fingerprint and not a counter
--
-- The obvious build is an integer bumped wherever the application changes
-- a meal. It was rejected, and the reason is the whole point of the
-- feature: a bump that is *forgotten* at one of those sites prints a
-- changed plan under an unchanged version number, which is worse than no
-- version number at all — two different sheets that both claim to be v2,
-- and a kitchen with no way to tell. The failure is silent and it lands on
-- the one sheet somebody is about to cook from.
--
-- So the version is derived rather than maintained. At print time the
-- card's own renderer hashes exactly the fields it is about to print; if
-- that hash differs from the one stored here, the version goes up by one
-- and the new hash is kept. Two consequences follow, and both are the
-- behaviour Rajeev asked for:
--
--   * A field cannot be missed. Anything the card prints is in the hash by
--     construction, because the hash is taken from the print model.
--   * Printing an unchanged plan twice does not bump. Two sheets reading
--     v2 ARE the same sheet, so nobody hunts for a difference that is not
--     there — and the printed timestamp beside the version says which of
--     the two came off the printer later.
--
-- What is deliberately NOT in the hash: who is rostered. The roster moves
-- daily from the staff schedule, and a card whose cooking instructions are
-- identical must not climb to v9 because three volunteers swapped shifts.
-- Also excluded: the appendix language and whether recipes were asked for.
-- Those are choices made by the person at the printer, not changes to the
-- meal — printing the same lunch in Kannada is not a new version of it.
--
-- ---------------------------------------------------------------------
-- Notes for the servers
--
-- The serving sheet (a new page in the same pack) had nothing to say on
-- it: the meal carries `kitchen_notes` and there was no equivalent for the
-- people handing food out. One column, sitting beside the one it mirrors.
-- =====================================================================

ALTER TABLE meal_services
    ADD COLUMN card_version      INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN card_fingerprint  TEXT,
    ADD CONSTRAINT meal_services_card_version_non_negative
        CHECK (card_version >= 0);

COMMENT ON COLUMN meal_services.card_version IS
    'Which version of this job card has been printed (V92). Zero until the first print. Goes up by one whenever the printed content differs from card_fingerprint, never on a reprint of an unchanged meal — the footer of every page carries it so that two people holding two sheets can tell which is current.';

COMMENT ON COLUMN meal_services.card_fingerprint IS
    'A hash of everything the last printed card actually said, taken from the print model itself so that no field can be left out of it by oversight. Compared on every print to decide whether card_version moves.';

ALTER TABLE meal_plans
    ADD COLUMN server_notes TEXT
        CHECK (server_notes IS NULL OR length(server_notes) <= 2000);

COMMENT ON COLUMN meal_plans.server_notes IS
    'Anything the people serving this meal need to know (V92) — the mirror of kitchen_notes, and what the serving sheet of the job card is for. A whole-meal fact written onto each dish row, read back the way kitchen_notes is.';
