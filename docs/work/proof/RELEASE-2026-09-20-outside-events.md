# Release 2026-09-20 — the outside-events wave (T-363 to T-366)

Fourth release of 20 September, after `RELEASE-2026-09-20.md` (curated recipes),
`RELEASE-2026-09-20-menu-layout.md` and `RELEASE-2026-09-20-staff-inventory.md`. Rajeev's standing
approval for this build series covers the commit, CI and the staging deploy. Deploy is to
**staging only**.

Released from the worktree `/Users/Rajeev/Workspace/kms-outside-events`, branch
`wave-outside-events`, already rebased by the work manager onto `origin/main` `a5dd6d97`. `git fetch`
before the first commit and again before the push: **`origin/main` had not moved**, so nothing in
this release had to be re-measured and the rebase forced no change of any kind.

**Deployed from the release worktree, not the main checkout.** `deploy.sh` ships the working tree,
and the main checkout is not this branch.

## What shipped

| Commit | |
|---|---|
| `1f023add` | `fix: an outside event opens from the day list, and no day claims to be empty when it is not` (T-363) |
| `afaa1640` | `fix: one row on a printed sheet says its quantities in one unit` (T-364) |
| `80926735` | `refactor: the WhatsApp fact on an order says whose it is` (T-365) |
| `b54a040d` | `tools: a repair for a temple that holds 400.98 coconuts` (T-366) |
| `5b1739e9` | `docs: the outside-events wave — changelog, the dispatch record, and four proofs` |

**No migration.** The tree's highest is V159, from the staff-and-inventory wave; **V160, reserved to
T-363, is unused, and the next free is still V156.** **No new error code** — the next free is still
**KMS-400192**, because `KMS-400183` already said what T-363's refusal had to say. **No new
permission.**

## Why it is four code commits and not one

Unlike the staff-and-inventory wave, the files allowed it. Only two files are wanted by two tasks —
`frontend/lib/api.ts` and `frontend/__tests__/lead-time-one-promise.test.tsx` — and in both the
hunks are far apart and belong unambiguously to one task each, so they were staged hunk by hunk with
`git apply --cached`. T-363 has the commit of its own that it is going to be looked for by.

## The gate: the committed tree, not the working tree

`git archive HEAD | tar -x` into `/tmp/kms-verify`, then `git init -q && git add -A` in it before
running anything — 2,301 files. The `git init` is not optional:
`frontend/__tests__/design-system.test.ts` enumerates what it audits with `git ls-files`, and in a
bare archive it dies with `fatal: not a git repository`. **It ran — 23 tests**, read out of the
vitest log rather than assumed.

Every figure below is read out of a log or out of the JUnit XML. **No figure here comes from an exit
code.**

- **Backend `./gradlew test`: `BUILD SUCCESSFUL in 7m 36s`.** Gradle's own summary: Total **3783**,
  Passed 3776, Failed **0**, Skipped **7**. Aggregated independently over the 263 JUnit XML files:
  `classes=263 tests=3783 failures=0 errors=0 skipped=7`. `grep -c " FAILED$"` over the log: **0**.
- Frontend `npx tsc --noEmit`: silent, exit 0.
- Frontend `npx eslint . --max-warnings=0`: silent, exit 0.
- Frontend `npx vitest run`: **188 files passed (188)**, **2772 tests passed (2772)**.
- Frontend `npx next build`: `✓ Compiled successfully`, `✓ Generating static pages (77/77)`.

Every figure matches the work manager's merged-tree run exactly.

### The thirteen repo-wide guards, each read from its own XML

A guard that fails to *run* and a guard that *passes* look identical in a green summary, so each was
read from its own file rather than inferred from the total.

| Guard | tests | failures | errors |
|---|---|---|---|
| `BaseQuantityIT` | 7 | 0 | 0 |
| `UnitLabelAgreementTest` | 2 | 0 | 0 |
| `TempleClockTest` | 1 | 0 | 0 |
| `CommunicationSendGuardSourceTest` | 2 | 0 | 0 |
| `ErrorCodeTest` | 972 | 0 | 0 |
| `FieldErrorMessageTest` | 6 | 0 | 0 |
| `NextStepPermissionTest` | 6 | 0 | 0 |
| `RolePermissionsTest` | 97 | 0 | 0 |
| `RowLevelSecurityIT` | 30 | 0 | 0 |
| `TenantLoopMigrationIT` | 1 | 0 | 0 |
| `PermissionBeforeValidationIT` | 97 | 0 | 0 |
| `MetaTemplateRulesTest` | 6 | 0 | 0 |
| `NotificationTemplateTest` | 10 | 0 | 0 |

