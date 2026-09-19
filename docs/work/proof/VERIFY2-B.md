# VERIFY2-B: shopping list (R-SL-1 to 4) and purchase orders (R-PO-1 to 4), round 2

I did not build this work and I edited no product code, schema or config. Local stack, 2026-09-19: web on
localhost:3000 (next dev), API on localhost:8080, database `kms_verify`. I used my own headless Playwright
Chromium 1134 at deviceScaleFactor 2, not Rajeev's Chrome. Scripts and screenshots are in
`scratchpad/v2b/` and `scratchpad/v2b/shots/`.

D-6 and D-7 are left out on purpose, because T-295 is fixing them.

**Sign-in.** Each session is a minted Firebase custom token, written to IndexedDB. Roles driven:
- Temple Admin: ikms.temple-admin.1
- Kitchen Manager: ikms.kitchen-staff.5, whose `whoami` role is KITCHEN_MANAGER
- Kitchen Staff: ikms.kitchen-staff.1
- Volunteer: ikms.volunteer.3, which has no permission

**Test data.** All of it is named "VERIFY2-B" and was made through the API as Temple Admin:
- Vendors: VERIFY2-B Grains and VERIFY2-B Veg.
- Ingredients: Rice (Bag = 25 Kg, which Grains sells as Bag at ₹1,500), Pepper 999g, Salt 1000g, Dal 10kg,
  Flour 100kg, Sugar 100.2kg, Lemon pcs, Poha 2792g, Ghee 1200ml, Tea (packs of 250, 500 and 1000 gm), and
  Jeera, Hing and Methi with no vendor.
- Orders:
  - PO-2026-0064: create form, Kitchen Manager at 390. Read back, then cancelled.
  - PO-2026-0066: made from the shopping list, then sent, with two part deliveries.
  - PO-2026-0068 and PO-2026-0069: API checks as Kitchen Staff and Kitchen Manager, both cancelled.

## Results per AC: 17 PASS, 0 FAIL

