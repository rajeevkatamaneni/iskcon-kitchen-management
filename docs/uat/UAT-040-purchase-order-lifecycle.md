# UAT-040: A purchase order's page — send, cancel, what arrived, and returns

| | |
|---|---|
| **Feature area** | Ordering — the purchase order page |
| **Technical stories** | E5-S3 (purchase order lifecycle); T-013 (goods back to the vendor), T-142 (closing a part-delivered order); procurement release 2026-09-19: R-PO-4 (one merged table, "Rejected on delivery", history per item, deliveries recorded on the Deliveries screen). Tasks T-265, T-289 |
| **Roles exercised** | Kitchen manager, kitchen staff, volunteer (to prove the refusal) |
| **Depends on** | UAT-039 (orders A and B); steps 11–18 need UAT-044's two deliveries against order A |
| **Environment needs** | None (sending on WhatsApp is UAT-043) |

> **Rewritten 2026-09-19.** The earlier script read an **Activity** and a **Documents** section, pressed
> **Receive delivery** on the order, and used **Edit lines**. None of those is on the page now: the
> button is **Edit**, deliveries are recorded on the **Deliveries** screen (UAT-044), and the order shows
> one merged table instead of separate items and deliveries tables.

## What this feature is for

The order page answers "what did we ask for, and what has actually come?" in one place: every item,
what was ordered, what was delivered, what was turned away at delivery, and what went back to the
vendor, with the history of each delivery a click away.

## How it is supposed to work

- The states are **Draft → Sent → Part delivered → Received**, or **Closed** (part delivered, and the
  rest will not come), or **Cancelled**. Only a draft can be edited.
- The header says when it was sent (**Sent <date>** or **Not sent yet**) and **Needed by <date>**.
- **One table**, headed **Items**: Item · **Ordered** · **Delivered** · **Rejected on delivery** ·
  **Returned**, and the actions. Under each item a quiet **▸ N deliveries** opens the history of what
  came, in date order.
- The words **Rejected at the gate** and **Fixed when the order was sent** are gone, and so is the
  separate **Deliveries received** table.
- Deliveries are **recorded on the Deliveries screen**. The page links there with **Record a delivery
  on the Deliveries screen →**. There is no record button on the order itself (Rajeev, 2026-09-19).
- Goods can still go back to the vendor with **Return to vendor** on the item's row.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.5@trading4good.org` (kitchen manager)
- **Start at:** **/orders**, with orders A and B from UAT-039 in **Draft**.

## Steps

### Draft, sent, cancelled

| # | Do this | You should see |
|---|---|---|
| 1 | Open **order A** | Its number, a **Draft** chip, **Not sent yet**, **Needed by** *&lt;the date&gt;*. Buttons: a document language list, **Generate PDF**, **Print**, **Edit**, **Mark sent** (and **Send on WhatsApp** only if the vendor has a phone number) |
| 2 | Press **Edit**, change **Needed by** to four days from today, and save | Saved. The header shows the new date |
| 3 | Press **Mark sent** | The chip reads **Sent** and the header **Sent** *&lt;today&gt;*. **Edit** is gone |
| 4 | Look for the line **Fixed when the order was sent** | Not there |
| 5 | Read the **Items** table | Item · Ordered · Delivered · Rejected on delivery · Returned. UAT Bulk Rice ordered **4 × Bag (25 Kg)** with **100 Kg** under it; Delivered **—**. No price column |
| 6 | Press **Record a delivery on the Deliveries screen →** | The Deliveries screen opens showing only order A's lines (UAT-044 records the deliveries) |
| 7 | Open **order B** and, at the foot, press **Cancel this purchase order** | A reason is asked for before anything happens |
| 8 | Try to cancel with the reason blank | Refused. A cancellation must say why |
| 9 | Give the reason `Ordered by mistake` and press **Cancel order** | The chip reads **Cancelled**, with the reason on the order. There is no **Mark sent** and no link to record a delivery |
| 10 | Cancel the two orders the kitchen staff and temple admin made in UAT-039 the same way | Both **Cancelled** |

