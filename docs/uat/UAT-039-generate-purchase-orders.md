# UAT-039: Create a purchase order

| | |
|---|---|
| **Feature area** | Ordering — creating purchase orders |
| **Technical stories** | E5-S3 (purchase order generation and lifecycle), E5-S2 (shopping list); T-134 (a tile per vendor); procurement release 2026-09-19: R-PO-1 (the button), R-PO-2 (the form's header), R-PO-3 (adding items, design A), R-SL-1 and R-SL-3 (readable amounts and packs on the order). Tasks T-260, T-263, T-288, T-295 |
| **Roles exercised** | Kitchen manager, kitchen staff, temple admin, volunteer (to prove the refusal) |
| **Depends on** | UAT-038 (the shopping list with UAT Bulk Rice and UAT Tea in the UAT Rice Traders tile) |
| **Environment needs** | None |

> **Rewritten 2026-09-19.** The earlier script was written for one **Generate purchase orders** button
> that raised an order for every vendor at once. That button went in T-134, and on 2026-09-12 Rajeev had
> the server endpoint behind it removed. Its steps were struck through and marked *needs a rewrite*;
> this is the rewrite, for the tile on the shopping list and for the new create form. The old
> **Raise an order** button, and the page with only a vendor dropdown behind it, are gone.

## What this feature is for

A shopping list is not an order. A purchase order is what the temple sends: one vendor, one number,
what is wanted and by when. There are two ways to make one: from a vendor's tile on the shopping list,
or by hand when somebody knows they need something the list cannot see.

## How it is supposed to work

- **From the shopping list:** a tile's **Generate purchase order** opens a panel, **Purchase order for
  <vendor>**, holding that tile's lines. Nothing is ordered until **Save**. The lines then leave the list,
  and come back if the order is cancelled.
- **By hand:** **/orders** has **Create a purchase order**, which opens the form straight away.
- **The form:** **Vendor** and **Needed by** side by side on one row; no *Deliver to* box; **Note for the
  vendor**, a box that grows as you type; a section called **Items** with the hint *Start typing an
  ingredient, or type anything to add a one-off item.* and no "Nothing on this order yet." line.
- **Items** is a table: Item · Quantity · Unit · Expected price (₹) · Line total · Remove, with always one
  empty row at the bottom. Filling it adds another.
- **The Item box types ahead.** The chosen vendor's items come first, under **Sold by <vendor>**, each with
  its list price; then **Other ingredients**. Picking one fills the unit and the expected price. Text that
  matches nothing offers **Add ‘<text>’ as a one-off item**, a free-text line with its own unit and price.
- Line totals and the **Order total** update as you type.
- A line sold in packs is ordered in packs: **4 × Bag (25 Kg)**, and still recorded as 100 Kg for stock.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.5@trading4good.org` (kitchen manager)
- **Start at:** **/shopping-list**, where the **UAT Rice Traders** tile holds UAT Bulk Rice (4 × Bag
  (25 Kg)) and UAT Tea (UAT-038).

## Steps

### From the shopping list

| # | Do this | You should see |
|---|---|---|
| 1 | On the **UAT Rice Traders** tile press **Generate purchase order** | A panel headed **Purchase order for UAT Rice Traders** and the line *Nothing is ordered until you save. These lines leave the shopping list when the order is created, and come back if it is cancelled.* |
| 2 | Read the UAT Bulk Rice line | **4** in the box, **× Bag (25 Kg)** beside it, **= 100 Kg**, and **List price ₹1,450 / bag · ₹58 / Kg** under the item |
| 3 | Read the UAT Tea line | **1 × 500 gm** (it has no list price yet) |
| 4 | Set **Needed by** to **three days from today**. Press **Escape** | The panel closes and nothing was created. The lines are still on the list |
| 5 | Open the panel again, set Needed by to three days from today, and press **Save** | Back on the list, a green line **PO-2026-<NNNN> was created for UAT Rice Traders.** Write the number down: it is **order A**, used by UAT-040 to UAT-046 |
| 6 | Look for UAT Bulk Rice and UAT Tea on the list | Gone. They are on order A |
| 7 | Open **/orders** and then order A | Status **Draft**. The Items table reads UAT Bulk Rice **4 × Bag (25 Kg)** with **100 Kg** under it, and UAT Tea **1 × 500 gm** |

### By hand

| # | Do this | You should see |
|---|---|---|
| 8 | On **/orders**, look at the top right | **Create a purchase order**. There is no **Raise an order** |
| 9 | Press it | The form opens straight away, titled **Create a purchase order**. No page with only a vendor dropdown in between |
| 10 | Read the top of the form at **1280px** | **Vendor** (*Choose a vendor…*) and **Needed by** on **one row**, level with each other. **Note for the vendor** below. No **Deliver to** box anywhere |
| 11 | Type four lines into **Note for the vendor** | The box grows with the text; no scroll bar inside it |
| 12 | Read the **Items** section before adding anything | The heading **Items**, the hint **Start typing an ingredient, or type anything to add a one-off item.**, the table headings Item · Quantity · Unit · Expected price (₹) · Line total · Remove, and one empty row. No *Nothing on this order yet.* |
| 13 | Choose vendor **UAT Rice Traders**. In the Item box type `UAT` | A list under **Sold by UAT Rice Traders**: their items (UAT Bulk Rice, UAT Tea, UAT Groundnut Oil, UAT Coconut, UAT Curry Leaves), each with its list price where it has one. Then a divider **Other ingredients** with the rest, such as UAT Jeera |
| 14 | Using only the keyboard: press **↓** twice, **↑** once, then **Enter** | The highlighted item is picked, and the cursor jumps to that line's **Quantity** box |
| 15 | Pick **UAT Bulk Rice** (by keyboard or mouse) | Unit **× Bag (25 Kg)**, Expected price **1450**, and **₹1,450 / bag · ₹58 / Kg** under the price. A new empty row appears below |
| 16 | Quantity `2` | Line total **₹2,900**, and the **Order total** says ₹2,900 at once |
| 17 | With the mouse, pick **UAT Groundnut Oil**, quantity `1` | Unit **× Tin (15 L)**, price **2400**. Order total **₹5,300** |
| 18 | Pick **UAT Tea** and open its **Unit** list | The ingredient's packs: **× 250 gm**, **× 500 gm**, **× 1 Kg**. Choose × 1 Kg, quantity `1`, price `500` |
| 19 | In the empty row type `Plastic stool` | The last option reads **Add ‘Plastic stool’ as a one-off item** |
| 20 | Choose it | A line marked **One-off item**, with a small unit list (pieces) and an empty price. Quantity `2`, price `350`. Order total **₹6,500** |
| 21 | Press **Escape** in an open Item list | The list closes and nothing is picked |
| 22 | Remove the Plastic stool line with its **Remove** button | Order total **₹5,800** |
| 23 | Set **Needed by** to yesterday and press **Create order** | **That date has already passed. Choose today or a day after it.** |
| 24 | Set Needed by to five days from today and press **Create order** | Back on **/orders**: **A purchase order for UAT Rice Traders was created.** This is **order B**, used for cancelling in UAT-040 |
| 25 | Start another order, choose a vendor, add **no** items, and press **Create order** | **An order needs at least one item. Type one into the Items table.** — and nothing about your connection, and no `KMS-` code |
| 26 | Press **Cancel** | Back on /orders; nothing created |

### Widths and roles

| # | Do this | You should see |
|---|---|---|
| 27 | Repeat steps 13–20 at **390px** | Vendor and Needed by stack; the Item list opens fully inside the screen and is not cut off; the page never scrolls sideways |
| 28 | Repeat step 24 as `ikms.kitchen-staff.1@trading4good.org` (kitchen staff) and as the temple admin | Both can create an order. Cancel these two in UAT-040 |
| 29 | Check **/ingredients** | Neither "Plastic stool" nor anything else typed as a one-off became an ingredient |
| 30 | Sign in as `ikms.volunteer.1@trading4good.org` and type **/orders/new** | **Not your page** |

## It passes if

- [ ] A vendor's tile creates that vendor's order from a panel, with the lines in packs where the vendor sells in packs, and nothing is created until Save.
- [ ] Ordered lines leave the shopping list.
- [ ] **Create a purchase order** opens the form directly; **Raise an order** is gone.
- [ ] Vendor and Needed by share one row at 1280; no Deliver to; the note grows; the section is called Items with the exact hint and no empty-order line.
- [ ] The Item box lists the vendor's items first with their list price, then Other ingredients, and works by keyboard and by mouse.
- [ ] Picking an item fills the unit (the pack, where there is one) and the expected price.
- [ ] A one-off item can be added, and never becomes an ingredient.
- [ ] Line totals and the order total update as quantities are typed.
- [ ] Nothing is cut off at 390, including the open list.

## Watch out for

- **The unit shown in the list disagreeing with the line.** If the list says an item is in gm and the
  line then orders it in Kg, write down which. Major, because it is where a thousandfold mistake starts.
- **A price per gram.** Every price should read per Kg, per L, per piece or per pack.
- Two orders numbered the same, or a number that goes backwards.
- The two confirmations say the same thing in two ways (*PO-2026-… was created for …* on the shopping
  list, *A purchase order for … was created.* on /orders). Known; not a defect in this test.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT039-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
