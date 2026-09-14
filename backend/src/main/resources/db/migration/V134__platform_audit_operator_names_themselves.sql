-- =====================================================================
-- V134 — An operator's platform audit row must name the operator who wrote it
--
-- platform_audit_superadmin_insert (V9) admits an insert into
-- platform_audit_events when the caller's verified uid holds a SUPER_ADMIN row.
-- It asks who is *connected*. It never asks who the row *says* did it: nothing
-- ties actor_user_id to that SUPER_ADMIN row. So an operator's connection could
-- record a platform act as done by another operator, by any temple user, by an
-- id that is nobody (refused only by the foreign key), or by nobody at all —
-- actor_user_id has been nullable since V66, and a NULL satisfies no foreign key.
--
-- T-192 (V133) found this while narrowing the two non-operator escapes, V65's ban
-- policy and V66's notice policy, to "the caller's own active account at this
-- temple", and deliberately left V9 alone because operators write through it. The
-- same question applies here with a simpler answer: an operator has exactly one
-- users row per uid (users_uid_platform, V52), so "themselves" is that row.
--
-- The application does not mis-attribute today. Every writer goes through
-- AuditService.recordPlatform, which passes actor.getUserId(), and every operator
-- caller's actor is the principal the authentication filter resolved from the
-- verified uid:
--
--   * NoticeService.raise / withdraw        — PLATFORM_NOTICE, from NoticeController;
--   * TenantExportService.export            — TENANT, from TenantController;
--   * TenantDeletionService.delete          — TENANT, from TenantController;
--   * MasterRecipeService.create/update/delete — MASTER_RECIPE, from MasterRecipeController;
--   * PendingAccountClaim.adopt             — USER, the operator's first sign-in. Its
--     actor is the pending row it has just bound to the verified uid, in the same
--     request, so that row already carries app.auth_uid when the audit row is written.
--
-- EmploymentBanService also calls recordPlatform, always as a temple user behind
-- MANAGE_STAFF, and writes through V133's escapes, not this policy. No SQL
-- function or trigger inserts into this table (delete_tenant_cascade, V44/V46,
-- deliberately does not touch it), no scheduled job writes to it, and
-- NoticeService.raiseFromPlatform writes no platform audit row by design. So
-- nothing legitimate writes an operator row authored by anyone but the operator,
-- and the policy can say so. It is the backstop; tenant isolation, and the
-- integrity of the record operators read, is the database's job rather than
-- something every caller must remember.
--
-- The author is matched the way the rule already identifies the caller — by the
-- verified uid the authentication filter sets as app.auth_uid, never by anything
-- taken from a request:
--
--   u.id = platform_audit_events.actor_user_id
--   AND u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
--   AND u.role = 'SUPER_ADMIN'
--   AND u.tenant_id IS NULL
--
-- NULLIF for the reason it is everywhere else: RESET leaves a custom setting as ''
-- rather than unset, so with no signed-in identity the comparison is against NULL,
-- matches nothing, and the insert is refused quietly — fail closed. A NULL
-- actor_user_id likewise matches no u.id and is refused.
--
-- tenant_id IS NULL restates users_tenant_matches_role (V2), which already makes a
-- SUPER_ADMIN row tenantless. It is written out so the policy reads on its own: the
-- same uid may also hold accounts at temples (V52), and those are never the operator.
--
-- Deliberately NOT added: u.status = 'ACTIVE', which V133 put on the two escapes.
-- A disabled operator is refused by the authentication filter before any endpoint
-- runs, and nothing in the application disables or re-enables an operator
-- (UserManagementService.setStatus reaches only rows at the admin's own temple,
-- and a SUPER_ADMIN row has none), so the condition would add nothing on those
-- paths. It would change one: PendingAccountClaim binds the uid and writes the
-- claim's audit row *before* the filter checks isActive, and the claim lookup does
-- not filter on status. A pending operator row seeded as DISABLED today claims,
-- writes its audit row, and is then refused with ACCOUNT_DISABLED; with the
-- condition, the audit insert would raise inside the filter instead, and the
-- person would get a failure rather than the plain "account disabled" answer.
-- Keeping the rule to the author question alone keeps that path as it is.
--
-- What is not touched:
--
--   * platform_audit_superadmin_read (V9). Reading is a separate question.
--   * platform_audit_ban_events_insert and platform_audit_notice_insert (V133).
--     Permissive policies of the same command are OR'd, so they must not readmit
--     what this refuses — and they cannot: both require u.tenant_id to equal
--     app.tenant_id, which an operator request never sets, and neither admits any
--     entity type but bans, ban checks and notices.
--
-- New writes only. Policies are checked when a row is written, never re-checked
-- against rows already stored, so there is nothing to backfill.
--
-- Release: after V132 and V133, in the same deploy or later.
-- =====================================================================

DROP POLICY platform_audit_superadmin_insert ON platform_audit_events;

CREATE POLICY platform_audit_superadmin_insert ON platform_audit_events
    FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM users u
            WHERE u.id = platform_audit_events.actor_user_id
              AND u.firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
              AND u.role = 'SUPER_ADMIN'
              AND u.tenant_id IS NULL
        )
    );
