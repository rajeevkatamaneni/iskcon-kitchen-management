# VERIFY2-D: invoices and payments, round 2 (§8 R-INV-1..8, §9 R-PAY-1..4, R-VEN-3/4 via invoices)

Verifier: fresh agent, did not build or verify round 1. 2026-09-19, local stack (web :3000 `next dev`,
API :8080, database `kms_verify`). Own headless Playwright Chromium 1134, never Rajeev's Chrome.
Sessions made by minting Firebase custom tokens (no password sign-in). Roles driven, UI and API:

- Temple Admin: `ikms.temple-admin.1` (Karuna Murti Das)
- Kitchen Manager: `ikms.kitchen-staff.5` (Madhava Das, role KITCHEN_MANAGER in `kms_verify`)
- Kitchen Staff: `ikms.kitchen-staff.1` (Gopal Das)
- Volunteer, no permission: `ikms.volunteer.1`

Scratchpad (scripts, JSON, screenshots), called `S` below:
`/private/tmp/claude-501/-Users-Rajeev-Workspace-kitchen-management-system/79bf5e51-4f9e-420c-b419-747d070a92df/scratchpad/verify2-d/`

**Result: 14 PASS, 0 FAIL.** Round-1 defects D1, D2, D3 and D5 are fixed, and D4 is now allowed by R-INV-7.
There are two new layout defects (N1, N2), and neither blocks an AC.

## Test data (all named VERIFY2-D, made through the API or the screens)

- Vendor **VERIFY2-D Traders**. Ingredients VERIFY2-D Tomato, Curry leaves, Coconut (pieces), Green chilli,
  Rice and Atta. Atta has the pack Bag = 25 Kg, and Tomato gained Crate = 20 Kg from the invoice form.
- Orders:
  - PO-2026-0057: the mock's four lines, delivered 9 / 3 / 10 / 1.5.
  - PO-2026-0058: rice 50 Kg, delivered as 45 Kg then 5 Kg.
  - PO-2026-0059: Atta 4 × Bag.
  - PO-2026-0060: rice 2,000 Kg.
  - PO-2026-0067: the mock's four lines again, **left unbilled** for the form comparisons.
  - PO-2026-0070: rice, two equal 5 Kg deliveries, **left unbilled**.
- Invoices:
  - VERIFY2-D-0917: PO-0057, ₹1,030, paid ₹300 UPI + ₹730 cash.
  - VERIFY2-D-INV-B: 0058 45 Kg billed as 50 Kg, ₹3,000, bill date 15 Sept.
  - VERIFY2-D-INV-C: Atta, ₹6,000.
  - VERIFY2-D-INV-E: rice, ₹1,24,000, paid ₹1,00,000 + ₹1,000.
  - VERIFY2-D-OLD-31: direct, bill date 25 July.
  - VERIFY2-D-KM-1: made by the Kitchen Manager, 0058 5 Kg, ₹320.
  - VERIFY2-D-KS-1: made by Kitchen Staff, direct, ₹70.

## Results per AC

**R-INV-1 PASS.** The list button reads "Create an invoice" for Temple Admin, Kitchen Manager and Kitchen
Staff. Pressing it (as Kitchen Manager) goes straight to `/invoices/new` with the form open.

**R-INV-2 PASS.**
- The upload accepts image and PDF with no `capture` attribute, as the mock has it.
- Hint, exactly: "Upload a copy of the bill — a photo, PDF or scan."
- Save with no file: stays on `/invoices/new`, the top notice lists "Upload a copy of the bill.", and the
  field says "Copy of the bill is required".
- A `.txt` is refused on screen: "That file isn’t a photo or a PDF." / "Upload a photo (JPG, PNG, WebP or
  HEIC) or a PDF." / "If you need help, quote KMS-400165".
- PNG (VERIFY2-D-0917, KM-1) and PDF (INV-B, C, E, KS-1) both saved.
- The bill comes back from `GET /vendor-invoices/{id}/bill` as `image/png` (1,189 bytes), so it is
  stored as a file.

**R-INV-3 PASS.**
- After choosing the vendor there is one tick per unbilled delivery, sorted by order then day:
  - "PO-2026-0057 · delivered 19 Sept · received by Karuna Murti Das · 4 items"
  - "… PO-2026-0058 … · 45 Kg", then "… · 5 Kg"
  - "… PO-2026-0059 … · 4 × Bag (25 Kg)"
  - "… PO-2026-0060 … · 2,000 Kg"
