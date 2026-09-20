# The API as the seed scripts have to call it

Working reference for whoever writes a phase script in `tools/seed/`. Read from the controllers,
their request records and the migrations on 2026-09-19, against the tree at 7a5ec86. Not a
design document and not a promise — **if a call refuses, believe the API and fix this file.**

Every path is literal. The temple is never a parameter: it comes from the bearer token through
Postgres row-level security. Errors come back as
`{code, message, action, fieldErrors:[{field,message}]}`; bean-validation failures are
`KMS-400001 VALIDATION_FAILED`.

The other two slices are written up in full beside this file and are not repeated here:

- `tools/seed/reference/api-meals-calendar-shifts.md` — meals, meal kinds, the calendar, occasions,
  shifts, kitchens, staff, today.
- `tools/seed/reference/api-procurement-donations.md` — shopping list, purchase orders, receiving,
  invoices, payments, ingredient requests, donations, wish list, equipment, attachments.

**Read the short list at the end of this file first.** It holds the handful of facts that will
otherwise cost a phase a rewrite.

---

## Who to sign in as

From `auth/RolePermissions.java`:

- **TEMPLE_ADMIN** — MANAGE_RECIPES, MANAGE_INVENTORY, MANAGE_VENDORS, MANAGE_DIETARY_POLICY,
  MERGE_INGREDIENTS, APPROVE_LARGE_STOCK_ADJUSTMENT, MANAGE_PURCHASE_ORDERS, RECEIVE_DELIVERIES.
- **KITCHEN_MANAGER / KITCHEN_STAFF** — the same minus dietary policy, merge and large-adjustment
  approval.
- **SUPER_ADMIN** — holds `MANAGE_RECIPE_LIBRARY` and **none** of the temple permissions.

So: ingredients, recipes, vendors and stock are seeded as the **Temple Admin**; only a Temple
Admin can set the Ekadashi flag, merge ingredients, or make an adjustment larger than 20% of what
is on hand. Loading the recipe library is the **Super Admin** and nobody else.

---

## 1. Ingredients, stock, vendors, recipes, library

### Units — one vocabulary everywhere

`ingredient/Unit.java`: **`KG`, `GM`, `L`, `ML`, `PIECES`**. Send the enum *name*, not the label.
Families: MASS (KG=1000, GM=1), VOLUME (L=1000, ML=1), COUNT (PIECES=1). Every unit column has a
CHECK against exactly these five. `SERVINGS` was removed in V80.

### Ingredient category is free text, not an enum

`ingredients.category` is `TEXT NOT NULL`, tenant-curated, nothing seeded. The only fixed list is
the importer's keyword map `library/IngredientCategories.java`, which can emit exactly:
`Vegetables`, `Spices`, `Dairy`, `Oils & fats`, `Sweeteners`, `Pulses`, `Grains`, `Nuts & seeds`,
`Fruit`, `Other`. **Use those ten strings when seeding**, so hand-made ingredients and
import-made ones agree.

### Ingredients — `/api/v1/ingredients`

| call | permission | notes |
|---|---|---|
| `GET /api/v1/ingredients` | MANAGE_RECIPES | `IngredientView[]` by name |
| `GET /api/v1/ingredients/search?q=` | MANAGE_RECIPES | prefix match on name + aliases, LIMIT 20 |
| `GET /api/v1/ingredients/{id}` | MANAGE_RECIPES | 404 `KMS-400030` |
| `POST /api/v1/ingredients` | MANAGE_RECIPES | 201 `{id}` |
| `PUT /api/v1/ingredients/{id}` | MANAGE_RECIPES | 204, full replacement |
| `PATCH /api/v1/ingredients/{id}/ekadashi-flag` | MANAGE_DIETARY_POLICY | 204 |
| `DELETE /api/v1/ingredients/{id}` | MANAGE_RECIPES | `KMS-400035` if in use |
| `POST /api/v1/ingredients/{id}/pack-sizes` | MANAGE_RECIPES | 201 `{id}` — the pack size id |
| `DELETE /api/v1/ingredients/{id}/pack-sizes/{packSizeId}` | MANAGE_RECIPES | `KMS-400159` if in use |

