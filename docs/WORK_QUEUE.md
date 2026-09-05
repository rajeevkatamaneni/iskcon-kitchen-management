# Work queue — what to pick up next, in order

**Read this at the start of a session, after `docs/OUTSTANDING_BUILD_LIST.md`.**

This file is *ordered*. Item 1 is the next thing to build unless Rajeev says otherwise. It exists
because neither of the neighbouring files answers "what next": `OUTSTANDING_BUILD_LIST.md` is
Rajeev's own review list and is binding but unordered, and `docs/stories/BACKLOG.md` is explicitly
work that is *not* scheduled. This is the scheduled queue.

When an item is built, delete its block and note it in `docs/CHANGELOG.md`. Do not leave a tombstone
here — the backlog keeps closed entries, this file does not.

---

## 1. Make the deployment pipeline quick

**Asked for by Rajeev, 2026-09-01,** after watching a deploy of an unchanged-dependency build take
about twenty-five minutes. His words, near enough: *is this typical?* It is not; a tuned pipeline for
a project this size is three to eight minutes, and the gap is all avoidable work.

**Measured on the 2026-09-01 deploy of `95529d0`** (`./infra/deploy.sh iskcon-kms-2026 staging`):
three Cloud Builds at 22:44, 23:05 and 23:19, the frontend image alone taking 5m07s, then three
Cloud Run rollouts one after another. Nothing in that release changed a single dependency.

**Where the time goes, in the order worth fixing:**

1. **No dependency caching.** Each image build re-downloads the whole Gradle and npm dependency tree
   inside a fresh container. This is the largest share and the purest waste. Fix by splitting the
   Dockerfiles so dependency resolution is its own layer above the source copy — `COPY build.gradle
   settings.gradle` then resolve, *then* `COPY src`; the npm equivalent is `COPY package*.json` then
   `npm ci`. A Cloud Build cache image (`--cache-from`) or Kaniko gets the rest.
2. **The two images build serially** and have no reason to. They share nothing.
3. **The three services roll out serially** — api, then worker, then web. The worker and the api use
   the same image; the web depends on the api only for its URL.
4. **Each rollout waits on a health check**, and the api's includes Flyway inspecting 86 migrations
   at startup. Worth measuring before touching: this may be a small share, and Flyway's check is not
   something to weaken for speed.

**One real constraint, not laziness.** The frontend must be built *after* the api is deployed:
`NEXT_PUBLIC_API_URL` is inlined at build time, and `deploy.sh` resolves it from the running api
service. Any parallelism plan has to keep that ordering, or resolve the URL another way (it is
predictable from the service name and project, which would break the dependency — consider it, but
the current approach fails honestly when the service is missing, and that is worth keeping).

**Do not** trade away the migration check, the health check, or the "build once, deploy the same
digest" property to make the number smaller.

**Verify by measuring**, not by feel: record the before and after wall-clock of a full deploy in the
changelog entry, the way the numbers above were recorded.

---

## 2. Equipment: servicing, and a screen for the register

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

## 3. Events, the end of catering, and knowing when to leave

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

## 4. English to Kannada comes back word-reversed

**Reported by Rajeev, 2026-09-04**, recalled from the demo: translating **"Hot water"** to Kannada
produced **"Water Hot"**.

Needs an investigation before it can be estimated. One thing to aim it with: Kannada puts the
adjective before the noun exactly as English does — *bisi niru* is "hot water", same order — so a
reversal is **not** a grammar difference. That points at the words being translated separately and
reassembled, or the source string being split before it was sent. A hypothesis, not a diagnosis:
capture the actual request and response first.

Affects the job-card language feature (`OUTSTANDING_BUILD_LIST.md` P4), which offers all 22 scheduled
languages and translates live on demand. A mangled ingredient name on a job card in a kitchen is
worse than an untranslated one.

---

## Also waiting — raised on 2026-09-01, not yet ordered

These came out of the review build and its verification. Rajeev has seen each one but has not said
where they sit; ask before assuming any of them outranks item 1.

- **Local development bypasses row-level security.** `application.yml` defaults `DB_USER` to `kms`,
  the compose superuser, and `V1`'s grants for the unprivileged `kms_app` role are gated on
  `IF EXISTS` — which it does not, locally. So the application runs locally as a superuser and RLS
  does nothing, which is the exact trap `CLAUDE.md` warns about. Symptom: other temples' rows appear
  in `/users` and `/staff`. Production and CI are unaffected. Fix is a local `kms_app` role plus a
  separate migrator role for Flyway.
- **A shift crossing midnight is refused** (`20:00`→`02:00`, KMS-5001), for a temple whose largest
  festival is at midnight. Found while seeding Janmashtami.
- **A festival occasion's `defaultServings` no longer reaches the meal composer.** The head-count
  change (E4-S7 D14) made all three counters open at zero unconditionally. Rajeev's rule was that the
  application does not *guess* a head count — but an occasion's stored default is a figure the temple
  typed for that festival, which is arguably not a guess. His call. `UAT-030` currently asks the
  tester to record what actually happens rather than asserting either behaviour.
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
- **`CrewPebble` uses a native `title=` tooltip** (`MealServices.tsx`) — hover-only, no keyboard and
  no touch route, which is the failure `InfoHint` was built to avoid. Found during the hint sweep and
  left alone because it is not sub-text under a control. One small conversion.
- **The job card no longer filters equipment by kind.** It used to print only MACHINE and TOOL, to
  keep trestle tables off the sheet; the category was removed on 2026-09-04 at Rajeev's instruction
  and there is now no way to tell a table from a grinder, so the card lists everything not scrapped.
  If that turns out to be noise on a real card, the fix is a flag on the equipment, not the category
  coming back.
- **`components/Field.tsx` is the shared field component and only 7 of 86 hints went through it.**
  The other ~50 screens hand-roll the same markup. Worth consolidating one day; it is why the hint
  sweep was fifty edits rather than one.
- **Three inline copies of the unit-family rule remain** in `InventoryItemService.adjust`,
  `DonationRecorder` and `IngredientRequestService`, each refusing with the generic `KMS-4001` rather
  than `IngredientUnits.requireSameFamily`'s `KMS-4013`. Recorded under `BL-9`.
