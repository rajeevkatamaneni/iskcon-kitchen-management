# UAT-087: One ingredient, not five — duplicates and preparation notes

| | |
|---|---|
| **Feature area** | Ingredients — duplicate prevention |
| **Technical stories** | Procurement release 2026-09-19: R-DUP-1 (preparation notes, library copy), R-DUP-2 (duplicates blocked). Tasks T-249, T-250, T-251, T-297 |
| **Roles exercised** | Temple admin, kitchen manager, kitchen staff |
| **Depends on** | UAT-013 (ingredients), UAT-015 (recipes) |
| **Environment needs** | None |

## What this feature is for

"Curd", "Curd, fresh", "Curd, sour" and "Curd, whisked" are one thing on the shelf. When they are four
ingredients, the stock is split four ways, the shopping list asks for curd four times, and each has its
own price. Most of these came from the recipe library, which turned the way a thing is cut or cooked
("Cashew, halved", "Green chilli, slit") into ingredients of their own.

So the ingredient is kept separate from its **preparation**. "Slit" becomes a note on the recipe line,
and the stock, price and orders stay on "Green chilli".

## How it is supposed to work

- A recipe line has a **preparation note** ("slit", "halved", "sour"). It prints wherever the line
  prints: the recipe page, the job card, the recipe PDF, the translation.
- Creating or renaming an ingredient whose name is close to an existing one **stops the save** and asks
  **"Did you mean Curd?"** with the line **"Use Curd, or add a preparation note instead."** and two
  buttons, **Use Curd** and **It’s a different ingredient**.
- **It’s a different ingredient** asks once more (**Keep “Curd sour” separate from Curd?**) and the
  choice is written to the audit log.
- This check applies on the ingredient form, when renaming on the list, on the Supplies form, and when a
  library recipe is copied into the temple.
- Copying a library recipe that has close matches opens **one screen** listing all of them before
  anything is copied.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/ingredients**
- The temple needs ingredients named **Curd**, **Tomato, ripe**, **Green chilli** and **Ginger**. Add
  any that are missing with **Add an ingredient** before step 1. Write down how many ingredients the list
  shows in total.

## Steps

### Creating a near-duplicate is stopped

| # | Do this | You should see |
|---|---|---|
| 1 | Press **Add an ingredient**. Name `Tomatos`, category Vegetables, unit Kg. Press **Add ingredient** | A dialog: **Did you mean Tomato, ripe?**, the line **Use Tomato, ripe, or add a preparation note instead.**, and the buttons **Use Tomato, ripe** and **It’s a different ingredient**. Nothing was saved |
| 2 | Press **Use Tomato, ripe** | You go to the ingredient list with **Tomato, ripe** open for editing. No "Tomatos" was created |
| 3 | Add an ingredient named `Curd sour` | **Did you mean Curd?** — the same dialog |
| 4 | Press **Escape** | The dialog closes and the form still holds `Curd sour` |
| 5 | Press **Add ingredient** again, then **It’s a different ingredient** | A second question: **Keep “Curd sour” separate from Curd?** and the line *It will have its own stock, prices and orders. Your choice is recorded in the audit log.* The focus is on **Go back**, not on the act |
| 6 | Press **Go back** | Back to the first question |
| 7 | Press **It’s a different ingredient**, then **Keep it separate** | Saved. `Curd sour` is in the list |
| 8 | Open **/audit** | An entry for adding `Curd sour`, saying it was added although it looks like Curd and was confirmed as a different ingredient, with your name |
| 9 | On **/ingredients**, open the ingredient `Curd sour` for editing and rename it `Curds` | Stopped with **Did you mean Curd?**. A rename is checked the same way as a new name |
| 10 | Press **Use Curd** | The edit closes and **Curd** is opened instead. `Curd sour` keeps its name. Delete `Curd sour` now so it does not get in the way later |
| 11 | Open **/supplies**, press **Add a supply** and name it `Tomatos` | The same question. Supplies are ingredients too |
| 12 | Sign in as `ikms.kitchen-staff.5@trading4good.org` (kitchen manager) and repeat step 1 | The same dialog. Repeat as `ikms.kitchen-staff.1@trading4good.org` (kitchen staff): the same |

### The preparation note on a recipe line

