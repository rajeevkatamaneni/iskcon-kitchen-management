# UAT-091: Merge duplicate ingredients

| | |
|---|---|
| **Feature area** | Ingredients — cleaning up duplicates that already exist |
| **Technical stories** | Procurement release 2026-09-19: R-DUP-3 (the merge tool, permission `MERGE_INGREDIENTS`). Tasks T-270, T-276 |
| **Roles exercised** | Temple admin; kitchen manager and kitchen staff (to prove the refusal) |
| **Depends on** | UAT-087 (duplicate prevention), UAT-089 (UAT Grain House), UAT-015 (recipes) |
| **Environment needs** | None |

> **Merge only the pair you make below.** Rajeev decided on 2026-09-19 that the merge is **not run on
> staging's real ingredients**: the catalogue will be cleaned at its source and the data reset, so the
> groups the screen proposes for real ingredients are left alone. A merge cannot be undone. If you are in
> any doubt, run this test on a local copy instead and say so in your report.

## What this feature is for

Duplicate prevention (UAT-087) stops new duplicates. This tool deals with the ones already there:
"Curd, fresh", "Curd, sour" and "Curd, whisked" become **Curd**, their stock is added together, and the
recipes keep "fresh", "sour" and "whisked" as preparation notes.

## How it is supposed to work

- Only the **Temple Admin** has it: a **Merge duplicates** button on **/ingredients**, opening
  **/ingredients/merge**, **Merge duplicate ingredients**.
- It **proposes** groups, such as *Curd, fresh / Curd, sour / Curd, whisked → Curd*, each showing the
  preparation note it would move onto the recipe lines. The admin keeps one, takes others out, edits the
  note, or adds one by hand.
- A **preview** says what will happen: on hand after, recipe lines moving, vendor supplies moving, and
  that the other name stays as an alias. Where the same vendor sells two of them at different prices, it
  asks which price to keep, and won't merge until you choose. Ingredients counted in different kinds of
  unit can't be merged.
- An approved merge, in one go: repoints everything that used the old ingredient to the kept one; moves
  the preparation words onto the recipe lines; adds the stock together (every stock movement kept, none
  deleted); merges vendor supplies, pack sizes, market rate and price history; and writes an audit entry.
  The old name becomes an **alias**, so searching for it, or importing it again, finds the kept one.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Make the pair:**
  1. On **/ingredients**, add **UAT Jaggery** (Sweeteners or any, **Kg**). Add **UAT Jaggery, grated**
     (Kg): you are asked **Did you mean UAT Jaggery?** — choose **It’s a different ingredient**, then
     **Keep it separate**. (This is how duplicates got in.)
  2. Put both in inventory (UAT-090): UAT Jaggery **10 Kg**, UAT Jaggery, grated **4 Kg**.
  3. On UAT Jaggery's page add the pack **Bag = 25 Kg**; on UAT Jaggery, grated's page add **500 gm**.
  4. On **UAT Grain House**, supply UAT Jaggery as **Bag = 25 Kg at ₹1,400**, and UAT Jaggery, grated as
     **Kg at ₹50**.
  5. Write a recipe **UAT Jaggery drink** (UAT-015) with a line of **UAT Jaggery, grated**, 1 Kg.
  6. On **/shopping-list**, **Add something to the list**: UAT Jaggery 50 Kg, UAT Jaggery, grated 3 Kg.
- **Start at:** **/ingredients**

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Sign in as `ikms.kitchen-staff.5@trading4good.org` (kitchen manager). Look at **/ingredients**, then type **/ingredients/merge** | No **Merge duplicates** button. The address shows **Not your page**. Do the same as `ikms.kitchen-staff.1@trading4good.org` (kitchen staff): the same |
| 2 | Back as the temple admin, on **/ingredients** press **Merge duplicates** | **Merge duplicate ingredients**. While it looks: **Looking for duplicates…**. Then a list of proposed groups |
| 3 | Find the group **UAT Jaggery, grated → UAT Jaggery** | A table: Keep · Ingredient · Preparation note · Unit · Recipe lines · On hand · Actions. **UAT Jaggery** is kept; **UAT Jaggery, grated** has the note **grated**, 1 recipe line, 4 Kg |
| 4 | Look at the other groups | Read them and write down two that look right and any that look wrong. **Do not merge them** |
| 5 | In the UAT Jaggery group, **Add an ingredient to this group**: choose **UAT Groundnut Oil** (counted in L) | **These can’t be merged**: they are counted in different kinds of unit (`KMS-400171`). Take UAT Groundnut Oil back out |
| 6 | Ask for the preview | **On hand after 14 Kg**, recipe lines moving **1**, vendor supplies moving **1**, and that the other name stays. It asks: **UAT Grain House has a different price for two of these. Which price should it keep?**, offering the two prices written the same way (per Kg, with the pack) |
| 7 | Look at the merge button before choosing a price | **Merge into UAT Jaggery** can't be pressed |
| 8 | Choose the **₹1,400 / bag** (₹56 / Kg) price. Press **Merge into UAT Jaggery** | A confirmation: **Merge 1 ingredient into UAT Jaggery? …** ending **This can’t be undone.** The focus is on **Go back** |
| 9 | Confirm | **Merged into UAT Jaggery.** The group is gone from the list |
| 10 | **/inventory** → UAT Jaggery | **14 Kg**. Its movement history holds **both** earlier movements (10 Kg and 4 Kg). Nothing was deleted |
| 11 | Open the recipe **UAT Jaggery drink** | The line reads **UAT Jaggery · grated** |
| 12 | **/shopping-list** | **One** UAT Jaggery line, **53 Kg**. No UAT Jaggery, grated |
| 13 | **UAT Grain House**'s page | One UAT Jaggery supply at **₹1,400 / bag · ₹56 / Kg**. UAT Jaggery's own page shows the packs **500 gm · Bag = 25 Kg**, with no duplicate |
| 14 | On **/ingredients**, search for `UAT Jaggery, grated` | It finds **UAT Jaggery**, where the old name is now one of its **Other names** |
| 15 | Try to add an ingredient called `UAT Jaggery, grated` | **Did you mean UAT Jaggery?** The old name leads to the kept one |
| 16 | Open **/audit** | **Merged UAT Jaggery, grated into UAT Jaggery.** with your name |
| 17 | Measure the merge screen and its preview at **1280px**, **1024px** and **390px** | Nothing cut off, no word split, nothing scrolls sideways |

## It passes if

- [ ] Only the Temple Admin sees and can open the merge tool.
- [ ] Groups are proposed with the preparation note each would move onto the recipes.
- [ ] Ingredients in different kinds of unit can't be merged.
- [ ] A price clash at one vendor has to be settled before merging.
- [ ] After the merge: on-hand stock is the sum, every movement is kept, the recipe line carries the note, the shopping list has one line, the supplies and packs are merged.
- [ ] The old name is an alias: searching or re-adding it finds the kept ingredient.
- [ ] The merge is in the audit log, and the confirmation says it can't be undone.

## Watch out for

- **Stock that is not the sum** (14 Kg), or a stock movement that disappeared. Blocker.
- **A recipe that lost its preparation word.** "UAT Jaggery" with no "grated" changes the recipe. Major.
- **Anything still pointing at the old ingredient**: an order line, a request, a delivery, a price row.
  The automated check proves none is left in the database; if you find one on a screen, write down where.
- **Running a proposed merge on a real ingredient.** Don't. If it happened by accident, say which, at once.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT091-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
