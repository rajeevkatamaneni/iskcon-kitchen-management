# EPIC 7 — Payments & Donations

**Goal:** Signed-in giving (UPI-first via Razorpay), one-time and recurring donations with donor-chosen frequency, 80G donor-data capture per the locked India research, wish-list sponsorship, a unified donations ledger, and vendor invoice payment recording.
**Depends on:** Epic 1; E5-S8 (invoice queue); E3-S5 feeds the ledger.
**Labels:** `epic:payments`

**Locked context (REQUIREMENTS.md §7):** cash >₹2,000 is 80G-ineligible (UPI-first design is deliberate); donor fields (name, address, PAN, amount, mode) captured in Phase 1, Form 10BD/10BE export deferred to Phase 2; 80G approval is per-tenant config.

**Reversed 2026-08-29 — giving requires an account.** Every donation this epic takes is made by a signed-in devotee and carries their name. There is no public donation page, no guest checkout and no anonymous online gift; a temple asks a supporter to register and give from inside the application, and the product publishes no web address of its own, since temples have their own websites. Section 115BBC, which taxes anonymous donations, is therefore moot here. Anonymity survives only where a staff member records a gift somebody brought to the temple in person (E3-S5, and the ledger display in E7-S7).

---

## ~~E7-S1 — Public temple donation page~~ · WITHDRAWN 2026-08-29

**Status:** Superseded. The story is kept, struck through, because other documents cite E7-S1 by id.

There is no public temple donation page. On 2026-08-29 Rajeev withdrew unauthenticated giving
altogether: a temple asks a supporter to sign up as a devotee or volunteer and to give from inside
the application, so every online donation carries a name. The product publishes no public web
address for a temple either — temples have their own websites, and this one is a kitchen management
system.

What the story asked for is either gone or has moved. The signed-in giving screen lives at
`/donate` and is covered by **E7-S2** (the payment itself) and **E7-S4** (donor details); it needs no
slug resolution, because the tenant comes from the verified token like everywhere else in the
product. The mobile performance budget it carried — under 200KB of initial JS, usable at 360px —
belongs to the application as a whole and is unchanged.

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

**Assumptions:** Locked: donor-chosen frequency (weekly/monthly/quarterly/annually per wireframe; Razorpay subscription plans support these intervals — "custom" beyond these maps to closest supported or is dropped; verify plan API at implementation). UPI Autopay preferred rail, card/eNACH as Razorpay offers. An account is required, as it now is for every kind of giving (reversal of 2026-08-29) — which happens to be what mandate management needed anyway, since a mandate has to hang on a persistent identity.

**Requirements:**
- Frequency + amount selection → Razorpay subscription created; local recurring-plan record linked to donor account; each cycle's charge webhook creates a donation record (COMPLETED) attached to the plan.
- Donor self-service: view plan status, payment history, cancel plan (cancels the Razorpay mandate; confirmation message).
- Failed cycle handling: record FAILED occurrence, notify donor with retry guidance per Razorpay's retry semantics; plan status reflects Razorpay's (ACTIVE/PAUSED/CANCELLED).
- Admin ledger view distinguishes recurring-cycle donations from one-time.

**Acceptance criteria:**
- [ ] Test-mode subscription: setup → first cycle webhook → donation record created and attached to plan.
- [ ] Cancellation stops future charges (verified in Razorpay test mode) and updates local status.
- [ ] Failed-cycle webhook records the failure and triggers donor notification.
- [ ] A visitor who is not signed in is asked to sign in or register before reaching the giving screen at all, with a one-line explanation.

---

## E7-S4 — 80G donor data capture

**Verified by:** [UAT-055](../uat/UAT-055-give-once.md)

**As a** donor, **I want** to decide whether to hand over the details a tax certificate requires, **so that** I claim the relief when I want it and am not asked for a PAN when I do not.

**Assumptions:** Every donor is signed in and named — the anonymous path was withdrawn on 2026-08-29 with the rest of unauthenticated giving. Name and contact come from the donor's own account rather than being typed again; a donor wanting 80G must add address and PAN; a donor who gives without a PAN still gets no certificate but is thankable by name. 80G fields shown only when tenant `is_80g_approved`.

