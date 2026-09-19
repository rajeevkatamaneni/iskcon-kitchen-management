# VERIFY-B — shopping list (R-SL-1..4) and purchase orders (R-PO-1..4)

Verifier did not build this work and edited no product code. Local stack, 2026-09-19, web
localhost:3000 (next dev), API localhost:8080 on `kms_verify`. Headless Playwright Chromium 1134,
deviceScaleFactor 2. Screenshots: scratchpad `.../79bf5e51-.../scratchpad/shots/` (names below).
The mock was copied to `frontend/app/dev-po/page.tsx` for measuring and **deleted afterwards**
(`ls frontend/app | grep dev-po` → nothing; `app/dev-invoices/` is another verifier's).

**Sign-in.** Password sign-in hit Firebase `QUOTA_EXCEEDED` after four logins (parallel verifiers),
so sessions were minted as custom tokens (admin SDK SA) and written to IndexedDB.
Roles driven: Temple Admin (ikms.temple-admin.1), Kitchen Staff (ikms.kitchen-staff.1 and .4),
Volunteer (ikms.volunteer.3, no permission). **Kitchen Manager: not driven** — `kms_verify` has no
Kitchen Manager account, and I did not change a shared test account's role while other verifiers use
them. For this group Kitchen Manager and Kitchen Staff hold the same permissions
(`RolePermissions.java`: MANAGE_VENDORS, MANAGE_PURCHASE_ORDERS, RECEIVE_DELIVERIES, MANAGE_INVENTORY),
but that is read from code, not verified on screen.

**Test data (all "VERIFY-B", made through the API as Temple Admin):** vendor VERIFY-B Grains;
ingredients Rice (Bag 25 Kg, vendor sells it as Bag at ₹1,500), Pepper 999g, Salt 1000g, Dal 10kg,
Flour 100kg, Sugar 100.2kg, Lemon pcs, Poha 2792g, Tea (packs 250/500/1000 gm), Jeera (no vendor),
Rava; each with a reorder level and stock so the top-up equals the named figure.
Orders: PO-2026-0054 (from the shopping list, sent, two part deliveries), PO-2026-0055 (create form,
Kitchen Staff).

## Results per AC

| AC | Result | Evidence |
|---|---|---|
| R-SL-1 Kalasipalya curry leaves read "3 Kg", no "2792" | **PASS** | API shortfall 2791.099 gm, suggested 3000 gm. Screen at 1280 and 390: Suggested box `[3]` Kg. Page text and all box values: 0 matches for 2792/2791, 0 quantities ≥1000 in gm. Kalasipalya PO editor: "Quantity of Curry leaves = 3" Kg (`B-editor-kalasi-1280.png`, not saved). |
| R-SL-1 on the PO, PDF, WhatsApp | **PASS**, PDF not verified as a render | Same path end to end with VERIFY-B Poha (need 2792 gm): list `[3]` Kg → PO line `3.0 KG` → PO page "3 Kg" → sheet "3 Kg". PDF locally is the stub renderer (140-byte file "not a real render"); the sheet's HTML source (`/print`) was checked instead. WhatsApp is a template with a summary and the PDF attached; its params contain no quantities (notification ebe4040a…). Real PDF render is a staging check. |
| R-SL-2 rounding examples | **PASS** | Screen, both widths: 999 gm → 1 Kg; 1000 gm → 1 Kg; 10 Kg → 10; 100 Kg → 100; 100.2 Kg → 105; 10.2 pieces → 11; 1200 gm → 1.5 Kg; 2792 gm → 3 Kg; tea 416 gm with {250, 500, 1000} → "1 × 500 gm". BuyingAmountTest has boundary, never-rounds-down and pack tests (read, not run: running Gradle would restart the shared API through DevTools). Step table is in BuyingAmount.STEPS, commented as Rajeev's rule. |
| R-SL-2 hand edit never re-rounded | **PASS** | VERIFY-B Rava: typed 1.37 in the Kg box, reload → `[1.37]`, API 1370 gm, `edited: true`. |
| R-SL-3 100 Kg sold as Bag 25 Kg → "4 × Bag (25 Kg)" | **PASS** | List: box `[4]` "× Bag (25 Kg)" "= 100 Kg". PO line: packCount 4, quantity 100 KG. PO page Ordered "4 × Bag (25 Kg) / 100 Kg". Sheet: "VERIFY-B Rice 4 × Bag (25 Kg) ₹1,500 / bag · ₹60 / Kg". (`B-sl-tile-1280.png`, `B-po-draft-1280.png`) |
| R-SL-4 choose vendor, tick on, persists after reload | **PASS** | Temple Admin, 1280: VERIFY-B Jeera in "No vendor yet", tick on by default, chose VERIFY-B Grains → line moved to that tile, which has a Generate purchase order button; reload → still there. See D-1 for the table layout. |
| R-PO-1 button and straight to the form | **PASS** | /orders: link "Create a purchase order" → /orders/new, "Raise an order" absent; landed on the form. |
| R-PO-2 header | **PASS** | Vendor and Needed by same top (125) at 1280, stacked at 390 as the mock; no "Deliver to"; note grows (46px); section "Items"; no "Nothing on this order yet." |
| R-PO-3 combobox by keyboard | **PASS** | 1280 (Kitchen Staff) and 390 (Temple Admin): Down opens browse list; Escape closes (`aria-expanded=false`); Down, Down, Up highlights the 2nd option via aria-activedescendant; Enter picks it and focus goes to its Quantity. Vendor's items first ("Sold by VERIFY-B Grains", "VERIFY-B Dal 10kg · ₹140/Kg"), then "Other ingredients". |
| R-PO-3 by mouse | **PASS** | Click on "VERIFY-B Rice · ₹60/Kg" → line added, focus in its Quantity. |
| R-PO-3 one-off | **PASS** | "Add ‘Plastic stool zq’ as a one-off item" as the only option; Enter and click both make a line with "One-off item", unit select (pieces default), price typed. |
| R-PO-3 totals update live | **PASS** | ₹400 → ₹520 → ₹640 (qty 2 → 4) → ₹1,390 → ₹2,540, "4 items". |
| R-PO-3 dropdown not clipped at 390 | **PASS** | Listbox left 40, right 296, width 256, inside 0–390; all four corners hit the listbox by elementFromPoint; no clipping ancestor; no option wider than the list; page width 390. |
| R-PO-3 submitted order | **PASS** with D-3 | Kitchen Staff created PO-2026-0055 ("A purchase order for VERIFY-B Grains was created."). |
| R-PO-4 two part deliveries | **PASS** | PO-2026-0054, deliveries recorded as two Kitchen Staff users: Dal Delivered "9 Kg", Rejected "1 Kg", "▸ 2 deliveries", expanded shows exactly two lines (6 Kg; 3 Kg + 1 Kg rejected (spoiled)); Rice "75 Kg", 2 lines. One table (Item · Ordered · Delivered · Rejected on delivery · Returned · actions), no "Deliveries received", no "Rejected at the gate", no "Fixed when the order was sent". Link "Record a delivery on the Deliveries screen →" → /deliveries?order=…. "Return to vendor" on each delivered row. Same at 390 (Kitchen Staff), page width 390. |
| No permission (Volunteer) | **PASS** | /shopping-list, /orders, /orders/new, /orders/{id}: "Not your page". API GET list/detail/shopping list 403; POST order → 403 KMS-400021. |
| §10.2 test suites | partly | vitest on the 8 PO/shopping-list files: 106/106 pass. Full tsc/eslint/vitest and `./gradlew test` **not run** by me (Gradle would restart the shared local API). |

## Mock comparison (DOM geometry, px, CSS pixels)

Real page driven to the mock's state (Kalasipalya, needed by 22 Sept, the mock's note, Coconut 4 ×
₹120, Curry leaves 3 × ₹60, Green chilli 2.5 × ₹70). Coordinates relative to the form's frame.

