# EPIC 5 — Ordering System & Vendor Management

**Goal:** Vendor records, auto-generated shopping lists from shortfalls + thresholds, purchase orders with translation/PDF/WhatsApp delivery, receiving with partial/rejected handling that updates inventory, and invoice capture feeding Payments.
**Depends on:** Epics 1–4. **Blocks:** E7-S9 (invoice payment).
**Labels:** `epic:ordering`

---

## E5-S1 — Vendor management

**Verified by:** [UAT-037](../uat/UAT-037-vendors.md)

**As a** Kitchen Staff member, **I want** vendor records with contact details and supplied items, **so that** ordering knows who sells what and where to send the PO.

**Assumptions:** Staff-managed only (locked: no vendor logins). Vendor phone is the WhatsApp destination — capture and validate accordingly.

**Amended:** 2026-08-31 (review item V1, signed off by Rajeev). *"Deactivation hides from pickers,
preserves history"* was one line and it was half true: the deactivation was recorded, and **why**
was not. The requirements and criteria below replace it. Automated cover for the amendment: the
*reason a vendor was dropped* and *contract end date* blocks in `VendorIT`, and
`frontend/__tests__/vendors.test.tsx`.

### Decisions

**D1 — The reason is history, not a field (2026-08-31).** `vendors.notes` already existed and was
the obvious place; it is the wrong one. It is one overwritable line with no author and no date, the
next edit destroys what was there, and — the actual defect the reviewers walked into — neither the
deactivate nor the reactivate endpoint ever touched it. **Putting the reason on `notes` was
considered and rejected on that**, and `notes` stays exactly as it is for everything else.

The history lives in its own table rather than on the audit trail, and that is the part worth
reading. The audit log answers to `VIEW_AUDIT_LOG`, which only a Temple Admin holds; vendors answer
to `MANAGE_VENDORS`, which a Kitchen Manager and Kitchen Staff hold too. **Recording it only in the
audit log was considered and rejected**: the person deciding whether to bring a supplier back would
then need the permission that also exposes every donation amount and pay change in the temple, to
read one sentence about a vendor. Audit retention is a second reason — the compliance record has its
own rules about how long it is kept, and this one has to be readable for as long as the vendor
exists. The precedent is `equipment_state_changes` (`V16`), which is the same shape for the same
reason.

**D2 — A contract end date warns, and never acts.** The other half of the comment asked for
**validity dates**, and the automatic version is **rejected**. A date-bounded vendor needs a job to
flip the active flag, and one morning it silently drops that vendor out of the preferred-vendor
lookup that feeds the shopping list. The list then suggests somebody else, and nobody can say why,
because the cause was a date set months ago by somebody who has forgotten it. Active/inactive plus a
dated, attributed reason gives the whole audit trail with none of the time bomb. So the date is
recorded and warned about: no job reads the column, no query filters on it, and a vendor whose
contract ended last March is fully active and fully selectable until a person decides otherwise —
and when they do, they leave a reason.

The warning horizon shipped at seven days, deliberately the same
`InventoryItemService.DEFAULT_EXPIRY_WINDOW_DAYS` the stock screens use for a batch nearing expiry,
with the question of whether a contract wants its own number left open.

**Answered, 2026-08-31 (Rajeev).** Seven days is too short to renegotiate a commercial contract, and
the resolution is the one written down above: **both horizons moved into temple settings together**
(`V85`), not just the one that changed. A temple that found the contract warning settable and the
stock warning nailed down would be looking at exactly the mismatch the shared constant was avoiding.

- `tenant_settings.stock_expiry_warning_days`, default **7** — unchanged behaviour for every temple.
- `tenant_settings.contract_end_warning_days`, default **30** — a month's notice to renegotiate. The
  migration writes both to every existing temple explicitly rather than leaving it to a column
  default, because a temple that has never opened the settings screen has no row for one to reach.
