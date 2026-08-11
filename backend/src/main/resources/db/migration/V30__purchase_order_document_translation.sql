-- =====================================================================
-- V30 — PO document translation provenance (E5-S5)
--
-- A PO sheet can be rendered in the vendor's language. The `language` column
-- (from V12) already records which; this adds the MT engine that produced the
-- non-English text, the same provenance E2-S6 records on a recipe translation.
-- Ingredient names come from the glossary first, machine translation for the
-- rest, static labels from the template — so this names the engine consulted.
-- =====================================================================

ALTER TABLE documents ADD COLUMN translation_provider TEXT;
