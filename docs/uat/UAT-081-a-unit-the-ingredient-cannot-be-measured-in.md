# UAT-081: A unit the ingredient cannot be measured in

| | |
|---|---|
| **Feature area** | Quantities — unit-family validation |
| **Technical stories** | E11-S2 (one unit vocabulary) · **BACKLOG BL-9** (the hole this closes) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-013 (ingredients), UAT-022 (stock), UAT-028 (a gift of goods), UAT-039 (a draft purchase order) |
| **Environment needs** | None for steps 1–17. Step 18 needs the person who set up the environment |

## What this feature is for

Rice is weighed. Ghee is poured. Coconuts are counted. Until now nothing in this application checked
that the unit a quantity arrived in was a unit the ingredient could be measured in at all, so
**"3 litres of rice flour"** was accepted — written to a purchase-order line, delivered against, and
booked into the store room, where it became three thousand grams of something nobody had bought.

The rule is now one sentence: **the unit must be in the same family as the unit the ingredient is
held in.** Weight, volume and count are three families and none of them converts into another.

## How it is supposed to work

- **Same family, not the same unit.** An ingredient held in kilograms can be given a quantity in
  **grams**, and it converts. That is ordinary and must keep working — the store room posts in grams,
  a purchase order in kilos, and both are weight.
- **What is refused is nonsense**: litres against something weighed, kilograms against something
  poured, either against something counted in pieces.
- The refusal is **`KMS-4013`** — *That quantity is in a unit this ingredient can't be measured in.*
  Its next step: *Weight, volume and pieces don't convert into one another. Use the unit the
  ingredient is held in.*
- On a screen that can hold **many lines**, the refusal also **names the line** — the ingredient, the
  unit it is held in, and the unit you gave: *Rice is measured in Kg, and there is no way to turn L
  into Kg.* Being told one of twenty lines is wrong without being told which is not much of a
  refusal.
- **The store-room ledger is the last gate, not the first.** Every screen that writes a quantity
  ought to have refused it already; the ledger refuses it too, so a request that never went near a
  screen is refused just the same.
- **Pieces convert to nothing.** Coconuts are not kilograms. That is not a special case — it is what
  a count is.
- **Nothing is refused on a *read*.** Rows written before this rule existed still render on every
  screen and report, because a screen that throws is worse than a screen showing the bad row somebody
  needs to see in order to fix it.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Know your three ingredients.** On **/ingredients**, look at the unit each is kept in and write it
  down. You need one of each family:

  | Ingredient | Kept in | Family |
  |---|---|---|
  | **Rice** | `Kg` | weight |
  | **Ghee** | `L` | volume |
  | **Coconut** | `pieces` | count — if the temple has none, add one (UAT-013), category *Produce*, unit `pieces` |

- Each of them needs **some stock on the shelf** before you can adjust it (UAT-022 or UAT-028).

## Steps

### Adjusting stock in the wrong kind of unit

| # | Do this | You should see |
|---|---|---|
| 1 | Open **/inventory** → **Rice** and press **Adjust stock** | The *Adjust a batch* form: Batch, Reason, **Change (e.g. -2)**, **Unit**, Note |
| 2 | Look at the **Unit** box | It opens on **Kg** — the unit Rice is kept in — and offers all five: Kg, gm, L, ml, pieces |
| 3 | Set Change to `-2`, Reason **Spoilage**, Unit **L**, and press **Record adjustment** | **Refused.** *That quantity is in a unit this ingredient can't be measured in.* — and under it *Weight, volume and pieces don't convert into one another. Use the unit the ingredient is held in.*, quoting **`KMS-4013`** |
| 4 | Look at the stock figure on the page | **Unchanged.** Nothing was written and then undone — nothing was written |
| 5 | Look at the **movement history** at the foot of the page | **No new row.** A refused adjustment leaves no trace in the ledger |
| 6 | Try again with Unit **pieces** | Refused the same way. Rice is not counted |
| 7 | Now set Change to `-2`, Unit **Kg**, and record it | **Accepted.** Stock falls by 2 Kg and a row appears in the history |
| 8 | Adjust again: Change `-500`, Unit **gm** | **Accepted.** Grams and kilos are the same family — stock falls by a further **0.5 Kg**, and the history row reads in grams or kilos but means half a kilo. Check the on-hand figure yourself |
| 9 | Open **/inventory** → **Ghee** and try Change `-1`, Unit **Kg** | **Refused**, `KMS-4013`. Ghee is poured, not weighed |
| 10 | Try Ghee with Unit **ml** | **Accepted** — same family |
| 11 | Open **/inventory** → **Coconut** and try Change `-1`, Unit **Kg**, then **L** | **Refused both times**, `KMS-4013`. A count converts to nothing |
| 12 | Try Coconut with Unit **pieces** | **Accepted** |

