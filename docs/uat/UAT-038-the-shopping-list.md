# UAT-038: The suggested shopping list

| | |
|---|---|
| **Feature area** | Ordering — auto-generated shopping list |
| **Technical stories** | E5-S2 (auto-generated shopping list) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-034 (shortfalls), UAT-023 (thresholds), UAT-037 (vendors) |
| **Environment needs** | **Background worker on** for the nightly refresh; **Regenerate** should work on demand without it |

## What this feature is for

Procurement should start from data, not from memory. The system knows two things nobody can hold in
their head: what the coming meal plan will need beyond current stock, and which items have dipped below
their reorder level. It merges them into one shopping list, with a suggested vendor for each line,
which staff then review and adjust before anything is ordered.

## How it is supposed to work

- Two streams feed the list: **shortfalls from the meal plan** over the coming days (and festivals a
  little further out), and **items below their reorder threshold**, topped back up with a safety margin.
- Each line shows the ingredient, what is on hand, the suggested quantity, **why** it is there, the
  suggested vendor (the preferred one), and the date it is needed by.
- **You can edit it**: change the quantity, change the vendor, or exclude a line.
- Regenerating refreshes the untouched lines but **keeps your edits**.
- Prohibited (non-sattvic) ingredients can never appear on it.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Set the scene first** so the list has something to say:
  1. Plan a festival meal that is **short** (UAT-034, step 10).
  2. Make sure at least one item is **below its reorder threshold** (UAT-023).
- **Start at:** **/shopping-list** (menu: **Shopping list**)

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Shopping list** | Either lines already, or *Nothing to order* with a **Regenerate** action |
| 2 | Press **Regenerate** | The list rebuilds; the ingredients you made short and low appear |
| 3 | Read one line | Include tick, Ingredient, On hand, Suggested quantity, **Why**, Vendor, Needed by |
| 4 | Expand or read the **Why** on a line | It says what drove it — a meal-plan shortfall, or below-threshold, or both |
| 5 | Check the **Vendor** column | Rice and Toor Dal suggest *Sri Balaji Provisions*; Ghee suggests *Nandini Dairy Agency* — the preferred vendors from UAT-037 |
| 6 | Check the **Needed by** date on a shortfall line | It is on or before the date of the meal that needs it |
| 7 | Check the suggested quantities | They are rounded up to sensible purchase amounts, not raw decimals like 4.37 Kg |
| 8 | Change a line's quantity by hand, and change another line's vendor | Both edits are accepted |
| 9 | Untick **Include** on one line | It is excluded |
| 10 | Press **Regenerate** again | **Your edits survive** — the changed quantity, the changed vendor and the exclusion are still there; untouched lines refresh |
| 11 | Look for **Onion** or **Garlic** on the list | Neither appears, ever |
| 12 | Cook the meal that caused a shortfall (UAT-035), then regenerate | That shortfall line is gone or reduced |
| 13 | Receive stock for a low item (UAT-028 or UAT-044), then regenerate | That line is gone or reduced |
| 14 | Type the **old** address **/order-list** into the address bar | You land on **/shopping-list**, showing the same list. The screen was renamed, and nobody's bookmark or older note breaks |

## It passes if

- [ ] The list is generated from meal-plan shortfalls and below-threshold items, and says which drove each line.
- [ ] The suggested vendor is the preferred vendor for that ingredient.
- [ ] Quantities are rounded to sensible purchase units.
- [ ] Lines can be edited and excluded, and those edits survive a regenerate.
- [ ] Prohibited ingredients never appear.
- [ ] Cooking or receiving changes the list on the next regenerate.
- [ ] The old **/order-list** address still lands on the shopping list.

## Watch out for

- **Step 10 is the one that usually breaks.** If regenerating throws away your edits, that is a Major defect — staff will stop trusting the list entirely.
- A shortfall from a **catering** booking (UAT-033) not appearing. Check specifically.
- Suggested quantities that are absurdly large or small. Do the arithmetic on one line by hand: needed by the plan, minus on hand, plus the top-up to threshold.
- A deactivated vendor being suggested.
- An item with **no** preferred vendor: what does the Vendor column show? Record it.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT038-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
