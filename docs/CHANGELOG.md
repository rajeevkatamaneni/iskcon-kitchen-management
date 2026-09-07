# Project Changelog — ISKCON Kitchen Management System

Tracks version history for the project's governing documents. Each locked version has an immutable snapshot in `docs/versions/`. The root-level copy (`REQUIREMENTS.md`, `SYSTEM_DESIGN.md`, etc.) always reflects the current approved version; edit it only alongside a new entry here and a new snapshot.

Per Commandment 8, no document is edited post-lock without the user's explicit sign-off — a lock is a decision, not a formatting convenience.

---

## DESIGN_SYSTEM.md

### v1.6 — 2026-08-30 — What lifts under the pointer, and what only changes tone (PENDING RAJEEV'S SIGN-OFF)

Written ahead of approval for the same reason as v1.5 below, and it comes out the same way if the
answer is no.

§4 now says which things move under the pointer. **Tiles and sidebar items lift two pixels and take
the pack's `shadow-raised`; table rows change tone and nothing else; a pressed button scales to
0.96 for 120ms.** The rule underneath is that discrete objects you are about to pick up may move,
and surfaces you are reading may not.

The table row was the live question, and it was tested rather than argued: lifted on the running
site, screenshotted, and rejected on three grounds — the shadow lands on the neighbouring rows'
figures, row hover fires at scanning speed where motion has to be imperceptible or absent, and under
`border-collapse: collapse` the lift cannot be drawn cleanly at all. §4 records all three, so the
next person to have the idea can see it was had and answered.

§4 also stops claiming there are no shadows. There are, since v1.5 — `shadow-card`, `shadow-raised`
and `shadow-overlay` are the pack's to set, and a pack that asks for none still gets none. And the
row hover class is corrected from `hover:bg-raised/60` to `hover:bg-sunken`: the old one assumed
`raised` was darker than `canvas`, which is true of terracotta and false of most of the fifteen
packs, so on those it was invisible.

### v1.5 — 2026-08-28 — Colour became the temple's choice, and the focus ring got a token (PENDING RAJEEV'S SIGN-OFF)

**This entry is written ahead of approval and is marked so deliberately.** The code it describes is
built and tested, and the document and the code would otherwise disagree with each other while the
question is open. If Rajeev rejects either half, this entry and the §2/§4 edits come back out
together.

**Colour is now a per-temple choice** (§2). At the 22 August demo the terracotta was loved by part
of the room and disliked by another part, and no single palette was going to satisfy both. So §2
stops being a list of colours and becomes a list of *roles*: twenty-three of them, fixed, whose
values come from a **theme pack** a temple administrator selects under Settings and which applies to
everybody who serves at that temple. The terracotta ships as the default pack, `temple-terracotta`,
so a temple that tries three others can come back to it by name.

The packs live in `frontend/lib/theme-packs.ts` rather than in the database. The first version of
this change created a `theme_packs` table and was replaced before it ran anywhere: nothing writes a
pack at run time, so a table was code wearing a table's clothes, and it dragged in a migration per
change, a policy set, an endpoint, a permission and an operator screen to administer sixteen rows.
The database now holds one column (V72) carrying which pack a temple picked, and has no opinion
about which packs exist — an unknown identifier resolves to the default in the browser. Packs are
retired rather than deleted, so withdrawing one never changes anybody's screens underneath them.

Nothing else in this document became themeable. Type, spacing, radii, motion and the geometry of a
field are decisions about legibility and rhythm, and a temple has no reason to want to change them.

**The focus ring has its own token** (§2, §4), and this half is a defect rather than a feature. The
ring had been drawing its colour from `accent-border`, whose job is the quiet hairline on a
secondary button and of which no contrast is asked. Measured while building the packs:
**`#ECD9CF` on the page is 1.36:1**, against the **3:1** WCAG 2.2 SC 1.4.11 asks of a focus
indicator. The one thing a keyboard user has to tell them where they are has been invisible since
the palette was written. Separating the two is what made it fixable — raising the shared value would
have put a dark line around every secondary button on every screen — and `focus-ring` is set to
`#BE775E`, the lightest terracotta clearing the floor on all three surfaces.

**Accessibility became a contract rather than a hope** (§2). `tools/theme/build_theme_pack.py`
holds the thirty-four pairings this interface actually makes, each with its floor, and *solves* every
lightness in a pack against them in OKLCH rather than choosing a colour and checking it afterwards.
A pack that fails one pairing does not build, and `__tests__/theme-contract.test.ts` runs the same
thirty-four over the whole catalogue on every commit, so one does not ship either. Both contrast failures in this project's history —
`ink-muted` on 20 August and the focus ring above — came from picking a colour and not thinking to
check a pairing, and fifteen packs is forty times the opportunity to do it again.

---

## PROJECT_COMMANDMENTS.md

### v1.2 — 2026-09-07 — Commandment 6 says `KMS-nnnnnn` (approved by Rajeev)

One phrase, and it is here because the commandments are locked and the rule is that a locked
document does not change without an entry — not because the change is large. Commandment 6 tells
whoever writes a UAT story to name "the specific `KMS-nnnn` codes that should appear". Error codes
became six digits on the same day (see **Error codes**, below), so the pattern was describing a
form no code takes any more, and a UAT story written to it would have been written to a shape that
no longer exists. Now `KMS-nnnnnn`.

Rajeev signed off the renumber and this consequence of it on 2026-09-07. Nothing else in
Commandment 6 moved, and no other commandment was touched. As at v1.1, the commandments still carry
no `docs/versions/` snapshot; the question of whether they should is still open.

### v1.1 — 2026-08-09 — Amended (approved by Rajeev)

Commandments 5 and 6 revised to separate a coding story's definition of *done* from user acceptance testing. The original text read Commandment 6 as "UAT every feature before closing its story," which assumed every story is a self-contained, independently demonstrable feature. Foundation work is not: tenant isolation, the audit kernel, background jobs, and observability have no manual surface and are verified by automated tests, while user-facing capabilities routinely span several coding stories (onboarding is E1-S4 + E1-S5 + E1-S6 together). Forcing a one-to-one UAT story onto that shape produces hollow tests and stalls coding stories behind acceptance passes that cannot yet run.

Now: a coding story is done on automated tests + review + design-doc conformance (plus a hand smoke-test where it has a surface); UAT is a separate activity scoped to a demonstrable capability, batched at capability and release boundaries, with a defined story template and two-way traceability between coding stories and the UAT story that covers them. This is the first recorded amendment to the commandments; the original stands as v1.0. (The commandments have not carried `docs/versions/` snapshots as the three core specs do — worth deciding separately whether they should.)

---

## DESIGN_SYSTEM.md

### v1.4 — 2026-08-21 — The accent cleared AA, and the words and geometry of a form settled (approved by Rajeev)

Three things this document did not say, each of which fifty pages had therefore each decided for
itself. From Rajeev's second drive of the built app, `docs/stories/BUILD-BRIEF-2026-08-21.md`.

**The accent darkened.** `accent #BE6444` under `ink-inverse #FCF8F5` — the primary button, the one
control every screen is built around — measured **3.90:1**, against the 4.5 WCAG AA asks of
body-size button text. v1.3 made contrast a floor across the site and this pair was missed, because
it is a fill under text rather than text on a surface. `accent` moves to **`#AE5838`** (4.68:1) and
`accent-hover` to **`#94482D`**, keeping the same step of darkening between them. Measured, not
estimated (§2).

