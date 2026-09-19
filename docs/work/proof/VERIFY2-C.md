# VERIFY2-C: Deliveries (§7 R-DEL-1..5, AC-DEL), round 2

Verifier: a fresh agent that neither built this nor ran round 1. 2026-09-19, local stack (web :3000 `next dev`, API :8080, db `kms_verify`).
Headless Playwright Chromium 1134, deviceScaleFactor 1, never Rajeev's Chrome. Sign-in used Firebase custom tokens minted
with the admin service account:
- Temple Admin: ikms.temple-admin.1 (Karuna Murti Das)
- Kitchen Manager: ikms.kitchen-staff.5 (Madhava Das, role KITCHEN_MANAGER)
- Kitchen Staff: ikms.kitchen-staff.1 (Gopal Das)
- no permission: ikms.volunteer.3 (Lalita Devi Dasi, Volunteer)

Scripts, screenshots and raw measurements are in the scratchpad:
`/private/tmp/claude-501/-Users-Rajeev-Workspace-kitchen-management-system/79bf5e51-4f9e-420c-b419-747d070a92df/scratchpad/v2c/`
(`shots/`, `m/`, `cmp.txt`, `sweep-ta.txt`).

**Test data** (Temple Admin made it through the API):
- vendors "VERIFY2-C Suppliers" and "VERIFY2-C Dairy Farm"
- ingredients VERIFY2-C Rice (Kg, pack Bag = 25 Kg), Toor dal (Kg), Paneer (Kg), Milk (L), Curd (L)
- PO-2026-0061 (needed 19 Sept: Rice 4 bags, Toor dal 50 Kg), now RECEIVED
- PO-2026-0062 (needed 21 Sept: Paneer 5 Kg, Milk 20 L)
- PO-2026-0063 (Dairy Farm, needed 20 Sept: Curd 30 L, Milk 10 L)
- PO-2026-0065 (Rice 4 bags, Toor dal 10 Kg; two part deliveries recorded through the API as setup)
- one return: 1 Kg of VERIFY2-C Paneer, spoiled

## Results per AC: 21 PASS, 1 FAIL

1. **R-DEL-1, menu: PASS.** Temple Admin, Kitchen Manager and Kitchen Staff all see `… Shopping list | Purchase orders | Deliveries | Vendors`
   in Ordering, at 1280 and 1024 in the sidebar and at 390 in the drawer. The Volunteer has no Deliveries item at any width.
2. **R-DEL-1, Q-1 access: PASS.** Kitchen Staff now open the page: h1 "Deliveries", 7 "Record a delivery" buttons, no "Not your page".
   Kitchen Manager sees the same. Both recorded deliveries through the UI:
   - Gopal Das (Kitchen Staff): the rest of PO-0061 at 1280; a curd part at 390.
   - Madhava Das (Kitchen Manager): Curd and Milk at 1280.

   The API gives GET /deliveries and GET /deliveries/received 200 to all three.
3. **R-DEL-1, the API refuses a user without the permission: PASS.** The Volunteer gets 403 KMS-400021 on:
   - GET /api/v1/deliveries
   - GET /deliveries/received
   - POST /deliveries with a valid body
   - GET and POST (valid body) /purchase-orders/{id}/receipts

   No token gives 401. Nothing was written: there are 0 goods_receipts by the Volunteer. The page shows "Not your page".
4. **R-DEL-1, no prices: PASS.** No "₹", "price", "rate" or "paid" in `<main>` on any tab, with the panel and every history open, at 1280
   and 390. The /deliveries JSON has no price, rate, amount or cost key. The new goods_receipt_lines have `unit_price` NULL.
5. **R-DEL-2, three tabs: PASS.**
   - Expected: grouped by vendor, with still to come and needed by.
   - Partly delivered: Ordered, Received, Still owed and "Owed for".
   - Received: Date, Vendor, Items, Received by, Rejected, Returned.
6. **R-DEL-2, summary strip: PASS.** The tiles read "Due today 1 vendor · Overdue 1 vendor (amber) · Partly delivered 4 orders · Received
   this week 9 deliveries". Against the mock, the header, tiles and tabs differ by 0.39% of pixels at 1280, and that is the tile values.
7. **R-DEL-3, one button per vendor, across orders: PASS.** VERIFY2-C Suppliers has one card, "4 items on orders PO-2026-0061,
   PO-2026-0062", and one button. The panel lists all four lines, each with its PO number under it and the amount still to come.
8. **Received now / Rejected + reason / Expiry: PASS.**
   - Reason stays disabled until a rejected amount is typed.
   - Paneer's expiry 2026-09-25 reached the stock movement and its batch (`expiry_date 2026-09-25`, batch set).
9. **Ordered unit to stock unit: PASS.** Rice shows "4 bags (100 Kg)", and the box is labelled "in Bag (25 Kg)". Typing 2 gave the stock
   movement `50.000 KG PO_RECEIPT`.
