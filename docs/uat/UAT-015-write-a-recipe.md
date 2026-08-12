# UAT-015: Write a recipe

| | |
|---|---|
| **Feature area** | Recipes |
| **Technical stories** | E2-S2 (recipe CRUD) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-013 |
| **Environment needs** | None |

## What this feature is for

A temple kitchen's knowledge lives in a handful of senior cooks' heads. This is where it becomes the
institution's: each recipe with its ingredients, its base yield, and its method — so a festival can be
cooked by whoever is available, not only by the one person who knows.

## How it is supposed to work

- A recipe has a **name**, a **category** (Rice, Dal, Sweets, Ekadashi…), a **base yield** (a number
  plus servings or litres — real temple recipe books use both), **ingredient lines** (ingredient,
  quantity, unit), a **method**, and optional notes and a region tag.
- Base yield is the anchor everything else scales from (UAT-017).
- A recipe needs at least one ingredient and a yield, and quantities must be positive.
- A recipe that a meal plan has used is archived rather than destroyed, so history stays readable.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/recipes** (menu: **Recipes**)
- **You will create these three recipes.** Later tests use them by name — create all three:

| Recipe | Category | Base yield | Ingredients |
|---|---|---|---|
| Aam Ras | Sweets | 100 servings | Mango Pulp 10 Kg, Sugar 2 Kg, Ghee 0.5 L |
| Khichdi | Rice | 100 servings | Rice 8 Kg, Toor Dal 3 Kg, Ghee 1 L |
| Sabudana Khichdi | Ekadashi | 100 servings | Ghee 1 L, Sugar 0.5 Kg *(add Sabudana as a new ingredient, unit Kg, 6 Kg)* |

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Recipes** | An empty list: *No recipes found* with an invitation to add one |
| 2 | Press **New recipe** | A form: Name, Category, Base yield, Yield unit, an Ingredients section, Method, Region tag, Sattvic override reason, Notes |
| 3 | Look at the **Category** choices | Beverages, Breakfast, Rice, Dal, Sabji, Roti, Sweets, Snacks, **Ekadashi** — seeded when the temple was created |
| 4 | Press **Create recipe** with nothing filled in | Refused; the missing required fields are marked. Nothing is saved |
| 5 | Fill in **Aam Ras** from the table. Add each ingredient line with **+ Add ingredient**, choosing the ingredient, typing the quantity, choosing the unit | Three ingredient lines |
| 6 | Type a method, one step per line | Accepted |
| 7 | Press **Create recipe** | Saved, and you are taken to the recipe |
| 8 | Read the recipe page | Name, category, base yield, the ingredient table with quantities and units, and the method as separate steps |
| 9 | Press **Edit**, change the sugar quantity to 2.5 Kg, and **Save changes** | The recipe shows 2.5 Kg |
| 10 | Edit again and use **Remove** to delete every ingredient line, then save | Refused — a recipe needs at least one ingredient |
| 11 | Create **Khichdi** and **Sabudana Khichdi** from the table | Both saved |
| 12 | Open **Sabudana Khichdi** | It carries an **Ekadashi-friendly** badge, because its category is Ekadashi |
| 13 | Try to create a second recipe also called `Khichdi` | Refused: *A recipe with that name already exists* (`KMS-4905`) |
| 14 | Try to save a recipe with a quantity of `-2` | Refused |

## It passes if

- [ ] All three recipes can be created with ingredients, yield, and method.
- [ ] The seeded categories are present, including Ekadashi.
- [ ] A recipe cannot be saved with no ingredients, no yield, or a negative quantity.
- [ ] A duplicate recipe name is refused with `KMS-4905`.
- [ ] Editing a recipe keeps the change.
- [ ] A recipe in the Ekadashi category is badged **Ekadashi-friendly**.

## Watch out for

- Whether an ingredient you need is missing from the picker — it must exist in **Ingredients** first (UAT-013). That is expected behaviour, but note it if the screen does not make that obvious.
- Units on ingredient lines that disagree with the ingredient's own unit (a recipe asking for Rice in litres). Note whether the system allows it and whether it should.
- The method losing its line breaks when saved — a Minor but irritating defect for a kitchen printout.
- The **Sattvic override reason** field is visible on this form. Leave it empty here; UAT-018 tests it.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT015-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