| AC | Result | Evidence (measured) |
|---|---|---|
| R-SL-1: amounts on the list are readable | PASS | At 1280, 1024 and 390, the VERIFY2-B boxes read: Dal 10 Kg, Flour 100 Kg, Ghee **2 L**, Lemon 11 pieces, Pepper **1 Kg**, Poha **3 Kg**, Rice 4 × Bag (25 Kg) = 100 Kg, Salt 1 Kg, Sugar 105 Kg, Tea 500 gm (1 × 500 gm), Hing/Jeera/Methi **1.5 Kg**. Across the whole page text: no g/ml figure of 1,000 or more, and no number box at ≥1000 beside gm/ml. The one regex hit was the ingredient name "Ghee 1200ml". |
| R-SL-1: readable on the PO, the sheet and WhatsApp | PASS (the PDF was checked as HTML) | PO-0066 lines: Poha `3 KG`, Pepper `1 KG`, Ghee `2 L`, Tea `500 GM` pack "500 gm" ×1. `/print`: "3 Kg", "2 L", "1 × 500 gm", no "/ gm" and no "/ ml". The WhatsApp notification 4d1d0691 (PO_DELIVERY) has params with a summary and names only, and no quantities. Locally the PDF comes from the stub renderer, so a real render is a staging check. |
| R-SL-2: rounding on screen | PASS | The server's suggestedQty, with the screen matching: 999→1000 gm, 1000→1000, 10→10 Kg, 100→100, 100.2→105, 10.2→11 pieces, 2792→3000 gm, 1600 ml→2 L, 1200 gm→1.5 Kg, tea 416 gm with {250, 500, 1000} → 1 × 500 gm, rice 100 Kg sold as Bag → 4 bags. |
| R-SL-2: unit tests | PASS | I ran them on a scratch copy of `backend/`, so the shared API was not touched. BuyingAmountTest: step table 38, packs 8, vendor pack 3, hand-typed 2, all green, including 999/1000/10 kg/100 kg, "never rounds down" and the fraction just over a boundary. ShoppingListIT 13/13 and ShoppingListPacksIT 4/4. The step table is `BuyingAmount.STEPS`, commented "Rajeev's rule, 2026-09-19". |
| R-SL-2: a hand edit is never re-rounded | PASS | Kitchen Staff at 1024 typed 1.37 in Methi's Kg box. After reload the box reads `1.37`, marked "edited". The API has 1370 GM with `edited: true`. |
| R-SL-3: vendor pack | PASS | List: `[4] × Bag (25 Kg) = 100 Kg`. PO-0066 line: 100 KG, packLabel "Bag (25 Kg)", packCount 4. Sheet: "4 × Bag (25 Kg) ₹1,500 / bag · ₹60 / Kg". Order page Ordered column: "4 × Bag (25 Kg) / 100 Kg". |
| R-SL-4: choose a vendor, tick on, reload | PASS | Kitchen Manager at 1280. The Jeera tick was on by default and the dropdown enabled. Choosing VERIFY2-B Veg moved the line into the "VERIFY2-B Veg" tile, which has a Generate purchase order button. It was still there after reload, and the DB has a vendor_supplies row (Veg, Jeera, preferred). |
| R-SL-4: bulk "Order these from" | PASS (the move only) | Kitchen Staff at 390, with 29 lines ticked. I set the bulk "Use this vendor next time" tick off, then chose VERIFY2-B Grains. Hing and Methi moved into the Grains tile, the "No vendor yet" tile emptied, and the page stayed 390 wide. After reload both lines were back under No vendor yet (tick off, so nothing was saved), and the DB shows no new link. I did not run bulk with the tick on: it would have linked 27 other people's ingredients. The single-line path above uses the same `onChooseVendor`. |
| R-PO-1: the button | PASS | On /orders, as Temple Admin, Kitchen Staff and Kitchen Manager: the link is "Create a purchase order" and goes to /orders/new. "Raise an order" is absent. |
| R-PO-2: the header | PASS | Vendor and Needed by share one row at the same top: 1280 is x312/x788, 460 wide each; 1024 is 332 each. At 390 they stack, as in the mock. No "Deliver to" and no "Nothing on this order yet.". The note grows 46→70 px at 1280 and 46→118 at 390. The heading is "Items" (h2), and the hint text matches the spec word for word. |
| R-PO-3: the combobox by keyboard | PASS (all 3 widths, 3 roles) | Typing "VERIFY2-B P" opens a group "Sold by VERIFY2-B Grains" with "Pepper 999g · ₹800/Kg" and "Poha 2792g · ₹60/Kg". Down then Up leaves aria-activedescendant on option 0. Escape sets aria-expanded=false. Enter picks the item and moves focus to "Quantity of VERIFY2-B Poha 2792g". Jeera shows under "Other ingredients". |
| R-PO-3: by mouse | PASS | Clicking "VERIFY2-B Rice" adds the line and moves focus to its Quantity box. The unit starts as "× Bag (25 Kg)" at 1500, with the note "₹1,500 / bag · ₹60 / Kg" (D-3 fixed). |
| R-PO-3: one-off item | PASS | Typing "VERIFY2-B plastic stool" offers "Add ‘VERIFY2-B plastic stool’ as a one-off item". Enter gives a line marked "One-off item", with a unit select defaulting to pieces and the price typed by hand. It saved as description, PIECES, 350. |
| R-PO-3: totals update live | PASS | ₹180 → ₹6,180 → ₹7,180 → ₹8,080 → ₹8,780; setting Poha to 5 gives ₹8,900, and the count reads "5 items" / "6 items". The same at 1280, 1024 and 390. |
| R-PO-3: dropdown not clipped at 390 | PASS | Listbox x 40–350 on a 390 page, with all four corners hit by elementFromPoint. At 1280 it is 332–652 and at 1024 332–652, all corners hit. Page scrollWidth equals the viewport at all three widths. |
| R-PO-4: two part deliveries | PASS (1280 TA, 1024 KS, 390 KM) | PO-0066 had deliveries recorded by Kitchen Staff (Gopal Das) and Kitchen Manager (Madhava Das). Dal: Ordered 10 Kg, Delivered 9 Kg, Rejected on delivery 1 Kg, Returned —. Expanding "▸ 2 deliveries" shows exactly two lines: "6 Kg received · Received by: Gopal Das" and "3 Kg received · 1 Kg rejected (spoiled) · Received by: Madhava Das". Rice shows Delivered 75 Kg with 2 deliveries. Headers are Item · Ordered · Delivered · Rejected on delivery · Returned · Actions. None of "Deliveries received", "at the gate" or "Fixed when the order was sent" appears. "Record a delivery on the Deliveries screen →" goes to /deliveries?order=…; Kitchen Staff and Kitchen Manager followed it and were not refused, and the page shows PO-2026-0066. Three "Return to vendor" buttons, one for each delivered item. |
| Access for each role | PASS | **Volunteer UI:** /shopping-list, /orders, /orders/new, /orders/{id} and /deliveries?order= all show "Not your page", with no data leaked. **Volunteer API**, each 403 KMS-400021: GET, POST and PATCH shopping-list; GET list and detail, POST create, send, cancel, whatsapp, print and receipts on purchase-orders; PUT vendor supplies; POST deliveries with a valid body. There were no 500s. **Kitchen Staff and Kitchen Manager API:** create PO 201, cancel 204, shopping list 200. |

