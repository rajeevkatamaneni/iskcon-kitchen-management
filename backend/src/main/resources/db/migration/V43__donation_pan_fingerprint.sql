-- =====================================================================
-- V43 — PAN fingerprint for donor matching (E7-S7)
--
-- The ledger's donor drill-down matches a donor across gifts by account, else by
-- PAN, else by exact contact. PAN is stored encrypted with a random IV, so two
-- gifts from the same PAN have different ciphertext and can't be matched by it. A
-- keyed fingerprint (HMAC of the normalised PAN) is a blind index: deterministic
-- for matching, and it reveals nothing without the key. Conservative matching —
-- no fuzzy merging — per the locked release-1 rule.
-- =====================================================================

ALTER TABLE donations ADD COLUMN pan_fingerprint TEXT;
ALTER TABLE recurring_plans ADD COLUMN pan_fingerprint TEXT;

CREATE INDEX donations_pan_fingerprint ON donations (tenant_id, pan_fingerprint)
    WHERE pan_fingerprint IS NOT NULL;
