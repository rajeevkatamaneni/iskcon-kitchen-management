-- =====================================================================
-- V44 — Tenant deletion (E1-S15, super-admin, DELETE_TENANT)
--
-- delete_tenant_cascade removes every trace of one temple: all rows in every
-- tenant-owned table, then the temple row itself. It exists because the schema
-- deliberately makes a temple hard to delete:
--
--   * every tenant-owned table references tenants with ON DELETE RESTRICT, so
--     nothing cascades on its own, and
--   * nine tables are append-only (the application's DELETE is revoked — see
--     make_append_only in V3), so the app cannot remove their rows at all.
--
-- This is the single, audited, DELETE_TENANT-gated path allowed to cross both
-- guards, and only ever for a whole-tenant purge. It is invoked by
-- TenantDeletionService, which first records the deletion on the platform audit
-- log (which is NOT tenant-owned and so survives the purge).
--
-- SECURITY DEFINER: the function runs as its owner — the schema owner — so in
-- production it holds the ownership needed to lift append-only, and in the test
-- topology (migrations run by the superuser) it simply has the rights outright.
-- The application role is granted EXECUTE and nothing more.
-- =====================================================================

CREATE OR REPLACE FUNCTION delete_tenant_cascade(p_tenant uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_table    text;
    v_lifted   text[] := '{}';   -- append-only tables we temporarily re-granted DELETE on
    v_progress boolean;
    v_passes   int := 0;
    v_rows     bigint;
BEGIN
    -- Scope every delete to this one tenant. In production the function runs as the
    -- (non-superuser) schema owner under FORCE ROW LEVEL SECURITY, so the RLS policy
    -- (tenant_id = app.tenant_id) is what confines the deletes; the explicit WHERE is a
    -- second belt. Transaction-local, so it cannot leak past commit.
    PERFORM set_config('app.tenant_id', p_tenant::text, true);

    -- Lift append-only only where the running role can't already delete: re-grant DELETE
    -- to ourselves on those tables. This change is transaction-local in visibility — no
    -- other connection ever observes the guard down — and it is undone below, and by any
    -- rollback, so the net committed state is always "append-only on".
    FOR v_table IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
        WHERE c.relkind = 'r' AND c.relnamespace = 'public'::regnamespace
          AND NOT has_table_privilege(current_user, c.oid, 'DELETE')
    LOOP
        EXECUTE format('GRANT DELETE ON public.%I TO CURRENT_USER', v_table);
        v_lifted := array_append(v_lifted, v_table);
    END LOOP;

    -- Delete the tenant's rows from every tenant-owned table. Rather than hardcode a
    -- dependency order across dozens of interlocking tables, retry until a full pass
    -- deletes nothing new: a row whose FK parent has not gone yet simply waits for a
    -- later pass. A per-statement block turns a not-yet-deletable table into a skip.
    LOOP
        v_progress := false;
        v_passes := v_passes + 1;
        FOR v_table IN
            SELECT c.relname
            FROM pg_class c
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
            WHERE c.relkind = 'r' AND c.relnamespace = 'public'::regnamespace
        LOOP
            BEGIN
                EXECUTE format('DELETE FROM public.%I WHERE tenant_id = $1', v_table) USING p_tenant;
                GET DIAGNOSTICS v_rows = ROW_COUNT;
                IF v_rows > 0 THEN
                    v_progress := true;
                END IF;
            EXCEPTION WHEN foreign_key_violation THEN
                NULL; -- FK parents remain; retry on a later pass
            END;
        END LOOP;
        EXIT WHEN NOT v_progress;
        IF v_passes > 100 THEN
            RAISE EXCEPTION 'delete_tenant_cascade(%): did not converge — a dependency cycle or an un-purgeable reference', p_tenant;
        END IF;
    END LOOP;

    -- Restore append-only on exactly the tables we lifted.
    FOREACH v_table IN ARRAY v_lifted LOOP
        EXECUTE format('REVOKE DELETE ON public.%I FROM CURRENT_USER', v_table);
    END LOOP;

    -- Finally the temple row itself (not tenant-owned, not RLS-scoped).
    DELETE FROM public.tenants WHERE id = p_tenant;
END;
$$;

COMMENT ON FUNCTION delete_tenant_cascade(uuid) IS
    'Purges one tenant: all rows in every tenant-owned table, then the tenant row. The single audited, DELETE_TENANT-gated path that crosses ON DELETE RESTRICT and append-only. Invoked by TenantDeletionService.';

-- The application role may execute it; SECURITY DEFINER supplies the rest.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kms_app') THEN
        GRANT EXECUTE ON FUNCTION delete_tenant_cascade(uuid) TO kms_app;
    END IF;
END;
$$;