`IngredientView`: `id, name, category, unit, ekadashiProhibited, supply, libraryDerived,
aliases[], createdAt, packSizes[], marketRate, marketRateOn, marketRateSource`
(`STOCK_TAKE`|`INVOICE`|`MANUAL`).

**`CreateIngredientRequest`** — `name` (req, ≤200), `category` (req, ≤100, free text),
`unit` (req, one of the five), `ekadashiProhibited` (boolean primitive; `true` needs
MANAGE_DIETARY_POLICY else **403 KMS-400021**), `supply` (boolean primitive — LPG, leaf plates,
soap; excluded from recipes), `aliases` (String[], each ≤200), `confirmDifferent` (Boolean).

Two refusals to expect and handle:
- **`KMS-400034 INGREDIENT_ALREADY_EXISTS`** (409) — exact case-insensitive name collision;
  details carry `existingIngredientId`. On a re-run this is the normal answer, not a failure.
- **`KMS-400156 INGREDIENT_LOOKS_LIKE_EXISTING`** (409) — the lookalike guard. `fieldErrors`
  carry `existingIngredientId` and `existingIngredientName`. Retry with `"confirmDifferent": true`
  **only when it really is a different thing**; otherwise reuse the id it named.

**`UpdateIngredientRequest`** — same fields, with two traps: `supply` is a **primitive**, so
omitting it silently sets it false — always send it. `ekadashiProhibited` is a **boxed Boolean**
where null means leave alone. Any real modification clears `library_derived`. Changing the unit
across families while pack sizes exist → `KMS-400160`.

**Pack sizes.** `AddPackSizeRequest { name String|null ≤40, quantity BigDecimal req >0, unit req }`.
Max 8 per ingredient (`KMS-400158`); same base size twice → `KMS-400157`; a unit from another
family → `KMS-400013`. `PackSizeView` gives `label` as `"250 gm"` or `"Bag = 25 Kg"`.

**Market rate.** `PUT /api/v1/ingredients/{id}/market-rate` (MANAGE_INVENTORY),
`{ "marketRate": BigDecimal }` — rupees per **one canonical unit**. Null, zero or negative →
`KMS-400161 STOCK_VALUE_REQUIRED`. Also `GET …/stock-value-suggestion` →
`{pricePerUnit, source}` where source is `PREFERRED_VENDOR`, `MARKET_RATE` or null.

### Opening stock — the one call to use

There is no endpoint that *sets* a stock level, and stock does **not** have to arrive by delivery.

> **`POST /api/v1/inventory/items`** (MANAGE_INVENTORY) → 201 `{id}` (the inventory item id)
> ```json
> { "ingredientId": "…", "storageLocation": "Dry store", "reorderThreshold": 25,
>   "notes": null,
>   "openingCount": { "quantity": 120, "unit": "KG", "pricePerUnit": 62.50 } }
> ```

`pricePerUnit` is rupees per the ingredient's **canonical** unit whatever unit you counted in, and
it is **required and > 0** — an opening count always adds stock. It becomes the ingredient's
market rate with source `STOCK_TAKE`. The count runs through the adjustment code in the same
transaction (`batchId: null`, `reason: COUNT_CORRECTION`), so a refused count leaves no item
behind.

**The Temple-Admin requirement for a large adjustment is lifted here**, but only when the item was
created by this very request *and* the ingredient has never moved in this temple. A later top-up
uses `POST /api/v1/inventory/items/{id}/adjustments` and **does** enforce the 20% rule — run that
as the Temple Admin.

One item per ingredient: a second `POST` → `KMS-400040 INVENTORY_ITEM_ALREADY_EXISTS` (409). That
is what a re-run will hit.

### Adjustments — `POST /api/v1/inventory/items/{id}/adjustments`

201 `{id}` = the **stock movement** id. `AdjustStockRequest`:

- `batchId` — **null opens a new batch with what is on the shelf now** (the stock-take path);
  non-null corrects an existing batch.
- `quantity` — signed, never 0; must be positive when `batchId` is null.
- `unit` — must be in the ingredient's family.
- `reason` — `SPOILAGE` | `DAMAGE` | `COUNT_CORRECTION` | `WASTE` | `OTHER`. `note` (≤500) is
  **required when reason is OTHER**.
- `pricePerUnit` — **required and > 0 on any positive adjustment**, whatever the reason. Ignored
  when negative.

