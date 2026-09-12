# Work queue — what to pick up next, in order

**Read this at the start of a session, after `docs/OUTSTANDING_BUILD_LIST.md`.**

This file is *ordered*. Item 1 is the next thing to build unless Rajeev says otherwise. It exists
because neither of the neighbouring files answers "what next": `OUTSTANDING_BUILD_LIST.md` is
Rajeev's own review list and is binding but unordered, and `docs/stories/BACKLOG.md` is explicitly
work that is *not* scheduled. This is the scheduled queue.

When an item is built, delete its block and note it in `docs/CHANGELOG.md`. Do not leave a tombstone
here — the backlog keeps closed entries, this file does not.

---

## 1. Equipment: servicing, and a screen for the register

**BUILT and deployed to staging 2026-09-05. Awaiting Rajeev's test.**

**Asked for by Rajeev, 2026-09-04.** Stories **E3-S10** and **E3-S11**.

The equipment register has existed since E3-S4 — tables, controller, tests, `V16` — and **has never
had a user interface**. No page, no API client, no menu entry. That is why nobody has seen it.

This builds the servicing half and the screen at once: a service recorded as an event rather than a
date somebody types over, an interval in days/weeks/months/years, a derived next-service date, red
past due and amber inside the temple's own horizon, service companies stored once in their own small
list, purchase cost, warranty expiry, serial number, and an overdue count on the Temple Admin's
dashboard. E3-S4's Phase 2 assumption was overruled by Rajeev on 2026-09-04 and the requirement bumped
to v1.4 to match.

**No link from the wish list.** Asked for and withdrawn the same day: funded, bought, delivered and
registered are four moments, and only the temple knows the fourth.

---

## 2. Events, the end of catering, and knowing when to leave

**BUILT and deployed to staging 2026-09-05. Awaiting Rajeev's test.**

**Asked for by Rajeev, 2026-09-04.** Stories **E4-S15** and **E4-S16**.

Three main meals a day, cooked for the temple's household; everything else is an **event** with its
own name, its own preparation and its own job card. One Event kind absorbs *Outside event*, and
**catering is removed from the product entirely** — the day type, the kind, the `needs_client` flag,
UAT-033 and the never-built *Upcoming catering* table. `DayType.CATERING` was checked first and has
no reader in product code. Plans the temple actually cooked are migrated, not deleted.

Then the travel estimate: Routes API on the existing GCP project, authenticated as the Cloud Run
service account, capped by per-API daily quota rather than a budget (a budget only alerts), and
**computing nothing that is stored** — the Maps terms permit caching coordinates and not durations.
Expected bill at India pricing: zero. The screen says *leave the temple by 11:15*, not *it takes 40
minutes*, because that is the sentence a driver can act on.

---

## 3. The gap sweep of 2026-09-06/07 — seven items, in this order

Found by reading the code rather than the documents, after three of the twelve "never built" items
turned out to be built. **Check any item here against the code before scheduling it**: the documents
were wrong a third of the time, always in the direction of describing finished work as undone.

Full register, with the 31 unrecorded gaps and the 9 built-but-never-proven items:
the UAT Docket artifact (ask Rajeev for the link, or `/artifacts` in Claude Code).

1. ~~**The broadcast limit has no screen.**~~ **BUILT 2026-09-07.** Settings → Volunteer messages.
2. ~~**No operator view of the audit log.** Narrower than `TRACEABILITY.md` G9 records: `/audit`
   exists and is in the menu for Temple Admins. Only the operator's per-temple drill-in is missing,
   and its design is already agreed — drill into one temple, never a cross-tenant firehose.
   `drillIntoTenantAudit` is written and uncalled. **Rajeev asked to see what is there before
   anything is built.**~~ **CLOSED WITHOUT BUILDING, 2026-09-12, by Rajeev:** *"I will verify during
   my UAT and let you know if I need nay changes. Consider it good for now and mark it done and
   remvoe it from the list."* Left struck through rather than deleted because this list's numbering
   is quoted elsewhere.
3. ~~**No screen manages festival occasions** (G3).~~ **BUILT 2026-09-07** (`67d5f05`, task T-004),
   deployed to staging, **not yet seen working by Rajeev**. Settings → Festival occasions, at
   `/settings/occasions`: add, rename and remove, Temple Admin only. A screen over a backend that
   was already finished — `OccasionController` had full CRUD behind `MANAGE_TEMPLE_SETTINGS` and the
   app called only the list. Left here struck through rather than deleted because this list's own
   numbering is quoted elsewhere as item 3.3.
