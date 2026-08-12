# UAT-041: The purchase-order sheet — print and PDF

| | |
|---|---|
| **Feature area** | Ordering — purchase order document |
| **Technical stories** | E5-S4 (PO document: PDF and print) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-040 |
| **Environment needs** | **Background worker on** and a **real document renderer**. With the stub renderer the file downloads but contains a placeholder |

## What this feature is for

The vendor needs a sheet: what the temple wants, how much, by when, and from whom. It goes out on
WhatsApp, by email, or on paper handed over a counter. It has to be unambiguous, because a
misunderstanding here becomes a wrong delivery.

## How it is supposed to work

- A sheet is produced when the order is sent, and on demand at any time.
- It carries the temple's identity, the order number and date, the vendor's block (including GSTIN when
  the temple recorded one), the lines, the needed-by date, notes, and space for a signature.
- **Prices are optional.** Temples often order without agreeing a price in advance, so the price column
  appears only if any line has one.
- A browser print view gives the same sheet immediately on A4.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/orders** → open the **Sent** Sri Balaji order from UAT-040
- **Confirm with the environment owner** that the worker and the real renderer are on. If not, run steps
  1–2 and mark the rest *blocked by environment*.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | On the order, find the document actions | **Print**, **Generate PDF**, a language choice, and a **Documents** section |
| 2 | Look at **Documents** | Either a sheet already generated when the order was sent, or *No sheet generated yet. It is created when the order is sent.* |
| 3 | Press **Generate PDF** | It works, then a sheet is available to **Download** |
| 4 | Open the downloaded sheet | Temple name; order number and date; vendor block with name, contact and phone; the ingredient lines with quantities and units; the needed-by date; a signature space |
| 5 | Check the vendor block | The GSTIN you recorded in UAT-037 appears |
| 6 | Check the price column | Since your lines have no expected prices, **there is no price column at all** |
| 7 | Go back, add an expected price to one line on a **draft** order, send it, and generate its sheet | Now a price column appears |
| 8 | Press **Print** | A clean A4 print view opens — no menu, no buttons, nothing cut off |
| 9 | Create an order with **twenty** lines and generate its sheet | The table breaks across pages cleanly; the header repeats or the continuation is obvious |
| 10 | Generate the sheet a second time for the same order | It works, and the **Documents** section keeps both, with the latest clearly identifiable |
| 11 | Compare the sheet against the order on screen, line by line | Identical quantities and units |

## It passes if

- [ ] A sheet can be generated and downloaded, and one exists after the order is sent.
- [ ] It carries temple identity, order number, vendor block with GSTIN, lines, needed-by date and signature space.
- [ ] The price column appears only when prices exist.
- [ ] The print view is clean A4.
- [ ] A long order paginates cleanly.
- [ ] Regenerating keeps earlier versions and marks the latest.

## Watch out for

- Environment first: a stub renderer produces a placeholder file. Record that as *environment* (root cause R5), not as a document defect.
- A sheet that does not match the order — a stale version generated before an edit. Check the quantities.
- A vendor block missing the phone number: it is the number the order will be sent to.
- The temple's own name or address missing. The vendor needs to know who is ordering.
- If generation fails, note the code — `KMS-5203` means the document could not be produced.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT041-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
