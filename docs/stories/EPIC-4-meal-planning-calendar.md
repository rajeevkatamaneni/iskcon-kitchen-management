# EPIC 4 — Meal Planning & Vaishnava Calendar

**Goal:** The astronomical Vaishnava calendar engine (post-2006/GCAL schema, per-tenant location), named festival occasions, a calendar-aware meal planner across four contexts, Ekadashi violation flagging, and inventory sufficiency feeding the ordering pipeline.
**Depends on:** Epics 1–3. **Blocks:** Epic 5 (order generation consumes shortfalls).
**Labels:** `epic:planning`

**Reference material:** `Menu and Quantity calculation for cooking_ICC.xlsx` — real per-festival menu tabs (Nityanand Trayodasi, Gaurpurnima, Ram Navami, JPS Vyasapuja, Narsimha Chaturdasi, Snana Yatra, Panihati, Ratha Yatra, Balaram Purnima, Janmastami, SP Appearance Day, Radhastami, Temple Anniversary, Sharad Purnima, SP Disappearance Day, Govardhan Puja, RNS Vyasapuja) — this is the festival vocabulary the planner must speak.

---

## E4-S1 — Calendar engine: astronomical computation

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

---

## E4-S4 — Meal plan CRUD across four contexts

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
