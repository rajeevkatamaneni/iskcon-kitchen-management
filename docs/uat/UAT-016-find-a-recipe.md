# UAT-016: Find a recipe

| | |
|---|---|
| **Feature area** | Recipes — browse and search |
| **Technical stories** | E2-S7 (recipe browse and search UX), E2-S2 (recipe CRUD) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-015 |
| **Environment needs** | None |

## What this feature is for

During prep, someone needs "that Ekadashi kheer recipe" in seconds, usually on a phone, usually with
one hand. Finding a recipe has to be quicker than asking the person who wrote it.

## How it is supposed to work

- The list can be **searched by name** and **filtered by category** with one tap.
- Badges show at a glance what matters: **Ekadashi-friendly**, and **Sattvic override** where one was
  applied.
- The list is designed to work on a phone first.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/recipes**
- For a fair test of search, add three or four more recipes of your own first (any names) so the list
  is not trivially short.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Recipes** | *Your temple's recipes, ready to scale and print.* Your recipes listed, with category chips across the top including **All** |
| 2 | Type `khich` into the search box | The list narrows to **Khichdi** and **Sabudana Khichdi** as you type |
| 3 | Clear the search and press the **Ekadashi** category chip | Only Ekadashi recipes — **Sabudana Khichdi** |
| 4 | Press **Sweets** | Only **Aam Ras** |
| 5 | Press **All** | Everything returns |
| 6 | Search for something that does not exist, e.g. `zzz` | *No recipes found* with an invitation to try a different search or category — not a blank screen |
| 7 | Search using an **alias** you set in UAT-013, e.g. `Aam Ras Pulp` | Record what happens. Whether aliases are searchable is exactly what this step is checking |
| 8 | Note the badges in the list | **Ekadashi-friendly** on Sabudana Khichdi. After UAT-018 there will also be a **Sattvic override** badge |
| 9 | Click a recipe name | Its full page opens, with a link back to **Recipes** |
| 10 | Time yourself: from opening **/recipes**, how long to get to a named recipe? | It should be seconds, without waiting for a spinner |
| 11 | Repeat steps 1–5 on a phone (or a narrow browser window, about 360 pixels wide) | Everything is reachable and readable; nothing is cut off; the chips wrap rather than overflow |

## It passes if

- [ ] Search by name narrows the list as you type.
- [ ] Category chips filter correctly, and **All** restores.
- [ ] A search with no results explains itself instead of showing an empty page.
- [ ] Badges (Ekadashi-friendly, Sattvic override) appear where they should.
- [ ] The list is usable on a phone-width screen.

## Watch out for

- **Step 7 is the interesting one.** The ingredient master supports aliases specifically because a
  temple calls the same thing several names. If searching by alias finds nothing, record it as Major —
  and note whether the search is meant to cover recipes only or ingredients too.
- Searching by *ingredient* ("what can we make with mango?"). Try it: type `Mango` in the search box.
  If it does not find Aam Ras, record what you tried and what happened.
- A list that jumps or re-orders while you type.
- Slow search — anything over about a second on a short list is worth noting.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT016-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