4. **The kitchen staff role is the least finished in the product.** One job, not five — and two of
   the five have since been done.
   - ~~They cannot see their own schedule~~ (G6). **BUILT 2026-09-07** (`bfca0ac`, task T-006):
     `/my-schedule`, and for admins and managers as well, because all three hold `VIEW_OWN_SHIFTS`
     and none of them had a route to it. G6's recorded cause was wrong in both halves, see below.
     ~~One thing it still does not show is approved leave, which needs a backend change.~~
     **The leave half was BUILT 2026-09-07** (`49ce170`, task T-032), closing `DECISIONS.md` **D-16**'s
     last open gap: a full day of approved leave now reads as the leave with its label instead of
     hours, a half day keeps its hours and is marked, and both are resolved on the server by the same
     `ScheduleResolver` call the manager's week grid makes. **Not yet seen working by Rajeev** — it
     wants a rostered person with approved leave in the next fortnight.
   - ~~The Today tile sends them to a page that refuses them~~. **BUILT 2026-09-07** (`81fd72f`,
     task T-002) — the count stays, it is simply not a link for a reader who may not go there.
   - Still open: they hold `REQUEST_OWN_LEAVE` and have no menu route to it; and Download and Print
     appear on their own approved ingredient request and both 403.
   - ~~*My shifts* can never contain anything for them.~~ **BUILT 2026-09-07** (`429f63f`, task
     T-031), deployed to staging: `DECISIONS.md` **D-16** completes D-10, and the page guard and the
     menu row now admit `VOLUNTEER` alone. Their own rostered days are `/my-schedule`. The
     staff-facing empty state T-002 wrote went with it — it pointed at `/shifts`, which refuses the
     same cook, and with the guard narrowed nobody is left to read it.

   **Neither of the two built halves has been seen working by Rajeev**, so neither is closed.
5. **A recorded meal cannot be corrected.** Agreed with Rajeev, 2026-09-07: **not a reopen but a
   correction.** The backend already has the primitive — a compensating entry that reverses the
   stock while leaving the original readable — so the screen says "640 plates, corrected from 400 by
   X on Y" and the audit trail comes for free. `StockMovementController` has it; nothing calls it.
   This one screen also closes most of the nine "a mistake is permanent" findings in the docket.
6. **A volunteer shortfall cannot raise a shift from the planner,** and a shift raised there cannot
   be seen there afterwards. Confirmed never built — `createShift` has never appeared in a planner
   file in the whole history. The overlay Rajeev remembers was on the Volunteers page and became a
   screen on 2026-08-21 (`b84dcd0`) under his own four-fields-becomes-a-screen rule.

**Decided and closed on 2026-09-06/07, so nobody re-opens them:**

- **The sattvic-prohibited flag** — **DELETED 2026-09-08** (`070d9ea` and `d441c8e`, tasks T-050 and
  T-051, decision **D-18**). Ruled by Rajeev after tracing how ingredients actually reach a
  catalogue: only provisioning's eleven seeded rows ever carried the flag, and import — the bulk
  path — creates everything unflagged, so rice was restricted on a fast day and maida was not, by
  nothing but how each got in. The column, the provisioning seed and every enforcement site are gone,
  `V98` dropped the two columns, and `KMS-400037` and `KMS-400104` are retired. **A new temple now
  starts with an empty ingredient catalogue and enforces nothing on a fast day until an admin flags
  things by hand** — accepted deliberately, because partial coverage looks like knowledge. What makes
  it honest is the warning box now standing on `/recipes` in Rajeev's own words. Everything Ekadashi
  survives untouched. **Not yet seen working by Rajeev** — `/recipes`, `/ingredients` and
  `/ingredients/new` want one pass together.

- **English to Kannada came back word-reversed** — the item that used to sit here. The translator was
  innocent: probed against the real API, "Hot water" returns ಬಿಸಿ ನೀರು correctly. The library files
  names the way a reference book does — 882 of 6,333 are written "Water, hot" — and a faithful
  translation of an inversion reads backwards in a language that has no such convention. The name is
  un-inverted before it is sent (`IngredientNames.readable`); what is *stored* is untouched, so the
  picker still sorts the three waters together. `V94` emptied the translation cache once. Fixed
  2026-09-06 (`701e582`).

