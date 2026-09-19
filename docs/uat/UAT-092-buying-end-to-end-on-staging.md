# UAT-092: Buying, end to end, on staging

| | |
|---|---|
| **Feature area** | Ordering — the whole chain, order to payment to costing |
| **Technical stories** | Procurement release 2026-09-19: §10 of the requirements (the end-to-end story), AC-3.1 (one temple cannot see another's new records), AC-3.2 (existing data survives the upgrade), and the checks that only staging can make (a real PDF, WhatsApp, uploads kept in cloud storage). Proven locally in `docs/work/proof/E2E-LOCAL.md` |
| **Roles exercised** | Temple admin, kitchen manager, kitchen staff, second temple admin |
| **Depends on** | Every other procurement test (UAT-038 to UAT-046, UAT-087 to UAT-090) describes one part of this in detail. Run this one in one sitting, on fresh data |
| **Environment needs** | **Staging only.** The document renderer, the WhatsApp channel and cloud file storage must be on (README §4). Part A must be done **before** the release is deployed |

## What this feature is for

The reason the procurement work was done: the rice issued to the Deity Kitchen was costed at **₹0**,
because no price ever reached the ingredient. This test follows one ingredient from the shopping list
to a paid bill and checks that a real rupee figure comes out the other end, on the real site.

## Part A — before the release is deployed (AC-3.2)

| # | Do this | You should see |
|---|---|---|
| A1 | As the temple admin, write down: the number and status of three purchase orders (one sent, one part delivered or received, one cancelled if there is one); the number, amount and status of three invoices, one of them paid; the payments on that paid invoice; and the on-hand figure of three inventory items | A written list, dated, before the deploy |
| A2 | After the deploy, open each one again | **Every figure is unchanged**: the same statuses, amounts, payments and on-hand. An old invoice with no lines still opens, showing its total, and **Nothing uploaded** for the bill. Anything changed is a **Blocker** |

## Part B — the chain

Make **UAT Chain Rice** (Kg) with packs **5 Kg** and **Bag = 25 Kg**, and a vendor **UAT Chain Traders**
with **your own WhatsApp number** as its phone.

| # | Who | Do this | You should see |
|---|---|---|---|
| 1 | Temple admin | On UAT Chain Traders' page, Other ingredients: tick UAT Chain Rice, Sells it as **Bag = 25 Kg**, List price `1450`, lead time `2`, Preferred. **Save** | **1 ingredient added to Supplies.** The row reads **₹1,450 / bag · ₹58 / Kg**, with no arrow |
| 2 | Kitchen manager | **Add to inventory**: UAT Chain Rice, **20** Kg on the shelf, warn below **100** Kg | The value box reads **What it would cost to buy today (₹ per Kg)**, pre-filled **58**. Saved |
| 3 | Kitchen manager | **/shopping-list** | In the UAT Chain Traders tile: Top-up **100 Kg**, on hand **20 Kg**, suggested **4 × Bag (25 Kg) = 100 Kg** |
| 4 | Kitchen manager | **Generate purchase order**, Needed by in three days, **Save** | **PO-2026-… was created for UAT Chain Traders.** The line reads **4 × Bag (25 Kg)**, **List price ₹1,450 / bag · ₹58 / Kg** |
| 5 | Kitchen manager | Open the order. **Generate PDF**, then download and open it | **A real PDF** (not a placeholder). The line reads **UAT Chain Rice 4 × Bag (25 Kg)**, **₹1,450 / bag · ₹58 / Kg**, total **₹5,800**. Dates are written like **20 Sept 2026**. No figure of 1,000 gm or more |
| 6 | Kitchen manager | **Send on WhatsApp** | The order is **Sent**. **On your phone**, the WhatsApp message arrives with the order's PDF attached, and the dates in it read like **20 Sept 2026** |
| 7 | Kitchen staff | **/deliveries** → Record a delivery for UAT Chain Traders: received **2** bags, rejected **1** bag, reason **Damaged**, an expiry. **Save delivery** | Green: **Delivery from UAT Chain Traders recorded. 1 item went into stock. 1 line is still to come from them.** Stock **+50 Kg** exactly. No price anywhere on the screen |
| 8 | Kitchen staff | **Partly delivered** → the same button → **Everything arrived** → **Save delivery** | **… UAT Chain Rice is complete: 100 of 100 Kg. Nothing more is owed by them.** The history reads two lines and **Received 100 of 100 Kg ordered · complete** *&lt;date&gt;* |
| 9 | Temple admin | **Create an invoice**, UAT Chain Traders, tick the **first** delivery. Amount `3000`, GST `150`, Grand total `3150`. Upload a **photo** of a bill. **Save invoice** | Rate **₹1,500 / bag · ₹60 / Kg**, the green tick, and **Invoice … was recorded.** |
| 10 | Temple admin | Reload the invoice page, then open the bill thumbnail **on a second device** (a phone signed in as the temple admin) | The photo opens on both. It was stored, not just shown from the browser that sent it |
| 11 | Temple admin | A second invoice for the **second** delivery: amount `3200`, GST `160`, Grand total `3360`, a **PDF** bill. Save | Rate **₹1,600 / bag · ₹64 / Kg**. The PDF opens from its thumbnail |
| 12 | Temple admin | Pay invoice 1 by **UPI** with a screenshot as proof | **Payment of ₹3,150 recorded. This invoice is now paid in full.** Paid by: your name; one thumbnail that opens |
| 13 | Temple admin | Pay invoice 2 in **Cash**: Received by a name, a photo of a signed note, a photo of the person, taken **with the phone's camera** from the upload | **Payment of ₹3,360 recorded…**; **Received by** the name; two thumbnails that open |
| 14 | Temple admin | UAT Chain Rice's ingredient page | UAT Chain Traders **₹1,600 / bag · ₹64 / Kg** with a **red up arrow**, tip **₹1,500 on** *&lt;date&gt;*. Market rate **₹64 / Kg · Set from an invoice** |
| 15 | Kitchen manager | Raise a request from **Deity Kitchen** for **10 Kg** of UAT Chain Rice; temple admin approves; kitchen manager issues. Note **/issued-from-store** for this month before and after | Deity Kitchen rises by exactly **₹640** (10 Kg × ₹64). Not ₹0 |
| 16 | Kitchen manager | A recipe **UAT Chain Plain Rice**: 10 Kg of UAT Chain Rice makes 20 Kg, 250 gm a head. Plan it as a Lunch next month for **80**. Open **/cost-per-serving** for that month | Lunch · 1 meal · 80 servings · **₹640** · **₹8** a serving. (If other Lunches are planned that month, Lunch's estimated materials rise by exactly ₹640 instead) |

## Part C — one temple cannot see another's (AC-3.1)

| # | Do this | You should see |
|---|---|---|
| C1 | As the temple admin, copy the addresses of: UAT Chain Rice's ingredient page, the purchase order, invoice 1, and UAT Chain Traders' vendor page | Four addresses |
| C2 | Sign in as `ikms.temple-admin.2@trading4good.org` (the second temple) and open each address | Each says it can't be found. **No** name, price, pack size, bill, proof or delivery from the first temple shows, even for a moment |
| C3 | As the second temple admin, open **/deliveries**, **/invoices** (and its Total owed) and **/ingredients/merge** | Nothing from the first temple: no UAT Chain Traders, none of its deliveries or invoices, and its money is not in this temple's Total owed |

## It passes if

- [ ] Existing orders, receipts, invoices, payments and stock read exactly as before the deploy.
- [ ] The chain runs end to end: packs on the list and the order, a real PDF, a WhatsApp message with the PDF, two part deliveries, one invoice per delivery, one UPI and one cash payment with proof.
- [ ] Uploaded bills and proofs open again after a reload and from another device.
- [ ] The list price and its arrow follow the bills; the market rate follows the latest bill.
- [ ] "Issued from the temple store" and "Cost per serving" show a real rupee figure for the rice.
- [ ] A second temple sees none of it.

## Watch out for

- **₹0 anywhere in steps 15–16.** The reason this work exists. Blocker.
- **A PDF that is a placeholder**, or a WhatsApp message with no document. Write down the order number
  and the time.
- **A thumbnail that opens on the device that uploaded it but not on another.** That means the file was
  never stored.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT092-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
