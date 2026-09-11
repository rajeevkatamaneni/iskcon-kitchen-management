-- =====================================================================
-- V121 — The shopping list stops being stored (T-132, D-24, D-24a)
--
-- Rajeev, 2026-09-10, on the ordering flow as a whole:
--
--   "The issue starts at where the data enters the system. Without
--    addressing that, everything is a compromised fix."
--
-- and, on this screen specifically:
--
--   "Why do we need the Regenerate shopping list button at all? Why can't
--    the shopping list auto populate every time the page loads?"
--
-- The answer turned out to have a second half he had not been told about.
-- There were TWO doors onto one write: POST /shopping-list/regenerate,
-- and ShoppingListRegenerateJob on a 04:30 IST cron trigger. Both did the
-- same thing — upsert a suggested line per ingredient, then DELETE every
-- unedited line that was no longer suggested. So the list already
-- populated itself once a night; the button was the second door, and the
-- honest answer to "why the button" is that neither should exist.
--
-- ---------------------------------------------------------------------
-- What this table was, and what it becomes
--
-- shopping_list_lines was a CACHE pretending to be a table. Every column
-- on it except three was recomputable from the meal plan, the stock
-- ledger, the vendor catalogue and the live purchase orders — and being
-- recomputable is not the problem. The problem is that the cache was
-- stale between writes and nothing on the screen said so, and that two
-- people reading the same list at the same moment could be shown a
-- different answer depending on which of them had last pressed a button.
--
-- After this migration the suggestions are DERIVED at read time
-- (ShoppingListService.list) and never written. The table keeps only
-- what a human decided, which no computation can recover:
--
--   * an edited quantity          -> suggested_qty  (now NULLABLE: null
--                                    means "no override, use the computed
--                                    figure", which is what an untick on
--                                    its own leaves behind)
--   * an untick                   -> included = false
--   * a line added by hand        -> hand_added = true
--
-- ---------------------------------------------------------------------
-- The four columns that go, and why each one is a computation
--
--   provenance          Which demand streams drove the line, and by how
--                       much. A statement about today's plan and today's
--                       shelf. Frozen, it says "shortfall 7 Kg" beside a
--                       hand-typed 20 long after the meal was cooked and
--                       the shortfall went to zero, with nothing on the
--                       screen to say the figure is a fossil. Recomputed,
--                       the two numbers can disagree — and that
--                       disagreement IS the information: a person
--                       overrode the suggestion, and here is what the
--                       system thinks now.
--
--                       upsertLine already drew this line and gave the
--                       rule: it refreshed order_by, lead_time_days,
--                       needed_by and current_stock even on an edited
--                       line, because "the edit-guarded columns are
--                       choices somebody made, and overwriting a choice
--                       loses work. These are computed facts about the
--                       plan and the catalogue as they stand this
--                       morning." Provenance is a computed fact.
--
--   suggested_vendor_id Pure derivation, never a human choice. Both
--                       writers set it from the same call —
--                       vendorService.preferredVendorId(...) — and
--                       updateLine accepted a vendor that NO SCREEN EVER
--                       SENT, which its own javadoc admitted. The
--                       snapshot D-25 wants already exists elsewhere:
--                       purchase_orders.vendor_id is written at creation
--                       and never derived again (T-091). What D-25 still
--                       needs stamped on the order is the LEAD TIME, not
--                       the vendor, and that belongs to T-134.
--
--   needed_by           The delivery date, from the earliest planned meal
--   order_by            that demands the ingredient. T-130 removes the
--   lead_time_days      two-day subtraction that used to sit between the
--                       demand and needed_by; order_by and lead_time_days
--                       come from vendor_supplies.lead_time_days (V119)
--                       and were already refreshed unconditionally.
--
--   current_stock       A sum over stock_movements. It was stale the
--                       moment anything was cooked.
--
--   edited              Replaced by the table's own existence: after this
--                       migration a row IS a human decision, so "edited"
--                       is "a row exists for this ingredient" and the
--                       view computes it. What the column could NOT say
--                       is the one distinction that now matters — see
--                       hand_added below.
--
-- ---------------------------------------------------------------------
-- Why hand_added has to be a real column
--
-- A decision row is an OVERLAY on whatever the read computes. An edited
-- quantity for rice means nothing if nothing suggests rice; a line added
-- by hand is its own reason to appear and must render whether or not any
-- stream reaches it. `edited` conflated the two, because addLine and
-- updateLine both wrote it true.
--
-- The backfill can tell them apart exactly, because addLine wrote
-- provenance = '{}' and the regenerator always wrote all three stream
-- keys. That identity is why this column is backfilled here rather than
-- guessed at later, and why provenance is read one last time on its way
-- out.
--
-- ---------------------------------------------------------------------
-- The rows that go, and the ones that stay
--
-- Every row with edited = false was a pure cache entry: the regenerator
-- put it there and no person ever touched it. Nothing is lost by deleting
-- it — the next page load recomputes exactly that line, from fresher
-- inputs. Rows with edited = true are decisions and every one is kept.
--
-- ---------------------------------------------------------------------
-- RLS, and why there IS a tenant loop in this file
--
-- shopping_list_lines carries enable_tenant_rls() (V25:46, under its old
-- name order_list_lines; V81 renamed the table and the rename kept the
-- policy, which hangs off the OID). The migration role is unprivileged
-- and holds no BYPASSRLS, so a bare UPDATE or DELETE here would run with
-- app.tenant_id unset, match nothing through the policy's NULLIF, and
-- report success having changed no row in any temple. Silent, and exactly
-- the failure this project's migrations adopt each tenant in turn to
-- avoid. ALTER TABLE is DDL and is not subject to the policy, so the
-- column changes below sit outside the loop.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1 · hand_added, and the backfill that can still see provenance
-- ---------------------------------------------------------------------

