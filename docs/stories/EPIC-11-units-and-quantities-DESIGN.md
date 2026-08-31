# EPIC 11 — One unit vocabulary, and one way to say a quantity

**Status: DESIGN. E11-S1 is built and green; the rest awaits the build.**
**Written:** 2026-08-30, from Rajeev's instruction of the same day.
**Depends on:** nothing. **Blocks:** E10 (request lines and dish quantities use this vocabulary).
**Labels:** `epic:units`

---

## 1. The instruction

> We should have 1 common Units which has: Kg, Grams, Liters, Ml and Pieces. Everywhere there are units
> the user has to select, these options MUST be shown in the dropdown. […] If we are planning a meal for
> 2 people and there is rice and rasam in that meal. Each person eats 200 grams of rice with 200 ml of
> rasam. The meal planner does the calculations […] and decides to show yield the kitchen should cook for
> as .6 KG. That is not wrong but not friendly […] it should use Grams instead. Same for rasam, instead
> of showing .6 L of rasam, it should say 600 ML. Please check the entire code base and take note of
> wherever the units are displayed. Make sure the display follows these rules universally.

And, on rounding:

> 10.08 KG and 10 KG are the same for practical cooking purposes. We are not measuring gold here. […]
> I know rounding is not exact science and sometimes rounding can add a bigger than expected error. I am
> hoping you have a way to make rounding smarter and do it in a way a human would do when they are doing
> it for real.

Three jobs, kept apart because they carry very different risk:

1. **One vocabulary** — collapse two overlapping enums into one list.
2. **One display rule** — a quantity is shown in whichever unit of its family reads naturally, rounded
   the way a person rounds.
3. **One conversion** — remove the silent 1000× bug that has been sitting in seven copies of one SQL
   fragment. This one is pure hardening and went first.

---

## 2. What the sweep found

### The rule already exists, and it is already correct — in one place

`backend/src/main/java/org/iskcon/kms/recipe/RecipeScaler.java:52-59`:

```java
boolean atLeastOneLarge = inBase.abs().compareTo(BigDecimal.valueOf(1000)) >= 0;
return switch (family) {
    case MASS -> atLeastOneLarge ? Unit.KG : Unit.GM;
    case VOLUME -> atLeastOneLarge ? Unit.L : Unit.ML;
    case COUNT -> Unit.PIECES;
};
```

**That is exactly the rule Rajeev is asking for**, written months ago, tested, and used by precisely one
feature: recipe scaling. Nothing else calls it.

### On the frontend the same rule exists, cut in half, in two functions

- `frontend/lib/format.ts:92` — `quantity()` promotes **upward only**: `GM→KG`, `ML→L`, at ≥ 1000.
  So `quantity(0.6, "KG")` returns **"0.6 Kg"**. This is Rajeev's complaint, exactly.
- `frontend/lib/format.ts:133` — `portion()` steps **downward only**, and only below 1.
  So `portion(0.6, "KG")` returns **"600 gm"**.

Which behaviour a screen gets depends on which of the two that page's author happened to import. They
also disagree on casing for the same unit: `quantity` renders `Kg`, `portion` renders `kg`.

### Most screens use neither

`format.ts` is imported for unit purposes by **three pages**. Everywhere else formats by hand, and
several places print the **raw enum name**:

| Where | What it prints today |
|---|---|
| `app/order-list/page.tsx:133` | `652 KG` |
| `app/orders/[id]/page.tsx:301, :356, :389` | `40 KG` |
| `backend/…/document/DocumentGenerationService.java:182` | `40 KG` on the purchase-order sheet |
| `backend/…/document/JobCardService.java:288` | `2 KG` on the job card — while the recipe card says `2 Kg` |
| `backend/…/inventory/LowStockAlertService.java:62` | `Ghee (173542 ML)` in the low-stock email |

