# T-404 — the library tests and the README made true for Rajeev's 44-recipe curated catalogue

## Before anything else: the worktree was 3 commits behind main

The worktree was handed to me at `7a5ec86`. `main` was at `2aadae4`. The three commits in between
are the ones that matter to part 3 of this task:

- `bccb9fe` an ingredient the temple never buys stays off the shopping list (T-402)
- `8710bbe` a library recipe keeps its preparation and its "never bought" mark (T-401, T-403)
- `fefe6bf` / `2aadae4` the wave's records

At `7a5ec86` the loader does **not** read `prep` or `not_bought` at all, and `LibraryPreparationIT`
and `RecipeImportNotBoughtIT` do not exist. I could not have proved anything about those two fields
on that base. `main` does not touch any file in my contract, nor any recipe-library JSON, so I
fast-forwarded:

```
$ git merge --ff-only main
=== HEAD now ===
2aadae4 docs: the release record for the curated-recipes loader deploy to staging
=== dirty ===
 M backend/src/main/resources/recipe-library/andhra_pradesh.json
 M backend/src/main/resources/recipe-library/karnataka.json
?? tools/seed/
```

The catalogue swap survived the merge intact (the 31 deletions are still staged as working-tree
deletions; `git status` above is filtered to hide them for readability).

## Files changed — every one inside the contract

| File | In contract |
|---|---|
| `backend/src/test/java/org/iskcon/kms/library/RecipeLibraryIT.java` | yes, named |
| `backend/src/main/resources/recipe-library/README.md` | yes, named |
| `backend/src/test/java/org/iskcon/kms/library/BookParserTest.java` | yes, "check the other tests in that package" |
| `backend/src/test/java/org/iskcon/kms/library/LibraryPreparationIT.java` | yes, same package (comment only) |
| `backend/src/test/java/org/iskcon/kms/library/RecipeImportNotBoughtIT.java` | yes, same package (comments only) |
| `backend/src/test/resources/ladder-book/ladder_north.json` | new, test-only fixture (see below) |
| `backend/src/test/resources/ladder-book/ladder_south.json` | new, test-only fixture |
| `docs/work/proof/T-404-catalogue-swap.md` | this file |

Nothing else was touched. `RecipeImportCloseMatchIT` and `BulkSabjisRenameIT` were read and need no
change: both build their `master_recipes` rows by hand or test a pure function, and neither reads the
catalogue.

## Every number was counted out of the JSON, not estimated

Counted with a Python port of `BookParser` (same regexes, same unit tables) over the two files, then
confirmed against the database by the assertions themselves.

```
recipes 44
yield units Counter({'L': 30, 'PIECES': 10, 'KG': 4})
per_head not null 41
rungs {0: 44}        (bare 44, +state 0, +state&category 0)
distinct display lower 44
dups by name>1: {}
ing lines 454  prep 83  not_bought 15
categories union ['breakfast-items','dal','ekadashi','jam-pickles','khichadi','rice','rotis','sabji-s-dry','sabji-s-wet','sweets'] 10
languages ['Kannada','Telugu'] 2
per book: karnataka 42, andhra_pradesh 2
prep values: 83 total, 26 distinct, 'Grated' 31
not_bought: 15 lines, every one named 'Water'
```

## What in `RecipeLibraryIT` was wrong, and what it is now

`EXPECTED_RECIPES` 5376 → 44 was the smallest part of it. The class carried assumptions that had no
data left at all:

- **32 books, 168 each.** Now 2 books, asserted per book by name: `andhra_pradesh` 2, `karnataka` 42.
- **The ladder: bare 3504 / +state 1870 / +state&category 2, and seventeen Sabudana Khichdis.** His
  44 names are all distinct, so every row is rung 0 and nothing is suffixed. Asserted as 44/0/0 plus
  "no display_name contains a parenthetical".
