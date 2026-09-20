# Release 2026-09-20 — the staff-and-inventory wave (T-428 to T-432)

Third release of 20 September, after `RELEASE-2026-09-20.md` (curated recipes) and
`RELEASE-2026-09-20-menu-layout.md`. Rajeev's standing approval for this build series covers the
commit, CI and the staging deploy. Deploy is to **staging only**.

Built in the worktree `/Users/Rajeev/Workspace/kms-staff-inventory`, branch `wave-staff-inventory`,
off `origin/main` `7ed4c3b1`.

## The merge, which had to happen first

`origin/main` had moved to `b69ff67e` — eight seeding commits, every one of them under `tools/seed/`
and nothing else. The wave's 112 dirty files and the seeding commits do not intersect at a single
path, so the rebase took no conflict and nothing had to be changed to make it work. Every figure
below was measured **after** the rebase, on the merged tree.

## What shipped

| Commit | |
|---|---|
| `88ba8c21` | `feat: a staff member has a record, and Inventory reads like a page somebody designed` (T-428 to T-432) |
| `1c5e202d` | `docs: the staff-and-inventory wave — changelog, the dispatch record, and a question a book cannot answer` |

Migrations **V155** (staff documents, previous employment, both under `enable_tenant_rls()`) and
**V159** (`shift_signups` by volunteer). **V156, V157, V158, V160 and V161 were reserved to this wave
and never used** — checked in the committed tree before the push, `ls` of
`backend/src/main/resources/db/migration` shows V155 and V159 and nothing else above V154 — so **the
next free is V156**, not V162. No new error code (the next free is still **KMS-400192**) and no new
permission: `MANAGE_STAFF` and `VIEW_OWN_SHIFTS` already existed and already covered this.

## Why it is one code commit and not five

Four of the five tasks edit `frontend/lib/api.ts`, and its added hunks interleave — T-428's staff
types and methods, T-429's `MyPastShiftView` and `myPastShifts`, T-432's `onOrder`, `lastCounted` and
`StockCover`. T-430 and T-432 both live inside `InventoryItemService.java` and `StockItemView.java`;
T-431 and T-432 both live inside `app/inventory/page.tsx`, `app/inventory/[id]/page.tsx`,
`components/InventoryItemForm.tsx` and `__tests__/inventory.test.tsx`; T-431 and T-432 both edit
`lib/format.ts`. There is no file-level split, and a hunk-level one would produce intermediate
commits whose greenness could only be claimed by running the whole suite five times. One commit that
is honestly green beats five that are green by assertion.

## The gate: the committed tree, not the working tree

`git archive HEAD | tar -x` into a clean directory, then `git init -q && git add -A` in it before
running anything. The `git init` is not optional:
`frontend/__tests__/design-system.test.ts` enumerates what it audits with `git ls-files`, and in a
bare archive it dies with `fatal: not a git repository`. **It ran — 23 tests**, read out of the
vitest log rather than assumed.

Every figure was read out of the log or the JUnit XML. No exit code was taken as a result.

- Backend `./gradlew test`: **`BUILD SUCCESSFUL in 15m 50s`**. Aggregated from the JUnit XML across
  **261 classes: 3755 tests, 0 failures, 0 errors, 7 skipped.** Gradle's own summary block says
  `Failed: 0`, `Skipped: 7`, `Result: SUCCESS`.
- `npx tsc --noEmit`: silent, exit 0.
- `npm run lint` (`eslint . --max-warnings=0`): silent, exit 0.
- `npm test` (vitest): **`Test Files 188 passed (188)`**, **`Tests 2756 passed (2756)`**.
- `npm run build`: **`✓ Compiled successfully`**, **`✓ Generating static pages (77/77)`**.
- `tools/check-ignored-sources.sh`: *"No ignored source files. Every source file under 6 trees is in
  git."* `tools/check-tfvars-example.sh`: *"all 19 declared variables present, none extra."* Both run
  before the push, not after.

These match the builders' merged figures exactly.

## CI

Run **35522544700** on `1c5e202d`, <https://github.com/rajeevkatamaneni/iskcon-kitchen-management/actions/runs/35522544700>.
All three jobs `success`: `Repository`, `Backend (Spring Boot)`, `Frontend (Next.js)`.

## The backup, before anything was deployed