- Two equal deliveries read "… · 5 Kg · 1st that day" and "… · 5 Kg · 2nd that day".
- Billed deliveries drop off the list: after five invoices only the 0058 5 Kg delivery was left
  (`billable-deliveries` returns 1).
- Delivery lines are locked: there is no remove button and no "Add an item" row in delivery mode.
- Direct invoices:
  - Tick Direct and type "VERIFY2-D Curry": the combobox offers "VERIFY2-D Curry leaves · Kg". Choosing
    it adds the line and puts the focus on "VERIFY2-D Curry leaves billed quantity".
  - An unknown name offers "Add ‘VERIFY2-D banana leaves’ as a one-off item", and the focus goes to its
    billed quantity. Direct lines have remove buttons (2).

**R-INV-4 PASS.**
- Columns: Item · Ordered · Delivered · Billed qty · Amount (₹) · Rate.
- Billed qty defaults to the delivered amount (Rice 45 for 45 Kg, 2000 for 2,000 Kg; Atta 4 bags).
- Rice billed 50 against 45 delivered shows "Billed 50 Kg, 45 Kg delivered" in amber, rgb(128,93,48).
  The chilli line shows "Billed 2 Kg, 1.5 Kg delivered" in the same colour.
- The rate is read-only: "₹32 / Kg", "₹35 / piece", "₹62 / Kg".
- Pack line: "4 × Bag (25 Kg)" with "100 Kg" under it, ₹6,000, rate "₹1,500 / bag · ₹60 / Kg".
- New unit on the spot:
  - "Add a pack size…" opens the group "New pack size for VERIFY2-D Tomato". Crate 20 Kg was saved and
    chosen, and 9 Kg became 0.45 crates.
  - 2 crates for ₹1,200 gives "₹600 / crate · ₹30 / Kg" and the amber line "Billed 40 Kg, 9 Kg delivered".

**R-INV-5 PASS.**
- The totals block is 384px wide, right-aligned: x 864–1248 at 1280, 608–992 at 1024, and 16–374 at 390.
- Sub total, GST, Other charges, the note box, Discount and Grand total all end at one right edge:
  x=1212 (1280), 956 (1024), 338 (390). Each has 12px right padding and `tabular-nums`.
- A matching total shows a tick with `role=img`, the name "Adds up to the grand total", colour
  rgb(56,105,68), and no sentence.
- Its tooltip "Adds up to the grand total" measured on hover and on keyboard focus (Tab from Grand total):
  - form at 1280: x 1094–1272
  - form at 390: x 204–382
  - invoice page at 1280: 1094–1272
  - invoice page at 390: 204–382

  All are inside the viewport.
- A wrong total (150 against 155) shows the red line "Adds up to ₹155, not ₹150", rgb(131,74,67). The
  tick goes, `aria-invalid=true`, and Save stays on the form with "The sub total, GST, other charges
  and discount don’t add up to the grand total on the bill."
- Other charges has its note box, "What the other charges are for"; the saved note "Delivery charge"
  shows under it on the invoice page.

**R-INV-6 / R-VEN-4 PASS.**
- After VERIFY2-D-0917, the list prices are: Tomato ₹32, Curry leaves ₹60, Coconut ₹35, Chilli ₹70.
- Each has a `vendor_price_history` row with source INVOICE, effective 17 Sept, and the market rate is
  set with source INVOICE.
- Atta: `last_price` 60 and `price_per_pack` 1500, with a history row of 60 / 1500.
- Rice: ₹60 (bill 15 Sept), then ₹62 (18 Sept), then ₹64 (19 Sept, the Kitchen Manager's invoice).
- The late bill VERIFY2-D-OLD-31 (25 July, curry leaves ₹55) went into history but did **not** overwrite
  ₹60 (`vendor_supplies.last_price` = 60).

**R-VEN-3 via invoices PASS.** The vendor page arrows all have `data-direction=up` and colour rgb(131,74,67):

- Rice ₹64, "Up from ₹62 on 18 Sept"
- Tomato ₹35, "Up from ₹32 on 17 Sept"
- Curry leaves ₹60, "Up from ₹55 on 25 Jul": the late bill, in bill-date order, as round 1 noted

**R-INV-7 PASS.**
- The invoice page has, in order: The invoice (summary), Items, the totals with the tick, "Invoiced vs
  received" (Items billed ₹958, Delivered at the order’s prices ₹923, "₹35 more than received", and the
  amber chilli line), and Payments.