Frontend group B tests: 12 files, 187 of 187 passed (vitest: shopping-list×4, po-×4, order-detail,
item-combobox, rate-formatter, manual-purchase-order, described-po-line).

## Round-1 defects

| Defect | Status | Evidence |
|---|---|---|
| D-1: "No vendor yet" Ingredient column squeezed | **Fixed** | Ingredient column: 122px at 1280 (was 46) and 92 at 1024 (was 37). No word breaks inside itself: every break found was at the hyphen in "VERIFY2-B". At 1024 the table is 635 inside a 628 wrapper, which scrolls sideways by 7px. That is T-278's recorded last resort. Every select and label ends at x 953, inside the wrapper's right edge at 966, so no control or text is hidden. At 390 the rows are cards and the page is 390 wide. |
| D-2: the create form ordered gm items in gm | **Fixed** | Poha: unit Kg, price prefilled 60, note "₹60 / Kg", 3 gives ₹180, saved as `3 KG @60`. Ghee is in L at ₹600 / L. |
| D-3: no packs on the create form | **Fixed** | Rice starts as × Bag (25 Kg) at 1500. Saved as 100 KG with packCount 4. Tea offers × 250 gm / × 500 gm / × 1 Kg. |
| D-4: "raised" and "created" for the same act | **Fixed** | The shopping list confirms "PO-2026-0066 was created for VERIFY2-B Grains.", and /orders confirms "A purchase order for VERIFY2-B Grains was created.". The words "Generate purchase order" and "Generated <date>" remain; R-SL-4's AC names that button. |
| D-5 (a–c): order page vs mock E | **Fixed** | The chip is 12px, weight 600, 20 high, the same as the mock. "Items" is 18px, weight 500, the same as the mock. The table sits 25/25 inside the card with a 20px gap under the heading at 1280 and 1024, the same as the mock. At 390 the inset is 1/1, which is T-289's recorded reason. (d) is Rajeev's. The /orders list chip is still weight 400; T-289 left it for whoever owns po-status.tsx. |
| D-8: gm-pack price read per gm | **Fixed** | Panel: "List price ₹250 / 500 gm · ₹500 / Kg". Sheet: "₹250 / 500 gm · ₹500 / Kg". The create form reads "₹500 / Kg". |

## Mock comparison (dev-po copied into `frontend/app/dev-po/page.tsx`, measured, then deleted)

**Design A against /orders/new**, set up in the mock's state: Kalasipalya, 22 Sept, the mock's note, and
Coconut 4 × 120, Curry leaves 3 × 60, Green chilli 2.5 × 70. The order total is ₹835 for 3 items in both.
- **1280, the same as the mock:** band 77 high; title 227×30 at 22px/600; Cancel 77×44; Create order
  119×44; labels 14px/500; boxes 44 high; note 46; Items 16px/600; hint 14px; header row 51; the Remove
  column 106; price box 112×44.
- **Width differences from the mock's bordered frame:** 870 against 936, spread across columns.
- **Pixel diff** (pixelmatch at 0.1, @2x): the header and fields region differs by 0.04% (231 px), and the
  top-left of the table by 0.00%.
- **1024:** the mock itself overflows (document 1153 wide on a 1024 screen). The real page fits: document
  1024 and table 680 in 680.