| # | Do this | You should see |
|---|---|---|
| 13 | Back as the temple admin, open a recipe of your own (UAT-015) and press **Edit** | Beside each ingredient on a line there is a box with the placeholder **Preparation, e.g. slit** |
| 14 | Add a line **Green chilli** with the note `slit`. Save | The recipe page reads **Green chilli · slit** |
| 15 | Open the recipe's print view or PDF (UAT-019) | **Green chilli · slit** there too |
| 16 | Plan this recipe on a day and open that day's job card (UAT-035) | The line reads **Green chilli · slit** |
| 17 | Look at **/ingredients** | The count is the same as you wrote down (plus anything you added before step 1). No "Green chilli, slit" ingredient was made |

### Copying a recipe from the library

| # | Do this | You should see |
|---|---|---|
| 18 | Open **/recipes**, switch to the recipe library and open **Ambali**. Press **Add to my recipes** | It is copied with no question (none of its ingredients is a close match). Its lines read **Green chilli · slit** and **Buttermilk · sour** |
| 19 | Check the ingredient count | Unchanged. The copy used your existing Green chilli, with the note, and made no new ingredient |
| 20 | Open the library recipe **Allam Uragaya** and press **Add to my recipes** | Before anything is copied, one dialog: **Did you mean these ingredients?**, a sentence saying an ingredient in the recipe is close to one you already have, and one row per close match, for example *Ginger, peeled — Did you mean Ginger?* |
| 21 | Press **Add to my recipes** in the dialog without answering | **Choose an answer for every ingredient first.** and the focus moves to the first unanswered row |
| 22 | On the Ginger row press **Use Ginger** | The row reads **Using Ginger · peeled.** with a **Change** button |
| 23 | On the next row press **It’s a different ingredient**, then **Go back** | Back to the question for that row, nothing decided |
| 24 | Answer **Use** on every remaining row and press **Add to my recipes** | The recipe is copied. Its lines read **Ginger · peeled** and, for the other rows, the existing ingredient with the rest of the library name as its note (for example **Mustard · split**) |
| 25 | Check the ingredient count | Unchanged |
| 26 | Open **/audit** | The recipe import is recorded, including which close matches used an existing ingredient |
| 27 | Archive the **Allam Uragaya** copy you just made, then repeat steps 20–24 at **phone width (390px)** | The dialog fits the screen; nothing is cut off, no sideways scrolling, and every button can be reached |

### Things that must not create an ingredient

| # | Do this | You should see |
|---|---|---|
| 28 | On **/orders/new** (UAT-039), type `Tomatos plastic crate` in the Item box and choose **Add ‘Tomatos plastic crate’ as a one-off item** | A one-off line. Check **/ingredients**: no new ingredient. A one-off item never becomes one |

## It passes if

- [ ] "Tomatos" (with "Tomato, ripe" present) and "Curd sour" (with "Curd" present) are stopped with the exact question and buttons.
- [ ] **Use …** leads to the existing ingredient and saves nothing new.
- [ ] **It’s a different ingredient** needs a second, deliberate confirmation, and the choice is in the audit log.
- [ ] A rename, a supply and every staff role meet the same check.
- [ ] A recipe line carries a preparation note, shown as "Green chilli · slit" on the recipe, print/PDF and job card.
- [ ] Copying a library recipe makes no new ingredient where one exists, and keeps the preparation as a note.
- [ ] Close matches in a library copy are all asked on one screen, before anything is copied.

## Watch out for

- **An ingredient count that went up** after a library copy where you answered **Use** everywhere. That
  is the failure this exists to stop. Major.
- **A preparation word lost.** "Ginger, peeled" copied as plain "Ginger" with no note changes the recipe.
  "Mustard, split" without "split" changes what is bought. Major.
- **`KMS-400156`** — *There’s already an ingredient with a name very like this one.* — is what the server
  answers behind the dialog. If you see the code on screen instead of the dialog, write down where.
- **A match that is wrong.** If two genuinely different things are stopped as duplicates ("Rice flour"
  against "Rice", say), note both names. The check is meant to catch spelling, not to refuse real
  ingredients, and the second button exists for this.
- The recipe library will be cleaned and replaced later (Rajeev, 2026-09-19). If **Ambali** or **Allam
  Uragaya** is not in the library on the site you test, use any library recipe whose ingredient names
  carry a comma ("…, slit", "…, peeled") and say which you used.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT087-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
