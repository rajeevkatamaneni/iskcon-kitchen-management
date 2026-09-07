# Dispatch ledger

Read `docs/work/README.md` first — it explains what this file is and who is allowed to write to it.
Read `docs/work/INTAKE.md` second — it is the verification behind every row here, and it is where the
docket items that are *not* build tasks went.

**Status: waves 0, 1 and 2 SHIPPED.** Wave 1 released 2026-09-07 in four commits — `dd3fb31`
(T-003), `81fd72f` (T-002), `0448573` (T-001) and the planning commit that carries this file. Wave 2
released 2026-09-07 in five, one per task, with T-030 travelling alongside it: `a41d094` (T-028),
`67d5f05` (T-004), `bfca0ac` (T-006), `723696c` (T-009), `99b91d6` (T-030). CI green and deployed to
staging both times. **T-017 did not ship and is not proven** — it stopped before writing a line of
product code and is `blocked` on Rajeev; see its row. **Waves 3-8 RE-PLANNED 2026-09-07 and NOT
dispatched: Rajeev has not seen the re-planned waves and nothing beyond wave 2 may be started
without him.**

**T-028 was added to wave 2 on 2026-09-07**, at Rajeev's instruction, making it a five-builder
wave. It is the shopping-list vendor defect this file had recorded under *"found while re-planning,
not scheduled"* and nowhere else — the only row here that came from no list. It is a live
data-loss bug on a shipped screen, it needs no reservation, and closing it closes nothing
elsewhere.

**T-029 went out on its own, ahead of wave 2 and outside the batch** (`e9f981e`, 2026-09-07):
"Continue with Google" never asked which account, so signing out and signing back in silently
returned the same person. It jumped the queue because it was the thing stopping Rajeev from testing
wave 1 — and it is a large part of why the formal UAT pack has never been run. Its row is under
**Out of band** below.

**Shipped is not done.** Everything from waves 1 and 2, plus T-029 and T-030, is on staging and
**not one of them has been seen working by Rajeev**, so nothing has left
`docs/OUTSTANDING_BUILD_LIST.md` and nothing here counts as accepted. That backlog of unverified
work is now eleven screens deep, which is itself worth saying out loud.

Wave 1 ran three builders concurrently and **no builder touched a file outside its contract** — the
working tree holds exactly the union of the three contracts plus the work manager's `nav.ts`
reservation. One contract was widened mid-wave, onto a file provably nobody else held; that is
recorded on T-002.

**Wave 1 still owes one thing, and it is a human's:** none of the three could be smoke-tested by
hand, because `.next/` and a running dev server cannot be shared by three concurrent builders and
the disabled-account screen needs a real Firebase sign-in. Commandment 5 wants all of it pressed on
staging after release — an approved ingredient request seen as a cook, Today as a cook, `/my-shifts`
typed directly as a manager, an empty staff schedule, a stock correction, stopping tracking an item,
and a disabled account signing in.

Wave 1's `frontend/lib/nav.ts` reservation is **committed** with the planning commit, and it is
**one line, not two**: `/donate` gained `ADMIN`. The second edit — narrowing `/my-shifts` to
`[VOLUNTEER]` — was **reverted before release** and is an open question for Rajeev in `DECISIONS.md`,
because it removed a menu entry no task had asked for and contradicted a deliberate assertion in
`nav.test.ts`. No other reservation has been written into any shared file.

> ## Re-planned 2026-09-07, and what changed
>
> Three things happened after the first plan was written, and each one moved something:
>
> 1. **The renumber shipped** (`ad509f7`). Every reserved code below has been re-allocated from
>    `KMS-400124`; the nine four-digit numbers the first plan proposed never existed and have no
>    successor. See the reservations table at the foot.
> 2. **Rajeev settled seven questions** — `DECISIONS.md` D-1 to D-7, binding. D-1 puts non-food
>    procurement in scope and splits it in two, which is **five new tasks, T-023 to T-027**, and a
>    new wave. D-4 names the two new permissions and one of them is **not** the name this file used.
> 3. **`SESSION_EXPIRED` came out of T-003** and stays out until Rajeev answers Question 8.
>
> **A gap in the reservation table, found while re-planning and now closed.** The protocol reserves
> `RolePermissions.java` but not `Permission.java`. That is wrong: the constant is declared in
> `Permission.java` and only *granted* in `RolePermissions.java`, so a new permission touches both,
> and `Permission.java` is written to be read as a document in exactly the same way — every constant
> carries a paragraph explaining why it was split out. Two builders appending to it concurrently
> corrupts it just as surely. **Both files are reserved from here on.**

**Wave 0 — T-000, the six-digit error-code renumber.** Ruled by Rajeev on 2026-09-07
(`DECISIONS.md` D-6), dispatched alone and first so that nothing else in this batch would be written
against a numbering scheme it was about to be renumbered out of. Proof in `proof/T-000.md`; the full
old→new table is `docs/ERROR-CODE-RENUMBER-2026-09-07.md`. **Committed and deployed to staging.**
Every code is now six digits — client `KMS-400001`–`KMS-400123`, server `KMS-500001`–`KMS-500005` —
allocated flat in declaration order, and the highest client number in use is `KMS-400123`.

> ### Error codes: resolved 2026-09-07, and the resolution is not what a reader would guess
>
> Two kinds of stale number were in this file and they needed opposite treatment.
>
> - **Codes that exist** now carry their six-digit successor inline, looked up in
>   `docs/ERROR-CODE-RENUMBER-2026-09-07.md` and then **checked against the shipped
>   `ErrorCode.java`**, which is the authority. Do not guess a successor from the old digits: the new
>   numbers follow declaration order and nothing else. Worth stating plainly, because two of the
>   three numbers handed to this re-plan in conversation were wrong —
>   `SESSION_EXPIRED` is **`KMS-400018`** (not 400012) and `MEAL_ALREADY_RECORDED` is
>   **`KMS-400098`** (not 400072). Both verified at `ErrorCode.java:155` and `:551`.
> - **Codes that were only ever proposed** — the nine `KMS-4995`–`4999` / `4018`–`4021` — had no
>   successor and never will. They are **re-allocated from `KMS-400124`** in the table at the foot of
>   this file, in wave order, so the numbers ascend in the order they will actually be appended to
>   `ErrorCode.java`. The constant names, HTTP statuses and copy were already reviewed and stand.
>
> **Question 12 is closed.** It asked Rajeev to approve spilling out of the 4900s band. There is no
> band to spill out of.

Batch: **the UAT Docket of 2026-09-06** (`docs/work/intake/2026-09-06-uat-docket.txt`), plus the
**procurement decisions of 2026-09-07** (`DECISIONS.md` D-1, D-2, D-7).
**26 tasks, 8 waves.** Every task below is `state: queued` except wave 1's three, and stays that way
until Rajeev authorises a wave. Reservations are **proposed**; the work manager writes them into the
shared files in one pass immediately before the wave it belongs to, and not before.

**Path contracts exclude the reserved files by construction.** No builder in this batch may open
`ErrorCode.java`, `Permission.java`, `RolePermissions.java`, `frontend/lib/api.ts`,
`frontend/lib/nav.ts`, `frontend/lib/routes.ts`, `frontend/components/Sidebar.tsx`,
`docs/CHANGELOG.md` or `docs/WORK_QUEUE.md`. Where a task needs something in one of those, it is
listed under **reservations** and will already be there.

**Procurement contracts name files, never packages.** The five procurement tasks sit in overlapping
packages — T-024 and T-025 are both in `purchaseorder/`, on different files — so every one of them
lists concrete paths and no `**` glob. A builder that reads its contract as "the package" will
collide with the builder beside it. This is called out again in each task.

---

# Wave 1 — three screens and one filter, no migrations, no new codes

**DISPATCHED 2026-09-07.** Three builders running concurrently. The cheapest real value in the
batch: T-001 is the docket's own first priority, T-002 its third, and T-003 costs nothing in
reservations because its error codes already exist and are merely never thrown.

Wave 1's whole reservation is **two lines in `frontend/lib/nav.ts`**, written before dispatch and
sitting uncommitted in the working tree. No migration, no new error code, no permission change.

### T-001 — Correcting a stock movement, and stopping tracking an item

- **source:** docket **M1** + **M8** (INTAKE M1, M8). `OUTSTANDING_BUILD_LIST` I2 is adjacent but not
  closed by this.
- **wave:** 1
- **state:** **shipped** *(2026-09-07 — `0448573` — released in wave 1; not yet seen working by Rajeev)*
- **what:** The inventory item's movement history already renders and already labels corrections, and
  the backend already has both a compensating-entry endpoint and a metadata-only delete — neither has
  a caller. Add a "Correct this movement" action to each row of the history on the item page, taking
  the mandatory note the endpoint requires (`@NotBlank`, max 500) and showing the resulting reversal
  in place; the endpoint refuses a second correction of the same movement, so surface that refusal
  rather than hiding the control. Separately, add a "Stop tracking this item" action that calls the
  existing delete, with a confirmation that says plainly what it does and does not do — it removes the
  item from the list and from low-stock warnings, and it leaves every movement in the ledger intact.
  Do not zero the stock as a substitute for deleting; that writes a false stock event, which is the
  confusion `OUTSTANDING_BUILD_LIST` I2 was raised about.
- **paths:**
  - `frontend/app/inventory/[id]/page.tsx`
  - `frontend/__tests__/inventory-correction.test.tsx` *(new)*
- **reservations:** none. `api.compensateMovement` (`frontend/lib/api.ts:3467`) and
  `api.deleteInventoryItem` (`:3443`) both already exist with the right signatures.
- **acceptance:**
  - A correction posted from the UI produces a reversing `ADJUSTMENT` row cross-referenced to the
    original, and the history shows both.
  - Correcting an already-corrected movement surfaces the server's refusal as readable text, not a
    silent failure or a raw code.
  - Deleting an item returns to the inventory list and the item is gone; a test asserts the
    confirmation copy states the ledger survives.
  - `npx tsc --noEmit` clean; the new vitest file passes.