- Both are **1 to 365**, enforced by a `CHECK` and by bean validation. Zero warns on the morning the
  thing has already ended, and beyond a year every contract is badged from the day it is entered.
- Set on the settings screen under **Warnings**, behind `MANAGE_TEMPLE_SETTINGS`, both in one
  section and saved by one button — which is what keeps somebody from moving one and forgetting the
  other. Neither is read by any job; they still only decide which rows carry a badge.

**Requirements:**
- Vendor CRUD: name, contact person, phone (E.164, flagged if not WhatsApp-reachable after first send failure), email (optional), address, GSTIN (optional — appears on POs when present), preferred language for PO documents (from tenant's configured language list), notes, active flag.
- Vendor ↔ ingredient supply mapping with optional last-known price (price history is Phase 2; one current price field only).
- Preferred-vendor-per-ingredient designation (consumed by E5-S2 suggestions and E3-S1).
- Deactivation hides from pickers and preserves history.
- **A reason is required to make a vendor inactive** (`KMS-4011`), and optional to bring one back.
  Each change writes a row to `vendor_status_changes` (`V83`) — from, to, reason, actor, timestamp —
  which is `enable_tenant_rls()` and `make_append_only()` like every other history in this schema.
  The vendor's page shows it newest first, under *Active and inactive*, with the author's name and
  the date; an entry with no reason reads *No reason given* rather than as a blank.
- The audit log keeps recording `VENDOR_DEACTIVATED` / `VENDOR_REACTIVATED` exactly as before, now
  carrying the reason as its note. The two records are not redundant; see D1.
- **A contract end date on the vendor** (`vendors.contract_end_date`, `V83`), editable on the vendor
  form, labelled *Contract ends* with the hint *Only a reminder. Nothing switches off on this date.*
  Within the temple's own contract warning horizon (`V85`, thirty days unless changed) — or any
  time after it — the vendor's page and the vendors list carry a warning saying when the contract
  ends or ended, and that they are still active and can still be ordered from.

**Acceptance criteria:**
- [ ] Vendor with supplied-items mapping created; appears in ingredient's preferred-vendor picker.
- [ ] Invalid phone rejected at entry; deactivated vendor vanishes from new-PO flows but old POs render.
- [ ] Preferred language stored and later drives PO translation default (E5-S5 contract).
- [x] Deactivating without a reason is refused with `KMS-4011`, and the vendor is left untouched — a refused deactivation is not a half-done one.
- [x] A blank body and no body at all are the same mistake and get the same message.
- [x] The reason comes back with the author's name and the moment it was written.
- [x] A vendor dropped twice reads as two entries, newest first, and neither is edited by the other.
- [x] Bringing a vendor back accepts a reason and does not demand one.
- [x] The history refuses UPDATE and DELETE through the application's own unprivileged role, at the database.
- [x] A contract that ran out warns, and the vendor stays active, stays listed and stays the preferred source.
- [x] A contract ending far off does not warn; the date is editable like any other field.
- [x] The contract horizon is the temple's own setting, defaulting to thirty days, and changing it
      changes which vendors warn. Out-of-range values are refused at the boundary and by the
      database.

---

## E5-S2 — Auto-generated shopping list

**Verified by:** [UAT-038](../uat/UAT-038-the-shopping-list.md), [UAT-039](../uat/UAT-039-generate-purchase-orders.md)

**As a** Kitchen Staff member, **I want** a suggested shopping list computed from meal-plan shortfalls and reorder thresholds, **so that** procurement starts from data, not memory.

**Assumptions:** Two input streams, merged per ingredient: (1) shortfall feed from E4-S5 (horizon: 14 days + festivals within 30, per that story's contract); (2) below-threshold items from E3-S3 topped up to threshold + safety factor (tenant config, default 1.2×). Suggested vendor = preferred vendor; suggested need-by = earliest demanding meal date minus lead buffer (tenant config, default 2 days).

**Requirements:**
- Nightly job regenerates the draft list; on-demand "Regenerate now" button (matches wireframe).
- List line: ingredient, current stock, needed-by date, suggested qty (with which stream(s) drove it, expandable), suggested vendor (editable), include/exclude checkbox — mirrors approved wireframe.
- Suggested quantities rounded up to sensible purchase units (Kg/L integers by default; per-ingredient pack-size override optional field).
- Sattvic guard: prohibited ingredients cannot enter the list except via the E2-S4 override rule (admin, reason, audit) — same enforcement point.

**Acceptance criteria:**
- [ ] Seeded scenario (planned festival meal + low-stock staple) produces correct merged quantities with provenance visible per line.
- [ ] Regenerate is idempotent: unedited lines refresh, human edits to vendor/qty/inclusion survive regeneration (edit-preserving merge).
- [ ] Garlic cannot appear on a generated list for a non-overridden tenant (test).
- [ ] Contract test with E4-S5 shortfall API passes both directions.

**Decisions:**
- **D1 — It is the shopping list, not the order list (OL1, 2026-08-31).** The screen was called the
  order list and never was one: it is a proposal of what to buy, computed from demand and editable
  in full before anything is committed to anybody. Orders are the next screen along, `/orders`, and
  they are what a vendor receives — two neighbouring destinations both named after orders is how a
  draft gets sent to a vendor. **"Purchase plan" was considered and rejected**: it is finance's
  word, and this application's vocabulary is deliberately the temple's. The person who carries the
  list to the market calls it the shopping list, and so does the temple. Renamed in one pass — the
  route, the API path, the Java package, the table (V81), the menu and every document — so nothing
  is left saying one thing on the screen and another in the code. `/order-list` permanently
  redirects, because somebody has it bookmarked.

---

## E5-S3 — Purchase order generation and lifecycle

**Verified by:** [UAT-039](../uat/UAT-039-generate-purchase-orders.md), [UAT-040](../uat/UAT-040-purchase-order-lifecycle.md)

**As a** Kitchen Staff member, **I want** approved order lines grouped into per-vendor POs with a tracked lifecycle, **so that** what we asked for, from whom, by when, is always unambiguous.

**Assumptions:** Lifecycle: `DRAFT → SENT → PARTIALLY_RECEIVED → RECEIVED / CANCELLED` (receiving drives the last states, E5-S6). PO number: per-tenant sequential with prefix (e.g. `PO-2026-0042`).

**Amended:** 2026-08-31. The needed-by date is now something a temple can say, not only something
the shopping list worked out. `purchase_orders.needed_by` had existed since V26 and been written
only by generation; no screen exposed it, so "we actually need this by Friday" could not be said at
all. It is now editable on a draft and frozen at SENT. No migration: the column was already there.
Automated cover for the amendment: the needed-by block in `PurchaseOrderIT`, the needed-by tests in
`frontend/__tests__/order-detail.test.tsx`, and `frontend/__tests__/format.test.ts`.

### Decisions

**D1 — The date is the temple's to set on a draft, and nobody's to move once the order is sent.**
The freeze is enforced on the server, in `PurchaseOrderService.update`, and refused with
`KMS-4919`; the screen shows a readout and no field at all, so a sent order does not offer an edit
that would be refused when pressed. Two reasons, and the second is the one that matters. First, the
date has been read out to a vendor — on the sheet, on WhatsApp — so moving it afterwards changes
what they were asked for without telling them. Second, it is the line the vendor scorecard measures
on-time against (E5-S9 D3). Leave it editable after sending and anybody can rewrite a supplier's
record after the deliveries have already happened, and the scorecard stops being evidence about
anything. **Editable only up to SENT is therefore not a tidiness rule about drafts**; it is what
makes the number underneath E5-S9 worth reading.

**D2 — Inside the lead buffer we warn. We do not refuse.** The shopping list plans with a two-day
buffer (`ShoppingListService.LEAD_BUFFER_DAYS`), subtracting it from the first meal that needs an
ingredient. That is a planning default, not a fact about what a supplier can do — the vegetable
seller two streets away can manage tomorrow, and a festival can move under everyone. A hard floor
there would refuse a request the temple can genuinely make, and the reliable consequence of refusing
a true thing is that people type a date they do not mean, which corrupts exactly the column E5-S9
reads. So the screen says *Sooner than the 2 days a vendor usually gets* and lets it through. **Only
a date behind the order's own date is refused** (`KMS-4014`), because that is not a request anybody
can act on — and it would score the vendor late from the moment the order was raised.

**D3 — The rule is about a date somebody typed, never about one that was computed.** Generation from
the shopping list derives needed-by from demand, and that arithmetic legitimately lands in the past:
a meal planned for tomorrow, less two days, is yesterday. Refusing it there would break the shopping
list rather than protect anything, so the floor is applied on manual creation and on the draft edit
only. Generation's own computation is untouched.

**D4 — An order is dated the temple's day.** `order_date` was `CURRENT_DATE`, which the driver
evaluates in whatever time zone the JVM happens to run in — so an order raised at 02:00 in Bengaluru
was dated the previous day by a server in UTC. It is now `LocalDate.now(Asia/Kolkata)`, matching
`nextPoNumber` beside it. Found while building D2's floor, which is measured against this date.

**D5 — Blank stays allowed.** The column is nullable and an order with nothing to meet is a real
order — a standing top-up, a vendor who comes when he comes. Clearing the field sends null rather
than omitting it. E5-S9 counts those aside as *orders without a needed-by date* rather than scoring
them a silent hundred per cent, so the honest empty is already handled downstream.

**Requirements:**
- "Generate POs for selected" → one draft PO per distinct vendor from checked lines (wireframe flow); manual PO creation also possible.
- PO: header (vendor, dates, delivery location free-text), lines (ingredient, qty, unit, optional expected price), notes; editable in DRAFT only.
- The needed-by date is editable on a DRAFT, pre-filled with whatever is there, and may be cleared.
  Refused if it falls before the order's own date (`KMS-4014`); warned but accepted inside the lead
  buffer. Frozen at SENT, on the server.
- State transitions with guards + timestamps + actor; cancel requires reason; every transition audited.
- PO list with status filters; per-PO activity trail.

**Acceptance criteria:**
- [ ] Three checked lines across two vendors → exactly two correct draft POs.
- [ ] Illegal transitions (e.g. edit after SENT, receive a DRAFT) rejected at service layer.
- [ ] PO numbering monotonic per tenant, gap-tolerant, never duplicated (concurrency test).
- [x] A draft's needed-by date can be set, changed and cleared; the change is refused on a sent
      order and the date the vendor was given still stands.
- [x] A needed-by date behind the order's own date is refused with `KMS-4014`.
- [x] Generation from the shopping list still computes the date it always did, including one that
      falls in the past.

---

## E5-S4 — PO document: PDF and print (English)

**Verified by:** [UAT-041](../uat/UAT-041-the-po-sheet.md)

**As a** Kitchen Staff member, **I want** a clean PO document, **so that** the vendor gets an unambiguous order sheet.

**Assumptions:** Reuses E2-S5 pipeline (Playwright/Chromium, GCS, signed URLs). Document: temple identity block, PO number/date, vendor block (incl. GSTIN when present), lines table, need-by date, notes, signature space.

**Requirements:**
- Generate on SENT (auto) and on demand; browser print view too.
- Amounts optional (POs may omit prices when the temple negotiates on delivery — price column renders only if any line has one).
- Regeneration on the rare post-SENT admin correction produces a new document version, prior versions retained.

**Acceptance criteria:**
- [ ] PDF renders correctly with and without price column; long line-sets paginate cleanly.
- [ ] Document versions retained and downloadable; latest clearly marked.
- [ ] Print view sane on A4 without tweaks.

---

## E5-S5 — PO translation

**Verified by:** [UAT-042](../uat/UAT-042-po-in-the-vendors-language.md)

**As a** Kitchen Staff member, **I want** the PO rendered in the vendor's language, **so that** a shopkeeper who doesn't read English gets an order he can actually fill.

**Assumptions:** Locked requirement. Reuses E2-S6 translation service + glossary (ingredient names are the same vocabulary — one glossary serves both). Default language from vendor record (E5-S1), overridable per document.

**Requirements:**
- "Translate PO" → background job → translated document stored alongside English version; both downloadable/printable.
- Translated: line-item ingredient names, units presentation, notes, static labels (template-level translations for labels — not MT, done once per language properly). Numbers/dates/PO number untouched.
- Glossary consulted first, MT for the rest, provenance recorded — same rules as E2-S6.

**Acceptance criteria:**
- [ ] Hindi and Telugu POs render correctly (script shaping verified in PDF; UAT includes native-reader check).
- [ ] Glossary term override wins over MT on a seeded ingredient.
- [ ] Vendor's default language pre-selected; override works per document.

---

## E5-S6 — Receiving: full, partial, and rejected deliveries

**Verified by:** [UAT-044](../uat/UAT-044-receiving-a-delivery.md)

**As a** Kitchen Staff member, **I want** to record exactly what arrived — including short and rejected goods — **so that** inventory reflects the truck, not the order.

**Assumptions:** Locked Phase 1 feature. Receiving event per delivery against a PO; multiple receipts per PO allowed. Received quantities (not ordered) write `PO_RECEIPT` movements with batch/expiry/received-date; rejected quantities recorded with reason (`DAMAGED/SPOILED/WRONG_ITEM/OTHER`) and do NOT touch stock; shortfall (ordered − received − rejected still outstanding) keeps PO `PARTIALLY_RECEIVED` and re-feeds the shopping list.

**Amended:** 2026-08-31 (review item INV1, signed off by Rajeev). The delivery now captures what was
actually paid. Automated cover for the amendment: the price block in `ReceivingIT`, and
`frontend/__tests__/order-detail.test.tsx`.

### Decisions

**D1 — The delivery is where the price becomes true (INV1, 2026-08-31).** `vendor_supplies.last_price`
had exactly one writer from `V24` until now — a person typing into a box on the vendor screen — and
it aged from the moment they stopped. Nothing in the system noticed or said so, while the shopping
list, every costing figure and the price the temple quotes for **outside catering** all read it as
current. That last one is the argument: the temple takes money for catering, and pricing it off a
hand-typed figure nobody has revisited in a year is how a temple loses money without seeing it
happen. The lorry brings a bill and the storekeeper standing in front of it is the one person in the
building who knows what was paid. **The reviewers asked for a moving average maintained from
invoices, and that is not what this is**: an average is a valuation model laid over a number nobody
is capturing. Capture first. What to do with the captured figure — a batch price, which this system
can do better than an average because FEFO already knows which lot every draw came from — is a
separate, later decision.

**D2 — Null is not zero.** Three ordinary things arrive without a price: a delivery ahead of its
bill, a donation, and a line rejected in full. The column is nullable and must stay so; a null never
overwrites a `last_price` somebody typed, and nothing downstream sums it as a zero. "The bill has
not come yet" and "this costs nothing" are different statements and the schema keeps them apart.

**D3 — Convert, don't assume.** The receipt price is per the line's own unit; `last_price` is per
the *ingredient's canonical unit*, which is how `BasketCostingService` and the printed purchase
order already read it. Every path that creates a PO line copies the canonical unit onto it, so in
practice the two agree — but manual PO creation accepts any of the five units, so the conversion is
done through `Unit.baseFactor()` rather than assumed. At ₹0.05 per gram a kilo is ₹50, and writing
0.05 into a per-Kg column would be wrong by a factor of a thousand. Where the two units are in
different families — ₹ per litre against an ingredient held in kilos — nothing is written back and
the mismatch is logged. **Guessing a density was rejected**: a stale price can be recognised as
stale, and a wrong one cannot.

**D4 — No variance threshold, and no approval step.** The expected price is a visible hint and
nothing more. There is no flag for a price that differs from it and nothing to approve, because the
temple negotiates in the real world and a delivery that came in dearer is a fact rather than an
exception — E5-S8 already takes the same line about invoice variance. A threshold here would put a
gate in front of the storekeeper at the one moment they are standing in a doorway with a lorry
waiting.

**D5 — Rejected goods do not reprice.** A line the temple refused was not bought, so it teaches
nothing about what the vendor charges. It is still recorded with its reason, and E5-S9 reads those
reasons back.

**Requirements:**
- Receiving screen per PO: line-by-line received qty, batch id, expiry date, rejected qty + reason; delivery note/photo upload optional (GCS).
- Movements written atomically with the receiving record; PO status auto-derives (`PARTIALLY_RECEIVED`/`RECEIVED`).
- Outstanding-quantity query feeds E5-S2 regeneration (short 6kg → next draft list includes it with provenance "PO-2026-0042 short").
- Idempotency: a receiving submission is a single unit; duplicate submission guarded (idempotency key per SYSTEM_DESIGN.md §6).
- **A price per line, captured where the goods arrive.** `V82` adds a nullable
  `goods_receipt_lines.unit_price`, in rupees per one of the line's own unit. The receiving form
  carries a *Price paid* box per line, pre-filled from the purchase order's expected price and
  hinted underneath with *expected ₹45 / Kg*, and the storekeeper corrects it against the bill that
  came with the lorry. Where a price is given on goods that were actually received, it is written
  back to `vendor_supplies.last_price` for that vendor and ingredient, creating the supply row if
  this vendor has never been recorded as supplying it — they now demonstrably have. `preferred` is
  never touched: a delivery says what a thing cost, not who the temple would rather buy it from.

**Acceptance criteria:**
- [ ] 36 ordered / 30 received / 2 rejected-spoiled → stock +30 with correct batch, rejection recorded, PO `PARTIALLY_RECEIVED`, 6 outstanding appears in next generated list with provenance.
- [ ] Second receipt completing the PO flips status to `RECEIVED`.
- [ ] Double-click/duplicate submit cannot double-book stock (idempotency test).
- [x] Rejected goods never appear in stock; rejection reasons queryable per vendor — **discharged by E5-S9**, which reports them per vendor and by reason.
- [x] A price entered at receiving lands on the receipt line and on the vendor's supply row.
- [x] A blank price is sent as null, not as zero, and changes no existing price.
- [x] A line rejected in full writes no price back — nothing was bought at that price or any other.
- [x] A price per gram is converted before it is written to a per-Kg column, and a price whose unit family the ingredient's cannot express is not written back at all.
- [x] Re-submitting the same delivery under the same idempotency key does not re-price anything.
- [x] The form shows what was expected beside the box, per the line's own unit.

---

## E5-S7 — WhatsApp PO delivery

**Verified by:** [UAT-043](../uat/UAT-043-send-a-po-on-whatsapp.md)

**As a** Kitchen Staff member, **I want** to send the PO to the vendor on WhatsApp from the app, **so that** ordering matches how Indian vendors actually communicate.

**Assumptions:** Locked requirement. Notification service (E1-S10) with an approved utility template carrying the PO document (PDF attachment or signed link, per Meta template capabilities — implementation verifies which; attachment preferred). Translated version sent when available, else English with a note.

**Requirements:**
- "Send via WhatsApp" on a SENT PO (or transitions DRAFT→SENT as part of sending, with confirmation); records message + delivery status on PO trail.
- Failure (undeliverable number) surfaces on the PO with fallback guidance (download + manual share); vendor record flagged for phone recheck.
- Resend allowed with rate guard (no accidental spam-looping a vendor).

**Acceptance criteria:**
- [ ] Test vendor number receives the PO document via WhatsApp in staging; delivery status lands on the PO trail via webhook.
- [ ] Undeliverable number → clear failure state on PO + vendor flag, no crash, fallback path usable.
- [ ] Send + resend audited with actor and timestamps.

---

## E5-S8 — Vendor invoice capture

**Verified by:** [UAT-045](../uat/UAT-045-record-a-vendor-invoice.md)

**As a** Kitchen Staff member, **I want** to record a vendor's invoice against its PO, **so that** Payments has a clean queue of what the temple owes.

**Assumptions:** Staff-entered (no vendor portal). Invoice ↔ PO linkage required for release 1 (invoices without POs — e.g. cash market purchases — recorded via a minimal "direct invoice" flag on the same entity, no separate flow). Payment execution/recording is E7-S9, not here.

**Requirements:**
- Invoice: vendor, PO ref (or direct flag + description), invoice number, date, amount, due date, scanned copy upload (GCS), status (`PENDING/PAID` — status flips in E7-S9).
- Mismatch surfacing: invoice amount vs received-quantity expectation (when line prices exist) shows an informational variance, doesn't block — the temple negotiates in the real world.
- Invoice list with status/due-date filters (wireframe's Invoices tab); overdue badge.

**Acceptance criteria:**
- [ ] Invoice against a received PO created with scan attached; appears PENDING in list and in E7's payment queue (contract test).
- [ ] Direct (no-PO) invoice recordable with description; clearly distinguished in list.
- [ ] Variance indicator appears when prices exist and differ; absent otherwise.
- [ ] Duplicate invoice number per vendor warns (soft — vendors reuse numbering schemes imperfectly).

---

## E5-S9 — Vendor performance

**Status:** DONE 2026-08-31 (review item V2, signed off by Rajeev).

**Verified by:** [UAT-077](../uat/UAT-077-vendor-performance.md). Automated cover:
`VendorPerformanceIT`, and `frontend/__tests__/vendor-performance.test.tsx`.

**As a** Kitchen Staff member, **I want** to read how each vendor has actually performed — what
arrived on time, how much of an order turns up, what was refused and what is still open — **so that**
deciding who to keep buying from is a reading rather than a recollection.

**Assumptions:** No new data capture and no migration. Everything the report needs is already kept:
`purchase_orders.needed_by` is what was asked for, `goods_receipts.received_at` is what happened,
`goods_receipt_lines` holds the quantities and the reject reasons, and both receipt tables are
append-only, so the history behind a figure cannot have been edited after the fact.
`RejectReason.java` says in as many words that the reasons were kept *"so a vendor's reliability can
be read back (Phase 2 scorecard)"*; this is that, and it discharges E5-S6's last acceptance
criterion.

### Decisions

**D1 — On time is per order, not per ingredient.** `needed_by` lives on the purchase-order header,
so an order of eight things is one observation, whichever of the eight was late. **Moving the date
onto the line was considered and rejected**: it would have to be typed by whoever raises the order,
on every line, purely to serve a report — a cost paid daily by the kitchen for a number read
monthly. For a scorecard the order is the right grain, and the screen says *Orders on time* in as
many words rather than leaving a reader to assume it counted ingredients.

**D2 — Fill rate, which nobody asked for.** On time tells you the lorry arrived; it does not tell
you whether it brought everything. A vendor who is always punctual and always short is exactly the
vendor you want to know about, and neither figure alone finds him. It is computed per line as a
fraction and averaged, never as a sum of quantities — 36 kilos of rice and 10 litres of oil do not
add up to 46 of anything, and a vendor's fill rate must not depend on which units their ingredients
happen to be held in. Accepted quantity only: a rejected sack was delivered and did not feed
anybody, and it is counted again by reason in its own column.

**D3 — The clock stops at the first receipt, and an order is judged only once its date has
strictly passed.** Two rules that hold each other up. Judging at the *first* receipt is knowingly
generous — a vendor who drops one sack on the due date and the rest a fortnight later scores on time
— and it is generous on purpose, because the fill rate beside it is what catches him, and the pair
says something neither figure says alone. **Measuring at completion was considered and rejected**
for a second reason as well: `ReceivingService.isFullyReceived` ignores rejected quantity, so an
order with anything refused stays `PARTIALLY_RECEIVED` until somebody re-delivers, and "completed"
would then be partly the temple's own timetable rather than the vendor's. *Strictly* passed, because
an order due today can still arrive today. An order with no needed-by date can never be judged —
there is nothing to be late against — so it is counted aside rather than scored a silent hundred per
cent.

**D4 — Too few orders is marked, not modelled and not hidden.** Below five judged orders a vendor is
labelled *Too few orders to rank* and sorted beneath the ranked ones, with the counts behind every
percentage on the screen beside it. There is no confidence interval and no statistical model: with
three orders one late lorry moves the figure thirty points, which makes it a statement about the
sample rather than about the supplier, and the honest answer is to say so rather than to compute a
better-dressed one.

**D5 — A deactivated vendor stays on the report, marked.** Their record is precisely what somebody
reads before bringing them back, and the reason they were dropped is often in these very numbers
(E5-S1 D1). Leaving them off would delete the evidence for the decision.

**D6 — The baseline this report measures against cannot be moved after the order was sent (added
2026-08-31).** `needed_by` became editable on a draft that day (E5-S3 D1), which is what a temple
had been asking for. It stops at SENT for this report's sake as much as for the vendor's: on-time is
measured against exactly this date, and a column anybody could rewrite after the lorries had come
and gone would make every percentage here a statement about who edited last. The freeze is enforced
in `PurchaseOrderService.update` and refused with `KMS-4919`. Nothing this report counts changed —
the figures, the aging buckets and the *orders without a needed-by date* column all read the same
column in the same way, and `VendorPerformanceIT` is unchanged.

**Requirements:**
- `GET /api/v1/vendor-performance?from=&to=` behind `MANAGE_VENDORS`, and a screen at
  `/vendor-performance` under Vendors in the menu. No migration and no new error code —
  `KMS-4988 COST_PERIOD_NOT_VALID` already says what a bad period is, and a second message for the
  same mistake is a second thing to read.
- Drafts and cancellations are out: a draft was never sent to a vendor, and a cancellation was the
  temple's own decision.
- The period selects orders by the date they were placed. The open-order and aging columns are the
  exception and say so on the screen: they are present tense and unfiltered by period, because an
  order left hanging since June is what aging exists to surface.
- Aging reuses the payables buckets — CURRENT / DUE_1_30 / OVERDUE_31_PLUS — read against the
  needed-by date. A second idea of "overdue" in one application is something a person has to learn.
- Rejections grouped per vendor by reason, commonest first.
- Rows sorted worst on time first, the unranked below them, so reading the column downwards is the
  answer.

**Acceptance criteria:**
- [x] No percentage appears without the counts behind it.
- [x] A punctual vendor who delivers short reads as on time and short, in two columns.
- [x] An order where nothing arrived at all counts as late.
- [x] Drafts and cancelled orders are counted nowhere.
- [x] An order whose needed-by date has not yet passed is not judged.
- [x] An order with no needed-by date is counted aside rather than scored on time.
- [x] Open orders fall into the payables screen's three buckets, in its words.
- [x] Rejections are grouped by reason, commonest first.
- [x] Under five judged orders the vendor is marked and sorted below, and the figure is still shown.
- [x] A deactivated vendor is on the report and marked as inactive.
- [x] A vendor with no orders in the period is absent; a period with no orders says so rather than showing a table of dashes.
- [x] The screen states what on-time actually measured before anybody reads a number.
- [x] A devotee is refused the endpoint and is not offered the screen.

