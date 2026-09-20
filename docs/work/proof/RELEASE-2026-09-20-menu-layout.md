# Release 2026-09-20 — the menu-layout and whole-counts wave (T-420 to T-427)

Sixth release since 19 September, after `RELEASE-2026-09-20-catalogue.md` and
`RELEASE-2026-09-20.md`. Rajeev's standing approval for this build series covers the commit, CI and
the cloud deploy without asking again. Deploy is to **staging only**.

Built in the worktree `/Users/Rajeev/Workspace/kms-menu-layout`, branch `menu-layout`, off
`origin/main` at `8e647ad8`. `origin/main` was fetched at the start of the release and was still
exactly `8e647ad8`, so nothing needed rebasing and every figure below was measured once, on the
finished tree. Nothing was brought across from the other worktrees; `agent-ad44cab0eee0a1c41` is a
seeding team's and was not touched.

## What shipped

| Commit | |
|---|---|
| `d7281af1` | `feat: a temple arranges its own left-hand menu` (T-420, T-421, T-422, T-426) |
| `ad596f6b` | `feat: a thing counted one by one cannot be entered as a fraction` (T-423, T-424) |
| `3b24874e` | `feat: a scaled recipe asks for whole bananas` (T-425) |
| `1fc82ec4` | `feat: copying a recipe says which buying settings it left alone` (T-427) |
| `d673b669` | `docs: the menu-layout and whole-counts wave — changelog, the ledger, the spec marked built` |

Migration **V154** (`tenant_settings.menu_layout`); the next free is **V155**. Error codes
**KMS-400189** (`MENU_LAYOUT_NOT_UNDERSTOOD`, 400), **KMS-400190** (`MENU_ITEM_IN_TWO_GROUPS`, 409)
and **KMS-400191** (`PART_OF_A_COUNTED_THING`, 400); the next free is **KMS-400192**. No new
permission: the menu endpoints sit behind `MANAGE_TEMPLE_SETTINGS`, which the Temple Admin holds.

## Why it is four code commits, and how the two shared files were split

The seams are the four things a reader would want to bisect separately: the menu screen, the refusal
at the server and screen doors, the rounding the scaler does, and the import's response. Two files
carry hunks belonging to more than one of them, and both divide cleanly by block:

- `backend/.../error/ErrorCode.java` — the two menu codes go with the menu commit, the counted code
  with the counted commit.
- `frontend/lib/api.ts` — `MenuLayout`, `WhoAmI.menuLayout` and the two client methods go with the
  menu commit; the `importRecipe` response type with the import commit.

Each commit therefore holds a tree that declares the types and codes the rest of that commit uses.
**The split was proved rather than trusted:** `git write-tree` over the whole working tree before
the first commit gave `485478c3eb4c039fe682213dc72bdf39698ccdb8`, and the tree after the fourth
commit is the same hash, so the surgery on those two files neither lost nor altered a byte.

## The gate: the committed tree, not the working tree

`git archive HEAD | tar -x -C <clean dir>`, then `git init -q && git add -A` in that directory
before running anything. The `git init` is not optional:
`frontend/__tests__/design-system.test.ts` enumerates the files it audits by shelling out to
`git ls-files`, and in a bare archive it dies with `fatal: not a git repository`.

Every figure below was read out of a log file or parsed from JUnit XML. **No harness exit code was
taken as a result** — this wave's own agents saw `work-lock` print `exited 0` over a failing build
eight times, once because Gradle was piped into `tail` and once because a dead `next start` left an
older server answering.

**Backend**, summed over `build/test-results/test/TEST-*.xml` per file (keying on the XML `name`
attribute silently drops every `@Nested` suite):

```
xml files (classes)=253 tests=3693 failures=0 errors=0 skipped=7
BUILD SUCCESSFUL in 6m 48s
```

**Frontend**, in the same clean directory after `npm ci`:

```
tsc --noEmit                 no output
eslint . --max-warnings=0    no output
 Test Files  187 passed (187)
      Tests  2702 passed (2702)
 ✓ Compiled successfully
 ✓ Generating static pages (77/77)
```

`/settings/menu` appears in the route table at 5.96 kB, so the new screen is in the 77.

The `hygiene` job's two scripts were also run by hand and both passed: no ignored source files, and
`terraform.tfvars.example` matches all 19 declared variables.

