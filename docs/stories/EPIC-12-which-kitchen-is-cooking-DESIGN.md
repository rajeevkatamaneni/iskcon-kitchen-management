# EPIC 12 — Which kitchen is cooking

**Status: BUILT 2026-09-19 in an unmerged worktree, awaiting Rajeev's test. Not committed, not deployed.**
Rajeev approved the build on 2026-09-19 with amendments, recorded in §8. Where §8 and the sections above
disagree, §8 wins; the rest is kept as the record of the design. Test: [UAT-093](../uat/UAT-093-kitchens-in-the-meal-planner.md).
**Written:** 2026-08-30. Shape settled by Rajeev the same day (Option B).
**Depends on:** E10-S2 (the `kitchens` table). **Blocks:** nothing.
**Labels:** `epic:planner`

---

## 1. Why

> we have to add a mandatory field for which kitchen is planning this meal and also display it clearly in
> a prominent spot so the user seeing it knows, Ohh, Main kitchen is making the meal and Sweets Kitchen is
> making the sweets.

Today a meal belongs to the temple and to nobody in particular. Once a temple has five kitchens, "Lunch
on Friday" stops being a complete sentence — somebody has to cook it, and the plan does not say who.

This also closes the loop E10 opened. E10 gives a kitchen a door to the store; this gives it a name on
the food it is making.

---

## 2. The shape — Option B, chosen 2026-08-30

Rajeev's own sentence decides it: *"Main kitchen is making the meal and Sweets Kitchen is making the
sweets."* That is **one lunch whose preparations come from two kitchens**, not two lunches.

**The kitchen is a property of the preparation, defaulted from a meal-level picker.**

- The composer asks which kitchen in step 1, beside "Ready by".
- That answer is copied onto every preparation as the meal is saved, exactly as `readyBy`, the head
  count and `crewRequired` already are.
- A single preparation can be pointed at a different kitchen, which is what makes Rajeev's sentence work.
- The meal's header then reads the distinct kitchens across its dishes: **"Main kitchen · Sweets kitchen"**.

### Why not Option A (the kitchen identifies the meal)

Because a meal's identity is `(tenant_id, plan_date, meal_kind)` and a great deal rests on it:

- `meal_services_one_per_meal` is `UNIQUE (tenant_id, plan_date, meal_kind)` (`V64:71`).
- `/planner/[date]/[kind]` addresses a meal by date and kind alone
  (`app/planner/[date]/[kind]/page.tsx:59`).
- The week grid, month grid and Today screen all use `mealKind` as their React key
  (`planner/page.tsx:508`, `:616`, `today/page.tsx:260`) — unique only because that index says so.

Making the kitchen part of the identity means two Lunches on one date, and every one of those breaks.
Option B changes no identity, no index, no route and no key.

### Where the column goes, and why it is not a question

On **`meal_plans`** — the dish row — denormalised onto every row of a meal, beside `ready_by`, `adults`,
`children`, `seniors` and `crew_required`. This is settled precedent, not a preference. `V67:10-24`:

> a meal_services row exists only once a card has been printed or the meal recorded, so a fact the
> planner sets weeks earlier has nowhere else to live.
>
> If meal_services is ever promoted to exist from planning time, crew_required moves there with the other
> four, as one migration. Moving one of the five on its own would leave a meal's facts in two places,
> which is how two screens come to disagree about the same lunch.

`kitchen_id` is the sixth member of that set and moves with them if that day ever comes.

### Only kitchens that opted in may be chosen

E10 D5 says a kitchen either plans its meals here or draws ingredients from the store, never both. So the
picker offers kitchens with `uses_meal_planner = true`, and choosing one is what "planning its meals here"
means in practice. A temple that has not turned the flag on for any kitchen sees only its main kitchen,
which the provisioning seed creates with the flag set.

---

## 3. The migration

One migration, in this order, and the order is the whole safety argument:

1. `ALTER TABLE meal_plans ADD COLUMN kitchen_id UUID REFERENCES kitchens(id) ON DELETE RESTRICT;` —
   **nullable at first**.
2. A per-tenant `DO $$ … $$` loop that sets `app.tenant_id`, ensures that temple has a main kitchen, and
   `UPDATE meal_plans SET kitchen_id = <it> WHERE kitchen_id IS NULL`.
3. Outside the loop: `ALTER TABLE meal_plans ALTER COLUMN kitchen_id SET NOT NULL;`

**Step 2 must loop per tenant or it silently does nothing.** Migrations run subject to
`FORCE ROW LEVEL SECURITY`, and `V48:57-66` records what that costs when forgotten:

> A plain cross-tenant INSERT is refused outright, and a plain cross-tenant UPDATE is worse: it silently
> matches nothing and reports success. (Neither shows up under Testcontainers, where migrations run as a
> superuser and superusers bypass RLS entirely. This one only appeared on a real deployment.)

