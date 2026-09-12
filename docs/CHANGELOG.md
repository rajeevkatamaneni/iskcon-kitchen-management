# Project Changelog — ISKCON Kitchen Management System

Tracks version history for the project's governing documents. Each locked version has an immutable snapshot in `docs/versions/`. The root-level copy (`REQUIREMENTS.md`, `SYSTEM_DESIGN.md`, etc.) always reflects the current approved version; edit it only alongside a new entry here and a new snapshot.

Per Commandment 8, no document is edited post-lock without the user's explicit sign-off — a lock is a decision, not a formatting convenience.

---

## DESIGN_SYSTEM.md

### 2026-09-08 — Six missing snapshots recovered from git history, and why there is no v1.2 (decision D-22)

**Ruled by Rajeev, 2026-09-08:** *"reconstruct the missing snapshots from git history."*

`docs/versions/` held snapshots for `REQUIREMENTS`, `SYSTEM_DESIGN` and `TECH_STACK` and **none at all
for `DESIGN_SYSTEM`**, against six declared locked versions. The convention stated in this file's own
preamble — an immutable snapshot per locked version — had never once been applied to that document,
so its locked status was nominal: there was nothing to compare a change against.

**Six snapshots were recovered from named commits** — `DESIGN_SYSTEM_v1.0.md` (`4a83a19`),
`v1.1` (`28cb5af`), `v1.3` (`e6fd51c`), `v1.4` (`50ec057`), `v1.5` (`cbef632`) and `v1.6`
(`ad509f7`). They were extracted verbatim, not written: the document declares its own version in its
first Status line, so each was taken from the commit at which the file itself said it was that
version, and **each self-verifies**. `v1.6` is byte-identical to the current root copy, which tests
the method rather than only the output.

**There is no v1.2, because it never existed as a committed state, and it must not be invented.** The
file's history runs v1.1 (10 August) straight to v1.3 (20 August) — the two edits were committed
together, or v1.2 lived only as a changelog entry. This changelog describes what v1.2 *did*: it added
the `info` family and moved Ekadasi onto it. A snapshot assembled from that description would be a
reconstruction of a document nobody ever approved in that form, which is fabrication wearing the
costume of a record, and worse than the gap — a gap is visibly a gap, while a plausible file is not.
The sentence is here so that the next reader who meets the hole knows it is deliberate and does not
go looking for a lost file.

**v1.6's sign-off, at last.** v1.6 had been sitting above marked *"PENDING RAJEEV'S SIGN-OFF"*, which
would have stacked any later amendment on top of a version he had never approved. He signed it off on
2026-09-08 — *"sign off v1.6"* — so the marker comes off (decision **D-20**).

**The root `DESIGN_SYSTEM.md` is not edited by this entry.** Its one sattvic mention is an
illustrative cell in the `danger` colour row, and it is left for whatever settles that document's own
next version.

### v1.6 — 2026-08-30 — What lifts under the pointer, and what only changes tone (approved by Rajeev 2026-09-08)

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

### v1.6 — 2026-09-10 — Recurring donations leave Phase 1 for the Phase 2 Backlog (approved by Rajeev, task T-113)

**The sign-off first, as v1.5 established.** Rajeev, 2026-09-10: *"needs to be researched properly
and built. we will do it later as an engagement once the app is live … clear out the phase 1
documents and jira stories and uat stories about this feature. throw it in the phase two bucket and
close it for now."* That is the explicit sign-off Commandment 8 requires before a locked document
changes, and it authorises the recurring-donation passages of `REQUIREMENTS.md`, `SYSTEM_DESIGN.md`
and `TECH_STACK.md` and **nothing else in any of them**.

**§3.4 — the Donations line stops promising a frequency.** It read *"one-time or recurring, with
donor-selected frequency"*, which was a live promise of a feature release 1 will not have. It now
says what Phase 1 does — one-time gifts, given by a signed-in devotee under their own name — and a
second bullet records the deferral in the shape the section already uses for the 80G filing workflow
and the FSSAI/BHOG food-safety log: what is deferred, and why. **Why:** recurring giving is not a
form with a frequency dropdown on it. It is a mandate product — UPI Autopay or eNACH registration,
cycle charges arriving by webhook for months after the donor has closed the page, failed-cycle
retries, pause and cancellation that must reach the gateway as well as our own record — and half of
it built to a release date is worse than none of it. A devotee who wants to give every month can
give every month, one gift at a time, and nothing about that is broken by the deferral.

**§4 Phase 2 Backlog — a row added, not a new section.** The backlog table already existed and
already held the deferrals this one now joins; recurring donations get a row in it saying what the
Phase 2 engagement has to build and that one-time giving is unaffected. No new heading was invented
for it.

**What is not edited, and why.** Section 5's *"Resolved this round"* line and §7.2's 80G findings
mention donations throughout without promising recurring giving, and §6 *Explicitly Out of Scope*
lists other things entirely. The rule from v1.5 applies unchanged: **a document's live promises are
amended, and its records of past decisions are left alone.** Nothing in this document recorded a past
round resolving the recurring question, so there was no historical passage to protect here — that
distinction did the work in `TECH_STACK.md` instead, below.

**Outside the locked documents**, under the same sign-off: **E7-S3 is withdrawn** and **UAT-056 is
withdrawn**, both marked in place and left readable rather than deleted, because a story records what
was decided and a UAT script records what was tested. `UAT-059` (the ledger) is **amended, not
withdrawn** — it is a Phase 1 feature that loses one of its four gift types. Traceability gap **G4**
closes by withdrawal. The code removals are separate tasks (T-111 backend, T-112 frontend).

- Snapshot: `docs/versions/REQUIREMENTS_v1.6.md`

### v1.5 — 2026-09-08 — Sattvic enforcement is withdrawn, and Ekadashi is the only dietary rule left (approved by Rajeev)

**The sign-off first, because a locked-document edit whose authorisation is not on the record is
later indistinguishable from one that skipped the rule.** Rajeev, 2026-09-08: *"Approved, mark them
withdrawn and bump the locked docs."* Recorded as decision **D-20**. It authorises the sattvic
passages of `REQUIREMENTS.md` and `SYSTEM_DESIGN.md` and **nothing else in either file**, and it is
the explicit sign-off Commandment 8 requires before a locked document changes.

**§3.1 Sattvic Ingredient Enforcement — withdrawn.** Decision **D-18** deleted the sattvic-prohibited
flag from the product: the column, the admin toggle, the API, the hard block on recipe save and
purchase-order submission, the admin override, and the audit action it wrote. The section no longer
promises any of it. **Ekadashi is now the only dietary restriction the product enforces**, and it is
a *warning* at planning time rather than a block, because a temple does legitimately cook grains for
non-fasting visitors on a fast day. The flag is set by hand by a Temple Admin, under
`MANAGE_DIETARY_POLICY` — renamed from `MANAGE_SATTVIC_POLICY` under **D-21**, since it now guards a
different rule from the one it was named for.

The reason the flag went is worth keeping: it only ever guarded rows that existed **because of it**.
Provisioning inserted onion, garlic, mushroom and egg into every temple's catalogue solely so that it
could tick them forbidden. That seed of eleven ingredients went in the same ruling, so **a new temple
now starts with an empty catalogue**, and the Recipes page carries a warning box saying that imported
ingredients arrive unflagged for Ekadashi. Two consequences were **accepted rather than missed**: an
imported recipe naming garlic now saves cleanly, and a new temple enforces nothing on a fast day
until an admin flags things by hand. Seven flagged staples among sixty unflagged ones looks like
knowledge and is not.

**§5's "Resolved this round" line is annotated and keeps every word — deliberately, and the rule is
worth stating once because it governed the whole amendment: a document's live promises are amended,
and its records of past decisions are annotated.** §3.1 promised what the product does, so
subtracting from it is the fix. §5 records what a past round of open questions resolved; rewriting it
would falsify exactly the history that "withdraw, do not delete" exists to preserve. Subtracting from
a promise is a correction; subtracting from a record is a second, quieter kind of deletion. The
distinction is written into §3.1 itself, so a reader who notices the inconsistency finds the reason
rather than assuming carelessness.

**Carried into the stories and the UAT pack in the same commit** (task T-055): **E2-S4**, **UAT-014**
and **UAT-018** are marked **withdrawn with their text left in place** — a story is a record of what
was decided and a UAT script a record of what was tested, so deleting them would lose the fact that
this temple once had the rule and chose to drop it, while marking them stops somebody running a
script for a feature removed on purpose. Five further story files and nine further UAT files had only
their sattvic assertions amended. One gap opened and is recorded as **G12** in
`docs/uat/TRACEABILITY.md`: UAT-014 also covered the admin-only rule on the dietary flag, and with it
withdrawn nothing now tests who may set the surviving **Ekadashi** flag.

**Not changed, deliberately.** `docs/DESIGN_SYSTEM.md` — its one sattvic mention is an illustrative
table cell, and that document waits on its own history (see the DESIGN_SYSTEM.md entry of the
same date).
Earlier `docs/versions/` snapshots, applied Flyway migrations, the build briefs, `docs/reviews/` and
`docs/stories/github-import/` still mention sattvic and are left alone: they record what was true
when they were written. Audit rows already written are untouched — an old `RECIPE_SATTVIC_OVERRIDDEN`
row is a true record of something that happened and stays readable in the temple audit log.

Minor (v1.x): no reversal of an earlier decision beyond D-18 itself, which is already recorded.
Snapshot: `docs/versions/REQUIREMENTS_v1.5.md`

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

### v1.5 — 2026-09-10 — `recurring_plans` comes off the entity list and §6 stops promising mandates (approved by Rajeev, task T-113)

**Following REQUIREMENTS.md v1.6 and under the same sign-off** — Rajeev, 2026-09-10, quoted in full
in that entry — which authorises the recurring-donation passages of this document and nothing else in
it.

**§5 Key entity groups.** The donations group read `donations/donors/wishlist_items/recurring_plans`.
`recurring_plans` is dropped from it, because the table is being dropped from the database: the
feature it existed for is Phase 2 now, and a schema list that names a table nobody can query sends
the next person designing against it looking for something that is not there. This is a live
description of the schema, so it is amended rather than annotated, and the amendment note under it
says what went and when. The other three tables are untouched — `donations` still carries every
one-time gift, wish-list sponsorship and in-kind intake — and the name is left free for Phase 2 to
take back.

**§6 Payment gateway.** It said *"Recurring donations use the gateway's mandate/subscription
primitives (UPI Autopay/e-mandate)"* — present tense, a live statement about what the integration
does. It now says release 1 takes one-time payments only, that the mandate primitives went to Phase 2
with the feature, and that no subscription-cycle event reaches the webhook handler, which serves
one-time capture and wish-list checkout. The primitives are still part of why the gateway was chosen,
and that choice is not reopened.

**§13 is deliberately not edited.** Item 4 of *Open Items Carried to Stage 3* still reads *"Payment
gateway (UPI Autopay/e-mandate support, wish-list checkout, fees)"*. That is a **record of what Stage
2 handed to Stage 3** — a question that was asked in August 2026 and answered by `TECH_STACK.md` §5 —
not a promise about the product. Rewriting it to match a later decision would delete the history the
withdrawal exists to preserve, which is the same distinction v1.4 drew over the audit-log list.

- Snapshot: `docs/versions/SYSTEM_DESIGN_v1.5.md`

### v1.4 — 2026-09-08 — The sattvic override comes off the audit-log list (approved by Rajeev)

**Following REQUIREMENTS.md v1.5 and under the same sign-off** — Rajeev, 2026-09-08: *"Approved, mark
them withdrawn and bump the locked docs"*, recorded as decision **D-20**, which authorises the
sattvic passages of this document and nothing else in it.

**One change, in §5.** The audit-log bullet listed the acts the shared kernel writes to
`audit_events`: *financial records, inventory adjustments, **sattvic overrides**, calendar date
overrides, role changes.* The third is gone, because the act no longer exists — decision **D-18**
deleted the sattvic-prohibited flag, its hard block and its admin override, and with them the
`RECIPE_SATTVIC_OVERRIDDEN` action. **Ekadashi is now the only dietary restriction the product
enforces**, and it warns at planning time rather than blocking a save, so it produces no override act
to record.

**Nothing else about the audit log moves**, and one thing is protected explicitly: the table is
append-only and **the rows already written are untouched**. An old `RECIPE_SATTVIC_OVERRIDDEN` row is
a true record of something that happened and stays readable in the temple audit log. That is the same
distinction the requirements amendment turns on — the *list* is a live description of what the kernel
writes and was cut; the *rows* are records and were not. On an append-only table it is the difference
between an amendment and a deletion.

Minor (v1.x): no reversal of an earlier decision and no re-review of dependent work.
Snapshot: `docs/versions/SYSTEM_DESIGN_v1.4.md`

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

### v1.1 — 2026-09-10 — §5 records that the requirement Razorpay was chosen for is now Phase 2; the decision stands and the evaluation is not edited (approved by Rajeev, task T-113)

**Under the same sign-off as REQUIREMENTS.md v1.6** — Rajeev, 2026-09-10, quoted in full in that
entry. **This document's first amendment since it locked on 2026-08-03**, and the most delicate of
the three, because §5 is where a live dependency and a historical record sit in the same table.

**The problem.** §5 chose Razorpay over Cashfree, and the Razorpay row justifies the choice with
*"both needed for donor-chosen-frequency recurring donations per REQUIREMENTS.md"*, while the
Cashfree row records it as *"Rejected, narrowly"* on cheaper fees and a stronger payouts product,
losing on recurring-billing tooling alone. As of 2026-09-10, `REQUIREMENTS.md` no longer asks for
donor-chosen frequency in Phase 1 — so left untouched, the section would tell the next reader that
release 1 depends on UPI Autopay and eNACH, which it does not.

**What was decided.** The provider choice **does not change**. Recurring giving is deferred, not
cancelled, so the need the choice was made against has moved in time rather than disappeared, and
re-opening a gateway comparison for a feature nobody is building this release would be churn for its
own sake. Razorpay's Phase 1 half of the case stands on its own without the recurring half:
provider-hosted UPI-first checkout, signed webhooks, documentation quality, and adoption among Indian
nonprofits.

**What was written, and where.** A blockquote directly under the §5 heading — before the table, so a
reader cannot reach the rows without it — stating that the requirement moved to Phase 2, that release
1 registers no mandate and handles no cycle charge, that Razorpay remains the pick and rests on the
future need, and that **the two rows below are to be read as the Stage 3 record of a 2026-08-03
evaluation, not as a live Phase 1 dependency**.

**The rows themselves are left exactly as written, and that is the point.** They record what was
evaluated and decided on 2026-08-03, when donor-chosen-frequency recurring giving genuinely *was* a
Phase 1 requirement. Editing them to agree with a later decision would falsify the evaluation rather
than update it — most of all the Cashfree row, whose narrow rejection turned on precisely the
requirement that has now moved, and whose own advice to re-quote both providers at implementation
time is now advice for whoever starts the Phase 2 engagement. **A live promise is amended; a record
of a decision is annotated.**

**Not edited:** §2's iText row and §6's Meta Cloud API row both say *"a recurring cost"* about
licensing and platform fees. They have nothing to do with donations and were left alone.

- Snapshot: `docs/versions/TECH_STACK_v1.1.md`

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

Not governing documents, but recorded here because these items are the ones CI evidence alone was taken as proof of — the first two were E1-S1 acceptance criteria marked done on a green run, and the entries since are about the runs themselves.

### 2026-09-08 — Two tests stop reading a picker before it has been filled, and the release they were blocking can ship (task T-075)

**A release sat undeployed for a day because of a race in a test, and nothing was wrong with the
product.** CI run `34249994747` on `36d62b3` — waves 7 and 7b, the invoice, donation and staff
corrections — failed the frontend job on
`frontend/__tests__/ingredient-request-new.test.tsx:124` with *"Unable to find an element with the
text: Prasadam kitchen"*. The release agent stopped rather than re-running, which was the right call
and is why the cause was found instead of papered over: the test file and the screen it exercises
are **byte-identical** to the last green commit, and the only variable that moved was load — CI ran
the suite in 71.36s against 13.26s locally.

**The mechanism, taken from the failure's own DOM dump rather than inferred.**
`IngredientRequestForm` renders its form **unconditionally** — there is no loading gate anywhere in
it — and fills the kitchen picker from `(kitchens.data ?? []).filter(...)`, where `kitchens` is a
`useAuthedQuery` seeded `null` and filled in an effect. So the `<select>` exists on the very first
paint carrying nothing but `Choose…`, `await findByLabelText` resolves on exactly that paint, and
the **synchronous** `within(picker).getByText(...)` that follows it has no retry. On an idle machine
the microtask wins; on a loaded one it does not. The dump showed the empty picker precisely.

**The shape was not the diagnostic, and that is the transferable part.** A sweep for the pattern — a
synchronous `within(...)` after a `findBy` — turned up twelve candidate sites, and **ten of them are
safe**: `occasions`, `settings-payments` and `staff-ban` are byte-unmodified, because in each the
container is either static markup, or behind a loading gate on the *same* query that fills it, or an
element that cannot exist without its own text (`ErrorNotice` is one `<div role="alert">` carrying
message, action and code from one `ApiError`). The question that actually separates them is whether
the container and its contents arrive from **different async sources**. Churning the ten would have
been harm, not thoroughness.

**A second fault the sweep could not see, same cause and a worse symptom.**
`app/orders/new/lines/page.tsx` runs two queries and gates its form on `loadingVendors` **only**,
filling the ingredient picker from a second, ungated `allIngredients`. Firing a change at a
`<select>` value no `<option>` carries **neither throws nor warns** — the DOM declines it, the value
stays empty, "Add line" stays disabled, and the run dies a dozen lines later on
`getByLabelText("Quantity of Rice")`, a field that was never created, pointing at a screen with
nothing wrong with it. The control caught this exactly: **the fault at line 313 was reported as a
failure at line 315.** So an assertion racing names the thing it could not find; an *action* racing
puts the blame on an innocent later line. Both sites in `manual-purchase-order.test.tsx` are fixed
with a waiting helper rather than a `waitFor`, which would re-fire the event on every poll.

**The absence assertion was made non-vacuous mechanically rather than by argument.** The test's
point is that the planner-fed kitchen is *absent* from the picker, and an absence read off an
unloaded picker passes for the wrong reason — wrapping it in `waitFor` does not help, because
`waitFor` succeeds on its first tick and the first tick is the one where nothing has arrived. The
wait now asserts the option **count**, which is the one thing that separates all three states:
unloaded carries 1, loaded and filtered carries 2, loaded with a broken filter carries 3. Neither
the clock nor a broken filter can satisfy it. **This was not theoretical**: the fixed test with its
positive assertions stripped went green in 23ms against a picker that had never loaded.

**Evidence, and why a green run was explicitly not accepted as any.** Green is what the flaky state
produced most of the time, so neither a passing CI run nor a re-run would have proved anything. The
control was **provoked** instead — the mocks that fill each picker resolve on a 50ms timeout so the
race is always lost, applied identically to both halves, with "before" taken verbatim from
`git show HEAD:`. Unfixed: `1 failed | 15 passed` with the DOM dump showing the empty `<select>`,
which is **CI's exact failure reproduced on demand**, and `2 failed | 11 passed` on the second file.
Fixed, same delay: 16/16 and 13/13. The delay does not survive into the shipped files.

**No product code changed, no migration, no error code, no `api.ts`, and the test count did not
move** — 106 files and 1161 tests before and after, because this reshapes assertions inside existing
tests rather than adding any. There is nothing to press, so there is no hand smoke-test and none was
warranted.

**What is not done, and it is filed as T-076 rather than smuggled in here.** `frontend/package.json`
defines a `lint` script and there is **no ESLint config and no ESLint dependency anywhere in
`frontend/`**, so the script cannot run and CI has never run it. `eslint-plugin-testing-library`
catches this exact shape at author time across all 106 test files. It is ranked above another hand
sweep for a reason one wave old: the hand sweep ran ten false positives in twelve **and missed the
two sites that were actually racy**. Introducing a linter to a codebase with none will surface a
backlog and touches CI, so it is a task and needs Rajeev's word.

**A latent fragility was reported and deliberately not fixed (T-077).**
`frontend/app/settings/page.tsx:1391` seeds `useState` from a prop, so if `LanguageSection` were ever
mounted before `locale` arrived the picker would read English **permanently** and no test would
notice. It is safe today only because of a page-wide `if (!settings)` gate — safe by an accident of a
neighbour rather than by its own construction. A test that is wrong about timing is not evidence that
the product is, and this test is not wrong about timing today.

### 2026-09-08 — The test JVM is given a heap ceiling it was previously only inheriting, and a build that runs out of one says so (task T-062)

**Three CI runs across two releases went red without a broken assertion in any of them**, each
reporting a crowd of `Failed to load ApplicationContext` errors against whichever classes happened to
run last, and each green on a re-run of the identical commit. One of them, `22e820a`, changed a single
markdown file and nothing else — which settles what it was.

**The diagnosis was measured before anything was changed, and it is the deliverable.** Gradle hands a
test worker **512 MB** when the build says nothing, and this build said nothing. A class histogram
taken from the live worker three quarters of the way through a full run found **81 live application
contexts** — with 81 HikariDataSources, HikariPools, SessionFactories and Tomcat servers beside them —
of which only 32 were still running. **The other 49 were closed and still reachable.** A run creates
about 106 contexts because nearly every integration class declares its own nested
`StubVerifierConfiguration` and imports it, and an imported configuration is part of the TestContext
cache key. The histogram totalled 886 MB live, compacting to a **446 MB floor** under pressure as
soft-referenced caches are discarded. Gradle's 512 MB default sat directly on that floor, so the suite
passed only by running permanently in emergency collection, and any variation at all tipped it over.

**The failure was then reproduced on demand on a completely unmodified tree**, held 64 MB below the old
ceiling at 448 MB: 23 `OutOfMemoryError`s, the worker dead after 1260 of 1830 tests, a summary reading
`Failed: 0` beside `Result: FAILURE`, and 8m10s against 3m17s. That is CI's exact signature, on command.

**`maxHeapSize = "2g"`** — about twice the measured live set and four and a half times the floor, on a
16 GB runner whose only other tenants are Gradle and one Postgres container. It is deliberately **not**
larger. A much bigger ceiling would hide the retention rather than pay for it, and the retention is the
actual defect; the build file says so in its own comment, at length, so that the next person to meet
this reads a measurement rather than a guess. **This buys room. It repairs nothing**, and the two
changes that would repair it — one shared stub-verifier configuration in place of 88 private ones, and
finding what holds a closed context reachable — are filed separately and deliberately not squeezed in
here, because granting them mid-task would have moved the number being measured underneath the
measurement.

**Every run now prints the ceiling it was given, passing or failing**, and a run that exhausts it says
in plain words that this is not a code failure, what the suite actually needs, and *do not simply run it
again*. The absence of that line is what cost three investigations: the log said a context had failed to
load and said nothing whatever about how much heap it had been given to load it into. Both faces of the
condition are named — the OutOfMemoryError buried as the root cause of a context-load failure, and the
worker dying outright to leave `Failed: 0` beside `Result: FAILURE` — because a suite that reddens for a
reason unrelated to the change under test teaches the next reader to re-run rather than read, and the
genuine failure after that gets the same glance and the same dismissal.

**What is not done.** `forkEvery` and a smaller context cache were both considered and both rejected on
the measurement: the cache is already bounded at 32 and already evicting, and **eviction frees nothing**
when what is evicted stays reachable. The new banner has been seen firing locally at a deliberately tiny
ceiling and the ceiling line has been seen on a passing run; the first real test on CI is this release's
own backend job.

