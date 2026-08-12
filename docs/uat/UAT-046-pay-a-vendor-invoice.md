# UAT-046: Pay a vendor's bill

| | |
|---|---|
| **Feature area** | Payments — vendor payables |
| **Technical stories** | E7-S8 (vendor invoice payment recording) |
| **Roles exercised** | Temple admin |
| **Depends on** | UAT-045 |
| **Environment needs** | None — no money moves through this system |

## What this feature is for

The temple pays its vendors outside this system — by bank transfer, UPI, cheque or cash — and records
it here. **The application never moves money out of the temple's accounts**, deliberately: outbound
payment is a different class of risk. What it does is keep payables tracked to zero so the books stay
clean.

## How it is supposed to work

- Only a **Temple Admin** can record payments; kitchen staff cannot.
- A payment carries the date, amount, method (bank transfer, UPI, cheque, cash), a reference and a note.
- **Partial payments are supported**; the invoice tracks what has been paid and only flips to **Paid**
  when the full amount is covered.
- Paying more than is outstanding is refused.
- Payments are audited, and corrections are made by recording a further entry, never by editing.
- The payables view ages what is outstanding — current, 1–30 days, 31+ days overdue.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/money** (menu: **Payments**)
- You need the two pending invoices from UAT-045 (₹1,980 and ₹2,400).

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Payments** | A payables list: Invoice, Vendor, Outstanding, Aging, and a **Record payment** action per row, with a **Total outstanding** figure |
| 2 | Check the total | It equals the sum of the unpaid invoices (₹4,380) |
| 3 | Check the **Aging** column | Each invoice sits in the right bucket for its due date — current, 1–30 days, or 31+ |
| 4 | On the ₹1,980 invoice, press **Record payment**, enter date today, amount `1000`, method **UPI**, reference `UPI-778812` | Accepted |
| 5 | Look at that invoice | Still **Pending**, with ₹980 outstanding |
| 6 | Check **Total outstanding** | Now ₹3,380 |
| 7 | Record a second payment of `980`, method **Bank transfer**, reference `NEFT-99120` | The invoice flips to **Paid** and leaves the outstanding list |
| 8 | Open **/invoices** | That invoice reads **Paid**, and both payments are visible in its history |
| 9 | On the ₹2,400 invoice, try to record a payment of `3000` | Refused: *That payment is more than the invoice's outstanding balance* (`KMS-4939`) |
| 10 | Pay it in full (`2400`, method **Cash**) | It flips to **Paid** |
| 11 | Try to record another payment against it | Refused: *This invoice is already fully paid* (`KMS-4940`) |
| 12 | Look for a way to edit or delete a recorded payment | There should be none — corrections are further entries |
| 13 | Go to **/audit** | The payments appear, naming you, with amounts and methods |
| 14 | Sign out; sign in as `ikms.kitchen-staff.1@trading4good.org` and type **/money** | *Not your page* |
| 15 | Return as admin | *Nothing outstanding — all vendor invoices are paid* |

## It passes if

- [ ] Payables list the unpaid invoices with correct outstanding amounts, aging and total.
- [ ] A partial payment reduces the outstanding balance without marking the invoice paid.
- [ ] Full payment flips the invoice to **Paid**.
- [ ] Overpayment (`KMS-4939`) and paying an already-paid invoice (`KMS-4940`) are refused.
- [ ] Payments cannot be edited or deleted.
- [ ] Every payment is in the audit log.
- [ ] Kitchen staff cannot open the payments screen.

## Watch out for

- Arithmetic: check the outstanding figure after every payment, by hand.
- The aging buckets being computed from the wrong date (invoice date rather than due date). Set a due date 45 days in the past and check it lands in 31+.
- A payment recorded against the wrong invoice — check the row you pressed against the invoice number shown in the dialog.
- Any suggestion anywhere on this screen that the app is *making* the payment. It records; it does not pay. If the wording implies otherwise, note it as Minor — it matters.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT046-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