- **Job card languages** — already built since 2026-08-22. All 23 offered, translated on demand.
  Rajeev asked whether the picker could show without the recipes checkbox and translate the whole
  card, with a proposal to print the worksheet's labels bilingually. **Ruled: leave exactly as it
  is.**
- **The Operations redesign** — already built, including the seven-day pulse. **Ruled: leave as is.**
- **The day-one dataset** (D1) — parked as its own task. The plan: *one* reference temple seeded
  realistically for training and the written manual; every other UAT temple starts bare so testers
  walk the real onboarding path.
- **The empty recipe dropdown** — hint only, **no shortcut button on the planner**. Choosing recipes
  is a setting-up step; the planner is opened most days. Built 2026-09-06.
- **Date formats** — day-first with a month name is already everywhere. Native date pickers follow
  the reader's device and are deliberately left alone; revisit only on a complaint.

~~**Waiting on Rajeev — one env var, and it is deliberately not set.**~~ **Struck 2026-09-08.
Nothing was waiting on anybody.** The entry claimed `GEOCODING_PROVIDER` was unset on staging and
that T-042 (wave 4c, D-17) was therefore inert there. **It is set, to `nominatim`** —
`infra/environment/main.tf:437` hardcodes it, the live revision `kms-staging-api-00116-7b4` carries
it, and the feature was driven working in a browser. The config file's `none` is only a default, and
a default is not a deployment.

**And it is now deleted rather than kept.** D-19 (Rajeev, 2026-09-08) replaced geocoding at
provisioning with the Google Places autocomplete picker the delivery-address field already uses, and
took Nominatim and every OpenStreetMap trace out of the tree. Measured before it went: the full
temple street address returns nothing from OSM, and the locality resolves ~600 m from the building.

**BUILT AND DEPLOYED 2026-09-08**, wave 4e-2, tasks **T-053** and **T-054**, with **T-057** carrying
the environment. `GoogleGeocodingProvider` sits behind the surviving port on the Maps key the other
three Google services already share; `/tenants/new` picks a place instead of geocoding a string;
`GeocodingController`, `GeocodedAddressView` and `api.geocodeAddress` are deleted for want of a
caller. **Not yet seen working by Rajeev, and one thing genuinely cannot be checked from here**: the
first real Google geocoding call this code has ever made happens on staging, and a key whose API
restrictions omit Geocoding fails closed and silent — the only symptom is `REQUEST_DENIED` in the api
log. See the Application entry of 2026-09-08 in `docs/CHANGELOG.md`.

The last sentence of the struck entry — *"It is free, needs no key and no billing"* — is the reason
Nominatim was chosen in the first place, and **that reasoning is now forbidden on this project**.
Rajeev: *"Paying for a quality service should NEVER be a consideration. It is THE DEFAULT answer."*
Argue a free option on its merits or not at all.

**Waiting on Rajeev:** what sits behind a temple-health indicator, and where it lives. Note that
`BACKLOG.md` BL-1 is wrong about this one in the other direction — it claims the backend already
serves a per-temple health read, and no such endpoint exists.

---

## Also waiting — raised on 2026-09-01, not yet ordered

These came out of the review build and its verification. Rajeev has seen each one but has not said
where they sit; ask before assuming any of them outranks item 1.

- **Local development bypasses row-level security.** ~~`application.yml` defaults `DB_USER` to
  `kms`, the compose superuser, and `V1`'s grants for the unprivileged `kms_app` role are gated on
  `IF EXISTS` — which it does not, locally. So the application runs locally as a superuser and RLS
  does nothing, which is the exact trap `CLAUDE.md` warns about. Symptom: other temples' rows appear
  in `/users` and `/staff`. Production and CI are unaffected. Fix is a local `kms_app` role plus a
  separate migrator role for Flyway.~~
  **Done, in two halves.** The application half was fixed on 2026-09-05 in `89777ec`:
  `infra/local/01-roles.sql` now creates `kms_app` and `kms_migration`, `docker-compose.yml` mounts
  it into the container's init directory, and `application.yml` defaults `DB_USER` to `kms_app`. The
  row above was simply never updated and stayed misleading for six days. The **migrator** half was
  fixed on 2026-09-11 as T-145: `README.md` had gone on telling every developer to run
  `DB_MIGRATION_USER=kms`, so migrations — not the application — still ran as the superuser locally,
  with RLS off and the tables owned by a superuser, while tests and Cloud SQL ran them unprivileged.
  Proof, including the live before/after on a throwaway database, is in
  `docs/work/proof/T-145.md`.
