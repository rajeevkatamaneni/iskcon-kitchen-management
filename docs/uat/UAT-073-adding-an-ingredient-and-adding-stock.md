# UAT-073: Adding an ingredient, and adding stock

| | |
|---|---|
| **Feature area** | Across the product — one way to add a thing |
| **Technical stories** | E10-S12 (Ingredients and Inventory adopt the focus-screen add) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-013 (Ingredients), UAT-022 (Inventory) |
| **Environment needs** | None |

## What this feature is for

A person who has learned how to add a recipe should already know how to add an ingredient. Until now
they did not: Recipes put the form on its own screen behind a button, while Ingredients and Inventory
kept a form permanently open above the list. Three pages, two patterns, and a rule in the design
document that both of them broke — **a form of four fields or more becomes a screen; three or fewer
stays inline.** Ingredients has four to five fields. Inventory has five.

This test checks that all three pages now behave the same way, and that the transition between them is
the one a person can learn once.

## How it is supposed to work

- The list page carries **one button, top right** — *Add an ingredient*, *Add to inventory* — and **no
  form on the page**.
- Pressing it opens **a screen of its own**: the task is the heading, the menu stays on the left, and
  **Cancel** and the primary action sit together in a header at the top right that stays put as you
  scroll. No second button at the foot of the form. No *← Back* link.
- Committing takes you **back to the list**, with a green line there confirming what you added.
- That green line appears **once**. It clears itself, and refreshing or coming back does not replay it.
- The empty states no longer point "above" at a form that is not there any more.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/recipes**, so you have the pattern fresh in your mind before you check the other two.

## Steps

### The pattern to compare against

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Recipes** and press **New recipe** | A screen of its own; task as the heading; menu still on the left; **Cancel** and the primary action together at the top right; nothing at the foot of the form |
| 2 | Press **Cancel** | Back where you came from, nothing saved |

### Ingredients

| # | Do this | You should see |
|---|---|---|
| 3 | Open **/ingredients** | The list, with **Add an ingredient** at the **top right**. **No form on the page** — not above the list, not below it, not collapsed behind a chevron |
| 4 | Press **Add an ingredient** | The same shape as step 1: task as the heading, menu on the left, **Cancel** and **Add ingredient** together at the top right. Fields: Name, Category, Unit, Aliases, and (for an admin) the *Sattvic-prohibited* tick |
| 5 | Scroll the form down, if it is long enough to scroll | The action header **stays put**. You never have to scroll back up to find the save |
| 6 | Look at the foot of the form | No second **Save** or **Add** button. No *← Back* link |
| 7 | Press **Add ingredient** with the name empty | Refused **on this screen**, with the missing field marked. You are not thrown back to the list |
| 8 | Fill in `Dry Ginger`, category `Spices`, unit `gm`, and save | You land back on **/ingredients**, with a green line confirming **Dry Ginger** was added, and it is in the list |
| 9 | **Refresh the page** | The green line does **not** come back |
| 10 | Wait a few seconds without touching anything | The green line clears itself |
| 11 | Add a second ingredient, then press the browser's **Back** button | You go back through the form, not into a replayed confirmation. The green line does not fire again |
| 12 | Press **Add an ingredient**, fill in something, then press **Cancel** | Back on the list, and **nothing was added** |
| 13 | Delete every ingredient so the list is empty — or look at a temple that has none | The empty state reads as a sentence about what the list is for, with an **Add an ingredient** button in it. **It does not say "above"**, or point at a form that is not there |

### Inventory

| # | Do this | You should see |
|---|---|---|
| 14 | Open **/inventory** | The list, with **Add to inventory** at the **top right**, and **no form on the page** |
| 15 | Press **Add to inventory** | A screen of its own, the same shape again. Fields: Ingredient, Storage location, what is on the shelf today, the level to warn at, Notes |
| 16 | Look at the foot, and scroll | No second button at the foot; the action header stays put |
| 17 | Save with no ingredient chosen | Refused on the screen, field marked |
| 18 | Track **Dry Ginger**, location `Main store`, warn below `100` | Back on **/inventory** with a green line confirming it, and the row in the list |
| 19 | Refresh, and wait | The line does not replay, and it clears itself |
| 20 | Press **Add to inventory**, then **Cancel** | Back on the list, nothing tracked |
| 21 | Look at the inventory empty state (a temple with nothing tracked) | A sentence about what to start with, and an **Add to inventory** button. **No "above"** |

### The three pages agree

| # | Do this | You should see |
|---|---|---|
| 22 | Put **/recipes**, **/ingredients** and **/inventory** side by side | The button is in the same place, says the same kind of thing, and opens the same kind of screen on all three |
| 23 | Note where each one lands you after saving | Ingredients and Inventory return to the **list**. Recipes lands on the **new recipe** — that is a deliberate difference, not a defect; record it if it surprises you |
| 24 | Do the whole of steps 3–8 and 14–18 again on a **phone** (see UAT-061) | The button is reachable, the form is usable, and the action header does not cover the field you are typing into |
| 25 | Sign in as `ikms.temple-admin.1@trading4good.org` and repeat step 4 | The admin sees the extra *Sattvic-prohibited* field; everything else is the same screen |

## It passes if

- [ ] Neither Ingredients nor Inventory has a form sitting on the list page.
- [ ] Both open a screen of their own from a top-right button, with the task as the heading and the menu still on the left.
- [ ] **Cancel** and the primary action are together at the top right, the header stays put when scrolling, and there is no second button at the foot and no *← Back*.
- [ ] Validation failures keep you on the form with the field marked.
- [ ] Committing returns to the list with a green confirmation naming what you added.
- [ ] The confirmation appears **once**: it clears itself, a refresh does not replay it, and neither does going back.
- [ ] Cancel adds nothing.
- [ ] Neither empty state refers to a form "above".
- [ ] Recipes, Ingredients and Inventory now look and behave like one another.

## Watch out for

- **The green line replaying.** Refresh the list twice, and press Back once. A confirmation that comes back every time you look at the page is a small bug with a nasty history — an unguarded version of it can spin the page until the browser runs out of memory. If the page becomes slow or unresponsive after adding something, stop and record it as a Blocker.
- A **second save button at the foot** of either form, or a *← Back* link. Both are explicitly out.
- The action header **scrolling away** on a long form, or covering the last field on a phone.
- **Editing** is still done inline on the Ingredients list, in the row itself. That is not part of this change — but say whether it feels inconsistent now that adding has moved to its own screen. A user's view of that is worth more than ours.
- An old bookmark or link that still points at a form on the list page.
- The confirmation naming the wrong thing, or nothing — *"Added"* rather than *"Dry Ginger added"*.
- Whether the ingredient you just added is immediately available in the Inventory picker, and in a recipe. If you have to refresh to see it, note it.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT073-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
