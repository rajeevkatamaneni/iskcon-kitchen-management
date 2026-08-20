# EPIC 4 — Meal Planning & Vaishnava Calendar

**Goal:** The astronomical Vaishnava calendar engine (post-2006/GCAL schema, per-tenant location), named festival occasions, a calendar-aware meal planner across four contexts, Ekadashi violation flagging, and inventory sufficiency feeding the ordering pipeline.
**Depends on:** Epics 1–3. **Blocks:** Epic 5 (order generation consumes shortfalls).
**Labels:** `epic:planning`

**Reference material:** `Menu and Quantity calculation for cooking_ICC.xlsx` — real per-festival menu tabs (Nityanand Trayodasi, Gaurpurnima, Ram Navami, JPS Vyasapuja, Narsimha Chaturdasi, Snana Yatra, Panihati, Ratha Yatra, Balaram Purnima, Janmastami, SP Appearance Day, Radhastami, Temple Anniversary, Sharad Purnima, SP Disappearance Day, Govardhan Puja, RNS Vyasapuja) — this is the festival vocabulary the planner must speak.

---

## E4-S1 — Calendar engine: astronomical computation

**Verified by:** [UAT-029](../uat/UAT-029-the-vaishnava-calendar.md)

**As a** system, **I want** tithi/Ekadashi/festival dates computed astronomically per tenant location, **so that** the planner knows what any date *is* without manual data entry.

**Assumptions:** Locked: compute, don't import; post-2006 Hari-bhakti-vilasa schema; MIT-licensed `gaurabda-calendar` (Python) as reference implementation. **Implementation decision within this story:** port the algorithm to Java, or run the Python reference as a tiny sidecar/CLI invoked by the nightly job. Recommendation: evaluate port-to-Java first (single-language codebase); the sidecar is the documented fallback if the port proves error-prone against reference output.

**Requirements:**
- Nightly job (E1-S9) precomputes `calendar_days` per tenant for 18 rolling months: date, tithi at local sunrise, paksa, masa, Ekadashi flag (incl. Maha Dvadashi postponement rule), major festival markers, fasting flags.
- Inputs: tenant lat/long/timezone (E1-S3). New tenant → precompute queued at provisioning (E1-S6).
- **Correctness gate:** computed output for a reference year/location must match published ISKCON calendar dates (e.g. vaisnavacalendar.info for the same city) — discrepancies block the story, not ship with it.
- Planner and all consumers read rows only; no live computation on request path.

**Acceptance criteria:**
- [ ] Full-year output for one Indian metro matches the published ISKCON calendar for: all Ekadashis (incl. postponements), Janmashtami, Gaura Purnima, Radhastami. Documented comparison attached to the story.
- [ ] Two tenants in different cities can produce different Ekadashi dates when astronomy says so (test with a known divergence case or synthetic coordinates).
- [ ] Nightly job is idempotent; re-runs update rather than duplicate.
- [ ] Ops page (E1-S11) shows last successful precompute per tenant.

---

## E4-S2 — Festival occasion catalog

**Verified by:** [UAT-030](../uat/UAT-030-festival-occasions.md)

**As a** Kitchen Staff member, **I want** the system to know named festival occasions, **so that** planning "Janmashtami" carries meaning — expected scale, menu history — not just a date with a generic flag.

**Assumptions:** Seed catalog = the 17 named occasions from the ICC workbook + major pan-ISKCON days from the calendar engine; tenant-extendable (temple anniversary is inherently tenant-specific). Occasions link to computed calendar dates where astronomical (Janmashtami) or are manually dated where local (anniversary).

**Requirements:**
- Occasion entity: name, type (computed/manual), linked calendar rule or fixed date, default expected-servings (tenant-editable, learns nothing automatically in release 1), notes.
- Seeded on provisioning; admin CRUD for tenant-specific occasions.
- Occasions surface on the planner calendar view and drive the festival day-type on meal plans (E4-S4).

**Acceptance criteria:**
- [ ] Seeded occasions appear on correct computed dates for the tenant's location.
- [ ] Admin creates "Temple Anniversary" with a fixed date; it appears in the planner annually.
- [ ] Deleting an occasion doesn't orphan historical meal plans (reference preserved).

---

## E4-S3 — Admin calendar override

**Verified by:** [UAT-031](../uat/UAT-031-correct-a-calendar-date.md)

**As a** Temple Admin, **I want** to correct an individual computed calendar date, **so that** astronomical edge cases (adhika/ksaya masa) or a local GBC ruling never force us to work around our own system.

**Assumptions:** Locked safety-net decision. Override = per-tenant, per-date row shadowing the computed value; audited; survives recomputes.

**Requirements:**
- Admin UI on the calendar view: select date → override tithi/Ekadashi/festival flags → mandatory reason.
- Override row wins over computed row for all consumers; visibly badged in UI.
- Audit event; revert path (remove override → computed value resumes).
- Nightly recompute never silently clobbers overrides.

