# ISKCON Kitchen Management System — Requirements

**Status:** LOCKED — v1.2, amended 2026-08-30.
**Version:** 1.2
**Approved:** 1.0 on 2026-08-03 by Rajeev; v1.1 on 2026-08-20 by Rajeev; v1.2 on 2026-08-30 by Rajeev
**Last updated:** 2026-08-30

> This is a living reference document tracking the current approved version. Point-in-time snapshots of each locked version are kept in `docs/versions/`. Material changes after lock require a version bump and a note in `docs/CHANGELOG.md`.

---

## 1. Product Overview

A multi-tenant web application (responsive; native mobile deferred to a future phase) that manages the food service operations of ISKCON temples. Each temple is a fully isolated tenant.

**Primary launch market:** India. International launch is a future phase.

**Design principles:**

- Very clean, modern, professional look and feel.
- Users are not always computer-literate. Layout must be intuitive and logically organized.
- Minimal color palette. Color is used as a tool to direct attention, not for decoration.

---

## 2. Users and Roles

| Role | Scope | Summary |
|---|---|---|
| Platform Super-Admin | Cross-tenant | Provisions new temple tenants and their first admin account. Tenants are centrally provisioned; there is no self-service temple signup. |
| Temple Admin | Single tenant | Full access within their temple: staff, settings, approvals, finances. |
| Kitchen Staff / Manager | Single tenant | Recipes, inventory, meal planning, ordering, day-to-day operations. Single combined role for release 1; may split later based on real usage. |
| Volunteer | Single tenant | Views and signs up for shifts, releases spots, receives reminders. Account required (email and phone needed for contact and reminders). |
| Donor / Devotee | Single tenant | Browses the wish list and gives. Account required — the guest donor was withdrawn on 2026-08-29, so every donation carries a name. No kitchen access. |

**Vendors do not have logins.** All vendor data, purchase orders, and invoices are entered and managed by temple staff. Vendors interact by phone or email outside the application.

---

## 3. Phase 1 Scope

### 3.1 Kitchen Management

**The temple's kitchens — Phase 1 as of the v1.2 amendment.**

A temple is not one kitchen. It runs three to five under one roof, and a large one ten or more: the
Deity kitchen, the prasadam kitchen, a restaurant, a Food-for-Life kitchen, a guest house. They share
one store room, and **most of them will never open this application** — they want ingredients, not
software.

- A temple registers each of its kitchens: name, description, where it is, who runs it, a contact.
- The list is **flat**. Every kitchen belongs to the temple; no kitchen sits inside another. Exactly
  one may be marked as the temple's **main kitchen**, which is a label rather than a behaviour.
- Each kitchen declares **whether it plans its meals in this application**. That declaration decides
  which door its stock leaves the store by, and the two doors are mutually exclusive — see
  *Issuing ingredients* below.
- A kitchen that has asked for ingredients is archived rather than deleted, so its history survives.

**Recipe Management**

- Create, edit, categorize, and search recipes.
- Ingredient lists with quantities and units.
- **Recipe scaling.** A recipe stored at a base serving count scales automatically to any target count. Scaled ingredient requirements feed inventory checks and order generation. Required because volumes swing from ~150 daily to 1200+ at festivals.
- **Download as PDF / Print.** Any recipe, at any scaled serving size, can be exported as a formatted PDF or sent to a browser print view — for use on a kitchen printout, not just on-screen.
- **Recipe translation.** A recipe can be translated into a temple-selected Indian language and downloaded as a PDF or printed in that language. Translated output should carry the same formatting as the English version (ingredient table, quantities, steps).
  - *Tech-stack note:* candidate approach is a machine-translation API for Indian languages — e.g. Bhashini, the Government of India's language AI initiative built specifically for Indian language pairs, alongside commercial options (Google Cloud Translation, Azure Translator). Final choice deferred to the Tech Stack stage; domain terms (ingredient names, units) should be reviewed for translation accuracy since generic MT can mishandle culinary vocabulary.

**Sattvic Ingredient Enforcement — Decided**

- Ingredient master data carries a compliance flag for prohibited items (onion, garlic, mushroom, eggs, and other non-sattvic items).
- **Decision:** prohibited ingredients hard-block recipe save and purchase order submission. Override is available only to Temple Admin, and every override is written to the audit trail (who, when, why).
- Rationale: an error here is a religious failure, not a cosmetic bug.

**Inventory Management**