**Design A, 1280** — mock / real:
- Band 77 high / 77. Title 22px/600 at (33,17) / (32,16). Cancel 77×44, Create order 119×44, both.
- Vendor and Needed by: 427 wide each / 460 each; labels 14px/500, boxes 44 high, both.
- Note 46 / 46. Items 16px/600, hint 14px, table header 51, rows 69, number boxes 112×44 — identical.
- Columns: Item 301 / 315, Quantity 149 / 162, Unit 64 / 77, Expected price 149 / 162, Line total 101 / 114, Remove 106 / 106.
- Order total ₹835, 3 items, both.
- Every width difference comes from the mock's frame: the mock draws the screen inside a bordered card, so its form is 870 wide against 936 real. The extra 66px is shared +13 per column gap, which is what §5 rule 3 says.
- Colours: none differ, apart from a hovered row.

**Design A, 390** — identical x, width and height for every table cell, box and button once the frame offset is taken out (mock 324 wide, real 358). The note is 70 high in the mock and 46 real: the mock's narrower box wraps the sample note onto two lines.

**Design A pixel diff** (pixelmatch threshold 0.1, @2x):
- Left strip at 1280: 0.68%. The differences are the title, and the green-chilli row, which was hovered.
- Full frame at 1280: 1.86%. Band at 1280: 7.08%.
- 390 body: 5.57%. That is line-wrap shift from the 34px narrower mock frame.
- Files: `B-diff-A-*.png`.

