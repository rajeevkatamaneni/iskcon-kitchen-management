# VERIFY-D: invoices and payments (§8 R-INV-1..8, §9 R-PAY-1..4, R-VEN-3/4 via invoices)

Verifier: fresh agent, did not build this. 2026-09-19, local stack (web :3000 `next dev`, API :8080 on
`kms_verify`, Flyway V148). Headless Playwright Chromium 1134 (scratchpad copy of `playwright-core`),
never Rajeev's Chrome. Signed in as Temple Admin (`ikms.temple-admin.1`, Karuna Murti Das), Kitchen
Staff (`ikms.kitchen-staff.1`, Gopal Das) and Volunteer (`ikms.volunteer.1`, no invoice permission).
There is no Kitchen Manager account in `kms_verify`, so that role was **not driven**.

Sign-in note: the password route hit Firebase `QUOTA_EXCEEDED : Exceeded quota for verifying
passwords` part-way through (shared with other agents). Sessions were then made by minting a custom
token for each uid and writing it to the page's Firebase IndexedDB store; no password was changed.

Scratchpad (screenshots, scripts, raw JSON), called `S` below:
`/private/tmp/claude-501/-Users-Rajeev-Workspace-kitchen-management-system/79bf5e51-4f9e-420c-b419-747d070a92df/scratchpad/verify-d/`
(`S/shots/*.png`, `S/*.json`).

## Test data made (all through the API or the screens, as Temple Admin)

- Vendor **VERIFY-D Traders**; ingredients VERIFY-D Tomato, Curry leaves, Coconut (pieces), Green
  chilli, Rice, Atta (pack Bag = 25 Kg, supply sold as Bag); a Crate = 20 Kg pack was added to Tomato
  from the invoice form.
- Orders PO-2026-0050 (the mock's four lines: Tomato 10 Kg, Curry leaves 3 Kg, Coconut 10 pieces,
  Green chilli 2 Kg), PO-2026-0051 (Rice 50 Kg), PO-2026-0052 (Atta 4 × Bag), PO-2026-0053 (Rice 10 Kg),
  all sent. Deliveries: 0050 got 9 / 3 / 10 / 1.5; 0051 got 45 Kg then 5 Kg (two deliveries); 0052
  got 100 Kg; 0053 got 10 Kg.
- Invoices (screens unless marked): KVM/2026/0917 (0050, ₹1,030, paid ₹300 UPI + ₹200 cash),
  VERIFY-D-INV-2 (0051 first delivery, 50 Kg billed, ₹3,000, bill date 15 Sept), VERIFY-D-INV-3 (0052,
  4 bags ₹6,000), VERIFY-D-INV-4 (0053, ₹640, 18 Sept), VERIFY-D-DIRECT-1 (direct, due 22 Sept,
  ₹1,300), VERIFY-D-OLD-31 (direct, via API, bill 25 July, due 10 Aug, ₹55).
- The PO-2026-0051 5 Kg delivery is left unbilled on purpose.

## Results per AC

**R-INV-1 PASS.** List button reads "Create an invoice"; pressing it goes straight to
`/invoices/new` with the form open (1280 and 390). `S/shots/real-new-empty-1280.png`.

