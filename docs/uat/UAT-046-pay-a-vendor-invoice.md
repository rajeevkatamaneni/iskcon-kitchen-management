# UAT-046: The invoice page, what is owed, and paying it

| | |
|---|---|
| **Feature area** | Ordering — invoices and payments |
| **Technical stories** | E7-S8 (vendor invoice payment recording); T-010, T-071, T-206 (void, credit note, reverse); procurement release 2026-09-19: R-INV-7 (the invoice page), R-INV-8 (filters and total owed), R-PAY-1 (pay from the invoice), R-PAY-2 (proof is mandatory), R-PAY-3 ("Paid by", thumbnails), R-PAY-4 (the Payments page removed), and Rajeev's answers to Q-17 (only payers see the total owed) and Q-19 (the page's allowed extras). Tasks T-272, T-274, T-275, T-280, T-281 |
| **Roles exercised** | Temple admin, kitchen manager, kitchen staff, volunteer (to prove the refusal) |
| **Depends on** | UAT-045 (invoices UAT-INV-1, -2, -3, MKT-1, MKT-OLD, MKT-LATE and the second MKT-1) |
| **Environment needs** | **Uploads need the file store.** On staging the proof is kept in cloud storage; opening a thumbnail is only a real check there. You need three photos on the device you test with (a UPI screenshot, a signed note, a photo of a person or an ID card). **No money moves through this system**: paying here records a payment made outside it |

> **Rewritten 2026-09-19.** The earlier script paid from a **Payments** page at **/money**, with no
> proof. That page and its menu item are gone. Unpaid invoices, their age and the total owed are now on
> **/invoices**, and a bill is paid from its own page, with proof.

## What this feature is for

The temple pays vendors by UPI, bank transfer, cheque or cash, outside this system, and records it here.
Each payment now carries proof, so months later somebody can see who paid, how, and to whom. For cash, that
means who took the money, a note they signed, and a photo of them.

## How it is supposed to work

- **The invoice page** shows the summary (with **Delivery billed**), the items table (**Billed qty**), the
  totals block, **Invoiced vs received** (what was billed against what was delivered at the order's
  prices), the bill's thumbnail, and **Payments**. It keeps three actions from before, all allowed:
  **Record a credit note**, **Void this bill**, and **Reverse** on a payment.
- **Only the Temple Admin pays.** Nobody else sees **Pay this invoice**, the Payments section, or the
  **Total owed**, and the server refuses them.
- **Pay this invoice** opens the payment form on the page. The amount starts at what is left. Method:
  **UPI**, **Bank transfer**, **Cheque**, **Cash**.
  - UPI, bank transfer, cheque: one required upload, *Upload proof of payment — a receipt, UPI screenshot
    or bank confirmation.*
  - Cash: three required things — **Received by** (a name), a **Signed note** (*A note signed by the
    person who received the cash, e.g. ‘Received ₹1,030 in cash from ISKCON South Bengaluru’*) and a
    **Photo of the person who took the cash** (*Their ID card, or a photo of them, so we can recognise who
    took the money.*). No ID-type box.
- The payment list says **Paid by**, not "Recorded by", and shows each payment's proof as thumbnails
  (two for cash).
- **/invoices** filters, for the Temple Admin: **All · Unpaid · Due this week · 1–30 days overdue · 31+
  days overdue · Paid · Voided**, with the **Total owed** at the top, always across everything owed
  whatever filter is chosen. Others see **All · Unpaid · Overdue · Paid · Voided** and no total. "Unpaid"
  means the same invoices for everybody.
- **/money** now goes to **/invoices?filter=unpaid**. There is no Payments item in the menu.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/invoices**

## Steps

### The list and what is owed

