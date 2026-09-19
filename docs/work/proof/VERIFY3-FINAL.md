# VERIFY3-FINAL: last check before commit (2026-09-19 PDT / 20 Sept IST)

Local stack: web :3000 (next dev), API :8080 on `kms_verify`. The verifier built none of this and changed no product code.
Headless Playwright Chromium 1134, deviceScaleFactor 1, never Rajeev's Chrome. Signed in with minted Firebase custom tokens:
Temple Admin `ikms.temple-admin.1`, Kitchen Manager `ikms.kitchen-staff.5`, Kitchen Staff `ikms.kitchen-staff.1`, Volunteer `ikms.volunteer.3`.
Scripts, screenshots and raw output: `scratchpad/v3/` (session scratchpad, `shots/*.png`).

"Measured clean" below means, at 1280, 1024 and 390: `documentElement.scrollWidth` and `body.scrollWidth` equal the viewport, 0 clipped
elements in `<main>` (scrollWidth > clientWidth with overflow set), 0 elements past the viewport or past their card, 0 console errors
(other than the dev-only hydration warning noted at the end), and no "N Sep" date without the t.

## Result

**A 17 PASS, 2 FAIL · B 12 PASS, 0 FAIL · C 10 PASS, 1 FAIL · D 4 PASS, 0 FAIL.** Totals: 43 PASS, 3 FAIL, plus 3 minor findings.

## A. Round-2 and E2E-LOCAL defects

| Defect | Result | Evidence |
|---|---|---|
| A-N1 stale KMS-400157 on ingredient pack sizes | PASS (new minor A3-1 below) | Sack 25 Kg → KMS-400157; retyping Size clears it before pressing Add (0 matches for KMS-400157 after typing). |
| A-N2 trend tooltip unit | PASS (per ruling) | E2E Bulk Rice: tooltip "₹1,500 on 20 Sept", aria "Up from ₹1,500 on 20 Sept", beside "₹1,600 / bag · ₹64 / Kg". |
| A-N3 Preferred unlabelled on 390 cards | PASS | Linked VERIFY2-A Vendor Two (not preferred) at 390: cell `data-label="Preferred"`, ::before "Preferred ", value "—". |
| A-N4 Link a vendor at 1024 | PASS | Row 2: Lead time x337 w96, Preferred tick x489, Link vendor x598 w113, all at y1225-1237. No row with Lead time alone. |
| A-N5 "Use Ginger" drops "peeled" | PASS | Archived copy e344e5a3, re-copied Allam Uragaya with Use Ginger / Use Mustard. Dialog: "Using Ginger · peeled." DB: Ginger `peeled`, Mustard `split`. |
| B-N1 type-ahead says gm, line in Kg | PASS | KS, VERIFY2-B Grains, "VERIFY2-B Jee" → "VERIFY2-B Jeera · Kg"; "Curry" → six options all "· Kg". |
| B-N2 empty order shows connection advice + KMS-0000 | PASS | Create order with no items: only "An order needs at least one item. Type one into the Items table." No "connection", no KMS code. |
| C-1 Deliveries history squeeze at 1024 | **FAIL (changed form)** | See defect F-1. PO number and "4 bags (100 Kg)" now stay on one line, but the squeeze moved into the Item cell. |
| C-2 "1 × 500 gm (500 gm)" | PASS | FINAL PO line (1 × unnamed 5 Kg pack): Expected tab "1 × 5 Kg", panel "1 × 5 Kg" and box suffix "× 5 Kg"; PO page, invoice form and invoice page "1 × 5 Kg". |
| D-N1 long reference / receiver wraps at 390 | PASS | Invoice with the 34-char reference "VERIFY2-D UTR SBIN0226019876543210" and FINAL-INV-1 with a 47-char cash receiver: scrollWidth 390, name box x41-349 inside card edge 374, 0 outside. |
| D-N2 PO number split on invoices list at 1024 | PASS | Against column 131px; every PO number 1 line at 1280/1024/390. |
| Cross-group: 400 before 403 for a Volunteer | PASS | 11 Volunteer POSTs with malformed/non-JSON/negative bodies (purchase-orders, cancel, deliveries, vendor-invoices, payments, pack-sizes, ingredients, vendors, inventory items, merge preview) all 403 KMS-400021. KM payment and KS merge preview 403 too. 0 payments written. |
| E2E-1 tick tooltip off phone screen | PASS | "Adds up to the grand total": form and page, left 204.1 / right 381.9 at 390; 1015.9 at 1024; 1271.9 at 1280. |
| E2E-2 "planned was saved." | PASS | Compose → /planner?view=day shows "The meal was planned."; /planner/2026-10-07?saved=planned shows "The meal was planned."; ?saved=Dinner shows "Dinner was saved." |
| E2E-3 "Variance" vs "Difference" | PASS | Invoices list reads "Difference ₹300"; 0 "Variance" at all widths. Invoice page "Difference · ₹20 more than received". |
| E2E-4 mixed units on Partly delivered | PASS | "4 bags (100 Kg) · 2 bags (50 Kg) · 2 bags (50 Kg)" (VERIFY2-C Rice); "4 bags (100 Kg) · 3 bags (75 Kg) · 1 bag (25 Kg)" (VERIFY-B Rice). |
| E2E-5 cash "Received by" lacks (required) | PASS | Label "Received by(required)". |
| E2E-6 "last-known prices" wording | PASS | /cost-per-serving, /issued-from-store, /today: 0 "last-known" in body text; they name "list price or market rate". |
| E2E-7 "Sep" on sheet/WhatsApp | PASS | See D; WhatsApp params for PO-2026-0075: raised "20 Sept 2026", neededBy "25 Sept 2026". |
| Shopping list 1024 squeeze (round-2 F5 leftover, T-304 claim "0px overflow") | **FAIL** | See defect F-2. |

