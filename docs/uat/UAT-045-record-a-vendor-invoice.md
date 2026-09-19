# UAT-045: Create an invoice — the bill, its deliveries, its lines and its totals

| | |
|---|---|
| **Feature area** | Ordering — vendor invoices |
| **Technical stories** | E5-S8 (vendor invoice capture); T-082; procurement release 2026-09-19: R-INV-1 (the button and form), R-INV-2 (the bill upload), R-INV-3 (billed per delivery), R-INV-4 (the items table, packs), R-INV-5 (totals and the check), R-INV-6 (saving updates prices), R-VEN-4 (the list price comes from the bill), R-VEN-3 (arrows after a bill), R-ING-3 (market rate from a bill), and Rajeev's answers to Q-5 (list price before GST) and Q-9 (price only on the invoice). Tasks T-267, T-271, T-273, T-277 |
| **Roles exercised** | Temple admin, kitchen manager, kitchen staff, volunteer (to prove the refusal) |
| **Depends on** | UAT-044 (three deliveries from UAT Rice Traders), UAT-089 (vendor **Market**) |
| **Environment needs** | **Uploads need the file store.** On staging the bill is kept in cloud storage; opening it again (step 12, and UAT-046) is only a real check there. You need a photo or PDF of a bill on the device you test with, and one small `.txt` file |

> **Rewritten 2026-09-19.** The earlier script used **Record an invoice**, pasted a purchase order id by
> hand, typed one amount and a **Scan reference**, and sent payment to a **Payments** page. All of that
> is gone. An invoice now bills **deliveries**, line by line, with an uploaded copy of the bill.

## What this feature is for

Prices used to be typed by hand on a vendor page nobody found, and invoices held only a total. Now each
bill is entered line by line against the delivery it is for, with a copy of the bill attached. Every
saved line sets that vendor's **list price** for the item, so costing runs on what the temple actually
pays.

## How it is supposed to work

- **/invoices** has **Create an invoice**, which opens the form straight away.
- The form: **Vendor**, **Invoice number**, **Invoice date**, **Due date**, then **Deliveries being
  billed** — one tick per delivery from that vendor not yet on a bill, reading like
  *PO-2026-0044 · delivered 12 Sept · received by Govinda Das · 2 × Bag (25 Kg)* — and **Copy of the
  bill**, an upload that is **required**: *Upload a copy of the bill — a photo, PDF or scan.*
- A **direct** invoice (**Direct, with no purchase order**, for example a cash buy from **Market**) adds
  its items by hand with the same type-ahead as the purchase order form, and has a **Description**.
- **Items**: Item · Ordered · Delivered · **Billed qty** · **Amount (₹)** · **Rate**. The lines of the
  chosen deliveries come in **locked**: none added, none removed. **Billed qty** starts at what was
  delivered. The **Rate** is worked out (Amount ÷ Billed qty) and can't be typed: **₹100 / Kg**, or for a
  pack **₹1,500 / bag · ₹60 / Kg**.
- Billing **more than was delivered** shows an amber line on the item: **Billed 50 Kg, 45 Kg delivered**.
- A bill in a unit the ingredient doesn't know yet (a crate, a can) is defined on the spot with **Add a
  pack size…**, and saved on the ingredient.
- **Totals**, laid out like a bill and right-aligned: **Sub total** (the lines added up), **GST**,
  **Other charges** with **What the other charges are for**, **Discount**, **Grand total** (typed from the
  bill). Sub total + GST + Other charges − Discount must equal the Grand total. When it does, a **green
  tick** beside the Grand total, named *Adds up to the grand total*, and no sentence. When it doesn't, one
  red line — **Adds up to ₹1,030, not ₹1,000** — and it can't be saved.
- **Saving** sets each line's rate as the vendor's **list price** for that item (per pack when sold in
  packs, before GST — Rajeev, 2026-09-19), adds it to the price history, and sets the ingredient's
  **market rate** (*Set from an invoice*). A bill dated earlier than the newest price goes into the
  history without overwriting it. A line for an ingredient the vendor was not yet linked to links it (not
  as preferred).

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- From UAT-044, UAT Rice Traders has three deliveries: **1** (kitchen staff: UAT Bulk Rice 2 bags),
  **2** (kitchen manager: UAT Bulk Rice 2 bags and UAT Tea 1 × 500 gm, order A) and **3** (UAT Groundnut
  Oil 1 tin, order C).
- On UAT Bulk Rice's ingredient page, the UAT Rice Traders list price should read **₹1,450 / bag ·
  ₹58 / Kg** (UAT-088 step 25).
- **Start at:** **/invoices**

## Steps

### The form

