# UAT-074: Quantities read the way a cook says them

| | |
|---|---|
| **Feature area** | Across the product — units and quantities |
| **Technical stories** | E11-S3 (one way to say a quantity), E11-S4 (every screen says it the same way), E11-S5 (documents and emails say it the same way), E11-S6 (every dropdown offers the one list) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-015 (the recipes), UAT-017 (scaling), UAT-022 (inventory), UAT-032 (a planned meal) |
| **Environment needs** | **None** for the screens. The job-card and work-order steps need the **background worker and a real document renderer**; the low-stock email step needs a **live message channel** — see §4 of the README |

## What this feature is for

Nobody in a kitchen says "nought point six kilos of rice". They say six hundred grams. A screen that
writes `0.6 Kg` is not wrong, but it makes a cook do arithmetic over a hot stove, and arithmetic over a
hot stove is how a batch gets ruined.

The same goes for false precision. `10.08 Kg` and `10 Kg` are the same sack of rice. Nobody is weighing
gold here.

But there is one place where the exact figure matters more than the readable one: the store's books.
A person reconciling a shelf against a count needs the rows on the screen to **add up**, and rounded
rows do not. So there are two modes, and this test checks that each is used where it belongs:

- **Cook's figures — rounded.** Anything somebody weighs or buys against: recipes, planner targets, job
  cards, work orders, shopping lists, shortfalls, low-stock notices.
- **Ledger figures — exact.** Anything somebody reconciles or is audited on: stock on hand, movement
  rows, batch quantities, goods receipts, invoice and purchase-order quantities.

## How it is supposed to work

- There is **one list of units** everywhere a unit is asked for: **Kg, gm, L, ml, pieces** — plus
  **servings**, which appears only where a recipe's yield is named, because an ingredient can never be
  measured in servings.
- A quantity is shown in whichever unit of its own family reads naturally: **below 1,000 of the small
  unit it stays small** (600 gm, 600 ml), **at 1,000 and above it promotes** (1.5 Kg, 175 L).
- A cook's figure is then rounded on a ladder that **grows with the size of the number** — the way a
  person rounds:

| Size of the number shown | Rounded to the nearest |
|---|---|
| under 1 | 0.1 |
| 1 to under 10 | 0.5 |
| 10 to under 100 | 1 |
| 100 to under 1,000 | 5 |
| 1,000 and above | 10 |

- **Counts are whole.** Pieces and servings never take a fraction — half an idli is not a plan.
- **Rounding is the last thing that happens before the characters reach the screen.** It never touches a
  stored value and it never enters a calculation, so twelve rounded lines cannot drift a recipe.
- An unknown quantity reads **—**, not `0`.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Add these ingredients** on **/ingredients** if they are not already there:

| Ingredient | Category | Unit |
|---|---|---|
| Jaggery | Sweeteners | `Kg` |
| Grated Coconut | Produce | `gm` |
| Cardamom | Spices | `gm` |
| Dry Ginger | Spices | `gm` |
| Edible Camphor | Spices | `gm` |

- **Create this recipe.** Its quantities are chosen deliberately: scaled by a fifth, every line lands on
  one of the cases this rule was designed from, so you can check them all on one screen.

| | |
|---|---|
| Name | **Sweet Pongal** |
| Category | Sweets |
| Base yield | **100 servings** |
| Ingredients | Rice `8.4` Kg · Jaggery `6` Kg · Grated Coconut `700` gm · Cardamom `112` gm · Dry Ginger `42` gm · Edible Camphor `4.2` gm · Ghee `3` L |

- For §C you need **Ghee stock of exactly 173.542 L**. Get there like this: on **/inventory** → Ghee,
  note what is on hand and adjust it **down to zero** with reason **Count correction** (UAT-024); then
  record three gifts (UAT-028) of **100 L**, **50 L** and **23.542 L**, each with a different expiry.
  If the form will not accept three decimal places, **stop and record that** — it is a finding, and it
  makes the rest of §C unrunnable as written.

## Steps

