# UAT-017: Scale a recipe for a festival

| | |
|---|---|
| **Feature area** | Recipes — scaling |
| **Technical stories** | E2-S3 (recipe scaling) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-015 |
| **Environment needs** | None |

## What this feature is for

A temple cooks for about 150 people on an ordinary day and for 1,200 or more at a festival. Doing that
arithmetic by hand, at the stove, for twenty ingredients, is how mistakes happen. Any recipe stored at
its base yield rescales to whatever is needed.

## How it is supposed to work

- Enter a target yield; every ingredient quantity scales by the same ratio.
- Quantities are presented sensibly — 24,000 gm is shown as 24 Kg — while the exact unrounded values
  are what the rest of the system (stock checks, shopping lists) uses underneath.
- Nothing is stored per scale: scaling is worked out fresh each time.
- Festival scale must work — up to tens of thousands of servings — without breaking.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/recipes** → open **Aam Ras** (base yield 100 servings: Mango Pulp 10 Kg, Sugar 2.5 Kg, Ghee 0.5 L)
- Bring a calculator. You are checking arithmetic.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Aam Ras** | Base yield 100 servings, and the ingredient table at base quantities |
| 2 | In **Scale to**, enter `200` and apply | Mango Pulp **20 Kg**, Sugar **5 Kg**, Ghee **1 L** — exactly double |
| 3 | Scale to `40` | Mango Pulp **4 Kg**, Sugar **1 Kg**, Ghee **0.2 L** |
| 4 | Scale to `150` | Mango Pulp **15 Kg**, Sugar **3.75 Kg**, Ghee **0.75 L** |
| 5 | Scale to `1200` (a festival) | Mango Pulp **120 Kg**, Sugar **30 Kg**, Ghee **6 L** |
| 6 | Press **Reset** | Back to the base 100-serving quantities |
| 7 | Open **Khichdi** (Rice 8 Kg, Toor Dal 3 Kg, Ghee 1 L at 100) and scale to `3000` | Rice **240 Kg**, Toor Dal **90 Kg**, Ghee **30 L** |
| 8 | Scale to `50000` | It computes, without error, without hanging, and the numbers are right (Rice 4,000 Kg) |
| 9 | Scale to `0` | Refused, or simply not applied — a zero-serving recipe is meaningless |
| 10 | Scale to `-5` | Refused or not applied |
| 11 | Scale to `137` (an awkward number) | Quantities scale correctly; check how many decimals are shown and whether they read sensibly for a kitchen |
| 12 | Check the units on a small quantity — scale **Aam Ras** to `10` | Ghee is 0.05 L. Note whether it is shown as `0.05 L` or promoted to `50 ml`. Record which |

## It passes if

- [ ] Every scaled quantity matches the arithmetic you did on the calculator.
- [ ] Reset returns to the base recipe.
- [ ] Festival scale (3,000 and 50,000) computes correctly and quickly.
- [ ] Zero and negative targets are refused.
- [ ] Numbers are shown to a sensible precision, in units a cook can work with.

## Watch out for

- **Step 12 is deliberate.** The design says small quantities should be promoted to readable units (gm→Kg, ml→L as appropriate). If 0.05 L is shown rather than 50 ml, record it as Minor with the exact numbers — it is a real usability question for a kitchen printout.
- Rounding that drifts: scale to 3,000, then back to 100, and check you get the original numbers back.
- Quantities shown with an absurd number of decimal places (`3.7499999998`) — Minor, but note it.
- The scaled quantity should be what a PDF at that scale prints (UAT-019) and what the stock check uses (UAT-034). If those disagree later, come back to this test.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT017-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
