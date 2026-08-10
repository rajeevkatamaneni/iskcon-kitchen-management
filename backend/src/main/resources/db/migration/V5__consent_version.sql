-- =====================================================================
-- V5 — Communication consent version (E1-S8)
--
-- users.contact_consent_at (V2) records WHEN a person agreed to be contacted.
-- This records WHICH wording they agreed to. A bare timestamp cannot prove what
-- was consented to once the text changes, and DPDP consent is to a stated
-- purpose — so pinning the version is what lets us re-ask when the purpose text
-- is revised, and prove after the fact what each person accepted.
--
-- Nullable: null until the person consents, alongside a null contact_consent_at.
-- No RLS or grant change — users is already tenant-owned and the app role
-- already holds DML on it.
-- =====================================================================

ALTER TABLE users ADD COLUMN consent_version TEXT;

COMMENT ON COLUMN users.consent_version IS
    'Version of the communication-consent text the user accepted; null until they consent. Paired with contact_consent_at.';
