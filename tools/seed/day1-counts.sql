-- Row counts for one temple, every tenant-owned table, non-empty ones only.
-- Run before and after tools/seed/day1-reset.sql to show exactly what went and what stayed.
--
--   docker exec -i kms-postgres psql -U kms_migration -d kms_seed \
--     -v tenant=f935450b-1b7c-4b2c-a7e3-73e40c7e31e3 -f - < tools/seed/day1-counts.sql
--
-- Read-only. Discovers the tables from the catalogue rather than a hardcoded list, so a table
-- added after this was written still shows up.

-- Both settings are needed and they are not the same thing.
--   app.tenant_id   is what the row-level-security policy reads. Without it every scoped
--                   table returns nothing, even to kms_migration, because the tables carry
--                   FORCE ROW LEVEL SECURITY and the owner is subject to it too.
--   app.count_tenant is this script's own parameter, used in the explicit WHERE below.
-- They are set to the same temple on purpose: if they ever disagree, RLS wins and the counts
-- come back as zero rather than as another temple's rows.
SET app.tenant_id = :'tenant';
SET app.count_tenant = :'tenant';

DROP TABLE IF EXISTS day1_counts;
CREATE TEMP TABLE day1_counts (table_name text, rows bigint);

DO $$
DECLARE
    v_table text;
    v_rows  bigint;
BEGIN
    FOR v_table IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
        WHERE c.relkind = 'r' AND c.relnamespace = 'public'::regnamespace
        ORDER BY c.relname
    LOOP
        EXECUTE format('SELECT count(*) FROM public.%I WHERE tenant_id = $1', v_table)
            INTO v_rows USING current_setting('app.count_tenant')::uuid;
        IF v_rows > 0 THEN
            INSERT INTO day1_counts VALUES (v_table, v_rows);
        END IF;
    END LOOP;
END;
$$;

\pset border 2
SELECT table_name AS "table", rows FROM day1_counts ORDER BY table_name;
SELECT count(*) AS "non-empty tables", coalesce(sum(rows), 0) AS "rows in total" FROM day1_counts;