### A. Six hundred grams, not nought point six kilos

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Sabudana Khichdi** and scale it to **120** servings (UAT-017) | The Sugar line — `0.5` Kg at base — reads **`600 gm`**. **Not `0.6 Kg`** |
| 2 | Open **Aam Ras** and scale it to **120** servings | The Ghee line — `0.5` L at base — reads **`600 ml`**. **Not `0.6 L`** |
| 3 | Open **Khichdi** at its base 100 servings | Rice **`8 Kg`**, Toor Dal **`3 Kg`**, Ghee **`1 L`** — a whole unit stays where it is |
| 4 | Scale **Khichdi** to `1200` servings | Rice **`96 Kg`**, Toor Dal **`36 Kg`**, Ghee **`12 L`** |
| 5 | Look at any unit label on any of those screens | `Kg`, `gm`, `L`, `ml` — never `KG`, never `LITRES`, never `kg` on one screen and `Kg` on the next |

### B. Rounded the way a person rounds

| # | Do this | You should see |
|---|---|---|
| 6 | Open **Sweet Pongal** and scale it to **120** servings | Seven lines. Check each against the next four rows — **every one of them, not a sample** |
| 7 | **Rice** (8.4 Kg × 1.2 = 10.08) | **`10 Kg`** — not `10.08 Kg`, not `10.1 Kg` |
| 8 | **Cardamom** (112 gm × 1.2 = 134.4) | **`135 gm`** — rounded to the nearest 5, because it is in the hundreds |
| 9 | **Dry Ginger** (42 gm × 1.2 = 50.4) and **Edible Camphor** (4.2 gm × 1.2 = 5.04) | **`50 gm`** and **`5 gm`** |
| 10 | **Grated Coconut** (700 gm × 1.2 = 840) | **`840 gm`** — already on the ladder, so it does not move |
| 11 | **Jaggery** (6 Kg × 1.2 = 7.2) and **Ghee** (3 L × 1.2 = 3.6) | **`7 Kg`** and **`3.5 L`** — between 1 and 10, so to the nearest half |
| 12 | Scale **Sweet Pongal** to **1,000** servings and read the Cardamom line (112 gm × 10 = 1,120 gm) | **`1.12 Kg`** rounded on the ladder — a figure in kilos between 1 and 10, so **`1 Kg`**. Whatever it says, it must be the same rule as step 11, applied to a bigger number |
| 13 | Plan a meal of **Sweet Pongal for 120 people** (UAT-032) and read the target and the ingredient figures | The **same numbers as step 6**. A planner target and a recipe line for the same food must not disagree |
| 14 | Look at any **servings** or **pieces** figure anywhere | A whole number. Never `120.5 servings`, never `3.4 pieces` |
| 15 | Find a quantity the system does not know — an inventory item with no stock recorded, or a target yield that has not been set | It reads **`—`**, not `0`. An unknown is not a zero |

### C. Cook's figures and ledger figures, on the same ghee

| # | Do this | You should see |
|---|---|---|
| 16 | Open **/inventory** → **Ghee** | On hand reads **`173.542 L`** — **exactly**, to three decimals. **Not `175 L`**, not `173.54 L`, not `173.5 L` |
| 17 | Read its three batch rows | **`100 L`**, **`50 L`**, **`23.542 L`** |
| 18 | Add them up by hand | **173.542.** They visibly sum to the balance above them. This is the whole reason the ledger is not rounded — a person reconciling a shelf needs the rows to add up |
| 19 | Read the **movement history** rows | Exact figures, matching what you recorded |
| 20 | Go to **/inventory** (the list) and find Ghee's row | **`173.542 L`** there too. The list and the item page agree |
| 21 | Now plan a Janmashtami meal: **Aam Ras for 34,700 servings** (UAT-032) | Its Ghee requirement is 173.5 L — and the planner shows it as a **cook's** figure: **`175 L`** |
| 22 | Put steps 16 and 21 side by side | **`173.542 L` on the store's books, `175 L` on the cooking figure.** They are the same ghee and they say different things **on purpose**. If both read `175 L`, the ledger has been rounded — Blocker. If both read `173.5 L`, the cook's figure has not been — Major |
| 23 | Look at the sufficiency check for that meal (UAT-034) | The shortfall on Mango Pulp is a **cook's** figure, written like a quantity — **not** `3470 KG` with a raw unit name |