ALTER TABLE shopping_list_lines
    ADD COLUMN hand_added BOOLEAN NOT NULL DEFAULT false;

DO $$
DECLARE
    t       RECORD;
    n       INTEGER;
    adopted INTEGER := 0;
    dropped INTEGER := 0;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        -- A line somebody typed in: addLine is the only writer that ever
        -- left provenance empty, because it is by definition a line no
        -- demand stream suggested.
        -- updated_at is deliberately NOT touched. The screen reads it to
        -- say how long ago a line was unticked, and there is no trigger
        -- on this table to bump it, so stamping now() here would tell
        -- every temple its old decisions were made the day we deployed.
        UPDATE shopping_list_lines
        SET hand_added = true
        WHERE tenant_id = t.id
          AND edited = true
          AND provenance = '{}'::jsonb;
        GET DIAGNOSTICS n = ROW_COUNT;
        adopted := adopted + n;

        -- Cache rows. The next page load recomputes them from fresher
        -- inputs than the ones that produced these.
        DELETE FROM shopping_list_lines
        WHERE tenant_id = t.id
          AND edited = false;
        GET DIAGNOSTICS n = ROW_COUNT;
        dropped := dropped + n;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);

    RAISE NOTICE 'V121: % hand-added line(s) adopted, % cached suggestion(s) dropped. '
        'What is left is only what a person decided (T-132).', adopted, dropped;
END $$;

-- ---------------------------------------------------------------------
-- 2 · The computed columns go
-- ---------------------------------------------------------------------

ALTER TABLE shopping_list_lines
    DROP COLUMN provenance,
    DROP COLUMN suggested_vendor_id,
    DROP COLUMN needed_by,
    DROP COLUMN order_by,
    DROP COLUMN lead_time_days,
    DROP COLUMN current_stock,
    DROP COLUMN edited;

-- An untick on its own says nothing about quantity, so the override is
-- nullable and null means "use the computed figure". The CHECK survives
-- unchanged and still refuses a zero: `NULL > 0` is NULL, which a CHECK
-- accepts, and that is the behaviour wanted here.
ALTER TABLE shopping_list_lines
    ALTER COLUMN suggested_qty DROP NOT NULL;