**The geometry of a field** (§4). An input sets its text 13px in from the box edge; the label, hint
and error sat at 0, so a label floated 13px to the left of the word it named. All three now indent
to a named `field-inset` token. One label style, `text-sm font-medium text-ink`. And a row of fields
declares three shared tracks — label, control, hint — via `grid-rows-subgrid`, because `align-items`
can only line up outer edges and the outer edges are not what a reader is looking at. Both settings
of `align-items` had already shipped as the fix for that row, twice each, each with a comment saying
why it was right.

**One screen, one task** (§4). Anywhere a button opened a form on top of a list, the form is now its
own screen: its own URL, the sidebar kept, the task as the `h1` with one line of context under it,
`[Cancel] [Primary]` top right and nowhere else, and a sticky header — because terminating somebody
with a ban ticked runs 1232px down an 836px window, and there was no scroll position where the
person's name and the button that ends their employment were both on screen. Four fields or more
converts; three or fewer stays inline.

**The words** (§9). The document settled colour, type, spacing, icons and error messages, and not
case — so hints were being written three ways on the same form. Now: sentence case everywhere,
twelve words to a hint, no semicolons, no ALL CAPS in content, no emoji, and one word per thing
site-wide. Four kinds of text are exempt and never deleted — consent wording, the DPDP line on PAN,
the warning above a ban, and anything stating that money moved.

Each rule has a check in `frontend/__tests__/design-system.test.ts`, so it stays.

### v1.3 — 2026-08-20 — Contrast made a floor, and badges set in semibold (requested by Rajeev)

Rajeev, looking at the workforce pebbles on the meal planner: *"I can hardly see the icons and the
numbers... go through the entire site and look at the values of Background VS Text displayed on them
and if they are too close make the text darker so it is readable."*

He was right, and the arithmetic was worse than the impression. Measured across every pair the app
renders, seven failed WCAG AA. `ink-muted` failed on all three surfaces — 2.99 on canvas, 2.82 on
raised, 2.57 on sunken — which meant "muted" had been quietly meaning *faint* everywhere it was
used: hint lines, table metadata, and those pebbles, which at 2.57:1 were the worst pair in the
product. `warning` sat at 4.13 on its own wash.

Both tokens are darkened by the least that clears the threshold, so each stays recognisably the
colour it was: `ink-muted` `#9C948C` → `#716B65`, `warning` `#8F6A1C` → `#87641A`.

His second rule — *"Just text can be made slightly darker so it is readable. Text in pills has to
stand out instantly"* — is now written into the type section: badges and pills are set in 600
semibold, because contrast alone makes a thing legible and legible is not the same as instant. The
shared `Badge` and 35 hand-rolled chips across 15 screens were brought in line.

One exception is recorded rather than fixed: the primary button's label measures 3.90:1, and
correcting it means repainting the terracotta that is the product's identity. That is his call, not
a defect to quietly patch.


### v1.2 — 2026-08-19 — A fourth semantic colour, and Ekadasi off the accent (requested by Rajeev)

Rajeev, on the calendar: *"Can we use the color we used for Ekadasi for Fasting day and use #edf7fc for Ekadasi instead."*

Two things follow. First, an **`info`** family joins danger, warning and success — `bg #EDF7FC` as he
specified, with `#356780` as the saturated member for dots and text, chosen at the same lightness as
`success #3E6B48` so the four read as one set. Second, Ekadasi moves onto it and the ordinary fasting
day takes the terracotta wash Ekadasi used to have.

Worth recording the tension rather than burying it: this document's own rule is that the terracotta
accent has *one job only* — the primary action on a screen — and Ekadasi has been quietly breaking
that since the calendar was built. Moving Ekadasi off the accent fixes the older violation; moving
the fasting day onto it re-creates a milder one, since what appears on a day cell is the pale wash
(`accent-bg`) rather than the saturated accent, and a pale wash on a calendar square is not going to
be mistaken for a button. Flagged for Rajeev on 2026-08-19; his call, and he made it.

The planner's Ekadashi badge was gold (`warning`) while the calendar's was terracotta — the two
screens had disagreed about the same day since they were built. Both are now `info`.

### v1.1 — 2026-08-10 — Palette revised (approved by Rajeev)

The colour palette changed from the Cocoon-derived olive-on-beige to a terracotta-on-warm-grey scheme, at Rajeev's request. Rationale: the olive greens weren't growing on him, and ISKCON's own saffron-orange identity (per iskconsv.com) is a better fit. The accent is a **softened/desaturated terracotta** (`#BE6444`) so it stays flat and calm rather than loud; text is **warm charcoal** (`#2B2621`); neutrals are a **near-neutral warm-grey** (`raised #FAF8F7`, `sunken #F1EDEB`) rather than the earlier warm cream, so the orange never overwhelms the surfaces. Semantic **warning shifts to gold** (`#8F6A1C`) so it can't be mistaken for the orange accent. Only colour tokens changed — spacing, type, radius, and every structural rule (incl. "one accent, one job") are unchanged. Applied centrally in `tailwind.config.ts`, so all screens re-coloured through tokens; the backend recipe-PDF template and the design-reference page were updated by hand (the only places colours were hardcoded). No `docs/versions/` snapshot: DESIGN_SYSTEM.md was never under that regime, unlike the three core specs.

---

## REQUIREMENTS.md

### v1.4 — 2026-09-04 — Catering is gone, meals are three-plus-events, and equipment gets serviced (approved by Rajeev)

Three amendments, all from the 2026-09-04 conversation, all at Rajeev's explicit instruction.

**§3 Meal Planner — the four contexts are now three main meals and everything else.** The locked text
said meals were planned *"across four contexts: regular days, weekends, festival days, and outside
catering commitments"*, which conflated two different things: what kind of day it is, and what kind
of cooking it is. Rajeev's classification replaces it — *"There are 3 MAIN Meals a day. Breakfast,
Lunch and Dinner. The temple cooks those 365 days a year because they have a small army to feed."*
Everything else is an **event**: its own preparation, its own name, quantified by how much to make
rather than by a head count, in-house or sent outside. Regular, weekend and festival stay, demoted
to what they always were — the character of the day, read from the calendar.

**Catering is removed from the product.** Not renamed, not deprecated: removed. The day type, the
meal kind, the `needs_client` flag, UAT-033 and the never-built *Upcoming catering* table all go. It
was added because ISKCON South Bangalore asked for it, and Rajeev's own reconsideration is the
reason it goes: *"I dont think all ISCKONS do that. So I was thinking, why cant those folks who do
Catering service use the Event and call it Catering Event."* They can, and they gain by it — the
Event block asks for everything catering asked and six things more. Verified before agreeing:
`DayType.CATERING` had **no reader in product code**, and the table UAT-033 tested had never been
built. Meals the temple actually cooked are migrated, never deleted. Detail in E4-S15.

**§2 Inventory — equipment service status is spelled out, and preventive maintenance comes into
Phase 1.** The locked text already required equipment to be tracked by *"condition, location, and
service status"*; it is now explicit that service status means a servicing history, an interval, a
derived next-service date, and who does the work, with overdue equipment surfaced to the temple
administrator. This **overrules E3-S4's own assumption** that preventive-maintenance scheduling was
Phase 2. Rajeev's reason: *"There might not be a phase two any time soon and we dont want them to
wait for it forever."* Detail in E3-S10 and E3-S11.

**Not changed, deliberately.** `docs/versions/` snapshots, applied Flyway migrations, the build
briefs and `docs/reviews/` still mention catering and are left alone. They record what was true when
they were written, and editing them would be rewriting history rather than changing a decision.

### v1.3 — 2026-08-31 — The order list is the shopping list (approved by Rajeev)

