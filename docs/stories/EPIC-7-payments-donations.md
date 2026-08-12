# EPIC 7 — Payments & Donations

**Goal:** Public donation pages (UPI-first via Razorpay), one-time and recurring donations with donor-chosen frequency, 80G donor-data capture with anonymity handling per the locked India research, wish-list sponsorship, a unified donations ledger, and vendor invoice payment recording.
**Depends on:** Epic 1; E5-S8 (invoice queue); E3-S5 feeds the ledger.
**Labels:** `epic:payments`

**Locked context (REQUIREMENTS.md §7):** cash >₹2,000 is 80G-ineligible (UPI-first design is deliberate); anonymous donations are legally viable for a wholly religious temple but forfeit the 80G certificate — donor chooses at checkout with a clear warning; donor fields (name, address, PAN, amount, mode) captured in Phase 1, Form 10BD/10BE export deferred to Phase 2; 80G approval is per-tenant config.

---

## E7-S1 — Public temple donation page

**Verified by:** [UAT-054](../uat/UAT-054-the-public-donation-page.md)

**As a** donor, **I want** the temple's donation page to load fast and work without an account, **so that** giving takes under a minute on any phone.

**Assumptions:** Public, unauthenticated, tenant-scoped by slug (`/t/{slug}/donate`), SSR/SSG per SYSTEM_DESIGN.md §9, resolved server-side to tenant (never a client-supplied tenant id). Amount presets tenant-configurable (defaults ₹51/₹501/₹1,001 + custom, per Indian devotional convention).

**Requirements:**
- Page: temple identity (name, logo — tenant branding from settings), one-time/recurring toggle, preset + custom amounts (INR), guest vs sign-in choice, anonymity option.
- Rate-limited (Cloudflare + app-level), no PII in URLs, indexable and shareable (this URL goes on WhatsApp and festival banners).
- Performance budget: <200KB initial JS; usable at 360px width; Lighthouse ≥90 mobile on staging.
- 404s gracefully for unknown/inactive slugs.

**Acceptance criteria:**
- [ ] Page renders tenant-correct branding and presets from a cold cache fast enough to pass the Lighthouse gate.
- [ ] Tenant resolution is server-side only (attempted id tampering test).
- [ ] All flows reachable without login; sign-in optional path works.

---

## E7-S2 — One-time donation via Razorpay

**Verified by:** [UAT-055](../uat/UAT-055-give-once.md)

**As a** donor, **I want** to complete a one-time donation with UPI (or card/netbanking), **so that** giving is as easy as any payment I make daily.

**Assumptions:** Razorpay hosted checkout (never raw credentials, per SYSTEM_DESIGN.md §6); UPI presented first per India-first mandate. Donation record created server-side before checkout (PENDING) and confirmed by webhook, not by client redirect alone.

**Requirements:**
- Server creates Razorpay order + local donation record (idempotency key); client opens checkout; webhook (signature-verified, idempotent) transitions PENDING → COMPLETED/FAILED.
- Donor detail capture per E7-S4 rules happens before checkout.
- Confirmation screen + receipt message (WhatsApp/email per provided contact; template from E1-S10) with temple thanks — this is the acknowledgment, not the 80G certificate.
- Failed/abandoned checkouts expire PENDING records (cleanup job).

**Acceptance criteria:**
- [ ] End-to-end test-mode donation completes: local record COMPLETED with Razorpay refs, confirmation message delivered.
- [ ] Webhook replay/duplicate does not double-record (idempotency test).
- [ ] Client-side "success" without webhook confirmation does not mark COMPLETED (tamper test).
- [ ] Abandoned PENDING records expire per policy.

---

## E7-S3 — Recurring donation

**Verified by:** [UAT-056](../uat/UAT-056-monthly-giving.md)

**As a** donor, **I want** to set up an automatic recurring donation at a frequency I choose, **so that** my support is steady without monthly effort.

**Assumptions:** Locked: donor-chosen frequency (weekly/monthly/quarterly/annually per wireframe; Razorpay subscription plans support these intervals — "custom" beyond these maps to closest supported or is dropped; verify plan API at implementation). UPI Autopay preferred rail, card/eNACH as Razorpay offers. Recurring requires an account (mandate management needs a persistent identity — guest recurring is not offered; this is an intentional narrowing consistent with the volunteer-account precedent).

**Requirements:**
- Frequency + amount selection → Razorpay subscription created; local recurring-plan record linked to donor account; each cycle's charge webhook creates a donation record (COMPLETED) attached to the plan.
- Donor self-service: view plan status, payment history, cancel plan (cancels the Razorpay mandate; confirmation message).
- Failed cycle handling: record FAILED occurrence, notify donor with retry guidance per Razorpay's retry semantics; plan status reflects Razorpay's (ACTIVE/PAUSED/CANCELLED).
- Admin ledger view distinguishes recurring-cycle donations from one-time.