**Requirements:**
- Pre-checkout step, two paths: (a) Named, no 80G — the account's name and contact, confirmed rather than re-entered; (b) 80G — additionally address and PAN (format-validated); inline explanation of why PAN is needed and the >₹2,000 cash rule note where relevant.
- PAN encrypted at column level (SYSTEM_DESIGN.md §7); visible only to TEMPLE_ADMIN; access audited.
- Donation records carry mode-of-payment (from Razorpay) — a Form 10BD-shaped dataset accumulates by construction (fields: name, address, PAN, amount, mode, section) even though export is Phase 2.
- DPDP consent text at capture; donor data deletable on request via admin action (audited) except where retention is legally required for filed years — release 1 rule: deletable freely since no 10BD has been filed yet; revisit at Phase 2 filing.

**Acceptance criteria:**
- [ ] Both paths store exactly their fields, and no donation is recorded without an identified donor (DB-level verification in test).
- [ ] PAN stored encrypted; non-admin roles cannot read it via any endpoint; admin read is audited.
- [ ] Non-80G tenant never shows the 80G path.
- [ ] Captured 80G donations satisfy a 10BD-shaped query (contract test for the Phase 2 export).

---

## E7-S5 — Wish list management (admin)

**Verified by:** [UAT-057](../uat/UAT-057-manage-the-wish-list.md)

**As a** Temple Admin, **I want** to publish and manage wish-list items, **so that** devotees can fund concrete needs.

**Assumptions:** Item = title, description, image (GCS), price (INR), category (consumable/equipment/other), quantity wanted (default 1; multi-quantity items like "rice sack ×10" supported), status (`ACTIVE/FULFILLED/ARCHIVED`). Fulfillment counting from sponsorships (E7-S6).

**Requirements:**
- CRUD + image upload; ordering control (manual sort, which is the order devotees see).
- Auto-status: fully sponsored quantity → FULFILLED (stays visible briefly as "Fulfilled 🙏" per tenant config days, then auto-archives).
- Optional link from a fulfilled consumable item to in-kind/inventory intake is **not** automatic — money buys the item through normal procurement; a note field guides staff ("sponsored via wish list, order via E5").

**Acceptance criteria:**
- [ ] CRUD with images works; the order devotees see matches the manual sort.
- [ ] Sponsoring the final unit flips status and presentation per config.
- [ ] Archived items vanish from the devotee-facing list, retained in ledger history.

---

## E7-S6 — Wish list and sponsorship checkout

**Verified by:** [UAT-058](../uat/UAT-058-sponsor-a-wish-list-item.md)

**As a** donor, **I want** to browse the temple's wish list and sponsor an item, **so that** I know exactly what my money provides.

**Assumptions:** No page of its own — the wish list is the **Equipment** tab of the signed-in giving screen at `/donate`, alongside the money tab, tenant resolved from the verified token. The public page at `/t/{slug}/wishlist` was withdrawn on 2026-08-29 with the rest of unauthenticated giving, and `/wishlist` is a different screen entirely: the Temple Admin managing the list (E7-S5), not a devotee giving towards it. Sponsorship = donation with `wishlist_item` reference reusing the whole E7-S2 + E7-S4 machinery (one payment pipeline, not two); partial sponsorship of multi-quantity items allowed (sponsor 2 of 10 sacks); no partial funding of a single unit in release 1 (complexity without demonstrated need).

**Requirements:**
- Grid per wireframe (image, title, price, progress for multi-quantity, Sponsor button) → quantity pick → donor-details step (E7-S4 paths) → Razorpay checkout.
- Oversubscription guard: quantity availability re-checked at order creation; race resolved in favor of first webhook-confirmed payment, later one gracefully converted to a general donation with donor notification (never a failed charge for a completed payment).
- Sponsor recognition: sponsors listed on the item ("Sponsored by …") — every sponsor is named now, so there is no anonymity choice for the display to honour.

**Acceptance criteria:**
- [ ] End-to-end sponsorship in test mode updates item progress and appears in ledger linked to the item.
- [ ] Race test: two checkouts for the last unit → one sponsorship + one converted-with-notification general donation; no orphaned charge.
- [ ] A sponsored item names its sponsors.
- [ ] The page holds the product's mobile budget (<200KB initial JS, usable at 360px), as every screen must.

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

