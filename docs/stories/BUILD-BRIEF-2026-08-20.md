# Build brief — 2026-08-20

**Status: CLOSED 2026-08-20. Every question answered; nothing here is open.**

The working record of the 2026-08-19/20 conversation, kept so "airtight before the build" is
something we can both check rather than remember. Stories get written from this; this is not itself
a story.

---

## 0. Scope change, signed off

**Payroll and leave move from Phase 2 into Phase 1.** E6-S1's assumption currently reads *"no
payroll, attendance, or leave-balance accounting in release 1 — that is Phase 2"*, and
REQUIREMENTS.md splits the phases the same way. Rajeev, 2026-08-20: *"The temple came back and
wanted it in Phase 1. So had to pivot and include it in."*

That is a requirement change from the customer, not scope creep, and his statement is the sign-off
CLAUDE.md requires. To be amended in one pass once the payments questions close: `docs/CHANGELOG.md`
entry, the Phase 1/2 split in `REQUIREMENTS.md`, and E6-S1's assumption line.

**Leave-balance accounting stays out** — the temple never asked for accrual. **Attendance stays out
too**, settled 2026-08-20: hourly pay was dropped, and hours worked were the only thing that would
have required recording attendance. So the amendment reads: leave and staff payments move to Phase 1;
attendance and leave accrual do not.

---

## 1. Pool A — fixes and modifications

No new concepts; no further discussion needed.

| | |
|---|---|
| A1 | Today: "Meals in the kitchen" → "Meals planned for today" |
| A2 | Today: meals clickable through to that day's planner |
| A3 | Today: group meals by kind, with dishes and servings beneath |
| A4 | Today: plates tile shows servings per meal kind, from the planner's *Scales to* |
| A5 | Monthly planner: festival names overflow the day box — truncate as the calendar does |
| A6 | Planner step 1, second row: "Ready by" sits a line above its neighbours (hint-line bug) |
| A7 | Swap the positions of *Catering order* and *Outside event* |
| A8 | Invoices: rows not clickable — open the detail |
| A9 | Purchase orders: drafts editable; a sent PO refuses and says to raise a new one |
| A10 | Staff: remove the *Schedule* link; *Edit* → *Update*; *End Employment* → *Terminate* |
| A11 | Staff schedule: remove the *Staff register* button |
| A12 | Termination: reword the clumsy "Nothing is deleted…" sentence |

---

## 1b. Pool B — new features

Each needs a story. Three of them are substantial. Sections below carry the detail.

| | | Where | Size |
|---|---|---|---|
| B1 | **Workforce status** — who is actually in, that day | §6b | Medium |
| B2 | **Cost of materials** for the day's meal service | §9 | Medium |
| B3 | Workforce pebbles on the daily and weekly planner | §6b | Small |
| B4 | Swap or edit a planned dish instead of cancel-and-re-add | §2 | Small |
| B5 | **Job card** | §3 | **Large** |
| B6 | *Outside event* gains "What is it for?" | §1c | Small |
| B7 | **Time off and sick leave** | §4 | **Large** |
| B8 | **Staff payments** — salary, advances, docking, settlement | §7 | **Large** |
| B9 | Ban / ineligible-for-rehire on termination, and the check at hire | §10 | **Large** |
| B10 | Festival recipes | §12 | Deferred |
| B11 | **Platform notice board** — raise, receive on Today, dismiss, keep | §11 | Medium |

---

## 1c. Outside event — "What is it for?" (B6)

*Outside event* gains a free-text purpose: a Bhagavad-gita reading, book distribution, a school
event. Sits beside the venue, which the meal kind already requires.

Not a picklist. The reasons a temple cooks for an outside event are open-ended, and a list of five
would be wrong by the sixth — this is a label for the kitchen and the job card, not something the
system reasons about.

*Catering order* and *Outside event* also swap positions (A7).

---

## 2. Meal status — what it is and is not

The status is not decoration: **marking a meal cooked is the moment its ingredients leave stock.**
Delete it and the store room never depletes, and the order list over-states what is on hand. So it
stays — but everything around it was theatre and goes.

- **Three states only: Planned, Cooked, Cancelled.** No "Cooking". It is unobservable, nobody with
  hot oil in front of them touches a screen, and a state inferred from a clock is one the app
  invented.
- **Recorded when the job card comes back**, by whoever is in the office — not by a cook mid-service.
- **Recording is per meal, not per dish.** One form: every dish listed, planned servings prefilled,
  editable to what actually went out, "not made" available per dish. The individual *Mark as cooked*
  buttons go.