Refusals in the order checked: `KMS-400013` → `KMS-400001` → `KMS-400161` → `KMS-400030` →
`KMS-400041 STOCK_WOULD_GO_NEGATIVE` (409, details carry `available`) → `KMS-400025
ADJUSTMENT_REQUIRES_ADMIN` (403) when the change exceeds 20% of on hand.

### Reading stock

`GET /api/v1/inventory/items?location=&category=&expiringWithinDays=` → `StockItemView[]` with
`onHand`, `committed`, `available` — **all computed, never stored**. `GET …/items/{id}` adds
batches (FEFO) and the meals the stock is committed to. `GET /api/v1/inventory/items/low-stock`
for the threshold list.

`GET /api/v1/inventory/movements?ingredientId=&type=&referenceId=&limit=` is the ledger, newest
first. There is **no POST that creates a movement directly** — every movement comes from a named
action. `POST /api/v1/inventory/movements/{id}/compensate` reverses one, body
`{ "note": "…" }` required; `KMS-400039` if already corrected.

Enums: `MovementType` = `PO_RECEIPT`, `DONATION_IN_KIND`, `CONSUMPTION`, `ADJUSTMENT`, `ISSUE`,
`RETURN_TO_VENDOR`, `USED_BEYOND_RECORDED_STOCK`. `MovementReference` = `PURCHASE_ORDER`,
`MEAL_PLAN`, `DONATION`, `CORRECTION`, `INGREDIENT_REQUEST`.

### Cooking a recipe draws stock

`POST /api/v1/inventory/consumption/preview` writes nothing; `POST /api/v1/inventory/consumption`
commits. `ConsumeRequest { recipeId req, targetYield req >0 in the recipe's own yield unit,
mealPlanId|null, batchOverrides[]|null, note|null }`.

**A commit is never refused on stock grounds** — a shortfall is booked as a
`USED_BEYOND_RECORDED_STOCK` movement (which moves no stock) and reported back. That is the lever
for the brief's "cooked more than planned" and "not enough food" cases.

### Vendors — `/api/v1/vendors` (all MANAGE_VENDORS)

`POST /api/v1/vendors` → 201 `{id}`. `name` required, unique per temple (`KMS-400049` on a
duplicate — the normal re-run answer). **`phone` is optional but, if sent, must be E.164**
`^\+[1-9][0-9]{7,14}$` — a blank string fails, so send null rather than "". Also
`contactPerson`, `email`, `address`, `gstin` (≤30), `preferredLanguage` (≤10), `notes`,
`contractEndDate`.

`POST /api/v1/vendors/{id}/deactivate` needs a **reason** (`KMS-400011` without one);
`…/reactivate` does not.

**Price lists — use the bulk call.**
`POST /api/v1/vendors/{id}/supplies/bulk`, body `{ "rows": [ SetVendorSupplyRequest, … ] }`,
max 500, one transaction (any refusal refuses every row), price history source `ONBOARDING`.
The single `PUT /api/v1/vendors/{id}/supplies` writes one link with source `MANUAL`.

`SetVendorSupplyRequest { ingredientId req, lastPrice|null, leadTimeDays|null (0..365),
preferred boolean, packSizeId|null, pricePerPack|null }`, and the cross-field rules matter:

- `pricePerPack` without `packSizeId` → `KMS-400001`.
- a `packSizeId` belonging to another ingredient → `KMS-400162`.
- `packSizeId` **and** `lastPrice` but no `pricePerPack` → `KMS-400163`. With a pack, the price is
  typed per pack only.
- with a pack, the server **derives** `last_price = pricePerPack / pack.canonicalQuantity` —
  Bag = 25 Kg at ₹1500 becomes ₹60/Kg — and **ignores any `lastPrice` you send**.
- `leadTimeDays` **null ≠ 0**: null means unrecorded and readers assume a lead time; 0 means
  cash-and-carry.
- `preferred: true` clears every other vendor's preference for that ingredient in the same
  transaction. One preferred vendor per ingredient, enforced by a partial unique index.

Upsert on `(vendor_id, ingredient_id)`, so the bulk call is safely re-runnable.

### Recipes — `/api/v1/recipes` (all MANAGE_RECIPES)

