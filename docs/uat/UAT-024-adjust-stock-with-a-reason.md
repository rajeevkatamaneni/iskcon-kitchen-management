# UAT-024: Adjust stock with a reason

| | |
|---|---|
| **Feature area** | Inventory — manual adjustment |
| **Technical stories** | E3-S7 (manual stock adjustment) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-022 and some stock (UAT-028 or UAT-044) |
| **Environment needs** | None |

## What this feature is for

The real world is messy: a sack splits, a count was wrong, something spoiled. The system has to be
able to record that — but never quietly. Every correction carries a reason, and the correction itself
becomes part of the item's permanent history rather than overwriting it.

## How it is supposed to work

- An adjustment is made **against a batch**, in a direction, with a **reason category** —
  Spoilage, Damage, Count correction, Waste, or Other (which requires a note).
- The reason categories are fixed deliberately, so that a waste report can be built later from data
  the temple has been collecting all along.
- An adjustment can never drive stock below zero.
- Large adjustments need a Temple Admin — that is UAT-025.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/inventory** → open **Rice**
- **You need stock on the shelf.** If Rice is at zero, record a gift first (UAT-028): 50 Kg of Rice.
- Note the current quantity before you start. You will be checking arithmetic.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open the **Rice** item | Current quantity, its batches, and an **Adjust stock** action |
| 2 | Press **Adjust stock** | A form: the batch to adjust, a reason, a change (e.g. `-2`), a unit, and a note field marked required for *Other* |
| 3 | Read the reason choices | Spoilage, Damage, Count correction, Waste, Other |
| 4 | Record a small loss: change `-2`, reason **Spoilage** | Stock falls by 2. The item's **Movement history** gains a row showing the date, type, change and reason |
| 5 | Record a correction upward: change `+1`, reason **Count correction** | Stock rises by 1; another history row |
| 6 | Choose reason **Other** and leave the note blank | Refused — a note is required to explain "Other" |
| 7 | Choose **Other** with the note `Spilled during transfer to the deity kitchen` and change `-1` | Accepted; the note appears in the history |
| 8 | Try to remove more than exists — if 48 Kg is on hand, enter `-100` | Refused: *That would take the stock below zero* (`KMS-4910`), with advice to check against the real count |
| 9 | Add up every movement in the history by hand | The total equals the quantity shown at the top of the page, exactly |
| 10 | Adjust a **specific batch** where the item has more than one | Only that batch's quantity changes; the others are untouched |

## It passes if

- [ ] An adjustment can be made up or down against a chosen batch.
- [ ] A reason category is always required, and *Other* additionally requires a note.
- [ ] Stock cannot go negative (`KMS-4910`).
- [ ] Every adjustment appears in the item's movement history with its reason.
- [ ] The movements add up exactly to the quantity shown.

## Watch out for

- An adjustment that changes the total but does not appear in the history — that is a Blocker: the record and the reality have diverged.
- The reason not being carried into the history (a row that just says "adjustment").
- Whether you can *edit or delete* a past adjustment. You should not be able to; corrections are made by recording another movement. If you can, that is a Blocker (see UAT-026).
- A batch adjusted to exactly zero — does the batch disappear, or stay at zero? Record which; it affects whether the history stays readable.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT024-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
