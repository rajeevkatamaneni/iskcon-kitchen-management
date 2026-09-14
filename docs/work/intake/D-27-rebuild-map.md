I've been through the migrations and code; the plan is below. The biggest findings:

- **The wipe is easier than it looks in one way.** The append-only trigger only blocks the app's own login, so a Flyway migration can delete those rows without lifting anything.
- **It is harder in another.** Unless the migration sets the temple id per tenant first, row-level security makes the delete remove nothing and still report success.
- **Stock will go up.** Deleting the stock movements from cooked meals puts that stock back on hand in the books.

## 1. Tables that identify a meal by date, kind or event text

| Table.columns | Meaning | Fate |
|---|---|---|
| `meal_plans.plan_date, meal_kind, event_name` (V22:31, V48:42, V88:81) | One row per dish; a meal is a group of these | Rename to dishes with `meal_id` FK; drop the three columns |
| `meal_services.plan_date, meal_kind, event_name` (V64:31-38, V89:92), unique at V89:197 | Job card number and recording for a meal | Fold into `meals`; drop the table |
| `documents.meal_service_id` (V64:166, shape check V117:64-72) | Job card PDFs | Becomes `meal_id` |
| `shifts.meal_date, meal_kind, meal_event_name` (V95:88-96) | Which meal a shift is for | Becomes nullable `meal_id`; drop the three columns and the check |
| `stock_movements` with reference `MEAL_PLAN` (V14:62) | `reference_id` is the dish id (InventoryConsumptionService.java:145,254) | Keep; dishes keep their ids |
| `meal_card_sequence` (V64:84) | Per-temple job card counter | Keep, reset on wipe |

No meal columns (nothing to change): `staff_leave` (V62:36), `staff_payment_deductions` (V63:197), `vendor_status_changes` (V83:53), `equipment_services` (V87:200), `ingredient_request_dishes` (free-text dish name, V77:142), `shift_signups` and attendance (V107).

- **Leave** only reads meals through a calculation (LeaveService.java:85, via MealCrewService).
- **`recurring_plans`** was a donations table and has already been dropped (OneTimeDonationIT.java:163-173).
- **Repeat and reuse** (MealPlanController.java:198, 211, 271) are the real "recurring meals"; they copy meals.

## 2. Per-meal fields

Today these are copied onto every dish row:
- ready-by (V48:44)
- adults, children, seniors and kitchen notes (V51:15-19)
- people needed, `crew_required` (V67:24)
- server notes (V92:66)
- purpose (V64:118)
- occasion name
- event fields: `is_outside`, handover, contact name and phone, delivery address, latitude, longitude, place id, sub-location, guests-eat-at and travel minutes (V88:69-116, V93:63)

On `meal_services` today: card number, version and fingerprint, recorded at/by with note, corrected at/by with note (V64:44-51, V92:54, V106:109).

Proposed homes:
- **Day table** (`tenant_id, plan_date`, unique): `day_type`. It is worked out from the date and calendar (V67:48).
- **`meals`**: `meal_kind_id` FK, `event_name`, occasion, every field in the first list, and all the card, recording and correction fields.
- **Dishes keep**: recipe, `target_yield`, status, actual servings, consumed quantity, not made, the two original figures (V64:96, V71:23, V106:164), Ekadashi acknowledgement (V23) and `cooked_at`.

## 3. What must change

**Backend**
- **Meal core.** Each class moves from grouping dish rows to meal rows:
  - MealPlanService (1,464 lines): create or find the day and meal, then write dishes.
  - ServedMealService (924): upsert and lookup by meal id (lines 643-651) instead of by date, kind and event.
  - MealPlanController, MealServiceController, and the request/view records CreateMealPlanRequest, UpdateMealPlanRequest, MealPlanView, ServedMeal, RecordMealRequest, CorrectMealRequest, SavedMealPlan: split into meal-level and dish-level shapes.
  - Reuse (ReusePlanPreview, ReusePlanRequest): copy meals.
  - MealKindService: remove the rename cascade (181-197); the in-use check (249-251) becomes a foreign key.
