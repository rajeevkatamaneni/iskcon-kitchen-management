-- =====================================================================
-- V4 — First sign-in account claim (E1-S6 defect / UAT1-D1)
--
-- Provisioning — and later invites (E1-S12) — create a user row before that
-- person has ever authenticated, with a placeholder firebase_uid of
-- 'pending:<uuid>'. On their first real sign-in Firebase issues a genuine uid
-- that matches no row, so without a way to bind the two a provisioned admin
-- can never actually get in. (E1-S6's test masked this by updating the uid by
-- hand.)
--
-- The bind is a "claim": on a uid miss, if the token carries a Firebase-
-- verified contact, the application adopts the real uid onto the pending row
-- whose email or phone matches. Finding that row happens before any tenant is
-- known, so RLS would hide it — the same chicken-and-egg the app.auth_uid
-- escape already solves for the ordinary lookup. This adds the sibling escape,
-- app.claim_contact, set by the authentication filter only during a claim and
-- only to an email Firebase reports verified or a phone (whose presence is
-- itself proof of OTP verification).
--
-- The escape is deliberately narrow. It exposes ONLY rows still pending
-- ('pending:%') AND whose email or phone equals the exact contact the filter
-- set. It cannot enumerate users, cannot reach an already-claimed row, and is
-- cleared when the connection returns to the pool. Adopting the uid is then an
-- ordinary tenant-scoped UPDATE: the filter sets the pending row's own tenant
-- as context first, so the existing write policy — left untouched — permits it.
-- No escape is added to writes.
-- =====================================================================

DROP POLICY tenant_isolation ON users;

CREATE POLICY tenant_isolation ON users
    FOR SELECT
    USING (
        tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
        OR (
            firebase_uid LIKE 'pending:%'
            AND NULLIF(current_setting('app.claim_contact', true), '') IS NOT NULL
            AND (
                email = NULLIF(current_setting('app.claim_contact', true), '')
                OR phone = NULLIF(current_setting('app.claim_contact', true), '')
            )
        )
    );