**Terminology only. No requirement, no boundary and no behaviour changed** — §3.1's sufficiency
paragraph and §3.2's first ordering bullet say *shopping list* where they said *order list*, and
that is the whole of it.

The screen was called the order list and never was one. It is a **proposal of what to buy**,
computed from demand and editable in full before anything is committed to anybody; purchase orders
are the next screen along, and they are what a vendor receives. Two neighbouring destinations both
named after orders is how a draft gets sent to a supplier, and it is the confusion the reviewers
walked into.

**"Purchase plan" was considered and rejected.** It is finance's word, and this application's
vocabulary is deliberately the temple's — the people using the screen include storekeepers who do
not work in procurement. The person who carries the list to the market calls it the shopping list.

Renamed in one pass so that nothing is left saying one thing on the screen and another in the code:
the route, the API path, the Java package, the table (`V81`), the menu entry and every document.
`/order-list` redirects permanently, because somebody has it bookmarked. Recorded as **E5-S2 D1**.

Minor (v1.x): a rename of a thing the document already described, reversing no decision and
invalidating no dependent work. Snapshot: `docs/versions/REQUIREMENTS_v1.3.md`

### v1.2 — 2026-08-30 — A temple has kitchens, and the store can issue to them (approved by Rajeev)

Two things this document did not know, both of which the temple has been doing all along.

**A temple is not one kitchen.** §3.1 gains the register: three to five kitchens under one roof and
sometimes ten, sharing one store room, most of which will never open this application. The list is
flat — the kitchens a temple runs are peers, and the first draft of the design asked whether it was a
two-level tree because the brief could be read either way. Rajeev settled it: *"there is no further
hierarchy … it is just temple and a bunch of child kitchens underneath it and only one of those
children is marked as the main kitchen."* So `is_main` is a label, and the flag that actually changes
behaviour is whether a kitchen plans its meals here.

**Stock can now leave by a second door.** §3.1's stock model had been closed and exhaustive since
v1.0 — increases from receipts and gifts, decreases from meals and adjustments — which was true for
the one kitchen whose meals this application sees and for no other. A kitchen may now ask the store
for ingredients, an admin or a Kitchen Manager may answer, and the store records what actually went
over the counter. That recording is the moment stock moves, mirroring the line already drawn between
sending a purchase order and receiving one.

**Two decisions inside that are worth reading before changing anything.**

*One store, not one per kitchen.* Issuing takes food off the temple's books rather than moving it
into a balance held by the receiving kitchen. Nothing would ever draw such a balance down — the
kitchen holding it is not running this software — so within a month it would claim the Deity kitchen
still holds rice it ate in September. v1.1 refused the same shape once already, keeping leave-balance
accrual out of Phase 1 because a balance nobody reconciles is a number that misleads.

*One kitchen, one door.* A kitchen that plans its meals here has its stock drawn when those meals are
recorded, so it may not also raise requests; allowing both takes the same rice off the books twice.
The temple was told about the double-count risk and said it would be careful, which is not a
guarantee, so the system makes it unreachable rather than trusting it. Turning the planner on for a
kitchen settles the requests already in flight — drafts deleted, anything awaiting or holding
approval denied, anything issued or dated in the past left alone as history.

Built as Epic 10. The design, with the questions it turned on and the two places Rajeev's answer
overturned the recommendation, is in `docs/stories/EPIC-10-kitchens-and-issuing-DESIGN.md`.

### v1.1 — 2026-08-20 — Payroll and leave move from Phase 2 into Phase 1 (approved by Rajeev)

A requirement change from the customer, not scope creep. Rajeev, 2026-08-20: *"The temple came back
and wanted it in Phase 1. So had to pivot and include it in."* That statement is the sign-off
Commandment 8 asks for, and this entry is the record of it.

What moved, and what deliberately did not:

- **In**: time off and sick leave, as a request-and-approve log — types time off / sick / unpaid,
  half-days, approved by the temple admin or by a Kitchen Manager where a temple has appointed one,
  back-datable and revocable. Approved leave drops the person out of the schedule grid and the
  workforce count.
- **In**: staff payments for salaried staff — an optional monthly salary, payments by cheque, cash
  or payroll, cash advances, and deductions that recover them. The app records; it does not compute
  what is owed. That line is the whole of the boundary: computing salary owed needs a pay period, a
  start date and a ledger of settled periods, which is payroll, and nobody asked for payroll.
- **In**, arriving with the meal-recording change rather than with payroll: **actual servings**,
  previously listed in the Phase 2 backlog as "waste and actual-vs-planned tracking". Recording a
  meal now captures what actually went out, per dish. Leftovers and waste weight stay in Phase 2.
- **Out, and stated so it is not assumed back in**: **leave-balance accrual** — never asked for, and
  a balance nobody reconciles is a number that misleads. **Attendance** — hourly pay was dropped, and
  hours worked were the only thing that would have required recording attendance, so nothing is left
  for it to serve.

Two consequences worth recording here rather than leaving to be rediscovered. A fifth role,
`KITCHEN_MANAGER`, joins the authorisation policy, because "the kitchen manager approves leave"
would otherwise collide with E6-S8's rule that a job title is a label and gates nothing; the
resolution is the one already on record in BL-4 — more roles, not a second concept beside them. And
the temple gains a currency, used properly by everything built for staff pay, while the existing
rupee-named columns across donations, wish list, invoices and purchase orders stay exactly as they
are: retrofitting them for a temple that does not exist is churn for a guess.

E6-S1's assumption line is amended in step, in both `docs/stories/EPIC-6-workforce-management.md` and
its GitHub-import body.

### v1.0 — 2026-08-03 — LOCKED
Approved by Rajeev. Stage 1 (Requirements & Wireframes) complete.

- All four core modules (Kitchen Management, Ordering System, Workforce Management, Payments & Donations) specified.
- Six Phase 1 gap features added: recipe scaling, sattvic ingredient enforcement, in-kind donation intake, partial/rejected delivery handling, volunteer waitlist with auto-promotion, Vaishnava calendar integration.
- Six Phase 2 backlog items recorded: cost-per-plate analytics, sponsor-a-day/feast, volunteer reliability tracking, vendor scorecards, waste tracking, multilingual UI.
- India regulatory research completed: FSSAI/BHOG (voluntary, deferred to Phase 2) and 80G donation receipting (donor data captured Phase 1, Form 10BD/10BE filing deferred to Phase 2).
- Recipe/purchase-order translation and PDF/print, WhatsApp PO delivery, and India-specific workforce reminder design (WhatsApp-first, per-event timing config, per-user channel preference) added.
- Vaishnava calendar resolved to astronomical computation (post-2006/Hari-bhakti-vilasa schema), not calendar import.
- Snapshot: `docs/versions/REQUIREMENTS_v1.0.md`

---

## SYSTEM_DESIGN.md

### v1.3 — 2026-08-31 — The order list is the shopping list (approved by Rajeev)

**Terminology only,** following REQUIREMENTS.md v1.3 and for the same reason. Three mentions change:
the background-worker box in the §2 diagram, the job list in §5's Postgres-backed queue bullet, and
§9's note that the heaviest operations are background jobs. All three now say *shopping-list
generation*.

Nothing about the architecture moves. The nightly job, its queue, its schedule and what it computes
are unchanged; the Java package and the table behind it were renamed in the same pass (`V81`) so the
document and the code agree. §5's entity list never named the table, so it needed no edit.

Minor (v1.x): no reversal of an earlier decision and no re-review of dependent work.
Snapshot: `docs/versions/SYSTEM_DESIGN_v1.3.md`

