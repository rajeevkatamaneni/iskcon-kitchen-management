# VERIFY-C — Deliveries (§7 R-DEL-1..5, AC-DEL) against `dev-deliveries` mock

Verifier: fresh agent, did not build it. 2026-09-19, local stack (web :3000, API :8080, db `kms_verify`).
Headless Playwright Chromium 1134, deviceScaleFactor 1. Signed in as ikms.temple-admin.1 (Temple Admin),
ikms.kitchen-staff.1 (Kitchen Staff), ikms.volunteer.3 (Volunteer), via Firebase custom tokens after the
password-verify quota ran out mid-run. **No Kitchen Manager account exists locally, so that role was not driven.**

Screenshots and raw measurements: scratchpad
`/private/tmp/claude-501/-Users-Rajeev-Workspace-kitchen-management-system/79bf5e51-4f9e-420c-b419-747d070a92df/scratchpad/vc/`
(`shots/*.png`, `m/*.json`). Mock copied to `frontend/app/dev-deliveries/page.tsx` for the run and deleted after.

Data made through the app as Temple Admin: vendor "VERIFY-C Traders"; ingredients "VERIFY-C Rice" (Kg, pack
Bag = 25 Kg), "VERIFY-C Toor dal" (Kg), "VERIFY-C Milk" (L); PO-2026-0048 (needed 19 Sept: Rice 4 × Bag, Toor
dal 50 Kg) and PO-2026-0049 (needed 21 Sept: Milk 20 L), both sent. (Adding the vendor supplies through
`/vendors/{id}/supplies/bulk` returned KMS-400001 with no field errors; not needed for this group, not chased.)
A past needed-by date can't be made (KMS-400014), so the late pill was checked on the existing Heritage Fresh
Dairy line PO-2026-0029 (needed 21 Aug).

## Results per AC

**R-DEL-1 menu — PASS.** Temple Admin menu: `… Shopping list | Purchase orders | Deliveries | Vendors …`.
Volunteer: no Deliveries item; /deliveries shows "Not your page".
**R-DEL-1 API refuses — PASS.** Volunteer: GET /api/v1/deliveries 403 KMS-400021; POST /api/v1/deliveries with
a valid body 403; GET /deliveries/received 403; POST /purchase-orders/{id}/receipts 403.
Kitchen Staff was refused (403 on all three) at the start of the run. Then F10 went live mid-run, and Kitchen
Staff now gets GET /deliveries 200 and posted a receipt (1 L Milk, "Gopal Das") through
/purchase-orders/{id}/receipts, as Q-1 intends.
**F10 is half done:** Kitchen Staff's menu now shows Deliveries, but the page still refuses them ("Not your
page"), because `app/deliveries/page.tsx:61` is still `RequireRole roles={["TEMPLE_ADMIN","KITCHEN_MANAGER"]}`.
Marked pending F10, not a defect of this group.
**No prices — PASS.** No "₹", "price" or "rate" in any captured state (3 tabs, panel, history, at 1280 and
390). The /deliveries JSON has no price field. The goods receipt lines have `unit_price` NULL.
**R-DEL-2 three tabs + summary strip — PASS.** The tiles are "Due today · Overdue · Partly delivered ·
Received this week": same StatTile, same geometry as the mock. Overdue is amber when above 0.
**R-DEL-3 one button per vendor, across orders — PASS.** VERIFY-C Traders has one card, "3 items on orders
PO-2026-0048, PO-2026-0049", and one Record a delivery. The panel lists Rice, Toor dal and Milk, each with its
order number underneath at 12px, colour ink-muted.
**Received / Rejected + reason / Expiry — PASS.** The Reason box stays disabled until a rejected amount is
typed. Expiry is on every line: an allowed difference (T-266 ruling).
**Everything arrived — PASS.** Blank → Rice 4, Toor dal 50, Milk 20.
**Ordered unit → stock unit — PASS.** "4 bags (100 Kg)" to come. Typed 4 bags → stock_movements
`VERIFY-C Rice 100.000 KG PO_RECEIPT`.
**Green confirmation — PASS.** InlineNotice bg rgb(231,244,233) text rgb(56,105,68) (success). Text: "Delivery
from VERIFY-C Traders recorded. 2 items went into stock. 2 lines are still to come from them."
**Rejected stays owed — PASS.** 30 received + 2 rejected (spoiled) → still to come 20 Kg, stock +30 only, with
"includes 2 Kg rejected 19 Sept [Today]" under it.
**Partial, then rest — PASS.** Rest recorded from the Partly delivered tab, same blank panel, 20 Kg.
Confirmation: "… VERIFY-C Toor dal is complete: 50 of 50 Kg. 1 line is still to come from them." PO-2026-0048
status is RECEIVED. Stock is 30 + 20.
**R-DEL-4 history wording — PASS.** Opened with Tab + Enter:
`19 Sept [Today] · 30 Kg received · 2 Kg rejected (spoiled) · Received by: Karuna Murti Das`,
`19 Sept [Today] · 20 Kg received · Received by: Karuna Murti Das`,
`Received 50 of 50 Kg ordered · complete 19 Sept [Today]`. Before completion it read "Received 30 of 50 Kg
ordered". No order number appears. `aria-expanded` goes false → true (by click and by Enter), with
`aria-controls`. Colour is ink-secondary, 14px. The same component is used on `app/orders/[id]` (code-read).
"(spoiled)" in brackets follows the document, where the mock has ", spoiled" (document wins).
**R-DEL-5 pills — PASS.** Today: 47×20, 12px/600, bg info rgb(229,242,253), text rgb(50,96,134), padding 2px 8px,
radius 5px. Late: 77.8×20 ("29 days late"), same font, padding and radius, warning bg rgb(249,238,226). The date
is always written ("21 Aug", "19 Sept"). The same numbers were measured at 390.
**No "Price paid" — PASS.**
**AC-DEL: nothing truncated, no sideways scroll, at 1280 and 390 — PASS for the Deliveries content, with one
exception in the shared sidebar.** scrollWidth equals the viewport in every state, and nothing inside `<main>` is
clipped. (The only clipped elements are sr-only spans and the phone-card thead, which are hidden on purpose.) At
390 nothing runs past a card.
**The exception, defect 2:** the shared Sidebar clips "ISKCON South Bengaluru" (278px of text in a 231px box,
text-overflow clip) and "Karuna Murti Das" (102 in 99, ellipsis), at 1280, 1024 and 390.

