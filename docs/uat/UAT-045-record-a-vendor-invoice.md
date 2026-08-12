# UAT-045: Record a vendor's bill

| | |
|---|---|
| **Feature area** | Ordering — vendor invoice capture |
| **Technical stories** | E5-S8 (vendor invoice capture) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-044 (a received order) |
| **Environment needs** | None |

## What this feature is for

When the goods arrive, so does the bill. Recording it turns a pile of paper into a queue of what the
temple owes and when — which is what the payments screen (UAT-046) works from.

## How it is supposed to work

- An invoice is recorded against its **purchase order**, or marked **direct** where there was no order
  (a cash market purchase), in which case it needs a description of what was bought.
- It carries the vendor, invoice number, date, amount, due date, and a reference to the scanned copy.
- It starts as **Pending** and stays so until payment is recorded.
- Where expected prices exist, a difference between the bill and what was expected is shown as
  information — it does not block anything, because temples negotiate in the real world.
- A duplicate invoice number for the same vendor is a soft warning, not a refusal — vendors reuse
  numbering imperfectly.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/invoices** (menu: **Invoices**)
- Have the **purchase order identifier** of the Sri Balaji order from UAT-044 to hand (copy it from that
  order's web address).

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Invoices** | *No invoices*, with a **Record an invoice** action |
| 2 | Press **Record an invoice** | A form: a **Direct (no purchase order)** tick, Vendor, Description, Purchase order id, Invoice number, Amount, Invoice date, Due date, Scan reference |
| 3 | Record an invoice against the Sri Balaji order: vendor **Sri Balaji Provisions**, purchase order id pasted, invoice number `SBP/2026/114`, amount `1980`, invoice date today, due date in 15 days, scan reference `drive-link-114` | *Invoice recorded.* and a row in the list |
| 4 | Read the row | Invoice number, what it is against, amount, due date, status **Pending** |
| 5 | Record a **direct** invoice: tick *Direct*, vendor **Nandini Dairy Agency**, no purchase order, amount `2400`, invoice number `NDA/88`, due in 7 days — but leave **Description** blank | Refused: *A direct invoice with no purchase order needs a description* (`KMS-4923`) |
| 6 | Add the description `Cash market vegetables and curd` and record it | Accepted, and clearly marked **Direct** in the list |
| 7 | Record another invoice for Sri Balaji reusing invoice number `SBP/2026/114` | Recorded, **with a warning** that another invoice already uses that number for this vendor — a warning, not a refusal |
| 8 | Set an invoice's due date to a date in the past | It appears with an **Overdue** badge |
| 9 | Use the filters: **All**, **Pending**, **Paid**, **Overdue only** | Each shows the right subset |
| 10 | Where the purchase order had expected prices, compare the invoice amount to what was expected | A variance is shown for information; nothing is blocked |
| 11 | Sign out; sign in as the **temple admin** and open **/money** (Payments) | The pending invoices appear in the payables queue (that is UAT-046) |

## It passes if

- [ ] An invoice can be recorded against a purchase order, and directly with a description.
- [ ] A direct invoice with no description is refused (`KMS-4923`).
- [ ] A duplicate invoice number warns but is allowed.
- [ ] Invoices start **Pending** and show an **Overdue** badge past their due date.
- [ ] Filters by status and overdue work.
- [ ] Recorded invoices appear in the payments queue.

## Watch out for

- Having to paste a **purchase order identifier by hand**. There is no picker on this form — record how that felt as a user, because a staff member with twenty orders open will get it wrong. It is a usability finding worth logging.
- An invoice recorded against another temple's order (it should be impossible — see UAT-006).
- Amounts losing their paise, or being stored in the wrong currency.
- The scan reference being a free-text field rather than an upload. Note whether that is workable for the temple.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT045-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
