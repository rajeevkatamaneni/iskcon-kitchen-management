# UAT-026: The movement history is a permanent record

| | |
|---|---|
| **Feature area** | Inventory — stock movements ledger |
| **Technical stories** | E3-S2 (stock movements ledger) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-024, and ideally UAT-028, UAT-035, UAT-044 so every movement type exists |
| **Environment needs** | None |

## What this feature is for

Inventory in this system is not a number somebody keeps up to date — it is the sum of everything that
has ever happened to an item. That makes the history the truth and the quantity merely a consequence.
It also means the history can never be edited: a mistake is corrected by recording the correction, the
way an accountant does, not by rubbing anything out.

## How it is supposed to work

- Every change is one row: **what kind** (delivery received, gift in kind, cooked, correction), the
  item and batch, the signed quantity, who did it, when, and what it refers to (a purchase order, a
  meal plan, a donation, a reason).
- Rows are **append-only**. No screen anywhere edits or deletes one.
- A mistaken movement is answered by a compensating movement that references the original.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/inventory** → **Rice**
- Ideally run this *after* UAT-028 (a gift), UAT-035 (cooking) and UAT-044 (a delivery), so the history
  contains all four kinds of movement.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open the **Rice** item and scroll to **Movement history** | A table: When, Type, Change, Reason / note |
| 2 | Identify each row's type | You should be able to tell a *delivery received* from a *gift* from a *cooked* from a *correction* without guessing |
| 3 | Add up every **Change** column value with a calculator | The total equals the **on hand** quantity at the top of the page, exactly |
| 4 | Look for any way to edit a row | There is none — no edit control, no delete, nothing that becomes editable on click or long-press |
| 5 | Check that a *cooked* movement names the meal it came from | The row references the meal plan (needs UAT-035) |
| 6 | Check that a *delivery* movement names its purchase order | The row references the PO (needs UAT-044) |
| 7 | Check that a *gift* movement names the donation | The row references the donation (needs UAT-028) |
| 8 | Sign in as the **temple admin** and open the same item | The same history, still not editable — not even for an administrator |
| 9 | Make a deliberate small mistake (adjust `-3` with reason *Count correction*), then correct it (`+3`, reason *Count correction*, note `Reversing an incorrect entry`) | **Both** rows remain in the history. The first is not removed |
| 10 | Filter the history by type or date if a filter is offered | It narrows correctly |

## It passes if

- [ ] Every movement shows type, quantity, when, and what it relates to.
- [ ] The movements add up exactly to the current quantity.
- [ ] No role, including Temple Admin, can edit or delete a movement.
- [ ] A correction adds a row rather than removing one.
- [ ] Movements can be traced back to the delivery, meal or donation that caused them.

## Watch out for

- **The arithmetic in step 3 is the heart of this test.** If the sum does not match the quantity shown, stop and record everything: the item, the rows, and both figures. That is a Blocker.
- A movement with no actor, or attributed to the wrong person.
- Movements missing entirely for something you know happened (you cooked a meal and no consumption row appeared) — Blocker.
- Rows that reference an internal identifier instead of a readable name ("meal plan 8b1f…"). Minor, but it makes the history unusable in practice, so record it.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT026-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