## Mock comparison (mock also signed in, so the tenant theme applies to both)

Unsigned, the mock uses the default palette, and every colour differed. Signed in, every static element
matches: h1, tabs, tiles, Record/Everything arrived/Cancel/Save buttons, table headings, pills, h3 and the
panel's help text. Width, height, font, colour, background, padding and radius were all equal at 1280 and 390.

Pixel diff (pixelmatch, threshold 0.1):
- Header, tiles and tabs region at 1280: 703 px, 0.25%. That is the tile values, which come from the data.
- The same region at 390: 3.8–4.3%. That is the mock's longer subtitle ("Sample data…", an allowed difference)
  pushing everything down 26px.
- Full page over the common height: 1.9–3.3% at 1280 and 4.8–5.8% at 390. That is different data: rows, vendor
  names and column widths set by the table fitter.

Allowed differences seen:
- the real menu
- the subtitle
- "Received now" starts blank on Partly delivered too
- the panel lists all of the vendor's owed lines from either tab
- Expiry on every line
- units in packs, "4 bags (100 Kg)"
- Received tab: 30 days + "Show older deliveries"
- "(spoiled)" in brackets

**Differences not named anywhere (defect 1):** the entry grid in the panel is not the mock's.
- The mock has 20px cell padding, inside the panel's padding.
- The real one has 12px, with 20px only at the row ends, and runs to the card edges (`GRID_SPACING`, lg:-mx-5).
- Measured at 1280: headings "Received now" 164.6→143, "Rejected on delivery" 177.6→156, Reason 159.6→138,
  Expiry 186→178, heading row height 65→45.
- The builder's comment says the mock's own grid ran past the card. I did not reproduce that: the mock's Heritage
  panel measured 0px past the card at 1280.
- Needs a conductor or Rajeev ruling, or a revert to the mock.

## Defects

1. **Panel grid spacing differs from the mock** (above). Repro: open Record a delivery at 1280, then measure
   the th padding: 12px where the mock has 20px.
2. **Sidebar truncates the temple name and the user's name** (shared component, every page). Repro: sign in as
   Temple Admin at any width. The name reads "ISKCON South Benga", and the user reads "Karuna Murti D…".
   `shots/sidebar-1280.png`.
3. **F5 range, and worse than a squeeze: at 1024 the Record a delivery panel is clipped by its card.**
   - Measured past the card edge: 40px at 1024, 4px at 1060, 0 from 1100 up.
   - "Everything arrived", "Save delivery" and the Expiry box are cut off (the card has overflow-hidden).
   - The item name breaks mid-word ("VERIFY-" / "C Milk"), and so does the order number ("PO-" / "2026-" /
     "0049"). The item cell is 78.6px wide.
   - Repro: 1024 wide, Expected → VERIFY-C Traders → Record a delivery. `shots/crop-1024-panel.png`.
   - Also at 1024 on the Received tab: Date is 136px wide while Vendor and Items get 101 each.
4. **Returned column drops the reason and date** that the mock shows ("Paneer 1 Kg, spoiled, 17 Sept"). The
   real one shows item and amount only, because the API doesn't carry them (the page's own comment says so).
   Code-read only: no return was made in this run.
5. **(minor, API)** A user without the permission who POSTs an *invalid* body to /api/v1/deliveries or
   /purchase-orders/{id}/receipts gets 400 with field errors, not 403. Bean validation runs before
   @PreAuthorize. A valid body is refused 403. It leaks no data.
6. **(minor)** `DeliveryHistory`'s Today pill uses the browser clock (`todayIso()`), while the rest of the page
   uses the server's `DeliveriesView.today`. Code-read only.

Not verified:
- Kitchen Manager role (no account).
- §10 item 2, the test suites (not run by me).
- `?order=` link from the PO page.
- "Show older deliveries" (no data older than 30 days was reached).
- The expiry date's effect on the batch.

Test data left: vendor VERIFY-C Traders, the three VERIFY-C ingredients, PO-2026-0048 (RECEIVED),
PO-2026-0049 (1 of 20 L received by Kitchen Staff through the old endpoint), and stock movements +100 Kg Rice,
+50 Kg Toor dal, +1 L Milk.