10. **Everything arrived: PASS.** All four boxes went from blank to Rice 4, Toor dal 50, Paneer 5, Milk 20.
11. **Saving: PASS.** Each save made one goods receipt per order. Movements are PO_RECEIPT with batches. Replaying the same idempotency key
    returned the same receipt id and did not add a second one.
12. **Green confirmation: PASS.** It is `role=status`, background rgb(231,244,233), text rgb(56,105,68), and reads "Delivery from VERIFY2-C
    Suppliers recorded. 3 items went into stock. 3 lines are still to come from them." A blank save shows "Type what arrived on at least
    one line, or press Everything arrived."
13. **Rejected stays owed: PASS.** 30 Kg were received and 2 Kg rejected (spoiled). Still to come became 20 Kg, with "includes 2 Kg
    rejected 19 Sept [Today]" under it. Stock went up by 30 only.
14. **The rest is recorded the same way: PASS.** Kitchen Staff opened the same panel from the Partly delivered tab, starting blank, and
    recorded 2 bags and 20 Kg. The confirmation read "… VERIFY2-C Rice is complete: 100 of 100 Kg. VERIFY2-C Toor dal is complete: 50 of
    50 Kg. 1 line is still to come from them." PO-2026-0061 is now RECEIVED.
15. **R-DEL-4, wording: PASS.** Opened with Tab and Enter, the history reads:
    - `19 Sept [Today] · 30 Kg received · 2 Kg rejected (spoiled) · Received by: Karuna Murti Das`
    - `19 Sept [Today] · 20 Kg received · Received by: Gopal Das`
    - `Received 50 of 50 Kg ordered · complete 19 Sept [Today]`

    Before it completes, it reads `Received 10 of 30 L ordered` and `8 L received · 2 L rejected (damaged)`, for the Kitchen Manager's part.
16. **R-DEL-4, component: PASS.**
    - No order number appears in the history.
    - `aria-expanded` goes false to true, and `aria-controls` points to the region.
    - Text is 14px, rgb(79,81,84) (ink-secondary).
    - The page uses the same `DeliveryHistory` on Expected, Partly delivered and Received, with the server's `today` (code-read).
17. **R-DEL-5, pills: PASS.** The date is always written next to the pill.

    | Pill | Size | Background | Text |
    |---|---|---|---|
    | Today | 47×20 | rgb(229,242,253) (info) | rgb(50,96,134) |
    | "29 days late" | 77.8×20 | rgb(249,238,226) (warning) | rgb(128,93,48) |
    | Overdue | 59.8×20 | warning | warning |

    All three are 12px/600, padding 2px 8px, radius 5px, and measure the same at 1280 and 390.
18. **R-DEL-5, no "Price paid": PASS.** The panel has six columns (Item, Still to come, Received now, Rejected on delivery, Reason, Expiry)
    and no price field.
19. **AC-DEL, partial then the rest: PASS.** Covered by items 12 to 15.
20. **AC-DEL, a user without the permission: PASS.** Covered by items 1 and 3.
21. **AC-DEL, nothing truncated and no sideways scroll at 1280 and 390: PASS.**
    - Measured on Expected, the panel (VERIFY2-C Suppliers, and VERIFY-B Grains with 10 lines), Partly delivered with every history open,
      and Received with 12 histories open.
    - scrollWidth equals the viewport in every state (1280/1280, 390/390).
    - 0 clipped elements in `<main>`. The only overflowing things are sr-only spans and the phone-card thead, which are hidden on purpose.
    - 0 elements past a card edge, and 0 words split across lines.
22. **Layout at 1024, judged by the logic of the layout rules (Q-18): FAIL.** See defect 1.

## Round-1 defects

- **C1, panel grid spacing: fixed.** At 1280 the real panel and the mock are the same:
  - table 894px at x=333
  - heading cells 45px tall
  - padding 12px, with 20px at the row ends

  Heading widths differ by 1 to 12px because the data differs.
- **C2, sidebar truncates the names: fixed.** "ISKCON South Bengaluru" is sw 231 = cw 231. "Karuna Murti Das" is sw 99 = cw 99 and wraps
  to 40px tall. "Madhava Das", "Gopal Das" and "Lalita Devi Dasi" are unclipped too. Checked at 1280, 1024 and 390, for all four roles.
- **C3, panel clipped at 1024: fixed.**
  - The panel now turns into cards at 1024.
  - Nothing is past the card, all inputs are inside it, and no word is split. That holds for VERIFY2-C Suppliers and for VERIFY-B Grains
    (10 lines).
  - "Everything arrived" and "Save delivery" are visible.
  - The mock itself runs 132.8px past its card at 1024.
- **C4, the Returned column: fixed.** It reads "VERIFY2-C Paneer 1 Kg, spoiled, 19 Sept", at 1280 and 390, signed in as Kitchen Staff.
- **Minor 5, invalid body gives 400 before 403: not fixed** (it was never dispatched). The Volunteer's `{}` POST to /deliveries and to
  /purchase-orders/{id}/receipts returns 400 KMS-400001 with field errors, not 403. A valid body is refused with 403. No data leaks.