### v1.2 — 2026-08-30 — §5 learns about kitchens and ingredient requests (approved by Rajeev)

Follows REQUIREMENTS.md v1.2 and records the same two facts in the entity list: `kitchens`, flat under
the tenant, and `ingredient_requests` with its lines and dishes. `stock_movements`' parenthetical grows
a fifth kind of change — ingredients issued to another of the temple's kitchens.

Nothing about §3 Multi-Tenancy moves. A kitchen is a grouping **inside** a tenant, an ordinary
`tenant_id` column and an FK, and explicitly **not** a second RLS dimension: the isolation boundary
stays nailed to the temple, one level, enforced by the database. Epic 9 remains the only thing in this
system that deliberately crosses it.

### v1.1 — 2026-08-11 — Observability surface split by audience (approved by Rajeev)

§10 "Business observability" revised. The single Super-Admin ops page described at v1.0 mixed platform-operator concerns with one-temple detail. It is now split by audience: the **Super-Admin Operations page** carries platform vitals only — system health, plus platform-wide notifications sent/failed today rendered as a **seven-day pulse of two-hour buckets** (each day split into twelve slots, so *when* sends and failures cluster is visible, not just how many). **Per-temple** detail (a temple's own sent/failed/suppressed breakdown, recent failed sends, last calendar precompute) moves to a proposed **Temple System Health Dashboard** (`docs/stories/BACKLOG.md`, BL-1), deferred out of the pilot.

Rationale: the platform-operator role deliberately holds no temple permissions, so the operator's cross-tenant view is deliberately limited to **aggregate counts that carry no temple business data** — distinct from the audit drill-in, whose per-record before/after would leak donation/payment detail through a side door. A count of sends is a legitimate operator vital sign; a temple's records belong to that temple's admin. The counts are still assembled from properly RLS-scoped per-tenant reads summed in app code, never a BYPASSRLS query. Two-hour buckets are computed in Asia/Kolkata (India-first display timezone).

Minor (v1.x): no reversal of an earlier decision and no re-review of dependent work — the ops page was always "lightweight business observability"; this refines what it shows and to whom. Snapshot: `docs/versions/SYSTEM_DESIGN_v1.1.md`

---

### v1.0 — 2026-08-03 — LOCKED
Approved by Rajeev. Stage 2 (System Design & Architecture) complete.

- Modular monolith architecture (not microservices) — sized for solo-operator pilot scale.
- Multi-tenancy via shared schema + PostgreSQL Row-Level Security.
- Postgres-only data layer (no Redis/broker at pilot scale) doing triple duty as system of record, audit log, and job queue.
- Managed IdP for auth (phone-OTP required), REST API, UPI-first payment integration, WhatsApp BSP integration, translation API — all left as named but unpicked in Stage 3.
- Cost envelope estimated at $80–180/month against a $50–200 budget.
- 8 open items explicitly carried to Stage 3 (Tech Stack).
- Snapshot: `docs/versions/SYSTEM_DESIGN_v1.0.md`

---

## TECH_STACK.md

### v1.0 — 2026-08-03 — LOCKED
Approved by Rajeev. Stage 3 (Technology Stack Selection) complete.

- All 8 open items from SYSTEM_DESIGN.md v1.0 §13 resolved: GCP (`asia-south1`/Mumbai); Spring Boot + Spring Data JPA backend; Next.js (React/TS) frontend; Firebase Authentication; Razorpay; Meta WhatsApp Cloud API direct (not a BSP — cost correction from the design-stage placeholder); Bhashini with Google Cloud Translation as an abstracted fallback; Quartz Scheduler (JDBC store) + Playwright (Java)/Chromium for PDF generation; GitHub + GitHub Actions + GitHub Issues/Projects; JUnit 5 + Mockito + Testcontainers, Vitest + React Testing Library, Playwright for testing.
- Pre-existing ERPNext + Zoho CRM + Power BI proposal formally evaluated and rejected in favor of the custom build (see memory: ERPNext Proposal Superseded).
- Backend revised during review from Django to Java/Spring Boot at Rajeev's request (existing Java expertise). Consequences worked through explicitly: Procrastinate → Quartz Scheduler; WeasyPrint → Playwright (Java)/Chromium for PDF generation, after Apache PDFBox was evaluated and rejected specifically for lacking GPOS support (no vowel-sign positioning for Devanagari); Django's free admin panel has no Java equivalent — resolved by building a small set of Super-Admin/Temple-Admin screens into the existing Next.js app rather than standing up a second frontend stack.
- Revised cost envelope: ~$90–195/month (was ~$85–185 under Django), still inside the $50–200 budget with reduced margin.
- Snapshot: `docs/versions/TECH_STACK_v1.0.md`

---

## docs/stories/

The story set is marked DRAFT (see `docs/stories/README.md`) and refined during implementation as building a story reveals gaps in an adjacent one. Changes are logged here for the record (Commandment 8) but do not carry `docs/versions/` snapshots while in draft.

### 2026-08-09 — EPIC-1, during E1-S7 implementation

**E1-S7 (Audit log framework) — Super-Admin viewing reinterpreted.** Approved by Rajeev. The story previously read "Super-Admin sees platform-level events." With `audit_events` decided as fully tenant-owned (RLS, `tenant_id NOT NULL`, no BYPASSRLS for the app role — consistent with every other tenant table), a Super-Admin cannot read across tenants at all. Reinterpreted to a **drill-in model**: a Super-Admin reads one temple's log at a time by selecting that tenant. Rationale beyond uniformity: a cross-tenant firehose would be a backdoor around E1-S5 RBAC — the Super-Admin role is deliberately denied `VIEW_DONATIONS` and `MANAGE_VENDOR_PAYMENTS`, but audit `before/after` values carry donation amounts and payment records, which a firehose would expose through a side door. Cross-tenant operator visibility lives in Cloud Logging, not the audit table. Added: a Super-Admin's drill-in is itself audited so the capability is not silent. Acceptance criteria updated accordingly.

**E1-S12 (Temple user management) — new story added.** Approved by Rajeev. E1-S7 surfaced that `MANAGE_USERS` exists and E1-S6's UI promises the first administrator can "add everyone else," yet no story built the surface — a temple with one admin and no way to add a cook is unusable. The role-change endpoint seeded in E1-S7 is the starting point; invite-user and disable-user belong in the new story. Epic 1 story count 11 → 12.

### 2026-08-10 — EPIC-1, E1-S8 scope decisions

Approved by Rajeev. E1-S8's "registration/profile" was reinterpreted for this application's reality and its dependencies:

- **"Registration" is not a signup form.** There is no open self-registration — users are created by provisioning (E1-S6) or invites (E1-S12), where email and phone are already collected and validated. E1-S8 therefore delivers the **self-service profile** (view contact details, change preferred channel) plus **communication consent**, not a registration screen. The "cannot register without valid email and phone" criterion is met by the provisioning/invite paths.
- **Consent is the user's own act, soft-gated.** The DPDP consent to be contacted must come from the person, not the super-admin who typed their details — so it is captured on first sign-in / from the profile, and gates *notifications* (E1-S10), never app access.
- **Contact details are read-only in the profile for now** (changing a phone needs re-OTP; changing an email collides with the sign-in identity) — a later increment / admin action.
- **Added `consent_version`** (migration V5) beside `contact_consent_at`, so a bare timestamp can prove *what* wording was agreed to and people can be re-asked when it changes.
- **Deferred to E1-S10:** "preference takes effect on next notification" and the first-notification verification message need the notification service, which does not exist yet. Verified as part of a later notifications capability (see UAT-010).

### 2026-08-10 — EPIC-1, E1-S10 scope decisions

Approved by Rajeev (WhatsApp Business API and Razorpay both unavailable, timelines unknown). The notification service is built in full behind channel **adapters**, so procurement does not block it:

- **All three channels are dev adapters** (log + can be forced to fail) — no real provider is wired, because none is ready. A real Meta WhatsApp / SMS / email adapter is a drop-in replacement for the one class of its channel. Meta's external setup is tracked in `docs/META_WHATSAPP_SETUP.md` (the long pole).
- **`notify()` is asynchronous and consent-gated:** it records the message and enqueues a send job (E1-S9), never sends inline; a user who has not consented (E1-S8) is recorded SUPPRESSED and not sent to. Vendors (raw phone, no account) carry no consent gate.
- **Fallback cascade** preferred → SMS → email, each attempt recorded.
- **Delivery webhook** is signature-verified (HMAC-SHA256) and idempotent (advances status only out of a non-terminal state, so Meta's retries are harmless). It finds a message pre-tenant via a new `app.webhook_message_id` RLS escape, mirroring the `auth_uid` / `claim_contact` escapes.
- **Deferred (external-blocked):** the real provider adapters and the "a test user receives a real WhatsApp message in staging" criterion — verified with the dev adapters and a simulated webhook for now (UAT-052). The **email-first** decision (making email the primary channel to ship without Meta) remains open; the adapter design commits us to nothing until it is made.

### 2026-08-10 — EPIC-1, E1-S11 scope decisions

Approved by Rajeev. Observability split along the two layers §10 already implies:

- **Structured JSON logs** carry `request_id` / `tenant_id` / `user_id` on every line (JSON under the `json` profile for Cloud Logging; readable locally). The `request_id` is **propagated into background jobs**, so a request and the send job it queued share one id across the API/worker boundary.
- **`/health`** now does real DB + scheduler checks (200/503) for the external uptime monitor.
- **Metrics → `/actuator/prometheus`** (job + notification + webhook counters), for Cloud Monitoring to scrape — this is where platform-wide aggregates, trends, and the job-failure-rate alert live.
- **Ops page reinterpreted:** §10's "jobs sent/failed today" as literal in-app platform totals hits two walls — RLS (the Super-Admin holds no BYPASSRLS, so no cross-tenant DB aggregate) and per-instance metrics (job counts live on the worker, not the API). So the in-app ops page is **system health + a per-temple operational drill-in** (consistent with the audit drill-in), and platform-wide aggregates are Cloud Monitoring's job. "Last calendar precompute" is deferred to E4 (no `calendar_days` table yet), shown as "not available yet".
- **Sentry:** backend wired via the Spring starter, inert until `SENTRY_DSN` is set. **Frontend Sentry deferred** to the frontend-integration effort — it is near-valueless on the current static shells (no live errors to catch until the UI is wired to the API), and an npm cache permission fault in this environment blocked a clean install; it will be added with `@sentry/nextjs` when the frontend is connected.
- **Deferred (external / ops setup, not code):** the external uptime monitor + phone/WhatsApp alerting, and Cloud Monitoring dashboards/alerts. The ACs "a test exception appears in Sentry" and "killing the DB fires the external alert" are staging steps against real accounts (UAT-004).

### 2026-08-11 — EPIC-1, E1-S15 written and completed (temple detail, data export, deletion)

Rajeev's decisions, recorded in the story as twelve numbered choices. The view-and-delete halves had
shipped in `ab7e073` **with no story at all** — found by the UAT pack's traceability pass (gap G1) —
so the story was written retrospectively and the one thing it was missing was built with it.

- **Deletion stays unconditional.** Considered and rejected: refusing to delete a temple holding
  completed donations or vendor payments. Rajeev's call — the safeguard is the export, not a guard on
  what the data contains. The trade-off is stated in the story rather than left implicit: an operator
  can destroy donation and audit history that no other code path can touch.
- **Export before delete, enforced by the API.** A temple cannot be deleted unless it was exported in
  the last 24 hours (`KMS-400081`). The `TENANT_EXPORTED` platform-audit event is both the record and
  the check, so what the log says and what the guard allows cannot drift apart.
- **The export is an Excel workbook** — a tab per table, raw rows, column headings, an autofilter and a
  frozen header, named after the temple. Excel rather than CSV or JSON because the likely reader is a
  temple accountant. Tables are discovered by "has a `tenant_id` column", the same rule the purge
  uses, so anything the purge destroys the export contains.
- **Encrypted values stay encrypted** in the file: a platform operator is denied `VIEW_DONATIONS`, and
  an export must not become a side door to plaintext donor PII.

Verified by 21 tests (5 workbook, 4 filename, 6 export integration, 6 deletion integration incl. the
stale-export and wrong-temple cases). Suite: 676 passed / 2 skipped backend, 134 frontend.

---

### 2026-08-10 — EPIC-1, E1-S12 implemented (Epic 1 complete)

Approved by Rajeev. Temple user management, completing Epic 1's foundation:

- **Add a person** — created pending their first sign-in (a `pending:` uid, claimed via E1-S6, own consent via E1-S8); `SUPER_ADMIN` refused (`KMS-400023`); a duplicate email at the same temple refused (`KMS-400033`).
- **Change role** — reuses E1-S7's guarded endpoint, now exposed in the People screen.
- **Disable / re-enable** — a status flip that blocks access on the next request (E1-S4), never a hard delete; you cannot disable your own account (`KMS-400024`).
- All three are audited with before/after and RLS-scoped to the acting admin's temple.

Notes: **"last activity"** in the user list is omitted — nothing records a last-seen time yet (a small future column), so it is left out rather than faked. The **People** and **Audit log** nav entries sit in the shared temple nav; splitting the temple nav by permission needs the frontend wired to the signed-in user's role, so it is deferred to the frontend-integration effort (verify: UAT-008).

### 2026-08-20 — Stories written for the 2026-08-20 build (EPIC-3, 4, 6, 7 and a new EPIC-9)

Written after the work, from `docs/stories/BUILD-BRIEF-2026-08-20.md`, which is the record of the
2026-08-19/20 conversation and the specification the build was made from. The scope change itself is
recorded separately under REQUIREMENTS.md v1.1 above and is not repeated here; this entry is about
the stories.

**Thirteen stories added.** Epic 3: **E3-S8** cost of materials. Epic 4: **E4-S10** per-meal
recording, **E4-S11** the job card, **E4-S12** dish swap-or-edit, **E4-S13** the outside-event
purpose, **E4-S14** the Today rewrite. Epic 6: **E6-S10** leave, **E6-S11** the editable week grid,
**E6-S12** the `KITCHEN_MANAGER` role, **E6-S13** staff pay, **E6-S14** the workforce count. Epic 7:
**E7-S10** the donations period control and year-on-year comparison. Epic 9: **E9-S1** the platform
notice board and **E9-S2** the ban record and the check at hire. The set goes from 8 epics and 70
stories to **9 epics and 84 stories**; `docs/stories/README.md` carries the table, whose per-epic
figures had drifted from the files since v1.0 and are now the real counts.

**EPIC-9 stops being a design.** It carried six open questions, all now answered (build brief §10 and
§11). The questions and their answers are kept in the file rather than deleted, because two of them
were decided against the recommendation and the argument is worth more than the conclusion:
**Q1**, the broadcast naming a person, was **dropped in both forms** — the named version on
defamation and DPDP grounds, the unnamed version on Rajeev's, that a notice nobody can act on is a
rumour with no handle on it, useful to nobody and corrosive anyway; and **Q3** reversed the
recommendation that the subject be shown what was recorded, because disclosure at the moment of
firing invites retaliation, and DPDP's right here is to information on request. The design's E9-S2
and E9-S3 collapse into one story, since a record raised at a dismissal and a check run at a hire
are one act once nothing is broadcast between them.

**Four earlier stories say plainly that they were overturned**, rather than being edited into
agreement. **E6-S1** loses per-date exceptions to E6-S11. **E6-S8**'s D8 — *"salary is not
collected"* — is reversed by E6-S13, and its D9 permission split is recorded as having held exactly
as designed when E6-S12 finally exercised it. **E4-S4** loses per-dish *Mark as cooked*. **E4-S8**'s
tiles are partly superseded by E4-S14. **E3-S6**'s consumption is now drawn against actual servings
rather than planned. A decision that was reversed is more useful on the record than one that appears
never to have been made.

**BL-4 and BL-6 are closed in `docs/stories/BACKLOG.md`**, in a convention the file now states: a
built item keeps its entry and gains a `CLOSED` line saying what closed it and where the work
landed. BL-4 was closed by the `KITCHEN_MANAGER` role — its own recommendation, *more roles, not a
second concept beside them*, taken unchanged. BL-6 was closed by the notice board and the check at
hire, one item short of what it asked for: the broadcast about a person is not built and will not
be.

**Two judgement calls worth naming.** Cost of materials went to Epic 3 rather than Epic 4, because
every argument that shapes it is an inventory argument — the reason it can never become exact is
that donated stock has an estimated value and no purchase price. And staff payments went to Epic 6
rather than Epic 7, because what the temple pays its own cook is a fact about a person it employs,
not about a donor or a vendor; putting it in Epic 7 would have put salary on a screen behind
`VIEW_DONATIONS`, which is what E6-S8's D9 split exists to prevent.

No UAT stories were written with these. The build brief's §14 makes Rajeev's own verification pass
the next step, and the UAT pack is written from what that pass finds.

### 2026-08-31 — Ten items from the presentation review, and the stories written for them

Written after the work. `docs/reviews/PRESENTATION-REVIEW-RESPONSE.md` holds the reasoning for all
eighteen review comments; this entry records what was built of them and where the stories landed.
Rajeev signed off all ten on 2026-08-31, including the two that needed it — the reversal of a locked
decision, and the rename.

**Three stories added, five amended.** New: **E3-S9** cost per serving by kind of meal, **E5-S9**
vendor performance, **E6-S15** where the schedule is short of hands, **E6-S16** conduct notes on an
employment record, **E10-S13** what the store issued to each kitchen. Amended: **E4-S6** (the
Ekadashi picker opens filtered), **E5-S1** (a reason on deactivation, kept as history, and a
contract end date that warns and never acts), **E5-S6** (the price captured where the goods arrive),
**E3-S8** (D2 corrected, D3 revised, D5 discharged), **E4-S7 D1** (the seeded meal kinds are seven,
not six — *Festival feast* was added by `V67` and the decision had not caught up).

**E3-S8 D3 is reversed, and it is worth reading why rather than that.** D3 said the materials
estimate was *"for the day, not per meal"*. It was right about the question it was asked: *what is
today's food costing us* is a headline for the Today screen, and a daily total is exactly that
answer. The reviewers asked a **comparison between categories** — what a public-prasadam plate costs
against a Sunday feast plate — and no single daily total can give one however it is presented. Same
data, different question, and the second answer is not a worse version of the first. So E3-S9 keeps
the split D3 threw away, over a period, on its own screen. **The daily total on Today is unchanged**,
and E3-S8's D1 (an honest estimate, because much of the store is donated) and D4 (no labour, because
a cook on a 6am–2pm shift makes breakfast *and* lunch and their pay can only be allocated, never
measured) both stand untouched. D5's extraction is discharged by the same story.

**Two review comments were declined in part, and the declines are in the stories.** The reviewers
asked for a *checkbox* to show only fasting-day preparations; E4-S6 D1 records why the picker opens
filtered instead. They asked us to call the meal category a *cost centre* rather than a kitchen;
E10-S13 D1 records why there is no third noun, and what the request was right about underneath it.
V1's automatic *validity dates* were rejected in E5-S1 D2 in favour of a date that warns and never
acts; that decision carries an open question for Rajeev about whether seven days is long enough for
a commercial contract, and whether both warning horizons belong in temple settings together.

**Two findings went to the backlog rather than into the build:** **BL-9**, that nothing validates a
purchase-order line's unit against the ingredient's family and `StockMovementService.record` does
not either, so a hand-posted cross-family line already books a nonsense movement today; and
**BL-10**, that `vendor_supplies.last_price` is `NUMERIC(12,2)` and a price converted down to a
small canonical unit loses precision.

No UAT stories were written with these. Five of them touch screens a person has to drive, and the
UAT pack is written from what Rajeev's own pass finds (Commandment 6).

---

## Error codes

Not a governing document, but the `KMS-` namespace is quoted from screenshots and support
calls and is meant to be permanent, so the one time it moved is recorded here rather than only in
a commit message.

### 2026-09-07 — Every code is six digits, and the old numbers are retired rather than reused (approved by Rajeev)

**What a user sees now: `KMS-400001` through `KMS-400123` for anything they did or that their
temple's data refused, and `KMS-500001` through `KMS-500005` for something that broke on our side.**
128 codes, numbered flat in the order they are declared in `ErrorCode.java`. The digits after the
first no longer carry any meaning, and are not supposed to.

**Why the old bands went.** The four-digit scheme grouped codes into hundreds that mirrored the
HTTP status — 4000s validation, 4100s auth, 4300s permission, 4900s conflict. Every entry already
stores its status as its own field, so the number was a second copy of a fact the code already
knew, and it constrained allocation for nothing in return: 103 of 154 references had crowded into
the 4900s conflict band, which had run out, while validation had used ten of its hundred and
not-found five.

**Why six digits and not a tidier four.** A flat four-digit scheme starting at 4001 lands on
4001–4129 for the client codes, and those numbers are already in use — `KMS-4102` means
`SESSION_EXPIRED` today and would have come to mean something else. Somebody reading an old code off
a screenshot or out of a commit would then get an answer that was wrong while looking perfectly
valid, which is worse than getting no answer. No four-digit code is a valid six-digit one, so the
two namespaces cannot overlap and no code ever means two things. That property is the whole reason
for the extra digits, and it cost a longer number to read down a phone — raised, and overruled.

**Why now.** We are pre-release. Nothing is printed, no manual quotes a code, and no temple has
filed a ticket against one. This was the last moment the change was free, and it does not become
free again.

**Old codes still resolve.** `docs/ERROR-CODE-RENUMBER-2026-09-07.md` carries the full 128-row
old→new table, sorted by the **old** number, because that is the one a person holding a screenshot
has. It also lists the four numbers that have no successor — `KMS-4017`, `KMS-4927`, `KMS-4969` and
`KMS-4972`, each either retired or proposed and never built — so that looking one up returns "this
was withdrawn" rather than silence.

**What stayed four-digit, on purpose.** Comments inside applied Flyway migrations, because editing
an applied migration changes its checksum and the application then refuses to boot against any
database that has already run it; V67 set that precedent against V22 and it is followed here. And
`KMS-0000`, the frontend's sentinel for "the server sent us no code at all" — it is not an
`ErrorCode` and so had no number to renumber.

**Still open.** Two things, both small and both flagged. The live `COMMENT ON
employment_ban_raising_tenant(uuid)` in V65 still names `KMS-4307`; correcting a comment that
reaches a real database needs a migration of its own, the way V67 restated V22's. And `KMS-0000` is
now the only four-digit code the application can put in front of a user, which argues it should
become a real code with real words.

Consequences: `PROJECT_COMMANDMENTS.md` v1.2 above, `CLAUDE.md`, and 182 files swept from a mapping
generated out of `ErrorCode.java` rather than by hand. `ErrorCodeTest` asserts the six-digit form
and the family-to-status agreement across all 128 codes.

---

## Build & tooling

Not governing documents, but recorded here because both items were E1-S1 acceptance criteria that had been marked done on CI evidence alone.

### 2026-09-04 — The deploy pipeline, and the cause nobody had guessed (work queue item 1)

Rajeev watched a release that changed no dependencies take about **twenty-five minutes** and asked
whether that was typical. It is not; three to eight is a tuned pipeline for a project this size.

The work queue had ranked the causes — no dependency caching first, then serial builds, serial
rollouts, health checks. **The largest one was not on that list.**

**`gcloud builds submit` reads `.gcloudignore` from the directory it is given, not from the
repository root.** Neither `backend/` nor `frontend/` had one, and neither had a `.gitignore` of its
own; the root `.gitignore` — which does exclude `node_modules/` and `.next/` — sits outside the
submitted directory and is never consulted. So every single deploy tarred, uploaded to GCS and
unpacked **861 MB of frontend** (645 MB of it `node_modules`, 213 MB `.next`) and **116 MB of
backend** (53 MB of build output). Measured after adding the ignore files: the frontend build context
is now **2.83 MB** and the backend's **28.8 MB**.

It was also a correctness bug. `COPY . .` in the frontend Dockerfile laid the **host's**
`node_modules` straight over the one `npm ci` had just installed in the `deps` stage — darwin/arm64
binaries into a linux image, and the dependency layer invalidated for nothing.

**The dependency cache the queue ranked first was also fake.** `RUN gradle dependencies --no-daemon
|| true` sat above the source copy calling itself a cache layer. `gradle dependencies` prints the
dependency *graph*; it does not download the jars. And `|| true` hid it when even that failed. So
`bootJar` re-fetched the whole tree every build, while compiling. Replaced by a real
`resolveDependencies` task in `build.gradle.kts` that resolves every resolvable configuration.

**And Cloud Build starts cold.** A fresh VM has no layer cache, so `--cache-from` against the
previous image is the only thing that makes any of the Dockerfile layering matter — including the
apt install and the Chromium download in the backend runtime stage, which had never changed and had
been redone every release. Both images now build via a `cloudbuild.yaml` that pulls the previous
image and its intermediate stage, passes both as `--cache-from`, and writes inline cache metadata
into what it pushes. Both moved to `E2_HIGHCPU_8`.

**Two things now run in parallel that never needed to be serial.** The images share nothing, and
`API_URL` is resolved from the *already running* api service before either build starts — a Cloud Run
URL is stable for the life of the service, so the frontend needs the address, not the new revision.
The ordering constraint the old comment described was only ever true on the very first deploy. At
rollout, the api still goes **first and alone** because it runs the migrations and the worker must not
start jobs against an unmigrated schema; the worker and the web now go together.

**Not traded away:** the Flyway migration check, the Cloud Run health check, and building each image
once and deploying that same digest. `deploy.sh` now prints build, rollout and total wall-clock, so
the next measurement is recorded rather than felt.

**Measured on the live pipeline, 2026-09-05**, which is what the work queue asked for:

| | Before (2026-09-01) | Cold cache | Warm cache |
|---|---|---|---|
| Builds | — | 9m17s | **3m46s** |
| Rollouts | — | 2m52s | **2m01s** |
| **Total** | **~25m** | 12m11s | **5m49s** |

The warm figure is the one that matters — it is what an ordinary release costs, and it sits inside
the three-to-eight-minute target. The cold column is the first deploy after the change, when the
`:cache` and `:builder-cache`/`:deps-cache` images did not yet exist for `--cache-from` to pull; it
is what a deploy costs after the registry is cleaned out.

**And the local verification was not enough.** Both `build` stages compiled on this machine, so this
entry originally called the change verified — and the first real deploy failed outright, because the
ignore files excluded `Dockerfile` and `cloudbuild.yaml`. `docker build` reads the Dockerfile from
the path and never noticed; `gcloud builds submit` honours `.gcloudignore` when it uploads the
source, and Cloud Build's BuildKit then looks for the Dockerfile *inside* that uploaded context:
`failed to read dockerfile: open …/buildkit-mount…/Dockerfile: no such file or directory`. Fixed,
with a comment in all four files so nobody excludes them again for tidiness. The lesson is the one
the queue item already stated — verify by measuring, not by feel — applied to the verification
itself.

### 2026-08-10 — Frontend-integration prep (CORS, Firebase, secrets)

Groundwork before wiring the frontend to the API:

- **CORS** added to the backend, env-driven (`CORS_ALLOWED_ORIGINS`, default `http://localhost:3000`, never a wildcard); exposes `X-Request-Id`; preflight from an unknown origin is refused. Bearer-token auth means no credentials/cookie handling.
- **Firebase Admin project id pinned** — a latent bug: the SDK was initialized with no project id, so it would default to the runtime credentials' GCP project (`iskcon-kms-2026`) while tokens are issued by the separate Firebase project (`iskcon-kms-2026-620ee`), and every real token would be rejected on an audience mismatch. Now set via `FIREBASE_PROJECT_ID` (default `iskcon-kms-2026-620ee`). Surfaced only because Firebase had never been enabled end-to-end.
- **Secrets in GCP Secret Manager:** Razorpay test key + secret added (`kms-staging-razorpay-*`, runtime SA granted access) — the last app secret that had been only local. Created via `gcloud`; to be brought into Terraform (`import`) when E7 consumes them.
- **Firebase runtime access:** the Cloud Run runtime SA granted `roles/firebaseauth.viewer` on the Firebase project (the cross-project step in `docs/DEPLOYMENT.md`), so `checkRevoked` token verification works when deployed.

### 2026-08-09 — Gradle wrapper restored; E1-S1 fresh-clone criterion actually verified

The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) was missing from the repository — only `gradle-wrapper.properties` had been committed. So `cd backend && ./gradlew test`, the command in CLAUDE.md, could not work in a fresh clone. It went unnoticed because CI runs `gradle build` via `gradle/actions/setup-gradle`, not `./gradlew`, and so never exercised the wrapper. E1-S1's criterion — "`./gradlew test` passes on a fresh clone" — had therefore never been verified; it was green on CI, which took a different path. Wrapper regenerated at Gradle 8.10 and the criterion verified for real by cloning the repo and running `./gradlew test` in the clone.