## CI

https://github.com/rajeevkatamaneni/iskcon-kitchen-management/actions/runs/35508296875 — **success**
on `d673b669`. Repository, Backend (Spring Boot) and Frontend (Next.js) all green.

## The backup, taken before the deploy

Staging now holds a fully seeded temple — 112 ingredients, 31 purchase orders, 95 meals, a month of
history — so V154 was not going to run against an empty database. On-demand backup of
`kms-staging-5325bd0d`, read back from a fresh listing rather than from the create call:

```
ID             TYPE       STATUS      ENQUEUED_TIME             END_TIME
1789904806875  ON_DEMAND  SUCCESSFUL  2026-09-20T11:46:46.875Z  2026-09-20T11:48:18.100Z
```

## The deploy, verified by evidence

`infra/deploy.sh iskcon-kms-2026 staging`, tag `20260920-045034`, total 9m24s (builds 6m55s,
rollouts 2m27s). The script's exit code was ignored; all of the following was read back afterwards.

| | before | after |
|---|---|---|
| api revision | `kms-staging-api-00173-xgg` | `kms-staging-api-00174-r44` |
| web revision | `kms-staging-web-00161-vws` | `kms-staging-web-00162-w6g` |
| worker revision | `kms-staging-worker-00155-ndz` | `kms-staging-worker-00156-hnl` |
| api image digest | `sha256:55a032ba86ff…` | `sha256:378bcbaa5c49…` |
| web image digest | `sha256:10f65303526b…` | `sha256:aaa1f1610e6a…` |

All three revisions advanced and both digests changed.

**Flyway, from the new api revision's own logs** — exactly one migration, and nothing else moved:

```
DbValidate - Successfully validated 148 migrations (execution time 00:00.293s)
DbMigrate  - Current version of schema "public": 153
DbMigrate  - Migrating schema "public" to version "154 - the temple arranges its own menu"
DbMigrate  - Successfully applied 1 migration to schema "public", now at version v154
```

V154 is catalogue-only DDL — `ADD COLUMN menu_layout JSONB` with a CHECK, and a column comment. It
writes no rows, so the seeded temple's data was not read or touched by it. The worker came up after
the api and logged `Schema "public" is up to date. No migration necessary.`

**Health.** `kms-staging-api-00174-r44` logged `Started KmsApplication in 39.53 seconds` with **no
ERROR-or-worse entries at all**; the worker logged `Started KmsApplication in 37.71 seconds`,
likewise clean.

```
GET https://kms-staging-api-bnpkv5hfrq-el.a.run.app/actuator/health  200  {"status":"UP"}
GET https://kms-staging-web-bnpkv5hfrq-el.a.run.app/                 200  10675 bytes
GET https://kms-staging-web-bnpkv5hfrq-el.a.run.app/settings/menu    200
```

## Where Rajeev can see it

Sign in at https://kms-staging-web-bnpkv5hfrq-el.a.run.app as the Temple Admin.

- **Settings → Menu** (from the Settings page; the screen has no row of its own in the left-hand
  menu, by D-M6). Drag an item, or use Move up / Move down / Move to group; rename a group, add one,
  delete an empty one; Save, and the left-hand menu repaints. Reset to the standard menu asks first.
  Worth trying on a phone as well — every move works without dragging and at 390px.
- **Whole counts.** Any quantity box against an ingredient measured in pieces — a purchase order
  line, a goods-in, an ingredient request, a stock adjustment, the shopping list — now refuses a
  fraction, and the server says the same thing if the request gets that far. The pack line on a
  goods-in still takes 2.8 bags on purpose, and a bill can still restate a delivery.
- **Recipe scaling.** Open a recipe with a counted ingredient and scale it up: the requirement is a
  whole number, rounded up, and the job card, the cooking draw, the sufficiency badge and the cost
  estimate all read the same figure. The cost estimate moves up a little as a result.
- **Copying a library recipe** whose ingredients the book marks "never bought" (water is the usual
  one) onto a temple that already owns that ingredient: the copy now names what it left alone.

## Not verified

Nobody has pressed any of it against the deployed app with the seeded temple's real data. The
evidence above is that the right code is live and the schema moved by exactly one migration — not
that the screens behave. That is the first of the two verification passes and it has **not** been
done for this wave.