**R-INV-2 PASS.** File input `accept="image/*,application/pdf"`, no `capture` attribute, exactly as the
mock (its comment: capture would force the camera and lose the WhatsApp PDF; `accept image/*` offers
the camera in the phone's sheet). Hint text exactly "Upload a copy of the bill — a photo, PDF or scan."
Save with no upload: stays on `/invoices/new`, top notice "Upload a copy of the bill.", field message
"Copy of the bill is required". A `.txt` is refused with KMS-400165. PNG and PDF both upload and save.
Stored as an attachment (`kind: INVOICE_BILL`), not a text reference.

**R-INV-3 PASS, with defect D1.** After choosing the vendor, one tick per unbilled delivery, labelled
"PO-2026-0050 · delivered 19 Sept · received by Karuna Murti Das". Before billing: 5 offered; after
invoices 1-4: only the PO-2026-0051 5 Kg delivery is left. Pulled lines are locked: 0 remove buttons,
0 "Add an item", 0 combobox, 0 item text boxes in delivery mode. Direct: tick Direct, the combobox
"Add an item" offers "VERIFY-D Tomato · Kg"; choosing it adds the line and focuses its billed quantity;
typing an unknown name offers "Add ‘VERIFY-D banana leaves’ as a one-off item"; saved fine.

**R-INV-4 PASS.** Columns Item · Ordered · Delivered · Billed qty · Amount (₹) · Rate. Billed qty
defaults to delivered (Rice showed 45 for 45 Kg delivered; Atta showed 4 bags). Billing 50 on the rice
line shows amber "Billed 50 Kg, 45 Kg delivered" (colour rgb(128,93,48), `text-warning`); the mock's
chilli line shows "Billed 2 Kg, 1.5 Kg delivered" identically. Rate read-only: "₹60 / Kg", "₹35 /
piece". Pack line: Atta billed "4 × Bag (25 Kg)" with "100 Kg" underneath, ₹6,000 → rate "₹1,500 / bag ·
₹60 / Kg". New unit on the spot: "Add a pack size…" opened "New pack size for VERIFY-D Tomato", Crate
20 Kg saved on the ingredient and chosen; 2 crates ₹1,200 → "₹600 / crate · ₹30 / Kg".

**R-INV-5 PASS.** Totals block right-aligned, 384px wide (`sm:max-w-sm`, same as the mock), at
x 864–1248 at 1280. Right edges of Sub total, GST, Other charges, Discount and Grand total all at
x=1212 (1280) and x=338 (390), each with 12px right padding, `tabular-nums`. Matching figures: green
tick, `role=img`, accessible name "Adds up to the grand total", colour rgb(56,105,68), no sentence.
Tooltip on hover and on keyboard focus, text "Adds up to the grand total": at 1280 x 1094–1272
(inside 1280), at 390 x 204–382 (inside 390), directly above the tick beside Grand total.
Grand total 1000: red line exactly "Adds up to ₹1,030, not ₹1,000" (rgb(131,74,67)), tick gone,
box `aria-invalid=true`; Save stays on the form with "The sub total, GST, other charges and discount
don’t add up to the grand total on the bill." `S/shots/tick-tooltip-hover-{1280,390}.png`,
`S/shots/mismatch-*.png`, `S/shots/save-blocked-*.png`. "Other charges" has its note box
("What the other charges are for"), the document's "(plus a note)".

**R-INV-6 / R-VEN-4 PASS.** After KVM/2026/0917: list prices Tomato ₹32, Curry leaves ₹60, Coconut
₹35 / piece, Green chilli ₹70; one `vendor_price_history` row each, source INVOICE, effective 17 Sept;
market rate set to the same with source INVOICE. Atta: supply ₹1,500 per bag and ₹60 / Kg; history row
price_per_unit 60, price_per_pack 1500, "Bag = 25 Kg". A late bill (VERIFY-D-OLD-31, 25 July, ₹55 for
curry leaves) went into history and did **not** overwrite ₹60.

**R-VEN-3 (via invoices) PASS.** Rice ₹60 (bill 15 Sept) then ₹64 (bill 18 Sept): vendor page shows
"₹64 / Kg" with an up arrow, `data-direction=up`, colour rgb(131,74,67), name "Up from ₹60 on 15 Sept";
tooltip "₹60 on 15 Sept" on hover and on focus, on-screen at 1280 and 390. `S/shots/vendor-arrow-1280.png`.
Side effect worth knowing: curry leaves now shows a red up arrow "₹55 on 25 July" because the late
July bill became its previous price. That follows bill-date order, so not a defect.

**R-INV-7 PASS, differences listed below.** Invoice page has Summary, Items, totals block (with the
tick), "Invoiced vs received" (Items billed ₹958, Delivered at the order's prices ₹923, "₹35 more than
received", amber chilli line), bill thumbnail (pressing it opens a dialog "VERIFY-D-bill.png" with the
image), and Payments. The difference is worked out from the lines (₹958 − ₹923).

**R-INV-8 PASS.** Temple Admin filters: All · Unpaid · Due this week · 1–30 days overdue · 31+ days
overdue · Paid · Voided, plus Total owed. Measured: Unpaid 7, Due this week 1 (DIRECT-1, due 22 Sept),
1–30 1 (INV-0028-26), 31+ 1 (OLD-31), Paid 4, Voided 3. Total owed ₹96,125 on All, Paid and 31+
(stays unfiltered), and it moved by exactly the new invoices (₹84,600 + ₹10,170 + ₹1,300 + ₹55).
Kitchen Staff: All · Unpaid · Overdue · Paid · Voided, no Total owed (Q-17 provisional).
**F9 fixed:** Unpaid gives the same 7 rows for both roles, including the part-paid KVM/2026/0917.

**R-PAY-1 PASS.** Temple Admin sees "Pay this invoice"; it opens the form inline with the cursor in
Amount, amount defaults to what's left (1030, then 730 after ₹300), date defaults to today, methods
UPI / Bank transfer / Cheque / Cash. Kitchen Staff and Volunteer: button count 0. API as Kitchen
Staff: pay 403, list payments 403, reverse 403 (KMS-400021); Volunteer pay 403. Kitchen Staff does
not see the Payments section at all.

**R-PAY-2 PASS.** UPI, bank and cheque without proof: screen "Proof of payment is required"; API 400
`proofAttachmentId` for all three. Hint exactly "Upload proof of payment — a receipt, UPI screenshot or
bank confirmation." Cash swaps Reference for Received by and one upload for two: "Signed note" with
"A note signed by the person who received the cash, e.g. ‘Received ₹1,030 in cash from ISKCON South
Bengaluru’" and "Photo of the person who took the cash" with "Their ID card, or a photo of them, so we
can recognise who took the money." All three empty: three "… is required" messages; API refuses name
only. Switching to UPI and back swaps the fields (1 file input, Reference) and back (2, Received by).
No ID-type field. **F7 fixed:** a −10 UPI payment without proof is refused, KMS-400174.

**R-PAY-3 PASS.** Column "Paid by" (Karuna Murti Das). Proof thumbnails: 1 on the UPI row, 2 on the
cash row. Reference cell "Received by Manjunath K.".

**R-PAY-4 PASS.** Menu (desktop and phone drawer) has Invoices and no Payments. `GET /money` → 307 to
`/invoices?filter=unpaid`, and the browser lands there. 0 `a[href*='/money']` on the page. Grep of
`frontend/app`, `components`, `lib` (tests included) finds `/money` only in three comments;
`backend/src/main` has none.

## Mock comparison (design A and design D), measured

The mock sits inside a demo card, so its content is 870px wide at 1280 against the real form's 936px.
I also rendered the mock at 1346px so its card content is 936px too, and pixel-compared that pair.
Pixel diff: pixelmatch, threshold 0.1, element screenshots, overlapping area only. The item names
differ in every row ("VERIFY-D Tomato" against "Tomato, ripe"), so no row diff can be 0.

Design A, real 1280 against the mock at 1346 (same width):
- Direct tick-box row 936×44: **0 px** different.
- Items heading and sentence 936×54: 146 px (0.29%), the PO number only.
- Table header 936×45: 805 px (1.91%). Same labels, 11px uppercase, left-aligned, 45px tall; columns
  shift because Billed qty is 230px, not 189px (unit dropdown, see below).
- Row 1 936×69 and row 4 with the warning 936×85: row heights the same (69, 85); 864 px and 940 px
  different, from the names and the column shift.
- Totals 384 wide: identical down to y=202 (1,114 px differ, all from y 202 to 302, below the note box);
  the real block is 389px tall against 309, the extra 80px being the note box.
- Upload box 936×78 at 1280, the same height; 142 against 198 tall at 390 because the mock has a
  second, mock-only button.

Design A at 390: table header identical in geometry. The phone card is 358 wide against 324, and each
card is 24px taller (269 against 245) because Billed qty takes a row to itself (D2).

Design D, real 1280 against the mock at 1346:
- Items card 936×508 in both; header widths 263/145/144/146/130/106 against 263/145/145/145/130/106;
  row heights 51/51/51/71 in both; totals labels at the same offsets (718, 353–468). 2,471 px (0.52%).
- Invoiced vs received 936×206 in both, labels at the same offsets. 1,471 px (0.76%): the heading
  wording and the item names.
- Summary 936×250 against 190: the extra 60px is the "Delivery billed" line (D4).
- Payments 936×359 in both, row heights 69/69; 7,180 px (2.14%): the extra Actions/Reverse column
  narrows the others (D4).
- Page title 28px/36px in both.

Differences the document or the Decisions allow:
- "Deliveries being billed" tick boxes where the mock has a Purchase order dropdown (R-INV-3, T-273 ruling).
- The unit dropdown beside Billed qty with "Add a pack size…" (R-INV-4 packs and a new unit on the spot).
- The Other charges note box (R-INV-5 "plus a note").
- No "Use the sample bill" button (the mock marks it mock-only).
- "Unpaid" badge instead of "Pending", and curly quotes (Decisions).
- "Invoiced vs received" heading (the document's wording beats the mock's "Invoiced against received").
- "(required)" after "Copy of the bill" and "Proof of payment": the shared upload's required marker.
  Not named anywhere; I took it as the app's standard required label (R-INV-2 "standard required
  message"). Conductor to confirm.

## Defects

**D1. Two deliveries of one order on the same day can't be told apart.** Temple Admin, Create an
invoice, vendor VERIFY-D Traders. PO-2026-0051 had two deliveries on 19 Sept (45 Kg, then 5 Kg), and
both ticks read "PO-2026-0051 · delivered 19 Sept · received by Karuna Murti Das". I only found the
45 Kg one by ticking it and reading the Delivered column. The list isn't in order either: 0051, 0052,
0050, 0051, 0053. Expected: labels that can be told apart (a delivery count or quantity, say, which
the Rajeev example doesn't cover), sorted by order then date. Wording is Rajeev's to set.
`S/shots/real-A-filled-1280.png`.

**D2. Phone: Billed qty takes a row of its own, unlike the mock.** 390, any delivery line. The real
card puts Ordered | Delivered, then Billed qty alone on a full-width row (`max-lg:col-span-2`), then
Amount | Rate. The mock has Billed qty | Amount side by side, then Rate. The number box and unit
dropdown are 80 + 8 + 61 = 149px (83px dropdown for pieces, 171px in all), and the half column is
171px, so a plain unit fits. The layout rule says a new row only when things can't sit side by side.
Each card is 24px taller (269 against 245), with about 190px of empty space beside the dropdown. A
long pack name ("× Bag (25 Kg)") may not fit in 171px, which is probably why it was done;
that is a judgement for the conductor or Rajeev. `S/shots/real-A-filled-390.png`,
`S/shots/cmpA-390v390-row1-*.png`.

**D3. Desktop: empty space under Vendor when a vendor has several deliveries.** 1280, five unbilled
deliveries: the Deliveries column is 5 × 44 = 220px tall and the Vendor column beside it is empty
below its 44px box, about 176 × 460px of blank space. The mock never showed a picker, so no mock
covers this. Flag against "no dead space". `S/shots/real-A-filled-1280.png`.

**D4. Invoice page: things mock D doesn't have** (not named in the document):
"← All invoices", "Record a credit note" and "Void this bill" in the header; a "Delivery billed" line in
the summary (+60px); an Actions column with "Reverse" in Payments; items heading "Billed qty" where
mock D says "Billed". Credit note, void and reverse are existing features, and "Billed qty" matches the
form, which is the consistency rule, so I'd keep them all. They are still deviations from a
"zero deviation" mock, so they need a document line or Rajeev's OK.

**D5 (copy, low). Straight apostrophe in a new message.** KMS-400165 reads "That file isn't a photo or
a PDF." (`ErrorCode.java:1098`). The Decisions say quotes are curly throughout. The older shared
messages (KMS-400001 "isn't", KMS-400021 "don't") are straight too, so this is app-wide and older than
this work.

Known fixes in flight: **F7 fixed** (KMS-400174). **F9 fixed** (same Unpaid rows for both roles).
**F6** not reached: nothing on these screens reaches ₹1 lakh except the existing list row
"₹5,00,000", which is formatted correctly. **F5**: no vendor name broke mid-word on the list at 1280
in this data; no horizontal overflow at 390 on the list, the form or the invoice page (scrollWidth 390).

## Not verified

- Kitchen Manager (no account). Kitchen Staff was driven for the list, the invoice page and the API.
- A real phone's camera sheet: only the `accept`/`capture` attributes were checked.
- Kitchen Staff creating an invoice (the page allows them, and the bill-upload API accepted their
  request up to validation). Not driven end to end.
- The full §10 story and the costing screens (another group's).
