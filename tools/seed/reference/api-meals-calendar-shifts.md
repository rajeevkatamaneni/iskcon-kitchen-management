# API reference — meal, calendar, occasion, shift, kitchen, staff, today

All paths are under the backend's base; every endpoint is tenant-scoped by the signed-in user's temple (row-level security), so there is no tenant parameter anywhere. Error bodies are `{code, message, action, fieldErrors[]}` (`/backend/src/main/java/org/iskcon/kms/error/ErrorResponse.java`); `code` is the `KMS-4000xx` string. Bean-validation failures are always `KMS-400001` (VALIDATION_FAILED) with `fieldErrors`.

## Cross-cutting gate you must satisfy before any planner call (commit 596ce9a)

`/backend/src/main/java/org/iskcon/kms/auth/PlannerKitchenGuard.java` + `PlannerKitchenGuardConfiguration.java`: an interceptor runs after the permission check on these path prefixes:

```
/api/v1/meals, /api/v1/meals/**
/api/v1/meal-plans, /api/v1/meal-plans/**
/api/v1/meal-crew, /api/v1/meal-crew/**
/api/v1/job-cards, /api/v1/job-cards/**
```

It allows: role `TEMPLE_ADMIN` always; anyone else only if their **current ACTIVE staff record names a kitchen that is ACTIVE and has `uses_meal_planner = true`**. No staff record ⇒ refused. Failure is **403 KMS-400183 PLANNER_NOT_FOR_YOUR_KITCHEN**. Deliberately *not* guarded: `/api/v1/meal-kinds`, `/api/v1/today`, `/api/v1/workforce`, `/api/v1/leave`, `/api/v1/staff`, `/api/v1/occasions`, `/api/v1/calendar`, `/api/v1/kitchens`.

So for your simulation: the seeding user must be a Temple Admin, or a staff member in a planner-enabled kitchen.

---

# 1. `meal` package

Files: `/backend/src/main/java/org/iskcon/kms/meal/`.

## 1a. `/api/v1/meal-kinds` — MealKindController

| Endpoint | Permission | Body / params | Response |
|---|---|---|---|
| `GET /api/v1/meal-kinds` | `MANAGE_MEAL_PLANS` | — | `MealKindView[]`: `{id: uuid, name: string, sortOrder: int, defaultReadyTime: "HH:mm:ss"|null, isEvent: bool, needsOccasion: bool}`, ordered by `sortOrder, name` |
| `POST /api/v1/meal-kinds` | `MANAGE_TEMPLE_SETTINGS` | `CreateMealKindRequest` | 201 `{id}` |
| `PUT /api/v1/meal-kinds/{id}` | `MANAGE_TEMPLE_SETTINGS` | `CreateMealKindRequest` | 204 |
| `DELETE /api/v1/meal-kinds/{id}` | `MANAGE_TEMPLE_SETTINGS` | — | 204; refused `KMS-400126 MEAL_KIND_IN_USE` (DB `RESTRICT` from `meals.meal_kind_id`) |

`CreateMealKindRequest` (`CreateMealKindRequest.java`):
- `name` string, **required**, non-blank, ≤80
- `sortOrder` int (primitive; absent ⇒ 0)
- `defaultReadyTime` LocalTime `"HH:mm"`/`"HH:mm:ss"`, **nullable** — null means the kind always asks for a time
- `isEvent` bool (primitive, default false) — meals of this kind carry `eventName` and the outside/delivery block
- `needsOccasion` bool (primitive, default false) — a feast; must name a festival

Duplicate name (case-insensitive per tenant) ⇒ `KMS-400047 MEAL_KIND_ALREADY_EXISTS`.

## 1b. `/api/v1/meals` — MealController (the main planning surface)

All `MANAGE_MEAL_PLANS` except `correct`.

| Endpoint | Permission | Notes |
|---|---|---|
| `GET /api/v1/meals?from=&to=` (ISO dates, required) | `MANAGE_MEAL_PLANS` | `ServedMeal[]`, each with dishes, kitchens, card, recording, live volunteer shift |
| `GET /api/v1/meals/summary?from=&to=` | `MANAGE_MEAL_PLANS` | `{unrecorded: int, platesByMealKind: {<mealKindName>: int}}` (plates are for `from` only) |
| `GET /api/v1/meals/{id}` | `MANAGE_MEAL_PLANS` | one `ServedMeal`; `KMS-400030 RESOURCE_NOT_FOUND` |
| `POST /api/v1/meals` | `MANAGE_MEAL_PLANS` | body `SaveMealRequest`; **201 `{id: uuid, warning?: ErrorResponse}`** |
| `PUT /api/v1/meals/{id}` | `MANAGE_MEAL_PLANS` | body `UpdateMealRequest`; **200 `{id, warning?}`** |
| `POST /api/v1/meals/{id}/cancel` | `MANAGE_MEAL_PLANS` | body `CancelMealRequest` **optional**; 200 `CancelledMeals` |
| `GET /api/v1/meals/{id}/later-in-series` | `MANAGE_MEAL_PLANS` | `LaterInSeries`; `KMS-400178 MEAL_NOT_IN_SERIES` |
| `POST /api/v1/meals/{id}/repeat` | `MANAGE_MEAL_PLANS` | body `RepeatEventRequest`; 200 `RepeatEventResult` |
| `GET /api/v1/meals/{id}/repeat-preview?everyWeeks=&until=` | `MANAGE_MEAL_PLANS` | same `RepeatEventResult`, writes nothing |
| `GET /api/v1/meals/{id}/travel-estimate` | `MANAGE_MEAL_PLANS` | `TravelEstimate`, always 200 |
| `POST /api/v1/meals/{id}/record` | `MANAGE_MEAL_PLANS` | body `RecordMealRequest`; 200 the updated `ServedMeal` |
| `POST /api/v1/meals/{id}/correct` | **`CORRECT_RECORDED_MEAL`** (Temple Admin only) | body `CorrectMealRequest`; 200 `ServedMeal` |

### `SaveMealRequest` — exact JSON (`SaveMealRequest.java`)

```jsonc
{
  "planDate": "2026-09-20",        // LocalDate, REQUIRED
  "mealKindId": "uuid",            // REQUIRED — the id from GET /meal-kinds, never a name
  "readyBy": "12:00",              // LocalTime, nullable ONLY if the kind has defaultReadyTime,
                                   //   else KMS-400072 READY_BY_TIME_REQUIRED

  // Event block — honoured only when the kind has isEvent=true; silently dropped otherwise
  "eventName": "Children's Bhagavad-gita Reading",  // ≤200; REQUIRED for an event kind (KMS-400075)
  "isOutside": false,              // primitive bool
  "handover": "PICKUP" | "DELIVERY" | null,
  "contactName": "…",              // ≤200  } both required when isOutside=true (KMS-400076)
  "contactPhone": "…",             // ≤200  }
  "deliveryAddress": "…",          // ≤300  } required when handover=DELIVERY (KMS-400077)
  "guestsEatAt": "13:30",          // LocalTime, same requirement
  "deliverySubLocation": "Clubhouse",  // ≤200
  "deliveryPlaceId": "ChIJ…",      // ≤300, Google place id
  "deliveryLatitude": 12.9,        // BigDecimal, -90..90
  "deliveryLongitude": 77.6,       // BigDecimal, -180..180
  "travelMinutes": 45,             // Integer, 1..600
  "travelMinutesManual": false,    // bool; decides stored travel_minutes_source MANUAL vs ESTIMATED

  "purpose": "…",                  // ≤300
  "occasionName": "Sri Krsna Janmastami",  // ≤200; REQUIRED (or derivable) when kind.needsOccasion

  "adults": 200,                   // Integer ≥0
  "children": 40,                  // Integer ≥0
  "seniors": 10,                   // Integer ≥0
                                   // For a NON-event kind at least one must be >0,
                                   //   else KMS-400080 MEAL_HEAD_COUNT_REQUIRED. Events are exempt.

  "kitchens": [                    // Epic 12 / commit 596ce9a — see §7
    { "kitchenId": "uuid", "crewRequired": 6 }
  ],
  "kitchenNotes": "…",             // ≤2000
  "serverNotes": "…",              // ≤2000
  "ekadashiAcknowledged": false,   // bool; true to knowingly plan a grain dish on an Ekadashi

  "dishes": [                      // REQUIRED, @NotEmpty
    { "id": null,                  // must be null/absent on create (else KMS-400001 field "dishes")
      "recipeId": "uuid",          // REQUIRED
      "targetYield": 150.0,        // REQUIRED, >0, ≤50000, in the RECIPE's own yield unit
      "kitchenId": "uuid" }        // optional only when the meal has exactly one kitchen
  ],

  "volunteerShift": {              // nullable — see MealShiftDraft below
    "title": "Lunch prep",
    "description": "…",
    "startTime": "09:00",
    "endTime": "12:00",
    "location": "Main kitchen",
    "capacity": 8,
    "reminderOffsetsMinutes": [1440]
  }
}
```

