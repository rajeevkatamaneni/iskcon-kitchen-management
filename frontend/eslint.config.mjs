// ESLint for the frontend test suite (T-076).
//
// WHY THIS EXISTS, AND WHY IT IS THIS SMALL
//
// `package.json` carried `"lint": "next lint"` from the first commit with no ESLint config and no
// ESLint dependency anywhere in the tree, so the script could not run, CI never called it, and
// nobody noticed for a year. The thing that finally justified fixing it was T-075: a hand sweep
// for one defect shape — a synchronous query asserted against content that arrives
// asynchronously — read 12 candidate sites, called 10 of them wrong, and missed the two that were
// actually racy. A machine reads that shape better than a person does, every time, which is the
// whole argument for a linter here.
//
// Rajeev's ruling on scope, and it is the part a linter usually gets wrong: NO STYLE RULES AT ALL.
// A formatting or preference sweep across 119 test files would produce a diff nobody can review,
// would bury the handful of real findings inside it, and would be reverted by whichever builder
// hits the first merge conflict. Every rule below is enabled because switching it off lets a test
// pass while proving nothing, or fail for a reason other than the one it names. If you cannot make
// that argument about a rule, it does not belong in this file.
//
// The counts in the comments are from the diagnostic pass on 2026-09-10: every non-deprecated rule
// the plugin ships, turned on at once against the tree. That run reported 2,261 problems, of which
// 2,257 came from the preference rules listed at the bottom. Four came from the rules enabled here.
//
// SCOPE: test files only. Every rule below is a Testing Library rule and has no meaning outside a
// test, so `app/`, `components/` and `lib/` deliberately match no config block and are not linted.
// That is not an oversight — see the note on eslint-plugin-react-hooks at the bottom of this file
// for the one thing that decision leaves on the table.
//
// Run it with `npm run lint`, which is `eslint . --max-warnings=0`. NOT `next lint`: Next 14's
// wrapper expects an `.eslintrc*` file and does not read flat config, so pointing the script at it
// would give us a linter that silently checks nothing — which is the failure this task exists to
// end, not to repeat.

import tsParser from "@typescript-eslint/parser";
import testingLibrary from "eslint-plugin-testing-library";

