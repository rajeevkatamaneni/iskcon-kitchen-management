# Dispatch ledger

Read `docs/work/README.md` first — it explains what this file is and who is allowed to write to it.
Read `docs/work/INTAKE.md` second — it is the verification behind every row here, and it is where the
docket items that are *not* build tasks went.

**Status: wave 0 SHIPPED; waves 1-6 PLANNED, NOT DISPATCHED. Nothing else is in flight; no
reservation has been written into any shared file; `tools/work-lock.sh status` should report nothing
held.**

**Wave 0 — T-000, the six-digit error-code renumber.** Ruled by Rajeev on 2026-09-07
(`DECISIONS.md` D-6), dispatched alone and first so that nothing else in this batch would be written
against a numbering scheme it was about to be renumbered out of. Proof in `proof/T-000.md`; the full
old→new table is `docs/ERROR-CODE-RENUMBER-2026-09-07.md`. **Committed and deployed to staging.**
Every code is now six digits — client `KMS-400001`–`KMS-400123`, server `KMS-500001`–`KMS-500005` —
allocated flat in declaration order, and the highest client number in use is `KMS-400123`.

> ### ⚠️ Every four-digit `KMS-` number below is old-scheme. None of them is usable as written.
>
> This file was planned before wave 0 and has not been re-planned. Two different kinds of stale
> number are in it and they need opposite treatment, which is why there has been no mechanical sweep:
>
> - **Codes that exist** — `KMS-4103`, `KMS-4102`, `KMS-4104`, `KMS-4962`, `KMS-4003`, `KMS-4001`,
>   `KMS-4935` — each has an exact successor in `docs/ERROR-CODE-RENUMBER-2026-09-07.md`. Look it up;
>   do not guess from the digits, because the new numbers follow declaration order and not the old
>   ones.
> - **Codes that were only ever proposed** — `KMS-4995`–`4999` and `KMS-4018`–`4021`, the nine in the
>   reservations table at the foot of this file — **have no successor and never will.** They were
>   picked to fit a conflict band that no longer exists. They must be re-allocated from `KMS-400124`
>   onward when their wave is dispatched, by the work manager, in one pass — not read off this page.
>
> **Question 12 is closed.** It asked Rajeev to approve spilling out of the 4900s band. There is no
> band to spill out of.

Batch: **the UAT Docket of 2026-09-06** (`docs/work/intake/2026-09-06-uat-docket.txt`).
21 tasks, 6 waves. Every task below is `state: queued` and stays that way until Rajeev authorises a
wave. Reservations are **proposed**; the work manager writes them into the shared files in one pass
immediately before the wave it belongs to, and not before.

**Path contracts exclude the reserved files by construction.** No builder in this batch may open
`ErrorCode.java`, `RolePermissions.java`, `frontend/lib/api.ts`, `frontend/lib/nav.ts`,
`frontend/lib/routes.ts`, `frontend/components/Sidebar.tsx`, `docs/CHANGELOG.md` or
`docs/WORK_QUEUE.md`. Where a task needs something in one of those, it is listed under
**reservations** and will already be there.

---

# Wave 1 — three screens and one filter, no migrations, no new codes

The cheapest real value in the batch. T-001 is the docket's own first priority; T-002 is its third;
T-003 costs nothing in reservations because all four error codes already exist and are merely never
thrown.

### T-001 — Correcting a stock movement, and stopping tracking an item

- **source:** docket **M1** + **M8** (INTAKE M1, M8). `OUTSTANDING_BUILD_LIST` I2 is adjacent but not
  closed by this.
- **wave:** 1
- **state:** queued
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
- **proof:** —
- **shipped:** —

### T-002 — The five controls that refuse the person looking at them

- **source:** docket **C1–C5**, which are also the docket's own S4c, S4d and S4e (INTAKE C1–C5, S4).
  The docket's third priority: *"An hour's work, and each otherwise arrives as a bug report from every
  tester holding that role."*
- **wave:** 1
- **state:** queued
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
- **reservations:**
  - `frontend/lib/nav.ts` — **C5**: add `ADMIN` to the `/donate` row (line 68), so it matches the roles
    `donate/page.tsx:13` actually admits, per nav.ts's own stated rule.
  - `frontend/lib/nav.ts` — **C3**: remove `MANAGER` and `KITCHEN` from the `/my-shifts` row (line 63).
