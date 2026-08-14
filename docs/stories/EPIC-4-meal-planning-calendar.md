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
> replaced there; the underlying CRUD, the calendar awareness and the cook-from-plan behaviour stand.

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

**Status:** DRAFT — written 2026-08-14 from Rajeev's redesign of the Meal plan screen. **Not yet
approved; no code written.** Supersedes parts of E4-S4 (see *What this replaces*).

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

### Open — needs Rajeev's decision before build

- **O1 — A minimal mark for fasting and festival days on the month view.** Recommended: a dot or a
  thin bar, no text. Without it, someone scanning a month for a free day cannot see that the 9th and
  23rd constrain the menu, or that Janmashtami is coming, without opening days one at a time.
- **O2 — Sunrise only, or sunrise and sunset?** Sunrise decides which day a fasting day is and when a
  fast may be broken; nothing we compute uses sunset. Recommended: show sunrise, drop sunset, and let
  the temple overrule.
- **O3 — May an admin correct the calendar on a past date?** Read-only planning is settled (D7); a
  past correction is arguably still legitimate for the record.
- **O4 — Are "Catering order" and "Outside event" genuinely different kinds?** They may be one thing
  with two names — a meal cooked for someone else, somewhere else.

### Requirements

- **Meal kinds** replace meal slots: name, optional default ready-by time, sort order; seeded per D1;
  a Temple Admin can add, rename, reorder and set times. Existing planned meals keep their kind.
- **`ready_by` on every planned meal**, required at the service layer, defaulted from the kind where
  the kind has one.
- **Day type is derived server-side** from the date and calendar and stored; it is not accepted from
  the client.
- **Client and venue** are captured only for kinds that need them (catering, outside event).
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