Categories are already seeded per temple: `Beverages, Breakfast, Rice, Dal, Sabji, Roti, Sweets,
Snacks` plus `Ekadashi` (the only one with `fastingCompatible = true`).
`POST /api/v1/recipe-categories` adds more (`KMS-400038` on a duplicate).

`POST /api/v1/recipes` → 201 `{id}`. Required: `name` (≤300, unique among ACTIVE recipes),
`categoryId`, `baseYieldQty` > 0, `baseYieldUnit`, and a non-empty `ingredients` array of
`{ ingredientId, quantity > 0, unit, preparationNote|null ≤200 }`. `perHeadUnit` must be in the
same **family** as `baseYieldUnit` or the class validator refuses.

Refusals: `KMS-400036 RECIPE_ALREADY_EXISTS` (409), `KMS-400001` with `unknownIngredientIds`,
and **`KMS-400127 NOT_A_FOOD_INGREDIENT`** (409) if a line names an ingredient with
`supply = true` — so supplies must never reach a recipe line.

`PUT` is a full replacement including the lines; `POST …/archive` and `…/restore` for the soft
delete; `DELETE` refuses with `KMS-400102` once any meal plan has named the recipe.

### Importing from the library

Two calls, in this order:

1. `GET /api/v1/recipes/import/{masterRecipeId}/close-matches` — writes nothing, returns
   `[{ libraryName, note, existingIngredientId, existingIngredientName }]`, empty when there are
   none. **Always call this first**, or the import refuses.
2. `POST /api/v1/recipes/import/{masterRecipeId}` → 201
   `{ id, name, ingredientsCreated, categoryCreated }`. Body optional; when close matches exist
   it must carry `{ "decisions": [ { libraryName, useIngredientId|null, confirmDifferent } ] }`.
   Exactly two valid shapes per decision: an id with `confirmDifferent:false` ("use that one"),
   or null with `confirmDifferent:true` ("different ingredient"). Anything else is unanswered and
   the import refuses with `KMS-400156`, its `fieldErrors` carrying the whole list flattened as
   `closeMatches[i].libraryName` and so on.

`KMS-400103 RECIPE_ALREADY_ADDED` (409) is the re-run answer. Nothing is written on any refusal.

