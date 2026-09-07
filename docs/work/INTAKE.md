# Intake register — the UAT Docket of 2026-09-06

**What this is.** The docket says, in its own words: *"The documents were wrong a third of the time;
nothing found by reading the code has been wrong yet… Before anything on this page is scheduled, it
is worth ten minutes against the code."* This file is that ten minutes, done once and written down
so nobody repeats it.

Every item the docket raises has a stable id, the docket's claim, **what the code actually shows at a
file:line somebody opened**, and a verdict. `docs/work/DISPATCH.md` schedules only the `build` rows.

**Verified 2026-09-06 against `ff1f3ef`.** 47 items. Where the docket and the code disagree, the code
wins and the row says so.

**Re-verified in part on 2026-09-07**, after Rajeev settled `DECISIONS.md` D-1 to D-7. Three of the
five blocked items are now scheduled and the counts below have moved with them:

| Verdict | Count | Change on 2026-09-07 |
|---|---|---|
| `build` — collapses into **26 tasks**, because several items share one screen or one endpoint | 35 | +3: B1, B2 and B3 unblocked by D-1 |
| `already built` / `claim wrong` outright | 3 | — |
| `blocked` — needs a decision from Rajeev before anyone starts | 2 | −3: only S2 and B4 remain |
| `not-a-build-task` — environment, credentials, human verification | 7 | — |

**B2 was "the big one" and it got bigger, then tractable.** Verified against the code on 2026-09-07:
a nullable `ingredient_id` reaches five consumers outside the purchase-order package, and the worst
of them fails *silently* — `PurchaseOrderService.java:69-75` inner-joins `ingredients`, so a
description-only line would vanish from the PO detail screen with no error at all. What made it a
one-wave task after all is D-1's own rule that a durable never lands in stock: `goods_receipt_lines`
and `stock_movements` both keep `ingredient_id NOT NULL`, and a described line is orderable and
payable but never receivable. See DISPATCH **T-024**.

Ten further items were **mis-sized rather than wrong**, and each is corrected in its own row: S5 is
bigger than recorded (backend, not a screen); S4b, M9's second half, B10's named screen, C3's "link"
and C4's own doc comment are wrong in detail; A3 and B1 are cheaper than recorded; P4's test count and
P9's "only screen" are both overstated.

**The single most important correction is not in any row below — it is the docket's own
recommendation.** See "The headline recommendation does not survive the code" at the foot of this
file.

---

## Lane 1 — Still to build (the docket's seven)

### S1 — The broadcast limit has no screen · **already built**
- **Claim:** `KMS-4935` tells a poster to ask a Temple Admin to raise the limit and no screen can;
  `getSettings` and `setBroadcastLimit` both exist and neither is called.
- **Code:** the screen exists. `frontend/app/settings/page.tsx:70` holds the state, `:146-151` mounts
  `VolunteerMessagesSection`, `:1254` carries a doc comment saying in as many words that it was built
  to answer `KMS-4935`, and its `save()` calls `api.setBroadcastLimit` (`frontend/lib/api.ts:4593` →
  `PUT /api/v1/settings/volunteer-broadcast-limit`, `backend/…/shift/SettingsController.java:94`).
  Shipped in `5f3d177`.
- **Verdict:** already built. `docs/WORK_QUEUE.md` is right; the docket is stale on its own "Next"
  item. **Do not schedule.**

### S2 — No operator view of the audit log · **blocked**
- **Claim:** `/audit` exists for Temple Admins; only the operator's per-temple drill-in is missing;
  `drillIntoTenantAudit` has 0 callers.
- **Code:** confirmed exactly. Both doors exist —
  `backend/…/audit/AuditController.java:39` (`GET /api/v1/audit-events`, `VIEW_AUDIT_LOG`,
  Temple Admin) and `:56` (`GET /api/v1/tenants/{tenantId}/audit-events`,
  `VIEW_PLATFORM_OPERATIONS`, super admin). `frontend/lib/api.ts:3165` wraps the second with **zero**
  callers. `frontend/app/tenants/[id]/page.tsx` has no audit reference at all.
- **Verdict:** blocked, not by code but by instruction. `docs/WORK_QUEUE.md` item 3.2 records
  **"Rajeev asked to see what is there before anything is built."** Screen-only when released.

### S3 — No screen manages festival occasions · **build**
- **Claim:** `OccasionController.java:55,65,76` is full CRUD behind `MANAGE_TEMPLE_SETTINGS`; the app
  calls only the list.
- **Code:** confirmed at those exact lines — `POST :55`, `PUT :65`, `DELETE :76`, all
  `MANAGE_TEMPLE_SETTINGS`; `GET :39` and `GET /resolved :46` behind `MANAGE_MEAL_PLANS`. The client
  is thinner than the docket implies: only `listOccasions` (`frontend/lib/api.ts:3566`) and
  `resolvedOccasions` (`:3569`) exist — **the create/update/delete wrappers were never written**, not
  merely uncalled. `listOccasions`' only caller is
  `frontend/components/planner/MealComposer.tsx:363`, an autocomplete.