- **Sufficiency and committed stock.** SufficiencyService, MealSufficiency, OutsideCommitment, CommittedStockService (371-381) and CommittedMeal: join through meals.
- **Shopping list.** ShoppingListService:645: join through meals.
- **Job cards.** JobCardService (369, 381), JobCardController (60-66, today `date`/`mealKind`/`eventName` parameters) and DocumentService (124, 253): take `mealId`.
- **Costing.** MealKindCostService:169 and MaterialsCostService:66: join through meals. IssuedFromStoreService: no change.
- **Menu history and occasions.** MenuHistoryService (64, 87), CalendarController and OccasionController: read occasion from meals.
- **Crew.** MealCrewService (147), MealCrewView and MealCrewController: key by meal id. CrewCoverageService: follow those changes.
- **Today.** TodayService (134-140) and TodayView: carry the meal id.
- **Shifts.** ShiftService (168-199, 261, 366-374), CreateShiftRequest, UpdateShiftRequest and ShiftView: `mealId`. MealMoment.isFor (65-68) and WorkforceService:199: compare ids.
- **Other.** RecipeService:344: no change. InventoryConsumptionService: no change. AuditEntityType: add `MEAL`.

**Frontend**
- `lib/api.ts`: types and endpoint calls (lines 1238, 1360, 3297, 4792-5157, 5946).
- **Planner:** `app/planner/page.tsx`, `[date]/page.tsx`, `[date]/[kind]/page.tsx` (route becomes a meal id), `compose`, `reuse`, `catch-up`, `components/planner/DayView.tsx`, `MealComposer.tsx` (1,767 lines).
- **Recording and job cards:** `MealServices.tsx` (1,544 lines).
- **Shifts:** `ShiftLayer.tsx` (137-139), `app/volunteers/[id]/edit/page.tsx:58`.
- **Other screens:** `app/today/page.tsx`, `app/inventory/[id]/page.tsx:316` (links to the planner), `app/cost-per-serving/page.tsx`, `app/leave/page.tsx:218`.

**Tests to rewrite** (count of `@Test`/`it` in brackets)
- **Meal:** MealPlanIT (19), TravelEstimateIT (17), MealCorrectionIT (15), EventIdentityIT (12), SufficiencyIT (11), MealKindIT (11), MealRecordingIT (8), MealCrewIT (8), ReusePlanIT (8), MenuHistoryIT (7), EkadashiFlaggingIT (6). 11 files.
- **Old-migration tests, likely retired:** CateringMigrationIT, DeliveryPinBackfillIT.
- **Job cards:** JobCardIT (26).
- **Costing:** MaterialsCostIT, IssuedFromStoreIT.
- **Stock and shopping:** InventoryStockIT, ShoppingListIT, ShoppingListPerformanceIT with TempleScaleFixture.
- **Shifts and crew:** ShiftMealLinkIT, CrewCoverageIT.
- **Today:** TodayIT.
- **Fixtures only** (they insert or delete `meal_plans`): PurchaseOrderIT, RecipeIT, RecipeLibraryIT, GivingPageIT, SupplyIngredientIT, plus tenant setup and TenantLoopMigrationIT.
- **Frontend (13 files):** meal-composer, planner, planner-day-routes, planner-shift, meal-recording, meal-correction, reuse-plan, shift-edit-keeps-meal-link, crew-pebble, today, cost-per-serving, inventory-correction, leave.

## 4. Wipe scope

- **Temple:** "ISKCON South Bengaluru", id `f935450b-1b7c-4b2c-a7e3-73e40c7e31e3`. This is from staging notes (`docs/work/DISPATCH.md:730`, `docs/work/proof/T-061.md:343`), not from any migration. Hardcoding it would run in every environment; loop over all temples instead, since there is only one.
- **Delete order, per temple after setting the temple id:**
  1. `documents` where kind is `JOB_CARD_PDF` (their stored files will be left behind in storage)
  2. `stock_movements` with reference `CORRECTION` whose reference points at a meal movement (corrections point at the original movement, StockMovementService.java:201-202), then those with reference `MEAL_PLAN`
  3. `shift_reminders`, `shift_broadcasts`, `shift_waitlist`, `shift_signups`
  4. `shifts`
  5. `meal_services`
  6. `meal_plans`
  7. reset `meal_card_sequence`