**Identity / upsert semantics (important for seeding):** a meal is `(meal_plan_day, meal_kind_id, lower(coalesce(event_name,'')))` — unique index `meals_one_per_meal` (V136). Posting the same `planDate` + `mealKindId` + `eventName` (case-insensitive) **reuses the existing meal row and its id, and *adds* the dishes to it**; it does not create a second meal. A meal already recorded is refused `KMS-400098 MEAL_ALREADY_RECORDED`. A cancelled meal is reused (row is re-planned).

Response: `201 {"id": "<mealId>"}`, plus `"warning": {code:"KMS-400078", …}` when the delivery address could not be geocoded (`DELIVERY_ADDRESS_NOT_FOUND`) — still saved.

Other refusals from `MealPlanService.create`: `KMS-400071 MEAL_KIND_UNKNOWN` (bad `mealKindId`), `KMS-400048 EKADASHI_NOT_ACKNOWLEDGED`, `KMS-400079 DELIVERY_CANNOT_ARRIVE_IN_TIME`, `KMS-400180 MEAL_NEEDS_A_KITCHEN`, `KMS-400181 KITCHEN_DOES_NOT_PLAN_MEALS`, `KMS-400182 DISH_KITCHEN_NOT_ON_MEAL`, `KMS-400108 KITCHEN_NOT_FOUND`, `KMS-400030` for an unknown `recipeId`.

### `UpdateMealRequest` (`UpdateMealRequest.java`)

Same fields **minus** `planDate` and `mealKindId` (a meal cannot change its day or kind), plus `dishes` and `kitchens` being **whole lists**:
- dish with `id` ⇒ kept/changed; dish without `id` ⇒ added; a planned dish omitted ⇒ cancelled.
- `kitchens: null` ⇒ leave kitchens untouched; a kitchen omitted from a non-null list ⇒ taken off the meal (refused `KMS-400182` if any dish sent is still under it); `[]` ⇒ `KMS-400180`.
- `volunteerShift: null` ⇒ leave the meal's shift alone; a draft ⇒ create or update it.
Refused with `KMS-400098` once the meal is recorded or any dish is COOKED. Unknown dish id ⇒ `KMS-400030`; a dish of this meal that is no longer planned ⇒ `KMS-400046 MEAL_PLAN_NOT_OPEN`.

### `ServedMeal` — the response you chain from (`ServedMeal.java`)

```
mealId, mealKindId, planDate, mealKind (name), readyBy,
adults, children, seniors, plates, crewRequired (sum over kitchens, nullable),
kitchens: [ {kitchenId, kitchenName, isMain, crewRequired} ],
dayType: "REGULAR"|"WEEKEND"|"FESTIVAL", occasionName,
eventName, isOutside, handover, contactName, contactPhone,
deliveryAddress, deliverySubLocation, deliveryPlaceId, deliveryLatitude, deliveryLongitude,
guestsEatAt, travelMinutes, travelMinutesSource ("ESTIMATED"|"MANUAL"),
purpose, kitchenNotes, serverNotes,
status: "PLANNED"|"COOKED"|"CANCELLED",     // derived from the dishes, no column
cardNumber, cardIssuedAt,
recorded (bool), recordedAt, recordedByName, recordingNote,
corrected (bool), correctedAt, correctedByName, correctionNote,
series: {seriesId, everyWeeks, until, position, count} | null,
dishes: [ MealDishView ],
volunteerShift: ShiftView | null
```

`MealDishView`: `{id, mealId, kitchenId, recipeId, recipeName, targetYield, targetYieldUnit, status, actualServings, consumedQuantity, notMade, originalActualServings, originalConsumedQuantity, cookedAt, ekadashiAcknowledged, createdAt}`. **`dishes[].id` is the `dishId` you pass to record/correct.**

### Cancel / repeat

`CancelMealRequest` (body optional):
- `reason` ≤500, nullable — told to volunteers
- `scope`: `"THIS"` (default when absent) | `"THIS_AND_LATER"`
- `expectedMealIds`: `uuid[]` ≤200, optional — from `later-in-series`; mismatch ⇒ `KMS-400179 SERIES_CHANGED_SINCE_CHECKED`
Response `CancelledMeals` `{volunteersTold: int, mealsCancelled: int, lastDate: date|null}`. A cooked meal ⇒ `KMS-400045 CANNOT_CANCEL_COOKED_MEAL`.

`RepeatEventRequest`: `{everyWeeks: int (required, 1..12 → KMS-400175), until: date (required → KMS-400176 if too far)}`. Response `RepeatEventResult` `{copies, preparations, dates[], skippedFasting[], skippedAlreadyPlanned[], lastDate, series:{seriesId,everyWeeks,until,position,count}}`. Nothing to copy ⇒ `KMS-400177 REPEAT_MAKES_NO_COPIES`. Source not planned ⇒ `KMS-400046`. No volunteer shift is copied. Series table is `meal_series` (V149): `every_weeks INTEGER 1..12`, `until_date DATE NOT NULL`, `meals.series_id`, `meals.series_edited_at`.

`LaterInSeries`: `{seriesId, later:[{mealId, planDate, edited, volunteersSignedUp}], lastDate, volunteersToTell}`.

### Recording actuals — `POST /api/v1/meals/{id}/record`

`RecordMealRequest` (`RecordMealRequest.java`):
```jsonc
{
  "note": "ran short, sent out at 220",   // ≤2000, nullable
  "dishes": [                              // @NotEmpty; MUST name EVERY still-PLANNED dish
    { "dishId": "uuid",                    // REQUIRED
      "actualServings": 58.0,              // what was COOKED, recipe yield unit. Required unless notMade.
                                           //   must be >0 and ≤100000 else KMS-400009 SERVINGS_NOT_VALID
      "consumedQuantity": 38.0,            // what went OUT. Nullable = "the card did not say";
                                           //   0 ≤ consumed ≤ actualServings else KMS-400009
      "notMade": false }                   // true ⇒ dish stored as CANCELLED, servings 0, draws no stock
  ]
}
```
Rules (`ServedMealService.record`): a still-planned dish left out of `dishes` ⇒ `KMS-400009`; a `dishId` that is not an open dish of this meal ⇒ `KMS-400030`; already recorded ⇒ `KMS-400098`; nothing left to record because all dishes cancelled ⇒ `KMS-400099 MEAL_NOT_RECORDABLE`. Recording draws stock FEFO per dish against `actualServings`, all-or-nothing for the whole meal. Sets `meals.recorded_at/recorded_by/recording_note`, and each dish `status='COOKED'`, `actual_servings`, `consumed_quantity`, `cooked_at`.

