-- =====================================================================
-- V70 — Nothing a temple holds stays untracked
--
-- Stock is derived from the stock_movements ledger (V14), and that ledger is
-- keyed by ingredient. inventory_items (V15) is a separate decision: the
-- consumables a temple has said it tracks. The two facts were allowed to
-- disagree, and in practice they did — a delivery received against a purchase
-- order, or an in-kind donation, wrote real kilograms into the ledger for an
-- ingredient nobody had added to the store first. The Inventory screen lists
-- inventory_items, so those kilograms were invisible: held, and dark.
--
-- Worse was what happened on noticing. Adding rice to the store then produced
-- an item that already held 652 kg out of nowhere, with a movement history
-- going back weeks that predated the row itself.
--
-- StockMovementService.track() closes this going forward: anything that moves
-- through the ledger is tracked from its first movement. This migration closes
-- what is already there — every ingredient a temple holds movements for, and no
-- item row, becomes a tracked item in the place its stock last moved to. No
-- reorder threshold: the ledger proves the temple holds the thing, and says
-- nothing about the level at which it wants more.
-- =====================================================================

DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);

        INSERT INTO inventory_items (tenant_id, ingredient_id, storage_location, notes)
        SELECT
            t.id,
            m.ingredient_id,
            -- Where its stock last moved to. Movements may name several locations
            -- over time and the most recent is the best answer available; null
            -- where none of them ever said, which reads as the default store.
            (ARRAY_AGG(m.storage_location ORDER BY m.created_at DESC)
                FILTER (WHERE m.storage_location IS NOT NULL))[1],
            'Tracked automatically — this temple already held stock of it.'
        FROM stock_movements m
        WHERE NOT EXISTS (
            SELECT 1 FROM inventory_items ii WHERE ii.ingredient_id = m.ingredient_id)
        GROUP BY m.ingredient_id
        ON CONFLICT (tenant_id, ingredient_id) DO NOTHING;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);
END $$;