### After the deliveries (run UAT-044 first)

| # | Do this | You should see |
|---|---|---|
| 11 | Open **order A** again | Chip **Received** (or **Part delivered** if UAT Tea has not all come). UAT Bulk Rice: Ordered **4 × Bag (25 Kg) / 100 Kg**, Delivered **100 Kg**, Rejected on delivery **25 Kg**, Returned **—** |
| 12 | Look for a second table of deliveries on the page, and for the words **Rejected at the gate** | Neither exists |
| 13 | Under UAT Bulk Rice press **▸ 2 deliveries** | It opens (a screen reader hears it as expanded) and reads, oldest first, in this shape: *&lt;date&gt;* **· 50 Kg received · 25 Kg rejected (damaged) · Received by:** *&lt;name&gt;* and *&lt;date&gt;* **· 50 Kg received · Received by:** *&lt;name&gt;*, then **Received 100 of 100 Kg ordered · complete** *&lt;date&gt;*. No order number in these lines |
| 14 | Press it again, and try it with the keyboard (Tab, then Enter) | It closes and opens the same way |
| 15 | On the UAT Bulk Rice row press **Return to vendor** | A form **Return goods to the vendor**. Because the item came in two deliveries, it asks which **Delivery** it goes back from. Then **Quantity**, **Reason** — a list: damaged, spoiled, wrong item, not delivered, other — (*Say why, so the vendor’s record shows it.*) and **Note** (optional) |
| 16 | Choose the second delivery, leave **Quantity** empty, press **Record return** | Refused: **Enter how much went back to the vendor.** |
| 17 | Quantity `5` (Kg), Reason **spoiled**, press **Record return** | The Returned column reads **5 Kg**. On **/inventory**, UAT Bulk Rice is down by exactly **5 Kg**, and its movement history has the return |
| 18 | Open **/deliveries**, tab **Received** | The return shows in the Returned column: **UAT Bulk Rice 5 Kg, spoiled,** *&lt;date&gt;* |

### The list, widths and roles

| # | Do this | You should see |
|---|---|---|
| 19 | Open **/orders** | Columns Vendor · Status · Generated · Sent · Needed by, and a **Status** filter: All, Draft, Sent, Part delivered, Received, Closed, Cancelled |
| 20 | Filter by **Cancelled**, then **Received** | Only those orders each time |
| 21 | Open order A at **1280px**, **1024px** and **390px** with the history open | Nothing cut off; nothing scrolls sideways; at 390 each item is a card with every value labelled |
| 22 | Sign in as `ikms.kitchen-staff.1@trading4good.org` (kitchen staff) and open order A | The same page. The history opens; **Return to vendor** is offered |
| 23 | Sign in as `ikms.volunteer.1@trading4good.org` and type order A's address | **Not your page** |

## It passes if

- [ ] A draft can be edited; a sent order cannot, and **Edit** is gone once it is sent.
- [ ] Sending shows **Sent <date>**; cancelling needs a reason and ends the order.
- [ ] The page has **one** table: Item · Ordered · Delivered · Rejected on delivery · Returned.
- [ ] An item delivered in two parts shows the right Delivered total and exactly two history lines, in the exact wording, with the closing line.
- [ ] **Rejected at the gate**, **Fixed when the order was sent** and a separate deliveries table appear nowhere.
- [ ] The page links to the Deliveries screen and has no record button of its own.
- [ ] **Return to vendor** needs a quantity and a reason, asks which delivery when there were several, and takes the stock back down.

## Watch out for

- **Delivered totals that do not add up** to the history lines under them. Add them by hand. Major.
- **Rejected goods counted as delivered.** 50 + 50 delivered with 25 rejected is 100 delivered and 25
  rejected, not 125. Major.
- An order that can go backwards (Sent → Draft), or be edited after it is sent. Blocker.
- **Close this order, part delivered** appears on a part-delivered order for when the rest will never
  come. It is not part of this release; if you use it, UAT-077 explains what its three choices do.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT040-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
