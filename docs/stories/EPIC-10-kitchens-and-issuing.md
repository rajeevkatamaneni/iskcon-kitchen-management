# EPIC 10 — Kitchens under one temple, and issuing to them

**Goal:** A temple runs three to ten kitchens under one roof. Register them, let any of them ask the store for ingredients, let the admin or storekeeper answer, and let the store record what actually went out — drawing it down from stock by lot, oldest expiry first, on a printed work order somebody signs.
**Depends on:** E1 (tenancy, RBAC, audit), E2 (ingredient master), E3 (the stock ledger), E4-S11 (the document pipeline), **E11 (the unit vocabulary and display rule)**.
**Blocks:** E12 (which kitchen is cooking).
**Labels:** `epic:kitchens`

**Design:** `EPIC-10-kitchens-and-issuing-DESIGN.md` — read it first. It amends `REQUIREMENTS.md`, which has no multi-kitchen concept and no notion of stock leaving the temple by any door but consumption.

---

## E10-S1 — The requirements say a temple has kitchens

**Verified by:** no manual surface; accepted on the documents being consistent with the build.

**As a** future reader of this project, **I want** the locked requirements to describe the system that exists, **so that** the next person does not design against a document that stopped being true.

**Assumptions:** This is a Commandment 8 amendment and needs Rajeev's sign-off, which the design review is. `REQUIREMENTS.md` v1.1 §3.1 closes the stock model — *"Stock increases from: purchase order receipt, and in-kind donation intake"* — and says nothing about more than one kitchen. Both change.

**Requirements:**
- `REQUIREMENTS.md` → v1.2: a temple has kitchens; one is its main kitchen; a kitchen either plans its meals here or draws ingredients from the store; issuing is a fifth kind of stock movement.
- Snapshot at `docs/versions/REQUIREMENTS_v1.2.md`; entry in `docs/CHANGELOG.md`.
- `SYSTEM_DESIGN.md` §5's entity list gains `kitchens` and `ingredient_requests`.

**Acceptance criteria:**
- [ ] The three documents agree with the schema this epic ships.
- [ ] The changelog entry names what changed and why, not just that it changed.

---

## E10-S2 — The kitchens register

**Verified by:** [UAT-067](../uat/UAT-067-set-up-the-temples-kitchens.md)

**As a** Temple Admin, **I want** to record the kitchens my temple runs, **so that** the system can tell them apart when they ask for ingredients.

**Assumptions:** Flat — every kitchen hangs off the tenant, no kitchen inside another (design D1, settled by Rajeev). `is_main` is a label with a database-enforced "at most one"; `uses_meal_planner` is the flag that changes behaviour (D5). Delete where nothing references it, archive where something does — the pattern `RecipeService.archive` and `IngredientService.delete` already set.

**Requirements:**
- `V76__kitchens.sql`: `name`, `description`, `location`, `is_main`, `uses_meal_planner`, `in_charge_user_id`, `contact_phone`, `status`, the usual audit columns, `enable_tenant_rls`.
- `CREATE UNIQUE INDEX kitchens_one_main_per_tenant ON kitchens (tenant_id) WHERE is_main` — at most one, not exactly one, because a temple with no kitchens yet has no row to carry the flag.
- `MANAGE_KITCHENS` (Temple Admin). Reading the list rides on `REQUEST_INGREDIENTS` — you cannot raise a request without choosing one.
- Marking a second kitchen main clears the first in the same transaction.
- A temple's first kitchen is created main.
- Audit on every write.

**Acceptance criteria:**
- [ ] Two kitchens cannot both be main — enforced by the database, proven by a test that tries.
- [ ] A kitchen from another temple is invisible and unreachable (RLS, and the cross-tenant FK check every service does).
- [ ] Deleting a referenced kitchen is refused with `KITCHEN_IN_USE`; archiving succeeds.
- [ ] A duplicate name in the same temple is refused; the same name in another temple is not.

---