- The difference is worked out from the lines: ₹958 − ₹923.
- Pressing the bill thumbnail opens a dialog "VERIFY-D-bill.png" with the image, full viewport at 1280
  and 390.
- The extras on the page are all allowed by R-INV-7 / Q-19, so they aren't flagged: Record a credit
  note, Void this bill, Reverse, "Delivery billed" and "Billed qty".
- Two ordinary edge cases:
  - A direct invoice has no "Invoiced vs received" card, since it has no delivery to compare.
  - The Atta invoice shows "—" for delivered and difference, because its order had no price (the
    conductor's ruling).

**R-INV-8 PASS.**
- Temple Admin sees the filters All · Unpaid · Due this week · 1–30 days overdue · 31+ days overdue ·
  Paid · Voided, and Total owed.
- The filter counts match what I worked out myself from `/api/v1/payables` (today 19 Sept, "this week" = due
  within 7 days):

  | Filter | Rows |
  |---|---|
  | Unpaid | 11 |
  | Due this week | 2 |
  | 1–30 days overdue | 1 |
  | 31+ days overdue | 2 |
  | Paid | 5 |
  | Voided | 3 |

- Total owed was ₹1,29,280 (the sum of `outstanding`) on every filter. After the next ₹1,000 payment and
  the ₹320 and ₹70 invoices it read ₹1,28,670, which is exactly right.
- Kitchen Manager and Kitchen Staff see All · Unpaid · Overdue · Paid · Voided and no Total owed. Their
  Unpaid is the same 11 rows (F9 holds). Their `?owed=true` API call returns 11.

**R-PAY-1 PASS.**
- Temple Admin: "Pay this invoice" opens the form inline with the cursor in Amount. The amount defaults
  to what's left: 1030, then 730 after ₹300 on VERIFY2-D-0917; 124000, then 24000 after ₹1,00,000 on
  INV-E.
- The date defaults to today (2026-09-19). Methods: UPI · Bank transfer · Cheque · Cash.
- The button is gone once the invoice is paid (0 on the paid invoice).
- Kitchen Manager and Kitchen Staff: 0 Pay buttons and no Payments card. Every one of their API calls
  below gets **403 KMS-400021**: pay, list payments, reverse, payables, void, credit, and upload payment
  proof.
- They can list and view invoices, see billable deliveries, fetch the bill, upload a bill and create an
  invoice, all 200/201.
- Volunteer: every invoice and payment endpoint above gets 403 KMS-400021. `/invoices`, `/invoices/{id}`
  and `/invoices/new` show "Not your page", and the menu has no Invoices.
- No refusal gave a 500.

**R-PAY-2 PASS.**
- UPI with no proof: "Proof of payment is required". The API returns 400 with `proofAttachmentId`
  "Proof of payment is required." for UPI, BANK_TRANSFER and CHEQUE.
- Hint, exactly: "Upload proof of payment — a receipt, UPI screenshot or bank confirmation."
- Cash swaps Reference for Received by (placeholder "Their full name"), and one upload for two:
  - "Signed note", hint "A note signed by the person who received the cash, e.g. ‘Received ₹1,030 in cash
    from ISKCON South Bengaluru’"
  - "Photo of the person who took the cash", hint "Their ID card, or a photo of them, so we can recognise
    who took the money."
- Cash with all three empty gives three "… is required" messages. The API refuses a name alone with
  `signedNoteAttachmentId` and `receiverPhotoAttachmentId`.
- Switching back to UPI brings back 1 file input and Reference. There is no ID-type field.
- **T-280 (payment amount) fixed:**
  - API: 0, −10 and −0.01, with and without proof, all get 400 **KMS-400174** "A payment has to be more
    than ₹0." / "Enter the amount paid. To undo a payment recorded by mistake, press Reverse beside it."
  - Screen: 0 and −10 show "Amount (₹) must be more than 0".
  - 2000 against ₹1,030 owed shows "That’s more than the ₹1,030 still owed."

**R-PAY-3 PASS.**
- Columns: Paid on · Amount · Method · Reference · **Paid by** · Proof, plus Actions (Reverse, allowed).
- Paid by: Karuna Murti Das. The UPI row has 1 proof thumbnail and the cash row has 2. The cash
  Reference cell reads "Received by VERIFY2-D Manjunath K.".

**R-PAY-4 PASS.**
- The menu has Invoices and no Payments: desktop for all three staff roles, and the phone drawer at 390.
- `GET /money` returns 307 to `/invoices?filter=unpaid`, and the browser lands there for every role.
- There are 0 `a[href*='/money']` links on the page.
- A grep of `frontend/app`, `components`, `lib` and `backend/src/main` finds `/money` only in comments
  and in the redirect page itself.

## Indian digit grouping (T-279), measured on screen

₹1,24,000 appears as the grand total, sub total, line amount, still owed and list amount. Other amounts
seen:

- "Payment of ₹1,00,000 recorded. ₹24,000 is still owed on this invoice."
- Paid to date ₹1,01,000
- Total owed ₹1,29,280, then ₹1,28,670
- The list row ₹87,600
- The delivery tick "· 2,000 Kg"

No figure over ₹99,999 appeared without lakh grouping. The pay form's Amount box holds the raw number
(124000), which is a number input and matches the mock.

## Round-1 defects

- **D1 fixed.** Each tick label ends with what came ("· 45 Kg", "· 5 Kg", "· 4 items"). Equal deliveries
  get "· 1st that day" / "· 2nd that day". The list is sorted by order, then day.
- **D2 fixed.** At 390, Billed qty and Amount sit side by side (171px each, both 68px tall) and Rate
  is on its own row, as in the mock. Card heights are 245 / 245 / 245 / 261 for both real and mock, and
  so are the table's header widths.
- **D3 fixed.** At 1280, Vendor (355px), Invoice number, Invoice date and Due date (178px each) share one
  row at y=169. The deliveries take the full 936px width below. There is nothing beside a growing
  column, and the 460 × 176 gap is gone.

  One side effect: the 178px number box shows 21 characters ("INV/2026-27/SB/000917" fits), and a
  23-character number scrolls inside the box. I note it, but don't call it a defect.
- **D4 resolved.** R-INV-7 now names these as allowed differences: credit note, void, reverse, Delivery
  billed and Billed qty.
- **D5 fixed.** KMS-400165 now reads "That file isn’t a photo or a PDF." with a curly apostrophe. The older
  shared messages are still straight, for example KMS-400001 "isn't" (seen on the proof refusal) and
  KMS-400021. They date from before this work.

## Layout at 1280, 1024 and 390 (DOM, Temple Admin unless stated)

No horizontal scroll (`scrollWidth` = `clientWidth`) at any of the three widths on:

- the create form
- the invoices list (Temple Admin and Kitchen Staff)
- the pay form, UPI and cash
- invoice pages VERIFY2-D-0917, INV-B and KS-1

The one exception is N1. No input or select is clipped, apart from the 23-character number noted above.
On the phone, the pay form's Cancel and Record payment sit side by side at the same size (131 × 54),
the same as the mock (114 × 54, where the label also wraps).

## Mock comparison (pixel diff)

Method:
- Copied `docs/work/mocks/dev-invoices.page.tsx` to `frontend/app/dev-invoices/page.tsx`.
- Took element screenshots at 1280, 1024 and 390, plus the mock at 1346, where its demo card is
  936px wide like the real content at 1280.
- Compared them with pixelmatch, threshold 0.1, over the overlapping area.
- Item names differ in every row ("VERIFY2-D Tomato" against "Tomato, ripe"), so no row can come out 0.

In the table, the size is width × height in pixels.

| Part | Real at 1280 against mock at 1346 (same width) | Pixels different | Same geometry? |
|---|---|---|---|
| A items table | 936×337 both | 4,691 (1.49%) | rows 69/69/69/85 both; headers 217/115/122/230/155/97 against 224/122/129/202/162/97 (Billed qty wider for the unit dropdown, allowed) |
| A totals | 384×389 against 384×309 | 1,114 (0.94%), all at y 202–302 | identical above the note box; +80px = the note box (R-INV-5, allowed) |
| A upload, empty | 936×78 both | 1,316 (1.8%) | same height |
| D summary | 936×250 against 190 | 6,267 (3.52%) | +60px = "Delivery billed" (allowed) |
| D items | 936×536 against 508 | 3,650 (0.77%) | rows 51/51/51/71 both; headers 263/145/144/146/130/106 against 263/145/145/145/130/106; +28px = the "Delivery charge" note line under Other charges (allowed) |
| D invoiced vs received | 936×206 both | 1,531 (0.79%) | same |
| D payments | 936×359 both | 7,692 (2.29%) | rows 69/69 both; the extra Actions column (allowed) |

At the same viewport widths (the mock's content is narrower, so these compare layout rather than
pixels):

- **1024:** A table rows are 69/85/69/117 against the mock's 97/97/97/97. The real gives Billed qty
  195px for the unit dropdown, and item names wrap between words in a 103px Item column. The real table
  is 48px shorter. D payments is 427 against 359 because my cash reference "Received by VERIFY2-D
  Manjunath K." wraps to 4 lines in a 105px column beside the extra Actions column.
- **390:**
  - The A table matches exactly: 996 tall, rows 245/245/245/261, same header widths.
  - The upload box is 142 against 198, because the mock has a "Use the sample bill" button that exists
    only in the mock.
  - The D cards on VERIFY2-D-0917 are 365 wide instead of 358: see N1.

Diff images: `S/shots/cmp/diff-*.png`. Real and mock crops: `S/shots/cmp/{real,mock}-<width>-<part>.png`.

**Clean-up:** `frontend/app/dev-invoices/` and `frontend/.next/types/app/dev-invoices` were deleted. `ls`
of each returns "No such file or directory", `ls frontend/app | grep -c dev-` returns 0, and
`git status` shows no dev-invoices path.

## New defects

**N1. Phone: a long payment reference or cash receiver's name pushes the invoice page off the screen.**
Temple Admin, 390 wide.

To reproduce:
1. Open an invoice with a payment whose Reference is 34 characters, for example
   `/invoices/fa762d51-fc96-4894-ac2a-4c855eb9ad9d` (INV-E, reference "VERIFY2-D UTR SBIN0226019876543210").
2. Scroll to Payments.

What I measured:
- The Reference cell never wraps in the phone card layout. Its class is `kms-fixed`, and its
  min-content is 315px.
- That widens the payments card, and because the page is a grid, every card on the page with it.
- On INV-E: `scrollWidth` 400 against 390, every card's right edge at x=400, and the page scrolls
  sideways by 10px.
- On VERIFY2-D-0917 (cash, "Received by VERIFY2-D Manjunath K."): the cards are 365 wide and end at
  x=381, so the right gutter is 9px instead of 16px.

A realistic UTR reference or a full name such as "Received by Manjunath Krishnamurthy Gowda" triggers
it. The mock uses the same class but only had a short reference.

Expected: the reference wraps between words inside the card, and the page stays 390 wide.

Screenshot: `S/shots/D-390-E-long-ref.png`, which is 400px wide.

**N2 (low). Invoices list at 1024: PO numbers break over three lines at their hyphens.** Temple Admin or
Kitchen Staff, `/invoices`, 1024 wide.

What I measured:
- The Against column is 85px, and "PO-2026-0026" reads "PO-" / "2026-" / "0026" in every PO row.
- Column widths: Invoice 160 (128 used), Vendor 113, Against 85, Amount 125, Due 110, Status 85.
- T-278's rule says words never break and only emails and URLs break at `- . @`. A PO number is one
  identifier.

Expected: the PO number on one line, wrapped whole if it has to be.

Screenshot: `S/shots/list-ta-1024.png`.

Two things I noticed but am not calling defects:
- At 1280 on the same list, vendor names wrap to 3 lines ("Kalasipalya / Vegetable / Mandi") in a 107px
  Vendor column. Beside it, Against is 223px, but it needs 199px on rows that carry a Variance badge.
  This is the fitter keeping short values on one line: the Q-18 lesser evil, and no word is broken.
- At 1024 on the invoice page, the Payments table wraps Reference to 4 lines and "Karuna Murti Das" to 2.
  That comes from 7 columns in 680px with my long test text.

## Known open items, not counted as defects

Q-22 (first count needs Temple Admin), the preparation-word list and merge (on hold), and the PO WhatsApp
wording "raised" (approved by Meta). Uploads that are never used are still never cleaned up. The Kitchen
Manager and Kitchen Staff bill-upload probes above each left one unclaimed upload.

## Not verified

- A real phone's camera sheet. Only `accept` and the absence of `capture` were checked.
- A PDF opening in the bill viewer. Only a PNG was opened on the invoice page; PDFs were uploaded and saved.
- Rajeev's own Chrome or screen size. All measurements come from headless Chromium at deviceScaleFactor 1.
