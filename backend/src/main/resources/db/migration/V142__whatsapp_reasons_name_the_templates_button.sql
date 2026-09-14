-- =====================================================================
-- V142 — stored WhatsApp template reasons stop saying "Press Reload"
--        (T-200, finishing T-188)
--
-- ---------------------------------------------------------------------
-- 1. Why
--
-- tenant_settings.whatsapp_refused_templates (V128) keeps, per temple, a
-- sentence for each template the last send left needing attention. From
-- 44f40e9 (T-169a, 2026-09-12) to 5cb4550 (T-188, 2026-09-13) the code
-- wrote sentences that told the administrator to "Press Reload". No
-- button on the screen is labelled Reload: its label says what is
-- waiting. T-188 rewrote the code's sentences to name "the templates
-- button in the WhatsApp section of Settings", and said rows already
-- stored keep the old words until the next send replaces them.
--
-- Nothing can show that no row holds them. That code was deployed to
-- staging, where South Bengaluru sent its templates, and a stored list
-- is only replaced by a later send. So they are rewritten here.
--
-- ---------------------------------------------------------------------
-- 2. What is rewritten
--
-- Exactly the six sentences 5cb4550 changed in
-- TenantWhatsAppSettingsService, each to the sentence the code writes now,
-- matched whole. The brief for T-200 named the first three; the other
-- three said "Press Reload" too, and leaving them would leave the defect:
--
--   NOT_TOLD_WHAT_META_HOLDS, STILL_IN_REVIEW, EDIT_LIMIT,
--   IN_REVIEW_OR_EDIT_LIMIT, NOT_REACHED, and plainReason's fallback.
--
-- A reason is replaced only when it equals an old sentence exactly, so a
-- sentence written by any other version of the code is left as it is.
-- Every other field of an entry, its name and its kind, and the order of
-- the list, are kept. A list with no old sentence in it is not written.
--
-- T-159's older "Press Save to try again" sentence is not touched: T-168
-- and T-169a already covered it, and staging's row was replaced by later
-- sends (see WhatsAppTemplateSubmissionIT).
--
-- ---------------------------------------------------------------------
-- 3. RLS: one temple at a time
--
-- tenant_settings is under FORCE ROW LEVEL SECURITY and migrations run as
-- a role that does not bypass it, so a plain UPDATE here would match no
-- row at all. The loop adopts each temple in turn, as V121 and V137 do,
-- and clears the setting when it is done.
-- =====================================================================

DO $$
DECLARE
    t           RECORD;
    n           INTEGER;
    rewritten   INTEGER := 0;
    button      CONSTANT TEXT := 'the templates button in the WhatsApp section of Settings';
    old_reasons TEXT[];
    new_reasons TEXT[];
BEGIN
    -- Two arrays, paired by position and unnested together below. Arrays rather than a temporary table,
    -- so the migration needs no privilege beyond the table it already owns.
    old_reasons := ARRAY[
        'Meta did not say which wording it holds for this message. Press Reload to try again.',
        'Meta is still reviewing this message, so its new wording has to wait. Press Reload again once the review is over.',
        'Meta allows a message to be reworded only once a day and ten times a month. Press Reload again tomorrow.',
        'Meta is not taking new wording for this message yet, because of a review or a recent change. Press Reload again tomorrow.',
        'Meta could not be reached while this message was being registered. Press Reload to try again.',
        'Meta did not accept this message. Press Reload to try again, and if it is refused again, report it with the message name shown here.'];
    new_reasons := ARRAY[
        'Meta did not say which wording it holds for this message. Try again with ' || button || '.',
        'Meta is still reviewing this message, so its new wording has to wait. Once the review is over, use ' || button || '.',
        'Meta allows a message to be reworded only once a day and ten times a month. Use ' || button || ' tomorrow.',
        'Meta is not taking new wording for this message yet, because of a review or a recent change. Use ' || button || ' tomorrow.',
        'Meta could not be reached while this message was being registered. Try again with ' || button || '.',
        'Meta did not accept this message. Try again with ' || button || ', and report it with the message name shown here if it is refused again.'];

    FOR t IN SELECT id FROM tenants ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        UPDATE tenant_settings s
        SET whatsapp_refused_templates = (
                SELECT COALESCE(jsonb_agg(
                           CASE WHEN r.new_reason IS NULL THEN e.entry
                                ELSE jsonb_set(e.entry, '{reason}', to_jsonb(r.new_reason)) END
                           ORDER BY e.position), '[]'::jsonb)
                FROM jsonb_array_elements(s.whatsapp_refused_templates) WITH ORDINALITY AS e(entry, position)
                LEFT JOIN unnest(old_reasons, new_reasons) AS r(old_reason, new_reason)
                       ON r.old_reason = e.entry ->> 'reason')
        WHERE s.tenant_id = t.id
          AND EXISTS (
                SELECT 1 FROM jsonb_array_elements(s.whatsapp_refused_templates) AS x(entry)
                WHERE x.entry ->> 'reason' = ANY (old_reasons));
        GET DIAGNOSTICS n = ROW_COUNT;
        rewritten := rewritten + n;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);
    RAISE NOTICE 'V142: rewrote the stored WhatsApp template reasons of % temple(s)', rewritten;
END
$$;