### D. Rounding must not enter the arithmetic

| # | Do this | You should see |
|---|---|---|
| 24 | Take **Sweet Pongal at 120 servings** and add the two spice lines you were shown — 135 gm + 50 gm | 185 gm |
| 25 | Now compute it from the recipe yourself: (112 + 42) × 1.2 | 184.8 gm — which the system should show as **`185 gm`** if it prints a total. A total is summed **exact and rounded once at the end**, never by adding rounded lines |
| 26 | Scale **Sweet Pongal** to 120, then back to 100, then to 120 again | The figures are **identical** each time. Rounding is not being fed back into the stored recipe |
| 27 | Cook a meal (UAT-035) whose recipe line displays a rounded figure, and check the stock that comes off | The stock falls by the **exact** amount, not the rounded one. This is the most important check in this section — if 10.08 Kg of rice is displayed as `10 Kg` and **10 Kg** is deducted, the books have started drifting. Blocker |

### E. Every screen says it the same way

| # | Do this | You should see |
|---|---|---|
| 28 | Open **/shopping-list** (UAT-038) | Quantities written as quantities. **No `652 KG`** — no raw unit name in capitals anywhere on the page |
| 29 | Open a purchase order, **/orders/[id]** (UAT-039) | The same. **No `40 KG`** |
| 30 | Open **/today** and find the target yield for a planned meal | It has **a unit on it**. A bare number with no unit is a defect |
| 31 | Walk through **/recipes**, a recipe page, **/ingredients**, **/inventory**, an inventory item, the planner day view, and a goods receipt | The **same** labels everywhere: `Kg`, `gm`, `L`, `ml`, `pieces`, `servings`. Write down any screen that differs, and exactly how |
| 32 | Look for any quantity anywhere written as a bare enum name — `KG`, `ML`, `LITRES`, `SERVINGS`, `PIECES` | There should be none. **List every one you find, with the screen** |
| 33 | Open a recipe whose yield was recorded in litres (from the recipe library, or one you make) | The unit reads **`L`**. The word **`LITRES`** must not appear anywhere in the product |
| 34 | Open a **library** recipe measured in litres (**/recipes/library**) and check its per-head portion | It still has one. If per-head portions have gone missing from volume recipes, that is a Blocker — record which recipes |

### F. What gets printed, and what gets emailed

*(These steps need the background worker and a real document renderer.)*

| # | Do this | You should see |
|---|---|---|
| 35 | Download the **job card** for the planned Sweet Pongal (from the planner day view) | Every ingredient line reads **exactly as it does on screen**: `10 Kg`, `135 gm`, `50 gm`, `5 gm`, `840 gm`, `7 Kg`, `3.5 L` |
| 36 | Download the **recipe card** for the same recipe at the same scale (UAT-019) | **Identical lines.** A job card and a recipe card must not write the same ingredient two different ways |
| 37 | Download the **job card** for the Janmashtami Aam Ras | The Ghee line reads **`175 L`** — a figure somebody can weigh against |
| 38 | Download a **work order** (UAT-071) | Cook's figures there too: `120 gm`, never `0.12 Kg` |
| 39 | Download a **purchase-order sheet** (UAT-041) | Quantities written as quantities, not `40 KG` |
| 40 | *(If a message channel is live)* Trigger or find a **low-stock notice** (UAT-023) | It names the item and a readable quantity — `Ghee (175 L)`. **Never `Ghee (173542 ML)`** |
| 41 | Produce a job card in **Hindi** or another Indian script (UAT-020) | The numbers and units are untouched and the script renders cleanly |

### G. One list, in every dropdown