### Recording a gift of goods in the wrong kind of unit

| # | Do this | You should see |
|---|---|---|
| 13 | Go to **/donations/new** and record a gift of goods (UAT-028) with **one** line: **Rice**, quantity `5`, unit **L** | **Refused**, `KMS-4013`. The gift is **not** recorded |
| 14 | Check **/donations** and **/inventory** → **Rice** | No donation row, no stock movement. The whole gift was refused, not the one line |
| 15 | Change that line's unit to **Kg** and record it again | Accepted, and Rice goes up by 5 Kg |
| 16 | Record a two-line gift: **Rice** `5` **Kg** (fine) and **Ghee** `2` **Kg** (nonsense) | **Refused**, `KMS-4013` — and **neither** line is written. An order or a gift is decided as a whole, never half-written |
| 17 | Look at the message on that two-line refusal | It should tell you **which line** — naming **Ghee**, the unit it is held in, and the unit you gave. If it only says *one of these is wrong*, write that down: a two-line gift is forgivable, a twenty-line order is not |

### The order line, and the request nobody typed

| # | Do this | You should see |
|---|---|---|
| 18 | **Ask the person who set up the environment** to do this one, because no screen in the application lets you choose a unit on a purchase-order line — it always uses the ingredient's own. Ask them to send an update to a **draft** purchase order (UAT-039) with one line changed to a unit from another family — Rice in `L` — straight to the system behind the site | **Refused** with **`KMS-4013`**, and the refusal carries a line naming the ingredient: *Rice is measured in Kg, and there is no way to turn L into Kg.* The draft is **unchanged** — no line is written |
| 19 | Open that draft in the application afterwards | Its lines are exactly as they were. A refused edit changes nothing |

### The old rows are still readable

| # | Do this | You should see |
|---|---|---|
| 20 | Open **/inventory** → **Rice** → the movement history, and **/cost-per-serving** (UAT-075) | Every screen loads. If the temple has any movement written before this rule existed in a unit that would now be refused, it is **shown**, not hidden and not an error |

## It passes if

- [ ] A quantity in a unit from another family is refused with **`KMS-4013`** wherever it is entered.
- [ ] A quantity in a *different but compatible* unit — grams against a kilo-held ingredient — is accepted and converts correctly.
- [ ] `pieces` refuses both weight and volume, and is refused by both.
- [ ] A refusal writes **nothing**: no stock movement, no donation, no changed order line.
- [ ] A multi-line gift or order is refused **whole**, and the message names the line at fault.
- [ ] The refusal holds for a request that never went through a screen.
- [ ] Screens and reports still render rows written before the rule existed.

## Watch out for

- **A quantity accepted and silently converted.** This is the defect the rule exists to stop. If
  `3 L` of Rice is accepted and the store room ends up 3 Kg — or 3,000 gm — heavier, that is a
  **Blocker**, and note the exact figure the store room ended on.
- **Half a gift, or half an order.** If step 16 writes the Rice line and refuses the Ghee line, the
  temple now has stock it was never given. Blocker.
- **A refusal that names no line** on a screen with more than one. Major — a twenty-line order is
  unfixable without it.
- **A screen that will not load** because an old row is in a unit that would now be refused. Major:
  the rule is about writing, never about reading.
- The unit box on an adjustment **opening on the wrong unit**. It should open on the unit the
  ingredient is kept in, so the ordinary case takes no thought at all.
- A large adjustment being sent for admin approval (UAT-025) *before* the unit is checked. Either
  order is arguable, but write down which happened — being told a nonsense figure needs approval is
  a confusing way to be told it is nonsense.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT081-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
