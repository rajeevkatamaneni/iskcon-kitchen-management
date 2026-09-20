-- =====================================================================
-- V153 — An ingredient the temple never buys
--
-- T-402, Rajeev on 2026-09-19: "water and the like must never reach a
-- shopping list."
--
-- Until now there was no way to say that and have it stick. The only
-- mechanism was a decision about ONE list — PATCH /api/v1/shopping-list/
-- {ingredientId} with included:false, which writes a row in
-- shopping_list_lines — and the next list computed from fresh data puts
-- water straight back, because the list is derived and an untick is an
-- overlay on one ingredient at one moment, not a standing fact about the
-- ingredient. His curated recipes mark fifteen such lines, so this is
-- fifteen unticks a temple would have had to repeat for ever.
--
-- This column is the standing fact. ShoppingListService leaves a marked
-- ingredient OUT of the list entirely rather than putting it there
-- unticked, because an unticked line is still a line somebody has to read
-- and decide about again.
--
-- ---------------------------------------------------------------------
-- What it does NOT mean, and the flag it sits beside
--
-- `is_supply` (V99) says a thing is not food — LPG, leaf plates,
-- dishwashing liquid, hand soap. A supply IS bought: it is ordered from a
-- vendor, received, stored and issued exactly as food is, and that was the
-- whole of D-1's argument for keeping it in this catalogue. So the two
-- columns answer different questions and a row can hold any combination
-- of them.
--
-- This one says only: the temple does not buy it. It still goes into the
-- pot, it still draws down stock, it is still costed, and it still shows
-- on "Issued to kitchens". Nothing about consumption, valuation or the
-- ledger changes — the single behaviour is the shopping list's.
--
-- ---------------------------------------------------------------------
-- RLS: nothing to add, and this is the reason rather than an omission
--
-- `ingredients` has called enable_tenant_rls() since V10. A column added
-- to a table is covered by that table's existing policy — a policy is a
-- row predicate, not a column list — so a new column on an RLS-protected
-- table needs no policy of its own, no second GRANT and no new table. The
-- flag is per tenant because the ROWS are per tenant: one temple marking
-- water is invisible to every other temple, by the policy already there.
--
-- ---------------------------------------------------------------------
-- Why one atomic DDL, and why there is no per-tenant backfill
--
-- V99 set the register for this and the argument is unchanged. Migrations
-- here run subject to RLS and any DML has to adopt each tenant in turn
-- (V97 and V98 both do). This migration runs no DML at all: ADD COLUMN
-- ... NOT NULL DEFAULT false is DDL executed as the table owner, and
-- PostgreSQL fills the default into every existing row itself, in one
-- catalogue-only operation, without a row-level policy ever being
-- consulted. Every ingredient that already exists therefore reads as
-- bought, in every tenant, including tenants nobody adopts during the
-- migration — so a loop over `tenants` here would iterate over nothing to
-- do.
--
-- The alternative shape (nullable, then UPDATE per tenant, then SET NOT
-- NULL) would create a real backfill to test and would be strictly worse:
-- three statements and a window in which the column means nothing, to
-- reach the state one statement reaches atomically.
--
-- The fact that criterion is reaching for — that rows written before this
-- column existed read as bought, including a row belonging to a temple
-- nobody signed in as — is asserted by
-- NotBoughtIngredientIT.everyExistingIngredientIsBought instead.
-- =====================================================================

ALTER TABLE ingredients
    ADD COLUMN is_not_bought BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN ingredients.is_not_bought IS
    'True where the temple never buys this — water, ice (T-402, Rajeev 2026-09-19: "water and the '
    'like must never reach a shopping list"). ShoppingListService leaves a marked ingredient out of '
    'the suggested list entirely, whichever demand stream asked for it and including a line added '
    'by hand; nothing else changes, so it still consumes stock, is still costed and still appears '
    'on Issued to kitchens. NOT the same question as is_supply beside it: a supply (LPG, leaf '
    'plates) IS bought, received and stored exactly as food is. Setting or clearing it needs '
    'MANAGE_BUYING_POLICY — the Temple Admin alone — and is audited under '
    'INGREDIENT_NOT_BOUGHT_CHANGED. It is deliberately absent from PUT /ingredients/{id}, because '
    'the supplies screen sends a whole update payload built from the fields it knows about and a '
    'flag riding on that PUT would be un-set by somebody renaming a mop.';
