-- =====================================================================
-- V135 — The meal data reset, before a meal becomes a row of its own (D-27, T-195)
--
-- WHY THIS EXISTS
--
-- Rajeev ruled on 2026-09-13 that a meal gets a row of its own and that a volunteer shift points at
-- that row by id (D-27). V136 builds the new tables. This migration clears the ground first, and it
-- does so by deleting rather than converting, which was his call and not a shortcut taken here:
--
--     "there is only 1 temple and it does not matter if we have to nuke all data from Isckon south
--      bangalore and do this change and then seed a minimum data set back to it to make it usable
--      for my UAT testing. We will still leave the vonfiguration data like Watts app, email stuff
--      and thememing and the staff and any thing that is not connected to this change."
--
-- His approved wipe list (D-27, answer 9) is what this file deletes, and nothing else:
--
--   DELETED  every meal plan and its dishes; served-meal records and corrections; job cards and
--            their PDFs; the stock movements of cooked meals and their corrections; every volunteer
--            shift, plain ones included, with signups, waitlist, reminders and broadcasts. The job
--            card number counter restarts at 1.
--   KEPT     WhatsApp, email and theme settings; staff and user accounts; recipes, ingredients and
--            stock levels; meal kinds and occasions; vendors, purchase orders, deliveries, invoices,
--            payments and scores; donations and receipts; leave; equipment; the audit history.
--
-- It loops over every temple rather than naming South Bengaluru's id, because a migration runs in
-- every environment. Production holds no temple data today. D-27 records the condition this ships
-- under: if that stops being true before the rebuild deploys, this file must not ship as written.
--
-- ---------------------------------------------------------------------
-- STOCK READS THE SAME AFTERWARDS (D-27, answer 8)
--
-- On hand is never stored. It is SUM(to_on_hand_qty(...)) over stock_movements (V116), so deleting
-- the draws a cooked meal made would quietly put that food back on the shelf in the books — rice
-- that was eaten a fortnight ago, counted as still in the store room. Rajeev chose the answer that
-- keeps every figure where it is:
--
--     for each ingredient, one ADJUSTMENT movement equal to the net of the meal-connected movements
--     being deleted, labelled as the meal data reset.
--
-- It is done per BATCH, not only per ingredient, because the FEFO allocator (FefoAllocator) draws
-- from batches by expiry, and a per-ingredient total that was right while one batch read ten kilos
-- too many and another ten too few would send the next meal to the wrong sack. Grouping by batch
-- makes the ingredient total right as a consequence, where the reverse is not true. storage_location
-- is in the grouping too, so a figure per store room survives as well; a batch lives in one place in
-- practice, and grouping by it costs nothing if so.
--
-- WHAT COUNTS AS MEAL-CONNECTED. A meal draws with reference_type MEAL_PLAN and reference_id the dish
-- id (InventoryConsumptionService). That includes the USED_BEYOND_RECORDED_STOCK memorandum row
-- (V115), which carries MEAL_PLAN too. A correction of any of those is a CORRECTION movement whose
-- reference_id is the movement it reverses (StockMovementService.compensate). So the set is the
-- MEAL_PLAN rows plus every CORRECTION that points into the set, followed recursively — a
-- correction of a correction is still about a meal, and leaving it behind would leave its sign in the
-- books with the thing it reversed gone. A CORRECTION pointing at a delivery or a manual adjustment
-- is not in the set and is not touched.
--
-- THE ARITHMETIC IS THE READERS' OWN. The net is SUM(to_on_hand_qty(quantity, unit, movement_type)),
-- exactly what every on-hand reader sums, so the memorandum rows count zero here for the same reason
-- they count zero on the inventory screen. A net of zero writes nothing: ADJUSTMENT rows may not be
-- zero (stock_movements_quantity_nonzero), and a batch whose meal rows cancelled out has nothing to
-- balance.
--
-- THE UNIT, WITHOUT WRITING A CONVERSION BY HAND. The net comes out in the family's base unit (grams,
-- millilitres, pieces). It is written back in the smallest unit that batch's deleted rows were
-- themselves recorded in — found by asking to_base_qty(1, unit), not by a CASE — and divided by that
-- same function. Every deleted row is a multiple of 0.001 of a unit at least that large, so the
-- quotient is exact to the column's three decimals: nothing is rounded, and on hand agrees to the
-- last gram rather than to within one. BaseQuantityIT exists because this table's unit arithmetic was
-- once copied into seven places; this adds no eighth.
--
-- THE LABEL. movement_type ADJUSTMENT with reason COUNT_CORRECTION, which is what
-- StockMovementService already files for a correction that moves stock — the books are being put
-- right against what is on the shelf, which is what that reason means. No reference: the rows it
-- balances are gone, and a CORRECTION reference to a deleted id would read as "reverses movement X"
-- to compensateAllFor while pointing at nothing. The note says what happened in words a storekeeper
-- reading the ingredient's history will understand.
--
-- THE ACTOR. actor_user_id is NOT NULL and a migration is nobody. It takes the person who made the
-- most recent of the movements it balances — somebody who really did handle that batch for a meal,
-- which is closer to the truth than an administrator who never touched it, and always present,
-- because every deleted row had one.
--
-- ---------------------------------------------------------------------
-- WHY THE DELETES WORK, AND WHERE THEY WOULD SILENTLY NOT (both learned the hard way here)
--
--   * ROW-LEVEL SECURITY. Every table below is under FORCE ROW LEVEL SECURITY, and the migration role
--     is not a superuser (AbstractIntegrationTest mirrors that, deliberately). A DELETE with
--     app.tenant_id unset matches nothing and reports success (V97's header). So each temple is
--     adopted in turn with set_config(..., true), exactly as V64 and V89 do, before a row is touched.
--
--   * APPEND-ONLY. stock_movements and shift_broadcasts refuse UPDATE and DELETE through the trigger
--     from V49, and that trigger refuses exactly one login: kms_app, the application's. Flyway runs as
--     the schema-owning migration role — kms_migrator in the test suite (AbstractIntegrationTest),
--     kms_migration on staging (FlywayMigrationRoleConfiguration, DB_MIGRATION_USER) — so these
--     deletes pass the trigger as it stands. Nothing here sets app.purging_tenant: that setting is the
--     whole-temple purge's announcement (V49), and borrowing it would widen who is trusted to use it.
--     If this file were ever run as kms_app, it would fail loudly with 42501, not quietly; that is
--     the correct outcome.
--
--   * FOREIGN KEYS run their checks as the owner of the referencing table. The migration role owns
--     them all. goods_receipt_lines and goods_returns reference stock_movements, but only receipts and
--     returns, never a meal draw, so no RESTRICT can be tripped by the stock delete.
--
-- ---------------------------------------------------------------------
-- WHAT ELSE POINTS AT THESE ROWS (checked against the V134 schema, not assumed)
--
--   Foreign keys:  documents.meal_service_id -> meal_services (ON DELETE CASCADE);
--                  shift_signups, shift_waitlist, shift_reminders, shift_broadcasts -> shifts (CASCADE);
--                  shift_reminders -> shift_signups (CASCADE);
--                  shift_broadcast_recipients -> shift_broadcasts (CASCADE).
--                  Nothing else references meal_plans, meal_services, shifts or their children.
--                  Attendance and its corrections are columns on shift_signups (V107, V110), not a
--                  table, so they go with the signups.
--   No FK at all:  audit_events (kept, per the approved list — history is not rewritten);
--                  notifications (kept: a message log of what was already sent to real people);
--                  ingredient_request_dishes (free-text dish names, not meal rows).
--   The schedule:  Quartz keeps its jobs in the database (V6), and two kinds point at rows deleted
--                  here without a foreign key. Shift reminders are grouped 'shiftrem-<shift id>'
--                  (ShiftReminderScheduler.group). Job card PDFs are generated by a job named
--                  'generate-document-<document id>' (DocumentService). Left in place, a reminder for a
--                  deleted shift would fire at a volunteer, and a pending card render would fail
--                  looking for its document. Both carry kms.tenantId in their job data, so they are
--                  matched with V86's quartz_job_is_orphaned against the temple being reset, and
--                  removed child-first in V86's order. Global jobs carry no tenant and are never
--                  matched; notification send jobs are left alone with the notifications they send.
-- =====================================================================


-- ---------------------------------------------------------------------
-- Which scheduled jobs belong to the meal and shift rows being deleted.
--
-- Named once so the seven Quartz deletes below read the same predicate. Called with app.tenant_id
-- already set, so the documents lookup sees only the temple being reset. Dropped at the end of this
-- file: it describes a one-off reset, not something the schema keeps.
-- ---------------------------------------------------------------------
CREATE FUNCTION meal_reset_job_is_doomed(p_job_name text, p_job_group text, p_job_data bytea,
                                         p_tenant uuid)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
    SELECT quartz_job_is_orphaned(p_job_data, p_tenant)
       AND (
            -- A reminder for a shift of this temple. Every shift is deleted, so every one goes.
            p_job_group LIKE 'shiftrem-%'
            -- A render still queued for a job card PDF this temple is about to lose.
            OR EXISTS (SELECT 1 FROM documents d
                       WHERE d.kind = 'JOB_CARD_PDF'
                         AND 'generate-document-' || d.id::text = p_job_name)
       );
$$;


DO $$
DECLARE
    t              RECORD;
    v_jobs         bigint;
    v_cards        bigint;
    v_adjustments  bigint;
    v_movements    bigint;
    v_shifts       bigint;
    v_dishes       bigint;
BEGIN
    FOR t IN SELECT id FROM tenants ORDER BY id LOOP
        -- Adopt this temple. Transaction-local, so it cannot outlive the migration.
        PERFORM set_config('app.tenant_id', t.id::text, true);

        -- --- 1. The schedule, while the documents it is matched against still exist ----------------
        DELETE FROM qrtz_cron_triggers c
            USING qrtz_triggers tr, qrtz_job_details j
            WHERE tr.sched_name = c.sched_name AND tr.trigger_name = c.trigger_name
              AND tr.trigger_group = c.trigger_group
              AND j.sched_name = tr.sched_name AND j.job_name = tr.job_name AND j.job_group = tr.job_group
              AND meal_reset_job_is_doomed(j.job_name, j.job_group, j.job_data, t.id);

        DELETE FROM qrtz_simple_triggers s
            USING qrtz_triggers tr, qrtz_job_details j
            WHERE tr.sched_name = s.sched_name AND tr.trigger_name = s.trigger_name
              AND tr.trigger_group = s.trigger_group
              AND j.sched_name = tr.sched_name AND j.job_name = tr.job_name AND j.job_group = tr.job_group
              AND meal_reset_job_is_doomed(j.job_name, j.job_group, j.job_data, t.id);

        DELETE FROM qrtz_simprop_triggers p
            USING qrtz_triggers tr, qrtz_job_details j
            WHERE tr.sched_name = p.sched_name AND tr.trigger_name = p.trigger_name
              AND tr.trigger_group = p.trigger_group
              AND j.sched_name = tr.sched_name AND j.job_name = tr.job_name AND j.job_group = tr.job_group
              AND meal_reset_job_is_doomed(j.job_name, j.job_group, j.job_data, t.id);

        DELETE FROM qrtz_blob_triggers b
            USING qrtz_triggers tr, qrtz_job_details j
            WHERE tr.sched_name = b.sched_name AND tr.trigger_name = b.trigger_name
              AND tr.trigger_group = b.trigger_group
              AND j.sched_name = tr.sched_name AND j.job_name = tr.job_name AND j.job_group = tr.job_group
              AND meal_reset_job_is_doomed(j.job_name, j.job_group, j.job_data, t.id);

        DELETE FROM qrtz_fired_triggers f
            USING qrtz_job_details j
            WHERE f.sched_name = j.sched_name AND f.job_name = j.job_name AND f.job_group = j.job_group
              AND meal_reset_job_is_doomed(j.job_name, j.job_group, j.job_data, t.id);

        DELETE FROM qrtz_triggers tr
            USING qrtz_job_details j
            WHERE tr.sched_name = j.sched_name AND tr.job_name = j.job_name AND tr.job_group = j.job_group
              AND meal_reset_job_is_doomed(j.job_name, j.job_group, j.job_data, t.id);

        DELETE FROM qrtz_job_details j
            WHERE meal_reset_job_is_doomed(j.job_name, j.job_group, j.job_data, t.id);
        GET DIAGNOSTICS v_jobs = ROW_COUNT;

        -- --- 2. Job cards ---------------------------------------------------------------------------
        -- Their stored PDF files stay behind in object storage: a migration cannot reach the bucket,
        -- and an unreferenced object costs pennies and harms nobody.
        DELETE FROM documents WHERE kind = 'JOB_CARD_PDF';
        GET DIAGNOSTICS v_cards = ROW_COUNT;

        -- --- 3. Stock: balance first, then delete ---------------------------------------------------
        WITH RECURSIVE meal_moves AS (
            SELECT m.id FROM stock_movements m WHERE m.reference_type = 'MEAL_PLAN'
            UNION
            SELECT c.id
            FROM stock_movements c
            JOIN meal_moves mm ON c.reference_type = 'CORRECTION' AND c.reference_id = mm.id
        ),
        per_batch AS (
            SELECT m.ingredient_id, m.batch_id, m.storage_location,
                   SUM(to_on_hand_qty(m.quantity, m.unit, m.movement_type)) AS net_base,
                   -- The smallest unit this batch's meal rows were recorded in (see the header).
                   (array_agg(m.unit ORDER BY to_base_qty(1, m.unit), m.unit))[1] AS unit,
                   (array_agg(m.actor_user_id ORDER BY m.created_at DESC, m.id))[1] AS actor_user_id
            FROM stock_movements m
            JOIN meal_moves mm ON mm.id = m.id
            GROUP BY m.ingredient_id, m.batch_id, m.storage_location
        )
        INSERT INTO stock_movements (
            tenant_id, ingredient_id, storage_location, batch_id, quantity, unit, movement_type,
            reason_category, reference_type, reference_id, note, actor_user_id)
        SELECT t.id, pb.ingredient_id, pb.storage_location, pb.batch_id,
               pb.net_base / to_base_qty(1, pb.unit), pb.unit, 'ADJUSTMENT',
               'COUNT_CORRECTION', NULL, NULL,
               'Meal data reset: the meal plans were cleared to rebuild how meals '
               || 'are stored, and this balances the cooking draws and corrections removed with them, '
               || 'so the stock on hand reads exactly as it did before.',
               pb.actor_user_id
        FROM per_batch pb
        WHERE pb.net_base <> 0;
        GET DIAGNOSTICS v_adjustments = ROW_COUNT;

        -- The same set, found again: the adjustments just written carry no reference, so they are not
        -- in it. One statement, so a correction and the movement it reverses go together.
        WITH RECURSIVE meal_moves AS (
            SELECT m.id FROM stock_movements m WHERE m.reference_type = 'MEAL_PLAN'
            UNION
            SELECT c.id
            FROM stock_movements c
            JOIN meal_moves mm ON c.reference_type = 'CORRECTION' AND c.reference_id = mm.id
        )
        DELETE FROM stock_movements m USING meal_moves mm WHERE m.id = mm.id;
        GET DIAGNOSTICS v_movements = ROW_COUNT;

        -- --- 4. Volunteer shifts, children first ----------------------------------------------------
        -- The foreign keys cascade from shifts, but each child is named so the wipe list can be read
        -- off this file, and so nothing here depends on a cascade somebody later changes to RESTRICT.
        DELETE FROM shift_reminders;
        DELETE FROM shift_broadcast_recipients;
        DELETE FROM shift_broadcasts;
        DELETE FROM shift_waitlist;
        DELETE FROM shift_signups;      -- attendance and its corrections are columns of this row
        DELETE FROM shifts;             -- every shift, the plain ones too (answer 9)
        GET DIAGNOSTICS v_shifts = ROW_COUNT;

        -- --- 5. The meals ---------------------------------------------------------------------------
        DELETE FROM meal_services;
        DELETE FROM meal_plans;
        GET DIAGNOSTICS v_dishes = ROW_COUNT;

        -- The next job card is number 1. ServedMealService's upsert inserts 1 when there is no row,
        -- which is exactly what deleting the row gives it.
        DELETE FROM meal_card_sequence;

        RAISE NOTICE 'V135 meal data reset, temple %: % dishes, % shifts, % job cards, % scheduled jobs, % stock movements removed; % balancing adjustments written',
            t.id, v_dishes, v_shifts, v_cards, v_jobs, v_movements, v_adjustments;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);
END $$;

DROP FUNCTION meal_reset_job_is_doomed(text, text, bytea, uuid);
