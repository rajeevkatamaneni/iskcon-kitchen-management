# T-405 — the loader stamps the curated catalogue's own provenance, and the prose the swap made false is corrected

**Task:** give `LibraryLoader.SOURCE` a value that is true of the curated catalogue, and correct the
comments and story docs that still describe the 32 vendored books. Comments and docs only; no
behaviour changed except the string stamped into `master_recipes.source_ref`.

## The stamp

`LibraryLoader.SOURCE` was `"kranthimj23/ikms@41cf173"`, giving every row a `source_ref` of
`kranthimj23/ikms@41cf173:karnataka.json`. That repository is no longer the source of anything in
`recipe-library/`, so the stamp was false.

It is now `"tools/seed/02b-build-catalogue.mjs@2026-09-19"` — the converter that generated the books
and the day Rajeev approved the recipes in them — so a row reads
`tools/seed/02b-build-catalogue.mjs@2026-09-19:karnataka.json`.

What I checked before choosing it:

- **Column width.** `master_recipes.source_ref` is `TEXT NOT NULL` (V68 line 136). No length limit,
  no `CHECK`. The new value is 44 characters plus the filename.
- **Parsing.** Nothing parses it. The only writer is `LibraryLoader` (`row.sourceRef = SOURCE + ":" +
  filename`), the only reader is `MasterRecipeService.detail` (`rs.getString("source_ref")` straight
  into `MasterRecipeView.sourceRef`), and `MasterRecipeService` insert/update pass an operator-typed
  value through unchanged. Nothing splits on the colon, so a value containing `/` and `.` is safe.
- **UI.** `sourceRef` exists in `frontend/lib/api.ts` (line 634) and in one test fixture
  (`frontend/__tests__/library-recipe-ingredients.test.tsx`, `sourceRef: ""`). No component renders
  it — `grep -rn sourceRef frontend --include=*.tsx` returns only that test. So no layout can break
  on the longer string.
- **Tests and migrations asserting the old value.** None. `grep -rn "kranthimj23\|41cf173"` over the
  whole tree (excluding `build/`) finds only prose: one javadoc line in `RecipeLibraryIT`, the
  provenance table in `recipe-library/README.md`, three lines in the EPIC-2 design doc, and
  `docs/work/` records. The five test files that mention `source_ref` only list it in their own
  `INSERT` fixtures.

**Where two different stamps could sit side by side.** Any database already carrying rows keeps the
old `kranthimj23/ikms@41cf173:<state>.json` on them until the loader runs again; the upsert keys on
`(state_slug, recipe_slug)`, so a re-load rewrites the 44 rows it recognises and leaves the other
5,332 vendored rows exactly as they are, stamp included. Staging is being reset before the load, so
nothing survives there. The one place this would show is a developer's local database loaded before
today and re-loaded after: it would hold 5,376 rows, 44 with the new stamp and 5,332 with the old,
for recipes whose files no longer exist. Dropping the database or `DELETE FROM master_recipes`
before the load is the fix; I have not changed the loader to purge rows whose file is gone, because
that is a behaviour change and this task is prose.

## Files changed

All inside the contract (backend source comments plus the two story docs; no `tools/seed/`, no
recipe-library JSON, no `RecipeLibraryIT`).

| File | What changed |
|---|---|
| `backend/src/main/java/org/iskcon/kms/library/LibraryLoader.java` | `SOURCE` value and its javadoc; class javadoc (two books, 44 recipes, 454 lines); the disambiguation and third-rung paragraphs; the `load()` and `load(String)` javadocs; the `scaled`/`prep` line comments |
| `backend/src/main/java/org/iskcon/kms/library/BookParser.java` | class javadoc counts and examples; `parseYield` examples; `perHead` counts and the Papdi paragraph; `ingredientQuantity` count; the count-token example |
| `backend/src/main/java/org/iskcon/kms/library/IngredientCategories.java` | coverage re-measured against the curated files, and what the uncovered tail now is |
| `backend/src/main/java/org/iskcon/kms/library/MasterRecipeView.java` | `prep`, `notBought` and `scaled` param docs |
| `backend/src/main/java/org/iskcon/kms/library/MasterRecipeService.java` | the ordering comment, the `prep`-null comment, one tense fix |
| `backend/src/main/java/org/iskcon/kms/library/RecipeImportService.java` | "84 of his notes" → 83; "all 46,337 lines parse" → 454 |
| `backend/src/main/java/org/iskcon/kms/library/SearchQuery.java` | `MAX_TERMS` comment |
| `backend/src/main/java/org/iskcon/kms/library/MasterRecipeInput.java` | "a batch of 5,376 nobody typed" |
| `backend/src/main/java/org/iskcon/kms/library/MasterRecipeController.java` | "Reads the vendored books" |
| `backend/src/main/java/org/iskcon/kms/audit/AuditAction.java` | `RECIPE_LIBRARY_LOADED` doc |
| `backend/src/main/java/org/iskcon/kms/ingredient/IngredientNameMatcher.java` | annotated the 2,238-name measurement as the vendored books' |
| `docs/stories/EPIC-2-recipe-library-DESIGN.md` | dated note under §1 and the **Source:** line |
| `docs/stories/README.md` | the EPIC-2 paragraph's count and "the source data is vendored" |