## B. Repeating events

Data: event "FINAL Gita Reading", Sat 3 Oct, Curd Rice × 30 (a grain dish), Temple Admin.

| Check | Result | Evidence |
|---|---|---|
| Control reads as a sentence | PASS | "Repeat FINAL Gita Reading once every [1] week until [date] (i) Repeat Close". Boxes named "Weeks between repeats", "Repeat until". |
| week / weeks | PASS | N=1 "week", N=2 "weeks" (minor A3-2 at N=13). |
| Live count | PASS | Default "Makes 6 copies · last one Sat 14 Nov 2026"; N=2 "Makes 6 copies · last one Sat 26 Dec 2026"; N=1 until 28 Nov "Makes 7 copies · last one Sat 28 Nov 2026"; N=2 until 28 Nov "Makes 4 copies · last one Sat 28 Nov 2026". |
| Refusals inline, Repeat disabled | PASS | N=13 "An event can repeat every 1 to 12 weeks. Choose a number from 1 to 12." (Repeat disabled true); until 5 Oct "No copies fit…"; until 1 Dec 2027 "The end date has to be within a year from today…". |
| Interval 1 and 2 made through the UI | PASS | Weekly: "Made 7 copies · last one Sat 28 Nov 2026." Every 2 weeks (FINAL Fortnightly Class, Sun 4 Oct → 31 Dec): "Made 6 copies · last one Sun 27 Dec 2026."; 18 Oct card "Repeats every 2 weeks until 31 Dec 2026 · event 2 of 7". |
| Fasting day skipped and named | PASS | Preview and outcome: "Skips/Skipped Sat 21 Nov 2026: a dish doesn’t suit the fasting day." (Utthana Ekadasi). DB: no 21 Nov copy. Outcome notice green rgb(231,244,233). |
| No copies on past dates | PASS | API preview of "FINAL Past Kirtan" (Sat 12 Sep, weekly to 10 Oct): dates 26 Sep, 3 Oct, 10 Oct; 19 Sep dropped. Until 19 Sep → KMS-400177. Past day pages (12 Sep, 19 Sep) offer no "Repeat this event". |
| Event page says it repeats and until when | PASS | Day card "Repeats every week until 28 Nov 2026 · event 3 of 8" (grey rgb(79,81,84), 1 line 563px at 1280, 2 lines at 1024/390). Edit page: "Repeats every week until 28 Nov 2026 · event 6 of 8. Changes here apply to this date only." |
| Series cancel asks, count + last date, lists edited ones | PASS | 17 Oct: "This event repeats. Which do you want to cancel?" · Just this event / This and all later ones; focus on "Keep it". Later: "Cancels this event and 4 later ones, the last on Sat 28 Nov 2026." · "Sat 14 Nov 2026 was changed on its own and will be cancelled too." (14 Nov edited through its edit page) · "No volunteers are signed up for any of them." Button "Cancel 5 events". Same text and buttons side by side (81×44 + 138×44) at 1280/1024/390; 0 overflow, 0 clipped. |
| All-later leaves past/cooked alone | PASS | 31 Oct recorded as cooked first: after "Cancel 5 events" → "FINAL Gita Reading was cancelled on 5 dates."; DB 3 Oct PLANNED, 10 Oct PLANNED, 17/24 Oct CANCELLED, 31 Oct COOKED, 7/14/28 Nov CANCELLED. Past: a 19 Sep occurrence joined to the Past Kirtan series (SQL, test setup only); THIS_AND_LATER from 12 Sep → 19 Sep stays PLANNED, not in the later list. 10 Oct (only cooked ones later) cancels with no question. |
| Non-series event: no extra question | PASS | "FINAL One-off Talk": "Cancel FINAL One-off Talk? Its preparations come off the plan." 0 radios → "FINAL One-off Talk was cancelled." |
| Permission refusal first for a Volunteer | PASS | repeat `{}`, broken JSON, wrong types; preview with none/garbage params; later-in-series; cancel with bad scope, non-JSON and an all-zero id: all 403 KMS-400021. |
| Planner day page "The meal was planned." | PASS | See E2E-2. |

