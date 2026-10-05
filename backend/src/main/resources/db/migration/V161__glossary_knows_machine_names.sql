-- =====================================================================
-- V161 — A glossary entry says whether a person wrote it (E10-T8c)
--
-- The Kitchen Assistant names stock in Kannada. Left to itself the model
-- spelt 12 of 28 items more than one way across five repeats of the same
-- question (mace came out seven ways; docs/work/proof/E10-T8b.md). Rajeev
-- decided on 2026-10-04 that the names come from the app, never from the
-- model: every stock item gets one Kannada name, stored once, and the
-- assistant reads it.
--
-- The store is the glossary the temple already keeps (V13), because that
-- is the name recipe sheets, purchase orders and work orders already use.
-- Where the temple has not typed a name, it is translated by machine once
-- and inserted here. That makes two kinds of row in one table, and they
-- must not be indistinguishable: a temple that trusts its glossary has to
-- be able to see which names nobody has checked. Google rendered "mace"
-- as the word for the weapon when this was built.
--
--   machine_translated_at   when the machine wrote the row; NULL means a
--                           person did (every row that exists today)
--   machine_provider        which engine wrote it ('google', 'stub', ...),
--                           so a row the local stub wrote is never
--                           mistaken for a real translation
--
-- The two are set together or not at all. A person's correction through
-- the glossary screen clears both (GlossaryService.upsert), so a corrected
-- name stops being listed as unchecked.
--
-- No backfill: every existing row was typed by a person, which is exactly
-- what NULL says. So this migration touches no rows and RLS does not
-- arise; the table's policy from V13 already covers the new columns.
-- =====================================================================

ALTER TABLE translation_glossary
    ADD COLUMN machine_translated_at TIMESTAMPTZ,
    ADD COLUMN machine_provider      TEXT,
    ADD CONSTRAINT translation_glossary_machine_mark_whole CHECK (
        (machine_translated_at IS NULL) = (machine_provider IS NULL));