- **Actual servings are the point.** Over a month they tell the temple their head counts are wrong,
  in which direction and by how much. That is the number that makes the data entry worth doing.
- **Today shows the truth, not a badge**: *Lunch · 12:00 · not yet recorded*.
- **A nudge, not an alarm**, for unrecorded meals: *"3 meals from earlier this week not yet
  recorded"* — otherwise stock silently overstates itself.
- **A dish can be swapped or edited until the meal is recorded**, never after (Pool B, B4).

---

## 3. Job card (B5)

One card per **meal kind** — a Breakfast card, a Lunch card. Carries: date, meal, ready-by, head
count breakdown, every dish with servings and **scaled** ingredient quantities, method, the day's
fasting and sattvic warnings, equipment, the staff rostered and volunteers signed up, and sign-off
boxes.

- **Marking off and signing are paper.** No app, no ticking, no friction. Rajeev: *"the sheet. No
  fancy app. We want practical and usable with little to no friction."*
- **A card number is printed on it** — *Lunch · 21 Aug 2026 · LC-2026-0142* — so a signed sheet in a
  folder can be traced back to its record six months later.
- A4. Reuses the existing Chromium renderer, so no new machinery.
- **Printable in the temple's own language and in English — the temple chooses at print time**,
  settled 2026-08-20. Same shape as the purchase order, which already takes a language on its print
  URL, so the pattern and the machinery both exist. Defaults to the temple's language, since the card
  goes to the kitchen; print it twice if the head cook wants English and the line cooks do not.
  Verified on live the same day: `TRANSLATION_PROVIDER=google` and `DOCUMENTS_RENDERER=playwright` are
  both real, so this is not a stub — the 2026-08-11 handoff saying otherwise is out of date.

---

## 4. Leave (B7)

- **No accrual and no balances.** Never asked for. A request-and-approve log.
- Types: **time off, sick, unpaid**. **Half-days** supported.
- **Approved by the temple admin, or by a Kitchen Manager where the temple has appointed one.**
- Approved leave **drops them out of the schedule grid and the workforce count**.

---

## 5. A new role: `KITCHEN_MANAGER`

"The kitchen manager can approve leave" collides with E6-S8's D2 — *a job title is a label and gates
nothing*. The resolution is the one already recorded in **BL-4**: *"more roles, not a second concept
beside them."*

So a `KITCHEN_MANAGER` role joins `RolePermissions`: everything `KITCHEN_STAFF` holds, plus
approving leave. The hire form already suggests an access level from the job title, so choosing
*Kitchen Manager* suggests it and the admin may still override. The title still grants nothing; the
access grants. "If one is appointed" falls out for free — nobody holding the role means only the
temple admin can approve.

**BL-4 can be closed by this.**

---

## 6. Schedule exceptions move to the week grid

The template page answers *"what is this person's pattern?"*. A swap is not a pattern, so it does not
belong there. Per-date exceptions leave that page entirely.

**The week grid becomes directly editable.** Click one cell — one person, one day — and get four
actions, each writing an override on that date alone and leaving the template untouched:

1. **Change the hours** for that day
2. **Mark them off**
3. **Add them on** to a day they do not normally work
4. **Swap** — pick the destination day; both halves are written together and linked, so undoing one
   undoes both. This is the case people get wrong by doing half of it.

Overrides already render distinctly on that grid, so an adjusted week looks adjusted.

Also on the grid: **approved leave, read-only**, so a manager sees why somebody is out and cannot
schedule over it; and **a count at the foot of each day column**. That count is the single source the
Today tile and the planner pebbles both read, rather than three screens each computing their own.

**No overtime.** Adding a salaried cook to an extra day changes the roster, not their pay.

---

## 6b. Workforce status (B1, B3)

Replaces the *Shifts unfilled* tile, which showed a warning about a shift on an unnamed date and gave
an admin nothing they could act on. What they actually want is a read on **today**: is there enough
of a kitchen to cook with?

**One number, computed once.** The count at the foot of each week-grid column (§6) is the single
source. The Today tile and the planner pebbles read it rather than each screen computing its own and
disagreeing by one.

- **Today gains a Workforce tile**: staff in today and volunteers signed up today, counted separately
  — a full-time cook and a two-hour evening volunteer are not interchangeable and should not be added
  together.
- **Staff in** means: the weekly template says they work that day, adjusted by any per-date override,
  minus approved leave. That last clause is why leave has to land before this — a tile that counts
  somebody who is on leave is worse than no tile.
