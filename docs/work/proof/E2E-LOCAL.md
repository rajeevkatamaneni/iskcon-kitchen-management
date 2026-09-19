# E2E-LOCAL: the §10 story end to end on the local stack

Run 2026-09-19 PDT (20 Sept IST), local web :3000, API :8080 on `kms_verify`. The verifier did not build any of this.
Headless Playwright Chromium 1134, deviceScaleFactor 1, never Rajeev's Chrome. Signed in with Firebase custom tokens:
Temple Admin `ikms.temple-admin.1` (Karuna Murti Das), Kitchen Manager `ikms.kitchen-staff.5` (Madhava Das),
Kitchen Staff `ikms.kitchen-staff.1` (Gopal Das), Volunteer `ikms.volunteer.3` (Lalita Devi Dasi).

Scratchpad (scripts, screenshots, raw DOM measurements):
`/private/tmp/claude-501/-Users-Rajeev-Workspace-kitchen-management-system/79bf5e51-4f9e-420c-b419-747d070a92df/scratchpad/e2e/`
(`shots/NN-name-{1280,390}.png`, `m/NN-name-{1280,390}.json`, `server.js` driver, `units.js` unit scanner).

At every screen below the page was measured at 1280 and 390. "Measured clean" means all of these held at both widths:
`documentElement.scrollWidth` equal to the viewport, 0 clipped/ellipsised elements in `<main>`, 0 visible elements past the viewport or past their card,
0 links to `/money` anywhere in the document, 0 g/ml figures of 1,000 or more in `<main>` text or control values, and no green element
except the ones listed. (Visually hidden elements, such as the table heading row in card mode, are excluded.)

Data created: ingredient **E2E Bulk Rice** (Kg, packs 5 Kg and Bag = 25 Kg), vendor **E2E Rice Traders**, PO-2026-0074,
invoices E2E-INV-1 and E2E-INV-2, request IR-2026-0005, recipe **E2E Plain Rice**, a lunch on 5 Oct 2026.

## Steps

1. **Pack sizes on the ingredient page (Temple Admin): PASS.** Created E2E Bulk Rice on /ingredients/new, then on /ingredients/{id}
   added "Bag" 25 Kg and a plain 5 Kg. Chips read "5 Kg" and "Bag = 25 Kg". The ⓘ reads exactly "The pack sizes vendors sell this in. The shopping
   list suggests whole packs." Measured clean (`01-ingredient-packs`).
2. **Vendor "Sells it as" (Temple Admin): PASS.** New vendor E2E Rice Traders. In "Other ingredients", searched "E2E", ticked it,
   Sells it as "Bag = 25 Kg", List price 1450, lead time 2, Preferred. The row showed "/ bag = ₹58 / Kg" before saving. Save gave
   "1 ingredient added to Supplies." and the Supplies row reads "Bag = 25 Kg · ₹1,450 / bag · ₹58 / Kg · 2 days · Preferred", with no arrow (no previous price).
   `vendor_price_history`: one ONBOARDING row, 58.0000 per Kg and 1450.00 per pack. Measured clean (`02-vendor-supplies`).
