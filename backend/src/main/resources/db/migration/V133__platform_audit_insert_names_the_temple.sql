-- =====================================================================
-- V133 — The two non-operator writes to the platform audit log name the temple
--
-- platform_audit_events (V9) was an operator-only log. Two later migrations each
-- opened a narrow INSERT escape for a temple user, for one act apiece:
--
--   * V65, platform_audit_ban_events_insert — a temple admin raising, amending or
--     retracting an employment ban, or running the check at a hire
--     (entity_type EMPLOYMENT_BAN / EMPLOYMENT_BAN_CHECK);
--   * V66, platform_audit_notice_insert — a temple admin raising or withdrawing
--     a platform notice (entity_type PLATFORM_NOTICE).
--
-- Both say "attributed to themselves", and both check it the same way: the row's
-- actor_user_id must be a users row carrying the caller's verified Firebase uid.
-- That was a sufficient definition of "themselves" when one uid meant one users
-- row. Since V52 it does not. One person may hold an account at several temples,
-- one row per temple, all carrying the same uid — and the users read policy (V2,
-- V4) admits every row with firebase_uid = app.auth_uid, whichever temple it
-- belongs to, for the whole of the request. T-190 found the consequence for
-- application reads on users (OwnAccountsAtOtherTemplesIT) and fixed those by
-- naming the temple in the SQL. This is the same finding on the write side of the
-- database.
--
-- Concretely, before this migration: somebody signed in at temple A could append a
-- platform audit row whose author is their own account at temple B. V65's policy
-- checked neither the temple nor whether that account was still active; V66's
-- checked the status but not the temple. The platform log is the record an
-- operator reads to see which temple did what, so an author at the wrong temple is
-- not a cosmetic error — it pins a cross-temple act on a temple that did not do it.
--
-- The application does not do this today. AuditService.recordPlatform passes
-- actor.getUserId(), which is the membership the request speaks for — the one the
-- authentication filter selected and whose tenant it set as app.tenant_id — so
-- only a bug passing some other id would reach the policy. The policy is the
-- backstop, and this project's position is that tenant isolation is the database's
-- job rather than something every caller must remember.
--
-- So each policy keeps exactly what it had — its entity types, the uid match, and
-- u.id = actor_user_id — and the author's row must now also be this temple's and
-- ACTIVE:
--
--   u.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
--   AND u.status = 'ACTIVE'
--
-- NULLIF for the reason it is everywhere else: RESET leaves a custom setting as ''
-- rather than unset, and ''::uuid raises. With no temple set the comparison is
-- against NULL, matches nothing, and the insert is refused quietly — fail closed.
--
-- ACTIVE is new for the ban escape and unchanged for the notice one. A disabled
-- account is refused at the authentication filter before any of this runs, so
-- this also only matters as a backstop; it is added so the two escapes say the
-- same thing about who "themselves" is.
--
-- What is deliberately not touched:
--
--   * platform_audit_superadmin_insert and platform_audit_superadmin_read (V9).
--     Operators have tenant_id NULL, so they could never satisfy the new
--     condition — and do not need to. An operator raising or withdrawing a notice,
--     provisioning, deleting or exporting a temple, claiming their account or
--     editing the recipe library writes through V9's policy, which asks only
--     whether the caller's uid holds a SUPER_ADMIN row and does not look at
--     entity_type. Row-level policies of the same command are permissive and
--     OR'd, so narrowing these two leaves that path exactly as it was.
--   * No other permissive INSERT policy exists on platform_audit_events: V9's two,
--     V65's and V66's are the only CREATE POLICY ... ON platform_audit_events in
--     the migrations, and no later migration alters or replaces any of them.
--   * NoticeService.raiseFromPlatform (automation) writes no platform audit row
--     at all, by design, so there is no tenantless non-operator writer to admit.
--
-- New writes only. Policies are checked when a row is written, never re-checked
-- against rows already stored, so there is nothing to backfill and no stored row
-- can "violate" this.
-- =====================================================================

DROP POLICY platform_audit_ban_events_insert ON platform_audit_events;

CREATE POLICY platform_audit_ban_events_insert ON platform_audit_events
    FOR INSERT
    WITH CHECK (
        entity_type IN ('EMPLOYMENT_BAN', 'EMPLOYMENT_BAN_CHECK')
        AND EXISTS (
            SELECT 1 FROM users u
            WHERE u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.id = platform_audit_events.actor_user_id
              AND u.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
              AND u.status = 'ACTIVE'
        )
    );

DROP POLICY platform_audit_notice_insert ON platform_audit_events;

CREATE POLICY platform_audit_notice_insert ON platform_audit_events
    FOR INSERT
    WITH CHECK (
        entity_type = 'PLATFORM_NOTICE'
        AND EXISTS (
            SELECT 1 FROM users u
            WHERE u.id = actor_user_id
              AND u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
              AND u.status = 'ACTIVE'
        )
    );