- **Volunteers** means: signed up for a shift falling on that date.
- **Two pebbles on the daily planner**, between the date and the festival line: staff, and volunteers.
- **The same two on each weekly-view tile.**
- **Not on the monthly view.** It would clutter a grid already fighting for room (A5), and clicking a
  day opens the daily view which carries it.

---

## 7. Staff payments (B8)

**Salaried staff only.** Hourly was dropped as more trouble than it is worth until somebody asks for
it — and with it went the only reason to record attendance.

**The app records; it does not compute what is owed.** The admin types the settlement figure. That is
deliberate: computing salary owed needs a pay period, a start date and a ledger of settled periods,
which is payroll, and nobody asked for payroll.

**What the termination screen therefore shows** — because "display what they owe" still has to mean
something:

- **The cash-advance balance, computed exactly.** Advances given minus deductions recovered, both of
  which we hold. This is arithmetic, not inference, so it is a hard number.
- **The last salary payment and its date** beside it — *"last recorded payment 31 July; terminating
  12 September"*. The admin draws their own conclusion about the months between. Showing what we know
  beats inventing a figure that looks authoritative and is not.

**Payments** record: amount, date, mode (**cheque, cash, payroll**), and a reference — cheque number,
payroll reference, or simply *Cash*.

**Advances** are paid by cheque or cash and recorded the same way.

**Docking**: a salary payment is **gross − deductions = net**, each deduction linked to the advance it
repays, so the advance balance falls on its own and never has to be maintained by hand.

**Currency: the narrow version, confirmed 2026-08-20** — a second country is not close.

- A **currency on the temple**, used properly by everything built here: salary, payments, advances.
  Neutral column names from the start.
- The existing rupee-named columns (`amount_inr`, `price_inr`, `cash_amount_inr` across donations,
  wish list, invoices, purchase orders) **stay as they are**. Retrofitting them for a temple that does
  not exist is churn for a guess; when a real second-country temple appears it becomes a bounded job.

**Who may see salary: the temple admin, and nobody else.** This needs a permission split, because
`KITCHEN_MANAGER` approves leave on screens that today also carry pay. Managing the roster and
approving leave is one permission; seeing what people are paid is another, held by the temple admin
alone. Without the split, appointing a kitchen manager quietly hands them everybody's salary.

---

## 8. Donations — periods and comparison

- One period control above the tiles: **This week · This month · This financial year · a specific
  year**. Financial year meaning **April–March**.
- Each tile shows the figure **and a comparison against the same window a year earlier** —
  *"₹1,24,000 · up 18% on this point last year"*. Same-point-to-same-point, because comparing a
  part-year against a whole one is how these screens mislead.
- **Counted by the date the gift was given**, not the date it was recorded. Truthful, at the cost of
  last week's total still being able to move.
- The period **filters the tiles and the ledger list**, and **Export CSV follows the filter** — so an
  accountant selects the financial year and gets the full-year file.
- *Given this month* leaves the Today screen; money coming in lives here, where somebody goes to look
  at it deliberately.

---

## 9. Cost of materials (B2)

**Estimated, from vendors' last-known prices, and labelled an estimate.** This is the final version,
not a stepping stone.

Perfect costing is rejected on its merits: true cost needs inventory valuation, and the store room
contains **donated goods**, which have an estimated value and no purchase price at all — so a
"perfect" number would be part fiction the moment a gift in kind is cooked.

**Labour costing is a candidate for later, not this build.** It needs no timesheet — the weekly
template says who works which hours and salary gives a day rate — but a cook on a 6am–2pm shift is
making breakfast *and* lunch, so labour can only be **allocated** across the meals their hours
overlap, never measured. If it is built, the screen must say "estimated, materials and labour
allocated".

---

## 10. Dismissal, bans, and cross-temple checks (E9)

Design in `EPIC-9-cross-temple-notices-DESIGN.md`. Settled since:

- **No broadcast naming a person.** Rajeev's argument won it: an unnamed notice — *"an employee has
  been blacklisted"* — is a rumour with no handle on it, useful to nobody and corrosive anyway. So
  the notice is dropped and the check moves to the point of hiring.
- **A global ban list.** The temple that creates a record **owns it** and may update or retract it.
- **Checked at hire**, exact on the PAN fingerprint (a blind index already built, identical across
  temples, revealing nothing), then a **probabilistic layer** over name, address and phone for
  somebody who has changed details. Exact signals match; fuzzy signals flag and never block.
- **Aadhaar is matched without storing the number** — the signed QR gives UIDAI's own name, DOB and
  last four, which together beat a typed number because they cannot be fabricated.