- Two inventory classes:
  - **Consumables** — groceries and perishables. Quantity-tracked, depleted by meal production.
  - **Equipment** — non-perishable kitchen assets. Tracked by condition, location, and service status rather than depletion.
- Current stock levels, reorder thresholds, and low-stock alerts.
- Stock increases from: purchase order receipt, and in-kind donation intake (below).
- Stock decreases from: a meal recorded against the plan, a manual adjustment, and — new in v1.2 —
  **ingredients issued to one of the temple's other kitchens**.
- **One store, not one per kitchen.** Issuing takes food off the temple's books; it does not move it
  into a second balance held by the receiving kitchen. A kitchen that only asks for ingredients is
  not running this application, so nothing would ever draw such a balance down, and within a month
  it would be a number claiming the Deity kitchen still holds rice it ate in September. This is the
  same reasoning that kept leave-balance accrual out of Phase 1: a balance nobody reconciles is a
  number that misleads.
- **Food safety (FSSAI/BHOG) — Decided: defer to Phase 2.** BHOG is a voluntary FSSAI certification for places of worship; the underlying FSSAI licensing obligation sits on the temple, not the software. Phase 1 does not build a compliance module, but batch, expiry, and received-date fields on inventory (already in scope via Ordering System receiving) are kept in the data model so a Phase 2 food safety log — hygiene checks, food handler training records, batch/temperature logs — layers on without rework.

**In-Kind Donation Intake**

- Devotees donate goods directly (e.g. rice sacks, ghee tins). Staff record these into inventory without an associated purchase order.
- Captures donor (or anonymous), item, quantity, and estimated value for accounting.
- Links the Donations module back into Kitchen inventory.

**Issuing ingredients to a kitchen — Phase 1 as of the v1.2 amendment.**

The second door out of the store, and the one that serves every kitchen whose meals this application
never sees.

- **Any temple staff member** may raise a request: which kitchen, when it is needed, why, the
  ingredients and quantities, and **what the kitchen is cooking and how much of it**.
- The dish list is **required** before a request can be reviewed. Writing down what you are cooking
  is what makes a requester work out what they actually need, rather than padding the list "just in
  case"; and it is the other half of the comparison an auditor needs when reading what was issued.
- A request may be left in draft and finished later. A draft is readable by everyone and editable
  only by its author — or deletable by a Temple Admin.
- **A Temple Admin or a Kitchen Manager reviews it**, approving or denying with a note. A denial is
  final: a refusal that can be edited and re-shown is not a refusal.
- The store then **records what it actually handed over**, which is the moment stock moves. Approval
  is a decision; issuing is a physical event. The system already draws that line between sending a
  purchase order and receiving one.
- Ingredients are drawn **oldest-expiry-first**, the same rule meal production already follows, and
  an issue the store cannot cover is refused whole rather than in part.
- An approved request produces a **work order**: what to pick, which batch to pick it from, why, for
  whom, and two signature boxes — the storekeeper issuing and the person taking delivery.
  Downloadable, printable, and translatable into any of the 22 scheduled languages plus English.
- **A kitchen that plans its meals here may not raise requests.** Its ingredients are drawn when its
  meals are recorded, and allowing both would take the same food off the books twice. Turning the
  meal planner on for a kitchen settles the requests already in flight for it: drafts are deleted,
  anything awaiting or holding approval is denied, and anything already issued or dated in the past
  is left alone as history.

**Meal Planner**

- Plan meals by date across four contexts: regular days, weekends, festival days, and outside catering commitments.
- Each planned meal references a recipe and an estimated serving count.
- Displays ingredient sufficiency status against current inventory; shortfalls feed the Ordering module's auto-generated order list.

**Vaishnava Calendar Integration — Researched and Decided**

- Built-in Vaishnava calendar covering Ekadashi, Kartik, and major festivals. Dates are lunar and shift year to year.
- Meal planner is calendar-aware: planning grain or bean dishes on Ekadashi raises a violation flag.
- Festival dates surface in the planner and drive higher serving-count forecasts.
- **Decision: compute astronomically, not import a published calendar.** Research confirms this is both feasible and the approach ISKCON itself uses:
  - GCAL, the official Gaurabda Calendar program of the **ISKCON GBC Vaishnava Calendar Committee** (2006–2014), computes tithi, naksatra, sankranti, and Ekadashi/fasting dates astronomically (ephemeris-based, configurable ayanamsa, ~20-iteration precision on tithi/sankranti timing).
  - An MIT-licensed Python implementation of the same calculation approach (`gaurabda-calendar`) is available and installable via pip, giving a legally reusable reference implementation rather than a from-scratch astronomical build.
  - Calculation is **location-dependent**: tithi is determined at local sunrise, so each temple's coordinates (lat/long, timezone) are required inputs, not a single global calendar. This must be captured as part of temple tenant setup.
  - **Fasting schema — Decided: post-2006 ("new" style per Hari-bhakti-vilasa, current GCAL).** Two schemas exist historically (pre-2006 "old"/VCAL vs. post-2006 "new"). Current, live ISKCON Ekadashi calendars for 2026 (including the Maha Dvadashi postponement rule — fast shifts to Dvadashi when Ekadashi tithi spans less than 50% of the sunrise-to-sunrise window) confirm the post-2006 schema is what's actually published and followed today. Default the system to this schema; the pre-2006 style is legacy and not needed.