- **acceptance:**
  - Rendered as `KITCHEN_STAFF`, the ingredient-request page shows neither Download nor Print and
    fires no request to `/api/v1/work-orders/languages`.
  - Rendered as `KITCHEN_STAFF`, the "Working today" tile is present and is not a link.
  - The `/my-shifts` empty state contains no instruction to browse shifts when the role cannot.
  - The staff-schedule empty state contains no link to `/staff`.
  - One test per fix, each asserting the role-conditional behaviour rather than the markup.
- **proof:** —
- **shipped:** —

### T-003 — The authentication filter says why it turned someone away

- **source:** docket **D1, D2, D3** (INTAKE D1–D3).
- **wave:** 1
- **state:** queued
- **what:** Three carefully written error codes — `ACCOUNT_DISABLED` (KMS-4103), `SESSION_EXPIRED`
  (KMS-4102), `NO_ACCOUNT_AT_TEMPLE` (KMS-4104) — are declared with good copy and thrown nowhere,
  because `AuthenticationFilter` drops silently in all three cases and the request falls through to a
  bare bodyless 401. **The real work is the mechanism, and it does not exist yet:**
  `GlobalExceptionHandler` is a `@RestControllerAdvice` and never sees anything thrown before
  `DispatcherServlet`, and the one precedent for writing from outside the dispatcher
  (`LoggingAccessDeniedHandler`) hand-rolls a non-standard body. Build it once — serialise the real
  `ErrorResponse` record to the response from within the filter, with the right status and content
  type — then apply it at all three sites. Then fix the other half: the frontend currently infers
  "no account at this temple" from *any* non-unreachable `/whoami` failure, which means a disabled
  account and an expired session are today both misreported as "you have no account here"; switch it
  to read the code the backend now sends. **`SESSION_EXPIRED` is conditional on Question 8** — the
  token verifier deliberately discards the reason a token failed, on a documented security argument,
  so build D1 and D3 unconditionally and only carve out the expired case if Rajeev has said yes; if he
  has not, leave `FirebaseTokenVerifier` and `TokenVerifier` untouched and say so in the proof.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/auth/AuthenticationFilter.java`
  - `backend/src/main/java/org/iskcon/kms/auth/SecurityConfiguration.java`
  - `backend/src/main/java/org/iskcon/kms/auth/FirebaseTokenVerifier.java` *(only if Q8 is yes)*
  - `backend/src/main/java/org/iskcon/kms/auth/TokenVerifier.java` *(only if Q8 is yes)*
  - `frontend/lib/auth-context.tsx`
  - `backend/src/test/java/org/iskcon/kms/auth/AuthenticationFailureIT.java` *(new)*
  - `frontend/__tests__/session-failures.test.tsx` *(exists — extend)*
- **reservations:** none. All three codes are already in `ErrorCode.java` at 4102, 4103, 4104 with
  their final text. This task adds no code and changes no text.
- **acceptance:**
  - A request from a disabled user returns 401 with a JSON body carrying `KMS-4103` and its existing
    next step, verified by an integration test, not a unit mock.
  - A verified Firebase user with no membership returns `KMS-4104`.
  - The frontend distinguishes the three states from the code rather than inferring from the absence
    of a network error, with a test for each.
  - The proof states plainly whether the `SESSION_EXPIRED` half was built or held for Question 8.
- **proof:** —
- **shipped:** —

---

# Wave 2 — four screens over finished backends

Every task here is a screen over an endpoint that already exists and is already tested. No migrations.
The only shared cost is `frontend/lib/api.ts`, which the work manager stubs in one pass beforehand.

### T-004 — A screen that manages festival occasions

- **source:** docket **S3** (INTAKE S3) · `WORK_QUEUE` item 3.3 · `TRACEABILITY` G3.
- **wave:** 2
- **state:** queued
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
- **proof:** —
- **shipped:** —

### T-006 — A cook can see their own schedule

- **source:** docket **S4** (INTAKE S4a) · `WORK_QUEUE` item 3.4 · `TRACEABILITY` G6.
- **wave:** 2
- **state:** queued
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
- **proof:** —
- **shipped:** —

### T-009 — Editing an equipment record, and confirming a scrapping

- **source:** docket **M7** (INTAKE M7).
- **wave:** 2
- **state:** queued
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
- **proof:** —
- **shipped:** —

### T-017 — A donor can see and stop a recurring gift

- **source:** docket **B9** (INTAKE B9).
- **wave:** 2
- **state:** queued
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
- **proof:** —
- **shipped:** —

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

# Wave 4 — money that was entered wrongly

Three separate backend packages, three migrations. These are the items where "a mistake is permanent"
costs a temple actual money: ₹45,000 keyed for ₹4,500, a bounced cheque, a gift entered twice.

### T-010 — A vendor invoice can be voided or credited, and a payment reversed

- **source:** docket **M4 + M5** (INTAKE M4, M5). Combined into one task: same package, one migration,
  and voiding a payment must recompute the invoice status the other half owns.
- **wave:** 4
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
  - `backend/src/main/resources/db/migration/V95__invoice_void_and_payment_reversal.sql` *(new)*
  - `frontend/app/invoices/[id]/page.tsx`
  - `frontend/app/invoices/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/invoice/InvoiceCorrectionIT.java` *(new)*
  - `frontend/__tests__/invoice-void.test.tsx` *(new)*
- **reservations:**
  - migration: **`V95`**.
  - error codes: `INVOICE_ALREADY_VOIDED` **KMS-4995** (409) — *"This invoice has already been voided."*
    / *"Look at the credit note recorded against it."*; `PAYMENT_ALREADY_VOIDED` **KMS-4996** (409) —
    *"This payment has already been struck."* / *"Record a new payment if one was actually made."*
  - permissions: none new — `MANAGE_VENDOR_PAYMENTS` (Temple Admin only) already fits.
  - `frontend/lib/api.ts`: `voidInvoice(id, reason, token)`, `creditInvoice(id, input, token)`,
    `voidInvoicePayment(invoiceId, paymentId, reason, token)` — exact signatures written at
    reservation time in the file's own style.
- **acceptance:**
  - Voiding a payment that took an invoice to `PAID` returns the invoice to `PENDING`, proven by an
    integration test on the real database.
  - A voided payment row still exists and is marked, not deleted.
  - A second void of the same payment returns KMS-4996.
  - The disputed/voided state is visible on the invoice screen, not only in the API.
- **proof:** —
- **shipped:** —

### T-012 — Voiding a hand-recorded donation

- **source:** docket **M6** (INTAKE M6).
- **wave:** 4
- **state:** queued
- **what:** `DonationController` is POST-only for hand-recorded gifts and the ledger controller is
  read-only throughout, so a gift entered twice or against the wrong donor permanently inflates the
  80G-relevant ledger — and if it was in kind it also inflated stock, in the same transaction, which
  cannot be reversed either. Build the void: mark the donation, and issue the compensating stock
  movement for the in-kind half **in one transaction**, so the two can never drift apart. The stock
  half can lean on the existing compensating-entry primitive rather than inventing a second mechanism.
  A voided donation must disappear from the 80G figures and stay visible in the ledger, marked — the
  same shape as every other correction in this batch. **Who may do this is Question 6:** recording a
  donation runs on `MANAGE_INVENTORY`, which kitchen staff hold, and that is far too wide for a control
  that moves the statutory ledger.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/donation/DonationController.java`
  - `backend/src/main/java/org/iskcon/kms/donation/DonationVoidService.java` *(new)*
  - `backend/src/main/java/org/iskcon/kms/donation/**` *(DTOs and the recorder, as needed)*
  - `backend/src/main/resources/db/migration/V96__donation_void.sql` *(new)*
  - `frontend/app/donations/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/donation/DonationVoidIT.java` *(new)*
  - `frontend/__tests__/donation-void.test.tsx` *(new)*
