-- =====================================================================
-- V162 — A person chooses their language, and a conversation keeps one
--        (E10-LANG)
--
-- Rajeev's rule for the Kitchen Assistant (2026-10-06, his words): "the
-- agent starts the conversation in english. If the user's account has his
-- language preference set to something other than english, then the agent
-- has to start in that language. Once a language is set, the agent has to
-- fully communicate in that language only UNTIL the user asks to switch to
-- a different language." On 2026-10-07 he chose to keep that preference on
-- each person's account rather than on the temple: a Bengaluru kitchen with
-- Odia and Assamese cooks has no single language.
--
--   users.preferred_language           the person's own choice, set on
--                                      their profile; 'en' until they set
--                                      one
--   assistant_conversations.language   the language this conversation is
--                                      in now. Copied from the person's
--                                      preference when the conversation is
--                                      started, and changed only when they
--                                      ask the assistant to switch
--                                      (set_conversation_language). Kept
--                                      on the conversation, not read from
--                                      the profile each time, because the
--                                      rule is that a conversation stays
--                                      in its language until asked: a
--                                      change of preference applies to the
--                                      next conversation, not the open one.
--
-- The CHECK lists the same 23 codes as translation/Languages.java and
-- frontend/lib/languages.ts (English and the 22 scheduled languages). The
-- three lists are kept in step by hand; a code added to the Java list and
-- not here fails on the first save, loudly, rather than storing a code the
-- rest of the app cannot render.
--
-- No backfill. ADD COLUMN with a constant DEFAULT fills every existing row
-- from the catalogue, without an UPDATE, so RLS does not arise (V161 has
-- the same note). Both tables' policies already cover the new columns.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN preferred_language TEXT NOT NULL DEFAULT 'en',
    ADD CONSTRAINT users_preferred_language_known CHECK (preferred_language IN (
        'en',
        'as', 'bn', 'brx', 'doi', 'gu', 'hi', 'kn', 'ks', 'gom', 'mai', 'ml',
        'mni-Mtei', 'mr', 'ne', 'or', 'pa', 'sa', 'sat', 'sd', 'ta', 'te', 'ur'));

COMMENT ON COLUMN users.preferred_language IS
    'E10-LANG: the language this person wants the Kitchen Assistant to start in. A code from translation/Languages.java.';

ALTER TABLE assistant_conversations
    ADD COLUMN language TEXT NOT NULL DEFAULT 'en',
    ADD CONSTRAINT assistant_conversations_language_known CHECK (language IN (
        'en',
        'as', 'bn', 'brx', 'doi', 'gu', 'hi', 'kn', 'ks', 'gom', 'mai', 'ml',
        'mni-Mtei', 'mr', 'ne', 'or', 'pa', 'sa', 'sat', 'sd', 'ta', 'te', 'ur'));

COMMENT ON COLUMN assistant_conversations.language IS
    'E10-LANG: the language the assistant replies in for this conversation. Set from the person''s preference at start; changed only by set_conversation_language.';
