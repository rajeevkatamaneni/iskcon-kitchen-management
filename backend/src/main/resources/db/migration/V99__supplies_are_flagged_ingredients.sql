-- =====================================================================
-- V99 — Supplies are a flag on the catalogue, not a second catalogue
--
-- D-1, ruled by Rajeev on 2026-09-07. LPG, single-use leaf plates and
-- cups, cleaning and dishwashing supplies, hand soap and first-aid kits
-- are bought from a vendor by weight or count, received, stored, used up
-- and wanted back when they run low. That is the ingredient lifecycle,
-- item for item.
--
-- D-1 rejected a parallel `supply_items` table with a stock ledger of its
-- own, in those words: it duplicates the entire inventory chain to express
-- a difference that is one boolean. `ingredients` was already nine-tenths
-- a general catalogue of purchasable things — `category` is free text on
-- purpose ("temples add their own, and a constraint would make each a
-- migration", V10__ingredients.sql:21) and `canonical_unit` already counts
-- in pieces. So this is the boolean, and nothing downstream moves:
-- inventory_items, stock_movements, low-stock alerts, goods_receipt_lines,
-- vendor_supplies and purchase_order_lines all key on ingredient_id and
-- are untouched.
--
-- ---------------------------------------------------------------------
-- Where the flag is read, and — more importantly — where it is not
--
-- Exactly one picker filters on it: the recipe ingredient picker, because
-- a mop is not an ingredient of anything. RecipeService refuses a supply
-- on a recipe line with KMS-400127 as well, because a picker is not a
-- guard — a raw POST does not go through the picker at all.
--
-- Every other picker deliberately keeps showing supplies: inventory,
-- ingredient requests, purchase orders, in-kind donations and a vendor's
-- supply list. Filtering them out everywhere would break the very thing
-- D-1 asked for, which is that a temple can order and stock its leaf
-- plates through the machinery it already has.
--
-- ---------------------------------------------------------------------
-- Why there is no backfill, and no per-tenant loop
--
-- Migrations here are subject to RLS and DML has to adopt each tenant in
-- turn (see V97 and V98, which both do). This one runs no DML. ADD COLUMN
-- ... NOT NULL DEFAULT false is DDL executed as the table owner, and
-- PostgreSQL fills the default into every existing row itself, in one
-- catalogue-only operation, without a row-level policy ever being
-- consulted. So every ingredient that already exists is food, in every
-- tenant, and a loop over `tenants` here would iterate over nothing to do.
--
-- This is worth saying out loud because the task's acceptance criteria
-- asked for "an integration test asserting the backfill ran per tenant
-- under RLS". On this column shape there is no backfill to assert. The
-- fact that matters — that existing rows all read as food, including rows
-- belonging to a tenant nobody was adopted into — is asserted instead by
-- SupplyIngredientIT.existingIngredientsAreAllFood, which reads a row
-- inserted before this column existed and one belonging to another temple.
--
-- The alternative shape (nullable column, then an UPDATE per tenant, then
-- SET NOT NULL) would have created a real backfill to test, and it would
-- have been strictly worse: three statements and a window in which the
-- column means nothing, to reach the state one DDL statement reaches
-- atomically.
-- =====================================================================

ALTER TABLE ingredients
    ADD COLUMN is_supply BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN ingredients.is_supply IS
    'A consumable supply rather than food — LPG, leaf plates, dishwashing liquid, hand soap, '
    'first aid (D-1). Bought, received, stored and issued exactly as food is, so it stays in this '
    'catalogue and in every picker but one: the recipe picker hides it, and RecipeService refuses '
    'it on a recipe line with KMS-400127. Changing it is ordinary catalogue editing '
    '(MANAGE_RECIPES) — unlike is_ekadashi_prohibited beside it, saying a thing is a mop is not a '
    'religious-policy decision and needs no separate permission.';
