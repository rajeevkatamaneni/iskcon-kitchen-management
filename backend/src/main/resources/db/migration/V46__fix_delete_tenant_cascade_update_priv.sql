-- =====================================================================
-- V46 — delete_tenant_cascade must also lift UPDATE, not just DELETE (E1-S15 follow-up)
--
-- V44/V45 lifted only DELETE on append-only tables. That is not enough. Deleting
-- a row that an append-only table *references* — e.g. a `users` row referenced by
-- `audit_events.actor_user_id` (ON DELETE RESTRICT) — makes PostgreSQL run the
-- restrict check as:
--     SELECT 1 FROM ONLY audit_events x WHERE $1 = x.actor_user_id FOR KEY SHARE OF x
-- and FOR KEY SHARE requires **UPDATE** privilege on that table (a row lock), which
-- append-only has revoked. So the purge deleted the append-only rows fine, then died
-- with "permission denied for table audit_events" the moment it tried to delete the
-- users those rows had pointed at.
--
-- Fix: lift BOTH UPDATE and DELETE on the append-only tables up front (identified by
-- the app role lacking DELETE), and restore both at the end. Back to an up-front lift
-- — the lift-on-error variant can't help here, because the refusal surfaces while
-- deleting a *different* table (users) than the one whose privilege is missing
-- (audit_events). Everything remains transaction-local and netted back to
-- "append-only on" at commit or on any rollback.
-- =====================================================================

CREATE OR REPLACE FUNCTION delete_tenant_cascade(p_tenant uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_table    text;
    v_lifted   text[] := '{}';   -- append-only tables we temporarily re-granted UPDATE+DELETE on
    v_progress boolean;
    v_passes   int := 0;
    v_rows     bigint;
BEGIN
    -- Scope every delete to this one tenant (RLS confines it in production; the explicit WHERE is a
    -- second belt). Transaction-local, so it cannot leak past commit.
    PERFORM set_config('app.tenant_id', p_tenant::text, true);

    -- Lift EVERY append-only table (any table the app role can't DELETE): restore UPDATE and DELETE
    -- for ourselves. DELETE removes the rows of the tenant-owned ones; UPDATE is needed on ALL of
    -- them — including the tenantless platform_audit_events — because deleting a row they reference
    -- (e.g. a user) makes the FK RESTRICT check take a FOR KEY SHARE lock on them, which requires
    -- UPDATE. No tenant_id filter here: a table with no tenant_id (platform_audit_events) is never
    -- purged, but its RESTRICT reference to users still has to be lockable. Restored at the end.
    FOR v_table IN
        SELECT c.relname
        FROM pg_class c
        WHERE c.relkind = 'r' AND c.relnamespace = 'public'::regnamespace
          AND NOT has_table_privilege(current_user, c.oid, 'DELETE')
    LOOP
        EXECUTE format('GRANT UPDATE, DELETE ON public.%I TO CURRENT_USER', v_table);
        v_lifted := array_append(v_lifted, v_table);
    END LOOP;

    -- Delete the tenant's rows from every tenant-owned table, retrying until a full pass deletes
    -- nothing new — a row whose FK parent has not gone yet simply waits for a later pass.
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

    -- The temple row itself (not tenant-owned, not RLS-scoped). This must happen while the lift is
    -- still in place: deleting it takes a FOR KEY SHARE lock on every table that references tenants
    -- via tenant_id — which is every tenant-owned table, the append-only ones included — and that
    -- lock needs UPDATE on them.
    DELETE FROM public.tenants WHERE id = p_tenant;

    -- Only now restore append-only on exactly the tables we lifted.
    FOREACH v_table IN ARRAY v_lifted LOOP
        EXECUTE format('REVOKE UPDATE, DELETE ON public.%I FROM CURRENT_USER', v_table);
    END LOOP;
END;
$$;