### 2026-08-09 — Testcontainers 1.20.1 → 1.21.4 (Docker Engine version drift)

The integration suite passed on CI but failed on a developer machine for the same commit. Cause: Testcontainers drives Docker through docker-java, which negotiates the Docker Engine API version at runtime; 1.20.1 defaulted to API 1.32, which Docker Engine 29 (minimum 1.40) refuses, while CI's older runner engine still accepted it. Rather than pin the API version by environment variable — which hides the skew until the next person hits it — Testcontainers was moved to a current 1.x that negotiates correctly across the engine versions in play (local 29, CI's `ubuntu-latest`). A `docker version` step was added to the CI backend job so the runner's engine version is always visible in the log, making any future drift diagnosable rather than mysterious.

---

## Application

Not governing documents. Recorded here because each entry closes a finding from the UAT docket of
2026-09-06 (`docs/work/INTAKE.md`), and because a reader asking "when did that screen start doing
that" should not have to read a commit log to find out. Every entry says plainly what is **not**
done, since none of these has been seen working by Rajeev yet.

### 2026-09-07 — A stock movement can be corrected, and an item can stop being tracked (docket M1 and M8, task T-001)

Both endpoints already existed, tested, with no caller anywhere. The inventory item page now has
them, which closes docket item **M1** and the premise of `OUTSTANDING_BUILD_LIST` **P8** — that a
mistake in stock is permanent. **Neither is marked done in `OUTSTANDING_BUILD_LIST.md`, and P8 stays
open**: nothing leaves that file until Rajeev has seen it working, and he has not.