---

## E7-S10 — The ledger by period, against the same point last year

**Status:** DONE 2026-08-20 (build brief §8).

**Verified by:** UAT-059 (to be extended). Automated cover: `DonationPeriodIT`,
`frontend/__tests__/donations.test.tsx`.

**As a** Temple Admin, **I want** to choose the window the donations screen is showing and see it
against the same point a year earlier, **so that** "are we doing better than last year" is a
question the screen answers rather than one I work out on paper.

**Assumptions:** Extends E7-S7's ledger rather than replacing it. Indian financial year, April–March,
as E7-S7 already had it.

**Where staff payments went, and why not here.** This epic is about money moving between the temple
and the people outside it — donors giving, vendors invoicing. What the temple pays its own cook is a
fact about a person it employs, recorded on that person's employment record, and it lives in
**E6-S13**. Putting it here would have put salary on a screen behind `VIEW_DONATIONS` and
`MANAGE_VENDOR_PAYMENTS`, which is precisely the separation E6-S8's D9 exists to keep.

### Decisions

**D1 — One period control above the tiles: this week, this month, this financial year, or a
specific year.** It filters the tiles, the ledger rows and the CSV together, so an accountant selects
the financial year and gets the full-year file. The server hands the window back rather than letting
the screen work it out — a screen computing its own dates would eventually disagree with the server
about where the financial year starts, and the export would quietly cover a different span from the
figures above it.

**D2 — Same point to same point, always.** A window 140 days old is compared with the first 140 days
of the equivalent window a year earlier, never with the whole of it. Comparing five months of this
financial year against twelve of the last produces a screen that says giving has collapsed, every
year, until March. That is how these screens mislead, and it is the whole value of the feature that
this one does not.

**D3 — The prior window is built from a day count, not a calendar rule.** "1 April plus 140 days"
means the same thing in a leap year and outside one; "the same date last year" silently gains or
loses a day whenever February intervenes.

**D4 — The week steps back 52 weeks, not a calendar year.** A temple's giving is strongly
weekday-shaped — Sunday and the festival days carry it — so a calendar year lands one or two
weekdays adrift and a Monday-to-Sunday week finds itself compared against a Wednesday-to-Tuesday
one. Over a month or a year that drift is a rounding error and the calendar date is the more natural
thing to say, so those step back a year.

**D5 — Counted by the date the gift was given, not the date it was recorded.** Truthful, at the cost
of last week's total still being able to move. `donated_on` is the column, on both sides of the
comparison.

**D6 — A percentage is withheld whenever it would be a lie.** A prior window of zero has no
denominator, and "up ∞%" is not a thing to put in front of an accountant — the screen says *nothing
at this point last year*. A temple whose records do not reach back that far has no prior window at
all, which the summary states separately, because "we have nothing to compare with" and "we compared
and found nothing" are different sentences.

**D7 — Every category that had money in either window is carried**, so a kind of giving that has
stopped says so instead of vanishing from the screen.

**D8 — *Given this month* leaves the Today screen** (E4-S14), and the endpoint behind it went with
it. Month-to-date and financial-year-to-date were the whole of what it could say about a period;
this story answers the same question and four others, and leaving both would have left two ways to
total the same gifts.

**Requirements:**
- `GET /api/v1/donations/period-summary`: the chosen window and its prior window, per-category totals
  and comparisons, whether a prior year exists at all, and the financial years the picker may offer.
- The period drives the ledger rows and `Export CSV` as well as the tiles.
- Behind `VIEW_DONATIONS`, like the rest of the ledger; anonymity handling unchanged (E7-S7).

**Acceptance criteria:**
- [x] Each of the four periods resolves to the right window, with April–March for a financial year.
- [x] A part-year window is compared against the same number of elapsed days a year earlier.
- [x] A week compares against 52 weeks earlier; a month and a year compare against the calendar year.
- [x] Gifts are counted by the day they were given, on both sides.
- [x] A category with nothing in the prior window shows no percentage and says why.
- [x] A temple with no history that far back is told there is nothing to compare against.
- [x] The CSV export covers exactly the window on screen.
- [x] Today no longer shows a donations figure, and the endpoint it used is gone.