- **Safety net:** even with astronomical computation, provide Temple Admin the ability to manually override or correct an individual date, with the override logged to the audit trail — astronomical edge cases (adhika/ksaya masa, DST-adjacent boundaries) have historically required hand correction even in GCAL's own changelog.

### 3.2 Ordering System

- **Vendor intake and management** — vendor records, contact details, supplied items, preferred vendor per item.
- **Auto-generated order list** — computed from current inventory levels against upcoming meal plan requirements over a configurable horizon. Staff review, adjust, and approve before purchase orders are issued.
- **Purchase order generation and tracking** — status through the order lifecycle.
- **Receiving, including partial and rejected deliveries** — receiving must handle short shipments, damaged or spoiled goods, and outright rejection. Received quantities, not ordered quantities, update inventory. Shortfalls and rejections feed back into reorder suggestions.
- **Automatic inventory update on delivery** — accepted items are added to inventory with quantity and received date logged. No manual re-entry.
- **Vendor invoicing** — invoices recorded against purchase orders and passed to the Payments module.
- **Purchase order translation, PDF, and print.** Many Indian shopkeepers cannot reliably read English ingredient names, and English-to-local-language translation by the vendor themselves is not something staff can rely on. A generated purchase order must be translatable into a staff-selected Indian language and downloadable as a PDF or sent to print, using the same translation approach as Recipe Management.
- **WhatsApp delivery of purchase orders.** Staff can send a purchase order directly to a vendor's WhatsApp number from within the app — this is standard practice for vendor communication in India. Requires WhatsApp Business API integration (see Section 3.5).

### 3.3 Workforce Management

- **Staff scheduling** — create and maintain work schedules for full-time kitchen staff.
- **Time off and sick leave — Phase 1 as of the v1.1 amendment.** The temple asked for it. A
  request-and-approve log, nothing more: time off, sick and unpaid, half-days supported, approved by
  the temple admin or by a Kitchen Manager where one has been appointed. Approved leave drops the
  person out of the schedule grid and the workforce count, may be back-dated, and may be revoked.
  **Leave-balance accrual stays in Phase 2** — the temple never asked for it, and a balance nobody
  reconciles is a number that misleads.
- **Attendance stays out of Phase 1.** Hourly pay was dropped, and hours worked were the only thing
  that would have required recording attendance. There is nothing left for it to serve.
- **Volunteer shift requests** — post volunteer opportunities per day, with role, time window, and spot capacity.
- **Volunteer signup and release** — volunteers claim open spots and may release a spot if they cannot attend.
- **Waitlist with auto-promotion** — when a shift is full, volunteers join a waitlist. A released spot is automatically offered to the next person on the waitlist and they are notified. Without this, releases silently become unfilled shifts.
- **Shift reminders — Decided.** WhatsApp is the primary reminder channel given the India-first market, with SMS and email as fallback. Requires WhatsApp Business API integration (see Section 3.5).
  - **Reminder timing is part of Event Setup Configuration.** When Temple Admin or Kitchen Staff posts a volunteer request ("event"), they configure how far in advance reminders go out (e.g. 24 hours, 48 hours, custom). Not a fixed system-wide constant.
  - **One-off broadcast.** The event owner has a button to immediately send a one-time reminder broadcast to everyone currently signed up for that event, independent of the scheduled reminder timing — for last-minute changes or confirmations.
  - **Channel default is a user account preference, not a system setting.** At account creation, every user (volunteer, staff, admin) provides both email and phone number, then selects their preferred default channel (WhatsApp, SMS, or email) from those two contact points. Reminders and notifications go out via that user's chosen default; WhatsApp/SMS/email remain the available options, but the individual controls which one they receive on.

### 3.4 Payments and Donations