**Design E vs the real order page, 1280** — mock / real:
- Order number 28px/600, both. Generated, Sent and Needed by lines 14px, both.
- "Part delivered" badge: 12px, weight 600, 20 high in the mock; weight 400, 24 high real (D-5).
- "Items" heading: 18px weight 500 / weight 600 (D-5).
- Where the table starts: 25px inside the card in the mock; flush with the card edge, x 33 against card 32, real (D-5).
- Row height 61 / 69: the kept "Return to vendor" is a 44px secondary button, where the mock's "Delivered" was a 36px ghost button.
- Actions column 114 / 182, from "Return to vendor".
- Ordered column 147 wide real, from "4 × Bag (25 Kg)" (packs, R-SL-3).

**Design E pixel diff:**
- Header region: 5.00% at 1280, 4.69% at 390.
- Card: 5.18% at 1280. Different data (order number, items), so it is not diagnostic.
- At 390 the card starts 133px lower. The real header's language, PDF, Print and WhatsApp buttons wrap above it.

**Allowed differences, with their ID:**
- R-PO-4: Return to vendor kept.
- R-PO-4 / R-DEL-4: history collapsed as "▸ N deliveries", where the mock's lines are always open.
- R-PO-4 / R-DEL-2: the mock's "Delivered" per item and "Record the whole delivery" are replaced by the link to the Deliveries screen.
- R-SL-3 / R-ING-1: the packs text "4 × Bag (25 Kg) / 100 Kg".
- R-SL-1: PDF and WhatsApp buttons on the order page, since those outputs are named in the AC.
- DESIGN_SYSTEM §5 ("a number box in a table grows to fit its figure"): number boxes `min-w-28` instead of `w-28`, measured at 112, the same as the mock. T-263's deviation is justified, not a defect.
- Consequences of the mock's frame, not of the build: the card width, and the note wrap.

## Defects

**D-1 (F5, at 1280 as well as 1024): "No vendor yet" table on the Shopping list squeezes the ingredient name letter by letter.**
- Temple Admin, /shopping-list, "No vendor yet" tile.
- At 1280: Ingredient 46px wide ("VERIFY-B Jeera" wraps one letter per line), while Order by is 208px holding "—".
- At 1024: Ingredient 37px, and the table is 857 wide in a 680 section. Its right edge is at x 1195 on a 1024 screen, so the Vendor column (Choose vendor, Use this vendor next time) is cut off, and the page does not scroll.
- At 1440 it is fine (172px).
- Screenshots: `B-sl4-before-1280.png`, `B-sl-novendor-1024.png`.

