-- =====================================================================
-- V86 — A deleted temple's scheduled work goes with it (E1-S15, D13)
--
-- Deleting a temple erased every row it owned and left its *schedule*
-- behind. The Quartz job store is a database, not a queue in memory: a
-- one-off calendar precompute queued at provisioning, a document
-- generation, a notification send, a shift reminder due next Tuesday —
-- each is a row in qrtz_job_details with a trigger pointing at it, and
-- none of them carries a tenant_id, so delete_tenant_cascade (V44/V49),
-- which finds its work by looking for that column, never saw them.
--
-- The consequence was observed on 2026-08-30: after DELETE
-- /api/v1/tenants/{id} the worker kept firing calendar-precompute and
-- generate-document for a temple that no longer existed, each attempt
-- failing with KMS-4401 (temple not found) and parking as a failure. A
-- deleted temple left permanent noise in the job log, and noise that
-- reads exactly like a live incident to whoever is on call.
--
-- ---------------------------------------------------------------------
-- How a job says which temple it is for
--
-- It does not say so in a column. KmsJob establishes the tenant context
-- from a JobDataMap entry, kms.tenantId, and every site that queues
-- tenant work sets it — CalendarPrecomputeScheduler, DocumentService,
-- NotificationService, ShiftReminderScheduler. The job key is no help:
-- only calendar-precompute-<tenant> happens to carry the id in its name;
-- generate-document-<documentId> and send-<notificationId> name a row
-- inside the temple instead, and shift reminders are grouped by shift.
--
-- So the job data is where the answer is, and Quartz stores it as a
-- serialized JobDataMap in a bytea (useProperties is off — application.yml
-- sets no such property, so the default stands). We do not deserialize
-- it here; we look for the bytes. Java serialization writes a String's
-- characters literally, so a map holding kms.tenantId -> '<uuid>'
-- contains both the key and the uuid as plain ASCII inside the blob.
-- Two containment tests, and nothing about the ordering or the internals
-- of the format is assumed:
--
--   * the blob contains 'kms.tenantId'  -> the job is tenant-scoped
--   * the blob contains '<uuid>'        -> it is that temple's
--
-- A global job — the six registered in JobSchedulingConfiguration, which
-- sweep every temple themselves — carries an empty map and matches
-- neither test, so it survives untouched. That is the whole reason the
-- first test is there: "mentions no living temple" must not be allowed
-- to mean "mentions no temple at all".
--
-- The child rows go first, exactly as V81 did it: qrtz_triggers is the
-- parent of the four trigger-detail tables and the child of
-- qrtz_job_details, and none of those foreign keys cascades. Retries
-- scheduled by KmsJob are simple triggers on the same job under a
-- generated name, so they are matched through the job, never by name.
-- =====================================================================


