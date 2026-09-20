# T-406 — the library page stops claiming it reads vendored books, and the categoriser stops being beaten by a trailing "s"

**Files changed:** all four inside the contract.

- `frontend/app/library/page.tsx` — the empty state's one sentence
- `frontend/__tests__/library.test.tsx` — the assertion that pinned the old wording
- `backend/src/main/java/org/iskcon/kms/library/IngredientCategories.java` — the singular second pass, and the coverage figures in its javadoc
- `backend/src/test/java/org/iskcon/kms/library/IngredientCategoriesTest.java` — new; the measurement is now a test

Not touched: `tools/seed/`, the two recipe-library JSON books, `RecipeLibraryIT`, any other
`docs/work/proof/` file.

---

## 1. The copy

Was (`page.tsx:171`):

```
Press “Load the books” to read the vendored recipe books in.
```

Is:

```
Press “Load the books” to bring in the approved catalogue.
```

60 characters to 58, measured, so it is shorter as asked. It keeps the page's own habit of naming
the button rather than apologising ("Nothing here yet", "Load the books", "Search the library…"),
stays an active imperative, and drops "vendored", which was both false and a word no cook or
operator uses. What it now says is what the loader does: the approved catalogue that ships inside
the application is read into the library.

One test asserted the old line, loosely — `frontend/__tests__/library.test.tsx:119` matched
`/press “load the books”/i`, which would have passed with the false half of the sentence still on
screen. It now asserts the whole sentence, with a comment saying why.

## 2. Plurals

`IngredientCategories` matched its rules against the name as written. A rule is a word with `\b` at
each end and a trailing `s` sits inside that boundary, so the rule holding `clove` did not name
"Cloves" and `coriander seed` did not name "Coriander seeds". Seven lines fell to `Other` for that
reason alone.

`forName` now runs the rules twice: first over the name as written, exactly as before, and only if
nothing matched, again over `IngredientNameMatcher.normalise(name)` — the same singularisation the
duplicate-ingredient guard uses to decide that "Tomatoes" and "tomato" are one ingredient. It was
reusable as it stands; `IngredientNameMatcher` is already imported by `RecipeImportService` in this
same package, so this adds no new coupling, and nothing in it was changed.

**The order is the whole of it.** Singularising first — the obvious implementation — breaks the
rules that are deliberately written in the plural because that is how the books write the
ingredient: `beans`, `greens`, `leaves`, `peas`, `dates`. "Beans" would become "bean" and no rule
holds it. Trying the written name first means the second pass is only ever reached by a name that
was going to be `Other`, so no name filed correctly today can change shelf. Three of the new tests
pin exactly that.

Nothing is hand-listed. The four words in the catalogue are asserted as evidence; the rule is the
singular of the whole name, and a second test proves it on five plurals no book has written —
"Cardamoms", "Tomatoes", "Green chillies", "Cashews", "Guavas" — covering `-s`, `-es`, `-ies`,
`-oes` and a plural inside a two-word name.

### Coverage, measured over the real 454 lines

Measured by running the real rules over the two books in `src/main/resources/recipe-library`, and
printed by the new test rather than by a script. Before (test run against the unmodified
`IngredientCategories`):

```
IngredientCategories over the catalogue: 423 of 454 lines (93.2%), 88 of 99 distinct names
  not named by any rule: {Water=15, Cloves=1, Coriander seeds=4, Mixed vegetables=4,
  Bisi bele bath pudi=1, Lemons=1, Huli pudi=1, Raisins=1, Eno=1, Chutney pudi=1,
  Vangi bath pudi=1}
```

After:

```
IngredientCategories over the catalogue: 430 of 454 lines (94.7%), 92 of 99 distinct names
  not named by any rule: {Water=15, Mixed vegetables=4, Bisi bele bath pudi=1, Huli pudi=1,
  Eno=1, Chutney pudi=1, Vangi bath pudi=1}
```

423 → 430 lines, 88 → 92 names. The before figures match T-405's exactly. The 24 lines left are
water and the temple's own spice blends, which belong on `Other`.

The javadoc's figures were updated to the new ones and now say the test is what measures them.

---

## Commands run and what came back

### The test fails without the change