Every row of the movement history carries a **Correct** action. It takes the mandatory note the
endpoint requires and appends the exact reverse of the movement — same batch, same unit, opposite
quantity — cross-referenced to the original, so the original is marked as corrected and the reversal
says what it reverses. Nothing edits the original; the ledger is append-only and this does not bend
that. The server refuses a second correction of the same movement, and that refusal is shown as its
own sentence rather than swallowed.

**Stop tracking this item** calls the metadata-only delete, behind a confirmation that says plainly
what it does and what it does not: the item comes off the inventory list and out of low-stock
warnings, and every movement stays in the ledger. Zeroing the stock as a substitute was explicitly
not done — that writes a false stock event, which is the confusion the docket raised in the first
place.

**A pre-existing render defect went with it,** on the very line the docket described as "already
labels corrections". The movement's reason and the word "Correction" were adjacent expressions with
no separator, so every correction row read **"Count correctionCorrection"**.

**One deviation, for Rajeev to sanction or reverse.** The Correct button stays enabled on a row that
already shows a correction, so a row can show a "Corrected" tag and a live button at once. The
argument for it: two people on two tabs is ordinary, and the second one to press deserves to be told
what happened rather than find the control gone. One line to change if tagged rows should be inert.

**Not done.** No hand smoke test — this adds two dialogs and wants a pass on staging. And stopping
tracking returns to the inventory list with no confirmation banner, because that list only reads
`?added=`; a one-line follow-up on a file outside this change.