**The import creates ingredients, and it is the only place outside the ingredient form that
does.** `RecipeImportService.create` inserts with `library_derived = true`, name = the *base*
from `IngredientNameMatcher.split` (so "Cashew, halved" becomes `Cashew` and "halved" becomes the
recipe line's `preparation_note`), category from the keyword map falling back to `Other`, and
canonical unit = the unit the book's quantity parsed to — so an ingredient first met as "200 gm"
is catalogued in `GM`, not `KG`. **No market rate.** That is why phase 03 has to go back over
every ingredient afterwards.

### The library itself — `/api/v1/library/recipes`

Reading (`GET` list, `/states`, `/{id}`) needs MANAGE_RECIPES **or** MANAGE_RECIPE_LIBRARY.
Writing — `POST`, `PUT`, `DELETE`, and **`POST /api/v1/library/recipes/load`** — needs
`MANAGE_RECIPE_LIBRARY`, which only the Super Admin holds. The load reads the vendored book JSON
and is idempotent (upsert on state slug + recipe slug), returning
`{books, recipes, bare, withState, withStateAndCategory}`.

There is no import endpoint under `/api/v1/library`; the import is on the recipe controller.

---

## "Not bought" — where it actually lives, and what it does not do

There is **no persistent "not bought" flag on an ingredient.** `ingredients` has no such column,
and `ingredients.supply` means "not food" (LPG, leaf plates), which is a different question.

What exists is a **per-shopping-list decision**:

> `PATCH /api/v1/shopping-list/{ingredientId}` (MANAGE_PURCHASE_ORDERS)
> `{ "suggestedQty": BigDecimal|null, "included": false }`

That keeps water off *one* list. It is not the same as Rajeev's `not_bought`, which is a property
of the ingredient and should keep water off *every* list without anyone deciding again — see the
loader gap written up for the conductor on 2026-09-19.

**Consequence for seeding:** until that flag exists, phase 06 must `PATCH … included: false` for
water and anything like it on every shopping list it builds, or the temple orders water.

---

## The facts that will cost you a rewrite

Measured against the seed stack (:8091 on `kms_seed`) on 2026-09-19, not taken from the code.

**1. The calendar does not reach back to 27 June 2026.** `GET /api/v1/calendar/2026-06-27`
answers **204 No Content**; `2026-09-04` answers 200. The local `calendar_days` run
**2026-08-01 → 2028-03-03**. Nothing computes astronomy on request: `CalendarPrecomputeJob` fills a
**forward-rolling 550-day horizon** and there is no backfill. So for 27 June – 31 July there is no
Ekadashi flag, no festival text, `day_type` can never resolve to `FESTIVAL`, and
`GET /api/v1/occasions/resolved` returns nothing. The brief's "festivals on their real dates"
is not achievable in that stretch without a product change.

**2. Four named occasions resolve in the window, all from 28 August.** Measured:

```
Balarama Purnima             2026-08-28   500 servings
Sri Krsna Janmastami         2026-09-04  2000
Srila Prabhupada Appearance  2026-09-05   800
Radhastami                   2026-09-19   800
```

Plus, from `calendar_days` directly: Ekadashi on 9 and 24 Aug and 7 and 22 Sep; Vamana Dvadasi
23 Sep, Ananta Caturdasi 25 Sep, Visvarupa Mahotsava and Bhadra Purnima 26 Sep. A simulation
starting **1 August** is therefore richer in real festivals than one padding June and July with
ordinary days.

**3. This temple has five meal kinds, not the six the provisioning code seeds.** Measured:
`Breakfast(1)`, `Lunch(2)`, `Dinner(3)`, `Festival feast(4, needsOccasion)`, `Event(50, isEvent)`.
There is **no "Deity Offering"** and the sort orders are 1/2/3/4/50, not the 10/20/30/35/40/50 the
seeder writes — the temple predates the current list. **Read the kinds from
`GET /api/v1/meal-kinds` and match on name; never hardcode ids or assume the sixth exists.**

**4. A meal is referenced by `mealKindId`, never by name.** `meals.meal_kind_id` is a foreign key
since V136. The old name-matching is gone.

**5. The planner refuses anyone outside a planning kitchen.** `PlannerKitchenGuard` sits on
`/api/v1/meals`, `/api/v1/meal-plans`, `/api/v1/meal-crew` and `/api/v1/job-cards`: TEMPLE_ADMIN
always passes, anyone else needs an ACTIVE staff record naming an ACTIVE kitchen with
`uses_meal_planner = true`. Otherwise **403 KMS-400183**. Locally only *Main Kitchen* and *Health
Kitchen* plan meals, so the three requesting kitchens' staff cannot touch the planner — which is
the behaviour the brief wants, but it means phase 11's joint festival meals must be saved by a
user who is in a planning kitchen.

**6. There is no "create a meal plan for a day" endpoint.** The day row is created implicitly the
first time a meal is saved on that date, and `day_type` is derived from the calendar, never sent.
So: loop dates, and `POST /api/v1/meals` once per meal. `POST /api/v1/meal-plans/reuse` copies a
1–62 day stretch and only ever adds; `POST /api/v1/meals/{id}/repeat` makes a series.

**7. Nothing regenerates the shopping list.** `POST /shopping-list/regenerate` and
`POST /purchase-orders/generate` **do not exist** — tests assert their absence. The list is derived
on every `GET`.

**8. Every receipt, delivery and return needs a fresh `idempotencyKey`.** Reusing one returns the
earlier record instead of making a new one, keyed on `(po_id, idempotency_key)` and on
`<key>:<poId>` for `/deliveries`. A seeding script that derives the key from the state ledger gets
re-runnability for free; one that hardcodes it silently creates nothing on the second run.

**9. Leftovers and shortfalls are not fields.** `POST /api/v1/meals/{id}/record` takes
`actualServings` (what was **cooked**) and `consumedQuantity` (what **went out**). Leftover =
cooked − consumed. Short = planned `targetYield` vs cooked. `notMade: true` cancels a dish and
draws nothing. Every still-PLANNED dish must be named in the call, and a meal can be corrected
**once** (`KMS-400137`).

**10. Delivered versus collected is `handover`.** `"PICKUP"` = collected, needs contact name and
phone. `"DELIVERY"` = delivered, also needs `deliveryAddress` and `guestsEatAt` (`KMS-400077`).
Both only on a meal whose kind has `isEvent = true`, where `eventName` is required
(`KMS-400075`) and `isOutside = true` makes the contact fields required (`KMS-400076`).

**11. A volunteer shift for a meal is not created through `POST /api/v1/shifts`.** Send
`volunteerShift` inside the meal's own save request, with no date and no mealId — the date comes
from the meal and it saves in the meal's transaction. A second live shift on one meal is
`KMS-400152`. Signing up is `POST /api/v1/shifts/{id}/signup` with **no body**, as the volunteer.
Filled is `signedUpCount` against `capacity`; there is no stored filled count.

**12. `MANAGE_VENDOR_PAYMENTS` is Temple Admin only.** A Kitchen Manager can record an invoice but
gets 403 on `/payments`, `/void`, `/credit`, `/payables` and `payment-uploads`.

**13. Dates are the temple's, not the server's.** Arrivals, deliveries and the service "is it in
the future" checks all go through `TempleClock` in Asia/Kolkata.

**14. `/api/v1/staff` is not a route.** The roster is **`GET /api/v1/staff/register`**, and it
answers `{"current": [...], "former": [...]}` — not a bare list. The bare path returns
**404 KMS-400030**, whose message is "We couldn't find what you were looking for", which reads like
a missing temple rather than a missing endpoint and sent one script looking in the wrong place.
The other staff paths all sit under it: `/staff/members`, `/staff/profiles/{id}`,
`/staff/kitchen-checks`, `/staff/schedule/week`.

**15. A weekly shift template has a row for every day, and `dayOfWeek` is a number.**
`GET /api/v1/staff/profiles/{id}` returns `{"profile": {...}, "template": [...]}` where each slot is
`{dayOfWeek: 1-7 (ISO, 1 = Monday), working: bool, startTime, endTime}`. **Seven rows always, some
with `working: false`** — so counting rows tells you everybody works every day. Filter on `working`
first. The same call is where `kitchenNeedsCheck` shows up.

**16. The two staff endpoints disagree about what the id is called.**
`/api/v1/staff/register` calls it `id`; `/api/v1/staff/kitchen-checks` calls the same value
`staffId`. `POST /api/v1/staff/kitchen-checks/confirm` wants `{"staffIds": [...]}`.

**17. A vendor price list is upserted on (vendor, ingredient), so re-running is safe.**
`POST /api/v1/vendors/{id}/supplies/bulk` twice leaves one row per pair, not two. Measured: 135
rows after two runs of the same 135. It is one transaction, so a single bad row refuses the whole
list — which is what makes a price list either wholly right or wholly absent.

**18. With a pack, send only `pricePerPack` and let the server divide.** Verified end to end:
Rice at ₹1450 for a `Bag = 25 Kg` came back as `lastPrice: 58.0/KG`; Ghee at ₹9750 for a
`Tin = 15 Kg` came back as `650.0/KG`. Sending `lastPrice` as well is refused with
**KMS-400163**, and sending `pricePerPack` without a `packSizeId` is a bean-validation failure.

**19. `GROUPS` is a bash built-in array, and assigning to it silently does nothing.** Not an API
fact, but it cost a debugging round here and will cost the next person one too. A shell variable
called `GROUPS` reads back as the user's first group id — `20`, the staff group, on this machine —
so every comparison against it failed and `run-all.sh` ran to the end having executed no phase at
all, **exiting 0**. Renamed to `WANTED`. The lesson generalises past the name: a runner that
matches nothing should say so and exit non-zero, which it now does.

**20. A phase that reports a problem exits 1, and that is not a failure.** Two ingredients with no
vendor, a unit the recipes and the catalogue disagree about — these are findings. `run-all.sh`
records them, carries on, and lists them at the end; only exit 2 or more stops the run. Halting on
a finding means the operator fixes one thing, re-runs, and waits to discover the next.

**21. The ordering rounds must all finish before any cooking is recorded.** Recording a meal draws
stock, and the drawdown is never refused — a kitchen that has already cooked the food cannot
un-cook it, so a shortfall is booked as `USED_BEYOND_RECORDED_STOCK` instead. Measured with the
cooking after a single ordering round: **1,574 lines cooked beyond recorded stock against 681
drawn from it.** Adding rounds afterwards does not repair it; the movements are already written
and the table is append-only.
