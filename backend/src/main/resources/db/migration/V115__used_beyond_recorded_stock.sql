-- =====================================================================
-- V115 — The ledger admits that a kitchen can cook food the books did not know about (T-087)
--
-- Numbered V115 because V114 is the highest version applied to staging today. A migration at or
-- below the highest applied version is rejected by Flyway's out-of-order check and the API refuses
-- to boot — "Detected resolved migration not applied to database" — which is exactly how V111 came
-- to be renumbered from V108 this week. `ls` on this directory cannot tell you what is deployed.
--
-- WHY THIS EXISTS, and it is a rule about the product rather than about the schema.
--
-- Recording a meal used to be refused when the store room's books did not hold enough for it.
-- Driven on staging: Dinner on 23 August at 60 L of curd rice was refused, at 20 L refused again,
-- and accepted at 1 L — so the record now says the temple served one litre of curd rice to 235
-- people. The refusal's advice was "cook a smaller quantity", which is meaningless for a meal that
-- has already happened. Rajeev's rule: there should never be a situation where the food was cooked
-- and the tool tells the temple they are lying.
--
-- Refusing the record does not put the rice back. It moves the lie out of the stock ledger and into
-- the meal record, where it is very much harder to find. So the check comes off the recording path
-- (InventoryConsumptionService.consume) and stays on the planning path, where a forecast can still
-- be argued with.
--
-- WHAT HAPPENS INSTEAD, and why it is a named movement rather than a bare negative.
--
-- The draw takes what the batches actually hold, and whatever the kitchen used beyond that is
-- posted as its own movement of a seventh kind, USED_BEYOND_RECORDED_STOCK. Rajeev's reasoning,
-- kept in his words because the alternative looks cheaper and is not: "Negative numbers get
-- normalised and ignored; a named movement appears in a list somebody reads, and it says which
-- ingredient's paperwork is behind."
--
-- And stock is allowed to go impossible — that is the finding rather than the bug. If the books say
-- 20 Kg and the kitchen used 60, the missing 40 did not come from nowhere: somebody did not record
-- a delivery. The movement is how that becomes visible, and it is what somebody chases.
--
-- The CHECK constraint below is the other half of MovementType.java. A value added on one side
-- alone fails at runtime rather than at compile time — on the day somebody records a short meal,
-- not on the day the enum was edited — which is why the addition is a migration at all, and why
-- StockMovementLedgerIT now inserts one row of every enum value to prove the two halves agree.
-- Same shape as V111, which added RETURN_TO_VENDOR, and its list is carried forward here unchanged.
-- =====================================================================

ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_type_valid;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_type_valid CHECK (
    movement_type IN ('PO_RECEIPT', 'DONATION_IN_KIND', 'CONSUMPTION', 'ADJUSTMENT', 'ISSUE',
                      'RETURN_TO_VENDOR', 'USED_BEYOND_RECORDED_STOCK'));

-- No backfill, and deliberately none.
--
-- Nothing in the history can be rewritten into this kind: a meal that was refused was never
-- recorded at all, so there is no row to reclassify, and a meal somebody shrank to get past the
-- refusal is a figure only the temple can correct — guessing at it here would replace one wrong
-- number with another and stamp the ledger with it. The staging recording that says one litre of
-- curd rice fed 235 people is left exactly where it is: it is the proof this task was written
-- from, and correcting it is the office's act through the correction form, not a migration's.
--
-- (This is also why there is no per-tenant loop here. Migrations run under RLS and a backfill would
-- have to be written tenant by tenant; this one writes no rows at all, so the question does not
-- arise. The constraint is DDL and applies to the table rather than to any temple's data.)

-- reference_type is not extended either. A shortfall belongs to the same meal plan the draws
-- belong to and carries MEAL_PLAN, which is what makes it reversible: correcting a meal finds
-- everything standing against (reference_type, reference_id) and compensates it, and a shortfall
-- filed under some type of its own would be the one row a correction silently walked past.
