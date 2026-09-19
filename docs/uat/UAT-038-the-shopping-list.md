# UAT-038: The shopping list — amounts, packs and vendors

| | |
|---|---|
| **Feature area** | Ordering — the suggested shopping list |
| **Technical stories** | E5-S2 (auto-generated shopping list); T-132 (worked out on every read), T-134 (a tile per vendor); procurement release 2026-09-19: R-SL-1 (readable amounts), R-SL-2 (buying amount), R-SL-3 (the vendor's pack), R-SL-4 ("No vendor yet" lines). Tasks T-259, T-264 |
| **Roles exercised** | Kitchen manager, kitchen staff, volunteer (to prove the refusal) |
| **Depends on** | UAT-088, UAT-089 and UAT-090 (the UAT ingredients, vendors and stock), UAT-034 (a meal-plan shortfall) |
| **Environment needs** | None. The list is worked out each time the page is opened; there is no button and no nightly job |

> **Rewritten 2026-09-19** for the procurement release. The earlier script pressed **Regenerate** and
> read one table with a Vendor column. Both went in T-132 and T-134: there is no Regenerate button, and
> the list is grouped into one tile per vendor. The old steps are not kept, because none of them can be
> run on the screen as it is.

## What this feature is for

The list works out what to buy from two things nobody can hold in their head: what the coming meal plan
needs beyond the stock on the shelf, and which items have dropped below the level they should be kept
at. It then says it the way a person buys it — **3 Kg**, not 2792 gm; **4 bags**, not 100 Kg — grouped by
the vendor who will be asked for it.

## How it is supposed to work

- The page is headed **Shopping list** and reads: *Worked out from the meal plan and the store room each
  time you open this page. Each vendor is ordered from separately — check the lines in a tile, then create
  that vendor’s purchase order.*
- One tile per preferred vendor, with **Generate purchase order**, and one tile **No vendor yet** for
  lines with no preferred vendor.
- Each line: **Include**, Ingredient, **Why** (chips such as *shortfall*, *Top-up*, *PO short*), **On
  hand**, **Suggested**, **Order by**. Below the reorder level, the line tops stock up to the reorder level
  plus a fifth (for example 20 Kg on hand, reorder at 100 Kg: 120 − 20 = **100 Kg**).
- **Readable amounts:** nothing of 1,000 gm or ml or more is ever written in gm or ml. The box shows Kg
  or L, and what you type is read in the unit printed beside it.
- **Rounded up, never down:** in the vendor's pack when the vendor sells it in one (100 Kg sold as
  **Bag = 25 Kg** → **4 × Bag (25 Kg)**); else in the ingredient's pack sizes, fewest packs with least
  left over (416 gm with packs of 250 gm, 500 gm and 1 Kg → **1 × 500 gm**); else by steps: under 1 Kg to
  the next 50 gm, 1–10 Kg to the next ½ Kg, 10–100 Kg to the next 1 Kg, over 100 Kg to the next 5 Kg,
  pieces to the next whole number (2792 gm → **3 Kg**).