## E10-S3 — The kitchens page

**Verified by:** [UAT-067](../uat/UAT-067-set-up-the-temples-kitchens.md)

**As a** Temple Admin, **I want** to see and manage my kitchens on one screen, **so that** onboarding a new one takes a minute.

**Assumptions:** The recipes shape, which is also `DESIGN_SYSTEM.md` §"One screen, one task": a top-right button, the form on its own URL, `[Cancel] [Primary]` in a sticky header, and committing returns to the list with the confirmation waiting there.

**Requirements:**
- `/kitchens` — table with Edit and Delete per row, **Add a kitchen** top right, empty state.
- `/kitchens/new` and `/kitchens/[id]/edit` — `FocusScreen` with the intake form.
- The main-kitchen checkbox names the kitchen that will lose the flag and waits for confirmation. On a temple's first kitchen it is ticked and disabled, with a line saying why.
- Two nav entries in the Kitchen group, after Inventory.

**Acceptance criteria:**
- [ ] The list→form→list transition matches recipes, and `?added=` flashes on return.
- [ ] Ticking main on a second kitchen warns by name before it moves.
- [ ] `design-system.test.ts` and `nav.test.ts` pass.

---

## E10-S4 — A kitchen starts planning its own meals

**Verified by:** [UAT-072](../uat/UAT-072-a-kitchen-starts-planning-its-own-meals.md)

**As a** Temple Admin, **I want** turning on the meal planner for a kitchen to settle the ingredient requests already in flight for it, **so that** the store cannot issue twice for the same food.

**Assumptions:** Design D5 and D6, settled by Rajeev. One store, two doors; a kitchen uses one or the other. The cascade rule is his, with two states he did not name — `SUBMITTED` gets the same answer as `APPROVED`, and `ISSUED` is untouched because the goods have left the shelf and the movements are append-only.

**This is the story to build carefully.** It is the only one in the epic that deletes a person's work and reverses a decision somebody already made, and it does both without being asked twice.

**Requirements:**
- Turning `uses_meal_planner` on settles every request for that kitchen: **every `DRAFT` deleted, whatever date it carries**; `SUBMITTED` and `APPROVED` denied where `needed_on` is today or later; `DENIED` untouched; `ISSUED` untouched. A submitted or approved request dated earlier is history and is not rewritten.
- **Drafts are not filtered by date (amended 2026-08-31).** A draft holds no history and its date is a field its author can still edit, so filtering on it filters on something they can change. The guard that refuses to update or submit a request naming a planner kitchen closes the obvious exploit, but only while the flag is on — opting the kitchen back out revives every stale draft.
- Saving with the flag newly on first says what is about to happen, with counts, and waits.
- `decided_by` is the administrator who flipped the switch; `decision_note` names the kitchen and the date.
- Every affected row is audited, deletions included.
- Turning it off restores the ability to request. Nothing recorded changes.
- A request naming a kitchen that plans its own meals is refused with `KITCHEN_PLANS_ITS_OWN_MEALS`.

**Acceptance criteria:**
- [ ] One test per row of the D6 table, including the two states Rajeev did not name.
- [ ] The confirmation reports the right counts before anything moves — including past-dated drafts, or the warning is a lie the first time somebody reads it.
- [ ] An audit row exists for every denial and every deletion.
- [ ] A draft dated last month is deleted along with the rest.
- [ ] A **submitted or approved** request dated yesterday survives untouched.

---

## E10-S5 — Asking the store for ingredients

**Verified by:** [UAT-068](../uat/UAT-068-ask-the-store-for-ingredients.md)

**As any** temple staff member, **I want** to write down what my kitchen needs and when, **so that** the store can get it ready.

**Assumptions:** Any staff member may raise one (Rajeev). A draft is private to edit and public to read. The dish list is mandatory at submission and not before — the discipline is the point of the field, and a draft is allowed to be rough.

