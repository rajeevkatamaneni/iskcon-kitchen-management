-- =====================================================================
-- V60 — Reading a sent communication without belonging to the temple (E8-S2)
--
-- The web copy is the whole of what WhatsApp can carry — Meta will not deliver
-- a newsletter, only a template and a link — and it is what "read this in your
-- browser" opens in every email. Both are read by people who are not signed in,
-- and often by people who are not signed in *anywhere*.
--
-- So this needs the same shape of escape the sign-in lookup and the payment
-- webhook already use: not a hole in isolation, but a policy narrow enough that
-- what it exposes is exactly what the caller already had.
--
-- What makes it narrow:
--
--   * it matches on the unguessable public_token, which is 122 bits of
--     randomness and is not derived from the row's id — holding one link tells
--     you nothing about any other;
--   * it is FOR SELECT only, so nothing can be written through it;
--   * it admits only SENT rows, so nothing half-written is ever readable; and
--   * app.public_communication_token is set by exactly one endpoint, for the
--     duration of one read, from the address in the URL.
--
-- The reader learns the letter that was mailed to them. That is the whole of it.
-- =====================================================================

CREATE POLICY communications_public_copy ON communications
    FOR SELECT
    USING (
        status = 'SENT'
        AND public_token = NULLIF(current_setting('app.public_communication_token', true), ''));

COMMENT ON POLICY communications_public_copy ON communications IS
    'Reads one sent communication by its unguessable address, for the web copy an email or WhatsApp link opens (E8-S2).';