- A quantity you type yourself is marked **edited** and is **never re-rounded**.
- **"No vendor yet" lines** have a **Choose vendor…** list and a **Use this vendor next time** tick (on by
  default). Choosing moves the line into that vendor's tile; with the tick on it stays there next time,
  because the vendor is linked to the ingredient. Several lines can be moved at once with **Order these
  from**.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.5@trading4good.org` (kitchen manager)
- **Stock the UAT ingredients** on **/inventory** → **Add to inventory** (UAT-090 explains the value box):

| Ingredient | On the shelf now | Tell me when stock drops below | What it would cost to buy today |
|---|---|---|---|
| UAT Bulk Rice | 20 Kg (done in UAT-090) | 100 Kg | — |
| UAT Tea | 64 gm | 400 gm | `0.5` (₹ per gm) |
| UAT Jeera | 808 gm | 3 Kg | `0.4` (₹ per gm) |

  Worked out: UAT Bulk Rice 120 − 20 = **100 Kg**; UAT Tea 480 − 64 = **416 gm**; UAT Jeera 3,600 − 808 =
  **2,792 gm**.
- **Start at:** **/shopping-list** (menu: **Ordering** → **Shopping list**)

## Steps

### Reading the list

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Shopping list** | The heading and the sentence above, word for word. **No** Regenerate, Generate shopping list or Generate purchase orders button at the top |
| 2 | Find the **UAT Rice Traders** tile | Its heading is the vendor's name, a line *N of M lines will go on this order.*, and a **Generate purchase order** button |
| 3 | Read the **UAT Bulk Rice** line | Why: **Top-up 100 Kg**. On hand: **20 Kg**. Suggested: a box holding **4**, **× Bag (25 Kg)** beside it and **= 100 Kg** under it |
| 4 | Find the **No vendor yet** tile | Its line reads *These have no preferred vendor yet. Choose a vendor for a line, or one for every ticked line.* It has an **Order these from** list with a **Use this vendor next time** tick beside it, and no Generate button. Each line has a **Vendor** column |
| 5 | Read the **UAT Tea** line | Suggested **500 gm**, with **1 × 500 gm** under it — one 500 gm pack covers 416 gm with the least left over |
| 6 | Read the **UAT Jeera** line | Suggested **3** with **Kg** beside it. The figure **2792** appears nowhere on the line |
| 7 | Search the whole page for any figure of **1,000 or more followed by gm or ml** | None (an ingredient's own *name* may contain such a figure; that is not a quantity) |

### Your edits stay yours

| # | Do this | You should see |
|---|---|---|
| 8 | In the UAT Jeera box type `2.75` (beside **Kg**) and move away | The line is marked **edited** |
| 9 | Reload the page | Still **2.75** Kg and **edited**. Not rounded to 3 |
| 10 | Untick **Include** on a line in the UAT Rice Traders tile, then tick it again | The line greys out while unticked, and the tile's *N of M lines* count follows |

### Lines with no vendor

| # | Do this | You should see |
|---|---|---|
| 11 | On the **UAT Tea** line, open **Choose vendor…** | The active vendors, including UAT Rice Traders. The **Use this vendor next time** tick beside it is **on** |
| 12 | Choose **UAT Rice Traders** | UAT Tea leaves **No vendor yet** and appears in the **UAT Rice Traders** tile, which still has its **Generate purchase order** button |
| 13 | Reload the page | UAT Tea is **still** in the UAT Rice Traders tile. UAT Rice Traders is now linked to UAT Tea (check the ingredient page, UAT-088) |
| 14 | In **No vendor yet**, untick **Include** on every line except UAT Jeera (the bulk choice acts on every ticked line, and with the tick on it links each of them to the vendor). Untick the **Use this vendor next time** tick beside **Order these from**, then choose **UAT Grain House** in Order these from | Every ticked line, UAT Jeera among them, moves into a **UAT Grain House** tile |
| 15 | Reload the page | UAT Jeera is back under **No vendor yet**. With the tick off nothing was remembered |
| 16 | Repeat step 14 with the tick **on**, then reload | UAT Jeera stays in the **UAT Grain House** tile |

### The rest of the list

| # | Do this | You should see |
|---|---|---|
| 17 | Plan a meal that runs short (UAT-034), then reload | A line with a **shortfall** chip, an **Order by** date on or before the day of the meal |
| 18 | In **Add something to the list** at the foot, choose an ingredient and a quantity, and add it | The line appears under its vendor's tile (or No vendor yet). A hand-added amount is not re-rounded |
| 19 | Type the old address **/order-list** | You land on **/shopping-list** |
| 20 | Measure the page at **1280px**, **1024px** and **390px** | Nothing cut off and nothing scrolls sideways, including the **No vendor yet** table at 1024. At 390 each line is a card |
| 21 | Sign in as `ikms.kitchen-staff.1@trading4good.org` (kitchen staff) and read the UAT Bulk Rice and UAT Tea lines | The same list and the same amounts as the kitchen manager sees |
| 22 | Sign in as `ikms.volunteer.1@trading4good.org` and type **/shopping-list** | **Not your page** |

Carry on in **UAT-039**, which turns the UAT Rice Traders tile into a purchase order.

## It passes if

- [ ] The list says what drove each line and is worked out on opening the page, with no button.
- [ ] No quantity of 1,000 gm/ml or more is written in gm/ml.
- [ ] A vendor pack is suggested in that pack (**4 × Bag (25 Kg) = 100 Kg**).
- [ ] The ingredient's own packs are used when the vendor has none (**1 × 500 gm** for 416 gm).
- [ ] Without packs, the step table rounds **up** (2,792 gm → **3 Kg**).
- [ ] A hand-typed quantity is marked edited and never re-rounded.
- [ ] A "No vendor yet" line can be given a vendor one at a time or in bulk; with **Use this vendor next time** on it stays there after a reload, with it off it does not.
- [ ] The old **/order-list** address still works.
- [ ] Nothing is cut off or scrolls sideways at 1280, 1024 or 390.

## Watch out for

- **Anything rounded down.** 2,792 gm must never become 2.5 Kg, and 101 Kg must become 105 Kg, not 100.
  Do the arithmetic on every line you read. Major.
- **Packs that do not cover the need.** 4 bags of 25 Kg is 100 Kg; if a pack suggestion adds up to less
  than the need, that is Major.
- **An amount typed in the wrong unit.** Typing `2.75` beside **Kg** on a line kept in gm must save
  2,750 gm, not 2.75 gm. Open the order it makes (UAT-039) and check.
- A deactivated vendor offered in **Choose vendor…** or **Order these from** (UAT-037). It should not be.
- The **Why** and **On hand** figures are rounded for reading, so they can differ slightly from the
  suggestion (1,600 ml reads *Top-up 1.5 L* beside a suggestion of 2 L). The suggestion is the one that
  must be right.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT038-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