---

### 2026-09-07 — Four screens stop offering what the API is right to refuse (docket C1-C4, task T-002)

Four places where a screen showed a control, a link or a sentence to somebody the server would then
turn away. Each was verified at a file and line before it was scheduled, and **none of them is a
permissions change** — every one is a screen offering something the API is correct to refuse, so the
button moves and the policy does not.

- **The work order on an ingredient request** rendered for anyone, and its language picker fired a
  request to `/work-orders/languages` on mount, so a cook took a 403 before pressing anything. The
  whole card is now gated on `mayIssue`, the boolean the same file already computed and already used
  correctly a few lines above. Gating the *card* rather than its buttons is the point: gating the
  buttons would have hidden the evidence of the 403 while leaving the 403 in place. The calm
  explanation a non-issuer already saw is untouched.
- **Today's "Working today" tile** linked every role to a staff schedule that refuses kitchen staff.
  The tile, its figure and its per-meal note stay — the count is fine for them to see — it simply is
  not a link for a reader who may not go there.
- **The `/my-shifts` empty state** told everyone to browse available shifts. Every write behind that
  screen needs `SIGN_UP_FOR_SHIFTS`, which only a volunteer has, so for a cook or a manager the
  screen is permanently empty and the instruction was unreachable. The copy is now conditional: a
  volunteer still reads the invitation, which is true and useful for them, and everyone else reads
  why the list is empty and where their own rostered work actually lives.