There are **eight copies** of the `UNIT_LABEL` map across the frontend, of which
`components/RecipePeek.tsx:225` has diverged (`KG: "kg"`, `LITRES: "L"`).

### And a latent 1000× stock bug — now fixed, see D6

The same fragment was hand-written in **seven production places** plus four more in tests, each ending
`ELSE 1`, which turned an unrecognised unit into one gram. Two of the four test copies had already
drifted, omitting `WHEN 'L'` entirely — so the tests and the application were computing different things
about litres, and neither said so.

---

## 3. Decisions

### D1 — One enum: `Unit` — `KG`, `GM`, `L`, `ML`, `PIECES`. `YieldUnit` is folded into it.

`Unit` survives and `YieldUnit` is retired, rather than the other way round, because `Unit` is the one
with data behind it: it constrains `ingredients.canonical_unit`, `recipe_ingredients.unit` and
`stock_movements.unit`, and every stock row ever written carries one of its names.

`YieldUnit`'s `LITRES` becomes `L`. That is a data migration on `recipes.base_yield_unit`,
`recipes.per_head_unit`, `master_recipes.yield_unit` and `master_recipes.per_head_unit`, and it is the
only genuinely delicate part of this epic — see D3.

### D2 — `SERVINGS` stays, as a yield option only. This is not a preference; the data settles it.

Rajeev's common list is Kg, Grams, Litres, Ml, Pieces — no servings. Servings cannot be removed:

- **All 57 seeded recipes yield in `SERVINGS`**, and **not one of them has a per-head portion**
  (`reference/recipes/seed_bangalore.sql` — `'SERVINGS'` appears 57 times, `per_head` zero times).
- `MealComposer.tsx:297` — `return recipe.baseYieldUnit === "SERVINGS" ? people : null;` is the **only**
  path from a head count to a cooking target for a recipe with no per-head portion. Remove `SERVINGS`
  and all 57 recipes return `null`, and no meal using them can be saved at all.
- `GivingPageController.java:116` — `CASE WHEN r.base_yield_unit = 'SERVINGS' THEN mp.target_yield END`
  is the fallback that derives plates-per-meal for legacy meals. Remove it and the public giving page
  loses its plate count and its cost-per-plate figure.

Migrating those rows off `SERVINGS` would mean inventing a per-head portion for 57 recipes nobody has
ever recorded. So: **`SERVINGS` is a sixth constant on `Unit`, in its own family, never converted, and
offered only where a recipe's yield is chosen.** An ingredient can never be measured in servings, so
ingredient, stock and request-line dropdowns filter it out — which is what "one list" means in practice:
one enum, and each dropdown shows the part of it that can be true there.

### D3 — `LITRES → L` is a rename with two traps, both found and both cheap once known.

1. **`library/BookParser.java:142-157`** — `perHead` filters with
   `parsed.filter(q -> q.unit().equals(yieldUnit))`, a **string equality on the unit name**. The parser
   emits `"LITRES"`. Rename the column values without renaming the parser's output and every per-head
   portion for every volume recipe in the library is silently discarded. Both move together.
2. **`document/JobCardService.java:563`** — the merge key is `ingredientId + "|" + rawUnit`. It groups
   within a single render from freshly-read rows, so it is safe **provided the migration leaves no mixed
   vocabulary behind**.

### D4 — One function, two implementations, one table of test vectors.

`RecipeScaler.pickDisplayUnit` is lifted into a shared `Quantities` helper and used by every backend
caller; `format.ts` gains the identical rule and `portion()` is deleted, its call sites moving to the
merged `quantity()`.

```
base = value × baseFactor(unit)      // → gm, ml, or pieces
SERVINGS or PIECES  → never converted
base ≥ 1000         → show in KG / L
base <  1000        → show in GM / ML
```