**D-2: the create form orders gm-stocked vegetables in gm, where the mock orders them in Kg.**
- Temple Admin / Kitchen Staff, /orders/new, vendor Kalasipalya.
- Pick Curry leaves or Green chilli, slit: the Unit column says "gm" (mock: "Kg"). Typing 3 orders 3 gm.
- Seen end to end with VERIFY-B Poha: the dropdown says "₹60/Kg", but the line prefills price 0.06 per gm. "3" gave "Order total ₹0.18" and was saved on PO-2026-0055.
- To order 3 Kg a cook must type 3000 gm. That is the "2792 gm in front of a vegetable merchant" problem R-SL-1 set out to remove, and it contradicts the dropdown's own per-Kg price.
- Mock and R-SL-1 disagree with the build here; the document does not name this difference.

**D-3: the create form offers no packs.**
- /orders/new, vendor VERIFY-B Grains, pick VERIFY-B Rice (sold as Bag 25 Kg): Unit "Kg", price ₹60/Kg. There is no way to order "4 × Bag".
- The document's preamble says to follow R-SL-3 wherever a line is sold in packs.
- T-263 recorded this as out of its contract. Nobody has built it.

**D-4: "raised" and "created" for the same act.**
- /orders confirms "…was created".
- /shopping-list confirms "PO-2026-0054 raised for VERIFY-B Grains.", and its subtitle says "raise that vendor's purchase order".
- The order page's cancel text says "raise a new order".
- R-PO-1 renamed the act "Create". Two words for one act across views (T-263's "was created" is fine on its own; the inconsistency is the defect).

**D-5: order page vs mock E, not named in the document.**
- (a) The status badge is weight 400 and 24 high; the mock's is 600 and 20.
- (b) The "Items" heading is weight 600; the mock's is 500.
- (c) The table runs flush to the Items card's edges; the mock insets it 25px inside `card px-6 py-5`.
- (d) The "Close this order, part delivered" panel below the table is existing function the mock does not show. It is a matter for Rajeev, not a build error.

**D-6: the create form title's letter-spacing.**
- The real title is the shared FocusScreen `<h1>`, with −0.44px tracking; the mock used a `<p>` with normal tracking. The text is 217 wide against 227.
- This comes from the mock using `<p>` in place of the app's header, not from the build. Listed so Rajeev can accept it.

**D-7: the unnamed "No vendor is active yet…" sentence on /orders/new** (T-263 call 10). It appears only when no vendor is active, so it could not be seen with this data. It is not in the mock or the document, and it is Rajeev's call.

**D-8: prices for gm packs read per gm.**
- The sheet and the PO editor show VERIFY-B Tea as "₹250 / 500 gm · ₹0.50 / gm".
- VERIFY-B Pepper, also a gm ingredient, reads "₹800 / Kg" on the same sheet. Readable units say per Kg.

**Observations, not AC failures (for Rajeev):**
- The "Why" and "On hand" figures on the list are rounded for display, so they can disagree with Suggested:
  - 201 gm shows as "200 gm", 808 gm as "810 gm", 416 gm as "Top-up 415 gm".
  - A 1.2 Kg top-up shows as "Top-up 1 Kg".
  - Chana masala powder reads "shortfall 2 Kg" beside Suggested 2.5 Kg.
- Delivered on a pack line reads "75 Kg", not in bags.
- The history line reads "19 Sept Today · 6 Kg received · Received by: Gopal Das". The mock has "12 Sept · 30 Kg · received by …". That is R-DEL-4, group C's to judge.
- On the 390 cards the column "Suggested" is labelled "Order".
- In my first run the Choose vendor dropdown stayed disabled for 30s (vendors list not loaded). It did not recur in three later runs. Not reproduced.