- **The staff-schedule empty state** linked a manager to `/staff`, which admits Temple Admins only —
  and that file's own documentation had claimed the link was already removed. The code now matches
  its documentation, for every role rather than only the manager.

One test file outside the fix disagreed with it: `ingredient-request-detail.test.tsx` asserted, with
a comment, that *"the work order is a reading act, so it is offered to whoever is looking"* — exactly
the belief the first item says is wrong. The assertion and its comment are inverted.

**Not done.** No hand smoke test; all four are user-facing and want one pass on staging as a cook and
as a manager.

---

### 2026-09-07 — A 401 from the filter chain says which 401 it is (docket D1-D3, task T-003)

Two error codes had been sitting in `ErrorCode.java` with finished copy and no thrower:
**`ACCOUNT_DISABLED` (`KMS-400019`)** and **`NO_ACCOUNT_AT_TEMPLE` (`KMS-400020`)**. Both are now
actually thrown. `AuthenticationFilter` returned silently at both sites and `SecurityConfiguration`
answered with a bare `HttpStatusEntryPoint` — a 401 with no body at all, the only response in the
product that carried no reference code for a person to quote back at us.
`GlobalExceptionHandler` could not have covered it: it is a `@RestControllerAdvice`, and a request
refused inside the filter chain never reaches `DispatcherServlet` for it to advise.

**The filter still does not throw, and that is the design.** Its class documentation has always been
explicit that a request without usable credentials passes through deliberately, because it may be
headed somewhere public — a provider webhook, an unsubscribe link opened out of an email, the temple
list a devotee is shown before they have an account anywhere. So the two refusing branches now
*record* the reason as a request attribute, and a new `AuthenticationFailureEntryPoint` turns that
into a body if — and only if — the request went on to reach something that required an identity. A
request that lands on a `permitAll` path never consults it and emits nothing. The entry point uses
the application's own `ObjectMapper` bean, so a body written from outside the dispatcher is
byte-for-byte what the dispatcher would have produced; `LoggingAccessDeniedHandler`'s hand-rolled
`{"error":"forbidden"}` is the precedent this deliberately does not follow.

**Why it matters on a screen.** `auth-context.tsx` treated any `/whoami` failure that was not a
network failure as "no account", so somebody whose access an administrator had just withdrawn was
told they belonged to no temple and offered the chance to sign up for one. That is untrue, and for a
person who has been serving at that temple for a year it is unkind. The web app now reads the code
rather than inferring from the absence of a network error, and a new `disabled` auth status is
handled in both places that branch on status — a status neither handled would have left that person
watching a spinner for ever.

**`SESSION_EXPIRED` (`KMS-400018`) is still deliberately thrown nowhere.** `TokenVerifier` states as
policy that a caller is never told *why* a token failed, and `FirebaseTokenVerifier` reads
`EXPIRED_ID_TOKEN` and discards it on purpose. Whether "expired" is the one disclosure safe enough to
carve out of that policy is Rajeev's to settle, not something to decide by quietly adding a code in
the filter. Neither verifier was opened, and a test now asserts that the bad-token branch's body
stays **empty**, so a later change cannot start explaining token failures without somebody noticing.
The honest consequence: an expired session still lands on the frontend's "no account" fallback.

**Not done.** No hand smoke test — the disabled-account screen needs a real Firebase sign-in, and
three concurrent builders could not share a dev server. Commandment 5 wants a pass on staging.

---

## Versioning convention

- Version bumps to a **locked** document require the user's explicit approval, per the Ten Commandments (never silently edit an approved decision).
- **Patch-level** (v1.0 → v1.1): typo/clarity fixes with no requirement or design change.
- **Minor** (v1.0 → v1.1... or v1.x → v1.y): scoped additions or clarifications that don't invalidate prior decisions.
- **Major** (v1.x → v2.0): a decision reversal, scope change, or anything that would require re-reviewing earlier-dependent work (e.g. Stage 3 tech choices made against SYSTEM_DESIGN v1.0).
- Every bump gets a snapshot in `docs/versions/` and an entry here.
