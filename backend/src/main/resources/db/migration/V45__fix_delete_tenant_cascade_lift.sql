-- =====================================================================
-- V45 — Fix delete_tenant_cascade's append-only handling (E1-S15 follow-up)
--
-- V44 decided which append-only tables to "lift" (temporarily re-grant DELETE
-- on) up front, using has_table_privilege(current_user, ...). In production that
-- check misreported inside the SECURITY DEFINER function — it excluded
-- audit_events from the lift even though the running role genuinely lacked
-- DELETE — so the subsequent delete hit "permission denied for table
-- audit_events" and the whole purge 500'd. (It passed in tests only because
-- there the function runs as a superuser, which never needs the lift at all.)
--
-- The fix stops guessing. We no longer pre-compute what to lift; we just attempt
-- each delete and react to what the database actually says:
--   * foreign_key_violation  -> a parent row hasn't gone yet; retry a later pass.
--   * insufficient_privilege -> an append-only table; grant ourselves DELETE (we
--                               own it), then retry. Restore it at the very end.
-- This responds to the real permission error rather than a catalog prediction, so
-- it is correct regardless of how has_table_privilege behaves. The grant/re-grant
-- is still transaction-local in visibility and netted back to "append-only on" at
-- commit (or by any rollback), exactly as before.
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

    -- Delete the tenant's rows from every tenant-owned table. Rather than hardcode a
    -- dependency order across dozens of interlocking tables, retry until a full pass
    -- deletes nothing new: a row whose FK parent has not gone yet simply waits for a
    -- later pass, and an append-only table gets its DELETE lifted on first refusal.
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
            EXCEPTION
                WHEN foreign_key_violation THEN
                    NULL; -- FK parents remain; retry on a later pass
                WHEN insufficient_privilege THEN
                    -- Append-only: lift DELETE for ourselves (we own the table) and retry it on
                    -- the next pass. Recorded so it is restored at the end.
                    IF NOT (v_table = ANY (v_lifted)) THEN
                        EXECUTE format('GRANT DELETE ON public.%I TO CURRENT_USER', v_table);
                        v_lifted := array_append(v_lifted, v_table);
                        v_progress := true;
                    END IF;
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
