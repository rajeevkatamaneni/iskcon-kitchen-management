-- =====================================================================
-- V81 — The order list is a shopping list (OL1, 2026-08-31)
--
-- The screen has never been a list of orders. It is a proposal of what to
-- buy, computed from demand and fully editable before anything is
-- committed to anybody; orders are a different screen, /orders, and they
-- are what a vendor receives. Two adjacent destinations both called some
-- kind of "order" is how a storekeeper sends a draft to a vendor.
--
-- "Purchase plan" was the alternative and was rejected. It is finance's
-- word. This application's vocabulary is deliberately the temple's, and
-- the person who carries this list to the market calls it the shopping
-- list. So does the temple.
--
-- ---------------------------------------------------------------------
-- What a rename does and does not carry with it
--
-- ALTER TABLE ... RENAME keeps the rows, the grants, the row-level
-- security flags and the policy — all of them hang off the table's OID,
-- not its name — so tenant isolation is untouched here. The policy that
-- enable_tenant_rls() created is named tenant_isolation on every table,
-- with no table name embedded in it, so there is nothing to rename for
-- readability either. RowLevelSecurityIT proves the table is still
-- isolated after this migration the same way it proved it before.
--
-- What a rename does NOT carry is the names of the constraints and the
-- index, which keep saying order_list_lines until told otherwise. One of
-- them is load-bearing beyond readability: BaseQuantityIT asserts on
-- the *name* of the unit CHECK when it catches the violation, because
-- that name is the only part of the error that says which table refused.
-- =====================================================================

ALTER TABLE order_list_lines RENAME TO shopping_list_lines;

ALTER TABLE shopping_list_lines RENAME CONSTRAINT order_list_lines_pkey
    TO shopping_list_lines_pkey;
ALTER TABLE shopping_list_lines RENAME CONSTRAINT order_list_lines_qty_positive
    TO shopping_list_lines_qty_positive;
ALTER TABLE shopping_list_lines RENAME CONSTRAINT order_list_lines_unit_valid
    TO shopping_list_lines_unit_valid;
ALTER TABLE shopping_list_lines RENAME CONSTRAINT order_list_lines_tenant_id_fkey
    TO shopping_list_lines_tenant_id_fkey;
ALTER TABLE shopping_list_lines RENAME CONSTRAINT order_list_lines_ingredient_id_fkey
    TO shopping_list_lines_ingredient_id_fkey;
ALTER TABLE shopping_list_lines RENAME CONSTRAINT order_list_lines_suggested_vendor_id_fkey
    TO shopping_list_lines_suggested_vendor_id_fkey;

ALTER INDEX order_list_lines_ingredient_per_tenant
    RENAME TO shopping_list_lines_ingredient_per_tenant;

COMMENT ON TABLE shopping_list_lines IS
    'Draft shopping list merged from shortfall + thresholds (E5-S2); regenerated but edit-preserving.';

-- ---------------------------------------------------------------------
-- The scheduled job, which lives in the database too
--
-- Quartz's job store is JDBC (application.yml), so the nightly
-- regeneration exists as a row in qrtz_job_details naming its Java class
-- and a cron trigger row pointing at it. That class has been renamed with
-- everything else, which leaves the stored row naming a class that is no
-- longer on the worker's classpath: come 04:30 IST the trigger fires,
-- fails to load the job, and goes to ERROR — a nightly job that silently
-- stops running is exactly the failure this list exists to prevent.
--
-- Keeping the old key to dodge that would leave the scheduler calling it
-- order-list-regenerate forever, which is the disagreement between the
-- code and the screen this change was made to end. So the old job and
-- every trigger of it go, and JobSchedulingConfiguration re-registers the
-- pair under shopping-list-regenerate on the worker's next boot
-- (overwrite-existing-jobs: true). Deleting a row the new deployment will
-- never write again is safe in any order relative to that boot; a worker
-- still running the old image simply finds its trigger gone, which Quartz
-- treats as an ordinary missing trigger.
--
-- The child rows go first: qrtz_triggers is the parent of the four
-- trigger-detail tables and the child of qrtz_job_details, and none of
-- those foreign keys cascades.
-- ---------------------------------------------------------------------

DELETE FROM qrtz_cron_triggers c USING qrtz_triggers t
    WHERE t.sched_name = c.sched_name AND t.trigger_name = c.trigger_name
      AND t.trigger_group = c.trigger_group AND t.job_name = 'order-list-regenerate';

-- A retry scheduled by KmsJob is a simple trigger on the same job, under a
-- generated name — so these are matched through the job, not by name.
DELETE FROM qrtz_simple_triggers s USING qrtz_triggers t
    WHERE t.sched_name = s.sched_name AND t.trigger_name = s.trigger_name
      AND t.trigger_group = s.trigger_group AND t.job_name = 'order-list-regenerate';

DELETE FROM qrtz_simprop_triggers p USING qrtz_triggers t
    WHERE t.sched_name = p.sched_name AND t.trigger_name = p.trigger_name
      AND t.trigger_group = p.trigger_group AND t.job_name = 'order-list-regenerate';

DELETE FROM qrtz_blob_triggers b USING qrtz_triggers t
    WHERE t.sched_name = b.sched_name AND t.trigger_name = b.trigger_name
      AND t.trigger_group = b.trigger_group AND t.job_name = 'order-list-regenerate';

DELETE FROM qrtz_fired_triggers WHERE job_name = 'order-list-regenerate';
DELETE FROM qrtz_triggers       WHERE job_name = 'order-list-regenerate';
DELETE FROM qrtz_job_details    WHERE job_name = 'order-list-regenerate';
