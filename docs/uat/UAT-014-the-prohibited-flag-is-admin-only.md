# UAT-014: The prohibited flag is admin-only

| | |
|---|---|
| **Feature area** | Recipes — sattvic policy |
| **Technical stories** | E2-S1 (ingredient master), E2-S4 (sattvic enforcement) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-013 |
| **Environment needs** | None |

## What this feature is for

Which ingredients a temple treats as prohibited — onion, garlic, mushroom, egg — is a religious
decision, not a kitchen convenience. So it sits with temple leadership: kitchen staff may add and
edit ingredients all day, but only a Temple Admin can decide that something is, or is no longer,
prohibited. Every such change is recorded.

## How it is supposed to work

- The prohibited flag can only be changed by a **Temple Admin**.
- A change is written to the audit trail (who, when, what it was before).
- The flag is what drives the hard block on recipes (UAT-018) and keeps prohibited items off purchase
  order lists.

## Before you start

- **You will use two accounts:** `ikms.kitchen-staff.1@trading4good.org` and
  `ikms.temple-admin.1@trading4good.org`.
- **Start at:** **/ingredients**

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Sign in as **kitchen staff** and open **Ingredients** | The list, with Onion, Garlic, Mushroom and Egg marked **Prohibited** |
| 2 | As kitchen staff, try to change **Garlic** from Prohibited to Allowed | Refused. Either the control is not offered at all, or pressing it gives *You don't have permission to do that* (`KMS-4301`) |
| 3 | As kitchen staff, add a new ingredient `Asafoetida`, category `Spices`, unit `gm`, and try to tick **Sattvic-prohibited** while adding it | Either the tick is not available to you, or the ingredient is created **not** prohibited. Record which of the two happens |
| 4 | Sign out. Sign in as the **temple admin** and open **Ingredients** | The same list, with the flag now changeable |
| 5 | As admin, change **Asafoetida** to **Prohibited** | The row now reads Prohibited |
| 6 | As admin, change it back to **Allowed** | The row reads Allowed again |
| 7 | Go to **/audit** | Both changes appear, naming you, the ingredient, and the value before and after |
| 8 | Sign in as kitchen staff again and re-check **Garlic** | Still Prohibited — nothing a staff member did changed it |

## It passes if

- [ ] Kitchen staff cannot set or clear the prohibited flag by any route on this screen.
- [ ] A Temple Admin can, in both directions.
- [ ] Each change is written to the audit log with the before and after value.
- [ ] The seeded prohibited items are prohibited by default at a brand-new temple.

## Watch out for

- **The dangerous case:** the screen offering kitchen staff the tick, the tick appearing to work, and the change silently not saving. If the row goes back to its old value on reload, that is confusing rather than safe — record it as Major with exactly what you saw.
- A change made by an admin that does **not** show up in the audit log — that is the finding, not the change itself.
- If step 3 lets kitchen staff create a *new* ingredient already flagged prohibited, that is a real gap in the rule (they could not flip an existing one, but could introduce a flagged one). Record it as Major with the note "created prohibited at creation time".

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT014-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