### 2026-09-08 — The seventh drifted variable is adopted, `deploy.sh` stops setting environment at all, and the geocoding environment moves to Google (task T-057)

T-052 closed six of the seven variables `terraform apply` was silently deleting and said plainly that
the runbook was **still** not safe to follow, because of a seventh: **`API_BASE_URL`**. This closes it,
and the deliberate order it was done in is the point of the entry.

**Part A — adoption.** `API_BASE_URL` becomes `var.api_base_url`, declared in `variables.tf` and
referenced on the api's Cloud Run block, mirroring `cors_allowed_origins` immediately above it. It
could not be *computed*: Terraform cannot reference a Cloud Run service from inside that service's own
definition, and it is deliberately **not** constructed from the project number and region either —
Cloud Run answers on two URL forms per service, they are different strings, and the one the running
service reports is the hash form. Building the other one here would have been a change to a running
configuration wearing the clothes of a record of it. So the value is **copied from what the deployed
service reports**, which is what makes this adoption rather than a change.

`infra/deploy.sh` now sets **no environment variables at all**. It had set this one with
`--update-env-vars` because Terraform could not self-reference — which is exactly what made the
variable invisible to Terraform, so `apply` deleted it on every run and the next deploy quietly put it
back. **Terraform owns the environment; the deploy script owns only which image is live.**

**Part B — a deliberate change, kept separate so it could not be read as drift.** `GEOCODING_PROVIDER`
goes `nominatim` → `google` and `NOMINATIM_USER_AGENT` is replaced by `GEOCODING_API_KEY` on the
existing `kms-staging-maps-api-key` secret, on **both** the api and the worker. This is what stops
D-19's deletion of the Nominatim class from taking the services down — see the Application entry
below.

**The evidence is three plans in an order that means something, and not one of them is an `apply`.** A
baseline plan on untouched `HEAD` reproduced the defect on demand (`- name = "API_BASE_URL" -> null`).
Part A's plan then carried **zero `env {` blocks on any service**. Part B's proposed **exactly the six
named geocoding deltas and nothing else**, with `API_BASE_URL` absent from it — which is what proves
Part A still holds underneath Part B. A green `apply` would have looked identical whether the file was
right or wrong; a plan that proposes exactly what you meant and nothing more is a different statement.

**Nothing was applied.** `terraform apply` has still not been run, and this task does not claim it is
safe by running it — it claims it by what `plan` declines to propose. **One caveat carried out of the
task honestly and filed to T-058: no plan in this repo is ever empty.** All three Cloud Run services
carry a perpetual `scaling { min_instance_count = 0 -> null }` diff, present in the untouched
baseline, and `main.tf`'s comment claiming that was resolved is wrong as well — the worker declares
`min_instance_count = 1` and still shows it. For a project whose repair proof is *"the tool proposes
nothing"*, that matters: the usable criterion is **no environment-variable diff**, which is what was
measured, not "an empty plan", which is unreachable here. `terraform fmt -check` is also not clean,
and both hunks were **proved to pre-date the task** rather than quietly reformatted.

**One decision declared rather than hidden:** `var.api_base_url` was given `default = ""` rather than
being required like `cors_allowed_origins`, because `terraform.tfvars.example` was outside the task's
contract and a required variable would break a fresh environment at plan time with no way to repair
the example. That is behaviour-identical to what `deploy.sh` did — its `${API_URL:+…}` set nothing on
a first deploy, and the application reads `${API_BASE_URL:}`. If the example file is repaired under
T-058, **required is the stricter shape** and should be taken.

### 2026-09-08 — Terraform learns about six environment variables the running services already carry, so `terraform apply` stops silently deleting them (task T-052)

Found sideways while investigating D-19. `infra/environment/main.tf` did not know about the Maps
configuration that three shipped features depend on, because it had been set outside Terraform — so
**step 2 of this repo's own deploy runbook was a landmine**. A baseline `plan` on the previous commit
proposed **13 environment-variable deletions**, seven on the api service and six on the worker, and
running it would have stripped Places, Static Maps and Routes out of the live configuration without
saying a word about the features that would stop working.

**Six variables are now described where they are actually set**, on **both** Cloud Run blocks, api
and worker. Three are literals — `PLACES_PROVIDER=google`, `STATIC_MAP_PROVIDER=google`,
`TRAVEL_TIME_PROVIDER=google-routes`. Three are the API keys, and they arrive through
`value_source.secret_key_ref` on `kms-${var.environment}-maps-api-key:latest` — `PLACES_API_KEY`,
`STATIC_MAP_API_KEY`, `ROUTES_API_KEY`. **No key material is in the tree**: `grep -rn AIza infra/
docs/DEPLOYMENT.md` exits 1. Which of the six took which shape is recorded here rather than left in a
diff, because inlining a key value is the obvious shortcut when a plan refuses to go quiet, and a
plain-text API key in a checked-in `.tf` would be a worse outcome than the drift it repaired.

**The proof is the diff the tool declines to propose.** The acceptance criterion was deliberately not
"a green `apply`" — an apply looks identical whether the file was right or wrong. It was a **no-op
`plan`**, which is what demonstrates the file describes what is actually running. After the change
the worker's `containers` diff is **gone entirely**, all 29 environment variables matching, and the
six named variables are gone from the api's diff. Generalising it past Terraform: whenever the work
is reconciling a description with a reality, the evidence is what the tool stops asking for.

**`terraform apply` is still not safe to run, and that must not be lost inside a green report.** A
**seventh** drifted variable was found that neither the survey nor the brief had: **`API_BASE_URL` on
the api service**, which `apply` still deletes. It is different in kind, which is why it was right not
to widen into it — `infra/deploy.sh:118` sets it deliberately, because Terraform cannot self-reference
a service's own `.uri`, and the live value is the hash-form URL, so writing the constructible
project-number URL would be a **change to running configuration rather than a no-op**. It cannot be
adopted the way the six were, and adoption is what this task was. Filed as **T-057**, unscheduled.
**Six of seven variables are defused; the runbook is not yet safe to follow.**

**Two judgements deliberately left for a human.** The secret is wired as a `data` source rather than a
`resource`, against the repo's own `smtp_password` precedent — because the secret already exists
outside state, so a resource block makes `plan` propose *creating* it and the only route back to a
no-op is `terraform import`, a write to shared remote state during a live wave. And a residual
`- scaling { manual_instance_count = 0 -> null }` on all three services is a provider artefact, not
the template scaling this config sets: `terraform state show` shows two distinct blocks, the template
one matches exactly, and it is present on `frontend`, which this task never touches, and in the
baseline. Reported rather than chased.

**Three factual errors in `docs/DEPLOYMENT.md`, now fixed.** It told a human to set
`TRAVEL_TIME_PROVIDER` by hand *"on the API service (not the worker)"* — Terraform now sets it, on
both. It claimed `ROUTES_API_KEY` *"is left unset"* and that the service uses ADC — it reads the
secret. And it never mentioned Places or Static Maps at all.

**For Rajeev, found sideways and needing a number: Places and Static Maps have no daily quota.** Both
are enabled on staging and neither is capped; only Routes and Geocoding are. **Places fires per
keystroke**, so its ceiling cannot be derived from any order or delivery count, and on this platform
quotas cap spend while budgets only alert. `DEPLOYMENT.md` records the quota as open rather than
inventing a figure. This does not contradict D-19 — *"paying for a quality service should never be a
consideration"* rules out cost as a reason to choose the weaker service, and says nothing about
leaving an uncapped meter on a keystroke-rate API.

**Nothing was applied and nothing on staging changed.** The value of this entry is entirely
preventive.

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

Not governing documents. Recorded here because each entry closes a finding — from the UAT docket of
2026-09-06 (`docs/work/INTAKE.md`), from a ruling of Rajeev's, or, from wave 4a onward, from driving
the deployed site — and because a reader asking "when did that screen start doing that" should not
have to read a commit log to find out. Every entry says plainly what is **not** done.

**What "done" means here changed twice, both on 2026-09-07, and the second change is the bigger one.**
These entries used to say uniformly that nothing had been seen working by Rajeev. His first amendment
made verification on staging the session's to do, and waves 1, 2 and 3 were driven as the real roles;
those results are in `docs/work/DISPATCH.md`. His second amendment extends the same reasoning to
`docs/OUTSTANDING_BUILD_LIST.md`, which until then only he could move an item out of: **verification
there is now two passes.** A session does the first, marks the item `DONE — verified <date>` with a
line saying what it actually pressed, and **leaves the item's block in the file**; Rajeev tests after
it and reopens anything missed. So an item marked done in that file means *a session verified it*, not
that Rajeev accepted it, and the file does not go until he says it goes. Where an entry below says a
thing has not been seen working, take it at its word rather than assuming a later wave settled it.

### 2026-09-12 — A shift can run past midnight (task T-146)

**Posting a shift from 20:00 to 02:00 used to fail with `KMS-500001`**, *"something went wrong at our
end, try again in a moment"* — advice that could never work, for a temple whose largest festival is at
midnight. Found while seeding Janmashtami. V34's `CHECK (end_time > start_time)` refused the insert and
nothing caught the violation, so the database's refusal surfaced as our fault.

**What a shift's date means is now written down once:** `shift_date` is the day the shift starts, and
an end time at or before the start time means the next day. `V127` relaxes the constraint to
`end_time <> start_time` and adds `shift_ends_at(date, start, end)` so SQL works out the end instant
in one place; `ShiftWindow.java` does the same in Java for the reminder scheduler and service.

**Equal times are still refused, and now by name.** 20:00 to 20:00 is either no shift or a 24-hour one
and nothing can say which. Posting or editing to equal times answers `KMS-400001` naming the end-time
field, and the shift form says so under the End box before the button is pressed. No new error code.

**The double-booking warning compares real start and end instants**, not clock times on one date, so a
volunteer holding 23:00–01:00 is warned about a 20:00–02:00 shift that night, and two shifts meeting
at 02:00 are back to back rather than a clash.

**Every shift screen prints *"20:00–02:00 (next day)"*** — the coordinator's shift list, a shift's
roster where attendance is taken, the volunteer's open *Shifts* and *My shifts* — and the form says the
shift ends the next day as the times are entered.

Negative controls for all four halves are in `docs/work/proof/T-146.md`. **Not done:** not yet driven
in a browser as a coordinator or a volunteer.

---

### 2026-09-10 — The shopping list stops being stored, a ladder can say it never needs servicing, and the stock ledger is summed once instead of three times (wave 21; tasks T-132, T-140, T-139, T-120)

**The largest single change this project has shipped, and two migrations in one push.** Four tasks
built by four different agents over several hours, none of which had been run against the other
three. The merged tree was archived out of `HEAD` into a clean directory and the whole suite run
there before anything was pushed.

**There was never one door onto the shopping list's write. There were two.** Rajeev asked why the
screen needed a *"Generate shopping list"* button when the list could populate itself on load. The
button was the visible door; `ShoppingListRegenerateJob`, on a 04:30 IST cron trigger, was the other,
and both did the same thing — upsert a suggested line per ingredient, then delete every unedited line
no longer suggested. The list already populated itself once a night. The honest answer to *"why the
button"* is that neither should exist.

`shopping_list_lines` was a cache pretending to be a table, stale between writes with nothing on the
screen saying so: two people opening the list at the same moment could be shown different answers
depending on which of them had last pressed the button. The suggestions are now computed on every
read — meal-plan shortfall, stock below its reorder level, short deliveries, and the live purchase
orders that already cover an ingredient — and never written. What is left in the table is only what a
person decided: an edited quantity, an untick, a line typed in by hand. `V121` drops the seven
computed columns, adds `hand_added`, makes `suggested_qty` nullable, and runs per tenant under RLS
because the migration role holds no `BYPASSRLS` and a bare `UPDATE` would match nothing through the
policy's `NULLIF` and report success having changed nothing anywhere.

**The job lives in the database as well as in Java**, so `V121` deletes its Quartz rows too. Without
that, 04:30 arrives, the stored trigger fires, fails to load a class no longer on the worker's
classpath, and goes to `ERROR` — a shape of failure no test in this repository would have caught.

**An untick persists, and the cost is named rather than designed around.** One made in September will
suppress a January shortfall, and between those dates the line is not on the screen for anybody to
notice. Expiring it would need either a write on a read — the single thing this change exists to stop
— or an invented validity window. The screen says how long ago a line was unticked instead, so a
stale decision announces itself when the ingredient is needed again.

**The page load used to sum the whole stock ledger three times, once per demand stream.** It sums it
once now and hands the map down: 445 ms to 126 ms median against a five-year fixture.
`ShoppingListIT.theLedgerIsSummedOncePerPageLoad` counts the statements one request issues and runs in
the ordinary suite, so a later change cannot quietly put the other two scans back. **The "nine reads"
figure quoted in the performance proof was wrong** — it was three, inflated threefold by
`pg_stat_user_tables` counters lagging the transaction that produced them.

That five-year fixture is itself new: a temple generated at staging's counts with every parameter
overridable from the environment. **It does not join the default suite.** Both classes are tagged
`perf` and gated on `KMS_PERF`, verified with the variable unset, where they skip without starting a
container.

**And a ladder is no longer a boiler nobody got round to scheduling.** A null servicing interval
carried two facts at once, and only one of them was a problem worth chasing. `V122` adds
`never_needs_servicing` with a `CHECK` forbidding it alongside an interval, so *"this never needs
servicing, and it needs servicing every six months"* is unrepresentable rather than merely refused;
the two columns carry three states between them and none of them is a null with two meanings. A
flagged item reads *"It does not need servicing"*, leaves the overdue banner, the *Overdue* filter and
Today's overdue count, and it is the second tier — owned, not serviced — that an earlier task was
asked for and shipped without.

**No existing row was flagged by the migration.** Nothing stored anywhere can tell a ladder from an
unscheduled boiler — that is the defect being fixed — and guessing from the name would write a
permanent claim about the temple's own equipment on the strength of a string match.

**Not done.** Neither screen has been seen working by Rajeev. On the shopping list: open it and
confirm it populates with no button, tick and untick a line, and check that a line an open order
already covers has left the list. On the equipment register: open a ladder, press *Change the
schedule*, tick *This never needs servicing*, and confirm the interval box empties and greys; then
flag a machine that is genuinely overdue and confirm it leaves the red banner and Today's count.
**The tick box is not on the registration form** — it lives on the item's own page, so declaring sixty
stools un-serviced is sixty visits. That form was outside the task's contract and the follow-up is
small. One more thing the work found and deliberately left alone: a single shopping-list page load
issues **404 SQL statements** and asks for 360 recipes to do it, because the whole pass runs twice.
That is queued as its own task and is not fixed here.

---

### 2026-09-10 — A field error is written for a person now, and the vendor no-show box needs an order that was actually sent (wave 20; tasks T-098, T-129)

**Two tasks built in separate trees and, for the first time in this project, verified only after they
were put together.** T-098 touched 79 request bodies across the backend and T-129 touched five files
in `purchaseorder/`, `error/` and the frontend; neither had ever been compiled against the other. The
merged tree was archived out of `HEAD` and run whole before anything was pushed, and the numbers
below are that run's, not either builder's.

#### T-098 — a field error is part of the product's voice

Posting a vendor with a blank name and a bad phone returned, live on staging, two messages in one
JSON body: *"Include the country code, for example +919876543210."* and *"must not be blank"*. The
second is Jakarta Bean Validation's own sentence. The same person read both of them, on the same
screen, in the same second.

`CLAUDE.md` states the rule as *"nothing technical reaches the user"* with no carve-out, and
`ErrorCodeTest` has enforced it for a year — **over `ErrorCode.java` only.** This was never a second
policy. It was one policy with an unpoliced channel, because `ErrorCodeTest` walks an enum and an
annotation is not in the enum. **383 constraints of the 558 a user can trip had no message at all.**

**The messages say what is wanted rather than translating the constraint.** `@NotBlank` on a vendor's
name is *"Enter the vendor's name."*, not *"Name must not be blank."*; `@Digits` on a purchase cost is
*"Enter a cost in rupees and paise, for example 4500."*, following the what-is-wanted-plus-an-example
standard the phone field already set. The style was taken from the 175 constraints that already
carried a message and from `ErrorCode`'s own sentences — *"That name is too long."*, *"Choose a
unit."* — rather than invented a second time.

**What stops it coming back is `FieldErrorMessageTest`**, sitting beside `ErrorCodeTest` and
importing its jargon and tone lists *verbatim*, so the product cannot end up with two registers in
one response body. Its scope is a rule and not a list: a constraint is in scope if and only if it is
reachable from a `@RequestBody` parameter of a `@RestController`, walking transitively through
`List`, `Map`, arrays and type arguments. `List<@Size(max = 100) String>` is a real shape here ten
times over and those messages reach a reader just as directly. Entities, projections and internal
service types are out **by that rule**, so nothing starts or stops being checked because somebody
edited an array of class names.

**An unset message is not empty**, which is the detail the whole test turns on. It is the literal
`{jakarta.validation.constraints.NotBlank.message}` — a bundle key the validator resolves out of the
library's properties file — so the test looks for the braces instead of guessing at English. Four
further checks: not Jakarta's sentence retyped by hand, a capital and a full stop so it reads as a
sentence beside a `KMS-nnnnnn` one, no developer jargon, no blame or theatre. **The bare word "must"
is deliberately allowed**: *"Latitude must be between -90 and 90."* was written by a person, is about
the temple's data, and already ships.

The walk reached 70 controllers, 108 request bodies, 110 of our own types and 558 constraints, and
asserts floors of 60/90/90/500 — floors rather than exact counts, so adding an endpoint does not fail
the build and train people to edit a number without reading it, while a walk that starts finding
*less* fails loudly. That is the failure mode a green-and-worthless test has.

**Two request bodies are never validated at all, and this changelog records it rather than leaving it
in a proof file.** `EmploymentBanController#retract` and `PurchaseOrderController#generate` both take
`@RequestBody(required = false)` with **no `@Valid`**. The ban reason's `@Size(max = 1000)` has
therefore never run, and the message it now carries still cannot reach anybody. That is the same
class of defect as T-098 itself — a check that looks like it is running and is not — and it is a task
of its own, not a line in this one.

#### T-129 — an order nobody sent cannot be a vendor's no-show

PO-2026-0036 was raised on staging as a **draft**, never sent, and cancelled with the *"Vendor Never
Delivered this Order"* box ticked. Heritage Fresh Dairy's scorecard then read **0% on time, 1 order
never delivered** — for an order the dairy had never heard of. The scorecard's own explanation said
*"Drafts are left out"*, and it was not true.

**Rajeev ruled on 2026-09-10, from three options, and took the second: the box is only offered once an
order has been marked sent.** That was against the coordinator's recommendation, which is worth
recording, because the losing option had a real case behind it and the ruling has a cost — below.

The cancel panel now hides the box on an unsent order and puts a sentence where it was: *"This order
was never sent, so there is nothing to hold the vendor to. Cancelling it counts against nobody's
delivery record."* `PurchaseOrderService.cancel` refuses the pairing with the new **`KMS-400147`**,
after the transition check and before the `UPDATE`, so a refused request leaves no half-applied
cancellation. A rule that lives only in a form is not a rule — the same endpoint takes the same field
from anything that can post to it. **The guard reads `sent_at` and not the status**, because a
cancelled order's status no longer says whether it was ever sent, which is precisely the case being
refused.

**There is no CHECK constraint, and that is a decision rather than an omission** — written into
`V120`'s own header so the next reader finds it beside the code. V118's
`purchase_orders_abandoned_is_a_cancellation` ties two columns written by a single `UPDATE`, and no
decision about the product could ever make a no-show on a live order meaningful; it is a coherence
rule about a row's state. Today's is a different kind of thing: **a policy about what a person is
allowed to assert**, chosen from three defensible options on one day. Policy that may be revisited
belongs where reverting it costs an edit to one method, not a second migration and a second rewrite
of stored rows. A smaller second reason: a CHECK here would constrain a row's *history* rather than
its state — `sent_at` is written by *Mark sent* and `vendor_abandoned` by *Cancel*, minutes or days
apart and possibly by different people, so the pairing is not atomically coherent the way V118's is.
**If it is ever added, `VendorPerformanceIT.abandoned()` has to gain a `sent_at` first**; it builds
its fixture as an unsent order with the flag set, which is legal today.

**`V120` clears the flag from every cancelled order that was never sent, per tenant.**
`purchase_orders` carries `enable_tenant_rls()` and the migration role is unprivileged, so a bare
cross-tenant `UPDATE` would match nothing through the policy's `NULLIF`, report success, and correct
nothing anywhere. That exact silence is what `AbandonedWithoutSendingMigrationIT` exists to catch,
and it seeds **two** temples on purpose: one would pass against a migration that adopted the first
tenant and stopped. A migration was the only place the correction could happen — the scorecard reads
the column directly, so leaving the row keeps scoring a supplier 0% forever, and `CANCELLED` is
terminal in `PurchaseOrderService`, so there is no in-app route to untick it.

**The activity trail is deliberately not rewritten, and this will look like a bug if nobody says so.**
PO-2026-0036 keeps its `po_events` line reading *"— recorded as never delivered by the vendor."*
`po_events` is append-only by design and that line is the honest record of what the coordinator
actually did on 10 September. What was wrong was never that the act happened; it was that the act
**scored somebody**. So the scoring input is corrected and the history of the act is not. After the
deploy PO-2026-0036 shows no *Never delivered* badge and its trail still says the vendor never
delivered it. **That is intended.**

**The cost of the ruling, stated plainly because somebody will hit it.** *Sent* in this application
means somebody pressed a button, not that the vendor knows. A temple that rings its dairy, never
marks the order sent, and is then let down must now mark the order sent before cancelling it. It is
not a defect; it is what option 2 buys, and it was the case against it.

#### The migration number, and why it moved

T-129's builder wrote the file as **`V121`**. The coordinator renumbered it to **`V120`** after the
build — the file, its internal comments, its test and its proof. T-091 had reserved `V120` and then
**refused its brief without using it**, so the number was never spent, and leaving the gap would have
handed the next task a number in good faith that Flyway would later refuse to boot under. **This
project has already lost a deploy to exactly that**, with `V108` sitting below an applied `V110`. The
rule written down then is the one applied here: a reserved number that goes unused goes straight back
to the pool.

#### Also in this release, documents only

`docs/work/DECISIONS.md` gains **D-24, D-24a and D-25** — Rajeev's ordering-flow rulings of
2026-09-10, covering the journey from the shopping list to the purchase order, what happens to a
draft nobody sends, and the vendor's lead time as a promise that binds both sides. `DISPATCH.md`
gains the **T-132 to T-137** task set those rulings produced, T-091's refusal, and T-131's live
verification.

---

### 2026-09-10 — A supply a vendor already has can be edited, which is what makes last night's lead time reachable at all (wave 19; task T-131)

**The field shipped with nowhere to type into.** T-090 added `vendor_supplies.lead_time_days` and put
a **Lead time (days)** box on the Supplies section of `/vendors/<id>` — on the **Add supply** form.
That form's picker is built as `ingredients.filter((i) => !suppliedIds.has(i.id))`, deliberately, so
that adding is not a second way of editing. **The consequence nobody drew: an ingredient the vendor
already supplies cannot be chosen, so its lead time could never be set.** Every supply that exists
already existed. On staging and in any real temple, the escalation T-090 built — amber while there is
slack, red the day the deadline lands — **could not fire on a single row**, because every row's lead
time was permanently `—`.

**The only route left was destructive.** Remove the supply and add it again, which starts from an
empty form: the last price the temple paid and the preferred flag the shopping list reads both go in
the bin in order to record how long a vendor takes. Three facts thrown away to change one.