- **Vendor invoice payment and recording** — invoices paid and recorded for accounting.
- **Staff payments — Phase 1 as of the v1.1 amendment.** Salaried staff only; hourly was dropped as
  more trouble than it is worth until somebody asks for it. Salary is a monthly figure and is
  optional, because a temple may take somebody on before pay is agreed. The app **records** salary
  payments, cash advances and the deductions that recover them; it does **not compute what is owed**.
  Computing salary owed needs a pay period, a start date and a ledger of settled periods — which is
  payroll, and nobody asked for payroll. The termination screen therefore shows the advance balance,
  which is exact arithmetic, and the last recorded payment with its date, and lets the admin draw
  their own conclusion.
- **Wish list** — the temple publishes items anyone can select and fund.
- **Donations** — one-time or recurring, with donor-selected frequency.
- **Giving needs an account — reversed 2026-08-29.** Guest donation was Phase 1 scope until 2026-08-29, when it was withdrawn. There is no public donation page and no guest checkout; a temple asks a supporter to register as a devotee or volunteer, and every online gift is made from inside the application and carries the giver's name.
- **Anonymity in office in-kind intake — *Deferred*.** A staff member recording a gift somebody brought to the temple in person may mark the giver anonymous, and whether that hides them only from public display or also from the temple's internal accounting is still open. Recommendation on record: hide publicly, retain internally for receipting and audit. Online giving no longer raises the question, because it is always named.
- **Donation accounting** — all donations properly recorded and reportable.
- **80G donor data capture — Decided.** Phase 1 captures the donor fields Form 10BD requires (name, address, PAN, donation amount, payment mode) at the point of donation, at the donor's option. **Form 10BD filing and Form 10BE certificate generation/export are deferred to Phase 2** — the data model is Phase 1's responsibility, the filing workflow is not.
- **Multi-currency** — architecture supports multiple currencies and regions from day one. INR is the release 1 default.

### 3.5 Cross-Cutting (Phase 1)

- Multi-tenant isolation between temples.
- Role-based access control per the roles table above.
- Audit trail for sensitive actions (financial records, inventory adjustments, compliance overrides, calendar date overrides).
- **WhatsApp Business API integration.** Now a Phase 1 technical dependency, not optional — used for both vendor purchase-order delivery (Ordering System) and volunteer/staff reminders (Workforce Management). Provider selection (Meta direct vs. a Business Solution Provider such as Gupshup, Twilio, or Interakt) deferred to the Tech Stack stage.
- **Indian-language machine translation.** Shared capability used by Recipe Management and Purchase Order translation. Provider selection (Bhashini vs. commercial MT) deferred to the Tech Stack stage.
- **Per-tenant geolocation.** Each temple's coordinates and timezone are required setup data, driven by the Vaishnava calendar's need for location-accurate tithi calculation — capture this at tenant provisioning, not as an afterthought.

---

## 4. Phase 2 Backlog

Recorded now so they are explicitly deferred rather than quietly carried. Details to be refined when Phase 2 begins.

| Item | Summary |
|---|---|
| Cost-per-plate analytics | Combine kitchen cost data with donation data to produce impact figures (e.g. "₹500 feeds 20 people"). Strong fundraising instrument derived from data already captured. |
| Sponsor-a-day / sponsor-a-feast | Annadanam-style sponsorship, including dedication in a family member's name. Likely a larger revenue model for Indian temples than the item wish list. |
| Volunteer reliability and recognition | No-show tracking, hours logged, service certificates. Targets volunteer retention rather than signup. |
| Vendor scorecard and price history | On-time delivery percentage, price variance over time, quality ratings. Supports negotiation. |
| ~~Waste and actual-vs-planned tracking~~ | **Actual servings moved into Phase 1** by the v1.1 amendment — recording a meal now captures what actually went out, per dish, which over a month tells the temple whether its head counts are wrong and in which direction. Leftovers and waste weight remain Phase 2. |
| Leave-balance accrual | Entitlement, accrual and carry-over. Deliberately out of Phase 1: the temple asked for a leave *log*, not a balance, and a balance nobody reconciles misleads. |
| Attendance and hourly pay | Recording hours worked, and paying against them. Out of Phase 1 because hourly pay was dropped; if it ever returns, attendance returns with it. |
| Labour cost per meal | The materials estimate exists (Phase 1); labour would have to be *allocated* rather than measured, since a cook on a 6am–2pm shift is making breakfast and lunch at once. Needs no timesheet — the weekly template says who works which hours and salary gives a day rate — but the allocation rule is a decision nobody has made. |
| Multilingual UI | Hindi and regional languages. May matter more for adoption than any single feature, given the target user base. |