3. **Stock-take value (Kitchen Manager): PASS.** On /inventory/new, choosing E2E Bulk Rice changed the label to "What it would cost to buy today (₹ per Kg)",
   pre-filled with 58 (the preferred vendor's list price). Saved 20 Kg with reorder level 100. Measured clean (`03-add-to-inventory`).
4. **Shopping list in packs (Kitchen Manager): PASS.** The E2E Rice Traders tile shows "Top-up 100 Kg", on hand "20 Kg", suggested `4` "× Bag (25 Kg)" "= 100 Kg"
   (needs 120 − 20 = 100 Kg, so 4 bags). Measured clean (`04-shopping-list`). The only g/ml figures of 1,000 or more on the page are other verifiers'
   ingredient *names* ("VERIFY-B Poha 2792g").
5. **Create the PO from the tile (Kitchen Manager): PASS.** Generate purchase order opened the tile editor. The line reads "List price ₹1,450 / bag · ₹58 / Kg",
   `4` × Bag (25 Kg) = 100 Kg. Needed by 22 Sept. Save gave "PO-2026-0074 was created for E2E Rice Traders." and the line left the list.
   DB line: 100.000 KG, pack_count 4, the Bag pack, expected price 58.0000. Measured clean (`05-sl-po-editor`, `06-po-draft`).
6. **Send (Kitchen Manager): PASS.** "Send on WhatsApp" sent it straight away (the app has no preview step). Status is Sent. The PO page Items table
   reads "4 × Bag (25 Kg) / 100 Kg", and the link "Record a delivery on the Deliveries screen →" is there. Print sheet HTML (`/print`, saved as `po-print.html`)
   reads "E2E Bulk Rice 4 × Bag (25 Kg) ₹1,450 / bag · ₹58 / Kg Total ₹5,800", with no g/ml figure. The WhatsApp notification (PO_DELIVERY, 1a636398…) has
   summary "1 item(s): E2E Bulk Rice" and no quantities, since the amounts travel in the attached sheet. Measured clean (`07-po-sent`).
7. **Partial delivery (Kitchen Staff): PASS.** The Kitchen Staff menu has Deliveries (Q-1). Expected tab: "E2E Bulk Rice · PO-2026-0074 · 4 bags (100 Kg) · 22 Sept".
   There is one Record a delivery button for the vendor. Received was blank, then entered 2 bags, rejected 1 bag, Damaged, expiry 31 Mar 2027.
   The confirmation is green (`bg-success-bg`, rgb(231,244,233)): "Delivery from E2E Rice Traders recorded. 1 item went into stock. 1 line is still to come from them."
   `<main>` has no "₹", "price", "rate" or "paid". Stock: PO_RECEIPT 50 KG. Partly delivered history (`aria-expanded` false then true):
   "20 Sept [Today] · 50 Kg received · 25 Kg rejected (damaged) · Received by: Gopal Das" and then "Received 50 of 100 Kg ordered". Still owed "2 bags (50 Kg)".
   Measured clean (`08`, `09`, `10`).
8. **Rest of the delivery (Kitchen Staff): PASS.** Same button and panel. Received started blank, and "Everything arrived" filled 2. Confirmation:
   "… E2E Bulk Rice is complete: 100 of 100 Kg. Nothing more is owed by them." The Received tab and PO page history both show two lines and
   "Received 100 of 100 Kg ordered · complete 20 Sept". The PO merged table reads Ordered 4 × Bag (25 Kg)/100 Kg, Delivered 100 Kg,
   Rejected on delivery 25 Kg, and Return to vendor. There is no separate deliveries table. Measured clean (`11-received`, `12-po-complete`).
9. **Invoice for delivery 1 (Temple Admin): PASS.** Create an invoice opens the form. Two unbilled deliveries were offered ("PO-2026-0074 · delivered 20 Sept · received by Gopal Das · 2 × Bag (25 Kg) · 1st that day").
   Ticked the 1st. The line is locked: Ordered 4 × Bag (25 Kg), Delivered 2 × Bag (25 Kg), Billed qty defaulted to 2 "× Bag (25 Kg)".
   Amount 3000 shows Rate "₹1,500 / bag · ₹60 / Kg". GST 150. A Grand total of 3100 showed the red line "Adds up to ₹3,150, not ₹3,100", and Save stayed on the form.
   At 3150 the green tick appears, with the name "Adds up to the grand total". Saving with no file gave "Copy of the bill is required". The file input accepts `image/*,application/pdf`.
   After uploading, it saved to /invoices/{id} with "Invoice E2E-INV-1 was recorded.". Invoiced vs received: ₹3,000 against ₹2,900, "₹100 more than received".
   Measured clean (`13-invoice1-form`, `14-invoice1-page`).
10. **Invoice for delivery 2 at a higher rate (Temple Admin): PASS.** Only the 2nd delivery was still offered. Amount 3200 gave "₹1,600 / bag · ₹64 / Kg". GST 160, total 3360, bill uploaded, saved.
11. **Payments: PASS.** *Kitchen Manager and Kitchen Staff:* the invoice page has 0 "Pay this invoice" buttons and no Total owed. `POST /vendor-invoices/{id}/payments`
    returned 403 KMS-400021 for the Kitchen Manager, Kitchen Staff and Volunteer, and 0 payments were written. *Temple Admin, invoice 1, UPI:* the amount defaulted to ₹3,150 (what was left).
    The hint reads exactly "Upload proof of payment — a receipt, UPI screenshot or bank confirmation.", and Save with no file gave "Proof of payment is required". With the proof uploaded:
    "Payment of ₹3,150 recorded. This invoice is now paid in full." The row has a "Paid by" column (Karuna Murti Das) and one proof thumbnail ("Open proof-upi.png").
    *Invoice 2, Cash:* switching the method swapped Reference for Received by, Signed note and "Photo of the person who took the cash". The hints are exact (curly quotes).
    Saving empty gave three required errors. With Ravi Kumar and both photos: "Payment of ₹3,360 recorded…". The row reads "Received by Ravi Kumar" with two thumbnails.
    DB: UPI row, and CASH row with received_by_name "Ravi Kumar". Measured clean (`15`, `16`, `17`, `18`).
12. **List price and arrow: PASS.** The vendor page and ingredient page both read "₹1,600 / bag · ₹64 / Kg" with an up arrow in `text-danger` (rgb(131,74,67)).
    Its name is "Up from ₹1,500 on 20 Sept". On hover and on keyboard focus the tooltip reads exactly "₹1,500 on 20 Sept" (the previous price per bag, per the A-N2 ruling).
    Inside the viewport at 1280 and 390. The market rate reads "₹64 / Kg · 20 Sept · Set from an invoice". History rows: ONBOARDING 58, INVOICE 60, then 64.
13. **Issued from the temple store: PASS.** Kitchen Manager raised IR-2026-0005 (Deity Kitchen, 10 Kg), Temple Admin approved it, and the Kitchen Manager recorded the issue.
    September's total went from ₹1,080 to **₹1,720**, a rise of exactly ₹640, which is 10 Kg × ₹64. "2 ingredients have no known price" (Banana, Beaten rice) is still said aloud. Measured clean (`22`).
14. **Cost per serving: PASS.** Kitchen Manager made recipe E2E Plain Rice (20 Kg from 10 Kg of E2E Bulk Rice, 250 gm a head) and planned Lunch on 5 Oct for 80.
    October reads Lunch · 1 meal · 80 servings · **₹640 · ₹8**. Measured clean (`23`).
15. **Role access, Volunteer: PASS.** The menu is My shifts, Available shifts, Donate and My donations. There is no Deliveries, Invoices, Payments or Merge, and there is no "Merge duplicates" link.
    /deliveries, /invoices, /invoices/new, /invoices/{id} and /ingredients/merge each show "Not your page", with no E2E data and no ₹.
    API: 13 calls return 403 KMS-400021 before anything else, including an unknown id, `{}`, a non-JSON body and a well-formed delivery. They were GET/POST /deliveries, GET /deliveries/received, GET/POST /vendor-invoices, GET /vendor-invoices/{id} (real and all-zero ids),
    POST …/payments, GET merge-proposals and POST merges/preview and merges. Merge proposals are also 403 for the Kitchen Manager and Kitchen Staff.
    `/money` returns 307 to `/invoices?filter=unpaid`.

## Defects

1. **Tick tooltip runs off the phone screen (R-INV-5, which says to fix the shared component).** Repro: at 390×844 as Temple Admin, open /invoices/dc5795f7-93c3-4a18-85d4-d43c223f0ea2 and focus the green tick beside Grand total.
   The tooltip box is x 220.1, width 177.7, so its right edge is at 397.9, 7.9px past the 390 viewport. It reads "Adds up to the grand tot" (`shots/25-tick-tooltip-390.png`). At 1280 it fits (right edge 1271.9).
   The same tick on the /invoices/new form was not re-measured at 390.
2. **"planned was saved." on the planner day page (not procurement).** Repro: /planner/compose?date=2026-10-05, Lunch, one dish, Save this meal. You land on /planner/2026-10-05 and the banner reads "planned was saved.".
   `app/planner/[date]/page.tsx:81` renders `${saved} was saved.`, and the composer now passes `saved=planned`. `app/planner/page.tsx:104` handles this case ("The meal was planned."), but the day page does not.
3. **The same thing has two labels (consistency across views).** The invoices list shows "Variance ₹300" under Against. The invoice page calls the same figure "Difference · ₹300 more than received".
4. **Units are mixed on the Partly delivered tab.** For one line, Ordered reads "100 Kg" while Still owed reads "2 bags (50 Kg)". The Expected tab and the PO say "4 bags (100 Kg)" or "4 × Bag (25 Kg)".
5. **The cash "Received by" label has no "(required)" marker,** although it is required ("Received by is required"). The two photo fields beside it do show the marker.
6. **Wording on Cost per serving.** It still says "from vendors’ last-known prices". R-VEN-1 renames the column to "List price" everywhere, costing labels included, and costing now also falls back to the market rate.
7. Minor, for Rajeev to judge: the PO sheet and WhatsApp parameters write "20 Sep 2026" while every screen writes "20 Sept 2026".

Not verified here: the real PDF render (the local renderer is a stub, so the sheet was checked as HTML), and anything on staging.