| # | Do this | You should see |
|---|---|---|
| 1 | Look at the top right of **/invoices** | **Create an invoice**. No **Record an invoice** |
| 2 | Press it | The form, titled **Create an invoice**, with **Cancel** and **Save invoice** at the top right. Fields as above. No **Scan reference** box, and no purchase order id to paste |
| 3 | Choose vendor **UAT Rice Traders** | Under **Deliveries being billed**, three ticks, one per delivery, each naming its order, the day, who received it and what came — for example *PO-2026-… · delivered* *&lt;date&gt;* *· received by* *&lt;name&gt;* *· 2 × Bag (25 Kg)*. Two deliveries on the same day of the same thing would read **1st that day** and **2nd that day** |
| 4 | Tick delivery **1** | The Items section reads *Pulled in from PO-2026-….* and one line: **UAT Bulk Rice**, Ordered **4 × Bag (25 Kg)**, Delivered **2 × Bag (25 Kg)**, Billed qty **2** × Bag (25 Kg), Amount empty. There is **no** remove button and **no** row to add an item |

### Invoice 1: the check, the upload, the price

| # | Do this | You should see |
|---|---|---|
| 5 | Invoice number `UAT-INV-1`, invoice date today, due date in 15 days. Amount `3000` | Rate **₹1,500 / bag · ₹60 / Kg**, and Sub total **₹3,000** |
| 6 | GST `150`, Grand total `3100` | One red line under the Grand total: **Adds up to ₹3,150, not ₹3,100**. No tick |
| 7 | Press **Save invoice** | Not saved. At the top: **This invoice can’t be saved yet.** listing **The sub total, GST, other charges and discount don’t add up to the grand total on the bill.** |
| 8 | Change the Grand total to `3150` | The red line goes. A **green tick** appears beside the Grand total; hover or tab to it: **Adds up to the grand total**. No sentence |
| 9 | Look at the totals block at **1280px** | It sits on the right like a bill. Sub total, GST, Other charges, Discount and Grand total all end on **one right edge**, in figures that line up |
| 10 | Press **Save invoice** without a file | Not saved: **Upload a copy of the bill.** at the top and **Copy of the bill is required** at the upload |
| 11 | Upload the `.txt` file | Refused: **That file isn’t a photo or a PDF.** — **Upload a photo (JPG, PNG, WebP or HEIC) or a PDF.** (`KMS-400165`) |
| 12 | Upload a **photo** of a bill, then **Save invoice** | You land on the invoice's own page with **Invoice UAT-INV-1 was recorded.** The bill's thumbnail is there; pressing it opens the photo |
| 13 | Open **UAT Bulk Rice**'s ingredient page | UAT Rice Traders reads **₹1,500 / bag · ₹60 / Kg** with a **red up arrow**; its tip reads **₹1,450 on** *&lt;the date it was set in UAT-088&gt;*. Market rate **₹60 / Kg ·** *&lt;the invoice date&gt;* · **Set from an invoice** |

### Invoice 2: two lines, over-billing, other charges

| # | Do this | You should see |
|---|---|---|
| 14 | **Create an invoice**, UAT Rice Traders | Only deliveries **2** and **3** are offered now. Delivery 1 is on a bill and is not offered again |
| 15 | Tick delivery **2**. Invoice number `UAT-INV-2`, invoice date today, due date **three days from today** | Two locked lines: UAT Bulk Rice (billed **2** × Bag (25 Kg)) and UAT Tea (billed **1** × 500 gm) |
| 16 | UAT Bulk Rice amount `3200` | Rate **₹1,600 / bag · ₹64 / Kg** |
| 17 | On UAT Tea change Billed qty to `2`, amount `520` | An **amber** line under it: **Billed 1 Kg, 500 gm delivered**. It warns; it does not stop you |
| 18 | Put UAT Tea back to `1`, amount `260` | The amber line goes. Rate **₹260 / 500 gm · ₹520 / Kg** |
| 19 | GST `0`, Other charges `50` with *What the other charges are for* `Delivery charge`, Discount `10`, Grand total `3500` | Sub total **₹3,460**. 3,460 + 0 + 50 − 10 = 3,500: the green tick |
| 20 | Upload a **PDF** of a bill, **Save invoice** | **Invoice UAT-INV-2 was recorded.** |
| 21 | Open UAT Bulk Rice's ingredient page | **₹1,600 / bag · ₹64 / Kg**, a **red up arrow**, tip **₹1,500 on** *&lt;invoice 1's date&gt;*. Market rate **₹64 / Kg** · Set from an invoice |

### Invoice 3: a unit the ingredient doesn't know

| # | Do this | You should see |
|---|---|---|
| 22 | Sign in as `ikms.kitchen-staff.5@trading4good.org` (kitchen manager). **Create an invoice**, UAT Rice Traders, tick delivery **3**, number `UAT-INV-3`, invoice date today, due date **today** | One line: UAT Groundnut Oil, delivered 1 × Tin (15 L) |
| 23 | The vendor billed it as 3 cans of 5 L. Open the Billed unit list and choose **Add a pack size…** | Small fields for a new pack on UAT Groundnut Oil: name, size, unit |
| 24 | Name `Can`, size `5`, unit L. Add it | **Can** is chosen, and the Billed qty now reads **3** (15 L is 3 cans) |
| 25 | Amount `2400`, GST `120`, Grand total `2520`, upload a photo, **Save invoice** | Saved. Rate **₹800 / can · ₹160 / L**. On UAT Groundnut Oil's page, the pack sizes now include **Can = 5 L** |

