# UAT-018: Keeping the kitchen sattvic

| | |
|---|---|
| **Feature area** | Recipes — sattvic enforcement |
| **Technical stories** | E2-S4 (sattvic enforcement on recipes) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-015 |
| **Environment needs** | None |

## What this feature is for

Cooking onion or garlic in a temple kitchen is not a cosmetic mistake — it is a religious failure. So
the system refuses outright to save a recipe containing a prohibited ingredient. The only way past it
is a Temple Admin taking personal responsibility, in writing, on the record. This is the most
important rule in the product.

## How it is supposed to work

- A recipe containing a prohibited ingredient is **rejected** — not warned about — with a message
  naming the ingredient.
- Kitchen staff cannot get past it by any route.
- A **Temple Admin** may save it by giving a **reason**. The recipe is then permanently badged as an
  override, and the act is written to the audit trail with the recipe, the ingredient and the reason.
- Removing the prohibited ingredient clears the badge on the next save.

## Before you start

- **You will use two accounts:** `ikms.kitchen-staff.1@trading4good.org` and
  `ikms.temple-admin.1@trading4good.org`.
- **Start at:** **/recipes**

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Sign in as **kitchen staff**, press **New recipe** | The recipe form |
| 2 | Fill in name `Test Onion Sabji`, category `Sabji`, base yield `100 servings` | Accepted so far |
| 3 | Add an ingredient line choosing **Onion** | The picker shows it, marked *(prohibited)* |
| 4 | Add quantity `5 Kg` and press **Create recipe** | **Refused.** A message says the recipe contains an ingredient the temple treats as prohibited, and to remove it or ask a Temple Admin to save it with a reason (`KMS-4906`). The recipe is **not** created |
| 5 | Type a reason into the **Sattvic override reason** field and try again, still as kitchen staff | **Still refused.** Kitchen staff cannot override, whatever they type |
| 6 | Check **/recipes** | `Test Onion Sabji` does not exist |
| 7 | Sign out. Sign in as the **temple admin**, press **New recipe**, and build the same recipe with Onion | Same refusal when the reason is blank |
| 8 | Now fill **Sattvic override reason** with `Prasadam for an outside catering client who requested it; approved by temple president` and save | The recipe is created |
| 9 | Look at the recipe page | A visible badge: *Sattvic override: <your reason>* |
| 10 | Go to **/recipes** | The recipe carries a **Sattvic override** badge in the list too |
| 11 | Go to **/audit** | An entry recording the override: who, when, the recipe, and the reason |
| 12 | Edit the recipe, remove the Onion line, clear the override reason, and save | The badge disappears |
| 13 | Edit **Khichdi** and add **Garlic** | Refused with `KMS-4906`, exactly as in step 4 — the rule applies to edits, not just new recipes |
| 14 | Delete the test recipe when done | Removed |

## It passes if

- [ ] A prohibited ingredient blocks the save for kitchen staff, on both create and edit.
- [ ] The refusal names the ingredient and shows `KMS-4906`.
- [ ] Kitchen staff cannot override even by filling in the reason field.
- [ ] A Temple Admin can override **only** with a reason.
- [ ] An overridden recipe is badged, in both the list and the recipe page.
- [ ] The override is in the audit log with the reason.
- [ ] Removing the ingredient clears the badge.

## Watch out for

- **Any** route that lets a save through without a reason is a Blocker. Try: saving with the reason field containing only spaces; saving, then editing to add the onion afterwards; adding the onion as a second line rather than the first.
- If the message does not name **which** ingredient was the problem, record it as Major — a cook with a twenty-line recipe needs to know which line to fix.
- If the override badge does not survive a page reload, record it.
- The reason should be stored verbatim. If it is truncated or altered, note it.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT018-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
