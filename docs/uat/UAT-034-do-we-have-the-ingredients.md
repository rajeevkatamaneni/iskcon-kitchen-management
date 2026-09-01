# UAT-034: Do we have the ingredients?

| | |
|---|---|
| **Feature area** | Meal planning — ingredient sufficiency |
| **Technical stories** | E4-S5 (ingredient sufficiency and shortfall feed) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-032 (planned meals), UAT-022 and UAT-028 (stock) |
| **Environment needs** | None |

## What this feature is for

Shortages should surface while you are planning, not at the stove with a hundred people waiting. Every
planned meal shows whether the temple actually has what it needs — and, crucially, two meals needing
the same rice cannot both claim to be covered by one sack.

## How it is supposed to work

- For each planned meal, the recipe is scaled to the target servings and compared against current
  stock — **minus whatever is already promised to other uncooked meals**.
- The meal shows one of three states: **ok** (sufficient), **short**, or **planning**.
- The detail shows, per ingredient: what is needed, what is on hand, and how much is missing.
- The total shortfall across the coming days is what feeds the suggested shopping list (UAT-038).

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/inventory** first, to write down exactly what is on hand for Rice, Toor Dal and Ghee.
- Then **/planner**.
- Bring a calculator. Khichdi at 100 servings needs Rice 8 Kg, Toor Dal 3 Kg, Ghee 1 L, so at 150
  servings it needs 12 Kg, 4.5 Kg and 1.5 L.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Note current stock: Rice ___ Kg, Toor Dal ___ Kg, Ghee ___ L | Written down |
| 2 | Plan **Khichdi** for **150 servings** tomorrow, where stock comfortably covers it | The meal is badged **ok** |
| 3 | Open the meal's detail (click it) | Per ingredient: needed, on hand, and nothing short |
| 4 | Plan a second **Khichdi**, also 150 servings, the day after — enough to exceed the remaining stock | The second meal is badged **short** |
| 5 | Open the second meal's detail | It names each ingredient that falls short and by how much |
| 6 | Do the arithmetic yourself | The shortfall equals *(needed by both meals) − (stock on hand)* — the first meal's claim is counted, not ignored |
| 7 | Record a gift (UAT-028) of enough Rice, Toor Dal and Ghee to cover the gap | Return to the planner |
| 8 | Look at the second meal again | It has flipped to **ok** without you touching the plan |
| 9 | Adjust stock **down** below what is needed (UAT-024) | The meal flips back to **short** |
| 10 | Plan a **3,000-serving** festival meal | Almost certainly **short**, with large, correct shortfall figures |
| 11 | Cancel one of the two competing meals | The other should stop being short, since the rice is no longer double-promised |

## It passes if

- [ ] Every planned meal shows a sufficiency state.
- [ ] The per-ingredient detail shows needed, on hand and short-by, and the numbers are arithmetically right.
- [ ] Two meals needing the same stock cannot both read **ok** — the second is short.
- [ ] Receiving or adjusting stock changes the badges without re-planning.
- [ ] Cancelling a meal releases its claim on the stock.

## Watch out for

- **The double-booking check (steps 4–6) is the heart of this test.** If both meals read **ok** against stock that only covers one, that is a Major defect — the temple would go shopping for nothing and then run out.
- Badges that only update after a full page reload — note it as Minor.
- Sufficiency that ignores catering bookings (UAT-033). Plan a 400-serving catering meal and check it consumes the same pool.
- Shortfall figures that disagree with the scaled recipe from UAT-017. If they do, one of the two is wrong; record both numbers.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT034-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
