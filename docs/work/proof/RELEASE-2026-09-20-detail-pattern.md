# Release 2026-09-20 — the detail-pattern wave (T-440, T-441)

Fifth release of 20 September, after `RELEASE-2026-09-20.md` (curated recipes),
`-menu-layout`, `-staff-inventory` and `-outside-events`. Rajeev's standing approval for this build
series covers the commit, CI and the staging deploy. Deploy is to **staging only**.

Built in the worktree `/Users/Rajeev/Workspace/kms-detail-pattern`, branch `wave-detail-pattern`,
off `origin/main` `bd8ba467`. `git fetch` before the first commit and again before the push:
**`origin/main` had not moved either time**, so there was no rebase and nothing had to be
re-measured.

**Deployed from the main checkout, not the build worktree** — the reverse of the last release, and
deliberate. `deploy.sh` ships the working tree, and the build worktree has two things in it that
should not go to staging: `docs/DESIGN_SYSTEM.md`, left modified on purpose (see the last section),
and a `frontend/node_modules` symlink. So the main checkout was fast-forwarded to the pushed `HEAD`
`0a88f93e` and deployed from there. Its tree carries one untracked file,
`docs/work/SEEDING-BRIEF.md`, which is outside both build contexts (`backend/` and `frontend/`) and
cannot reach an image.

## What shipped

| Commit | |
|---|---|
| `df565f0f` | `feat: an inventory item opens as a record, and changing it is its own screen` (T-440) |
| `04c51437` | `feat: an ingredient or a supply opens as a page, and changing it is its own screen` (T-441) |
| `0a88f93e` | `docs: the detail-pattern wave — the changelog entry and the dispatch record` |

Two code commits because the file sets are disjoint — T-440 is `app/inventory/**` and
`__tests__/inventory*`, T-441 is `app/ingredients/**`, `app/supplies/**`, `IngredientForm.tsx`,
`ingredient/IngredientFacts.tsx` and their tests. Nothing appears in both. **Everything was staged
by explicit path**: `frontend/node_modules` in that worktree is a symlink to the main checkout's
modules, and `.gitignore`'s `node_modules/` with its trailing slash does not match a symlink, so
`git add -A` there would have committed 645 MB of somebody else's dependencies.

**No migration.** Flyway stays at **V159** and the next free is still **V156**. **No new error
code** — still **KMS-400192**. **No new permission.** **No backend file was changed by either
task**, which is why the backend suite below was run to confirm it was unchanged rather than to
prove anything new.

## The gate: the committed tree, not the working tree

`git archive HEAD | tar -x` into `/tmp/kms-verify` at `04c51437`, then `git init -q && git add -A`
in it before running anything — **2,311 files**. The `git init` is not optional:
`frontend/__tests__/design-system.test.ts` enumerates what it audits with `git ls-files`, and in a
bare archive it dies with `fatal: not a git repository`. **It ran — 23 tests**, read out of the
vitest log. The log holds no `not a git repository` at all.

The archive was taken at `04c51437` rather than at the final `0a88f93e`, because the docs commit
that followed it touches `docs/CHANGELOG.md` and `docs/work/DISPATCH.md` and nothing else —
`git diff --stat 04c51437 0a88f93e` is those two files, 138 insertions. No file under `backend/` or
`frontend/` differs between the tree that was verified and the tree that was deployed.

**Every figure below is from the runner's own summary or the JUnit XML. None is an exit code** —
exit codes have lied on this project repeatedly, and T-441's builder was handed a notification
claiming exit 0 over a log reading `exited 1`.

| Check | Result |
|---|---|
| `npm ci` | clean install |
| `npx tsc --noEmit` | **no output at all** (`tsc.log` is 0 bytes) |
| `npx eslint .` | **no output at all** (`eslint.log` is 0 bytes) |
| `npx vitest run` | **192 files, 2796 tests, 0 failed** |
| `npm run build` | `✓ Compiled successfully`, `✓ Generating static pages (77/77)` |
| `./gradlew test` | **Total 3783, Passed 3776, Failed 0, Skipped 7** — `BUILD SUCCESSFUL in 15m 47s` |

**The backend figure matches its last release exactly** — 3783 / 0 / 7 — which is what a wave that
changed no backend file should produce, confirmed rather than assumed. It was cross-checked a second
way: summing `tests`/`failures`/`errors`/`skipped` across the 263 JUnit XML files in
`build/test-results/test/` gives `tests=3783 failures=0 errors=0 skipped=7`. Two independent reads,
the same numbers.

The frontend gained **2 files and 19 tests** (190/2777 → 192/2796). The two new files are
`inventory-edit.test.tsx` (13) and `ingredient-edit.test.tsx` (16); the rest of the arithmetic is
T-441 deleting the assertions about an editing row that no longer exists.

`next build` lists both new routes, which is the export check tsc and vitest cannot make:
`ƒ /inventory/[id]/edit  2.21 kB` and `ƒ /ingredients/[id]/edit  1.56 kB`.

## CI

Run **35560712609** — https://github.com/rajeevkatamaneni/iskcon-kitchen-management/actions/runs/35560712609