**Requirements:**
- `V77__ingredient_requests.sql`: the request, its lines and its dishes, all tenant-owned, all RLS.
- `reference` minted per tenant (`IR-2026-0041`), so somebody can say it down a phone.
- Line units come from E11's vocabulary, filtered to the ingredient's own family; a cross-family unit is refused.
- Dish lines: name, quantity, unit — text and numbers, no recipe link.
- `REQUEST_INGREDIENTS` for Temple Admin, Kitchen Manager and Kitchen Staff.
- Draft: the creator edits and deletes; a Temple Admin may delete; everyone may read.
- Submit requires at least one ingredient line and at least one dish.

**Acceptance criteria:**
- [ ] Someone else's draft can be read and cannot be edited or deleted — `NOT_YOUR_INGREDIENT_REQUEST`.
- [ ] A Temple Admin can delete anybody's draft.
- [ ] Submitting with no dishes is refused with `INGREDIENT_REQUEST_NEEDS_DISHES`.
- [ ] A litre of rice is refused; 500 gm of rice against a KG ingredient is accepted.
- [ ] A request cannot name a kitchen from another temple.

---

## E10-S6 — Review: approve, deny, withdraw

**Verified by:** [UAT-069](../uat/UAT-069-review-approve-and-deny-a-request.md)

**As a** Temple Admin or storekeeper, **I want** to answer a request, **so that** the store knows what to get ready and the kitchen knows where it stands.

**Assumptions:** There is no Storekeeper role and this epic does not add one (D4) — `APPROVE_INGREDIENT_REQUESTS` goes to Temple Admin and Kitchen Manager, which is what that role is for. Self-approval is allowed and visible rather than forbidden and deadlocking. A denial is terminal: a refusal that can be edited and re-shown is not a refusal.

**Requirements:**
- Approve and deny, each with an optional note, in the `LeaveService` shape: one transactional method per transition, a shared guard on the current state, a distinct error code per illegal transition.
- The creator or a Temple Admin may edit a submitted request, or withdraw it to `DRAFT`, while it is undecided.
- Denied and issued requests are immutable.
- A per-request event trail, as `po_events` does, for the detail screen to show.

**Acceptance criteria:**
- [ ] Every illegal transition has its own code and its own test.
- [ ] Approving your own request works and is audited as such.
- [ ] A denied request cannot be edited, deleted, re-approved or withdrawn.
- [ ] Kitchen Staff cannot approve — 403, `KMS-4301`.

---

## E10-S7 — Recording what was issued

**Verified by:** [UAT-070](../uat/UAT-070-issue-the-ingredients-and-watch-the-stock-fall.md)

**As a** storekeeper, **I want** to record what I actually handed over, **so that** the store's books match its shelves.

**Assumptions:** Stock moves when the goods move, not when the request is approved (D3) — the same distinction the system already draws between sending a purchase order and receiving one. FEFO comes from `InventoryConsumptionService`'s existing comparator rather than a second one. All-or-nothing, as consumption is.

**Requirements:**
- `MovementType.ISSUE` and `MovementReference.INGREDIENT_REQUEST`, with the `V14` CHECKs widened.
- Per line, an actual quantity pre-filled with the approved one; zero writes no movement, as a not-made dish does.
- FEFO allocation across batches, with a batch override.
- Insufficient stock refuses the whole issue with `INSUFFICIENT_STOCK` and an itemised shortfall — the storekeeper corrects the count first, on a screen that already exists.
- The kitchen is not written onto the movement: it carries `reference_type = 'INGREDIENT_REQUEST'` and the request carries the kitchen, so the two can never disagree. `storage_location` stays null, as it is on every consumption movement.
- `ISSUE_INGREDIENTS` for Temple Admin and Kitchen Manager.

**Acceptance criteria:**
- [ ] Issuing draws the right batches oldest-expiry-first across a multi-batch ingredient.
- [ ] A shortfall on any line writes nothing at all.
- [ ] Stock after issuing equals stock before minus what was issued.
- [ ] The movements carry the request reference and no storage location.
- [ ] An approved request cannot be issued twice.