**Each supply row now has an Edit beside its Remove**, and Edit swaps the row for the same row as
inputs with a Save and a Cancel at the end — the pattern `/ingredients` already uses for the same
interaction on the same kind of thing, read first and copied rather than invented a second time. Price,
lead time and preference are all editable. **The ingredient is not**, and that is the one departure
from the precedent: on `/ingredients` the name is a property of the thing, whereas here the ingredient
is what the row *is* — the server addresses a supply by (vendor, ingredient) — so changing it in the
box would not correct this supply, it would create a different one and leave this one behind.

**No backend production code changed, and that is the finding worth keeping.** `VendorService.setSupply`
already ended in `INSERT … ON CONFLICT (vendor_id, ingredient_id) DO UPDATE`, so a PUT for a pair the
vendor already has has always edited that row. And the clash with `vendor_supplies_one_preferred` —
the partial unique index allowing one preferred vendor per ingredient per temple — was already handled
three lines above it: setting `preferred` clears any other vendor's preference for that ingredient in
the same transaction, so the tick **moves** the preference rather than colliding with the index.
**Neither claim had ever been exercised from a screen, because no screen could send the request.**
Both have tests now, and the negative control demonstrates the second rather than asserting it: switch
that one statement off and ticking Preferred on an existing row is a 500. The screen therefore owes
the person a sentence rather than a refusal, and there is one above the table — *"Only one vendor can
be preferred for an ingredient, so ticking Preferred here takes it from whichever vendor holds it
now"* — placed over the table and the Add form both, **because the vendor losing the preference is not
on this screen and cannot be named there.**

> **The trap this turned up, and it is the kind that erases data quietly.** `setVendorSupply`'s
> `lastPrice` and `leadTimeDays` were **optional** keys in `frontend/lib/api.ts`. But the server writes
> **all three columns on every call**, so a caller that omits one is not saying *leave it alone* — it is
> silently erasing it, and an edit meaning to change a lead time would take the price and the preference
> down with it. That is the exact loss this task exists to stop, arriving through a different door. Both
> are now **required-and-nullable**, which turns forgetting one into a compile error, and the reason is
> in the method's own javadoc. Anyone writing a form over an upsert should read it.

**Clearing a box lands as null and never as 0**, in one named function used by the Add form and the
edit row alike rather than in four inline ternaries — because the two ways of getting it wrong are
opposite and both are one character from correct. `Number("")` is `0`, which turns *nobody has recorded
this* into *the goods arrive the same day* and has the planner tell a cook there is still time to order
rice that can no longer be got. The reflex guard `Number(t) || null` fixes that direction and breaks
the other, quietly discarding the real `0` that a shop you walk into and carry the goods home from
actually has. The blank is tested as a string, before anything is coerced.

**And the wider lesson, which cost a whole wave.** T-090 was green by every measure this project has:
2155 backend tests, 1330 frontend, `tsc` silent, `next build` clean, a negative control that reproduced
both of its claims, green CI, a verified deploy, and the served bundle grepped for all three badge
sentences. **Not one of those could see that the field had no way in.** Its tests set a lead time on a
*new* supply — the one case the screen allowed, and the one case a real temple never has. The defect was
found in the first minute of driving the deployed app. Nothing is a substitute for that minute.

**No migration.** The column exists; staging stays at `V119`.

**Not done, and it needs Rajeev.**

1. **Not yet seen working by a person.** The check to run: open a vendor with an existing supply, press
   **Edit** on a row, put a number in **Lead time (days)**, Save, and confirm the row reads that many
   days with the price and the Preferred badge still on it. Then Edit again, empty the box, Save, and
   confirm it goes back to **—** rather than to **0 days**.
2. **`preferred` was put into the inline edit rather than left out of it**, which the brief left open.
   The reasoning: it is one of the three facts Remove-and-add was destroying, so leaving it out would
   fix two thirds of the defect and leave the third still needing the destructive route. The
   alternative — leave the tick on the Add form only and send the row's current value back untouched —
   is two lines away. **What cannot be had both ways** is `preferred` editable and no explanation on
   the screen.
3. **The sentence about the preference moving is a session's wording, not his.**
4. **The Add supply form is unchanged and still cannot reach an existing supply.** Deliberate, and
   pinned by a test: with the row editable, "add" and "edit" being two routes to one thing is how they
   come to disagree. If he would rather the picker offered everything and the form simply overwrote,
   that is a different decision.
5. **T-090's own outstanding items still stand.** The order screen's warning still uses the global
   two-day guess even on rows where a real lead time now exists, and the shopping list still reads
   amber on most rows. Both are outside this task, and the second of them is **T-130**, which is
   waiting on him.

### 2026-09-10 — How long a vendor takes is a thing the product knows, and a shortage says the day it has to be ordered (wave 18; task T-090)

**The prerequisite was real and it was the bulk of the work (T-090).** Rajeev's rule from his review
of 2026-09-08 — *order-by date = the date it is needed minus the lead time; amber while there is
slack, red the day you hit it, and past that say something different* — could not be computed at all,
because **there was no lead time anywhere in the product**. Not on the ingredient, not on the vendor.
`vendor_supplies.lead_time_days` (**`V119`**) is that field, and it sits on the (vendor, ingredient)
pair beside `last_price` and `preferred` because it is the same kind of fact: the rice merchant may
deliver rice next morning and take a week over a sack of jaggery he has to fetch. Putting it on the
vendor would average those into a figure wrong for both; putting it on the ingredient would say rice
takes four days no matter who is asked. It is entered on the Supplies section of `/vendors/<id>`,
which already existed — nothing new had to be invented for somebody to type it in.

**Nobody having said is not the same as same-day, and the column is built so it cannot quietly
become zero.** Nullable, no `DEFAULT`, and the fallback is the two-day assumption the product already
carried. A `DEFAULT` of 2 was considered and rejected: it would make *unknown* and *the vendor told us
two days* the same row, and the first is worth chasing while the second is worth acting on. The
collapse is blocked at all four points it could happen — the form checks for a blank box before
`Number("")` turns it into 0, the request record, `getObject` rather than `getInt` in both row
mappers, and a boxed `Integer` on `LeadTimes.effectiveDays` so unboxing at a call site is a compile
error rather than a silent zero. **Zero remains a real and different answer:** the shop somebody
walks into and carries the goods home from.

**Three states, and the third is a sentence rather than a darker red.** A short meal's badge on the
planner and every computed shopping-list line now carry an order-by date: `Short · order by 12 Sep`
in amber, `Short · order today` in red, and `Short · won't arrive in time` **in the same red**. A meal
tomorrow that is short of rice is not less serious because the deadline has passed, so escalating the
colour a third time would be wrong in the other direction — and a shade cannot tell a cook which of
two quite different problems they have: get the order out today, or find another way to feed people.
The tests assert the words, not the colours, and the negative control patches the third sentence into
the second to show the distinction is load-bearing.

**The two "2"s in the codebase are different numbers, and that is now written where it cannot drift.**
`ShoppingListService.LEAD_BUFFER_DAYS` writes `needed_by = earliest demand − 2` and
`PurchaseOrderService.generate` copies it onto the order as the date the vendor is asked to *deliver*
by. That is a **delivery buffer** answering *what date do we write on the order*. A **lead time**
answers *when is the last day we can ask*, and nothing in the product could answer it. So a recorded
lead time supersedes the assumption **for the order-by date only** and leaves the buffer alone —
recorded in three places, `V119`'s header, `LeadTimes`' javadoc and a new javadoc on
`LEAD_BUFFER_DAYS` itself. **`shopping_list_lines.needed_by` is untouched**, because changing it
would change what every generated purchase order asks a supplier for, which is a product decision and
not a side effect of adding a field.

**Nothing new refuses anything.** No gate, no blocked save, no disabled button. The only new refusal
in the whole task is a lead time outside 0–365, ordinary bean validation on a number nobody means to
type, reaching the person through the existing validation path — so no error code was allocated.

**Two presentational calls, both named so he can overrule them.** The shopping list's *Needed by*
column now reads **Order by**: with no lead time recorded the two dates are identical, so keeping both
would have put two columns side by side showing the same date under two names. And a muted
**"assumed"** appears beside a badge whose lead time came from us rather than from the vendor, as the
nudge towards recording a real one. The delivery date is still in the payload, still on every purchase
order and still on the order screen.

**Not done, and worth knowing before anybody tests it.** Nobody has driven this on a screen — record a
lead time on a preferred vendor, open a planner day whose dish is short, and read the badge on three
days either side of the order-by date. **The shopping list will read amber on most rows**, because the
rule as given says amber while there is slack and it was built as written rather than with an invented
threshold; if amber should be reserved for the last few days that is one comparison in
`OrderUrgency.on`. The order screen's own notice warning still uses the global two-day guess even
where a real lead time now exists for that vendor and ingredient.

**And the defect the task uncovered and deliberately did not fix, recorded as T-130.** The shopping
list writes a purchase order's delivery date as *the meal date minus two days*, and the order screen
then warns that that very date gives the vendor too little notice — the buffer is applied when the
order is written and complained about when it is read. A meal on 12 September with the list
regenerated on 10 September produces the warning on a date the application itself chose. The fix is to
stop subtracting the buffer now that an order-by date exists to carry the notice, but it changes what
every generated order asks a supplier for, so it is Rajeev's to settle and its own task.

### 2026-09-10 — The last copy of the unit rule that the backend could get rid of is gone (wave 17; task T-128)

**The recipe scale preview stops choosing its own units (T-128).** Which unit a quantity is *said* in
— kilos once there is a whole one of them, grams below that, and the line's own unit when there is
none of it at all — was written in three places. `Quantities` was lifted out of `RecipeScaler` on
2026-08-30 to give the whole application one answer, and **the original was left behind**. So when
`Quantities` learned in wave 16 that a quantity of nothing is said in the unit the thing is kept in,
the scale preview did not: an ingredient measured in litres, scaled up for a festival, still read
**0 ml** there. `RecipeScaler.pickDisplayUnit` is deleted and both callers now reach the same zero
case. Two copies remain — this one and `frontend/lib/format.ts` — and they are in two languages and
cannot be merged, which is what the test is for: **`QuantitiesTest`'s seventeen vectors are run
through both backend callers**, so fixing one and not the other fails.

**The rounding deliberately did not merge, and that is the part worth knowing.** `RecipeScaler` rounds
to two decimal places because the screen puts its figure in a column beside the raw one;
`Quantities.cooks` rounds to a step a person can weigh to — 135 gm, 10 Kg. Folding those together
would change every figure on the scale preview, which is a product decision and not a tidy-up. Both
classes now carry the reason, so the next reader does not merge them by mistake.

**One guard was traded away knowingly.** The deleted `switch` was exhaustive on `Unit.Family`, so a
new family would have failed to compile there; the map that replaces it shows an unknown family in
its own unit instead. Fail-soft rather than fail-wrong, already how pieces behave, and it is written
into the field's comment for whoever adds the next family.

**Not done, and there is less to see than the task implied.** A recipe line of zero **cannot be
entered at all** — the form and the API both refuse a quantity that is not greater than zero — so the
*0 L* this fixes is reachable only by a line that got into the database some other way. Found by
calling the deployed API after this shipped. The change stands on the copy it removed, not on a
screen anybody can be shown. No migration in this wave: staging stays at `V118`.
And **a draft purchase order nobody sent can still be ticked as "the vendor never delivered"**, while
the scorecard's own explanation says drafts are left out. That is recorded as **T-129** with two
options and a recommendation, and it is Rajeev's to settle, not a defect to fix quietly.

### 2026-09-10 — A vendor is scored on what actually arrived, a cancellation can name the supplier who never came, and five things found by driving the deployed app (wave 16; tasks T-124, T-125, T-126, T-127)

**On-time is scored per item, and the fifth scenario finally has somewhere to go (T-124).** Rajeev
wrote out five delivery scenarios on 2026-09-09, each with the figure he expected from it, and the
application answered three of them wrongly. On-time was a **binary read of the first goods receipt**:
the lorry either came inside the promised window or it did not, and every item after that counted for
nothing. It is now the **mean of the per-item fractions** — eight of ten sacks inside the window is
80%, not a pass and not a fail — so a part delivery reads as the part delivery it was. A line is
**capped at fully delivered**, because bringing more than was ordered is not a bonus, and a
`NOT_DELIVERED` return **takes its quantity back out of the count**, because a receipt reversed as
never having happened should not go on scoring. A return for weevils leaves on-time alone: the goods
were there on the day, and that is what on-time measures.

