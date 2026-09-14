-- =====================================================================
-- V141 — the temple's Meta App ID, so the purchase order can carry its
--        PDF on WhatsApp (T-200)
--
-- ---------------------------------------------------------------------
-- 1. Why
--
-- Rajeev decided the purchase-order message sends the order sheet as a
-- PDF: "PDF's survue transmission and different devices better than
-- anything else." A WhatsApp template with a document header can only be
-- registered with a sample document, and Meta issues the sample's handle
-- through its Resumable Upload API, which is addressed to an app:
-- POST /<APP_ID>/uploads
-- (https://developers.facebook.com/docs/graph-api/guides/upload).
--
-- Until now each temple stored the phone number id, the business account
-- id, the access token and the app secret, and no app id anywhere. So the
-- Settings → WhatsApp screen gains an App ID box, and this is its column.
--
-- ---------------------------------------------------------------------
-- 2. The shape
--
-- TEXT, nullable. Null means the temple has not entered one, which is the
-- true statement about every existing row, and every temple can still
-- send every other message. Without it the purchase-order template is not
-- registered, and the stored refusal reason for it names the App ID box.
--
-- Digits only when present. Meta's app ids are numbers written as text
-- (South Bengaluru's shows on its app page), and the check stops a pasted
-- app secret or token landing here, where it would be shown in full.
-- The length allows for Meta's ids growing, not for anything else.
--
-- Not a secret, and kept with the other two Meta ids rather than in
-- TenantSecretStore: an app id alone cannot send anything or read
-- anything, it is printed on the app's dashboard, and the screen shows it
-- in full so an administrator can see what they pasted.
--
-- ---------------------------------------------------------------------
-- 3. RLS, and why there is no tenant loop
--
-- tenant_settings has carried enable_tenant_rls() since V36. ADD COLUMN
-- is DDL, run as the table owner, and touches no rows, so there is nothing
-- to backfill per tenant, the reason V128 gives for the same shape. Reads
-- and writes of the column go through the table's ordinary tenant policy.
--
-- The V55 webhook-lookup SELECT policy lets an unauthenticated Meta
-- callback holding a temple's opaque token read that temple's row, which
-- now includes this id. It is an id Meta prints on the app's own page,
-- and cannot send a message or identify a person.
-- =====================================================================

ALTER TABLE tenant_settings
    ADD COLUMN whatsapp_app_id TEXT,
    ADD CONSTRAINT tenant_settings_whatsapp_app_id_is_digits
        CHECK (whatsapp_app_id IS NULL OR whatsapp_app_id ~ '^[0-9]{1,32}$');

COMMENT ON COLUMN tenant_settings.whatsapp_app_id IS
    'The temple''s Meta App ID (T-200), which the Resumable Upload API is addressed to when the purchase-order template is registered with its sample PDF. Digits only; not a secret. Null until an administrator enters it, and then the purchase-order template is not registered.';