**Step 3 is the tripwire.** `ALTER TABLE` is DDL and is not filtered by RLS, so a tenant the loop somehow
missed fails the deploy instead of shipping a half-filled column. `V48:106-110` uses exactly this pattern
for `ready_by` and is the template to copy.

Volume is small — `meal_plans` is one row per dish, roughly 15–25 a day, so hundreds to low thousands per
tenant. The risk here is entirely correctness, not time.

### Two things outside the migration, or new temples ship broken

- **`tenant/TenantProvisioningService.java:81`** already calls `mealKindService.seedForCurrentTenant()`.
  A `kitchenService.seedForCurrentTenant()` goes beside it, creating the temple's main kitchen with
  `is_main` and `uses_meal_planner` set. Without it a brand-new temple cannot plan its first meal.
- **`docs/reset-temple-data.sql`** keeps a `v_keep` list of tenant-owned tables that survive an
  operational wipe. `kitchens` is configuration, not operational data, and belongs in it — otherwise a
  reset deletes the kitchens and the `meal_plans` FK blocks the wipe.

---

## 4. Where it shows

Rajeev asked for "a prominent spot". Six surfaces, and they do not all have room for the same thing.

| Surface | Where the kitchen goes |
|---|---|
| **Meal block header** (`MealServices.tsx:242-303`) | The distinct kitchens of the meal's dishes, on the fact line under the meal name — the same line that already carries head count · servings · occasion · venue. Where a meal has one kitchen it reads as one name; where it has two it reads "Main kitchen · Sweets kitchen", which is Rajeev's sentence exactly. |
| **Dish rows** (`MealServices.tsx:305-360`) | Only where a dish differs from the meal's default, so the common case stays quiet and the exception is visible. |
| **Edit screen** (`app/planner/[date]/[kind]/page.tsx:133`) | The `who` line, which already reads date · time · plates. |
| **Week grid** (`planner/page.tsx:508-523`) | Too tight for a name at `text-xs` across seven columns. The kitchen joins the existing `2 preparations · 133 plates` line only when a day involves more than one kitchen. |
| **Month grid** (`planner/page.tsx:615-622`) | Nothing. It is one truncated line per meal and already caps at three. |
| **Today** (`app/today/page.tsx:270-290`) | The `text-xs text-ink-muted` sub-line that already takes `· occasion`. |
| **Job card** (`JobCardTemplate.java:219-236`) | The top-right identity line, joining `Dinner · Friday 21 August 2026`. A cook picking a sheet off the printer needs to know it is theirs. |

**The job card becomes per-kitchen.** A sheet for the sweets kitchen listing the main kitchen's rice is
worse than no sheet. Where a meal spans kitchens, one card is issued per kitchen, each carrying only that
kitchen's preparations, and `card_number` is issued per card as it is today. This is the one piece of E12
with real depth to it, and it gets its own story.

---

## 5. The code that changes

| Layer | File | Change |
|---|---|---|
| Schema | new migration | `meal_plans.kitchen_id`, backfill loop, `SET NOT NULL` |
| Provisioning | `tenant/TenantProvisioningService.java:81` | seed the main kitchen |
| Ops | `docs/reset-temple-data.sql:56` | `kitchens` into `v_keep` |
| DTO in | `meal/CreateMealPlanRequest.java`, `UpdateMealPlanRequest.java` | `@NotNull UUID kitchenId` |
| Service | `meal/MealPlanService.java:200` (INSERT), `:264` (UPDATE), `:337` (validation → `KITCHEN_REQUIRED`), `:493` (SELECT), `:508` (RowMapper) | |
| **Trap** | `meal/MealPlanService.java:184-190` | `duplicateWeek` rebuilds a `CreateMealPlanRequest` **positionally** from a `MealPlanView`. Adding a component breaks this call site at compile time — good — and it must carry the kitchen across, or duplicating a week silently reassigns every meal. |
| DTO out | `meal/MealPlanView.java`, `meal/ServedMeal.java` | `kitchenId` + `kitchenName` |
| Frontend types | `lib/api.ts` `MealServiceView`, `MealPlanView`, `TodayMeal` | |
| Form | `components/planner/MealComposer.tsx:509` (step 1 picker), `:349` (blocking rule + hint), `:386` (`mealFacts()`), `:647` (per-dish override) | |
| Display | the six surfaces in §4 | |
| Documents | `document/JobCardService.java`, `JobCardTemplate.java` | per-kitchen cards |

---

## 6. The stories