- **Verdict:** build → **T-004**.

### S4a — Kitchen staff cannot see their own schedule · **build**
- **Code:** `backend/…/staff/StaffScheduleController.java:204` serves `GET /api/v1/staff/schedule/me`
  behind `VIEW_OWN_SHIFTS`; `RolePermissions.java:96,118` confirm `KITCHEN_MANAGER` and
  `KITCHEN_STAFF` both hold it; `frontend/lib/api.ts:4456` wraps it as `myStaffSchedule` with **zero**
  callers. Only the screen is missing — `TRACEABILITY.md` G6's recorded cause was wrong in both
  halves, exactly as `WORK_QUEUE.md` already notes.
- **Verdict:** build → **T-006**.

### S4b — Kitchen staff hold `REQUEST_OWN_LEAVE` and have no route to it · **claim wrong**
- **Code:** they have a route. `frontend/app/profile/page.tsx:45` admits `KITCHEN_STAFF` and its
  `MyLeave()` component calls `api.myLeave` / `api.requestLeave`; `/profile` is reachable from the
  persistent account menu at `frontend/components/Sidebar.tsx:330-338`. What is true is narrower:
  there is no *sidebar* row, because the `/leave` row (`frontend/lib/nav.ts:123`) is the approver's
  queue, a different screen.
- **Verdict:** claim wrong. UX polish at most. **Not scheduled.**

### S4c / S4d / S4e — **duplicates of C1, C2, C3.** Scheduled once, in **T-002**.

### S5 — A recorded meal cannot be corrected · **build, and mis-sized**
- **Claim:** *"The primitive is already written and tested; this is the screen for it."*
- **Code:** the primitive is real —
  `backend/…/inventory/StockMovementController.java:49` (`POST /api/v1/inventory/movements/{id}/compensate`,
  `MANAGE_INVENTORY`, DTO `CompensateRequest(note)`), implemented at
  `StockMovementService.java:156-187`, appending a reverse `ADJUSTMENT` cross-referenced
  `referenceType=CORRECTION`, guarded once-only, and genuinely tested at
  `backend/src/test/java/org/iskcon/kms/inventory/StockMovementLedgerIT.java:161,186,306`.
  `frontend/lib/api.ts:3467` wraps it with zero callers.
- **But it is not sufficient, and this matters:**
  1. `InventoryConsumptionService.java:82-96` writes **one movement per (ingredient, batch) draw**,
     tagged `reference_type=MEAL_PLAN`. Correcting "the meal" means compensating a *set*.
  2. **There is no way to find that set.** `StockMovementController.history()` (`:38-46`) filters by
     `ingredientId`, `type`, `limit` — there is no filter by `referenceId`. The read capability is
     missing.
  3. The meal record is untouched by any stock reversal. `ServedMealService.java:194` throws
     `MEAL_ALREADY_RECORDED`; `MealServiceController.java` has exactly `GET`, `GET /summary`,
     `POST /record`. Compensate every movement and the meal still reads `COOKED` and still refuses.
- **Verdict:** build → **T-007**. **Needs new backend work**, not a screen. The docket sized this one
  smaller than it is.

### S6 — A volunteer shortfall cannot raise a shift from the planner · **build**
- **Code:** confirmed. `createShift` at `frontend/lib/api.ts:4536` → `POST /api/v1/shifts`
  (`backend/…/shift/ShiftController.java:62`, `MANAGE_VOLUNTEER_SHIFTS`). Its only caller anywhere is
  `frontend/app/volunteers/new/page.tsx`; no file under `frontend/app/planner/**` or
  `frontend/components/planner/**` references it.
- **Verdict:** build → **T-019**. Also `OUTSTANDING_BUILD_LIST` **P6**.