`tools/work-lock.sh run verify 'cd backend && ./gradlew test --tests "*IngredientCategoriesTest*" -i'`,
run against `IngredientCategories` as it was:

```
IngredientCategoriesTest > a plural reaches the rule that holds its singular > and any other plural, including ones no book has written yet FAILED
    org.opentest4j.AssertionFailedError:
    expected: "Spices"
     but was: "Other"
        at app//org.iskcon.kms.library.IngredientCategoriesTest$Plurals.notInTheCatalogue(IngredientCategoriesTest.java:142)

IngredientCategoriesTest > a plural reaches the rule that holds its singular > the four the catalogue actually writes FAILED
    org.opentest4j.AssertionFailedError:
    expected: "Spices"
     but was: "Other"
        at app//org.iskcon.kms.library.IngredientCategoriesTest$Plurals.inTheCatalogue(IngredientCategoriesTest.java:131)

IngredientCategoriesTest > measured over the catalogue > names all but a handful of the catalogue's lines, and says which it misses FAILED
    java.lang.AssertionError: [catalogue lines a rule names, of 454]
    Expecting actual:
      423
    to be greater than or equal to:
      430

  Total:    5
  Passed:   2
  Failed:   3
  Skipped:  0
  Result:   FAILURE
```

Worth recording: that run printed `work-lock: 'verify' released; command exited 0` under a
`BUILD FAILED`. The exit code lied again; every number below is read from the runner's own summary
or from the JUnit XML.

### `org.iskcon.kms.library`, after the change

`tools/work-lock.sh run verify 'cd backend && ./gradlew test --tests "org.iskcon.kms.library.*"'`

```
RecipeLibraryIT > the preparations and the never-bought marks in the curated files reach master_recipes PASSED
RecipeLibraryIT > every yield and every ingredient quantity resolved — nothing was skipped PASSED
RecipeLibraryIT > adding a library recipe creates a full copy, its ingredients and its category PASSED
...
────────────────────────────────────────────────────────
  Test summary
────────────────────────────────────────────────────────
  Total:    81
  Passed:   81
  Failed:   0
  Skipped:  0
  Result:   SUCCESS
────────────────────────────────────────────────────────

BUILD SUCCESSFUL in 18s
```

The coverage line, read from
`backend/build/test-results/test/TEST-org.iskcon.kms.library.IngredientCategoriesTest$Measured.xml`:

```
<system-out><![CDATA[IngredientCategories over the catalogue: 430 of 454 lines (94.7%), 92 of 99 distinct names
  not named by any rule: {Water=15, Mixed vegetables=4, Bisi bele bath pudi=1, Huli pudi=1, Eno=1, Chutney pudi=1, Vangi bath pudi=1}
]]></system-out>
```

and the per-class counts from the same directory:

```
TEST-org.iskcon.kms.library.IngredientCategoriesTest$Plurals.xml:tests="4" skipped="0" failures="0" errors="0"
TEST-org.iskcon.kms.library.IngredientCategoriesTest$Measured.xml:tests="1" skipped="0" failures="0" errors="0"
```

### The full backend suite

`tools/work-lock.sh run verify 'cd backend && ./gradlew test'`

```
> Task :test

────────────────────────────────────────────────────────
  Test summary
────────────────────────────────────────────────────────
  Total:    3620
  Passed:   3613
  Failed:   0
  Skipped:  7
  Result:   SUCCESS
────────────────────────────────────────────────────────

BUILD SUCCESSFUL in 6m 23s
```

Read back independently from the JUnit XML in `backend/build/test-results/test/`, summing every
`TEST-*.xml`:

```
JUnit XML aggregate: tests=3620 skipped=7 failures=0 errors=0 passed=3613
```

Baseline was 3615 / 3608 passed / 0 failed / 7 skipped. This is 3620 / 3613 / 0 / 7 — the five new
tests in `IngredientCategoriesTest`, nothing else moved. No failures to name.

### Frontend

`tools/work-lock.sh run verify 'cd frontend && npx tsc --noEmit; npm test'`

```
=== tsc --noEmit ===
tsc exit: 0
=== npm test ===
 ✓ __tests__/routes.test.tsx  (3 tests) 1ms

 Test Files  182 passed (182)
      Tests  2603 passed (2603)
   Start at  00:21:24
   Duration  23.35s
```

