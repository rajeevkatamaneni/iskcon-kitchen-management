-- =====================================================================
-- V8 — First-sign-in claim for the platform super-admin (E1-S13)
--
-- The super-admin belongs to no tenant (tenant_id IS NULL). Nothing in the
-- app creates one — the platform's root operator is seeded out of band by a
-- privileged operator (docs/DEPLOYMENT.md), as a 'pending:<uuid>' row exactly
-- like a provisioned temple admin, and then claims it on first sign-in.
--
-- V4 added only a *read* escape for the claim. Adopting the real uid is an
-- ordinary tenant-scoped UPDATE (PendingAccountClaim.adopt), and V2's write
-- policy requires tenant_id = app.tenant_id — which no setting can satisfy for
-- a NULL tenant, since `NULL = anything` is NULL, never true. So a super-admin's
-- pending row can be read but never bound, and the operator can never sign in.
-- Invisible in local/dev, which connect as a superuser that bypasses RLS; it
-- only bites under the unprivileged app role.
--
-- The write escape must apply to UPDATE only: minting a super-admin (an INSERT
-- of a tenantless row) must stay impossible through the app. A single FOR ALL
-- policy can't express that — its WITH CHECK would also admit such an INSERT —
-- and a *separate* FOR UPDATE policy alongside the FOR ALL one does not work
-- either: Postgres does not OR their WITH CHECK expressions as one might expect,
-- so the adopt is refused. The reliable, and clearest, shape is to replace the
-- single FOR ALL write policy with per-command policies: INSERT and DELETE keep
-- the strict tenant rule unchanged, and UPDATE carries the escape OR-ed into one
-- expression, where the semantics are unambiguous.
--
-- The escape itself is as narrow as V4's read escape: it binds a real uid onto a
-- row that is still pending, tenantless (i.e. a super-admin, per the
-- users_tenant_matches_role CHECK), and whose email or phone equals the exact
-- contact Firebase verified for this caller — app.claim_contact, set by the
-- authentication filter alone and only during a claim. After adoption the row is
-- no longer pending, so the escape can never touch it again.
--
-- SELECT is unaffected: it is governed by the tenant_isolation (FOR SELECT)
-- policy from V4, which already covers the tenant, auth-uid, and pending-claim
-- branches. The FOR ALL policy contributed nothing to reads that the read policy
-- did not already grant.
-- =====================================================================

DROP POLICY tenant_isolation_write ON users;

CREATE POLICY users_insert ON users
    FOR INSERT
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY users_delete ON users
    FOR DELETE
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY users_update ON users
    FOR UPDATE
    USING (
        tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR (
            tenant_id IS NULL
            AND role = 'SUPER_ADMIN'
            AND firebase_uid LIKE 'pending:%'
            AND NULLIF(current_setting('app.claim_contact', true), '') IS NOT NULL
            AND (
                email = NULLIF(current_setting('app.claim_contact', true), '')
                OR phone = NULLIF(current_setting('app.claim_contact', true), '')
            )
        )
    )
    WITH CHECK (
        tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR (
            -- The adopted row keeps its tenant (null), role, and contact; only the
            -- uid changes. Re-assert the invariants so the escape cannot be a lever
            -- to move a row to a tenant or off the matched contact.
            tenant_id IS NULL
            AND role = 'SUPER_ADMIN'
            AND NULLIF(current_setting('app.claim_contact', true), '') IS NOT NULL
            AND (
                email = NULLIF(current_setting('app.claim_contact', true), '')
                OR phone = NULLIF(current_setting('app.claim_contact', true), '')
            )
        )
    );
