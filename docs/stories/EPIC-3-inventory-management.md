# EPIC 3 — Inventory Management

**Goal:** Two inventory classes (consumables with batch/expiry tracking, equipment with lifecycle state), a movements-ledger model where every stock change is a row, in-kind donation intake, and low-stock alerting.
**Depends on:** Epic 1, Epic 2 (ingredient master). **Blocks:** Epic 4 (sufficiency checks), Epic 5 (auto-generated shopping list, receiving).
**Labels:** `epic:inventory`

**Design note carried from SYSTEM_DESIGN.md §5:** inventory is *derived from* `stock_movements` — receipts, consumption, donations, adjustments — never directly edited. This gives audit-friendly inventory by construction and is the foundation the Phase 2 FSSAI/BHOG food-safety log will layer onto (batch/expiry/received-date fields exist from day one, per locked decision).

---

## E3-S1 — Consumable inventory items and stock view

**Verified by:** [UAT-022](../uat/UAT-022-track-a-consumable.md)

**As a** Store Manager (Kitchen Staff role), **I want** to see current stock for every consumable with batch and expiry visibility, **so that** I know what we have without walking the storeroom.

**Assumptions:** Consumable inventory item = 1:1 link to an ingredient (E2-S1) + storage metadata. Multi-store-room support (Deity kitchen vs main kitchen vs catering, per the prior proposal's multi-store requirement) is modeled as an optional `storage_location` on items/movements — locations are a simple tenant-scoped list, not a full warehouse hierarchy, for release 1.

**Requirements:**
- Inventory item: ingredient ref, storage location(s), reorder threshold, preferred vendor (nullable until E5-S1).
- Stock computed from movements, grouped by batch (batch id, quantity, expiry date, received date).
- Stock list view: current quantity, unit, expiring-soon and below-threshold badges; filter by location/category.
- Expiring-soon = within N days (`tenant_settings.stock_expiry_warning_days`, default 7, `V85`). Set
  on the settings screen under **Warnings** behind `MANAGE_TEMPLE_SETTINGS`, beside the vendor
  contract horizon (E5-S1 D2) — the two moved out of constants together so they cannot quietly
  disagree. 1 to 365, enforced by a `CHECK` and by bean validation. A request may still name its own
  window; the setting is what answers when it does not.

**Acceptance criteria:**
- [ ] Stock shown always equals the sum of movements (property-based or invariant test).
- [ ] Batch with nearest expiry surfaces first (FEFO presentation).
- [ ] Below-threshold and expiring-soon badges appear per rules; filters work.
- [x] The expiring-soon window is the temple's own setting, defaulting to 7 days, and changing it
      changes which batches are badged. It was a constant until `V85`.
- [ ] No endpoint exists that sets stock directly.

---

## E3-S2 — Stock movements ledger

**Verified by:** [UAT-026](../uat/UAT-026-the-movement-history.md)

**As a** Temple Admin, **I want** every stock change recorded as an immutable movement with type and actor, **so that** inventory history is a fact, not a guess.

**Assumptions:** Movement types for release 1: `PO_RECEIPT` (E5), `DONATION_IN_KIND` (E3-S5), `CONSUMPTION` (E3-S6), `ADJUSTMENT` (E3-S7), `WASTE/SCRAP` (recorded via adjustment with reason category — full waste analytics is Phase 2).

**Requirements:**
- Movements table: type, ingredient/item, batch, signed quantity, unit, actor, timestamp, reference (PO id, meal plan id, donation id, reason), tenant. Append-only (no UPDATE/DELETE grants, like audit_events).
- Correction of a mistaken movement = compensating movement referencing the original, never editing history.
- Movement history view per item, filterable by type/date.

**Acceptance criteria:**
- [ ] App role cannot UPDATE/DELETE movement rows (DB-enforced, tested).
- [ ] Compensating movement flow works and both rows cross-reference each other.
- [ ] Item history reconstructs current stock exactly for a seeded 1,000-movement item.

---

## E3-S3 — Reorder thresholds and low-stock alerts

**Verified by:** [UAT-023](../uat/UAT-023-reorder-thresholds-and-low-stock.md)

**As a** Kitchen Staff member, **I want** alerts when items dip below threshold, **so that** we discover shortages before the cook does.

**Assumptions:** Alerting = in-app dashboard badge + daily digest via notification service (E1-S10) to KITCHEN_STAFF and TEMPLE_ADMIN, not per-event spam. Real-time per-item alerts deliberately avoided (alert fatigue).

**Requirements:**
- Threshold per inventory item (E3-S1); nightly job (E1-S9) computes below-threshold set.
- Daily digest lists item, current qty, threshold, expiring-soon items — sent only when non-empty.
- Dashboard "Low stock" count (matches approved wireframe's dashboard card) linking to filtered stock view.
- Below-threshold items feed the auto-generated shopping list (E5-S2) — this story exposes the query, E5 consumes it.

**Acceptance criteria:**
- [ ] Dropping an item below threshold appears in next digest and dashboard count.
- [ ] Digest suppressed when nothing qualifies.
- [ ] Digest respects each recipient's channel preference.

---

## E3-S4 — Equipment inventory

**Verified by:** [UAT-027](../uat/UAT-027-the-equipment-register.md)

**As a** Kitchen Staff member, **I want** kitchen equipment tracked by condition, location, and service status, **so that** we know what we own and what state it's in.

**Assumptions:** Per locked requirements: equipment is state-tracked (condition/location/service status), not quantity-depleted. Preventive-maintenance scheduling is Phase 2 (prior proposal's maintenance module) — release 1 records state and history, no scheduling engine.

**Requirements:**
- Equipment item: name, category (machine/tool/furniture, per proposal's categories), location, condition (`GOOD/NEEDS_REPAIR/IN_REPAIR/SCRAPPED`), acquisition date, source (purchased/donated → links donation if in-kind), notes.
- State-change flow with reason; history retained (state changes are events, consistent with the ledger philosophy).
- List with filters (condition, location); scrapped items excluded by default but queryable (feeds the proposal's "scrap report" as a Phase 2 report).

**Acceptance criteria:**
- [ ] State transitions record actor + reason + timestamp and show in item history.
- [ ] Donated equipment created via E3-S5 links back to its donation record.
- [ ] SCRAPPED items disappear from default views, remain in history and filtered queries.

---

## E3-S5 — In-kind donation intake

**Verified by:** [UAT-028](../uat/UAT-028-record-a-gift-in-kind.md)

**As a** Kitchen Staff member, **I want** to record donated goods straight into inventory with donor details, **so that** a devotee's rice sack is tracked, valued, and thankable.

**Assumptions:** Locked Phase 1 feature. Creates either consumable stock movements (`DONATION_IN_KIND`, with batch/expiry) or equipment records (E3-S4). Donor may be anonymous; when named, captures name + contact (and optionally links to an existing donor record — full donor unification lives in E7).

**Requirements:**
- Intake form: donor (name/contact or anonymous), item(s) — ingredient + qty + batch/expiry OR equipment details — estimated value (INR), date, notes.
- Writes: stock movement(s)/equipment record + a donation record of type `IN_KIND` visible to the donations ledger (E7-S8) with estimated value for accounting.
- Printable/sendable acknowledgment (thank-you) using notification service when donor contact is present — simple template, not an 80G receipt (in-kind 80G treatment is out of scope for release 1; flag in ledger).
- Audit event on creation.

**Acceptance criteria:**
- [ ] Donated groceries appear in stock immediately with correct batch/expiry and show `DONATION_IN_KIND` provenance in history.
- [ ] Donation record with estimated value appears in the donations ledger flagged `IN_KIND`.
- [ ] Anonymous intake stores no donor PII; named intake can trigger a thank-you via preferred available channel.
- [ ] Donated equipment lands in E3-S4 with source=donated and linked record.

---

## E3-S6 — Consumption on meal production

**Verified by:** [UAT-035](../uat/UAT-035-cook-a-meal.md)

**As a** cook, **I want** cooking a planned meal to draw down inventory, **so that** stock reflects reality without separate bookkeeping.

**Assumptions:** Consumption is triggered from the meal planner ("mark as cooked", E4-S5 owns the UI moment) and writes `CONSUMPTION` movements for the scaled ingredient quantities. FEFO batch selection by default with manual batch override. Partial-cook / leftover handling: release 1 records planned-quantity consumption with an optional adjustment; actual-vs-planned analytics is Phase 2 (locked).

**Revised 2026-08-20 by E4-S10.** Two of those sentences no longer hold. The trigger is no longer a
per-dish "mark as cooked" — it is recording the whole meal from the returned job card, once — and
the quantities drawn are the **actual** servings the office typed in, not the planned ones. Actual
servings therefore arrive in Phase 1 (REQUIREMENTS v1.1); leftovers and waste weight stay in
Phase 2. A dish recorded as *not made* writes no movement at all.

**Requirements:**
- Service API: given (recipe, scale, meal plan ref) → movement set, FEFO across batches, negative-stock guarded (block with clear message listing shortfalls; staff resolve via adjustment or receiving first).
- Manual batch override in the confirmation UI.
- Movements reference the meal plan for traceability.

**Acceptance criteria:**
- [ ] Cooking a scaled recipe writes correct per-batch movements (FEFO verified across a multi-batch item).
- [ ] Insufficient stock blocks with an itemized shortfall message — no partial silent writes.
- [ ] All resulting movements carry the meal-plan reference.

---

## E3-S7 — Manual stock adjustment

**Verified by:** [UAT-024](../uat/UAT-024-adjust-stock-with-a-reason.md), [UAT-025](../uat/UAT-025-large-adjustments-need-an-admin.md)

**As a** Kitchen Staff member, **I want** to correct stock with a reason (spoilage, count correction, waste), **so that** the system tracks the messy real world without losing auditability.

**Assumptions:** Adjustment = movement with mandatory reason category (`SPOILAGE`, `DAMAGE`, `COUNT_CORRECTION`, `WASTE`, `OTHER`+text). Large adjustments (> tenant-config threshold, default 20% of current stock) require TEMPLE_ADMIN.

**Requirements:**
- Adjustment form per item/batch: direction, quantity, reason category, note.
- Threshold rule: above-threshold adjustments blocked for staff, allowed for admin; both audited (movement + audit event).
- Reason categories power the Phase 2 waste report — categories locked now so data accumulates from day one.

**Acceptance criteria:**
- [ ] Staff adjustment below threshold succeeds; above threshold prompts for admin.
- [ ] Every adjustment shows in item history with reason; audit event written for above-threshold ones.
- [ ] Adjustment cannot drive stock negative.

---

## E3-S8 — What the day's food is costing

**Status:** DONE 2026-08-20 (B2, build brief §9).

**Verified by:** UAT to be written. Automated cover: `MaterialsCostIT`, and the Today tile in
`frontend/__tests__/today.test.tsx`.

**Why it is in this epic.** It was offered to Epic 4, beside sufficiency (E4-S5), which reads the
same scaled basket. It sits here instead because the two ask different questions of different
things: sufficiency asks whether the store room can cover the plan, and costing asks what the store
room's contents are worth — a fact about stock. Every argument in the decisions below is an
inventory argument, and the one that settles the design (donated goods have an estimated value and
no purchase price) is this epic's own problem, arriving through E3-S5. The price itself is a vendor
datum (E5-S1's supply mapping), so this story depends on Epic 5 as well; it does not belong there,
because a vendor's price is an input to the question and not the question.

**As a** Temple Admin, **I want** to know roughly what today's food is costing, **so that** the
morning screen says something about money going out as well as about food going out.

**Assumptions:** Estimated, from vendors' last-known prices, and labelled an estimate. **This is the
final version, not a stepping stone.**

### Decisions

**D1 — An honest estimate beats a false exact figure.** True cost needs inventory valuation — which
batch each spoonful came out of and what that batch was paid for. The store room will not support
it, and not because code is missing: a great deal of what it holds was donated, and a gift in kind
has an estimated value and no purchase price at all. So a "perfect" number becomes part fiction the
moment a gift is cooked. An estimate that says it is an estimate is the more truthful of the two,
and it is cheap.

**D2 — The gap is reported, never absorbed.** An ingredient nobody has priced has no price here
either. *(Amended 2026-08-31: this decision used to read "`last_price` is maintained by hand —
`setSupply` is its only writer, and nothing in receiving, invoicing or goods receipts writes a price
back". That is no longer true. Since `V82` a goods-receipt line carries what was actually paid, and
`ReceivingService` writes it back to `vendor_supplies.last_price` — see E5-S6. The gap is smaller
than it was; everything the decision says about the gap is unchanged, because a delivery that
arrived ahead of its bill, and a donated ingredient that was never bought at all, still have no
price.)* Such an ingredient is counted and named:
*"₹18,400 estimated · 6 ingredients have no known price"*. A total that quietly omits a third of the
basket is worse than one that admits the hole, because only the second can be acted on.

**D3 — For the day, not per meal.** It replaces *Given this month* on Today and answers "what is
today's food costing us". A per-meal figure would need the same estimate cut three ways with nothing
gained.

> **Revised 2026-08-31 by E3-S9 (review item MP4, signed off by Rajeev).** D3 was right about the
> question it was asked. *What is today's food costing us* is a headline for the Today screen, and a
> daily total is exactly that answer; **the daily total on Today is unchanged and this revision does
> not touch it.** The reviewers asked a different question — what a public-prasadam plate costs
> against a Sunday feast plate — and that is a **comparison between categories**, which no single
> daily total can give however it is presented. Same data, different question, and the second answer
> is not a worse version of the first. So the split D3 threw away is kept, over a period rather than
> a day, on its own screen. What D3 refused and E3-S9 still refuses is a per-meal figure on Today.
> **D1 and D4 stand unchanged**: the figure is an honest estimate because much of the store is
> donated, and labour is absent because a cook on a 6am–2pm shift makes breakfast *and* lunch and
> their pay can only be allocated, never measured.

**D4 — Labour is deliberately absent, and it is not a data problem.** The weekly template says who
works which hours and a monthly salary gives a day rate (E6-S13). It is that a cook on a 6am–2pm
shift is making breakfast *and* lunch, so their pay can only ever be **allocated** across the meals
their hours overlap, never measured. Whatever split were chosen would be an assumption presented as
a figure. If it is ever built, the screen must say "estimated, materials and labour allocated".

**D5 — The basket computation is knowingly duplicated, and recorded as such.**
`SufficiencyService` already scales planned meals into per-ingredient quantities, but every part of
it is private and that package was being changed under other work in the same build. Widening an API
across a moving boundary was the worse of the two costs. A later pass should extract the shared
"scaled ingredient basket for a date range" and have both call it.

*Discharged 2026-08-31 by E3-S9.* The extraction is done: `costing/IngredientBasket`,
`costing/BasketCostingService` and `costing/CostedBasket` are the one path from a planned dish to
ingredients and from any basket to money. The day's figure, the per-meal-kind report (E3-S9) and the
issued-from-store report (E10-S13) are three questions asked of one calculation. `SufficiencyService`
still scales its own baskets — it asks about stock rather than money, and folding the two would be a
second change on top of this one — so the debt this decision recorded is smaller, not gone.

**Requirements:**
- `GET /api/v1/materials-cost` for a date: the estimated total in the temple's currency, and the
  ingredients in the day's basket with no known price, named.
- Behind `MANAGE_MEAL_PLANS` — it is a fact about the day's cooking, not about payables.
- Cancelled meals and dishes marked *not made* (E4-S10) contribute nothing.
- Surfaces as the *Cost of materials* tile on Today (E4-S14).

**Acceptance criteria:**
- [x] The figure is the scaled basket for the day priced at each ingredient's last-known vendor price.
- [x] Ingredients with no price are counted and named rather than costed at zero.
- [x] A day with nothing planned reports nothing, not a zero that reads as a statement.
- [x] The tile says it is an estimate wherever it appears.

---

## E3-S9 — What a serving costs, by kind of meal

**Status:** DONE 2026-08-31 (review item MP4, signed off by Rajeev — it revises E3-S8 D3).

**Verified by:** [UAT-075](../uat/UAT-075-cost-per-serving.md). Automated cover: the *cost per
serving, compared across kinds of meal* block in `MaterialsCostIT`, and
`frontend/__tests__/cost-per-serving.test.tsx`.

**As a** Temple Admin, **I want** to read what a serving costs at each kind of meal over a period,
**so that** I can compare a public-prasadam plate against a Sunday feast plate instead of guessing at
the difference.

**Assumptions:** The same estimate as E3-S8, kept split instead of summed. Materials only, at
vendors' last-known prices, labelled an estimate wherever it appears. No new table and no migration:
`meal_plans` already carries the kind, the yield and the head count.

### Decisions

**D1 — This is a different question, not a better answer to the old one.** E3-S8 D3 settled that the
materials estimate was for the day and not per meal, and it was right about the question it was
asked: *what is today's food costing us* is a headline for Today, and a daily total is exactly that.
The reviewers asked for a **comparison between categories**, and no single daily total can give one
however it is presented. The daily total on Today is unchanged, and nothing here appears on Today.
**Cutting the day's figure three ways on the morning screen was considered and is still rejected**,
for D3's original reason — three thirds of one estimate on a tile nobody is comparing anything on.

**D2 — The kinds are the temple's own.** Nothing here names Breakfast or Festival feast, and nothing
seeds a kind for the report's benefit. `meal_kinds` is tenant data (E4-S7 D1), so a temple that
cooks an Annadana sees an Annadana row, and a kind nobody cooked in the period does not appear at
all — a row of dashes is not a finding. **A fixed list of reporting categories was rejected**: it
would be a second vocabulary for meals, and the first screen where the two disagreed would be this
one.

**D3 — A meal with no head count is totalled but not divided.** The head count comes from adults,
children and seniors, derived the way `ServedMealService` derives it. Where no dish of a meal
carries any of the three, that meal has no head count and the report will not invent one — in
particular **it does not fall back to `target_yield`**, which since `V69` holds an amount of *food*
and not a number of people, and since `V80` cannot even be expressed in servings, because servings
is not a unit. Dividing a cost by litres of rasam would put a number under a column headed *cost per
serving* that is not one. Such a meal counts in the kind's total and is left out of **both halves**
of the per-serving figure — out of the denominator alone would divide a whole period's cost by part
of its people and overstate every plate — and the number of meals left out travels beside the
figure, exactly as the unpriced ingredients do. A kind where no meal at all was counted shows a dash
and sorts to the foot, present and plainly not compared.

**D4 — Costed at what was planned, not at what the job card came back saying.** So this report and
the Today tile are one calculation of one thing, and a period of days adds up to the days in it.
Planned against actually cooked is a different report with a different name; conflating them would
mean the same Tuesday costs two different amounts on two screens.

**D5 — One calculation behind every costing figure.** E3-S8 D5 recorded the duplicated basket code
as a debt; this story pays it. `IngredientBasket`, `BasketCostingService` and `CostedBasket` turn a
planned dish into ingredients and any basket into money, and the daily figure, this report and
E10-S13 are three callers of the same two methods. A second costing path written for this screen
would have been a second opinion about what a kilo of rice is worth.

**Requirements:**
- `costing/IngredientBasket`, `costing/BasketCostingService` and `costing/CostedBasket` extracted
  from `MaterialsCostService`, which keeps its daily behaviour unchanged.
- `MealKindCostService` and `GET /api/v1/materials-cost/by-meal-kind?from=&to=`, behind
  `MANAGE_MEAL_PLANS` — the same permission as the daily figure, because it is the same fact about
  the same cooking asked a different way. Both dates optional, defaulting to the four weeks up to
  today at the temple.
- A meal is the pair (date, kind), as everywhere else: a lunch of three dishes is one meal costing
  the sum of its three baskets, fed to one head count rather than three.
- Cancelled dishes contribute nothing; a meal marked cooked still does, so a month of cooking does
  not report as having cost nothing.
- Rows sorted dearest serving first, so reading top to bottom is the answer.
- `KMS-4988 COST_PERIOD_NOT_VALID` for a backwards period or one longer than a year; the report walks
  every dish in the range, and an unbounded one is a slow page rather than an answer.
- Screen at `/cost-per-serving`, titled *Cost per serving*, in the kitchen group of the menu beside
  *Issued from store*. Every figure carries *estimated, materials only* and the count of ingredients
  with no known price. The all-meals row deliberately leaves the per-serving cell blank: an average
  across every kind would read as a fact about none of them.

**Acceptance criteria:**
- [x] The kinds' totals for a day add up to exactly what the Today tile reports for that day.
- [x] Two kinds cooked in one period each report their own cost per serving.
- [x] A lunch of three dishes is one meal, not three, and is divided by one head count.
- [x] A meal with no head count is in the total, out of the division, and counted as left out.
- [x] A kind with no head count anywhere shows a dash rather than a figure.
- [x] The rows are the temple's own kinds, and a kind nobody cooked is absent.
- [x] A cancelled meal contributes nothing.
- [x] A period with nothing cooked says so rather than showing a table of zeroes.
- [x] A backwards period and one over a year are both refused with `KMS-4988`.
- [x] Ingredients with no price are counted and named, per kind and overall, never costed at zero.
- [x] No figure appears anywhere without saying it is an estimate of materials alone.
- [x] A devotee is refused the endpoint and is not offered the screen.

