-- =====================================================================
-- V116 — On hand is a count of a shelf, so it stops at zero (T-122)
--
-- Numbered V116 because V115 is the highest version applied to staging, and V116 was reserved for
-- T-089, released unused and reallocated here. A migration at or below the highest applied version
-- is rejected by Flyway's out-of-order check and the API refuses to boot. `ls` on this directory
-- cannot tell you what is deployed.
--
-- WHY THIS EXISTS, and it is a correction to V115 rather than a new feature.
--
-- V115 (T-087) stopped recording a meal being refused on stock grounds, which was right and has
-- shipped. Where the books could not cover what the kitchen cooked, it posts the remainder as a
-- movement of its own, USED_BEYOND_RECORDED_STOCK — and it made that movement SUBTRACT, so an
-- ingredient's total went to minus forty kilos. Shown that, Rajeev:
--
--     "That makes no sense. We should stop at 0. How does negative ingredients make any sense?"
--
-- His original ruling had already said so and was read the wrong way round:
--
--     "Negative numbers get normalised and ignored; a named movement appears in a list somebody
--      reads, and it says which ingredient's paperwork is behind."
--
-- The named movement was taken as what makes the minus EXPLICABLE. It was meant as what REPLACES
-- the minus. A store room cannot hold minus forty kilos of rice; that is not a quantity, it is a
-- symptom.
--
-- WHAT CHANGES, and it is only the arithmetic.
--
-- The consumption draws what actually exists — FEFO takes each lot to zero and stops, exactly as
-- before — and the shortfall row stays exactly where it was, in this table, carrying the same
-- quantity, the same meal-plan reference and the same note naming the ingredient. What changes is
-- that it no longer counts towards what is on the shelf. It is a record of a DISCREPANCY rather
-- than a movement of STOCK: it says "40 Kg used beyond recorded stock" and it subtracts nothing.
--
-- TWO FIGURES, TWO RULES, AND THEY ARE NOT THE SAME RULE.
--
--   * ON HAND never goes negative. It is a physical count of a shelf, and there is no shelf in
--     Bengaluru holding minus forty kilos of anything.
--   * AVAILABLE may. Available is on hand minus what the saved plans have claimed (T-086), so a
--     negative there says "you have promised more than you have" — which is true, useful, and
--     deliberately left alone by this migration.
--
-- WHY THE ROW STAYS IN THIS TABLE, since a row that does not move stock is a fair question.
--
-- Three things about it are load-bearing and all three already work here, and nowhere else:
-- it is reachable from the meal that caused it, because it carries the same
-- (reference_type, reference_id) pair as the draws beside it — which is what lets
-- StockMovementService.compensateAllFor give it back when the meal is corrected; it appears in a
-- list somebody reads, because that list is the movement history on the ingredient's own screen;
-- and it names which ingredient's paperwork is behind, in its note. A table of its own would have
-- had to rebuild all three, and would have needed the live staging rows moved into it — out of an
-- append-only ledger, per tenant, under RLS. The ledger keeps a memorandum row instead, and this
-- function is what makes the ledger's own arithmetic know about it.
--
-- =====================================================================

-- ---------------------------------------------------------------------
-- One definition of what a movement does to the shelf.
--
-- Written as a function for the same reason V74 wrote to_base_qty as one: the alternative is the
-- same rule spelled out at seven call sites across four services, and V74's header records what
-- happened last time this table's arithmetic was copied around — two of the copies had already
-- drifted, and nothing said so. Six readers sum this ledger for an on-hand figure, and the whole
-- risk in this change is that one of them is missed and two screens then disagree about how much
-- rice there is. They cannot disagree if they are all asking the same function.
--
-- LANGUAGE SQL rather than plpgsql so the planner can inline it: this sits inside SUM() over the
-- ledger on the inventory screen, the planner, and the shopping list.
--
-- to_base_qty is called on every row, including the ones multiplied by zero, and that is on
-- purpose. Its job is to RAISE on a unit it does not recognise rather than return NULL, because
-- SUM skips NULLs and would drop the bad row silently (V74). Short-circuiting past it for
-- discrepancy rows would make those the one kind of row a corrupt unit could hide in.
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION to_on_hand_qty(qty NUMERIC, unit TEXT, movement_type TEXT)
RETURNS NUMERIC AS $$
    SELECT to_base_qty(qty, unit) * CASE
        -- The kitchen cooked with more of this than the books held. The row records a discrepancy
        -- for somebody to chase; it moves nothing, because nothing moved that the store room can
        -- account for. Whatever it left the store as, it did not leave the shelf we are counting.
        WHEN movement_type = 'USED_BEYOND_RECORDED_STOCK' THEN 0
        ELSE 1
    END;
$$ LANGUAGE SQL IMMUTABLE;

COMMENT ON FUNCTION to_on_hand_qty(NUMERIC, TEXT, TEXT) IS
    'What one movement does to the shelf, in the family''s base unit. Sum this, never to_base_qty, for an on-hand figure: a USED_BEYOND_RECORDED_STOCK row records a discrepancy rather than a movement of stock and counts as zero, which is why on hand stops at zero instead of going impossible (T-122).';

COMMENT ON TABLE stock_movements IS
    'Append-only ledger. On hand is SUM(to_on_hand_qty(quantity, unit, movement_type)) over these rows and never a stored level. Not every row moves stock: USED_BEYOND_RECORDED_STOCK records that the kitchen cooked with more than the books held, and counts as zero (T-122).';

COMMENT ON COLUMN stock_movements.quantity IS
    'Signed, never zero. For every kind but USED_BEYOND_RECORDED_STOCK this is the movement of stock; for that one it is the size of the discrepancy, which is why on-hand sums go through to_on_hand_qty().';

-- ---------------------------------------------------------------------
-- No backfill, and this time it is not merely unnecessary — it is impossible and unwanted.
--
-- The figure this corrects is derived at read time, not stored. Every USED_BEYOND_RECORDED_STOCK
-- row already in the ledger — including the ones on staging that this task was written from — is
-- re-read correctly by the readers the moment they call the function above. There is nothing to
-- rewrite, which is fortunate: this table is append-only (make_append_only), so a backfill could
-- not have edited a row even had it wanted to.
--
-- And nothing here loops over tenants, because nothing here touches a tenant's rows. Migrations
-- run under RLS and a data change would have had to be written temple by temple; a function and
-- three comments are schema, and apply to every temple at once by applying to none of them
-- individually.
-- ---------------------------------------------------------------------
