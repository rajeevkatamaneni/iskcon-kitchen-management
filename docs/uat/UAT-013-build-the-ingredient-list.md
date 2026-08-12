# UAT-013: Build the ingredient list

| | |
|---|---|
| **Feature area** | Recipes — ingredient master |
| **Technical stories** | E2-S1 (ingredient master) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-008 |
| **Environment needs** | None |

## What this feature is for

Recipes, the store room and purchase orders must all speak the same ingredient language. If a recipe
says "Toor Dal" and the store room says "Arhar Dal", nothing lines up. So the temple keeps one
catalogue of ingredients, each with its unit, and everything else refers to it.

## How it is supposed to work

- Each ingredient has a **name**, a **category** (Grains, Pulses, Spices…), a **canonical unit**
  (Kg, gm, L, ml, pieces), and optional **aliases** — so "Arhar Dal" and "Toor Dal" can be the same thing.
- The catalogue belongs to the temple: each temple curates its own.
- A new temple starts with the common prohibited items and staple grains and pulses already present.
- An ingredient that a recipe uses cannot simply be deleted — it has to be freed first.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/ingredients** (menu: **Ingredients**)

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Ingredients** | A list that is **not** empty: Onion, Garlic, Mushroom, Egg (all marked **Prohibited**) and Rice, Wheat Flour, Semolina, Toor Dal, Moong Dal, Chana Dal, Urad Dal |
| 2 | Open **Add an ingredient** | Fields: Name, Category, Unit, Aliases (comma-separated), and a *Sattvic-prohibited* tick |
| 3 | Add `Ghee`, category `Dairy`, unit `L` | It appears in the list, marked **Allowed** |
| 4 | Add `Mango Pulp`, category `Fruit`, unit `Kg`, aliases `Aam Ras Pulp, Mango Puree` | Added with its aliases |
| 5 | Add `Sugar`, category `Sweeteners`, unit `Kg` | Added |
| 6 | Add `Cardamom`, category `Spices`, unit `gm` | Added |
| 7 | Try to add `ghee` again (different capitalisation) | Refused: *An ingredient with that name already exists* (`KMS-4903`), suggesting an alias instead |
| 8 | Press **Edit** on **Mango Pulp**, change its category to `Fruits`, and **Save** | The change is kept |
| 9 | Press **Delete** on **Cardamom** | It is removed from the list |
| 10 | Note the unit shown for each ingredient | Each shows exactly one canonical unit — the unit its stock and recipe quantities will be counted in |
| 11 | *(After UAT-015)* Come back and try to delete an ingredient a recipe uses | Refused: *That ingredient is used by one or more recipes* (`KMS-4904`) |

## It passes if

- [ ] The temple starts with the seeded ingredients present and correctly flagged.
- [ ] A new ingredient can be added with a name, category, unit and aliases.
- [ ] A duplicate name is refused with `KMS-4903` and an alias is suggested.
- [ ] An ingredient can be edited and deleted.
- [ ] An ingredient in use by a recipe cannot be deleted (`KMS-4904`).

## Watch out for

- Whether the unit list matches what a temple kitchen actually uses: Kg, gm, L, ml, pieces. Anything missing that the temple needs is a finding worth recording.
- Aliases that do not actually help you find the ingredient later. UAT-016 checks searching; if aliases do nothing there, note it against this test too.
- Deleting an ingredient that stock movements refer to. It should be as protected as one used by a recipe. If it silently deletes, that is a Major defect.
- Category is free text here rather than a fixed list. Inconsistent categories ("Fruit" vs "Fruits") are a usability finding — record it if it bothers you as a user.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT013-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
