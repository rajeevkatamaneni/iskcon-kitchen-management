# Release — the curated catalogue replaces the vendored books (T-404, T-405, T-406)

2026-09-20, to staging. No migration, no new error code; next free remain **V154** and
**KMS-400189**. **Not yet seen working by Rajeev.**

## Commits, in order, straight to `main`

| sha | what |
|---|---|
| `4cfe9509` | feat: the temple's own approved recipes are the library now — the 32 vendored books deleted, karnataka (42) + andhra_pradesh (2) in their place, T-404's rewritten tests and README, and `backend/src/test/resources/ladder-book/` |
| `1c35369a` | fix: a loaded recipe is stamped with where it actually came from — T-405's `LibraryLoader.SOURCE` and the stale prose in ten backend files and two story documents |
| `a981d800` | fix: a plural no longer sends an ingredient to the wrong shelf — T-406's second categoriser pass, `IngredientCategoriesTest`, and the library page's empty-state copy |
| `41791ad2` | build: the seeding toolkit that fills a temple with three months of work — `tools/seed/`, 35 files |
| `8eef0731` | docs: the catalogue-swap wave — changelog, dispatch record, the three proofs |

`origin/main` went `2aadae4b..8eef0731`, linear.

**`git push origin main` pushed nothing and said "Everything up-to-date."** The release ran in the
worktree `agent-ad44cab0eee0a1c41`, whose branch is `worktree-agent-ad44cab0eee0a1c41`, so `main`
resolved to the main checkout's stale ref. `git push origin HEAD:main` is what moved it. Worth
knowing for every future release worked in a worktree: the push can appear to succeed having done
nothing at all, which is the same failure shape as a lying exit code.

## The gate: the committed tree, not the working tree

`git archive HEAD` into a clean directory, then `git init && git add -A` (without which
`frontend/__tests__/design-system.test.ts` dies on `git ls-files` and takes its twenty tests with
it), then `npm ci`. Every figure below read from the JUnit XML or the runner's own summary line,
never from an exit code.

- backend `./gradlew build` — **BUILD SUCCESSFUL in 6m 51s**, `:check` included.
  **3620 tests, 3613 passed, 0 failed, 0 errors, 7 skipped**, counted across 250 XML files.
- frontend — **Test Files 182 passed (182), Tests 2603 passed (2603)**.
- `npx tsc --noEmit` — zero output.
- eslint — `npm run lint` *is* `eslint . --max-warnings=0`, so the zero-warning gate is what CI
  already runs. Silent.
- `npm run build` — `✓ Compiled successfully`, `✓ Generating static pages (76/76)`.
- `tools/check-ignored-sources.sh` against the archived tree — "No ignored source files." Nothing
  in `.gitignore` hid source from a fresh clone.

## CI

Run **35497460406** — https://github.com/rajeevkatamaneni/iskcon-kitchen-management/actions/runs/35497460406
— **success**: Repository, Backend (Spring Boot), Frontend (Next.js). Read from
`gh run view --json`, not from `gh run watch`'s exit status.

## Backup, before the deploy

**1789889576012** — ON_DEMAND, **SUCCESSFUL**, enqueued 2026-09-20T07:32:56.012Z, ended
**2026-09-20T07:34:27.262Z**, described "before catalogue swap release T-404/405/406 (HEAD
8eef0731)". Read back from a fresh `gcloud sql backups list`; `gcloud sql backups create` printed
no id.

## The deploy

`infra/deploy.sh iskcon-kms-2026 staging`, tag **`20260920-005523`**. Builds 7m15s, rollouts 2m08s,
**total 9m25s**, ending `==> Deployed.`

| service | revision | image digest |
|---|---|---|
| api | `kms-staging-api-00173-xgg` | `sha256:55a032ba86ffc7717143a52915b5dde8884f4dea631ce3d9bbac56f3206061a2` |
| web | `kms-staging-web-00161-vws` | `sha256:10f65303526b763f0309e063fb09623e21804502c9c799ebf4210003d6e682b9` |
| worker | `kms-staging-worker-00155-ndz` | api digest, as always |

Digests **changed** from the previous release's api
`sha256:c584ac712e12d2313fd5226f47776a1f20c7da299944e3e7e41eb7a3d4ed2f8b` and web
`sha256:c296f77c778109248049dfcf90b37b686b5dbb609a2a579bb2d60467c76682ba`. Checked because
`deploy.sh` has exited 0 while leaving the old image live under new environment variables; the
revision name alone would not have caught that.

**Flyway, from the api revision's own logs** — correct for a release with no migration:

```
Database: jdbc:postgresql://10.103.0.3:5432/kms (PostgreSQL 16.14)
Successfully validated 147 migrations (execution time 00:00.292s)
Current version of schema "public": 153
Schema "public" is up to date. No migration necessary.
```

**Health** `https://kms-staging-api-bnpkv5hfrq-el.a.run.app/health`:
`{"status":"UP","db":"UP","scheduler":"STANDBY","worker":"RUNNING"}`.
Web `https://kms-staging-web-bnpkv5hfrq-el.a.run.app` answers **200**.

## Where Rajeev can see it

**Recipe library**, `https://kms-staging-web-bnpkv5hfrq-el.a.run.app/library`, as the super admin.

**But it will still show the old 5,376 vendored recipes, and that is correct for now.** The two
curated books ship *inside the jar*, so they are on staging — they are not *in the database*. The
load is a separate, deliberate act, `POST /api/v1/library/recipes/load` as the super admin, and the
seeding team runs it after this release. Until then the `master_recipes` table holds the rows the
load will replace. The screen worth looking at before the load is the **empty state's** new wording,
and after the load, 44 recipes carrying their preparation notes.

Staging was reset to day one before all this (backup 1789885048166), so the temple itself is empty.

## Checked on the content, not taken on the description

- Measured from the two files: **44 recipes, 454 ingredient lines, 83 preparations, 15 not-bought
  marks**; halubai absent, as Rajeev marked it `needs-work`.
- `LibraryLoader.SOURCE` is `tools/seed/02b-build-catalogue.mjs@2026-09-19`, and that string is the
  file's **only** non-comment change. Every other T-405 file is comment-only, confirmed by filtering
  the diff for non-comment lines.
- `MasterRecipeView`'s "439 of the catalogue's 454 lines" agrees with 454 − 15.
- **`tools/seed` scanned for credentials over all 35 committed files.** Clean. Passwords come from
  `KMS_SEED_PASSWORD*` and the scripts refuse to run without them; the Firebase web API key is read
  from `frontend/.env.local.example`, where it already is, a web API key not being a secret.
  `local-seed-backend.sh` exports `DB_MIGRATION_PASSWORD=kms_migration`, byte-identical to the
  already-tracked `tools/local-backend.sh:8`. `.state/` and `__pycache__/` confirmed ignored with
  `git check-ignore -v`, and no such path is in the commit.

## One correction beyond the three tasks

`tools/check-ignored-sources.sh` — the script the `hygiene` job runs — said in its own comment that
the recipe library "is 32 committed JSON files" and that one swallowed by an ignore rule would load
"5,208 recipes instead of 5,376". False as of this release, in the one file whose job is to stop a
missing source file going unnoticed. Corrected to two files and 44 recipes, in `4cfe9509`. Comment
only.