---

## E10-S8 — The requests list

**Verified by:** [UAT-068](../uat/UAT-068-ask-the-store-for-ingredients.md)

**As** any staff member, **I want** to see every request and filter it, **so that** I can find mine and the approver can find theirs.

**Assumptions:** Modelled on recipes. Rajeev named three filters; the set adds the one an approver most needs and the one that closes the loop.

**Requirements:**
- `/ingredient-requests` — every request in a table, newest first, **New request** top right.
- Filters as a `SegmentedControl`: All · Draft · Awaiting review · Approved · Denied · Issued, with the choice in the address bar so the view is linkable and survives a refresh.
- Each row: reference, kitchen, needed-on, requester, status.

**Acceptance criteria:**
- [ ] Filters work and are reflected in the URL.
- [ ] Empty state per filter reads sensibly.
- [ ] A volunteer is refused the page.

---

## E10-S9 — The request form

**Verified by:** [UAT-068](../uat/UAT-068-ask-the-store-for-ingredients.md)

**As** any staff member, **I want** a form that makes me think about what I am asking for, **so that** I ask once and ask right.

**Assumptions:** The mandatory dish list is the point of this screen, not paperwork — Rajeev's reasoning is that writing down what you are cooking is what stops "let me get that too just in case".

**Requirements:**
- `/ingredient-requests/new` and `/[id]/edit` — `FocusScreen`.
- Kitchen (dropdown, kitchens that do not plan their own meals), needed-on, reason.
- Ingredient lines: ingredient, quantity, unit from E11's vocabulary filtered to the family.
- Dish lines: name, quantity, unit.
- Save as draft, or submit for review, from the same form.

**Acceptance criteria:**
- [ ] Submitting without dishes is refused, in the form, before the request is sent.
- [ ] The unit dropdown offers only what can be true for the chosen ingredient.
- [ ] A draft may be saved incomplete and completed later.

---

## E10-S10 — The request record

**Verified by:** [UAT-069](../uat/UAT-069-review-approve-and-deny-a-request.md), [UAT-070](../uat/UAT-070-issue-the-ingredients-and-watch-the-stock-fall.md)

**As** anybody looking at a request, **I want** one screen that shows where it stands and what I can do about it, **so that** I do not have to know the rules to follow them.

**Requirements:**
- `/ingredient-requests/[id]` — the request, its lines, its dishes, its history.
- Only the acts this person may perform in this state are shown.
- Recording the issue happens here, with actual quantities per line.
- The work order downloads from here.

**Acceptance criteria:**
- [ ] The same request shows different controls to its creator, an approver and a bystander.
- [ ] The event trail reads as a sentence per event, with who and when.

---

## E10-S11 — The work order

**Verified by:** [UAT-071](../uat/UAT-071-the-work-order-printed-and-translated.md)

**As a** storekeeper, **I want** one sheet naming what to pick and which lot to pick it from, **so that** I can walk the store room with it and have it signed.

**Assumptions:** A picking list computed live, not a snapshot frozen at approval (D3) — an afternoon's cooking can empty the lot the sheet names. Quantities are cook's figures (E11 D4b). Signing is paper, as `E4-S11` D1 settled for the job card.

**Requirements:**
- `documents.kind = 'WORK_ORDER_PDF'`, a `WorkOrderTemplate` beside `JobCardTemplate`, the same Noto stack.
- Contents: temple and kitchen, reference and date wanted, the reason, **the dishes and how much of each**, every line with ingredient, quantity and the batches to pick from in expiry order, requester and approver by name and date, and two ruled signature boxes.
- Both paths from one control: a synchronous print view and a queued, versioned PDF.
- All 23 languages, offered from the client's own list; labels through `DocumentLabelTranslator` with a `WORK_ORDER` label set.