`PermissionBeforeValidationIT` is the one this wave needed most: T-363 **removed** an endpoint, and
that guard asserts over the framework's own request-mapping registry. 97 passed with
`/api/v1/meal-plans/outside-commitments` gone.

## CI

<https://github.com/rajeevkatamaneni/iskcon-kitchen-management/actions/runs/35527095971> — **success**.
Repository (hygiene) success, Frontend (Next.js) success, Backend (Spring Boot) success in 16m18s.

## The backup, before anything was deployed

`gcloud sql backups create --instance=kms-staging-5325bd0d`. Id **1789926114186**, read back from a
fresh `gcloud sql backups list`: status **SUCCESSFUL**, type ON_DEMAND, started
`2026-09-20T17:41:54Z`, **ended `2026-09-20T17:43:45Z`**, description *"pre-deploy outside-events
wave T-363..T-366"*.

## The deploy, confirmed by evidence rather than by the exit code

`infra/deploy.sh iskcon-kms-2026 staging`, tag `20260920-110622`. Builds 6m14s, rollouts 2m10s,
**total 8m26s**. The wrapper exited 0, which proves nothing; what follows does.

**All three revisions are new and all three digests changed.**

| Service | Revision before → after | Digest before → after |
|---|---|---|
| `kms-staging-api` | `00175-gxb` → **`00176-m2n`** | `sha256:d4fe14c0…a7c9` → **`sha256:3cc43f99…0ee0`** |
| `kms-staging-web` | `00163-btg` → **`00164-lrl`** | `sha256:cf4cae5f…5292` → **`sha256:5b8e9403…89f1`** |
| `kms-staging-worker` | `00157-8n7` → **`00158-k6q`** | `sha256:d4fe14c0…a7c9` → **`sha256:3cc43f99…0ee0`** |

**Flyway, from `kms-staging-api-00176-m2n`'s own logs**, which is the point of asking rather than
assuming:

```
o.f.core.internal.command.DbValidate - Successfully validated 150 migrations (execution time 00:00.291s)
o.f.core.internal.command.DbMigrate - Current version of schema "public": 159
o.f.core.internal.command.DbMigrate - Schema "public" is up to date. No migration necessary.
```

Nothing applied, still at **v159**, as a wave with no migration should be.

- `GET https://kms-staging-api-bnpkv5hfrq-el.a.run.app/actuator/health` → **200 `{"status":"UP"}`**
- `GET https://kms-staging-web-bnpkv5hfrq-el.a.run.app/` → **200**

## The new behaviour answering on staging, signed in

Signed in as `ikms.temple-admin.1@trading4good.org` through the seed harness's custom-token route.

- **The removed endpoint is gone.** `GET /api/v1/meal-plans/outside-commitments` → **404
  `KMS-400030`**, not 403 — deliberate, so `PermissionBeforeValidationIT` cannot mask it.
- **`GET /api/v1/today` carries `upcomingOutside`**, with a real row:
  `{"mealId":"e465b291-…","planDate":"2026-09-24","eventName":"Koramangala house programme",
  "mealKind":"Event","handover":"DELIVERY","readyBy":"17:30:00","preparations":3}` — the `mealId`
  being there is what makes the row a link.
- **The fortnight starts tomorrow, demonstrated on real data.** The two outside events in the window
  are *Janieswar* on the 20th and *Koramangala house programme* on the 24th. The heads-up holds the
  24th only: today's is already in the meals card, and listing it twice would say the temple has two.
- **The deployed web bundle carries the words.** `chunks/app/today/page-139a68ab25abf7ec.js` contains
  *"Going out of the temple"*, *"In the days ahead, soonest first"*, *"We deliver it"* and *"They
  collect it"*; the planner's shared meal-card chunk `chunks/3399-644282d8d79620b1.js` carries the
  pill too. The planner page chunk contains neither *"Upcoming outside commitments"* nor
  *"outside-commitments"* — the card and its call are gone from what the browser is served.

## The seeded temple is untouched

Counted through the API as the Temple Admin, before the deploy and again after it, identical both
times: **111 inventory items, 31 purchase orders, 96 meals** between 1 August and 31 October. (The
brief expected 95 meals; 96 is what staging held *before* this deploy as well, so nothing here moved
it.)

**Worth knowing before he retests:** the event Rajeev reported on, *"Children's Bhagavad-gita
Reading"*, is **not in staging any more** — the meals in the window run to 26 September and it is not
among them. He will want *Koramangala house programme* on 24 September, or an event of his own, to
test against.

## Still owed

**Nobody has driven these screens signed in on staging.** The evidence above is API payloads,
deployed bundle contents and revision digests, which is what a release record can prove; it is not a
person pressing Job card on an outside event. The first verification pass is still owed, and
`tools/seed/01c-whole-counted-stock.py` has **not been run anywhere** and must not be run by anyone
but Rajeev.