- **Optionally** delete `audit_events` rows for meal plans and shifts. They have no foreign key, so they would just point at nothing.
- **Append-only tables in scope:** `stock_movements`, `shift_broadcasts` and optionally `audit_events`. The trigger function only refuses the app's own login, `kms_app` (V49:33-35), so a migration run as the migration role deletes without lifting anything. A purge run through the app would have to set `app.purging_tenant` to `on`. The grant-based lift in V45 is out of date.
- **RLS:** the tables use forced row-level security, so a delete without the temple id set removes nothing and still reports success (V97:97-103).
- **Stock:** on-hand is a sum over the movements and is never stored (V116:91). Deleting meal movements adds back everything meals drew. The rows for "used beyond recorded stock" count as zero. The books will then show more than the shelves, so plan a stock count adjustment or a stock wipe.

## 5. Builder tasks

1. **Schema and purge migration (V135 onward), medium.**
   - New tables with `enable_tenant_rls()`, the dishes `meal_id`, the shifts `meal_id`, the `documents` change, drop the old columns and `meal_services`, the purge, a minimal reseed.
   - Update TenantLoopMigrationIT.
2. **Backend meal core, large.** The `meal` package, job cards, `document`, today, costing, stock, shopping list, calendar and occasions, with their tests.
3. **Backend shifts and crew, small to medium.** The `shift` package, MealMoment, WorkforceService, CrewCoverageService, ShiftMealLinkIT and CrewCoverageIT. Can run in parallel with task 2.
4. **Frontend planner, recording and today, large.** This task owns `lib/api.ts`; planner, today, cost, inventory and leave pages, with their tests.
5. **Frontend shifts, small.** ShiftLayer, volunteer edit, and the planner-shift, shift-edit and crew-pebble tests. Runs after task 4 because both touch `api.ts`.

Order: 1, then 2 and 3 together, then 4, then 5.

## 6. Risks

- **Kinds:** `meal_kinds` is unique on `lower(name)` (V22:23), which PostgreSQL won't accept as a foreign-key target (V96:71). Point at `meal_kinds.id`.
- **Events:** two unnamed events on one day are currently one meal (V89:200). You need a rule, such as requiring a name, and a uniqueness rule within a day.
- **Corrections:** "only once" is guarded by `corrected_at` (V106:152), and stock is reversed by dish id. Keep the dish ids stable.
- **Migrations:** TenantLoopMigrationIT (seeds a temple at V2, then runs every migration) will run the purge, so every migration must loop per temple. BaseQuantityIT checks the unit rules on quantity columns, so a new yield or unit column must keep its check.
- **No stored cost snapshots.** Costs and menu history are computed from dish rows on read, so the wipe empties those screens. The job card fingerprint (V92) is the only stored snapshot.
- **Links:** bookmarks and links to `/planner/[date]/[kind]` stop working.
- **Process:** CLAUDE.md requires Rajeev to sign off changes to locked documents, and `docs/OUTSTANDING_BUILD_LIST.md` still has open items to raise with him first.

### Critical Files for Implementation
- /Users/Rajeev/Workspace/kitchen-management-system/backend/src/main/java/org/iskcon/kms/meal/MealPlanService.java
- /Users/Rajeev/Workspace/kitchen-management-system/backend/src/main/java/org/iskcon/kms/meal/ServedMealService.java
- /Users/Rajeev/Workspace/kitchen-management-system/backend/src/main/java/org/iskcon/kms/shift/ShiftService.java
- /Users/Rajeev/Workspace/kitchen-management-system/frontend/components/planner/MealComposer.tsx
- /Users/Rajeev/Workspace/kitchen-management-system/frontend/lib/api.ts