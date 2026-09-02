# UAT-035: Cook a meal — stock comes down

| | |
|---|---|
| **Feature area** | Meal planning — consumption on production |
| **Technical stories** | E3-S6 (consumption on meal production), E4-S4 (meal plan) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-034 |
| **Environment needs** | None |

## What this feature is for

When a meal is actually cooked, the store room must reflect it — without anybody doing a second piece
of bookkeeping. Marking a meal cooked draws down exactly what the recipe called for, from the batches
that should be used first.

## How it is supposed to work

- **Mark as cooked** writes the consumption for the scaled recipe against stock.
- Batches are used **first-expiring-first**, so the oldest goods leave the shelf first.
- If there is not enough stock, the whole thing is **refused** — with the shortfalls named. Nothing is
  half-written; you either cook it or you don't.
- Once cooked, the meal cannot be cancelled; a mistake is corrected with a stock adjustment instead.
- Every resulting movement references the meal it came from.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/inventory** — write down Rice, Toor Dal and Ghee quantities and their batches.
- **Set up two batches** of Rice so first-expiring-first can be tested: record two gifts (UAT-028) of
  20 Kg each, one expiring next month and one expiring in six months.
- Then **/planner**.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Write down: Rice batch A (expiring soonest) ___ Kg, batch B ___ Kg, Toor Dal ___, Ghee ___ | Noted |
| 2 | On a planned **Khichdi for 150 people** badged **ok**, press **Cook** | The meal's status changes to **cooked** |
| 3 | Go to **/inventory** → **Rice** | Total stock has fallen by exactly **12 Kg** (8 Kg per 100 servings × 1.5) |
| 4 | Look at the batches | The **soonest-expiring** batch was drawn down first. If it held less than 12 Kg, it is now empty and the remainder came from the next batch |
| 5 | Check Toor Dal (−4.5 Kg) and Ghee (−1.5 L) | Both reduced by the scaled amounts |
| 6 | Look at Rice's **Movement history** | A *consumption* row of −12, referencing this meal plan |
| 7 | Back on the planner, try to **Cancel** the cooked meal | Refused: *This meal has already been cooked, so it can't be cancelled* (`KMS-4914`), advising a stock adjustment if the stock was wrong |
| 8 | Try to **Cook** it a second time | Refused — a meal that is already cooked cannot be cooked again (`KMS-4915`) |
| 9 | Plan a meal you know there is **not** enough stock for — Khichdi for **3,000** people, the head count typed into step 2 of the form as UAT-032 describes — and press **Cook** | Refused: *There isn't enough stock to cook this* (`KMS-4911`), naming what is short |
| 10 | Check stock after that refusal | **Completely unchanged** — not one ingredient was deducted |
| 11 | Cook a meal whose recipe includes an ingredient with **no** stock at all | Refused the same way, naming that ingredient |

## It passes if

- [ ] Cooking a meal reduces every ingredient by the correctly scaled amount.
- [ ] Batches are consumed soonest-expiry-first.
- [ ] The movements reference the meal plan.
- [ ] A cooked meal cannot be cancelled (`KMS-4914`) or cooked twice.
- [ ] Insufficient stock refuses the whole thing (`KMS-4911`) and changes nothing.

## Watch out for

- **Step 10 is critical.** A partial deduction on a refused cook would corrupt the store room silently. Check every ingredient, not just the one that was short.
- Whether you can choose a **different batch** by hand before cooking (the design allows a manual batch override). If there is no way to, record it — a cook may know the older sack is at the back.
- Rounding: 150 people at an 8 Kg-per-100 recipe is 12 Kg exactly, but try a head count of 137 and check the deduction against your own arithmetic.
- A meal you cannot plan at all because the form will not save without a head count is **correct** since 2026-08-31 (`KMS-4989`, UAT-032), not a fault in this test.
- Cooking a meal for a *past* date, or a *future* date. Record what the system allows.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT035-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