## Every count in those files was measured, not estimated

From the two JSON files, and for the parser and categoriser figures by running the project's own
compiled classes over them:

```
$ node scratchpad/count.mjs
books 2 recipes 44 ingredient lines 454
prep lines 83 distinct prep values 26
not_bought lines 15 names {"Water":15}
scaled lines 0 recipes with per 31
distinct ingredient names 99
top preps: Grated=31, Chopped=10, Diced=6, Juiced=6, Fine=3
units: {"Kg":86,"gm":275,"ml":20,"nos":1,"L":39,"Pieces":29,"pieces":1,"Nos":3}
dup recipe names: none
categories(10): breakfast-items, ekadashi, rice, sabji-s-wet, sabji-s-dry, dal, khichadi, rotis, sweets, jam-pickles
```

```
$ java -cp backend/build/classes/java/main Qty.java     # BookParser.ingredientQuantity on all 454
lines=454 failures=0 []
raw tokens={Kg=86, L=39, Nos=3, Pieces=29, gm=275, ml=20, nos=1, pieces=1}
resolved units={GM=275, KG=86, L=39, ML=20, PIECES=34}
```

```
$ java -cp backend/build/classes/java/main Yld.java     # BookParser.parseYield / perHead on all 44
recipes=44 unresolved yields=0 units={KG=4, L=30, PIECES=10}
per field only=31 parenthetical only=10 both=0 neither=3
per-head discarded (unit family mismatch)=0 -> []
count nouns (8): {chapatis=1, chiroti=1, doses=1, idlis=1, obbattu=1, pooris=1, rottis=2, unde=2}
```

```
$ java -cp backend/build/classes/java/main Cov.java     # IngredientCategories.forName on all 454
lines=454 named=423 (93.2%)
distinct=99 named=88 (89%)
unnamed distinct: [Water, Cloves, Coriander seeds, Mixed vegetables, Bisi bele bath pudi, Lemons, Huli pudi, Raisins, Eno, Chutney pudi, Vangi bath pudi]
by category: {Dairy=29, Fruit=14, Grains=26, Nuts & seeds=47, Oils & fats=26, Other=31, Pulses=29, Spices=170, Sweeteners=16, Vegetables=66}

$ java -cp backend/build/classes/java/main Miss.java
unnamed lines=31 by name={Water=15, Cloves=1, Coriander seeds=4, Mixed vegetables=4, Bisi bele bath pudi=1, Lemons=1, Huli pudi=1, Raisins=1, Eno=1, Chutney pudi=1, Vangi bath pudi=1}
```

Two comments named a recipe that no longer exists, and both keep their explanation with a real
replacement or an explicit "none today":

- `BookParser.perHead` said *"Delhi's Papdi is the only one in the whole library"* for a portion in a
  different unit family from the yield. Measured: the curated catalogue has none — every recipe that
  states a portion states it in the yield's own family — so the paragraph now says the filter
  currently discards nothing, why it stays, and keeps Papdi as what the vendored library had.
- `LibraryLoader` said the third disambiguation rung exists for Alugadde Palya, twice in the
  Karnataka book. It is in the catalogue once, under Ekadashi. The paragraph keeps the story and says
  the rung is unused today.

All four measurement programs were re-run against the classes the final tree compiles to, and
returned the same numbers.

## Commands run

**The backend suite, through the lock.**

```
$ tools/work-lock.sh run verify 'cd backend && ./gradlew test'
> Task :test

────────────────────────────────────────────────────────
  Test summary
────────────────────────────────────────────────────────
  Total:    3615
  Passed:   3608
  Failed:   0
  Skipped:  7
  Result:   SUCCESS
────────────────────────────────────────────────────────

BUILD SUCCESSFUL in 6m 17s
5 actionable tasks: 4 executed, 1 up-to-date
work-lock: 'verify' released; command exited 0
```

**Counted from the JUnit XML rather than trusting the exit code** (248 result files in
`backend/build/test-results/test/`):

```
$ python3 - <<'EOF'   # sums tests/failures/errors/skipped over every XML
xml files: 248
tests=3615 failures=0 errors=0 skipped=7 passed=3608
failing classes: none
```

3615 / 3608 passed / 0 failed / 7 skipped — the baseline exactly.

**Compiled again afterwards**, because the last few javadoc edits landed after that run began:

```
$ tools/work-lock.sh run verify 'cd backend && ./gradlew compileJava compileTestJava'
> Task :compileJava
> Task :processResources UP-TO-DATE
> Task :classes
> Task :compileTestJava UP-TO-DATE

BUILD SUCCESSFUL in 2s
work-lock: 'verify' released; command exited 0
```