**Acceptance criteria:**
- [ ] The sheet names batches oldest-expiry-first and matches what issuing would actually draw.
- [ ] The dish list is on the sheet — an auditor needs both halves of the comparison.
- [ ] It renders in a non-Latin script without tofu.
- [ ] A request with no approval has no work order.

---

## E10-S12 — Ingredients and Inventory adopt the focus-screen add

**Verified by:** [UAT-073](../uat/UAT-073-adding-an-ingredient-and-adding-stock.md)

**As a** kitchen staff member, **I want** adding an ingredient to work the way adding a recipe works, **so that** I learn one pattern instead of three.

**Assumptions:** This reverses a deliberate earlier decision, and the reversal is better founded than the decision was. `app/inventory/page.tsx` records that the inline panel *replaced* a `/inventory/new` focus screen in order to match Ingredients. But `DESIGN_SYSTEM.md:281-284` is unambiguous: **"A form of four fields or more becomes a screen. Three or fewer stays inline."** Ingredients has four to five fields; Inventory has five. Both were already in breach. The earlier change bought consistency between two pages at the price of disagreeing with Recipes and with the rule; this one makes all three agree with each other and with the document.

**Requirements:**
- `/ingredients/new` and `/inventory/new` as `FocusScreen`s; the form body extracted into a presentational component taking `{ formId, busy, error, onSubmit }`, as `RecipeForm` does.
- Top-right button on each list; the inline panel goes.
- Committing returns to the list with `?added=` waiting there — `DESIGN_SYSTEM.md` rule 8, and the `captured`-ref guard from `app/tenants/page.tsx:47-67`, because an unguarded object-state flash effect loops.
- The empty states stop saying "above".

**Acceptance criteria:**
- [ ] Both pages match the recipes transition.
- [ ] The flash appears once and clears itself, and a refresh does not replay it.
- [ ] `design-system.test.ts` passes, including its rule that a `FocusScreen`'s submit lives in `actions={}`.

---

## E10-S13 — What the store issued to each kitchen, costed

**Status:** DONE 2026-08-31 (review items INV4 and INV5, signed off by Rajeev).

**Verified by:** [UAT-076](../uat/UAT-076-issued-from-the-temple-store.md); it reads back the
issuing that [UAT-070](../uat/UAT-070-issue-the-ingredients-and-watch-the-stock-fall.md) records.
Automated cover: `IssuedFromStoreIT`, and `frontend/__tests__/issued-from-store.test.tsx`.

**As a** Temple Admin or storekeeper, **I want** to read what the temple store issued to each
kitchen over a period, costed, **so that** the temple knows where its food is going without keeping
a second set of books.

**Assumptions:** No new table, no new noun and no migration. An issue already records which kitchen
the food went to — `stock_movements.reference_id` points at the request and the request carries the
kitchen (`V77`) — so the attribution has been sitting in the ledger since E10-S7 and nobody had
asked it a question. The costing is the one from E3-S9: `BasketCostingService` prices this basket
exactly as it prices a day of planned meals.

### Decisions

**D1 — The kitchen is the cost centre; there is no third noun.** The reviewers asked us to call the
meal category a cost centre rather than a kitchen, and that is **declined**, because it rests on a
misreading: a `meal_kind` is a category of preparation and a `kitchen` is a place with a door, a
person in charge and a phone number. They are two different things, both already exist, and neither
is misnamed. **A `cost_centres` entity beside them was considered and rejected**: it would have to
be created, maintained and mapped, and on the first day it would map one-to-one onto the kitchens —
E10 D1 and this project's standing rule against abstractions nobody can name a case for both point
the same way. If a temple ever names a case where the two genuinely differ, that is the day to build
the third noun.

What the request was **right** about is underneath it, and it is the reason this story exists: an
issue is a cost attribution the temple was not using as one.