- **A shift crossing midnight is refused** (`20:00`→`02:00`, KMS-500001), for a temple whose largest
  festival is at midnight. Found while seeding Janmashtami.
  **Built 2026-09-12 as T-146, on staging, not yet verified by Rajeev.** A shift's date is the day it
  starts; an end time at or before the start means the next day, and screens print
  *"20:00–02:00 (next day)"*. Equal start and end times are refused by name (`KMS-400001`). Proof in
  `docs/work/proof/T-146.md`.
- **A festival occasion's `defaultServings` no longer reaches the meal composer.** The head-count
  change (E4-S7 D14) made all three counters open at zero unconditionally. Rajeev's rule was that the
  application does not *guess* a head count — but an occasion's stored default is a figure the temple
  typed for that festival, which is arguably not a guess. His call. `UAT-030` currently asks the
  tester to record what actually happens rather than asserting either behaviour.
- **`TRACEABILITY.md` G6's cause is wrong in both halves** (corrected in that file 2026-09-07, noted
  here because the queue points at it): `MANAGE_STAFF_SCHEDULE` is not admin-only — `KITCHEN_MANAGER`
  holds it — and `GET /api/v1/staff/schedule/me` already exists behind `VIEW_OWN_SHIFTS`, which
  kitchen staff hold, with a client method nothing calls. Only the screen is missing, which makes it
  far cheaper than the row implied. The same hole is wider than kitchen staff: a Temple Admin holds
  `VIEW_OWN_SHIFTS` too and has no *My shifts* entry either.
- **`docs/uat/TRACEABILITY.md` §1 is stale** for everything after 2026-08-20 — E3-S8, E4-S9 onward
  and E6-S10 onward are missing. It wants one pass of its own.
- **`docs/stories/github-import/` has been behind since E1-S12** and is a job of its own, per
  `CLAUDE.md`.
- **A settings test has been passing for the wrong reason.** `settings-payments`' "connects to
  WhatsApp" case only passed because the *hint string* contained the words "message templates".
  Removing the hint (2026-09-04) exposed it: the connected panel it appears to assert never renders,
  because `SettingsView`'s fetch effect depends on `getToken` and the `useAuth` mock returns a fresh
  `getToken` every render, so the effect re-runs and overwrites the just-saved settings with the
  stub. Same effect-identity trap as the flash-capture loop, different state. **The connect-then-
  render path is currently untested.**
  **Built 2026-09-12 inside T-151, not yet verified by Rajeev.** The mock now returns one stable
  `getToken`, and the connected panel is asserted directly. Proof in `docs/work/proof/T-151.md`.
- **`CrewPebble` uses a native `title=` tooltip** (`MealServices.tsx`) — hover-only, no keyboard and
  no touch route, which is the failure `InfoHint` was built to avoid. Found during the hint sweep and
  left alone because it is not sub-text under a control. One small conversion.
  **Built 2026-09-12 as T-147, not yet verified by Rajeev.** The breakdown is an `InfoHint` beside
  the pill, reading *"More about crew for Lunch"* (or the event's name). Proof in
  `docs/work/proof/T-147.md`.
- **The job card no longer filters equipment by kind.** It used to print only MACHINE and TOOL, to
  keep trestle tables off the sheet; the category was removed on 2026-09-04 at Rajeev's instruction
  and there is now no way to tell a table from a grinder, so the card lists everything not scrapped.
  If that turns out to be noise on a real card, the fix is a flag on the equipment, not the category
  coming back.
- **`components/Field.tsx` is the shared field component and only 7 of 86 hints went through it.**
  The other ~50 screens hand-roll the same markup. Worth consolidating one day; it is why the hint
  sweep was fifty edits rather than one.
- **Three inline copies of the unit-family rule remain** in `InventoryItemService.adjust`,
  `DonationRecorder` and `IngredientRequestService`, each refusing with the generic `KMS-400001` rather
  than `IngredientUnits.requireSameFamily`'s `KMS-400013`. Recorded under `BL-9`.
  **Built 2026-09-12 as T-150, not yet verified by Rajeev.** All three now answer `KMS-400013` with a
  field error naming the ingredient. **No screen shows that field line yet**, so UAT-081 step 17
  still fails on the donation screen; that is queued as T-154. Proof in `docs/work/proof/T-150.md`.