Cloud SQL on-demand backup of `kms-staging-5325bd0d`, id **1789920500674**, status **SUCCESSFUL**,
enqueued `2026-09-20T16:08:20.674Z`, ended `2026-09-20T16:09:51.875Z`. Status and end time read back
from a fresh `gcloud sql backups list`, not from the create call.

## The deploy

`infra/deploy.sh iskcon-kms-2026 staging`, run **from this worktree** with `git status` empty at
`1c5e202d`. `deploy.sh` ships the working tree, so running it from the main checkout would have
shipped whatever that checkout happened to hold. Tag `20260920-094156`. Builds 5m50s, rollouts 1m59s,
total 7m52s.

The exit code was ignored. The evidence:

| | before | after |
|---|---|---|
| api | `kms-staging-api-00174-r44` `sha256:378bcbaa…` | `kms-staging-api-00175-gxb` `sha256:d4fe14c0…` |
| web | `kms-staging-web-00162-w6g` `sha256:aaa1f161…` | `kms-staging-web-00163-btg` `sha256:cf4cae5f…` |
| worker | `kms-staging-worker-00156-hnl` `sha256:378bcbaa…` | `kms-staging-worker-00157-8n7` `sha256:d4fe14c0…` |

Three new revisions, both digests changed. `/actuator/health` → `200 {"status":"UP"}`; the web root
→ `200`.

## Flyway, from the new revision's own logs

```
Successfully validated 150 migrations
Current version of schema "public": 154
Migrating schema "public" to version "155 - a staff record carries its papers and its past jobs"
Migrating schema "public" to version "159 - signups by volunteer"
Successfully applied 2 migrations to schema "public", now at version v159 (execution time 00:00.325s)
```

**Exactly two migrations, the two this wave adds.** Neither touches an existing row: V155 creates two
tables, their indexes and their RLS policies and then re-comments `staff_profiles.notes` — a comment
is metadata, so the three staff rows that hold notes text are untouched and the column is still
there. V159 is a single `CREATE INDEX` and one comment. No backfill, no `UPDATE`, no `DELETE`
anywhere in either file.

## The seeded temple is intact

Counted through the API as the Temple Admin, before and after the deploy: **111 inventory items**
both times, **31 purchase orders** both times, **46 items below their reorder level** both times, and
**no item's on-hand figure moved**. The staff register still reads 6 current and 5 former.

## What answers on staging now

- `GET /api/v1/staff/members/{id}` → `200`, shape `{profile, banned, documents, previousEmployment}`,
  and **`notes` is absent from the profile**. `documents` and `previousEmployment` are empty, which is
  right: nobody has uploaded anything yet.
- `GET /api/v1/my-shifts/past` → `200 []` for an account with no past shifts. The route exists and is
  scoped to the caller.
- The inventory payload carries three fields it did not have this morning — `onOrder`, `lastCounted`,
  `lastsFor`.
- The deployed `/inventory` chunk contains **On hand**, **On order**, **Last counted** and the search
  box; the deployed `/staff/[id]` chunk contains **PAN card**, **Aadhaar**, **Previous employment**
  and no free-text Notes (its one "Notes" is `staffConductNotes`, which is a different, intended
  feature); the deployed `/my-shifts` chunk contains **Past shifts** and "most recent shifts".
- The hole T-432 closed, exercised against the live API: `PUT /inventory/items/{id}` with
  `reorderThreshold: 8.5` on Agarbatti (counted in pieces) is refused **400 KMS-400191**, field error
  *"Agarbatti is counted in whole pieces. Enter 8 or 9."* — T-431's sentence and T-432's server-side
  check in one answer. The item was re-read afterwards: threshold still 8.0, nothing was written.

## One thing that cannot be seen on staging yet

T-430's fix is live but has nothing to bite on. **Water is the only ingredient marked "never bought"
and it is not held in inventory on the seeded temple**, so the low-stock list was 46 before the
deploy and 46 after. The builder's minus-1,358.5-litres measurement came from its own `kms_wave5`
copy, which was a different seed. The fix is proved by `NotBoughtLowStockIT` in the suite, not by the
staging figures.

## Not verified by driving the app signed in

Nobody has opened a staff record, uploaded a PAN scan, or looked at the rebuilt Inventory screen in a
browser on staging. Everything above is API evidence, deployed-bundle evidence and log evidence.
