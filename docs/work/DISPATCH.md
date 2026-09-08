# Dispatch ledger

Read `docs/work/README.md` first — it explains what this file is and who is allowed to write to it.
Read `docs/work/INTAKE.md` second — it is the verification behind every row here, and it is where the
docket items that are *not* build tasks went.

**Status: waves 0, 1, 2, 3, 4a, 4b and 4c are all SHIPPED to `main`.** Wave 4c released 2026-09-07
in **nine product commits plus the ledger commit that carries this file** — the largest release of
the batch, eleven tasks across three sub-waves, every one of them proven before it was handed over:

| Commit | Tasks | What |
|---|---|---|
| `09404a0` | **T-038**, **T-047** | Renaming a meal kind carries the meals with it; deleting one in use is refused with `KMS-400126`; a duplicate rename answers `KMS-400047` instead of a 500. Migration **`V96`**. |
| `bd5398c` | **T-039**, **T-046** | A refused self access change, and a refused self end-of-employment, are both on the audit trail after the 403. A successful access change writes `ROLE_CHANGED` of its own. |
| `a83f4d5` | **T-040** | The dead `PATCH /users/{id}/role` and everything behind it deleted; `KMS-400023` retired under D-9. |
| `61e9f19` | **T-041** | The temple correction screen narrows to name, address and 80G per **D-17**; the calendar-rebuild path goes, and the freeze is enforced at the service. |
| `2b61e2f` | **T-042** | Provisioning geocodes the address. **Inert as deployed** — `GEOCODING_PROVIDER` is unset on staging by design. |
| `3c5b51d` | **T-043** | Event meals can be recorded again, and a refusal is shown at the button that raised it. |
| `66a35f8` | **T-044** | Editing a placed delivery event stops re-pinning it to `0,0`. |
| `4c807e6` | **T-045** | An ingredient can be marked Ekadashi-prohibited, from the list and from the create form. |
| `9b0395d` | **T-048** | Migration **`V97`** unpins the delivery events already stranded at `0,0`. |

**Two commits carry two tasks each, and that is deliberate rather than sloppy.** T-038/T-047 both
edit `MealKindService.java` and a `MealKindIT.java` that did not exist before the wave; T-046 removed
the very helper T-039 had asked its successor to keep. In both cases the file's final state is what
the two builders produced together, and splitting it by hunk would have meant writing an intermediate
version neither of them ever wrote. Splitting a shared file is right where the hunks are independent
— `frontend/lib/api.ts`'s five slices and `ErrorCode.java`'s two were split that way, each into the
task that owns it — and wrong where it would mean fabricating history. The whole split was verified
mechanically: the tree written by the ten commits is byte-identical to the merged tree that was
handed over, checked with `git write-tree` before and after rather than by eye.

**Nothing in this wave has been seen working by a person.** Two things want a human before anything
else: recording an event meal from `/planner/catch-up` — the defect T-043 fixes is the one that
blocked two items of Rajeev's own review list — and reopening a delivery event that was picked from
the map to confirm its leave-by line is right again.

**Superseded status, kept for the history.** Wave 4b released 2026-09-07 in
three commits — `49ce170` (T-032), `8605028` (T-008) and the ledger commit that carries this file.
**Two of the four tasks shipped code and two shipped none**: T-005 stopped on a product decision it
was right to stop on, and T-018 turned out to be already built. Their proof files travel with the
ledger commit because a stop that is not written down is a stop that gets re-attempted. The two
product commits together reproduce the merged tree byte for byte — the `frontend/lib/api.ts` slices
were split by hunk and the reconstruction was checked by `diff`, not by eye.

**Nothing in this wave has been seen working by a person.** Both surfaces want one pass on staging:
`/my-schedule` as somebody on the payroll with approved leave in the next fortnight, and
`/tenants/[id]/edit` as the operator. Note for anyone reading T-032's proof: its "not done" section
says there is no way to sign in as kitchen staff on UAT. **That was true on the morning of
2026-09-07 and is no longer true** — the five `ikms.kitchen-staff.*` Firebase accounts now exist,
verified, and resolve as `KITCHEN_STAFF` at ISKCON South Bengaluru on `/api/v1/whoami`. What T-032
still needs is a rostered person with **approved leave in the next fortnight**, which is data setup
and not an access wall.

**Status: waves 0, 1, 2 and 3 SHIPPED.** Wave 1 released 2026-09-07 in four commits — `dd3fb31`
(T-003), `81fd72f` (T-002), `0448573` (T-001) and the planning commit that carries this file. Wave 2
released 2026-09-07 in five, one per task, with T-030 travelling alongside it: `a41d094` (T-028),
`67d5f05` (T-004), `bfca0ac` (T-006), `723696c` (T-009), `99b91d6` (T-030). CI green and deployed to
staging both times. **Wave 3 released 2026-09-07 in four commits, one per task, in the
order the wave was built** — `e2916fb` (T-034, the model and `V95`), `a93b3ee` (T-033), `429f63f`
(T-031) and `a29c654` (T-022), plus the ledger commit that carries this file. The four reserved-file
slices were split by hunk so each commit carries only its own task's share of `ErrorCode.java` and
`frontend/lib/api.ts`, and the four together reproduce the merged tree byte for byte — checked by
hash, not by eye. **D-16 shipped with T-031**, the task it authorises. **T-017 did not ship and is not proven** — it stopped before writing a line of
product code, and it is now `blocked` by Rajeev's own ruling rather than by the builder's; see its
rewritten row.

> ## Re-planned 2026-09-07, after Rajeev verified wave 3 on staging
>
> He drove the live site as the real roles after `527b23b` and passed T-033 and T-031 end to end.
> T-034 is **proven by test but not seen working** — its planner affordance is T-019, so it must be
> pressed by hand the moment that lands. T-022 wrote no product code, as intended. Four findings came
> back with him, and they became **three tasks, not four**:
>
> - **T-036 and T-037 are new.** T-036 is the stock-correction dialog — the lowercased unit and month,
>   *and* the *Correct* control offered on a movement already badged *Corrected*. Rajeev listed those
>   as two findings; they are **one id** because they are the same two components of the same file, and
>   two ids would have put two builders in it. T-037 is the registration dead end, built to the shape
>   Rajeev chose: remember the credential and resume at the join.
> - **The refused-page finding got no new id.** It is **T-035**, which already existed for exactly
>   this; his fourth surface and the disabled-account sign-out are folded into its row, because
>   `AccountDisabled` is declared inside `RequireRole.tsx` and a second id would have been a second
>   builder in one file.
> - **The native validation bubble is a finding, not a task** — it is the equipment screen's existing
>   convention in three places, not a wave-3 regression, and the file is forbidden to T-035 by design.
>   Recorded with its evidence at the foot of this file.
> - **Waves 5–9 did not move, and neither did one migration version or error code.** The planned wave
>   4 split into **4a** (the three defects) and **4b** (the four planned tasks) instead of a renumber.
>   That is a direct consequence of the correction Rajeev made to this file: the `V95` reallocation
>   swept the tables and not the path contracts, and wave 5 would have written a duplicate `V95` that
>   Flyway refuses at boot. The cheapest way not to repeat it is not to renumber.
> - **One stale index found and fixed while checking the reservations:** the `api.ts` list carried two
>   `W4:` entries and every label after the second was one wave too low, left behind by the earlier
>   wave insertion. No builder was misled — each task's own reservations bullet was right — but the
>   planner's index was wrong. Noted at the foot.

> ## Re-planned again 2026-09-07, after D-13 to D-16
>
> Four decisions landed after wave 2 was dispatched, and they moved more of this file than the last
> re-plan did.
>
> - **Four new tasks, T-031 to T-034**, all ruled by Rajeev on 2026-09-07. Three are in the new wave
>   3 with T-022; the fourth is in wave 4. **D-16** finishes **D-10** (`/my-shifts` narrows to
>   `VOLUNTEER`) and settles that D-10's closing blockquote does not park it; **D-15** adds equipment
>   reinstatement; **D-14** splits the crew-linkage *model* out of T-019 and promotes it; and D-16's
>   closing paragraph names the `/my-schedule` leave gap, which is T-032.
> - **Two waves inserted, so the old waves 4–8 are now 5–9.** Every `**wave:**` line, the waves table
>   and both reservation tables were renumbered with them.
> - **`V95` went to T-034 and the eleven migrations behind it slid up one**, because Flyway applies in
>   ascending order and refuses a version below the highest already applied, and T-034 ships first.
>   T-019's conditional `V105` is gone: its migration *became* T-034's.
> - **`KMS-400124` and `KMS-400125` went to T-033 and T-034**, and the fourteen reserved codes slid up
>   by two. Codes carry no ordering constraint — `ErrorCodeTest` asserts none, checked — so this is
>   the file's own convention that numbers ascend in append order, kept rather than enforced.
> - **`AuditAction.java` is now a reserved file**, on the same evidence that added `Permission.java`
>   last time. See the note under *Path contracts* below.
> - **Question 7 is closed** (D-13, operator only), so T-008's caveat is resolved in its row, and
>   **T-017 is re-scoped and stays blocked** on Rajeev's instruction — see its row.
> - **Three defects found verifying wave 2 on live staging, 2026-09-07.** One was folded into T-033
>   (T-009's scrapping dialog starts lying the day reinstatement ships — the D-4 precedent, same task
>   or nothing). One is recorded against T-031, where it self-resolves (`/my-shifts` sends a cook to
>   `/shifts`, which refuses them). The third is **T-035, new**, in wave 4: a refused page renders
>   with no navigation at all, on **all 82** guarded routes. Everything else in wave 2 passed on
>   staging as the real roles.
>
>
> ~~**Nothing beyond wave 3 may be started without Rajeev.** Wave 4 is planned, not authorised.~~
> **Wave 4 is authorised, 2026-09-07**, after Rajeev verified wave 3 on staging himself and handed
> back four new findings with the instruction to plan and dispatch. It is split into **4a** and **4b**
> — see the note at the head of 4a for why that split, and not a renumber.

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
>    `KMS-400126`; the nine four-digit numbers the first plan proposed never existed and have no
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
allocated flat in declaration order. The highest client number in use was `KMS-400123`; wave 3's two
reservations took it to `KMS-400125`, and wave 4c's one takes it to **`KMS-400126`**.

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
>   successor and never will. They are **re-allocated from `KMS-400126`** in the table at the foot of
>   this file, in wave order, so the numbers ascend in the order they will actually be appended to
>   `ErrorCode.java`. The constant names, HTTP statuses and copy were already reviewed and stand.
>
> **Question 12 is closed.** It asked Rajeev to approve spilling out of the 4900s band. There is no
> band to spill out of.

Batch: **the UAT Docket of 2026-09-06** (`docs/work/intake/2026-09-06-uat-docket.txt`), plus the
**procurement decisions of 2026-09-07** (`DECISIONS.md` D-1, D-2, D-7).
**29 tasks, 10 waves** (`4a` and `4b` count as two). Every task below is `state: queued` except the
shipped ones, and stays that way until Rajeev authorises a wave. Reservations are **proposed**; the work manager writes them into the
shared files in one pass immediately before the wave it belongs to, and not before.

**Path contracts exclude the reserved files by construction.** No builder in this batch may open
`ErrorCode.java`, `Permission.java`, `RolePermissions.java`, **`AuditAction.java`**,
`frontend/lib/api.ts`, `frontend/lib/nav.ts`, `frontend/lib/routes.ts`,
`frontend/components/Sidebar.tsx`, `docs/CHANGELOG.md` or `docs/WORK_QUEUE.md`.

> **`AuditAction.java` is reserved from 2026-09-07, and it is the same gap `Permission.java` was.**
> It was found while planning T-033. The enum is written to be read as a document — every constant
> carries a paragraph saying why the act is worth a temple-wide trace — and **four tasks in this
> batch each append a constant to it**: T-033 (`EQUIPMENT_REINSTATED`), and, when they are built,
> T-012, T-007 and T-014, none of whose contracts name the file. Two builders appending to it
> concurrently corrupts it exactly as two appending to `Permission.java` would, and nothing about
> the corruption is loud: the file still compiles. The three later rows are not amended here because
> their constants have not been designed; **whoever dispatches waves 7 and 8 allocates them the same
> way this wave allocated `EQUIPMENT_REINSTATED`.** Where a task needs something in one of those, it is
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
  the mandatory note the endpoint requires (`NotBlank`, max 500) and showing the resulting reversal
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
  not exist yet:** `GlobalExceptionHandler` is a `RestControllerAdvice` and never sees anything
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
    `import { AccountDisabled } from "/components/RequireRole"` reads oddly. Moving it to its own
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
two files are touched by nothing else until wave 5.

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

### T-017 — A recurring gift a donor can see the next charge of, and stop

- **source:** docket **B9** (INTAKE B9). **Rewritten and re-scoped 2026-09-07 on Rajeev's ruling**,
  after the wave-2 builder stopped on the blocker below: *"holding the whole screen until the webhook
  work happens. They belong together so do it together."*
- **wave:** **none. Blocked, and deliberately not in this batch.** Do not dispatch it.
- **state:** **blocked** *(2026-09-07. Dispatched once in wave 2 and stopped before a line of product
  code; both contract files are still untouched, so re-dispatch costs nothing but the scope is now
  larger than the row it stopped on.)*
- **the two halves, and why they are one task.** Acceptance asked for each plan's **next charge
  date**, and the wave-2 builder proved that value exists nowhere in the stack: `recurring_plans`
  (V42/V43) has no such column; `RecurringPlanView` is `id, frequency, amountInr, status,
  subscriptionId, shortUrl, createdAt` on both sides; and a tree-wide grep for
  `next_charge|nextCharge|charge_at|chargeAt|current_end|currentEnd` across `backend/src`,
  `frontend/lib` and `frontend/app` returns zero. Razorpay holds the real schedule and we never read
  or store it. The builder **could** have derived `createdAt + frequency` — it would typecheck and
  its own test would pass — and refused to, because it is a fabricated date about a live mandate that
  goes silently wrong on a failed cycle, a `HALTED` plan, or provider anniversary drift. Rajeev was
  offered the narrower screen (list, cancel, empty state, *started on* instead of *next charge*) and
  **declined it**: the screen and the value it is supposed to show ship together or not at all.
- **what the one task now covers:**
  1. A `next_charge_at` column on `recurring_plans`, fed from the **Razorpay subscription webhook** —
     `backend/.../payment/PaymentWebhookService.java` and
     `backend/.../donation/RecurringPaymentHandler.java` are where the provider's events already
     land, so the value is stored where the provider says it, never computed.
  2. `RecurringPlanView` and `myPlans` in `backend/.../donation/RecurringDonationService.java` carry
     it through, and `frontend/lib/api.ts`'s `RecurringPlanView` gains the field.
  3. The donor screen: `frontend/app/donate/recurring/page.tsx` *(new)* and
     `frontend/__tests__/recurring-giving.test.tsx` *(new)*, listing the plans the signed-in donor
     holds with amount, frequency, status and next charge, each plan's history, and a cancel with a
     confirmation. Reached from `/donate`; no nav row.
- **also found, and it belongs to this row now:** the server does not refuse a **second** cancel.
  `requireOwned` has no status filter and the UPDATE is idempotent, so a repeat cancel reaches
  Razorpay and comes back as `PAYMENT_GATEWAY_ERROR` — readable, but it names the gateway instead of
  saying the plan has already stopped. `StubPaymentGateway.cancelSubscription` is a no-op returning
  204. The readable refusal comes from the screen.
- **reservations, when it is scheduled:** a migration number **allocated at dispatch, after `V105`**
  — deliberately not reserved now, because an unused number in the middle of a live sequence is a
  trap for whoever schedules the next thing. Probably one error code for the second cancel. No new
  permission. An `api.ts` type change.
- **why it must not drift to the back of the queue.** Until it lands, **a donor can start a recurring
  charge and cannot stop it** — the endpoints and all three client wrappers exist
  (`frontend/lib/api.ts:4816-4832`) and no screen calls any of them. This is the one item in the
  batch where the gap is not an inconvenience but a person's money going out every month with no way
  to turn it off from inside the product.