His fifth scenario — *nothing ever came, we cancelled and went elsewhere* — had no home in the
product at all. The report's rule excluded every cancelled order, which was written on a true premise
(a cancellation is usually the temple's own decision and cannot be held against a supplier) and
missed that a cancellation is sometimes the **only record of a supplier's worst possible
performance**. Cancelling now offers a tick box in Rajeev's own words, **"Vendor Never Delivered this
Order"**. Ticked, the order scores 0% and counts as abandoned; unticked — the default, and every
cancellation ever raised before today — it is counted **nowhere at all, in either direction**, because
silence blames nobody. `V118` adds the column with a CHECK tying it to `CANCELLED`, so it cannot be
set on an order still in progress, and the flag goes into the existing `PO_CANCELLED` audit record
rather than growing a second pair of who-and-when columns beside `cancelled_at`.

**This is also how T-109 stops being a question rather than getting an answer.** It asked what an
order-grain on-time figure should do when one line of four never arrived, and at that grain there was
no defensible answer to give. At item grain there is no special case left to rule on.

**The cancelled order says on its face who is being blamed (T-126).** T-124 recorded the tick in the
row, the trail and the scorecard; a person opening the order still saw only the reason. Beside it now:
a **Never delivered** badge and the sentence *"The vendor never delivered this order. It counts
against their delivery record."* A bare badge under a line already reading *Cancelled* could as easily
mean the goods never came **because** we called it off — the sentence names who we are holding
responsible, which is the entire difference between the two kinds of cancellation. The wording is the
cancel form's own promise said back, so nobody is told two different things about one tick.

**Five things found by driving the deployed app after wave 15 (T-125).** An ingredient's movement list
printed **`USED_BEYOND_RECORDED_STOCK`** at the reader — three of the seven kinds had no label at all,
so the screen fell back to the stored constant; all three have one now and the fallback humanises
whatever a later migration adds instead of shouting it. **Zero was shown in the wrong unit**: an item
kept in litres read *0 ml*, because the rule that turns 0.4 L into 400 ml has nothing to step down from
when the figure is nothing; zero now keeps the item's own unit, while *no figure at all* still reads as
an em dash, because "we have no figure" and "we have none of it" are different things. **The receipt
card never noticed its PDF was ready** — the document is generated elsewhere and the card was drawn
once, so the only way to get the download button was to reload the page; it now re-reads while one is
pending, stops the moment it is ready, and does not run while the tab is hidden. **The banner after
recording a gift promised a thank-you nobody could receive** — an anonymous gift leaves nobody to
thank, and a named donor with neither phone number nor email cannot be reached; it now says which of
the three it is, from the server's own reachability test. And **the duplicate-invoice warning counted
voided invoices**, which is exactly the case where a number is being reused on purpose — T-073 ruled
that a struck invoice should not hold its number and the ruling had never been built.

**The printed sheet and the screen stop disagreeing about zero (T-127).** The backend keeps its own
copy of the quantity display rule, for the job card, the work order and the generated documents. T-125
fixed the screen; this side still stepped zero down, so a cook holding the sheet read *0 ml* against a
screen reading *0 L* for the same ingredient. The comparison is on the sign rather than on equality
with zero — a quantity comes back through JDBC carrying its column's scale, so a genuine nothing
arrives as `0.000` and an equality test would answer false on the scale alone, working in a unit test
and not in the application. A figure that is merely small still steps down. The class javadoc claimed
that changing one copy without the other fails the build; that was never true, this defect is what it
cost, and it now says what actually holds.

**Not done.** None of the four has been seen working by anybody yet — they deploy to staging with this
entry and want a pass as the Kitchen Manager (the scorecard, a cancellation with the tick, a cancelled
order's face) and as a Temple Admin (the receipt card, the ledger banner, an item kept in litres).
**T-126's sentence is the builder's wording, not Rajeev's** — he gave the tick box's label only, and
one string is cheap to change. **A third copy of the quantity rule still exists**, in `RecipeScaler`,
with the same missing zero case; it is recorded as **T-128** rather than folded in, because three
copies of one rule is now a pattern and the question of whether they collapse or get a shared test is
bigger than the fix.

### 2026-09-10 — An ingredient's stock stops at zero, a donation opens on its own page with its 80G receipt, and seven errors stop naming a door the reader cannot open (wave 15; tasks T-122, T-110, T-104, T-095)

**Stock stops at zero, and the shortfall stays on the record (T-122).** T-087 shipped that morning
with the amount cooked beyond the books **subtracting**, so an ingredient read **−40 Kg** on its own
screen. Rajeev, seeing it: *"That makes no sense. We should stop at 0. How does negative ingredients
make any sense?"* On hand is now the sum of what actually moved. The `USED_BEYOND_RECORDED_STOCK` row
is unchanged in every respect a person can see — same quantity, same meal, same note naming the
ingredient, same place in the movement list — and it posts nothing to the total. In ledger terms it is
a **memorandum entry**: a row the book records and does not post, and V116 says so in the comments on
the table and on the quantity column so the next reader meets the rule rather than a puzzle.
**Available is deliberately left alone and can still go below zero** — it is a forecast, what is on the
shelf minus what is already promised, and being over-promised is a real thing a planner needs to see.
Six readers now agree on the on-hand figure: the item screen, the FEFO allocator, the shopping list,
the sufficiency check and both sums behind them. **No backfill** — staging carries V115's negative
rows and heals on this deploy with no data touched, which is the right property for a correction to
something already live.

**A donation opens on its own page, with its receipt and the donor's other gifts (T-110).** The
donations list finally links somewhere. `/donations/[id]` carries the gift, its receipt — issue,
download, send again — and the donor's other gifts, completed ones by default with everything on a
toggle. T-020 wanted a detail screen to hang a receipt on and T-073's second half wanted the history;
Rajeev merged them because somebody deciding whether to issue a receipt is asking both questions at
once. The receipt is a **fifth kind in the existing documents pipeline** rather than a path beside it,
so it inherits storage, download and send, and it carries a **permanent number** from a per-tenant
counter, issued once and never reissued — pressing *send again* re-sends the document that exists
rather than building a new one. **The wording tells the truth about what can actually be claimed:** a
cash gift from a registered temple gets an 80G receipt, goods get an acknowledgement that says plainly
it is not one because only money qualifies, and a temple with no registration gets a receipt saying it
supports no deduction.

*Not done, and named rather than hidden:* refusing a receipt on a struck donation **reuses
`KMS-400134`** because `ErrorCode.java` was reserved and no code could be minted — it reads correctly
there and the screen withholds the control anyway. `donorHistory` is **unbounded** — no limit, no
paging — which has never mattered because until now it had no caller at all; recorded as **T-123**
rather than guessed at. And **WhatsApp will not carry the receipt** until `donation_receipt` is
registered with Meta; until then it falls through to SMS and email.

**Seven errors stop telling a reader to do something their role does not allow (T-095).** A next step
is a lie whenever the person who can hit the error cannot open the door it names. A cook meeting a
scrapped machine was told to reinstate it — the Temple Admin's alone. A volunteer meeting a cancelled
shift was told to post a new one. Somebody requesting their own leave was told to change the existing
record, which is the approver's job. Two kitchen errors told an ingredient requester to restore an
archived kitchen or turn a meal planner off, both Temple Admin settings. An approver was told to send
a request for review when only its author can. And **`KMS-400139` told a cook to change the mark on a
roster the day after T-106 stopped cooks doing exactly that** — the sentence was written to name a
control, and then the control moved.

`NextStepPermissionTest` now reads the tree rather than a list somebody maintains: each code's
throwing endpoints, the permission each declares, and whether the reader who can reach the error holds
what the next step's action needs. **Its limits are in its own javadoc rather than left to be
discovered** — it matched only 4 of the 33 codes that instruct a door, **six of the seven fixed here
were invisible to it**, and on the seventh it went green while identifying the wrong door. It is a
floor, not the audit; the audit was a person reading all 148 sentences.

**The double-send guard can no longer be deleted quietly (T-104).** T-102's guard against two admins
sending one letter twice lives in the statement that records the send — `WHERE id = ? AND status =
'DRAFT'`. With the row lock in front of it **no request can make that predicate fire**, so removing it
left all fourteen tests green: a green suite was consistent with the belt-and-braces being present
*and* with its having been deleted. This adds the one assertion that tells those apart, as a **named
exception** to this project's rule against asserting on the text of SQL rather than a quiet breach of
it — the rule holds everywhere behaviour can reach the code, and this is the one place it cannot. Not
a grep, for a concrete reason: `CommunicationRetryIT` already contains a **second** `UPDATE
communications SET status = 'SENT'` with no predicate at all, and a file-wide grep reads the wrong one
of the two and passes. No production code changed.

**Nobody has seen any of this working yet.** It is on staging and awaiting a pass on the deployed
site.


### 2026-09-08 — A recorded meal can be corrected, a failed message can be sent again to the addresses it failed for, and a roster can say who turned up (wave 8; tasks T-007, T-015, T-016)

**What was cooked can now be changed, and the sentence saying it could not is gone from four places
(T-007).** A Temple Admin holding `CORRECT_RECORDED_MEAL` opens a recorded meal in the planner and
presses *Correct the figures*. The correction reverses the stock the meal drew and draws it again at
the new figure, in **one transaction with the mark on the meal** — a `@SpyBean` test makes the stock
half throw and asserts that afterwards `corrected_at`, `correction_note` and
`original_actual_servings` are all still null and no audit event exists, so the two halves cannot
commit apart. The meal keeps what it first said: the planner reads *"640 kg cooked, corrected from
400 kg by Anand Das on 8 Sept 2026"*, and the original recording, its author and its note stay on the
screen underneath. Correcting twice is refused with **KMS-400137** and moves no stock on the second
press. A dish whose figure is restated unchanged is left entirely alone — compared with `compareTo`
rather than `equals`, because `400` against a column holding `400.000` is the same number and
`BigDecimal.equals` says otherwise — so no ledger pair is written that nets to nothing and no
untouched dish is badged as corrected. `MEAL_ALREADY_RECORDED`'s next step used to read *"What was
cooked can't be changed afterwards. Ask a Temple Admin if the figures are wrong."*; it now reads
*"Record a correction if the figures are wrong."* `V106` adds five columns and three CHECKs, no
backfill and no index. `AuditAction` gains `MEAL_CORRECTED` rather than filing a second `MEAL_COOKED`
— a dish corrected to *not made* was not cooked, and an entry saying it was is a trail that lies.

**Cost per serving deliberately does *not* follow the corrected figure, and there is now a test that
says so.** The brief asked for the opposite. `MealKindCostService` costs a dish at `target_yield` —
what was **planned** — on a documented decision whose own comment says *"a period of days must add up
to the days in it"*, and a correction moves neither column that report reads. Rather than drop the
criterion, the builder inverted it: `costPerServingIsUnmovedByACorrection` asserts the figure is
identical either side of a 400→640 correction, with the reasoning in its Javadoc. Anyone who later
decides the report should follow what was cooked will meet that test and make the change knowingly,
instead of hearing about it from a temple whose costs moved overnight. *(Rajeev has since ruled that
costing should move to actuals. That is a separate, unbuilt decision recorded in
`docs/work/DISPATCH.md`; this release ships the pinning test as written.)*

**A message can be sent again to the addresses it failed for, and to nobody else (T-015).** *Send it
to them again* sits in the "Who it went to" card of a sent message, where the failures are already
listed, and re-queues only the recipients whose notification is `FAILED`. Delivered and suppressed
recipients keep the notification they already had — asserted by uuid, not by count. The letter cannot
be changed by this path: a retry carrying a rewritten subject leaves `subject`, `body_html`,
`body_text`, `status` and `audience_count` byte-identical. A message every copy of which arrived is
refused with **KMS-400138**, as is a second press while the first retry is still pending, so the
button stops working rather than sending a third copy.

**The trap this one nearly shipped into, because it is the whole reason the feature exists.** The
per-recipient insert was `ON CONFLICT … DO NOTHING`. A retry built on it queues a fresh notification,
fails silently to attach it, and answers `200 {"retried":1}` — a success it has not had, with the
recipient reading *Failed* on the screen for ever. It is now `DO UPDATE SET notification_id =
EXCLUDED.notification_id`, and the negative control that restores `DO NOTHING` turns exactly those two
assertions red.

**A roster can say who turned up, and a coordinator can take a named volunteer off a shift (T-016).**
`shift_signups` gains `attended` and `attendance_recorded_at` (`V107`, two nullable columns moving
together under a CHECK, no backfill — every existing signup is genuinely unmarked and NULL is the
honest reading of it). Attendance has **three** states, not two: a signup nobody has marked reads
*Not marked*, never *Did not come*. The negative control for this is the sharpest in the wave — it
swaps one `getObject` for `getBoolean`, which answers `false` for SQL NULL, and three tests turn red
saying every unmarked volunteer has become a no-show. Marking twice is refused with **KMS-400139**.
Removal is a second endpoint behind `MANAGE_VOLUNTEER_SHIFTS`, never a widening of the volunteer's own
release: it writes the same `released_at` through the same code path, so the spot is freed, the
waitlist head is promoted and the pending reminders are cancelled exactly as before, and a volunteer
calling it for somebody else gets a 403 with the other volunteer still on the roster.

**A next step that told a coordinator to do something the product cannot do was caught before it
reached a user.** `ATTENDANCE_ALREADY_RECORDED` was reserved reading *"Change it on the shift's
roster."* Nothing in the product changes a mark once it is made, so that sentence sent somebody to a
screen to perform an act that does not exist there. T-016's builder refused it and it now reads *"Look
at the roster to see who was marked."*, which is true. A real correction path is queued as T-079.

**Not done.** None of the three has been driven by hand in a browser — this release is commit and push
only, so **staging is behind `main`** until the next deploy and nothing here can be tested on the live
site yet. T-007's public giving page is a finding, not a fix: `GivingPageController` computes the
public cost-per-plate from **planned** figures and never reads `actual_servings`, so a temple that
corrects a meal to 640 has formally stated it fed 640 people while the public number does not move.
That predates this work but a correction feature makes it visible in a new way, and it wants its own
task. `StockMovementService.compensateAllFor` duplicates `DonationVoidService.reverseGoods`; the
duplication is named in its own doc comment rather than left to be discovered. And the audit screen
labels 5 of roughly 200 actions — `MEAL_CORRECTED` is labelled because it is new here, the general gap
is a screen-level problem of its own.

### 2026-09-08 — The temple can name its own meals, a credit note settles the variance it was raised for, and a struck gift stops haunting the reconciliation report (docket A3, wave 7b's third-reader sweep; tasks T-005, T-071, T-072, T-078)

**Meal kinds get a screen, and a cascade that shipped in wave 4c gets its first caller (T-005,
docket A3).** Settings → Meal kinds, at `/settings/meal-kinds`, Temple Admin only. The endpoints
have existed since E4-S7 and **three of the four had no caller anywhere in the application** —
`GET /api/v1/meal-kinds` had four, the planner, the composer and the day view, while `POST`, `PUT`
and `DELETE` had none. So a temple was stuck with whatever provisioning named its six kinds: it
could not start serving an evening meal, could not call Lunch *Raj Bhog* — the docket's headline
ask — and could not remove a kind added by mistake, because there was no way to add one. T-038 went
further and shipped a **whole rename cascade with `V96` and a test suite that no human could
reach**. This screen is what reaches it.

Built as the sibling of `/settings/occasions` and to the same shape deliberately — one table, one
add form above it, an edit and a delete per row — because curating the kinds of meal and curating
the festival occasions are the same act on two different standing facts about the temple. The role
split matches the server rather than being chosen: reading the kinds is `MANAGE_MEAL_PLANS`, because
the planner has to know a Deity Offering has no usual hour, and changing them is
`MANAGE_TEMPLE_SETTINGS`. **No migration, no new error code, no new permission** — the two refusals
it renders, `KMS-400047` for a duplicate name and `KMS-400126` for a kind in use, both already
existed and were re-read in `ErrorCode.java` rather than taken from a row.

**Its negative control is the strongest evidence in the wave, and it is a control on the *test*
rather than on the fix.** It synthesised a meaningless error code and **the alert still rendered** —
so a test asserting merely that "an error appeared" would have passed against a screen showing the
wrong words entirely. Only the assertions on the server's own sentence and its next step went red.
That is worth recording because the convenient assertion is the one everybody writes.

**A credit note now settles the variance it was raised for (T-071).**
`VendorInvoiceService.withVariance` computed the informational variance on a PO invoice as
`amount - expectedReceivedValue`, using the **gross** invoiced amount; it is now
`amount - credited_amount - expected`. This is a **credit** defect rather than a **void** defect,
which is exactly why wave 7b's status-based sweep did not reach it — wave 7 added two things to
`vendor_invoices` and only one of them is a status.

Why it is worse than an arithmetic tidiness point: the single most common reason to raise a credit
note *is* the thing this variance exists to surface — a short delivery, a damaged sack, a price
argued down after the bill was cut. Against the gross amount, **the one act that resolves the query
is the one act that appears to do nothing**, for ever. Three places now answer "how much does this
bill come to" the same way — `restateStatus` deciding PAID, `InvoicePaymentService` computing what is
outstanding, and this — and they have to agree or the screens disagree with each other.
`expectedValue` is deliberately untouched: it is what the goods received are worth at the PO's line
prices, a fact about the delivery that a credit note does not change, and both operands stay on the
view beside `creditedAmount` so any screen wanting the gross figure back can work it out. A partial
credit leaves the remainder showing, which is the argument for netting rather than clearing.

**A struck gift stops being reported as a mismatch nobody could clear (T-072).** The daily
reconciliation selected `WHERE status = 'COMPLETED'` with no void clause. `V104` makes a void a
**mark** rather than a status change — `voided_at` is stamped and `status` stays `COMPLETED` — so
every gift the temple had struck was still being put to the gateway. That is the worst row to ask
about: a voided gift is by definition one *recorded wrongly*, so it is the one least likely to have
a real payment behind it, and it came back UNKNOWN on every run for ever, with nothing an operator
could do to clear it. Permanent noise in the one report whose entire value is that it is normally
empty, at the price of a live gateway API call per struck gift per run.

Excluding them loses no signal, which is the argument for excluding rather than reporting them
separately: a struck gift that *did* capture was never reported anyway, because the gateway answers
CAPTURED and the loop says nothing. So the only struck rows this report ever named were the ones
whose gift never happened — precisely what striking them recorded. `DonationLedgerService:186` and
`MonetaryDonationService:379,463` already read this way; this reader was the one that did not.

**A test that could never pass again (T-078). Test-only — the product is untouched and correct.**
`frontend/__tests__/reuse-plan.test.tsx` pinned a source window at 2026-09-01 and expected a landing
day of 2026-09-08, while `app/planner/reuse/page.tsx` clamps that day forward against `todayIso()`,
because a copy landing on a day that has gone plans meals nobody can cook. So the expectation was
really an assertion about the day the suite happened to run on, and **today only moves forward**:
once the temple's zone passed the 8th the test was permanently red, and CI in UTC had been red for
five and a half hours of every day since, because IST rolls over at 18:30 UTC. Pushing the literals
further out only re-arms the same bomb for whoever runs the suite after that date; the clock is
pinned instead.

**The zone is the half worth writing down.** `todayIso()` renders in the *temple's* zone, not the
machine's, so what has to be pinned is an **instant**, and a date literal without an offset is not
one — `new Date("2026-09-01T12:00:00")` is parsed in the machine's zone, so it is midday on the 1st
in Kolkata from a machine in UTC but 03:30 on the **2nd** from one at −10. **The naive fake breaks
to the west rather than the east**, which includes the machine it was written on. `+05:30` states
the instant outright, and it was proved rather than argued: run under `TZ=UTC`,
`TZ=Pacific/Kiritimati` (+14) and `TZ=Pacific/Honolulu` (−10), and the offset dropped to watch
Honolulu go red. Only `Date` is faked, because RTL's `findBy*` polls on real timers, and the clock is
handed back in `afterAll`.

**Two things found and deliberately left, both wanting Rajeev.** Every **hand-recorded cash gift** is
the same permanent reconciliation mismatch by a different route — `DonationRecorder` writes cash as
`ONE_TIME` with no `provider_payment_id`, and `RazorpayPaymentGateway` makes a real network call for
it and returns UNKNOWN from its catch — and the one-line fix contradicts T-072's own acceptance
criterion and changes what an operator's money report says. And a **voided invoice still gets a
variance computed** for a debt no longer owed, which is a product call rather than a sweep's.

**Not yet seen working by Rajeev.** `/settings/meal-kinds` wants one pass: add a kind, rename one a
plan already uses and watch the plans follow, then try to delete a kind in use and read the refusal.

### 2026-09-08 — A bill, a payment and a gift can each be undone, and the four figures that would have gone on counting them stop (docket M4, M5, M6, M9, tasks T-010, T-012, T-014, T-068, T-069)

**Shipped as one release on purpose, and it is the point of the entry.** Wave 7 built the three
corrections; wave 7b, run *before* release rather than after, repaired the places that already
**summed** the tables wave 7 gave a new state to. Wave 7 on its own would have put a wrong
cost-per-plate figure on the public giving page and let a struck gift go on completing a wish. The
two waves are one commit because separating them would mean deliberately shipping a known
donor-facing wrong number for the length of a release cycle.

**A vendor invoice can be struck or reduced, and a payment undone (T-010, docket M4 + M5, `V103`).**
`InvoiceStatus` was `PENDING` and `PAID` and nothing else, so there was not even a state for a bill
that should never have been raised; it sat in the payables queue for ever. A bill the vendor later
reduced could never be settled, because status is decided by comparing payments against the full
amount and nobody would ever pay it. And `InvoicePaymentController` was append-only with the invoice
flipping to `PAID` the moment the payments sum reached the amount, so a bounced cheque was permanent.

Three acts, deliberately three rather than one. **VOID** means the bill was never owed — terminal, and
every figure that sums invoices must skip it. **CREDIT** means it was owed and is now owed less, which
is exactly why a credit is *not* a status: an invoice can be credited and then paid, and both facts
have to survive. **REVERSE** means a payment did not happen after all. A temple arguing with a vendor
a year later needs "never owed" and "owed less" to be different answers rather than one word, which is
why the void mark and `credited_amount` are different columns.

The two tables are corrected in opposite ways, and the test is who reads them. `vendor_invoices` is
**marked** — the row stays, stamped with who struck it and why — because an invoice is read one row at
a time by somebody asking what was owed on this bill. `invoice_payments` is **compensated**, because
it is append-only since `V40` and its trigger refuses exactly the UPDATE a mark would need; the ledger
is read as a sum, and a sum corrects itself with a negative entry. `V40` had already designed for
this: `amount` is signed and its comment has said *"negative for a compensating correction"* since
2025, so the only two columns `V103` adds to that table are the ones the signed amount could not
carry — **which** payment a row undoes, and why. Double reversal is refused by a partial
`UNIQUE INDEX … WHERE reverses IS NOT NULL` rather than by the service alone, because the service's
guard reads the table and then writes it and two administrators pressing at once would both read
"not yet reversed".

The `PAID` decision moved out of `recordPayment` into `VendorInvoiceService.restateStatus`, now the
single place that decides it and called by all three write paths, with `VOIDED` terminal there so a
reversal cannot resurrect a struck bill. The status CHECK was **replaced** rather than dropped: a
status column with no constraint is a column that will eventually hold a typo. No backfill in the
migration, so there is no DML for row-level security to scope — worth saying rather than leaving to
inference, because a cross-tenant `UPDATE` in a migration here matches nothing and says nothing about
it.

**Two things about invoices are deliberately not changed and are Rajeev's to rule.** Voiding a bill
that has already been paid is still allowed — `voidInvoice` guards only against double-voiding — and
it is not silent, because the audit records `paidToDate` in the before-state and the sequence "void,
then reverse" now works. And a credit is refused where it would take what is owed below what has
already been paid, because the temple would then be owed money *by* the vendor; that is a refund
record, and the product does not have one. Both are coherent as they stand.

**A hand-recorded gift can be struck (T-012, docket M6, `V104`).** `DonationController` was POST-only
and the ledger read-only throughout, so a gift entered twice, or against the wrong donor, permanently
inflated the figures the temple reports under 80G — and where it was in kind it had inflated the
store-room in the same transaction, which could not be undone either. Striking one gift now touches
two records and corrects them in opposite ways for the same reason invoices and payments differ:
`donations` is **marked** because an administrator reads it one row at a time to answer what somebody
gave, and `stock_movements` is **compensated** because it is append-only and its only consumer is a
sum. Both halves commit in one transaction, so the two records cannot drift apart. The mark is
written **before** the stock is reversed, deliberately — the other order makes the atomicity claim
untestable.

A reason is demanded rather than offered, at the API and again in a CHECK constraint, so it cannot be
reduced to a space bar by some later write path: this is the one correction in the product that
changes what the temple tells the tax authority it received, and the next person to read the row is
entitled to know why it does not count.

**Two things went beyond what was asked, and both were right.** `form10bdRows()` now excludes voided
gifts — that is the **literal 80G filing**, not a screen, so a struck gift would have gone to the tax
authority with a donor's PAN attached, which is worse than the summary tile that was actually named.
And the donation CSV gains `Voided` and `Void reason` columns, which changes a file an accountant may
already have a template for.

**One named limit rather than a gap:** the void strikes the gift and reverses the food, and **leaves
donated equipment in the register**, with the dialog saying so before the button is pressed. Refusing
such a donation would need an error code that did not exist and would leave the 80G figure permanently
wrong to protect a register entry an admin can already correct by hand.

**Somebody whose employment was ended can be taken back on (T-014, docket M9).** `endEmployment` and
`update` shared one `requireStillEmployed` guard, so ending employment was irreversible *and* locked
the record in the same instant — a misclick could not even be corrected, and there was no reinstate
route among the controller's thirteen. There is one now, and **the guard on `update` is not
loosened**: the ordinary path stays closed and there is exactly one explicit, named, audited way back,
which is the shape `EquipmentService.reinstate` already set under D-15.

**It needed no migration, and that was established rather than assumed.** The end state is three
existing columns from `V57`, two of them nullable, so the inverse is representable as the schema
stands. The one thing that might have forced a column is a rejoining date, and it does not: it cannot
overwrite `date_of_joining` without destroying a fact about the person, a `date_of_rejoining` column
would keep only the most recent return and lose every earlier one — somebody can leave and come back
more than once, so this is an *event* and not an attribute — and `audit_events` is append-only and
already holds the matching `lastWorkingDay` on `STAFF_EMPLOYMENT_ENDED`. The ENDED/REINSTATED pair is
the durable record of the whole cycle.

**The access they come back with is asked for, not restored.** Ending an employment either disabled
the account or demoted it and kept no note of what it had been, so there is nothing to put back. Asking
for no access demotes and deliberately does *not* re-enable a disabled login — they came back without
one, and that is what that means. That branch does real work: `endEmployment` sets `status =
'DISABLED'` but leaves `role` alone, so a dismissed administrator still carries `TEMPLE_ADMIN` on
their users row, and reinstating them without a login has to take it off.

**A bill that was struck stops inflating the cost per plate a stranger reads (T-068).**
`GivingPageController` computed the public giving page's cost per plate as
`SUM(amount) … WHERE invoice_date >= CURRENT_DATE - INTERVAL '30 days'`, with **no status filter**.
That was correct on the day it was written and was falsified by T-010 in the same wave: a bill struck
as never owed, and the credited portion of a bill the vendor had halved, both went on counting as
kitchen spend at face value. The figure's own comment is why this matters more than its size — *"a
made-up number here would be quoted back at them by a donor"*. It is the one number in this product a
stranger reads.

The clause is `status <> 'VOIDED'` and **not** `IN ('PENDING', 'PAID')`, which is the same query today
and differs only on the day somebody adds a fourth status: the exclusion counts it, the inclusion
silently drops it. Money leaving the temple is the default state of an invoice row, and understating
spend here understates the cost of a plate — so a donor paying for "one plate" would be quoted less
than a plate costs and the temple would quietly carry the difference. A total that is too big is
checkable against the invoice list; a division that is slightly too small just looks like a cheaper
plate.

**A struck gift stops buying the temple a grinder nobody gave it (T-069).** Three sums, not the two
the task was filed for. `WishlistService` had two — the one that flips an item to `FULFILLED`, and the
`paid_inr` the giving page displays — both filtering `status = 'COMPLETED'` with no void clause. A
voided gift keeps that status, deliberately, because `V104` marks the row rather than giving it a
status of its own; so every sum filtering on status alone went on spending money nobody gave.
Applying the same question to the money path found a **third**, in `MonetaryDonationService`: the sum
`startWishlistCheckout` caps a devotee's gift at, and the one the webhook capture path uses to decide
whether an already-paid gift belongs to the item or is diverted to general funds. Repairing only
`WishlistService` would have left the product **worse than the bug** — the page reading "₹5,000 of
₹20,000" while checkout refused every further gift as over-funding, a devotee turned away from an item
the same screen says is not paid for.

**The general rule this pair paid for, now in `docs/work/README.md`: when a task adds a state to a
row, ask who already sums that table.** `SUM(amount)` goes on compiling perfectly when the meaning of a
row changes underneath it. Nothing is renamed and nothing is removed, so no compiler, no test and no
grep for an identifier finds it. Both defects were invisible to every test in the repo and to `tsc`,
both were found by the builder that created them, and both were correctly left alone rather than
reached for across a contract boundary.

**Half of T-069 is deliberately not built, and the reason it matters got sharper when it was
measured.** An item already marked `FULFILLED` by a gift since voided does not un-fulfil itself —
`markFulfilledIfComplete` only ever goes `ACTIVE → FULFILLED`. That is a product question, not a
patch. Measured on the finished code, the do-nothing outcome is worse than the odd number it was
expected to be: the row reads **"FULFILLED — ₹0 of ₹15,000" on the public giving page**, and pressing
*Give* is refused with `KMS-400068` because checkout tests status **before** it looks at money, so
fixing the arithmetic does not open that door. The daily archive sweep then takes the item away on a
clock that started when it was fulfilled and is **not** reset by the void. So a temple that needed a
₹15,000 mixer, was told it had one, and then found the gift was not real **silently loses the wish** —
never bought, never re-offered, nobody told. Four options with who is surprised by each are in
`docs/work/proof/T-069.md`; the live fork is between leaving it, reopening the item, and flagging it
to admins, and it turns on what a temple owes a donor who has already been thanked.

**The fix creates the visible contradiction rather than revealing one**, and that is the right trade —
a wrong number that looks right is worse than a right number that looks odd — but it is a thing to
meet in this entry rather than on a screen.

**Five new error codes and one permission.** `KMS-400132` (invoice already voided), `KMS-400133`
(payment already struck), `KMS-400134` (donation already voided), `KMS-400135` (this person is still
employed — nothing to reinstate) and `KMS-400136` (a record on file from when they left, which refuses
a reinstatement rather than warning about it, because D1 has that flag carrying a reason across all
ISKCON temples). `VOID_DONATION` goes to `TEMPLE_ADMIN` alone, forced rather than chosen: `VIEW_DONATIONS`
is already Temple Admin alone, so anything wider would let somebody void a record they cannot read.

**And one piece of advice that has been wrong for some time is fixed.** `EMPLOYMENT_ALREADY_ENDED`
(`KMS-400085`) told users *"Hire them again to bring them back"*, which has **never worked** — `hire`
refuses anyone whose user id already carries a staff profile. T-014 is the way back that sentence was
promising.

**`V105` was allocated to T-014 conditionally and is unused.** No file was ever written for it, wave
7b needed no migration, and Flyway does not care about a gap. It is recorded here so the next person
allocating a version does not assume it shipped; the highest migration on disk is `V104` and the next
free number is `V106`.

**Four further readers were filed and are not built** — T-070 (a donor's own charge list shows a
struck charge as an ordinary one), T-071 (a credit note settles a variance and the variance goes on
showing the full discrepancy), T-072 (a struck gift becomes a permanent unclearable reconciliation
mismatch) and T-073 (two readers that need a ruling rather than a patch) — plus T-074, a
`WISHLIST_SPONSORSHIP_CONVERTED` message sent to a donor whose gift was diverted because an item was
"full", which is never retracted when the gift that filled the item is later struck. The capture path
behaves correctly going forward; what is unaddressed is conversions already made on the strength of a
gift since voided, and the honest repair means telling a donor their gift is moving back — the same
family of product decision as T-069's deferred half.

**None of this has been seen working by Rajeev, and none of it was exercised on staging after the
deploy, on purpose.** Voiding an invoice or a gift writes durable corrections and reversing a payment
moves money records, all on the data he is about to test. The deploy was confirmed by revision,
digest, health and Flyway and nothing else was pressed.

**Released 2026-09-08 as `36d62b3`, deployed on 2026-09-08 alongside `7bf8f6a`** — api
`kms-staging-api-00125-97l → 00126-j5x`, web `00114-8p2 → 00115-brf`, worker `00108-w84 → 00109-2rx`,
all three digests moved. Flyway went **102 → 103 → 104** in the new revision's own boot log, both
migrations applied in 399ms. **The commit sat undeployed for a day**: CI run `34249994747` failed the
frontend job on a race in a test that had nothing to do with any of this work, and the release stopped
rather than re-running. That repair is T-075, recorded under Build & tooling, and it is why the
deployed image carries three waves instead of one.

### 2026-09-08 — An order can be raised by hand, the shopping list can be added to, and two numbers that were confidently wrong stop being wrong (docket B1 and B3, decision D-7, tasks T-026, T-027, T-060, T-061)

**A one-off purchase order can be raised from the screen, vendor first (T-026, docket B1, shape ruled
by D-7).** `POST /api/v1/purchase-orders` has existed all along and `api.createPurchaseOrder` wrapped
it with **zero callers**; `/orders` had a header with no action and an empty state that said *"or
create one directly"* with nothing behind it. It is now two screens, in the order D-7 ruled:
`/orders/new` asks the single question — which vendor — with *Add a vendor* beside the picker, and
`/orders/new/lines` takes the lines. Routing out to `/vendors/new` costs nothing precisely because
nothing has been entered yet, which is D-7's own argument and the reason the vendor is asked first.

**It invents no mechanism, which was the binding constraint (D-3).** `FocusScreen`, `ButtonLink` in
the two shapes already on `/vendors`, a native `<select>` picking a related entity the way the
invoice form does, and the hand-rolled `?added=` flash idiom copied from `/vendors/new` → `/vendors`
— including its `useRef` guard, which is not decoration: without it a new router object on each
render turns the effect into a loop, and the negative control reproduced exactly that, thirteen tests
never finishing, killed at 180 seconds with the worker at 104% CPU and memory still climbing.

Three server rules the form respects rather than discovers: the picker offers **active vendors only**,
because `requireVendor` checks only that the vendor exists and would happily accept a dropped one;
a needed-by date earlier than today is refused readably (`KMS-400014`) rather than by a 400 from the
server; and `?vendor=` is **re-resolved against the active list**, so a hand-edited or stale URL lands
on *"No vendor chosen"* with a way back rather than on a form that will fail at submit. An ingredient
line and a described line (T-024) can sit on the same order.

**One half of D-7 is deliberately not built, and it is now T-067.** D-7 opens *"route out to
`/vendors/new` and return with the vendor selected"*. Leaving is built and costs nothing; **returning
preselected is not** — `/vendors/new` ends with an unconditional `router.push("/vendors?added=…")`, a
file this task was forbidden. The new vendor **is** in the picker on return, merely not chosen for
you. D-7's rationale also rejects the obvious way to get the rest (a general `returnTo` plus a
`sessionStorage` draft) without saying what should happen instead; two readers have now tripped on
that gap, and it is written up for Rajeev rather than settled here, because `DECISIONS.md` is his.

**A line can be added to the shopping list by hand (T-027, docket B3).** The list could be
regenerated and its existing rows edited, but a cook who could see that something was missing had no
way to say so. `POST /api/v1/shopping-list`, behind `MANAGE_PURCHASE_ORDERS` like its three
neighbours, plus an ingredient picker on `/shopping-list`, which had no picker of any kind — the
vendor cell was read-only text.

**The one thing this had to get right is the `edited` flag, and it is the difference between a
feature and a disappearance.** The nightly regenerator deletes every row where `edited = false`, so a
hand-added line survives the night **if and only if** it is written `edited = true`. The defining test
asserts that against a real regeneration rather than against the column, and the negative control
made the point better than the test does: patched back to `false`, the line was **gone after the
regeneration ran** — `$.length()` expected 2, was 1. That is the 3am disappearance on demand.

**A duplicate is refused rather than silently overwritten.** `shopping_list_lines` is unique on
`(tenant_id, ingredient_id)`, so adding something already listed is a duplicate and not a second row,
and it answers **`KMS-400131`** — *"That's already on the shopping list. Change the quantity on the
line that's there."* It is implemented as `ON CONFLICT … DO NOTHING` with a zero-rows check, so two
cooks adding the same thing at the same moment get one line and one readable refusal instead of a
constraint violation. Quantity is checked positive and the unit is the ingredient's own canonical
unit — the client cannot choose one, which is how a list ends up asking for five litres of rice. **No
migration:** the unique index and the `edited` column were both already there, which is the whole
reason this was cheap.

**A described line stops dragging a vendor's fill rate down for ever (T-060).** `countLines` counted
every purchase-order line as *ordered* and joined goods receipts for *accepted*. A described line —
four plastic stools, two extension cords, something the catalogue has never heard of — is orderable
and payable but **can never be received**: `ReceivingService` refuses a receipt line against one
(`KMS-400129`) and the NOT NULL on `goods_receipt_lines.ingredient_id` would refuse it after that. So
its accepted quantity was not unknown; it was permanently, structurally zero, and every such line was
a zero-fill entry that never cleared. Buy four stools from a wholesaler and their delivery
performance falls for good, on a report whose only purpose is to be a judgement about a supplier.

`AND pol.ingredient_id IS NOT NULL` — one clause, and **the ruling behind it is written into the
method** rather than implied, so the next reader does not remove it as dead weight. The boundary is
deliberate: an order of nothing but described lines contributes no judged lines, so a vendor with no
other business in the period shows a **blank** fill rate beside a lines-judged count of zero, not 0%.
That is the same choice the class already makes for an order with no needed-by date.

**What T-060 did not fix, and it is filed rather than forgotten (T-066).** The *on-time* half of the
same report inherits a worse version of the same defect, and it is not a one-clause fix because it
turns on a product question nobody has answered: an order of nothing but described lines can never be
closed, so it is late for ever. T-060 pinned today's behaviour in a test so the answer is visible
whenever it is given. **T-066 is blocked on a decision and is deliberately unscheduled.**

**The operator's temple list stops saying every temple has nobody in it (T-061, migration V102).**
`/tenants` and `/tenants/{id}` both showed **People with accounts: 0**, for every temple, always, and
could never have shown anything else. Three real accounts at one staging temple — an admin, a
kitchen-staff and a volunteer, all three answering `/whoami` with that temple's id — and the list
still said 0.

**Nothing was broken. Row-Level Security was doing precisely its job**, and that is what made this
expensive. The controller counted with a plain subselect over `users`, which is tenant-owned and
carries `FORCE ROW LEVEL SECURITY`; a platform operator is tenantless, so the policy's first clause
is `tenant_id = NULL` and its second matches only the operator's own row. Every row was filtered out
and `count(*)` returned a confident, silent **0**. CLAUDE.md describes that `NULLIF(…, '')` idiom as
failing *"closed quietly instead of raising"* — and an aggregate over an RLS-filtered table degrades
not to an error but to a **plausible wrong number**, which is a shape worth remembering wherever else
we count across tenants.

**The fix works inside RLS rather than around it.** `tenant_user_count(uuid)` — `SECURITY DEFINER`
with a pinned `search_path`, returning `bigint` — adopts one temple's context transaction-locally,
counts **inside** the policy, and puts the caller's context back before returning. It is the shape
`delete_tenant_cascade` has used since V45, and it lands on the permitted side of **D-13** by
construction rather than by promise: it can only ever answer for the one temple it was asked about,
and it can only ever return a number. No rows, no names. The alternative — `BYPASSRLS` on the
application role — would have undone the guarantee the whole design rests on, for a headcount.

**Two things about it were got wrong first and are recorded because the record is the useful part.**
The task as written prescribed a `SECURITY DEFINER` function *owned by the migration role*, on the
reasoning that an owner is exempt from its own table's policies. **The owner is not exempt** — that is
what FORCE means, and V1's own header says so — so that function would have returned the same
confident 0. It was caught by measurement rather than by argument, and `OperatorUserCountIT` now
**builds the naive function as the migration role and watches it answer 0** for a temple with three
members, so the wrong idea cannot come back quietly. Second, `delete_tenant_cascade` never restores
the context it adopts, because it is a destructive one-shot at the end of a request. This function is
called **once per row of the operator's list**, so leaving `app.tenant_id` set would have the
operator's own request carry on holding a tenant context it must never have — the same silent
wrongness pointed the other way. The caller's value is saved and restored, restored as `''` rather
than NULL because `''` is what the rest of the system means by "no tenant", and the restore is
asserted rather than trusted. `EXECUTE` is revoked from `PUBLIC` and granted to `kms_app` by name;
who may *see* the answer is still decided by `MANAGE_TENANTS` above it, because every request arrives
on the same connection and the database cannot tell an operator from a cook.

**T-061 is settled from the far side; the other three are not.** On staging, `/api/v1/tenants` and
`/tenants/{id}` now both report **`user_count: 13`** for the temple that read **0** this morning, and
the number was corroborated through a path that does not touch the new function at all — the temple
admin's own `/api/v1/users` returns exactly 13 rows. The deployed database's boot log shows Flyway
going `101 → 102`, and the function's ACL reads
`{kms_migration=X/kms_migration,kms_app=X/kms_migration}`: explicit, no PUBLIC entry, `kms_app` by
name — so both halves of V102's guarded grant ran rather than being skipped.

**Not seen working by Rajeev.** No screen was driven. Nobody has raised an order through the two new
screens, added a line to the shopping list, or looked at a fill-rate cell, and the operator's temple
list was read through the API rather than looked at. That pass is his, and the items stay open until
he has made it.

### 2026-09-08 — A vendor you walk into needs no phone number, and a picked coordinate stops arriving with fifteen digits (decision D-2, tasks T-025, T-059)

**Ruled by Rajeev (D-2):** the temple buys from shops it walks into. A hardware shop sells it four
brooms across the counter, and there is no WhatsApp number, no order to send and nobody to message.
Until now the vendor record refused to exist without one.

**`vendors.phone` comes off `NOT NULL` (V101), and the E.164 check is rewritten rather than dropped.**
Relaxing *"a number is required"* must not quietly become *"any text is a number"* — those are two
rules and only the first has run out — so the constraint now reads `phone IS NULL OR phone ~ E.164`.
SQL would have given the null case for free (a CHECK evaluating to NULL is satisfied), and the branch
is spelled out anyway so that somebody reading `\d vendors` in two years sees the rule the table keeps
instead of having to recall three-valued logic. `@NotBlank` comes off both request DTOs; `@Pattern`
stays. The migration touches no rows and says why it does not need a per-tenant loop: every existing
vendor has a number, and `DROP NOT NULL` / `ADD CONSTRAINT` are DDL run as the table owner, so the
validation genuinely checks every tenant's rows rather than silently checking none of them.

**The refusal moves to where the reason for it lives.** The column was `NOT NULL` for one stated
purpose — V24's own header says the phone *is* the WhatsApp destination — so sending a purchase order
is what refuses now, with **`KMS-400130`**: *"This vendor has no phone number to send to. Download the
order and hand it over, or add a number to the vendor."* It refuses **above** the DRAFT → SENT
transition, so the order is never touched. What it replaces was worse than a missing check: the number
was read *after* the transition, concatenated into a recipient label reading `Vendor null`, and
rejected a moment later by a generic validation error about a missing contact address. The transaction
rolled the transition back, so nothing was corrupted — but the person got a meaningless 400 for an
entirely sensible thing to want. `guardRate` moved above the transition with it, so that *everything
that can refuse, refuses before anything changes* is true rather than nearly true.

**`vendors.whatsapp_reachable` is untouched and is not the same fact.** That flag is cleared when a
send *fails*, and it means "we tried a number and it bounced". A phoneless vendor reads `true` on it
like any other new row. The two must not be conflated: one is a number that did not work, the other is
no number at all.

**A described purchase-order line stops printing as the literal `null` in a vendor's WhatsApp
message.** `summarize()` read `line.ingredientName()` directly, which T-024 made nullable a release
earlier, so an order carrying *"4 plastic stools"* would have reached the vendor as `Rice, null,
Sugar`. It reads `PurchaseOrderLineView::subject` now — the accessor T-024 added for exactly this — so
the line names whatever the line is about. It does not fail a compile, which is why it needed finding
rather than waiting to be reported.

**The vendor audit trail is rebuilt from the stored row.** Both the before and the after snapshot are
now read back through `findById` rather than assembled from the request, so the trail cannot agree with
the caller by construction; and it is a `LinkedHashMap` rather than `Map.of`, which throws on a null
value and would have turned "this vendor has no number" — the entire point of the change — into a 500
on creation and again on every later edit. A cleared number belongs in the trail: it is exactly the
kind of change somebody reads an audit log to find.

**Three vendor screens follow.** The Phone field stops being required on both forms and carries a hint
in its focusable `i` — *"Only needed to send orders on WhatsApp. Leave it blank for a shop you walk
into."* — because a box that silently stops being required explains nothing; and `/vendors` renders a
missing number as an em-dash rather than as an empty cell, which reads as a rendering fault rather than
as a fact about the supplier. `VendorView.phone` is typed `string | null` and not `phone?:` in the
client, deliberately: an optional property lets a spread omit it silently and cannot be told from an
absent key by a test.

**Separately, a coordinate picked from Google is cut to six decimals** where the reply becomes a
`Coordinates`, in both providers behind the port (task T-059, found by Rajeev pressing the picker on
`/tenants/new`). Places answers with the shortest decimal that names its own double, which for a great
many places is seventeen significant digits — ISKCON Mysuru filled the latitude box with fifteen — and
that number is then shown to an operator under the words *is this the right place?* A person cannot
check what they cannot read. Six is not a preference: `tenants.latitude` has been `NUMERIC(9,6)` since
V1 and `meal_plans.delivery_latitude` since V88, so six decimals is what the database was always going
to keep, and the cut makes the number an operator confirms and the number a row holds the same number.
The sixth decimal of a degree is about eleven centimetres; the only machines reading these are the
Vaishnava calendar, which wants a sunrise, and the Routes call, which wants a street. It rounds rather
than formats, so a value that was already short stays short, and hands back anything non-finite
untouched, because neither provider is allowed to raise.

**What is not done.** None of this has been seen working by a human. Vendors created before V101 all
still have numbers, so nothing changes for them. The `KMS-400130` refusal has a live fixture waiting on
staging — `PO-2026-0030` carries a described line and a vendor to send to — and pressing *Send on
WhatsApp* is deliberately left for Rajeev, because that is an outward-facing send. The Phone hint's
wording and the `guardRate` move are both his to confirm or overturn.

### 2026-09-08 — The temple can buy things that are not food: supplies are one flag on the catalogue, and a purchase-order line can name something the catalogue has never heard of (decision D-1, tasks T-023, T-024)

**Ruled by Rajeev, 2026-09-07:** buying non-food is a real need — LPG, single-use plates and cups,
cleaning and dishwashing supplies, first aid kits, hand soap, *"and much much more"* — and it is two
different things wearing one name. Both halves are built.

**Consumable supplies are a flag, not a second catalogue.** LPG, leaf plates, dishwashing liquid and
hand soap are bought from a vendor by weight or count, received, stored, used up and wanted back when
they run low. That is the ingredient lifecycle item for item, so `ingredients.is_supply` (`V99`) is
the whole of the schema change and nothing downstream moves: inventory, stock movements, low-stock
alerts, receiving, vendor supplies and PO lines all still key on `ingredient_id`. D-1 rejected a
parallel `supply_items` table with a stock ledger of its own, in those words — it duplicates the
entire inventory chain to express a difference that is one boolean.

**Exactly one picker filters, and the guard is not the picker.** The recipe ingredient picker hides
supplies, because a mop is not an ingredient of anything. Every other picker — inventory, ingredient
requests, purchase orders, in-kind donations, a vendor's supply list — keeps showing them, because
that is the whole point of the flag: a temple orders and stocks its leaf plates through the machinery
it already has. `RecipeService` refuses a supply on a recipe line with **`KMS-400127`** as well as the
picker hiding it, because a raw POST never goes through a picker. Without that refusal the server
saves the recipe with the leaf plates on it and answers `201`, which is how it was proved rather than
asserted.

**The catalogue screen grew a Type column, and only the exception is badged.** Food is the
overwhelming majority of any catalogue and badging all of it would say nothing. The flag is edited
through Edit rather than as a one-click toggle, unlike Ekadashi beside it: `supply` has no endpoint of
its own and goes up with the name and the category, so a one-click toggle would have sent the whole
row while looking like it sent one bit. The supply checkbox sits **above** the Ekadashi one on the
form — what a thing *is* comes before what a rule says about it.

**There is no backfill and no per-tenant loop in `V99`, and that is a decision.** Migrations here run
unprivileged and under RLS, so DML has to adopt each tenant in turn — `V97` and `V98` both do.
`ADD COLUMN … NOT NULL DEFAULT false` is DDL run as the table owner and PostgreSQL fills every
existing row itself, catalogue-only, so there is no backfill to test. The task's acceptance criterion
asked for a test that one ran; it was declined as vacuous and the fact it was reaching for asserted
instead — every pre-column row reads as food, **including a row in a tenant nobody was adopted into**,
which is precisely the row a per-tenant DML backfill would have missed.

**One-off durables want the opposite, and got it.** Four plastic stools from a furniture store and two
extension cords from an electrical one should invent nothing in `ingredients` and land nothing in
stock. `purchase_order_lines.ingredient_id` becomes nullable with a `description` beside it and a
CHECK that exactly one of the two is present (`V100`, `po_lines_has_exactly_one_subject`). The
constraint is a strict XOR rather than "a description may sit beside an ingredient as a note", because
`ingredient_id IS NULL` is the discriminator six separate consumers now branch on, and a column that
is sometimes a subject and sometimes a footnote gives none of them a question they can ask. A blank
description is refused by the database, not just trimmed by the application.

**The governing rule: a described line is orderable and payable, and never receivable.**
`goods_receipt_lines.ingredient_id`, `stock_movements.ingredient_id` and `vendor_supplies.ingredient_id`
all stay `NOT NULL` and were deliberately not relaxed — the store room counts things, and a stool has
no batch, no expiry, no on-hand quantity and no reorder point. Receiving an order that carries one
skips that line visibly, says so on the trail (`DESCRIBED_LINES_NOT_STOCKED`), and refuses a receipt
that tries to take it into stock with **`KMS-400129`**. A line naming both subjects or neither is
refused with **`KMS-400128`**, and the order is refused whole rather than written and repaired.

**The column was the small half. Seven consumers read a PO line, and they failed in different ways —
one of them silently, which was the dangerous one.** The PO detail query was an `INNER JOIN
ingredients`: a described line would have **vanished from the order screen with no error at all**. It
is a `LEFT JOIN` now ordering on `COALESCE(i.name, l.description)`, and the test for it fails without
the change rather than merely passing with it. The unit-family check skips a line with no ingredient
instead of throwing `RESOURCE_NOT_FOUND` on a null id. The printed vendor sheet reads the line's
subject, which fixes both an NPE in the Kannada glossary path **and** a quieter sibling nobody had
noticed: on the English path a null name escapes to `""`, so a vendor would have been handed a sheet
showing a quantity, a price and a blank where the item should be.

**The shopping list's guard turned out to prevent two defects, not one.** Grouping outstanding
quantities by `ingredient_id` collapses every described line into one bogus bucket — stools added to
extension cords in base units. One step further on, the regeneration ends with
`DELETE … WHERE ingredient_id NOT IN (…)`, and SQL's `NOT IN` is never true when the list contains a
null, so **the delete would have silently matched nothing and stale suggestions would have survived
regeneration for ever**, on every tenant with a described line on a live order. An `IS NOT NULL` guard
closes both.

**The giving page counts described spend rather than hiding it, decided visibly.** Every figure that
screen shows is a percentage of a total the query itself computes, so excluding ₹4,000 of furniture
leaves no visible gap — it silently inflates every remaining slice. On a page whose entire job is to
tell a devotee honestly where their donation went, that is the one thing it must not do. Described
spend lands in an **Other supplies** bucket, deliberately not the existing "Everything else", which
already means the fourth-and-below categories.

**A described line can be created from the UI, and this is Rajeev's to accept or cut.** There is no
purchase-order *creation* screen anywhere in the application, so `AddLine` on the order detail page
was the only surface on which a described line could exist at all; without it the migration, the seven
repaired consumers, two error codes and a database constraint would have shipped behind no way to
reach any of it — which is the shape this batch deleted three waves ago as dead code that reads as a
feature (T-040). It is two separate controls, a catalogue picker and a description box with its own
*Add described line* button, so a line naming both — which the server refuses — cannot be built from
the form by accident. It is new UI on a screen he has not seen; the clean removal is one control block
and two tests, and the record change and the other six consumers do not depend on it.

**Two things found at the contract boundary and reported rather than reached into.**
`VendorPerformanceService.countLines` counts every PO line as ordered and joins receipt lines for
accepted, so a described line is a permanent zero-fill on that vendor's scorecard — buy four stools
from a wholesaler and their fill rate drops for ever. It is silent, it fails no existing test, and it
is **T-060**, queued rather than fixed here, because `vendor/` was another task's file this wave.
`PurchaseOrderDeliveryService.summarize` joins line names through `Collectors.joining`, which renders
a null as the literal four characters `null` — so a described line would put **"null" into the
WhatsApp message a vendor receives**. It was confirmed by running it, not by reading the javadoc, and
it is T-025's in wave 5-2, now one call away from a fix: `PurchaseOrderLineView.subject()` was added
by this wave for exactly that.

**Not done, and said plainly.** **Nothing here has been seen working by anybody.** The Type column,
the supply checkbox on both ingredient forms, the described-line control on an order, the receiving
table's un-receivable row and the printed sheet carrying a described line all want a human pass on
staging. "A supply goes onto a purchase order exactly as food can" is verified by reading and by the
property that makes all seven pickers work — `GET /ingredients` returns supplies — rather than by a
test of the PO picker itself, which belonged to the other task in the wave: **somebody should confirm
the PO picker offers supplies.** The giving page's "Other supplies" bucket has no test of its own. And
the React key collision on the draft line table is fixed, but the brief's framing of it was stronger
than what could be reproduced: on today's code both rows render correctly and the only deterministic
signal is React's own duplicate-key warning, which the test now asserts.

### 2026-09-08 — OpenStreetMap comes out of the product entirely, provisioning picks a real place instead of guessing at a string, and the planner gets its way back to today (decision D-19, tasks T-053, T-054, T-049)

**Ruled by Rajeev, 2026-09-08:** *"Remove any traces of Nominatim AND/OR OpenStreetMap. We dont care if
its free but doesnt do what we want. **Paying for a quality service should NEVER be a consideration.**"*
He restated the second sentence to be certain it was taken as a standing principle rather than a remark
about maps: *"It is THE DEFAULT answer."* So **"it is free" is not an argument that may appear in a
recommendation on this project.** If a free option is genuinely better, say why on its merits and do not
mention the price. That sentence is the reason the rest of this entry exists, and it outlives the maps.

**It was measured before it went, against the real temple address on staging.** The full street address
— *"No 1, 3rd Main, Samvrudhi Enclave, Kumaraswamy Layout, Uttarahalli, Bengaluru - 560111"* — returned
**nothing**: OpenStreetMap has no street-level data there. Cutting it back to the locality resolved, to
a **centroid about 600 m from the building**. Immaterial for a calendar; wrong for anything that points
at a door.

**Nominatim is deleted, not switched off.** The provider class, its configuration block, its
country-code and User-Agent settings, and every OpenStreetMap attribution note written for its usage
policy are gone. `GoogleGeocodingProvider` takes its place behind the same `GeocodingProvider` port, on
the same Maps key the other three Google services already share — so the deployment gained a variable
and not a vendor. **The port and `NoGeocodingProvider` both survive**: with a real implementation behind
it, `none` is a named off-switch of the same shape as `NoPlaceSuggestionProvider`, not a null port.

**Three things in the new provider are there because of a trap, and are worth knowing about.** A 200
from the Geocoding API is **not** a success — unlike the Places API, it answers almost everything with
HTTP 200 and puts the verdict in a `status` field, so `REQUEST_DENIED` for a key that is not permitted
looks green in every metric. `status` is therefore checked before anything is read, because Jackson
answers `0.0` for an absent node and `0, 0` is a real place in the Gulf of Guinea that would have been
written onto a temple. India is a **restriction** (`components=country:in`), not a ranking hint, so a
Bengaluru in Texas is not returned rather than merely ranked lower. And answers are cached — bounded,
and **expiring**, because Google's terms permit temporary caching for performance and the free service
this replaces treated a hit as permanent, which its licence allowed and this one does not.

**Provisioning stops geocoding a typed string at all.** `/tenants/new` now uses the same Google Places
autocomplete picker the delivery-address field has used since Epic 4 — the operator picks the actual
place, which removes the 600 m by construction rather than by a better geocoder. The typed
latitude/longitude boxes stay as the fallback and the confirm step stays. `AddressLookup.tsx` is
retired. `PlacesController`'s three endpoints widen from `MANAGE_MEAL_PLANS` to
`hasAnyAuthority('MANAGE_MEAL_PLANS','MANAGE_TENANTS')`, because the operator provisioning a temple is
not a meal planner; no new permission was needed and no new grant was made.

**`GeocodingController`, `GeocodedAddressView` and the client's `geocodeAddress` are deleted, and that
was a decision rather than a tidy-up.** Once provisioning picks a place, nothing calls them — the
devotee temple search calls the *provider* directly and never the endpoint. It follows T-040's
precedent, which was Rajeev's own instinct: **an endpoint nothing calls is not a spare part, it is a
feature that isn't one**, and the next person planning work reads it as capability.

**`PlacesController` had no test of any kind before this**, anywhere, and D-19 was about to make it
load-bearing for provisioning. It has twelve now, against a loopback server serving canned JSON —
hermetic, and still covering the parse. The mutation that mattered on the Nominatim work was reproduced
here: a confirm step that echoes its own input back instead of the server's answer looks like a
confirmation while confirming nothing.

**And the planner has a way back to today** (`docs/OUTSTANDING_BUILD_LIST.md` N2). This is the fourth
time that navigation has been reported broken, and the fix was explicitly *not* a fifth Today button.
The control went into the shared `PeriodNav` that the planner, the calendar and three report screens
all use, as an optional prop; the calendar's own header button is **deleted** in favour of it, so there
is one implementation and it cannot diverge again. The three report screens opt out by leaving the prop
off rather than by passing a flag. One thing the plan did not know: **the calendar keeps two cursors**,
the period it steps and the day it has open, and wiring the button's inertness to the first alone would
have disabled the calendar's Today on every day of the current month somebody clicked. A second
optional prop carries it, and no acceptance criterion had asked for either.

**What is not done, and it is the whole risk in this entry.** **No real Google geocoding call has ever
been made by this code.** The provider cannot be exercised without a live key, so the first one happens
on staging. A key whose API restrictions do not include Geocoding fails **closed and quietly**: the
provider returns no result, the devotee search falls back to matching temple names, and nothing on any
screen says why — the only symptom is `Geocoding … answered REQUEST_DENIED` in the api log. The
mis-sequencing risk is bounded in the other direction and was proved, not assumed: `google` with no key
at all loads cleanly and degrades to today's behaviour, while the **old** value with the new code
leaves no `GeocodingProvider` bean at all and the api and worker do not start. That is why the wave
shipped as one commit and why the deploy set both services to `none` before the image moved.

**Also not done: none of it has been seen working by a person.** `/tenants/new` wants an operator to
type an address and watch suggestions appear, pick one, and confirm the pin. The planner's Today wants
two days forward and one press — the behaviour is tested and the *appearance* is not.

### 2026-09-08 — The permission that gates the Ekadashi flag stops being named for a feature that was deleted (decision D-21, task T-056)

**Ruled by Rajeev, 2026-09-08:** *"Rename it to MANAGE_DIETARY_POLICY."* `MANAGE_SATTVIC_POLICY`
survived D-18 for a good reason — it is what gates the **Ekadashi** flag, so the obvious tidy would
have taken a surviving rule out with the dead one — and that left the product with a permission
**named for a feature that no longer exists**, guarding a different rule, across five live sites.
`RolePermissions.java` is meant to read as a document, and a document that names the wrong thing is
worse than one that is merely terse. `MANAGE_DIETARY_POLICY` is the generalisation that lasts:
Ekadashi is the only dietary restriction the product now enforces, and a second one would sit under
the same permission rather than needing a third name.

**Four of the five sites are compiler-checked and the fifth is a string, which is the whole risk.**
The enum constant, the grant in `RolePermissions`, the `Permission.MANAGE_DIETARY_POLICY` reference
in `IngredientService` and a private helper renamed with them all fail the build if missed.
`IngredientController` carries `@PreAuthorize("hasAuthority('MANAGE_SATTVIC_POLICY')")` — **a string
literal inside an annotation**. Rename the enum and leave that string and the code compiles, deploys,
and **403s for everyone including Temple Admins**, because it names a permission nobody holds. The
sweep for it was done by inventorying *every* authority string in the backend rather than grepping
the known name, so a site spelling it differently would also have surfaced; the count for the old
name was exactly one, and no frontend code names the authority at all.

**It is a rename and not a migration, established from the far side of the boundary rather than
assumed.** Authorities are built at request time from `permission.name()` and what is persisted is
`users.role`, so no row anywhere stores a permission name. All 21 lines across 11 migrations that
mention a `Permission` were inspected and every one is a `--` comment or `COMMENT ON` text — never a
column, an `INSERT` or a `CHECK`. The two in `V10__ingredients.sql` are left exactly as they are: a
migration comment that has aged is history, not a defect.

**Proved by a request, not by a green compile.** A rename that builds is not a rename that works, so
the criterion was a Temple Admin calling `PATCH /ingredients/{id}/ekadashi-flag` and not getting a
403. The negative control patched **only** the annotation string back to the old name, left enum,
grant and service correct, and watched `IngredientIT` fail with `Status expected:<204> but was:<403>`
— the trap reproduced on demand, then restored through an `EXIT` trap and `cmp`-verified byte for
byte. Eight of the nine tests still passed under the broken string, and that is not a hole: the ones
that assert a **denial** pass vacuously, because a permission nobody holds denies everybody. **The
admin's 204 is the only assertion in the codebase that distinguishes a working authority string from
a dead one**, and a comment at the annotation now names that test so the next person to touch the
line knows what is holding it up.

**Four apologies come out with it.** `IngredientService`, `CreateIngredientRequest`,
`IngredientController` and `IngredientIT` each carried a note saying the name was *"historical, see
Permission"*. Earlier builders found the misnomer and documented it instead of fixing it — which is
why it was findable at all, and good discipline — but a codebase should not go on explaining a
problem that no longer exists.

**Not done.** Nobody has clicked it. The failure mode of this change is a silent 403 on one screen,
so it is worth **one pass on staging: set an ingredient's Ekadashi flag as a Temple Admin** and see
it take. 1763 backend tests pass, unchanged from the wave before — correct rather than suspicious,
since this renames a constant and adds no test.

### 2026-09-08 — The sattvic flag is deleted, a new temple starts with an empty catalogue, and the Recipes page says what that costs (decision D-18, tasks T-050 and T-051)

Rajeev traced how ingredients actually reach a temple's catalogue and found the rule was being
applied by accident. There are three ways in and only one of them ever set a dietary flag.
Provisioning seeded eleven rows — onion, garlic, mushroom and egg marked sattvic-prohibited, and
rice, wheat flour, semolina and four dals marked Ekadashi-prohibited. A person adding an ingredient
by hand set both flags deliberately. Recipe import, which is the bulk path, created whatever a
recipe named and the temple lacked, with both flags false. So rice was flagged because it was
*seeded* and maida, fine rava, jowar flour and roasted gram flour were not because they were
*imported*: the same rule, the opposite answer, decided by how the ingredient got in. A menu using
rice was stopped on a fast day and one using maida was waved through.

**His ruling: delete the sattvic-prohibited flag entirely, delete the provisioning seed, and warn on
the Recipes page.** The first two are one idea rather than two. The flag existed to mark rows that
only existed because of the flag — a temple kitchen does not stock onion or garlic, so provisioning
inserted them solely so it could tick them forbidden, and they arrived by no other route. Remove the
seed and the column guards nothing; split the two and each half is separately indefensible.

**What is gone.** `ingredients.is_sattvic_prohibited` and `recipes.sattvic_override_reason`, dropped
by `V98`. The enforcement in `RecipeService`, `ShoppingListService` and `RecipeImportService`. The
`PATCH /ingredients/{id}/sattvic-flag` endpoint. The Sattvic column and toggle on `/ingredients`, the
checkbox on the ingredient form, the *Sattvic override reason* field and the *(prohibited)* suffix on
the recipe form, the override badge on `/recipes` and the override line and per-line marker on a
recipe's own page. `KMS-400037` and `KMS-400104` are retired — never reused, reasoning recorded in
`docs/ERROR-CODE-RENUMBER-2026-09-07.md`. The override chain went with the column rather than being
left behind: with no ingredient able to carry the flag, the reason could never be written again, and
what would have remained is a textarea that silently discards what is typed into it, a badge
permanently false and a job-card warning that can never fire.

**Four things the product no longer refuses, and every one of them is the ruling working rather than
a regression.** A recipe naming garlic saves like any other. Garlic below its reorder threshold now
produces a shopping-list line. A library recipe that used to be refused with `KMS-400104` imports
cleanly — accepted because the shared library is the temple's own and should not contain them. The
sattvic-flag endpoint answers `404`: gone, not inert. Each is pinned by a test named for it, which is
the only kind of control a wave that removes a guard can offer — the point is to prove the guard is
*absent*, not that it still works.

**And a new temple now enforces nothing on a fast day until somebody flags things by hand.** That is
the deliberate half. Seven flagged staples among sixty unflagged grains is *worse* than none, because
partial coverage looks like knowledge. What makes the new state honest is a standing warning box on
`/recipes`, above the search, in Rajeev's own words and not to be reworded:

> **Imported ingredients arrive unflagged for Ekadashi**
> A recipe import adds any ingredient this temple doesn’t have, and can’t tell which are restricted
> on a fast day — so it flags none. Set the Ekadashi flag on each yourself, or the meal planner will
> allow them onto an Ekadashi menu.

It is on Recipes rather than on Ingredients, where the flag is actually set, because import is the
act that creates the unflagged rows and this is the screen import starts from. It uses the design
system's `InlineNotice` with `tone="warning"`, which refuses `autoDismiss` by construction — this is
exactly the case that rule was written for, because there is something left in it for the reader to
do.

**Everything Ekadashi survives, deliberately.** The flag, its endpoint, the create-time check, the
planner's fasting-day filter and the seeded recipe categories are all untouched. So does the
permission `MANAGE_SATTVIC_POLICY`, whose name is now historical and says so in place: it gates the
Ekadashi flag, so the obvious tidy would have taken the surviving rule out with the dead one.
Renaming an authority string across its `@PreAuthorize` sites belongs to the permissions review D-11
records, not to this ruling.

> **Annotated 2026-09-08 (decision D-21).** The paragraph above is left exactly as written, because
> it is a record of what wave 4d did and not a live description of the code — the same rule T-055
> applied to `REQUIREMENTS.md`'s §5. What it records has since been overtaken: Rajeev ruled the same
> day that the permission be renamed, and `MANAGE_SATTVIC_POLICY` is now **`MANAGE_DIETARY_POLICY`**.
> The rename did not wait for the permissions review D-11 records. See the D-21 entry at the top of
> this section.

**One incidental change worth declaring, because it is CSS in a backend diff.** Two now-unreachable
`.badge` rules came out of `JobCardTemplate` and `RecipeCardTemplate` with the warning that was their
only caller.

**Not done.** Nobody has seen any of this on a screen. The two tasks are halves of one user-facing
change split by tree rather than by feature, so neither builder could see the whole of it. Worth one
pass on staging over `/recipes`, `/ingredients` and `/ingredients/new` together. **Deployed to staging 2026-09-08**, api revision
`kms-staging-api-00117-9sd` and web `kms-staging-web-00109-swc`, both image digests moved. `V98`
applied there on a schema at v97 and reported the figure nobody can recover afterwards — how many
recipes had been saved past the old block — as **zero**, read out of the rollout log. So the dropped
column took no text with it.

---

### 2026-09-07 — The first verification pass over Rajeev's own review list, and the rule that made it possible (docket `OUTSTANDING_BUILD_LIST.md`)

`docs/OUTSTANDING_BUILD_LIST.md` had carried eighteen items since 23 August, every one marked
**BUILT, unverified**, because the only person allowed to move one was Rajeev. That made him the
bottleneck and let unverified work stack eleven screens deep. It was also written when there was no
way to sign in as most roles; all seventeen UAT accounts work now, so a session can *be* each role
and press the real thing.

**He amended the rule on 2026-09-07: verification is two passes.** The session does the first, marks
the item `DONE — verified <date>` with a line saying what it actually pressed, and **leaves the
item's block in the file** so he has something to test against. He tests after it and reopens
anything missed. The half of the old rule that stands unchanged: nothing is removed because it looks
stale, because a later conversation did something adjacent, or because it cannot be reproduced. The
file goes when he says it goes, and the `CLAUDE.md` banner with it. Both documents were rewritten to
say so.

**The first pass ran the same day and did not come back clean.** Eight items are marked
`DONE — verified`, each with the evidence beneath it: the sidebar's scroll position on login (N1),
the day view's first card (P1), the meal section's structure (P2–P3), the meal's buttons and the 23
job-card languages (P4), the recipe panel that opens over the planner and closes on Escape (P5), the
Edit/Delete order on a recipe (R1), the reorder-threshold field in human words with its unit (I1),
and stock that starts being tracked the moment an in-kind donation of it is recorded (I2).

**Two are only partly verified and both are blocked on the same defect.** T1 and P8 could not be
finished because *Record this meal* did nothing at all on the screen T1 is about — which is how
T-043 below was found. **One is reopened: N2 is not done.** Its root cause is fixed, but the planner
still has no Today control while the calendar has one, so there is no way back from two days forward
except browser back. **Four are left open deliberately as matters of taste** — the ghost button's
resting border, the "Ready by" wording, "Record actuals" as one word or two, and recipe-list density.
Those are his and a session must not close them.

**Not done.** Nothing has been accepted by Rajeev. Eight items were verified by a session and are
waiting for his second pass; the file is unchanged in every other respect and no block was deleted.

---

### 2026-09-07 — An event meal can be recorded again, and a refusal is shown to whoever pressed the button (build list T1 and P8, task T-043)

Found by driving `/planner/catch-up` on staging as Temple Admin, during the verification pass above.
*Record this meal* was pressed four times on the event "Bhagavad Gita Parayanam" and nothing
happened — no success, no error, no visible change. Two separate defects, and the second is what hid
the first.

**The recording never sent the event's name.** The server resolves the meal with the date, the kind
*and* the event name, and it has to: every event of every temple is called "Event", so the date and
the kind alone do not say which preparation is being written down. It answered 404 `KMS-400030` every
time, with a complete message and next step. **The root was the client type, not the call.**
`RecordMealInput` declared four fields and no `eventName`, so TypeScript could never have caught
this — passing the field would have been the type error. The damning detail is that the same
component hands `meal.eventName` to the job card two hundred lines above: the job card knew which
event it was; the recording did not. Because this is the shared recording component, it was every
event meal, from every screen, always — while Breakfast, Lunch and Dinner worked, which is exactly
why it survived. Recording is how stock is drawn, so **an event's ingredients were never consumed**,
the store showed them on hand for ever, and the Today nudge about unrecorded meals could never be
cleared for an event.

`eventName` is now **required and nullable** on the input type rather than optional. Optional is the
property that allowed the omission; an everyday meal now says `null` out loud.

**The refusal was never discarded — it was rendered where nobody was looking.** All three consumer
screens put their error notice at the top, `catch-up` above as many as seven day sections, while the
button that raised it sits far below with no scroll and no focus move. The recording form now shows
its own refusal immediately above its own button, carrying the server's message, its next step and
the code, announced as an alert, in the shape the vendor status dialog already used. No consumer
screen needed changing, so all three get it.

The general lesson is worth keeping: the type agreed with the caller and **both disagreed with the
server**, so `tsc`, the tests and the reviewer's eye all reported green. Nothing in this arrangement
checks the client contract against the server's; a read-only sweep was run beside this wave to do it
by hand, and it produced the two entries below.

**Not done.** Not seen working by a person. Worth two minutes on staging: record an event from
`/planner/catch-up` and confirm both the success and a refusal land where you are looking. One
decision is flagged for Rajeev — the recording refusal is no longer *also* raised to the page banner,
because none of the three screens ever clears that banner and a stale red notice would contradict the
green success after a retry. It is pinned by a test and is a one-line change if both are wanted. Four
further misplaced-feedback cases in those screens are recorded in `docs/work/proof/T-043.md` and
deliberately untouched.

---

### 2026-09-07 — Editing a delivery event no longer re-pins it to the Gulf of Guinea, and the rows already poisoned are unpinned (tasks T-044 and T-048)

The same shape of defect as the one above, one rung worse: **it did not fail, it succeeded and wrote
a wrong pin onto the sheet a driver acts on.** Change nothing but the head count on a delivery event
whose address was picked from the map, save, and its coordinates became `0,0` — a point in the
Atlantic about 600 km south of Accra — while the address, the contact and the serving time all went
on reading correctly. The leave-by time was then computed from there.

Four links, all of which had to go. The meal-plan view never returned the coordinates, so the
composer had nothing to reopen an edit on and rebuilt the picked place with zeroes as placeholders.
It sent them. And `isPlaced()` was `latitude != null && longitude != null` — **it never consulted the
place id, and `0` is not null** — so it short-circuited both Places and the geocoder and wrote the
placeholder down.

`isPlaced()` now requires a place id **and** a coordinate that is not zero, and both halves are
needed. The place id is the semantic fix: the predicate claims somebody chose this address from a
list, and it was never consulting the one field that could support the claim. Refusing zero is the
sentinel fix, needed because a stale browser bundle can still send a real place id beside a
placeholder pair. The asymmetry makes it safe to be blunt: a genuine pick refused here still carries
its place id, so the true coordinates are fetched and the event ends up correctly pinned at the cost
of one API call — while a placeholder accepted here reaches a printed job card.

**V97 repairs the rows already written.** It clears `delivery_latitude`, `delivery_longitude` and
`geocoded_at` wherever both axes are exactly zero, per tenant and under RLS, and **deliberately
leaves `delivery_place_id` standing**: that pairing *is* the repair, because with no coordinates the
row is no longer placed and no longer fresh, so the next read re-resolves it from Places by id,
losslessly. The rejected alternative — clearing only rows with zeroes and *no* place id — would have
cleared the unrecoverable ones and left the recoverable ones wrong, precisely backwards. A row with
one zero axis and one real coordinate is left alone, and that is decided rather than overlooked: zero
is legal on each axis by itself, and nulling such a row would change nothing the application does,
since `isCoordinate()` already treats it as unplaced. Given a tie, do not destroy evidence. Two
assertions pin that decision so a later tidy-up into an `OR` fails the build.

One correction to the record, because three documents had repeated it: the defect did **not** compile
because `mealFacts()` lacked a return-type annotation. A spread exempts *excess* properties from
TypeScript's check, never *missing required* ones — proved by removing both and watching `tsc` still
error. What closes the hole is declaring the pair required-and-nullable on the input type; the
annotation only moves where the error lands, one at the payload builder that can fix it instead of
two at the call sites that cannot. Worth stating because no passing test would ever have found it.

**Not done.** Not seen working by a person; the check is to open a saved delivery event that was
*picked* from the map, change only the head count, save, reopen, and confirm the leave-by line still
reads the same drive. And the irony worth keeping: the method already carried a comment recording the
fix of the *previous* incarnation of this bug, five days old. **A comment recording a fix is not a
test of it.**

---

### 2026-09-07 — Renaming a kind of meal carries the meals with it, and deleting one in use is refused (task T-038, with T-047)

A temple that calls its midday meal something else could rename the kind, and every plan, recorded
meal and linked shift went on holding the old name. The kind is stored as a **name** and not as a
reference — `meal_kinds` is unique on an expression index, which PostgreSQL will not accept as a
foreign-key target — so three text columns quietly disagreed with the settings screen. The rename now
cascades across all three, per tenant and under RLS, only when the name actually changes.

The match is **case-insensitive**, and the reason is an asymmetry nobody had noticed: `shifts.meal_kind`
stores what the caller typed and never passes through the catalogue, while the code that reads it
folds both sides. A shift linked as `"lunch"` against a temple storing `"Lunch"` is a **working row
today**, so an exact-match cascade would have renamed the meals around that shift and stranded it —
reintroducing the exact defect this change exists to fix.

**Deleting a used kind is now refused with `KMS-400126`, which points at the rename instead.** The old
delete was unconditional and its comment claimed the meals kept reading as what they were. They do
not: four read paths resolve the stored name back through the catalogue — the reuse-a-plan preview
walking historical plans, and the job-card language, document and print paths for a meal already
served — so a deletion armed a `KMS-400071` to go off weeks later, on a screen with nothing to do
with settings, naming a kind the temple deliberately removed. A kind nothing has ever used still
deletes. Deactivate-rather-than-delete was considered and is a strict superset available later;
nobody has asked to retire a used kind, and the refusal surfaces that question to the person who has
it rather than guessing.

**And a rename onto a name the temple already has is a typo, not a crash.** It was reaching the user
as `KMS-500001`, *"Something went wrong at our end"*, because the unique-index violation had no catch.
It now answers `KMS-400047`, the same as creating a duplicate always has, caught off the database
rather than pre-checked so two admins renaming at once cannot slip between the check and the write.
Only the `meal_kinds` UPDATE is inside the catch: the cascade after it can raise a duplicate of its
own, which is a different fault and must not be reported as this one.

**V96 corrects V64's column comment**, which this change made false. Comment only — no DDL, no data,
verified by a full `pg_dump` either side on a database replayed to V95. V64's own text cannot be
edited without breaking its Flyway checksum, so V96 says plainly that it supersedes it, and a reader
of V64 will still meet the false sentence there.

**Not done.** Not seen working by a person, and in fact it cannot be yet: the settings screen for meal
kinds does not exist (that is T-005, which was split around this task). Nobody has looked at how a
screen renders either `KMS-400126` on delete or `KMS-400047` on a duplicate rename.

---

### 2026-09-07 — An attempt to change your own access, or to end your own employment, is on the record (tasks T-039 and T-046)

Both refusals were silent. `AuditService.recordSeparately` exists precisely so a record survives the
403 that follows it, and it had two call sites in the whole backend — one of them in a service with
no caller at all. The staff form is the only door a temple role actually changes through, so **a
blocked attempt to raise your own access there left no trace whatsoever**: the only evidence of an
attempted escalation was its absence.

The staff form now writes `ROLE_CHANGE_REJECTED` before it throws, and the identical `KMS-400022`
with the identical detail map after it. The same guard's sibling — an administrator ending their own
employment and revoking their own sign-in, which locks a temple out of itself with nobody left
holding `MANAGE_STAFF` — now writes `STAFF_EMPLOYMENT_END_REJECTED`. Each refusal is filed where a
reader would look for the act it refused: the access one against the user account, as `ROLE_CHANGED`
is; the employment one against the staff record, as `STAFF_EMPLOYMENT_ENDED` is. The whole request is
recorded rather than loose fields, because an attempted resignation and an attempt to take your own
login away are not the same attempt.

**A successful access change now also writes `ROLE_CHANGED` of its own**, beside the `STAFF_UPDATED`
that covers the profile edit. Two events for one request is right here — two acts arrive together,
and somebody filtering the log for privilege changes should not have to find one by reading every
corrected phone number. Without it, the audit screen's *"Role changed"* filter would have matched
nothing for ever once the dead role endpoint went, one commit later.

What proves the property is a **pair** of counts, not one: after the same 403 the log holds zero
`STAFF_UPDATED` and one `ROLE_CHANGE_REJECTED`. Either number alone would also be produced by a
transaction that never rolled back.

**Not done.** Not seen working by a person. On staging: edit a staff member's access and confirm the
entry appears under **Audit log → Action → "Role changed"**; then try to change your own access, and
try to end your own employment, and confirm both refusals appear as *"Role change refused"* and
*"Tried to end their own employment"*. The wording of that second label is a judgement call and a
one-word change if Rajeev prefers another.

---

### 2026-09-07 — The role-change endpoint is deleted: dead code that read as a feature (task T-040)

`PATCH /api/v1/users/{id}/role`, its service, its request type and its client wrapper are gone. No
screen ever called it. It was written as the seed of user management before hiring existed; hiring
then became the only act that grants a temple role, and the seed was never pulled up — so it sat as a
second door onto a decision the staff register owns, and being unused, a door nobody was watching.

Saying that plainly matters, because the machinery was convincing: four documented guards, full
before/after auditing, an integration test per guard, and a class javadoc calling itself *the exemplar
of before/after auditing*. **That appearance is what produced docket item B10** — a task raised to
build a screen for an endpoint nobody was calling. Leaving a second one behind while naming the
pattern would have been worse than the first.

Nothing about the protections is lost, and the ordering that guarded that is worth recording: the only
test in the repo asserting that a refused role change is audited lived in the file this task deletes,
so the property was rebuilt on the staff path **first**, and this task's evidence is that assertion
passing on a tree with the deletion already in it. The cross-tenant guard was RLS all along. The guard
against promoting anybody to `SUPER_ADMIN` now holds **structurally** rather than by a check:
`staff/SystemAccess` has three constants and cannot express the value the guard existed to refuse. The
protection did not go; it changed kind.

`KMS-400023 CANNOT_ASSIGN_SUPER_ADMIN` was thrown by that one guard and now has no writer anywhere.
**It is retired, never to be reallocated**, on **D-9**'s precedent rather than as a new decision: an
error code that exists and can never be raised is a lie in a catalogue whose entire value is that a
number means one thing for ever, and it is exactly what somebody quotes off an old screenshot.
Recorded beside `KMS-400018` in `docs/ERROR-CODE-RENUMBER-2026-09-07.md`.

**Not done.** There is no user-facing surface here — that is the whole finding — so there was nothing
to click. The one useful human check on staging is negative and cheap: no screen has lost a control.
The other two endpoints in `user/` were checked and both are live.

---

### 2026-09-07 — The temple correction screen narrows to name, address and 80G, and the calendar rebuild goes with it (decision D-17, task T-041)

**D-17**, ruled by Rajeev field by field over the screen shipped the day before. Latitude, longitude,
timezone and currency are shown and no longer offered: a building does not move; a currency changed
after money is recorded shows invoices, payments and donations in a currency they were never in; and
a temple that cannot move cannot change timezone either. They are presented as label-and-value pairs
under *"Fixed when the temple was created"*, the way the temple's own page presents its details, so
what cannot be edited does not read as a field somebody greyed out. **No disabled inputs** — a
disabled control submits nothing, and this endpoint replaces the whole record.

The frozen four are read off the temple the form loaded rather than carried in hidden inputs, and the
reasoning is the better one: a hidden input round-trips latitude through `Number()`, so **a lost value
arrives as `0` rather than as an error** and silently relocates a temple — which is the defect found
independently in the delivery pin above — and a hidden field is editable from devtools, which is
exactly the offer this screen has just stopped making.

**The freeze is enforced at the service, not only on the screen.** Narrowing the form alone would have
left an operator able to `PATCH` a new timezone by hand while `calendar_days` kept the tithi, Ekadashi
dates and sunrise times of the old zone with nothing saying so — strictly worse than before, and dead
machinery of exactly the kind the previous entry exists to remove. So the fields are still accepted,
and a value differing from what is stored is refused with a field-level message in the shape
`rejectSlugChange` already had. Every offending field is collected before throwing, so a caller
holding a stale record hears about all four at once. Only then is the calendar re-queue honestly
unreachable, and deleted.

The comparison is `compareTo`, not `equals`. Latitude is `NUMERIC(9,6)`, so the row holds `12.971600`
while the form sends `12.9716`, and `BigDecimal.equals` is scale-sensitive: it would have refused the
operator's own unchanged values and made the screen unsaveable. The test covering it sends a third
scale again.

**The cost, stated rather than hidden:** a provisioning typo in a temple's coordinates can no longer
be corrected through this screen or this API. D-17 rules that an error big enough to move the calendar
is obvious at once, one small enough to miss shifts sunrise by seconds, and a typo caught during
onboarding costs nothing because the temple has no data yet. The reasoning is written into the service
and the page so a reader meets it where they meet the restriction.

**Not done.** Not seen working by a person; what to look at on staging is the *"Fixed when the temple
was created"* block reading sensibly, and that saving a renamed temple still returns to its detail
page. One rough edge recorded rather than fixed: a frozen-field refusal shows the generic error notice
on that screen rather than the field-level message, because the frozen fields no longer have fields to
attach one to. It is unreachable from the UI, since the screen sends back exactly what it loaded, and
the per-field messages are all present in the API response. The section headings and the 80G tick's
move into the first section are the builder's judgement, not D-17's, and are raised for Rajeev.

---

### 2026-09-07 — Provisioning looks a temple's coordinates up from its address (decision D-17, task T-042)

**D-17**, and Rajeev's own words in it: *"If you want to use the back end we have to translate an
address to Lat Long, go for it. That is a VERY handy feature to have."* Adding a temple now offers a
lookup above the two coordinate boxes. Type the address, press it, and the screen shows what came
back — a static map pin where a map key is configured, and the geocoder's own normalised rendering of
the address where one is not — and asks the operator to confirm before anything is filled in.

**The confirm step is the point and not decoration.** Filling the boxes straight from the reply would
trade a typo for a wrong match, which is worse: a typo is visible and a plausible wrong address is
not. A wrong resolved address is as obvious to a human as a wrong pin, and `12.905125` is obvious to
nobody. So the picture is an upgrade to the same question, never a different design, and both boxes
stay typeable throughout — a coordinate the operator would rather type still wins over the one that
was looked up.

A no-match is an ordinary answer: 200 with `found: false`, no error code, the typed fields left
working. Provisioning must not be blocked because a temple's address does not geocode. A lookup that
fails outright reads the same way, deliberately — a failed request must not become an error notice on
a form somebody is halfway through.

Reading the normalised address needed the port to carry it, and that was found by testing the brief's
own claim rather than believing it: `GeocodingProvider.locate()` returned coordinates only, and the
Nominatim implementation read `display_name` and threw it away, so the panel would have shipped
permanently in its weakest state. `describe()` is added as a **default** method beside `locate()`,
which is what keeps the two existing callers and the lambda-shaped test stubs untouched.

The endpoint is behind `MANAGE_TENANTS` and proxied through the API like every other map call here, so
no key reaches a browser bundle. `kms.geocoding.provider` still defaults to `none` and no test sets
it, so nothing in the suite can reach OpenStreetMap by construction; only that property's comment
changed.

~~**Not done, and this one is inert as shipped.**~~ **Struck 2026-09-08: the claim was false and
the way it was reached matters more than the fact.**

It said `GEOCODING_PROVIDER` is unset on staging, so the endpoint answers `found: false` every time
and the screen reads as it did before. **It is set, to `nominatim`, and has been since before wave
4c deployed.** `kms.geocoding.provider: none` is only the default in `application.yml`;
`infra/environment/main.tf:437` hardcodes `GEOCODING_PROVIDER = "nominatim"`, and the live revision
`kms-staging-api-00116-7b4` was confirmed to carry it. The feature was driven in a browser and
works. So T-042 has been live from the moment it deployed, along with the devotee temple-distance
search and the delivery-address geocode behind the travel estimate, and there is no switch for
anybody to throw.

**Corrected even though the provider is being deleted** (D-19 removes Nominatim entirely), because a
false statement about a deployment is exactly what the next session builds on. The error is *reading
one side of a boundary and concluding what the other side does* — the config default was read, the
Cloud Run environment was not — which is the defect shape of this whole batch and has now caught a
builder, a work manager, a release agent and the coordinator. **A default is not a deployment.**

What the provider actually returns, measured before it goes, so nobody re-derives it: the full
street address returns **nothing** from OpenStreetMap, and cutting it back to the locality resolves
about **600 m from the building**, because it hands back a locality centroid rather than a place.
Immaterial for the calendar, wrong for anything that points at a door — and the reason D-19 replaces
it with the Places picker, which has the operator choose the place instead of geocoding a string.

Separately, `STATIC_MAP_PROVIDER` has never been exercised against Google by anything, so
whether a usable map comes back is unknown until a key exists; the screen confirms the resolved
address instead and is fully tested in that state.

---

### 2026-09-07 — An ingredient can be marked Ekadashi-prohibited, from the list and from the create form (task T-045)

Found by the client/server contract sweep run beside this wave. The endpoint has existed and been
audited since the ingredient module was built. What was missing was **every way to reach it**: no
client wrapper, no column on the list, no box on the create form. So the flag could be set nowhere but
the provisioning seed, and any ingredient a temple added afterwards was permanently unmarked — while
`EkadashiPolicy` and the recipe service go on deciding from that column which recipes may be cooked on
a fasting day.

Both controls mirror the sattvic ones exactly rather than inventing a second pattern for the same
shape of decision (**D-3**): the same toggle, the same two words, the same colours, the same
admin-only rule, the same read-only fallback for kitchen staff, and the same checkbox shape on the
form.

`ekadashiProhibited` is **required** on the client types, not optional, and the six fixtures it broke
were fixed rather than routed around. The Java field is a primitive `boolean`, so an absent JSON key
deserialises to `false` — the permissive answer, silently — and optional in TypeScript reproduces that
silence exactly, because `undefined` is falsy the same way. A grain would read as permitted because
nobody said otherwise.

The test for that deliberately does not use `objectContaining`: a missing property and an explicit
`false` read identically to it, and a missing property is precisely what the defect was. It inspects
the payload's own keys and then asserts the value.

**Not done.** Not seen working by a person; the check is to mark an ingredient prohibited from
`/ingredients`, un-mark it, and add a new one with the box ticked. One thing left for Rajeev rather
than taken: the two toggles in a row now share an accessible name, and labelling them properly means
changing the *shipped* sattvic control, which is a deviation from an approved design and his to rule
on.

---

### 2026-09-07 — A temple's profile can be corrected, and 80G approval recorded (docket A1 and A2, task T-008)

`TenantController` had POST, GET, export and DELETE and no PUT or PATCH at all. A temple's name,
address, coordinates, currency, timezone and 80G status were settled once at provisioning, and a
temple provisioned wrongly could only be fixed by deleting it and starting again. Testers provision
temples all day. `PATCH /api/v1/tenants/{id}` now exists behind `MANAGE_TENANTS`, with the operator's
screen at `/tenants/[id]/edit`, reached from the temple's own page.

**80G approval is part of this endpoint and not a separate one.** The flag was written by the
provisioning insert and by nothing else — the only other `UPDATE tenants` statement in the backend
touches `locale` — so a temple that got its 80G certificate after it was set up had no way to say so,
and its receipts stayed wrong. A test goes through the real provisioning endpoint with the flag false,
turns it on through the correction screen's endpoint, then signs in as that temple's admin and reads
it back off the giving page, because that is the row receipts actually consult.

**Changing the timezone re-queues that temple's calendar precompute, and only then.** `calendar_days`
is computed per tenant from the zone, so a zone changed without a rebuild leaves the temple with
tithi and Ekadashi rows computed against the old one while every "today" in the product quietly
disagrees with the panchanga. The re-queue is asserted for that tenant, asserted `never()` for a
second seeded temple, and paired with a test that a correction leaving the zone alone rebuilds
nothing. One limit is documented in the service rather than relied on silently: the precompute's
horizon starts at the first of the current month, so days before it keep what they were computed
with. Rewriting the calendar underneath a meal already planned and served would change the record of
what happened.

**The audit event records what was stored, never what was asked for — and the first run of the tests
proved why that distinction is not pedantry.** The before-state was read from the row and the
after-state built from the request, so latitude rendered `12.971600` on one side (the `NUMERIC(9,6)`
column) and `12.9716` on the other. Every `TENANT_UPDATED` event on that temple would have claimed
its coordinates had moved, in a field nobody had touched. An audit trail with a gap is a trail
somebody knows to distrust; one that lies is a trail that gets believed. Both halves of the event are
now the database's own rendering of the row, and the test asserts that by value.

`TENANT_UPDATED` is its own action rather than `SETTINGS_UPDATED`, which is what a temple admin does
to its own settings; this is the operator changing what a temple *is*, and the timezone and 80G
fields make that a different kind of act — the same reasoning that split `EQUIPMENT_REINSTATED` out
from an ordinary condition change. No migration was needed: `audit_events` constrains `action` only
by `length(action) > 0`.

**`slug` is declared on the request so that it can be refused.** Spring Boot leaves
`FAIL_ON_UNKNOWN_PROPERTIES` off, so an undeclared field would have been dropped in silence while the
caller was told the save had worked. Sending one now gets `KMS-400001` naming the field, and a test
asserts that nothing else in the request was written either.

Per **D-13** this is operator-only, ruled against a recommendation that the fields be split so a
temple admin could correct its own address. The cost is deliberate: a temple cannot fix its own
address and every correction is an operator ticket. What it buys is one auditable answer to who may
change what a temple is, keeping the two genuinely dangerous fields — the zone that rewrites the
calendar, and a legal status no temple should assert about itself — on the operator's side without a
field-by-field permission boundary.

`TenantDetail` now carries `latitude` and `longitude` as **required** fields, which earned their
keep immediately: making them required stopped `tenant-detail.test.tsx` compiling until its fixture
had them, which is exactly the failure an optional pair would have hidden — a form posting a silently
relocated temple.

**Not done.** Not seen working by a person; what a human should check on staging is opening a temple,
pressing *Edit details*, changing the name and ticking 80G, confirming the detail page reads back
"Approved", then changing the timezone and confirming that temple's calendar rebuilds rather than
staying on the old tithi. Two findings recorded rather than fixed, both in
`docs/work/proof/T-008.md`: there is no post-save confirmation banner on `/tenants/[id]`, and
`/tenants/new` and `/tenants/[id]/edit` each hold their own copy of the offered timezones and
currencies with nothing making the two agree. The second is also why the hard-coded-timezone guard in
`design-system.test.ts` gained a third exemption — the edit screen defaults to the temple's own stored
zone and assumes nobody's, so the literal there is one option in a list rather than a zone presumed
of a reader.

---

### 2026-09-07 — A cook's own schedule shows the leave they were given (decision D-16, task T-032)

`/my-schedule` drew the next fortnight from the seven-day template and the per-date exceptions, and
knew nothing about leave. Somebody granted Thursday off still saw Thursday's hours. The screen was
honest about it — T-006 shipped in wave 2 with a muted line reading *"Approved leave is not shown
here."* — but an honest wrong answer is still a wrong answer to the person deciding whether to come
in.

`GET /api/v1/staff/schedule/me` now resolves leave and the screen draws it: a full day reads as the
leave with its label instead of hours, a half day keeps the hours and is marked *"Sick leave, half
day"*, in the same words the manager's week grid uses. The admission line is gone because it is no
longer true.

**The leave is resolved on the server, and that is the whole design.** The endpoint returns the
**dates leave covers**, not the spans it was requested as, from `ScheduleResolver.resolve` — the same
call `weekView` makes for the manager's grid. Mapping spans onto dates in the browser would have been
a second answer sitting beside the server's, and the two would have disagreed the first time the
resolution order changed. An integration test fetches both endpoints for the same person and the same
two dates and compares `leaveId`, `leaveType`, `leaveLabel` and `halfDayLeave` across them, so the
grid and the cook's own schedule cannot drift apart without a test failing.

**The window is stated on the wire rather than assumed.** The server resolves 28 days from the
temple's own today and says so as `leaveFrom`/`leaveTo`; the screen lists 14 and honours the window it
was actually answered across, dropping anything outside it and saying in muted text if the answer
stopped short. Absent or null means *not resolved* — never *no leave*. Only an empty list inside a
stated window means a clear fortnight, and a screen that read the two the same way would tell somebody
they had no leave when nobody had asked. The horizon stayed two numbers on purpose: how much to
display is a decision about reading, how much to answer is a decision about the endpoint, and holding
one number in two languages is how they drift.

Pending leave is not an absence, and there is a test saying so: a request nobody has answered yet
leaves the schedule alone. A screen that emptied itself on request would tell a cook they had a day
off that no one had granted.

**Not done.** Not seen working by a person. The five `ikms.kitchen-staff.*` accounts now exist on
staging and resolve as `KITCHEN_STAFF`, so signing in as a cook is no longer the blocker it was this
morning; what this still needs is somebody on the payroll with **approved leave in the next
fortnight** — one full day and one half day — which is data setup, not access. Two deliberate limits,
both recorded in `docs/work/proof/T-032.md`: leave resolves for active staff only, the same silence
the grid gives a person whose employment has ended; and leave changes how a listed day reads, never
which days are listed, so leave covering somebody's ordinary day off stays off the list.

---

### 2026-09-07 — Registration remembers the credential it made, and resumes at the join (task T-037)

`/register` created the Firebase credential and *then* asked the server to join the temple. When the
join was refused, the account stayed behind with nothing to remove it and nothing to remember it —
so the second press hit `auth/email-already-in-use` and the screen answered **"There is already an
account with that email. Sign in instead."** That is advice which was wrong for exactly this person:
they now had an identity and a membership nowhere, the join was never retried, and the flow was a
dead end recoverable only by accident.

**The credential is now remembered across attempts and the next press resumes at the join.** One
guard in front of the credential step rather than three fixes, because Google, password and phone are
inline branches of the one `createAccount()`: the credential an earlier press made is held in a ref,
set *before* the join is attempted so the identity outlives the refusal, and reused only while the
typed email still matches it. A phone credential carries no email, so "no email" counts as still the
same person; a changed address is a different identity and gets made rather than reused.

**A reload is covered too, and by evidence rather than by a password.** With nothing remembered, an
`auth/email-already-in-use` looks at `auth.currentUser`: if somebody is signed in with the same email
that was typed, that is the stranded credential and the join resumes. If it is nobody, or somebody
else, the original message stands. This works because the page deliberately does not sign out after a
failed join, and Firebase's session survives a reload and a browser restart.

**The recommended variant — re-signing in with the same email and password — was not built**, and the
reasons are on the record. It takes the right message away from the person it is right for: somebody
who genuinely already has an account and types their real password would be silently joined to a
temple instead of being told to sign in. And it makes the register screen a password-checking
surface, since being joined-or-not is an observable answer to *is this the password for this email*.
With `currentUser` the register form gets no such answer. The case it uniquely covers is somebody
stranded who *also* lost their Firebase session — cleared site data, a private window, another device
— and everything short of that is already recovered.

**Nothing is deleted.** Compensating-deletion of the Firebase user was rejected on Rajeev's own
reasoning: the delete can itself fail and leave somebody worse off than the bug does, and it assumes
the account is not legitimately theirs, which is the very assumption that produced the wrong advice.
Reserving the temple before creating the credential was rejected as unbuildable here — the join
endpoint needs an authenticated uid, and the authentication filter admits a verified uid with no
membership precisely so this flow can work.

**The characterisation test was rewritten, not deleted.** The test written a day earlier to pin this
defect as it behaved (*"leaves a Firebase account behind that the second attempt cannot get past"*) is
in the same place with its header rewritten to say what it now prevents and its assertions inverted:
one credential across both presses, the join retried, no wrong advice, and the same landing as a
first-time success. Five tests joined it — Google (no second popup), phone (no second OTP spent, since
a used code is spent), the reload recovery, and the two cases where the old message is still right.

**The sliver it leaves, named rather than hidden:** somebody stranded who has also lost their Firebase
session still sees "Sign in instead.", signs in, and is bounced to `/choose-temple` — recoverable, but
only by accident. Closing that properly wants a *"you are signed in but belong to no temple"* screen,
which is its own task.

**Not done:** not driven by hand, and honestly it could not be — the path needs the server to refuse a
join on demand. It wants one pass on staging: register, have the join fail, press the button again,
and confirm the second press finishes rather than saying "Sign in instead."

### 2026-09-07 — The correction dialog says the unit and the month back as the table wrote them (task T-036)

Two findings from Rajeev's staging pass, both on the stock-movement screen, both in one file.

**The dialog was lower-casing its whole sentence.** It read *"The adjustment of +1.8 kg on 23 aug
2026"* where the table directly above it renders *"+1.8 Kg"* on *"23 Aug 2026"*. The cause was a
`.toLowerCase()` on an already-formatted string, applied so the leading type label would read
mid-sentence — and it corrupted the month abbreviation and the unit along with it. Units are not
decoration here: `Kg`, `gm`, `L` and `ml` name different amounts of the same thing.

**This codebase had already written this lesson down and reintroduced it anyway.** The note at
`components/planner/MealComposer.tsx:1362` records `toLowerCase()` rendering a litre's `"L"` as the
digit-like `"l"`, which is why unit labels there are printed through `unitLabel()` and left in the
case it gives them. Same lesson, second place. The fix is therefore at construction rather than at
the call sites: `summary` lower-cases the **type label alone**, both call sites interpolate it
untouched, and the reason is in a comment at the point where the string is built — decide the case
where the string is made, so a call site cannot flatten a formatter's output on its way to the
screen. *"The adjustment of…"* still reads mid-sentence, and that is asserted rather than assumed.

**And *Correct* is no longer offered on a movement already badged *Corrected*.** The row held the
answer three lines above the button — the same `reversedBy` that draws the badge — and offered the
control regardless, so a person wrote out a reason before being told no. This is the defect class the
wave-1 withdrawals existed to remove, on the screen wave 1 built.

**A corrected row shows an empty actions cell — nothing, not a disabled button.** The explanation is
already on screen one column to the left, in the badge; a second copy of it in a greyed-out control
would say the same thing twice in two registers, and a control that is disabled and can never become
enabled only asks the reader to work out what would enable it. That follows the five withdrawals
shipped earlier the same day, not one of which was a disabled control.

**The server-side refusal stays, and now has only one way to reach it.** `KMS-400039` still renders
readably; what was removed is the *ordinary* route to it, not the refusal. The branch is for the
two-tab race — whoever presses second is looking at a screen that was right when it loaded — and the
doc comment that used to argue the control should be unconditional was rewritten rather than left to
contradict the code.

**Not done:** not driven by hand. Worth one pass on staging — press **Correct** on an ordinary
movement and read the first sentence against the row above it, then check that a movement carrying
the **Corrected** badge has an empty actions column. The two-tab race is worth the extra minute,
because it is now the only way to see the `KMS-400039` dialog at all.

### 2026-09-07 — A refused page still has a way out of it (task T-035)

Opening a page your role is not allowed to open used to render *"Not your page"* on a bare white
page — no menu, no link, nothing. The browser's back button was the only way out, and Rajeev hit it
on four surfaces driving live staging: `/donate`, `/shifts` and `/my-shifts` as a cook, and the
disabled-account screen. **The wrong-role refusal now renders inside the application's own chrome**,
with the sidebar beside it and a link to the reader's own home.

**The link goes to `homeForRole(role)`, not to a fixed `/today`, and that is the whole design.** A
volunteer is refused `/today` exactly as a cook is refused `/donate`, so a hard-coded way out would
have landed a refused reader on a *second* "Not your page" and reproduced the defect inside its own
fix. The button's label is that destination's own row label from `nav.ts` — *Go to Today*, *Go to My
shifts* — so the button and the menu row beside it cannot come to call one screen two things.

**Fixed once, in `RequireRole`, for all 81 guarded pages and every one written after today.** What
makes that safe is a survey: of the 81 page components that mount the guard, 54 put `<Sidebar>`
inside it, 24 get one from `FocusScreen` inside it, 3 delegate to a component that does the same —
and **not one draws chrome above the guard**, so no page can end up with two menus. Per page it
would have been the same edit 54 times, and the 55th page would still have been free to reintroduce
it.

**The disabled-account screen gains a sign-out and nothing else.** No menu, because a menu is a list
of things to do and there is nothing this person can do in this account until access is given back —
twenty doors that all refuse them. The exception is leaving, which is the one useful act left: *"Use
a different account"* clears the Firebase session, the temple and the clock, and lands on sign-in. On
a shared temple tablet the tab was previously dead, and so was the next person's turn at it. The
`KMS-400019` code is still there to quote.

**`ServerUnreachable` gains neither, deliberately**, and there is now a test asserting it has no menu
and no sign-out so that a later tidy-up cannot make the three refusals alike. A working menu painted
over a server that is not answering is decoration on a dead app — every row in it lands back here —
and signing out helps nobody when signing back in needs the server that just failed.

**A real defect found on the way.** `homeForRole` fell off its exhaustive switch for a role not in
`PrincipalRole` and returned `undefined`, which handed `<Link>` an undefined `href` and blanked the
refusal screen entirely. That is what a server sending a new role ahead of a frontend deploy looks
like, and the screen it would break is the one a mismatched reader lands on. It now falls back to `/`
— the landing router, which knows what to do with anybody — labelled *the home page*.

**Not done:** nobody has seen this in a browser. It is proven by nine new tests that render real
routes (`/donate` refused as a cook, `/vendors/new` refused as a volunteer, both admitted, the
unreachable branch, the disabled branch) plus the disabled sign-out driven through the genuine
`AuthProvider` with `whoami` refusing `KMS-400019` — not a stubbed `useAuth`, because a mocked
callback would only prove the button calls the mock. The disabled screen is worth thirty seconds by
hand on staging in particular: it is the one place the fix is a button that ends a session.

### 2026-09-07 — The five screens nobody had ever tested now have tests, and one of them found a defect (docket P9, task T-022)

The docket named `/unsubscribe` as the only screen in the application with no test, and made the
right point about it: it is also the only screen reached from an email by somebody who is not signed
in, which is exactly why it should not have been the untested one. **The superlative was wrong.**
`/library`, `/choose-temple`, `/register` and `/c/[token]` had none either — the first two appeared
in tests only as an href or a redirect target, never rendered.

Five new test files, 53 tests, over behaviour exactly as it ships. No product file was touched, on
purpose: these are characterisation tests, and their job is to record what the screens do so that a
later change has to say what it is changing.

**One found a real defect, and it is deliberately not fixed.** Registration creates the Firebase
credential first and joins the temple second. If the join is refused — server down, a validation the
form did not anticipate — the credential already exists and nothing removes it, signs out of it, or
remembers it. The second press then meets `auth/email-already-in-use`, and the screen answers *"There
is already an account with that email. Sign in instead."* That advice is wrong for this person: they
have an identity and **no membership of any temple**, so signing in is not what helps, and the
registration flow is a dead end from that point. It is recoverable only by accident — signing in
gives a 401 that redirects to `/choose-temple`, where the join can be finished — and nothing on
screen says so.

It is asserted **as it currently behaves**, under a test name that says it documents a defect, with a
comment saying the same. Whoever fixes it has to come back and change what the test claims, which is
the point of writing it that way. The fix is a different task and it needs a decision first: delete
the orphaned credential, remember it across attempts and skip to the join, or reserve the temple
before the credential exists. That is Rajeev's to pick, not a test-writing task's.

**Two smaller things the tests walked past, raised rather than changed.** Taking a recipe out of the
shared cross-tenant library is a bare `×` with no confirmation — the most consequential destructive
action in the application without an "are you sure". And `/choose-temple` paints the full form for
one frame to somebody already signed in before redirecting them, which is cosmetic and only reachable
by typing the URL.

### 2026-09-07 — *My shifts* is the volunteer's seva board, and only theirs (decision D-16, task T-031)

`/my-shifts` admits `VOLUNTEER` alone now, at both ends — the menu row and the page guard carry the
same list. A kitchen manager or a cook who follows the URL gets **Not your page**.

Rajeev's rule, 2026-09-07: *"NO My shifts OR Donate options for Staff. They are already doing their
part."* This is the second half of the table `/donate` shipped a few hours earlier, and **it reverses
part of what shipped the same morning, deliberately** — the same argument, one screen along.

**Staff lose nothing, and that is worth saying because the name suggests otherwise.** `/my-shifts` is
the volunteer seva board: the shifts a volunteer signed up for, their waitlist positions, *release
your spot*, *leave the waitlist*. Every write behind it needs `SIGN_UP_FOR_SHIFTS`, which volunteers
alone hold — so for a cook the page was **permanently empty, structurally, not by policy**. Their
working days are `/my-schedule`, which shipped in wave 2 and is a different screen with a name one
word away. The confusion is a naming problem the product handed its reader, and it is recorded in
`DECISIONS.md` D-16 rather than only fixed.

**It also closes a defect found on live staging, and closes it by deletion.** The empty state had a
staff-facing branch reading *"…Who is covering which shift is on the Volunteer shifts screen."* —
and opening `/shifts` as that same cook gave **Not your page**. That is the rule the wave-1 task
which wrote the sentence had set for itself (*an empty state must not point anywhere the reader is
refused*) failing inside its own text. With the guard narrowed, the only role that can read the
sentence is the one `/shifts` admits, so the dead branch was deleted rather than reworded. A test now
asserts that as a **rule** rather than as a string: for each role, if the sentence renders, the real
shifts page must not refuse that role — so it fails from either direction, whether someone later
widens this page or narrows that one.

**Not done.** Nobody has watched a cook try it. Those accounts still have no Firebase identity to
sign in with, which is the same wall that has kept most of this batch off a human's screen.

### 2026-09-07 — A scrapped machine can be brought back, once, by name, with a reason (decision D-15, task T-033)

Scrapping a piece of equipment was permanent, and the confirmation said so. Somebody who scrapped
the wrong grinder, or who scrapped the right one and then could not find a replacement, had no way
back — the service refuses every condition change after `SCRAPPED` and was right to.

It still does. **Terminality is not loosened**: `POST /equipment/{id}/condition` goes on refusing a
scrapped item with `KMS-400043`, and that guard is byte-for-byte unchanged. The way back is a second
endpoint, `POST /equipment/{id}/reinstate`, which takes the condition the machine comes back in and a
**required** reason. That is deliberate rather than tidy: a request body that could switch a guard
off is a guard in name only.

**Temple Admin alone**, on a new `REINSTATE_SCRAPPED_EQUIPMENT` permission split out from
`MANAGE_INVENTORY` — which everybody who runs the kitchen holds, and which is what scraps a machine
in the first place. Recording a disposal and undoing one are different kinds of act. Widening the
grant later is one line; narrowing it after temples have built a habit is a conversation with every
one of them.

It audits under its own `EQUIPMENT_REINSTATED`, never as another condition change. Rajeev asked for
a reinstatement to be visible in the audit trail, and one filed under the same name as an ordinary
repair is visible only to somebody already looking for it. The state change itself goes through the
same trail as every other, with `from_condition = 'SCRAPPED'` and the reason — no second history was
invented, and **no migration was needed**: `equipment_state_changes` has permitted that row since
`V16`.

**The confirmation copy had to move with it, and that is this change's job rather than a follow-up.**
The scrapping dialog read *"Scrapping cannot be undone. Its condition can never be changed again."*
Both sentences stop being true the moment reinstatement ships, and a sentence that is false is worse
than one that is missing, because the reader acts on it. It now reads: *"Scrapping takes it off the
equipment list, and its condition can no longer be changed here. Only a Temple Admin can bring it
back, and they must record why."* The steer to *Needs repair* for a merely broken machine survives
verbatim — it is the sentence that stops most wrong scrappings before they happen — and the
confirmation is no weaker, because reinstatement is meant to be effortful rather than easy. The same
claim was made three more times in the page's own prose and in `EquipmentCondition`'s javadoc, and
every one of them was rewritten; a javadoc still reading "terminal" is how somebody later re-adds the
guard this change deliberately kept out.

On a scrapped item's page, *Change condition* is replaced by *Bring it back* rather than sitting
beside it — a screen should not offer what the server is right to refuse. The register still hides
scrapped items by default, so the way to a reinstatement runs through the scrapped filter and the
item's own page: the reader has to go and find the thing the temple wrote off, which is the right
amount of friction.

Reinstating something that was never scrapped is refused with **`KMS-400124`**; so is reinstating
*to* `SCRAPPED`. `EQUIPMENT_SCRAPPED`'s next step now reads *"Register a replacement, or reinstate
this item if it's back in use."*

**Not done.** Whether a kitchen manager finding a mis-scrapped grinder at seven in the morning should
be able to undo it was ruled Temple Admin only before anybody had seen the finished flow. Worth
confirming in front of the screen.

### 2026-09-07 — A shift can say which meal it is for, and the crew count stops guessing from the clock (decision D-14, task T-034)

Until now a shift fell to a meal by overlapping its ready-by time, and that is wrong in both
directions at once. A devotee who signs up 06:00–10:00 to cut vegetables for lunch was counted
toward **breakfast**, because breakfast is what is due at 08:00 — so the clock missed the lunch the
hands were actually promised to, and inflated breakfast with hands committed elsewhere. The second
half is the worse one: a crew figure that is quietly too high is never questioned, because nobody
goes looking for hands they think they already have.

A shift now carries the meal it was posted for. `V95` adds `meal_date`, `meal_kind` and
`meal_event_name` to `shifts`, all nullable, with a `CHECK` that keeps date and kind together — half
a link is not a weaker link, it is a link to nothing. A caller who sends half of one is refused by
name with **`KMS-400125`** before the statement is ever sent.

**The link is a natural key, not a foreign key, and there was no choice about that.** There is no
meal table: one `meal_plans` row is one dish, and a lunch of three dishes is three rows sharing a
date, kind, head count and ready-by. "A meal" is an inference the application assembles by grouping
those rows. `meal_services` does have a row per meal, but it is null exactly when this link needs
it — that row appears when the meal is carded, and a shift is posted while planning, weeks earlier.
No foreign key is possible on the kind either: `meal_kinds` is unique on `(tenant_id, lower(name))`,
an expression index, which PostgreSQL will not accept as a foreign-key target. That was checked
rather than assumed, and the migration header records it where the next reader will find it.

**Two rules, and the second one is not a compatibility hack.** A *linked* shift counts toward its
meal and toward no other, whatever the clock says. An *unlinked* shift keeps behaving exactly as it
does today — counting toward every meal whose ready-by its hours span. An unlinked shift is a general
offer of hands matched by the clock; a linked shift is hands committed to one meal; a temple says
both. It is also what keeps a 06:00–22:00 festival shift counting toward all three meals, which is
right.

**Expect breakfast's number to fall where lunch prep overlaps it.** That is the over-count being
corrected, and it is asserted on purpose as a before/after on the same shift rather than left to be
discovered: unlinked it lands on breakfast, linked to lunch it leaves breakfast and lands on lunch.

**A silent zero was found and closed on the way, and it is the more valuable half of this change.**
The crew count fetched shifts by a date range built from the *meals'* dates, so a shift posted for
"grind the masala on Thursday for Sunday's feast" would have loaded no shift at all and read **zero
on precisely the shift somebody had taken the trouble to link**. The query now matches on either
date. The alternative — widening the range by a fixed number of days — is a guess about how far
ahead a temple prepares.

The event name is folded exactly as the meal grouping folds it (blank becomes empty, otherwise
trimmed and lower-cased), and the fold lives in `MealMoment`'s own constructor so both sides of every
comparison go through it and no call site can forget the rule.

**Not done.** There is no way to *set* the link from a screen yet — the planner affordance is its own
task, and until it lands every shift is unlinked and every count is exactly what it was. A link to a
meal nobody has planned yet counts toward nothing, silently; whether the planner should say so is a
question for the planner task.

### 2026-09-07 — Giving is for people who do not work here (decision D-8/D-10, task T-030)

`/donate` admits `VOLUNTEER` alone now, at both ends — the menu row and the page guard carry the
same list, which is `nav.ts`'s own rule — so a temple admin, kitchen manager or cook who follows the
URL gets **Not your page** rather than a request for money.

Rajeev's rule, 2026-09-07: *"ALL people employed by the temple will never donate... Volunteering and
Donations are JUST for outside people who sign up as volunteers."* The reasoning is about the people
and not about the software — a temple kitchen wage is not a king's ransom, and their service is the
donation. That is why it is a guard and not merely a hidden link: a tone-deaf screen reached by
typing a URL is still tone-deaf.

**This reverses what wave 1 shipped a few hours earlier, and the reversal is the point.** The same
finding — a menu row and a page guard disagreeing about `/donate` — could be closed from either end.
Wave 1 took the cheaper end and widened the row to admit admins, without asking which end should
move. Which end it was had been a product decision all along.

The backend deliberately does not move: `POST /donations/one-time` and the wishlist endpoint stay on
`isAuthenticated()`. Nobody is harmed by a staff member who insists on giving through the API, and
minting a permission to stop them is ceremony. The inconsistency is recorded as deliberate.

**Not done.** The same ruling (D-10) narrows `/my-shifts` to volunteers by the same argument, and
that half is **not built** — the row still offers it to managers and cooks. And no hand pass on
staging: the two new refusal tests exercise the guard and its copy, and nobody has watched a cook
try it.

### 2026-09-07 — An equipment record can be corrected, and scrapping asks first (docket M7, task T-009)

Two things on the same register, both in the browser, neither a backend change.

A transposed digit in a serial number was permanent. The `PUT` taking exactly the descriptive fields
— name, storage location, acquisition date, source, notes, serial number, purchase cost, warranty
expiry — has existed since the register did and had no client wrapper, so nothing ever called it.
`/equipment/[id]/edit` does, reached by an **Edit details** button in the item header. The form
offers no condition, service interval or service company, and that is the server's shape rather than
an omission: `UpdateEquipmentRequest` excludes all three, because condition moves only through a
recorded state change with a reason and the interval travels with the service company.

The second half was the one costing something. `SCRAPPED` sat in a plain dropdown beside three
reversible values, and the service refuses every condition change after it — so a misclick was
unrecoverable and nothing on the way there said so. Choosing it now raises a confirmation naming
both consequences, that it cannot be undone and that the condition can never be changed again, with
two buttons and the keyboard focus on **Cancel**. The three reversible values gain no confirmation:
a dialog on every choice teaches people to click through dialogs.

**`SCRAPPED` stays terminal.** This makes it harder to do by accident and does not make it
reversible. Question 13 — whether it should ever be reversible — was open while this was built and
has since been answered as **D-15**: a separately named, separately audited reinstatement carrying a
required reason, which is unbuilt. The two are complementary and neither replaces the other.

**Not done.** The serial-number round trip has only been proven to the API boundary, against a mock,
so *"persists and shows on the detail page"* wants one walk on staging. And the confirmation dialog
sets initial focus and closes on Escape but is **not focus-trapped**, matching the two dialogs
already in the product — a shared-component gap rather than this screen's.

### 2026-09-07 — A cook, a manager and an admin can each see their own rostered days (docket S4, task T-006)

`GET /api/v1/staff/schedule/me` has been served behind `VIEW_OWN_SHIFTS` since Epic 6 with no caller
at all, and its client wrapper had none either. So `TRACEABILITY` **G6**'s recorded cause was wrong
in both halves — the permission was never admin-only and the endpoint was never missing — and only
the screen was absent. It is at `/my-schedule`.

Two sections, because a schedule is two questions. **Next 14 days** is the days ahead this person is
actually in, with hours. **Your usual week** is the Monday-to-Sunday template underneath. A day an
override switched off is kept and marked *Changed* with the manager's note, because that is the
outbound half of a swap; ordinary off days are simply absent.

The hole was wider than kitchen staff, which is why the menu row carries three roles: a Temple Admin
holds `VIEW_OWN_SHIFTS` as surely as a cook does and had no route either. A volunteer is
deliberately not here — they hold the permission but have no staff profile for it to read, and *My
shifts* is a different screen answering a different question. Dates are the temple's day, not the
device's. Nothing from the employment record is drawn though the payload carries it: a person's date
of birth, address and PAN digits have no business on a screen about which mornings they are in.

Somebody with no staff profile gets a worded empty state rather than `KMS-400030`, and that empty
state links to the staff schedule only for roles allowed to open it — a cook is given a person to
ask instead of a door that refuses them.

**Not done, and the screen says so in a muted line: approved leave is not shown.** `/schedule/me`
returns the template and its exceptions, and leave is resolved server-side for the manager's grid
only, so somebody given a Thursday off still sees Thursday's hours. Deliberately not papered over in
the browser; the honest fix folds leave into `scheduleForUser` the way `weekView` does, which is a
backend change and Rajeev's call. No hand pass either — it wants pressing on staging as a cook, as a
kitchen manager, and as a Temple Admin who is *not* on the staff register.

### 2026-09-07 — A temple can curate its own festival occasions (docket S3, work queue 3.3, task T-004)

*"Temple Anniversary"* is the story's own example of an occasion a temple would add, and there was
no way to add one. `OccasionController` has had create, update and delete behind
`MANAGE_TEMPLE_SETTINGS` since the occasion existed; the application called only the list, from the
autocomplete in the meal composer. This is the screen half, **Settings → Festival occasions** at
`/settings/occasions`, and there is no backend change in it.

An occasion is added as one of two things and the screen asks in those words rather than the enum's:
the Vaishnava calendar decides the date, matched on wording, or it falls on the same date every
year. Which of the two it is **cannot be changed afterwards** — `UpdateOccasionRequest` has no
`type` field, because a computed occasion and a fixed-date one are different things and the server
asks you to recreate rather than convert — so the edit dialog says so instead of offering a control
whose value would be discarded.

Removing one states the consequence before the press: the planner stops marking that day, and meals
already planned or cooked for it keep the name they were saved with. **The acceptance criterion here
rested on a wrong premise and was met differently**: *"deleting an occasion in use fails readably"*
cannot happen, because a meal plan keeps the occasion name as text (E4-S4), so a delete orphans
nothing and cannot fail. Rather than invent a server refusal that does not exist, the screen says
what will happen and lets the reader decide.

Seeded festivals are badged **Standard** and are otherwise ordinary — editable and removable,
because the backend treats them identically. Locking them would have to be a backend rule first, and
nobody has asked for one.

**Not done.** No hand pass. The edit dialog is the only modal here that scrolls, so it wants looking
at on a phone.

### 2026-09-07 — Ticking an include box stops wiping that shopping-list line's vendor (task T-028)

**A live data-loss defect on a screen that shipped months ago, on no list until it was found while
re-planning.** `ShoppingListService.updateLine` wrote `suggested_vendor_id = ?` unconditionally,
while `suggested_qty` in the same statement was already `COALESCE(?, suggested_qty)`. Neither caller
on the shopping list sends a vendor — `setIncluded` and `setQty` both post `{ suggestedQty,
included }` and nothing else — so **every tick of an include box and every quantity edit silently
nulled that line's suggested vendor.**

It was silent in both directions: the screen renders a blank vendor cell either way and the endpoint
answers `204`. The cost landed one step later — `generate()` only picks up lines that have a vendor,
so the line quietly stopped being orderable and the count under the button fell with no explanation.

The fix is one line, `suggested_vendor_id = COALESCE(?, suggested_vendor_id)`, in the service rather
than the screen: the endpoint is a `PATCH`, and a partial write that destroys the fields it was not
told about is wrong for every caller and not only this one. `included = ?` is left unconditional on
purpose and the javadoc now says why — both callers always send it, the column is `NOT NULL`, so an
omission fails loudly instead of destroying a value. No migration, no error code, no permission, no
signature change anywhere. Nothing loses a capability: no screen offers clearing a vendor, and
regeneration writes vendors through its own upsert.

**It does not repair the damage already done.** Lines whose vendor was nulled before this stay blank
until the list is regenerated, and there is no backfill — the suggestion is derived, and
regeneration is how it comes back.

Proven by a test written and run against the unmodified service first: 5 tests, 1 failed
(*"Expected a non-empty value at JSON path `$[0].suggestedVendorId` but found: null"*), then 5
passed after the one-line change with the same test bytecode across both runs.

**Not done.** No hand pass, and it cannot easily have one — a correct `PATCH` looks exactly like the
broken one on screen. Worth a look on staging: regenerate the list, tick a box on a line that shows
a vendor, and watch the vendor name survive.

> **One task from this release did not ship.** T-017, a screen for a donor to see and stop a
> recurring gift, stopped before writing a line of product code: its first acceptance criterion asks
> for each plan's **next charge date**, and that value exists nowhere in the stack — not in
> `recurring_plans`, not in `RecurringPlanView`, not in the client type. Razorpay holds the real
> schedule and we neither read nor store it. Deriving `createdAt + frequency` would typecheck and
> pass its own test, and would be a fabricated date about a live financial mandate. It is awaiting a
> re-scope decision from Rajeev. **The product can still start a recurring charge and offers no
> screen to stop one.**

### 2026-09-07 — "Continue with Google" asks which Google account again (task T-029)

Not from the docket. Rajeev found this signing out of the super-admin account to sign in as kitchen
staff, and being returned to the super-admin without being shown anything.

Signing out of this application signs a person out of **Firebase**. It does not sign their browser
out of **Google**, and it cannot — that session belongs to `accounts.google.com` and we have no
reach into it. Google's OAuth endpoint, asked to authorise with no `prompt` parameter, treats one
live session as an unambiguous answer and skips the account chooser altogether. So the next press of
Continue with Google put the person straight back in as whoever signed in last, with no screen in
between and nothing to click, which reads as the sign-out having failed.

Both call sites — the sign-in screen, through the auth context, and the register screen — now build
their provider through one exported `googleProvider()` that sets `prompt: "select_account"`. One
place, one explanation, and a third call site added later cannot quietly reintroduce it.
Deliberately **not** `prompt: "consent"`: that would also re-ask for scopes already granted, which
is a worse experience and is not what was wrong. A test asserts the `consent` case does *not*
happen, so changing it means changing the test.

**It was blocking UAT, not merely annoying.** Staff added on `/staff` exist as `pending:` users that
bind to a Firebase identity on their first Google sign-in (E1-S6, claim-on-match), so binding them
means signing in as each address in turn — the one thing this defect made impossible. **31 of the 47
UAT stories in `docs/uat/README.md` are written for kitchen staff.** That is a large part of why the
formal pack has never been run by a human.

The register screen gets a second thing out of it: it has been telling people "You'll be asked to
choose your Google account when you finish" while that was untrue.

**Not done, and this is the one that matters.** The acceptance criterion lives inside Google's OAuth
endpoint, which no test of ours reaches. The tests prove the parameter is set on the provider handed
to Firebase — verified by mutation, both fail without it — and the shipped bundle was grepped for
the strings. They cannot prove Google then draws the chooser. That needs one human: sign in on
staging, sign out, press Continue with Google, and see a chooser.

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

## DESIGN_SYSTEM v1.7 — 2026-09-11

**The `danger` row stopped naming a feature that no longer exists.** §3's *"Meaning here"* cell read
*"Overdue invoice, rejected delivery, sattvic violation"*. The sattvic flag was removed by **D-18**
and withdrawn from the locked documents by **D-20**; this one illustrative example was missed and had
been false since.

**Replaced with a real current use of the colour:** *"Overdue invoice, rejected delivery, a purchase
order past its order-by date"* — the red badge T-137 shipped on 2026-09-11, which is the same
severity of thing as the two beside it.

**Approved by Rajeev, 2026-09-11**, on being shown the exact one-cell change. Found by T-058's
builder, which correctly refused to edit a locked document and reported it instead.

**Nothing else in the file changed.** No token, no value, no rule — the colour, its fill and its text
are untouched. Snapshot in `docs/versions/DESIGN_SYSTEM_v1.7.md`.

---

## Versioning convention

- Version bumps to a **locked** document require the user's explicit approval, per the Ten Commandments (never silently edit an approved decision).
- **Patch-level** (v1.0 → v1.1): typo/clarity fixes with no requirement or design change.
- **Minor** (v1.0 → v1.1... or v1.x → v1.y): scoped additions or clarifications that don't invalidate prior decisions.
- **Major** (v1.x → v2.0): a decision reversal, scope change, or anything that would require re-reviewing earlier-dependent work (e.g. Stage 3 tech choices made against SYSTEM_DESIGN v1.0).
- Every bump gets a snapshot in `docs/versions/` and an entry here.
