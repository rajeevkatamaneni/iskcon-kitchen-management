-- =====================================================================
-- V145 — every list price the temple already holds becomes the first row of
-- its price history (T-252, R-VEN-3), and the list price is kept to four
-- decimal places (section 0)
--
-- V144 created vendor_price_history empty. The arrows beside a List price
-- compare the newest history row with the one before it, and the service that
-- writes history (VendorPriceHistoryService) compares a newly typed price with
-- the last RECORDED one. Without this backfill, the first edit of a price typed
-- before V144 (₹60 → ₹64) would have no earlier row to compare with: no arrow,
-- no tooltip, and the ₹60 would be lost from the record for good. So every
-- vendor_supplies row with a price gets one history row for it now.
--
-- The conductor's calls for this migration (2026-09-19, recorded in
-- docs/work/proof/T-252.md):
--
--   * one row per supply with a non-null last_price; source MANUAL, because
--     every such price was typed by hand on the vendor page or written by a
--     delivery, and ONBOARDING names the bulk table that did not exist yet;
--   * effective date = the supply row's updated_at, read as a date in the
--     temple's own time zone. updated_at is NOT NULL since V24, so the
--     "else the migration date" fallback of the call is never needed; it is
--     kept in a COALESCE anyway so a null could never skip a row;
--   * the work manager's call on WHO set it: nobody we know. Nothing ever
--     recorded who typed a price before V144, and naming the oldest Temple
--     Admin would be a trail that names someone who never typed it (a trail
--     that lies is worse than a gap). So set_by becomes nullable, a new
--     column says the row was backfilled, and a CHECK allows a missing
--     set_by ONLY on a backfilled row. Every row written by the application
--     still carries who set it, and no temple is skipped for having no users.
--
-- No pack columns are filled: V144 added vendor_supplies.pack_size_id and
-- price_per_pack with no default, so no existing supply is sold in a pack.
-- =====================================================================


-- ---------------------------------------------------------------------
-- 0. vendor_supplies.last_price to four decimal places (conductor's call,
--    2026-09-19, T-252).
--
-- last_price is the list price per ONE canonical unit, and since V144 it is
-- derived from a pack price by the service (₹1,500 per Bag = 25 Kg is
-- ₹60 / Kg). For an ingredient counted in gm or ml that quotient is a
-- fraction of a paisa: ₹65 per 1 Kg pack is ₹0.065 / gm, which V24's
-- NUMERIC(12, 2) stores as ₹0.07 — 8% out on every costed figure, and
-- different from the ₹0.0650 the price history keeps, so the arrow beside the
-- List price could point the wrong way. NUMERIC(14, 4) is the scale V144 gave
-- every derived per-unit rate. Widening only: every existing value is kept
-- exactly (58.00 becomes 58.0000). The CHECK on it is unaffected, and no
-- view or function depends on the column's type.
--
-- Done first, so the backfill below copies the widened values.
-- ---------------------------------------------------------------------

ALTER TABLE vendor_supplies
    ALTER COLUMN last_price TYPE NUMERIC(14, 4);

COMMENT ON COLUMN vendor_supplies.last_price IS
    'The list price (shown as "List price"), in rupees per one canonical unit. Four places since V145, because a per-gram or per-ml price is a fraction of a paisa. Derived from price_per_pack when the vendor sells in packs.';


-- ---------------------------------------------------------------------
-- 1. The schema change: set_by may be missing, but only on a backfilled row.
--
-- DDL runs as the table owner and does not fire the append-only trigger
-- (it is a BEFORE UPDATE OR DELETE row trigger; adding a column with a
-- constant default rewrites no row through it). The table is empty on every
-- database that reaches this migration except one where the application has
-- already written prices since V144, and those rows all carry set_by, so the
-- CHECK holds for them as it is added.
-- ---------------------------------------------------------------------

ALTER TABLE vendor_price_history
    ADD COLUMN backfilled BOOLEAN NOT NULL DEFAULT false,
    ALTER COLUMN set_by DROP NOT NULL,
    ADD CONSTRAINT vendor_price_history_set_by_known CHECK (set_by IS NOT NULL OR backfilled);

COMMENT ON COLUMN vendor_price_history.backfilled IS
    'True for a price copied from vendor_supplies.last_price by V145: a price recorded before price history existed. Who typed it was never recorded, so set_by is null on these rows and only on these.';
COMMENT ON COLUMN vendor_price_history.set_by IS
    'Who set the price. Null only on a backfilled row (V145), where it was never recorded.';


-- ---------------------------------------------------------------------
-- 2. The backfill, one temple at a time.
--
-- vendor_supplies and vendor_price_history both carry FORCE ROW LEVEL
-- SECURITY and the migration role is unprivileged, so one INSERT ... SELECT
-- across temples would read no supplies (the policy fails closed with
-- app.tenant_id unset), write nothing and report success. Each tenant is
-- adopted in turn, as V144's alias backfill does. TenantLoopMigrationIT plans
-- this loop body against a real temple; VendorPriceHistoryIT checks what it
-- writes.
--
-- A pair that already has any history row (a price saved through the
-- application between V144 and this migration) is left alone: its history
-- already starts, and inserting an older-dated row beneath it would rewrite
-- what "the previous price" means for that pair.
--
-- The loop variable is v_tenant, never t: V57 crash-looped a deployment when
-- a table alias inside a loop resolved to a record variable of the same name.
-- ---------------------------------------------------------------------

DO $$
DECLARE
    v_tenant  RECORD;
    v_written INTEGER;
    v_total   INTEGER := 0;
BEGIN
    FOR v_tenant IN SELECT id, timezone FROM tenants LOOP
        PERFORM set_config('app.tenant_id', v_tenant.id::text, true);

        INSERT INTO vendor_price_history (
            tenant_id, vendor_id, ingredient_id, price_per_unit,
            effective_on, source, set_by, backfilled)
        SELECT vs.tenant_id, vs.vendor_id, vs.ingredient_id, vs.last_price,
               COALESCE((vs.updated_at AT TIME ZONE v_tenant.timezone)::date, current_date),
               'MANUAL', NULL, true
        FROM vendor_supplies vs
        WHERE vs.tenant_id = v_tenant.id
          AND vs.last_price IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM vendor_price_history h
              WHERE h.vendor_id = vs.vendor_id AND h.ingredient_id = vs.ingredient_id);

        GET DIAGNOSTICS v_written = ROW_COUNT;
        v_total := v_total + v_written;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);

    RAISE NOTICE 'V145: % list price(s) copied into vendor_price_history as their first row.', v_total;
END $$;