### S7 — A shift raised from the planner cannot be seen there afterwards · **build, one design question**
- **Code:** the planner shows an *anonymous* crew count — `CrewPebble` at
  `frontend/components/planner/MealServices.tsx:686-701`, fed by `MealCrewView`, which
  `MealCrewService.java:27` documents as deliberately shift-agnostic ("a shift posted 11:00–14:00
  falls to lunch without anybody having to link it to one"). It never shows *which* shift, its title,
  or its sign-up count. The data exists: `GET /api/v1/shifts?from&to` returns `ShiftView` with
  `signedUpCount`, `waitlistCount`, `capacity` (`backend/…/shift/ShiftView.java:14-28`).
- **The question:** there is no meal↔shift foreign key. Matching by time window needs no migration;
  an explicit link does. See Question 9.
- **Verdict:** build → **T-019** (same task as S6). Also `OUTSTANDING_BUILD_LIST` **P7**.

---

## Lane 2 — "A mistake, once made, is permanent" (9)

### M1 — The stock correction mechanism exists and no screen uses it · **build**
Confirmed; see S5 for the endpoint's detail. `frontend/app/inventory/[id]/page.tsx:286` already
renders movement history and even labels corrections at `:332` — there is simply no button.
Screen-only, zero backend. → **T-001**.

### M2 — A recorded meal can never be amended · **duplicate of S5.** → **T-007**.

### M3 — Goods accepted into stock can never be returned or corrected · **build**
`backend/…/receiving/ReceivingController.java:29-42` — `GET` and `POST` only. Rejection is
gate-time: `ReceiptLineInput.java:20-27` carries `rejectedQty` inside the same submission.
`MovementType.java:21-36` is exactly `PO_RECEIPT, DONATION_IN_KIND, CONSUMPTION, ADJUSTMENT, ISSUE` —
no return type, confirmed by grep. Needs a new enum value, **a migration** (the enum is mirrored in a
DB `CHECK`), a service method and an endpoint. → **T-013**.

### M4 — A vendor invoice cannot be corrected, voided or credited · **build**
`backend/…/invoice/VendorInvoiceController.java:30-50` — `GET`, `GET/{id}`, `POST`.
`InvoiceStatus.java:7-14` is exactly `PENDING, PAID`. Backend + **migration**. → **T-010**.

### M5 — A vendor payment cannot be reversed · **build**
`InvoicePaymentController.java:32-52` has no void; `InvoicePaymentService.java:72-73` flips to `PAID`
with no path back. The model to copy is real and already used in the UI:
`backend/…/staff/StaffPayController.java:72-79` — `POST …/payments/{paymentId}/void`, whose own doc
comment reads *"A POST rather than a DELETE, because nothing is removed: the row stays, marked, and
the URL says what actually happens to it."* Called from `frontend/app/staff/[id]/pay/page.tsx:155`.
Combined with M4 into one task — same package, one migration. → **T-010**.

### M6 — A hand-recorded donation cannot be voided · **build**
`backend/…/donation/DonationController.java:36-44` — POST only; `DonationLedgerController.java` is
read-only throughout. In-kind gifts write stock in the same transaction at
`DonationRecorder.java:76-78`. Backend + **migration**. → **T-012**.

### M7 — Equipment can never be edited; scrapping is a one-way door · **build, half already there**
The backend PUT **exists**: `backend/…/equipment/EquipmentController.java:80-89` accepts
`UpdateEquipmentRequest(name, storageLocation, acquisitionDate, source, notes, serialNumber,
purchaseCostInr, warrantyExpiry)`. No client wrapper, no `/equipment/[id]/edit` route.
`frontend/app/equipment/[id]/page.tsx:385-390` is a bare `<select>` with no confirmation.
**One correction:** `EquipmentService.java:171-174` refuses transitions *out of* `SCRAPPED` only in
`changeCondition`; the descriptive `update()` at `:144-166` has no such guard. And the class doc says
SCRAPPED is terminal **by design** — see Question 13. Screen-only. → **T-009**.

### M8 — An inventory item cannot be deleted · **build**
`InventoryItemController.java:86-91` (`MANAGE_INVENTORY`); `InventoryItemService.java:190-200` deletes
metadata only, commenting *"the movement history remains, so stopping tracking never erases the
ledger."* `frontend/lib/api.ts:3443` wraps it, zero callers. Screen-only. → **T-001**.

### M9 — Ending employment is irreversible and locks the record · **build, one half of the claim wrong**
- **Confirmed:** `StaffEmploymentService.java:242` calls `requireStillEmployed`, defined at `:389-393`;
  `update()` at `:173` calls the same guard, so a misclick cannot be corrected. No reinstate route
  anywhere in `StaffEmploymentController` (13 routes enumerated); grep for "reinstate" across backend
  and frontend returns nothing.
- **Wrong:** *"The role change that would undo the demotion has no screen either."* It does.
  `frontend/components/staff/StaffForm.tsx:167-190` renders a working `systemAccess` select, rendered
  by `frontend/app/staff/[id]/edit/page.tsx:102-110`, and `StaffEmploymentService.update():178-189`
  already handles promote/demote/revoke.
- → **T-014** (reinstatement only).

---

## Lane 3 — "The temple's own record cannot be corrected" (3)

### A1 — A temple's profile can be created and deleted, never edited · **build**
`backend/…/tenant/TenantController.java` — `POST :55`, `GET :72`, `GET/{id} :91`, `GET/{id}/export
:124`, `DELETE :160`. **No PUT or PATCH**, confirmed by grep. Entity columns are mutable
(`Tenant.java:23-62`, `slug` alone `updatable=false`); the only `UPDATE tenants` in the whole backend
is `TenantSettingsService.java:118`, touching `locale`.
**One thing the docket does not mention and a builder must handle:** `calendar_days` is precomputed
per tenant from `Tenant.timezone` (`CalendarService.java:87,204`), so a timezone edit must re-trigger
`CalendarPrecomputeScheduler.enqueueForTenant` or the temple keeps stale tithi rows. No migration. → **T-008**.

### A2 — 80G approval can never be recorded after provisioning · **build**
Every `is_80g_approved` site is a read or the provisioning insert
(`TenantProvisioningService.java:132,145,302`); readers at `TenantController.java:82,102`,
`GivingPageController.java:62,68`, `MonetaryDonationService.java:497`,
`RecurringDonationService.java:209`. Zero updates. Column already exists
(`V1__tenancy_foundation.sql:36`) — no migration. Same endpoint as A1. → **T-008**.

### A3 — Meal kinds cannot be created or renamed · **build (smaller than recorded)**
`MealKindController.java` — `GET :37`, `POST :43`, `PUT :50`, `DELETE :58`, writes behind
`MANAGE_TEMPLE_SETTINGS`. Wrappers `listMealKinds`, `updateMealKind`, `createMealKind` already exist
at `frontend/lib/api.ts:3573-3582`; only `deleteMealKind` is missing. Zero callers of the writers.
**The docket's worry is unfounded:** grep for `BREAKFAST`/`LUNCH`/`DINNER` string literals across
`frontend/app`, `frontend/lib`, `frontend/components` returns **zero** — no screen is hardcoded around
the seeded list, so renaming is safe. Screen-only. → **T-005**.

---

## Lane 4 — "Half a workflow" (10)

### B1 — A one-off purchase order cannot be raised · **build → T-026** *(Question 2 answered by D-1; shape set by D-7)*
`PurchaseOrderController.java:47-54` — `POST /api/v1/purchase-orders`, `MANAGE_PURCHASE_ORDERS`,
`createManual`, taking `CreatePurchaseOrderRequest(vendorId, neededBy, deliveryLocation, notes,
lines)`. `frontend/lib/api.ts:4078` wraps it, **zero** callers. `frontend/app/orders/` has no `new/`
route. Cheap, and now scheduled. `DECISIONS.md` **D-7** sets the shape: `/orders/new` asks for the vendor
on a screen of its own, first, with an "Add a vendor" `ButtonLink` beside a native `<select>`, and
choosing leads to the lines. Routing out costs nothing because nothing has been typed yet. → **T-026**.

### B2 — Nothing that is not an ingredient can be purchased · **build → T-023 + T-024** *(unblocked by D-1)*
`PoLineInput.java:12` — `@NotNull UUID ingredientId`; `V26__purchase_orders.sql:52-64` —
`ingredient_id UUID NOT NULL REFERENCES ingredients(id)`. The id is threaded far downstream, not just
into the PO line: `ReceivingService.java:272-274` looks up `canonicalUnit(ingredientId)` for unit
conversion and upserts `vendor_supplies(vendor_id, ingredient_id)` for price history;
`DocumentGenerationService.java:186-246` reads `l.ingredientName()` for every line of the PO sheet.
Honest size: two or more tables migrated, PO totals, receiving, the PDF template, and probably
invoicing. **D-1 split it in two and both halves are scheduled.** *Consumable supplies* — LPG, disposables,
cleaning, hand soap, first aid — behave exactly as ingredients already do, so they are **a flag on
`ingredients`** and everything downstream keying on `ingredient_id` is unchanged (**T-023**). *One-off
durables* — stools, extension cords — want the opposite, so `purchase_order_lines.ingredient_id`
becomes nullable with a `description` beside it (**T-024**). A parallel `supply_items` table was
rejected: it duplicates the whole inventory chain to express a difference that is one boolean.
The honest size above stands for T-024 and is why it has a wave to itself with two neighbours and no
glob in its contract.

### B3 — Nothing can be added to the shopping list by hand · **build → T-027** *(unblocked by D-1)*
`ShoppingListController.java` — `GET :31`, `POST /regenerate :37`, `PATCH /{ingredientId} :44`, which
only updates an existing row. **The schema already supports it:** `shopping_list_lines` is unique on
`(tenant_id, ingredient_id)` with an `edited BOOLEAN`, and
`ShoppingListService.java:161` deletes only rows where `edited = false`, so a hand-added line survives
regeneration by construction. Needs a `POST` and a service method, nothing more — **and the hand-added row must be written with
`edited = true`**, or the nightly regeneration deletes it at 3am. Re-verified 2026-09-07: the delete
is `ShoppingListService.java:156-163`, and the unique index on `(tenant_id, ingredient_id)` means the
add is an upsert or a readable refusal, never a blind insert. → **T-027**.

### B4 — A fully funded wish-list item has no path to being bought · **blocked**
`WishlistService.java:120-138` flips `ACTIVE → FULFILLED`, `:140-150` archives; neither touches
procurement. But `docs/WORK_QUEUE.md:31` records Rajeev **withdrawing** the wish-list link on
2026-09-04: *"funded, bought, delivered and registered are four moments, and only the temple knows the
fourth."* That ruling is about the *fourth* moment (equipment registration); it does not clearly
settle the *second* (bought). See Question 10.

### B5 — There is no donation receipt a donor can be given or re-sent · **build**
`backend/…/document/` holds exactly four templates — `RecipeCardTemplate`, `JobCardTemplate`,
`WorkOrderTemplate`, `PurchaseOrderSheetTemplate`. `donation/DonationReceipt.java:10-17` is a plain
record feeding a thank-you notification, not a document. The plug-in point is
`DocumentGenerationService.generate():90-159`, switching on `documents.kind`, whose
`documents_kind_valid` CHECK (`V12__documents.sql:13-40`) must gain a value. **Migration.** → **T-020**.

### B6 — A sent message cannot be re-sent or retried · **build**
`CommunicationService.java:178` sets `status='SENT'`; `requireDraft()` at `:327-332` throws
`COMMUNICATION_ALREADY_SENT` for anything non-draft and is called by `update`, `delete` and `send`.
Per-recipient failure is already stored and already on screen —
`communication_recipients` joined to `notifications`, exposed by `deliveries()` at `:266-282` and
`CommunicationController.java:116`. **No migration**, but `queueFor()`'s
`ON CONFLICT … DO NOTHING` at `:253` must become a `DO UPDATE` or a retry silently no-ops. → **T-015**.

### B7 — Volunteer no-shows cannot be recorded; nobody can be struck off a roster · **build**
`shift_signups` (`V34__shifts.sql:47-65`) is `id, tenant_id, shift_id, volunteer_user_id,
signed_up_at, released_at, source` — **no attendance column of any kind**; grep for
`no_show|noShow|attendance` across the shift package returns nothing.
`VolunteerShiftController.java:59-66` releases `actor.getUserId()` only — the caller's own id.
**Migration required.** → **T-016**.

### B8 — An approved ingredient request can never be closed · **claim wrong**
The state machine is `DRAFT → SUBMITTED → APPROVED → ISSUED` with `SUBMITTED → DENIED` and
`SUBMITTED → DRAFT` (`IngredientRequestStatus.java:34-63`), and withdraw/delete are gated as claimed
(`IngredientRequestService.java:321-330`, `:347-359`). **But an approved request does close today:**
`POST /{id}/issue` (`IngredientRequestController.java:138`) drives `APPROVED → ISSUED`, and
`IssuedLineInput.java:25` explicitly permits an issued quantity of **zero** per line. The storekeeper's
queue clears by issuing nothing. Further, `IngredientRequestStatus.java:29-32` records that a
`CANCELLED` state was **considered and rejected**: *"Both were considered and both would add a state
that nothing reads."* See Question 11.

### B9 — A recurring donation can be started but never seen or stopped · **build**
`RecurringDonationController.java` — `POST :32`, `GET :40`, `GET /{id}/history :46`,
`POST /{id}/cancel :53`. All three wrappers exist (`frontend/lib/api.ts:4816-4832`). **Zero** callers
of any of them; no donor-facing recurring screen exists. Screen-only. → **T-017**.

### B10 — Nobody's role can be changed from the Users screen · **build, but the docket names the wrong screen**
`UserController.java:70-79` — `PATCH /api/v1/users/{id}/role`, `MANAGE_USERS`, with three real guards
in `RoleChangeService.java`: no self-change (`:60-63`), no promotion to `SUPER_ADMIN` (`:75-78`), and
cross-tenant targets invisible under RLS (`:67-71`) — each refusal separately audited (`:105-117`).
`changeUserRole` (`frontend/lib/api.ts:3204`) has zero callers.
**But `/users` is the devotee register by design** — `frontend/app/users/page.tsx:40` lists
`VOLUNTEER` only, and its own comment at `:24-26` says *"Nor is there a role control: a devotee holds
one role, by definition."* The right home is the staff record. → **T-018**.

---

## Lane 5 — "A control that refuses the person looking at it" (5) — all confirmed

All five are small, self-contained frontend fixes. They become one task, **T-002**, because no two
make competing edits to the same lines.

| id | Verified at | Fix |
|---|---|---|
| **C1** | `frontend/app/ingredient-requests/[id]/page.tsx:372` renders `<WorkOrder>` with no role test; every endpoint in `backend/…/document/WorkOrderController.java` (`:64,:83,:90,:103,:115`) requires `ISSUE_INGREDIENTS`, which `RolePermissions.java:110-122` shows `KITCHEN_STAFF` does not hold. **Even the language picker 403s before a button is pressed.** | Gate on `mayIssue`, the boolean the same file already computes at `:109` and already uses for `RecordIssue` at `:559-565`. |
| **C2** | `frontend/app/today/page.tsx:128` — `href="/staff-schedule"`, no role test; `frontend/app/staff-schedule/page.tsx:62` admits `TEMPLE_ADMIN, KITCHEN_MANAGER` only; `frontend/components/RequireRole.tsx:41-48` renders a blocking "Not your page". The pattern to copy is `TodayService.java:352-357` (returns `null` without `MANAGE_EQUIPMENT_SERVICING`) consumed at `today/page.tsx:605-628`. | `StatTile`'s `href` is optional (`frontend/components/ds/StatTile.tsx:30,52`) — pass `undefined` for a cook. Frontend-only; the count itself is fine for them to see. |
| **C3** | `RolePermissions.java:96,118` grant `VIEW_OWN_SHIFTS` but `:128` grants `SIGN_UP_FOR_SHIFTS` to `VOLUNTEER` only; every write endpoint in `VolunteerShiftController.java:43,60,70,78` needs it, and `ShiftController` has no assign-a-person endpoint. The page is **structurally, permanently empty** for both kitchen roles. **Correction to the docket:** `my-shifts/page.tsx:65` is not a link — it is plain `<p>` text with no `href`. Dead copy, not a broken control. | Drop `/my-shifts` from those roles (`nav.ts:63`) or reword. |
| **C4** | `frontend/app/staff-schedule/page.tsx:159` links to `/staff`, which `frontend/app/staff/page.tsx:39` admits `TEMPLE_ADMIN` only. **The file contradicts itself:** its own doc at `:22-23` claims *"The link to the register itself has gone (A11)."* It did not. | Remove the link, as the doc comment already claims. |
| **C5** | `frontend/app/donate/page.tsx:13` admits `TEMPLE_ADMIN`; `frontend/lib/nav.ts:68` omits `ADMIN`. nav.ts's own rule at `:11-15`: *"a menu should never offer someone a destination they'll only be refused at. Every item carries the exact set of roles the destination's own page guard allows."* | Add `ADMIN` to the roles array. |

---

## Lane 6 — "Messages nobody will ever see" (4) — all confirmed

**D1, D2 and D3 share one root cause in one file** and become one task, **T-003**.
`backend/…/auth/AuthenticationFilter.java` drops silently in three places instead of emitting a code:
`:139-142` (disabled), `:93-101` (bad or expired token), `:118-134` (no membership — the block's own
comment reads *"Verified by Firebase, but a member of no temple yet"*).

**There is no existing mechanism to emit a coded error from a filter**, and this is the real work.
`GlobalExceptionHandler` is a `@RestControllerAdvice`, so it never sees anything thrown before
`DispatcherServlet`; `SecurityConfiguration.java:87-89` wires a bare
`HttpStatusEntryPoint(UNAUTHORIZED)` with **no body at all**, and the one precedent,
`LoggingAccessDeniedHandler.java:55`, writes a hand-rolled `{"error":"forbidden"}` rather than the
shared `ErrorResponse` shape. The task builds that mechanism once and applies it three times.

- **D1** `ACCOUNT_DISABLED` (KMS-4103) — declared with good copy, never referenced anywhere else.
- **D2** `SESSION_EXPIRED` (KMS-4102) — never thrown. **The signal exists but is deliberately
  discarded:** `FirebaseTokenVerifier.java:88-95` checks `AuthErrorCode.EXPIRED_ID_TOKEN`, then throws
  it away into a generic `InvalidTokenException` at `:63`/`:81`, because `TokenVerifier.java:12-16`
  states the policy: *"never distinguished further, since telling a caller precisely why their token
  failed helps an attacker more than a user."* See Question 8 — this is a decision, not a bug.
- **D3** `NO_ACCOUNT_AT_TEMPLE` (KMS-4104) — never thrown. The frontend infers it at
  `frontend/lib/auth-context.tsx:112-118` by treating *any* non-unreachable `/whoami` failure as
  "no-account" — **which means a disabled account and an expired session are currently both misread as
  "you have no account here."** D1 and D2 make that inference actively wrong, so the frontend half
  must ship with the backend half.
- **D4** `INVALID_PHONE_NUMBER` (KMS-4003) — never thrown; **structurally cannot be** today. Every
  phone check is a Bean Validation `@Pattern` (`JoinTempleRequest.java:24-27`,
  `ProvisionTenantRequest.java:65-69`, `CreateVendorRequest.java:13-14`, `UpdateVendorRequest.java:13-14`,
  `HireStaffRequest.java:36-39,64-67`; `CreateKitchenRequest.java:53-54` and
  `CreateRecurringRequest.java:17` have no format check at all), and
  `GlobalExceptionHandler.java:56-71` stamps `VALIDATION_FAILED` unconditionally on every
  `MethodArgumentNotValidException`. A different and larger change than D1–D3 — **its own task,
  T-021.**

---

## Lane 7 — "Built, but never proven" (9) — not build tasks

Kept out of the waves deliberately. Seven of the nine need a credential or a human, not a builder.

| id | Verdict | Class | Evidence |
|---|---|---|---|
| **P1** Razorpay has never taken a payment | confirmed | **Rajeev — credential** | `application.yml:132` — `provider: ${PAYMENTS_PROVIDER:stub}`; no `PAYMENTS_PROVIDER` / `RAZORPAY_*` anywhere in `infra/environment/main.tf`, `infra/deploy.sh` or `backend/cloudbuild.yaml`. Both gateways exist (`StubPaymentGateway`, `RazorpayPaymentGateway`). |
| **P2** WhatsApp has never sent anything | confirmed | **Rajeev — credential + Meta approval** | No `WHATSAPP_*` env beyond the webhook secret. **Design nuance the docket misses:** credentials are per-temple by design (`TenantWhatsAppSettingsService`), not a platform env var, and `WhatsAppChannelAdapter.java:44-49` degrades gracefully to SMS/email rather than failing. `TRACEABILITY.md` E8-S4 records it blocked on a MARKETING template approval. |
| **P3** Email reaches only pre-authorised addresses | confirmed | **Rajeev — account** | `infra/environment/terraform.tfvars:16-21` — `smtp_host = "smtp.mailgun.org"`, sandbox domain in both username and from-address. |
| **P4** The UAT pack has never had a human pass | **mostly confirmed, partly wrong** | **Human verification** | The pack is **84 tests**, not 61 (`docs/uat/README.md:10`; `SESSION_HANDOFF.md:167` is stale). And three *have* had dated live passes by Rajeev — `UAT-003:98-100`, `UAT-004:71-74`, `UAT-031:81-84` — which found real defects, one already fixed. Every other file still carries the blank template row. |
| **P5** Tenant isolation never proven on the deployed DB | confirmed | **Human verification** (+ optional code work) | `SESSION_HANDOFF.md:145` verbatim: *"I re-enabled FORCE after seeding but did not test cross-tenant isolation on the live DB. Believed to hold; unverified."* `RowLevelSecurityIT` proves a great deal locally — but always as a **non-owning** role, so the exact staging hazard (owner exempt without `FORCE`) is never exercised. A Testcontainers role that owns the schema would close it locally. |
| **P6** Two smoke tests permanently skipped | confirmed | **Env / human** | `GoogleTranslationSmokeIT.java:15` gated on `TRANSLATION_SMOKE`; `GcsDocumentStorageSmokeIT.java:17` on `DOCUMENTS_BUCKET`; `.github/workflows/ci.yml` sets neither. Deliberate, to keep CI hermetic. |
| **P7** The calendar has never been checked against a panchanga | **claim wrong** | none | `docs/CALENDAR-CORRECTNESS.md` records the opposite: computed dates compared against drikpanchang (geoname 1277333) and the ISKCON event calendar; all 25 Ekadashis for 2025 match. The Pandava Nirjala divergence was chased down to a real bug — `nMahaType != 0` vs `!= EV_NULL` in the ported Maha-Dvadashi calc — found because **Rajeev compared a computed date against the official ISKCON app** and fixed 2026-08-17, pinned by `MahadvadasiRuleTest`. Nothing to do. |
| **P8** Geocoding runs on Nominatim | confirmed | **Rajeev — decision** | `infra/environment/main.tf:437,668` — `GEOCODING_PROVIDER = "nominatim"`. Only `NominatimGeocodingProvider` and `NoGeocodingProvider` exist; **there is no Google geocoding implementation to switch to**, though `GoogleRoutesTravelTimeProvider`, `GoogleStaticMapProvider` and `GooglePlaceSuggestionProvider` do exist. |
| **P9** The unsubscribe page has no test | confirmed, superlative wrong | **build → T-022** | No test references `frontend/app/unsubscribe/page.tsx`. But it is **not** the only one: `app/library`, `app/choose-temple`, `app/register` and `app/c/[token]` are also untested (the first two appear in tests only as href/redirect assertions). |

---

## The headline recommendation does not survive the code

The docket's "What I would do with this" opens with:

> *"Wire up the correction endpoint. It closes the recorded-meal item and, with it, most of the nine
> 'a mistake is permanent' findings, which all bottom out in the same missing screen."*

**They do not bottom out in the same screen.** `StockMovementController.compensate` is scoped to rows
in `stock_movements`. It has no awareness of invoices, donations, meals, employment or equipment as
first-class entities. Checked one by one:

- **Closed by it:** M1 — and that is all. (M8 is also screen-only, but through
  `deleteInventoryItem`, an unrelated endpoint.)
- **Each needs its own new backend surface:** M2 (meal amend must also correct
  `meal_plans.actual_servings`), M3 (a `MovementType` that does not exist), M4 (invoice statuses that
  do not exist), M5 (a void endpoint on the payment side), M6 (a donation-side void), M7 (equipment is
  an asset register, not a quantity ledger), M9 (staff reinstatement touches no stock at all).

So: **1 of 9, not "most of the nine."** The correction screen is still worth building first — it is
cheap and it is the docket's own priority — but scheduling the other eight on the assumption that one
screen absorbs them would have under-planned the batch by roughly six backend tasks and five
migrations. This is the same failure mode the docket itself warns about, one layer up: a document
sizing work from a plausible summary rather than from the code.

---

## Questions for Rajeev

**Seven of the thirteen were answered on 2026-09-07** and are recorded in `docs/work/DECISIONS.md`,
which is binding. They are struck through here rather than deleted, so that a reader who arrives at
this file from the docket can see the question *and* what became of it.

| # | Question | Answer |
|---|---|---|
| 2 | Procurement scope — B1, B2, B3 | **D-1.** All three are release one. B2 splits in two. The wish-list→purchase→equipment chain is deferred, not rejected. |
| 5 | Who may correct a recorded meal | **D-4.** New `CORRECT_RECORDED_MEAL`, Temple Admin only. `KMS-400098`'s next step changes with it. |
| 6 | Who may void a donation | **D-4.** New `VOID_DONATION`, Temple Admin only — **note the name**, this file proposed `CORRECT_DONATIONS`. Forced, not chosen: `VIEW_DONATIONS` is already admin-only, so anything wider lets somebody void what they cannot read. |
| 12 | The 4900s band is nearly full | **D-6, and he answered a larger question.** Every code became six digits in one sweep. There is no band. |
| — | Recording a donation on `MANAGE_INVENTORY` | **D-5.** Left as it is, knowingly. A cook can create an 80G-relevant entry they can never read back; recorded rather than fixed, so it is inherited deliberately. |
| — | Inline vendor creation, and how it should look | **D-2 then D-7.** A manual PO creates a real vendor row, never typed text; `vendors.phone` relaxes from `NOT NULL`. The vendor is asked on a screen of its own, first — not inline, and not a preserved draft. |

**Still open, and nothing below is scheduled.** Nine remain, and they keep their original numbers so
that anything quoting them still resolves. **Question 8 is the one that has become urgent**: T-003
shipped without it and is two-thirds of a fix until it is answered.

1. **Temple-health indicator.** `BACKLOG.md` BL-1 says the backend already serves a per-temple health
   read. It does not — only the global unauthenticated `/health` exists. So this needs a backend
   endpoint *and* a decision: **what sits behind the dot** (last successful background job? unread
   failed sends? stale calendar precompute? something else), and **where it lives**.
3. **The day-one dataset** (`OUTSTANDING_BUILD_LIST` D1, parked). Still parked, or scheduled now?
4. **The operator audit drill-in (S2).** `WORK_QUEUE.md` records that you asked to see what `/audit`
   already does before anything is built. Do you want to look now?
7. **Who edits a temple's profile (A1/A2)?** Timezone and 80G status are operator-shaped
   (`MANAGE_TENANTS`, super admin); name and address are temple-shaped. One endpoint for the operator,
   or a split?
8. **`SESSION_EXPIRED` (D2) — now blocking the tail of a built task.** T-003 was dispatched on
   2026-09-07 with this carved *out* of its contract, so `ACCOUNT_DISABLED` (`KMS-400019`) and
   `NO_ACCOUNT_AT_TEMPLE` (`KMS-400020`) are now emitted and `SESSION_EXPIRED` (`KMS-400018`) is not.
   The consequence, carried deliberately and worth knowing: **an expired session is still reported to
   the user as "you have no account at this temple"**, because it falls through to the frontend's
   fallback branch. That was already true before T-003 and is not a regression — but T-003 fixed the
   other two cases around it, so it is now the only one left misreporting. `TokenVerifier` deliberately
   refuses to say *why* a token failed, on
   the stated grounds that it helps an attacker. Either carve out "expired" as the one safe
   disclosure — my recommendation, since staleness reveals nothing an attacker gains from — or delete
   KMS-4102 as dead copy. It cannot stay as it is.
9. **The planner's shift (S7).** Match a shift to a meal **by time window** (no migration, follows the
   existing deliberate design in `MealCrewService`) or add an **explicit meal↔shift link** (a
   migration, exact, but a new coupling the current design avoided on purpose)? My recommendation is
   the time window, to stay consistent with what is there.
10. **B4, the funded wish-list item.** Your 2026-09-04 withdrawal covered the *fourth* moment
    (registering the equipment). Does it also rule out the *second* — a funded item seeding a purchase
    order or a shopping-list line?
11. **B8, the ingredient request.** The claim is wrong: an approved request already closes by issuing
    every line at zero. Adding a real `CANCELLED` state would contradict a decision recorded in
    `IngredientRequestStatus.java:29-32`. Leave it, or overrule that?
13. **Equipment `SCRAPPED`.** `EquipmentService`'s own doc calls it terminal by design. T-009 adds a
    confirmation step but keeps it terminal. Confirm that is what you want.