- **390:** each cell has the mock's x and width once the frame offset is removed. For example, the table
  header runs Quantity 58, Unit 29, Expected price 113, Line total 65 in both. The pixel diff in the band
  and field regions is 8.8%. That comes from the sticky phone top bar and band, which the real page has
  and the mock frame does not, and from the note wrapping at 324 in the mock against 358 real.
- **Differences that were recorded by their builders (T-288, "for Rajeev"):**
  - Rows are 89 high against the mock's 69, from the rate note under the price box ("₹60 / Kg"). At 390
    they are 218 against 198.
  - The quantity box is 80 wide against 112, at every width (see observation 1).
  - The type-ahead spans three cells with `max-w-xs`: 320 wide at 1280, 312 at 1024 and 310 at 390,
    against the mock's 269, 256 and 256.

**Design E against /orders/{id} (PO-0066):**
- These match the mock at 1280 and 1024: order number 28px/600; chip 12px/600, 20 high; "Items"
  18px/500; table inset 25/25; a 20px gap under the heading.
- Row heights are 69 against 61, because "Return to vendor" is a 44px button, allowed under R-PO-4.
- The Actions column is 182 against 114, for the same reason.
- The card starts 21px lower at 1280, because of the PDF, WhatsApp and language controls in the header
  (allowed under R-SL-1).
- The mock's "Record the whole delivery" button is replaced by the link, which Q-8 and R-DEL-2 allow.
- The card pixel diff is 5.24% at 1280, from different data. The heading row differs by 9.58%, from the
  link in place of that button.
- At 1024 the mock overflows (document 1153) and the real page fits (1024).

**Clean-up:** `frontend/app/dev-po/` and `frontend/.next/types/app/dev-po` were deleted.
`ls` shows neither exists; only another verifier's `dev-invoices` remains.

## New defects

**N-1: the type-ahead says "gm" for an item the line then orders in Kg.**
- Found as Temple Admin, Kitchen Staff and Kitchen Manager on /orders/new, at 1280, 1024 and 390.
- Steps: choose VERIFY2-B Grains, then type "VERIFY2-B Jee".
- The option reads "VERIFY2-B Jeera · gm". Picking it gives a line whose Unit is "Kg".
- The same happens for a vendor's own item with no list price. With Kalasipalya, "Curry leaves · gm" and
  "Green chilli, slit · gm" both become Kg lines.
- Expected: the option says Kg, the unit the line uses. Items that have a price already do, for
  example "₹60/Kg".
- The cause is in `app/orders/new/page.tsx`: the option text uses `unitLabel(i.unit)` in `everyone`, and
  in `vendorOffers` when there is no price. It should use `readableUnit`.

**N-2: an order with no items is refused with "Check your connection and try again" and "quote KMS-0000".**
- Kitchen Staff, /orders/new, at 1280.
- Steps: choose VERIFY2-B Grains, add no items, then press Create order, or press Enter on the Vendor
  select.
- The screen shows: "An order needs at least one item. Type one into the Items table." / "Check your
  connection and try again." / "If you need help, quote KMS-0000".
- Expected: a next step that fits the refusal, and no connection advice or code.
- The cause is `toApiError(null, …)` at `app/orders/new/page.tsx:391`. T-288 found this and left it.
- Screenshot: `shots/create-empty.png`.

## Observations (not AC failures; for Rajeev)
1. The quantity box is 80px at every width. T-288's reason was fitting the page at 1024. At 1280 and 390
   there is room for the mock's 112.
2. On the order page, Pepper reads Ordered "1 Kg" and Delivered "500 gm" in the same row. Neither is
   1,000 or more, but the row uses two units.
3. As in round 1, the "Why" and "On hand" figures are rounded for display, so they can disagree with
   Suggested. For example, Ghee reads "Top-up 1.5 L" (it is 1,600 ml) beside Suggested 2 L, and Poha's
   on-hand reads 810 gm (it is 808).
4. For a Volunteer, a malformed body returns 400 KMS-400001 with field errors before the permission check
   gives 403. This was POST /purchase-orders `{lines: []}`, and the same on /shopping-list and
   /deliveries. Nothing was written and nothing returned 500, but the body validation runs before the
   permission check. It is app-wide, not specific to this build.
5. The Kalasipalya supplies in `kms_verify` now have no list price, so the mock-state run prefilled no
   prices. The prices were typed in.
