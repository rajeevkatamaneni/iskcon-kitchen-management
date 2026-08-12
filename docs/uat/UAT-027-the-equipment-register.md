# UAT-027: The equipment register

| | |
|---|---|
| **Feature area** | Inventory — equipment |
| **Technical stories** | E3-S4 (equipment inventory) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-008 |
| **Environment needs** | None |

## What this feature is for

Vessels, grinders, gas burners and trolleys are not consumed — they wear out, get repaired, get moved,
and eventually get scrapped. The temple needs to know what it owns and what state each thing is in.
That is a different kind of record from a sack of rice, so it is a different register.

## How it is supposed to work

- Equipment is tracked by **condition** (Good, Needs repair, In repair, Scrapped), **location**, and
  where it came from (purchased, donated, or unknown).
- A condition change is an **event**, recorded with a reason, and kept in the item's history — the same
  philosophy as the stock ledger.
- **Scrapped** items drop out of the everyday view but stay in the record and can still be found.
- Equipment received as a gift links back to its donation (UAT-028).

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/equipment** (menu: **Equipment**)

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Equipment** | *No equipment yet*, with a **Register equipment** action |
| 2 | Press **Register equipment** | A form: Name, Category, Location, Condition, Acquired on, Source (Unknown / Purchased / Donated), Notes |
| 3 | Register `Wet Grinder 10L`, category `Machine`, location `Main kitchen`, condition **Good**, acquired today, source **Purchased** | It appears in the list |
| 4 | Register `Steam Cauldron 200L`, `Machine`, `Main kitchen`, **Good**, source **Purchased** | Two items |
| 5 | Register `Serving Trolley`, `Furniture`, `Prasadam hall`, **Needs repair**, source **Donated** | Three items |
| 6 | Open **Wet Grinder 10L** | Its page, with **Change condition** and a **History** section |
| 7 | Change its condition to **Needs repair**, reason `Pressure valve leaking` | The condition updates; a History row records the change, the reason, and when |
| 8 | Change it again to **In repair**, reason `Sent to the workshop on Tuesday` | A second History row; both remain |
| 9 | Change it back to **Good**, reason `Returned from workshop, tested` | Three rows of history — the full story of the machine |
| 10 | Change **Serving Trolley** to **Scrapped**, reason `Frame cracked beyond repair` | It disappears from the default list |
| 11 | Tick **Show scrapped items** | It reappears, marked scrapped |
| 12 | Try to change the condition of the scrapped trolley | Refused: *This item has been scrapped, so its condition can't change* (`KMS-4912`), suggesting registering a replacement |
| 13 | Filter by condition and by location | The list narrows correctly |
| 14 | *(After UAT-028)* Find the equipment you registered there as a gift | It shows source **Donated** and links back to the donation record |

## It passes if

- [ ] Equipment can be registered with category, location, condition and source.
- [ ] Every condition change records a reason and stays in the item's history.
- [ ] Scrapped items leave the default view and can be found again with the toggle.
- [ ] A scrapped item's condition cannot be changed (`KMS-4912`).
- [ ] Filters by condition and location work.
- [ ] Donated equipment links back to its donation.

## Watch out for

- A condition change accepted with **no reason**. The reason is what makes the register worth keeping — record it as Major if it is optional.
- History that shows only the latest change rather than all of them.
- A scrapped item still appearing in pickers elsewhere in the product.
- Whether equipment can be *deleted* outright. It should not be — the register is a history. If a delete exists, note it.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT027-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