export default [
  {
    // Nothing here is a source directory; listing them keeps a stray `eslint .` from walking the
    // build output, which contains generated code no rule of ours has an opinion about.
    ignores: ["node_modules/**", ".next/**", "public/**", "coverage/**", "next-env.d.ts"],
  },
  {
    files: ["__tests__/**/*.ts", "__tests__/**/*.tsx"],
    languageOptions: {
      // The TypeScript parser without type information. Every rule below works on syntax alone, so
      // the project-service machinery would cost a full type-check per run and buy nothing.
      parser: tsParser,
      ecmaVersion: 2022,
      sourceType: "module",
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    plugins: { "testing-library": testingLibrary },
    rules: {
      // ---------------------------------------------------------------------------------------
      // The async group. This is the T-075 defect shape, and it is the reason for the whole file.
      // ---------------------------------------------------------------------------------------

      // `screen.findByText(...)` without `await` returns a pending promise. Every matcher passes
      // against a promise object, so the assertion is green whether or not the element ever
      // appears. A test that cannot fail is worse than no test, because it is counted.
      "testing-library/await-async-queries": "error",

      // The same hole for `waitFor` and `waitForElementToBeRemoved`: unawaited, the test finishes
      // before the condition is ever evaluated, and the rejection surfaces — if at all — as an
      // unhandled promise inside whichever unrelated test happens to be running at the time.
      "testing-library/await-async-utils": "error",

      // `userEvent`'s methods are async in v14. Unawaited, the click is queued and the assertions
      // below it run against the screen as it was before the click.
      "testing-library/await-async-events": "error",

      // The inverse, and the reason it is here rather than in the preference list at the bottom:
      // `await screen.getByText(...)` resolves to the element, so it reads like a wait and is not
      // one. Whoever wrote it believed there was a wait at that line. There is not, and the next
      // person to trust that line is the one who gets the flake.
      "testing-library/no-await-sync-queries": "error",

      // `fireEvent.click(el, somePromise)` — the event is dispatched with a promise as its init
      // object and the interaction the test claims to perform never happens.
      "testing-library/no-promise-in-fire-event": "error",

      // A `waitFor` callback is retried until it stops throwing, so anything in it that is not a
      // pure assertion runs several times: a click inside one fires twice on a slow machine and
      // once on a fast one, which is a flake that only ever reproduces in CI.
      "testing-library/no-wait-for-side-effects": "error",

      // Its sibling, and read what it actually does before judging it: in plugin 7.16.2 this fires
      // on a *second assertion against the same subject* inside one waitFor, not on any two
      // assertions. That second one is retried for the whole timeout when it fails, so a broken
      // expectation is reported as "waitFor timed out" instead of as the assertion that failed.
      "testing-library/no-wait-for-multiple-assertions": "error",

      // A snapshot taken inside a retried callback captures whichever intermediate render the
      // retry happened to land on, and is written to disk as if it were the settled state.
      "testing-library/no-wait-for-snapshot": "error",

      // `waitForElementToBeRemoved(() => screen.getByText(x))` throws the moment x is gone, which
      // is the exact condition being waited for. The helper is designed around `queryBy*`
      // returning null; `getBy*` makes success indistinguishable from an error.
      "testing-library/prefer-query-by-disappearance": "error",

      // ---------------------------------------------------------------------------------------
      // Assertions that can never run, and queries that lie.
      // ---------------------------------------------------------------------------------------

      // `expect(screen.getByText(x)).not.toBeInTheDocument()` never reaches the matcher: the query
      // throws first. The test does fail when the element is absent, but it fails saying it could
      // not find the element it was asserting was absent, which sends the reader the wrong way.
      "testing-library/prefer-presence-queries": "error",

      // A /g regexp keeps `lastIndex` between calls, so the same query against the same DOM
      // matches and then does not match, depending on how many elements it was tested against
      // first. It is a genuine source of order-dependent failures and is never intentional.
      "testing-library/no-global-regexp-flag-in-query": "error",

      // Importing `render` or `fireEvent` from @testing-library/dom rather than /react gets you
      // the unwrapped versions: no `act()` around the event, so React state updates are not
      // flushed before the next line reads the DOM, and no auto-cleanup, so the previous test's
      // markup is still mounted and `getBy*` starts throwing "found multiple elements".
      "testing-library/no-dom-import": "error",

      // ---------------------------------------------------------------------------------------
      // DELIBERATELY OFF. Left listed, with counts, so nobody has to re-run the diagnostic to
      // find out whether a rule was considered. Each is a preference, not a defect.
      // ---------------------------------------------------------------------------------------
      //
      //   prefer-user-event             (898) fireEvent works; this is a change of testing idiom
      //                                       across every test in the tree.
      //   prefer-implicit-assert        (930) and prefer-explicit-assert (80) are each other's
      //                                       opposite — the plugin ships both, and enabling both
      //                                       is incoherent. Proof, if any were needed, that
      //                                       "turn on everything recommended" is not a policy.
      //   no-node-access                (231) .querySelector on a rendered node. Sometimes the
      //                                       only way to assert on an element with no role.
      //   prefer-find-by                 (36) waitFor + getBy is correct, just longer than findBy.
      //   prefer-screen-queries          (32) the suite scopes with within(...) on purpose.
      //   no-unnecessary-act             (27) redundant act() is harmless.
      //   no-container                   (17) same argument as no-node-access.
      //   render-result-naming-convention (3) naming.
      //   no-manual-cleanup               (2) FALSE POSITIVE HERE, both times. calendar.test.tsx
      //                                       and shift-attendance.test.tsx call cleanup() in the
      //                                       middle of a test to unmount before rendering again;
      //                                       without it the second render duplicates the markup
      //                                       and every getBy* throws. The rule flags the import
      //                                       and cannot see the difference between that and a
      //                                       redundant afterEach.
      //   no-test-id-queries              (1) the suite has one, deliberately.
      //   no-debugging-utils              (0) catches a left-behind screen.debug(). Real, but it
      //                                       is tidiness, not correctness, and the ruling was
      //                                       correctness only.
      //   no-render-in-lifecycle          (0) rendering in beforeEach is a legitimate choice.
      //   no-await-sync-events            (0) a redundant await on a sync event is harmless.
      //   prefer-query-matchers           (0) requires a project convention we have not set.
      //   consistent-data-testid          (0) naming, and it needs a pattern configured.
      //   prefer-user-event-setup         (0) follows prefer-user-event, which is off.
    },
  },
];

// ONE THING THIS CONFIG DOES NOT COVER, recorded so it is not rediscovered.
//
// app/planner/reuse/page.tsx:156 and components/planner/MealComposer.tsx:376 each carry an
// `// eslint-disable-next-line react-hooks/exhaustive-deps` comment, written when there was no
// ESLint in the project at all. Nothing defines that rule, so linting those directories reports
// "Definition for rule 'react-hooks/exhaustive-deps' was not found" — an error about the comment,
// not about the code. Fixing it properly means adding eslint-plugin-react-hooks and enabling
// rules-of-hooks, which is worth doing (a conditionally-called hook is a crash, not a style
// question) but adds a plugin this task was not scoped to add and forces a separate decision about
// what to do with exhaustive-deps' own findings. Left for a task of its own; see
// docs/work/proof/T-076.md.