**Acceptance criteria:**
- [ ] Override changes what the planner shows and how violation checks behave immediately.
- [ ] Recompute preserves the override; revert restores computed truth.
- [ ] Audit event carries before/after and reason; override badge visible to all staff.

### Screen decisions (2026-08-11)

The backend shipped with the epic; the admin surface did not, and the gap was found by the UAT pack's
traceability pass (TRACEABILITY.md gap G2) — the planner *displayed* an override marker while nothing
could create one, leaving the locked safety net unreachable by the person meant to use it. Decided
with Rajeev before building it:

**D1 — It lives in the planner, not on a calendar screen of its own.** The planner already is the
calendar; a second one would drift from it. Clicking a day's tithi opens a day panel below the grid,
the same way planning a meal does.

**D2 — The panel serves everyone, the correction serves the admin.** Anyone who can plan meals sees
what the engine computed for that day — tithi, month, Gaurabda year, the fast and its name, any
Maha-Dvadashi variant, festivals, sunrise and sunset. That was previously invisible, so an Ekadashi
warning arrived with nothing behind it. Only a Temple Admin (`OVERRIDE_CALENDAR_DATE`) sees the
correction form or the undo.

**D3 — All four correctable fields are exposed, with the tithi as a named dropdown.** Rajeev's call.
The API accepts `isEkadashi`, `ekadashiName`, `tithi` and `festivalNote`; the alternative was to
expose only the fasting flag and drop `tithi` from the contract. Exposing all four leaves nothing in
the API that no screen can reach — the same principle that removed `tenants.status`. The tithi is
chosen by name (Gaura Ekadasi, Krsna Dvitiya, Purnima…), never as the raw 0–29 code the API takes.

**D4 — A correction is never silent.** The reason is mandatory (the API enforces it), the day is
badged as hand-corrected with its reason visible to *all* staff, not only admins, and both the
correction and the undo are audited.

**D5 — A date the engine has not computed cannot be corrected.** The panel says so plainly instead of
offering a form. An override on a date with no computed row would not surface anywhere, so offering
it would be a lie.

---

## E4-S4 — Meal plan CRUD across four contexts

> **Partly superseded by E4-S7 (2026-08-14).** The day-type choice and the meal-slot model are
> replaced there; the underlying CRUD and the calendar awareness stand.
>
> **And by E4-S10 (2026-08-20).** "Mark as cooked" per dish is gone, endpoint included. A meal is
> recorded once, for all its dishes, from the returned job card, and stock is drawn against what
> actually went out rather than against what was planned.

**Verified by:** [UAT-032](../uat/UAT-032-plan-a-meal.md), [UAT-033](../uat/UAT-033-outside-catering.md), [UAT-035](../uat/UAT-035-cook-a-meal.md)

**As a** Kitchen Staff member, **I want** to plan meals by date with day-type context (regular / weekend / festival / outside catering), **so that** the week's cooking is visible, assignable, and scaled right.