- **paths:** settled when it is scheduled; the five files named above are the shape of it.
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
  T-027's in wave 6, so a backend-only fix keeps this task off a contended file three waves early.
  Nothing loses a capability — no screen offers clearing a vendor, and regeneration writes vendors
  through its own SQL, never through `updateLine`.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/shoppinglist/ShoppingListService.java` *(the `updateLine` UPDATE only)*
  - `backend/src/test/java/org/iskcon/kms/shoppinglist/ShoppingListIT.java`
- **forbidden:** `frontend/app/shopping-list/page.tsx`, `ShoppingListController.java` and
  `UpdateShoppingListLineRequest.java` — the DTO already carries `suggestedVendorId` as a nullable
  `UUID` and `api.ts` already declares it optional, so **no signature changes anywhere**. The
  controller and the screen are T-027's in wave 6.
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


# Wave 3 — the four things Rajeev ruled today, model first

**Four tasks ruled on 2026-09-07 and given ids here: T-031 to T-034.** Three of them come straight
out of `DECISIONS.md` **D-14**, **D-15** and **D-16**, written after wave 2 was dispatched; the
fourth is the leave gap D-16 names in its closing paragraph. T-022 joins them from the old wave 3
because it writes five brand-new test files and shares nothing with anything.

**The wave was ordered around one serialisation and it cost nothing.** T-032 (`/my-schedule` shows
approved leave) and T-034 (the crew-linkage model) both live in `backend/.../staff/`. Their file
sets are provably disjoint — `StaffScheduleService`/`StaffProfileDetailView` against
`MealMoment`/`WorkforceService`, and `countAt` is called from `MealCrewService` and nowhere else, so
T-034's ripple never reaches `weekView`, which uses `countFor`. They could probably have run
together. They are serialised anyway, because there are eight tasks and a ceiling of four per wave:
**splitting them across two full waves costs zero wall-clock**, and the alternative is betting the
wave on a builder in the staff package resisting a neighbouring file. T-034 goes first because D-14
calls crew-at-the-right-time one of the two things the product must ace, and because T-019 sits on
top of it.

### T-031 — *My shifts* is the volunteer's seva board, and only theirs

- **id:** T-031
- **source:** `DECISIONS.md` **D-16**, ruled by Rajeev 2026-09-07: *"Remember NO My shifts OR Donate
  options for Staff. They are already doing their part."* The second half of **D-10**'s table; T-030
  shipped the first half. D-16 exists precisely to say that D-10's closing blockquote — *"nothing
  here goes into a wave"* — governs D-11 and D-12 and **not** this, which is what stopped the same
  change in wave 1.
- **wave:** 3
- **state:** **SHIPPED** *(2026-09-07 — wave 3, commit `429f63f`. CI verdict, staging revision and digest are in the release report for this wave; not yet certified by observation.)*
- **what:** Exactly the shape of T-030, one screen along. `frontend/app/my-shifts/page.tsx:16` goes
  from `roles={["VOLUNTEER", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}` to `["VOLUNTEER"]`, and the
  `/my-shifts` row in `nav.ts` from `[VOLUNTEER, MANAGER, KITCHEN]` to `[VOLUNTEER]` — the nav half
  is the work manager's and is **already written**. Everything behind that page needs
  `SIGN_UP_FOR_SHIFTS`, which `RolePermissions.java:125-128` grants to `VOLUNTEER` alone, so for a
  cook it is permanently empty; but the reason it goes is D-10's, not that one. It was never theirs.
  Staff lose nothing: `/my-schedule` shipped in wave 2 and is their work schedule.
- **what has to move with it, and it is more than the two lines.** Three test files assert the shape
  that is going:
  - `frontend/__tests__/nav.test.ts:37` — `expect(hrefs).toContain("/my-shifts") // kitchen staff can
    offer seva too` — is now the opposite of the rule and must assert the absence. `:27`, the
    volunteer's own list, is unchanged and must stay unchanged. The comment at `:72` explains the
    old rule and needs to explain the new one.
  - `frontend/__tests__/role-refusals.test.tsx`, section **(3)** at `:249` onwards, renders
    `MyShiftsPage` as `KITCHEN_STAFF` and asserts the role-conditional empty-state copy T-002
    shipped. **That premise is gone** — a cook cannot reach the page at all now — so the section
    asserts the refusal instead. Do not delete the volunteer-facing assertions.
  - `frontend/__tests__/my-shifts.test.tsx` renders as `VOLUNTEER` throughout and should need
    nothing; confirm rather than assume.
  - **T-002's conditional empty state is now dead code, and one branch of it is a live defect.**
    Verified on staging 2026-09-07 as a real cook: the else-branch at `app/my-shifts/page.tsx:75`
    reads *"Shifts are signed up for by volunteers, so this list stays empty for you. Who is covering
    which shift is on the Volunteer shifts screen."* — and opening `/shifts` as that same cook gives
    **"Not your page"**. That is T-002's own acceptance criterion (*"an empty state must not point
    anywhere the reader is refused"*) failing inside the text T-002 itself wrote.
    **It self-resolves here, and the reasoning was checked rather than assumed:**
    `app/shifts/page.tsx:16` is `roles={["VOLUNTEER"]}` and `nav.ts` offers `/shifts` to `VOLUNTEER`,
    so once this guard narrows, every reader who can see that sentence can also open the screen it
    names. **Delete the else-branch** — with the guard narrowed it is unreachable — and keep the
    volunteer branch, which is true and useful. **A test must assert the sentence renders only for a
    role `/shifts` admits**, so this cannot silently come back.
    **If you find any role that can still reach this page and cannot open `/shifts`, the sentence
    goes entirely** and you say so in the proof. Do not leave it standing on the reasoning above
    without checking it yourself.
- **paths:**
  - `frontend/app/my-shifts/page.tsx`
  - `frontend/__tests__/nav.test.ts`
  - `frontend/__tests__/role-refusals.test.tsx`
  - `frontend/__tests__/my-shifts.test.tsx`
  - `frontend/__tests__/routes.test.tsx` *(`homeForRole("VOLUNTEER") === "/my-shifts"` at `:10` is
    unaffected; granted so the check can be made rather than guessed at)*
- **forbidden:** `frontend/lib/nav.ts` — **done, do not open it.** Also
  `frontend/app/my-schedule/page.tsx` and `frontend/__tests__/my-schedule.test.tsx`, which are
  T-032's in wave 4, and `frontend/app/shifts/page.tsx`, which D-10 records as already correct.
- **reservations:** `frontend/lib/nav.ts` — written by the work manager before dispatch:
  `{ href: "/my-shifts", label: "My shifts", icon: "calendar-check", roles: [VOLUNTEER] }`, with the
  comment recording D-16. No migration, no error code, no permission, no `api.ts` change.
- **acceptance:**
  - Rendered as `KITCHEN_STAFF` and as `KITCHEN_MANAGER`, `/my-shifts` refuses; as `VOLUNTEER` it
    renders as it does today.
  - The empty state names no destination the reader is refused — asserted for every role that can
    reach the page, which after this change is one.
  - `nav.test.ts` asserts `/my-shifts` is offered to volunteers and to nobody else.
  - `npx tsc --noEmit` clean, `npm test` green, `npm run build` exports the route.
- **proof:** `docs/work/proof/T-031.md`
- **notes from the build, for whoever reviews it:**
  - **The live staging defect is closed, and the reachability claim was checked rather than taken on
    trust.** The builder read `RequireRole` and confirmed it is a plain `roles.includes(appUser.role)`
    with **no admin or operator bypass** — which is what makes "exactly one role reaches this page"
    true rather than merely likely. The else-branch was deleted as unreachable and the contingency
    ("if any role can still reach it, the sentence goes entirely") was not triggered.
  - **The surviving sentence is asserted as a rule, not as a string.** For each role: render
    `MyShiftsPage`; if the seva sentence appears, render the real `ShiftsPage` for that role and
    assert it is not refused. It reads both live guards, so it fails from either direction — someone
    widening `/my-shifts` later, or narrowing `/shifts`, breaks it. That is better than the
    acceptance criterion asked for.
  - **Mutation-checked.** The old guard was put back, the suite re-run, and the guard restored: four
    tests failed, two of them being the staging defect itself (*"never points KITCHEN_STAFF anywhere
    KITCHEN_STAFF is refused"*). Output in the proof. The tests bite rather than merely pass.
  - `my-shifts.test.tsx` and `routes.test.tsx` needed nothing — confirmed by running them, not
    assumed. `maySignUp` and the now-unused `appUser` went with the ternary.
  - Full suite **952 passing in 90 files**, up from wave 2's 943; `tsc` clean; `next build` exports
    `/my-shifts` at 2.83 kB.
  - **A protocol point the builder self-reported, and it is the protocol's fault rather than the
    builder's.** It read `frontend/lib/nav.ts` and `frontend/components/RequireRole.tsx` — both on
    its forbidden list — **read-only, modifying neither**, and `git status` confirms `nav.ts` carries
    only the work manager's own edit. Reading `RequireRole` was in fact *necessary*: acceptance
    criterion 3 required establishing that the guard has no bypass, and the alternative was to assume
    it. **The contracts should say "may read, must not write" for reserved files**, because a
    reservation exists to stop two agents *writing* one file, and forbidding the read costs accuracy
    for nothing. Worth fixing in the wording before the next wave.
  - **Not smoke-tested by hand, and it cannot be.** The change is only observable as kitchen staff or
    a kitchen manager, and those accounts have no Firebase identity to sign in with — the same wall
    that stopped wave 1, and the same wall `DECISIONS.md` records at its foot.
- **shipped:** `429f63f` — *fix: My shifts is the volunteer's seva board, and staff stop being offered a page that can only be empty*

### T-033 — A scrapped machine can be brought back, once, by name, with a reason

- **id:** T-033
- **source:** `DECISIONS.md` **D-15**, ruled by Rajeev 2026-09-07, answering Question 13 and adding
  to it: *"Scrapped items should not be able to have condition change Or anything done to them.
  Also, if some one accidentally scraps an item OR wants to bring back a scrapped item because they
  cant find a replacement, that should be possible and a reason recorded and visible in the audit
  trail."* **Read D-15 in full before starting** — the design and the reasoning are there.
- **wave:** 3
- **state:** **SHIPPED** *(2026-09-07 — wave 3, one contract widening, commit `a93b3ee`. CI verdict, staging revision and digest are in the release report for this wave; not yet certified by observation.)*
- **what:** Terminality is **not** loosened. `changeCondition` goes on refusing every post-scrap edit
  with `EQUIPMENT_SCRAPPED` **`KMS-400043`**, unchanged, and the guard at `EquipmentService.java:173`
  stays unconditional — that is the whole reason this is a second endpoint and not a flag on the
  first. A request body that can switch a guard off is a guard in name only.
  Add `POST /api/v1/equipment/{id}/reinstate`, taking the condition the item comes back as and a
  **required** reason (`@NotBlank`, max 500, matching `ChangeConditionRequest`). Permitted only from
  `SCRAPPED`; refused otherwise with **`KMS-400124`** `EQUIPMENT_NOT_SCRAPPED`, mirroring
  `EMPLOYMENT_NOT_ENDED`'s shape on T-014.
  **No migration, and this was not obvious.** `equipment_state_changes`
  (`V16__equipment.sql:54-70`) already permits `from_condition = 'SCRAPPED'` — the CHECK lists all
  four conditions on both ends — and already has `reason TEXT NOT NULL`, `actor_user_id` and
  `created_at`. A reinstatement is representable today. Write the state-change row through the same
  `recordStateChange` the condition path uses; do not invent a second trail.
  It audits under its **own** `AuditAction.EQUIPMENT_REINSTATED`, never
  `EQUIPMENT_CONDITION_CHANGED`. Rajeev asked for it to be visible in the audit trail, and a
  reinstatement filed under the same action name as an ordinary repair is visible only to somebody
  already looking for it. The constant is already in `AuditAction.java`.
- **the copy that becomes false the day this ships, and it is this task's to fix — not a follow-up.**
  **Read on staging on 2026-09-07**, triggering T-009's scrapping confirmation on a real item. It
  reads, verbatim:
  > *"Scrap Wet grinder, 10 litre? **Scrapping cannot be undone.** Its condition can never be changed
  > again, and it drops off the equipment list. The record stays on the register with everything
  > written against it, so its history and what it cost are still readable. If the machine is only
  > broken, choose* Needs repair *instead — that one can be taken back."*

  Two of those sentences stop being true the day this ships: **"Scrapping cannot be undone"** and
  **"Its condition can never be changed again"**. The other two are still true and stay.
  **This is the precedent D-4 already set** on T-007, where `MEAL_ALREADY_RECORDED`'s next step had
  to be rewritten by the same task that made it untrue rather than left to a later one. Same rule
  here, and the same reason: a sentence that is false is worse than a sentence that is missing,
  because the reader acts on it.

  **The replacement must still discourage a casual scrapping**, because reinstatement is Temple Admin
  only and deliberately effortful — the way back exists, it is not easy, and the dialog should say
  both. Something close to: *"Scrapping takes it off the register and its condition can no longer be
  changed. Only a Temple Admin can bring it back, and they must record why."* Wording is the
  builder's; those two facts are not. **The* Needs repair *steer at the end is good and survives
  verbatim** — it is the sentence that stops most wrong scrappings before they happen.

  The same claim is made three more times in the file's own prose — the doc comment at `:39-49`, the
  notes at `:398` and `:499`. Rewrite each so it says what is now true: the ordinary condition path
  stays closed to a scrapped item, and there is one named, audited way back that a Temple Admin alone
  can take. **Do not weaken the confirmation itself** — it is what stops the accident this undoes,
  and D-15 says the two are complementary.
- **where the action lives.** The register hides scrapped items by default
  (`list(includeScrapped=false)`), so the way to a reinstatement is through the scrapped filter and
  the item's own page. D-15 calls that the right amount of friction: the reader has to go and find
  the thing they wrote off. Do not add a reinstate control anywhere else.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/equipment/EquipmentController.java`
  - `backend/src/main/java/org/iskcon/kms/equipment/EquipmentService.java`
  - `backend/src/main/java/org/iskcon/kms/equipment/ReinstateEquipmentRequest.java` *(new)*
  - `backend/src/main/java/org/iskcon/kms/equipment/EquipmentDetailView.java` *(granted in case a
    field is genuinely needed; it probably is not — the state-change history already carries the
    reinstatement, and the screen decides on `condition === "SCRAPPED"`)*
  - `backend/src/test/java/org/iskcon/kms/equipment/EquipmentReinstatementIT.java` *(new)*
  - `backend/src/test/java/org/iskcon/kms/equipment/EquipmentIT.java`
  - `frontend/app/equipment/[id]/page.tsx`
  - `frontend/__tests__/equipment-reinstate.test.tsx` *(new)*
  - `frontend/__tests__/equipment.test.tsx`
  - `frontend/__tests__/equipment-edit.test.tsx` *(T-009's, shipped; granted because it asserts the
    scrapping-confirmation copy this task rewords)*
- **forbidden, all six done by the work manager:** `ErrorCode.java`, `Permission.java`,
  `RolePermissions.java`, `AuditAction.java`, `frontend/lib/api.ts`, `frontend/lib/nav.ts`. Also
  `frontend/app/equipment/[id]/edit/page.tsx` and `EquipmentServicingIT.java`, which this does not
  touch.
- **reservations — all written before dispatch:**
  - migration: **none.** Confirmed against `V16__equipment.sql:54-70`.
  - error code: `EQUIPMENT_NOT_SCRAPPED` **`KMS-400124`** (409) — *"This item hasn't been
    scrapped."* / *"There is nothing to reinstate."*
  - **text change, work manager's:** `EQUIPMENT_SCRAPPED` **`KMS-400043`** keeps its number and its
    first sentence; its next step becomes *"Register a replacement, or reinstate this item if it's
    back in use."*, because *"Register a replacement if you've acquired one."* stops being the whole
    truth.
  - permission: **`REINSTATE_SCRAPPED_EQUIPMENT`, `TEMPLE_ADMIN` alone** — confirmed by Rajeev,
    2026-09-07. Declared in `Permission.java` with its paragraph and granted in
    `RolePermissions.java`. **Both files, both done.**
  - audit: `AuditAction.EQUIPMENT_REINSTATED`, with its doc comment. Done.
  - `frontend/lib/api.ts`: `reinstateEquipment(id, { condition, reason }, token)` — stubbed, in the
    file's own style, beside `changeEquipmentCondition`.
- **acceptance:**
  - Reinstating a scrapped item moves it to the condition given, writes an `equipment_state_changes`
    row with `from_condition = 'SCRAPPED'` and the reason, and returns it to the default register
    listing. An integration test on the real database, not a mock.
  - Reinstating an item that is not scrapped returns **`KMS-400124`**.
  - `POST /{id}/condition` on a scrapped item **still** returns `KMS-400043` — asserted, because
    that is the property this task must not break.
  - Reinstating *to* `SCRAPPED` is refused; the condition it comes back as is a live one.
  - `KITCHEN_STAFF` and `KITCHEN_MANAGER` are refused, though both hold `MANAGE_INVENTORY`. Asserted
    — it is the whole point of the new permission.
  - The audit row's action is `EQUIPMENT_REINSTATED`, asserted by name.
  - **No sentence anywhere in `app/equipment/[id]/page.tsx` still claims scrapping cannot be undone
    or that the condition can never change again** — a test asserts the confirmation's new text,
    naming that only a Temple Admin can reverse it and that a reason is recorded. The confirmation
    itself is still there, still fires before the change is sent, and still steers a merely-broken
    machine to *Needs repair*.
  - `./gradlew test` green; `npx tsc --noEmit` clean; `npm test` green; `npm run build` clean.
- **proof:** `docs/work/proof/T-033.md`
- **contract widened mid-wave, and the check that made it safe.** The builder found
  `backend/.../equipment/EquipmentCondition.java`'s javadoc still saying *"`SCRAPPED` is terminal"*
  with no mention of the way back — the same false sentence as the dialog copy, one file along — and
  **stopped at its contract boundary and reported instead of reaching in.** That is the protocol
  working, not a failure. Every contract in this file and both tasks still flying in wave 3 were
  grepped: **nothing else names that file, in this wave or any later one**, so it was added to
  T-033's and the same builder sent back for it. Same case as T-002 in wave 1 — a path contract
  widened onto a file provably nobody else holds, which is the case that rule exists to make safe.
  The reason it is not a follow-up is D-4's precedent, which this task already applies twice over:
  **the task that makes a sentence untrue is the task that rewrites it.** A javadoc reading
  "terminal" is how somebody later re-adds the very guard this task deliberately kept out of
  `changeCondition`.
- **notes from the build, for whoever reviews it:**
  - **The guard is proven to be a property of state, not a one-shot.**
    `EquipmentReinstatementIT.conditionChangeStillRefusedAfterScrapping` scraps → reinstates →
    scraps again and asserts `KMS-400043` on **both sides of the round trip**. That is stronger than
    the acceptance criterion, which only asked that a scrapped item still be refused.
    `changeCondition` is byte-for-byte unchanged.
  - **All four false sentences went, and the test looks in the right place.** The confirmation now
    reads *"Scrapping takes it off the equipment list, and its condition can no longer be changed
    here. Only a Temple Admin can bring it back, and they must record why."* The *Needs repair* steer
    and the record-stays-readable sentence survive verbatim, as instructed. A test asserts the
    forbidden strings are absent from **`document.body`**, not merely from the dialog — so the claim
    cannot have been relocated rather than removed.
  - The scrapped item's page offers *Bring it back* (Temple Admin only) **in place of** *Change
    condition*, since the server refuses the latter with `KMS-400043`. That is T-002's rule applied
    without being asked: a screen should not offer what the server is right to refuse.
  - **No migration**, confirmed against `V16__equipment.sql:54-70` before a line was written.
  - **Verified scoped, deliberately.** 18/18 backend against real PostgreSQL; `tsc` silent; 39/39
    across the three suites that render this page, confirmed by grep to be the complete set;
    `next build` exit 0. The full backend and frontend suites were **not** run inside the wave, and
    should not have been — two other builders were mid-edit, and the lock serialises the verify phase
    but not the edit phase. **The merged-tree run is the work manager's after the wave, and the
    clean-tree `git archive HEAD` run is the release agent's.**
  - **The widening came back comment-only, and it was proven rather than asserted**: 23 insertions /
    3 deletions, and filtering the diff for non-javadoc lines returns nothing. **No enum constant,
    ordinal or name moved** — which matters more than usual here, because these values are persisted
    as *text* in `equipment_items.condition` and `equipment_state_changes` and `V16`'s CHECKs name
    all four. The re-run moved nothing: 18/18 backend with identical test names, 39/39 frontend.
    The javadoc now states both halves in the order that stops either being read alone, and closes
    with the specific failure it exists to prevent — **a later reader "simplifying" by merging the
    guard with the way back is removing it, not tidying it.**
  - **Not smoke-tested by hand.** The path needs a live Temple Admin session, which is the one role
    Rajeev *can* be on staging — so unlike most of this batch, this one is testable by hand and
    wants a walk on UAT: scrap something, find it behind the scrapped filter, bring it back, check
    the machine reappears in the unfiltered register, and check the audit trail reads
    `EQUIPMENT_REINSTATED` with "Scrapped → Needs repair" and the reason. Worth reading the reworded
    confirmation at the width it was seen at on staging.
  - **One thing to confirm in front of the screen, not in the abstract:** `TEMPLE_ADMIN` alone was
    ruled on 2026-09-07 without anybody looking at the finished flow. If a kitchen manager finding
    a mis-scrapped grinder at seven in the morning should be able to undo it, widening the grant is
    one line in `RolePermissions.java`; narrowing it later is a conversation with every temple.
- **shipped:** `a93b3ee` — *feat: a scrapped machine can be brought back, once, by name, with a reason*

### T-034 — A shift says which meal it is for

- **id:** T-034. **Split out of T-019 and promoted**, per `DECISIONS.md` **D-14**'s closing
  paragraph. T-019 keeps the planner UI and stays in wave 9, on top of this.
- **source:** `DECISIONS.md` **D-14**, ruled by Rajeev 2026-09-07, closing Question 9 against the
  recommendation: *"Having enough raw ingredients and enough people at the right time are the two
  main things the Kitchen Management App must ACE… When it comes to the 2 main core things it has to
  ace, we cant leave ANYTHING on the table no matter how hard it is. So explicit link it is."*
  **Read D-14 in full before writing a line.** It carries the design, the trap, and the numbers that
  will move.
- **wave:** 3
- **state:** **SHIPPED** *(2026-09-07 — wave 3, commit `e2916fb`. CI verdict, staging revision and digest are in the release report for this wave; not yet certified by observation.)*
- **what:** Today the planner infers a meal's crew from time-window overlap. That is wrong in **both**
  directions and D-14 is explicit about it: a volunteer signed up 06:00–10:00 to cut vegetables for
  lunch counts toward *breakfast*, because breakfast is what is due at 08:00 — so the clock rule
  misses lunch **and inflates breakfast** with hands committed elsewhere. Give a shift the meal it
  was posted for, and count it there.
- **the link is a natural key, not a foreign key, and this is the part to get right.** **There is no
  meal table.** `ServedMeal`'s own doc says so: one `meal_plans` row is one dish, and a lunch of
  three dishes is three rows sharing a date, kind, head count and ready-by. A meal is an inference
  `ServedMealService.list` assembles by grouping dish rows on `Key(plan_date, meal_kind, event_name)`.
  `meal_services` exists and is **null exactly when it is needed** — `ServedMeal.serviceId` is
  documented as null *"when the meal has neither been carded nor recorded"*, and a shift is posted
  while planning, weeks before carding. So `V95` carries `meal_date`, `meal_kind` and
  `meal_event_name` on `shifts`, all nullable, with a `CHECK` making date and kind all-present or
  all-absent. No FK is possible for the date or the event name; `meal_kind` may reference
  `meal_kinds` only if that table's key allows it — check, do not assume.
- **the normalisation trap, verbatim from D-14, and it fails silently.** `ServedMealService.Key.of`
  (`:542-547`) normalises the event name: **null or blank becomes `""`, otherwise
  `trim().toLowerCase(Locale.ROOT)`.** Matching a linked shift to a meal must apply the identical
  rule. Get it wrong and the link matches nothing, the count reads zero, and it looks exactly like a
  shift nobody signed up for. `Key` is private to `ServedMealService`, which is **out of this
  contract** — so replicate the rule, and **normalise once, in `MealMoment`'s canonical
  constructor**, so it cannot be got wrong at a call site. Say in the proof where you put it.
- **the two rules.** A **linked** shift counts toward its meal and toward **no other**, whatever the
  clock says. An **unlinked** shift keeps today's behaviour exactly: it counts toward every meal
  whose ready-by its window spans, both ends inclusive. That second rule is not a compatibility hack,
  though it does protect every existing row: an unlinked shift is a general offer of hands matched by
  the clock, a linked shift is hands committed to one meal, and a temple says both. It is also what
  keeps a 06:00–22:00 festival shift counting toward all three meals, which is correct.
- **what it costs, and where it ripples.** `MealMoment(date, readyBy)` — in the `staff` package
  deliberately, *"it is the question the roster is asked"*, and it **stays there** — gains the meal's
  kind and event name. That ripples into `WorkforceService.countAt` (both overloads and
  `volunteersAt`, `:119-168`) and into `MealCrewService.crewFor`, `crewIfAway` and `momentOf`
  (`:64, :89-95, :194-203`), which is the only caller of `countAt` anywhere. `countFor` is untouched,
  so `TodayService`, `CrewCoverageService`, `WorkforceController` and `StaffScheduleService.weekView`
  are all unaffected — **verified, not assumed**; if you find otherwise, stop and report.
- **expect breakfast to fall, and assert it deliberately.** Where a lunch-prep shift overlaps
  breakfast, breakfast's volunteer count drops once those shifts are linked. That is the over-count
  being corrected, not a regression. A test should record it on purpose — post one shift
  06:00–10:00 linked to lunch, assert breakfast counts zero of it and lunch counts all of it, and
  assert that the same shift left unlinked counts toward breakfast as it does today.
- **no frontend.** The planner affordance is T-019 in wave 9. `api.ts` already carries the optional
  fields (work manager's reservation) so T-019 has something to build against; nothing in
  `frontend/app` changes here.
- **paths:**
  - `backend/src/main/resources/db/migration/V95__a_shift_says_which_meal.sql` *(new)*
  - `backend/src/main/java/org/iskcon/kms/shift/CreateShiftRequest.java`
  - `backend/src/main/java/org/iskcon/kms/shift/UpdateShiftRequest.java`
  - `backend/src/main/java/org/iskcon/kms/shift/ShiftView.java`
  - `backend/src/main/java/org/iskcon/kms/shift/ShiftService.java`
  - `backend/src/main/java/org/iskcon/kms/staff/MealMoment.java`
  - `backend/src/main/java/org/iskcon/kms/staff/WorkforceService.java`
  - `backend/src/main/java/org/iskcon/kms/meal/MealCrewService.java`
  - `backend/src/test/java/org/iskcon/kms/shift/ShiftMealLinkIT.java` *(new)*
  - `backend/src/test/java/org/iskcon/kms/shift/ShiftIT.java`
  - `backend/src/test/java/org/iskcon/kms/meal/MealCrewIT.java`
  - `backend/src/test/java/org/iskcon/kms/meal/MealCrewDefaultTest.java`
  - `backend/src/test/java/org/iskcon/kms/staff/CrewCoverageIT.java` *(granted defensively; it goes
    through `countFor` and should need nothing)*
  - **no `**` glob anywhere.** This task is in three packages and names every file in each.
- **forbidden, and each for a reason:** `staff/StaffScheduleService.java` and
  `staff/ScheduleResolver.java` — T-032's package in wave 4, and the resolver is not part of this
  question; `meal/ServedMealService.java` — T-007's in wave 8, and the `Key` rule is to be
  replicated, not imported; `shift/SignupService.java` — T-016's in wave 8; `ErrorCode.java`,
  `Permission.java`, `RolePermissions.java`, `AuditAction.java`, `frontend/lib/api.ts`, all done.
- **reservations — all written before dispatch:**
  - migration: **`V95`**. Allocated here and not from the wave 5-9 block **because Flyway applies in
    ascending order and refuses a version below the highest already applied** — this ships before
    every one of them, so it takes the next free number and the eleven behind it slid up one. See the
    migration table. `shifts` is tenant-owned, so the migration respects the existing RLS and
    backfills per tenant if it backfills at all. It should not need to: every existing row is
    unlinked, which is the correct default and is what the three nullable columns already say.
  - error code: `SHIFT_MEAL_LINK_INCOMPLETE` **`KMS-400125`** (400) — *"A shift linked to a meal
    needs the date and the meal kind together."* / *"Give both, or leave the shift unlinked so it
    counts by its hours."* The database `CHECK` is the backstop; this is what the caller reads.
  - permissions: none new — `MANAGE_VOLUNTEER_SHIFTS`, as `createShift` already is.
  - `frontend/lib/api.ts`: `mealDate`, `mealKind`, `mealEventName` added to `ShiftView` and to
    `ShiftInput`, all three **optional**. Done; nothing in `frontend/app` reads them yet. Optional
    rather than required-nullable because every existing `ShiftView` fixture is built by hand and
    required fields would have forced edits into `volunteer-shifts.test.tsx`, which is nobody's this
    wave. The server still sends all three on every row.
- **acceptance:**
  - A shift posted with a meal date and kind saves and reads back with them; one posted with a kind
    and no date returns **`KMS-400125`** and writes nothing.
  - A linked shift counts toward its own meal and toward no other, asserted against a day carrying
    at least two meals — including the breakfast case above, asserted as a deliberate decrease.
  - An unlinked shift counts exactly as it does today. Every existing crew test still passes on its
    original numbers, or a changed number is explained in the proof.
  - The event-name normalisation matches `Key.of` — a test links a shift to an event meal whose name
    differs only in case and surrounding whitespace and asserts it still matches.
  - `./gradlew test` green, including a boot that applies `V95`.
- **proof:** `docs/work/proof/T-034.md`
- **notes from the build, and two of them changed the shape of the task:**
  - **The normalisation went into `MealMoment`'s canonical constructor**, in a private `fold(String)`
    — null/blank → `""`, otherwise `trim().toLowerCase(Locale.ROOT)` — plus `MealMoment#isFor(...)`,
    which folds the *link* it is handed before comparing. **Both sides of every comparison pass
    through the record**, so there is no call site at which the rule can be forgotten. `Key.of`'s
    rule was replicated, not imported, as the contract required.
  - **A silent failure found and closed that the task description did not anticipate, and it was the
    exact one D-14 warns about.** `countAt` fetched shifts by a range built from the *meals'* dates,
    so a shift posted for "grind masala on Thursday for Sunday's feast" would have **loaded no shift
    at all** and read **zero on precisely the shift somebody had linked**. The builder added
    `ShiftService.listCountingTowardMeals(from, to)` — one new method, inside its contract — whose
    query matches `shift_date IN range OR meal_date IN range`. The alternative, widening the range by
    a fixed ±N days, is a guess about how far ahead a temple prepares. **This is the task's most
    valuable finding**: without it the feature would have shipped looking correct and reading zero in
    the one case it exists for.
  - **No FK on `meal_kind`, checked rather than assumed** — the contract said to check.
    `meal_kinds` is unique on `(tenant_id, lower(name))`, an **expression** index, and PostgreSQL
    will not accept one as an FK target. Recorded in the migration header where the next reader will
    find it.
  - **A sanctioned deviation, and the two findings interlock.** It folds the **kind** as well as the
    event name, which `Key.of` does not. That is safe *because* of the finding above: since
    `meal_kinds` is unique on `lower(name)`, two kinds differing only in case **cannot exist in one
    temple**, so folding cannot create a false match — and it closes `"lunch"` vs `"Lunch"` counting
    toward nothing. Strictly a superset of exact equality.
  - **The breakfast decrease is asserted as a deliberate before/after**, in the order the mistake
    happens — the same shift, unlinked, is asserted to land on breakfast (today's wrong behaviour)
    and then, linked, to leave it:

    | | Breakfast 07:30 | Lunch 12:00 |
    |---|---|---|
    | 06:00–10:00 **unlinked** | 1 | 0 |
    | same shift **linked to Lunch** | **0** | **1** |

  - **No existing test was edited and no existing number moved.** `MealCrewIT`, `CrewCoverageIT`,
    `ShiftIT`, `MealCrewDefaultTest`, `TodayIT`, `StaffScheduleIT`, `VolunteerSignupIT`,
    `RowLevelSecurityIT` and `TenantLoopMigrationIT` all pass as they stood. `V95` is proven applied
    by a raw-SQL test that inserts a half-link **past the service** and asserts the failure names
    `shifts_meal_link_complete` — so the database constraint is proven, not just the Java guard.
  - **Two flags for whoever takes T-019.** `ShiftIT`'s *"rewrites every field"* test no longer covers
    every field, which is a small coverage regression worth closing there. And **a link to a
    not-yet-planned meal counts toward nothing, silently** — intended, but whether the planner should
    warn that a link matches no meal is a product decision, and it belongs to the planner task rather
    than to the model.
  - **No hand smoke test was possible**, and for once that is not the sign-in wall: this task adds no
    user-facing surface at all. Nothing in `frontend/app` reads the fields until T-019.
- **shipped:** `e2916fb` — *feat: a shift says which meal it is for, so the crew count stops guessing from the clock*

### T-022 — Tests for the five screens that have none

- **source:** docket **P9** (INTAKE P9) — the only one of the nine "never proven" items that is
  actually a build task.
- **wave:** 3
- **state:** **SHIPPED** *(2026-09-07 — wave 3, commit `a29c654`. Test-only; nothing to certify by observation.)*
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
- **proof:** `docs/work/proof/T-022.md`
- **notes from the build:**
  - **The contract held exactly**: six new files, **no product file touched**, and the proof carries
    a `git status --porcelain` scoped to the contract's paths *and* an unscoped one showing the other
    builders' work in flight, so the two can be told apart by a reviewer. **53 tests in five files**,
    `tsc` silent, `next build` 67/67 pages, exit 0.
  - None of the five filenames existed beforehand — **checked first, as instructed**, rather than
    overwritten. The nearest thing was `google-account-chooser.test.tsx` (T-029's), which renders the
    register page for one assertion about one OAuth parameter and is not a test of the screen.
  - The one intermediate failure was the builder's own: `HintedField` renders an `InfoHint` button
    named *"More about Create a password"*, so a case-insensitive regex matched two elements. **Fixed
    in the test, not in the product**, which is the rule this task was given.
  - **It found a real defect and did not fix it**, which is exactly what characterisation tests are
    for. See *For Rajeev — found while building wave 3* below. The defect is asserted **as it
    currently behaves**, under a test name that says so, with a comment block recording that it
    documents a defect rather than endorsing it — so whoever fixes it must come back and change what
    the test claims. That is the right way to leave it.
  - The docket's P9 superlative — *"the only screen with no test"* — is **confirmed wrong**, as the
    row predicted. Four other screens had none.
- **shipped:** `a29c654` — *test: the five screens that had no tests get them, and registration's dead end is written down*

---

## Wave 3, as it actually ran — 2026-09-07

Four builders concurrently. **No builder wrote a file outside its contract** — verified against
`git status` after each returned, and the tree holds exactly the union of the four contracts plus the
work manager's reservations. **One contract was widened mid-wave**, onto a file provably nobody else
held; that is recorded on T-033.

**All four proven.** Every proof carries real pasted command output, run through
`tools/work-lock.sh`. The lock was visibly doing its job — T-033's re-verify queued five seconds
behind T-034's `ShiftIT`/`MealCrewIT` run.

**Checked on the merged tree afterwards**, because green inside a wave is not green on the merged
tree — wave 1's lesson under T-001, and the reason every builder this wave was told to run *scoped*
suites and leave the whole-project run alone. With all four builders' work in one checkout:

- `./gradlew test` — **1735 total, 1733 passed, 0 failed, 2 skipped. BUILD SUCCESSFUL.**
- `npx tsc --noEmit` — clean.
- `npx vitest run` — **1017 tests in 96 files, all passing** (943 after wave 2; T-022's 53 and
  T-033's new suite account for the rest).
- `npm run build` — compiled, 67/67 static pages, with `/my-shifts` and `/equipment/[id]` exporting.

**Three deviations to sanction, all argued before the fact rather than after:**
1. T-034's `ShiftService.listCountingTowardMeals` — a new method nobody asked for, closing a silent
   zero-count the task description had missed. See its row.
2. T-034 folding the meal *kind* as well as the event name, which `Key.of` does not — safe because
   `meal_kinds` is unique on `lower(name)`.
3. T-033's *Bring it back* replacing *Change condition* on a scrapped item's page, rather than
   sitting beside it. T-002's rule applied without being asked.

**A protocol wording fix, found by T-031 and owed to the builders rather than by them.** Contracts
said reserved files were "forbidden". Two builders read one read-only — modifying neither — and
**one of those reads was necessary**: T-031's acceptance required establishing that `RequireRole` has
no admin bypass, and the alternative was to assume it. A reservation exists to stop two agents
**writing** one file. From wave 4 the wording is **"may read, must not write"**.

**What none of this proves.** Not one of the four was smoke-tested by hand. T-034 and T-022 add no
user-facing surface, so there is nothing to press. T-031's change is only observable as kitchen staff
or a kitchen manager, and those accounts still have no Firebase identity — the wall at the foot of
`DECISIONS.md`. **T-033 is the exception and is worth a walk on staging**, because reinstatement is
Temple Admin's and Temple Admin is a role Rajeev can actually be.

---

# Wave 4a — the three defects Rajeev found on staging

Rajeev drove the live site as the real roles after `527b23b` and found four things. Three are built
here; the fourth was already resolved by T-031 and is struck through in the findings section below.
All three are **frontend-only, need no reservation of any kind, and share no file** — the first wave
in this batch where the work manager writes nothing into a shared file before dispatch.

> **Why this is `4a` and not a new wave 4, with the old waves 5–9 pushed to 6–10.** Because a
> renumber is the one edit in this file that has already gone wrong once. When `V95` was reallocated,
> the table and the `migration:` bullets were swept and **the migration filenames in the path
> contracts were not** — every task from T-023 onward named the version below its allocation, and
> wave 5 would have written a duplicate `V95` that Flyway refuses at boot rather than in a diff.
> Splitting the planned wave into `4a` and `4b` gets the same ordering for free: **not one migration
> version, error code, permission or wave label anywhere below this line moves.** `V96`–`V105` and
> `KMS-400126`–`KMS-400139` keep the tasks and the wave numbers they already have. The lesson from
> that correction was *sweep the path contracts too*; the cheaper lesson is *don't renumber what you
> don't have to*.
>
> *Neither half held, in the end, and the way each broke is the instructive part. The **migration
> versions did move**, though not for the reason anyone expected: T-038 chose a design that needed
> none, and then the coordinator ruled that V64's now-false column comment be corrected by a
> fix-forward — so T-047 took `V96` and `V96`–`V105` slid to `V97`–`V106`, filenames included. The
> **error codes moved once more** too: T-038 landed in 4c, needed a code, took `KMS-400126`, and the
> fourteen behind it slid to `KMS-400127`–`KMS-400140`. That is the third slide of the same fourteen
> numbers in one batch, and it is now clear that "don't renumber what you don't have to" is not a
> rule this batch can keep while tasks keep arriving ahead of waves already planned. What keeps it
> safe is a property the migrations do not have: a slid code is wrong in this file and nowhere else,
> and every builder is handed its code verbatim in its brief. A slid migration version is wrong in a
> filename a builder types, and Flyway refuses to boot rather than showing a diff.*

**Why these three run together, and ahead of the planned wave.** They are defects on screens Rajeev
is verifying right now, they are small, and they are all in the browser — no Gradle, so the verify
lock is barely contended. Running them first also removes a trap the wave table had already flagged
for the planned wave: **T-035 changes what every refusal renders**, and T-005, T-008 and T-018 all
write fresh page tests on guarded routes. One wave apart, T-035 is committed first and the four build
on it, instead of asserting against a shape moving underneath them.

### T-035 — A refused page still has a way out of it

- **id:** T-035
- **source:** Rajeev, 2026-09-07, found verifying wave 2 on live staging, and **confirmed on a fourth
  surface on 2026-09-07** after the wave-3 deploy. Not from the docket, not on
  `OUTSTANDING_BUILD_LIST`, not in `WORK_QUEUE` — so **closing it closes nothing elsewhere**. It is
  the natural companion to T-002: that task stopped screens *offering* what the server refuses; this
  one stops the refusal itself being a dead end.
- **wave:** 4a — moved out of the planned wave, which is now 4b. See the note at the head of 4a.
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4a, commit `f091745`. CI verdict is in the release report for this wave. **Not deployed by the release agent** — `deploy.sh` and even a read-only `gcloud run describe` were refused to subagents, so staging was the main session's to do, exactly as it was for wave 3. **No longer true from wave 4b on:** Rajeev added scoped allow rules and the release agent deployed wave 4b itself. Do not hand a deploy back to the main session on the strength of this sentence. Not yet certified by observation.)*
- **what:** Opening `/donate` as `KITCHEN_STAFF` renders *"Not your page / You don't have access to
  this part of the app. Ask your temple administrator."* on a **bare white page with no sidebar and
  no link anywhere**. The only way out is the browser's back button. Rajeev has now seen it on
  **four** surfaces — `/donate`, `/shifts` and `/my-shifts` as `KITCHEN_STAFF`, and the
  disabled-account screen (`KMS-400019`) — and his instinct that it would repeat everywhere was right.
- **the survey, which is what decides the shape. Corrected 2026-09-07 by the builder, and the
  correction matters to the words but not to the fix.** Of the **82** page components that use
  `RequireRole`: **54 put `<Sidebar>` *inside* `<RequireRole>`**, **24 render `FocusScreen`**, and
  **3 delegate to a component that draws one**; **not one puts a sidebar outside the guard.**
  My original row said the 28 `FocusScreen` pages *"render no sidebar at all"*. **That was wrong** —
  the grep behind the number looked for `<Sidebar>` in the page file, and `FocusScreen` renders one
  itself (`frontend/components/ds/FocusScreen.tsx:51`; rule 2 of the eight it enforces is *"the
  sidebar stays"*). Checked independently against the tree, not taken on trust.
  **The load-bearing half of the survey holds exactly as written:** every one of those sidebars sits
  *below* the guard, so a refused reader reached none of it, all 82 strand that reader today, and
  there is no double sidebar anywhere to fear. The fix's shape is unchanged.
- **the fix, and why this shape rather than the other.** Rajeev named two options: render the refusal
  inside the app chrome, or give the refusal screen an explicit link to Today. **Do both, in
  `RequireRole.tsx` alone.** The survey makes the first one cheap and safe: because no page renders
  `<Sidebar>` outside the guard, `RequireRole` can render the chrome around its own refusal branch
  with **no risk of a double sidebar anywhere**, and it fixes all 82 routes in one file — including
  the 28 chromeless ones, which strand a reader worst of all, and including every guarded route built
  after today, which is the half a per-page fix cannot reach. Editing 54 pages would be the same
  change 54 times and would still let the 55th reintroduce it.
  The explicit link to Today goes in as well, and is not redundant: the sidebar may be collapsed
  behind a control on a narrow viewport, and a person who has just been told they are in the wrong
  place should not have to find a menu first.
- **the disabled-account screen gets a sign-out, and it is the worst of the four.** Rajeev:
  *"no sign-out, so a disabled person cannot even switch accounts and the tab is dead."* `AccountDisabled`
  is declared **in this same file** (`RequireRole.tsx:35-47`) and today has no button at all — its
  own comment says *"there is no button and no retry — only who to ask"*. That comment was right
  about chrome and wrong about sign-out, and the distinction is the point: **a sign-out is not
  chrome.** The reasoning for keeping that branch chromeless — *"nothing they can do on any screen
  will help until it is given back"* — is exactly the argument that the one useful action left is
  leaving. A shared machine, a second account, a person disabled at one temple and active at another:
  all of them are stuck in a dead tab today. Use the app's own `signOut` from
  `lib/auth-context.tsx:259-269`; `choose-temple/page.tsx:52` is the worked example and calls it
  *"Use a different account"*.
- **the two neighbouring branches stay chromeless, deliberately, and adding a sign-out does not
  change that.** `AccountDisabled` and `ServerUnreachable` gain no sidebar. A disabled person cannot
  use any screen the sidebar would offer, and drawing a working menu over an unreachable API is
  decoration over a dead app. Only the wrong-role branch gains chrome, because it is the only one of
  the three where the rest of the application still works for the person reading it. **Say this in
  the code**, or the next reader will make all three consistent and undo it. `ServerUnreachable` keeps
  its "Try again" and gains nothing — signing out of an app that cannot reach its server helps nobody.
- **not in this task, and this is deliberate:** the reinstatement form's required-reason check
  surfacing as the browser's native validation bubble. It is the same family — how a refusal is
  presented — and it is **still not this task's**, for two reasons. It lives in
  `frontend/app/equipment/[id]/page.tsx:687`, and **every file under `frontend/app/` is forbidden
  here**; breaking that for one input is how a one-file task becomes a fifty-file one. And it is not
  a wave-3 regression: the same screen uses a raw `required` in **three** places (`:513`, `:687`,
  `:764`), so it is the file's existing convention, not something T-033 introduced. See the finding
  recorded below.
- **paths:**
  - `frontend/components/RequireRole.tsx`
  - `frontend/__tests__/refusal-has-a-way-out.test.tsx` *(new)*
  - `frontend/__tests__/session-failures.test.tsx` *(exists — extend if the branch assertions need it)*
  - `frontend/__tests__/role-refusals.test.tsx` *(T-031's, shipped in wave 3; granted because it is
    where the refusals of four screens are asserted)*
- **forbidden, and this is the whole discipline of the task:** **every file under `frontend/app/`.**
  If the fix needs a page edit, the shape is wrong — stop and report rather than starting down 54
  files. Also `frontend/components/Sidebar.tsx` and `frontend/lib/nav.ts`, both reserved and neither
  needing a change; `frontend/components/ServerUnreachable.tsx`; and `frontend/lib/auth-context.tsx`
  — **read `signOut`, call it, do not change it.**
  **Two pages are being edited beside you in this wave** — `frontend/app/inventory/[id]/page.tsx`
  (T-036) and `frontend/app/register/page.tsx` (T-037). You may not edit either, and when you pick a
  page to prove "no double sidebar" against, **pick neither of those two**; any of the other 52 will
  do. (`register` has no guard at all, so it could not serve anyway.)
- **reservations:** **none.** No migration, no error code, no permission, no `api.ts` change, no nav
  row. Like T-028, this task needs nothing from the work manager.
- **a widening was offered mid-wave and the builder handed it back, 2026-09-07 — and it was right to.**
  T-036's full-suite run showed the shipped wave-2 `frontend/__tests__/occasions.test.tsx` failing
  against T-035's in-progress `RequireRole.tsx`. I checked ownership (T-036 held the inventory page
  and its test, T-037 the register page and its test; nobody held this one), granted the file, and
  **diagnosed the cause wrongly** — I read the wholesale `vi.mock("@/lib/auth-context")` at `:44` and
  concluded a guard newly reaching for `signOut` was getting a mock that never provided one. It was
  not that: that test's status is `signed-in`, so `AccountDisabled` — the only branch that reads
  `signOut` — never renders. **The real cause was in the builder's own file**, and it is a genuine
  defect rather than a test artefact. The refusal branch's link out points at `homeForRole(role)`
  rather than a fixed `/today` (a volunteer is refused `/today` as surely as a cook is refused
  `/donate`, so a fixed link would have recreated T-002's defect). `occasions.test.tsx` loops over
  five roles including `ACCOUNTANT`, which is **not in `PrincipalRole`**, so `homeForRole` fell off
  its exhaustive switch, returned `undefined`, and blanked the refusal screen —
  *"Failed prop type: The prop 'href' expects a 'string' or 'object' in `<Link>`, but got
  'undefined'"*. The day a role is added to the server ahead of a frontend deploy, the one screen a
  mismatched reader lands on would have been the screen that breaks. `WrongRole` now falls back to
  `/`, the landing router, which knows what to do with anybody. `occasions.test.tsx` is **untouched
  and green** — confirmed against `git status`, not taken on trust.
  **The lesson is mine, not the builder's:** a work manager diagnosing from a grep of a test file is
  guessing, and a grant justified by a wrong cause is still a grant. The ownership check was the part
  that was worth doing; the diagnosis should have been left to whoever was in the file.
- **acceptance:**
  - Rendered as a role the guard refuses, the page shows the refusal **and** the sidebar **and** a
    link to Today — asserted through the route, not by calling the component's branch directly.
  - A refused reader on a `FocusScreen` route (one of the 28 with no sidebar of its own) gets the
    same way out.
  - **The disabled-account screen offers a sign-out that calls the app's own `signOut`**, asserted
    with the real branch, and a disabled reader can therefore reach `/sign-in` and use another account.
  - **No page renders two sidebars** — asserted on at least one of the 54, because that is the one
    way this fix could break a screen that works today.
  - `ServerUnreachable` is unchanged and still renders without chrome and without a sign-out, and
    `AccountDisabled` still renders without a sidebar — each with a test saying so on purpose, so a
    later tidy-up cannot quietly make all three alike.
  - An allowed role sees no change at all.
  - `npx tsc --noEmit` clean, `npm test` green, `npm run build` clean.
- **proof:** `docs/work/proof/T-035.md`
- **shipped:** —

### T-036 — The correction dialog says the unit and the month back wrongly, and offers itself on a movement already corrected

- **id:** T-036
- **source:** Rajeev, 2026-09-07, findings **2 and 3** of the staging verification of waves 1–3;
  recorded in *"Found while verifying waves 1 and 2 on staging"* below. Both are on the screen T-001
  built, and finding 3 is the defect class T-002 existed to remove. Neither is on
  `OUTSTANDING_BUILD_LIST` or in `WORK_QUEUE`, so closing this closes nothing elsewhere.
- **wave:** 4a
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4a, commit `649eb97`. CI verdict is in the release report for this wave. **Not deployed by the release agent**, for the reason on T-035's row. Not yet certified by observation.)*
- **why the two findings are one task, and one id.** Rajeev listed them separately and they are
  separate defects, but they are **the same file and the same pair of components** —
  `MovementHistory` (`:325`, table body `:397-449`) and `CorrectMovement` (`:485`), both local to
  `frontend/app/inventory/[id]/page.tsx`, which has no separate dialog component to split off. Two
  ids would mean two builders in one file, which is the single thing this whole arrangement exists to
  prevent; two waves would spend a full build-and-deploy cycle on a three-line fix. The person fixing
  the lowercasing is already reading the component that renders the button. **Merged deliberately,
  and reported as merged.**
- **what — finding 2, the lowercasing.** The dialog reads *"The adjustment of +1.8 kg on 23 aug 2026"*
  where the table directly above renders *"+1.8 Kg"* and *"23 Aug 2026"*. The cause is exact:
  `summary` is built at `:501-504` as `` `${TYPE_LABEL[...]} of ${quantity(...)} on ${moment(...)}` ``
  — already-formatted unit and date inside it — and both call sites lowercase **the whole string** to
  make the leading type label read mid-sentence: `:569` (*"The {summary.toLowerCase()} stays in the
  ledger…"*) and `:545` (*"…marked as a correction of {summary.toLowerCase()}."*). The intent was the
  type label; the unit and the month abbreviation are collateral. Units carry meaning here
  (`Kg`/`gm`/`L`), so it is not cosmetic.
  **This trap is already written down in this codebase and was reintroduced anyway.**
  `frontend/components/planner/MealComposer.tsx:1362` records that `toLowerCase()` rendered litre
  `"L"` as a digit-like `"l"`, and that unit labels are therefore printed through `unitLabel()`.
  Quote that precedent in whatever you leave behind, so the third occurrence has something to find.
  **Fix it at construction, not at the call sites** — lowercase only the type label where `summary`
  is assembled (or keep the label and the rest as two values) and delete both `.toLowerCase()` calls.
  `:423` lowercases only a `TYPE_LABEL` with the date outside the call and is **correct as it stands**
  — leave it alone. A sweep of the rest of `frontend/` found no third instance that corrupts a unit or
  a month; every other `toLowerCase` is on an enum, a slug, a search key or an aria label.
- **what — finding 3, the control that is offered and then refused.** The row already knows the
  answer. `reversedBy = byOriginal.get(m.id)` at `:402` is what draws the *Corrected* badge at
  `:429-433`, and the *Correct* button at `:438-443` is rendered **unconditionally on every row**,
  three lines below it. So a person reads *Corrected*, presses *Correct*, writes out a reason, and is
  then told no by the server — `MOVEMENT_ALREADY_CORRECTED` **`KMS-400039`**
  (`StockMovementService.java:158-161`). The fix is a guard on a value already in scope.
  **The server-refusal dialog stays.** It is reachable and correct whenever a second reader corrects
  the same movement in another tab, and the screen renders it readably today (`:33` holds the code,
  `:530-556` the branch). This task removes the *ordinary* way of reaching it, not the branch — deleting
  the branch would trade a rude screen for a broken one. Say so where you add the guard.
- **the one judgement to make and to state in the proof:** whether a corrected row shows nothing in
  its actions column or a disabled control, and if a disabled control, what it says. Both are
  defensible; T-002 shipped five of these and its precedent governs — follow whatever it did, and if
  it did more than one thing, say which you copied and why.
- **paths:**
  - `frontend/app/inventory/[id]/page.tsx`
  - `frontend/__tests__/inventory-correction.test.tsx`
- **forbidden:** every other file under `frontend/app/`; `frontend/lib/format.ts` — **read
  `quantity()`, `unitLabel()` and `moment()`, do not change them**, they are correct and are shared by
  every screen in the product; `frontend/lib/api.ts` (reserved, and needs nothing — `referenceType`
  and `referenceId` at `:664-665` are all the frontend needs and both already exist); the whole of
  `backend/` — **the server is right and is not this task's.** `frontend/__tests__/inventory.test.tsx`
  and `inventory-new.test.tsx` are not yours either.
- **reservations:** **none.** No migration, no error code, no permission, no `api.ts` change, no nav
  row.
- **acceptance:**
  - The dialog prints the unit and the date **exactly as the table above it does** — a test asserting
    `Kg` and `Aug` in the dialog's own sentence, not merely that some lowercase call is gone. Cover a
    litre movement too, because `L` → `l` is the case the `MealComposer` note singles out.
  - The leading type label still reads mid-sentence: the sentence must not become *"The Adjustment
    of…"*.
  - A movement carrying a *Corrected* badge **does not offer a Correct control**, asserted on the same
    fixture that already asserts the badge (`inventory-correction.test.tsx:187`).
  - A movement with no correction against it still offers it, and the existing correction round trip
    still passes untouched.
  - The already-corrected refusal dialog still renders readably when the server returns
    `KMS-400039` — a test proving the branch survived.
  - `npx tsc --noEmit` clean, `npm test` green, `npm run build` clean.
- **proof:** `docs/work/proof/T-036.md`
- **shipped:** —

### T-037 — Registration remembers the credential it made and resumes at the join

- **id:** T-037
- **source:** Rajeev, 2026-09-07, finding **4**, ruling on the decision T-022 raised and left open —
  *"For Rajeev — found while building wave 3"*, item 1, below. T-022 wrote it up as a passing
  characterisation test rather than fixing it, exactly as it was told to.
- **wave:** 4a
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4a, commit `b0dcdf2`. CI verdict is in the release report for this wave. **Not deployed by the release agent**, for the reason on T-035's row. Not yet certified by observation.)*
- **what:** `createAccount()` (`frontend/app/register/page.tsx:71-129`) creates the Firebase
  credential **before** calling `api.joinTemple` (`:98-107`). A refused join leaves the account behind
  with nothing to remove it and nothing to remember it — there is no cleanup in the `catch`
  (`:123-128`). The second press then hits `auth/email-already-in-use` and the screen answers
  *"There is already an account with that email. Sign in instead."* (`readableFirebaseError`,
  `:344-357`, the string at `:348`) — **advice that is wrong for this person**, who now has an
  identity and a membership nowhere. The join is never retried and the flow is a dead end. It is
  recoverable only by accident: signing in gives `whoami` a 401, which `RequireRole` bounces to
  `/choose-temple`. Nothing tells them that and nobody would guess it.
- **the shape is Rajeev's, and he took the recommended one.** *"Remember the credential across
  attempts and resume at the join."* **Say in the proof why the other two were not built**, because
  the next reader will ask:
  - **Compensating-delete the Firebase user** — the most destructive of the three and the hardest to
    get right. Deleting an account when the delete itself may fail leaves somebody worse off than the
    bug does, and the account may legitimately be theirs.
  - **Reserve the temple first, create the credential last** — the largest change, and probably not
    buildable: `POST /api/v1/temples/{id}/join` needs an authenticated uid
    (`AuthenticationFilter.java:123,129-134` admits a verified uid with no membership precisely so
    this flow works), so the identity genuinely has to exist first.
  - The chosen shape matches what actually happened — **their identity is fine and only the
    membership is missing** — deletes nothing, and is the same shape as the claim-on-match design
    already in the product.
- **it applies to all three branches, and they are not three functions.** Google (`:81-86`,
  `signInWithPopup`), password (`:87-88`, `createUserWithEmailAndPassword`) and phone (`:89-92`,
  `pendingCode.confirm`) are inline branches inside the one `createAccount()`, selected by the
  `method` state (`:54`). So *remember and resume* is one guard in front of the credential step, not
  three — but **each branch must be proven**, because they fail differently and only the password one
  produces `auth/email-already-in-use`.
- **the case the in-session memory does not cover, and it needs a decision you must state.** A page
  reload loses the remembered credential, and the person is then back to the wrong copy. The
  page currently does **not** sign out after a failed join — `register.test.tsx:430` asserts
  `firebaseSignOut` was not called — so the credential is usually still live in `auth` and
  `auth.currentUser` may be enough on its own. **Recommended, for the password branch only:** on
  `auth/email-already-in-use` with nothing remembered, sign in with the same email and password and
  retry the join; if that sign-in fails, the account really is somebody else's and *"Sign in instead."*
  becomes the right answer again, so keep it for that case. Google and phone re-authenticate naturally
  on the second press and need only to skip to the join. **Choose, build it, and write the choice and
  its reasoning into the proof** — do not leave the reload case silently unhandled.
- **the characterisation test is updated, never deleted.** `register.test.tsx:411-442`,
  `it("leaves a Firebase account behind that the second attempt cannot get past")`, inside the
  describe at `:392-443`. Its own comment at `:408-409` says *"This test exists so that whoever fixes
  it has to come here and change what it says."* You are that person. **Rewrite it to assert the
  fixed behaviour** — same place, same describe block — and rewrite the `CHARACTERISATION OF A
  DEFECT` header at `:397` so it no longer describes the file as documenting a defect it now prevents.
  Deleting it would erase the only record that this was ever wrong.
- **paths:**
  - `frontend/app/register/page.tsx`
  - `frontend/__tests__/register.test.tsx`
- **forbidden:** `frontend/lib/auth-context.tsx` and `frontend/lib/firebase*` — **read them, do not
  change them**; the fix is in the page's own flow, and if you find it genuinely has to reach into the
  auth context, **stop and report** rather than widening. `frontend/lib/api.ts` (reserved, and needs
  nothing — `joinTemple` already exists). Every other file under `frontend/app/`, including
  `choose-temple/page.tsx` and `sign-in/page.tsx`. The whole of `backend/` — the server is right:
  `AuthenticationFilter` already admits a verified uid with no membership so that this retry can work.
- **reservations:** **none.** No migration, no error code, no permission, no `api.ts` change, no nav
  row. `/register` is one of the few routes with no `RequireRole` guard at all, so T-035's change in
  this same wave cannot reach it.
- **built 2026-09-07, and the builder declined the recommended reload variant — correctly.** The row
  recommended that, on `auth/email-already-in-use` with nothing remembered, the password branch sign
  in with the typed password and retry the join. The builder built a narrower thing instead: it checks
  `auth.currentUser` and resumes only if its email matches what was typed, otherwise the original
  error is re-thrown and *"Sign in instead."* stands. **Its reasoning beats the recommendation and is
  worth keeping.** The password retry would silently join a genuine account-holder who happens to type
  their real password — taking the message away from exactly the person the acceptance criterion
  protects — and it would make `/register` answer the question *"is this the password for this
  email?"*, which is a password oracle on an unauthenticated screen. It also buys less than it looks:
  a failed join never signs out, so the Firebase session survives a reload and a browser restart, and
  the retry only covers the case where that session was *also* cleared. **The residual sliver — a
  person both stranded and with their session cleared still gets the wrong advice — is named in the
  proof rather than hidden.** That is the right trade and the right way to report it.
- **acceptance:**
  - A refused join followed by a second press **does not create a second credential and does not say
    "Sign in instead."** — it retries the join, and on success the person lands where a first-time
    success lands.
  - Proven for **all three** methods: password, Google and phone.
  - Somebody who genuinely already has an account and is not mid-registration **still gets
    *"There is already an account with that email. Sign in instead."*** — the advice is wrong only for
    the stranded person, and this must not take it away from the person it is right for.
  - The password branch's existing behaviour is untouched on the happy path: still signs out and
    redirects to `/sign-in?registered=…` (`:111-119`).
  - `register.test.tsx`'s characterisation test **exists, is renamed to describe the fixed
    behaviour, and passes** — the diff shows it changed, not that it went away.
  - `npx tsc --noEmit` clean, `npm test` green, `npm run build` clean.
- **proof:** `docs/work/proof/T-037.md`
- **shipped:** —

---

# Wave 4b — the temple's own record, and one cook's own hours

**This is the wave 4 that was planned, minus T-035**, which moved to 4a so that the refusal shape is
committed before three of these four write fresh page tests on guarded routes. T-032 is here rather
than in wave 3 because it and T-034 are both in `backend/.../staff/`; see the note at the head of
wave 3 for why serialising them cost nothing. Nothing else about these four rows has changed, and
**no reservation, migration version or error code below this point moved** — see the note at the head
of 4a.

Reservations for this wave — `deleteMealKind`, `updateTenant` and T-032's leave fields in
`frontend/lib/api.ts`, and the `/settings/meal-kinds` row in `frontend/lib/nav.ts` — are written by
the work manager in one pass immediately before 4b is dispatched, and not before. **Wave 4a needs
none of them**, which is why it can go first with nothing written into a shared file at all.

> **Written 2026-09-07, in one pass, immediately before dispatch**, with `npx tsc --noEmit` clean and
> `__tests__/nav.test.ts` green (13 passed) on the reserved tree before any builder started — so no
> builder inherits a red baseline it did not cause. **Three things about that pass are worth keeping,
> because each is a decision a builder must not re-open:**
>
> 1. **The nav row's reservation was incomplete.** It was recorded here as
>    `{ href: "/settings/meal-kinds", label: "Meal kinds", roles: [ADMIN] }` — and `NavItem` requires
>    an `icon`. The row could not be written as reserved. `soup` was chosen (beside Festival occasions
>    in the *Temple* group) and **checked against `tabler-icons-outline.css` rather than assumed**, the
>    webfont being the only thing that decides whether an icon name renders or renders nothing.
>    A reservation is only as good as the type it has to satisfy.
> 2. **T-032's shape choice was taken away from its builder, deliberately.** Its row left the payload
>    shape open — *"a new field on `StaffProfileDetailView` or a small resolved-days list beside the
>    template is the builder's to choose"* — but the TypeScript half of that choice lands in
>    `api.ts`, which is reserved. Leaving it open would have meant either a builder editing a reserved
>    file or a stub that guessed. So it is fixed here: `ScheduleLeaveDay { date, leaveId, leaveType,
>    leaveLabel, halfDayLeave }` — `WeekScheduleView.ResolvedDay`'s four leave fields and no others —
>    plus `leaveDays` and an inclusive `leaveFrom`/`leaveTo` window on `StaffProfileDetailView`. The
>    builder is told the choice was made for it, told why, and told to **stop and report** if it thinks
>    the shape is wrong rather than comply with a shape it doubts.
> 3. **Those three fields are optional *and* nullable**, which is not fussiness. `StaffProfileDetailView`
>    is served by two endpoints: `/staff/schedule/me`, which will resolve leave, and
>    `/staff/profiles/{id}`, which answers a manager's template question and will not. There is no
>    Jackson `NON_NULL` inclusion configured in `application.yml`, so the second endpoint puts a literal
>    `null` on the wire — while a hand-built test object leaves the field *absent*. Required-nullable
>    was the wave-3 mistake that broke `volunteer-shifts.test.tsx` from inside a reservation; optional
>    alone would have been a type that lies about the other endpoint. Absent or null means **not
>    resolved**, never **no leave**, and the type comment says so, because a screen that read an empty
>    list as a clear fortnight would be wrong on exactly the day this task exists for.

### T-005 — A screen that manages meal kinds

- **source:** docket **A3** (INTAKE A3).
- **wave:** 4b — held out of wave 2 deliberately; see the wave table.
- **state:** **stopped — blocked on a product decision about renaming. Nothing was written.**
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
  - `frontend/lib/nav.ts` — **written as** `{ href: "/settings/meal-kinds", label: "Meal kinds", icon: "soup", roles: [ADMIN] }`,
    in the *Temple* group under Festival occasions. The `icon` is the correction: the reservation above
    omitted one and `NavItem` requires it. `soup` was verified present in the Tabler outline webfont
    before it was written. The page guard must carry `["TEMPLE_ADMIN"]` — the same set as this row,
    which is nav.ts's own rule.
- **acceptance:** a renamed kind appears renamed on the planner without a code change; delete refuses
  readably when the kind is in use; Temple Admin only.
- **proof:** `docs/work/proof/T-005.md` — a stop, not a build.
- **shipped:** never, and correctly so. Nothing was written. The proof file shipped with wave 4b's
  ledger commit on 2026-09-07 so the finding survives; the task stays open on the docket until
  Rajeev answers the rename question in that proof.

> **Stopped by its builder on 2026-09-07. Two of the four acceptance criteria rest on a false premise
> about the server, and the operation this row calls safe is the dangerous one.** Both findings were
> re-checked by the work manager against the tree, not taken on the builder's word.
>
> **1. There is no delete refusal to render.** `MealKindService.delete()` (`:101-106`) is
> unconditional — `DELETE FROM meal_kinds WHERE id = ?`, 204 every time — and its own comment says
> why: *"A plan records its kind by name, not by reference, so removing a kind never breaks the meals
> already planned under it — they keep reading as what they were."* `grep -rn "REFERENCES meal_kinds"`
> over `db/migration` returns **nothing**, and V95 records that an FK is not even available:
> `meal_kinds` is unique on an *expression* index over `(tenant_id, lower(name))`, which PostgreSQL
> will not accept as an FK target. So "delete refuses readably when the kind is in use" had exactly
> two routes — invent a client-side guard, which the brief forbids, or add the refusal on the server,
> which the contract forbids. The builder stopped instead of picking one, which is right. There are
> not "both delete outcomes" to test; there is one.
>
> **2. The planning check this row quotes was right and answered the wrong question — that error is
> the work manager's.** The row states the docket's worry was checked and is unfounded because no
> hardcoded `BREAKFAST`/`LUNCH`/`DINNER` literal exists in `frontend/app`, `frontend/lib` or
> `frontend/components`. The builder re-verified that and it holds: all 29 case-insensitive hits are
> prose in doc comments or one form placeholder. **But the coupling was never in frontend code — it is
> in server data.** Three tables store the kind as a *name string*: `meal_plans.meal_kind`, the
> recorded-meal table's `meal_kind` (V64:38) and `shifts.meal_kind` (V95:90, T-034's own column).
> `MealKindService.update()` touches none of them.
>
> So renaming *Lunch* to *Raj Bhog* — the docket's headline ask and this row's first acceptance
> criterion — **orphans every existing plan, recorded meal and linked shift.** That would be survivable
> if the stored name were only ever displayed. It is not: `MealPlanService.java:237` resolves stored
> names through `require()` on a read path behind **Reuse a plan** —
> `kinds.computeIfAbsent(meal.mealKind(), mealKindService::require)` — which throws
> `MEAL_KIND_UNKNOWN` **`KMS-400071`** (409). The work manager confirmed eight `require()` call sites
> across `MealPlanService`, `ServedMealService` and `MealCrewService`. The plain planner grid is safe
> (`list()` is straight SQL), **so the breakage hides until somebody reuses a plan or records a
> meal** — which is worse than failing immediately, not better. And `KMS-400071`'s own next step reads
> *"ask a Temple Admin to add it in temple settings"*: it points the reader at the screen that would
> have caused it.
>
> **The lesson, and it is a new one for this file.** "No hardcoded literal in the frontend" is not the
> same claim as "renaming is safe", and the planning pass treated them as one. Where a value is stored
> as a **name rather than a reference**, the question to ask is not *who hardcodes it* but *who
> resolves it*, and the answer to that is on the server. Two of the three tables involved were checked
> for other reasons in this very batch — V64 and V95 — and neither check asked this question.
>
> **What is needed before this can be dispatched again: one decision about temple data.** When a
> temple renames a kind, what happens to meals already planned under the old name? The builder's
> recommendation, which the work manager agrees with: **rename cascades, delete stays permissive** —
> `update()` also updates the three name columns per tenant under RLS when the name changes, delete
> keeps today's behaviour that V48 and V64 both argue for. No migration, no new error code, and it
> fits the design that is already there. The alternative — a surrogate key and a backfill across three
> tables — is more correct in the long run and much larger. **Either way it is a backend task that
> must land before or with this screen**, and this row is currently a frontend-only contract with no
> backend reservation at all.
>
> **The fallback the builder deliberately did not assume:** ship list, add, delete and the non-name
> fields with the name read-only and a line saying why. It offered it and waited, rather than deciding
> on the temple's behalf which field a settings screen may not settle.
>
> ### Ruled 2026-09-07 — the rename cascades, and this row becomes two tasks
>
> **Rename cascades per tenant under RLS.** It is the only non-broken option when the name *is* the
> join key. That half is settled and is now **T-038**.
>
> **"Delete stays permissive" is *not* accepted as stated, and the reason is worth reading twice.**
> The argument for it was borrowed from T-004's occasions, and the two may not be the same shape. An
> occasion's name is **snapshotted** onto the meal as text, which is exactly why deleting one changes
> no record. Meal kinds are resolved by eight `require()` sites — so **before delete can be permissive,
> somebody must establish whether those resolve at read time or only validate at write time.** If reads
> resolve, then deleting a kind breaks the reading of historical plans and meals, and a permissive
> delete ships a time bomb rather than a convenience. `MealPlanService.java:237` is on a read path
> behind *Reuse a plan*, which is already evidence pointing one way, but it is one site of eight and
> the question must be answered with evidence rather than inference.
>
> T-038 answers it and chooses from the answer: **permissive** if the stored value is genuinely a
> snapshot; **refuse-when-in-use** or **deactivate-rather-than-delete** if it is not. Which one, and
> why, goes in T-038's row.
>
> **This row is now the screen only, and it does not run alone.** T-038 lands first or with it, never
> after. The screen is not to be dispatched again on its own — that is what produced this stop.
>
> **What the form would have exposed, recorded so it is not relitigated:** all five fields. Name; sort
> order as position in the picker; `defaultReadyTime` as a **genuinely clearable** input, because null
> is meaningful — it makes the kind always ask, and a time picker that cannot be emptied would destroy
> that silently; and `isEvent` / `needsOccasion` shown with their consequence written beside them
> rather than as bare checkboxes, because dropping them would make every kind added on this screen an
> ordinary sitting, with no way to add a feast or an event at all.
>
> **The reservations written for this task are therefore live but unused**, which is a state this file
> has not had before: `deleteMealKind` in `api.ts` has no caller, and the `/settings/meal-kinds` row in
> `nav.ts` points a Temple Admin's menu at a route that does not exist. **Both are to be reverted once
> wave 4b's other builders are out of the tree** — not while they are mid-verify, because editing a
> shared file under a running `next build` is the collision this arrangement exists to prevent. Wave 1
> has the precedent: of its two nav edits, one shipped and one was reverted.

### T-008 — A temple's profile can be corrected, and 80G approval recorded

- **source:** docket **A1 + A2** (INTAKE A1, A2). The docket's second priority: *"Testers provision
  temples all day."*
- **wave:** 4b
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4b, commit `8605028`, after two follow-up passes: the `TENANT_UPDATED` allocation with the `TenantDetail` coordinates, and the merged-tree guard-test exemption. CI verdict and the deploy result are in the release report for this wave, at the foot of this file. Not yet certified by observation.)*
- **what:** `TenantController` has POST, GET, export and DELETE and **no PUT or PATCH at all**, so name,
  address, coordinates, currency, timezone and 80G status are set once at provisioning and a temple
  provisioned wrongly can only be fixed by deleting it. Add the update endpoint and the screen over it.
  **Two things a builder must not miss.** First, `is_80g_approved` is written only by the provisioning
  insert and the sole `UPDATE tenants` statement anywhere in the backend touches `locale` — so the 80G
  field is part of this endpoint, not a separate one, and receipts stay wrong until it exists. Second,
  **timezone is not just a column**: `calendar_days` is precomputed per tenant from it, so changing it
  must re-trigger the calendar precompute for that tenant or the temple keeps stale tithi and Ekadashi
  rows and every "today" in the product silently disagrees with the panchanga. `slug` is
  `updatable=false` and stays that way. **Question 7 is closed** — `DECISIONS.md` **D-13**, ruled by
  Rajeev on 2026-09-07: *"operator only"*, against a recommendation that the fields be split so a
  temple admin could correct its own address. So build exactly what this row already assumed:
  `MANAGE_TENANTS`, **no new permission**, and the screen at `/tenants/[id]/edit` — operator
  territory, beside the provisioning flow — and **not** anywhere under `/settings`. The cost of the
  ruling, stated because it is deliberate: a temple cannot fix its own address, and every correction
  is an operator ticket. What it buys is a single auditable answer to "who may change what a temple
  is", and it keeps the two genuinely dangerous fields on the operator's side without a
  field-by-field permission boundary — **timezone**, which silently rewrites `calendar_days`, and
  **`is_80g_approved`**, which is a legal status no temple should assert about itself.
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
  - permissions: none new — `MANAGE_TENANTS`. Settled by D-13; the caveat this row used to carry
    resolves in favour of the default it named.
- **acceptance:**
  - An integration test changes a tenant's timezone and asserts the calendar precompute is re-enqueued
    for that tenant — not merely that the column changed.
  - Recording 80G approval after provisioning makes the flag true and is reflected wherever receipts
    read it.
  - `slug` is rejected as unchangeable.
  - The change is audited like every other tenant-level act.
- **proof:** `docs/work/proof/T-008.md` — real output, every run through `tools/work-lock.sh run verify`.
- **shipped:** 2026-09-07, wave 4b, commit `8605028`.

> **Built and green 2026-09-07.** `PATCH /api/v1/tenants/{id}` behind `MANAGE_TENANTS` (D-13, no new
> permission) and the operator screen at `/tenants/[id]/edit`, reached from an *Edit details* link on
> the temple's page. `TenantUpdateService` mirrors `TenantProvisioningService` — JDBC,
> transaction-local tenant context for the audit write, before/after snapshot — and a timezone change
> calls `calendarScheduler.enqueueForTenant(tenantId)`, the same hook provisioning uses, **only when
> the zone actually changed**. `GET /{id}` now also returns the coordinates, which the edit screen has
> to open on.
>
> **`slug` is declared on `UpdateTenantRequest` precisely so it can be refused.** Spring Boot leaves
> `FAIL_ON_UNKNOWN_PROPERTIES` off, so an undeclared field would have been **silently dropped and the
> caller told the save worked** — a refusal that only exists if something is there to refuse it.
>
> **The precompute question is answered and needed no stop.** `CalendarService.upsertDays` writes
> `ON CONFLICT (tenant_id, cal_date) DO UPDATE SET tithi = EXCLUDED.tithi, … sunrise = …`, so the whole
> row is recomputed under the new offset — it corrects rather than merely adds. One limit, and it is a
> decision rather than an accident: the horizon starts at the first of the *current* month, so days
> already past keep their old-zone values. Accepted as recorded — rewriting the calendar under a meal
> already cooked would change the record of what happened.
>
> **The most valuable thing in this proof is a run that failed.** The first backend run reported what
> looked like `jsonb` spacing, and the pasted output showed the before-state rendering latitude as
> `"12.971600"` against an after-state built from the request that would have said `"12.9716"` —
> meaning **every audit event would have claimed the temple had moved.** Fixed by reading the
> after-state back from the row, and now asserted. That is a defect that ships silently and is
> unarguable a year later.
>
> **Three things it declined to do on its own, all three correctly, and two are now written.**
>
> 1. **It used `SETTINGS_UPDATED` and asked for its own action rather than taking one**, because
>    `AuditAction.java` was outside its contract. It was right that the borrowed action is wrong:
>    an operator changing a temple's legal 80G status is not a temple admin rotating Razorpay keys, and
>    this repo's own `EQUIPMENT_REINSTATED` precedent is that an act filed under a neighbouring name is
>    invisible to anyone not already looking. **`TENANT_UPDATED` allocated by the work manager**, and
>    `AuditAction.java` joins the reserved files — see the reservations section.
> 2. **`TenantDetail` lacked the coordinates**, so it declared a local `TenantForEditing` alias and
>    flagged it for deletion rather than editing `api.ts`. **Written**, as required fields.
> 3. **No post-save banner on `/tenants/[id]`**, because it needs `useSearchParams` and
>    `__tests__/tenant-detail.test.tsx` — a file outside its contract — mocks `next/navigation` without
>    it. **This one stays declined.** Returning to the detail page where the correction is visible is a
>    good enough answer, and reaching into another file's mock in passing is how a wave gets corrupted.
>    Recorded as a finding, not a task.
>
> **Contract widened by exactly one file, after checking ownership.** `frontend/__tests__/tenant-detail.test.tsx`
> is handed to T-008 **for one edit only** — its `const TENANT: TenantDetail` fixture and the
> `exportedJustNow()` variant cannot typecheck until they carry the two new coordinate fields. No other
> contract in wave 4b names that file and every other builder had finished, which is the check that
> makes the widening safe rather than lucky. The builder is told explicitly that this is not licence to
> add the banner.
>
> **Not hand smoke-tested** — the builder cannot drive a browser and does not deploy. The proof names
> what a human should check on staging.
>
> **A merged-tree run caught what neither the builder nor the work manager could have.** After every
> builder was out of the tree, the work manager ran the **full** frontend suite over the finished
> state — all four tasks plus the shared-file edits — and got
> `Test Files 1 failed | 97 passed (98)`, `Tests 1 failed | 1052 passed (1053)`. The one failure was
> `design-system.test.ts` → *"nobody hard-codes a time zone outside the one place that resolves it"*,
> naming `app/tenants/[id]/edit/page.tsx`. The guard scans every file for the literal `Asia/Kolkata`
> and exempts exactly two: `lib/api.ts`, which holds the one fallback, and `app/tenants/new/page.tsx`,
> the provisioning form.
>
> **It is not a defect in what was built.** The edit screen is a picker over an existing value
> (`defaultValue={temple.timezone}`), so it assumes nothing about anybody's zone — which is the thing
> the guard exists to prevent. The test asserted a world with **one** temple-writing screen in it, and
> T-008 made two. This is the case Rajeev named: a fix is correct and an existing test asserts the old
> behaviour, the builder cannot leave the suite red and cannot safely reach the file, and only the work
> manager can see whether anybody else holds it. Ownership checked — no wave-4b contract names
> `__tests__/design-system.test.ts` and every builder had finished — so it was handed to T-008 for that
> one edit, with the instruction not to weaken the regex to make the failure go away.
>
> **The lesson, and it is the sharpest one in this batch.** T-008's own verification was green and
> correct: `tsc`, four tenant test files, `next build`. A **targeted vitest run never loads a
> repo-wide guard test**, so no builder in this arrangement can catch one by construction, however
> careful it is. Wave 4a got its merged-tree run by luck — the last builder's run happened to postdate
> every other file. **Wave 4b got one because it was run deliberately, and it found something.** From
> here the merged-tree run is the work manager's step, not an accident of timing.
>
> **One finding recorded rather than fixed:** the timezone option list is now hardcoded in two places,
> `tenants/new/page.tsx` and `tenants/[id]/edit/page.tsx`, two-item lists that must agree with nothing
> making them. Lifting them into a shared module is arguably right and means editing a file in nobody's
> contract this wave, so it is a finding and not a task.

### T-018 — Changing a person's role

- **source:** docket **B10** (INTAKE B10).
- **wave:** 4b
- **state:** **closed — already built. Nothing was written and nothing ships.**
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
- **proof:** `docs/work/proof/T-018.md` — a refusal, not a build.
- **shipped:** never. See below.

> **Refused by its builder on 2026-09-07, and the refusal is right.** It was dispatched with the
> brief's own stop condition — *"if `systemAccess` turns out to be the same write on the server, stop
> and report"* — and that condition fired harder than the condition anticipated: not only is it the
> same write, **the control this task asked for already exists on the screen it was told to build it
> on.** Both claims were checked by the work manager rather than taken on the builder's word:
>
> - `frontend/components/staff/StaffForm.tsx:168-195` is an **App access** select — *No login /
>   Kitchen staff / Kitchen manager / Temple admin* — rendered on the edit path at
>   `frontend/app/staff/[id]/edit/page.tsx`. Promoting a volunteer and demoting a manager both work
>   there today, neither by hiring the person again. That is verbatim what this row asked for.
> - It is the same column by a different door: `RoleChangeService.java:88` writes
>   `UPDATE users SET role = ? …`; `StaffEmploymentService.promote` (`:305-307`) writes
>   `UPDATE users SET role = ?, status = 'ACTIVE' …` and `demoteToDevotee` (`:317-319`) writes
>   `UPDATE users SET role = 'VOLUNTEER' …`. `SystemAccess` is a thin wrapper carrying a `User.Role`.
>
> Building as briefed would have put **a second control writing the same column on the same form, via
> a different endpoint, submitted by the same Save** — last write wins, ordering undefined. The edit
> page's own comment already rejects that shape for a weaker case: *"A fourth button on the row would
> be a second door to the same room."*
>
> **The docket's premise, precisely.** INTAKE B10's literal claim is true — `changeUserRole` has zero
> callers — and its inference from that is false. `UserController.java:62-64` states that hiring is
> *"the only act that grants a temple role"*, and `TenantProvisioningService.java:206-215` gives the
> founding admin a `staff_profiles` row so they are not stranded outside it. Every non-volunteer
> therefore has a staff record and is reachable. The wrapper is **dead client code, not a missing
> feature** — which is a different finding from the one the docket wrote, and a cheaper one.
>
> **The three guards B10 wanted surfaced are surfaced, on the path that survived.** Self-change:
> `StaffEmploymentService.java:180` calls `requireNotSelf(actor, before, CANNOT_CHANGE_OWN_ROLE)` —
> the *same* `KMS-400022` the role endpoint raises — rendered through `ErrorNotice`. Super admin:
> structurally unassignable, since `SystemAccess` has no such value. Cross-tenant: `find(id)` is
> RLS-scoped and a miss is `RESOURCE_NOT_FOUND`.
>
> **What the re-homing cost, and it is a real defect — see the finding below.** `RoleChangeService`
> audits its *refusals* separately (`:105-117`, `AuditService.recordSeparately`, so a blocked
> escalation survives the 403). `StaffEmploymentService` calls `recordSeparately` **nowhere** —
> verified: the only two call sites in the whole backend are in `RoleChangeService` and in
> `AuditService` itself. So a refused self-demotion on the staff form throws and leaves **no audit
> record at all**, and since that form is now the only role-change door, the audit property B10
> valued has quietly been lost.


### T-032 — A cook's own schedule shows the leave they were given

- **id:** T-032
- **source:** `DECISIONS.md` **D-16**, closing paragraph: *"The **only** gap is that `/my-schedule`
  does not show approved leave, so a person given Thursday off still sees Thursday's hours — its own
  task, at Rajeev's instruction (**"We need this. Add it in."**)."* T-006 shipped in wave 2 knowing
  this and said so on the screen; `app/my-schedule/page.tsx:160` carries the admission in muted text:
  *"Approved leave is not shown here."*
- **wave:** 4b
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4b, commit `49ce170`. CI verdict and the deploy result are in the release report for this wave, at the foot of this file. Not yet certified by observation: it needs a rostered person with approved leave in the next fortnight.)*
- **what:** `GET /api/v1/staff/schedule/me` returns `StaffProfileDetailView` — the profile, the
  seven-day template and the per-date exceptions — and **no leave at all**, so a cook approved for
  Thursday off still reads Thursday's hours on their own screen. Fold leave into `scheduleForUser`
  the way `weekView` already does it.
- **the fix is on the server, and this is the load-bearing constraint.** Do **not** resolve leave a
  second time in the browser out of `myLeave`. T-006's builder was asked to and refused, and was
  right: it would put a second answer beside the server's, and the two would disagree the first time
  the resolution order changed. `WeekScheduleView.ResolvedDay` already states the order —
  *"approved leave if there is any, otherwise the per-date override, otherwise the template"* — and
  says the order lives once, in `ScheduleResolver`. `scheduleForUser` must get the same answer from
  the same place, so that the grid a manager reads and the list the cook reads can never differ.
  `StaffScheduleService` already holds a `resolver`; `weekView` (`:242-275`) is the worked example.
- **what the payload gains.** Enough for the screen to draw a day off: the leave's id, its type, its
  printable label and whether it is a half day — the four fields `ResolvedDay` already carries for
  exactly this, and for the same reason (*"so the browser keeps no copy of the vocabulary"*). A half
  day leaves them in for part of it, so the hours still stand and the screen must not blank them.
  Whether that arrives as a new field on `StaffProfileDetailView` or as a small resolved-days list
  beside the template is the builder's to choose; **state the choice and the reason in the proof**,
  and keep it a record in the `staff` package.
- **what the screen does with it.** The horizon is 14 days from the temple's own `todayIso`, which is
  the convention T-006 documented and which this does not change. A day covered by approved leave
  reads as leave with its label, not as hours. **Delete the muted admission at `:160`** — it is the
  sentence this task exists to make untrue. Nothing else on that screen moves: it still renders two
  fields out of a payload that carries a date of birth and the last four of a PAN, and it still draws
  no route a reader is refused.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/staff/StaffScheduleService.java` *(`scheduleForUser` only —
    `weekView`, `setTemplate`, `setException`, `swap` and `deleteException` are not this task's)*
  - `backend/src/main/java/org/iskcon/kms/staff/StaffProfileDetailView.java`
  - one **new** record in `backend/src/main/java/org/iskcon/kms/staff/` if the shape needs one
  - `backend/src/test/java/org/iskcon/kms/staff/OwnScheduleLeaveIT.java` *(new)*
  - `frontend/app/my-schedule/page.tsx`
  - `frontend/__tests__/my-schedule.test.tsx`
- **forbidden:** `staff/ScheduleResolver.java` — **read it, do not change it.** It is where the
  resolution order lives and this task consumes that order rather than restating it; if you find it
  genuinely has to change, **stop and report** rather than widening. Also `staff/LeaveService.java`,
  `staff/WorkforceService.java` and `staff/MealMoment.java` (T-034's in wave 3, committed by the time
  you start), `staff/StaffEmploymentService.java` (T-014's in wave 7), `frontend/lib/api.ts` and
  `frontend/lib/nav.ts` — both done.
- **reservations:**
  - migration: **none.** `staff_leave` and its four statuses already carry everything; D-16 settled
    that the states stay `PENDING, APPROVED, DECLINED, REVOKED` and no column is added.
  - error codes: none new.
  - permissions: none new — `VIEW_OWN_SHIFTS`, which the endpoint already sits behind.
  - `frontend/lib/api.ts`: **written 2026-09-07** as `ScheduleLeaveDay { date, leaveId, leaveType,
    leaveLabel, halfDayLeave }` plus `leaveDays?: ScheduleLeaveDay[] | null` and an inclusive
    `leaveFrom`/`leaveTo` window on `StaffProfileDetailView`. This **settles the shape choice this row
    left to the builder** — see the note at the head of the wave for why it had to be settled here and
    why the fields are optional *and* nullable. The builder is told to stop and report if it disagrees,
    not to edit `api.ts`.
  - `getProfile` (`StaffScheduleService.java:69-71`) constructs the same record and resolves no leave,
    so it passes `null` for all three. That one line is granted to T-032 despite the row scoping it to
    `scheduleForUser`, because adding a record component makes it a compile error rather than a choice.
- **acceptance:**
  - An integration test approves leave covering a working weekday and asserts `GET /schedule/me`
    returns it — on the real database, as the person themselves, under RLS.
  - A **half day** comes back marked as a half day and the day's hours are still present. This is the
    case a client-side reimplementation would have got wrong.
  - The manager's `weekView` and the cook's `/schedule/me` agree about the same date for the same
    person — asserted in one test, because "one answer, not two" is the whole point of the shape.
  - The screen shows the leave with its label instead of hours, and the muted "Approved leave is not
    shown here." line is gone.
  - `./gradlew test` green; `npx tsc --noEmit` clean; `npm test` green; `npm run build` clean.
- **proof:** `docs/work/proof/T-032.md` — real output, every run through `tools/work-lock.sh run verify`.
  **One claim in it is out of date and must not be repeated**: its "not done" section says there is no
  way to sign in as kitchen staff on UAT. True that morning, false by the afternoon — the five
  `ikms.kitchen-staff.*` accounts exist and resolve as `KITCHEN_STAFF`. What is still owed is approved
  leave in the next fortnight for a rostered person.
- **shipped:** 2026-09-07, wave 4b, commit `49ce170`.

> **Built and proven 2026-09-07. Every acceptance criterion is met and no contract was breached.**
> The server half takes its leave from `ScheduleResolver.resolve(from, to)` — the same call `weekView`
> makes — and returns **the dates leave covers, not the spans**, so the browser maps nothing and keeps
> no copy of the vocabulary. `weekView`, `setTemplate`, `setException`, `swap` and `deleteException`
> are untouched, as the contract required, and `ScheduleResolver` was read and not changed.
>
> **Two edits inside `StaffScheduleService.java` beyond `scheduleForUser`, both forced and both inside
> the contract.** `getProfile`'s constructor call, which was granted in advance because adding a record
> component makes it a compile error rather than a choice; and a **`TempleClock` constructor
> parameter**, which was not anticipated when the task was written. The reasoning is right and worth
> keeping: the endpoint carries no date and the controller is not in the contract, so the *server* has
> to decide which day the window opens on — and an inline `ZoneId.of("Asia/Kolkata")` is the exact bug
> `TempleClock` exists to remove. Nothing constructs the service outside Spring, and the backend
> compiles and runs green, which is what would have caught it if anything did.
>
> **The window is 28 days — `TempleClock.today()` through `today + 27`, inclusive — and it is stated
> on the wire.** Four weeks answered against a screen that lists two, deliberately: the 14-day horizon
> is a decision about *reading* and lives on the screen, while *how much to answer* is the endpoint's,
> and one number held in two languages drifts. The screen **honours the window rather than trusting
> it**: leave outside `leaveFrom`–`leaveTo` is dropped, and a muted line appears if the list ever
> reaches past it. Absent or null reads as *not resolved*, never *no leave* — which is the property the
> reservation's type comment was written to protect, now actually enforced by a test.
>
> **Two tests beyond the acceptance, both closing ways this could have been wrong while passing
> everything asked for:** pending leave is not an absence (`pendingLeaveIsNotYetAnAbsence` — a screen
> that emptied itself on request would tell a cook they had a day off nobody granted), and the screen
> refuses to invent an answer when it was told nothing about leave, or told about a shorter window than
> it draws.
>
> **The reserved shape was accepted, not merely obeyed** — the builder was told it could refuse it and
> reported that it is right.
>
> **Three things it recorded as not done, none of them blocking.** No hand smoke test is possible: this
> screen is read as a rostered person and there is no way to sign in as kitchen staff on UAT (the
> blocker `DECISIONS.md` already records), so a Temple Admin who is on the payroll should exercise it
> as themselves once it is on staging, with one approved full day and one approved half day inside the
> fortnight. Leave resolves for **active staff only**, which is `ScheduleResolver`'s existing silence
> rather than a new rule — worth knowing that a former employee's `/my-schedule` still draws their old
> template as hours, which is pre-existing and outside this contract. And **a leave day the person was
> not rostered for is not listed**: leave changes how a listed day reads and never which days are
> listed, so leave over somebody's ordinary day off stays out. If that should change it is a decision
> about which days the list contains, and its own task.
>
> **One line is owed elsewhere:** `docs/work/DECISIONS.md` **D-16** still reads *"The only gap is that
> `/my-schedule` does not show approved leave"*, which T-032 has now closed. That file is neither the
> builder's nor the work manager's to write.


---

## For the main session — found while building wave 4b, not scheduled

### A refused role change on the staff form is audited nowhere

Found by T-018's builder while establishing that its task was already built, and **confirmed by the
work manager**: `AuditService.recordSeparately` — the method whose whole purpose is that a record
survives the 403 that follows it — has exactly two call sites in the backend, one in
`user/RoleChangeService.java:109` and one in `audit/AuditService.java` where it is declared.
`staff/StaffEmploymentService.java` has none. Its `requireNotSelf(actor, before,
CANNOT_CHANGE_OWN_ROLE)` at `:180` throws and nothing is written.

Why it matters more than it looks: `RoleChangeService`'s endpoint has **no callers**, so the staff
form is the only door through which a temple role actually changes — and it is the door with no
record of a refused attempt. Somebody trying to escalate their own role leaves no trace. The audit
class of evidence B10 was written to protect exists on the path nobody uses.

**Not scheduled, and deliberately not dispatched now.** The fix is in
`backend/.../staff/StaffEmploymentService.java`, which is **T-014's file in wave 7**. Handing it to a
builder today would either open a fifth contract in a flying wave or collide with T-014 later. The
two honest options are to fold it into T-014's row as a second acceptance criterion, or to give it
its own id in a wave before 7. **That is a decision for the main session**, not a blocker: nothing in
flight depends on it, and Rajeev asked to be woken only for a real blocker.

Two smaller things from the same proof, both one-liners and neither scheduled:

1. **"No login" names the side effect rather than the act.** The `App access` select's empty option
   (`StaffForm.tsx:184`) reads as a state, where every other option names a role. Cosmetic, one line,
   and it belongs to whoever next opens that form rather than to a task of its own.
2. **`changeUserRole` and `PATCH /api/v1/users/{id}/role` should be deleted or documented as
   deliberately retained.** Dead code that looks like a feature is what produced B10 in the first
   place, and leaving it unexplained will produce B10 again. `frontend/lib/api.ts` is a reserved file,
   so this cannot be a builder's aside — it is the work manager's, in some wave's reservation pass.


---

# Wave 4c — six tasks, two sub-waves, and not one of them was in the batch when it was planned

**Grew from three to six on 2026-09-07.** It began as the three things wave 4b *found* rather than
was sent to build (T-038, T-039, T-040). Then Rajeev ruled **D-17** while verifying the screen wave 4b
had just shipped, which added two (T-041, T-042). Then the verification pass over
`OUTSTANDING_BUILD_LIST.md` found a live defect on staging that outranks all five (T-043). That is
the shape this batch keeps taking, and it is worth naming: **the waves that were planned are not
where most of the work is coming from.** Waves 1–3 were planned; 4a, 4b and 4c are almost entirely
made of what the previous wave found or what Rajeev found driving the result of it.

**Split into 4c-1 and 4c-2, and the split is forced twice over.** Six builders in one wave is well
past the practical ceiling — three or four, beyond which the verify lock is the bottleneck and the
parallelism has bought nothing. And T-039 must land **before** T-040, for a reason that is about
evidence rather than files: `RoleChangeIT` is currently the only test in the repo asserting that a
refused role change is audited, and T-040 deletes it. Deleting the only worked example of a property
before it has been rebuilt elsewhere is how a property quietly stops being true.

| Sub-wave | Tasks | Builders | Why these together |
|---|---|---|---|
| **4c-1** | **T-043**, T-038, T-039, T-041 | 4 | Four disjoint areas: the planner's recording component, `meal/MealKindService`, `staff/StaffEmploymentService`, and the tenant edit screen with its service. Nothing shared but the two files the work manager wrote first. |
| **4c-2** | T-040, T-042 | 2 | T-040 waits for T-039's evidence. T-042 waits for nothing — it is here because it and T-041 are both in the tenants area and the second wave was free, so serialising them cost the wave nothing at all. |

**On T-041 and T-042 specifically, because the question was asked directly.** Their contracts were
checked for intersection and **they do not intersect**: T-041 is `tenants/[id]/edit` plus
`TenantUpdateService`/`UpdateTenantRequest`, T-042 is `tenants/new` plus a new controller in the `geo`
package. They could have run side by side. They are serialised anyway, because the sub-wave split was
already forced by the count and by T-039→T-040, so serialising them is free — and the one file they
might both have reached for, `frontend/__tests__/design-system.test.ts`, is forbidden to both and left
to the work manager. Free certainty is worth taking.

### T-038 — When a temple renames a meal kind, and what deleting one means

- **id:** T-038
- **source:** T-005's stop, 2026-09-07; ruled by the coordinator the same day.
- **wave:** **4c-1**
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4c, commit `09404a0`. Was: **proven** *(2026-09-07)*. CI verdict, staging revision and digest are in the release report at the foot of this file. Not yet certified by observation.)*
- **what:** Kinds are stored as **name strings** in three tables — `meal_plans.meal_kind`, the
  recorded-meal table's `meal_kind` (V64:38) and `shifts.meal_kind` (V95:90) — and
  `MealKindService.update()` touches none of them, so a rename orphans every plan, recorded meal and
  linked shift. **Rename cascades**: `update()` also updates the three name columns, per tenant, under
  RLS, when and only when the name changes. Migrations in this project are themselves subject to RLS
  and so is this — never a blanket `UPDATE` across all rows.
- **and the question this task exists to answer with evidence.** `MealKindService.delete()` is
  unconditional today and its comment claims removal *"never breaks the meals already planned under
  it"*. That claim is true only if the stored name is a **snapshot**. There are **eight** `require()`
  call sites across `MealPlanService`, `ServedMealService` and `MealCrewService`; establish for each
  whether it resolves at **read** time or only validates at **write** time, and put the list in the
  proof. Then choose: **permissive** delete if the value is genuinely a snapshot; **refuse-when-in-use**
  or **deactivate-rather-than-delete** if it is not. `MealPlanService.java:237` resolves stored names
  on a read path behind *Reuse a plan* and throws `MEAL_KIND_UNKNOWN` **`KMS-400071`** — that is one
  site of eight, and it is a reason to check the other seven rather than a reason to skip them.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/meal/MealKindService.java`
  - `backend/src/test/java/org/iskcon/kms/meal/MealKindIT.java` *(new)*
- **forbidden, and the builder will be tempted by every one of them:** `MealPlanService.java`,
  `ServedMealService.java`, `MealCrewService.java`, `MealKindController.java`,
  `document/JobCardService.java`, `db/migration/`, `ErrorCode.java`, and the whole frontend. It has to
  **read** most of those to answer the question; it may write neither.
- **acceptance:** a rename leaves every existing plan, recorded meal and linked shift readable, proven
  on the real database under RLS with more than one tenant present **and through a path that resolves
  the name**, not by selecting the column back; the other tenant's identically-named kind is untouched;
  delete refuses with `KMS-400126` for each of the three tables independently, including the
  linked-shift case a naive implementation misses; delete still removes a never-used kind; the eight
  sites are enumerated in the proof with read/write beside each.
- **reservations:**
  - migration: **none, and that is a design decision rather than an absence.** See the ruling below.
  - error code: `MEAL_KIND_IN_USE` **`KMS-400126`** (409) — *"Meals have already been planned or
    recorded as this kind."* / *"Rename it instead. Everything recorded under it takes the new name."*
    Written into `ErrorCode.java` by the work manager before dispatch. The next step is worded to point
    at the rename this same task builds.
- **ordering:** T-005's screen lands **with or after** this, never before.
- **proof:** `docs/work/proof/T-038.md`
- **shipped:** `09404a0`, 2026-09-07, wave 4c — shared with T-047 — both edit `MealKindService.java` and `MealKindIT.java`.

> ### The delete question, answered with evidence, 2026-09-07 — and the answer is not the one T-005 recommended
>
> The question this task existed to settle was whether the stored kind name is a **snapshot** or is
> **resolved**. It is resolved. Eight `MealKindService.require(...)` sites, and **four of them are on
> read-shaped paths**:
>
> | Site | Verdict |
> |---|---|
> | `MealPlanService.previewReuse` `:237` | **READ.** `POST /meal-plans/reuse/preview` is a POST only because it has a body — the controller's own comment says it is read-only. It walks *historical* plans and resolves every distinct stored kind, so one deleted kind anywhere in the window throws the whole preview. |
> | `MealPlanService.create` `:399`, `update` `:486` | WRITE — validating a name the caller supplied. |
> | `ServedMealService.find` `:110` | **READ.** Reached from `GET /job-cards/languages` and from `JobCardService.build()` rendering a meal already served. |
> | `ServedMealService.record` `:191` | WRITE. |
> | `ServedMealService.issueCardNumber` `:283` | **Mixed** — `POST /job-cards` and `GET /job-cards/print`. |
> | `ServedMealService.serviceFor` `:308` | **Mixed** — `POST /job-cards`, `GET /job-cards/documents`, `GET /job-cards/print`. |
> | `MealCrewService.suggestedCrew` `:143` | READ (a GET), but forward-looking. |
>
> So **"delete stays permissive" is refused, and it was right not to accept it as stated.** The
> argument for it was borrowed from T-004's occasions, where the name really is snapshotted onto the
> meal as text. Meal kinds are not that. A permissive delete does not break anything on the day it is
> pressed; it breaks a job card or a reuse preview weeks later, on a screen that has nothing to do with
> settings, with an error naming a kind the temple deliberately removed.
>
> **Chosen: refuse when in use. Not deactivate-rather-than-delete, and the reasoning is a trade.**
> Deactivation is the larger and arguably more correct answer — it would let a temple stop serving an
> evening meal without losing the history — but it needs a column, and a column needed `V96`, which was
> wave 5's at the time. Taking it would have slid `V96`–`V105` up one **and required sweeping the migration
> filenames in all ten path contracts**, which is the exact correction this file has already had to
> record twice. *(Postscript, same day: the slide happened anyway — T-047 took `V96` for the V64
> comment fix-forward. That does not make the reasoning wrong. The slide was worth paying for a
> correction the coordinator ruled must not wait, and was not worth paying for a deactivation feature
> nobody has asked for. Different question, different answer, same price.)* That cost is worth paying for something asked for. Nobody has asked to retire a used
> meal kind; the docket asked to **rename** one, which is the other half of this task.
>
> Refusing forecloses nothing — deactivation remains available later as a strict superset — it closes
> the live gap today, and it leaves the common real case working: a kind added by mistake and removed
> before use still deletes. **The question deactivation would answer is Rajeev's, not a builder's**,
> and the refusal text surfaces it to the person who has it rather than guessing on their behalf.
>
> ### Built and green 2026-09-07 — and the builder's own investigation moved the design
>
> **8 tests, 8 passed; the wider `*MealKind*` filter 9/9; `BUILD SUCCESSFUL in 8s`**, everything through
> the lock on real PostgreSQL under RLS. Proof: `docs/work/proof/T-038.md`.
>
> **The load-bearing find, and it was not in the brief.** The dispatch asked the builder to decide
> whether the old name is matched exactly or case-insensitively and to justify it. The answer is
> case-insensitive, `lower(meal_kind) = lower(?)`, and the reason is an asymmetry nobody had noticed:
> **`ShiftService.java:157` stores `shifts.meal_kind` as `trimToNull(request.mealKind())` — what the
> caller typed, never passed through `require()`.** `staff/MealMoment` then folds both sides when
> matching, so a shift linked to `"lunch"` against a temple storing `"Lunch"` is a **working row
> today**, and `ShiftMealLinkIT` proves it. An exact-match cascade would have renamed the plans and the
> recorded meals around that shift and stranded it — the precise failure this task exists to prevent,
> reintroduced by its own fix. Both the rename test and the shift-delete test link in lower case, so
> **the branch that was not chosen is the one under test.**
>
> **The eight sites were re-enumerated and the ruling needed no softening** — the builder's verdicts
> agree with the table above on all eight. Sites 1 (`previewReuse`) and 6 (reached from
> `document/JobCardService.java:305`) resolve *stored historical* names, which is what made the old
> permissive delete a time bomb rather than a convenience.
>
> **Append-only was checked properly rather than taken on trust.** The complete set was enumerated from
> every `SELECT make_append_only(...)` in the migration tree — twelve tables, none of them
> `meal_plans`, `meal_services` or `shifts`. It also corrected a detail in its own comment: V49/V50
> enforce append-only with a `BEFORE UPDATE OR DELETE` **trigger**, not a rule.
>
> **A negative control, and this sub-wave now has two of them.** `MealKindService.java` was reverted to
> `HEAD` with the tests left alone: **5 of 8 failed**, including the reuse preview returning
> `409 KMS-400071` because the plan still said `Lunch` after the rename — *the live defect reproduced
> on demand*. Restored, all 8 pass. Together with T-043's mutation check, two of the four builders here
> proved their own tests were not vacuous without being asked to. **That is worth asking for by
> default**, and it costs one extra hold of a lock that is already held.
>
> **Two things left deliberately, both correctly.**
> 1. **`update()` does not catch `DuplicateKeyException`**, so renaming a kind onto an existing name
>    returns a 500 rather than `MEAL_KIND_ALREADY_EXISTS` the way `create()` does. Pre-existing, one
>    line, unasked-for — reported rather than fixed. It wants an id.
> 2. **V64's column comment is now provably wrong.** It still says a temple may delete a kind and the
>    meals "keep reading as what they were". Applied migrations are not edited in this project, so it
>    would need a comment-only fix-forward if one is wanted.
>
> **Not hand smoke-tested, and in fact it cannot be:** T-005's settings screen does not exist yet,
> which is the whole reason this task was split out of it. Nobody has seen the new 409 land on a
> screen and nobody can until T-005 is built on top of this.

### T-039 — A refused role change is recorded, on the door people actually use

- **id:** T-039
- **source:** found by T-018's builder, 2026-09-07, while establishing its own task was already built.
- **wave:** **4c-1**
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4c, commit `bd5398c`. Was: **proven** *(2026-09-07)*. CI verdict, staging revision and digest are in the release report at the foot of this file. Not yet certified by observation.)*
- **what:** `AuditService.recordSeparately` exists so that a record **survives the 403 that follows
  it**. It has exactly two call sites in the backend: `user/RoleChangeService.java:109` and its own
  declaration in `audit/AuditService.java`. `staff/StaffEmploymentService.java` has **none** — its
  `requireNotSelf(actor, before, CANNOT_CHANGE_OWN_ROLE)` at `:180` throws and nothing is written.
  Since the staff form is the only door a temple role actually changes through (T-018 established
  that), **a refused attempt to change one's own role is currently a privilege change that leaves no
  trace at all.** Record it, the way `RoleChangeService` already does.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/staff/StaffEmploymentService.java`
  - `backend/src/test/java/org/iskcon/kms/staff/StaffEmploymentIT.java`
- **and a second half added at dispatch, which is not scope creep but the consequence of T-040.**
  `AuditAction.ROLE_CHANGED` is written in **exactly one place** in the backend — `RoleChangeService:96`
  — and T-040 deletes that service two days later. `frontend/app/audit/page.tsx:25` offers *"Role
  changed"* as an audit filter. Unless the staff path writes it, that filter matches nothing for ever
  and a privilege change on the only working door is filed under `STAFF_UPDATED` beside a corrected
  phone number. So T-039 also writes `ROLE_CHANGED` when `systemAccess` actually changes — an
  **additional**, narrower event scoped to the privilege change, not a replacement for the
  `STAFF_UPDATED` at `:231`. Two events for one request is right here: they are two acts that happen to
  arrive together, and the reader filtering for role changes wants the second.
- **acceptance:** an integration test attempts a self-demotion on the staff path, asserts the refusal,
  and asserts the audit row exists **after** the 403 — on the real database, under RLS, end to end
  through the endpoint rather than through a service call, because surviving the rollback is the whole
  point of `recordSeparately`; a successful access change writes `ROLE_CHANGED` alongside
  `STAFF_UPDATED`; a profile edit that does not touch access writes no `ROLE_CHANGED`.
- **reservations:** **none, confirmed at dispatch rather than assumed.** Both `ROLE_CHANGED`
  (`AuditAction.java:49`) and `ROLE_CHANGE_REJECTED` (`:55`) already exist — note the constant is
  `ROLE_CHANGE_REJECTED`, not `ROLE_CHANGE_REFUSED` as this row previously said. No migration, no
  permission, no error code: `CANNOT_CHANGE_OWN_ROLE` **`KMS-400022`** is what the staff path already
  raises and it is unchanged.
- **carried as a finding, deliberately not built:** `requireNotSelf` has a **second** call site,
  `:247`, with `CANNOT_DISABLE_SELF` — ending one's own employment — and that refusal is unaudited
  too. Same shape of gap, but a different act with no `AuditAction` for it, and inventing an audit
  constant is not a builder's to do. The builder reports it; it wants a constant and a task.
- **ordering:** **before T-040**, which deletes the only existing test of this property. And **before
  T-014** (wave 7), which is in the same file: `StaffEmploymentService.java`. That is a cross-wave
  serialisation of the kind this table already carries four of, not a collision — T-014 builds on this
  rather than beside it.
- **proof:** `docs/work/proof/T-039.md`
- **shipped:** `bd5398c`, 2026-09-07, wave 4c — shared with T-046 — T-046 removed the helper T-039 kept, so the file's final shape is the two together.

> **Built and green 2026-09-07. 15 tests, 15 passed, `BUILD SUCCESSFUL in 16s`, on real PostgreSQL
> under RLS through the lock.** The static `requireNotSelf` at the role-change site became a non-static
> `requireNotChangingOwnAccess(actor, before, attempted)` that writes `ROLE_CHANGE_REJECTED` through
> `recordSeparately` and then throws the identical `CANNOT_CHANGE_OWN_ROLE` **`KMS-400022`** with the
> same detail map — mirroring `RoleChangeService:105-117` rather than paraphrasing it. A
> `boolean accessChanged`, captured **before** the branch that reassigns `userId`, drives the second
> narrower `ROLE_CHANGED` event, written after the untouched `STAFF_UPDATED`.
>
> **The assertion that actually proves the property is a pair, not a single count.** The self-refusal
> test asserts `STAFF_UPDATED` is **zero** and `ROLE_CHANGE_REJECTED` is **one** after the same 403.
> Either alone would pass against a transaction that had not rolled back; together they say the write
> was discarded and the record was not.
>
> **It checked the premise the dispatch gave it instead of taking it.** `frontend/app/audit/page.tsx:22-27`
> hardcodes `ACTION_LABELS` and builds the filter from `Object.entries(...)`, so `ROLE_CHANGED` is fed
> by backend rows and nothing else; `grep -rn ROLE_CHANGED backend/src/main` finds the single writer at
> `RoleChangeService:96` that T-040 deletes. The second half of the task holds.
>
> **It reported a transient it was right not to touch.** `TenantUpdateIT.java` would not compile while
> it ran — four `cannot find symbol: method never()` after a missing static import — which is **T-041
> mid-edit in another builder's file**, and Gradle compiles all test sources whatever `--tests` selects.
> It left the file exactly as found, said so, and named `EmploymentBanIT` as wanting a re-run on the
> merged tree. That is the correct behaviour and the merged-tree run is where it is settled.
>
> **The `endEmployment` sibling stays a finding.** `requireNotSelf`'s other call site — `:247`,
> `CANNOT_DISABLE_SELF` **`KMS-400024`** — is still unaudited. Not built, deliberately: it is a
> different act, no existing `AuditAction` fits it, and filing a refused resignation under
> `ROLE_CHANGE_REJECTED` would be wrong. It needs a constant allocated and a task. The `requireNotSelf`
> javadoc now says so in the file, which is the right place for it.

### T-040 — Deleting the role endpoint that reads as a feature and is not one

- **id:** T-040
- **source:** T-018's finding, 2026-09-07; ruled by the coordinator the same day.
- **wave:** **4c-2**
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4c, commit `a83f4d5`. Was: **proven** *(2026-09-07)*. CI verdict, staging revision and digest are in the release report at the foot of this file. Not yet certified by observation.)*
- **what:** `changeUserRole` and `PATCH /api/v1/users/{id}/role` are dead. **Verified before this row
  was written**, which the ruling required: `grep -rn changeUserRole frontend` returns exactly one
  line, its own definition in `lib/api.ts:3350`; `RoleChangeService` is referenced only by
  `UserController`. Delete the wrapper, the endpoint, the service and its request DTO. The commit
  message says plainly that this was **dead code that read as a feature** — because that is precisely
  what produced docket item B10, and leaving a second one behind while naming the pattern would be the
  worse outcome.
- **the one thing that makes this ordered rather than trivial, found while verifying the ruling.**
  The endpoint is dead in the product but **not** untested: `backend/src/test/java/org/iskcon/kms/user/RoleChangeIT.java`
  exercises its guards, and it is **the only test in the repo asserting that a refused role change is
  audited**. Deleting it removes the only worked example and the only proof of the property that
  **T-039** is being built to establish on the staff path. So T-039 lands first, and T-040's proof must
  show T-039's assertion green before `RoleChangeIT` is removed. **If T-039 has not landed, stop.**
- **paths, as verified at dispatch:**
  - `backend/src/main/java/org/iskcon/kms/user/RoleChangeService.java` *(delete)*
  - `backend/src/main/java/org/iskcon/kms/user/ChangeRoleRequest.java` *(delete)*
  - `backend/src/test/java/org/iskcon/kms/user/RoleChangeIT.java` *(delete)*
  - `backend/src/main/java/org/iskcon/kms/user/UserController.java` — the `@PatchMapping("/{id}/role")` at `:70`, the field at `:32` and the constructor parameter at `:35`
  - `backend/src/main/java/org/iskcon/kms/user/UserManagementService.java` — one javadoc line at `:21` that points at the deleted class
- **not to be touched:** `frontend/app/audit/page.tsx:25-26`, which keeps both labels — after T-039 the
  staff path writes both actions, so the filter stays meaningful. `frontend/__tests__/audit.test.tsx`
  asserts the *"role changed"* option exists and must stay green untouched.
- **acceptance:** nothing references the deleted symbols; the full backend and frontend suites are
  green; `ROLE_CHANGED` and `ROLE_CHANGE_REJECTED` remain in `AuditAction.java` because T-039 writes
  both.
- **proof:** `docs/work/proof/T-040.md`
- **shipped:** `a83f4d5`, 2026-09-07, wave 4c.

> **Deleted and green 2026-09-07. Backend 24/24, frontend 14/14, `tsc` silent.** `audit.test.tsx` has a
> zero-line diff and does not appear in `git status`, which is the check that it was left alone rather
> than adjusted to stay green.
>
> **The gate held, and the proof shows it holding rather than asserting it.** The first line of the
> backend run, *after* the deletion, is
> `StaffEmploymentIT > a refused self role change is on the audit trail after the 403, not rolled back with it PASSED`.
> That is the property `RoleChangeIT` used to be the only test of. The ordering constraint this task
> carried for two days did exactly what it was for.
>
> **Three findings, and the second wants a ruling.**
>
> 1. **One dangling reference survives, correctly left alone.**
>    `backend/src/test/java/org/iskcon/kms/tenant/TenantUpdateIT.java:36` reads
>    *"for the reason `{@code RoleChangeIT}` gives:"* and now points at nothing. It is `{@code}` rather
>    than `{@link}`, so the build stays green with it in place — which is the whole problem: it is a
>    dead cross-reference that compiles. The builder left it because the file was dirty from another
>    builder in the same wave. **Folded into T-042's contract in 4c-3**, which is the next task in the
>    tenant area; the sentence it points at needs inlining (MockMvc over TestRestTemplate, because the
>    JDK HTTP client cannot `PATCH`).
> 2. **`CANNOT_ASSIGN_SUPER_ADMIN` `KMS-400023` now has no writer anywhere in the backend.** It was
>    thrown only by the deleted guard. It is untouched, correctly — `ErrorCode.java` is reserved and
>    codes are never reused — and `ErrorCodeTest` checks uniqueness and tone but never usage, so
>    nothing fails. **This is precisely the situation D-9 ruled on**, when `SESSION_EXPIRED` was
>    declared with finished copy and thrown nowhere: *"An error code that exists and is never thrown is
>    worse than none: the next person reads `ErrorCode.java` and believes the app says something it
>    does not."* By that precedent this code should be deleted and **`KMS-400023` retired forever**,
>    joining `KMS-400018` and the four no-successor codes in
>    `docs/ERROR-CODE-RENUMBER-2026-09-07.md`. **Not done, because retiring a number is permanent and
>    D-9 was Rajeev's ruling, not a work manager's.** Put to him.
> 3. **Guard 2 now holds structurally rather than by a check, which is stronger but different.**
>    `staff/SystemAccess.java` has exactly three constants and cannot express `SUPER_ADMIN`, so the
>    only remaining role-assigning path cannot represent the value that the deleted guard existed to
>    refuse. Worth having on the record rather than buried: the protection did not go, it changed kind.
>
> **The question the brief asked — is another `user/` endpoint in the same condition? No.**
> `GET /api/v1/users` has three callers (`app/users/page.tsx:40`, `app/staff/new/page.tsx:40`,
> `components/KitchenForm.tsx:29`) and `PATCH /{id}/status` has one (`app/users/page.tsx:158`).
> `UserController` is now two endpoints and both are live.
>
> **No hand smoke test was possible, and that is the finding rather than a gap:** there is no
> user-facing surface here, which is why the code was dead.
- **reservations:**
  - `frontend/lib/api.ts` — the `changeUserRole` **deletion** is the work manager's, in the dispatch
    pass, like any other edit to that file. The builder does not remove it itself.
  - migration: none. Error codes: none — and note that `CANNOT_CHANGE_OWN_ROLE` **`KMS-400022`** stays,
    since the staff path raises the same code.
- **proof:** —
- **shipped:** —

### T-043 — No event meal can be recorded, anywhere, and the failure is invisible

- **id:** T-043
- **source:** Rajeev's session, 2026-09-07, driving `/planner/catch-up` on staging as Temple Admin
  during the first verification pass over `docs/OUTSTANDING_BUILD_LIST.md`.
- **wave:** **4c-1** — and it outranks everything else in it.
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4c, commit `3c5b51d`. Was: **proven** *(2026-09-07)*. CI verdict, staging revision and digest are in the release report at the foot of this file. Not yet certified by observation.)*
- **what:** *Record this meal* was pressed four times on the event "Bhagavad Gita Parayanam",
  5 September. Nothing happened — no success, no error, no visible change. `fetch` was patched in the
  page to capture the exchange: the request body carried `planDate`, `mealKind: "Event"`, `note` and
  three dishes, and the server answered **404 `KMS-400030`** with a complete user-facing message and
  next step. **Two separate defects, and the second is what hid the first.**
- **defect 1 — `eventName` is never sent, so the meal cannot be found.** `RecordMealRequest`'s own
  javadoc says why the field exists: *"every event of every temple is called Event"*, so the date and
  the kind no longer say which preparation is being written down. `ServedMealService.record` resolves
  with `require(planDate, kind, request.eventName())`; absent, it resolves nothing. **The root is the
  client type, not the call:** `RecordMealInput` declared four fields and no `eventName`, so
  TypeScript could never have caught it — passing the field would have been a type error. The damning
  detail is that the same component passes `meal.eventName` correctly a few hundred lines away, to
  `requestJobCard` at `:226` and `jobCardLanguages` at `:196`. **The job card knows which event it is;
  the recording does not.** `MealServices.tsx` is the shared recording component used by
  `planner/page.tsx`, `planner/catch-up/page.tsx` and `DayView.tsx` alike, so this is every event
  meal, from every screen, always. Ordinary Breakfast/Lunch/Dinner recording works — which is exactly
  why it survived: `eventName` is legitimately absent for those.
- **what it costs:** recording is how stock is drawn. An event's ingredients are therefore **never
  consumed** — the store shows them on hand for ever, which is the very thing the Today nudge
  complains about, and for an event that nudge can never be cleared. Events are Phase 1 (E4-S15/S16).
- **defect 2 — the refusal never reached the person pressing the button**, and the builder is told to
  **establish where it actually went before fixing it**. The work manager's reading, offered to be
  verified rather than trusted: `MealServices` *does* call `onError(...)` at `:542`, and
  `catch-up/page.tsx` *does* render `{error && <ErrorNotice error={error} />}` at `:100` — but that
  notice sits at the top of the page, above every day section, so a refusal raised from a section
  further down appears off-screen with no scroll and no focus move. If that is the diagnosis then the
  bug is *"the refusal is rendered where nobody is looking"* and not *"the refusal is discarded"*, and
  the fix differs. Either way the requirement is the same: the server's own message and next step,
  visible at the point of action.
- **paths:**
  - `frontend/components/planner/MealServices.tsx`
  - `frontend/app/planner/page.tsx`
  - `frontend/app/planner/catch-up/page.tsx`
  - `frontend/components/planner/DayView.tsx`
  - `frontend/__tests__/meal-recording.test.tsx`
  - `frontend/__tests__/planner.test.tsx`
  - `frontend/__tests__/planner-day-routes.test.tsx`
- **acceptance:** a test on a **real event meal** — `mealKind: "Event"` with a populated `eventName` —
  asserts the object handed to `api.recordMeal` carries it. **A test that uses Breakfast passes today
  and proves nothing**, and is explicitly not acceptable as the headline case. Plus: an everyday meal
  sends `eventName: null`; a rejected recording shows the server's message *and* next step where the
  person is looking; `tsc` silent.
- **reservations:** `frontend/lib/api.ts` — `RecordMealInput` gains `eventName: string | null`,
  **required and nullable rather than optional**, written before dispatch. Optional was considered and
  rejected: optional is the property that allowed the omission in the first place. It is the type
  change and not the call change that fixes the class of defect.
- **proof:** `docs/work/proof/T-043.md`
- **shipped:** `3c5b51d`, 2026-09-07, wave 4c.

> **Built and green 2026-09-07, and it came in under its contract rather than at it.** Two files
> changed, not seven: `MealServices.tsx` and `meal-recording.test.tsx`. The three consumer screens and
> two of the three test files in the contract needed no edit at all, because **both fixes belong in the
> shared component** — so all three screens get them and no consumer's `onError` contract moves.
> `tsc` silent, `Test Files 3 passed (3) / Tests 53 passed (53)`.
>
> **Defect 2's diagnosis was established, not assumed, and the work manager's reading was right.** The
> refusal was never discarded: all three consumers render `{error && <ErrorNotice …>}` at the top of
> the screen — `catch-up:100` above as many as seven day sections, `DayView:57`, `planner/page.tsx:167`
> — while `onError` is handed to a `MealServices` far below it. *Rendered where nobody is looking.* The
> fix puts an `ErrorNotice` immediately above the *Record this meal* button, copying the pattern
> `VendorStatusDialog.tsx` already uses rather than inventing one.
>
> **The best thing in this proof is that it proves its own tests are not vacuous.** Inside a single
> hold of the verify lock the builder patched both fixed lines back out, re-ran, got
> `Tests 3 failed | 10 passed` with `expected … to have property "eventName" with value null`, and
> restored the file through an `EXIT` trap, then diffed it byte-for-byte against the verified copy.
> That is a stronger claim than "the tests pass" and it is cheap; it is worth asking for wherever a
> test asserts the absence of a defect that was invisible for months.
>
> **One deviation, flagged rather than taken quietly.** The recording refusal is no longer *also*
> bubbled to `onError`. The builder's reason: none of the three pages ever clears its `error` state, so
> bubbling would leave a stale red banner contradicting the green success after a retry. Pinned by a
> test, and a one-line change if the coordinator wants both. **The unclearedstate is itself a finding**
> — four more misplaced-feedback cases in the consumer screens are named in the proof and deliberately
> left untouched.
>
> **The server was never wrong, and the proof establishes that too.** `MealRecordingIT` contains no
> occurrence of `Event` or `eventName` and does not cover an event recording — but `EventIdentityIT`'s
> `recordingOneEventLeavesTheOtherOpen()` (`:184`) POSTs `{"mealKind":"Event","eventName":…}` and
> expects 200 with the name echoed. The defect was purely client-side, which is exactly why every
> backend test stayed green through it.
>
> **Not hand smoke-tested** — a `next build` writes `.next/` outside the verify lock in a shared
> checkout, and the builder does not deploy. Worth two minutes on staging: record an event from
> `/planner/catch-up` and watch both the success and a refusal land where you are looking.

> **The general lesson, and the reason a separate read-only sweep was launched beside this wave.** The
> type agreed with the caller and **both disagreed with the server**, so every tool in the stack
> reported green: `tsc`, the tests, the reviewer's eye. Nothing in this arrangement catches that,
> because everything in this arrangement checks the frontend against itself. An `Explore` agent was
> dispatched alongside wave 4c-1 to diff **every** request type in `frontend/lib/api.ts` against its
> server DTO in both directions — fields the server declares and the client lacks (this defect's
> shape, ranked by whether the server *resolves* with the missing field, merely *validates* on it, or
> only *stores* it), fields the client sends that Jackson silently discards, and server-required
> fields that are optional on the client. It writes nothing, so it cannot collide with a builder. Its
> findings go to Rajeev, not into this wave.
>
> This is the second time in three waves that the sharpest finding has been about *where a value is
> resolved*. Wave 4b's lesson was that a name stored in the database is resolved on the server, so
> grepping the frontend proves nothing. This one is the mirror: a field the server resolves with, that
> the client type does not know exists, cannot be caught by any amount of type checking on either side
> alone. **Both are contract questions, and this repo has no mechanical check of that contract.**

### T-041 — The temple edit screen narrows, and the calendar rebuild goes with it

- **id:** T-041
- **source:** `DECISIONS.md` **D-17**, ruled by Rajeev 2026-09-07, working field by field over the
  screen T-008 had shipped the day before.
- **wave:** **4c-1**
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4c, commit `61e9f19`. Was: **proven** *(2026-09-07)*. CI verdict, staging revision and digest are in the release report at the foot of this file. Not yet certified by observation.)*
- **what:** On `/tenants/[id]/edit`, **latitude, longitude, currency and timezone become read-only** —
  displayed, not editable. Name, address and 80G approval stay editable. D-17's table carries the
  reasoning per field and the builder is told to read it rather than infer it: a building does not
  move; a currency changed after money is recorded shows invoices, payments and donations in a currency
  they were never in; and a temple that cannot move cannot change timezone either. The endpoint is a
  whole-record replacement, so the form still **sends** all seven fields, taking the frozen four from
  the temple it loaded — and it must not do that with disabled inputs, which submit nothing.
- **and it deletes the calendar-rebuild path.** D-17: the rebuild *"was never fixing a defect; it was
  the safety requirement of allowing the field to be edited, and the field is no longer edited."* So
  `reprecomputeCalendar`, the `CalendarPrecomputeScheduler` field, its constructor parameter and its
  import all come out of `TenantUpdateService`, and **T-008's test asserting the enqueue goes with
  them** — it now asserts something that should not happen.
- **the one place this widened the instruction, and it is deliberate.** The brief said the backend
  keeps accepting the fields so nothing breaks. Taken literally — screen-only narrowing plus a deleted
  enqueue — an operator with `MANAGE_TENANTS` could still `PATCH` a new timezone by hand, the column
  would change, and `calendar_days` would keep the tithi, Ekadashi dates and sunrise times of the old
  zone with nothing anywhere saying so. **That is strictly worse than today**, and it is dead machinery
  of exactly the kind T-040 exists to remove — a guarantee removed while the thing it guarded stayed
  reachable. So the four frozen fields are made read-only **at the layer that enforces it**, in the
  shape this file already has for `slug`: the field is still accepted in the payload, and a value that
  **differs from what is stored** is refused with a field-level message. Then the rebuild is genuinely
  unreachable rather than merely unused, and deleting it is honest. Flagged to the coordinator as a
  deviation rather than made quietly.
- **the trap named in the brief, because it would have looked like the builder's bug.** `latitude` and
  `longitude` are `BigDecimal` at scale 6, so the row holds `12.971600` while the form sends `12.9716`.
  **`BigDecimal.equals` is scale-sensitive** and would call those different, refusing the operator's own
  unchanged values and making the screen unsaveable. Compare with `compareTo(...) == 0`. This is the
  same scale difference that made this endpoint's first audit snapshot claim every temple had moved —
  the best find of wave 4b, met here from the other side.
- **paths:**
  - `frontend/app/tenants/[id]/edit/page.tsx`
  - `frontend/__tests__/tenant-edit.test.tsx`
  - `backend/src/main/java/org/iskcon/kms/tenant/TenantUpdateService.java`
  - `backend/src/main/java/org/iskcon/kms/tenant/UpdateTenantRequest.java`
  - `backend/src/test/java/org/iskcon/kms/tenant/TenantUpdateIT.java`
- **forbidden:** `frontend/lib/api.ts` (reserved; `UpdateTenantInput` keeps all seven fields and needs
  nothing); `frontend/app/tenants/new/page.tsx` (T-042's, next sub-wave); `frontend/app/tenants/[id]/page.tsx`;
  `TenantController.java`; everything under `backend/.../calendar/`; and
  `frontend/__tests__/design-system.test.ts` — see below.
- **acceptance:** the four frozen fields are visible with no control offering to change them; a PATCH
  changing any of them is refused with a readable field-level message; a PATCH repeating the stored
  values, **including a differently-scaled latitude**, succeeds; name, address and 80G still save and
  are still audited as `TENANT_UPDATED`; no reference to `CalendarPrecomputeScheduler` survives in the
  tenant package or its tests.
- **reservations:** none. No migration, no error code — the refusals reuse `VALIDATION_FAILED` with a
  field-level message, exactly as `rejectSlugChange` does.
- **proof:** `docs/work/proof/T-041.md`
- **shipped:** `61e9f19`, 2026-09-07, wave 4c — carries `TenantUpdateIT.java` whole, including T-042's four javadoc lines: the two edits share one paragraph.

> **Built and green 2026-09-07.** Backend `15 passed, 0 failed, BUILD SUCCESSFUL`; frontend
> `Tests 34 passed (34)` across `tenant-edit.test.tsx` and `design-system.test.ts`; `tsc --noEmit`
> silent. The five enqueue assertions and the scheduler spy were **removed, not weakened**.
>
> **The frozen four are presented as a `<dl>` of label/value pairs** in a *"Fixed when the temple was
> created"* section, laid out the way the temple's own page lays out its details, beside the web
> address that was already read-only. No disabled inputs anywhere.
>
> **The payload question was answered better than the brief's own suggestion.** The frozen four are
> read off the loaded `temple` rather than carried in hidden inputs, and the reason is written into the
> file: a hidden input round-trips `latitude` through `Number()`, so **a lost value arrives as `0`
> rather than as an error** — which is precisely the defect the sweep found in T-044, met here in
> advance — and a hidden input is editable from devtools, which is exactly the offer this screen has
> just stopped making.
>
> **`rejectFrozenFieldChanges` collects every offending field** rather than throwing on the first, so a
> caller holding a stale record is told about all four at once. `compareTo(...) == 0` for the two
> `BigDecimal`s, with the reason written in, and the unchanged-save test uses a **third** scale —
> `12.97160000` against a stored `12.971600` — which is a better test than the two the brief asked for.
>
> **Acceptance criterion 5 cannot be met literally, and the builder said so rather than fudging it.**
> `CalendarPrecomputeScheduler` still appears twice in the tenant package, in
> `TenantProvisioningService` — a brand-new temple has no calendar until it is enqueued, so that use is
> correct and permanent, and the file was outside the contract. Nothing in `TenantUpdateService` or its
> test refers to it, which is what the criterion was reaching for.
>
> **The guard test bit again, in the other direction, and the builder fixed the right file.**
> `design-system.test.ts`'s copy rule caught a new paragraph that spliced sentences with semicolons.
> It corrected its own page and did not touch the test — the file it had been forbidden — which is the
> behaviour the forbidden list exists to produce.
>
> **Not hand smoke-tested:** it needs a `next build`, which would hold the shared lock through a full
> build. The proof names what to look at on staging.

> **The dead exemption, and why it is the work manager's.** `design-system.test.ts`'s hard-coded-timezone
> guard exempts three files, one of which is this page (`:81`), because it held a `TIMEZONES` list
> containing `Asia/Kolkata`. Removing the picker removes the literal, so the exemption comes to exclude
> nothing — the guard still passes either way, which is why this is tidiness rather than a fix. The
> builder is told to leave the file alone and say so; the work manager removes the dead line after every
> builder is out of the tree and before the merged-tree run. It joins the `lib/api.ts` exemption that
> wave 4b recorded as already dead for the same reason. **Two of the three exemptions in that list will
> then exclude nothing**, which is worth knowing before somebody reads the list as a statement about the
> codebase.

### T-044 — Editing a delivery event re-pins it to the Gulf of Guinea

- **id:** T-044
- **source:** the read-only client/server contract sweep launched beside wave 4c-1, 2026-09-07 —
  the sweep T-043 caused.
- **wave:** **4c-2**
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4c, commit `66a35f8`. Was: **proven** *(2026-09-07)*. CI verdict, staging revision and digest are in the release report at the foot of this file. Not yet certified by observation.)*
- **what:** **This is `eventName`'s defect one rung worse: it does not fail, it succeeds and stores a
  wrong pin on the sheet a driver acts on.** `CreateMealPlanInput` (`api.ts:1342-1398`) and
  `UpdateMealPlanInput` (`:1404`) declare no `deliveryLatitude`/`deliveryLongitude`, though
  `CreateMealPlanRequest:73-74` and `UpdateMealPlanRequest:61-62` both do. Four links, and all four are
  needed:
  1. The input types never declared the pair, so nothing forced `MealPlanView` to return it either —
     `api.ts:1077` carries `deliveryPlaceId` and no coordinates.
  2. On **edit**, `MealComposer.tsx:177-183` therefore rebuilds the picked place as
     `{ placeId, latitude: 0, longitude: 0 }`. The zeroes are placeholders; they are all it has.
  3. `mealFacts()` sends them. **It compiles only because `mealFacts` has no return-type annotation**
     (`MealComposer.tsx:769`) and its result is *spread* at `:826`/`:836` — spread properties are exempt
     from TypeScript's excess-property check. Every other payload builder in the app **is** annotated
     (`readShiftForm`→`ShiftInput`, `collect()`→`IngredientRequestInput`, `build()`→`KitchenInput`), so
     this is the one escape hatch in the codebase and it is where the bug lives.
  4. `MealPlanService.isPlaced()` (`:1328-1330`) is `latitude != null && longitude != null` — **it does
     not consult `placeId`, and `0` is not null.** So `place()` short-circuits both Places and the
     geocoder, returns `new Located(0, 0, now(), null)`, and `:494` writes it.
  The read path is defeated by the same zeroes: `api.ts:3921` prefers the coordinates "when both
  non-null" and only falls back to `placeId` otherwise, so the on-screen estimate at
  `MealComposer.tsx:309` is computed from Null Island too.
- **the irony worth keeping:** `MealPlanService.java:1147-1153` documents fixing the *previous*
  incarnation of this bug on 2026-09-05, and `MealComposer.tsx:784-786` documents sending the pin for
  exactly that reason. **The type was never updated to match, and the `0/0` seed slipped in
  underneath.** A comment recording a fix is not a test of it.
- **paths:** allocated at 4c-2 dispatch. The frame is `frontend/components/planner/MealComposer.tsx`,
  `backend/.../meal/MealPlanService.java`, `backend/.../meal/MealPlanView.java`, a backend IT and a
  frontend test. Disjoint from T-040 (`user/`), T-042 (`geo/`, `tenants/new`) and T-045 (`ingredients`).
- **acceptance:** editing a placed delivery event and saving leaves its coordinates **unchanged**,
  asserted on the real database rather than on the screen; `mealFacts()` carries a return-type
  annotation so the escape hatch closes; `isPlaced()` no longer treats `0,0` as a placed event; a test
  covers the *edit* path specifically, since create was never broken.
- **reservations:** `frontend/lib/api.ts` — the coordinate pair on both input types and on
  `MealPlanView`. **Required-nullable, not optional**, for the reason T-043 established.
- **proof:** `docs/work/proof/T-044.md`
- **shipped:** `66a35f8`, 2026-09-07, wave 4c.

> **Built and green 2026-09-07. Frontend `Tests 105 passed (105)` across its four contract files with
> `tsc` silent; backend `MealPlanIT` 18/18.**
>
> ### It corrected the brief's central claim, and the correction matters beyond this task
>
> The brief — and the sweep that produced it, and this file's own summary of both — said the defect
> compiled *because* `mealFacts()` lacked a return-type annotation and its result was spread, spread
> properties being exempt from the excess-property check. **The builder's negative control disproved
> half of that.** Control C1 removed the annotation *and* the two pin fields, and `tsc` **still
> errored**: a spread exempts *excess* properties from checking, **not missing required ones**.
>
> So the accurate account is two-sided and neither half alone is the story. The spread exemption is
> what let the composer send `deliveryLatitude` while the type did not declare it — that is how `0,0`
> reached the wire unremarked. But what **closes** the hole is the required-and-nullable declaration in
> `api.ts`, not the annotation. What the annotation buys is *where the error lands*: one error at the
> builder (`:816`) instead of two at the call sites (`:871`, `:880`). The proof claims that and no
> more, which is the right size of claim.
>
> **Worth keeping as a method note:** the negative control was not merely a check that the tests bite.
> It falsified a stated mechanism in the brief that three separate documents had already repeated. A
> control that tests the *explanation* rather than only the *fix* is worth more than either.
>
> ### Sentinel or semantics — both, and the asymmetry is the argument
>
> `isPlaced()` is now `placeId != null && isCoordinate(latitude) && isCoordinate(longitude)`.
> The **semantic** fix is `placeId != null`: the method claimed *"somebody chose this"* while never
> consulting the only field that could support the claim. But that alone does not stop the defect
> arriving from the wire, because the old composer sent a **real place id** beside the zeroes — hence
> `isCoordinate()`, which refuses exactly `0` on either axis.
>
> The bluntness is justified by an asymmetry the builder stated well: **a genuine pick wrongly refused
> still has its id and is re-resolved from Places at the cost of one API call; a placeholder wrongly
> accepted reaches a driver's job card.** `fresh()` uses the same predicate, so rows already poisoned
> go stale rather than being trusted for thirty days.
>
> On the composer side, `placed` reopens an edit on the real stored pin, and its type allows
> `latitude: null` — so an **older** plan carrying a place id and no pin keeps the id and sends `null`,
> and the server asks Places by id rather than losing the pick. That case would have been easy to
> break while fixing this one.
>
> **Not hand smoke-tested, and it notes why that matters more here than usually:** the defect renders
> **invisibly**. Address, contact and serving time all look right on the screen while the pin is wrong.
> There is nothing for an eye to catch, which is why it survived a fix on 2026-09-05 that documented
> itself in a comment.

### T-048 — The temples already pinned to the Gulf of Guinea

- **id:** T-048
- **source:** found by T-044's builder while fixing the cause, 2026-09-07. **The cause is fixed; this
  is the damage already done.**
- **wave:** **4c-3**
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4c, commit `9b0395d`. Was: **proven** *(2026-09-07)*. CI verdict, staging revision and digest are in the release report at the foot of this file. Not yet certified by observation.)*
- **what:** T-044 stopped new `0,0` pins being written. It did not clean up the ones already stored.
  **Any delivery event edited since 2026-09-05 may hold `delivery_latitude = 0` and
  `delivery_longitude = 0` today**, and staging is a real environment with real test data on it.
- **why it does not self-heal, which was the tempting assumption.** T-044 made `fresh()` use the same
  predicate as `isPlaced()`, so a poisoned row goes **stale** rather than being trusted for thirty
  days — and a stale row is re-resolved from the address text. But re-resolving from address text is
  exactly what the place picker exists because it often **cannot** do: a sub-premise, a landmark, a
  half-written pincode. So the self-healing covers the easy cases and leaves the hard ones, which are
  the ones the picker was built for. That is not good enough for a pin a driver follows.
- **the honest fix:** a migration nulling `delivery_latitude`, `delivery_longitude` and
  `geocoded_at` wherever both coordinates are exactly zero — **and leaving `delivery_place_id` alone.**
  Null is the correct value for the pin: it means *"we do not know where this is"*, which is true, and
  it makes the row ask again rather than assert something false. Per-tenant and under RLS, like every
  other backfill in this project.
- **the coordinator's proposed condition was `0,0` *and no* `place_id`, and it would have matched
  nothing. Pushed back, 2026-09-07.** The intent behind it is right and is kept: do not destroy a
  genuine pick. But the discriminator does not work here, because **every poisoned row has a real
  place id.** T-044's report is explicit — the old composer rebuilt the place as
  `{ placeId: openDish.deliveryPlaceId, latitude: 0, longitude: 0 }`, so it sent a true `place_id`
  beside the placeholder zeroes. Restricting the backfill to rows *without* a place id would therefore
  skip the entire population it exists to repair, while a genuine Gulf-of-Guinea pick would have a
  place id too — so the field does not separate the two cases in either direction.
  **What protects the pick is not touching `place_id`.** Cleared coordinates plus a surviving place id
  is exactly the state T-044 built the recovery for: `isPlaced()` reads false, the row goes stale, and
  the server re-resolves the pin **from Places by id** — one API call, and the pick is recovered
  losslessly. So the coordinator's requirement is met more completely by leaving a column alone than by
  filtering on it.
- **reservations:** migration **`V97`** —
  `backend/src/main/resources/db/migration/V97__unpin_the_gulf_of_guinea.sql`. Taking it slid
  `V97`–`V106` to `V98`–`V107`, filenames included and verified against the table afterwards. **This
  is the second slide in one day and the fourth in the batch**; see the note above the migration table.
- **paths:** the migration, plus a test asserting the backfill leaves a genuine pin alone and clears a
  zeroed one. **No product code** — T-044 already fixed the behaviour.
- **acceptance:** a row at exactly `0,0` has its two coordinates and `geocoded_at` nulled and its
  `delivery_place_id` **untouched**; a row with a real pin is untouched entirely; a row with one zero
  axis and one real is **reported rather than guessed at** — decide and justify, since `0` is a valid
  latitude at the equator even though `0,0` together is not a temple in India.
- **also needed, and it is explicitly not the builder's:** staging's database is on a private IP, so
  a count needs a throwaway Cloud Run job on the VPC or Cloud SQL Studio. **The coordinator is asking
  Rajeev for it.** The migration is built to be correct whatever the number turns out to be; the
  builder states in its proof how many rows its `WHERE` clause would match **on its own test
  fixtures**, and how it decided, which is the part it can actually answer.
- **proof:** `docs/work/proof/T-048.md`
- **shipped:** `9b0395d`, 2026-09-07, wave 4c.

> **Built and green 2026-09-07.** A per-tenant, RLS-respecting `DO` loop following V94's pattern, with
> the `tenant_id` filter **inside** the loop, nulling the two coordinates and `geocoded_at` where both
> axes are exactly zero — and `delivery_place_id` in neither the `SET` nor the `WHERE`, which is the
> whole recovery path. `DeliveryPinBackfillIT` migrates a throwaway database to V96, seeds two temples
> and eight delivery events, then applies the rest of the history on top, modelled on
> `CateringMigrationIT`/`TenantLoopMigrationIT` — so the loop body is **planned against real rows**
> rather than against an empty table, which is how a per-tenant backfill test can otherwise pass while
> proving nothing.
>
> ### The single-zero decision, and the argument is better than the one the brief offered
>
> **A row with one zero axis is left alone.** The brief expected the reasoning to be *"0 is a valid
> latitude at the equator"* — true, but weak on its own. The builder's argument is stronger and is the
> one to keep: **nulling one would change nothing the application does.** T-044's `isCoordinate()`
> already refuses zero on either axis, so a single-zero row is *already* unplaced, *already* stale, and
> *already* re-resolved on the next read by place id or address. Nulling it produces behaviour
> indistinguishable from leaving it, and differs only in destroying a stored datum irreversibly.
> **Given a tie, do not destroy evidence.**
>
> It is argued in the migration comment and **pinned by two assertions, so a later tidy of the `WHERE`
> into an `OR` fails the build.** That is the part worth copying: a decision that costs nothing today
> is exactly the kind a future reader "simplifies" away, and the test is what makes the reasoning
> load-bearing instead of decorative. One line to reverse if Rajeev wants the other answer, and the
> test names the two assertions to flip.
>
> ### The negative control produced the defect itself, in a row
>
> Neutralising the `WHERE` gave:
> ```
> expected: "(none)|(none)|(none)|place-poisoned-a"
>  but was: "0.000000|0.000000|2026-09-05 09:15:00+05:30|place-poisoned-a"
> ```
> **That is the live defect, with a real place id sitting beside the zeroes** — which is also the
> clearest possible confirmation that the coordinator's proposed `no place_id` filter would have
> matched nothing. Restored via `trap ... EXIT`, diffed against an independent pre-patch copy
> (`identical`), re-run green, all in one lock hold.
>
> ### Counts, and what nobody should read into them
>
> **Three of eight fixture rows matched** — two poisoned rows in temple A (one with a place id, one
> without) and one in temple B. The five that did not: two genuine pins, the two single-zero rows, and
> one never-located row. **Staging was not counted and could not be**, and the proof says so plainly:
> *"nobody should read 3 as a production figure."* That count is the coordinator's ask of Rajeev and
> needs a Cloud Run job on the VPC.
>
> **One honest gap, stated rather than papered over:** the migration `RAISE NOTICE`s its own total —
> sensible, because once the rows are repaired there is no query left that says how many there were —
> but the builder **did not verify that Flyway surfaces the notice**, and wrote that into the comment
> instead of claiming it. If the number matters on the day, query it before deploying.

### T-045 — No ingredient added after provisioning can be marked Ekadashi-prohibited

- **id:** T-045
- **source:** the same sweep, 2026-09-07.
- **wave:** **4c-3** — moved out of 4c-2 at the reservation pass; see the wave table for why.
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4c, commit `4c807e6`. Was: **proven** *(2026-09-07, after one stop and one granted file)*. CI verdict, staging revision and digest are in the release report at the foot of this file. Not yet certified by observation.)*
- **what:** `ekadashiProhibited` is unreachable from the client **three times over**: the create type
  omits it (`api.ts:599-605`) against `CreateIngredientRequest:29`; `IngredientView` (`:589-597`) does
  not expose it, so no screen can show it; and the server's dedicated
  `PATCH /api/v1/ingredients/{id}/ekadashi-flag` (`IngredientController.java:92-99`) **has no wrapper
  in `api.ts` at all** — only its sattvic twin does (`api.ts:3524-3529`, called from
  `app/ingredients/page.tsx:150`). The Java side is complete and audited
  (`IngredientService.java:85, 107, 178-188`). Values reach the column **only** from the provisioning
  seed (`TenantProvisioningService.java:262`).
- **why it is silent, and it is the same mechanism as T-043:** the record component is a primitive
  `boolean`, so an absent JSON key deserialises to `false` — the permissive answer. Nothing anywhere
  says no.
- **what it costs:** `EkadashiPolicy.of()` (`EkadashiPolicy.java:26-33`) and `RecipeService.java:91`
  decide from this column which recipes may be cooked on Ekadashi. So the composer offers grain dishes
  on a fasting day for every ingredient a temple has added since it was provisioned. **This is a
  religious-observance defect, not a data one**, which is why it is scheduled rather than filed.
- **the shape is not the builder's to invent:** mirror the sattvic toggle that already exists on the
  same screen, exactly. If that pattern cannot be mirrored, stop and report rather than designing a
  second one — `DECISIONS.md` D-3 is the standing rule.
- **paths:** allocated at 4c-2 dispatch; the frame is `frontend/app/ingredients/page.tsx`, its test,
  and possibly `frontend/app/ingredients/new/page.tsx`.
- **reservations:** `frontend/lib/api.ts` — `ekadashiProhibited` on `CreateIngredientInput` and
  `IngredientView`, plus the missing `setEkadashiFlag` wrapper mirroring the sattvic one.
- **proof:** `docs/work/proof/T-045.md`
- **shipped:** `4c807e6`, 2026-09-07, wave 4c.

> ### Stopped, widened by one file after an ownership check, then green — 2026-09-07
>
> **`tsc --noEmit` exit 0, down from 8 errors across 7 files at baseline; 76/76 targeted tests across
> eight files; two negative controls in one lock hold with a trap restore.**
>
> **It stopped rather than widened, and both workarounds it rejected were the tempting ones.** The
> create form is `frontend/components/IngredientForm.tsx`, which the contract did not name —
> `app/ingredients/new/page.tsx` only *hosts* it, and because `onSubmit` is typed inside the component
> the type error was unreachable from anything writable. Inlining a second form in the page would have
> duplicated a shared component and invented a parallel pattern, which **D-3 forbids by name**; an
> `<input form="add-ingredient">` outside the form would have reached `FormData` and never been read by
> the component's own `submit`. Either would have produced a green build and a wrong design.
>
> **The contract was wrong and the error was the work manager's.** The brief said six fixtures would
> break. It was seven: the pass that wrote the `api.ts` reservation checked which **test files** named
> `IngredientView` and never checked which **components** construct `CreateIngredientInput`, so
> `IngredientForm.tsx` was already broken before the builder started. Recorded as a planning error
> rather than folded quietly into the widening.
>
> **Ownership checked before granting**, which is the step that makes a mid-wave widening safe: the
> only other claim on that file is **T-023 in wave 5** — not flying, weeks away — the file was clean in
> `git status`, and no other 4c-3 contract named it.
>
> **The test that pins the actual defect, and it is the sharpest thing here.** The create-form test
> *"says so out loud when the flag is not ticked, rather than leaving the key out"* deliberately avoids
> `objectContaining`, **because a missing property and an explicit `false` read identically to it.** It
> inspects `Object.keys(payload)` and then asserts `.toBe(false)`. That pins the exact silence that
> caused the defect — a primitive `boolean` deserialising an absent key to the permissive answer —
> rather than testing around it. Any task fixing a "field the client never sends" defect should be held
> to this: `objectContaining` cannot tell absence from `false`, and absence was the bug.
>
> **A caveat it volunteered rather than let a reader assume.** Patching the create form out fails **3
> of its 4** new tests, not 4 — the fourth asserts an *absence* (*"keeps the Ekadashi flag from kitchen
> staff"*) and so passes vacuously when the feature is gone. That is correct rather than a gap, and
> flagging it is what stops "4 new tests, 3 failures" reading as a hole. **A negative control's count
> needs its own explanation whenever any test asserts a negative.**
>
> **Three edits declared for veto and approved.** Its change falsified three counts the files state
> about themselves — `IngredientForm.tsx`'s *"Five fields with the sattvic flag, four without"*, the
> `isAdmin` prop's *"Only an administrator may declare an ingredient sattvic-prohibited"*, and
> `app/ingredients/new/page.tsx`'s *"four fields, five for an administrator"*. Its reasoning is right
> and is the rule to keep: **fixing a comment your own edit made untrue is not the same as improving
> the file**, and in a repo that comments to explain *why*, leaving the lie is worse than the edit. The
> design conclusion each comment draws — a screen not a panel, threshold four — is unchanged, and all
> three revert independently of the code.
>
> **Two things left open.** The audit label T-046 added and T-045 reworded (*"Tried to end their own
> employment"*) **has no test**, because `__tests__/audit.test.tsx` was outside the contract and asserts
> only the "Role changed" cell — one line for whoever next holds that file. And the
> **two-identical-toggles** finding stands: both controls now carry the accessible name
> "Allowed"/"Prohibited", and labelling them properly means changing the *existing* sattvic control, so
> it goes to Rajeev. `flagCell()` locates a column by its header, so labels can be added later without
> touching a single test — which is what makes that finding cheap to act on.
>
> **Not hand smoke-tested:** both surfaces have been seen only by jsdom. `tsc` is clean now, so a build
> is no longer blocked and it wants driving once on UAT.

> ### What the sweep found, and what it did not — 2026-09-07
>
> Launched read-only beside wave 4c-1 because T-043 exposed a class of defect nothing in this
> arrangement catches: **the client type agreed with the caller and both disagreed with the server**, so
> `tsc`, the tests and the reviewer's eye all reported green. It diffed all 50 request/input types in
> `frontend/lib/api.ts`, plus ~26 inline body literals and every query-string builder, against their
> server counterparts in both directions.
>
> **Two findings, and the first is worse than the defect that prompted the sweep** — T-044 stores a
> wrong pin rather than failing to store anything. T-045 is quieter and is a religious-observance
> defect. Both are scheduled above.
>
> **The clean results matter and are stated rather than omitted.** No field the client sends is dropped
> by Jackson anywhere. No server-required field is optional on the client — every `@NotNull` /
> `@NotBlank` / `@NotEmpty` component was cross-checked. Every `URLSearchParams` builder matches its
> controller's `@RequestParam` set exactly, the filter groups included. And the job-card endpoints
> already pass `eventName` as a **required positional `string | null`** (`api.ts:4088`, `:4109`,
> `:4135`) — which is to say the fix T-043 applied to `RecordMealInput` was already the house pattern
> three lines away from the defect.
>
> **Two omissions confirmed deliberate, not defects.** `UpdateTenantInput` lacks `slug` — the server's
> class note names `api.ts` and explains the field exists *so a slug that arrives is refused rather
> than silently dropped*. And `HireStaffInput` / `UpdateStaffInput` / `RaiseBanInput` lack `aadhaar`,
> documented on both sides as an unwired seam.
>
> **The generalisable finding — as first written, and as corrected by the task it produced.**
>
> The sweep concluded, and this file repeated, that T-044 compiled *because* `mealFacts()` lacked a
> return-type annotation and its result was spread, spread properties being exempt from the
> excess-property check; and therefore that annotating every payload builder was the whole defence and
> a lint rule waiting to be written. **T-044's negative control falsified half of that.** Removing the
> annotation *and* the two fields still produced a `tsc` error: **a spread exempts excess properties,
> not missing required ones.**
>
> The corrected account has two halves and needs both. The spread exemption is what let the composer
> **send** `deliveryLatitude` while the type did not declare it — that is how `0,0` reached the wire
> with nothing complaining. What **closes** the hole is the required-and-nullable declaration in
> `api.ts`; the annotation only moves where the error lands, from two call sites to the one builder.
> So the lint rule is still worth writing, but for **legibility of failure** rather than for
> detection — and the actual defence is the one T-043 established and this task confirmed:
> **declare the field, required, on the client type.**
>
> Recorded at length because three separate documents had already repeated the wrong mechanism before
> a builder tested it. **A negative control that checks the explanation, rather than only the fix, is
> worth more than either** — and none of the three documents would ever have been corrected by a
> passing test.

### T-046 — A refused attempt to end your own employment is recorded

- **id:** T-046
- **source:** found by T-039's builder while building it, 2026-09-07; **ruled by the coordinator the
  same day** — *"A refused attempt to change your own employment is exactly the record that should
  exist and does not."*
- **wave:** **4c-2**
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4c, commit `bd5398c`. Was: **proven** *(2026-09-07)*. CI verdict, staging revision and digest are in the release report at the foot of this file. Not yet certified by observation.)*
- **what:** `requireNotSelf` in `StaffEmploymentService` has **two** call sites. T-039 fixed the first
  — `:180`, `CANNOT_CHANGE_OWN_ROLE`. The second, `:247` with `CANNOT_DISABLE_SELF`
  **`KMS-400024`**, still throws and writes nothing. Same shape, same fix: record through
  `AuditService.recordSeparately` so the record survives the 403, then throw the identical error
  unchanged. T-039's builder left notes in its proof for whoever took this — read them first.
- **why it was not folded into T-039:** it is a different **act**, and no existing `AuditAction` fits
  it. Filing a refused resignation under `ROLE_CHANGE_REJECTED` would be wrong, and inventing an audit
  constant is not a builder's to do. That is the whole reason it needed its own id rather than a
  second acceptance criterion.
- **reservations:** `backend/.../audit/AuditAction.java` — a new constant, allocated and written by the
  work manager before dispatch, between the employment actions rather than beside the role ones,
  because that is where a reader of that file will look for it. **No migration** — `V3__audit_events.sql:98`
  constrains `action` only with `length(action) > 0` and has no enumerated `CHECK`, verified when
  `TENANT_UPDATED` was allocated in wave 4b. No error code: `CANNOT_DISABLE_SELF` already exists and is
  unchanged. No permission.
- **paths:** `backend/.../staff/StaffEmploymentService.java`,
  `backend/src/test/java/org/iskcon/kms/staff/StaffEmploymentIT.java`.
- **ordering:** after T-039 (done), and **before T-014** in wave 7 — the third task in a row to take
  this file, and the reason the cross-wave note on T-039 matters.
- **acceptance:** an integration test attempts to end one's own employment through the endpoint,
  asserts the refusal, and asserts the audit row exists **after** the 403 — real database, under RLS.
  Plus a negative control: with the record removed, the test fails.
- **proof:** `docs/work/proof/T-046.md`
- **shipped:** `bd5398c`, 2026-09-07, wave 4c — shared with T-039, for the same reason.

> **Built and green 2026-09-07. Backend 16/16 — T-039's fifteen all still green — `tsc` silent,
> `audit.test.tsx` 6/6, and the audit screen's diff is `1 +`.**
>
> **The negative control was run as asked and behaved exactly as a good one should:** with the
> recording call patched out, **exactly one** test failed — `expected: 1 but was: 0` on the refusal-row
> count — and the restored file diffed byte-identical to the pre-control copy. One failure and not
> five is the useful signal: it says the new test is specific to the new behaviour rather than
> entangled with the rest of the file.
>
> **The entity type differs from T-039's on purpose, and the reasoning is the right one.** This event
> is filed against `STAFF_MEMBER` and the staff-profile id, with the same before/after shape
> `STAFF_EMPLOYMENT_ENDED` uses — *"it sits where a reader would look for the act it refused"* — which
> is why it is `STAFF_MEMBER` here and `USER` in the role-change sibling. A refusal belongs beside the
> act it refused, not beside the other refusals.
>
> **It deleted `requireNotSelf`, which T-039's proof had told the next builder not to simplify away,
> and it was right to.** T-039's stated reason for keeping the parameterised helper was that the second
> call site's refusal *"is not yet recorded"* — this task records it, so the method had **zero callers
> and a javadoc describing a gap that no longer exists.** Leaving it would have been unreachable code
> that documents a false state of the world, which is the same defect T-040 was dispatched to remove
> two files away. It also had to repoint two `{@link #requireNotSelf}` references, since a link to a
> deleted method is a doclint break. **A note left for a successor is evidence, not an instruction**,
> and this successor read the reasoning rather than the conclusion. Three-line revert if wanted.
>
> **One wording call for Rajeev, worth thirty seconds.** The label is *"Employment end refused"*,
> matching *"Role change refused"* literally. *"Ending employment refused"* reads better in isolation
> and breaks the parallelism of the filter list. Left as the parallel form.
>
> **Wave interference, reported and correctly not touched:** its first negative-control attempt could
> not compile because of six `cannot find symbol: method pinOf(UUID)` errors in T-044's
> `MealPlanIT.java`, mid-edit. The trap restored its own file cleanly and the retry compiled. That is
> the second time this wave that Gradle compiling **all** test sources has made one builder's
> in-progress edit visible to another — see T-039's note in 4c-1. It costs a retry and nothing else,
> but it is worth knowing that `--tests` narrows *execution*, never *compilation*.
> **Not hand smoke-tested** — backend-only, needs a deployed environment and a signed-in temple admin.
> The UAT steps are in the proof, and T-039's equivalent is still outstanding too.

### T-047 — A duplicate meal-kind name is a typo, not a crash — and V64's comment stops lying

- **id:** T-047
- **source:** found by T-038's builder and reported rather than fixed, 2026-09-07; **both halves ruled
  by the coordinator the same day.**
- **wave:** **4c-3**
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4c, commit `09404a0`. Was: **proven** *(2026-09-07)*. CI verdict, staging revision and digest are in the release report at the foot of this file. Not yet certified by observation.)*
- **what, half one:** `MealKindService.update()` does not catch `DuplicateKeyException`, so renaming a
  kind onto a name the temple already has returns a **500**. `create()` already raises
  `MEAL_KIND_ALREADY_EXISTS` for exactly this. A person typing a duplicate name is shown a crash, which
  is the plainest possible breach of the rule that **nothing technical reaches the user**. One line,
  and the correct code already exists — no allocation needed.
- **what, half two — and this is why the task carries a migration.** V64's own column comment on
  `meal_services.meal_kind` still says the two names always agree *"— a temple may delete a kind, and
  the meals cooked under it must keep reading as what they were."* **T-038 made that false**: deleting
  a used kind is now refused precisely because they would *not* keep reading as what they were. A false
  statement sitting in a migration next to the code that contradicts it is what the next planner
  believes — this file has already had to correct one stale sentence of its own for the same reason.
- **the honest limit of the fix, to be written into the migration itself.** An applied migration is
  immutable — editing V64 changes its Flyway checksum and the application refuses to boot — so V64's
  `--` text cannot be removed and **a reader of V64 will still read the false sentence.** What the
  fix-forward can do, and must: set the **database** comment via `COMMENT ON COLUMN`, which is what
  `\d+` and every schema browser show, and carry a `--` header saying in terms that V64's and V48's
  claim is superseded, so that anyone grepping `meal_kind` across the migration tree meets the
  correction as well as the error. The project has the precedent four times over — V57, V65, V83 and
  V95 all use `COMMENT ON COLUMN`.
- **reservations:**
  - migration: **`V96`** — `backend/src/main/resources/db/migration/V96__meal_kind_deletion_is_refused_when_in_use.sql`.
    **It is a comment-only migration: no DDL, no data.** Taking it slid `V96`–`V106` to `V98`–`V107`,
    filenames swept, and both were verified against the table afterwards rather than assumed.
  - error codes: none. `MEAL_KIND_ALREADY_EXISTS` exists and is what `create()` already raises.
- **paths:** `backend/.../meal/MealKindService.java`, the new migration,
  `backend/src/test/java/org/iskcon/kms/meal/MealKindIT.java`.
- **acceptance:** renaming a kind onto an existing name returns `MEAL_KIND_ALREADY_EXISTS` and not a
  500, case-insensitively, because the uniqueness index is on `lower(name)`; the migration applies on a
  clean database and on one already at V95; the eight tests T-038 left green stay green.
- **proof:** `docs/work/proof/T-047.md`
- **shipped:** `09404a0`, 2026-09-07, wave 4c — shared with T-038, for the same two files.

> **Built and green 2026-09-07. 12 passed / 0 failed**, T-038's eight untouched among them.
>
> **The `try` is drawn tightly, and the reasoning is the best thing in this proof.** Only the
> `UPDATE meal_kinds` statement is wrapped; **the rename cascade that follows is deliberately outside
> it.** `meal_services` is unique on `(tenant_id, plan_date, meal_kind, lower(COALESCE(event_name,'')))`
> and can raise a duplicate of its own — a different fault entirely, and reporting it to a temple as
> *"that kind of meal already exists"* would be a lie in plain language. A wider `try` would have
> looked identical in review and been wrong only in the case nobody tests.
>
> **No pre-check, on purpose.** The case-insensitivity is the index's — `meal_kinds_name_per_tenant`
> over `(tenant_id, lower(name))` — rather than Java's, because a Java exact-match guard would have
> passed `"Lunch"` → `"DINNER"` straight through into the same 500 it was written to prevent. Letting
> the database be the authority on its own constraint is the correct instinct and it is the one this
> repo already follows.
>
> **The migration was verified against an existing database, not only a fresh one, and that is rarer
> than it should be.** A throwaway `postgres:16-alpine` in the harness's three-role topology, `V1`–`V95`
> replayed as the unprivileged `kms_migrator`, then `V96` alone on top. `pg_dump --data-only` before
> and after differs only in pg_dump's own random `\restrict` nonce; the `--schema-only` diff over
> **8,600 lines** is the single added `COMMENT ON COLUMN` and nothing else. That is what "changes no
> data" looks like when it is demonstrated rather than asserted.
>
> **Negative control:** with the mapping replaced by `throw e;`, exactly the two new tests fail with
> `Status expected:<409> but was:<500>` — and the proof names what the user would actually have seen,
> `KMS-500001 UNEXPECTED_FAILURE`, *"Something went wrong at our end"* — while the other nine pass.
> Restored by `trap ... EXIT`; sha256 identical before and after, `diff -u` empty.
>
> **V96 says its own limit out loud**, which was the requirement: an applied migration is immutable, so
> V64's text stands and a reader of V64 still meets the false sentence. V96 keeps the *true* half of
> V64's reasoning (no FK is possible against an expression index), quotes the false clause, and names
> T-038 and `KMS-400126` as what replaced it — in the database comment *and* in the `--` header, so a
> grep of the migration tree meets the correction as well as the error.
>
> **One gap reported and correctly not taken:** `meal_plans.meal_kind` has **no column comment at all**,
> so the correction sits only on `meal_services.meal_kind`. Putting it on both would have needed a
> second reservation the builder did not have. Worth a line in whichever migration next touches that
> table.
>
> **Not hand smoke-tested** — the frontend was forbidden to it. Worth confirming in one glance, once
> T-005's settings screen exists, that a duplicate rename renders `KMS-400047` and a delete-in-use
> renders `KMS-400126`.

### T-049 — The planner has no way back to today

- **id:** T-049
- **source:** `docs/OUTSTANDING_BUILD_LIST.md` **N2**, re-verified on staging by the coordinator,
  2026-09-07.
- **wave:** **4c-4** — see below for why it is not in 4c-3.
- **state:** queued
- **what:** `/calendar` has a **Today** button; `/planner` has none. Navigate the planner forward two
  days and there is no route home. The highlighted pill on the period control is a **current-period
  indicator, not a button** — it was clicked on staging and nothing happened — and a search for a Today
  control finds only the sidebar link to the Today **dashboard**, which is a different destination.
  Browser back or editing the query string are the only ways back, on the screen a kitchen opens every
  day.
- **what is already right, so nobody rebuilds it:** the planner's middle control names the period
  ("Sat, 12 Sept 2026" in day view, "September 2026" in month view) rather than saying "Today" — that
  was N2's root cause and it is fixed. The segmented box is on both screens, and the differing period
  sets (Day/Week/Month against Month/Week/Year) are correct: a planner needs a day, a calendar needs a
  year.
- **and the fix is smaller and better than the entry asks for, which is worth checking before
  building.** N2 asks for the control to be made a *shared copy*. **It already is one:**
  `components/ds/PeriodNav.tsx` is imported by both screens (`app/planner/page.tsx:13`), and the
  planner already passes it `current={isCurrentPeriod(view, anchor, today)}`. What is **not** shared is
  the Today affordance: `/calendar` renders its own `<Button variant="secondary">` in `PageHeader`'s
  `actions` slot (`app/calendar/page.tsx:125-127`) and the planner's `actions` slot holds something
  else. So there is one copy, not two, and the divergence N2 complains about is a **missing** control
  rather than a drifted one.
  **Therefore: put Today inside `PeriodNav`**, beside the stepper it belongs with, and delete the
  calendar's header copy so there is exactly one. Then it lands on both screens by construction and
  cannot diverge a fifth time — which is what the entry is actually asking for when it says this has
  broken "for the third or fourth time".
- **placement is a decision, not a copy.** N2 says follow the calendar's placement, which is top right
  beside the primary action. But the planner's top right is occupied differently, and that asymmetry is
  itself the argument for moving the control **out** of the `actions` slot and into `PeriodNav`, where
  both screens have identical structure. **Deleting the calendar's existing button is a visible change
  to a screen that is currently correct** — flag it in the proof rather than slipping it in, and if the
  result reads worse on either screen, stop and report instead of shipping a regression to fix a gap.
- **paths:** `frontend/components/ds/PeriodNav.tsx`, `frontend/app/planner/page.tsx`,
  `frontend/app/calendar/page.tsx`, `frontend/__tests__/planner.test.tsx`,
  `frontend/__tests__/calendar.test.tsx`.
- **acceptance:** from the planner, moved two days forward, one control returns to today in **every**
  view — day, week and month, since "today" means a different anchor in each; the same control works on
  `/calendar`; there is exactly **one** Today control per screen; and a test asserts the control is
  absent or inert when the anchor already **is** the current period, so it never offers a journey to
  where the reader already stands.
- **reservations:** none expected — no `api.ts`, no nav row, no migration, no error code. Confirm at
  dispatch.
- **why not in 4c-3:** T-045's reservation deliberately makes `ekadashiProhibited` **required**, which
  breaks six fixtures until T-045 repairs them. `tsc --noEmit` is repo-wide, so a fifth builder joining
  the flying wave would see failures in files it may not touch and could not say what green meant. That
  is the same constraint that split T-044 from T-045 in the first place, and it applies to any frontend
  task dispatched beside T-045. **A reservation that deliberately breaks callers is a scheduling
  constraint**, and this is the second time it has decided a wave boundary.
- **proof:** —
- **shipped:** —

### T-042 — Provisioning geocodes the address instead of asking for two typed numbers

- **id:** T-042
- **source:** `DECISIONS.md` **D-17**, the section headed *"The better fix, which Rajeev asked for"* —
  *"If you want to use the back end we have to translate an address to Lat Long, go for it. That is a
  VERY handy feature to have."*
- **wave:** **4c-3**
- **state:** **SHIPPED to `main`** *(2026-09-07 — wave 4c, commit `2b61e2f`. Was: **proven** *(2026-09-07, after one report and two granted files)*. CI verdict, staging revision and digest are in the release report at the foot of this file. Not yet certified by observation.)*
- **what:** `frontend/app/tenants/new/page.tsx:195-203` asks a human for two free-text six-decimal
  numbers, which is where a wrong temple location comes from. Type the address, geocode it, **show the
  pin and have the operator confirm it** — a wrong pin is obvious at a glance in a way `12.905125`
  never is. **Keep the typed fields as a fallback**, because some temple addresses will not geocode
  cleanly and provisioning must not be blocked by that.
- **what is already in the tree, established before this row was written.** `GeocodingProvider.locate(place)`
  returning `Optional<Coordinates>`, and a complete `NominatimGeocodingProvider` — 1s throttle
  satisfying Nominatim's policy, a 500-entry cache, `countrycodes=in`, and it returns `Optional.empty()`
  for every failure rather than throwing. `NoGeocodingProvider` is the default (`kms.geocoding.provider`
  is `none`, and the key is set nowhere, so `none` is live in every environment). `StaticMapProvider`
  returns **PNG bytes**, not a URL. Its only implementation is Google's and it needs a key.
- **three things found while planning it that change the shape:**
  1. **There is no endpoint that geocodes an arbitrary address.** `GET /api/v1/temples?q=` is the
     devotee search and never surfaces coordinates. One has to be built — in the `geo` package, not in
     `tenant/`, which is what keeps this disjoint from T-041.
  2. **`StaticMapProvider` is Google-only and needs `STATIC_MAP_API_KEY`.** D-17 chose Nominatim
     precisely to avoid keys and billing, and the map is a different provider from the geocoder. So the
     screen must **degrade**: with a key, a pin; without one, the matched address and coordinates as
     text, and the operator confirms either way. Turning `kms.static-map.provider` to `google` also
     lights up the map on job-card delivery sheets — a side effect on an existing feature, to be named
     rather than discovered.
  3. **The map bytes must reach the browser inside the JSON response as a data URI**, the way
     `JobCardService` already does it, because an `<img src>` cannot carry a bearer token and the
     endpoint is behind `MANAGE_TENANTS`.
- **no new error code.** A no-match is a UI state, not a refusal: the endpoint answers 200 with "not
  found" and the typed fields carry on. `DELIVERY_ADDRESS_NOT_FOUND` **`KMS-400078`** is the precedent
  and it is deliberately non-blocking too.
- **paths:** allocated at 4c-3 dispatch. **Plus one line inherited from T-040**:
  `backend/src/test/java/org/iskcon/kms/tenant/TenantUpdateIT.java:36` carries a `{@code RoleChangeIT}`
  cross-reference to a file T-040 deleted. It compiles, which is why nothing catches it. Inline the
  sentence it points at rather than deleting the reference — the fact it records (MockMvc over
  TestRestTemplate, because the JDK HTTP client cannot `PATCH`) is still true and still worth knowing.
  The rest of the frame is `frontend/app/tenants/new/page.tsx`,
  `frontend/__tests__/tenant-new.test.tsx`, a new controller and result record under
  `backend/.../geo/`, a new IT beside it, `backend/src/main/resources/application.yml`, and one new
  frontend component. **`frontend/__tests__/design-system.test.ts` is forbidden** — this page keeps its
  exemption and needs no change to it.
- **the config trap to check before enabling anything.** `kms.geocoding.nominatim.user-agent` has a
  default in `application.yml`, so switching the provider on will not fail context startup — but the
  suite must still be proved not to reach OpenStreetMap. `MembershipIT` stubs the provider with
  `@Primary` for exactly that reason (*"it would make the tests depend on somebody else's uptime and
  their rate limit"*), and any new test copies that pattern.
- **ruled by the coordinator 2026-09-07, and it removes the blocker: build it so a missing key
  degrades rather than fails.** Rajeev has been asked whether he wants Google Static Maps enabled and
  has not yet answered, and the task does not wait on him. **Nominatim returns a normalised address
  string alongside the coordinates**, so when no map can be drawn the screen shows
  *"We found: &lt;the address Nominatim resolved&gt;"* beside the numbers and the operator confirms
  **that**. That is most of the value for none of the cost: a wrong resolved address is as obvious to a
  human as a wrong pin, and `12.905125` is obvious to nobody. If the key is later enabled, the image
  becomes an **upgrade to the same confirm step** rather than a different design — which is the
  property worth engineering for, because it means the decision can arrive late without invalidating
  the build.
- **acceptance:** typing an address and confirming the resolved address fills the coordinates; the pin
  renders when a static-map key is configured and its absence changes nothing else on the screen; a
  no-match leaves the typed fields usable and says so plainly; provisioning is never blocked. **The row
  and the proof must both say plainly that the pin itself is unproven on staging until a key exists** —
  it is the one part of this task no automated test and no unkeyed environment can demonstrate.
- **proof:** `docs/work/proof/T-042.md`
- **shipped:** `2b61e2f`, 2026-09-07, wave 4c — its `TenantUpdateIT.java` javadoc lines travelled in `61e9f19` with T-041.

> ### The brief's premise was false, and the work manager had already written the disproof
>
> **This row, written before dispatch, records the correct signature in as many words:**
> *"`GeocodingProvider.locate(place)` returning `Optional<Coordinates>`"*. The brief then asserted, from
> D-17, that *"Nominatim returns a normalised address string alongside the coordinates"* — **true of
> Nominatim's HTTP response and false of this codebase's port.** `NominatimGeocodingProvider` read
> `lat`/`lon` and discarded `display_name`. So `resolvedAddress` would have been null in every
> deployment and the confirm step would have fallen back to bare coordinates — the exact state D-17
> exists to get away from.
>
> **The planning error is worth naming precisely: conflating what an upstream service returns with what
> our port exposes.** It is the same shape as three defects this wave — a boundary where one side knows
> something the other side's type does not. The evidence was in hand and was written down; it was the
> inference that failed, not the research.
>
> **It was caught because the builder tested the brief's mechanism rather than its own fix** — the
> second time in this batch:
> ```
> GeocodingIT > CONTROL: a hit carries the geocoder's normalised address, as the brief assumes FAILED
>     java.lang.AssertionError: No value at JSON path "$.resolvedAddress"
> ```
> A control aimed only at its own change would have passed, and the screen would have shipped
> permanently in its weakest state.
>
> **It reported instead of widening**, scoped the fix to three files, and named the two it could not
> open. Ownership checked — no contract in the ledger names either port file, `geo/` was otherwise
> untouched, and only one builder was still in the tree — so both were granted.
>
> ### As finished
>
> `Located(Coordinates, String)` and a **defaulted** `describe()` on the port — defaulted is what keeps
> `MealPlanService` and `NoGeocodingProvider` untouched and makes the change genuinely additive.
> `locate()` now delegates to `describe()`, **so the two can never disagree about where a place is**,
> and the cache holds the whole answer: still one request and one entry per place, nothing extra asked
> of Nominatim. The never-throws contract survives — a missing or blank `display_name` is an absent
> description, not an exception and not a failed geocode.
>
> **Backend 49/49, `TSC_EXIT=0`, frontend 9/9.** `TravelEstimateIT` (15) and `MembershipIT` (8) are in
> that run **deliberately**: they are the two existing `locate()` callers in files the builder never
> opened, and `MembershipIT`'s stub is a lambda — so its continuing to compile is the evidence that the
> interface is still functional and the change genuinely additive. That is a better proof of
> "additive" than reading the diff.
>
> ### One socket, and it is still hermetic
>
> `nominatimReadsTheDisplayName` runs an `HttpServer` on `127.0.0.1:0` serving canned JSON against a
> directly constructed provider. **Justified rather than sneaked in:** the `display_name` read was
> otherwise *uncoverable*, because the provider is `@ConditionalOnProperty(havingValue = "nominatim")`
> and the suite never sets that, so the bean is never built. Round-two mutation **M2 deleted the parse
> and only this test caught it** — without it that deletion ships green. Loopback, no DNS, no external
> host, no rate limit spent. The rule this project actually holds is *"the suite must not depend on
> somebody else's uptime"*, and a loopback server does not.
>
> ### The mutation worth remembering
>
> Round-two control ran **three** mutations, 4 of 11 failed, restored by trap, all three files diffed
> identical. The third is the one to keep: it made the port's default **echo the caller's own query
> back as the "resolved" address.** That is the plausible-looking version of this feature — *it looks
> like a confirmation on screen while confirming nothing at all* — and
> `theDefaultDescriptionIsAPositionWithNoLabel` catches it. **A confirm step that echoes the input is
> worse than no confirm step**, because it manufactures the reassurance it was built to provide, and
> the same shape would be tempting in any future provider.
>
> ### Still open
>
> **The pin is unproven** until a static-map key exists, and **the resolved address has never been seen
> from the real service**: every test feeds it from a stub or the loopback server, so nobody has yet
> seen what OpenStreetMap actually writes for a real temple address. That is the live question for a
> human. The feature is dark until `GEOCODING_PROVIDER=nominatim` is set on staging — the builder set
> nothing and does not deploy — and setting it also lights up the devotee temple search and the
> delivery-address travel estimate.

---

# Wave 5 — procurement, the three foundations

**New on the re-plan.** `DECISIONS.md` **D-1** put non-food procurement in scope on 2026-09-07 and
split it along a line that matters to the code: *consumable supplies* behave exactly as ingredients
already do and want a flag; *one-off durables* want the opposite and are a nullable PO line. **D-2**
settled that a manual PO creates a real vendor row, which forces `vendors.phone` off `NOT NULL`.
Those are the three tasks here. The two screens that sit on top of them are wave 6, because they
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
- **wave:** 5
- **state:** queued
- **what:** LPG, disposable plates, cleaning and dishwashing supplies, hand soap and first-aid kits
  are bought from a vendor, received, stored, used up and wanted back when they run low — which is
  the ingredient lifecycle exactly. So they are ingredients with a flag, not a parallel table. D-1
  rejected a `supply_items` table with its own stock ledger outright: it duplicates the entire
  inventory chain to express a difference that is one boolean. Add the column, expose it on the
  catalogue screen and its form, and make the **recipe** picker exclude supplies so a leaf plate can
  never reach a recipe.
  **Three verified facts that change how this is built.** First, there is **no `Ingredient` JPA
  entity and no `IngredientRepository`** — the only two `Entity` classes in the backend are
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
  - `backend/src/main/resources/db/migration/V98__supplies_are_flagged_ingredients.sql` *(new)*
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
  - error code: `NOT_A_FOOD_INGREDIENT` **`KMS-400127`** (409) — *"That's a supply, not something you
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
  - The recipe ingredient picker does not offer it, **and** the API refuses it with `KMS-400127` if it
    is posted anyway — a test asserts the second, not only the first.
  - Existing ingredients are all food after the migration; an integration test asserts the backfill
    ran per tenant under RLS.
  - `./gradlew test` green; `tsc` clean; new vitest passes.
- **proof:** —
- **shipped:** —

### T-024 — A purchase order line can name something that is not in the catalogue

- **source:** `DECISIONS.md` **D-1**, second half · docket **B2** (INTAKE B2).
- **wave:** 5
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
  - `backend/src/main/resources/db/migration/V99__a_purchase_line_need_not_be_an_ingredient.sql` *(new)*
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
    here; `receiving/` is otherwise T-013's in wave 9.
- **reservations:**
  - migration: **`V99`** — nullable `ingredient_id`, a `description` column, and a check that exactly
    one is present. `purchase_order_lines` is tenant-owned (`enable_tenant_rls`, `V26:67`).
  - error codes:
    - `PURCHASE_LINE_NEEDS_A_SUBJECT` **`KMS-400128`** (400) — *"Each line needs either an ingredient
      or a description, not both and not neither."* / *"Pick an ingredient, or describe what you're
      buying."*
    - `CANNOT_RECEIVE_A_DESCRIBED_LINE` **`KMS-400129`** (409) — *"A described line can't be received
      into stock."* / *"Record it as delivered on the order; it isn't something the store tracks."*
  - `frontend/lib/api.ts`: `PoLineInput` (`:1733-1738`) and `PurchaseOrderLineView` (`:1711-1718`) —
    `ingredientId` and `ingredientName` become optional, `description` is added.
  - permissions: none new — `MANAGE_PURCHASE_ORDERS`.
- **acceptance:**
  - A PO carrying one ingredient line and one described line saves, **and both lines appear on the
    detail screen** — the inner-join regression has its own named test.
  - Posting a line with both an ingredient and a description, or with neither, returns `KMS-400128`.
  - The generated PO sheet renders the described line without NPEing in the glossary path.
  - Receiving the ingredient line works; the described line is skipped visibly and returns
    `KMS-400129` if a receipt tries to take it into stock. **No stock movement and no
    `vendor_supplies` row is written for it** — asserted, not assumed.
  - Shopping-list outstanding quantities are unchanged by the presence of a described line.
- **proof:** —
- **shipped:** —

### T-025 — A vendor you walk into has no WhatsApp number

- **source:** `DECISIONS.md` **D-2** (its substance stands after D-7 superseded the interaction).
- **wave:** 5
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
  DRAFT → SENT. The `Transactional` at `:54` rolls it back, so it is not corrupting — but the user
  gets a meaningless 400 for a perfectly sensible situation. **The guard belongs before `:59`.**
  Relaxing the column means the `CHECK` must become "null, or E.164" rather than being dropped — a
  dropped check would let a malformed number in, which is a different defect. `NotBlank` comes off
  `phone` in both `CreateVendorRequest.java:13-14` and `UpdateVendorRequest.java:13-14` while
  `Pattern` stays. `VendorService.java:113` and `:140` both call `request.phone().trim()` and will
  NPE on null. And on the form, `frontend/app/vendors/new/page.tsx:97-100` submits phone at `:54`
  **without** passing through `emptyToNull` (`:143-146`) unlike every other optional field, so it
  would post `""` and fail the pattern.
- **paths:**
  - `backend/src/main/resources/db/migration/V100__a_vendor_need_not_have_a_phone.sql` *(new)*
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
  - migration: **`V100`** — drop `NOT NULL` on `vendors.phone` and replace `vendors_phone_e164` with a
    check that permits null. `vendors` is tenant-owned (`enable_tenant_rls`, `V24:42`).
  - error code: `VENDOR_HAS_NO_WHATSAPP_NUMBER` **`KMS-400130`** (409) — *"This vendor has no phone
    number to send to."* / *"Download the order and hand it over, or add a number to the vendor."*
  - `frontend/lib/api.ts`: `phone` becomes optional on `VendorInput` (`:1659-1669`) and on
    `VendorView`.
  - permissions: none new.
- **acceptance:**
  - A vendor saves with no phone, and one with a malformed phone is still refused — the check permits
    null, it does not permit rubbish.
  - Sending a PO on WhatsApp to a phoneless vendor returns `KMS-400130` **and leaves the order in
    DRAFT** — a test asserts the status, because the failure currently happens after the transition.
  - No notification row is written with a `"Vendor null"` label.
  - An existing vendor with a phone is completely unaffected.
- **proof:** —
- **shipped:** —

---

# Wave 6 — the two procurement screens

Both sit on wave 5. T-026 needs T-024's described line and T-025's phoneless vendor before its form
can be built once rather than twice; T-027 needs T-023's flag so a supply can be added to the list.

### T-026 — Raising a one-off purchase order, vendor first

- **source:** docket **B1** (INTAKE B1), unblocked by D-1 · `DECISIONS.md` **D-7** for the shape,
  **D-2** for why the vendor is a real row, **D-3** for the constraint on inventing UI.
- **wave:** 6
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
  - **forbidden:** `frontend/app/orders/[id]/page.tsx` — T-024's file in wave 5 and T-013's in wave 9.
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
- **wave:** 6
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
  - error code: `ALREADY_ON_THE_SHOPPING_LIST` **`KMS-400131`** (409) — *"That's already on the
    shopping list."* / *"Change the quantity on the line that's there."*
  - `frontend/lib/api.ts`: `addShoppingListLine(input, token)` — no such wrapper exists today.
  - permissions: none new — `MANAGE_PURCHASE_ORDERS`, matching the other three endpoints.
- **acceptance:**
  - A hand-added line survives `POST /regenerate` — **the defining test**, and it must assert against
    a real regeneration, not against the `edited` column alone.
  - Adding an ingredient already on the list returns `KMS-400131`.
  - Quantity zero and an unknown unit are both refused.
  - After T-023, a supply can be added to the list the same way food can.
- **proof:** —
- **shipped:** —

---

# Wave 7 — money that was entered wrongly

Three separate backend packages, three migrations. These are the items where "a mistake is permanent"
costs a temple actual money: ₹45,000 keyed for ₹4,500, a bounced cheque, a gift entered twice.

### T-010 — A vendor invoice can be voided or credited, and a payment reversed

- **source:** docket **M4 + M5** (INTAKE M4, M5). Combined into one task: same package, one migration,
  and voiding a payment must recompute the invoice status the other half owns.
- **wave:** 7
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
  - `backend/src/main/resources/db/migration/V101__invoice_void_and_payment_reversal.sql` *(new)*
  - `frontend/app/invoices/[id]/page.tsx`
  - `frontend/app/invoices/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/invoice/InvoiceCorrectionIT.java` *(new)*
  - `frontend/__tests__/invoice-void.test.tsx` *(new)*
- **reservations:**
  - migration: **`V101`**.
  - error codes: `INVOICE_ALREADY_VOIDED` **`KMS-400132`** (409) — *"This invoice has already been voided."*
    / *"Look at the credit note recorded against it."*; `PAYMENT_ALREADY_VOIDED` **`KMS-400133`** (409) —
    *"This payment has already been struck."* / *"Record a new payment if one was actually made."*
  - permissions: none new — `MANAGE_VENDOR_PAYMENTS` (Temple Admin only) already fits.
  - `frontend/lib/api.ts`: `voidInvoice(id, reason, token)`, `creditInvoice(id, input, token)`,
    `voidInvoicePayment(invoiceId, paymentId, reason, token)` — exact signatures written at
    reservation time in the file's own style.
- **acceptance:**
  - Voiding a payment that took an invoice to `PAID` returns the invoice to `PENDING`, proven by an
    integration test on the real database.
  - A voided payment row still exists and is marked, not deleted.
  - A second void of the same payment returns `KMS-400133`.
  - The disputed/voided state is visible on the invoice screen, not only in the API.
- **proof:** —
- **shipped:** —

### T-012 — Voiding a hand-recorded donation

- **source:** docket **M6** (INTAKE M6).
- **wave:** 7
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
  - `backend/src/main/resources/db/migration/V102__donation_void.sql` *(new)*
  - `frontend/app/donations/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/donation/DonationVoidIT.java` *(new)*
  - `frontend/__tests__/donation-void.test.tsx` *(new)*
- **reservations:**
  - migration: **`V102`**.
  - error codes: `DONATION_ALREADY_VOIDED` **`KMS-400134`** (409) — *"This donation has already been
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
  - A second void returns `KMS-400134`.
- **proof:** —
- **shipped:** —

### T-014 — Reinstating someone whose employment was ended

- **source:** docket **M9** (INTAKE M9), reinstatement half only. The second half of that docket item —
  *"the role change that would undo the demotion has no screen either"* — is **wrong** and is not in
  this task; that screen exists and works.
- **wave:** 7
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
  - `backend/src/main/resources/db/migration/V103__staff_reinstatement.sql` *(new, only if a column is needed)*
  - `frontend/app/staff/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/staff/StaffReinstatementIT.java` *(new)*
  - `frontend/__tests__/staff-reinstate.test.tsx` *(new)*
- **reservations:**
  - migration: **`V103`** — allocated conditionally. The existing columns may be enough to clear; if the
    builder finds it needs none, it leaves `V103` unused and says so in the proof. A gap in the sequence
    is harmless; a second builder taking the same number is not.
  - error codes: `EMPLOYMENT_NOT_ENDED` **`KMS-400135`** (409) — *"This person is still employed."* /
    *"There is nothing to reinstate."*
  - permissions: none new — `MANAGE_STAFF` (Temple Admin only).
  - `frontend/lib/api.ts`: `reinstateStaff(id, input, token)`.
- **acceptance:** a reinstated person is editable again and can sign in if they could before;
  reinstating someone still employed returns `KMS-400135`; reinstating someone marked ineligible for rehire
  is refused; the act is audited.
- **proof:** —
- **shipped:** —

---

# Wave 8 — the recorded meal, and two half-workflows

### T-007 — Correcting a recorded meal

- **source:** docket **S5** and **M2** (INTAKE S5, M2) · `OUTSTANDING_BUILD_LIST` **P8** ("Still
  outstanding: reopening a recorded meal to correct it") · `WORK_QUEUE` item 3.5.
- **wave:** 8
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
  - `backend/src/main/resources/db/migration/V104__meal_correction.sql` *(new)*
  - `frontend/app/planner/[date]/[kind]/page.tsx`
  - `frontend/components/planner/MealServices.tsx`
  - `backend/src/test/java/org/iskcon/kms/meal/MealCorrectionIT.java` *(new)*
  - `frontend/__tests__/meal-correction.test.tsx` *(new)*
- **reservations:**
  - migration: **`V104`** — to carry what the original figures were, so the screen can say "corrected
    from 400" without reading it out of the ledger.
  - error codes: `MEAL_ALREADY_CORRECTED` **`KMS-400136`** (409) — *"This meal has already been
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
  - Correcting twice returns `KMS-400136`.
  - The screen shows "640, corrected from 400 by <name> on <date>".
  - A rollback test proves the stock half and the meal half cannot commit separately.
- **proof:** —
- **shipped:** —

### T-015 — Retrying a message to the addresses it failed for

- **source:** docket **B6** (INTAKE B6).
- **wave:** 8
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
  - error codes: `NOTHING_FAILED_TO_RETRY` **`KMS-400137`** (409) — *"Every copy of this message was
    delivered."* / *"There is nothing to send again."* *(Question 12 is closed; there is no band.)*
  - permissions: none new — `MANAGE_COMMUNICATIONS`.
  - `frontend/lib/api.ts`: `retryFailedDeliveries(id, token)`.
- **acceptance:** a retry re-queues only the failed recipients, proven by an integration test that
  asserts the succeeded ones are untouched; retrying a fully-delivered message returns `KMS-400137`; the
  message body cannot be edited by this path.
- **proof:** —
- **shipped:** —

### T-016 — Volunteer attendance, and striking someone off a roster

- **source:** docket **B7** (INTAKE B7).
- **wave:** 8
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
  - `backend/src/main/java/org/iskcon/kms/shift/**` *(DTOs and views, as needed)* — **read this
    before opening any of them.** T-034 (wave 3) put the meal link on `ShiftView`, `ShiftService`,
    `CreateShiftRequest` and `UpdateShiftRequest`: `mealDate`, `mealKind`, `mealEventName`, with an
    all-or-nothing `CHECK` and `KMS-400125` behind it. **Extend those files, never rewrite them.** A
    builder that regenerates a DTO from the story rather than from the file silently unpicks D-14,
    and nothing about that failure is loud — the crew count simply goes back to being wrong.
  - `backend/src/main/resources/db/migration/V105__shift_attendance.sql` *(new)*
  - `frontend/app/shifts/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/shift/ShiftAttendanceIT.java` *(new)*
  - `frontend/__tests__/shift-attendance.test.tsx` *(new)*
- **reservations:**
  - migration: **`V105`** — the attendance column on `shift_signups`. The table is tenant-owned, so the
    migration must respect the existing RLS on it and backfill per tenant, never across all rows.
  - error codes: `ATTENDANCE_ALREADY_RECORDED` **`KMS-400138`** (409) — *"Attendance for this shift has
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

# Wave 9 — the last four

### T-013 — Returning goods to a vendor after they were accepted

- **source:** docket **M3** (INTAKE M3).
- **wave:** 9
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
  - `backend/src/main/resources/db/migration/V106__return_to_vendor.sql` *(new)*
  - `frontend/app/orders/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/receiving/ReturnToVendorIT.java` *(new)*
  - `frontend/__tests__/goods-return.test.tsx` *(new)*
- **reservations:**
  - migration: **`V106`**.
  - error codes: `RETURN_EXCEEDS_RECEIVED` **`KMS-400139`** (400) — *"You can't return more than was
    received."* / *"Check the quantity against the goods receipt."*; `ALREADY_RETURNED` **`KMS-400140`**
    (409) — *"These goods have already been returned."* / *"Look at the return recorded against this
    receipt."*
  - permissions: none new — `MANAGE_INVENTORY`.
  - `frontend/lib/api.ts`: `returnReceivedGoods(receiptId, input, token)`.
- **acceptance:** a return reduces on-hand by exactly the returned quantity through a new movement;
  over-returning gives `KMS-400139`; the goods receipt itself is never mutated; the `CHECK` constraint and
  the Java enum agree, proven by an integration test that inserts the new type.
- **proof:** —
- **shipped:** —

### T-019 — Raising a shift from the planner, and seeing it there afterwards

- **source:** docket **S6 + S7** (INTAKE S6, S7) · `OUTSTANDING_BUILD_LIST` **P6** and **P7** ·
  `WORK_QUEUE` item 3.6. One task because they are one piece of work in one set of files.
- **wave:** 9
- **state:** queued
- **what:** When the crew a meal needs exceeds the staff working that day, the planner shows the
  shortfall and offers nothing. `createShift` exists and has never appeared in a planner file in the
  whole history — the overlay Rajeev remembers was on the Volunteers page and became its own screen on
  2026-08-21 under his four-fields-becomes-a-screen rule. Build the affordance: open the post-a-shift
  form as a layer over the planner with the title derived from date and meal ("Lunch preparation on
  September 1 2026") and the date and capacity pre-filled, post, and land back in the planner where you
  were. Then the other half: show the shift beside the existing crew count, with its sign-up count, and
  let it be opened and edited in the same layer.
  **Question 9 is closed and this row is the smaller half of what it used to be.** `DECISIONS.md`
  **D-14** chose the explicit meal↔shift link over time-window matching, and the model that carries
  it — the migration, `MealMoment`'s new shape, `WorkforceService.countAt` and `MealCrewService` —
  was split out as **T-034 and built in wave 3**. So there is no migration here and no
  crew-calculation change: by the time this runs a shift can already say which meal it is for, and
  this task is the affordance that lets a planner say it. The form posts `mealDate`, `mealKind` and
  `mealEventName` alongside the rest, taken from the planner page the reader is standing on, and the
  shift then shows against that meal's crew count rather than against whatever the clock caught.
- **paths:**
  - `frontend/components/planner/MealServices.tsx`
  - `frontend/app/planner/[date]/[kind]/page.tsx`
  - `frontend/components/planner/ShiftLayer.tsx` *(new)*
  - `frontend/__tests__/planner-shift.test.tsx` *(new)*
- **reservations:**
  - migration: **none.** T-034 shipped `V95` in wave 3; this task adds no schema.
  - `frontend/lib/api.ts`: a shifts-for-a-date-range wrapper if one is not already present; `createShift`
    (`:4536`) and `shiftRoster` (`:4533`) already exist.
- **acceptance:** posting from the planner creates the shift and returns the reader to the same day and
  meal; the shift then appears beside the crew count with its sign-up figure; opening and closing the
  layer without saving changes nothing.
- **proof:** —
- **shipped:** —

### T-020 — A donation receipt a donor can be given or sent again

- **source:** docket **B5** (INTAKE B5).
- **wave:** 9
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
  - `backend/src/main/resources/db/migration/V107__donation_receipt_document.sql` *(new)*
  - `frontend/app/donations/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/document/DonationReceiptIT.java` *(new)*
  - `frontend/__tests__/donation-receipt.test.tsx` *(new)*
- **reservations:**
  - migration: **`V107`** — the `donation_id` column and the widened `kind` CHECK.
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
- **wave:** 9
- **state:** queued
- **what:** `INVALID_PHONE_NUMBER` (**`KMS-400003`**) would say *"Include the country code, for example
  +91 98765 43210."* It is never thrown, and today it **structurally cannot be**: every phone check is a
  Bean Validation `Pattern` on a DTO field, and the validation handler stamps `VALIDATION_FAILED`
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
| 2 · **4 shipped, 1 blocked** | T-004, T-006, T-009, T-017, **T-028** | yes, 5 builders | Four new routes under four different directories, each over a backend already finished and tested. The only shared cost is `api.ts`, stubbed beforehand. **T-028 is the fifth and shares nothing with the other four** — it is two files in `backend/.../shoppinglist/`, no frontend, no reservation of any kind. It sits in this wave rather than a later one because it is destroying vendor selections in production every time somebody edits the list, and because both its files are free until wave 5. | **Ran and released 2026-09-07.** T-004 (`67d5f05`), T-006 (`bfca0ac`), T-009 (`723696c`) and T-028 (`a41d094`) are on staging; T-017 stopped before writing product code on a blocker that needs Rajeev (no next-charge date exists anywhere in the stack) and **stays queued, not shipped and not proven**. No contract breached, none widened.
| 3 · **dispatched 2026-09-07** | **T-031**, **T-033**, **T-034**, T-022 | yes, 4 builders | The four things Rajeev ruled on 2026-09-07, model first, plus the one old wave-3 task that shares nothing with anything. Path sets: one frontend guard and four test files (T-031); the equipment package plus one detail screen (T-033); three backend packages named file by file, with `V95` (T-034); five brand-new test files and no product file at all (T-022). **T-034 is the one to read twice** — it is in `shift/`, `staff/` and `meal/` at once, so it names every file in each and no `**` glob is permitted. It is forbidden `staff/StaffScheduleService.java` and `staff/ScheduleResolver.java` (T-032's, next wave), `meal/ServedMealService.java` (T-007's, wave 8) and `shift/SignupService.java` (T-016's, wave 8). |
| 4a · **shipped to `main` 2026-09-07** | **T-035**, **T-036**, **T-037** | yes, 3 builders | The three defects Rajeev found driving live staging as the real roles after `527b23b`. **Zero reservations between them** — no migration, no error code, no permission, no `api.ts` wrapper, no nav row — so nothing is written into a shared file before this wave, a first for this batch. Path sets are disjoint and each is a single product file plus its tests: `RequireRole.tsx` (T-035), `frontend/app/inventory/[id]/page.tsx` (T-036), `frontend/app/register/page.tsx` (T-037). All three are frontend-only, so the verify lock carries `tsc`/`vitest`/`next build` and no Gradle. **T-035 is the one to read twice**: it is forbidden every file under `frontend/app/`, which is what keeps it out of the other two — and it is told by name not to pick either of their pages for its double-sidebar assertion. **T-036 merges Rajeev's findings 2 and 3 under one id** because both live in the same two components of the same file; two ids would have put two builders in it. **This wave runs before 4b on purpose:** T-035 changes what every refusal renders, and T-005, T-008 and T-018 all write fresh page tests on guarded routes — one wave apart, they build on a committed shape instead of asserting against a moving one. | **Ran 2026-09-07. All three proven, no contract
breached.** One widening was offered (`occasions.test.tsx`, to T-035) and **handed back by the
builder, whose diagnosis was right and the work manager's wrong** — so the wave finished on exactly
the contracts it was dispatched with.
**This wave has a merged-tree run, which is unusual and worth stating.** Normally "green inside a
wave is not green on the merged tree" and the check has to be done again afterwards. Here it does not:
T-035's final run started at **11:21:10**, and the newest product or test file in the entire wave is
`refusal-has-a-way-out.test.tsx` at **11:18:16** — checked by `stat`, not by eye — so that one run
exercised the finished form of all three tasks together. It is `Test Files 97 passed (97) /
Tests 1035 passed (1035)`, `tsc --noEmit` silent, `next build` `✓ Compiled successfully`, exit 0,
with T-036's `inventory-correction` (7) and T-037's `register` (20) green inside it, and
`occasions.test.tsx` (10) green and untouched at an mtime that predates the wave's dispatch.
The only file written after that run is T-035's own proof (11:22:08). |
| 4b · **shipped to `main` 2026-09-07** *(2 built, 1 stopped, 1 closed)* | **T-032**, T-005, T-008, T-018 | yes, 4 builders | The planned wave 4, minus T-035. **T-032 and T-034 are serialised across waves 3 and 4b, and it cost nothing.** Both are in `backend/.../staff/`; their file sets are provably disjoint (`StaffScheduleService`/`StaffProfileDetailView` against `MealMoment`/`WorkforceService`, and `countAt` has exactly one caller — `MealCrewService` — so T-034 never reaches `weekView`, which uses `countFor`). **T-005 is still held out of wave 2's company on purpose** — it and T-004 are both settings-area screens and I am not certain neither reaches into `frontend/app/settings/page.tsx`; doubt means serialise. T-018 is the frontend staff *screens*, T-032 the backend staff *package* — disjoint halves. T-008 is the tenant package and the operator's screens. This wave carries the batch's reservations for `api.ts` and `nav.ts`, written in one pass immediately before dispatch. |
| 4c-1 · **all four proven 2026-09-07** | **T-043**, **T-038**, **T-039**, T-041 | yes, 4 builders | **Nothing in wave 4c was in the batch when it was planned.** T-038/T-039/T-040 came out of what wave 4b *found*; T-041 and T-042 out of D-17, ruled while Rajeev verified what 4b shipped; T-043 out of the staging verification pass, and it outranks the rest. Four disjoint areas: `frontend/components/planner/` (T-043), `meal/MealKindService.java` (T-038), `staff/StaffEmploymentService.java` (T-039), and the tenant edit screen with its service (T-041). **T-038 is the one to read twice** — it must *read* `MealPlanService`, `ServedMealService`, `MealCrewService` and `JobCardService` to answer its own question and may write none of them. Reservations: two files, `ErrorCode.java` (`KMS-400126`) and `frontend/lib/api.ts` (`RecordMealInput.eventName`), both written in one pass before dispatch. **No migration anywhere in this wave, and that was a design choice rather than luck** — see T-038's ruling: taking `V98` would have slid `V98`–`V107` and forced the eleven-contract filename sweep this file has twice had to correct. |
| 4c-2 · **dispatched 2026-09-07** | T-040, **T-044**, **T-046** | yes, 3 builders | Three disjoint areas: `user/` (T-040), the planner composer plus `meal/MealPlanService.java` (T-044), `staff/StaffEmploymentService.java` (T-046). **T-040 waited on T-039's evidence, not its files** — `RoleChangeIT` was the only test asserting a refused role change is audited, and T-040 deletes it. T-039 has rebuilt the property, so it is free. **T-044 is the one to read twice**: it is the only task in wave 4c that *writes wrong data* rather than failing, and its fix has a type-system half (annotate `mealFacts`'s return, closing the spread escape hatch) and a server half (`isPlaced()` must stop reading `0,0` as placed) — either alone leaves the defect reachable from the other direction. |
| 4c-3 | T-042, **T-045**, **T-047** | yes, 3 builders | **T-045 is here rather than in 4c-2 because of a mistake in the reservation pass, caught before dispatch and worth recording.** Its `api.ts` slice makes `ekadashiProhibited` required on `IngredientView`, which breaks six hand-built fixtures in test files T-045 must own; T-044's slice breaks `MealComposer`. `tsc --noEmit` is **repo-wide**, so run side by side each builder would have seen the other's breakage in files it was forbidden to touch, and neither could have said what "green" meant. The reservations were written, the collision spotted, and **T-045's slice rolled back out of `api.ts` and deferred to this sub-wave** — verified against `git diff` rather than by eye. The lesson generalises: **a reservation that deliberately breaks callers is itself a scheduling constraint**, because the compiler does not respect path contracts. T-047 also carries `V96`, and a wave with a migration is a wave nothing else should be migrating in. |
| 5 | T-023, T-024, T-025 | yes, 3 builders | **The riskiest wave in the batch, and the one to read twice.** T-024 and T-025 are both inside `backend/.../purchaseorder/` — `PurchaseOrderService.java` and `PurchaseOrderDeliveryService.java` respectively — so neither contract may use a `**` glob and each names the other's file as forbidden. Three migrations, `V98`/`V99`/`V100`. Three and not four because every one carries a migration and the verify lock is the bottleneck. **T-023 takes `ShoppingListService.java` (its `IS NOT NULL` guard) only after T-028 has left it in wave 2** — a different method in the same file, so the ordering is what keeps them apart, not the path set. |
| 6 | T-026, T-027 | yes, 2 builders | Both sit on wave 5 and cannot precede it: T-026 needs T-024's described line and T-025's phoneless vendor, T-027 needs T-023's flag. Deliberately a thin wave — the alternative was pulling wave 7 forward into files T-024 has just left, which is the bet this arrangement exists to avoid. T-026 is forbidden `orders/[id]/page.tsx`, which T-024 owns in wave 5 and T-013 in wave 9. **T-027 takes `ShoppingListService.java` and `frontend/app/shopping-list/page.tsx` after T-028 (wave 2) and T-023 (wave 5)**, and must build its hand-added line on the corrected `updateLine`, not the destructive one. |
| 7 | T-010, T-012, T-014 | yes, 3 builders | Three separate backend packages — invoice, donation, staff — and three migrations, `V101`/`V102`/`V103`, allocated here because Flyway would not notice the collision until it refused to boot. |
| 8 | T-007, T-015, T-016 | yes, 3 builders | T-007 reaches into the inventory package as well as the meal package, so nothing else touching inventory runs beside it. T-007 takes `meal/` after **T-034** has left `MealCrewService.java` in wave 3 — different files, and three waves apart. **T-016's `shift/**` glob is now more dangerous than it was**: `ShiftView`, `ShiftService`, `CreateShiftRequest` and `UpdateShiftRequest` will carry T-034's meal link by then, and a builder that rewrites rather than extends them silently unpicks D-14. Its row says so. **T-019 stays held back** — it was held for Question 9, which is now closed, and the reason survives the answer: it is the planner half of the same feature and it belongs after the model, not beside it. |
| 9 | T-013, T-019, T-020, T-021 | yes, 4 builders | Four deliberate cross-wave serialisations, not four bets. **T-019 is now frontend-only** — its migration and its crew-calculation half became T-034 in wave 3 — so it takes the planner after T-007 (wave 8) and nothing else. T-013 takes `receiving/` only after T-024 (wave 5) has left it and the inventory package only after T-007. T-020 takes `DocumentGenerationService.java` only after T-024, and donations only after T-012. **T-021 takes `CreateVendorRequest.java` and `UpdateVendorRequest.java` only after T-025 has left them** — the two tasks both rewrite the phone rule on the same two DTOs, and running them together would have been the collision this wave table exists to catch. T-019 takes the planner only after T-007. |

**Not in any wave, and not to be added to one without Rajeev:** the seven
environment/credential/human-verification items (P1–P6, P8), S2, and **T-017**, which is blocked by
Rajeev's own ruling rather than by a missing answer. B1, B2 and B3 **were** blocked and are now
scheduled, unblocked by D-1 — they are T-026, T-024/T-023 and T-027 respectively.

**B4 is answered and is not scheduled.** Rajeev ruled on 2026-09-07 that a funded wish-list item
**may** seed a purchase. Recorded here so the answer is not lost between the docket and the day
somebody builds it; it is not a task in this batch, has no id, and nothing above depends on it.

**B8 is the only thing in the docket still genuinely open**: whether to leave the recorded decision
against a `CANCELLED` request state, or overrule it. Everything else that was open when this batch
was planned has been ruled on — Questions 7, 9, 12 and 13 are all closed, and D-16 closed the
`/my-shifts` question this file itself raised. B8 blocks nothing currently scheduled.

---

## Wave 4b, as it actually ran — 2026-09-07

**Four dispatched. Two proven, one stopped, one closed as already built — and both refusals were
right.** The wave's most valuable output was not code: T-018 established that its own task had been
built already and found an audit gap while proving it, and T-005 established that its first acceptance
criterion would have corrupted every existing plan. Neither wrote a line of product code, and the
batch is better for both.

**Merged-tree verification, run by the work manager after every builder was out of the tree and after
the last shared-file edit** — the step wave 4a got by luck and this one got on purpose:

- **Frontend, first run:** `Test Files 1 failed | 97 passed (98)`, `Tests 1 failed | 1052 passed (1053)`.
  The single failure was `design-system.test.ts`'s hard-coded-timezone guard naming T-008's new edit
  screen — a guard test no builder's targeted run loads. Handed back to T-008 with the file added to
  its contract; see its row.
- **Frontend, after the fix:** `Test Files 98 passed (98)`, `Tests 1053 passed (1053)`, `tsc --noEmit`
  clean, `✓ Compiled successfully` with `/tenants/[id]/edit` in the route table. **The wave is green on
  the tree that ships**, which is a different statement from every builder being green on its own.
  The exemption cost one functional line and fourteen of reasoning; the regex was not touched, so the
  next `new Date(x).toLocaleDateString()` is still caught.
- **Backend:** `Total: 1751 / Passed: 1749 / Failed: 0 / Skipped: 2 / Result: SUCCESS`,
  `BUILD SUCCESSFUL in 3m 14s`, over the whole finished tree including `TENANT_UPDATED` and T-032's
  `OwnScheduleLeaveIT`.

**Reservations written, and two taken back.** The pass before dispatch wrote `deleteMealKind`,
`updateTenant`/`UpdateTenantInput`, T-032's leave fields, and the `/settings/meal-kinds` nav row. Two
were reverted afterwards because T-005 never built its screen — a menu row pointing a Temple Admin at
a route that does not exist is a defect this ledger would otherwise have shipped. Two were added
mid-wave on builder evidence: `AuditAction.TENANT_UPDATED`, and the coordinates on `TenantDetail`.

**Two contracts widened, both after checking that every other builder had finished**, and both for one
file and one edit: `__tests__/tenant-detail.test.tsx` (a fixture that could not typecheck against a
required field) and `__tests__/design-system.test.ts` (a guard asserting a world with one
temple-writing screen in it). Neither builder widened its own contract; both stopped and reported, and
one of them declined a third widening it could have taken.

**What the wave produced beyond its tasks:** three new tasks (T-038, T-039, T-040), a sixth reserved
file, and three protocol lessons now written into `docs/work/README.md` rather than left in one task's
row.

**Two findings from the guard-test fix, both flagged and neither fixed — correctly.**

1. **The `!file.endsWith("lib/api.ts")` exemption in `design-system.test.ts` is dead.** That test's
   `sources()` globs only `app` and `components`, so `lib/api.ts` is never scanned and the exemption
   excludes nothing. The builder left it: removing it is scope creep in a file borrowed for one edit,
   and if the glob is ever widened it becomes correct again. Worth knowing before somebody reads the
   list as a statement of what the codebase does.
2. **The shared-timezone-module refactor would move an exemption, not remove one.** The module would
   then hold the literal and would need exempting itself. That makes it the better end state for the
   **drift** reason and not for the **guard** reason — worth having straight before somebody picks it
   up expecting the exemption list to empty out. The two hardcoded lists now carry doc comments
   pointing at each other.

---

## Wave 4c-1, as it actually ran — 2026-09-07

**Four dispatched, four proven, and not one contract breached or widened.** The first wave in this
batch where every builder finished what it was sent to do — and the two most valuable outputs were
still findings rather than code.

**Merged-tree verification, run by the work manager after every builder was out and after the last
shared-file edit**, which is now the protocol's step rather than an accident of timing:

```
frontend  TSC-SILENT · Test Files 98 passed (98) · Tests 1056 passed (1056)
backend   Total: 1770  Passed: 1768  Failed: 0  Skipped: 2  Result: SUCCESS
          BUILD SUCCESSFUL in 3m 13s
```

**Green first time, which is worth stating because wave 4b's was not.** 4b's merged run found T-008
tripping a repo-wide guard that no builder's targeted run could load. This one found nothing, and the
reason is that the guard in question was handled deliberately: `design-system.test.ts` was **forbidden
to both tenants-area builders** and its now-dead timezone exemption removed by the work manager after
they left — the seventh reserved file, and the first one reserved because it is a *scanner* rather
than a registry.

**One shared-file edit after the wave, by the work manager.** `frontend/__tests__/design-system.test.ts`
loses the `app/tenants/[id]/edit/page.tsx` exemption, because T-041 removed the `TIMEZONES` list the
exemption existed for. The `lib/api.ts` exemption stays, knowingly: it excludes nothing today because
`sources()` globs only `app` and `components`, but it becomes correct again the moment that glob
widens, and removing it would read as a claim that `api.ts` holds no zone when it holds the one
sanctioned fallback. Both facts are now written into the test's own comment.

**What the wave produced beyond its four tasks: four more tasks and two protocol lessons.**
T-044 and T-045 came from the read-only contract sweep T-043 justified; T-046 and T-047 from what
T-039 and T-038 found and correctly refused to fix in flight. The lessons — **ask for a negative
control by default**, and **a builder declining the brief's approach is the outcome to want** — are in
`docs/work/README.md` as items 4 and 5 rather than in any one task's row.

**A note on how this wave was planned, since the shape is now consistent.** Of the six tasks in wave
4c, **none was in the batch when the batch was planned.** Three came from the previous wave's
findings, two from a ruling Rajeev made while verifying the previous wave's output, one from driving
staging. Waves 1–3 were planned work; 4a, 4b and 4c are almost entirely the product of looking at what
shipped. That is not a failure of planning — it is what happens when the thing is real enough to be
driven — but it does mean **every reservation held for wave five and later should be assumed to move
again**, and the tables should be swept in that expectation rather than in the hope that they are
final.

---

## Wave 4c-2, as it actually ran — 2026-09-07

**Three dispatched, three proven, no contract breached and none widened.** Two of the three came back
with something the plan did not have.

**Merged-tree verification:**

```
frontend  TSC-SILENT · Test Files 98 passed (98) · Tests 1060 passed (1060)
backend   Total: 1766  Passed: 1764  Failed: 0  Skipped: 2  SUCCESS   BUILD SUCCESSFUL in 3m 16s
```

*(The backend total falls from 1770 to 1766 because T-040 deleted `RoleChangeIT`'s four cases. A
falling count is the expected shape of a wave that removes dead code, and it is worth saying so before
somebody reads it as a regression.)*

**A mistake of the work manager's, recorded rather than quietly fixed.** `ErrorCode.java` was edited —
retiring `KMS-400023` — **while that backend run was in flight.** It does not corrupt a run, since
Gradle has already compiled, but it makes the result stale: the tree that was tested is no longer the
tree on disk. **The work manager's own shared-file edits must queue for the verify lock exactly like a
builder's**, and this one did not. Re-verified afterwards rather than reporting a number that predates
the edit. The rule was written for concurrent builders and quietly assumed the work manager was not
one of them; it is.

**Two builders returned findings that outrank their own code.**

- **T-044 falsified the mechanism its brief, the sweep and two sections of this file all asserted.** A
  spread exempts *excess* properties, never *missing required* ones — so the annotation moves where
  the error surfaces and the required declaration is what closes the hole. Corrected in three places
  above and written into `README.md` as the stronger form of the negative-control rule.
- **T-046 deleted a helper T-039's proof had told it to keep**, and was right: T-039's stated reason
  for keeping it was a gap that T-046 itself closed. **A note left for a successor is evidence, not an
  instruction.**

**Wave interference seen twice, and it is a property of Gradle rather than of the protocol.**
`--tests` narrows *execution*, never *compilation*, so one builder's in-progress test file becomes
another builder's compile error. T-039 hit T-041's `TenantUpdateIT`; T-046 hit T-044's `MealPlanIT`.
Both left the foreign file alone, said so, and retried. It costs a retry. **The wrong response — and
the one to guard against — is a builder "fixing" a neighbour's file to get its own run green**, which
is precisely the collision the path contracts exist to prevent, arriving through the back door.

---

## Wave 4c-3, as it actually ran — 2026-09-07

**Four dispatched, four proven — but two stopped first, and both stops were the work manager's error,
not theirs.** This sub-wave is the clearest evidence in the batch that the contracts and the
stop-don't-widen rule are doing their job.

**Merged-tree verification, after every builder was out and after the last shared-file edit:**

```
frontend  TSC-SILENT · Test Files 98 passed (98) · Tests 1075 passed (1075)
backend   Total: 1776  Passed: 1774  Failed: 0  Skipped: 2  SUCCESS   BUILD SUCCESSFUL in 3m 16s
```

**Contract audit, run rather than assumed.** All 65 changed files map to exactly one contract each:
T-040's five in `user/` (three deletions, two edits); `IngredientForm.tsx` under the one widening
granted to T-045; `MealComposer.tsx` and `MealServices.tsx` to their owners; `AddressLookup.tsx` as
T-042's single permitted new component; **both migrations at exactly the allocated `V96` and `V97`,
with no duplicate version** — the failure mode the reservation table exists for; and all four reserved
files (`api.ts`, `ErrorCode.java`, `AuditAction.java`, `design-system.test.ts`) carrying only work
manager edits.

### Two contracts were wrong, and the same mistake made both

**T-045's was short by one file.** The reservation pass checked which **test files** named
`IngredientView` and never checked which **components** construct `CreateIngredientInput`, so
`IngredientForm.tsx` was already broken by the `api.ts` slice before the builder started, and the
brief said six files when it was seven.

**T-042's rested on a false premise.** The brief asserted that Nominatim's normalised address arrives
with the coordinates. True of Nominatim's HTTP response; false of this codebase's port, which returns
`Optional<Coordinates>` and nothing else — **as this file's own T-042 row had already recorded before
the brief was written.**

**Both are the same error in different clothes: reasoning about a boundary from one side of it.** The
first checked the type's consumers in one directory and not another; the second took what an upstream
service returns as what our port exposes. That is also the shape of three of this wave's *defects* —
`eventName`, the delivery pin, and the Ekadashi flag are all a boundary where one side knows something
the other side's type does not. **The planning pass is not exempt from the failure mode it is
scheduling fixes for.**

**Both builders stopped rather than widening, and both rejected workarounds that would have
compiled** — T-045 refused to inline a duplicate form (D-3) or to smuggle an input past the
component's own `submit`; T-042 refused to reach into a port it had been told not to open. Ownership
was checked before each widening: T-045's file was claimed only by T-023 in **wave 5**, not flying;
T-042's two port files were claimed by nobody at all.

### What the negative controls caught that a passing suite never would

Every task in this sub-wave ran one, and three of them found something:

- **T-042 falsified its own brief** — `CONTROL: a hit carries the geocoder's normalised address, as
  the brief assumes FAILED`. A control aimed only at its fix would have passed and the screen would
  have shipped permanently in its weakest state.
- **T-042's round-two mutation M2** deleted the `display_name` parse; **only its loopback-server test
  caught it.** That test opens a socket, which needed justifying rather than excusing: the provider is
  `@ConditionalOnProperty(havingValue = "nominatim")` and the suite never sets that, so the code was
  otherwise *uncoverable*. Loopback, no DNS, no external host — and the project's actual rule is that
  the suite must not depend on somebody else's uptime.
- **T-042's third mutation is the one to remember**: it made the port echo the caller's own query back
  as the "resolved" address — *the plausible-looking version of the feature, which looks like a
  confirmation while confirming nothing.* **A confirm step that echoes its input is worse than no
  confirm step**, because it manufactures the reassurance it exists to provide.
- **T-048's control printed the live defect in a row**, place id and zeroes side by side — which is
  also what proved the rejected `no place_id` filter would have matched nothing.
- **T-045 explained its own control's arithmetic** — 3 failures from 4 new tests, because one asserts
  an absence and passes vacuously once the feature is gone.

### Three builder decisions that improved on their instructions

- **T-047 drew its `try` tightly**, leaving the rename cascade outside it, because `meal_services` has
  its own unique constraint and reporting that collision as *"that kind of meal already exists"* would
  be a lie in plain language. A wider `try` would have looked identical in review.
- **T-048 replaced the brief's argument for the single-zero case with a better one** — nulling it
  changes nothing the application does, so given a tie, do not destroy evidence — and pinned it with
  assertions so a later tidy of the `WHERE` fails the build.
- **T-045 fixed three comments its own edit had falsified**, declared them for veto, and was right:
  fixing a comment your edit made untrue is not the same as improving the file.

---

## Reservations, in one place

Nothing below is written yet **except wave 1's and wave 2's**, both in the working tree — wave 2's `api.ts` and `nav.ts` reservations were written in one pass on 2026-09-07 immediately before dispatch. Each block goes into
the shared files in a single pass immediately before its wave is authorised.

**Migrations** — the tree is at `V94`, so:

> **Renumbered 2026-09-07, and it was forced rather than tidy.** T-034 ships in wave 3, ahead of
> every migration this table already held, and **Flyway applies in ascending order and refuses a
> version below the highest already applied** — so T-034 takes `V95` and the eleven behind it slid up
> one. T-019's conditional `V106` is gone entirely: its migration *became* T-034's. Nothing had been
> written to disk, so the renumber cost a table edit and nothing else. The lesson is worth keeping:
> **a migration reservation is only safe while its wave order is fixed**, and inserting a wave
> invalidates every number after it.

> **Corrected 2026-09-07 by the main session, after wave 3 shipped `V95`.** The renumber above was
> applied to this table and to each task's `migration:` bullet, but **not to the migration filenames
> in the path contracts** — every one from T-023 onward still named the version below its allocation.
> A builder follows its path contract, so T-023 would have written `V95__supplies_are_flagged_ingredients.sql`
> against a database where `V95` was already applied, and **Flyway would have refused to boot** —
> precisely the collision this table exists to prevent, and the one failure mode that shows up at
> startup rather than in a diff. T-023 was worst: it carried **three** different numbers — `V95` in its
> path, `V100` in its bullet, `V96` here. All eleven filenames now match this table.
>
> The lesson on top of the one already recorded: **a renumber has to sweep the path contracts too.**
> The table is what a planner reads; the filename is what a builder types.

> **Third and fourth corrections, both on 2026-09-07, and together they retire the idea that this can
> be done by eye.** Wave 4c slid the block twice more — `V96` for T-047's fix-forward, then `V97` for
> T-048's backfill — and **each sweep failed in a new way**:
>
> 1. **The regex that swept the table did not match the filenames.** `\b` is not a word boundary
>    between `V96` and `__`, because `_` is a word character. The table moved ten rows and not one
>    path contract followed. This is the *same failure as the one recorded above*, reached by a
>    different route: last time the sweep was forgotten, this time it silently matched nothing.
> 2. **A line-range guard meant to protect the historical narrative silently skipped two live
>    contracts.** The excluded band had been widened after the file grew, and by the second slide it
>    covered T-016's and T-013's rows. Two filenames stayed a version behind and the table said
>    otherwise — which is precisely the state that makes Flyway refuse to boot.
>
> **Both were caught by a verification pass and neither by reading.** The check that catches them is
> mechanical and takes seconds: for every `db/migration/V<n>__*.sql` in the file, find the `### T-nnn`
> heading above it and assert that the version table gives that same task for `V<n>`. It ran, printed
> `MISMATCH` twice, and printed `ALL CONSISTENT` only after both were fixed.
>
> **So the standing rule is now: never hand-verify a renumber.** Sweep it, then pair filenames against
> the table programmatically and paste the result. Four attempts at this in one batch have produced
> three different failures, and the one thing that has caught every one of them is the pairing check.

| Version | Task | Wave | For |
|---|---|---|---|
| `V95` | T-034 | **3** | The meal a shift is for — `meal_date`, `meal_kind`, `meal_event_name` on `shifts`, all-or-nothing (D-14) |
| `V96` | T-047 | **4c-3** | A fix-forward correcting V64's column comment, which T-038 made false — plus nothing else. See T-047. |
| `V97` | T-048 | **4c-3** | Nulling the `0,0` delivery pins already written — the damage T-044 stopped, not the cause. Tenant-owned: per-tenant, under RLS. |
| `V98` | T-023 | 5 | The flag separating supplies from food on `ingredients` |
| `V99` | T-024 | 5 | Nullable `ingredient_id`, a `description`, and a check that exactly one is present |
| `V100` | T-025 | 5 | `vendors.phone` off `NOT NULL`; the E.164 check permits null |
| `V101` | T-010 | 7 | Invoice void/credit states, payment reversal marks |
| `V102` | T-012 | 7 | Donation void |
| `V103` | T-014 | 7 | Staff reinstatement — **conditional**, may go unused |
| `V104` | T-007 | 8 | The figures a meal was corrected from |
| `V105` | T-016 | 8 | Attendance on `shift_signups` (tenant-owned: RLS-respecting, per-tenant backfill) |
| `V106` | T-013 | 9 | The return-to-vendor movement type and its `CHECK` |
| `V107` | T-020 | 9 | The donation-receipt document kind and its `donation_id` |


Every one of `V95`, `V97`, `V98`, `V99`, `V100`, `V102` and `V105` touches a tenant-owned table. Migrations are
themselves subject to RLS in this project, so each backfills per tenant and never across all rows.

**Error codes.** The nine numbers the first plan proposed never existed; these are their
replacements. **Renumbered a second time on 2026-09-07**, for the same reason the migrations were:
T-033 and T-034 ship in wave 3 and each needs a code, so they took `KMS-400124` and `KMS-400125` —
the next two free — and the fourteen behind them slid up by two, to `KMS-400126`–`KMS-400139`. Codes
carry no ordering constraint the way migrations do; the slide is to keep the file's own convention
that **numbers ascend in the order they will be appended to `ErrorCode.java`**. None of the fourteen
had been written, so this too was a table edit. Constant names, statuses and copy are unchanged.

**Renumbered a third time on 2026-09-07, and by now the pattern matters more than the event.** T-038
arrived in wave 4c — ahead of every wave holding a reservation — and needed a code. It took
`KMS-400126`, written into `ErrorCode.java` in the pass before that wave was dispatched, and the same
fourteen slid one more place to `KMS-400127`–`KMS-400140`. Three slides of one block in one batch
says what the first two did not: **codes reserved for wave five and later will move again, every time
a defect found on staging is scheduled ahead of them.** That is tolerable only because of an asymmetry
with migrations that is worth stating outright. A slid code is wrong in this file and nowhere else,
and no builder ever reads its code from here — it is handed to it verbatim in its brief. A slid
migration version is wrong in a filename a builder types, and Flyway refuses to boot rather than
showing a diff. So this table is swept mechanically; the migration table is swept with care, and the
path contracts with it.

| Code | Task | Wave | Text / next step |
|---|---|---|---|
| `EQUIPMENT_NOT_SCRAPPED` **`KMS-400124`** (409) | T-033 | **3** | "This item hasn't been scrapped." / "There is nothing to reinstate." |
| `SHIFT_MEAL_LINK_INCOMPLETE` **`KMS-400125`** (400) | T-034 | **3** | "A shift linked to a meal needs the date and the meal kind together." / "Give both, or leave the shift unlinked so it counts by its hours." |
| `MEAL_KIND_IN_USE` **`KMS-400126`** (409) | T-038 | **4c** | "Meals have already been planned or recorded as this kind." / "Rename it instead. Everything recorded under it takes the new name." |
| `NOT_A_FOOD_INGREDIENT` **`KMS-400127`** (409) | T-023 | 5 | "That's a supply, not something you can cook with." / "Choose a food ingredient, or add this one to the catalogue as food." |
| `PURCHASE_LINE_NEEDS_A_SUBJECT` **`KMS-400128`** (400) | T-024 | 5 | "Each line needs either an ingredient or a description, not both and not neither." / "Pick an ingredient, or describe what you're buying." |
| `CANNOT_RECEIVE_A_DESCRIBED_LINE` **`KMS-400129`** (409) | T-024 | 5 | "A described line can't be received into stock." / "Record it as delivered on the order; it isn't something the store tracks." |
| `VENDOR_HAS_NO_WHATSAPP_NUMBER` **`KMS-400130`** (409) | T-025 | 5 | "This vendor has no phone number to send to." / "Download the order and hand it over, or add a number to the vendor." |
| `ALREADY_ON_THE_SHOPPING_LIST` **`KMS-400131`** (409) | T-027 | 6 | "That's already on the shopping list." / "Change the quantity on the line that's there." |
| `INVOICE_ALREADY_VOIDED` **`KMS-400132`** (409) | T-010 | 7 | "This invoice has already been voided." / "Look at the credit note recorded against it." |
| `PAYMENT_ALREADY_VOIDED` **`KMS-400133`** (409) | T-010 | 7 | "This payment has already been struck." / "Record a new payment if one was actually made." |
| `DONATION_ALREADY_VOIDED` **`KMS-400134`** (409) | T-012 | 7 | "This donation has already been voided." / "Record it again if it was actually received." |
| `EMPLOYMENT_NOT_ENDED` **`KMS-400135`** (409) | T-014 | 7 | "This person is still employed." / "There is nothing to reinstate." |
| `MEAL_ALREADY_CORRECTED` **`KMS-400136`** (409) | T-007 | 8 | "This meal has already been corrected." / "Look at the correction that was recorded against it." |
| `NOTHING_FAILED_TO_RETRY` **`KMS-400137`** (409) | T-015 | 8 | "Every copy of this message was delivered." / "There is nothing to send again." |
| `ATTENDANCE_ALREADY_RECORDED` **`KMS-400138`** (409) | T-016 | 8 | "Attendance for this shift has already been recorded." / "Change it on the shift's roster." |
| `RETURN_EXCEEDS_RECEIVED` **`KMS-400139`** (400) | T-013 | 9 | "You can't return more than was received." / "Check the quantity against the goods receipt." |
| `ALREADY_RETURNED` **`KMS-400140`** (409) | T-013 | 9 | "These goods have already been returned." / "Look at the return recorded against this receipt." |

Every one satisfies `ErrorCodeTest`: unique, no jargon, a non-blank next step, both sentences ending
in a full stop, `KMS-\d{6}` (`ErrorCodeTest.java:93`), and `number/100000 == httpStatus/100`
(`:104-108`). All seventeen are 4xx, so all seventeen are in the `400xxx` family. `ErrorCodeTest` asserts
no ordering, checked — so the renumber above is convention, not compulsion.

**Two text changes to existing codes.** T-033: `EQUIPMENT_SCRAPPED` **`KMS-400043`**
(`ErrorCode.java:261`) keeps its number and its first sentence — *"This item has been scrapped, so
its condition can't change."*, which stays true. Its next step becomes *"Register a replacement, or
reinstate this item if it's back in use."*, because *"Register a replacement if you've acquired
one."* stops being the only thing the reader can do. **Written by the work manager, not the
builder.** And T-007: `MEAL_ALREADY_RECORDED` **`KMS-400098`**
(`ErrorCode.java:551`) keeps its number and its first sentence. Its next step becomes *"Record a
correction if the figures are wrong."* — because the current one, *"What was cooked can't be changed
afterwards. Ask a Temple Admin if the figures are wrong."*, becomes untrue the day T-007 ships. D-4
says so explicitly.

**Permissions.** Three new constants — two settled by `DECISIONS.md` **D-4**, one by **D-15** — all
three `TEMPLE_ADMIN` only, no new roles. The enum goes from 36 to 39; the five roles stay five.
`RolePermissionsTest` asserts no permission counts (only that a volunteer holds fewer than the other
roles), checked, so a grant added to `TEMPLE_ADMIN` breaks nothing.

| Constant | Task | Wave | Grant | Why admin-only |
|---|---|---|---|---|
| `REINSTATE_SCRAPPED_EQUIPMENT` | T-033 | **3** | `TEMPLE_ADMIN` | Scrapping stays on `MANAGE_INVENTORY`, which everyone who runs the kitchen holds; taking a scrapping back does not. Undoing a disposal is a different kind of act from recording one, and the register hides scrapped items by default — somebody reinstating one has gone looking for a thing the temple already wrote off. **Confirmed by Rajeev, 2026-09-07**, when it was put to him as an assumption (D-15). |
| `VOID_DONATION` | T-012 | 7 | `TEMPLE_ADMIN` | Forced, not chosen: `VIEW_DONATIONS` is already Temple Admin alone, so anything wider lets somebody void a record they cannot read. |
| `CORRECT_RECORDED_MEAL` | T-007 | 8 | `TEMPLE_ADMIN` | Recording stays on `MANAGE_MEAL_PLANS` — everyday kitchen work. Correcting rewrites a number stock consumption and cost-per-serving have already inherited. Widening later is one line; narrowing after temples build a habit is a conversation with every one of them. |

**Note the rename.** This file previously proposed `CORRECT_DONATIONS`. D-4 named it
**`VOID_DONATION`**, and D-4 wins.

**A sixth reserved file, learned from wave 4b: `backend/src/main/java/org/iskcon/kms/audit/AuditAction.java`.**
It is the same kind of file as `ErrorCode.java` and `Permission.java` — an enum with a paragraph of
reasoning per constant, appended to by nearly every task that records anything, and corrupted by two
concurrent appends in exactly the same way. It was not in the protocol's table and it should have
been. T-008 found it the honest way: it needed `TENANT_UPDATED`, found the file outside its contract,
used the neighbouring `SETTINGS_UPDATED` **and said so** rather than either reaching in or quietly
filing the act under the wrong name.

| Constant | Task | Wave | Why its own action |
|---|---|---|---|
| `TENANT_UPDATED` | T-008 | **4b** | Written 2026-09-07, between `TENANT_EXPORTED` and `ROLE_CHANGED`. An operator changing what a temple *is* is not a temple admin changing that temple's settings, and two fields make it a different kind of act: `timezone` silently rewrites the precomputed calendar, and `is_80g_approved` is a legal status a receipt quotes. **No migration** — `V3__audit_events.sql:98` constrains `action` only with `length(action) > 0` and has no enumerated CHECK, verified before allocating. |

**A seventh reserved file, learned from wave 4c: `frontend/__tests__/design-system.test.ts`.**
Not for the usual reason. It holds no allocations and nothing is appended to it — but it is a
**repo-wide guard** that scans every file under `app` and `components`, so it is the one test file
that any task can break without touching it, and the one file two tasks can need in the same wave for
unrelated reasons. Wave 4b had to hand it to T-008 mid-wave after checking ownership. Wave 4c has two
tasks in the tenants area, one of which (T-041) removes the very literal the guard exempts for its
page and the other of which (T-042) keeps its exemption — so it was **forbidden to both** and its
maintenance made the work manager's, done after the wave and before the merged-tree run. The rule
this settles: **a guard test that scans the whole tree belongs to the work manager, because by
construction no builder's targeted run loads it.**

**Both files, not one.** Each constant is declared in `backend/.../auth/Permission.java` with the
paragraph explaining why it was split out, and granted in `backend/.../auth/RolePermissions.java`.
The protocol's reservation table named only the second; both are reserved. `Permission.java` is
written to be read as a document in exactly the same way, and concurrent appends corrupt it just as
surely.

**`frontend/lib/api.ts`** — wrappers and types to stub, by wave. W2: `createOccasion`,
`updateOccasion`, `deleteOccasion`, `updateEquipment`. **W3: `reinstateEquipment`, and `mealDate` /
`mealKind` / `mealEventName` on `ShiftView` and on `ShiftInput`, all three **optional** — written
2026-09-07, before dispatch. Required-nullable was tried first and broke `volunteer-shifts.test.tsx`,
which builds a `ShiftView` by hand; optional keeps the reservation inside the one reserved file
instead of reaching into another task's test. Nothing in `frontend/app` reads the three fields yet; they are
there so T-019 in wave 9 builds against a type that already matches the server.**
**W4a: none at all** — T-035, T-036 and T-037 need no wrapper and no type change between them.
**W4b, as it actually stands after the wave:** `updateTenant` with `UpdateTenantInput`; `ScheduleLeaveDay`
plus `leaveDays`/`leaveFrom`/`leaveTo` on `StaffProfileDetailView` for T-032; and `latitude`/`longitude`
on `TenantDetail`, added *after* T-008 reported needing them — required rather than optional, because
the endpoint always sends both and an optional pair would let a blank form post a silently relocated
temple. **`deleteMealKind` was written before dispatch and reverted after**, together with the
`/settings/meal-kinds` row in `nav.ts`: T-005 stopped without building its screen, and a menu row
pointing a Temple Admin at a route that does not exist is a defect this file would have shipped.
Wave 1 set that precedent — two nav edits, one shipped, one reverted — and this is the second time a
reservation has had to be taken back. Both reverts were made **after every builder was out of the
tree**, never under a running `next build`.
**W4c-1: one field, and it is the whole of T-043.** `RecordMealInput` gains
`eventName: string | null` between `mealKind` and `note` — **required and nullable, not optional**,
written 2026-09-07 before dispatch. Optional was considered and rejected: optional is what let the
field be missing in the first place, since a caller that omits it still compiles. Required-and-nullable
makes every call site say which case it is in, and it is the type change rather than the call change
that fixes the class of defect. It breaks any hand-built fixture until updated, which is intended and
which is why T-043 holds the three planner test files.
**W4c-2, written in one pass before dispatch:** `changeUserRole` **deleted** for T-040 — the wrapper
itself, not just its callers, since there are none — and T-044's `deliveryLatitude`/`deliveryLongitude`
on `MealPlanView` and on `CreateMealPlanInput` (hence `UpdateMealPlanInput`, which is an alias of it),
**required-and-nullable in a record whose every other field is optional**. That inconsistency is the
point: the defect was that a payload builder could omit them and compile, because `mealFacts()` has no
return-type annotation and its result is *spread*, and spread properties are exempt from the
excess-property check. `null` means "typed, not picked" and is a thing a caller is allowed to say;
`undefined` was the ambiguity the defect lived in. T-046's reservation is not in this file at all —
it is `AuditAction.STAFF_EMPLOYMENT_END_REJECTED`, written the same pass.

**W4c-3:** `ekadashiProhibited` on `IngredientView` and `CreateIngredientInput` (required, not
optional — the Java field is a primitive `boolean`, so an absent key deserialises to `false`, and
`undefined` in TypeScript is falsy in exactly the same way), plus the missing `setIngredientEkadashiFlag`
wrapper mirroring `setIngredientSattvicFlag`; and T-042's geocoding wrapper and result type.
**This slice was written before 4c-2 and rolled back out**, because `tsc` is repo-wide and it would
have shown T-045's six broken fixtures inside T-044's verification run. Deferred, not abandoned.
W5: the supplies flag on `IngredientView` / `CreateIngredientInput` / `UpdateIngredientInput` and a
filter argument on `listIngredients`; `ingredientId`/`ingredientName` become optional and
`description` is added on `PoLineInput` and `PurchaseOrderLineView`; `phone` becomes optional on
`VendorInput` and `VendorView`. W6: `addShoppingListLine` — **none for T-026**, which needs no new
wrapper at all. W7: `voidInvoice`, `creditInvoice`, `voidInvoicePayment`, `voidDonation`,
`reinstateStaff`. W8: `correctRecordedMeal`, `movementsForMeal`, `retryFailedDeliveries`,
`recordShiftAttendance`, `releaseVolunteerFromShift`. W9: `returnReceivedGoods`,
`generateDonationReceipt`, `resendDonationReceipt`, and a shifts-for-range wrapper if none exists.

> **Corrected 2026-09-07 while planning 4a, and it is the same class of error as the migration
> filenames.** This list carried **two `W4:` entries**, and every label from the second onward was one
> wave too low — the supplies flag was filed under W4 when T-023/T-024/T-025 are wave 5, and so on
> down to the donation receipt, filed under W8 when T-020 is wave 9. It was left behind by the wave
> insertion that created waves 5–9, which swept the task rows and the migration table but not this
> paragraph. **No builder was ever misled**, because each task's own **reservations** bullet is what a
> builder is given and every one of those was right; this is the planner's index, and it was wrong for
> the planner. Recorded rather than quietly fixed, because the count of places a wave number hides in
> this file is now three: the task rows, the tables, and this list.

**`frontend/lib/nav.ts`** — five edits. Wave 1 widened `/donate` to add `ADMIN`; **T-030 reversed
that** and narrowed the row to `[VOLUNTEER]` (D-8, subsumed by D-10). Wave 2 added `/my-schedule` and
`/settings/occasions`. **Wave 3 narrows `/my-shifts` to `[VOLUNTEER]`** — the edit wave 1 made, then
reverted for want of a ruling, and which **D-16 has now ruled**; written 2026-09-07 before dispatch,
with a comment recording D-16 and pointing at `/my-schedule` as what staff have instead. One row is
left, in wave 4: `/settings/meal-kinds`. Neither procurement screen needs a row — `/orders/new` is
reached from `/orders` and the shopping-list add is on the list itself. `routes.ts` and `Sidebar.tsx`
are untouched by this batch.

---

## For Rajeev — found while building wave 3, not scheduled

**T-022 was told that if a characterisation test found a defect it should report it and not fix it.**
It found four. None is scheduled, none has an id, and none is in any wave. Each is asserted in the
new tests **as it currently behaves**, so fixing one means coming back and changing what its test
says — which is the point.

### 1. Registration can strand a person with an identity and no temple — ~~**needs a decision**~~ **→ T-037, wave 4a**

> **Decided by Rajeev, 2026-09-07**, taking the recommendation below: **remember the credential across
> attempts and resume at the join** — not the compensating delete (*"the most destructive of the three
> and the hardest to get right"*) and not the reordering (*"the largest change"*). It applies to the
> Google and phone branches too. **The characterisation test is updated to assert the fixed behaviour,
> never deleted** — its own comment says whoever fixes this must come to it and change what it says.
> Scheduled as **T-037** in wave 4a. The finding stands below exactly as it was written.

`frontend/app/register/page.tsx`, `createAccount()`: the Firebase credential is created **before**
`api.joinTemple` is called, and a refused join leaves the account behind with nothing to remove it
and nothing to remember it. The second press hits `auth/email-already-in-use` and the screen answers
*"There is already an account with that email. Sign in instead."* — advice that is **wrong for this
person**, who now has an identity and a membership nowhere. The join is never retried and the
registration flow is a dead end from that point.

It is recoverable only by accident: signing in gives `whoami` a 401, which `RequireRole` bounces to
`/choose-temple`. Nothing tells them that, and nobody would guess it.

**Three shapes are possible and it is Rajeev's call, but there is a recommendation.** Delete the
credential compensatingly; remember it and skip straight to the join on the second press; or reserve
the temple first and create the credential last.

**Recommended: remember it and resume at the join.** The third is probably not buildable —
`/api/v1/temples/{id}/join` needs an authenticated uid (`AuthenticationFilter.java:123,129-134`
admits a verified uid with no membership precisely so this flow works), so the identity genuinely has
to exist first. The first is destructive on a failure path: deleting a Firebase account when the
delete itself may fail leaves somebody worse off than the bug does, and the account may be legitimately
theirs. The second matches what actually happened — **their identity is fine and only the membership
is missing** — so on `auth/email-already-in-use` the screen signs in with the credential just given
and retries the join. It turns a dead end into a retry, deletes nothing, and is the same shape as the
claim-on-match design already in the product.

### 2. The shared recipe library's `×` permanently deletes a cross-tenant recipe, with no confirmation

Operator-only (`MANAGE_RECIPE_LIBRARY`, and `Permission.java` says why: *"the library reaches every
temple on the platform"*). That is the reason it is worth raising rather than the reason it is not —
a single misclick removes something every temple reads, with no confirmation step and no undo. Every
other destructive act in this batch got a confirmation naming the consequence.

### 3. `/choose-temple` paints its whole form for one frame before redirecting an already-signed-in reader

Cosmetic, and the sort of thing that reads as a broken screen on a slow device.

### 4. `/unsubscribe` sends an empty token to the server when `?token=` is absent

A pointless round trip that produces a failure the reader cannot act on. It is the one screen in the
application reached from an email by somebody with no session, which is why P9 wanted it tested.

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

---

## Found while verifying waves 1 and 2 on staging — 2026-09-07, not yet tasks

Driving the live site as each role, under Rajeev's amendment that verification is now mine
([[verification-is-mine-now]] in the session memory). **Everything in wave 2 passed** — T-004's
create/list/delete round trip, T-006 as a real cook, T-009's edit persisting and its scrapping
confirmation, T-028's vendor surviving two `PATCH`es, T-030 refusing `/donate`. Wave 1's T-001 and
T-003 passed too. These four are what did not.

1. **A refused page strands the reader — no navigation, no sign-out.** **→ scheduled as T-035, wave
   4a** (2026-09-07), which existed for this already; Rajeev's fourth surface and the sign-out are
   folded into its row rather than given a second id, because `AccountDisabled` is declared inside
   `RequireRole.tsx:35-47` and a second id would have put two builders in one file. Seen on four
   surfaces — `/donate`, `/shifts` and `/my-shifts` as `KITCHEN_STAFF`, and the disabled-account
   screen. Original finding, for the record: — seen on three surfaces:
   `/donate` and `/shifts` as `KITCHEN_STAFF` ("Not your page"), and the disabled-account screen
   (`KMS-400019`). The last is the worst — a disabled person cannot even sign out to try another
   account, so the tab is dead. Cause on the guarded routes: `<Sidebar>` sits *inside*
   `<RequireRole>`, so the refusal branch renders without it; likely repeated across every guarded
   route, so this wants a look at all of them rather than one fix. Needs a shape decision — refusal
   inside the app chrome, or an explicit link out.

2. **The stock-correction dialog lowercases the date and the unit.** **→ scheduled as T-036, wave 4a**
   (2026-09-07), together with finding 3 — same file, same two components. Cause confirmed exactly:
   `summary` is assembled at `frontend/app/inventory/[id]/page.tsx:501-504` with the unit and date
   already formatted into it, and both call sites (`:545`, `:569`) lowercase the whole string to make
   the leading type label read mid-sentence. **The codebase already had this written down** —
   `components/planner/MealComposer.tsx:1362` records that `toLowerCase()` rendered litre `"L"` as a
   digit-like `"l"` — and it was reintroduced anyway. Original finding: It reads *"The adjustment of
   +1.8 kg on 23 aug 2026"* where the table directly above it renders *"+1.8 Kg"* and
   *"23 Aug 2026"*. Consistent with a `.toLowerCase()` on an already-formatted string so it reads
   mid-sentence; it corrupts the month abbreviation and the unit together. Units carry meaning here
   (`Kg`/`gm`/`L`), so it is not purely cosmetic. The ledger row formats correctly — this is the
   dialog only.

3. **"Correct" is offered on a movement already badged "Corrected".** **→ scheduled as T-036, wave
   4a** (2026-09-07), merged with finding 2. The row already holds the answer three lines above the
   button: `reversedBy = byOriginal.get(m.id)` (`:402`) draws the badge at `:429-433`, and the
   *Correct* control at `:438-443` is rendered unconditionally regardless. Original finding: The
   server refuses correctly
   with `MOVEMENT_ALREADY_CORRECTED` **`KMS-400039`** (`StockMovementService.java:158-161`) and the
   screen renders that refusal readably. But the row draws a *Corrected* badge, so it already holds
   the fact — and still offers the control, meaning a person writes out a reason before being told
   no. This is the defect class T-002 existed to remove, on the screen T-001 built.

4. ~~**`/my-shifts`'s empty state points a cook at a page that refuses them**~~ **RESOLVED by T-031,
   verified on staging 2026-09-07 after the wave-3 deploy: signed in as `ikms.kitchen-staff.1`, the
   *My shifts* row is gone from the menu and the URL typed directly gives "Not your page". A cook can
   no longer reach that empty state, so the sentence is now only ever read by volunteers, who can open
   `/shifts`. The reasoning held.** Original finding, for the record: — *"Who is covering which
   shift is on the Volunteer shifts screen"*, and `/shifts` gives that same cook "Not your page".
   T-002's own acceptance was that an empty state must not point anywhere the reader is refused.
   **Expected to self-resolve** when T-031 lands, since only volunteers will then reach it and they
   can open `/shifts` — worth confirming after the wave-3 deploy rather than assuming.

**Not verified, and not counted:** T-029's Google account chooser, which needs an OAuth popup and
which Rajeev effectively confirmed himself when it unblocked his wave-1 testing.


### Wave 3 verified on staging — 2026-09-07, after `527b23b`

Deployed by the main session (the release agent was denied `deploy.sh` and even a read-only
`gcloud run describe`, while the same commands were allowed from the main session — the classifier is
stricter on subagents). Revisions and digests both moved: web `00104-7bd` → **`00105-bkw`**
(`sha256:f4f29931…` → `sha256:40be8c39…`), api `00112-8jt` → **`00113-s9x`**
(`sha256:03509a4f…` → `sha256:fe17d405…`).

- **T-033 — passed end to end, as Temple Admin.** The panel copy now reads *"Scrapped is the end of
  this form — the item stays on the register, drops out of the list, and only a Temple Admin can
  bring it back."* The confirmation reads *"Scrapping takes it off the equipment list, and its
  condition can no longer be changed here. Only a Temple Admin can bring it back, and they must
  record why."* **Both false sentences are gone** and the *Needs repair* steer survives. Scrapped the
  wet grinder, saw *Change condition* **replace itself** with *Bring it back* — the sanctioned
  deviation, and the right one — reinstated it as *Needs repair* with a reason, and the audit trail
  shows `Scrapped → Needs repair` with the reason, the actor and the timestamp beside the
  `Good → Scrapped` that preceded it. That is exactly what Rajeev asked for, in his own case: someone
  who cannot find a replacement bringing a scrapped item back. The reason is enforced — submitting
  empty raises the browser's required-field validation. Record restored to `Good` afterwards; the
  trail keeps all of it, which is correct.
- **T-031 — passed at both ends.** As `ikms.kitchen-staff.1`: no *My shifts* row in the menu, and the
  URL typed directly gives "Not your page". No *Donate* either, so T-030 still holds after the redeploy.
- **T-034 — not verifiable through the UI, and that is expected.** It is the crew-linkage model; the
  planner affordance that would exercise it is T-019, still in the last wave. `V95` applied (the API
  is serving on the new revision) and its 11 `ShiftMealLinkIT` cases passed on the committed tree.
  **Recorded as proven-by-test, not seen working**, and it should be pressed by hand the moment T-019
  lands.
- **T-022 — nothing to see.** Tests only; it wrote no product code by design.

**One small thing noticed, not raised as a defect:** the reinstatement form's required-reason check
surfaces as the browser's native validation bubble rather than the app's own error styling, which is
inconsistent with how the rest of the product reports a missing field. Worth a look whenever the
refusal-screen work (finding 1) is done, since both are about how refusals are presented.

> **Looked at while planning 4a, and deliberately not scheduled — this is a finding, not a task.**
> Rajeev asked whether it belongs in T-035. **It does not**, and the reason is worth keeping:
>
> - **It is not a wave-3 regression.** `frontend/app/equipment/[id]/page.tsx` uses a raw
>   `<label>`/`<input required>` pair in **three** places — the condition-change form (`:513`), the
>   reinstatement form T-033 added (`:687`) and the service-record form (`:764`). T-033 copied the
>   screen's existing convention. Fixing only the newest of the three would leave one form on the
>   app's styling and two on the browser's, on the same page, which reads worse than the
>   inconsistency it set out to fix.
> - **It cannot go in T-035 without breaking the one rule that keeps T-035 honest.** That task is
>   forbidden every file under `frontend/app/`, precisely so a one-file fix for all 82 guarded routes
>   does not turn into a page-by-page march. `equipment/[id]/page.tsx` is one of those pages.
> - **The pattern to adopt already exists and needs no invention** — `frontend/components/Field.tsx`,
>   whose `FIELD_ERROR` (`:51`) and `aria-describedby`/`aria-invalid` wiring (`:74-76`, `:91`) are what
>   `tenants/new/page.tsx:162` and four other screens already use. So this is a *consistency pass over
>   forms that use raw inputs*, which is a real piece of work with a name, an owner and a scope —
>   and not a corner of a defect wave. Sizing it means counting the raw `required` inputs across
>   `frontend/app/`, which has not been done.
>
> **Routed to the main session rather than to Rajeev**: he is away and asked to be woken only for a
> real blocker, and this blocks nothing. It is here so the forms pass starts with the evidence.


### Wave 4a verified on staging — 2026-09-07, after `eea869f`

Deployed by the main session in 6m34s. Web `00105-bkw` → **`00106-9vk`**
(`sha256:40be8c39…` → `sha256:534b2bee…`). **The api digest did not change** (`sha256:fe17d405…` on
both `00113-s9x` and the new `00114-b2r`) — a new revision over an identical image, which is
independent confirmation that wave 4a was genuinely frontend-only, exactly as the release agent found
when its backend run came back a clean no-change.

**All three defects are fixed on the live site.**

- **T-035, wrong-role branch.** As `ikms.kitchen-staff.1` at `/donate`: the sidebar is back, the whole
  menu is usable, and a **"Go to Today"** button sits under the refusal — labelled from that reader's
  own `nav.ts` row and pointing at their own home, not a hard-coded `/today`. No menu row is
  highlighted, which is the deliberate `activeHref=""`. Before this, the same page was bare white with
  no way out but the browser's back button.
- **T-035, disabled account — and this is the one that mattered.** Disabled
  `ikms.volunteer.5`, signed in as them, got *"This account has been disabled … Signed in with the
  wrong account? **Use a different account**"* with `KMS-400019` still quotable and still no menu.
  **Pressed the button: it ended the session and landed on `/sign-in`.** The dead tab is gone. This
  was worth pressing rather than reading, because it is a control that ends a session and a proof by
  test would not have shown it working.
- **T-036, casing.** The dialog now reads *"The adjustment of **+1.8 Kg** on **7 Sept 2026**, 21:54"*
  where it read *"+1.8 kg on 23 aug 2026"* before. Matches the table above it.
- **T-036, the refused control.** Both movements badged *Corrected* now show an **empty actions
  cell**; only the one uncorrected movement still offers *Correct*. The screen no longer asks for a
  reason it is going to refuse.

**T-037 is not verified and is not counted.** Reaching it needs a temple join the server actually
refuses, which cannot be constructed from the UI. It stays proven-by-test.

**Test data restored:** `ikms.volunteer.5` re-enabled (7 active), Almond still at 1.8 Kg, the wet
grinder back to `Good`.

**Still owed to a human, carried forward:** T-034's crew linkage (proven by test; unverifiable until
T-019 builds the planner affordance that exercises it) and T-037's stranded-session sliver — somebody
who loses their Firebase session entirely still reads "Sign in instead", which wants a "signed in, no
membership" screen and is its own task.


### Wave 4b released — 2026-09-07

**Three commits**, in the order the wave was worked, on top of `eea869f`:

| Commit | Task | What is in it |
|---|---|---|
| `49ce170` | T-032 | `/my-schedule` shows approved leave, resolved on the server |
| `8605028` | T-008 | `PATCH /api/v1/tenants/{id}`, `/tenants/[id]/edit`, `TENANT_UPDATED` |
| `ec8d575` | ledger | this file, `DECISIONS.md` (D-16 closed), `WORK_QUEUE.md` item 4, and all four proof files |

The `frontend/lib/api.ts` slice was **split by hunk** so each product commit carries only its own
task's share — the leave types to `49ce170`, `UpdateTenantInput`/`updateTenant`/the `TenantDetail`
coordinates to `8605028`. The two applied in order reproduce the merged tree byte for byte, checked
with `diff`, not by eye. `AuditAction.java`, `design-system.test.ts` and `tenant-detail.test.tsx` went
with T-008, which is the only task that needed them.

**The gate — a full suite over `git archive HEAD` in a clean directory, `git init && git add -A` so
`design-system.test.ts` can run its `git ls-files`.** Both halves reproduce the work manager's
merged-tree numbers exactly, which is the useful part: the tree that ships is the tree that was
verified.

```
backend   Total: 1751  Passed: 1749  Failed: 0  Skipped: 2  SUCCESS   BUILD SUCCESSFUL in 3m 31s
frontend  npm ci clean · TSC CLEAN · Test Files 98 passed (98) · Tests 1053 passed (1053)
          ✓ Compiled successfully · ƒ /tenants/[id]/edit  3.52 kB
```

`TenantUpdateIT` 12/12 and `OwnScheduleLeaveIT` 4/4 in that clean run, read out of the JUnit XML
rather than off the console. `tools/check-ignored-sources.sh`: *"No ignored source files. Every source
file under 6 trees is in git."*

**CI green**, run [`34156101124`](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/actions/runs/34156101124)
on `ec8d575` — Backend, Frontend and Repository all `success`.

#### The release agent deployed this one itself, and that is new

**Every previous release agent was refused `deploy.sh` and even a read-only `gcloud run describe`,
while the same commands succeeded from the main session** — waves 2, 3 and 4a were all deployed by
hand for that reason, and T-035's row still says so. Rajeev added scoped allow rules before this wave
and **they work from a subagent**: `gcloud run describe`, `gcloud auth application-default
set-quota-project` and `infra/deploy.sh` all ran without a prompt. The hand-off in the middle of a
release is gone.

Deployed in **8m27s** (builds 6m26s, rollouts 1m59s) — slower than the 5m49s warm figure, which is
expected: this wave changed the backend, so neither image came off a warm cache.

| Service | Revision | Image digest |
|---|---|---|
| `kms-staging-api` | `00114-b2r` → **`00115-gv6`** | `sha256:fe17d405…` → **`sha256:c76d440a…`** |
| `kms-staging-web` | `00106-9vk` → **`00107-6l2`** | `sha256:534b2bee…` → **`sha256:f88fa494…`** |
| `kms-staging-worker` | `00097-…` → **`00098-9zc`** | takes the api's new `sha256:c76d440a…` |

All three revisions were created at 19:50–19:51 UTC, so this is a new build and not an old image
re-pointed. **Both digests moved**, which is the check that matters: wave 4a's api digest deliberately
did not, because that wave was frontend-only.

**Proved by behaviour, not by the exit code** — `deploy.sh` has exited 0 while leaving the old image
live under new environment variables, so the served bundle was probed for strings only this wave
contains:

- `/tenants/[id]/edit` **is served at all**, which it was not before this wave, at
  `_next/static/chunks/app/tenants/%5Bid%5D/edit/page-cd300386abcf8c44.js` — and that chunk contains
  `Edit this temple` and `Approved for 80G receipts`.
- `my-schedule/page-9fcbfe64a6c92caf.js` contains `Approved leave is only shown up to` and
  `, half day`, and — the negative half, which is the stronger evidence — **no longer contains
  `Approved leave is not shown here.`**, the admission T-006 shipped with and T-032 removed.

The api half has no equivalent no-token probe: `AuthenticationFilter` answers 401 for a route that
exists and a route that never did, so an unauthenticated `PATCH /api/v1/tenants/{id}` cannot tell the
two apart. Its evidence is the moved digest, the migration step and health check the rollout passed,
and `/actuator/health` answering 200 on the new revision. Minting a super-admin token would settle it
outright but needs an IAM binding that is outside the rules granted here.

**Neither task has been certified by observation, and both want one pass.** `/tenants/[id]/edit` as
the operator: change a name, tick 80G, confirm the detail page reads *Approved*, then change the
timezone and confirm that temple's calendar rebuilds rather than staying on the old tithi.
`/my-schedule` as somebody on the payroll — which now needs **approved leave in the next fortnight**,
one full day and one half day, and no longer needs an account that did not exist.


### Wave 4b verified on staging — 2026-09-07/08, after `fe1c4e1`

Deployed by the **release agent itself** — Rajeev's scoped `gcloud` allow rules work from a subagent,
so the hand-off to the main session is no longer needed and T-035's row saying otherwise has been
corrected. Api `00114-b2r` → `00115-gv6`, web `00106-9vk` → `00107-6l2`, worker to `00098-9zc`, all
digests moved.

**T-032 — verified end to end, and the data for it was built rather than waited for.** The proof said
this needed "a rostered person with approved leave in the fortnight" and treated that as unavailable.
It was not: `/leave` has **Record leave for someone**, approved as it is recorded. Recorded a full day
(8 Sept, *"Family function"*) and a half day (10 Sept, *"Afternoon off for a clinic appointment"*) for
Gopal Das, then signed in as `ikms.kitchen-staff.1` and opened `/my-schedule`:

- **Tuesday 8 September** reads **"Time off"** with the hours *removed* — a full day off shows no shift.
- **Thursday 10 September** keeps **"08:00–17:00"** and carries a **"Time off, half day"** badge — a half
  day annotates the hours rather than erasing them.

The distinction is handled correctly in both directions, and T-006's muted admission *"Approved leave
is not shown here."* is gone from the page. **The two leave records were left in place deliberately**:
they are approved leave for a test cook on a staging tenant, and they make this screen testable by a
human later without repeating the setup. Noted so a future reader does not mistake them for stray state.

**T-008 — half verified, and I stopped short of the other half on purpose.**

- **80G approval works.** Ticked *Approved for 80G receipts* as the platform operator, saved, and the
  detail page reads **"80G receipts: Approved"**; reopening the form shows the box still ticked, so the
  value round-tripped rather than being echoed. This is the field that could **never** be set after
  provisioning, so every receipt was permanently wrong before this.
- The screen is well made: the slug is shown as *"fixed when the temple was created and can't be
  changed"*, and the timezone field carries its own warning — *"Changing this rebuilds the temple's
  calendar — its tithi, Ekadashi dates and sunrise times are all worked out from it."*
- **The timezone change was NOT exercised, and this is a judgement rather than an oversight.** The
  offered zones are Kolkata, Dubai, London and New York — every one shifts the offset, so any test
  moves the temple's "today". The precompute is documented as correcting **past** days, which this
  ledger itself calls *"a decision nobody has yet seen the consequence of"*, and `f935450b…` is the
  single tenant every other UAT story depends on. Running an unattended, unobserved rebuild of its
  calendar overnight to prove a path that `TenantUpdateIT`'s 12 cases already assert is a bad trade —
  the failure mode is a wrong calendar on the shared tenant, and the person who would notice is asleep.
  **It is exactly the case worth Rajeev's own minute**, because what he would be checking is whether
  the consequence is acceptable, not whether the code fires.

**Still owed:** T-008's timezone rebuild (above), T-034's crew linkage (needs T-019), and T-037's
stranded-session sliver.

---

## Wave 4c released — 2026-09-07, `09404a0`…`8b7852f`

The largest release of the batch: **eleven tasks, ten commits, two migrations**, and the only wave so
far whose full backend suite had never been run to completion over the finished tree before the
release agent ran it.

### The gate

Run over `git archive HEAD` into a clean directory with `git init && git add -A`, which is what makes
it match what `actions/checkout` hands CI — without it `design-system.test.ts` dies on
`git ls-files` and takes twenty tests with it.

```
frontend  TSC-SILENT · Test Files 98 passed (98) · Tests 1075 passed (1075) · next build ✓ 67/67 pages
backend   Total: 1776  Passed: 1774  Failed: 0  Skipped: 2  Result: SUCCESS
          BUILD SUCCESSFUL in 3m 34s
```

**The backend figure reconciles exactly with the work manager's interim 1766, and the arithmetic is
worth writing down** because it confirms that agent's own note that its number was stale:
`1766 − 5 + 15 = 1776`. The **−5** is `ErrorCodeTest`'s five parameterised methods losing the retired
`KMS-400023` — the very edit the work manager made while its backend run was in flight and correctly
recorded as making the result stale. The **+15** is 4c-3's new tests: `GeocodingIT` 11, `MealKindIT`
+3 from T-047, `DeliveryPinBackfillIT` 1. A count that lands where the arithmetic says it should is a
better check than a count that is merely green.

`tools/check-ignored-sources.sh`: *"No ignored source files. Every source file under 6 trees is in
git."*

### The migrations, paired by script rather than by eye

Four attempts at a renumber in this batch produced three different failures, and the standing rule is
never to hand-verify one. The pairing check ran over every `db/migration/V<n>__*.sql` named in this
file, matched each against the `### T-nnn` heading above it and against the version table, and
printed **`ALL CONSISTENT`** — thirteen filenames, thirteen `OK`, no `MISMATCH` and no `NOT-IN-TABLE`.
On disk: `V1..V97`, 97 files, no duplicate and no gap; `V96` and `V97` are the only two absent from
`HEAD`, so neither collides with anything already applied.

Confirmed afterwards by the deployed application rather than by the script:

```
Migrating schema "public" to version "96 - meal kind deletion is refused when in use"
Migrating schema "public" to version "97 - unpin the gulf of guinea"
Successfully applied 2 migrations to schema "public", now at version v97 (execution time 00:00.126s)
```

### CI

Run **34182363865** on `8b7852f`, **success** — all three jobs green (`Repository`,
`Backend (Spring Boot)`, `Frontend (Next.js)`).
<https://github.com/rajeevkatamaneni/iskcon-kitchen-management/actions/runs/34182363865>

### Deploy

Deployed by the release agent in **9m13s** (builds 7m04s, rollouts 2m07s) — slower than the 5m49s
warm figure, as expected for a wave that changes both halves.

| Service | Revision | Image digest |
|---|---|---|
| `kms-staging-api` | `00115-gv6` → **`00116-7b4`** | `sha256:c76d440a…` → **`sha256:b2f0f6e2…`** |
| `kms-staging-web` | `00107-6l2` → **`00108-265`** | `sha256:f88fa494…` → **`sha256:ac6b9e88…`** |
| `kms-staging-worker` | `00098-9zc` → **`00099-sk2`** | takes the api's new image |

**Proved by behaviour, not by the exit code**, which has lied before. The served bundle was probed
for strings only this wave contains — and, more usefully, for one that must have *gone*:

| Task | Found in the deployed bundle |
|---|---|
| T-041 | `Fixed when the temple was created`, `What can be changed` |
| T-042 | `Find the coordinates from the address`, `Use these coordinates`, `No coordinates came back for that address` |
| T-043 | `planDate:n.planDate,mealKind:n.mealKind,eventName:n.eventName` — **the defect itself, fixed, in the minified payload builder** |
| T-045 | `Ekadashi-prohibited (rice, wheat, dal, chickpeas`, `ekadashi-flag` |
| T-040 | `changeUserRole` and the `/users/{id}/role` path are **absent** — the negative half, and the stronger one |

One thing the probe turned up that would otherwise read as a regression: `Asia/Kolkata` is still in
the chunk set the edit screen loads. It is **not** a surviving picker. It is in a shared chunk, in
`lib/api.ts`'s `kms.templeZone` localStorage fallback — the one sanctioned zone literal, which
`design-system.test.ts`'s comment now explains and deliberately still exempts. The edit page's own
chunk does not contain it.

The api half has no equivalent unauthenticated probe: `AuthenticationFilter` answers 401 for a route
that exists and a route that never did, so `/api/v1/geocode` and the deleted `/users/{id}/role` are
indistinguishable from outside. Its evidence is the moved digest, the two migrations applied at
rollout, and `/actuator/health` answering `{"status":"UP"}` on the new revision.

### T-048's row count: **zero**

This is the figure that was owed, and applying the migration is how it was obtained. V97's own notice,
from the rollout logs:

```
DB: V97: unpinned 0 delivery event(s) that were sitting at 0,0; each keeps its
    delivery_place_id and is re-resolved from Places on next read.
```

**Zero is a real answer and not a failed run.** The poisoning needed somebody to *edit* an
already-placed delivery event since 2026-09-05, and on staging nobody had. So the damage was latent
rather than realised: the mechanism was live and reachable — `MealPlanIT` reproduces it on demand —
and no row had yet been written through it. T-048's own proof says the migration is correct whatever
the number is, including zero, because it matches on a value rather than on a date or an id and is
idempotent. It is worth having the answer rather than assuming it either way.

**A small thing T-048 flagged as unverified is now verified:** it did not know whether Flyway would
surface a `RAISE NOTICE` at all, and said so rather than claiming it. It does —
`DefaultSqlScriptExecutor` logs client notices with a `DB:` prefix. The count is recoverable from a
deploy log for any future backfill written the same way.

### What is inert, deliberately

**T-042 is dark as deployed.** `GEOCODING_PROVIDER` is unset on staging, so the endpoint answers
`found: false` every time and `/tenants/new` reads as it did before, minus one button. Rajeev is
setting that variable himself after this release, so the change is separable from it and he can watch
what OpenStreetMap actually returns for a real temple address. **Nothing about T-042 has been or can
be seen working until he does.** Setting it also lights up the devotee temple-distance search and the
delivery-address geocode behind the travel estimate; both were built for it and both fail soft.

### Nothing here has been certified by observation

Two screens want a human first, and they are the two that matter most:

- **`/planner/catch-up` as Temple Admin** — press *Record this meal* on an event, and confirm both
  that it records and that a refusal appears **in the form** rather than at the top of the page. This
  is the defect that blocked two items of Rajeev's own review list, so verifying it unblocks T1 and
  P8 as well as T-043.
- **A delivery event picked from the map** — reopen it, change only the head count, save, reopen, and
  confirm the leave-by line still reads the same drive.

Then, in descending order of how likely they are to be wrong: `/tenants/[id]/edit`'s new
*"Fixed when the temple was created"* block; `/ingredients` and `/ingredients/new` for the Ekadashi
flag; and the audit log for *"Role changed"*, *"Role change refused"* and
*"Tried to end their own employment"*. The meal-kind work (T-038, T-047) **cannot** be seen by hand
yet: its settings screen is T-005 and does not exist.
