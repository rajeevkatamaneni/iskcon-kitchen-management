# EPIC 11 — One unit vocabulary, and one way to say a quantity

**Goal:** Collapse two overlapping unit enums into one list, replace three half-implementations of the display rule with a single one used everywhere, round quantities the way a person rounds them, and remove the silent-1000× conversion bug that has been sitting in seven copies of one SQL fragment.
**Depends on:** nothing. **Blocks:** Epic 10 (ingredient-request lines and dish quantities use this vocabulary and print these quantities).
**Labels:** `epic:units`

**Design:** `EPIC-11-units-and-quantities-DESIGN.md` — read it first. Every decision below is argued there, including the two Rajeev settled on 2026-08-30 (the rounding ladder, and cook's-figures-rounded / ledger-figures-exact).

---

## E11-S1 — A quantity conversion that cannot fail quietly

**Verified by:** automated only — `BaseQuantityIT`. No manual surface; this story changes no behaviour.

**As a** temple admin whose stock figures decide what gets bought, **I want** the database's unit conversion to refuse a unit it does not recognise, **so that** a wrong number is never presented to me as a right one.

**Assumptions:** The seven production copies and four test copies of the conversion are all computing the same thing — a quantity in the family's base unit — and can be replaced by one function without changing a single result for any unit currently in use. Verified by the acceptance criteria below, which assert the function agrees with `Unit.baseFactor()` for every enum constant.

### The defect

The same fragment is hand-written in seven production places:

```sql
SUM(quantity * CASE unit WHEN 'KG' THEN 1000 WHEN 'L' THEN 1000 ELSE 1 END)
```

`ELSE 1` swallows anything it does not recognise. A row carrying a unit this CASE has never heard of is counted as **one gram**, silently — so a stock figure can be wrong by a factor of 1000 with nothing raised, nothing logged, and no test failing. Two of the four test copies already omit `WHEN 'L'` entirely, which means the tests and production have been computing different things.

Nothing has gone wrong yet only because every writer happens to go through `Unit.valueOf`. Three columns are not even protected by that: `shopping_list_lines.unit`, `purchase_order_lines.unit` and `goods_receipt_lines.unit` have **no CHECK constraint at all**.

This story is deliberately first, and deliberately alone. E11-S2 renames a unit; doing that while the failure mode is silent is how a 1000× error ships.

**Requirements:**
- A SQL function `to_base_qty(qty NUMERIC, unit TEXT) RETURNS NUMERIC`, `IMMUTABLE`, with **no `ELSE` branch** — an unrecognised unit yields `NULL`, which propagates through `SUM` and surfaces as a missing figure rather than a wrong one.
- All seven production call sites use it. All four test copies use it.
- `shopping_list_lines.unit`, `purchase_order_lines.unit`, `goods_receipt_lines.unit` gain the same CHECK the other unit columns carry.

**Acceptance criteria:**
- [ ] `to_base_qty` agrees with `Unit.baseFactor()` for every constant of the enum — asserted by iterating the enum in a test, not by a hand-written list that can drift.
- [ ] `to_base_qty(5, 'FURLONGS')` is `NULL`, and a `SUM` containing one such row is `NULL` rather than a number.
- [ ] No `CASE unit WHEN` remains anywhere in `backend/src/` — asserted by a test that greps the source tree.
- [ ] The three previously unconstrained unit columns reject a bad value.
- [ ] Every existing inventory, sufficiency and shopping-list test still passes unchanged, proving the replacement computes what the fragments computed.

---

## E11-S2 — One unit vocabulary

**Verified by:** [UAT-074](../uat/UAT-074-quantities-read-the-way-a-cook-says-them.md)

**As a** cook choosing a unit, **I want** the same five units wherever a unit is asked for, **so that** I never have to learn that this screen says `LITRES` and that one says `L`.

**Assumptions:** `Unit` survives and `YieldUnit` is retired, because `Unit` is the one with data behind it — it constrains `ingredients.canonical_unit`, `recipe_ingredients.unit` and `stock_movements.unit`, and every stock row ever written carries one of its names. `SERVINGS` becomes a sixth constant in its own family (design D2): the data forces it, since all 57 seeded recipes yield in servings, none has a per-head portion, and `MealComposer.tsx:297` is the only path from a head count to a cooking target for such a recipe.

**Requirements:**
- One enum: `KG`, `GM`, `L`, `ML`, `PIECES`, `SERVINGS`. `SERVINGS` is never converted and is offered only where a yield is named.
- `LITRES → L` in `recipes.base_yield_unit`, `recipes.per_head_unit`, `master_recipes.yield_unit`, `master_recipes.per_head_unit`, with the CHECKs widened to the new vocabulary.
- `library/BookParser.java` emits the new names **in the same migration**, because `perHead` filters with a string equality on the unit name (`parsed.filter(q -> q.unit().equals(yieldUnit))`) — rename one without the other and every per-head portion for every volume recipe in the library is silently discarded.

**Acceptance criteria:**
- [ ] No `YieldUnit` type remains; one enum serves recipes, ingredients, stock and requests.
- [ ] No row in any unit column holds `LITRES` after the migration, asserted by a test.
- [ ] A library import still produces per-head portions for volume recipes (the `BookParser` trap, tested directly).
- [ ] Every recipe, planner, sufficiency and order test passes.

**Added 2026-08-31 (BL-9).** The vocabulary said which units convert into which; nothing checked
that the unit a quantity arrived in was one the ingredient could be measured in at all, so "3 litres
of rice flour" was accepted and booked. `IngredientUnits.requireSameFamily` is that check, called
from `StockMovementService` and `PurchaseOrderService`, refusing with **KMS-4013**. It is the
family that must match, not the unit — issuing and cooking post in the family's base unit, and an
order in kilos for a gram-held ingredient is ordinary. `PIECES` needed no special case: it is
`Family.COUNT`, and one comparison covers it.

---

## E11-S3 — One way to say a quantity

**Verified by:** [UAT-074](../uat/UAT-074-quantities-read-the-way-a-cook-says-them.md)

**As a** cook, **I want** a quantity written the way I would say it, **so that** I never read "0.6 Kg" for something I would call 600 grams.

**Assumptions:** The rule already exists and is already right — `RecipeScaler.pickDisplayUnit` — and is used by exactly one feature. It is lifted into a shared helper rather than rewritten. `format.ts`'s `quantity()` (promotes upward only) and `portion()` (steps downward only, and disagrees on casing) are merged into one function and `portion()` is deleted.

**Requirements:**
- `Quantities` (Java) and the merged `quantity()` (TypeScript), both implementing: convert to base → pick the unit by the ≥1000 rule → convert → round → re-promote if rounding reached a whole unit.
- The rounding ladder of design D4a: step `0.1` below 1, `0.5` to 10, `1` to 100, `5` to 1000, `10` above. Counts (`PIECES`, `SERVINGS`) are always whole.
- Two modes, per design D4b, settled by Rajeev: **cook's figures rounded, ledger figures exact.**
- Rounding is the last step before text. It never touches a stored value and never enters a calculation.
- One `UNIT_LABEL`, exported once. The eight duplicated copies go.

**Acceptance criteria:**
- [ ] Both implementations pass the same table of vectors, held in one place so they cannot drift: `0.6 KG → 600 gm`, `0.6 L → 600 ml`, `20 GM → 20 gm`, `173542 ML → 175 L` (cook) / `173.54 L` (ledger), `5 KG → 5 Kg`, `1500 GM → 1.5 Kg`, `999 GM → 999 gm`, `3 PIECES → 3 pieces`, `null → —`.
- [ ] Rajeev's five rounding cases: `10.08 KG → 10 Kg`, `134.4 GM → 135 gm`, `50.4 GM → 50 gm`, `5.04 GM → 5 gm`, `840 GM → 840 gm`.
- [ ] A twelve-line recipe scaled and displayed sums, from its stored values, to the same total as before — proving rounding did not enter the arithmetic.
- [ ] `portion()` no longer exists.

---

## E11-S4 — Every screen says it the same way

**Verified by:** [UAT-074](../uat/UAT-074-quantities-read-the-way-a-cook-says-them.md)

**As a** kitchen staff member, **I want** every screen to write a quantity the same way, **so that** the number means the same thing wherever I read it.

**Assumptions:** Several screens print the raw enum name today — `652 KG` on the shopping list, `40 KG` on purchase orders. Those are the visible half of this story; the invisible half is the seven duplicated label maps.

**Requirements:**
- Adopt the shared function at: `app/shopping-list/page.tsx:133,140,144`; `app/orders/[id]/page.tsx:301,356,389`; `components/planner/MealServices.tsx:202,348,352,539,595`; `components/planner/MealComposer.tsx:720`; `app/recipes/[id]/page.tsx:234,235,242,246,368`; `app/inventory/**`; `app/ingredients/page.tsx:156`; `app/today/page.tsx:303` (a target yield printed with no unit at all).
- Ledger mode on inventory balances, movement rows and batch quantities; cook's mode everywhere else.
- Delete the duplicated `UNIT_LABEL` maps, including the diverged one in `components/RecipePeek.tsx:225`.

**Acceptance criteria:**
- [ ] No raw enum name reaches a screen — asserted by a test that scans the source for a unit rendered without the formatter, in the manner of `design-system.test.ts`.
- [ ] The inventory list still shows figures that visibly sum to the balance (E3-S1's criterion, preserved).
- [ ] One exported `UNIT_LABEL` remains in the codebase.

---

## E11-S5 — Documents and emails say it the same way

**Verified by:** [UAT-074](../uat/UAT-074-quantities-read-the-way-a-cook-says-them.md)

**As a** cook reading a printed job card, **I want** the sheet to say `135 gm`, **so that** I can weigh it without doing arithmetic over a hot stove.

**Assumptions:** The job card currently prints `2 KG` while the recipe card prints `2 Kg` for the same line, because one uses `ScaledLine.rawUnit()` and the other `Unit.label()`. Documents are cook's figures by definition — somebody weighs against them.

**Requirements:**
- `document/JobCardService.java:288`, `document/DocumentGenerationService.java:182,273,282,314,329` and `inventory/LowStockAlertService.java:62` adopt the shared helper in cook's mode.
- `meal/SufficiencyService.java:102-106` stops labelling shortfalls with the raw enum name.

**Acceptance criteria:**
- [ ] A job card and a recipe card render the same ingredient line identically.
- [ ] The low-stock email reads `Ghee (175 L)`, never `Ghee (173542 ML)`.
- [ ] A card renders correctly in a non-Latin script (the existing Devanagari check still passes).

---

## E11-S6 — Every dropdown offers the one list

**Verified by:** [UAT-074](../uat/UAT-074-quantities-read-the-way-a-cook-says-them.md)

**As a** person entering a quantity, **I want** the unit picker to offer the same options everywhere, **so that** I am never offered a unit that cannot be true here, or refused one that can.

**Assumptions:** "One list" means one enum, each dropdown showing the part of it that can be true in that place — an ingredient can never be measured in servings, and a recipe's yield can.

**Requirements:**
- All eight dropdowns fed from the one enum: `RecipeForm.tsx:166,184,216`; `app/ingredients/page.tsx:96,212`; `app/inventory/[id]/page.tsx:261`; `app/inventory/page.tsx:477`; `app/donations/new/page.tsx:293`.
- Ingredient, stock, donation and request lines: the five physical units, filtered to the ingredient's own family where one is known.
- Recipe yield: the five plus `SERVINGS`. Per-head portion: the five (never `SERVINGS` — what one person eats is a quantity of food, not a count of servings).

**Acceptance criteria:**
- [ ] No hard-coded unit array remains in any component.
- [ ] Choosing a unit outside the ingredient's family is refused with a plain message.
- [ ] `inventory.test.tsx`'s entry-unit conversion still passes.