Those edits were comments only, so the test result above still stands for the tree as it is now; the
compile proves the javadoc is well-formed.

## Acceptance criteria

1. **`LibraryLoader.SOURCE` is true of the curated catalogue and traceable.** Now
   `tools/seed/02b-build-catalogue.mjs@2026-09-19` — the generator and the approval date. Verified by
   reading the write path (`row.sourceRef = SOURCE + ":" + filename`) and the column definition
   (`TEXT NOT NULL`, no `CHECK`), and by grepping for any parser, test or migration that asserts the
   old value: none exists.
2. **Nowhere would now hold two different stamps, except one case, and it is stated.** Written out
   above: only a database loaded before today and re-loaded after, which staging's reset removes.
3. **The false prose is corrected, every count measured.** All six named files plus five more the
   grep found. The grep terms from the brief — 5376, 5,376, 46337, 168, "32 state", "vendored",
   kerala/gujarat/tamil_nadu/delhi/bihar — were each re-run after the edits; what remains is listed
   under "left deliberately" below.
4. **"No vendored book carries a `prep` key" is gone.** `LibraryLoader.load(String)` now says what
   the fixture books are actually for: the ladder, because no name in the catalogue collides, and the
   older comma-in-the-name shape, which the catalogue no longer contains.
5. **Explanations kept, examples replaced.** Papdi and Alugadde Palya both keep their paragraph with
   a measured "none in today's catalogue" beside it; `BookParser`'s yield examples are now three real
   strings from the current files (Bassaru, Mysore Pak, Rave Idli).
6. **EPIC-2 §1 carries the replacement note.** Dated 2026-09-19, naming the converter and the true
   counts, in the same amendment style as the 2026-09-08 note above it.
7. **Suite green at baseline.** 3615/3608/0/7, read from the XML.

## Left deliberately, and why

- **`V68__master_recipes.sql` and `V69__recipe_yield_and_portion.sql`** still say "5,376 recipes from
  32 state books" in their comments. Flyway checksums the whole file and `validateOnMigrate` is on by
  default, so editing a comment in an applied migration breaks the next startup of every environment
  that already ran it. The stale words stay; the live description is in `LibraryLoader`.
- **`docs/stories/EPIC-2-recipe-library-DESIGN.md` §1 to §13 counts.** §1 is headed "What is actually
  in that repository" and is a record of an investigation, not a description of our code — the
  numbers are still true of `kranthimj23/ikms`. The doc's own 2026-09-08 amendment sets the rule:
  live descriptions are amended, records of what was investigated keep their words. So the section
  gets the dated note the task asked for, stating the true current figures and where the live ones
  live, and the investigation is left intact. **If Rajeev wants the numbers themselves rewritten,
  say so and it is a half-hour's work** — but it would turn "32 states × 168 each, exactly" into
  something that records nothing.
- **`RecipeLibraryIT`, `BookParserTest`, `LibraryPreparationIT`, `RecipeImportNotBoughtIT`** — out of
  contract, and already corrected by T-404; their vendored references are all past tense.
- **`recipe-library/README.md`** — the brief says it is correct, and it is. Its closing line still
  warns that the design doc's counts are the vendored ones, which remains true and is now also said
  in the design doc itself.

## Not done / needs Rajeev

- **`frontend/app/library/page.tsx:171` still reads "Press "Load the books" to read the vendored
  recipe books in."** That is user-facing copy and it is now wrong — nothing is vendored. It is
  outside this task's contract (backend and docs), so I have not touched it. One-line fix, wants an
  agent that owns that file.
- **`docs/stories/README.md` still says EPIC-2 "is a design, not yet built"** while the design doc
  says "Status: BUILT 2026-08-22" and the library is shipped. Pre-existing, nothing to do with the
  catalogue swap, so I corrected only the count and the "vendored" claim in that paragraph. Worth
  someone fixing.
- **Mysore Pak loses its per-head portion.** Its yield says `~14 Kg (140 gm per devotee)` and
  `BookParser`'s parenthetical rule only reads a bare number before "per devotee", so the figure is
  shown in the yield text and not used in the arithmetic. Measured, not assumed — it is one of the
  three recipes with no machine-readable portion. I have documented it in the javadoc and changed no
  behaviour: widening the rule to accept a unit inside the parenthetical is a behaviour change and
  needs a decision, since it also has to agree with the yield's own family.
- **Four ingredient names fall to `Other` only because they are plural** — Cloves, Coriander seeds,
  Lemons, Raisins, 7 lines between them — where the rules already hold clove, coriander seed, lemon
  and raisin. Documented in `IngredientCategories`; not fixed, because changing the rules changes
  which shelf an imported ingredient lands on and that is behaviour, not prose.
- **No hand smoke-test.** This task changes comments and one stamped string; the only user-visible
  surface is `source_ref`, which nothing in the frontend renders (checked by grep). Verified by the
  test suite and by reading the write and read paths, not by driving the deployed app — the staging
  load has not been run yet.