| # | Do this | You should see |
|---|---|---|
| 1 | Look at the menu | **Invoices** under Ordering. **No Payments** item anywhere |
| 2 | Type **/money** | You land on **/invoices** with **Unpaid** chosen |
| 3 | Read the filters and the top of the list | **All · Unpaid · Due this week · 1–30 days overdue · 31+ days overdue · Paid · Voided**, and **Total owed** with a figure. Columns Invoice · Vendor · Against · Amount · Due · Status |
| 4 | Add up **Still owed** over every Unpaid invoice (open each, or use what you know) | It equals **Total owed**, to the rupee, grouped the Indian way (a figure over a lakh reads like ₹1,29,280, never ₹129,280) |
| 5 | Choose **Due this week** | UAT-INV-2 (due in three days) and UAT-INV-3 (due today). Not UAT-INV-1 (15 days) and not MKT-1 (no due date) |
| 6 | Choose **1–30 days overdue** | **MKT-LATE** (10 days late), badged **Overdue** |
| 7 | Choose **31+ days overdue** | **MKT-OLD** (35 days late) |
| 8 | Look at **Total owed** while switching filters | It **does not change**. It is always everything owed |
| 9 | Read UAT-INV-1's row | Against: its order number, with **Difference ₹100** under it. The word **Variance** appears nowhere |

### The invoice page

| # | Do this | You should see |
|---|---|---|
| 10 | Open **UAT-INV-1** | In order: the heading with an **Unpaid** badge and the buttons **Pay this invoice**, **Record a credit note**, **Void this bill**; **The invoice** (Grand total ₹3,150, Against, Invoice date, Due, **Delivery billed**); the bill's thumbnail; **Items** (Item · Ordered · Delivered · **Billed qty** · Amount · Rate); the totals block with the green tick; **Invoiced vs received**; **Payments** |
| 11 | Read **Invoiced vs received** | Items billed **₹3,000** against Delivered, at the order’s prices **₹2,900**, Difference **₹100 more than received**. Worked out from the lines: 2 bags at ₹1,450 was ₹2,900 on the order |
| 12 | Press the bill thumbnail | The bill opens, filling the screen, and closes with Escape |
| 13 | Open **UAT-INV-2** | Under Other charges, the note **Delivery charge** |

### Paying by UPI

| # | Do this | You should see |
|---|---|---|
| 14 | On UAT-INV-1 press **Pay this invoice** | A form on the page, **Record a payment**, with the cursor in **Amount**, already **3150** (what is left). Date today. Method list UPI · Bank transfer · Cheque · Cash. **Reference** (*Bank, UPI or cheque number*). **Proof of payment**, with *Upload proof of payment — a receipt, UPI screenshot or bank confirmation.* |
| 15 | Method **UPI**, reference `UPI-778812`, no file. **Record payment** | Not saved: **Proof of payment is required** |
| 16 | Upload the UPI screenshot, **Record payment** | **Payment of ₹3,150 recorded. This invoice is now paid in full.** The badge reads **Paid** and **Pay this invoice** is gone |
| 17 | Read the Payments table | Paid on · Amount · Method · Reference · **Paid by** · Proof, and actions. Paid by is **your name**. One proof thumbnail, which opens the screenshot. The words **Recorded by** appear nowhere |

### Paying in cash

| # | Do this | You should see |
|---|---|---|
| 18 | On **UAT-INV-2**, **Pay this invoice**, method **Cash** | **Reference** is replaced by **Received by** (*Their full name*, marked required), and the one upload by two: **Signed note** and **Photo of the person who took the cash**, each with its hint word for word as above. There is no box for the type of ID |
| 19 | **Record payment** with all three empty | Three refusals: **Received by is required**, and one for each photo |
| 20 | Switch the method to **UPI** and back to **Cash** | UPI shows one upload and Reference again; Cash shows the three again |
| 21 | Received by `Ravi Kumar`, both photos, **Record payment** | **Payment of ₹3,500 recorded. This invoice is now paid in full.** The row's Reference reads **Received by Ravi Kumar**, Paid by is your name, and there are **two** thumbnails |

### Part payment, over-payment, reversal, void and credit