**Acceptance criteria:**
- [ ] Test-mode subscription: setup → first cycle webhook → donation record created and attached to plan.
- [ ] Cancellation stops future charges (verified in Razorpay test mode) and updates local status.
- [ ] Failed-cycle webhook records the failure and triggers donor notification.
- [ ] Guest flow correctly gates recurring behind account creation with a one-line explanation.

---

## E7-S4 — 80G donor data capture and anonymity choice

**Verified by:** [UAT-055](../uat/UAT-055-give-once.md)

**As a** donor, **I want** to decide whether to share my details for a tax certificate or give anonymously, **so that** my choice about identity is respected without losing the temple its accounting integrity.

**Assumptions:** Locked design: anonymity = hidden from public display, retained internally where provided; anonymous donors skip detail capture entirely (nothing to retain); donors wanting 80G must provide name, address, PAN; donors may also give non-anonymously without PAN (no certificate, name still thankable). 80G fields shown only when tenant `is_80g_approved`.

**Requirements:**
- Pre-checkout step, three paths: (a) Anonymous — no PII captured, warning "no 80G certificate possible"; (b) Named, no 80G — name + contact only; (c) 80G — name, address, PAN (format-validated), contact; inline explanation of why PAN is needed and the >₹2,000 cash rule note where relevant.
- PAN encrypted at column level (SYSTEM_DESIGN.md §7); visible only to TEMPLE_ADMIN; access audited.
- Donation records carry mode-of-payment (from Razorpay) — a Form 10BD-shaped dataset accumulates by construction (fields: name, address, PAN, amount, mode, section) even though export is Phase 2.
- DPDP consent text at capture; donor data deletable on request via admin action (audited) except where retention is legally required for filed years — release 1 rule: deletable freely since no 10BD has been filed yet; revisit at Phase 2 filing.

**Acceptance criteria:**
- [ ] Three paths store exactly their fields — anonymous stores zero PII (DB-level verification in test).
- [ ] PAN stored encrypted; non-admin roles cannot read it via any endpoint; admin read is audited.
- [ ] Non-80G tenant never shows the 80G path.
- [ ] Captured 80G donations satisfy a 10BD-shaped query (contract test for the Phase 2 export).

---

## E7-S5 — Wish list management (admin)

**Verified by:** [UAT-057](../uat/UAT-057-manage-the-wish-list.md)

**As a** Temple Admin, **I want** to publish and manage wish-list items, **so that** devotees can fund concrete needs.

**Assumptions:** Item = title, description, image (GCS), price (INR), category (consumable/equipment/other), quantity wanted (default 1; multi-quantity items like "rice sack ×10" supported), status (`ACTIVE/FULFILLED/ARCHIVED`). Fulfillment counting from sponsorships (E7-S6).

**Requirements:**
- CRUD + image upload; ordering control (manual sort for the public page).
- Auto-status: fully sponsored quantity → FULFILLED (stays visible briefly as "Fulfilled 🙏" per tenant config days, then auto-archives).
- Optional link from a fulfilled consumable item to in-kind/inventory intake is **not** automatic — money buys the item through normal procurement; a note field guides staff ("sponsored via wish list, order via E5").

**Acceptance criteria:**
- [ ] CRUD with images works; public order matches manual sort.
- [ ] Sponsoring the final unit flips status and public presentation per config.
- [ ] Archived items vanish publicly, retained in ledger history.

---

## E7-S6 — Public wish list and sponsorship checkout

**Verified by:** [UAT-058](../uat/UAT-058-sponsor-a-wish-list-item.md)

**As a** donor, **I want** to browse the temple's wish list and sponsor an item, **so that** I know exactly what my money provides.

**Assumptions:** Public page `/t/{slug}/wishlist` (SSR, same performance/limits as E7-S1); sponsorship = donation with `wishlist_item` reference reusing the whole E7-S2 + E7-S4 machinery (one payment pipeline, not two); partial sponsorship of multi-quantity items allowed (sponsor 2 of 10 sacks); no partial funding of a single unit in release 1 (complexity without demonstrated need).

**Requirements:**
- Public grid per wireframe (image, title, price, progress for multi-quantity, Sponsor button) → quantity pick → donor-details step (E7-S4 paths) → Razorpay checkout.
- Oversubscription guard: quantity availability re-checked at order creation; race resolved in favor of first webhook-confirmed payment, later one gracefully converted to a general donation with donor notification (never a failed charge for a completed payment).
- Sponsor recognition: named sponsors optionally listed on the item ("Sponsored by …") honoring anonymity choice.