-- ---------------------------------------------------------------------
-- The predicate, named once so the seven deletes below can read it.
-- ---------------------------------------------------------------------
--
-- p_tenant NOT NULL — the job belongs to that temple.
-- p_tenant NULL     — the job belongs to a temple that no longer exists,
--                     which is what the one-time sweep at the foot of
--                     this file needs and nothing else should use.
--
-- STABLE, not IMMUTABLE: the null case reads the tenant registry.
CREATE OR REPLACE FUNCTION quartz_job_is_orphaned(p_job_data bytea, p_tenant uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SET search_path = pg_catalog, public
AS $$
    SELECT p_job_data IS NOT NULL
       AND position(convert_to('kms.tenantId', 'UTF8') in p_job_data) > 0
       AND CASE
               WHEN p_tenant IS NOT NULL
                   THEN position(convert_to(p_tenant::text, 'UTF8') in p_job_data) > 0
               ELSE NOT EXISTS (
                   SELECT 1 FROM public.tenants t
                   WHERE position(convert_to(t.id::text, 'UTF8') in p_job_data) > 0)
           END;
$$;

COMMENT ON FUNCTION quartz_job_is_orphaned(bytea, uuid) IS
    'True when a Quartz job''s serialized job data names the given temple — or, with a null temple, names no temple that still exists. Tenant-scoped jobs carry kms.tenantId; global jobs carry neither and are never matched.';


-- ---------------------------------------------------------------------
-- Removing one temple's scheduled work.
-- ---------------------------------------------------------------------
--
-- Called from delete_tenant_cascade below, inside the same transaction as
-- the purge, so a temple cannot be half-deleted: either its rows and its
-- schedule both go, or neither does.
CREATE OR REPLACE FUNCTION delete_tenant_scheduled_jobs(p_tenant uuid)
RETURNS integer
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_jobs integer;
BEGIN
    DELETE FROM qrtz_cron_triggers c
        USING qrtz_triggers t, qrtz_job_details j
        WHERE t.sched_name = c.sched_name AND t.trigger_name = c.trigger_name
          AND t.trigger_group = c.trigger_group
          AND j.sched_name = t.sched_name AND j.job_name = t.job_name AND j.job_group = t.job_group
          AND quartz_job_is_orphaned(j.job_data, p_tenant);

    DELETE FROM qrtz_simple_triggers s
        USING qrtz_triggers t, qrtz_job_details j
        WHERE t.sched_name = s.sched_name AND t.trigger_name = s.trigger_name
          AND t.trigger_group = s.trigger_group
          AND j.sched_name = t.sched_name AND j.job_name = t.job_name AND j.job_group = t.job_group
          AND quartz_job_is_orphaned(j.job_data, p_tenant);

    DELETE FROM qrtz_simprop_triggers p
        USING qrtz_triggers t, qrtz_job_details j
        WHERE t.sched_name = p.sched_name AND t.trigger_name = p.trigger_name
          AND t.trigger_group = p.trigger_group
          AND j.sched_name = t.sched_name AND j.job_name = t.job_name AND j.job_group = t.job_group
          AND quartz_job_is_orphaned(j.job_data, p_tenant);

    DELETE FROM qrtz_blob_triggers b
        USING qrtz_triggers t, qrtz_job_details j
        WHERE t.sched_name = b.sched_name AND t.trigger_name = b.trigger_name
          AND t.trigger_group = b.trigger_group
          AND j.sched_name = t.sched_name AND j.job_name = t.job_name AND j.job_group = t.job_group
          AND quartz_job_is_orphaned(j.job_data, p_tenant);

    -- Execution state, not history: a row here means a worker is holding, or was
    -- holding, this trigger. Leaving it would invite the cluster's recovery sweep
    -- to re-fire the job of a temple that no longer exists — the very thing this
    -- migration is here to stop. Quartz has no completed-job table, so nothing
    -- anyone could read as a record is being removed.
    DELETE FROM qrtz_fired_triggers f
        USING qrtz_job_details j
        WHERE f.sched_name = j.sched_name AND f.job_name = j.job_name AND f.job_group = j.job_group
          AND quartz_job_is_orphaned(j.job_data, p_tenant);

    DELETE FROM qrtz_triggers t
        USING qrtz_job_details j
        WHERE t.sched_name = j.sched_name AND t.job_name = j.job_name AND t.job_group = j.job_group
          AND quartz_job_is_orphaned(j.job_data, p_tenant);

    DELETE FROM qrtz_job_details j
        WHERE quartz_job_is_orphaned(j.job_data, p_tenant);
    GET DIAGNOSTICS v_jobs = ROW_COUNT;

    RETURN v_jobs;
END;
$$;

COMMENT ON FUNCTION delete_tenant_scheduled_jobs(uuid) IS
    'Removes one temple''s Quartz jobs, their triggers and their retries — or, with a null temple, those of every temple that no longer exists. Returns how many jobs went. Called by delete_tenant_cascade.';

-- The only intended caller is delete_tenant_cascade, which runs as this
-- function's owner. The application role needs no route to it — it can already
-- write the Quartz tables directly, so this is tidiness rather than a boundary.
REVOKE EXECUTE ON FUNCTION delete_tenant_scheduled_jobs(uuid) FROM PUBLIC;


-- ---------------------------------------------------------------------
-- The purge now takes the schedule with it.
-- ---------------------------------------------------------------------
--
-- Unchanged from V49 but for the one call marked below. Restated in full
-- rather than patched, because a function that exists in fragments across
-- five migrations is a function nobody can read.
CREATE OR REPLACE FUNCTION delete_tenant_cascade(p_tenant uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_table    text;
    v_progress boolean;
    v_passes   int := 0;
    v_rows     bigint;
BEGIN
    -- Scope every delete to this one tenant. Transaction-local, so it cannot leak past commit.
    PERFORM set_config('app.tenant_id', p_tenant::text, true);

    -- Announce the purge to the append-only trigger. Transaction-local for the same reason:
    -- no other connection ever observes the guard down, and a rollback puts it back.
    PERFORM set_config('app.purging_tenant', 'on', true);

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

    PERFORM set_config('app.purging_tenant', 'off', true);

    -- New in V86. The Quartz tables carry no tenant_id, so the loop above cannot
    -- reach them; without this the temple's queued and scheduled work outlives it
    -- and keeps firing. Inside the same transaction on purpose — a temple whose
    -- data went but whose schedule stayed is the defect, not a lesser version of
    -- success.
    PERFORM delete_tenant_scheduled_jobs(p_tenant);

    -- Finally the temple row itself (not tenant-owned, not RLS-scoped).
    DELETE FROM public.tenants WHERE id = p_tenant;
END;
$$;

COMMENT ON FUNCTION delete_tenant_cascade(uuid) IS
    'Purges one tenant: all rows in every tenant-owned table, its scheduled and queued Quartz work, then the tenant row. The single audited, DELETE_TENANT-gated path that crosses ON DELETE RESTRICT and append-only. Invoked by TenantDeletionService.';


-- ---------------------------------------------------------------------
-- The temples already deleted, whose work is still firing.
-- ---------------------------------------------------------------------
--
-- One-time, and a no-op wherever there is nothing to clean: on a fresh
-- database there are no Quartz rows at all, and on a database whose every
-- temple still exists no job matches. Null means "every temple that no
-- longer exists", which is exactly the set this is for.
SELECT delete_tenant_scheduled_jobs(NULL::uuid);
