# EPIC 5 — Ordering System & Vendor Management

**Goal:** Vendor records, auto-generated order lists from shortfalls + thresholds, purchase orders with translation/PDF/WhatsApp delivery, receiving with partial/rejected handling that updates inventory, and invoice capture feeding Payments.
**Depends on:** Epics 1–4. **Blocks:** E7-S9 (invoice payment).
**Labels:** `epic:ordering`

---

## E5-S1 — Vendor management

**Verified by:** [UAT-16](UAT.md#uat-16--vendors--the-auto-order-list)

**As a** Kitchen Staff member, **I want** vendor records with contact details and supplied items, **so that** ordering knows who sells what and where to send the PO.

**Assumptions:** Staff-managed only (locked: no vendor logins). Vendor phone is the WhatsApp destination — capture and validate accordingly.

**Requirements:**
- Vendor CRUD: name, contact person, phone (E.164, flagged if not WhatsApp-reachable after first send failure), email (optional), address, GSTIN (optional — appears on POs when present), preferred language for PO documents (from tenant's configured language list), notes, active flag.
- Vendor ↔ ingredient supply mapping with optional last-known price (price history is Phase 2; one current price field only).
- Preferred-vendor-per-ingredient designation (consumed by E5-S2 suggestions and E3-S1).
- Deactivation hides from pickers, preserves history.

**Acceptance criteria:**
- [ ] Vendor with supplied-items mapping created; appears in ingredient's preferred-vendor picker.
- [ ] Invalid phone rejected at entry; deactivated vendor vanishes from new-PO flows but old POs render.
- [ ] Preferred language stored and later drives PO translation default (E5-S5 contract).

---

## E5-S2 — Auto-generated order list

**Verified by:** [UAT-16](UAT.md#uat-16--vendors--the-auto-order-list)

**As a** Kitchen Staff member, **I want** a suggested order list computed from meal-plan shortfalls and reorder thresholds, **so that** procurement starts from data, not memory.

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

---

## E5-S3 — Purchase order generation and lifecycle

**Verified by:** [UAT-17](UAT.md#uat-17--purchase-orders--receiving)

**As a** Kitchen Staff member, **I want** approved order lines grouped into per-vendor POs with a tracked lifecycle, **so that** what we asked for, from whom, by when, is always unambiguous.

**Assumptions:** Lifecycle: `DRAFT → SENT → PARTIALLY_RECEIVED → RECEIVED / CANCELLED` (receiving drives the last states, E5-S6). PO number: per-tenant sequential with prefix (e.g. `PO-2026-0042`).

**Requirements:**
- "Generate POs for selected" → one draft PO per distinct vendor from checked lines (wireframe flow); manual PO creation also possible.
- PO: header (vendor, dates, delivery location free-text), lines (ingredient, qty, unit, optional expected price), notes; editable in DRAFT only.
- State transitions with guards + timestamps + actor; cancel requires reason; every transition audited.
- PO list with status filters; per-PO activity trail.

**Acceptance criteria:**
- [ ] Three checked lines across two vendors → exactly two correct draft POs.
- [ ] Illegal transitions (e.g. edit after SENT, receive a DRAFT) rejected at service layer.
- [ ] PO numbering monotonic per tenant, gap-tolerant, never duplicated (concurrency test).

---

## E5-S4 — PO document: PDF and print (English)

**Verified by:** [UAT-17](UAT.md#uat-17--purchase-orders--receiving)

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

**Verified by:** [UAT-18](UAT.md#uat-18--po-translation--whatsapp-delivery)

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

**Verified by:** [UAT-17](UAT.md#uat-17--purchase-orders--receiving)

**As a** Kitchen Staff member, **I want** to record exactly what arrived — including short and rejected goods — **so that** inventory reflects the truck, not the order.

**Assumptions:** Locked Phase 1 feature. Receiving event per delivery against a PO; multiple receipts per PO allowed. Received quantities (not ordered) write `PO_RECEIPT` movements with batch/expiry/received-date; rejected quantities recorded with reason (`DAMAGED/SPOILED/WRONG_ITEM/OTHER`) and do NOT touch stock; shortfall (ordered − received − rejected still outstanding) keeps PO `PARTIALLY_RECEIVED` and re-feeds the order list.

**Requirements:**
- Receiving screen per PO: line-by-line received qty, batch id, expiry date, rejected qty + reason; delivery note/photo upload optional (GCS).
- Movements written atomically with the receiving record; PO status auto-derives (`PARTIALLY_RECEIVED`/`RECEIVED`).
- Outstanding-quantity query feeds E5-S2 regeneration (short 6kg → next draft list includes it with provenance "PO-2026-0042 short").
- Idempotency: a receiving submission is a single unit; duplicate submission guarded (idempotency key per SYSTEM_DESIGN.md §6).

**Acceptance criteria:**
- [ ] 36 ordered / 30 received / 2 rejected-spoiled → stock +30 with correct batch, rejection recorded, PO `PARTIALLY_RECEIVED`, 6 outstanding appears in next generated list with provenance.
- [ ] Second receipt completing the PO flips status to `RECEIVED`.
- [ ] Double-click/duplicate submit cannot double-book stock (idempotency test).
- [ ] Rejected goods never appear in stock; rejection reasons queryable per vendor (Phase 2 scorecard groundwork).

---

## E5-S7 — WhatsApp PO delivery

**Verified by:** [UAT-18](UAT.md#uat-18--po-translation--whatsapp-delivery)

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

**Verified by:** [UAT-19](UAT.md#uat-19--vendor-invoices--payables)

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