### A direct invoice from Market

| # | Do this | You should see |
|---|---|---|
| 26 | Sign in as `ikms.kitchen-staff.1@trading4good.org` (kitchen staff). **Create an invoice**, tick **Direct, with no purchase order**, vendor **Market** | A **Description** box (placeholder *Cash market vegetables*). No delivery ticks. Items reads *Add each item on the bill. Type the amount printed for the line, and the rate is worked out.* with an **Add an item** box |
| 27 | Description `Cash vegetables for the Sunday feast`, number `MKT-1`, invoice date today, **no due date**. Type `UAT Cur` in the item box | **UAT Curry Leaves** under **Sold by Market**. Pick it: the focus moves to its billed quantity |
| 28 | Billed qty `2` Kg, amount `120` | Rate **₹60 / Kg** |
| 29 | Add **UAT Jeera** (not sold by Market yet), `1` Kg, amount `400`. Then type `Banana leaves` and choose **Add ‘Banana leaves’ as a one-off item**, `20` pieces, amount `100` | Rates **₹400 / Kg** and **₹5 / piece**. Each direct line has a **Remove** button |
| 30 | Grand total `620`, upload a photo, **Save invoice** | Saved. On Market's vendor page, **UAT Jeera** is now in Supplies at **₹400 / Kg**, **not** preferred. Banana leaves did not become an ingredient |
| 31 | Create another direct invoice from Market, number `MKT-OLD`, **invoice date 40 days ago** and **due date 35 days ago**, UAT Curry Leaves `1` Kg at `55`, Grand total `55` (upload a photo of a bill for this invoice and the next two) | Saved. On Market's page UAT Curry Leaves still reads **₹60 / Kg** — the older bill did not overwrite the newer price — with a **red up arrow** whose tip reads **₹55 on** *&lt;that older date&gt;* |
| 32 | Create one more direct invoice from Market, number `MKT-LATE`, invoice date **20 days ago**, due date **10 days ago**, UAT Coconut `10` pieces at `300`, Grand total `300` | Saved. (UAT-046 needs one invoice 1–30 days overdue) |
| 33 | Create another direct invoice from Market reusing the number `MKT-1`, with any one line | Saved, with an **amber** notice: **Invoice MKT-1 was recorded.** and **Another invoice from this vendor already uses that number.** A warning, not a refusal |

### Widths and access

| # | Do this | You should see |
|---|---|---|
| 34 | Fill a form like invoice 2 at **1024px** and **390px** | Nothing cut off; nothing scrolls sideways. At 390 each line is a card with **Billed qty** and **Amount** side by side and **Rate** under them; the totals block keeps its right edge; the green tick's tip stays inside the screen |
| 35 | On a **phone**, press the bill upload | The phone offers the camera as well as files |
| 36 | Sign in as `ikms.volunteer.1@trading4good.org` | No **Invoices** in the menu. **/invoices/new** shows **Not your page** |

## It passes if

- [ ] **Create an invoice** opens the form directly; **Record an invoice** and **Scan reference** are gone.
- [ ] A copy of the bill (photo or PDF) is required; anything else is refused (`KMS-400165`).
- [ ] The vendor's unbilled deliveries are offered as ticks; a billed one is never offered again.
- [ ] A delivery's lines come in locked, with Billed qty defaulting to what was delivered.
- [ ] The rate is worked out and read-only, per pack **and** per stock unit for a pack line.
- [ ] Billing more than was delivered gives the amber line in the exact wording, and does not block.
- [ ] A new pack can be defined on the spot and is saved on the ingredient.
- [ ] The totals block checks Sub total + GST + Other charges − Discount against the Grand total: a green tick with no sentence, or the red line and no save.
- [ ] Saving sets the list price (before GST), the price history and the arrow, and the market rate; a late, older bill does not overwrite a newer price.
- [ ] A direct invoice adds items by hand with the same type-ahead, and a one-off never becomes an ingredient.
- [ ] Temple admin, kitchen manager and kitchen staff can create invoices; a volunteer cannot.

## Watch out for

- **GST in the list price.** ₹3,000 of rice with ₹150 GST is ₹1,500 a bag, not ₹1,575. Rajeev ruled
  the list price is **before** GST. Major.
- **A rate per gram.** Every rate reads per Kg, per L, per piece or per pack.
- **A price that floats free of a unit.** A bill line must never save without knowing what one unit of
  it is.
- **The tick's tip running off a phone screen.** It was fixed in the shared tooltip; if it recurs, note
  the screen width.
- `KMS-400168` (*The figures don’t add up to the grand total.*), `KMS-400169` (*One of those deliveries is
  already on another invoice…*) and `KMS-400170` (*The items don’t match the deliveries being billed.*) are
  the server's answers behind the form. You should only meet them if two people bill the same delivery at
  once — try it in two windows and note what the second one sees.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT045-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