| Input | Output | Why |
|---|---|---|
| `0.6 KG` | `600 gm` | Rajeev's rice |
| `0.6 L` | `600 ml` | Rajeev's rasam |
| `20 GM` | `20 gm` | already the readable unit |
| `0.02 KG` | `20 gm` | never `0.02 Kg` |
| `173542 ML` | `173.54 L` | the existing `quantity()` behaviour, preserved |
| `5 KG` | `5 Kg` | a whole unit stays put |
| `1500 GM` | `1.5 Kg` | promotes |
| `999 GM` | `999 gm` | does not |
| `3 PIECES` | `3 pieces` | counts are never converted |
| `100 SERVINGS` | `100 servings` | a yield, never converted |
| `null` | `—` | an unknown is not a zero |

And the same table again in cook's mode (D4a), which is the one most call sites use:

| Input | Cook's figure | Ledger figure |
|---|---|---|
| `10.08 KG` | `10 Kg` | `10.08 Kg` |
| `0.1344 KG` | `135 gm` | `134.4 gm` |
| `50.4 GM` | `50 gm` | `50.4 gm` |
| `5.04 GM` | `5 gm` | `5.04 gm` |
| `840 GM` | `840 gm` | `840 gm` |
| `173542 ML` | `175 L` | `173.54 L` |
| `999.6 GM` | `1 Kg` | `999.6 gm` |

**Two implementations of one rule drift silently**, so the table above becomes a fixture shared by
`QuantitiesTest` (Java) and `format.test.ts` (Vitest) — the same inputs, the same expected strings.
`format.ts` has **no test coverage of `quantity()` at all** today, which is part of how the two halves
came to disagree.

### D4a — A quantity is rounded the way a person rounds it, on a ladder that grows with magnitude.

A human does not round to a fixed number of decimals. They round to a **step that grows with the size of
the number** — grams of camphor to the nearest half-gram, grams of coconut to the nearest five, kilos of
rice to the nearest kilo. Rajeev's five examples encode exactly one ladder, and it was derived from them
rather than invented:

| Magnitude of the displayed value | Step | Worked example |
|---|---|---|
| < 1 | 0.1 | 0.3 gm saffron → `0.3` |
| 1 – <10 | 0.5 | Edible camphor `5.04 GM` → **5** |
| 10 – <100 | 1 | `10.08 KG` → **10** · dry ginger `50.4 GM` → **50** |
| 100 – <1000 | 5 | Cardamom `134.4 GM` → **135** · coconut `840 GM` → **840** |
| ≥ 1000 | 10 | `1500 GM` → `1500` |

All five of his cases pass. Error is ~4% at the smallest magnitudes and under 1% above 100 — bounded by
the step, never compounding.

**Order of operations, which is what answers the error he is worried about:**