All three jobs `success`: Repository (hygiene), Frontend (Next.js), Backend (Spring Boot).

## The database was backed up first

`gcloud sql backups create --instance=kms-staging-5325bd0d`, taken before anything was pushed.
Read back from a fresh `gcloud sql backups list` rather than from the create call's own output:

```
ID             STATUS      END_TIME                  TYPE       DESCRIPTION
1789963454316  SUCCESSFUL  2026-09-21T04:05:45.509Z  ON_DEMAND  pre-release detail-pattern wave T-440 T-441
```

## The deploy, confirmed by evidence and not by the exit code

`cd infra && ./deploy.sh iskcon-kms-2026 staging` — builds 7m01s, rollouts 2m26s, **total 9m30s**.
The wrapper's exit code is ignored on principle here; what follows is the evidence.

**All three digests changed.**

| Service | Before | After | Digest before → after |
|---|---|---|---|
| api | `00177-vlx` | **`00178-fmv`** | `sha256:3cc43f99…c0ee0` → **`sha256:d42d6692…1a12c`** |
| web | `00165-6x9` | **`00166-nvm`** | `sha256:c8856b8f…38981` → **`sha256:173531b2…88461`** |
| worker | `00159-dts` | **`00160-ldj`** | `sha256:3cc43f99…c0ee0` → **`sha256:d42d6692…1a12c`** |

api and worker share the api image, as they always do.

**Flyway, from the new api revision's own logs** (`kms-staging-api-00178-fmv`):

```
04:45:45.079  org.flywaydb.core.FlywayExecutor - Database: jdbc:postgresql://10.103.0.3:5432/kms (PostgreSQL 16.14)
04:45:45.866  o.f.core.internal.command.DbMigrate - Current version of schema "public": 159
04:45:45.872  o.f.core.internal.command.DbMigrate - Schema "public" is up to date. No migration necessary.
```

**V159, no migration applied** — which is what a wave with no migration must say.

**Health and the site.** `GET /actuator/health` → `200 {"status":"UP"}`. The web root → `200`.

## The new screens answer on staging

A 200 on a Next.js route is not evidence on its own: every route on this app serves the same shell
containing the word "Loading" and an inlined not-found template, because the pages render client
side. **The discriminator is the status code** — a route that is not in the build returns 404:

```
/inventory/<id>/edit          200   12278 bytes
/inventory/<id>/nosuchroute   404   11085 bytes
/definitely-not-a-route       404   11041 bytes
/ingredients/<id>/edit        200   12295 bytes
```

Both edit routes are new in this wave and both answer 200, so the new web image is serving.

Positive evidence as well, read out of the JavaScript the deployed site actually hands a browser:

- `/_next/static/chunks/app/inventory/[id]/edit/page-423e30be6c18ab2c.js` contains **"Save changes"**
  and **"Where is it stored"**.
- `/_next/static/chunks/app/ingredients/[id]/edit/page-96a1d658542ba02e.js` contains
  **"Save changes"**.
- `/_next/static/chunks/app/ingredients/[id]/page-64841c7497552281.js` contains both
  **"About this ingredient"** and **"About this supply"**.
- `/_next/static/chunks/app/supplies/page-a906093bd48b2dc7.js` contains **neither** "Move to
  Ingredients" **nor** "Move to Supplies". They are gone from the shipped bundle.

All nine screens in the wave answer 200: `/inventory`, the item, its edit; `/ingredients`, an
ingredient, its edit; `/supplies`, a supply (on the shared ingredient page), its edit.

## The seeded temple is untouched

Counted through the API as the Temple Admin, **before the deploy and again after it**, both times
the same:

```
inventory items: 111
purchase orders: 31
```

Also read after the deploy: 112 ingredients, 84 food and 28 supplies.

## What nobody has done

**Nobody has clicked any of this.** Neither builder could sign in to a running application from the
build worktree, so every behaviour claim in the wave rests on its tests. T-440 additionally measured
its three screens at 390 and 1280 on the real rendered markup with the project's own compiled
stylesheet — no overflow at either width — but that is markup in an iframe, not the app, and no
press was made. **T-441 did not measure at all and says so outright.** The screens Rajeev wants to
drive:

- `https://kms-staging-web-bnpkv5hfrq-el.a.run.app/inventory` — click an item's name, press Edit.
- `…/ingredients` — click an ingredient's name, press Edit.
- `…/supplies` — click a supply's name; it opens on the ingredient page, which is deliberate.

## One thing deliberately left behind

`docs/DESIGN_SYSTEM.md` is **modified and uncommitted in
`/Users/Rajeev/Workspace/kms-detail-pattern`, and was not part of this release.** It is a locked
document; the work manager part-amended §4 for this change and its permissions refused the rest, so
it sits half-done — the new rule is in, the version note inside it says v1.1 where it should say
v1.15, the Status line still says v1.14, and no `docs/versions/DESIGN_SYSTEM_v1.15.md` exists.
Commandment 8 puts finishing it behind Rajeev's sign-off, so it was neither completed nor reverted.
It is still sitting in that worktree waiting for him.