- **proof:** `docs/work/proof/T-001.md`
- **notes from the build, for whoever reviews it:**
  - It fixed a pre-existing render defect on the very line the task described as "already labels
    corrections": the reason and the word "Correction" were adjacent expressions with no separator, so
    every correction row read **"Count correctionCorrection"**. In contract and inside the feature.
  - **One deviation to sanction.** The "Correct" button stays enabled on rows that already show a
    correction, so a row can show a "Corrected" tag *and* a live button. The builder's argument is
    that two people on two tabs is ordinary and the second presser deserves to be told what happened
    rather than find the control gone — which is also what the task asked for ("surface that refusal
    rather than hiding the control"). Reversible in one line if Rajeev prefers tagged rows inert.
  - **Not done: no hand smoke test.** Evidence is `tsc` and vitest only, and this adds two dialogs, so
    Commandment 5 still wants it pressed by hand before it counts as seen working.
  - Stopping tracking returns to the list with **no confirmation banner**, because
    `app/inventory/page.tsx` only reads `?added=` and was outside the contract. A one-line follow-up.
  - **A protocol finding worth keeping.** Its first `tsc` failed on `app/page.tsx` — another builder's
    file, caught mid-write. `tools/work-lock.sh` serialises the *verify* phase but not the *edit*
    phase, so a whole-project `tsc` taken inside the lock can still read a neighbour's half-written
    file. Green inside a wave is therefore not green on the merged tree, and the release agent's
    clean-tree run over `git archive HEAD` remains the only real gate.
- **shipped:** `0448573` — *feat: a stock movement can be corrected, and an item can stop being tracked*

### T-002 — The five controls that refuse the person looking at them

- **source:** docket **C1–C5**, which are also the docket's own S4c, S4d and S4e (INTAKE C1–C5, S4).
  The docket's third priority: *"An hour's work, and each otherwise arrives as a bug report from every
  tester holding that role."*
- **wave:** 1
- **state:** **shipped** *(2026-09-07 — `81fd72f` — released in wave 1; not yet seen working by Rajeev)*
- **what:** Five independent refusals, each verified at a line in INTAKE. (1) On the ingredient-request
  detail, the whole work-order card — language picker included, which 403s before a button is even
  pressed — renders for anyone; gate it on `mayIssue`, the boolean the same file already computes and
  already uses correctly for `RecordIssue` a few lines above, and leave the calm explanation that is
  already there for the non-issuer. (2) Today's "Working today" tile links every role to a page that
  refuses kitchen staff; `StatTile`'s `href` is optional, so pass `undefined` when the reader may not
  go there — the count itself is fine for them to see, so do not strip the tile. (3) `/my-shifts` can
  structurally never hold anything for kitchen staff or managers, and its empty state instructs an
  action they have no route to; reword it so it does not offer what the role cannot do (the nav row is
  the work manager's, see reservations). (4) The staff-schedule empty state links a manager to
  `/staff`, which admits Temple Admins only — and the file's own doc comment already claims that link
  was removed; make the code match its documentation. (5) is a nav-only fix and is the work manager's.
  **Do not "fix" the underlying permissions.** Every one of these is a screen offering something the
  API is right to refuse; the menu and the button move, the policy does not.
- **paths:**
  - `frontend/app/ingredient-requests/[id]/page.tsx`
  - `frontend/app/today/page.tsx`
  - `frontend/app/my-shifts/page.tsx`
  - `frontend/app/staff-schedule/page.tsx`
  - `frontend/__tests__/role-refusals.test.tsx` *(new)*
  - **forbidden, and this one is easy to reach for:** `frontend/components/RequireRole.tsx` is
    **T-003's file this wave**. It is where the blocking "Not your page" screen lives, so a builder
    fixing C2 is tempted straight into it. The fix for all four is on the pages, never in the guard.
    Also forbidden: `frontend/components/ds/StatTile.tsx` — its `href` is already optional
    (`:30,52`), so C2 only passes `undefined` and changes nothing in the component.
- **reservations:** both **written into `frontend/lib/nav.ts` before dispatch**; the builder was told
  the file is done and not to open it.
  - **C5**: `/donate` now reads `roles: [ADMIN, VOLUNTEER, MANAGER, KITCHEN]`. It omitted `ADMIN`
    while `donate/page.tsx:13` admits `TEMPLE_ADMIN` — the exact inverse of the defect C1–C4 are, and
    a breach of nav.ts's own rule at `:11-15`.
    **Reversed by T-030 on 2026-09-07.** There were two ways to close that breach — widen the menu
    or narrow the page — and this wave took the cheaper one without asking. Rajeev ruled the other
    way (D-8): the row and the guard both become `[VOLUNTEER]`. The lesson is not about donations —
    *when a task can be closed from either end, which end is a product decision and goes to him.*
  - **C3 — written, then reverted, and it did not ship.** `/my-shifts` was narrowed to
    `roles: [VOLUNTEER]` on the reasoning that every write behind that screen needs
    `SIGN_UP_FOR_SHIFTS`, which `RolePermissions.java:128` grants to `VOLUNTEER` alone, so for a
    manager or a cook the page can never hold anything. The main session reverted it before release:
    no task asked for a menu row to be removed, and `nav.test.ts` asserts the opposite on purpose.
    C3's actual fix is the empty-state rewrite on the page, which did ship. The menu row is now a
    question for Rajeev — `DECISIONS.md`, "For Rajeev".
- **acceptance:**
  - Rendered as `KITCHEN_STAFF`, the ingredient-request page shows neither Download nor Print and
    fires no request to `/api/v1/work-orders/languages`.
  - Rendered as `KITCHEN_STAFF`, the "Working today" tile is present and is not a link.
  - The `/my-shifts` empty state contains no instruction to browse shifts when the role cannot.
  - The staff-schedule empty state contains no link to `/staff`.
  - One test per fix, each asserting the role-conditional behaviour rather than the markup.
- **proof:** `docs/work/proof/T-002.md`
- **contract widened mid-wave, and why that was allowed:** the builder found
  `frontend/__tests__/ingredient-request-detail.test.tsx:343` asserting *"The work order is a reading
  act, so it is offered to whoever is looking"* — the exact belief C1 says is wrong — and **stopped at
  its contract boundary and reported instead of reaching in.** That is the protocol working, not a
  failure. The file is in no other task's contract this wave, so it was added to T-002's and the same
  builder sent back to invert the assertion and its comment. The protocol's "fix it between waves,
  never during" is about *reservations*; this is a path contract widened onto a file provably nobody
  else holds, which is the case that rule exists to make safe.
- **sanctioned deviation:** the `/my-shifts` empty state is **conditional by role**, not flatly
  stripped. The acceptance criterion said "no instruction to browse shifts" without qualification and
  the criterion was wrong: `/shifts` genuinely exists and is genuinely in a volunteer's nav, so for a
  volunteer the sentence is true and useful, and the defect was only ever that it was shown to roles
  who cannot act on it. Conditional copy fixes the real defect; unconditional deletion would have
  removed something correct. The builder's reading, accepted over mine.
- **note:** gating the whole work-order card rather than its buttons is deliberate — the
  `/work-orders/languages` query lives inside the card and fires on mount, so gating the buttons
  would have hidden the evidence of the 403 while leaving the 403 in place.
- **not done: no hand smoke test.** All four fixes are user-facing and `.next/` was shared with two
  live builders. Commandment 5 still wants one pass on staging.
- **shipped:** `81fd72f` — *fix: four screens stop offering what the server is right to refuse*

### T-003 — The authentication filter says why it turned someone away

- **source:** docket **D1, D2, D3** (INTAKE D1–D3).
- **wave:** 1
- **state:** **shipped** *(2026-09-07 — `dd3fb31` — released in wave 1; not yet seen working by Rajeev)*
- **what:** Two carefully written error codes — `ACCOUNT_DISABLED` (**`KMS-400019`**,
  `ErrorCode.java:159`) and `NO_ACCOUNT_AT_TEMPLE` (**`KMS-400020`**, `:163`) — are declared with good
  copy and thrown nowhere, because `AuthenticationFilter` drops silently at `:139-143` and `:118-128`
  and the request falls through to a bare bodyless 401. **The real work is the mechanism, and it does
  not exist yet:** `GlobalExceptionHandler` is a `@RestControllerAdvice` and never sees anything
  thrown before `DispatcherServlet`; `SecurityConfiguration.java:87-88` wires a bare
  `HttpStatusEntryPoint` with no body at all; and the one precedent for writing from outside the
  dispatcher (`LoggingAccessDeniedHandler.java:55`) hand-rolls `{"error":"forbidden"}` rather than the
  shared `ErrorResponse` shape. Build it once, apply it twice.
  **The filter must not throw.** Its class doc at `:38-43` is explicit that a request without usable
  credentials passes through deliberately, because it may be headed somewhere public — a provider
  webhook, an unsubscribe link opened from an email, the temple list a devotee sees before they have
  an account. The shape that keeps that property is to record the reason as a request attribute where
  the filter currently returns, and replace `HttpStatusEntryPoint` with an entry point that reads the
  attribute and serialises the real `ErrorResponse`. A request that reaches a `permitAll` endpoint
  never invokes the entry point and so emits nothing, which is exactly right.
  Then the frontend half, which ships with it: `auth-context.tsx:112-118` treats *any* non-unreachable
  `/whoami` failure as `no-account`, so a disabled account is today misreported as "you have no
  account here" and sent to the temple picker. Branch on `ApiError.code` — `api.ts:35` says in as many
  words that a screen branching on a status number instead is drifting.
- **the `SESSION_EXPIRED` carve-out — what changed on re-plan:** `SESSION_EXPIRED` (**`KMS-400018`**)
  was in this task and is **now out of its contract.** It is Question 8, unanswered:
  `TokenVerifier.java:12-16` states a deliberate policy that a caller is never told *why* a token
  failed, and `FirebaseTokenVerifier.java:88-95` checks `EXPIRED_ID_TOKEN` and then discards it on
  purpose. Whether to carve "expired" out as the one safe disclosure, or delete the code as dead copy,
  is Rajeev's to settle. So `FirebaseTokenVerifier.java` and `TokenVerifier.java` are **out of the
  path contract entirely**, the bad-token branch at `:97-102` keeps emitting nothing, and the builder
  was told to say so in its proof. **The honest consequence, carried deliberately:** an expired token
  still lands on the frontend's fallback branch and is still reported as `no-account`. D3's complaint
  is therefore two-thirds closed, and the last third is Rajeev's call, not a builder's.
- **contract widened on re-plan.** Adding an auth status obliges both of its consumers:
  `app/page.tsx:26-35` and `components/RequireRole.tsx:29-35` both branch on `status`, and a status
  neither handles leaves the app spinning on `<Loading />` for ever. A disabled person must be told
  what happened, not shown a spinner. Both files are in the contract, and `RequireRole.tsx` is named
  in T-002's forbidden list so the two builders in this wave cannot meet inside it.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/auth/AuthenticationFilter.java`
  - `backend/src/main/java/org/iskcon/kms/auth/SecurityConfiguration.java`
  - one **new** class in `backend/src/main/java/org/iskcon/kms/auth/` for the coded entry point
  - `backend/src/test/java/org/iskcon/kms/auth/AuthenticationFailureIT.java` *(new)*
  - `frontend/lib/auth-context.tsx`
  - `frontend/app/page.tsx`
  - `frontend/components/RequireRole.tsx`
  - `frontend/__tests__/session-failures.test.tsx` *(exists — extend)*
  - `frontend/__tests__/home.test.tsx` *(exists — only if the new status breaks it)*
  - **out of contract, deliberately:** `FirebaseTokenVerifier.java`, `TokenVerifier.java`
- **reservations:** none. Both codes are already in `ErrorCode.java` with their final text —
  `ACCOUNT_DISABLED(400019, 401)` and `NO_ACCOUNT_AT_TEMPLE(400020, 401)`. This task adds no code and
  changes no text.
- **acceptance:**
  - A request from a disabled user returns 401 with a JSON body carrying `KMS-400019` and its existing
    next step, verified by an integration test on the real database, not a unit mock.
  - A verified Firebase user with no membership returns `KMS-400020`.
  - **A public endpoint still works with a bad or absent token** — the property most easily broken by
    this change. `GET /api/v1/temples` and `/api/v1/public/unsubscribe` are `permitAll`
    (`SecurityConfiguration.java:75-80`).
  - The join flow still works: a verified uid with no membership reaching `/api/v1/temples/{id}/join`
    is still authenticated as an unaffiliated visitor (`AuthenticationFilter.java:123,129-134`).
  - The frontend distinguishes the states from the code rather than from the absence of a network
    error, with a test for each; a disabled account renders a message and never sits on `<Loading />`.
  - The proof states plainly that the `SESSION_EXPIRED` half was held for Question 8.
- **proof:** `docs/work/proof/T-003.md`
- **notes from the build:**
  - The mechanism was built as planned — the filter *records* a request attribute and a new
    `AuthenticationFailureEntryPoint` serialises the real `ErrorResponse`, taking the application's
    own `ObjectMapper` bean so a body written outside the dispatcher is byte-for-byte what the
    dispatcher would have produced. Nothing is thrown, so the deliberately-unauthenticated public
    paths are untouched. Attribute absent → today's bodyless 401.
  - **`SESSION_EXPIRED` was held, and defended.** `FirebaseTokenVerifier` and `TokenVerifier` were
    never opened, no attribute is set on the bad-token branch, and **a test now asserts that branch's
    body stays empty** — so a later change cannot quietly start explaining why tokens fail without
    someone noticing. That is better than the instruction asked for.
  - `home.test.tsx` was in the contract but did not need touching; the new status did not break it.
  - **One deviation to sanction.** Both consumers need identical copy, and duplicating user-facing
    text is how two screens come to disagree — so `AccountDisabled` is exported from
    `RequireRole.tsx` and imported by `app/page.tsx`. It works, but
    `import { AccountDisabled } from "@/components/RequireRole"` reads oddly. Moving it to its own
    file is a two-line follow-up, and it needed a path the contract did not grant.
  - Its first backend run was red, 16 of 36, and the cause is worth keeping: the join-flow test writes
    a real audit row, `audit_events` references `users` `ON DELETE RESTRICT`, so a teardown
    `DELETE FROM users` was refused — leaving a tenant behind and cascading a duplicate-slug failure
    into two *other* test classes sharing the database. Fixed by deleting the audit trail first, as
    four other ITs already do. Test fixture only, no product code involved.
- **shipped:** `dd3fb31` — *feat: the 401 says which 401 it is, so a switched-off account is not told it never existed*

---

# Out of band — T-029, shipped alone and ahead of wave 2

Not part of the batch and not in a wave. Rajeev hit it on 2026-09-07 while trying to test wave 1,
which is exactly why it jumped the queue: it was the thing stopping him from testing anything.
One builder, one task, released on its own so that nothing else was riding on it.

### T-029 — "Continue with Google" always shows the account chooser

- **source:** Rajeev, 2026-09-07, reported live while signing out of the super-admin account to sign
  in as kitchen staff. Not from the docket. It is the same wall recorded at the foot of
  `DECISIONS.md` — *"there is no way to sign in as kitchen staff"* — approached from the other side:
  even once those accounts can be bound, binding them means signing in as each address in turn.
- **wave:** none — dispatched alone and immediately, ahead of wave 2, because it blocked UAT.
- **state:** **shipped** *(2026-09-07 — `e9f981e` — released alone; not yet seen working by Rajeev)*
- **what:** Both Google call sites built a bare `new GoogleAuthProvider()`. With no `prompt`
  parameter Google's OAuth endpoint skips the account chooser whenever exactly one Google session is
  live — and signing out of this application clears the *Firebase* session while leaving the
  *Google* one untouched, which it cannot help: that session belongs to `accounts.google.com`. So
  the next press silently returned the previous person. Both sites now build through one exported
  `googleProvider()` setting `{ prompt: "select_account" }`. Not `"consent"`, which would re-ask for
  scopes already granted.
- **paths:**
  - `frontend/lib/auth-context.tsx`
  - `frontend/app/register/page.tsx`
  - `frontend/__tests__/google-account-chooser.test.tsx` *(new)*
- **reservations:** none. No migration, no error code, no permission, no shared file.
- **acceptance:**
  - After signing out, pressing Continue with Google offers the account chooser rather than reusing
    the last session — on the sign-in screen, and on the register screen.
  - `prompt: "consent"` is not used.
  - `npx tsc --noEmit` clean; the whole suite passes.
- **proof:** `docs/work/proof/T-029.md`
- **notes from the build, for whoever reviews it:**
  - **One deviation, raised before the fact rather than after.** The task said to set the parameter
    at both call sites; the builder put both behind one exported `googleProvider()` instead. Same
    behaviour, one place for the explanation, and a third call site added later cannot quietly
    reintroduce the defect. Flatten it to two inline lines if that is preferred.
  - **The tests were mutation-checked.** Removing the `setCustomParameters` call fails both; putting
    it back passes them. Worth stating, because a test asserting only that `signInWithPopup` was
    called would have passed happily throughout the defect — the popup always opened, it just never
    asked anything. The assertion reaches into `signInWithPopup.mock.calls[0][1]`, the provider
    Firebase was actually handed, not merely the last one constructed.
  - The register test drives the real form — temple searched and picked, name, email and phone
    filled, the Google tab selected, "Create my account" pressed — so it covers the route a person
    takes rather than a direct call to the handler.
  - **Not smoke-tested by hand, and this one cannot be proven any other way.** The acceptance
    criterion lives inside Google's OAuth endpoint. The tests prove the parameter reaches the
    provider; they cannot prove Google then draws a chooser. Confirmed at release only as far as
    evidence goes: the web revision's digest changed and `select_account` is in the served bundle.
    It wants one human pass — sign in on staging, sign out, press Continue with Google.
  - Worth pressing the **register** screen too. It has been promising "You'll be asked to choose your
    Google account when you finish" while that was untrue.
- **shipped:** `e9f981e` — *fix: signing out and pressing Continue with Google asks who you are again*

---

# Wave 2 — four screens over finished backends, and one silent data loss

Four of the five are a screen over an endpoint that already exists and is already tested. No
migrations. The only shared cost is `frontend/lib/api.ts`, which the work manager stubs in one pass
beforehand — and **T-028 does not even need that**: it is a one-line backend correction to a defect
on a screen that shipped months ago, added here on 2026-09-07 because it is losing data now and its
two files are touched by nothing else until wave 4.

### T-004 — A screen that manages festival occasions

- **source:** docket **S3** (INTAKE S3) · `WORK_QUEUE` item 3.3 · `TRACEABILITY` G3.
- **wave:** 2
- **state:** **shipped** *(2026-09-07 — `67d5f05` — released in wave 2; not yet seen working by Rajeev)*
- **what:** A temple cannot add its own occasion — "Temple Anniversary" is the story's own example. The
  backend is finished: create, update and delete all sit behind `MANAGE_TEMPLE_SETTINGS`, and the app
  calls only the list, from an autocomplete in the meal composer. Build the management screen at
  `/settings/occasions`: list the temple's occasions, add one, rename one, delete one. `WORK_QUEUE`
  records the design question as already settled — Rajeev asked whether an Event could carry this
  instead and accepted that it cannot, *"an Event is a thing cooked on one date, an occasion is a
  recurring entry that tells the planner what kind of day it is"* — so build the occasion, not an
  event. Deleting one that the planner has already resolved against must be handled visibly rather
  than left to a 500.
- **paths:**
  - `frontend/app/settings/occasions/page.tsx` *(new)*
  - `frontend/__tests__/occasions.test.tsx` *(new)*
- **reservations:**
  - `frontend/lib/api.ts` — three wrappers to stub, matching the file's existing style:
    - `createOccasion: (input: OccasionInput, token?: string) => request<{ id: string }>("/api/v1/occasions", { method: "POST", body: JSON.stringify(input), token })`
    - `updateOccasion: (id: string, input: OccasionInput, token?: string) => request<void>(\`/api/v1/occasions/${id}\`, { method: "PUT", body: JSON.stringify(input), token })`
    - `deleteOccasion: (id: string, token?: string) => request<void>(\`/api/v1/occasions/${id}\`, { method: "DELETE", token })`
  - `frontend/lib/nav.ts` — one row: `{ href: "/settings/occasions", label: "Festival occasions", roles: [ADMIN] }`, matching `MANAGE_TEMPLE_SETTINGS`.
- **acceptance:** create, rename and delete each round-trip and are reflected in the list without a
  reload; the screen is refused for every role but Temple Admin; deleting an occasion in use fails
  readably. `tsc` clean, new vitest passes.
- **proof:** `docs/work/proof/T-004.md`
- **shipped:** `67d5f05` — *feat: a temple can curate its own festival occasions*

### T-006 — A cook can see their own schedule

- **source:** docket **S4** (INTAKE S4a) · `WORK_QUEUE` item 3.4 · `TRACEABILITY` G6.
- **wave:** 2
- **state:** **shipped** *(2026-09-07 — `bfca0ac` — released in wave 2; not yet seen working by Rajeev)*
- **what:** `GET /api/v1/staff/schedule/me` has existed behind `VIEW_OWN_SHIFTS` for some time, kitchen
  staff and managers both hold that permission, and the client wrapper `myStaffSchedule` has never had
  a caller — so G6's recorded cause was wrong in both halves and only the screen is missing. Build
  `/my-schedule`: the signed-in person's own rostered shifts, in the temple's own clock. **The hole is
  wider than kitchen staff** — a Temple Admin holds `VIEW_OWN_SHIFTS` too and has no route either — so
  the nav row carries all three roles. Empty state must not point anywhere the reader is refused; that
  is the exact defect T-002 is fixing four times over.
- **paths:**
  - `frontend/app/my-schedule/page.tsx` *(new)*
  - `frontend/__tests__/my-schedule.test.tsx` *(new)*
- **reservations:**
  - `frontend/lib/nav.ts` — `{ href: "/my-schedule", label: "My schedule", roles: [ADMIN, MANAGER, KITCHEN] }`.
  - `frontend/lib/api.ts` — none; `myStaffSchedule` already exists at `:4456`.
- **acceptance:** renders the signed-in person's shifts for each of the three roles; dates in the
  temple's timezone; empty state offers no refused destination.
- **proof:** `docs/work/proof/T-006.md`
- **shipped:** `bfca0ac` — *feat: a cook, a manager and an admin can each see their own rostered days*

### T-009 — Editing an equipment record, and confirming a scrapping

- **source:** docket **M7** (INTAKE M7).
- **wave:** 2
- **state:** **shipped** *(2026-09-07 — `723696c` — released in wave 2; not yet seen working by Rajeev)*
- **what:** A wrong serial number or warranty date is currently permanent — but only because the client
  never wrapped the `PUT` that already exists and accepts exactly the descriptive fields at issue
  (name, storage location, acquisition date, source, notes, serial number, purchase cost, warranty
  expiry; it deliberately excludes condition and service interval). Add `/equipment/[id]/edit` over it.
  Separately, `SCRAPPED` currently sits in a plain dropdown with no confirmation next to three
  reversible values, and the service refuses every later condition change — so a misclick is
  unrecoverable. Add a confirmation step that names the consequence before the change is sent.
  **Keep SCRAPPED terminal** (`EquipmentService`'s own doc calls it terminal by design; Question 13
  asks Rajeev to confirm) — this task makes it harder to do by accident, it does not make it
  reversible.
- **paths:**
  - `frontend/app/equipment/[id]/edit/page.tsx` *(new)*
  - `frontend/app/equipment/[id]/page.tsx`
  - `frontend/__tests__/equipment-edit.test.tsx` *(new)*
- **reservations:**
  - `frontend/lib/api.ts`:
    - `updateEquipment: (id: string, input: UpdateEquipmentInput, token?: string) => request<void>(\`/api/v1/equipment/${id}\`, { method: "PUT", body: JSON.stringify(input), token })`
- **acceptance:** a serial number corrected on the edit screen persists and shows on the detail page;
  choosing `SCRAPPED` requires an explicit confirmation naming that it cannot be undone; no other
  condition change gains a confirmation.
- **proof:** `docs/work/proof/T-009.md`
- **shipped:** `723696c` — *feat: an equipment record can be corrected, and scrapping asks first*

### T-017 — A donor can see and stop a recurring gift

- **source:** docket **B9** (INTAKE B9).
- **wave:** 2
- **state:** **blocked** *(2026-09-07 — dispatched in wave 2, stopped by the builder before writing any
  product code. Both contract files are untouched, so re-dispatch costs nothing.)*
- **blocker — needs Rajeev:** acceptance asks for each plan's **next charge date**, and that value does
  not exist anywhere in the stack. `recurring_plans` (V42/V43) has no such column; `RecurringPlanView`
  is `id, frequency, amountInr, status, subscriptionId, shortUrl, createdAt` on both sides; a tree-wide
  grep for `next_charge|nextCharge|charge_at|chargeAt|current_end|currentEnd` across `backend/src`,
  `frontend/lib` and `frontend/app` returns zero matches. Razorpay holds the real schedule and we never
  read or store it. The builder could have derived `createdAt + frequency` — it would typecheck and its
  own test would pass — and refused to, because it is a fabricated date about a live mandate that goes
  silently wrong on a failed cycle, a HALTED plan, or provider anniversary drift. Storing it properly
  needs a migration plus backend and `api.ts` changes, which is a different task in a later wave.
  **Two ways forward, Rajeev's call:** re-scope this row to list + cancel + empty state showing amount,
  frequency, status and *started on* (buildable inside the existing contract, today), or hold T-017
  until the `next_charge_at` webhook work is scheduled.
- **also found, not blocking:** the server does not refuse a second cancel — `requireOwned` has no
  status filter and the UPDATE is idempotent, so a repeat cancel reaches Razorpay and comes back as
  `PAYMENT_GATEWAY_ERROR`, which is readable but names the gateway rather than saying the plan has
  already stopped. `StubPaymentGateway.cancelSubscription` is a no-op returning 204. The readable
  refusal has to come from the screen; that is buildable inside the contract.
- **what:** The giving page can start a monthly mandate. The list and the cancel exist on both sides —
  four controller endpoints and all three client wrappers — and **no screen calls any of them**, so the
  product can create a recurring charge and cannot stop it. Build the donor-facing screen: the plans
  the signed-in donor holds, each plan's history, and a cancel with a confirmation. This is the one
  item in the batch where the gap is not an inconvenience but a person's money.
- **paths:**
  - `frontend/app/donate/recurring/page.tsx` *(new)*
  - `frontend/__tests__/recurring-giving.test.tsx` *(new)*
- **reservations:** none — `startRecurringPlan`, `myRecurringPlans` and `cancelRecurringPlan` all exist
  at `frontend/lib/api.ts:4816-4832`. Reached from `/donate`, so no new nav row.
- **acceptance:** an active plan is listed with its amount and next charge date; cancelling it removes
  it from the list and a second cancel is refused readably; a donor with no plans sees an empty state,
  not an error.
- **proof:** `docs/work/proof/T-017.md` *(a stop report, not evidence of a build)*
- **shipped:** —

### T-028 — Editing a shopping-list line stops nulling its vendor

- **source:** **not a docket item.** Found while re-planning on 2026-09-07 and recorded in this file's
  own *"For Rajeev — found while re-planning"* section, item 3, which this row replaces. It is on no
  other list: not `OUTSTANDING_BUILD_LIST`, not `WORK_QUEUE`, not `BACKLOG`. Added at Rajeev's
  instruction 2026-09-07. **Closing it therefore closes nothing elsewhere** — the only list it has to
  leave is this one.
- **wave:** 2
- **state:** **shipped** *(2026-09-07 — `a41d094` — released in wave 2; not yet seen working by Rajeev)*
- **what:** A live data-loss defect on a screen that has already shipped. `updateLine`
  (`ShoppingListService.java:78-84`) writes `suggested_vendor_id = ?` **unconditionally**, while the
  `suggested_qty` immediately beside it on the same line is `COALESCE(?, suggested_qty)`. Neither
  caller on `frontend/app/shopping-list/page.tsx` sends the field — `setIncluded` (`:47-52`) and
  `setQty` (`:54-59`) both post `{ suggestedQty, included }` only — so **every tick of an include box
  and every quantity edit silently nulls that line's suggested vendor**. It is silent because the
  screen shows a blank vendor cell either way and returns `204`. What it costs is the next step:
  `generate()` (`:61-73`) selects only lines that have a vendor, and `ShoppingListService.generate`
  (`:61-73`) requires one, so the line quietly stops being orderable and the count under the button
  drops with no explanation. `included = ?` is unconditional too but is harmless — both callers always
  send it, and an omission would fail loudly on `NOT NULL` rather than destroy a value.
- **fix:** `suggested_vendor_id = COALESCE(?, suggested_vendor_id)`, matching the column beside it.
  **One line, in the service, not in the screen.** Two reasons for putting it there rather than making
  the frontend send the id back: the endpoint is `PATCH` and a partial write that destroys unmentioned
  fields is wrong for every caller, not just this screen; and `frontend/app/shopping-list/page.tsx` is
  T-027's in wave 5, so a backend-only fix keeps this task off a contended file three waves early.
  Nothing loses a capability — no screen offers clearing a vendor, and regeneration writes vendors
  through its own SQL, never through `updateLine`.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/shoppinglist/ShoppingListService.java` *(the `updateLine` UPDATE only)*
  - `backend/src/test/java/org/iskcon/kms/shoppinglist/ShoppingListIT.java`
- **forbidden:** `frontend/app/shopping-list/page.tsx`, `ShoppingListController.java` and
  `UpdateShoppingListLineRequest.java` — the DTO already carries `suggestedVendorId` as a nullable
  `UUID` and `api.ts` already declares it optional, so **no signature changes anywhere**. The
  controller and the screen are T-027's in wave 5.
- **reservations:** **none.** No migration, no error code, no permission, no `nav.ts` row, and no
  `api.ts` change — the wrapper at `api.ts:4057-4066` already types `suggestedVendorId` as optional.
  This is the only task in the batch that needs nothing from the work manager.
- **acceptance:** a new `ShoppingListIT` test that sets a vendor on a line, PATCHes it with quantity
  and `included` only, and asserts the vendor **survives**; it must fail against the current code
  before the change and pass after — the proof file carries both runs. `editsSurviveRegeneration`
  (`:143`) and the other three existing tests still pass.
- **proof:** `docs/work/proof/T-028.md`
- **shipped:** `a41d094` — *fix: ticking an include box no longer wipes that shopping-list line's vendor*

---


## Wave 2, as it actually ran — 2026-09-07

Five builders concurrently. **No builder touched a file outside its contract** — verified against
`git status` after each returned — and **no contract had to be widened**, unlike wave 1. The
reservations (`api.ts`'s four wrappers, `nav.ts`'s two rows) were written in one pass before dispatch
and no builder opened either file.

**Four proven, one blocked before it wrote anything.** T-004, T-006, T-009 and T-028 each left a
proof file carrying real command output; T-017 stopped on a blocker and left a stop report.

**Checked on the merged tree afterwards**, because green inside a wave is not green on the merged
tree — the lesson wave 1 recorded under T-001. With all four builders' work in one checkout:
`npx tsc --noEmit` clean, `npm test` **943 tests in 90 files, all passing**, and `npm run build`
exporting all four new/changed routes (`ƒ /equipment/[id]/edit`, `○ /my-schedule`,
`○ /settings/occasions`, `ƒ /equipment/[id]`). The backend half is T-028's alone and was proven in
its own run.

**One deviation from this file's reservation text, made deliberately by the work manager.** T-004's
row specified a single `OccasionInput` shared by create and update. The backend does not agree:
`CreateOccasionRequest` carries `type` and `UpdateOccasionRequest` deliberately does not. A shared
type would have posted a field the PUT endpoint does not declare, so `api.ts` got
**`CreateOccasionInput` and `UpdateOccasionInput`** instead, and T-004's builder was given both names.
Everything else was written verbatim.

**Two acceptance criteria turned out to rest on wrong premises**, both found by the builder reading
the backend rather than guessing, and both recorded in their rows:
- T-004's *"deleting an occasion in use fails readably"* — it cannot fail. Meal plans keep the
  occasion name as text (E4-S4), so removing an occasion orphans nothing. Met by stating the
  consequence before the press rather than by inventing a server refusal.
- T-017's *"next charge date"* — the value exists nowhere in the stack. See its row.

**Released 2026-09-07** in five commits, one per task, so each is readable on its own. The work
manager's `api.ts` and `nav.ts` reservations were split back out along task lines and committed with
the task that needed them, rather than as a sixth "reservations" commit that would belong to nobody.
The clean-tree gate — `git archive HEAD` into an empty directory, `git init && git add -A`, then the
full backend and frontend suites — passed there before anything was pushed.

## Out of band — 2026-09-07

Work ruled into a wave and missed by the work manager, built on its own rather than waiting. It is
listed here and not under a wave because it did not run with one.

### T-030 — Giving narrows to volunteers, at both ends

- **id:** T-030
- **source:** `docs/work/DECISIONS.md` **D-8**, ruled by Rajeev on 2026-09-07: *"Admins shouldn't be
  asked for money by their own admin app… Same rule applies for Temple staff too. They are already
  serving which is donation enough."* D-8's own text says **"Scheduled into wave 2 rather than
  shipped alone"**, and **wave 2 was dispatched without it — the work manager's miss, not a
  builder's.** Built out of band because the tree currently carries the *opposite* behaviour and
  **wave 1 shipped that opposite to staging**, so it cannot wait behind the release of wave 2.
- **what:** `/donate` admits `VOLUNTEER` alone, at both ends. The page guard in
  `frontend/app/donate/page.tsx:13` goes from
  `roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF", "VOLUNTEER"]}` to `["VOLUNTEER"]`,
  and the `/donate` row in `frontend/lib/nav.ts` goes from `[ADMIN, VOLUNTEER, MANAGER, KITCHEN]` to
  `[VOLUNTEER]` — restoring `nav.ts`'s own rule that a row carries exactly the roles its destination
  admits. The tests that assert the current shape are **updated to the new rule, not deleted**.
- **reverses a wave-1 change.** Wave 1 closed the same docket finding from the other end: it *widened*
  the `nav.ts` `/donate` row to add `ADMIN`, without asking which end should move. D-8 says the
  reversal is the point — *when a task can be closed from either end, which end is a product
  decision and goes to Rajeev.*
- **paths:**
  - `frontend/app/donate/page.tsx`
  - `frontend/__tests__/nav.test.ts`
  - `frontend/__tests__/donate-signed-in.test.tsx`
  - `frontend/__tests__/donate-checkout.test.tsx`
  - `docs/work/proof/T-030.md` *(new)*
- **reservations:** `frontend/lib/nav.ts` — **the work manager made this edit itself**, before
  dispatch, preserving wave 2's two uncommitted new rows (`/my-schedule`, `/settings/occasions`).
  The builder was forbidden the file.
- **explicitly not in scope:** the backend stays `isAuthenticated()` on `POST /donations/one-time`
  and `/donations/wishlist/{itemId}`. D-8 records that inconsistency as deliberate — nobody is
  harmed by a staff member who gives through the API, and minting a permission to stop them is
  ceremony. **No migration, no error code, no permission.**
- **wave:** none — dispatched alone, between wave 2's return and its release.
- **state:** **shipped** *(2026-09-07 — `99b91d6` — released alongside wave 2; not yet seen working by Rajeev)*
- **proof:** `docs/work/proof/T-030.md`
- **what the builder found:** less was broken than expected. `donate-checkout.test.tsx` renders
  `DonatePage` directly and never the route, so it never passes through `RequireRole` and needed no
  change; and `nav.test.ts`'s gap was a **missing** negative assertion rather than a wrong positive
  one — no case asserted `/donate` was offered to staff, so nothing had to be flipped, only added.
  The one structural change was in `donate-signed-in.test.tsx`, whose `useAuth` mock was a fixed
  volunteer object and became a mutable ref so a test can render the route as somebody else.
- **shipped:** `99b91d6` — *fix: the giving page is for people who do not work here*

---


# Wave 3 — the temple's own record

### T-005 — A screen that manages meal kinds

- **source:** docket **A3** (INTAKE A3).
- **wave:** 3 — held out of wave 2 deliberately; see the wave table.
- **state:** queued
- **what:** A temple that starts serving an evening meal, or wants "Raj Bhog" rather than "Lunch",
  cannot say so. The backend is full CRUD behind `MANAGE_TEMPLE_SETTINGS` and three of the four client
  wrappers already exist, uncalled. Build `/settings/meal-kinds`: list, add, rename, delete. The
  docket's stated worry — *"every planner screen is built around the seeded list"* — was checked and is
  unfounded: there is not one hardcoded `BREAKFAST`/`LUNCH`/`DINNER` string literal anywhere in
  `frontend/app`, `frontend/lib` or `frontend/components`, so renaming a seeded kind is safe. Deleting
  a kind that planned meals already reference must refuse readably rather than orphan them.
- **paths:**
  - `frontend/app/settings/meal-kinds/page.tsx` *(new)*
  - `frontend/__tests__/meal-kinds.test.tsx` *(new)*
- **reservations:**
  - `frontend/lib/api.ts` — one wrapper (the other three exist at `:3573-3582`):
    - `deleteMealKind: (id: string, token?: string) => request<void>(\`/api/v1/meal-kinds/${id}\`, { method: "DELETE", token })`
  - `frontend/lib/nav.ts` — `{ href: "/settings/meal-kinds", label: "Meal kinds", roles: [ADMIN] }`.
- **acceptance:** a renamed kind appears renamed on the planner without a code change; delete refuses
  readably when the kind is in use; Temple Admin only.
- **proof:** —
- **shipped:** —

### T-008 — A temple's profile can be corrected, and 80G approval recorded

- **source:** docket **A1 + A2** (INTAKE A1, A2). The docket's second priority: *"Testers provision
  temples all day."*
- **wave:** 3
- **state:** queued
- **what:** `TenantController` has POST, GET, export and DELETE and **no PUT or PATCH at all**, so name,
  address, coordinates, currency, timezone and 80G status are set once at provisioning and a temple
  provisioned wrongly can only be fixed by deleting it. Add the update endpoint and the screen over it.
  **Two things a builder must not miss.** First, `is_80g_approved` is written only by the provisioning
  insert and the sole `UPDATE tenants` statement anywhere in the backend touches `locale` — so the 80G
  field is part of this endpoint, not a separate one, and receipts stay wrong until it exists. Second,
  **timezone is not just a column**: `calendar_days` is precomputed per tenant from it, so changing it
  must re-trigger the calendar precompute for that tenant or the temple keeps stale tithi and Ekadashi
  rows and every "today" in the product silently disagrees with the panchanga. `slug` is
  `updatable=false` and stays that way. Who may call this is **Question 7** — build it behind
  `MANAGE_TENANTS` unless Rajeev says the temple admin owns part of it.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/tenant/TenantController.java`
  - `backend/src/main/java/org/iskcon/kms/tenant/UpdateTenantRequest.java` *(new)*
  - `backend/src/main/java/org/iskcon/kms/tenant/TenantUpdateService.java` *(new)*
  - `frontend/app/tenants/[id]/edit/page.tsx` *(new)*
  - `frontend/app/tenants/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/tenant/TenantUpdateIT.java` *(new)*
  - `frontend/__tests__/tenant-edit.test.tsx` *(new)*
- **reservations:**
  - migration: **none** — every column already exists (`V1__tenancy_foundation.sql:36` for the 80G flag).
  - `frontend/lib/api.ts`:
    - `updateTenant: (id: string, input: UpdateTenantInput, token?: string) => request<void>(\`/api/v1/tenants/${id}\`, { method: "PATCH", body: JSON.stringify(input), token })`
  - permissions: none new — `MANAGE_TENANTS`, pending Question 7.
- **acceptance:**
  - An integration test changes a tenant's timezone and asserts the calendar precompute is re-enqueued
    for that tenant — not merely that the column changed.
  - Recording 80G approval after provisioning makes the flag true and is reflected wherever receipts
    read it.
  - `slug` is rejected as unchangeable.
  - The change is audited like every other tenant-level act.
- **proof:** —
- **shipped:** —

### T-018 — Changing a person's role

- **source:** docket **B10** (INTAKE B10).
- **wave:** 3
- **state:** queued
- **what:** `PATCH /api/v1/users/{id}/role` exists behind `MANAGE_USERS` with three real guards — no
  self-change, no promotion to super admin, cross-tenant targets invisible under RLS — and each refusal
  is separately audited. Its client wrapper has zero callers. **The docket names the wrong screen.**
  `/users` is the devotee register by deliberate design, listing volunteers only, and its own comment
  says *"a devotee holds one role, by definition"* — putting a role control there would contradict that.
  The right home is the staff record, where `systemAccess` is already edited for a currently-employed
  person. Build the promotion and demotion path there: promoting a volunteer to kitchen staff, or
  demoting a manager, without going through hiring them again. Surface each of the three guard
  refusals as readable text.
- **paths:**
  - `frontend/app/staff/[id]/edit/page.tsx`
  - `frontend/components/staff/StaffForm.tsx`
  - `frontend/__tests__/role-change.test.tsx` *(new)*
- **reservations:** none — `changeUserRole` exists at `frontend/lib/api.ts:3204`.
- **acceptance:** a role change round-trips and the person's menu changes on their next sign-in;
  attempting to change one's own role is refused readably; the audit entry exists for both the success
  and the refusal.
- **proof:** —
- **shipped:** —

### T-022 — Tests for the five screens that have none

- **source:** docket **P9** (INTAKE P9) — the only one of the nine "never proven" items that is
  actually a build task.
- **wave:** 3
- **state:** queued
- **what:** The docket names `app/unsubscribe/` as the only screen in the application with no test, and
  makes the point that it is also the only one reached from an email by someone who is not signed in —
  which is exactly why it should not be the untested one. The superlative is wrong: `app/library`,
  `app/choose-temple`, `app/register` and `app/c/[token]` are untested too (the first two appear in
  tests only as an href or a redirect target, never rendered). Write a test per screen. These are
  characterisation tests over shipped behaviour — **if one of them finds a defect, report it, do not
  fix it**; fixing it is a different task in a different contract.
- **paths:**
  - `frontend/__tests__/unsubscribe.test.tsx` *(new)*
  - `frontend/__tests__/library.test.tsx` *(new)*
  - `frontend/__tests__/choose-temple.test.tsx` *(new)*
  - `frontend/__tests__/register.test.tsx` *(new)*
  - `frontend/__tests__/claim-link.test.tsx` *(new)*
- **reservations:** none.
- **acceptance:** each screen renders under test, including its signed-out path where it has one; five
  new files pass; no product file is modified — a proof that touches one has broken its contract.
- **proof:** —
- **shipped:** —

---

# Wave 4 — procurement, the three foundations

**New on the re-plan.** `DECISIONS.md` **D-1** put non-food procurement in scope on 2026-09-07 and
split it along a line that matters to the code: *consumable supplies* behave exactly as ingredients
already do and want a flag; *one-off durables* want the opposite and are a nullable PO line. **D-2**
settled that a manual PO creates a real vendor row, which forces `vendors.phone` off `NOT NULL`.
Those are the three tasks here. The two screens that sit on top of them are wave 5, because they
read what these three add.

**Everything below was verified against the code on 2026-09-07**, and the verification moved the
sizing twice. Both corrections are recorded in the tasks: there is **no `Ingredient` JPA entity**
(the module is raw `JdbcTemplate` throughout), and a nullable `ingredient_id` reaches **five**
consumers outside the purchase-order package, one of which fails silently rather than loudly.

**Read this before dispatching the wave.** T-024 and T-025 are both inside
`backend/.../purchaseorder/`, on different files. Their contracts name files and **no `**` glob is
permitted in either.** A builder that widens to the package will meet the other one.

### T-023 — Supplies are a flag on the catalogue, not a second catalogue

- **source:** `DECISIONS.md` **D-1**, first half · docket **B2** (INTAKE B2), now unblocked.
- **wave:** 4
- **state:** queued
- **what:** LPG, disposable plates, cleaning and dishwashing supplies, hand soap and first-aid kits
  are bought from a vendor, received, stored, used up and wanted back when they run low — which is
  the ingredient lifecycle exactly. So they are ingredients with a flag, not a parallel table. D-1
  rejected a `supply_items` table with its own stock ledger outright: it duplicates the entire
  inventory chain to express a difference that is one boolean. Add the column, expose it on the
  catalogue screen and its form, and make the **recipe** picker exclude supplies so a leaf plate can
  never reach a recipe.
  **Three verified facts that change how this is built.** First, there is **no `Ingredient` JPA
  entity and no `IngredientRepository`** — the only two `@Entity` classes in the backend are
  `Tenant` and `User`, and the ingredient module is raw `JdbcTemplate`. The row is two records,
  `IngredientView.java:8-16` and `IngredientSummary.java:6-11`, with mappers at
  `IngredientService.java:301-309` and `:311-316`. Second, **no ingredient flag has ever been used as
  a query filter** — `list()` (`:49-55`) and `search()` (`:58-75`) have no `WHERE` on any flag, so
  the filter argument is net-new in both. The pattern to copy is not on ingredients at all, it is
  **vendors**: `vendors.active` with `?includeInactive=` at `VendorController.java:33-36` and
  `VendorService.list(boolean)` at `:253-256`. Third, the frontend **never calls
  `/api/v1/ingredients/search`** — its only caller anywhere is an integration test. All seven pickers
  load the whole catalogue through `api.listIngredients` and render a plain `<select>`.
  **Only the recipe picker filters.** Supplies must keep appearing in the inventory, ingredient-request,
  PO-line, donation and vendor-supplies pickers, because supplies are genuinely bought, received,
  stored and issued. Filtering them everywhere would break the very thing D-1 asked for. The
  server-side refusal belongs in `RecipeService` as well as the picker, because a picker is not a
  guard.
  The flag's shape should follow `is_sattvic_prohibited` (`V10:30`), which is the fullest existing
  pattern — but **not** its separate-permission split: prohibiting an ingredient is religious policy,
  and saying a thing is a mop is not. `MANAGE_RECIPES` is right.
- **paths:**
  - `backend/src/main/resources/db/migration/V95__supplies_are_flagged_ingredients.sql` *(new)*
  - `backend/src/main/java/org/iskcon/kms/ingredient/IngredientController.java`
  - `backend/src/main/java/org/iskcon/kms/ingredient/IngredientService.java`
  - `backend/src/main/java/org/iskcon/kms/ingredient/IngredientView.java`
  - `backend/src/main/java/org/iskcon/kms/ingredient/IngredientSummary.java`
  - `backend/src/main/java/org/iskcon/kms/ingredient/CreateIngredientRequest.java`
  - `backend/src/main/java/org/iskcon/kms/ingredient/UpdateIngredientRequest.java`
  - `backend/src/main/java/org/iskcon/kms/recipe/RecipeService.java` *(the server-side refusal only)*
  - `frontend/app/ingredients/page.tsx`
  - `frontend/components/IngredientForm.tsx`
  - `frontend/components/RecipeForm.tsx`
  - `backend/src/test/java/org/iskcon/kms/ingredient/SupplyIngredientIT.java` *(new)*
  - `frontend/__tests__/supplies.test.tsx` *(new)*
- **reservations:**
  - migration: **`V98`**. `ingredients` is tenant-owned with `enable_tenant_rls('ingredients')` at
    `V10:54`, so a backfill runs per tenant and never across all rows.
  - error code: `NOT_A_FOOD_INGREDIENT` **`KMS-400124`** (409) — *"That's a supply, not something you
    can cook with."* / *"Choose a food ingredient, or add this one to the catalogue as food."*
  - `frontend/lib/api.ts`: the flag added to `IngredientView` (`:557-565`), `CreateIngredientInput`
    (`:567-573`) and `UpdateIngredientInput` (`:575+`), and an optional filter argument on
    `listIngredients` (`:3344-3345`). **Note while in there:** `IngredientView` declares
    `sattvicProhibited` but omits `ekadashiProhibited`, which the backend does return. Not this
    task's to fix; recorded so it is not mistaken for something this task broke.
  - permissions: none new — `MANAGE_RECIPES`.
- **acceptance:**
  - A supply can be created, appears on the catalogue screen marked as a supply, and can be put into
    inventory and onto a purchase order exactly as food can.
  - The recipe ingredient picker does not offer it, **and** the API refuses it with `KMS-400124` if it
    is posted anyway — a test asserts the second, not only the first.
  - Existing ingredients are all food after the migration; an integration test asserts the backfill
    ran per tenant under RLS.
  - `./gradlew test` green; `tsc` clean; new vitest passes.
- **proof:** —
- **shipped:** —

### T-024 — A purchase order line can name something that is not in the catalogue

- **source:** `DECISIONS.md` **D-1**, second half · docket **B2** (INTAKE B2).
- **wave:** 4
- **state:** queued
- **what:** Four plastic stools from a furniture store and two extension cords from an electrical one
  want the opposite of a catalogue entry: nothing invented in `ingredients`, and nothing landing in
  stock. So `purchase_order_lines.ingredient_id` (`V26__purchase_orders.sql:57`, `NOT NULL` today)
  becomes nullable with a `description` beside it and a check that **exactly one** of the two is
  present.
  **The column is the small half. INTAKE B2 called this "not a one-wave task" and it was right about
  the reach, though not about the shape.** A null `ingredient_id` reaches five consumers outside the
  purchase-order package, and they fail in different ways — one of them silently, which is the
  dangerous one:
  1. `PurchaseOrderService.java:69-75` — an **INNER** `JOIN ingredients`. A description-only line
     would **vanish from the PO detail screen without any error at all.** This is the single most
     dangerous site in the task. It becomes a `LEFT JOIN`, ordering on
     `COALESCE(i.name, l.description)`.
  2. `PurchaseOrderService.java:298` — `ingredientUnits.requireSameFamily(l.ingredientId(), …)`;
     `IngredientUnits.find()` (`:99-106`) throws `RESOURCE_NOT_FOUND` on a null id. Must be skipped,
     not made lenient.
  3. `ShoppingListService.java:220-241` — groups outstanding PO quantity **by `ingredient_id`** into a
     map. A null key collapses every described line into one bogus bucket. Needs an explicit
     `WHERE pol.ingredient_id IS NOT NULL`.
  4. `GivingPageController.java:171-181` — `spendShares()` inner-joins ingredients and groups by
     category, so described spend silently disappears from the public "where the money went"
     breakdown. Decide visibly: either an "Other" bucket or an explicit exclusion, not an accident.
  5. `DocumentGenerationService.java:253` — `names.add(l.ingredientName())`, and a null name NPEs in
     the glossary lookup at `:311`. The template itself is safe (`PurchaseOrderSheetTemplate.esc()`
     at `:199-204` renders null as `""`); the danger is upstream.
  **The governing rule, and it is what keeps this tractable: a described line is orderable and
  payable, but never receivable into stock.** That is D-1's own intent — "nothing landing in stock" —
  and it is also what the schema already insists on: `goods_receipt_lines.ingredient_id` is `NOT NULL`
  at `V27__goods_receipts.sql:48` and `stock_movements.ingredient_id` is `NOT NULL` too. **Do not
  relax either.** Receiving a PO that contains a described line must skip that line visibly and say
  so, and `ReceivingService`'s five ingredient sites (`:120`, `:189`, `:224`, `:239-243`, `:327`) each
  need that guard — including the `vendor_supplies` price write-back at `:239-243`, whose
  `ingredient_id` is `NOT NULL` at `V24:49` and which must simply be skipped rather than made
  nullable.
  Unit stays `NOT NULL` with its `CHECK (unit IN ('KG','GM','L','ML','PIECES'))`
  (`V74__base_quantity_function.sql:83-84`), so a described line still names a unit — `PIECES` for
  four stools. That is fine and needs no change.
  **One frontend trap:** `frontend/app/orders/[id]/page.tsx:376` keys the line table on
  `key={l.ingredientId}`, which collides the moment two lines have a null ingredient. It becomes the
  line's own id.
- **paths:**
  - `backend/src/main/resources/db/migration/V96__a_purchase_line_need_not_be_an_ingredient.sql` *(new)*
  - `backend/src/main/java/org/iskcon/kms/purchaseorder/PoLineInput.java`
  - `backend/src/main/java/org/iskcon/kms/purchaseorder/PurchaseOrderLineView.java`
  - `backend/src/main/java/org/iskcon/kms/purchaseorder/PurchaseOrderService.java`
  - `backend/src/main/java/org/iskcon/kms/receiving/ReceivingService.java`
  - `backend/src/main/java/org/iskcon/kms/shoppinglist/ShoppingListService.java` *(the `IS NOT NULL` guard only)*
  - `backend/src/main/java/org/iskcon/kms/donation/GivingPageController.java` *(`spendShares` only)*
  - `backend/src/main/java/org/iskcon/kms/document/DocumentGenerationService.java`
  - `frontend/app/orders/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/purchaseorder/DescribedPurchaseLineIT.java` *(new)*
  - `frontend/__tests__/described-po-line.test.tsx` *(new)*
  - **no `**` glob.** `PurchaseOrderDeliveryService.java` is T-025's file this wave and is forbidden
    here; `receiving/` is otherwise T-013's in wave 8.
- **reservations:**
  - migration: **`V96`** — nullable `ingredient_id`, a `description` column, and a check that exactly
    one is present. `purchase_order_lines` is tenant-owned (`enable_tenant_rls`, `V26:67`).
  - error codes:
    - `PURCHASE_LINE_NEEDS_A_SUBJECT` **`KMS-400125`** (400) — *"Each line needs either an ingredient
      or a description, not both and not neither."* / *"Pick an ingredient, or describe what you're
      buying."*
    - `CANNOT_RECEIVE_A_DESCRIBED_LINE` **`KMS-400126`** (409) — *"A described line can't be received
      into stock."* / *"Record it as delivered on the order; it isn't something the store tracks."*
  - `frontend/lib/api.ts`: `PoLineInput` (`:1733-1738`) and `PurchaseOrderLineView` (`:1711-1718`) —
    `ingredientId` and `ingredientName` become optional, `description` is added.
  - permissions: none new — `MANAGE_PURCHASE_ORDERS`.
- **acceptance:**
  - A PO carrying one ingredient line and one described line saves, **and both lines appear on the
    detail screen** — the inner-join regression has its own named test.
  - Posting a line with both an ingredient and a description, or with neither, returns `KMS-400125`.
  - The generated PO sheet renders the described line without NPEing in the glossary path.
  - Receiving the ingredient line works; the described line is skipped visibly and returns
    `KMS-400126` if a receipt tries to take it into stock. **No stock movement and no
    `vendor_supplies` row is written for it** — asserted, not assumed.
  - Shopping-list outstanding quantities are unchanged by the presence of a described line.
- **proof:** —
- **shipped:** —

### T-025 — A vendor you walk into has no WhatsApp number

- **source:** `DECISIONS.md` **D-2** (its substance stands after D-7 superseded the interaction).
- **wave:** 4
- **state:** queued
- **what:** D-2 rejected free-text vendors on a PO, because `purchase_orders.vendor_id` is `NOT NULL`
  and invoices, payments, receiving and vendor spend all hang off it — a PO carrying only a store name
  silently loses the ability to be invoiced or paid, and "Reliance Fresh" typed three ways becomes
  three stores that never merge. The real obstacle to creating a vendor at the moment of need is
  `vendors.phone NOT NULL` with an E.164 check (`V24__vendors.sql:18`, constraint
  `vendors_phone_e164` at `:34`), and the reason for it is that the phone **is** the WhatsApp
  destination a purchase order is sent to. That reason does not apply to a shop you walk into. So
  relax the constraint and refuse the WhatsApp send instead, with a code that says why.
  **Where the guard goes is the whole of the care in this task.** Today
  `PurchaseOrderDeliveryService.sendViaWhatsApp` (`:53-100`) reads the phone at `:68-70` and passes it
  through with no check at all. A null flows to `NotificationService.java:162`, which builds the
  recipient label by string concatenation and persists the literal **`"Vendor null"`**, and only then
  fails at `:171-174` with a generic `VALIDATION_FAILED` and an opaque `"no contact address"`. That
  failure happens **after** `purchaseOrders.send(actor, poId)` at `:59` has already moved the order
  DRAFT → SENT. The `@Transactional` at `:54` rolls it back, so it is not corrupting — but the user
  gets a meaningless 400 for a perfectly sensible situation. **The guard belongs before `:59`.**
  Relaxing the column means the `CHECK` must become "null, or E.164" rather than being dropped — a
  dropped check would let a malformed number in, which is a different defect. `@NotBlank` comes off
  `phone` in both `CreateVendorRequest.java:13-14` and `UpdateVendorRequest.java:13-14` while
  `@Pattern` stays. `VendorService.java:113` and `:140` both call `request.phone().trim()` and will
  NPE on null. And on the form, `frontend/app/vendors/new/page.tsx:97-100` submits phone at `:54`
  **without** passing through `emptyToNull` (`:143-146`) unlike every other optional field, so it
  would post `""` and fail the pattern.
- **paths:**
  - `backend/src/main/resources/db/migration/V97__a_vendor_need_not_have_a_phone.sql` *(new)*
  - `backend/src/main/java/org/iskcon/kms/vendor/CreateVendorRequest.java`
  - `backend/src/main/java/org/iskcon/kms/vendor/UpdateVendorRequest.java`
  - `backend/src/main/java/org/iskcon/kms/vendor/VendorService.java`
  - `backend/src/main/java/org/iskcon/kms/purchaseorder/PurchaseOrderDeliveryService.java`
  - `frontend/app/vendors/new/page.tsx`
  - `frontend/app/vendors/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/vendor/VendorWithoutPhoneIT.java` *(new)*
  - `frontend/__tests__/vendor-without-phone.test.tsx` *(new)*
  - **no `**` glob.** `PurchaseOrderService.java` is T-024's file this wave and is forbidden here.
- **reservations:**
  - migration: **`V97`** — drop `NOT NULL` on `vendors.phone` and replace `vendors_phone_e164` with a
    check that permits null. `vendors` is tenant-owned (`enable_tenant_rls`, `V24:42`).
  - error code: `VENDOR_HAS_NO_WHATSAPP_NUMBER` **`KMS-400127`** (409) — *"This vendor has no phone
    number to send to."* / *"Download the order and hand it over, or add a number to the vendor."*
  - `frontend/lib/api.ts`: `phone` becomes optional on `VendorInput` (`:1659-1669`) and on
    `VendorView`.
  - permissions: none new.
- **acceptance:**
  - A vendor saves with no phone, and one with a malformed phone is still refused — the check permits
    null, it does not permit rubbish.
  - Sending a PO on WhatsApp to a phoneless vendor returns `KMS-400127` **and leaves the order in
    DRAFT** — a test asserts the status, because the failure currently happens after the transition.
  - No notification row is written with a `"Vendor null"` label.
  - An existing vendor with a phone is completely unaffected.
- **proof:** —
- **shipped:** —

---

# Wave 5 — the two procurement screens

Both sit on wave 4. T-026 needs T-024's described line and T-025's phoneless vendor before its form
can be built once rather than twice; T-027 needs T-023's flag so a supply can be added to the list.

### T-026 — Raising a one-off purchase order, vendor first

- **source:** docket **B1** (INTAKE B1), unblocked by D-1 · `DECISIONS.md` **D-7** for the shape,
  **D-2** for why the vendor is a real row, **D-3** for the constraint on inventing UI.
- **wave:** 5
- **state:** queued
- **what:** `POST /api/v1/purchase-orders` (`PurchaseOrderController.java:47-54`, `createManual`,
  `MANAGE_PURCHASE_ORDERS`) has existed all along and `api.createPurchaseOrder`
  (`frontend/lib/api.ts:4078-4083`) wraps it with **zero callers**; `frontend/app/orders/` has no
  `new/` route. Build it in the shape D-7 ruled: **`/orders/new` asks one question — which vendor —
  with an "Add a vendor" `ButtonLink` beside a native `<select>`, and choosing leads to the lines.**
  Routing out to `/vendors/new` costs nothing because nothing has been entered yet.
  **This invents no mechanism, and that was the binding constraint (D-3).** `FocusScreen`
  (`components/ds/FocusScreen.tsx:32-71`) is used by 21 files; `ButtonLink` in the exact two shapes
  needed (`app/vendors/page.tsx:72` for a list-header primary, `app/vendors/new/page.tsx:81-83` for a
  FocusScreen cancel); a native `<select>` picking a related entity 72 times over, of which
  `app/invoices/new/page.tsx:103-111` is a **vendor picker inside a `FocusScreen`** and therefore
  screen one almost exactly; and the `?added=` flash, whose producer/consumer pair is
  `app/vendors/new/page.tsx:67` → `app/vendors/page.tsx:50-58`.
  **Copy the flash idiom carefully — there is no helper.** It is a hand-rolled ten lines repeated on
  16 pages: `useSearchParams()`, a `useRef` guard so it fires once, `setFlash`, `router.replace` to
  strip the param, and an `InlineNotice`. The ref guard is not optional; `app/vendors/page.tsx:47-58`
  records that without it a new router object on each render turns the effect into a loop. The page
  also needs a `<Suspense>` boundary for `useSearchParams` (`app/vendors/page.tsx:24-28`). This is the
  same failure already in this codebase's memory as the ref-guarded flash-banner bug.
  **Three server rules the form must respect**, all verified: `neededBy` earlier than today throws
  `NEEDED_BY_BEFORE_ORDER_DATE` (`PurchaseOrderService.java:325-336`); `requireVendor` (`:338-343`)
  checks only that the vendor exists, so an **inactive** vendor is accepted and the picker should not
  offer one; and `api.listVendors`'s flag is **inverted** — `listVendors(false)` (the default) asks
  for *inactive included*, and `listVendors(true)` is active-only (`api.ts:3976-3988`, whose own
  comment records this was a bug once).
  The orders list at `frontend/app/orders/page.tsx` gains the entry point — its header at `:37-42` has
  no action button today, and its empty state at `:59-64` already says "or create one directly" with
  nothing behind it.
- **paths:**
  - `frontend/app/orders/new/page.tsx` *(new — the vendor question)*
  - `frontend/app/orders/new/lines/page.tsx` *(new — the lines; final path the builder's to choose,
    but it stays under `orders/new/`)*
  - `frontend/app/orders/page.tsx`
  - `frontend/__tests__/manual-purchase-order.test.tsx` *(new)*
  - **forbidden:** `frontend/app/orders/[id]/page.tsx` — T-024's file in wave 4 and T-013's in wave 8.
    This task never opens it.
- **reservations:**
  - migration: none. Backend complete.
  - error codes: none new.
  - `frontend/lib/api.ts`: none — `createPurchaseOrder`, `listVendors` and `createVendor` all exist.
  - `frontend/lib/nav.ts`: none. Reached from `/orders`, not from the menu.
- **acceptance:**
  - Choosing a vendor then entering lines creates the order and lands on it, with the `?added=` flash
    on the list behaving exactly as `/vendors/new` → `/vendors` does.
  - "Add a vendor" leaves for `/vendors/new` and comes back with the new vendor selected, **and
    nothing was lost**, because nothing had been entered.
  - A described line (T-024) can be added alongside an ingredient line.
  - The picker offers active vendors only; a past `neededBy` is refused readably.
  - The flash effect is ref-guarded — a test asserts it does not re-fire.
- **proof:** —
- **shipped:** —

### T-027 — Adding a line to the shopping list by hand

- **source:** docket **B3** (INTAKE B3), unblocked by D-1.
- **wave:** 5
- **state:** queued
- **what:** `ShoppingListController` has `GET`, `POST /regenerate` and `PATCH /{ingredientId}`, which
  only updates a row that already exists — so a cook who knows the list is missing something cannot
  say so. **The schema already supports the fix and needs no migration**, which is the whole reason
  this is cheap: `shopping_list_lines` is unique on `(tenant_id, ingredient_id)`
  (`V25:44`, renamed `V81:48-49`) and carries an `edited BOOLEAN`, and the regenerator deletes only
  rows where `edited = false` (`ShoppingListService.java:156-163`) — so **a hand-added line survives
  regeneration if and only if it is written with `edited = true`.** That is the one thing this task
  must get right; get it wrong and the line silently disappears at 3am when the nightly job runs.
  Add a `POST` and a service method. Because of the unique index the add is an upsert or a readable
  refusal, never a blind insert. Three constraints the input must satisfy:
  `CHECK (suggested_qty > 0)`, so a quantity of zero is not a way to add a placeholder;
  `CHECK (unit IN ('KG','GM','L','ML','PIECES'))`; and the ingredient must exist.
  The screen (`frontend/app/shopping-list/page.tsx`) has **no ingredient picker at all** today — the
  vendor cell is read-only text at `:168-170`. The shape to copy is `AddLine` on the order detail
  (`app/orders/[id]/page.tsx:545-597`), whose own comment at `:538-542` explains the choice: *"A
  picker rather than a box to paste an identifier into: nobody knows an ingredient by its id, and the
  vendor page and the invoice form both choose one this way already."* Filter out what is already on
  the list, as `AddLine` does at `:557`.
  **A defect found while verifying, and deliberately not fixed here:** both existing PATCH callers
  (`:47-52`, `:54-59`) omit `suggestedVendorId`, and `updateLine` (`:80-84`) sets that column
  unconditionally — so **every quantity or include edit from this screen silently nulls the line's
  suggested vendor**, which is the field `generate()` at `:61-73` then requires to raise a PO. It is
  out of this contract because it is a different bug with a different fix. Raised to Rajeev instead.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/shoppinglist/ShoppingListController.java`
  - `backend/src/main/java/org/iskcon/kms/shoppinglist/ShoppingListService.java`
  - `backend/src/main/java/org/iskcon/kms/shoppinglist/AddShoppingListLineRequest.java` *(new)*
  - `frontend/app/shopping-list/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/shoppinglist/HandAddedLineIT.java` *(new)*
  - `frontend/__tests__/shopping-list-add.test.tsx` *(new)*
- **reservations:**
  - migration: **none** — confirmed; `edited` and the unique index both already exist.
  - error code: `ALREADY_ON_THE_SHOPPING_LIST` **`KMS-400128`** (409) — *"That's already on the
    shopping list."* / *"Change the quantity on the line that's there."*
  - `frontend/lib/api.ts`: `addShoppingListLine(input, token)` — no such wrapper exists today.
  - permissions: none new — `MANAGE_PURCHASE_ORDERS`, matching the other three endpoints.
- **acceptance:**
  - A hand-added line survives `POST /regenerate` — **the defining test**, and it must assert against
    a real regeneration, not against the `edited` column alone.
  - Adding an ingredient already on the list returns `KMS-400128`.
  - Quantity zero and an unknown unit are both refused.
  - After T-023, a supply can be added to the list the same way food can.
- **proof:** —
- **shipped:** —

---

# Wave 6 — money that was entered wrongly

Three separate backend packages, three migrations. These are the items where "a mistake is permanent"
costs a temple actual money: ₹45,000 keyed for ₹4,500, a bounced cheque, a gift entered twice.

### T-010 — A vendor invoice can be voided or credited, and a payment reversed

- **source:** docket **M4 + M5** (INTAKE M4, M5). Combined into one task: same package, one migration,
  and voiding a payment must recompute the invoice status the other half owns.
- **wave:** 6
- **state:** queued
- **what:** `VendorInvoiceController` has GET, GET/{id} and POST; `InvoiceStatus` is `PENDING` and
  `PAID` and nothing else, so there is not even a state for a disputed bill. `InvoicePaymentController`
  is append-only, and the invoice flips to `PAID` the moment the payments sum reaches the amount, with
  no path back — so a bounced cheque is unrecoverable. **The model to copy is already in this codebase
  and already used by the UI:** `StaffPayController`'s void, whose own doc comment states the principle
  — *"A POST rather than a DELETE, because nothing is removed: the row stays, marked, and the URL says
  what actually happens to it."* Follow it exactly. Voiding a payment must recompute the invoice's paid
  total and drop it back out of `PAID` when the sum no longer covers it. Nothing is deleted anywhere in
  this task; the ledger stays append-only and gains marks.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/invoice/**` *(controllers, services, `InvoiceStatus`, DTOs)*
  - `backend/src/main/resources/db/migration/V98__invoice_void_and_payment_reversal.sql` *(new)*
  - `frontend/app/invoices/[id]/page.tsx`
  - `frontend/app/invoices/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/invoice/InvoiceCorrectionIT.java` *(new)*
  - `frontend/__tests__/invoice-void.test.tsx` *(new)*
- **reservations:**
  - migration: **`V98`**.
  - error codes: `INVOICE_ALREADY_VOIDED` **`KMS-400129`** (409) — *"This invoice has already been voided."*
    / *"Look at the credit note recorded against it."*; `PAYMENT_ALREADY_VOIDED` **`KMS-400130`** (409) —
    *"This payment has already been struck."* / *"Record a new payment if one was actually made."*
  - permissions: none new — `MANAGE_VENDOR_PAYMENTS` (Temple Admin only) already fits.
  - `frontend/lib/api.ts`: `voidInvoice(id, reason, token)`, `creditInvoice(id, input, token)`,
    `voidInvoicePayment(invoiceId, paymentId, reason, token)` — exact signatures written at
    reservation time in the file's own style.
- **acceptance:**
  - Voiding a payment that took an invoice to `PAID` returns the invoice to `PENDING`, proven by an
    integration test on the real database.
  - A voided payment row still exists and is marked, not deleted.
  - A second void of the same payment returns `KMS-400130`.
  - The disputed/voided state is visible on the invoice screen, not only in the API.
- **proof:** —
- **shipped:** —

### T-012 — Voiding a hand-recorded donation

- **source:** docket **M6** (INTAKE M6).
- **wave:** 6
- **state:** queued
- **what:** `DonationController` is POST-only for hand-recorded gifts and the ledger controller is
  read-only throughout, so a gift entered twice or against the wrong donor permanently inflates the
  80G-relevant ledger — and if it was in kind it also inflated stock, in the same transaction, which
  cannot be reversed either. Build the void: mark the donation, and issue the compensating stock
  movement for the in-kind half **in one transaction**, so the two can never drift apart. The stock
  half can lean on the existing compensating-entry primitive rather than inventing a second mechanism.
  A voided donation must disappear from the 80G figures and stay visible in the ledger, marked — the
  same shape as every other correction in this batch. **Who may do this is settled** — `DECISIONS.md` **D-4** grants the new `VOID_DONATION` to Temple
  Admin alone. Recording a donation stays on `MANAGE_INVENTORY`, which kitchen staff hold, and D-5
  records that as knowingly inherited rather than fixed here: a cook can still create an 80G-relevant
  entry they can never read back.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/donation/DonationController.java`
  - `backend/src/main/java/org/iskcon/kms/donation/DonationVoidService.java` *(new)*
  - `backend/src/main/java/org/iskcon/kms/donation/**` *(DTOs and the recorder, as needed)*
  - `backend/src/main/resources/db/migration/V99__donation_void.sql` *(new)*
  - `frontend/app/donations/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/donation/DonationVoidIT.java` *(new)*
  - `frontend/__tests__/donation-void.test.tsx` *(new)*
- **reservations:**
  - migration: **`V99`**.
  - error codes: `DONATION_ALREADY_VOIDED` **`KMS-400131`** (409) — *"This donation has already been
    voided."* / *"Record it again if it was actually received."*
  - permissions: **new constant `VOID_DONATION`, granted to `TEMPLE_ADMIN` only** — settled by
    `DECISIONS.md` **D-4**. Note the name: D-4 named it `VOID_DONATION`, not the `CORRECT_DONATIONS`
    this file proposed. Forced rather than chosen — `VIEW_DONATIONS` is already Temple Admin alone,
    so anything wider would let somebody void a record they cannot read.
  - `frontend/lib/api.ts`: `voidDonation(id, reason, token)`.
- **acceptance:**
  - Voiding an in-kind donation reverses its stock movement and marks the donation, in one transaction
    — an integration test asserts that a failure in either half rolls back both.
  - The voided gift is excluded from the 80G period summary and still present in the ledger, marked.
  - A second void returns `KMS-400131`.
- **proof:** —
- **shipped:** —

### T-014 — Reinstating someone whose employment was ended

- **source:** docket **M9** (INTAKE M9), reinstatement half only. The second half of that docket item —
  *"the role change that would undo the demotion has no screen either"* — is **wrong** and is not in
  this task; that screen exists and works.
- **wave:** 6
- **state:** queued
- **what:** `endEmployment` and `update` share one `requireStillEmployed` guard, so ending employment is
  irreversible **and** locks the record in the same instant — a misclick cannot even be corrected, and
  there is no reinstate route anywhere in the controller's thirteen. Add one. It must clear the
  employment end state and, where sign-in was revoked as part of ending it, restore the user row that
  was disabled — otherwise a reinstated person is employed and cannot sign in. Reinstating somebody
  marked ineligible for rehire must refuse, not warn: `OUTSTANDING_BUILD_LIST` D1 has that flag
  carrying a reason across all ISKCON temples, and quietly overriding it would be worse than the gap.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/staff/StaffEmploymentController.java`
  - `backend/src/main/java/org/iskcon/kms/staff/StaffEmploymentService.java`
  - `backend/src/main/java/org/iskcon/kms/staff/ReinstateStaffRequest.java` *(new)*
  - `backend/src/main/resources/db/migration/V100__staff_reinstatement.sql` *(new, only if a column is needed)*
  - `frontend/app/staff/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/staff/StaffReinstatementIT.java` *(new)*
  - `frontend/__tests__/staff-reinstate.test.tsx` *(new)*
- **reservations:**
  - migration: **`V100`** — allocated conditionally. The existing columns may be enough to clear; if the
    builder finds it needs none, it leaves `V100` unused and says so in the proof. A gap in the sequence
    is harmless; a second builder taking the same number is not.
  - error codes: `EMPLOYMENT_NOT_ENDED` **`KMS-400132`** (409) — *"This person is still employed."* /
    *"There is nothing to reinstate."*
  - permissions: none new — `MANAGE_STAFF` (Temple Admin only).
  - `frontend/lib/api.ts`: `reinstateStaff(id, input, token)`.
- **acceptance:** a reinstated person is editable again and can sign in if they could before;
  reinstating someone still employed returns `KMS-400132`; reinstating someone marked ineligible for rehire
  is refused; the act is audited.
- **proof:** —
- **shipped:** —

---

# Wave 7 — the recorded meal, and two half-workflows

### T-007 — Correcting a recorded meal

- **source:** docket **S5** and **M2** (INTAKE S5, M2) · `OUTSTANDING_BUILD_LIST` **P8** ("Still
  outstanding: reopening a recorded meal to correct it") · `WORK_QUEUE` item 3.5.
- **wave:** 7
- **state:** queued
- **what:** **Read INTAKE S5 before starting — the docket sizes this as "a screen for it" and that is
  wrong.** The compensating-entry primitive is real, tested and unused, but three things stand between
  it and a corrected meal. Recording writes **one movement per (ingredient, batch) draw**, so a meal is
  a *set* of movements, not one. There is **no way to find that set** — the movement history filters by
  ingredient, type and limit, and not by reference — so a read capability is missing. And reversing
  every movement leaves the meal itself reading `COOKED`, still refusing a second recording, because
  the stock ledger and `meal_plans` are separate. So: add the reference-based lookup, add the meal-side
  correction, and coordinate both halves in one transaction. Rajeev's decision of 2026-09-07, recorded
  in `WORK_QUEUE`, governs the shape: **not a reopen but a correction** — a compensating entry that
  reverses the stock while leaving the original readable, so the screen says *"640 plates, corrected
  from 400 by X on Y"* and the audit trail comes for free. The original recording is never overwritten.
  **One consequence to carry:** `MEAL_ALREADY_RECORDED` (**`KMS-400098`**) currently tells the reader *"What
  was cooked can't be changed afterwards. Ask a Temple Admin if the figures are wrong."* That sentence
  becomes false the day this ships, and its rewrite is a reservation, not the builder's to make.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/meal/MealServiceController.java`
  - `backend/src/main/java/org/iskcon/kms/meal/ServedMealService.java`
  - `backend/src/main/java/org/iskcon/kms/meal/CorrectMealRequest.java` *(new)*
  - `backend/src/main/java/org/iskcon/kms/inventory/StockMovementController.java`
  - `backend/src/main/java/org/iskcon/kms/inventory/StockMovementService.java`
  - `backend/src/main/java/org/iskcon/kms/inventory/InventoryConsumptionService.java`
  - `backend/src/main/resources/db/migration/V101__meal_correction.sql` *(new)*
  - `frontend/app/planner/[date]/[kind]/page.tsx`
  - `frontend/components/planner/MealServices.tsx`
  - `backend/src/test/java/org/iskcon/kms/meal/MealCorrectionIT.java` *(new)*
  - `frontend/__tests__/meal-correction.test.tsx` *(new)*
- **reservations:**
  - migration: **`V101`** — to carry what the original figures were, so the screen can say "corrected
    from 400" without reading it out of the ledger.
  - error codes: `MEAL_ALREADY_CORRECTED` **`KMS-400133`** (409) — *"This meal has already been
    corrected."* / *"Look at the correction that was recorded against it."*
  - **text change, work manager's to make:** `MEAL_ALREADY_RECORDED` (**`KMS-400098`**) keeps its number and
    its first sentence; its next step becomes *"Record a correction if the figures are wrong."*
  - permissions: **new constant `CORRECT_RECORDED_MEAL`, granted to `TEMPLE_ADMIN` only** — settled by
    `DECISIONS.md` **D-4**.
  - `frontend/lib/api.ts`: `correctRecordedMeal(id, input, token)`, `movementsForMeal(mealPlanId, token)`.
- **acceptance:**
  - An integration test records a meal at 400, corrects it to 640, and asserts: consumption movements
    net to the 640 figure; the original recording is still readable; and cost-per-serving recomputes
    from the corrected number.
  - Correcting twice returns `KMS-400133`.
  - The screen shows "640, corrected from 400 by <name> on <date>".
  - A rollback test proves the stock half and the meal half cannot commit separately.
- **proof:** —
- **shipped:** —

### T-015 — Retrying a message to the addresses it failed for

- **source:** docket **B6** (INTAKE B6).
- **wave:** 7
- **state:** queued
- **what:** Send flips the record to `SENT` and every mutation is then refused by one shared
  draft-guard, so forty failed WhatsApp deliveries mean composing the whole letter again — while the
  per-recipient failure list sits on the screen in front of the sender. Add a retry that re-sends the
  existing message to **only** the recipients whose delivery failed, leaving the ones that succeeded
  alone. **One trap to avoid:** the recipient-queueing statement is `ON CONFLICT … DO NOTHING`, so a
  naive retry silently no-ops and reports success — it must become an update, or the retry is a lie.
  No migration: the per-recipient rows and their notification statuses already carry everything needed.
  Do not loosen the draft guard for editing; the letter's text stays frozen once sent, only delivery is
  retried.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/communication/CommunicationController.java`
  - `backend/src/main/java/org/iskcon/kms/communication/CommunicationService.java`
  - `frontend/app/communications/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/communication/CommunicationRetryIT.java` *(new)*
  - `frontend/__tests__/communication-retry.test.tsx` *(exists as `communications.test.tsx` — add a new file)*
- **reservations:**
  - migration: none.
  - error codes: `NOTHING_FAILED_TO_RETRY` **`KMS-400134`** (409) — *"Every copy of this message was
    delivered."* / *"There is nothing to send again."* *(Question 12 is closed; there is no band.)*
  - permissions: none new — `MANAGE_COMMUNICATIONS`.
  - `frontend/lib/api.ts`: `retryFailedDeliveries(id, token)`.
- **acceptance:** a retry re-queues only the failed recipients, proven by an integration test that
  asserts the succeeded ones are untouched; retrying a fully-delivered message returns `KMS-400134`; the
  message body cannot be edited by this path.
- **proof:** —
- **shipped:** —

### T-016 — Volunteer attendance, and striking someone off a roster

- **source:** docket **B7** (INTAKE B7).
- **wave:** 7
- **state:** queued
- **what:** The sign-up record carries times and reminder statuses and **no attendance of any kind** —
  so a no-show cannot be recorded, and consequently no reliability figure and no hours-contributed
  figure can ever be computed for anybody. Worse, the only release endpoint acts on the caller's own
  id, so a coordinator cannot remove a volunteer from a roster at all. Add both: an attendance mark on
  the sign-up, and a coordinator-side release that names the person being removed. The coordinator's
  release belongs on the shift-management surface behind `MANAGE_VOLUNTEER_SHIFTS`, **not** on the
  volunteer's own controller, which is deliberately scoped to the caller and must stay that way.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/shift/ShiftController.java`
  - `backend/src/main/java/org/iskcon/kms/shift/SignupService.java`
  - `backend/src/main/java/org/iskcon/kms/shift/**` *(DTOs and views, as needed)*
  - `backend/src/main/resources/db/migration/V102__shift_attendance.sql` *(new)*
  - `frontend/app/shifts/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/shift/ShiftAttendanceIT.java` *(new)*
  - `frontend/__tests__/shift-attendance.test.tsx` *(new)*
- **reservations:**
  - migration: **`V102`** — the attendance column on `shift_signups`. The table is tenant-owned, so the
    migration must respect the existing RLS on it and backfill per tenant, never across all rows.
  - error codes: `ATTENDANCE_ALREADY_RECORDED` **`KMS-400135`** (409) — *"Attendance for this shift has
    already been recorded."* / *"Change it on the shift's roster."*
  - permissions: none new — `MANAGE_VOLUNTEER_SHIFTS`.
  - `frontend/lib/api.ts`: `recordShiftAttendance(shiftId, input, token)`,
    `releaseVolunteerFromShift(shiftId, userId, token)`.
- **acceptance:** attendance marks persist and are visible on the roster; a coordinator can remove a
  named volunteer and the volunteer's own release endpoint is unchanged; `VolunteerShiftController`
  still acts only on the caller's id — a test asserts a volunteer cannot release somebody else.
- **proof:** —
- **shipped:** —

---

# Wave 8 — the last four

### T-013 — Returning goods to a vendor after they were accepted

- **source:** docket **M3** (INTAKE M3).
- **wave:** 8
- **state:** queued
- **what:** Rejection only works at the gate — the rejected quantity is a field of the receiving
  submission itself — so weevils found the next morning, or 50 kg keyed instead of 5, have no path.
  There is no return-to-vendor movement type: the enum is exactly `PO_RECEIPT`, `DONATION_IN_KIND`,
  `CONSUMPTION`, `ADJUSTMENT`, `ISSUE`. Add the type and the endpoint. **The enum is mirrored in a
  database `CHECK` constraint**, so this is a migration and not only a Java change — a new value added
  on one side alone fails at runtime, not at compile time. A return must reduce stock through a movement
  like everything else, never by editing the receipt, and must not be able to return more than was
  received.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/inventory/MovementType.java`
  - `backend/src/main/java/org/iskcon/kms/receiving/**`
  - `backend/src/main/resources/db/migration/V103__return_to_vendor.sql` *(new)*
  - `frontend/app/orders/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/receiving/ReturnToVendorIT.java` *(new)*
  - `frontend/__tests__/goods-return.test.tsx` *(new)*
- **reservations:**
  - migration: **`V103`**.
  - error codes: `RETURN_EXCEEDS_RECEIVED` **`KMS-400136`** (400) — *"You can't return more than was
    received."* / *"Check the quantity against the goods receipt."*; `ALREADY_RETURNED` **`KMS-400137`**
    (409) — *"These goods have already been returned."* / *"Look at the return recorded against this
    receipt."*
  - permissions: none new — `MANAGE_INVENTORY`.
  - `frontend/lib/api.ts`: `returnReceivedGoods(receiptId, input, token)`.
- **acceptance:** a return reduces on-hand by exactly the returned quantity through a new movement;
  over-returning gives `KMS-400136`; the goods receipt itself is never mutated; the `CHECK` constraint and
  the Java enum agree, proven by an integration test that inserts the new type.
- **proof:** —
- **shipped:** —

### T-019 — Raising a shift from the planner, and seeing it there afterwards

- **source:** docket **S6 + S7** (INTAKE S6, S7) · `OUTSTANDING_BUILD_LIST` **P6** and **P7** ·
  `WORK_QUEUE` item 3.6. One task because they are one piece of work in one set of files.
- **wave:** 8
- **state:** queued
- **what:** When the crew a meal needs exceeds the staff working that day, the planner shows the
  shortfall and offers nothing. `createShift` exists and has never appeared in a planner file in the
  whole history — the overlay Rajeev remembers was on the Volunteers page and became its own screen on
  2026-08-21 under his four-fields-becomes-a-screen rule. Build the affordance: open the post-a-shift
  form as a layer over the planner with the title derived from date and meal ("Lunch preparation on
  September 1 2026") and the date and capacity pre-filled, post, and land back in the planner where you
  were. Then the other half: show the shift beside the existing crew count, with its sign-up count, and
  let it be opened and edited in the same layer. **The linkage is Question 9** — today the planner
  infers crew from time-window overlap, which `MealCrewService`'s own doc defends as deliberate ("a
  shift posted 11:00–14:00 falls to lunch without anybody having to link it to one"). Build the
  time-window match unless Rajeev has chosen the explicit link; if he has, `V105` is reserved for it.
- **paths:**
  - `frontend/components/planner/MealServices.tsx`
  - `frontend/app/planner/[date]/[kind]/page.tsx`
  - `frontend/components/planner/ShiftLayer.tsx` *(new)*
  - `frontend/__tests__/planner-shift.test.tsx` *(new)*
  - `backend/src/main/resources/db/migration/V105__meal_shift_link.sql` *(new — **only** if Q9 chooses the explicit link)*
- **reservations:**
  - migration: **`V105`**, conditional on Question 9. Unused otherwise.
  - `frontend/lib/api.ts`: a shifts-for-a-date-range wrapper if one is not already present; `createShift`
    (`:4536`) and `shiftRoster` (`:4533`) already exist.
- **acceptance:** posting from the planner creates the shift and returns the reader to the same day and
  meal; the shift then appears beside the crew count with its sign-up figure; opening and closing the
  layer without saving changes nothing.
- **proof:** —
- **shipped:** —

### T-020 — A donation receipt a donor can be given or sent again

- **source:** docket **B5** (INTAKE B5).
- **wave:** 8
- **state:** queued
- **what:** The product captures PAN, builds the 80G rows and exports the statutory ledger, and the only
  per-donation artefact is an internal record used once to fire a thank-you. Recipe cards, job cards,
  work orders and PO sheets all have templates; a receipt does not — which is the one document a donor
  actually asks for. Add the template as a fifth alongside the four, through the existing document
  generation path rather than a parallel one, and an endpoint to generate and re-send it. The
  `documents` table constrains its `kind` column by a `CHECK`, so a new kind is a migration; the table
  also needs to point at the donation the receipt is for.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/document/DonationReceiptTemplate.java` *(new)*
  - `backend/src/main/java/org/iskcon/kms/document/DocumentGenerationService.java`
  - `backend/src/main/java/org/iskcon/kms/document/DocumentService.java`
  - `backend/src/main/java/org/iskcon/kms/document/DonationReceiptController.java` *(new)*
  - `backend/src/main/resources/db/migration/V104__donation_receipt_document.sql` *(new)*
  - `frontend/app/donations/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/document/DonationReceiptIT.java` *(new)*
  - `frontend/__tests__/donation-receipt.test.tsx` *(new)*
- **reservations:**
  - migration: **`V104`** — the `donation_id` column and the widened `kind` CHECK.
  - error codes: none new; document generation already has its failure codes.
  - permissions: none new — `VIEW_DONATIONS` to read, `MANAGE_INVENTORY` to generate, matching how the
    donation surfaces are already split.
  - `frontend/lib/api.ts`: `generateDonationReceipt(donationId, token)`,
    `resendDonationReceipt(donationId, token)`.
- **acceptance:** a receipt renders with the temple's 80G status and the donor's details and downloads;
  re-sending does not create a second document; a voided donation (T-012) cannot have a receipt issued.
- **proof:** —
- **shipped:** —

### T-021 — A malformed phone number gets the message written for it

- **source:** docket **D4** (INTAKE D4). Held out of T-003 deliberately: it shares neither a file nor a
  mechanism with the other three.
- **wave:** 8
- **state:** queued
- **what:** `INVALID_PHONE_NUMBER` (**`KMS-400003`**) would say *"Include the country code, for example
  +91 98765 43210."* It is never thrown, and today it **structurally cannot be**: every phone check is a
  Bean Validation `@Pattern` on a DTO field, and the validation handler stamps `VALIDATION_FAILED`
  (**`KMS-400001`**) on every such failure regardless of which field failed — so in an application where the
  phone number is a way to sign in, the one message that would actually help is unreachable. Make the
  specific code reach the reader when the phone field is the failure. **While in there:** two DTOs check
  only length and no format at all, so a malformed number is accepted outright in those two paths;
  bring them onto the same rule. Do not weaken the generic handler for every other field — this is a
  carve-out for one named field, not a redesign of validation.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/error/GlobalExceptionHandler.java`
  - `backend/src/main/java/org/iskcon/kms/tenant/JoinTempleRequest.java`
  - `backend/src/main/java/org/iskcon/kms/tenant/ProvisionTenantRequest.java`
  - `backend/src/main/java/org/iskcon/kms/vendor/CreateVendorRequest.java`
  - `backend/src/main/java/org/iskcon/kms/vendor/UpdateVendorRequest.java`
  - `backend/src/main/java/org/iskcon/kms/staff/HireStaffRequest.java`
  - `backend/src/main/java/org/iskcon/kms/kitchen/CreateKitchenRequest.java`
  - `backend/src/main/java/org/iskcon/kms/kitchen/UpdateKitchenRequest.java`
  - `backend/src/main/java/org/iskcon/kms/donation/CreateRecurringRequest.java`
  - `backend/src/test/java/org/iskcon/kms/error/PhoneValidationIT.java` *(new)*
- **reservations:** none — `KMS-400003` already exists with its final text. No migration.
- **acceptance:** posting a malformed phone to each of the listed endpoints returns `KMS-400003`, not
  `KMS-400001`; a request failing on a phone number *and* another field still returns `KMS-400001` with both in
  the field errors; `ErrorCodeTest` still passes.
- **proof:** —
- **shipped:** —

---

## Waves

| Wave | Tasks | Concurrent? | Why it is safe, or why it is serialised |
|---|---|---|---|
| 1 · **shipped** | T-001, T-002, T-003 | yes, 3 builders | Path sets disjoint: one inventory screen, four unrelated role-gated pages, and the auth package plus three frontend files. The one place two of them could have met is `RequireRole.tsx` — T-003 needs it for the new status, T-002 is tempted into it by the "Not your page" screen — so it is named in T-003's contract and in T-002's forbidden list. No migrations, no new codes; the whole wave's reservation was two lines in `nav.ts`, of which one shipped and one was reverted. |
| 2 · **4 shipped, 1 blocked** | T-004, T-006, T-009, T-017, **T-028** | yes, 5 builders | Four new routes under four different directories, each over a backend already finished and tested. The only shared cost is `api.ts`, stubbed beforehand. **T-028 is the fifth and shares nothing with the other four** — it is two files in `backend/.../shoppinglist/`, no frontend, no reservation of any kind. It sits in this wave rather than a later one because it is destroying vendor selections in production every time somebody edits the list, and because both its files are free until wave 4. | **Ran and released 2026-09-07.** T-004 (`67d5f05`), T-006 (`bfca0ac`), T-009 (`723696c`) and T-028 (`a41d094`) are on staging; T-017 stopped before writing product code on a blocker that needs Rajeev (no next-charge date exists anywhere in the stack) and **stays queued, not shipped and not proven**. No contract breached, none widened.
| 3 | T-005, T-008, T-018, T-022 | yes, 4 builders | **T-005 is held out of wave 2 on purpose.** It and T-004 are both settings-area screens and I am not certain neither reaches into `frontend/app/settings/page.tsx` for a link — doubt means serialise. T-022 writes only into `frontend/__tests__/` under five named filenames, so the directory is never reserved whole. |
| 4 | T-023, T-024, T-025 | yes, 3 builders | **The riskiest wave in the batch, and the one to read twice.** T-024 and T-025 are both inside `backend/.../purchaseorder/` — `PurchaseOrderService.java` and `PurchaseOrderDeliveryService.java` respectively — so neither contract may use a `**` glob and each names the other's file as forbidden. Three migrations, `V95`/`V96`/`V97`. Three and not four because every one carries a migration and the verify lock is the bottleneck. **T-023 takes `ShoppingListService.java` (its `IS NOT NULL` guard) only after T-028 has left it in wave 2** — a different method in the same file, so the ordering is what keeps them apart, not the path set. |
| 5 | T-026, T-027 | yes, 2 builders | Both sit on wave 4 and cannot precede it: T-026 needs T-024's described line and T-025's phoneless vendor, T-027 needs T-023's flag. Deliberately a thin wave — the alternative was pulling wave 6 forward into files T-024 has just left, which is the bet this arrangement exists to avoid. T-026 is forbidden `orders/[id]/page.tsx`, which T-024 owns in wave 4 and T-013 in wave 8. **T-027 takes `ShoppingListService.java` and `frontend/app/shopping-list/page.tsx` after T-028 (wave 2) and T-023 (wave 4)**, and must build its hand-added line on the corrected `updateLine`, not the destructive one. |
| 6 | T-010, T-012, T-014 | yes, 3 builders | Three separate backend packages — invoice, donation, staff — and three migrations, `V98`/`V99`/`V100`, allocated here because Flyway would not notice the collision until it refused to boot. |
| 7 | T-007, T-015, T-016 | yes, 3 builders | T-007 reaches into the inventory package as well as the meal package, so nothing else touching inventory runs beside it. **T-019 is held back** even though it looks disjoint: if Question 9 goes to the explicit meal↔shift link it lands in the shift package T-016 is migrating. Dependencies beat parallelism. |
| 8 | T-013, T-019, T-020, T-021 | yes, 4 builders | Four deliberate cross-wave serialisations, not four bets. T-013 takes `receiving/` only after T-024 (wave 4) has left it and the inventory package only after T-007. T-020 takes `DocumentGenerationService.java` only after T-024, and donations only after T-012. **T-021 takes `CreateVendorRequest.java` and `UpdateVendorRequest.java` only after T-025 has left them** — the two tasks both rewrite the phone rule on the same two DTOs, and running them together would have been the collision this wave table exists to catch. T-019 takes the planner only after T-007. |

**Not in any wave, and not to be added to one without Rajeev:** S2 and B4 (still blocked — the
operator audit drill-in, and whether a funded wish-list item may seed a purchase), and the seven
environment/credential/human-verification items (P1–P6, P8). B1, B2 and B3 **were** blocked and are
now scheduled, unblocked by D-1 — they are T-026, T-024/T-023 and T-027 respectively.

---

## Reservations, in one place

Nothing below is written yet **except wave 1's and wave 2's**, both in the working tree — wave 2's `api.ts` and `nav.ts` reservations were written in one pass on 2026-09-07 immediately before dispatch. Each block goes into
the shared files in a single pass immediately before its wave is authorised.

**Migrations** — the tree is at `V94`, so:

| Version | Task | Wave | For |
|---|---|---|---|
| `V95` | T-023 | 4 | The flag separating supplies from food on `ingredients` |
| `V96` | T-024 | 4 | Nullable `ingredient_id`, a `description`, and a check that exactly one is present |
| `V97` | T-025 | 4 | `vendors.phone` off `NOT NULL`; the E.164 check permits null |
| `V98` | T-010 | 6 | Invoice void/credit states, payment reversal marks |
| `V99` | T-012 | 6 | Donation void |
| `V100` | T-014 | 6 | Staff reinstatement — **conditional**, may go unused |
| `V101` | T-007 | 7 | The figures a meal was corrected from |
| `V102` | T-016 | 7 | Attendance on `shift_signups` (tenant-owned: RLS-respecting, per-tenant backfill) |
| `V103` | T-013 | 8 | The return-to-vendor movement type and its `CHECK` |
| `V104` | T-020 | 8 | The donation-receipt document kind and its `donation_id` |
| `V105` | T-019 | 8 | Meal↔shift link — **conditional on Question 9**, may go unused |

Every one of `V95`, `V96`, `V97`, `V99` and `V102` touches a tenant-owned table. Migrations are
themselves subject to RLS in this project, so each backfills per tenant and never across all rows.

**Error codes.** The nine numbers the first plan proposed never existed; these are their
replacements, allocated from **`KMS-400124`** in wave order so the numbers ascend in the order they
will be appended to `ErrorCode.java`. Five are new to the re-plan (T-023 to T-027). Constant names,
statuses and copy for the original nine were already reviewed and are unchanged.

| Code | Task | Wave | Text / next step |
|---|---|---|---|
| `NOT_A_FOOD_INGREDIENT` **`KMS-400124`** (409) | T-023 | 4 | "That's a supply, not something you can cook with." / "Choose a food ingredient, or add this one to the catalogue as food." |
| `PURCHASE_LINE_NEEDS_A_SUBJECT` **`KMS-400125`** (400) | T-024 | 4 | "Each line needs either an ingredient or a description, not both and not neither." / "Pick an ingredient, or describe what you're buying." |
| `CANNOT_RECEIVE_A_DESCRIBED_LINE` **`KMS-400126`** (409) | T-024 | 4 | "A described line can't be received into stock." / "Record it as delivered on the order; it isn't something the store tracks." |
| `VENDOR_HAS_NO_WHATSAPP_NUMBER` **`KMS-400127`** (409) | T-025 | 4 | "This vendor has no phone number to send to." / "Download the order and hand it over, or add a number to the vendor." |
| `ALREADY_ON_THE_SHOPPING_LIST` **`KMS-400128`** (409) | T-027 | 5 | "That's already on the shopping list." / "Change the quantity on the line that's there." |
| `INVOICE_ALREADY_VOIDED` **`KMS-400129`** (409) | T-010 | 6 | "This invoice has already been voided." / "Look at the credit note recorded against it." |
| `PAYMENT_ALREADY_VOIDED` **`KMS-400130`** (409) | T-010 | 6 | "This payment has already been struck." / "Record a new payment if one was actually made." |
| `DONATION_ALREADY_VOIDED` **`KMS-400131`** (409) | T-012 | 6 | "This donation has already been voided." / "Record it again if it was actually received." |
| `EMPLOYMENT_NOT_ENDED` **`KMS-400132`** (409) | T-014 | 6 | "This person is still employed." / "There is nothing to reinstate." |
| `MEAL_ALREADY_CORRECTED` **`KMS-400133`** (409) | T-007 | 7 | "This meal has already been corrected." / "Look at the correction that was recorded against it." |
| `NOTHING_FAILED_TO_RETRY` **`KMS-400134`** (409) | T-015 | 7 | "Every copy of this message was delivered." / "There is nothing to send again." |
| `ATTENDANCE_ALREADY_RECORDED` **`KMS-400135`** (409) | T-016 | 7 | "Attendance for this shift has already been recorded." / "Change it on the shift's roster." |
| `RETURN_EXCEEDS_RECEIVED` **`KMS-400136`** (400) | T-013 | 8 | "You can't return more than was received." / "Check the quantity against the goods receipt." |
| `ALREADY_RETURNED` **`KMS-400137`** (409) | T-013 | 8 | "These goods have already been returned." / "Look at the return recorded against this receipt." |

Every one satisfies `ErrorCodeTest`: unique, no jargon, a non-blank next step, both sentences ending
in a full stop, `KMS-\d{6}` (`ErrorCodeTest.java:93`), and `number/100000 == httpStatus/100`
(`:104-108`). All fourteen are 4xx, so all fourteen are in the `400xxx` family.

**One text change to an existing code**, T-007: `MEAL_ALREADY_RECORDED` **`KMS-400098`**
(`ErrorCode.java:551`) keeps its number and its first sentence. Its next step becomes *"Record a
correction if the figures are wrong."* — because the current one, *"What was cooked can't be changed
afterwards. Ask a Temple Admin if the figures are wrong."*, becomes untrue the day T-007 ships. D-4
says so explicitly.

**Permissions.** Two new constants, both settled by `DECISIONS.md` **D-4**, both `TEMPLE_ADMIN` only,
no new roles. The enum goes from 36 to 38; the five roles stay five.

| Constant | Task | Wave | Grant | Why admin-only |
|---|---|---|---|---|
| `VOID_DONATION` | T-012 | 6 | `TEMPLE_ADMIN` | Forced, not chosen: `VIEW_DONATIONS` is already Temple Admin alone, so anything wider lets somebody void a record they cannot read. |
| `CORRECT_RECORDED_MEAL` | T-007 | 7 | `TEMPLE_ADMIN` | Recording stays on `MANAGE_MEAL_PLANS` — everyday kitchen work. Correcting rewrites a number stock consumption and cost-per-serving have already inherited. Widening later is one line; narrowing after temples build a habit is a conversation with every one of them. |

**Note the rename.** This file previously proposed `CORRECT_DONATIONS`. D-4 named it
**`VOID_DONATION`**, and D-4 wins.

**Both files, not one.** Each constant is declared in `backend/.../auth/Permission.java` with the
paragraph explaining why it was split out, and granted in `backend/.../auth/RolePermissions.java`.
The protocol's reservation table named only the second; both are reserved. `Permission.java` is
written to be read as a document in exactly the same way, and concurrent appends corrupt it just as
surely.

**`frontend/lib/api.ts`** — wrappers and types to stub, by wave. W2: `createOccasion`,
`updateOccasion`, `deleteOccasion`, `updateEquipment`. W3: `deleteMealKind`, `updateTenant`.
W4: the supplies flag on `IngredientView` / `CreateIngredientInput` / `UpdateIngredientInput` and a
filter argument on `listIngredients`; `ingredientId`/`ingredientName` become optional and
`description` is added on `PoLineInput` and `PurchaseOrderLineView`; `phone` becomes optional on
`VendorInput` and `VendorView`. W5: `addShoppingListLine` — **none for T-026**, which needs no new
wrapper at all. W6: `voidInvoice`, `creditInvoice`, `voidInvoicePayment`, `voidDonation`,
`reinstateStaff`. W7: `correctRecordedMeal`, `movementsForMeal`, `retryFailedDeliveries`,
`recordShiftAttendance`, `releaseVolunteerFromShift`. W8: `returnReceivedGoods`,
`generateDonationReceipt`, `resendDonationReceipt`, and a shifts-for-range wrapper if none exists.

**`frontend/lib/nav.ts`** — four edits, not five. **Wave 1 shipped one**: `/donate` gained `ADMIN`.
The `/my-shifts` narrowing was reverted and is Rajeev's to settle, so it is no longer counted here.
Three new rows remain, all in waves 2–3: `/settings/occasions`,
`/settings/meal-kinds` and `/my-schedule`. Neither procurement screen needs a row — `/orders/new` is
reached from `/orders` and the shopping-list add is on the list itself. `routes.ts` and `Sidebar.tsx`
are untouched by this batch.

---

## For Rajeev — found while re-planning, not scheduled

Four things the verification turned up. **Item 3 is no longer one of them** — Rajeev scheduled it
on 2026-09-07 and it is now **T-028** in wave 2. It is left in place, struck through, so the finding
and the task it became stay attached to each other.

1. **The pattern D-3 pointed at no longer exists.** D-3 named the equipment service-company list as
   the analogue for inline vendor creation. It was **deleted on 2026-09-04 at Rajeev's own request**
   — `V90__service_company_as_text.sql:10-16` quotes him: *"can we make 'Service company' a free text
   box and Remove the Add a Service Company button, the back end code and tables for it… A Text box
   serves the purpose JUST FINE."* The migration's own reasoning lists the cost it was shedding as,
   among other things, *"a picker with an add-without-leaving-the-screen flow on three screens"* —
   which is exactly the affordance D-2 originally proposed. **This does not block anything**, because
   D-7 had already overturned that inline shape in favour of routing out, and D-7's shape reuses only
   patterns that do exist. It is worth saying plainly that D-7 is now the *only* shape consistent
   with the 2026-09-04 ruling, and that the "no such pattern exists" finding D-3 asked for has
   happened and is this.
2. **Why a vendor is still a real row when a service company is free text**, since the two look like
   the same question answered opposite ways. The difference is real: a service company hangs nothing
   off it, while `purchase_orders.vendor_id` is `NOT NULL` and invoices, payments, receiving and
   vendor spend all hang off it. D-2's reasoning survives V90. Stated here so it does not look as
   though the 2026-09-04 ruling was ignored.
3. ~~**A live defect on the shopping list, out of every contract.**~~ **→ scheduled as T-028, wave 2**
   (2026-09-07). Left below exactly as it was written. Both PATCH callers on
   `frontend/app/shopping-list/page.tsx` (`:47-52` include, `:54-59` quantity) omit
   `suggestedVendorId`, and `ShoppingListService.updateLine` (`:80-84`) writes that column
   unconditionally — so **every edit from that screen silently nulls the line's suggested vendor**,
   which is the field `generate()` at `:61-73` then requires in order to raise a purchase order. Tick
   a box, lose the vendor, and the line quietly stops being orderable. ~~Its own task if you want it.~~
   It is T-028.
4. **`library_derived` on `ingredients` is write-only dead weight** — written at
   `RecipeImportService.java:202`, read by nothing, exposed nowhere. Noted while inventorying the
   flags so it is not mistaken later for something T-023 broke.
