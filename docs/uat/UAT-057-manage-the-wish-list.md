# UAT-057: Manage the wish list

| | |
|---|---|
| **Feature area** | Donations — wish-list management |
| **Technical stories** | E7-S5 (wish list management) |
| **Roles exercised** | Temple admin |
| **Depends on** | UAT-007 |
| **Environment needs** | None |

## What this feature is for

"Give what you can" raises less than "this is what we need". The wish list is the temple's concrete
asks — twenty sacks of rice, a new steam cauldron — that a devotee can fund knowing exactly what their
money provides.

## How it is supposed to work

- An item has a **title**, a description, a **price**, a **quantity wanted** (ten sacks, not just one),
  and a **category** — consumable, equipment or other.
- Items are **Active**, **Fulfilled** once fully sponsored, or **Archived**.
- Sponsoring the last unit flips it to Fulfilled; it stays visible as a thank-you for a while, then
  retires by itself.
- The order items appear in on the Equipment tab is under the temple's control.
- Only a **Temple Admin** manages the list.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/wishlist** (menu: **Wish list**)
- **Create these three items** — UAT-058 sponsors them:

| Title | Price | Quantity wanted | Category |
|---|---|---|---|
| Sack of rice (25 Kg) | 1300 | 10 | Consumable |
| Tin of ghee (15 L) | 9000 | 4 | Consumable |
| Stainless steel serving trolley | 18000 | **1** | Equipment |

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Wish list** | *Concrete needs devotees can fund; fulfilled items retire automatically.* An empty list |
| 2 | Press **Add an item** | Fields: Title, Price, Quantity wanted, Category, Description |
| 3 | Add all three items from the table | Each appears with its price, quantity and status **Active** |
| 4 | Try an item with a price of `0` or a negative price | Refused |
| 5 | Try an item with quantity wanted `0` | Refused |
| 6 | Sign in as `ikms.donor.1@trading4good.org` in a second browser, open **/donate** and press the **Equipment** tab | All three items appear with their prices and a **Sponsor** action |
| 7 | Back as admin, look for a way to **edit** an item (change a price or a description) | Record what you find — if editing is not possible, say so |
| 8 | Look for a way to control the **order** items appear in on the Equipment tab | Record what you find |
| 9 | Look for a way to add an **image** to an item | Record what you find — the design calls for images, since a photograph is what makes a concrete ask feel real |
| 10 | Press **Archive** on one item | It leaves the active list |
| 11 | Check the **Equipment** tab of **/donate** again | The archived item is gone |
| 12 | *(After UAT-058)* Come back after the trolley is fully sponsored | It shows as **Fulfilled**, and on the Equipment tab as *Fulfilled 🙏* |
| 13 | Sign in as `ikms.kitchen-staff.1@trading4good.org` and type **/wishlist** | *Not your page* |

## It passes if

- [ ] Items can be added with title, price, quantity wanted and category.
- [ ] Zero or negative prices and quantities are refused.
- [ ] Items appear immediately on the **Equipment** tab of **/donate**.
- [ ] Archiving removes an item from the Equipment tab but keeps its history.
- [ ] A fully sponsored item shows as Fulfilled, on the Equipment tab and on the admin's list.
- [ ] Only a Temple Admin can manage the list.

## Watch out for

- **Steps 7, 8 and 9 are checks for missing pieces.** Editing, manual ordering, and images are all part of what this feature was meant to be. If any is missing, record it plainly — a wish list of unedited, unordered, imageless rows is a much weaker fundraising tool than the one that was designed, and that is a finding worth having.
- An archived item a donor can still sponsor.
- The quantity wanted not being reflected in the public progress (2 of 10 sponsored).
- Prices displayed without the rupee symbol or with the wrong currency.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT057-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