## C. §10 regression pass (each at 1280, 1024, 390, own role)

All measured clean unless stated: shopping list (KM, TA), PO create (KM, KS), PO page draft + sent (KM, KS, and E2E's PO-2026-0074),
Deliveries (KS, TA), invoice form empty and filled (TA), invoice pages (TA, incl. long reference), payments = /invoices?filter=unpaid and
/money redirect (TA), ingredient page (TA), vendor page (TA), merge screen (TA), cost per serving and issued from store (KM).
Volunteer sees "Not your page" on /deliveries, /invoices, /invoices/{id}, /ingredients/merge, /planner/{date}. Dates read "Sept" everywhere a
September date appears; 0 "N Sep".

- **FAIL: shopping list at 1024** (KM and TA): F-2.
- The FINAL flow ran end to end through the UI: PO-2026-0075 created (1 × 5 Kg) and sent by KM, delivered in full by KS ("Nothing more is owed by them."),
  FINAL-INV-1 (₹340 + ₹17 = ₹357, bill uploaded, rate "₹340 / 5 Kg · ₹68 / Kg", "₹20 more than received"), paid in cash by TA ("Payment of ₹357 recorded. This invoice is now paid in full.").

## D. Document dates

| Document | Result | Evidence |
|---|---|---|
| PO sheet HTML (`/purchase-orders/{id}/print`, PO-2026-0075) | PASS | "20 Sept 2026", "25 Sept 2026"; 0 "Sep " / ISO / slash dates. Line "E2E Bulk Rice 1 × 5 Kg ₹320 / 5 Kg · ₹64 / Kg Total ₹320". |
| Work order (`/work-orders/print`, IR-2026-0005) | PASS | "20 Sept 2026" ×3, stamp "20 Sept 2026, 01:07"; heading uses the long form "Sunday 20 September 2026" (DisplayDates.LONG_DAY, by design). |
| Job card (`/job-cards/print`, FINAL Gita Reading 3 Oct) | PASS | Stamp "printed 20 Sept 2026, 01:07"; long form "Saturday 3 October 2026". (Printing issued card number EC-2026-0012.) |
| WhatsApp PO params | PASS | "20 Sept 2026", "25 Sept 2026". |

## Defects

**F-1 (layout, C-1 in a new form). Deliveries, Partly delivered, at 1024: an opened history is crushed into a 115px Item column beside ~560px of empty row.**
Repro: Kitchen Staff, /deliveries, 1024×900, Partly delivered tab, VERIFY-B Grains, press "2 deliveries" on VERIFY-B Rice (PO-2026-0054).
Measured: Item cell 115px, PO 123, Ordered 128, Received 119, Still owed 112, Owed for 81; the row is 413px tall. The history reads one to three words a line
("19 Sept · 50 / Kg / received · / Received / by: Gopal / Das"). At 1060: Item 151px, row 287px. At 1280: Item 295px, row 187px. The other five cells hold one line each,
so the space under them is empty. Screenshot `scratchpad/v3/shots/c1-row-1024.png`. Round 2 had the short columns squeezed; T-299 made the history give way first,
which now breaks the "no dead space beside squeezed content" rule. A fix would let the open history span the row (a full-width sub-row) instead of living in the Item cell.

**F-2 (layout). Shopping list at 1024: the "No vendor yet" table scrolls sideways by 10px.**
Repro: Kitchen Manager (or Temple Admin), /shopping-list, 1024×900, scroll to "No vendor yet". `.table-wrap` scrollWidth 638 vs clientWidth 628.
Columns: Include 79, Ingredient 95, Why 85, On hand 55, Suggested 89, Order by 71, Vendor 164. In the same table "0 gm" wraps to two lines and the
"Won't arrive in time" pill to four. Page scrollWidth is 1024 (so T-304's page-level check passes), but the table itself clips 10px.
Screenshot `scratchpad/v3/shots/f-sl-1024-overflow.png`. 1280 and 390: 0 overflow.

## Minor findings

- **A3-1 (copy).** Ingredient page pack sizes: enter Size 0 → Add ("Size must be more than 0"), then type −5 → Add. Two errors show at once, "Size must be at least 0"
  (placed between Size and Unit, x641) and the stale "Size must be more than 0" under the row. They contradict each other. `shots/a-n1-minus5.png`.
- **A3-2 (copy).** Repeat control with 13 in the box reads "once every 13 week" (singular) beside the refusal. At 2 it correctly says "weeks".
- **A3-3 (alignment).** Repeat sentence at 1280: "Repeat FINAL … " text bottom at y399.4, "once every … until" at y401.0, a 1.6px baseline step.

## Notes, not defects

- Every page logs one dev-mode React warning, "Extra attributes from the server: data-finish,style" on `<html>`, from the theme pre-paint script (`lib/theme.ts`,
  committed in ddb8daa; `app/layout.tsx` has no `suppressHydrationWarning`). Next dev only; not checked in a production build. Excluded from the console counts above.
- Preview refusals (400) log "Failed to load resource" in the console; that is the expected server answer.
- PO created on /orders/new says "A purchase order for E2E Rice Traders was created."; from the shopping list it says "PO-2026-0074 was created for E2E Rice Traders." Same event, two wordings.
- PO page cancel reason option "Vendor Never Delivered this Order" is in title case.
- `frontend/app/dev-menu/` and `frontend/app/dev-kitchen-meal/` exist on disk; both are excluded via `.git/info/exclude` (`frontend/app/dev-*/`), so not committed.

## Test data left in kms_verify

Events FINAL Gita Reading (3 Oct–28 Nov; 31 Oct recorded as cooked, job card EC-2026-0012 issued), FINAL Past Kirtan (12 Sep–10 Oct, 19 Sep joined to the series by SQL),
FINAL Fortnightly Class (4 Oct–27 Dec), FINAL One-off Talk (cancelled), a KM Dinner on 7 Oct (Chitranna × 25); PO-2026-0075, its delivery, FINAL-INV-1 and its cash payment;
a new Allam Uragaya copy (30c06efd…, the T-297 copy e344e5a3 archived); VERIFY2-A Vendor Two linked to E2E Bulk Rice at ₹61 / Kg.

## Not verified

Real PDF render (local renderer is a stub; sheets checked as HTML), anything on staging, Rajeev's Chrome, a real phone, 200% zoom, the backend and frontend test suites.