**Acceptance criteria:**
- [ ] End-to-end sponsorship in test mode updates item progress and appears in ledger linked to the item.
- [ ] Race test: two checkouts for the last unit → one sponsorship + one converted-with-notification general donation; no orphaned charge.
- [ ] Anonymity choice controls public recognition display.
- [ ] Page passes the same Lighthouse gate as E7-S1.

---

## E7-S7 — Donations ledger and accounting view

**Verified by:** [UAT-059](../uat/UAT-059-the-donations-ledger.md)

**As a** Temple Admin, **I want** every donation — online, recurring, wish-list, in-kind — in one filterable ledger, **so that** "properly accounted for" is a screen, not an aspiration.

**Assumptions:** Ledger aggregates: E7-S2/S3/S6 (monetary, Razorpay-sourced) + E3-S5 (in-kind, estimated value). Multi-currency-ready per SYSTEM_DESIGN (currency column, INR default); no FX conversion in release 1.

**Requirements:**
- Ledger view: date range, type (one-time/recurring/wish-list/in-kind), status, anonymity-aware donor display, amount, mode, Razorpay ref; CSV export (accountant's real interface).
- Summary cards: month/FY-to-date totals by type (Indian FY Apr–Mar — matters for 80G-year alignment).
- Donor detail drill-down (admin): giving history per donor identity (matching by account, else by PAN where present, else by exact contact — conservative matching, no fuzzy merging in release 1).
- Wish-list donations link to their item; recurring link to plan; in-kind link to intake record.

**Acceptance criteria:**
- [ ] All four donation types from seeded data appear with correct linkage and filterable.
- [ ] CSV export matches on-screen filters; totals reconcile with summary cards.
- [ ] Anonymous donations show as "Anonymous" with no PII leak in any ledger surface, export included.
- [ ] FY boundary handled correctly (donation on Mar 31 vs Apr 1 lands in the right FY bucket).

---

## E7-S8 — Vendor invoice payment recording

**Verified by:** [UAT-046](../uat/UAT-046-pay-a-vendor-invoice.md)

**As a** Temple Admin, **I want** to record payments against vendor invoices, **so that** payables are tracked to zero and the books stay clean.

**Assumptions:** Recording, not execution — the temple pays vendors outside the app (bank/UPI/cash) and records it here; no payment-rails integration for outbound money in release 1 (deliberate: outbound money movement is a different risk class; also consistent with assistant-safety norms — the system never moves temple funds).

**Requirements:**
- On a PENDING invoice (E5-S8): record payment (date, amount, method — bank transfer/UPI/cheque/cash, reference number, note); partial payments supported (invoice tracks paid-to-date, flips PAID at full).
- Payment records audited; invoice status + payment history visible on the invoice and in a payables view (due/overdue aging buckets).
- Payables summary for the dashboard card (matches wireframe "Pending Vendor Invoices").

**Acceptance criteria:**
- [ ] Full and partial payment flows work; PAID flips only at full amount; overpayment blocked with message.
- [ ] Payment records are audit-evented and immutable (corrections via compensating entry, consistent with ledger philosophy).
- [ ] Aging view buckets correctly (current/1–30/31+ days overdue).

---

## E7-S9 — Razorpay webhook infrastructure

**Verified by:** [UAT-055](../uat/UAT-055-give-once.md), [UAT-058](../uat/UAT-058-sponsor-a-wish-list-item.md)

**As a** system, **I want** hardened, idempotent webhook processing for all Razorpay events, **so that** money state is always consistent no matter how webhooks arrive.

**Assumptions:** Shared infrastructure story extracted deliberately — E7-S2/S3/S6 all depend on it; build once, first. Signature verification, idempotency (event id dedup), ordered-safe processing (events may arrive out of order), dead-letter parking for unprocessable events surfaced on the ops page (E1-S11).

**Requirements:**
- Endpoint verifying Razorpay signatures; event store with dedup; per-event-type handlers registered by the donation stories; unknown event types logged and acked (never 500 on unknowns — Razorpay retries punish that).
- Out-of-order tolerance: e.g. subscription charge before subscription-activated resolves correctly via reconciliation.
- Daily reconciliation job: local COMPLETED records vs Razorpay API for the day; mismatches surface on ops page.
- Test-mode fixtures for every consumed event type (foundation for E7-S2/S3/S6 test suites).

**Acceptance criteria:**
- [ ] Replayed, duplicated, and shuffled event fixtures produce identical final state (property test).
- [ ] Bad signature → rejected + alerting counter; unknown type → acked + logged.
- [ ] Reconciliation catches a seeded local/remote mismatch and surfaces it.
- [ ] Dead-lettered event visible on ops page with replay-after-fix path.