**Leftovers / short**: there is no leftovers field. Leftover = `actualServings − consumedQuantity` (V71 explicitly: cooked vs consumed, both in the recipe's yield unit; the difference is what came back). "Short" is the planned-vs-cooked comparison (`targetYield` vs `actualServings`); nothing is stored for it. A dish never made is `notMade: true`.

### Correcting — `POST /api/v1/meals/{id}/correct` (`CORRECT_RECORDED_MEAL`)

`CorrectMealRequest`: `{note: string REQUIRED non-blank ≤2000, dishes: [{dishId, actualServings, consumedQuantity, notMade}]}` — identical dish shape and identical validation to recording. Restates the whole meal (not a delta). Second correction ⇒ `KMS-400137 MEAL_ALREADY_CORRECTED`; not recorded yet ⇒ `KMS-400030`/`KMS-400001`. The first recorded figures are preserved on the dish as `originalActualServings` / `originalConsumedQuantity`; stock movements are compensated and re-drawn.

## 1c. `/api/v1/meal-plans` — MealPlanController (date-level helpers; no meal writes here)

All `MANAGE_MEAL_PLANS`.

| Endpoint | Params | Response |
|---|---|---|
| `GET /api/v1/meal-plans/day-context?date=` | ISO date | `DayContext {suggestedDayType, occasionName, suggestedServings, isEkadashi}` |
| `GET /api/v1/meal-plans/ekadashi-check?date=&recipeId=` | | `EkadashiCheck {isEkadashi, compatible, offendingIngredients[]}` |
| `GET /api/v1/meal-plans/menu-history?occasionName=&before=` | | `MenuHistoryView {occasionName, lastCookedOn, mealKind, preparationCount, missingCount, preparations:[{recipeId, recipeName}], mealId}` |
| `GET /api/v1/meal-plans/sufficiency?from=&to=` | | `MealSufficiency[] {dishId, mealId, planDate, mealKind, eventName, readyBy, recipeName, status, shortfalls[], orderBy, orderUrgency}` |
| `GET /api/v1/meal-plans/shortfall` | — | `ShortfallItem[] {ingredientId, ingredientName, shortBy, unit}` |
| `GET /api/v1/meal-plans/outside-commitments` | — | `OutsideCommitment[] {mealId, planDate, eventName, mealKind, handover, contactName, contactPhone, deliveryAddress, readyBy, guestsEatAt, preparations}` |
| `GET /api/v1/meal-plans/event-names?q=` | prefix, optional, ≤10 results | `EventSuggestion[] {eventName, isOutside, handover, contactName, contactPhone, deliveryAddress}` |
| `GET /api/v1/meal-plans/travel-estimate?placeId=&latitude=&longitude=&planDate=&guestsEatAt=` | planDate + guestsEatAt required | `TravelEstimate {available, leaveBy, optimisticMinutes, pessimisticMinutes, guestsEatAt, reason}` |
| `POST /api/v1/meal-plans/reuse/preview` | `ReusePlanRequest` | `ReusePlanPreview` |
| `POST /api/v1/meal-plans/reuse` | `ReusePlanRequest` | `ReusePlanResult {copied, daysWritten, daysLeftAlone, notCopied, sourceWasEmpty}` |

`ReusePlanRequest`: `{sourceStart: date REQUIRED, days: int 1..62 REQUIRED, targetStart: date REQUIRED, mealKinds: string[]|null (names; null = all main kinds), eventNames: string[]|null (null = none)}`. Only ever adds — a target day that already has any meal is left entirely alone.

## 1d. `/api/v1/meal-crew` — MealCrewController

All `MANAGE_MEAL_PLANS`.

- `GET /api/v1/meal-crew?from=&to=` — max span 62 days (`KMS-400001` with `field:"to"` otherwise). Returns `MealCrewView[]`: `{mealId, planDate, mealKind, readyBy, crewRequired, staffIn, volunteers, rostered, shortOfCrew, mealKitchenCount, kitchens: [KitchenCrewView]}`; `KitchenCrewView = {kitchenId, kitchenName, crewRequired, staffIn, staffNames[], volunteers, rostered, shortOfCrew}`.
- `GET /api/v1/meal-crew/at?date=&readyBy=&kitchenId=(opt)&countVolunteers=(default true)` — `{planDate, readyBy, staffIn, volunteers, rostered, staffNames[]}`. Read-only; creates nothing.
- `GET /api/v1/meal-crew/suggested?mealKind=<name>` — `{crewRequired: int|null}`.

## 1e. Related but outside `meal`: job cards and work orders

- `POST /api/v1/job-cards?mealId=&language=(opt)&kitchenId=(opt)` — `MANAGE_MEAL_PLANS` (+ planner-kitchen guard). **202** `{documentId, cardNumber, status:"PENDING"}`. `kitchenId` may be omitted only when one kitchen cooks the meal; else `KMS-400186 JOB_CARD_NEEDS_A_KITCHEN` / `KMS-400187 KITCHEN_NOT_ON_THIS_MEAL`. Card number is one per meal; each kitchen gets its own card version (`meal_kitchens.card_version`, V152).
- `GET /api/v1/job-cards/languages?mealId=`, `GET /api/v1/job-cards/documents?mealId=`, `GET /api/v1/job-cards/documents/{documentId}`, `GET /api/v1/job-cards/documents/{documentId}/download`, `GET /api/v1/job-cards/print?mealId=&language=&kitchenId=` (HTML).
- `/api/v1/work-orders` is **not** about meals — it is the store's ingredient-request paperwork, permission `ISSUE_INGREDIENTS`: `POST /api/v1/work-orders?requestId=&language=`, `GET /api/v1/work-orders/languages`, `GET /api/v1/work-orders/documents?requestId=`, `GET /api/v1/work-orders/documents/{documentId}`, `…/download`, `GET /api/v1/work-orders/print?requestId=&language=`.

---

# 2. `calendar` package — `/api/v1/calendar`

`/backend/src/main/java/org/iskcon/kms/calendar/CalendarController.java`

| Endpoint | Permission | Notes |
|---|---|---|
| `GET /api/v1/calendar?from=&to=` | `MANAGE_MEAL_PLANS` | both required, `to >= from` and span ≤ **550 days**, else `KMS-400001` with `field:"range"`. Returns `CalendarDayView[]` |
| `GET /api/v1/calendar/{date}` | `MANAGE_MEAL_PLANS` | `CalendarDayView`, or **204 No Content** when that date has not been precomputed |
| `PUT /api/v1/calendar/{date}/override` | `OVERRIDE_CALENDAR_DATE` (Temple Admin) | `SetCalendarOverrideRequest`; 204 |
| `DELETE /api/v1/calendar/{date}/override` | `OVERRIDE_CALENDAR_DATE` | 204 |

`CalendarDayView`: `{date, tithi (0..29), paksa (0|1), masa (0..12), gaurabdaYear, naksatra, isEkadashi, ekadashiName, mahadvadashi, fastType, sunrise, sunset, festivals: [{text, priority}], overridden, overrideReason}`.

`SetCalendarOverrideRequest`: `{isEkadashi: bool, ekadashiName: ≤120 nullable, tithi: Integer 0..29 nullable, festivalNote: ≤200 nullable, reason: string REQUIRED non-blank ≤500}`.

**How festival dates are stored/read.** Table `calendar_days` (V19), one row per tenant per date, unique `(tenant_id, cal_date)`. Festivals live in a **JSONB array `festivals` of `{text, priority}`** on the day row. Values are computed by a Java port of the ISKCON GCAL engine (`calendar/engine/VaishnavaCalendarEngine.java`) at the temple's own lat/long, **precomputed nightly for a rolling 550-day horizon** (`CalendarService.HORIZON_DAYS = 550`) by `CalendarPrecomputeJob`/`CalendarPrecomputeRunner`; nothing computes astronomy on a request. Watermark table `calendar_precompute_state {tenant_id, last_run_at, computed_through, days_computed}`. Overrides live in `calendar_overrides` (V21) and survive the recompute.

**Is there a "list festivals for a date range" endpoint?** Two, depending on what you mean:
- Raw calendar festivals: `GET /api/v1/calendar?from=&to=` and read each day's `festivals[]`.
- Named occasions resolved to dates: `GET /api/v1/occasions/resolved?from=&to=` (below).

---

# 3. `occasion` package — `/api/v1/occasions`

| Endpoint | Permission | Body | Response |
|---|---|---|---|
| `GET /api/v1/occasions` | `MANAGE_MEAL_PLANS` | — | `OccasionView[] {id, name, type, matchText, fixedMonth, fixedDay, defaultServings, notes, seeded}` |
| `GET /api/v1/occasions/resolved?from=&to=` | `MANAGE_MEAL_PLANS` | — | `ResolvedOccasion[] {occasionId, name, date, defaultServings, type}`, sorted by date then name |
| `POST /api/v1/occasions` | `MANAGE_TEMPLE_SETTINGS` | `CreateOccasionRequest` | 201 `{id}` |
| `PUT /api/v1/occasions/{id}` | `MANAGE_TEMPLE_SETTINGS` | `UpdateOccasionRequest` | 204 |
| `DELETE /api/v1/occasions/{id}` | `MANAGE_TEMPLE_SETTINGS` | — | 204 |

`CreateOccasionRequest`: `{name: REQUIRED non-blank ≤200, type: "COMPUTED"|"MANUAL" REQUIRED, matchText: ≤200 (REQUIRED for COMPUTED, else KMS-400001 field "matchText"), fixedMonth: 1..12, fixedDay: 1..31 (both REQUIRED for MANUAL, else KMS-400001 field "fixedDate"), defaultServings: Integer ≥0, notes: ≤1000}`. Duplicate name ⇒ `KMS-400044 OCCASION_ALREADY_EXISTS`. `UpdateOccasionRequest` is the same minus `type` (type is fixed at creation).

**Resolution** (`OccasionService.resolve`): a COMPUTED occasion matches `lower(calendar_days.festivals::text) LIKE '%'||lower(matchText)||'%'` within the range; a MANUAL one recurs on its fixed month/day each year in the range.

**Are festivals seeded?** Yes. `OccasionService.seedForCurrentTenant()` is called from `TenantProvisioningService` at temple provisioning (idempotent, `ON CONFLICT DO NOTHING` on `lower(name)`), writing these 13 COMPUTED occasions with `seeded=true` — name / matchText / defaultServings:

```
Nityananda Trayodasi / Nityananda Trayodasi / 300
Gaura Purnima / Gaura Purnima / 1000
Rama Navami / Rama Navami / 500
Nrsimha Caturdasi / Nrsimha Caturdasi / 500
Snana Yatra / Snana Yatra / 300
Ratha Yatra / Ratha Yatra / 1000
Guru Purnima / "Guru (Vyasa) Purnima" / 300
Balarama Purnima / "Balarama -- Appearance" / 500
Sri Krsna Janmastami / Janmastami / 2000
Srila Prabhupada Appearance / "Srila Prabhupada -- Appearance" / 800
Radhastami / Radhastami / 800
Govardhana Puja / Govardhana Puja / 500
Srila Prabhupada Disappearance / "Srila Prabhupada -- Disappearance" / 500
```

The *calendar days themselves* (`calendar_days`) are not seeded by a migration — they come from the precompute job. If a fresh temple has no calendar rows, `GET /api/v1/calendar/{date}` gives 204 and `day-context` will report no festival.

Also seeded at provisioning: **meal kinds** (`MealKindService.seedForCurrentTenant()`), exactly six, `name / sortOrder / defaultReadyTime / isEvent / needsOccasion`:

```
"Breakfast"      10  07:30  false false
"Lunch"          20  12:00  false false
"Dinner"         30  19:30  false false
"Festival feast" 35  null   false true
"Deity Offering" 40  null   false false
"Event"          50  null   true  false
```

These are the **exact strings**. They are stored in `meal_kinds.name` as typed; uniqueness is case-insensitive per tenant (`meal_kinds_name_per_tenant` on `(tenant_id, lower(name))`). `MealKindService.create/update` trims the name; there is no other canonicalisation — no title-casing, no vocabulary. A meal points at its kind by `meals.meal_kind_id` (FK, `RESTRICT`), **never by name** (since V136/D-27). Older kinds `Catering order` / `Outside event` were folded into `Event` by V88 and no longer seed.

---

# 4. `shift` package

## `/api/v1/shifts` — ShiftController (coordinator side)

| Endpoint | Permission | Body | Response |
|---|---|---|---|
| `GET /api/v1/shifts?from=&to=&includeCancelled=false` | `MANAGE_VOLUNTEER_SHIFTS` | — | `ShiftView[]` |
| `GET /api/v1/shifts/{id}` | `MANAGE_VOLUNTEER_SHIFTS` | — | `ShiftView` |
| `GET /api/v1/shifts/{id}/roster` | `MANAGE_VOLUNTEER_SHIFTS` | — | `RosterView` |
| `POST /api/v1/shifts` | `MANAGE_VOLUNTEER_SHIFTS` | `CreateShiftRequest` | **201 `{id}`** |
| `PUT /api/v1/shifts/{id}` | `MANAGE_VOLUNTEER_SHIFTS` | `UpdateShiftRequest` | 204 |
| `POST /api/v1/shifts/{id}/cancel` | `MANAGE_VOLUNTEER_SHIFTS` | `{reason: REQUIRED non-blank ≤500}` | 204 |
| `POST /api/v1/shifts/{id}/attendance` | `MANAGE_VOLUNTEER_SHIFTS` | `RecordAttendanceRequest` | 204 |
| `PUT /api/v1/shifts/{id}/attendance/{userId}` | **`CORRECT_RECORDED_ATTENDANCE`** | `{attended: Boolean REQUIRED}` | 204 |
| `POST /api/v1/shifts/{id}/signups/{userId}/release` | `MANAGE_VOLUNTEER_SHIFTS` | `RemoveVolunteerRequest` | 204 |
| `POST /api/v1/shifts/{id}/broadcast` | `MANAGE_VOLUNTEER_SHIFTS` | `{message: REQUIRED ≤1000, includeWaitlist: bool}` | 201 `{broadcastId, recipients, queued}` |

`CreateShiftRequest`: `{title REQUIRED non-blank ≤200, description ≤2000, shiftDate REQUIRED (the date it STARTS), startTime REQUIRED, endTime REQUIRED, location ≤300, capacity int REQUIRED >0, reminderOffsetsMinutes: int[]|null (each >0; default `[1440]`)}`. `endTime == startTime` is refused by `@AssertTrue` (KMS-400001); `endTime < startTime` means the shift runs through midnight. **`POST /api/v1/shifts` cannot make a meal shift** — no meal fields; a shift for a meal is only raised from the meal (see below).

`UpdateShiftRequest`: same fields **plus `mealId: uuid|null`**, which must be echoed back exactly as read — changing it (or the date of a meal shift) ⇒ `KMS-400153 MEAL_SHIFT_KEEPS_ITS_MEAL`. Non-OPEN shift ⇒ `KMS-400058 SHIFT_NOT_OPEN`. A capacity increase promotes the waitlist and reschedules reminders.

`RecordAttendanceRequest`: `{marks: [{userId: uuid REQUIRED, attended: Boolean REQUIRED (boxed)}] @NotEmpty}`. Whole roster in one call; a person left out stays **unmarked** (a third state, distinct from absent). Before the shift has started ⇒ `KMS-400144 SHIFT_NOT_STARTED`; a second call ⇒ `KMS-400139 ATTENDANCE_ALREADY_RECORDED`; a userId not on the roster ⇒ `KMS-400062 NOT_ON_SHIFT`.

`RemoveVolunteerRequest`: `{reason: "SHIFT_CANCELLED"|"NO_LONGER_NEEDED"|"ROTA_CHANGED"|"OTHER" REQUIRED, internalNote: REQUIRED non-blank ≤1000}`. The old `DELETE .../signups/{userId}` is withdrawn (now `KMS-400030`).

## `/api/v1/...` — VolunteerShiftController (volunteer side)

| Endpoint | Permission | Response |
|---|---|---|
| `GET /api/v1/available-shifts?from=&to=` | `SIGN_UP_FOR_SHIFTS` | `AvailableShiftView[] {id, title, description, shiftDate, startTime, endTime, location, capacity, signedUpCount, waitlistCount, callerState}` where `callerState ∈ AVAILABLE|FULL|SIGNED_UP|WAITLISTED` |
| `POST /api/v1/shifts/{id}/signup` | `SIGN_UP_FOR_SHIFTS` | **201 `{signupId, overlapWarning}`**; no body. Acts on the caller only |
| `POST /api/v1/shifts/{id}/release` | `SIGN_UP_FOR_SHIFTS` | 204 |
| `POST /api/v1/shifts/{id}/waitlist` | `SIGN_UP_FOR_SHIFTS` | 201, no body |
| `DELETE /api/v1/shifts/{id}/waitlist` | `SIGN_UP_FOR_SHIFTS` | 204 |
| `GET /api/v1/my-shifts` | `VIEW_OWN_SHIFTS` | `MyShiftView[] {signupId, shiftId, title, shiftDate, startTime, endTime, location, source, signedUpAt}` |
| `GET /api/v1/my-shifts/released` | `VIEW_OWN_SHIFTS` | `MyReleasedShiftView[]` |
| `GET /api/v1/my-waitlist` | `VIEW_OWN_SHIFTS` | `MyWaitlistView[]` |

Sign-up refusals: `KMS-400060 ALREADY_SIGNED_UP`, `KMS-400061 SHIFT_FULL`, `KMS-400058 SHIFT_NOT_OPEN`, `KMS-400059 SHIFT_ALREADY_STARTED`, `KMS-400064 SHIFT_NOT_FULL` (joining a waitlist on a shift with room), `KMS-400062 NOT_ON_SHIFT`, `KMS-400063 ALREADY_ON_WAITLIST`.

**Required vs filled** is expressed as `ShiftView.capacity` (the "Volunteers requested" number, column `shifts.capacity`) against `ShiftView.signedUpCount` (signups not released), with `waitlistCount` behind it. There is no stored "filled" column — both counts are computed. For the *meal's* crew there is a different pair: `crewRequired` (per kitchen, summed on the meal) against `rostered = staffIn + volunteers`, exposed on `MealCrewView`/`KitchenCrewView` with `shortOfCrew`.

**Shift table** (`shifts`, V34 + V95 + V127): `title, description, shift_date, start_time, end_time, location, capacity, reminder_offsets_minutes JSONB DEFAULT '[1440]', status TEXT CHECK IN ('OPEN','CANCELLED'), cancel_reason, cancelled_at, created_by, meal_id (nullable FK to meals)`. `shift_signups {shift_id, volunteer_user_id, signed_up_at, released_at, released_reason, released_note, source ('SIGNUP'|…), attended BOOLEAN NULL, attendance_recorded_at, attendance_corrected_at/_by}`; `shift_waitlist {shift_id, volunteer_user_id, joined_at, promoted_at, left_at}`.

## Shift settings — `/api/v1/settings` (SettingsController lives in `shift`)

`GET /api/v1/settings` (`MANAGE_TEMPLE_SETTINGS`) ⇒ `{volunteerBroadcastDailyLimit, locale, themeId, stockExpiryWarningDays, contractEndWarningDays, equipmentServiceWarningDays}`.
`PUT /api/v1/settings/theme` `{themeId: non-blank}`; `PUT /api/v1/settings/language` `{language: non-blank ISO 639-1}`; `PUT /api/v1/settings/warning-horizons` `{stockExpiryWarningDays, contractEndWarningDays, equipmentServiceWarningDays}` all int 1..365, **all three required**; `PUT /api/v1/settings/volunteer-broadcast-limit` `{limit: int >0}`. All 204, all `MANAGE_TEMPLE_SETTINGS`.

---

# 5. `kitchen` package — `/api/v1/kitchens`

| Endpoint | Permission | Body | Response |
|---|---|---|---|
| `GET /api/v1/kitchens?includeArchived=false` | `MANAGE_KITCHENS` **or** `REQUEST_INGREDIENTS` | — | `KitchenView[]` |
| `GET /api/v1/kitchens/{id}` | `MANAGE_KITCHENS` | — | `KitchenView` |
| `POST /api/v1/kitchens` | `MANAGE_KITCHENS` | `CreateKitchenRequest` | 201 `{id}` |
| `PUT /api/v1/kitchens/{id}` | `MANAGE_KITCHENS` | `UpdateKitchenRequest` | 204 |
| `GET /api/v1/kitchens/{id}/meal-planner-impact` | `MANAGE_KITCHENS` | — | `{draftsDeleted: int, requestsDenied: int}` |
| `POST /api/v1/kitchens/{id}/archive` | `MANAGE_KITCHENS` | — | 204; refused `KMS-400185 KITCHEN_HAS_STAFF` |
| `POST /api/v1/kitchens/{id}/restore` | `MANAGE_KITCHENS` | — | 204 |
| `DELETE /api/v1/kitchens/{id}` | `MANAGE_KITCHENS` | — | 204; refused `KMS-400107 KITCHEN_IN_USE` (archive instead) |

`CreateKitchenRequest`: `{name REQUIRED non-blank ≤200, description ≤2000, location ≤500, isMain bool, usesMealPlanner bool, inChargeUserId uuid|null, contactPhone ≤30 (deliberately NOT E.164)}`. `UpdateKitchenRequest` is the same set (full replacement). First kitchen of a temple is forced `isMain`. Setting `isMain=true` moves the flag off the previous main. Errors: `KMS-400106 KITCHEN_NAME_TAKEN`, `KMS-400108 KITCHEN_NOT_FOUND`, `KMS-400109 KITCHEN_ARCHIVED`, `KMS-400110 KITCHEN_PLANS_ITS_OWN_MEALS`, `KMS-400120 KITCHEN_MAIN_MOVED`.

`KitchenView`: `{id, name, description, location, isMain, usesMealPlanner, inChargeUserId, inChargeName, staffCount, contactPhone, status ("ACTIVE"|"ARCHIVED"), createdAt}`.

---

# 6. `staff` package

## `/api/v1/staff` — StaffScheduleController

| Endpoint | Permission | Body | Response |
|---|---|---|---|
| `GET /api/v1/staff/register` | `MANAGE_STAFF` | — | `StaffRegisterView {current: StaffProfileView[], former: FormerStaffView[]}` |
| `GET /api/v1/staff/job-titles` | `MANAGE_STAFF` | — | `JobTitleOption[]` |
| `POST /api/v1/staff/members` | `MANAGE_STAFF` | `HireStaffRequest` | 201 `{id}` (staff profile id) |
| `PUT /api/v1/staff/members/{id}` | `MANAGE_STAFF` | `UpdateStaffRequest` | 204 |
| `POST /api/v1/staff/members/{id}/end-employment` | `MANAGE_STAFF` | `EndEmploymentRequest` | 204 |
| `POST /api/v1/staff/members/{id}/reinstate` | `MANAGE_STAFF` | `ReinstateStaffRequest` | 204 |
| `GET /api/v1/staff/kitchen-checks` | `MANAGE_STAFF` | — | `StaffKitchenCheckView[]` (the V150 backfill guesses) |
| `PUT /api/v1/staff/members/{id}/kitchen` | `MANAGE_STAFF` | `{kitchenId: uuid}` | 204 |
| `POST /api/v1/staff/kitchen-checks/confirm` | `MANAGE_STAFF` | `{staffIds: uuid[] @NotEmpty}` | 204 |
| `GET /api/v1/staff/members/{id}/pan` | `MANAGE_STAFF` | — | `{pan: "…"}` (audited reveal) |
| `GET /api/v1/staff/profiles/{id}` | `MANAGE_STAFF_SCHEDULE` | — | `StaffProfileDetailView` |
| `PUT /api/v1/staff/profiles/{id}/template` | `MANAGE_STAFF_SCHEDULE` | `SetScheduleTemplateRequest` | 204 |
| `PUT /api/v1/staff/profiles/{id}/exceptions` | `MANAGE_STAFF_SCHEDULE` | `SetScheduleExceptionRequest` | 204 |
| `POST /api/v1/staff/profiles/{id}/exceptions/swap` | `MANAGE_STAFF_SCHEDULE` | `SwapShiftRequest` | 204 |
| `DELETE /api/v1/staff/profiles/{id}/exceptions/{exceptionId}` | `MANAGE_STAFF_SCHEDULE` | — | 204 |
| `GET /api/v1/staff/schedule/week?weekStart=` | `MANAGE_STAFF_SCHEDULE` | — | `WeekScheduleView` |
| `GET /api/v1/staff/schedule/me` | `VIEW_OWN_SHIFTS` | — | `StaffProfileDetailView` |

`HireStaffRequest`:
```jsonc
{
  "existingUserId": null,               // uuid|null — attach to an existing account
  "fullName": "…",                      // REQUIRED non-blank ≤200
  "phone": "+919876543210",             // nullable, must match ^\+[1-9][0-9]{7,14}$ else KMS-400003
  "email": "a@b.c",                     // nullable, @Email
  "jobTitle": "COOK",                   // REQUIRED, enum below
  "jobTitleOther": "…",                 // ≤100, for jobTitle=OTHER
  "employmentType": "FULL_TIME",        // REQUIRED: FULL_TIME|PART_TIME|CONTRACT
  "dateOfJoining": "2026-06-01",        // REQUIRED
  "dateOfBirth": "1990-01-01",          // nullable, @Past
  "address": "…",                       // ≤500
  "emergencyContactName": "…",          // ≤200
  "emergencyContactRelationship": "…",  // ≤100
  "emergencyContactPhone": "+91…",      // E.164
  "pan": "ABCDE1234F",                  // nullable, ^[A-Za-z]{5}[0-9]{4}[A-Za-z]$
  "systemAccess": "KITCHEN_STAFF",      // nullable: TEMPLE_ADMIN|KITCHEN_MANAGER|KITCHEN_STAFF; null = no sign-in
  "monthlySalary": 18000,               // nullable, >0, 10.2 digits
  "acknowledgedBanCheckId": null,       // uuid|null
  "aadhaar": {…},                       // nested AadhaarIdentity, nullable
  "kitchenId": "uuid",                  // Epic 12 — REQUIRED in practice; missing ⇒ KMS-400184 STAFF_NEEDS_A_KITCHEN
  "notes": "…"                          // ≤2000
}
```
`UpdateStaffRequest` is the same minus `existingUserId`, `acknowledgedBanCheckId`, `aadhaar`, and with `pan` allowing empty string. Errors: `KMS-400057 PERSON_ALREADY_EMPLOYED`, `KMS-400088 STAFF_ACCESS_NEEDS_CONTACT`, `KMS-400031 NO_STAFF_RECORD`, `KMS-400085 EMPLOYMENT_ALREADY_ENDED`, `KMS-400135 EMPLOYMENT_NOT_ENDED`, `KMS-400136 EMPLOYMENT_RECORD_ON_FILE`, `KMS-400100/101` for bans.

Enums: `JobTitle` = `TEMPLE_ADMINISTRATOR, KITCHEN_MANAGER, HEAD_COOK, COOK, ASSISTANT_COOK, SWEET_MAKER, PRASADAM_SERVER, STORE_MANAGER, STOREKEEPER, HOUSEKEEPING, DISHWASHER, DRIVER, SECURITY, OFFICE_ASSISTANT, ACCOUNTANT, OTHER, UNRECORDED` (UNRECORDED not choosable). `EmploymentType` = `FULL_TIME, PART_TIME, CONTRACT`. `SystemAccess` = `TEMPLE_ADMIN, KITCHEN_MANAGER, KITCHEN_STAFF`. `EmploymentStatus` = `ACTIVE, RESIGNED, TERMINATED, CONTRACT_ENDED`.

`EndEmploymentRequest`: `{status: EmploymentStatus REQUIRED, lastWorkingDay: date REQUIRED, reason ≤1000, revokeSignIn: bool, ban: RaiseEmploymentBanRequest|null}`.
`ReinstateStaffRequest`: `{dateOfRejoining: date REQUIRED, systemAccess: SystemAccess|null, reason ≤1000}`.
`SetScheduleTemplateRequest`: `{days: [{dayOfWeek: int 1..7, working: bool, startTime, endTime}] @NotEmpty}`.
`SetScheduleExceptionRequest`: `{exceptionDate: date REQUIRED, working: bool, startTime, endTime, note ≤300}`. `CANNOT_SCHEDULE_OVER_LEAVE` = `KMS-400092`.
`SwapShiftRequest`: `{fromDate REQUIRED, toDate REQUIRED, note ≤300}`; same day both ends ⇒ `KMS-400093 SWAP_NEEDS_TWO_DAYS`.

`StaffProfileView`: `{id, userId, fullName, phone, email, jobTitle, jobTitleOther, jobTitleLabel, employmentType, dateOfJoining, dateOfBirth, address, emergencyContactName, emergencyContactRelationship, emergencyContactPhone, panLast4, systemAccess, kitchenId, kitchenName, kitchenNeedsCheck, employmentStatus, lastWorkingDay, endReason, notes, createdAt}`.

## `/api/v1/leave` — LeaveController

| Endpoint | Permission | Body | Response |
|---|---|---|---|
| `GET /api/v1/leave/mine` | `REQUEST_OWN_LEAVE` | — | `LeaveView[]` |
| `POST /api/v1/leave/mine` | `REQUEST_OWN_LEAVE` | `RequestLeaveRequest` | 201 `{id}` |
| `DELETE /api/v1/leave/mine/{id}` | `REQUEST_OWN_LEAVE` | — | 204 |
| `GET /api/v1/leave` | `APPROVE_LEAVE` | — | pending queue `LeaveView[]` |
| `POST /api/v1/leave` | `APPROVE_LEAVE` | `RecordLeaveRequest` | 201 `{id}` (record leave on someone's behalf, already decided) |
| `GET /api/v1/leave/{id}/impact` | `APPROVE_LEAVE` | — | `MealCrewView[]` — which meals go short |
| `POST /api/v1/leave/{id}/approve` | `APPROVE_LEAVE` | `DecideLeaveRequest` optional | 204 |
| `POST /api/v1/leave/{id}/decline` | `APPROVE_LEAVE` | optional | 204 |
| `POST /api/v1/leave/{id}/revoke` | `APPROVE_LEAVE` | optional | 204 |

`RequestLeaveRequest`: `{leaveType: "TIME_OFF"|"SICK"|"UNPAID" REQUIRED, fromDate REQUIRED, toDate REQUIRED, halfDay: bool, reason ≤500}`.
`RecordLeaveRequest`: same plus `staffProfileId: uuid REQUIRED` and `decisionNote ≤500`.
`DecideLeaveRequest`: `{note ≤500}`.
Errors: `KMS-400005 LEAVE_DATES_INVALID`, `KMS-400006 HALF_DAY_IS_ONE_DAY`, `KMS-400089 LEAVE_OVERLAPS_EXISTING`, `KMS-400090 LEAVE_ALREADY_DECIDED`, `KMS-400091 LEAVE_NOT_APPROVED`, `KMS-400151 LEAVE_ALREADY_STARTED`, `KMS-400026 NOT_YOUR_LEAVE_REQUEST`. `LeaveStatus` = `PENDING, APPROVED, DECLINED, REVOKED, WITHDRAWN`.

## `/api/v1/staff/...` — StaffPayController (all `MANAGE_STAFF`)

- `GET /api/v1/staff/members/{id}/pay` ⇒ `StaffPayView`
- `POST /api/v1/staff/members/{id}/payments` — `RecordStaffPaymentRequest {paidOn date REQ, amount BigDecimal REQ, mode "CHEQUE"|"CASH"|"PAYROLL" REQ, reference ≤100 (required unless CASH ⇒ KMS-400008), purpose "SALARY"|"SETTLEMENT" REQ, note ≤1000, deductions: PaymentDeductionRequest[]}` ⇒ 201 `{id}`. Errors `KMS-400007 AMOUNT_NOT_POSITIVE`, `KMS-400094 DEDUCTIONS_EXCEED_GROSS`, `KMS-400095 DEDUCTION_EXCEEDS_ADVANCE`.
- `POST /api/v1/staff/members/{id}/advances` — `RecordStaffAdvanceRequest {paidOn REQ, amount REQ, mode REQ (PAYROLL not allowed), reference ≤100, note ≤1000}` ⇒ 201 `{id}`
- `POST /api/v1/staff/members/{id}/payments/{paymentId}/void` ⇒ 204, `KMS-400097 STAFF_PAYMENT_NOT_VOIDABLE`
- `POST /api/v1/staff/members/{id}/advances/{advanceId}/void` ⇒ 204, `KMS-400096 ADVANCE_ALREADY_RECOVERED`

## Others

- `GET /api/v1/staff/members/{id}/conduct-notes` and `POST …/conduct-notes` — permission `MANAGE_STAFF_CONDUCT_NOTES`; body `{body: string ≤4000}` (empty ⇒ `KMS-400012 CONDUCT_NOTE_EMPTY`); 201 `{id}`.
- `GET /api/v1/workforce?from=&to=` — `MANAGE_MEAL_PLANS` ⇒ `WorkforceCount[] {date, staffIn, volunteers}` (`rostered()` = staffIn+volunteers).
- `GET /api/v1/crew-coverage?from=&to=` — `MANAGE_STAFF_SCHEDULE` ⇒ `DayCoverageView[] {date, staffIn, volunteers, state, shortBy, shortAt, shortAtReadyBy, shortAtRequired, shortAtRostered, shortAtMealId}`.

---

# 7. `today` package

`GET /api/v1/today` — permission `MANAGE_MEAL_PLANS`, no parameters (the date is the temple's own today, in its timezone). **Not** behind the planner-kitchen guard.

`TodayView`: `{date, calendar: {fastingToday, fastingTomorrow, todayName, tomorrowName, sunrise, tithi, paksa, masa, naksatra, ahead:{date,name,kind:"FAST"|"FESTIVAL",daysAway}}|null, meals: [{mealId, mealKind, eventName, readyBy, plates, recorded, awaitingRecord, occasionName, kitchenNames: string[], dishes: [{id, recipeName, targetYield, targetYieldUnit, actualServings, notMade, status}]}], platesToday, itemsBelowThreshold, itemsTracked, workforce: {staffIn, volunteers, meals: MealCrewView[]}, materialsCost: {estimatedTotal, withoutPrice, mealsCostedAsCooked, mealsCostedAsPlanned}, unrecordedMeals, approvals: {ingredientRequests, ingredientRequestsSoon, leaveRequests, leaveRequestsSoon}, deliveries: [{purchaseOrderId, poNumber, vendorName, neededBy, state:"AWAITED"|"INVOICE_OVERDUE"}], equipmentOverdue: int|null}`. Fields the reader may not see come back **null** (not zero).

---

# Direct answers to your specific questions

**1. How is a meal plan created for one day? Per-day, range, or series?**
There is **no "create a meal plan for a day" endpoint**. The day row (`meal_plan_days`, one per temple per date, carrying only `day_type`) is created implicitly by `MealPlanService.dayFor(date, dayType)` the first time a meal is saved on that date, and `day_type` is derived from the calendar (`FESTIVAL` > `WEEKEND` > `REGULAR`) — never sent by the client. So: **`POST /api/v1/meals`, one call per meal, per day.** For bulk there are two helpers: `POST /api/v1/meal-plans/reuse` (copy a 1–62 day stretch of plan onto another stretch; only ever adds, never overwrites a day that already has meals) and `POST /api/v1/meals/{id}/repeat` (every N weeks until a date, creating a `meal_series`). For three months of simulated operations, the natural recipe is: loop dates → for each of Breakfast/Lunch/Dinner, `POST /api/v1/meals`.

**2. Meal kinds — exact strings, who canonicalises.** Six seeded per temple on provisioning by `MealKindService.seedForCurrentTenant()` (called from `TenantProvisioningService`): `"Breakfast"`, `"Lunch"`, `"Dinner"`, `"Festival feast"`, `"Deity Offering"`, `"Event"` (sortOrder 10/20/30/35/40/50; default ready times 07:30 / 12:00 / 19:30 / none / none / none; `Event` has `isEvent=true`, `Festival feast` has `needsOccasion=true`). Stored in `meal_kinds.name` verbatim; only `.trim()` is applied on create/update. Uniqueness is `lower(name)` per tenant. **Always reference a kind by `mealKindId`** — `meals.meal_kind_id` is an FK and nothing matches by name any more.

**3. Adding a dish, servings, recipe link.** A dish is an element of the meal's `dishes[]` array on `POST /api/v1/meals` (adds to the meal, creating it if needed) or `PUT /api/v1/meals/{id}` (whole-list replace). Fields: `recipeId` (FK to `recipes`), `targetYield` (BigDecimal >0, ≤50000, **in the recipe's own yield unit** — `meal_dishes.target_yield`, renamed from `target_servings` by V69; the unit comes back as `targetYieldUnit`), `kitchenId` (which of the meal's kitchens cooks it), and `id` only when editing an existing dish. Row lands in `meal_dishes` with `status='PLANNED'`. Note: the *meal's* plate count comes from `adults/children/seniors`, not from the dishes.

**4. Marking cooked and recording actuals.**
- `POST /api/v1/meals/{id}/record` — `{note, dishes:[{dishId, actualServings, consumedQuantity, notMade}]}`. `actualServings` = what was **cooked** (the figure stock is drawn against, >0, ≤100000); `consumedQuantity` = what actually **went out** (nullable, 0..actualServings); `notMade: true` = never cooked, dish becomes CANCELLED with 0 and draws nothing. Every still-PLANNED dish must be named. This is what sets `meals.recorded_at` and each dish to `COOKED`, i.e. "the plan is cooked". There is no separate "mark as cooked" endpoint.
- **Leftovers** are not a field: leftover = cooked − consumed. **Short** = planned (`targetYield`) vs cooked (`actualServings`).
- `POST /api/v1/meals/{id}/correct` — permission `CORRECT_RECORDED_MEAL` — same dish shape plus a required `note`; restates the whole meal, reverses and re-draws stock, keeps the first figures as `originalActualServings`/`originalConsumedQuantity`. One correction only (`KMS-400137`).

**5. Attaching an event / festival, delivered vs collected.**
- A **festival** is attached as `occasionName` on the meal (`meals.occasion_name`, free text ≤200). It is auto-derived from the calendar for any day whose `dayType` resolves to `FESTIVAL`; for a kind with `needsOccasion=true` (the seeded `"Festival feast"`) the client may choose one and it is **required** — nothing to name ⇒ `KMS-400001` with `field:"occasionName"`.
- A **temple/outside event** is a meal whose kind has `isEvent=true` (the seeded `"Event"` kind). Then `eventName` is required (`KMS-400075`) and is part of the meal's identity. `isOutside=false` = in-house temple event; `isOutside=true` = food leaves the temple, and `contactName` + `contactPhone` become required (`KMS-400076`).
- **Delivered vs collected** is the `handover` enum: **`"PICKUP"` = collected** (stops at contact name + phone), **`"DELIVERY"` = delivered** (also requires `deliveryAddress` and `guestsEatAt`, else `KMS-400077`; optional `deliverySubLocation`, `deliveryPlaceId`, `deliveryLatitude/Longitude`, `travelMinutes` 1..600 with `travelMinutesManual` deciding stored source `MANUAL` vs `ESTIMATED`). Column `meals.handover TEXT CHECK IN ('PICKUP','DELIVERY')`. Delivered events feed `GET /api/v1/meals/{id}/travel-estimate` and `GET /api/v1/meal-plans/outside-commitments`.

**6. Festivals seeded, and listing them for a range.** Yes — 13 COMPUTED occasions per temple, seeded at provisioning from `OccasionService.SEEDS` (list above), `seeded=true`, each with a `matchText` substring-matched against the calendar engine's festival texts and a `defaultServings`. The astronomical day rows are *not* seeded: they are computed nightly per temple over a 550-day horizon into `calendar_days.festivals` (JSONB `[{text, priority}]`). To list festivals for a range: `GET /api/v1/occasions/resolved?from=&to=` (named occasions on concrete dates) or `GET /api/v1/calendar?from=&to=` (raw per-day festival texts, Ekadashi flags, sunrise/sunset), the latter capped at 550 days.

**7. Shifts — create, sign up, required vs filled.**
- For a meal: **you cannot use `POST /api/v1/shifts`.** Send `volunteerShift` inside `SaveMealRequest`/`UpdateMealRequest` — `{title, description, startTime, endTime, location, capacity, reminderOffsetsMinutes[]}` with **no date and no mealId**; `ShiftService.saveForMeal` takes the date from the meal row and links `shifts.meal_id`, all in the meal's transaction (nothing saved unless the meal saves). Second live shift for one meal ⇒ `KMS-400152 MEAL_ALREADY_HAS_SHIFT`.
- Standalone: `POST /api/v1/shifts` with `CreateShiftRequest` (has `shiftDate`, no meal fields) ⇒ 201 `{id}`.
- Volunteer signs up: `POST /api/v1/shifts/{id}/signup` with **no body**, permission `SIGN_UP_FOR_SHIFTS`, acting on the caller ⇒ 201 `{signupId, overlapWarning}`. Full shift ⇒ `KMS-400061`, then `POST /api/v1/shifts/{id}/waitlist`.
- Required vs filled: `capacity` vs `signedUpCount` (+ `waitlistCount`) on `ShiftView`/`AvailableShiftView`; there is no stored filled count.
- Attendance: `POST /api/v1/shifts/{id}/attendance` once for the whole roster (`MANAGE_VOLUNTEER_SHIFTS`), then `PUT /api/v1/shifts/{id}/attendance/{userId}` per person for corrections (`CORRECT_RECORDED_ATTENDANCE`). Attendance is tri-state: `true` / `false` / unmarked (null).

**8. Sharing a meal between kitchens (commit 596ce9a, V150–V152).**
A meal has **sections**, one row per kitchen in `meal_kitchens (meal_id, kitchen_id, crew_required, card_version, card_fingerprint)` with `UNIQUE (meal_id, kitchen_id)`. On the wire this is `kitchens: [{kitchenId, crewRequired}]` in `SaveMealRequest`/`UpdateMealRequest` (`MealKitchenDraft`: `kitchenId` required `@NotNull`, `crewRequired` Integer ≥0 where **0 and null both mean "nobody has said"**), and comes back as `ServedMeal.kitchens: [{kitchenId, kitchenName, isMain, crewRequired}]`, ordered for the viewer (own kitchen first, else main, then Settings order). Each dish carries `kitchenId`, enforced by the composite FK `meal_dishes (meal_id, kitchen_id) → meal_kitchens (meal_id, kitchen_id)`: a dish can only sit under a kitchen already on its meal. Rules:
- `kitchens` omitted/null on create ⇒ one section in the saver's default planning kitchen; on update ⇒ leave sections as they are.
- `kitchens: []` ⇒ `KMS-400180 MEAL_NEEDS_A_KITCHEN`.
- a kitchen that does not use the meal planner ⇒ `KMS-400181 KITCHEN_DOES_NOT_PLAN_MEALS`; unknown ⇒ `KMS-400108`.
- a dish naming a kitchen not on the meal, or omitting `kitchenId` when the meal has 2+ kitchens ⇒ `KMS-400182 DISH_KITCHEN_NOT_ON_MEAL`.
- `meals.crew_required` was **dropped** by V151: People needed is per kitchen and `ServedMeal.crewRequired` is the read-only sum.
- Each kitchen prints its own job card (`POST /api/v1/job-cards?mealId=&kitchenId=`) off one shared `meals.card_number`; `card_version` moved to `meal_kitchens` in V152 (`KMS-400186`, `KMS-400187`).
- Every staff member now has `staff_profiles.kitchen_id` (NOT NULL) plus `kitchen_needs_check` for backfilled guesses; `KMS-400184 STAFF_NEEDS_A_KITCHEN`, `KMS-400185 KITCHEN_HAS_STAFF` (cannot archive), and the planner gate `KMS-400183` described at the top.

## Key files
- `/backend/src/main/java/org/iskcon/kms/meal/` — `MealController.java`, `MealPlanController.java`, `MealKindController.java`, `MealCrewController.java`, `SaveMealRequest.java`, `UpdateMealRequest.java`, `RecordMealRequest.java`, `CorrectMealRequest.java`, `CancelMealRequest.java`, `RepeatEventRequest.java`, `ReusePlanRequest.java`, `MealKitchenDraft.java`, `ServedMeal.java`, `MealDishView.java`, `MealPlanService.java` (2002 lines, `create` at 489, `update` at 570, validation helpers 1199–1320), `ServedMealService.java` (`record` at 223, `correct` at 326, figure validation 605–660)
- `/backend/src/main/java/org/iskcon/kms/calendar/CalendarController.java`, `CalendarService.java`, `CalendarDayView.java`
- `/backend/src/main/java/org/iskcon/kms/occasion/OccasionController.java`, `OccasionService.java` (SEEDS at line 241)
- `/backend/src/main/java/org/iskcon/kms/shift/ShiftController.java`, `VolunteerShiftController.java`, `ShiftService.java` (`saveForMeal` at 378), `SignupService.java`, `MealShiftDraft.java`
- `/backend/src/main/java/org/iskcon/kms/kitchen/KitchenController.java`, `KitchenOrder.java`
- `/backend/src/main/java/org/iskcon/kms/staff/` — `StaffScheduleController.java`, `LeaveController.java`, `StaffPayController.java`, `StaffConductNoteController.java`, `CrewCoverageController.java`, `WorkforceController.java`
- `/backend/src/main/java/org/iskcon/kms/today/TodayController.java`, `TodayView.java`
- `/backend/src/main/java/org/iskcon/kms/auth/PlannerKitchenGuard.java`, `PlannerKitchenGuardConfiguration.java`
- `/backend/src/main/java/org/iskcon/kms/error/ErrorCode.java` (all KMS codes with their HTTP status)
- Migrations in `/backend/src/main/resources/db/migration/`: `V19__calendar_days.sql`, `V20__occasions.sql`, `V21__calendar_overrides.sql`, `V22__meal_plans.sql`, `V34__shifts.sql`, `V48__meal_kinds_and_ready_by.sql`, `V64`, `V67`, `V69`, `V71`, `V88`, `V92`, `V95`, `V106`, `V107`, `V127`, `V136__a_meal_is_a_row_of_its_own.sql`, `V137`, `V149__meal_series.sql`, `V150__which_kitchen_is_cooking.sql`, `V151__crew_is_per_kitchen.sql`, `V152__card_version_is_per_kitchen.sql`
