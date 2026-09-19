# Procurement rework: round-2 verification summary (2026-09-19)

Four fresh verifiers (none built or verified round 1), one per group, run in parallel on the local stack
(web :3000, API :8080, db kms_verify). Each drove Temple Admin, Kitchen Manager (ikms.kitchen-staff.5),
Kitchen Staff and Volunteer, in the UI and by direct API calls, at 1280, 1024 and 390, signed in with
minted Firebase custom tokens in headless Playwright Chromium. Test data is named VERIFY2-<group>.
The mock pages (frontend/app/dev-po, dev-deliveries, dev-invoices) and their .next/types entries were
deleted afterwards; `ls frontend/app/dev-*` finds nothing.

| Group | Scope | PASS | FAIL | Report |
|---|---|---|---|---|
| A | §9A, §5, ingredient page, vendor page, merge, library close-match, Add to inventory | 12 | 1 | VERIFY2-A.md |
| B | §4 shopping list, §6 purchase orders vs dev-po mock (D-6/D-7 excluded, T-295) | 17 | 0 | VERIFY2-B.md |
| C | §7 Deliveries vs dev-deliveries mock, incl. Kitchen Staff access (Q-1) | 21 | 1 | VERIFY2-C.md |
| D | §8 invoices, §9 payments vs dev-invoices mock | 14 | 0 | VERIFY2-D.md |

Round-1 defects: A1-A8 fixed where reported (A2 and A8 recur on the new ingredient page as A-N1, A-N3);
B D-1..D-5, D-8 fixed; C C1-C4, minor 6 and the Kitchen Staff guard fixed, minor 5 never dispatched;
D D1-D3, D5 fixed, D4 resolved by R-INV-7.

## Defects open after round 2

- A-N1: ingredient page, pack-size duplicate error KMS-400157 stays on screen after entering 0 or -5 (round-1 A2 recurring; components/ingredient/PackSizes.tsx).
- A-N2 (minor): price-trend tooltip on a pack-sold supply shows the previous price with no unit ("₹60 on 19 Sept" beside "₹1,600 / bag").
- A-N3: ingredient page phone cards at 390 show Preferred as a bare dash with no label (round-1 A8 recurring; IngredientVendors.tsx).
- A-N4 (layout): at 1024 Link a vendor puts Lead time alone on a row with 534px empty beside it; tick and button would fit there.
- A-N5 (FAIL, needs Rajeev): library copy answering "Use Ginger" for "Ginger, peeled" drops "peeled"; no preparation note saved. Overlaps held Q-12 prep-word list.
- B-N1: /orders/new type-ahead labels a gm-stocked item "gm" but the line is created in Kg (unitLabel used instead of readableUnit in app/orders/new/page.tsx).
- B-N2: /orders/new, Create order with no items (or Enter in Vendor) shows the right message plus "Check your connection" and "KMS-0000" (toApiError(null, ...) at page.tsx:391).
- C-1 (FAIL, layout): Deliveries, Partly delivered at 1024, opening a "2 deliveries" history squeezes columns: "PO-2026-0054" onto 3 lines (123 to 63px), "2 bags (50 Kg)" onto 4 lines (119 to 57px) beside a 262px Item cell. Fine from 1060 up.
- C-2 (minor, copy): unnamed pack line reads "1 × 500 gm (500 gm)".
- D-N1: invoice page at 390, a long payment reference (34 chars) or cash receiver name does not wrap; page 400px wide on a 390 screen, 10px sideways scroll.
- D-N2 (low): Invoices list at 1024 splits PO numbers over 3 lines at the hyphens (Against column 85px).
- Cross-group (A, B, C): a Volunteer POSTing a malformed body gets 400 with field errors before the 403 permission refusal. Nothing is written or leaked; valid bodies get 403 KMS-400021.

## Left for Rajeev, not counted as defects

- A-N5 above (carry the text after the comma as the note, or wait for Q-12).
- C: Received tab at 1024, Date 136px vs 100-101px for other columns; 1024 panel cards put Expiry alone on a second row.
- B: T-288 recorded mock differences: rows 20px taller (rate note), quantity box 80px vs mock 112 at every width, type-ahead 310-320px vs 256-269.

## Not verified

Backend suite (C, D; A ran 1143/1143 green), "Show older deliveries" (no data older than 30 days), the ?order= link
from the PO page, a real phone's camera sheet, opening a PDF in the bill viewer.
