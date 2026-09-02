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
- **Three inline copies of the unit-family rule remain** in `InventoryItemService.adjust`,
  `DonationRecorder` and `IngredientRequestService`, each refusing with the generic `KMS-4001` rather
  than `IngredientUnits.requireSameFamily`'s `KMS-4013`. Recorded under `BL-9`.