| # | Do this | You should see |
|---|---|---|
| 42 | **/ingredients** → Add an ingredient → the **Unit** dropdown | **Kg, gm, L, ml, pieces.** **No servings** — an ingredient can never be measured in servings |
| 43 | **/recipes** → New recipe → the **base yield unit** dropdown | The five, **plus servings** |
| 44 | On the same form, the **per-head portion** unit dropdown | The five, and **never servings** — what one person eats is a quantity of food, not a count of servings |
| 45 | **/inventory** → Add to inventory, choose **Rice**, open the unit dropdown | **Kg and gm only** — filtered to that ingredient's family |
| 46 | Do the same having chosen **Ghee** | **L and ml only** |
| 47 | **/inventory/[id]** → Adjust stock, on Rice | Kg and gm only |
| 48 | **/donations/new** → a gift of goods → the unit dropdown | The five physical units |
| 49 | **/ingredient-requests/new** → an ingredient line on Rice (UAT-068) | Kg and gm only. On a **dish** line: all six, servings included |
| 50 | Try to force a cross-family unit anywhere — 3 L of Rice | Refused with a plain message that says what is wrong, not a code with no explanation |
| 51 | Compare the wording in all eight dropdowns | The same words in the same order everywhere. If one says `LITRES` or `kg` while another says `L` or `Kg`, record which |

### H. What you type is what you see while you type it

| # | Do this | You should see |
|---|---|---|
| 52 | Edit a recipe line and type `0.5` into a quantity box, in Kg | It stays **`0.5`** while you type. It must **not** rewrite itself to `500` under your cursor |
| 53 | Save, and look at the same line in the recipe's table | Now it reads **`500 gm`** |
| 54 | Edit it again | The box shows **`0.5`** again — what was stored, not what was displayed |

## It passes if

- [ ] `0.6 Kg` reads **600 gm** and `0.6 L` reads **600 ml**, everywhere they appear.
- [ ] Rajeev's five cases all hold on one screen: `10.08 → 10 Kg`, `134.4 → 135 gm`, `50.4 → 50 gm`, `5.04 → 5 gm`, `840 gm → 840 gm`.
- [ ] The rounding step grows with the size of the number, consistently, at every magnitude.
- [ ] Counts are whole; an unknown reads `—`, not `0`.
- [ ] The Ghee balance reads **173.542 L** exactly and its batch rows visibly sum to it, while the cooking figure for the same ghee reads **175 L**.
- [ ] Stock deductions use the exact figure, never the rounded one, and re-scaling a recipe is repeatable.
- [ ] No screen anywhere prints a raw unit name (`KG`, `ML`, `LITRES`), and no quantity is printed without a unit.
- [ ] The word `LITRES` appears nowhere, and library recipes measured in litres still have their per-head portions.
- [ ] A job card, a recipe card, a work order and a purchase-order sheet write the same ingredient line identically, in cook's figures.
- [ ] All eight dropdowns offer the one list, filtered to what can be true in that place, with the same wording.
- [ ] A quantity being typed is left exactly as typed.

## Watch out for

- **Step 27 is the one that matters most.** Everything else on this page is cosmetic; that one is the books. If a rounded figure ever reaches a stock movement, the store room and the ledger start drifting apart and nothing on screen says so. Check the arithmetic by hand.
- **Step 22 is the second.** The two modes disagreeing is the design, not a bug. Reporting "the inventory page says 173.542 and the job card says 175" as a defect is exactly the mistake this test exists to prevent — but reporting that the **inventory rows no longer add up** is a Blocker.
- The two halves of the old behaviour still showing through: one screen writing `0.6 Kg` and another writing `600 gm` for the same value, or `Kg` on one and `kg` on the next. Say which screens.
- A quantity that has been rounded **inside an input box** you are editing.
- A total that equals the sum of the **rounded** lines rather than the rounded sum of the exact ones. Check step 25 with a calculator.
- Rounding applied to money. This rule is about weights and volumes; a rupee figure that starts rounding to the nearest 5 is a serious defect — record it immediately.
- Anything on a purchase order or a goods receipt that has been rounded. Those are figures a vendor invoices against, and they are ledger figures.
- The unit dropdown on an ingredient offering **servings**, or a recipe's per-head portion offering it.
- If your temple has recipes created before this change, check a few of them specifically — an old row still holding the word `LITRES` would show up there first.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT074-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