1. Convert to the family's base unit.
2. Pick the display unit (D4's ≥1000 rule).
3. Convert into that unit.
4. **Then** round, on the ladder above.
5. If rounding pushed a small-unit figure up to exactly 1000, promote once (`999.6 gm` → `1 Kg`).

**Rounding never touches a stored value and never enters a calculation.** It is the last thing that
happens before characters reach a screen. A recipe of twelve ingredients is scaled from exact figures and
each line rounded independently for display only, so the twelve roundings cannot drift the recipe; a
total is summed exact and rounded once, at the end. Rounding compounds if you round *then* compute. We
round last.

**Counts are integers.** `PIECES` and `SERVINGS` never take a fractional step — half an idli is not a
plan, which is the reasoning `MealComposer.tsx:301` already applies with `Math.ceil` for pieces.

### D4b — The ledger is not rounded, and that is not an exception to D4a but the point of it.

`EPIC-3-inventory-management.md` E3-S1 carries the acceptance criterion **"Stock shown always equals the
sum of movements"**. Round each movement row independently and the rows visibly stop adding up to the
balance above them. The person reading that screen is reconciling a store room against a count, and
arithmetic that does not add up is exactly the thing they are looking for. Concretely, 173,542 ml of ghee
reads `173.54 L` today and would become `175 L` — right for a cook, wrong for a count.

So one rule, two modes, chosen by what the number is *for*:

| Mode | Where | Why |
|---|---|---|
| **Cook's figure — rounded** | recipe lines, scaled recipes, planner targets, job cards, work orders, order lists, shortfalls, low-stock notices | somebody weighs or buys against it |
| **Ledger figure — exact** | stock on hand, movement rows, batch quantities, goods receipts, invoice and PO quantities | somebody reconciles or is audited on it |

Same function, one flag, and the choice is made per call site rather than per screen.

**Settled 2026-08-30 by Rajeev**, in those words: cook's figures rounded, ledger figures exact. So E3-S1's
acceptance criterion stays exactly as written, and a story that adds a new quantity to a screen has one
question to answer — is this weighed against, or reconciled against — with the mode following from it.

### D5 — Editable inputs keep what the person typed. Everything else humanises.

A field being edited shows the number as entered — rewriting `0.5` to `500` under somebody's cursor
mid-keystroke is its own bug. The rule applies the moment the value is displayed anywhere other than the
input it was typed into: tables, cards, totals, documents, emails.

This is the one boundary in the epic that is a judgement rather than a fact, and it is stated here so it
is reviewed rather than discovered.

### D6 — The seven SQL fragments become one function that cannot fail quietly.

```sql
CREATE OR REPLACE FUNCTION to_base_qty(qty NUMERIC, unit TEXT)
RETURNS NUMERIC AS $$
BEGIN
    IF unit IS NULL OR unit NOT IN ('KG', 'GM', 'L', 'ML', 'PIECES') THEN
        RAISE EXCEPTION 'Unknown unit of measure: %', COALESCE(unit, '(null)') ...
    END IF;
    RETURN qty * CASE unit WHEN 'KG' THEN 1000 WHEN 'GM' THEN 1
                           WHEN 'L'  THEN 1000 WHEN 'ML' THEN 1
                           WHEN 'PIECES' THEN 1 END;
END;
$$ LANGUAGE plpgsql IMMUTABLE;
```

**Corrected during the build, 2026-08-30.** This decision first read *"no `ELSE`; an unrecognised unit
yields `NULL`, which propagates through `SUM` and shows up as a missing figure rather than a wrong one."*
**That is false, and the test written to prove it caught it: SQL's `SUM` skips `NULL`s.** `SUM` over two
kilos and one unreadable row returns `2000` — the bad row silently dropped, the total confidently wrong,
and nothing anywhere saying a row had been left out. Which is a different shade of the same fault the
story exists to remove.

Only an exception survives an aggregate, so the function raises. A raise is unreachable in normal running
now that every unit column carries a CHECK, and if one ever does fire, a failed query naming the offending
unit is a far better morning than a stock figure quietly wrong by a factor of a thousand.

**Built and green, 2026-08-30** — `V74__base_quantity_function.sql`, seven production call sites and four
test copies replaced, `BaseQuantityIT` 7/7, and the full backend suite 1194 passing with the existing
inventory, sufficiency and order tests unchanged, which is what proves the replacement is faithful.

---

## 4. Every site that changes

**Display, frontend:** `lib/format.ts` (merge `quantity`/`portion`, single exported `UNIT_LABEL`);
delete the seven duplicated label maps in `app/ingredients`, `app/inventory`, `app/inventory/[id]`,
`app/recipes/[id]`, `app/donations/new`, `components/RecipeForm`, `components/RecipePeek`;
`app/recipes/[id]/page.tsx:442` `unitWord()`; `components/planner/MealComposer.tsx:720`;
`components/planner/MealServices.tsx:202,348,352,539,595`; `app/order-list/page.tsx:133,140,144`;
`app/orders/[id]/page.tsx:301,356,389`; `app/today/page.tsx:303` (a target yield printed with no unit at
all).

**Display, backend:** `document/DocumentGenerationService.java:182,273,282,314,329`;
`document/JobCardService.java:288`; `inventory/LowStockAlertService.java:62`;
`meal/SufficiencyService.java:102-106` (shortfalls labelled with the raw enum name).

**Selection:** eight dropdowns — `RecipeForm.tsx:166,184,216`; `app/ingredients/page.tsx:96,212`;
`app/inventory/[id]/page.tsx:261`; `app/inventory/page.tsx:477`; `app/donations/new/page.tsx:293`. Each
is fed from the one enum, filtered to what can be true in that place.

**Schema:** widen/rename `recipes.base_yield_unit`, `recipes.per_head_unit`,
`master_recipes.yield_unit`, `master_recipes.per_head_unit`. Add the missing CHECKs to
`order_list_lines.unit`, `purchase_order_lines.unit` and `goods_receipt_lines.unit` — **all three are
unconstrained today**, and `Unit.valueOf` at `OrderListService:237` is the only thing standing between a
bad row and a crash.

**Tests:** `__tests__/date-range.test.tsx:47-58` (the `portion` test, oddly filed there),
`recipe-detail.test.tsx:201-213`, `planner.test.tsx:284`, `planner-day-routes.test.tsx:131`,
`meal-recording.test.tsx:155`, `inventory.test.tsx:136-151`, plus ~24 backend ITs carrying `'SERVINGS'`
fixtures and `RecipeScalerTest`, `RecipeCardTemplateTest`.

---

## 5. The stories

| Story | What |
|---|---|
| **E11-S1** | `to_base_qty()` replaces the seven hand-written CASE fragments, and the three missing CHECKs are added |
| **E11-S2** | One `Unit` enum; `YieldUnit` retired; `LITRES → L` migration, with `BookParser` moved in lockstep |
| **E11-S3** | One display rule: `Quantities` (Java) and the merged `quantity()` (TS), against the shared vector table |
| **E11-S3a** | The rounding ladder (D4a) and the cook's/ledger split (D4b), with both vector tables |
| **E11-S4** | Every display site adopts it — frontend |
| **E11-S5** | Every display site adopts it — documents, emails and API-facing labels |
| **E11-S6** | Every dropdown is fed from the one enum, filtered per context |

**Build E11-S1 first and on its own.** It is pure hardening, it changes no behaviour, and it turns the
silent failure mode into a loud one *before* anything starts moving unit names around.

**UAT:** one story, **UAT-074 — Quantities read the way a cook says them**, walking the inventory list,
a scaled recipe, a planned meal, a job card and the low-stock email, checking each against the D4 table.

---

## 6. Questions

**Q1 — `SERVINGS`.** Keep it as a yield-only option (my recommendation, and D2 sets out why the data
forces it), or spend a separate piece of work removing it and answering what 57 servings-yield recipes
become?

**Q2 — The labels themselves.** Today they render `Kg`, `gm`, `L`, `ml`, `pieces`. Rajeev wrote
"Kg, Grams, Liters, Ml" — if that is the wording he wants on screen rather than just how he named the
concepts, it is a one-line change to a single map now that there is only one. Tables are tight, which is
the argument for the short forms, but it is his call.

**Q3 — Rounding on the ledger. ANSWERED 2026-08-30 by Rajeev: "Cook's figures — rounded. Ledger figures
— exact. Agreed."** D4b stands as written, and E3-S1's *"stock shown always equals the sum of movements"*
is preserved rather than rewritten.

**Q4 — The 1–10 step.** I set it to 0.5, so `5.04 gm` → `5` and `4.7 gm` → `4.5`. A step of 1 would make
that second one `5`. Half-grams matter for camphor and saffron and not much else; worth a look at real
recipe lines before fixing it.

**Q5 — Ordering.** This epic blocks E10, because ingredient-request lines and dish quantities use this
vocabulary and would otherwise be written twice. Recommend building E11 first, then E10, then E12.