- **Minor 6, the Today pill used the browser clock: fixed** (code-read). All three `DeliveryHistory` uses on the page pass the server's
  `today`.
- **F10, the page guard: fixed.** Kitchen Staff are admitted on the running app (item 2).

## Mock comparison (both signed in as Temple Admin; pixelmatch, threshold 0.1)

The mock was copied to `frontend/app/dev-deliveries/page.tsx`, rendered at 1280, 1024 and 390, then deleted.
`frontend/app/dev-deliveries` and `frontend/.next/types/app/dev-deliveries` were both confirmed gone afterwards (`test -e` false). The
dev-invoices and dev-po copies belong to other verifiers and were left alone.

| Width | Header, tiles and tabs (`<main>` only) | Full page, common height |
|---|---|---|
| 1280 | 0.39% (the tile values) | 2.67–3.91% |
| 1024 | 0.53% | 2.52–3.81% |
| 390 | 3.4–4.3% (the mock's longer subtitle pushes everything down) | 5.1–5.9% |

The full-page differences are different data: rows, names, and column widths set by the table fitter.

Identical in width, height, font, colour, background, padding and radius, at every width where both render them:
- the h1 and the three tabs
- Record a delivery, Everything arrived, Cancel and Save delivery
- every table heading label
- the Today and Overdue pills
- the reason select
- the panel's h3 and help text

The late pill is 71 against 77.8 wide, because the text is "2 days late" in the mock and "29 days late" here.

Differences, all allowed by a named ruling:
- the card layout of the panel at 1024 (T-285, Q-18; the mock overflows there)
- Received now starts blank on Partly delivered (T-266)
- Expiry on every line (T-266)
- units in packs
- "(spoiled)" in brackets (the document wins)
- the Received tab's 30 days plus "Show older deliveries"
- the subtitle and the real menu

## Defects

1. **At 1024, opening a delivery history on Partly delivered squeezes a short column so that it wraps or splits.**
   - **Where:** Temple Admin (any role with the page), `http://localhost:3000/deliveries`, 1024 wide, Partly delivered tab.
   - **Steps:** press "▸ 2 deliveries" on a row.
   - **What happens:** the table is re-fitted. The Item cell takes 262–267px for the open history, and a short column collapses:
     - Order column 123 → 63px on VERIFY-B Grains, and **"PO-2026-0054" breaks across three lines ("PO-" / "2026-" / "0054")**.
       Repro: open "2 deliveries" on VERIFY-B Dal 10kg or VERIFY-B Rice. Pepper, with 1 delivery, does not trigger it.
       Screenshot: `shots/c-1024-partly-B.png`.
     - Still owed 119 → 57px on VERIFY2-C Suppliers, where "2 bags (50 Kg)" runs to four lines beside a 262px Item cell.
       Repro: open "2 deliveries" on VERIFY2-C Rice. Screenshot: `shots/c-1024-partly-mine.png`.
   - **With the histories closed**, every short cell is on one line at 1024. At 1060 and above the Order column holds 122–123px, one line.
   - **Expected:** the PO number stays whole, since the panel already keeps it `whitespace-nowrap`, and a short value is not squeezed while
     the Item column has the width.
2. **(minor, copy)** An unnamed pack line reads "1 × 500 gm (500 gm)" under Still to come, and "× 500 gm (500 gm)" beside the box, which
   says the amount twice. Seen on VERIFY-B Tea in the VERIFY-B Grains panel at 1024. `shots/ta-1024-panelcard-VERIFYBGrains.png`.
3. **(minor, API, carried from round 1)** Invalid body gives 400 before 403 (above).

## Left for Rajeev (named, not counted as defects)

- **Received tab at 1024**, histories closed: Date is 136px against Vendor 101, Items 101, Rejected 100 and Returned 100. T-285 left this
  for him.
- **Panel card mode at 1024:** Expiry sits alone on a second row of each card. The first row fills to 463 of 508px, so a 146px date box
  cannot fit beside it. T-285 flagged this as not verified as the best layout.

## Not verified

- The backend test suite (`./gradlew test`, ErrorCodeTest, RLS ITs). Not run, to avoid recompiling under the running API. The frontend
  deliveries tests were run through `tools/work-lock.sh`: `deliveries`, `delivery-history`, `record-delivery-panel` and `nav`, 59/59
  passed.
- "Show older deliveries": all data is within 30 days, and `hasOlder` is false.
- The date box shows "mm/dd/yyyy" because headless Chromium runs in the en-US locale. Native pickers follow the device, so this is not
  judged.
- The `?order=` filter link from the PO page.

## Test data left

- Vendors: VERIFY2-C Suppliers, VERIFY2-C Dairy Farm.
- Ingredients: five VERIFY2-C ingredients, plus the Bag pack on Rice.
- Orders:
  - PO-2026-0061: RECEIVED.
  - PO-2026-0062: Paneer received, then 1 Kg returned; Milk still owed.
  - PO-2026-0063: part received.
  - PO-2026-0065: part received.
- Stock movements for those receipts and that return.