**Assumptions:** Planned meal = date, meal slot (tenant-configurable list, default Lunch/Dinner + Deity offerings slot), recipe ref, target servings, day-type (auto-suggested: festival if occasion present, weekend by weekday, else regular; catering always explicit), status (`PLANNED/COOKED/CANCELLED`). Catering context carries client fields (name, contact, venue, delivery time) per locked requirement — full catering billing is out of scope for release 1 (prior proposal's profit-catering module is Phase 2+).

**Requirements:**
- Calendar (month/week) and list views; create/edit/cancel planned meals; recipe picker with scale preview (E2-S3).
- Day-type auto-suggestion with manual override; festival days pre-fill expected servings from occasion default (E4-S2).
- Catering plans capture client details; catering list view for upcoming commitments (matches wireframe tab).
- "Mark as cooked" triggers consumption (E3-S6) with confirmation showing the movement preview.

**Acceptance criteria:**
- [ ] Planning a meal on a festival date auto-tags day-type and pre-fills servings; both overridable.
- [ ] Mark-as-cooked writes consumption and flips status; cancelling a cooked meal is blocked (compensate via E3-S7 with guidance message).
- [ ] Catering commitment shows client details and appears in the catering list.
- [ ] Month view renders a fully planned month (≈90 meals) without pagination jank on mobile.

---

## E4-S5 — Ingredient sufficiency and shortfall feed

**Verified by:** [UAT-034](../uat/UAT-034-do-we-have-the-ingredients.md)

**As a** Kitchen Staff member, **I want** each planned meal to show whether we have the ingredients, **so that** shortages surface at planning time, not at the stove.

**Assumptions:** Sufficiency = scaled requirements (E2-S3, unrounded) vs current stock (E3-S1) minus quantities already committed to other uncooked planned meals in the horizon (double-booking guard). Statuses: `SUFFICIENT / SHORT / PLANNING` (matches wireframe badges).

**Requirements:**
- Sufficiency computed on plan save and on relevant stock changes (event-driven or cheap recompute — implementation's call, correctness first).
- Per-meal badge + per-ingredient shortfall detail (needed, have, short-by).
- Aggregated shortfall query across a configurable horizon (default: through 14 days + any festival within 30) — this is the contract E5-S2 consumes.
- Commitment accounting: two meals needing the same rice can't both read "sufficient" against one sack.

**Acceptance criteria:**
- [ ] Badge transitions correctly when stock arrives (receiving) or another meal consumes the same ingredient.
- [ ] Double-booking test: two planned meals against stock covering one → second shows SHORT.
- [ ] Shortfall API returns exact quantities E5 needs (contract test shared with E5-S2).

---

## E4-S6 — Ekadashi violation flagging

**Verified by:** [UAT-036](../uat/UAT-036-the-ekadashi-guard.md)

**As a** Kitchen Staff member, **I want** the planner to flag grain/bean recipes on Ekadashi, **so that** a fasting-day menu mistake is caught at planning time.

**Assumptions:** Ingredient master gains `is_ekadashi_prohibited` (grains, beans, certain flours — seeded list, admin-editable), parallel to the sattvic flag. A recipe is Ekadashi-compatible iff no line contains a flagged ingredient (the seeded Ekadashi category from E2-S2 should pass by construction). Flag severity: **warning requiring explicit acknowledgment**, not hard-block — Ekadashi rules bind ashram residents' meals, but temples do cook grains for non-fasting visitors/children on Ekadashi; the temple decides. (Contrast with sattvic hard-block, where no legitimate exception exists at cooking time.)

**Requirements:**
- Planning a non-compatible recipe on an Ekadashi (computed or overridden, per E4-S1/S3) raises a blocking confirmation naming the offending ingredients; proceeding records acknowledgment (actor, timestamp) on the plan.
- Ekadashi dates visually distinct in planner views; compatible recipes filterable in the picker on those dates.
- Acknowledged violations badged on the plan (visible, not shameful — informational tone).

**Acceptance criteria:**
- [ ] Khichdi (rice+dal) on Ekadashi triggers the confirmation; kheer from the Ekadashi category does not.
- [ ] Acknowledgment recorded and visible on the plan; no silent bypass path exists.
- [ ] Calendar override (E4-S3) flipping a date's Ekadashi status immediately changes flagging behavior.

---

## E4-S7 — Meal planning by day

**Status:** APPROVED 2026-08-14 by Rajeev, after four open points were settled (D10–D13). Supersedes
parts of E4-S4 (see *What this replaces*).

**Verified by:** UAT-032 (to be rewritten with this story)

**As a** Kitchen Staff member, **I want** the planner to show the meals we are cooking and to give me
one place per day to plan them, **so that** the screen answers "what are we cooking, and by when?"
instead of describing the religious calendar at me.

### Why

The screen is called Meal plan and, as built, contains no meals. Every cell is filled with calendar
text; planning is a "+" that opens a form a full screen below the click. Driving it end to end
(2026-08-14) found the core purpose unusable: the day panel and the plan form both open off-screen
(UAT031-1), and the recipe list is empty at a new temple so no meal can be planned at all (INT-2).

Rajeev's framing, which this story adopts: **the calendar shows the plan; the religious data is an
input to planning, not the content of the screen.**

### What this replaces

- **E4-S4's day-type input.** Regular / Weekend / Festival / Catering stop being a human choice.
  Weekend follows from the date, festival from the calendar, and catering becomes a kind of meal. The
  day type is still derived and stored, so history and reporting are unaffected — nobody picks it.
- **E4-S4's meal slots.** "Slot" becomes **meal kind**, carrying a default ready-by time.
- **E4-S3's correction surface.** The correction moves into the day view's context panel; the
  standalone panel below the grid goes away.

### Decisions

**D1 — One list of meal kinds, each with or without a default time.** Seeded per temple: Breakfast
07:30, Lunch 12:00, Dinner 19:30, and — deliberately with no default — Deity offering, Catering
order, Outside event. A kind with no default *must* ask for a time; a kind with one pre-fills it and
lets the planner change it. Rajeev's decision: routine meals have known times, occasional ones do
not, and guessing a time for a deity offering is worse than asking.

**D2 — Every planned meal has a ready-by time.** Not a start time, not a duration: the moment the
food must be ready. It is what a cook works backwards from, and what makes a day's plan orderable.

**D3 — Default times are one set per temple**, editable by a Temple Admin (Rajeev's decision). Not
per person and not global — temples eat at different times.

**D4 — Three views: Daily, Weekly, Monthly**, chosen in the header where the subtitle is today.
Monthly shows each date with a stack of meal chips (kind + time). Weekly gives seven columns with
room for servings and readiness. Daily is one day in time order with full detail.

**D5 — Clicking a day opens a full-screen day view**, with three stacked panels:
1. **Context** — the date, what day it is religiously, the fast (full or half), festivals observed,
   sunrise. A Temple Admin can correct it here (E4-S3) rather than on a separate panel.
2. **The plan** — the meals already planned for that day, in time order, each showing its kind,
   ready-by time, recipe, servings, ingredient readiness, and its actions (mark cooked, cancel).
3. **Plan another meal** — the planning tool. New meals join the stack above as they are saved.

**D6 — The day view absorbs the cell actions.** Cooking, cancelling and the ingredient-sufficiency
badge move off the calendar cell and into the stack, so a cell carries information and one gesture:
open the day.

**D7 — Past days are read-only.** The context panel still reads; nothing can be planned, cooked or
cancelled. *(Open: whether an admin may still correct a past date's calendar — see below.)*

**D8 — An empty day is empty.** No placeholder, no "+" — the whole cell is the target.

**D9 — The planner never dead-ends.** With no recipes in the temple, the planning tool says so and
links to Recipes rather than offering an empty dropdown (INT-2).

### Decisions taken on the open points (2026-08-14, Rajeev)

**D10 — Fasting and festival days carry a minimal mark on the month view.** A dot or thin bar, no
text. Rajeev agreed with the argument: a planner scanning a month must be able to *see* that a day
constrains the menu, or that a major festival is coming, without opening each day in turn. The names
stay in the day view; only the signal is on the grid.

**D11 — Sunrise is shown; sunset is not.** Sunrise decides which day a fasting day falls on and when
a fast may be broken. Nothing we compute uses sunset, so it goes rather than sit there as decoration.

**D12 — The calendar cannot be corrected on a past date.** Read-only means read-only; there is no
value in editing a day that has been and gone.

**D13 — Catering and outside events are genuinely different kinds, and differ by who is paying and
who sets the menu.** In Rajeev's words: an **outside event** is the temple's own — it takes food to
feed devotees after a programme somewhere, on the temple's dime, with a menu the temple sets and
usually light. **Catering** is when a devotee or an outsider asks the temple to cook for their own
occasion — a housewarming, a birthday, a puja at their house — and that person pays and agrees the
menu and quantities with the temple.

The consequence for the screen: both need a **venue** and an explicit **ready-by time**; only
catering needs a **client** (name and contact — the person who asked and is paying). An outside event
has no client, so asking for one would be noise. The existing database constraint that ties a client
to the *day type* `CATERING` moves to the meal kind.

### Requirements

- **Meal kinds** replace meal slots: name, optional default ready-by time, sort order; seeded per D1;
  a Temple Admin can add, rename, reorder and set times. Existing planned meals keep their kind.
- **`ready_by` on every planned meal**, required at the service layer, defaulted from the kind where
  the kind has one.
- **Day type is derived server-side** from the date and calendar and stored; it is not accepted from
  the client.
- **Client and venue are captured per kind, not per day type** (D13): catering asks for a client
  name and contact plus a venue; an outside event asks only for a venue; the everyday kinds ask for
  neither. The `meal_plans_catering_has_client` check moves off `day_type` accordingly.
- **The month view marks fasting and festival days** with a non-textual signal (D10).
- **Sunrise is shown in the day view; sunset is dropped** (D11).
- **The correction control is hidden on past dates** (D12).
- **Three views** with the period navigation the current header has.
- **Full-screen day view** per D5, reachable by clicking anywhere on a day.
- **Past days** render the context and the plan, with no actions.

### Acceptance criteria

- [ ] The month view shows planned meals, not calendar text; a day with nothing planned is empty.
- [ ] Clicking a day opens the day view over the calendar; nothing important renders off-screen.
- [ ] The day view shows the day's religious context, the planned meals in time order, and the tool to add another.
- [ ] A meal of a kind with a default time pre-fills that time and allows a change.
- [ ] A meal of a kind without a default cannot be saved without a time being entered.
- [ ] Every planned meal displays its ready-by time wherever it appears.
- [ ] Nobody is asked to choose a day type; festival and weekend days still record correctly.
- [ ] A Temple Admin can set the temple's default times; kitchen staff cannot.
- [ ] Daily, Weekly and Monthly views each render the same plan at their own level of detail.
- [ ] A past day can be read but not changed.
- [ ] With no recipes in the temple, the planning tool explains and links to Recipes instead of offering an empty list.
- [ ] Fasting and festival days are visible on the month view without reading any text.
- [ ] A catering meal asks for a client; an outside event does not; neither pre-fills a time.

---

## E4-S8 — Today: the temple's morning screen

**Status:** DONE 2026-08-14. Written from the ISKCON Kitchen Design System's `TodayScreen`,
which invented it — we had not. **Partly superseded by E4-S14 (2026-08-20):** D1–D7 stand unchanged,
but the screen now groups meals by kind, counts plates per meal, and carries *Working today* and
*Cost of materials* where D2's shift and donation tiles were.

**Verified by:** UAT-062

**As a** Temple Admin or Kitchen Staff member, **I want** one screen that tells me what today needs,
**so that** signing in first thing in the morning shows me the state of the temple instead of an
empty form.

### Why

Rajeev, on seeing the mockup: *"Imagine you are a temple admin, you login for the day in the morning
and seeing a dashboard with all the things you need to run a tight ship is THE most important and
useful thing."* He is right, and we missed it: today an admin lands on their own **Profile**, which
tells them nothing about the temple. Every number this screen needs already exists in the backend —
meal plans, low stock, unfilled shifts, donations, purchase orders — but nothing brings them together.

This is the one screen in the kit that is a genuine product idea rather than a restyling, so it is a
story and not part of the design port.

### Decisions

**D1 — It is where a temple person lands after signing in.** Admin and kitchen staff both. Volunteers
keep landing on My shifts, and the platform operator on Temples — neither has a temple day to run.

**D2 — It answers four questions in one line each**, as tiles: how much are we cooking today, what
are we about to run out of, who is missing from a shift, and what has come in. Each tile is a link to
the screen that acts on it — a number nobody can act on is decoration.

**D3 — Anything that changes what the kitchen may cook is a banner, not a tile.** A fasting day
tomorrow changes every menu on it, so it says so across the top with a link to the plan. This is the
one place a warning is allowed to be loud.

**D4 — The meals of today are listed with their state** — planned, cooking, served — in ready-by
order (E4-S7), because that is the order the kitchen actually works in.

**D5 — Deliveries expected today** are listed against their purchase orders, with what has arrived,
what is awaited, and what is overdue, so the store keeper's first question is answered without
opening Purchase orders.

**D6 — It reads; it does not act.** Every action on it is a link to the screen that owns the action.
A dashboard that also mutates is how two screens end up disagreeing.

**D7 — Nothing is invented.** Every figure comes from an existing endpoint. Where a temple has no
data yet, the tile says what would put something there rather than showing a zero with no meaning.

### Requirements

- A `GET /api/v1/today` read model assembling: today's planned meals with status and ready-by; the
  count of items below their reorder threshold; shifts today and tomorrow with unfilled spots;
  donations received this month; purchase orders expected today and any overdue invoice; and whether
  today or tomorrow is a fasting day.
- One request, not six: the screen is the first thing loaded each morning, often on a phone.
- Behind `MANAGE_MEAL_PLANS`, the permission both temple roles hold; RLS-scoped like everything else.
- The screen, per D1–D7, built on the design system's `StatTile`, `Card`, `Badge` and `InlineNotice`.
- Landing changes for `TEMPLE_ADMIN` and `KITCHEN_STAFF`.

### Acceptance criteria

- [x] Signing in as a temple admin or kitchen staff lands on Today.
- [x] The four tiles show real figures from the temple's own data, and each links to the screen that acts on it.
- [x] A fasting day today or tomorrow shows as a banner naming which, with a link to the plan.
- [x] Today's meals list in ready-by order with their status.
- [x] Deliveries expected today appear with their purchase order and state.
- [x] A temple with no data yet shows what would fill each tile, not a wall of zeros.
- [x] The whole screen loads in one request.
- [x] A volunteer cannot reach it; a platform operator does not land on it.

---

## E4-S9 — The Vaishnava calendar, as its own screen

**Status:** DONE 2026-08-15. Asked for by Rajeev, from the ISKCON Kitchen Design System's
`CalendarScreen`.

**Verified by:** UAT-064 (to be written)

**As a** Temple Admin or Kitchen Staff member, **I want** the temple's own calendar on its own
screen, **so that** I can see what kind of day each day is before I decide what to cook on it.

### Why

The engine (E4-S1) has computed the whole calendar since the beginning, and until now the only way
to see any of it was one day at a time, inside the planner. A temple runs on this calendar: the
fasts decide the menu, the festivals decide the scale, and both are settled days ahead. It deserves
a screen.

### Decisions

**D1 — It sits directly below Today.** The two morning questions, in order: what day is it, and what
are we cooking.

**D2 — Month, week and year.** Month to plan the fortnight, week to read a day's detail across seven
days at once, year to find when the next festival falls.

**D3 — The colours mean exactly four things**, and the legend says so on the screen: Ekadasi,
fasting day, festival or feast, observance. Nothing else is coloured.

**D4 — Ekadasi outranks a festival.** When a feast falls on a fast, it is the fast that changes the
cooking, so that is what the day is coloured for.

**D5 — Every day panel ends in the same action** — plan this day's menu — because looking at the
calendar is almost always the step before planning against it.

**D6 — The kitchen note is ours, not the engine's.** The engine emits astronomy; the note says what
it means for the plate count. It lives in one place (`lib/vaishnava-day.ts`) so the calendar and the
planner cannot disagree.

**D7 — What we do not have, we do not invent.** The design's bhoga-offering times are temple
settings we have never collected, so they are left out rather than shown as a plausible guess.
Recorded here as the next thing this screen wants.

### Acceptance criteria

- [x] Month, week and year views, with a legend and prev/next/Today.
- [x] A day panel naming tithi, naksatra, masa, sunrise and sunset, and the day's events.
- [x] A fasting day and a feast day each say what they do to the cooking.
- [x] A hand-corrected day (E4-S3) says it was corrected, and why.
- [x] A temple with no calendar computed that far ahead is told so plainly.
- [x] Behind `MANAGE_MEAL_PLANS`; a volunteer cannot reach it.

---

## E4-S10 — Recording a meal, not a dish

**Status:** DONE 2026-08-20 (build brief §2). Replaces E4-S4's per-dish *Mark as cooked*.

**Verified by:** UAT to be written. Automated cover: `MealRecordingIT`,
`frontend/__tests__/meal-recording.test.tsx`.

**As a** Temple Admin or Kitchen Staff member, **I want** to type in what actually went out at a
meal, once, from the sheet the kitchen sends back, **so that** the store room depletes by what was
really cooked and the temple learns how wrong its head counts are.

**Assumptions:** Marking a meal cooked is the moment its ingredients leave stock — take it away and
the store room never depletes and the order list over-states what is on hand. So the status stays.
Everything around it was theatre and goes.

### Decisions

**D1 — Three states only: Planned, Cooked, Cancelled.** No *Cooking*. It is unobservable, nobody
with hot oil in front of them touches a screen, and a state inferred from a clock is one the app
invented.

**D2 — Recording is per meal, not per dish.** One form: every dish listed, planned servings
prefilled, editable to what actually went out, with *not made* beside each. The per-dish *Mark as
cooked* buttons are gone and `POST /api/v1/meal-plans/{id}/cooked` was **removed**. Naming every
dish in the request is required rather than optional — a dish left out is a dish nobody said
anything about, and deciding on the office's behalf whether it was cooked is the one thing this form
must not do.

**D3 — Actual servings are the point, and stock is drawn against them.** `target_servings` is never
overwritten; `actual_servings` sits beside it. Over a month the gap between the two tells the temple
its head counts are wrong, in which direction and by how much — which is the only thing that makes
the data entry worth doing. A dish marked *not made* draws nothing.

**D4 — Recorded by whoever is in the office, when the card comes back.** Not by a cook mid-service.
The recording carries who typed it, when, and an optional note — *"ran short, sent out at 220"*.

**D5 — A meal is the pair `(plan_date, meal_kind)`, and it gets a row of its own.** There is still no
meal-line table: one `meal_plans` row is one dish, and that shape is load-bearing for sufficiency,
the order list and Today. Splitting it into a parent and its children would have rippled through
every one of them to buy nothing the brief asks for. So `meal_services` (V64) carries only what
belongs to a whole meal — the card number and the recording — and is created on demand, so a temple
that never prints a card never accumulates empty rows.

**D6 — Today says the truth, not a badge.** *Lunch · 12:00 · not yet recorded*, and a nudge counting
the week's unrecorded meals, because stock silently overstates itself until somebody types the card
in. A nudge, not an alarm (E4-S14).

**Requirements:**
- V64: `meal_services` unique on `(tenant_id, plan_date, meal_kind)`; `actual_servings` and
  `not_made` on `meal_plans`; existing `COOKED` rows backfilled with their planned figure, since
  that is the only figure anyone ever gave and the only one their stock was drawn against.
- `POST /api/v1/meal-services/record` takes the whole meal; `GET /api/v1/meal-services` and
  `/summary` read them back grouped, in the order the kitchen works — by date, then by when each is
  due.
- Consumption (E3-S6) is written from the actual figures at recording time.
- Behind `MANAGE_MEAL_PLANS`; audited.

**Acceptance criteria:**
- [x] One form records the whole meal; there is no per-dish cooked action anywhere, in the API or on a screen.
- [x] Stock is drawn against actual servings, not planned.
- [x] A dish marked *not made* draws nothing and reads as cancelled at the stove rather than in the plan.
- [x] Recording twice is refused (`KMS-4962`); recording a cancelled meal is refused (`KMS-4963`).
- [x] Servings that are not a plausible figure are refused (`KMS-4009`).
- [x] Today shows *not yet recorded* for a meal nobody has typed in, and counts the week's unrecorded meals.
- [x] The planned figure survives the recording, so actual-against-planned can be read a month later.

---

## E4-S11 — The job card

**Status:** DONE 2026-08-20 (B5, build brief §3).

**Verified by:** UAT to be written. Automated cover: `JobCardIT`.

**As a** cook, **I want** one sheet of A4 that tells me everything this meal needs, **so that** the
kitchen works from paper and nobody has to hold a phone with oily hands.

**Assumptions:** One card per **meal kind** — a Breakfast card, a Lunch card. The documents pipeline
(V12, extended by V29) already does everything a job card needs: pending → ready, object storage, an
authorised download, and a language on the row. A third document kind is admitted to it explicitly.

### Decisions

**D1 — Marking off and signing are paper.** No app, no ticking, no friction. Rajeev: *"the sheet. No
fancy app. We want practical and usable with little to no friction."* The card carries sign-off boxes
— cooked by, checked by, served by — and the system never learns what was written in them.

**D2 — A card number is printed on it**, issued once on the first print and never re-issued —
*Lunch · 21 Aug 2026 · LC-2026-0142* — so a signed sheet in a folder can be traced back to its
record six months later. A number that changed between reprints could not do that. Per-tenant
counter, the same shape as the purchase-order sequence; gaps are fine, because the number identifies
a sheet rather than counting them.

**D3 — Printable by anybody who can see the meal plan, cooks included.** `MANAGE_MEAL_PLANS`. It is
their worksheet; putting it behind an admin permission would mean the cook has to ask for their own
job sheet.

**D4 — It prints in the temple's own language or in English, chosen at print time.** It defaults to
the temple's, because the card goes to the kitchen; print it twice if the head cook wants English
and the line cooks do not. Same shape as the purchase order, which already takes a language on its
print URL. Words are translated — dish names, ingredients, method, notes, the fixed labels. Numbers,
times, units and the card number never are, and English is the fallback when translation fails, so a
card always prints.

**D5 — It gathers; it does not compute.** Every figure comes from the service that owns it — scaled
quantities from `RecipeService.scale`, the fast from the calendar and the Ekadashi rule, the roster
from the staff schedule, the volunteers from their shifts — so the card cannot disagree with the
screens it was printed from.

**D6 — Cards are versioned, not overwritten.** A card reprinted after a dish was swapped is a
different sheet, and the kitchen may still be holding the earlier one. Recipe cards overwrite; this
one behaves like the purchase-order sheet.

**D7 — Translated labels are cached in a general table, not in the purchase order's.**
`document_label_translations` is `po_label_translations` with a label-set discriminator — what that
table would have had if it had been written second. Widening the shipped one would have meant
changing its unique key and the `ON CONFLICT` that targets it mid-build, for behaviour no temple can
see. The honest cost is two tables doing one job until somebody folds them together, and it is
recorded rather than left to be discovered.

**Requirements:**
- The card carries: temple, card number, meal kind, date, ready-by, occasion, head-count breakdown
  and what it scales to, the day's fasting and sattvic warnings, the client, venue and purpose where
  the kind has them, kitchen notes, every dish with its servings, scaled ingredients and method,
  equipment, the staff rostered and volunteers signed up, and the sign-off boxes.
- `POST /api/v1/job-cards` queues a PDF; `/print` renders the same card as HTML for a browser print,
  with no worker in the way. Both issue the number.
- A4, self-contained, Noto stack so Indic scripts shape into glyphs rather than tofu.
- V64 admits `JOB_CARD_PDF` to `documents` with an exhaustive target-shape CHECK.

**Acceptance criteria:**
- [x] A card prints for one meal with every dish, scaled to that dish's own servings.
- [x] The card number is issued once and survives reprinting.
- [x] The card defaults to the temple's language and can be printed in English, or any other, on request.
- [x] Kitchen staff can print their own card.
- [x] A fasting day names the offending ingredients on the card, per dish.
- [x] Reprinting produces a new version; earlier versions remain listed.
- [x] A meal that does not exist is refused rather than printed empty.

---

## E4-S12 — Swapping or editing a planned dish

**Status:** DONE 2026-08-20 (B4).

**Verified by:** Automated cover: `MealPlanIT`, `frontend/__tests__/meal-composer.test.tsx`.

**As a** Kitchen Staff member, **I want** to change a planned dish in place, **so that** correcting
the day's plan is one decision in the record rather than two.

**Assumptions:** Small, and it exists because of what cancel-and-re-add leaves behind: a cancelled
row that never went anywhere and a new one with no memory of what it replaced, so the day reads as
two decisions where the kitchen made one.

### Decisions

**D1 — Editable until the meal is recorded, never after.** What was cooked cannot be changed
afterwards (`KMS-4962`). The boundary is the recording, not the date.

**D2 — The edit form is the planning form.** Recipe, servings, ready-by, client, venue, purpose,
head-count breakdown and kitchen notes — the same shape as creating one. The first version of the
update request deliberately left the head count out, and the planner has wanted it ever since: the
commonest correction is not the recipe at all, it is that forty more people are coming.

**D3 — The Ekadashi rule is re-run on the edit.** Swapping a compatible dish for an incompatible one
on a fasting day raises the same acknowledgment E4-S6 requires; there is no silent path past it
through the edit.

**Acceptance criteria:**
- [x] A dish's recipe can be swapped and its servings changed without cancelling the row.
- [x] The head count and kitchen notes can be corrected in the same form.
- [x] Editing after the meal is recorded is refused (`KMS-4962`).
- [x] An edit onto an Ekadashi-incompatible recipe raises the E4-S6 acknowledgment.

---

## E4-S13 — What an outside event is for

**Status:** DONE 2026-08-20 (B6, build brief §1c).

**Verified by:** Automated cover: `MealPlanIT`, `frontend/__tests__/meal-composer.test.tsx`.

**As a** Kitchen Staff member, **I want** to say what an outside event is for, **so that** the
kitchen and the job card know whether they are cooking for a Bhagavad-gita reading, book
distribution or a school event.

### Decisions

**D1 — Free text, never a picklist.** The reasons a temple cooks for an outside event are
open-ended, and a list of five would be wrong by the sixth. Nothing in the system reasons about the
value: it is a label for the kitchen and for the job card, stored as the person wrote it.

**D2 — A flag on the meal kind, not a name the application recognises.** `meal_kinds.needs_purpose`,
modelled exactly like `needs_client` and `needs_venue` — so a temple that also wants a purpose on
its catering orders sets the flag and no code changes.

**D3 — *Catering order* and *Outside event* swap positions** (A7). They are ordered by `sort_order`
alone, so V64 is the whole change, with the provisioning seed altered to match for temples created
after it.

**Acceptance criteria:**
- [x] Planning an outside event asks what it is for, beside the venue.
- [x] The purpose appears on the job card.
- [x] The everyday kinds ask for nothing new.
- [x] Outside event now sits before Catering order in the kind list, for existing temples and new ones alike.

---

## E4-S14 — Today, rewritten around the meal

**Status:** DONE 2026-08-20 (A1–A4, B1, B2, build brief §1d, §8, §9). Supersedes parts of E4-S8.

**Verified by:** UAT-062 (to be reworked with this story). Automated cover: `TodayIT`,
`frontend/__tests__/today.test.tsx`.

**As a** Temple Admin or Kitchen Staff member, **I want** the morning screen to describe the day as
the kitchen actually experiences it, **so that** the first thing I read is true.

**Assumptions:** E4-S8's seven decisions stand — it reads and never acts, nothing is invented, and
every tile links to the screen that owns it. What changed is what it says, not what it is.

### Decisions

**D1 — A meal, not a dish.** The screen listed one row per preparation, so a lunch of three dishes
read as three lunches. Meals are now grouped by kind with their dishes beneath, each a link through
to that day's planner.

**D2 — Plates are counted per meal, from its own head count, never as a sum of dish servings.** A
lunch of three dishes at 250 servings each is 250 plates, not 750 — the previous code reported the
second. The tile sums across breakfast, lunch and dinner, which is three plates for the same person
and is the right answer to "how much are we cooking today", but never across the dishes of one meal.

**D3 — *Working today* replaces *Shifts unfilled*.** The old tile warned about a shift on an unnamed
date and gave an admin nothing to act on. The figure comes from `WorkforceService` (E6-S14) — the
same source the week grid's column totals and the planner pebbles read.

**D4 — *Cost of materials* replaces *Given this month*.** Money coming in moved to the donations
ledger (E7-S10), where somebody goes to look at it deliberately and where it now has a period
control and a year-on-year comparison; a month-to-date figure on a morning screen had neither. The
cost figure comes from E3-S8 and says how many ingredients it could not price.

**D5 — The truth, not a badge.** A meal reads *not yet recorded* rather than wearing a status chip,
and an unrecorded-meal nudge counts the week's and says why it matters: stock only leaves the store
room when a meal is recorded (E4-S10).

**D6 — Platform notices sit above all of it** (E9-S1). A supplier recall is not a thing to scroll
past.

**D7 — Two figures on this screen are read from elsewhere on purpose.** The workforce count and the
plate count. A dashboard computing its own is how it comes to disagree with the page it links to.

**Requirements:**
- `TodayView` carries meals grouped by kind with their dishes, plates per meal, the workforce pair,
  the materials cost with its unpriced count, the unrecorded-meal count, and the existing calendar,
  stock and delivery lines.
- Still one request, still `MANAGE_MEAL_PLANS`, still nullable-not-zero for anything the reader may
  not see.
- Festival names on the monthly planner truncate the way the calendar's do (A5); the planner's
  ready-by hint line no longer sits a line above its neighbours (A6).

**Acceptance criteria:**
- [x] A lunch of three dishes reads as one lunch with three dishes beneath it.
- [x] The plates tile reports each meal's head count, and never a sum of dish servings.
- [x] Every meal links through to that day's planner.
- [x] *Working today* shows staff and volunteers separately and agrees with the roster and the planner.
- [x] *Cost of materials* shows an estimate and names how many ingredients had no known price.
- [x] An unrecorded meal says so; the week's unrecorded meals are counted as a nudge, not an alarm.
- [x] Undismissed platform notices appear above everything else.