COMMENT ON TABLE shopping_list_lines IS
    'Human decisions about the shopping list, and nothing else (T-132, D-24). The suggestions themselves are derived on every read by ShoppingListService.list from the meal-plan shortfall, stock below its reorder level, short deliveries and the live purchase orders - they are never written. A row here is an overlay on that: an edited quantity, an untick, or a line somebody added by hand. There is no regeneration and no nightly job; V121 removed both.';

COMMENT ON COLUMN shopping_list_lines.suggested_qty IS
    'The quantity a person typed, overriding the computed suggestion. NULL means no override - an untick on its own must not freeze a quantity, because the figure beside it goes on being recomputed.';

COMMENT ON COLUMN shopping_list_lines.included IS
    'FALSE when somebody unticked the line. It persists, which is deliberate and has a named cost: an untick made in September suppresses a January shortfall, and between those dates the line is not on the screen for anybody to notice. Expiring it would need either a write on a read - the single thing T-132 exists to stop - or an invented validity window. The mitigation is legibility instead: when the line reappears the screen says when it was unticked, read off updated_at.';

COMMENT ON COLUMN shopping_list_lines.hand_added IS
    'TRUE for a line somebody typed in that no demand stream suggested - gas, leaf plates, flowers for a festival (T-027). It is its own reason to appear on the list; every other row here is only an overlay on a line the derivation already produced, and renders nothing on its own.';

COMMENT ON COLUMN shopping_list_lines.updated_at IS
    'When the decision on this row was last changed. Read by the screen to say how long ago a line was unticked, so a stale untick announces itself the moment the ingredient is needed again.';

-- ---------------------------------------------------------------------
-- 3 · The nightly job, which lives in the database too
--
-- Quartz's job store is JDBC (application.yml), so the 04:30 IST
-- regeneration exists as a row in qrtz_job_details naming its Java class
-- and a cron trigger row pointing at it. ShoppingListRegenerateJob is
-- deleted by this task, which leaves the stored row naming a class that
-- is no longer on the worker's classpath: come 04:30 the trigger fires,
-- fails to load the job, and goes to ERROR. Deleting the rows is what
-- stops that, and nothing re-registers them, because the beans in
-- JobSchedulingConfiguration are gone too.
--
-- This is V81's own removal, run once more and for the last time — that
-- migration deleted the pair under the OLD key when the screen was
-- renamed, and re-registered them under this one. The child rows go
-- first: qrtz_triggers is the parent of the four trigger-detail tables
-- and the child of qrtz_job_details, and none of those foreign keys
-- cascades.
--
-- Quartz's tables are global rather than tenant-owned, so this block is
-- outside the loop above and needs no tenant adopted.
-- ---------------------------------------------------------------------

DELETE FROM qrtz_cron_triggers c USING qrtz_triggers t
    WHERE t.sched_name = c.sched_name AND t.trigger_name = c.trigger_name
      AND t.trigger_group = c.trigger_group AND t.job_name = 'shopping-list-regenerate';

-- A retry scheduled by KmsJob is a simple trigger on the same job, under
-- a generated name — so these are matched through the job, not by name.
DELETE FROM qrtz_simple_triggers s USING qrtz_triggers t
    WHERE t.sched_name = s.sched_name AND t.trigger_name = s.trigger_name
      AND t.trigger_group = s.trigger_group AND t.job_name = 'shopping-list-regenerate';

DELETE FROM qrtz_simprop_triggers p USING qrtz_triggers t
    WHERE t.sched_name = p.sched_name AND t.trigger_name = p.trigger_name
      AND t.trigger_group = p.trigger_group AND t.job_name = 'shopping-list-regenerate';

DELETE FROM qrtz_blob_triggers b USING qrtz_triggers t
    WHERE t.sched_name = b.sched_name AND t.trigger_name = b.trigger_name
      AND t.trigger_group = b.trigger_group AND t.job_name = 'shopping-list-regenerate';

DELETE FROM qrtz_fired_triggers WHERE job_name = 'shopping-list-regenerate';
DELETE FROM qrtz_triggers       WHERE job_name = 'shopping-list-regenerate';
DELETE FROM qrtz_job_details    WHERE job_name = 'shopping-list-regenerate';