---

## 5. Open Questions

Resolved items removed. Remaining:

None outstanding. Section 5 is closed as of this round.

**Resolved this round:** Sattvic enforcement (hard-block + admin override), Vaishnava calendar source (astronomical computation, MIT-licensed reference implementation), volunteer reminder channel (WhatsApp primary) and timing model (per-event configuration + one-off broadcast + per-user channel preference), anonymous donations (public-only anonymity by donor choice, retained internally — **reversed 2026-08-29** for online giving, which is now signed-in and named; anonymity survives only in office in-kind intake), 80G scope (capture donor fields in Phase 1, defer Form 10BD/10BE filing/export to Phase 2), FSSAI/BHOG scope (defer food safety logging to Phase 2, keep the data model ready), fasting-rule schema (post-2006 / Hari-bhakti-vilasa / current GCAL — see Section 3.1).

---

## 7. India Regulatory Findings (researched 2026-08-03)

### 7.1 FSSAI — Food Safety

- Any entity involved in preparing, storing, distributing or serving food is a Food Business Operator and requires FSSAI registration or licensing. Free distribution does not by itself create an exemption, and temples have obtained FSSAI licences in practice.
- FSSAI runs a dedicated scheme for places of worship: **BHOG (Blissful Hygienic Offering to God)**, launched 2018 under Eat Right India. It covers places of worship handling food in packed, loose or meal form.
- BHOG is **voluntary, not mandatory**. It involves FOSTAC basic catering training for food handlers, a third-party hygiene audit, and certification valid for 2 years.
- **Implication:** the underlying FSSAI registration is a legal obligation on the temple, not something the software provides. What the software can do is support the operational record-keeping that licensing and BHOG audits expect — food handler training records, hygiene checks, temperature and batch logs.
- **Recommendation:** do not build a compliance module in Phase 1. Instead ensure the data model can carry batch, expiry and received-date fields on inventory (already in scope via receiving), so a Phase 2 food safety log can be layered on without rework.

### 7.2 80G — Donation Receipting

Confirmed and materially affects the Donations module:

- Institutions approved under Section 80G must file **Form 10BD**, an annual statement of donations, and issue **Form 10BE** certificates to donors. Both are due **31 May** following the financial year.
- Form 10BD requires, per donor: **name, address, PAN (or alternate ID), donation amount, mode of payment, and the section the donation qualifies under**.
- **Cash donations above ₹2,000 are entirely ineligible** for 80G deduction. Non-cash rails (UPI, bank transfer, card, cheque) are required above that threshold.
- **Section 115BBC** taxes anonymous donations at 30% above a threshold of the higher of ₹1 lakh or 5% of total donations — but **wholly religious institutions are exempt** from this. Mixed religious-and-charitable institutions face the tax only on anonymous donations earmarked for educational or medical institutions they run. **Moot for online giving as of 2026-08-29:** every online donation is made by a signed-in devotee and carries a name, so the section has nothing to bite on there; it bears only on gifts taken in at the office.

**Implications for the product:**

1. If a temple is 80G-approved, the system must capture donor name, address and PAN for any donation the donor wants to claim, and must be able to export a Form 10BD–shaped dataset.
2. The donation flow should present PAN capture as optional but clearly explain that omitting it forfeits the 80G certificate.
3. UPI as the primary rail is reinforced — it keeps donations outside the ₹2,000 cash ineligibility trap.
4. **Open question 4 — the finding stands, the design it recommended was reversed on 2026-08-29.** The law is unchanged: true anonymity is legally viable for a wholly religious temple, unlike the US position, and an anonymous donor cannot receive an 80G certificate. What went is the design. Anonymity as a donor choice at checkout was withdrawn together with unauthenticated giving — there is no public donation page, no guest checkout, and every online gift is made by a signed-in devotee under their own name. This paragraph was the authority the code cited for letting somebody give without an account, and it no longer grants it. Anonymity survives in one place only: a staff member recording a gift brought to the temple in person (Section 3.4).
5. Temples that are 80G-approved versus not is a **per-tenant configuration**, not a global assumption.

---

## 6. Explicitly Out of Scope for Release 1

- Native mobile applications.
- Vendor-facing portal or vendor logins.
- Self-service temple registration.
- Separate Accountant/Treasurer role distinct from Temple Admin.
- US and international regulatory compliance.
