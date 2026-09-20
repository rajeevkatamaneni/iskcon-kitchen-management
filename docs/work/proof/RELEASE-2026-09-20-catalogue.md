# Release — the temple's own recipes become the library

2026-09-20, staging. Commits `4cfe9509`, `1c35369a`, `a981d800`, `41791ad2`, `8eef0731`, on top of
`2aadae4b`, pushed straight to main.

## What shipped

Rajeev curated 45 recipes by hand on 2026-09-19 after deciding the mass import had been a mistake —
*"It was a BAD idea to mass import that many recipes without vetting each first."* This release
makes those recipes the library. `backend/src/main/resources/recipe-library/` no longer holds the 33
vendored upstream state books; it holds two generated books, karnataka (42) and andhra_pradesh (2).

**44 recipes, 454 ingredient lines, 83 preparations, 15 not-bought marks**, measured from the files
and asserted by `RecipeLibraryIT`. Halubai is held back because he marked it `needs-work`; that is
his call to reverse, not ours.

Four corrections went in with it:

- `LibraryLoader.SOURCE` stamped every loaded row `kranthimj23/ikms@41cf173`, which is now false and
  is visible in the database. It reads `tools/seed/02b-build-catalogue.mjs@2026-09-19`.
- `IngredientCategories` sent seven lines to `Other` only because they were plural — Cloves,
  Coriander seeds, Lemons, Raisins — while the application's own matcher singularises before it
  compares. The categoriser now falls back to that same singularisation: **423 → 430 of 454 lines**,
  88 → 92 of 99 names. The order matters and is tested: rules written in the plural (`beans`,
  `greens`, `leaves`, `peas`, `dates`) are tried first, so nothing filed correctly today moves.
- The library page told the user it would "read the vendored recipe books in". It now says it brings
  in the approved catalogue.
- The stale counts — 5,376 recipes, 32 books, 46,337 lines — across 11 backend files and 2 story
  docs, every replacement re-measured from the files rather than estimated.

`tools/seed/`, the seeding toolkit, is committed for the first time: the phase scripts, the curation
fix-ups, the catalogue converter, `day1-reset.sql`, `day1-counts.sql` and `backdate.sql`.

## Two data decisions made in the curation, both reversible in one line

**Mustard, `"z gm"`.** The mango pickle's mustard quantity was unreadable, and the same typo is in
the vendored source book, so it was not the curation's fault. Set to **300 gm** from the recipe's own
arithmetic: everything in it is quoted against 12 Kg of raw mango — salt 167 g/Kg, chilli powder
100, fenugreek and turmeric 12.5, asafoetida 5 — and ground mustard in a Karnataka uppinakayi masala
runs about twice the fenugreek. Rule 5 in `tools/seed/02a-curation-fixups.mjs`.

**Ghee, quoted both ways.** The approved set measured ghee 11 times by weight and 12 times by volume.
`Unit.java` converts inside a family and never across it, so a temple stocking ghee in Kg could not
answer a line asking for 2 L. Ghee was the only ingredient in the set with that split — measured
across all 44 recipes, not assumed. The 12 volume lines are now weight at **0.91 Kg per litre**
(2 L → 1.82 Kg, 800 ml → 730 gm), which is a real conversion rather than pretending a litre is a
kilogram, which would have put each of those lines 9% over. Rule 6, same file.

## Proof

Committed tree, not the working tree: `git archive HEAD` into a clean directory, `npm ci`.

| | |
|---|---|
| backend | BUILD SUCCESSFUL 6m51s — **3620 tests, 3613 passed, 0 failed, 7 skipped**, 250 XML files |
| frontend | 182 files, **2603 tests passed** |
| tsc | silent |
| eslint | `--max-warnings=0`, silent |
| next build | compiled, 76/76 pages |
| ignored sources | "No ignored source files." |

Every figure read from the JUnit XML or the runner's own summary. Exit codes were not trusted, and
were right not to be: twice in this series a wrapper reported success over a `BUILD FAILED`.

CI run **35497460406** — Repository, Backend and Frontend all green.

## Deploy

Tag `20260920-005523`. Builds 7m15s, rollouts 2m08s, total 9m25s.

- `kms-staging-api-00173-xgg`, `kms-staging-web-00161-vws`, `kms-staging-worker-00155-ndz`, each
  serving 100% of traffic.
- Digests changed: api `…c584ac71` → `sha256:55a032ba86ffc7717143a52915b5dde8884f4dea631ce3d9bbac56f3206061a2`,
  web `…c296f77c` → `sha256:10f65303526b763f0309e063fb09623e21804502c9c799ebf4210003d6e682b9`.
- Flyway: "Successfully validated 147 migrations", "Current version of schema public: 153", "Schema
  public is up to date. No migration necessary." No migration in this release.
- `/actuator/health` UP.
- Pre-deploy Cloud SQL backup **1789889576012**, ON_DEMAND, SUCCESSFUL, end time
  2026-09-20T07:34:27Z — read back from a fresh listing, because the create command prints no id.

## What this release deliberately does not do

**It does not load the catalogue.** The books ship inside the jar, so they reach staging with the
deploy, but `master_recipes` keeps the old vendored rows until somebody calls
`POST /api/v1/library/recipes/load` as the super admin. That is a separate, deliberate act, run by
the seeding work after this.

## Two traps found on the way, worth carrying forward

**`git push origin main` from a worktree pushes nothing and says "Everything up-to-date."** The
worktree is on its own branch, so `main` resolves to the main checkout's stale ref.
`git push origin HEAD:main` is what works.

**A developer's local database can end up with two stamps.** The loader upserts on
`(state_slug, recipe_slug)`, so re-loading rewrites the 44 rows it recognises and leaves the 5,332
vendored rows stamped for files that no longer exist. Drop or empty `master_recipes` first. Staging
does not have the problem — it was reset to day one before this.

## Before it

Staging was reset to its first morning: 6,190 rows to 812, 5,147 cleared directly and 231 by
cascade, with the people, kitchens, meal kinds, vendors, calendar, staff and wish list kept.
Restore point, backup 1789885048166.