- **The banning temple is named to the hiring admin**, with what they wrote, so it becomes a phone
  call between two administrators rather than a verdict.
- **Bans fade at 10 years** and stop appearing on hire screens. Rajeev to confirm the figure with the
  temple.
- **The subject is not shown the reason in the app.** Rajeev's ground-truth argument: they lose
  access at termination anyway, and disclosure at the moment of firing invites retaliation, which is
  a real risk in India borne by his people. DPDP's right here is to information *on request*, not
  proactive disclosure, so a documented out-of-band process satisfies it. Consequence: the subject is
  no longer a check on a wrong entry, so retraction, the 10-year fade and naming the banning temple
  carry the whole of the error correction.
- **Queried only on a new hire**, settled 2026-08-20 — never browsable. There is no "search the ban
  list" endpoint: the check runs *as part of* creating a staff record, so a query cannot exist without
  a hire attempt behind it. That is what stops it becoming a lookup service, and combined with every
  query landing on the platform audit log it means no temple can fish through it. Re-hiring someone is
  a new hire and is checked; editing an existing record is not.

---

## 11. Platform notices (E9-S1) — agreed, and in this build

The generic carrier BL-6 argued for, decoupled from dismissals entirely.

- **Operators**: a *Notices* item beside Operations — a downtime notice is an operations act.
- **Temple admins raising one**: under *Temple*, beside Audit log and Settings. Rare and serious
  enough to sit somewhere deliberate.
- **Receiving**: undismissed notices at the top of **Today**, dismissible per temple, permanent on a
  Notices page.

---

## 12. Deferred, not open

**Festival recipes (B10) — waiting on the temple's own.** Rajeev, 2026-08-20: *"Ignore for now. I
will get you the actual recipes from the temple."* Which is the right call and what CLAUDE.md asks
for: the temple's own festival menus beat anything I would research, and inventing plausible ones
would put fiction in a recipe book cooks are meant to trust.

---

## 13. The wipe before the clean run

**A one-off throwaway job on the VPC**, confirmed 2026-08-20. The database is private-IP only, so
nothing reaches it from a laptop; this is the same route used to nudge a Quartz trigger previously.

**An on-demand Cloud SQL backup is taken first.** There is no export-before-delete guard on a partial
wipe the way there is on a tenant deletion (E1-S15), and this is live data.

**Two things make it more than a DELETE.** `stock_movements` and `audit_events` are append-only, now
enforced by a trigger rather than a revoke, so the job lifts the guard and puts it back. And the
foreign keys are `ON DELETE RESTRICT` almost everywhere, so the order is fixed: payments → invoices →
receipts → PO lines → POs → order lists → meal plan lines → meal plans → stock movements → stock
levels.

**Wiped:** meal plans and their lines, order lists, purchase orders and lines, goods receipts, vendor
invoices, invoice payments, stock movements, inventory stock levels.

**Kept:** ingredients, recipes, vendors and their supply mappings, staff, devotees, shifts and
signups, donations, the wish list, calendar overrides. None of it is kitchen usage.

---

## 14. Order of work

**This build, in this order.** Leave lands before workforce, because a workforce count that ignores
leave is worse than none; payments land before the termination changes, because the settlement figure
has to exist before a screen can show it.

1. **Pool A** — a day's work, and it makes the screens being tested coherent
2. **B7** leave, and the week-grid rewrite (§6) that replaces date exceptions
3. **B8** staff payments, and the `KITCHEN_MANAGER` role plus the salary permission split
4. **B1 / B3** workforce tile and pebbles
5. **B2** cost of materials
6. **B4 / B6** dish swap, and the outside-event purpose
7. **B5** job card, and with it the per-meal recording that replaces per-dish marking (§2)
8. **B9** the ban record on termination, and the check at hire

**Then, and only then:** deploy once, self-test, fix, retest, and hand over a verification list.

**After Rajeev's verification pass** — not before, since the build changes the shapes involved:

- the backup and the throwaway VPC wipe (§13)
- **inventory intake first** — the store room holds stock that predates the app, and ordering against
  an empty store room is what made the last simulation unrealistic
- then the full run: meal planning for a month, staff and volunteer scheduling, granting leave,
  ordering, receiving in full and in part and rejecting part of a delivery, paying vendors, paying
  staff, cash advances, newsletters — everything an admin does, done as an admin would.

9. **E9-S1** the platform notice board — in this build, confirmed 2026-08-20. Built last because it
   depends on nothing else here, so it can be cut without disturbing anything if the build runs long.
