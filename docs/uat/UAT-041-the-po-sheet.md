# UAT-041: The purchase-order sheet — print and PDF

| | |
|---|---|
| **Feature area** | Ordering — purchase order document |
| **Technical stories** | E5-S4 (PO document: PDF and print); procurement release 2026-09-19: R-SL-1 and R-SL-3 on the sheet (T-260, T-268, T-312) |
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
- Lines read the way the screens do: packs as **4 × Bag (25 Kg)**, prices per pack and per stock unit,
  nothing of 1,000 gm or more in gm, rupees grouped the Indian way, dates like **20 Sept 2026**.
- **Prices are optional.** Temples often order without agreeing a price in advance, so the price column
  appears only if any line has one.
- A browser print view gives the same sheet immediately on A4.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/orders** → open the **Sent** Sri Balaji order from UAT-040, and later **order A** (UAT-039)
- **Confirm with the environment owner** that the worker and the real renderer are on. If not, run steps
  1–2 and 8 (the print view does not need the renderer) and mark the rest *blocked by environment*.
  **The real PDF can only be checked on staging.**

## Steps

*Amended 2026-09-19 for the procurement release.* The order page no longer has a **Documents** section:
**Generate PDF** makes the sheet and downloads it. The sheet now writes packs, rates and amounts the way
the screens do.

| # | Do this | You should see |
|---|---|---|
| 1 | On the order, find the document actions | A document language list, **Generate PDF** and **Print** |
| 2 | Press **Generate PDF** | *Preparing PDF…*, then the sheet downloads |
| 3 | Open the downloaded sheet | Temple name; order number and date; vendor block with name, contact and phone; the lines with quantities and units; the needed-by date; a signature space |
| 4 | Check the vendor block | The GSTIN you recorded in UAT-037 appears |
| 5 | Check the price column on an order with **no** expected prices | **There is no price column at all** |
| 6 | Generate the sheet for **order A** (UAT-039: UAT Bulk Rice sold as Bag = 25 Kg at ₹1,450) | The line reads **UAT Bulk Rice 4 × Bag (25 Kg)** and the price **₹1,450 / bag · ₹58 / Kg**; the total **₹5,800**, grouped the Indian way. An unnamed pack reads like **1 × 500 gm**, and its price like **₹250 / 500 gm · ₹500 / Kg** |
| 7 | Read every quantity and date on it | No figure of **1,000 gm or ml or more** — those read in Kg or L. Dates read like **20 Sept 2026**, never *20 Sep 2026* or *2026-09-20* |
| 8 | Press **Print** | A clean A4 print view opens — no menu, no buttons, nothing cut off — with the same lines, packs and prices |
| 9 | Create an order with **twenty** lines and generate its sheet | The table breaks across pages cleanly; the header repeats or the continuation is obvious |
| 10 | Compare the sheet against the order on screen, line by line | Identical quantities, packs and units |

## It passes if

- [ ] A sheet can be generated and downloaded.
- [ ] It carries temple identity, order number, vendor block with GSTIN, lines, needed-by date and signature space.
- [ ] The price column appears only when prices exist.
- [ ] A pack line reads **4 × Bag (25 Kg)** with **₹1,450 / bag · ₹58 / Kg**; no quantity of 1,000 gm/ml or more is written in gm/ml; money is grouped the Indian way; dates read like 20 Sept 2026.
- [ ] The print view is clean A4.
- [ ] A long order paginates cleanly.

## Watch out for

- Environment first: a stub renderer produces a placeholder file. Record that as *environment* (root cause R5), not as a document defect.
- A sheet that does not match the order — a stale version generated before an edit. Check the quantities.
- A vendor block missing the phone number: it is the number the order will be sent to.
- The temple's own name or address missing. The vendor needs to know who is ordering.
- If generation fails, note the code — `KMS-500004` means the document could not be produced.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT041-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