| # | Do this | You should see |
|---|---|---|
| 22 | On **UAT-INV-3** (₹2,520), pay `1000` by **Bank transfer** with proof | **Payment of ₹1,000 recorded. ₹1,520 is still owed on this invoice.** Paid to date ₹1,000, Still owed ₹1,520. Still **Unpaid** |
| 23 | Press **Pay this invoice** again | The amount starts at **1520** |
| 24 | Type `2000` | **That’s more than the ₹1,520 still owed.** Type `0`: **Amount (₹) must be more than 0**. Close the form |
| 25 | Press **Void this bill** on UAT-INV-3 | Refused while the ₹1,000 payment stands. A bill with payments that have not been reversed can't be voided |
| 26 | Press **Reverse** on the ₹1,000 payment and give a reason | The payment is marked reversed and Still owed is ₹2,520 again |
| 27 | Now **Void this bill**, with the reason `Vendor sent the wrong bill` | *The bill leaves the payment queue for good …*. After confirming: badge **Voided**, and it shows under the **Voided** filter, not under Unpaid. **Total owed** fell by ₹2,520 |
| 28 | **Create an invoice** for UAT Rice Traders (UAT-045) | Delivery **3** is offered again: voiding the bill released the delivery it billed. Press Cancel |
| 29 | On the second **MKT-1**, press **Record a credit note**, credit ₹20 for `Bruised bananas` | Still owed goes down by ₹20 and the page shows **Credited** and **Owed after credit** |
| 30 | Pay **MKT-1** (₹620) in **Cash** as in step 21 | **Paid in full.** A market buy is paid like any vendor's |

### Who can pay, and widths

| # | Do this | You should see |
|---|---|---|
| 31 | Sign in as `ikms.kitchen-staff.5@trading4good.org` (kitchen manager). Open **/invoices** | Filters **All · Unpaid · Overdue · Paid · Voided**. **No Total owed** |
| 32 | Choose **Unpaid** | The same unpaid invoices the temple admin saw under Unpaid |
| 33 | Open UAT-INV-1 | The invoice, its items, totals and Invoiced vs received. **No** Pay this invoice, Record a credit note, Void this bill, and **no Payments** section |
| 34 | Repeat steps 31–33 as `ikms.kitchen-staff.1@trading4good.org` (kitchen staff) | The same |
| 35 | As the kitchen manager, type **/money** | You land on **/invoices?filter=unpaid** |
| 36 | *(Technical tester only.)* As the kitchen manager, `POST /api/v1/vendor-invoices/<UAT-INV-3's id>/payments` on the system address | **403**, `KMS-400021`. Nothing is written |
| 37 | Sign in as `ikms.volunteer.1@trading4good.org` | No Invoices in the menu; **/invoices** and an invoice's address show **Not your page** |
| 38 | Back as the temple admin, open **UAT-INV-2** and the list at **1280px**, **1024px** and **390px** | Nothing cut off; nothing scrolls sideways. At 390 the pay form's **Cancel** and **Record payment** sit side by side at the same size, a long reference or receiver's name wraps inside its card, and the green tick's tip stays on screen |
| 39 | Open **/audit** | Each payment, the reversal, the void and the credit note, naming you, with amounts and methods |

## It passes if

- [ ] The invoice page shows summary (with Delivery billed), items (Billed qty), totals, Invoiced vs received worked out from the lines, the bill thumbnail and Payments; credit note, void and reverse remain.
- [ ] Only the Temple Admin sees Pay this invoice, Payments and Total owed; the server refuses anyone else.
- [ ] The pay form starts at what is left, and swaps its fields when the method changes.
- [ ] UPI, bank transfer and cheque need one proof; cash needs Received by, a signed note and a photo; no ID-type box.
- [ ] The list says **Paid by** and shows one thumbnail (two for cash).
- [ ] Filters and the total owed are right for the Temple Admin; others see the plain filters and no total, and "Unpaid" is the same set for everybody.
- [ ] Nothing links to /money, the menu has no Payments, and /money lands on the unpaid invoices.
- [ ] A voided bill releases its delivery; a bill with live payments can't be voided.

## Watch out for

- **Any wording suggesting the app makes the payment.** It records one; the money moved elsewhere.
- **Total owed that is not the sum of what is still owed**, or that changes when you change the filter.
  Major.
- **A cash payment saved without all three.** That is the gap this closes. Blocker.
- `KMS-400174` — *A payment has to be more than ₹0.* — is the server's answer behind the form's own
  refusal. `KMS-400166` — *That file is too large to upload.* — for a file over 10 MB: try one if you have
  it.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT046-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