**D2 — Issues are the only costing path for a kitchen that does not plan meals.** There is one store
and two doors out of it. A kitchen that plans its meals here draws `CONSUMPTION` and is costed
through what it cooked, on the cost-per-serving report (E3-S9). A kitchen that does not — the Deity
kitchen, by design (E10 D5, one kitchen one door) — draws `ISSUE`, and can be costed no other way.
So this is not a rough proxy for that kitchen's cost; it is the only measurement that exists. Two
doors, two paths, one report each.

**D3 — A floor, not a total, and the report is named for the half it knows (INV5).** The mathajis of
the Deity kitchen sometimes buy food themselves, and E10 D2 accepts that on purpose: issuing takes
food off the temple's books and what the kitchen does afterwards is its own business. A kitchen not
running this application will not record its own purchases in it, so the store's half is all this
report can ever see. Hence the heading **Issued from the temple store**, and a subtitle that says a
kitchen may also buy food itself and that each figure is a floor rather than a total. **Calling it
"Deity kitchen food cost" is the thing this decision exists to prevent** — under that name it would
be quoted as a total inside a week, and no caveat further down the page would catch it. Where those
purchases do physically land in the temple store they are already recordable, as
`MovementType.DONATION_IN_KIND`, which is batch-establishing and expects no purchase price; the
screen says so.

**D4 — A corrected issue is not an issue.** The ledger is append-only, so a mistake is undone by a
compensating `ADJUSTMENT` pointing back at the original. Costing both would charge a kitchen for
food it never received, and costing the original alone would too, so a reversed movement is left out
of the basket entirely — which is the same answer the store's own balance already gives.

**Requirements:**
- `costing/IssuedFromStoreService` over uncorrected `ISSUE` movements, joining
  `stock_movements → ingredient_requests → kitchens`. The kitchen comes from the request and never
  from the movement, so the two can never come to disagree; `storage_location` is not it either —
  that says where in the store a thing sat, not where it went.
- `GET /api/v1/issued-from-store?from=&to=` behind `MANAGE_INVENTORY`, with the same 366-day cap and
  the same `KMS-4988` as E3-S9. `MANAGE_KITCHENS` was the other candidate and is wrong: it gates
  deciding that a kitchen exists, is held by the Temple Admin alone, and using it here would lock the
  storekeeper out of the report about their own issuing.
- Screen at `/issued-from-store`, in the kitchen group of the menu beside *Cost per serving*. One
  row per kitchen the store issued to, dearest first; a kitchen issued nothing does not appear,
  because a row of zeroes is not a finding.
- Each row carries how many requests were filled, how many distinct ingredients went over the
  counter, the estimate, and how many of its ingredients have no known price. A kitchen that now
  plans its meals here is marked, because anything on its row is from before that.
- The period runs on the temple's own days: an issue at 9pm belongs to the day the storekeeper
  handed it over.
- Every figure carries the same caveats as the rest of the costing — estimated, materials only, no
  labour and no utilities (E3-S8 D1 and D4) — and unpriced ingredients are counted and named rather
  than costed at zero.

**Acceptance criteria:**
- [x] The heading reads *Issued from the temple store*, and the screen states that a kitchen may buy food itself and that each figure is a floor, not a total.
- [x] Each kitchen is costed from what it was issued, in the order the server sent.
- [x] Several requests to one kitchen are one row.
- [x] A corrected issue leaves the figure alone.
- [x] Consumption is not an issue and is counted nowhere here.
- [x] The period runs on the temple's days, not the server's.
- [x] A kitchen that now plans its meals here is marked as such.
- [x] A kitchen issued nothing is absent; a period with nothing issued says so rather than showing a table of zeroes.
- [x] Unpriced ingredients are named above the table and against the kitchen, and the figure still says it is an estimate when every ingredient has a price.
- [x] A backwards period and one over a year are both refused with `KMS-4988`.
- [x] The screen points a temple at `DONATION_IN_KIND` for food a kitchen bought that reaches the store.
- [x] A devotee is refused the endpoint.
- [ ] The screen is not offered to a devotee. *(`RequireRole` gates it, but no test asserts it here — provable by hand.)*

