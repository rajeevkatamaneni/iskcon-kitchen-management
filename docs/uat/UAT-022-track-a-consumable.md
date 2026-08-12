# UAT-022: Track a consumable and read the stock view

| | |
|---|---|
| **Feature area** | Inventory — consumables |
| **Technical stories** | E3-S1 (consumable inventory items and stock view) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-013 |
| **Environment needs** | None |

## What this feature is for

The store manager needs to know what is on the shelf without walking to the storeroom — and which
batch will spoil first. This is the stock view: every consumable the temple tracks, how much there is,
and what is about to become a problem.

## How it is supposed to work

- You choose which ingredients to **track**. An inventory item is one ingredient plus its storage
  location, its reorder threshold, and (later) its preferred vendor.
- **Stock is never typed in.** It is the sum of everything that happened to that item — deliveries
  received, gifts donated, meals cooked, corrections made. There is deliberately no "set the stock to
  40" box anywhere in the product.
- Stock is grouped into **batches**, each with its own expiry and received date, and the batch expiring
  soonest is shown first.
- Badges mark items **below their reorder level** and batches **expiring soon**.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/inventory** (menu: **Inventory**)
- Note: a freshly tracked item shows **zero** stock. Stock arrives through UAT-028 (a gift) or UAT-044
  (a delivery). That is correct behaviour and is exactly what this test is checking.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Inventory** | *Nothing tracked yet*, with a **Track an item** action |
| 2 | Press **Track an item** | A form: Ingredient (chosen from your catalogue), Storage location, Reorder threshold, Notes |
| 3 | Track **Rice**, location `Main store`, reorder threshold `20` | It appears in the list: Item, Location, **On hand 0**, Reorder at 20, Status |
| 4 | Track **Toor Dal** (`Main store`, threshold `10`), **Ghee** (`Cold room`, threshold `5`), **Mango Pulp** (`Cold room`, threshold `10`), **Sugar** (`Main store`, threshold `10`) | Five items listed |
| 5 | Try to track **Rice** a second time | Refused: *You're already tracking that ingredient in inventory* (`KMS-4909`) |
| 6 | Look at the location filter | You can filter to `Main store` or `Cold room`; **All** shows everything |
| 7 | Look for any field that lets you type a stock quantity directly | **There is none.** If you find one, that is a Blocker — record exactly where |
| 8 | Open one item (click **Rice**) | Its page: current quantity, batches (none yet — *No stock on the shelf. It appears here once goods are received or donated*), and an empty movement history |
| 9 | *(After UAT-028 or UAT-044)* Come back to this item | Batches listed with quantity, expiry and received date, soonest expiry first |
| 10 | *(After stock exists)* Check an item whose quantity is below its reorder threshold | It is badged **Low** in the list and **Below reorder level** on its page |
| 11 | *(After stock exists)* Check an item with a batch expiring within a week | Badged **Expiring soon** |

## It passes if

- [ ] Ingredients can be put under tracking with a location and a reorder threshold.
- [ ] A duplicate is refused with `KMS-4909`.
- [ ] A newly tracked item shows zero stock, not an error.
- [ ] There is no way anywhere to type a stock figure directly.
- [ ] Once stock exists, batches show quantity, expiry and received date, soonest expiry first.
- [ ] Low-stock and expiring-soon badges appear according to the thresholds you set.

## Watch out for

- The ingredient picker offering ingredients from another temple — see UAT-006; that would be a Blocker.
- Stock that does not equal the sum of the movements listed on the item's page. Add them up by hand once; they must agree exactly.
- An item shown as **Low** when it is above its threshold, or not shown as Low when it is below. Note the exact numbers.
- Storage locations being free text: two spellings of "Cold room" make two locations. Record it if it bites you.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT022-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