- **reservations:**
  - migration: **`V96`**.
  - error codes: `DONATION_ALREADY_VOIDED` **KMS-4997** (409) — *"This donation has already been
    voided."* / *"Record it again if it was actually received."*
  - permissions: **new constant `CORRECT_DONATIONS`, granted to `TEMPLE_ADMIN` only** — pending
    Question 6.
  - `frontend/lib/api.ts`: `voidDonation(id, reason, token)`.
- **acceptance:**
  - Voiding an in-kind donation reverses its stock movement and marks the donation, in one transaction
    — an integration test asserts that a failure in either half rolls back both.
  - The voided gift is excluded from the 80G period summary and still present in the ledger, marked.
  - A second void returns KMS-4997.
- **proof:** —
- **shipped:** —

### T-014 — Reinstating someone whose employment was ended

- **source:** docket **M9** (INTAKE M9), reinstatement half only. The second half of that docket item —
  *"the role change that would undo the demotion has no screen either"* — is **wrong** and is not in
  this task; that screen exists and works.
- **wave:** 4
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
  - `backend/src/main/resources/db/migration/V97__staff_reinstatement.sql` *(new, only if a column is needed)*
  - `frontend/app/staff/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/staff/StaffReinstatementIT.java` *(new)*
  - `frontend/__tests__/staff-reinstate.test.tsx` *(new)*
