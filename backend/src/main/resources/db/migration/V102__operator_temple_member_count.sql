-- =====================================================================
-- V102 — the operator's temple list says every temple has nobody in it
--
-- /tenants and /tenants/{id} both showed "People with accounts: 0", for every
-- temple, always, and could never have shown anything else. Three real accounts
-- at one staging temple — an admin, a kitchen-staff and a volunteer — and the
-- list still said 0.
--
-- Nothing was broken. Row-Level Security was doing precisely its job. The
-- controller counted with a plain subselect:
--
--     (SELECT count(*) FROM users u WHERE u.tenant_id = t.id)
--
-- and `users` carries FORCE ROW LEVEL SECURITY (V2:86-87) under a policy
-- (V4:31-45) whose readable clauses are
--
--     tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
--     OR firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
--
-- A platform operator is tenantless, so `app.tenant_id` is the empty string, the
-- first clause is `tenant_id = NULL` and never true, and the second matches only
-- the operator's own row — whose tenant_id is itself NULL and so is excluded by
-- the subselect's own WHERE. Every row is filtered out and count(*) returns a
-- confident, silent 0.
--
-- "Silent" is the whole defect. CLAUDE.md describes the NULLIF(..., '') idiom as
-- failing "closed quietly instead of raising", and quiet is exactly what made
-- this expensive: nothing errored, nothing logged, and the screen rendered a
-- number that looked like an answer. An aggregate over an RLS-filtered table
-- degrades to a plausible wrong number rather than to an error, which is a
-- shape worth remembering wherever else we aggregate across tenants.
--
-- ---------------------------------------------------------------------
-- Why SECURITY DEFINER alone does not fix this
--
-- The obvious reach is a SECURITY DEFINER function, on the reasoning that it
-- runs as the schema owner and the owner is exempt from the table's policies.
-- The owner is not exempt: FORCE ROW LEVEL SECURITY exists precisely to subject
-- a table's owner to its own policy, and V1:94-98 says so in as many words —
-- "without FORCE, an owning role would silently see every tenant's rows". So a
-- SECURITY DEFINER function owned by the migration role is filtered exactly as
-- the application is, and would still return 0. That is not an argument here; it
-- is asserted by OperatorUserCountIT, which builds such a function as the
-- migration role and watches it answer 0 for a temple that demonstrably has
-- three members.
--
-- Nor is the answer BYPASSRLS on the application role. That would quietly undo
-- the guarantee the entire design rests on, for a headcount.
--
-- ---------------------------------------------------------------------
-- What actually works, and it is already in the tree
--
-- delete_tenant_cascade (V45:36-40) works *inside* RLS rather than around it: it
-- adopts one tenant's context transaction-locally, and the policy then confines
-- it to that tenant. This function is the same shape, and lands on the permitted
-- side of D-13 by construction rather than by promise — it can only ever answer
-- for the one temple it was asked about, and it can only ever return a number.
-- No rows, no names, no personal data. Rajeev's ruling drew the line at an
-- operator reading a cross-tenant record firehose and expressly allowed
-- aggregate counts; a bigint is as far from a firehose as this can get.
--
-- ---------------------------------------------------------------------
-- The one thing V45 does not have to care about and this does: restoring
--
-- delete_tenant_cascade is a destructive one-shot at the end of a request, so it
-- never restores the setting it adopted. This function is called once per row of
-- the operator's temple list. If it left `app.tenant_id` set, the operator's
-- request would carry on holding a tenant context it must never have, and the
-- next query in that transaction would quietly read one temple's data — the same
-- class of silent wrongness this migration exists to remove, pointed the other
-- way. So the caller's value is saved and put back.
--
-- It is put back as '' rather than as NULL when the caller had none. That is the
-- NULLIF(..., '') convention seen from the other end: the application's
-- TenantAwareDataSource sets the empty string for a tenantless request, RESET
-- leaves the setting as an empty string rather than null, and the policies are
-- written to read '' as "no tenant". Restoring a literal NULL would work today
-- but would be the one value in the system that means "no tenant" differently
-- from every other, so it is not what we restore. OperatorUserCountIT asserts
-- the restore rather than trusting it.
-- =====================================================================

CREATE OR REPLACE FUNCTION tenant_user_count(p_tenant uuid)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
-- A SECURITY DEFINER function without a pinned search_path is a privilege
-- escalation vector: the caller chooses which schema `users` resolves to, and
-- the function then reads their table as the owner. Pinned, and pg_catalog
-- first, so no shadowing of the built-ins the body relies on either.
SET search_path = pg_catalog, public
AS $$
DECLARE
    -- text, not uuid: the caller may legitimately hold no tenant, and the value
    -- that means that is the empty string, which is not a uuid.
    v_caller_tenant text;
    v_count         bigint;
BEGIN
    -- current_setting(..., true) returns NULL when the setting was never set at
    -- all in this session; coalesce folds that onto the same '' the application
    -- itself writes for a tenantless request, so the restore below is exact for
    -- both the "never set" and the "set to empty" cases.
    v_caller_tenant := coalesce(current_setting('app.tenant_id', true), '');

    -- Adopt this one temple's context, transaction-locally. This is what makes
    -- the count visible at all, and equally what makes it impossible for the
    -- count to include anybody else: the policy, not the WHERE clause, is the
    -- thing doing the confining. The WHERE is a second belt, exactly as in V45.
    PERFORM set_config('app.tenant_id', p_tenant::text, true);

    SELECT count(*) INTO v_count FROM public.users u WHERE u.tenant_id = p_tenant;

    -- Put the caller back where they were. No exception handler wraps this: if
    -- the count above raises, the statement — and with it every transaction-local
    -- set_config made inside it — is rolled back by the database, so an error
    -- path cannot leave a context behind either. A handler here would only add a
    -- subtransaction per temple row for no gain.
    PERFORM set_config('app.tenant_id', v_caller_tenant, true);

    RETURN v_count;
END;
$$;

COMMENT ON FUNCTION tenant_user_count(uuid) IS
    'How many people hold an account at one temple. For the platform operator, who is tenantless and therefore sees no users row at all under RLS. Returns a count and nothing else, and restores the caller''s tenant context before returning.';

-- ---------------------------------------------------------------------
-- Execute privileges.
--
-- PostgreSQL grants EXECUTE on a new function to PUBLIC, which for a SECURITY
-- DEFINER function means handing the owner's reach to every role in the cluster.
-- Only the application role has any business calling this, so PUBLIC loses it
-- and the application role is granted it by name.
--
-- What this does not do is decide who may see the answer. Every request arrives
-- on the same kms_app connection, so the database cannot tell an operator from a
-- temple's cook; the endpoints that call this are behind MANAGE_TENANTS and that
-- is where the authorisation lives, as it does for delete_tenant_cascade. The
-- exposure if that were ever wrong is a headcount, which is the reason this
-- returns an aggregate and not rows.
--
-- Guarded so the migration still runs where kms_app does not exist — and the
-- guard covers the REVOKE too, deliberately: revoking from PUBLIC without a role
-- to grant to would leave the function callable by nobody but its owner.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kms_app') THEN
        EXECUTE 'REVOKE EXECUTE ON FUNCTION tenant_user_count(uuid) FROM PUBLIC';
        EXECUTE 'GRANT EXECUTE ON FUNCTION tenant_user_count(uuid) TO kms_app';
    END IF;
END
$$;