`tsc --noEmit` printed nothing and exited 0. The library page's own file, run alone so it is named
in the output rather than only counted:

```
 ✓ __tests__/library.test.tsx  (12 tests) 1826ms

 Test Files  1 passed (1)
      Tests  12 passed (12)
```

One thing had to be fixed to run any of this: this worktree had no `frontend/node_modules`, so both
commands failed with "This is not the tsc command you are looking for" and "vitest: command not
found". `frontend/package.json` and `frontend/package-lock.json` are byte-identical to the main
checkout's (`diff` on both, no output), so its `node_modules` was cloned in with `cp -Rc`. Nothing
in the repository changed.

---

## Acceptance criteria

1. **The library page no longer claims to read vendored books.** Rewritten, 58 characters against
   60, verified by the vitest assertion on the whole sentence (see the frontend run above).
2. **The categoriser agrees with the matcher on plurals, by reusing its singularisation.**
   `IngredientCategories.forName` calls `IngredientNameMatcher.normalise`; no second
   singularisation was written and `IngredientNameMatcher` is unchanged.
3. **Coverage measured before and after over the real 454 lines.** 423/454 and 88/99 before,
   430/454 and 92/99 after, both printed by the test and quoted above.
4. **A test that fails without the change.** Three of the five new tests failed against the
   unmodified class; output above.
5. **No hand-listed four words.** The rule is the singular of the whole name; proved on five
   plurals absent from the catalogue.
6. **T-404's assertions still hold against the rebuilt books.** `RecipeLibraryIT` passed in the
   library run above, including "the preparations and the never-bought marks in the curated files
   reach master_recipes" (83 preparations, 15 never-bought marks) and its 454-line assertions.
   **No test asserts a ghee figure read from the books.** Checked rather than assumed:
   `grep -rin "ghee" backend/src/test/java/org/iskcon/kms/library/ frontend/__tests__/` returns one
   backend hit, `RecipeImportCloseMatchIT:329` — `{"Ghee, hot", "Ghee", "hot"}` — which is a row in
   the name-splitting table and carries no quantity or unit. Every frontend hit is a hand-built
   fixture ("Ghee" as an ingredient name, "0.5 Kg" as an invented pack size); none of them reads the
   catalogue. The wider `grep -rin "ghee" backend/src/test` adds only more fixtures of the same kind
   (`TenantExportIT`, `PurchaseOrderIT`, `PurchaseOrderLeadTimeIT`, a `ladder-book` recipe step). So
   the litres-to-kilograms rebuild had nothing asserted to break, and what `RecipeLibraryIT` does
   assert — that every yield and every ingredient quantity resolved, and its line, preparation and
   mark counts — passed against the rebuilt books.
7. **The whole run is green.** Library package 81/81, full backend 3620 with 0 failed, frontend
   2603 with 0 failed and a silent `tsc`.

## Not done / needs Rajeev

- **No hand smoke-test of the copy.** The sentence only appears when the library holds nothing, and
  staging's library is loaded, so the empty state cannot be reached there without emptying it.
  Verified by the vitest assertion on the exact string instead. Stated plainly: not verified in a
  browser.
- **"Field beans" is filed under Vegetables, not Pulses.** Found while measuring, unrelated to
  plurals: the Pulses rule holds `field bean`, whose `\b` the trailing `s` defeats, and the
  Vegetables rule's `beans` catches it first. The singular pass cannot fix it, because the first
  pass already found an answer. Left alone — changing it means reordering or rewording a rule, which
  is a decision about the rules, not a bug in this task. Not asserted either way in the new test.
- **The remaining 24 uncovered lines are deliberate.** Water (15) and the temple's own four blends,
  plus "Mixed vegetables" (4) and "Eno" (1). They belong on `Other`.
- **`next build` was not run.** CI runs it and it catches page-export errors `tsc` and vitest miss.
  The frontend change here is one string literal inside an existing JSX expression, with no change
  to any export, so the risk is as low as a frontend change gets — but it was not run, and that is a
  gap rather than a verdict.
- **This worktree had no `frontend/node_modules`.** Cloned in from the main checkout after checking
  both manifests are identical; see the frontend section. Nothing in the repository changed, but the
  next agent to get this worktree will find a `node_modules` there.