| Story | What | Status (2026-09-19) |
|---|---|---|
| **E12-S1** | `meal_plans.kitchen_id` — migration, backfill, `SET NOT NULL`, provisioning seed, reset keep-list | Built, not deployed (T-350, V150). Built as a section per kitchen on each meal, with `meal_dishes.kitchen_id`, since the D-27 rebuild replaced `meal_plans` |
| **E12-S2** | The planner asks, and refuses to save without an answer — DTOs, service, validation, `duplicateWeek` | Being built (T-354, V151) |
| **E12-S3** | The composer's kitchen picker, and the per-dish override | Built, not deployed (T-351). Built as the approved mock's sections instead of a picker plus a per-dish override (§8) |
| **E12-S4** | Every surface says whose meal it is (§4) | Frontend built, not deployed (T-352); per-kitchen crew and Today's names being built (T-358) |
| **E12-S5** | One job card per kitchen | Frontend built, not deployed (T-352); the card itself being built (T-356, V152) |
| *Added 2026-09-19* | Staff belong to one kitchen, and the Temple Admin's check list | Frontend built, not deployed (T-353); API being built (T-357) |
| *Added 2026-09-19* | Only people whose kitchen plans its meals here can open the planner | Frontend built, not deployed (T-353); server rule being built (T-357) |

"Being built" means the task was in progress when this line was written; its proof in
`docs/work/proof/` says whether it finished.

**UAT-075 — Two kitchens, one lunch.** *(Written as UAT-093 on 2026-09-19; UAT-075 had been used for cost per serving.)* Plan a lunch whose sweets come from another kitchen, see both
named on the planner and on Today, print both job cards, and check each carries only its own preparations.

---

## 7. Questions

**Q1 — The picker's default.** *Answered 2026-09-19: the person's own kitchen; the main kitchen if they have none, or theirs doesn't plan meals here (§8).* I default it to the temple's main kitchen, since that is what most meals
are. The alternative is no default and a forced choice every time, which is more honest and more friction.
Recommend defaulting.

**Q2 — Existing meals.** *Answered 2026-09-19: yes, backfill to the main kitchen, and the same for existing staff, with a check list for the Temple Admin (§8).* The backfill assigns every meal already planned to the main kitchen. For a
temple that has been running the planner for months this is right by definition — there was only one
kitchen. Flagging it because it is a silent bulk assignment, and it is the sort of thing worth knowing
happened.

**Q3 — Does the crew count split per kitchen?** *Answered 2026-09-19: per kitchen (§8).* `crew_required` is one number for the meal. If a lunch
spans two kitchens, "we need 6 people" no longer says where. Not built here, and worth an answer before
somebody asks: leave it whole-meal for now, or make it per-kitchen alongside the dishes?

---

## 8. Decided 2026-09-19

Rajeev said *"GO ahead, add the Which Kitchen is making this meal feature"* and approved these amendments
the same day. The source is `docs/work/NEXT-MENU-LAYOUT.md`, "Also approved 2026-09-19: Epic 12", and the
approved mock at `/dev-kitchen-meal`. The wording below is a summary of that note, not his words, except
where quoted.

- **The view is the mock's Option 1:** one Lunch card with a clear section per kitchen, not two Lunch cards.
- **The composer is the mock's build tab** (*"looks AMAZING"*): one section to start; **+ Add another
  kitchen**, a searchable list of kitchens that plan meals here and are not already on the meal; × on each
  section, with an inline *"Remove <kitchen> and its N dishes?"*; no Move. This replaces §2's meal-level
  picker with a per-dish override.
- **Own kitchen first.** The signed-in person's own kitchen is the first section when building, after
  saving, on every edit, and in the meal's card. Then the kitchens in Settings order. A person with no
  kitchen, or whose kitchen isn't on the meal, sees the main kitchen first. He first asked for the main
  kitchen always on top, then took it back: *"we have to go with the user's kitchen assignment"*.
- **Staff belong to exactly one kitchen**, required on add and edit, so rostering works per kitchen,
  including a sister kitchen with its own staff. A "home kitchen, can also cook in" idea only if it
  proves common.
- **Planner access.** The Temple Admin sees and plans for every kitchen. Anyone else can open the meal
  planner only if their kitchen plans its meals here. Server-enforced (`KMS-400183`) and hidden from the
  menu. A person with no staff record is refused.
- **People needed and the job card are per kitchen.** Each kitchen's section has its own People needed,
  shows that kitchen's rostered staff, and prints its own job card. The card number stays one per meal.
- **The backfill.** Existing meals go to the main kitchen, and so do existing staff. The Temple Admin
  gets a **Check these kitchen assignments** list to move anyone.

**Two assumptions made while building, not yet put to Rajeev:**

1. **Volunteers are counted in the main kitchen's section.** A meal's volunteers are counted once, in
   the main kitchen's section if it is on the meal, otherwise in the first section. Nothing yet records
   which kitchen a volunteer is helping.
2. **Turning a kitchen's planner switch off locks its staff out of the planner.** By the access rule,
   Kitchen Staff and Kitchen Managers in that kitchen lose the planner on their next page load, including
   for meals they already planned.