- **`thirdRung`, on Karnataka's two Alugadde Palyas.** The curated Karnataka book has one. Rewritten
  as `ladderSuffixesOnlyWhenNamesCollide`, driven from a new two-book fixture at
  `src/test/resources/ladder-book/` through the package-private `LibraryLoader.load(String)` — the
  same mechanism `LibraryPreparationIT` already uses. **This is the one place I added rather than
  corrected**, and the reason is in the test's javadoc: the ladder is live code that no real file now
  exercises, and deleting the case would have removed rungs 1 and 2 from coverage silently.
- **Yield units 2918 L / 1619 KG / 839 PIECES.** Now 30 / 4 / 10, plus an assertion they sum to 44.
- **`per_head_qty` 5031 of 5032, "the one dropped is Delhi's Papdi".** Now 41 of 44, and the three
  without are named: Limbe Uppinakayi, Mavinakayi Uppinakayi, Mysore Pak.
- **Every import test drove Majjige** — 8 lines, category Beverages, per-head 0.2, ingredient
  'Curd, fresh'. The curated catalogue has no Majjige. Replaced throughout by **Chitranna**: 14
  lines all distinct, category `rice` → temple category Rice (seeded), 30 L at 300 ml a head so
  per-head 0.3. The pre-existing-ingredient case uses 'Turmeric', one of Chitranna's own lines.
- **`substringIsNotTheRule` imported Jharkhand's Bhuja** for its "Onion-free chaat masala" line.
  Jharkhand's book is gone. Rewritten as `nothingProhibitedIsInTheCatalogue`: no ingredient line
  anywhere in the catalogue matches `%onion%` or `%garlic%`. Stronger than the old case and it fails
  loudly if a future curated recipe brings one in.
- **Search for a tag, "jain".** No curated recipe carries a `tags` array at all. Replaced by the
  category name, which sits at the same weight in `search_doc`: "ekadashi" finds exactly 8. The
  other half of that rule — prose is not indexed — is now asserted positively: "knead" appears in six
  method steps and in no name, subtitle, ingredient or category, and finds 0.
- **Browse with `limit=100` expected exactly 100 rows.** The whole catalogue is 44, so that is what
  it asserts.
- **The ordering test searched "rice".** Changed to "salt", which reaches both books (35 Karnataka,
  2 Andhra Pradesh), so the state ordering is actually under test rather than trivially satisfied.

## Part 3 — does the loader still carry `prep` and `not_bought`?

**Yes, and `RecipeImportPreparationIT` does not cover it.** That class builds its `master_recipes`
rows by hand with an inline INSERT and never loads a book, so it proves the *import* end, not the
loader. The two classes that do cover the loader are `LibraryPreparationIT` and
`RecipeImportNotBoughtIT`, and **both drive the fixture at `src/test/resources/prep-book`, not the
real files** — they were written before any real book held either key.

So I added the missing assertion on the real catalogue, in `RecipeLibraryIT`:

```java
@Test
@DisplayName("the preparations and the never-bought marks in the curated files reach master_recipes")
void preparationsAndNeverBoughtMarksSurviveTheLoad()
```

It loads the real two books and asserts against `master_recipes.ingredients`:

- 454 ingredient lines in total
- 83 with `prep` not null
- 15 with `not_bought` true
- 0 lines missing either key (both are written explicitly, `null`/`false` included, so "the book did
  not say" and "the book said no" stay distinguishable)
- every never-bought line is named `Water`
- Akki Rotti's water line reads `prep = "Hot"`, so a preparation is the word itself and not an empty
  string left by a field that was read but not carried

Note for whoever reads this next: `jsonb_exists(line, 'prep')`, not `line ? 'prep'`. A literal
question mark in a JDBC statement is a bind placeholder and PostgreSQL never sees jsonb's operator.

## Commands run, and what came back

### The library package

```
$ tools/work-lock.sh run verify 'cd backend && ./gradlew test --tests "org.iskcon.kms.library.*"'
```

Tail of the log:

```
> Task :test

────────────────────────────────────────────────────────
  Test summary
────────────────────────────────────────────────────────
  Total:    76
  Passed:   76
  Failed:   0
  Skipped:  0
  Result:   SUCCESS
────────────────────────────────────────────────────────

BUILD SUCCESSFUL in 21s
5 actionable tasks: 4 executed, 1 up-to-date
work-lock: 'verify' released; command exited 0
```

Read out of the JUnit XML rather than the exit code, per class:

```
ingredient quantities                                   tests=3 failures=0 errors=0 skipped=0
per-head portions                                       tests=5 failures=0 errors=0 skipped=0
yields                                                  tests=5 failures=0 errors=0 skipped=0
org.iskcon.kms.library.BookParserTest                   tests=1 failures=0 errors=0 skipped=0
org.iskcon.kms.library.BulkSabjisRenameIT               tests=2 failures=0 errors=0 skipped=0
org.iskcon.kms.library.LibraryPreparationIT             tests=8 failures=0 errors=0 skipped=0
org.iskcon.kms.library.RecipeImportCloseMatchIT         tests=15 failures=0 errors=0 skipped=0
org.iskcon.kms.library.RecipeImportNotBoughtIT          tests=13 failures=0 errors=0 skipped=0
org.iskcon.kms.library.RecipeImportPreparationIT        tests=7 failures=0 errors=0 skipped=0
org.iskcon.kms.library.RecipeLibraryIT                  tests=17 failures=0 errors=0 skipped=0
TOTAL tests=76 failures=0 errors=0 skipped=0
```

The three new or rewritten cases, named in the log:

```
RecipeLibraryIT > a name held by two books is suffixed with the state, and within one book with the category PASSED
RecipeLibraryIT > the preparations and the never-bought marks in the curated files reach master_recipes PASSED
RecipeLibraryIT > the 44 curated names are already distinct, so nothing is suffixed PASSED
```

### The full backend suite

```
$ tools/work-lock.sh run verify 'cd backend && ./gradlew test'
```

Tail of the log:

```
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

BUILD SUCCESSFUL in 6m 23s
5 actionable tasks: 2 executed, 3 up-to-date
work-lock: 'verify' released; command exited 0
```

Counted from the 248 JUnit XML files rather than the summary or the exit code:

```
XML files: 248
tests=3615 failures=0 errors=0 skipped=7 passed=3608
FAILURES: none
```

**No failures anywhere in the suite.** The 7 skipped are the usual ones that need real cloud
credentials or the perf fixture, none of them related to this change:

```
GoogleTranslationSmokeIT > translates English to Kannada via the real Cloud Translation API
ShoppingListPerformanceIT > T-139: the shopping list, timed at the API over a temple with years of history
TempleScaleFixtureControlIT > doubling the history parameter doubles the ledger it generates
TempleScaleFixtureControlIT > the fixture produces exactly the rows it claims, counted from the database
TempleScaleFixtureControlIT > each temple's generated data is invisible to the other, as the application role
TempleScaleFixtureControlIT > the count check fails when the rows are not there — it is not a formality
GcsDocumentStorageSmokeIT > stores and reads back bytes against the real GCS bucket
```

Docker was up throughout; Testcontainers started real PostgreSQL for every IT.

### Facts checked against the tree before writing them into the README

```
$ git check-ignore -v docs/work/reference/curated-recipes/index.json
.git/info/exclude:24:docs/work/reference/curated-recipes/    (gitignored — confirmed)

$ ls docs/work/reference/curated-recipes/*.json | wc -l
      46
status counts: Counter({'approved': 44, 'needs-work': 1})   (+ index.json)

$ grep -n 'curated-recipes' tools/seed/02b-build-catalogue.mjs
12: * In:  docs/work/reference/curated-recipes/   (the canonical copy — see that folder's README)
13: * Out: backend/src/main/resources/recipe-library/
78:const files = readdirSync(IN).filter((f) => f.endsWith('.json') && f !== 'index.json').sort();
```

So the README's claims — generated, 44 approved, one `needs-work` left out, the input folder
gitignored, that command regenerates it — are each checked, not assumed.

## Acceptance criteria

| Asked for | Verified how |
|---|---|
| `EXPECTED_RECIPES` correct | 44, counted from JSON and asserted against `master_recipes`; test passes |
| Every other 32/33-book, 168-per-book, 21-category, 16-language assumption corrected | Class read end to end; every such assertion rewritten to counted figures, listed above. No `21` or `16` assertion existed in the class — those were in the prose, now rewritten |
| Corrected to what the files actually contain, never estimated | Python port of `BookParser` over the JSON produced each figure; the passing assertions then confirm it end to end through Flyway + the real loader |
| Other tests in the package checked | All 6 read. `BookParserTest` and `BulkSabjisRenameIT` are catalogue-independent (2 stale comments fixed in `BookParserTest`). `RecipeImportCloseMatchIT` and `RecipeImportPreparationIT` insert rows by hand — no change. `LibraryPreparationIT` and `RecipeImportNotBoughtIT` use their own fixture — 3 stale comments fixed |
| Tests elsewhere naming a deleted state book | Grepped all 30-odd state names across `backend/src/test`. Hits in `TenantExportFilenameTest`, `NotificationTemplateTest`, `ShiftMealLinkIT` are city and person names ("Bengaluru", "Radha Devi"), not books. None needed changing |
| README rewritten and true | Rewritten; every claim in it checked against the tree above |
| Loader still carries `prep` / `not_bought` | New `RecipeLibraryIT` test on the real files: 454 lines, 83 preps, 15 marks, no missing keys, all marks are Water, Akki Rotti's water reads "Hot". Passes |
| Library package run, numbers from the log/XML | 76/76, XML per class above |
| Full backend suite, counts from the XML plus every failure named | 3615 total, 3608 passed, 0 failed, 7 skipped, all 7 named |

## Not done / needs Rajeev

**1. `LibraryLoader.SOURCE` still stamps every row with the wrong provenance.** It is
`"kranthimj23/ikms@41cf173"`, so all 44 curated rows get a `source_ref` of e.g.
`kranthimj23/ikms@41cf173:karnataka.json`. That is now false — these files are generated from
Rajeev's curated recipes, not copied from that commit. `LibraryLoader.java` is outside my contract
so I did not touch it. It wants a one-line change and a note, and it is visible in the database.

**2. Other stale prose about the vendored books, all outside my contract:**

- `LibraryLoader.java` class javadoc: "32 state files, 168 recipes each, 5,376 in all", "byte-for-byte
  copies … so they can still be diffed against upstream", "count every name across all 32 books",
  "leaves 5,376 rows rather than 10,752". Its `load(String)` javadoc also says "no vendored book
  carries a `prep` key … `RecipeLibraryIT` reads the real 32", both now false.
- `BookParser.java`: "all 5,376 recipes and all 46,337 ingredient lines", the eight-token frequency
  table, "4,563 recipes carry it in their own `per` field … 344 have neither", "Delhi's Papdi is the
  only one in the whole library".
- `SearchQuery.java`: "a 5,376-row library". `MasterRecipeInput.java`: "a batch of 5,376 nobody
  typed". `MasterRecipeService.java`: "on 5,376 short dish names".
- `docs/stories/EPIC-2-recipe-library-DESIGN.md` §1: still the vendored spec — 5,376 recipes, 32
  states × 168, "8.95 MB, 5,376 rows". My README now points at it *and says its counts are wrong*,
  which is the honest stopgap, not a fix.

None of that breaks anything. All of it will mislead the next person who reads it.

**3. Commit message body in `8710bbe` says "84 of his notes" and "45 recipes"; the catalogue as
built has 83 preparations across 44 recipes.** I did not chase the difference. It is most likely the
excluded `karnataka__halubai.json`, which would account for both the missing recipe and the missing
preparation, but I have not confirmed that and am not going to state it as fact.

**4. No hand smoke-test.** This change is test code and a README; it adds no user-facing surface, so
Commandment 5's manual pass has nothing to drive. The behaviour the catalogue swap *does* change —
what the library screen lists — is not mine in this task and has not been driven in a browser by me.

**5. The fixture I added is a judgement call.** `src/test/resources/ladder-book/` is two small books,
five recipes, existing purely to keep the disambiguation ladder's rungs 1 and 2 under test now that
no real file reaches them. If Rajeev would rather the ladder simply lose its coverage until a second
curated book collides with the first, delete `ladderSuffixesOnlyWhenNamesCollide` and that folder;
nothing else depends on them.