- **reservations:**
  - migration: **`V97`** — allocated conditionally. The existing columns may be enough to clear; if the
    builder finds it needs none, it leaves `V97` unused and says so in the proof. A gap in the sequence
    is harmless; a second builder taking the same number is not.
  - error codes: `EMPLOYMENT_NOT_ENDED` **KMS-4998** (409) — *"This person is still employed."* /
    *"There is nothing to reinstate."*
  - permissions: none new — `MANAGE_STAFF` (Temple Admin only).
  - `frontend/lib/api.ts`: `reinstateStaff(id, input, token)`.
- **acceptance:** a reinstated person is editable again and can sign in if they could before;
  reinstating someone still employed returns KMS-4998; reinstating someone marked ineligible for rehire
  is refused; the act is audited.
- **proof:** —
- **shipped:** —

---

# Wave 5 — the recorded meal, and two half-workflows

### T-007 — Correcting a recorded meal

- **source:** docket **S5** and **M2** (INTAKE S5, M2) · `OUTSTANDING_BUILD_LIST` **P8** ("Still
  outstanding: reopening a recorded meal to correct it") · `WORK_QUEUE` item 3.5.
- **wave:** 5
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
  **One consequence to carry:** `MEAL_ALREADY_RECORDED` (KMS-4962) currently tells the reader *"What
  was cooked can't be changed afterwards. Ask a Temple Admin if the figures are wrong."* That sentence
  becomes false the day this ships, and its rewrite is a reservation, not the builder's to make.
- **paths:**
  - `backend/src/main/java/org/iskcon/kms/meal/MealServiceController.java`
  - `backend/src/main/java/org/iskcon/kms/meal/ServedMealService.java`
  - `backend/src/main/java/org/iskcon/kms/meal/CorrectMealRequest.java` *(new)*
  - `backend/src/main/java/org/iskcon/kms/inventory/StockMovementController.java`
  - `backend/src/main/java/org/iskcon/kms/inventory/StockMovementService.java`
  - `backend/src/main/java/org/iskcon/kms/inventory/InventoryConsumptionService.java`
  - `backend/src/main/resources/db/migration/V98__meal_correction.sql` *(new)*
  - `frontend/app/planner/[date]/[kind]/page.tsx`
  - `frontend/components/planner/MealServices.tsx`
  - `backend/src/test/java/org/iskcon/kms/meal/MealCorrectionIT.java` *(new)*
  - `frontend/__tests__/meal-correction.test.tsx` *(new)*
- **reservations:**
  - migration: **`V98`** — to carry what the original figures were, so the screen can say "corrected
    from 400" without reading it out of the ledger.
  - error codes: `MEAL_ALREADY_CORRECTED` **KMS-4999** (409) — *"This meal has already been
    corrected."* / *"Look at the correction that was recorded against it."*
  - **text change, work manager's to make:** `MEAL_ALREADY_RECORDED` (KMS-4962) keeps its number and
    its first sentence; its next step becomes *"Record a correction if the figures are wrong."*
  - permissions: **new constant `CORRECT_RECORDED_MEAL`, granted to `TEMPLE_ADMIN` only** — pending
    Question 5.
  - `frontend/lib/api.ts`: `correctRecordedMeal(id, input, token)`, `movementsForMeal(mealPlanId, token)`.
- **acceptance:**
  - An integration test records a meal at 400, corrects it to 640, and asserts: consumption movements
    net to the 640 figure; the original recording is still readable; and cost-per-serving recomputes
    from the corrected number.
  - Correcting twice returns KMS-4999.
  - The screen shows "640, corrected from 400 by <name> on <date>".
  - A rollback test proves the stock half and the meal half cannot commit separately.
- **proof:** —
- **shipped:** —

### T-015 — Retrying a message to the addresses it failed for

- **source:** docket **B6** (INTAKE B6).
- **wave:** 5
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
  - error codes: `NOTHING_FAILED_TO_RETRY` **KMS-4018** (409) — *"Every copy of this message was
    delivered."* / *"There is nothing to send again."* **Note the band overflow** — see the
    reservations summary and Question 12.
  - permissions: none new — `MANAGE_COMMUNICATIONS`.
  - `frontend/lib/api.ts`: `retryFailedDeliveries(id, token)`.
- **acceptance:** a retry re-queues only the failed recipients, proven by an integration test that
  asserts the succeeded ones are untouched; retrying a fully-delivered message returns KMS-4018; the
  message body cannot be edited by this path.
- **proof:** —
- **shipped:** —

### T-016 — Volunteer attendance, and striking someone off a roster

- **source:** docket **B7** (INTAKE B7).
- **wave:** 5
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
  - `backend/src/main/resources/db/migration/V99__shift_attendance.sql` *(new)*
  - `frontend/app/shifts/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/shift/ShiftAttendanceIT.java` *(new)*
  - `frontend/__tests__/shift-attendance.test.tsx` *(new)*
- **reservations:**
  - migration: **`V99`** — the attendance column on `shift_signups`. The table is tenant-owned, so the
    migration must respect the existing RLS on it and backfill per tenant, never across all rows.
  - error codes: `ATTENDANCE_ALREADY_RECORDED` **KMS-4019** (409) — *"Attendance for this shift has
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

# Wave 6 — the last four

### T-013 — Returning goods to a vendor after they were accepted

- **source:** docket **M3** (INTAKE M3).
- **wave:** 6
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
  - `backend/src/main/resources/db/migration/V100__return_to_vendor.sql` *(new)*
  - `frontend/app/orders/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/receiving/ReturnToVendorIT.java` *(new)*
  - `frontend/__tests__/goods-return.test.tsx` *(new)*
- **reservations:**
  - migration: **`V100`**.
  - error codes: `RETURN_EXCEEDS_RECEIVED` **KMS-4020** (400) — *"You can't return more than was
    received."* / *"Check the quantity against the goods receipt."*; `ALREADY_RETURNED` **KMS-4021**
    (409) — *"These goods have already been returned."* / *"Look at the return recorded against this
    receipt."*
  - permissions: none new — `MANAGE_INVENTORY`.
  - `frontend/lib/api.ts`: `returnReceivedGoods(receiptId, input, token)`.
- **acceptance:** a return reduces on-hand by exactly the returned quantity through a new movement;
  over-returning gives KMS-4020; the goods receipt itself is never mutated; the `CHECK` constraint and
  the Java enum agree, proven by an integration test that inserts the new type.
- **proof:** —
- **shipped:** —

### T-019 — Raising a shift from the planner, and seeing it there afterwards

- **source:** docket **S6 + S7** (INTAKE S6, S7) · `OUTSTANDING_BUILD_LIST` **P6** and **P7** ·
  `WORK_QUEUE` item 3.6. One task because they are one piece of work in one set of files.
- **wave:** 6
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
  time-window match unless Rajeev has chosen the explicit link; if he has, `V101` is reserved for it.
- **paths:**
  - `frontend/components/planner/MealServices.tsx`
  - `frontend/app/planner/[date]/[kind]/page.tsx`
  - `frontend/components/planner/ShiftLayer.tsx` *(new)*
  - `frontend/__tests__/planner-shift.test.tsx` *(new)*
  - `backend/src/main/resources/db/migration/V101__meal_shift_link.sql` *(new — **only** if Q9 chooses the explicit link)*
- **reservations:**
  - migration: **`V101`**, conditional on Question 9. Unused otherwise.
  - `frontend/lib/api.ts`: a shifts-for-a-date-range wrapper if one is not already present; `createShift`
    (`:4536`) and `shiftRoster` (`:4533`) already exist.
- **acceptance:** posting from the planner creates the shift and returns the reader to the same day and
  meal; the shift then appears beside the crew count with its sign-up figure; opening and closing the
  layer without saving changes nothing.
- **proof:** —
- **shipped:** —

### T-020 — A donation receipt a donor can be given or sent again

- **source:** docket **B5** (INTAKE B5).
- **wave:** 6
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
  - `backend/src/main/resources/db/migration/V102__donation_receipt_document.sql` *(new)*
  - `frontend/app/donations/[id]/page.tsx`
  - `backend/src/test/java/org/iskcon/kms/document/DonationReceiptIT.java` *(new)*
  - `frontend/__tests__/donation-receipt.test.tsx` *(new)*
- **reservations:**
  - migration: **`V102`** — the `donation_id` column and the widened `kind` CHECK.
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
- **wave:** 6
- **state:** queued
- **what:** `INVALID_PHONE_NUMBER` (KMS-4003) would say *"Include the country code, for example
  +91 98765 43210."* It is never thrown, and today it **structurally cannot be**: every phone check is a
  Bean Validation `@Pattern` on a DTO field, and the validation handler stamps `VALIDATION_FAILED`
  (KMS-4001) on every such failure regardless of which field failed — so in an application where the
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
- **reservations:** none — KMS-4003 already exists with its final text. No migration.
- **acceptance:** posting a malformed phone to each of the listed endpoints returns KMS-4003, not
  KMS-4001; a request failing on a phone number *and* another field still returns KMS-4001 with both in
  the field errors; `ErrorCodeTest` still passes.
- **proof:** —
- **shipped:** —

---

## Waves

| Wave | Tasks | Concurrent? | Why not, if not |
|---|---|---|---|
| 1 | T-001, T-002, T-003 | yes, 3 builders | Path sets disjoint: one inventory screen, four unrelated role-gated pages, the auth package plus one frontend lib file. No migrations, no new error codes, no permission changes — the whole wave's reservations are two lines in `nav.ts`. |
| 2 | T-004, T-006, T-009, T-017 | yes, 4 builders | Four new routes under four different directories, each over a backend that is already finished and tested. The only shared cost is `api.ts`, stubbed by the work manager beforehand. |
| 3 | T-005, T-008, T-018, T-022 | yes, 4 builders | **T-005 is held out of wave 2 on purpose.** It and T-004 are both settings-area screens, and I am not certain neither reaches into `frontend/app/settings/page.tsx` for a link — doubt means serialise. T-022 writes only into `frontend/__tests__/` under five named filenames, so the directory is never reserved as a whole. |
| 4 | T-010, T-012, T-014 | yes, 3 builders | Three separate backend packages — invoice, donation, staff — and three separate migrations, `V95`/`V96`/`V97`, allocated by the work manager because Flyway would not notice the collision until it refused to boot. Three, not four, because each carries a migration and the verify lock is the bottleneck. |
| 5 | T-007, T-015, T-016 | yes, 3 builders | T-007 reaches into the inventory package as well as the meal package, which is why nothing else touching inventory runs beside it. **T-019 is held back from this wave** even though it looks disjoint: if Question 9 goes to the explicit meal↔shift link, it lands in the shift package that T-016 is migrating. Dependencies beat parallelism. |
| 6 | T-013, T-019, T-020, T-021 | yes, 4 builders | T-013 takes the inventory package only after T-007 has left it. T-019 takes the planner only after T-007's meal-correction screen has left it. T-020 takes donations only after T-012 has left them. Each of these is a deliberate serialisation across waves rather than a bet that two builders would miss each other. |

**Not in any wave, and not to be added to one without Rajeev:** the five blocked items (S2, B1, B2,
B3, B4) and the seven environment/credential/human-verification items (P1–P6, P8). They are recorded in
`docs/work/INTAKE.md`, with the specific decision each is waiting on.

---

## Proposed reservations, in one place

Nothing below is written yet. Each block goes into the shared files in a single pass immediately before
its wave is authorised.

**Migrations** — the tree is at `V94`, so:

| Version | Task | For |
|---|---|---|
| `V95` | T-010 | Invoice void/credit states, payment reversal marks |
| `V96` | T-012 | Donation void |
| `V97` | T-014 | Staff reinstatement — **conditional**, may go unused |
| `V98` | T-007 | The figures a meal was corrected from |
| `V99` | T-016 | Attendance on `shift_signups` (tenant-owned: RLS-respecting, per-tenant backfill) |
| `V100` | T-013 | The return-to-vendor movement type and its `CHECK` |
| `V101` | T-019 | Meal↔shift link — **conditional on Question 9**, may go unused |
| `V102` | T-020 | The donation-receipt document kind and its `donation_id` |

**Error codes — SUPERSEDED by wave 0. The nine numbers in the table below are dead; read them as
row labels, not as codes.** They are re-allocated from **`KMS-400124`** onward at dispatch time, in
the order the work manager appends them to `ErrorCode.java`. Everything else in the table stands: the
constant names, the HTTP statuses, and the copy have all been written and reviewed, and a 400-family
number must still land on a 4xx status and a 500-family one on a 5xx.

*What it used to say, kept because it is why the numbers look the way they do:* the 4900s conflict
band had five numbers left (highest in use was 4994) and this batch needed eight.
`ErrorCode.java:105-112` set the precedent for what to do — *"the band is a convention; the
permanence of a number is the rule, and where they disagree the rule wins"* — so the first five took
4995–4999 and the rest continued at 4018 (4017 being retired and not reused). That needed Rajeev's
nod, as Question 12. He answered a larger question instead.

| Code | Task | Text / next step |
|---|---|---|
| `INVOICE_ALREADY_VOIDED` **4995** (409) | T-010 | "This invoice has already been voided." / "Look at the credit note recorded against it." |
| `PAYMENT_ALREADY_VOIDED` **4996** (409) | T-010 | "This payment has already been struck." / "Record a new payment if one was actually made." |
| `DONATION_ALREADY_VOIDED` **4997** (409) | T-012 | "This donation has already been voided." / "Record it again if it was actually received." |
| `EMPLOYMENT_NOT_ENDED` **4998** (409) | T-014 | "This person is still employed." / "There is nothing to reinstate." |
| `MEAL_ALREADY_CORRECTED` **4999** (409) | T-007 | "This meal has already been corrected." / "Look at the correction that was recorded against it." |
| `NOTHING_FAILED_TO_RETRY` **4018** (409) | T-015 | "Every copy of this message was delivered." / "There is nothing to send again." |
| `ATTENDANCE_ALREADY_RECORDED` **4019** (409) | T-016 | "Attendance for this shift has already been recorded." / "Change it on the shift's roster." |
| `RETURN_EXCEEDS_RECEIVED` **4020** (400) | T-013 | "You can't return more than was received." / "Check the quantity against the goods receipt." |
| `ALREADY_RETURNED` **4021** (409) | T-013 | "These goods have already been returned." / "Look at the return recorded against this receipt." |

Every one satisfies `ErrorCodeTest`: unique, no jargon, a non-blank next step, both sentences ending in
a full stop, `KMS-\d{6}`, and `number/100000 == httpStatus/100`.

**One text change to an existing code**, T-007: `MEAL_ALREADY_RECORDED` **KMS-4962** keeps its number
and its first sentence. Its next step becomes *"Record a correction if the figures are wrong."*, because
the current one — *"What was cooked can't be changed afterwards"* — becomes untrue the day T-007 ships.

**Permissions.** Two new constants, both pending a decision:

| Constant | Task | Grant | Question |
|---|---|---|---|
| `CORRECT_RECORDED_MEAL` | T-007 | `TEMPLE_ADMIN` only | 5 |
| `CORRECT_DONATIONS` | T-012 | `TEMPLE_ADMIN` only | 6 |

Everything else in the batch reuses an existing permission. Note `RECORD_DONATIONS` does not exist —
recording a gift runs on `MANAGE_INVENTORY`, which is precisely why Question 6 needs answering.

**`frontend/lib/api.ts`** — wrappers to stub, by wave: W2 `createOccasion`, `updateOccasion`,
`deleteOccasion`, `updateEquipment`; W3 `deleteMealKind`, `updateTenant`; W4 `voidInvoice`,
`creditInvoice`, `voidInvoicePayment`, `voidDonation`, `reinstateStaff`; W5 `correctRecordedMeal`,
`movementsForMeal`, `retryFailedDeliveries`, `recordShiftAttendance`, `releaseVolunteerFromShift`;
W6 `returnReceivedGoods`, `generateDonationReceipt`, `resendDonationReceipt`, and a shifts-for-range
wrapper if none exists.

**`frontend/lib/nav.ts`** — five edits, all in wave 1–3: `/donate` gains `ADMIN`; `/my-shifts` loses
`MANAGER` and `KITCHEN`; and three new rows, `/settings/occasions`, `/settings/meal-kinds` and
`/my-schedule`. `routes.ts` and `Sidebar.tsx` are untouched by this batch.
