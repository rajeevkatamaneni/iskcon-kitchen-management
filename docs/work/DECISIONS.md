# Decisions on the 2026-09-06 docket batch

What Rajeev has ruled while the batch was being planned. Written by the main session as he answers,
because a decision that lives only in a conversation is lost at the next compaction — and the whole
arrangement in `docs/work/README.md` exists to stop that happening.

This is not the ledger. `DISPATCH.md` says what is being built; this says what was settled and why,
so nobody re-opens it. Where a ruling changes a task, the ledger cites the section here.

---

## D-1 · Non-food procurement is in scope, and it is two things, not one

**Ruled by Rajeev, 2026-09-07.** Buying non-food is a real need — LPG, single-use plates and cups,
spoons and forks, cleaning and dishwashing supplies, first aid kits, hand soap, "and much much
more". Sometimes from the same vendors as food, often not: plastic stools come from a furniture
store and extension cords from an electrical one, and the temple cannot be expected to keep those
in a vendor list ahead of time.

The list splits along a line that matters to the code, and both halves are in scope:

**Consumable supplies** — LPG, disposables, cleaning and dishwashing supplies, hand soap, first
aid. These behave exactly as ingredients already do: bought from a vendor by weight or count,
received, stored, used up, wanted back when they run low. `ingredients` is already nine-tenths a
general catalogue of purchasable things — `category` is deliberately free text ("temples add their
own, and a constraint would make each a migration", `V10__ingredients.sql:21`) and `canonical_unit`
already includes *pieces*. So this is **a flag separating food from supplies**, plus pickers that
filter on it so leaf plates never appear in a recipe. Everything downstream — `inventory_items`,
stock movements, low-stock alerts, receiving, PO lines — keys on `ingredient_id` and is unchanged.

**One-off durables** — four plastic stools, two extension cords. These want the opposite: no
catalogue entry invented for a stool, and nothing landing in stock. `purchase_order_lines.ingredient_id`
(`V26__purchase_orders.sql:57`, currently `NOT NULL`) becomes nullable with a `description` beside
it and a check that exactly one of the two is present.

**Rejected:** a parallel `supply_items` table with its own stock ledger. It duplicates the entire
inventory chain to express a difference that is one boolean.

**Deferred, not rejected:** the funded-wish-list → purchase → equipment-record chain. It is the
larger design the docket correctly flags as connecting nothing to nothing, and it is not release one.
A receipt stays a receipt for now.

## D-2 · A manual PO creates a real vendor row, not typed-in text

*Superseded on the interaction by D-7 — the vendor is picked on a screen of its own, not inline. The reasoning below about why it must be a real vendor row still stands.*

**Ruled by Rajeev, 2026-09-07**, accepting the counter-proposal to free text.

Rajeev's need: "We can't always have a vendor in the vendors list." True, and the fix is to let the
list grow at the moment of need rather than to route around it.

Free text on the PO was rejected because `purchase_orders.vendor_id` is `NOT NULL` and invoices,
payments, receiving and vendor spend all hang off it — a PO carrying only a store name silently
loses the ability to be invoiced or paid, and "Reliance Fresh" typed three ways becomes three
stores that never merge. A vendor is already almost nothing: name and phone are its only required
fields (`CreateVendorRequest`), so creating one inline is the same keystrokes as typing a name, and
the second purchase from that electrical store finds it by typeahead.

The real obstacle was `vendors.phone NOT NULL` with an E.164 check (`V24__vendors.sql:18,34`),
required because the phone **is** the WhatsApp destination a PO is sent to. That reason does not
apply to a shop you walk into. So the constraint is relaxed, and a WhatsApp send against a vendor
with no phone is refused with its own error code that says why.

## D-3 · The UI must reuse an existing pattern, not invent one

**Rajeev's constraint, 2026-09-07, and it is binding on the task:** inline vendor creation must be
"seamless… smooth and frictionless", must not feel "forced and out of place", and must keep the UI
language "as consistent with the other parts of the app as possible to avoid a one-off learning
pattern for the users."

So the design is not the builder's to invent. The closest existing analogue is the service-company
list built for equipment servicing (E3-S10/E3-S11, 2026-09-05), which solves the same problem —
pick from a small list, or add one that is not in it yet. **An inventory of the existing patterns is
being taken before this task is written**, and the task will name the pattern it copies. If it turns
out no such pattern exists anywhere in the app, that is itself a finding to put to Rajeev before
anything is built, not a licence to invent.

See also `docs/DESIGN_SYSTEM.md`, and the standing rule that an approved mockup is a specification.

## D-4 · Two new permissions, Temple Admin alone. No new roles.

**Ruled by Rajeev, 2026-09-07** — first that a new role would be acceptable if it met the need, then,
on the main session's advice, that permissions meet it and no role is needed. Recommendations
accepted in full.

This is not a new idea: `RolePermissions.java` already does exactly this three times, each with its
reasoning written into the file. When one act inside a broad domain carries money, religious or
permanent-record weight, a narrow permission is split out and given to the Temple Admin alone —
`MANAGE_STAFF_CONDUCT_NOTES` out of `MANAGE_STAFF`, `MANAGE_EQUIPMENT_SERVICING` "narrower than
`MANAGE_INVENTORY` on purpose (E3-S10 D10)", and `APPROVE_LARGE_STOCK_ADJUSTMENT`. These are the
fourth and fifth instances. The enum goes from 36 permissions to 38; the five roles stay five.

**`VOID_DONATION` — TEMPLE_ADMIN only.** Forced rather than chosen: `VIEW_DONATIONS` is already
Temple Admin alone, so anything wider would let somebody void a record they cannot read.

**`CORRECT_RECORDED_MEAL` — TEMPLE_ADMIN only.** Recording stays on `MANAGE_MEAL_PLANS`
(`MealServiceController` `POST /record`) — everyday kitchen work held by admin, manager and staff
alike. Correcting is a different act: it rewrites a number that stock consumption, cost-per-serving
and every materials figure have already inherited. Its nearest sibling is
`APPROVE_LARGE_STOCK_ADJUSTMENT`, and the file's own note on kitchen staff says a correction is "a
decision for temple leadership, not something to resolve mid-shift."

Admin-only rather than also Kitchen Manager, on an asymmetry: widening it later is one line in a
diff; narrowing it after temples have built a habit around it is a conversation with every one of
them.

Shipping this makes KMS-4962's text false the day it lands, so that string changes with it.

**Both lines go into `RolePermissions.java`, a reserved file** — the work manager writes them once
before the wave, and no builder opens it.

## D-5 · Recording a donation stays on `MANAGE_INVENTORY`, knowingly

**Ruled by Rajeev, 2026-09-07**, accepting the recommendation to leave it.

Found while answering D-4: `DonationController` `POST /donations` is gated on `MANAGE_INVENTORY`,
which kitchen staff hold, while `VIEW_DONATIONS` is Temple Admin alone. So **a cook can create an
80G-relevant ledger entry they can never read back.**

There is a real reason for it: an in-kind gift is a sack of rice arriving at the gate, and the
person receiving it is a cook, so it is goods receipt as much as it is a donation. A cook logging
receipt and an admin reconciling is a defensible split, and separating cash from in-kind gifts is a
bigger change than this batch should carry.

Recorded rather than fixed, so it is inherited deliberately and not by accident. If it is to be
fixed, it is its own task.

## D-6 · Every error code becomes six digits, in one sweep, now

**Ruled by Rajeev, 2026-09-07.** `KMS-nnnnnn`: client errors from **400001**, server errors from
**500001**, allocated flat in declaration order. The hundreds no longer carry meaning.

**Why now.** The 4900s conflict band was full — 103 of 154 codes had crowded into it, while
validation used 10 of its 100 and not-found 5. The bands mirrored the HTTP status, which every entry
already stores as its own field, so the number duplicated data and constrained allocation for
nothing. We are pre-release with no manuals written, so this is the last moment it is free.

**Why six digits rather than a flat four.** The main session first proposed 4001–4999 / 5001–5999.
Rajeev proposed going wider, and he was right: with 129 client codes a four-digit flat scheme lands
on 4001–4129, which **overlaps codes already in use** — KMS-4102 means `SESSION_EXPIRED` today and
would come to mean something else, so an old code quoted from a screenshot or a commit gives an
answer that is wrong while looking valid. No four-digit code is a valid six-digit code, so the old
and new namespaces are disjoint and no code ever means two things. That is the property worth having.

**On reading it aloud.** The main session raised that six digits is worse down a phone, which is the
codes' stated purpose. Rajeev overruled it — "Reading loud is not a bad thing" — and that is the
call.

*His two messages differed: the numeric example was five digits (`40001–49999`), the pattern he then
named was six (`KMS-nnnnnn`). Built as six, as the later and more explicit of the two, and flagged
to him while it was still free to change.*

**Consequences accepted:** two lines change in `ErrorCodeTest.java` (the `KMS-\d{4}` assertion and
the `number() / 1000` family derivation); ~709 literal references are swept from a generated
mapping; `PROJECT_COMMANDMENTS.md` is locked at v1.0 and says `KMS-nnnn`, so it changes under
Rajeev's sign-off with a changelog entry. `docs/ERROR-CODE-RENUMBER-2026-09-07.md` carries the full
old→new table, because git history and his own review notes quote four-digit codes and cannot be
rewritten.

**Sequencing:** this ran **alone, and first**, as wave 0. Everything else in the batch allocates from
the new scheme rather than being renumbered a week after being written.

## D-7 · The manual PO asks for its vendor on a screen of its own, first

**Ruled by Rajeev, 2026-09-07**, overturning D-2's inline picker.

Route out to `/vendors/new` and return with the vendor selected — but with the vendor asked **first,
on its own screen**, so routing out costs nothing: nothing has been entered yet, so there is no
draft to lose and no half-filled PO to restart. `/orders/new` asks one question, with an
"Add a vendor" `ButtonLink` beside the dropdown; choosing leads to the lines.

**Why this shape and not one screen with a preserved draft.** One screen would need two mechanisms
the app has never had — a `returnTo` param (zero uses anywhere) and a form draft in `sessionStorage`
(which holds only the sidebar's scroll position today) — and restoring state in an effect is the
exact shape that already bit this codebase once, in the ref-guarded flash-banner loop. The two-screen
shape invents nothing: `FocusScreen`, a native `<select>`, a `ButtonLink`, and the `?added=` flash
already used 13 times.

It is also true to the domain: the vendor is not a field of a purchase order, it is the order's
identity, and `orders/[id]` already refuses to change it afterwards ("Cancel this order and raise it
against the right…").

**On the anti-wizard note** in `tenants/new` ("One form, not a wizard"): judged not to reach here.
That reasoning is about not splitting *one record* across screens — a temple and its first
administrator are created together. A vendor is a prior, separate, immutable choice.

**D-2 is superseded on the interaction only.** Its substance stands: the PO still creates a real
vendor row rather than holding typed-in text, because invoices, payments, receiving and spend all
hang off `vendor_id`. And `vendors.phone` is still relaxed from `NOT NULL`, since a shop you walk
into has no WhatsApp destination.

## D-8 · Giving is for volunteers only. Staff already serve.

**Ruled by Rajeev, 2026-09-07:** "Admins shouldn't be asked for money by their own admin app…
Same rule applies for Temple staff too. They are already serving which is donation enough."

`/donate` narrows to `VOLUNTEER` alone — **both** the page guard in `app/donate/page.tsx` (today
`TEMPLE_ADMIN, KITCHEN_MANAGER, KITCHEN_STAFF, VOLUNTEER`) and the `nav.ts` row.

**This reverses what wave 1 shipped**, and the reversal is the point. The docket found that the page
admitted Temple Admin while the menu did not, breaking `nav.ts`'s own rule that an item carries
exactly the roles its destination allows. There were two ways to close that gap — widen the menu or
narrow the page — and wave 1 took the cheaper one without asking. Rajeev's answer is the other one.
*The lesson is not about donations: when a task can be closed from either end, which end is a
product decision and goes to him.*

**The backend stays `isAuthenticated()`** on `POST /donations/one-time` and
`/donations/wishlist/{itemId}`. Nobody is harmed by a staff member who insists on giving through the
API, and minting a permission to prevent it is ceremony. Recorded so the inconsistency is deliberate.

Scheduled into wave 2 rather than shipped alone — it is two lines plus test updates, and a full
CI-and-deploy cycle for that is waste.

## D-9 · `SESSION_EXPIRED` is deleted, and `KMS-400018` is retired forever

**Ruled by Rajeev, 2026-09-07.** The code was declared with finished copy — "Your session has
expired. Sign in again to continue." — and thrown nowhere, because `TokenVerifier` deliberately
refuses to say *why* a token failed. Rather than carve an exception into that, the message goes.

**Why deleting is right and not a loss.** The browser already handles the case a real person meets:
`SessionGuard` runs a one-hour idle limit with a warning before it fires, so somebody who steps away
is told cleanly without the server involved. And `auth-context` fetches a token per request
precisely so it never goes stale. A server-side "expired" is therefore rare and usually means
something odd — exactly the case you do not want to explain to whoever is asking. An error code that
exists and is never thrown is worse than none: the next person reads `ErrorCode.java` and believes
the app says something it does not.

**`KMS-400018` is retired, never reallocated.** Codes are permanent in both directions — a number
that has been declared does not come back meaning something else. The enum goes to 127 codes, and
400018 stays a gap. Record it alongside the four no-successor codes in
`docs/ERROR-CODE-RENUMBER-2026-09-07.md`.

**Closes Question 8**, the last thing gating T-003, which is otherwise shipped. Two test files carry
comments calling this an open question (`AuthenticationFailureIT.java:213`,
`session-failures.test.tsx:220`); both want rewriting to say it was decided, not deferred. Scheduled
into wave 2.

## D-10 · Volunteering and giving are for outside people. Employed people do neither.

**Ruled by Rajeev, 2026-09-07, and it is the general rule the previous three questions were each
groping at:** "ALL people employed by the temple will never donate and never volunteer. Irrespective
of their role: Admin OR Manager or cooks. Volunteering and Donations are JUST for outside people who
sign up as volunteers."

**His reasoning, which matters more than the rule:** they have no financial means to donate — a
temple kitchen wage is not a king's ransom — and no time to volunteer, because they are already
working. Both halves are facts about the people, not policy about the software.

That is why this is a guard and not just a hidden menu row. If the reason were "the page would be
empty" then hiding the link would do. The reason is that offering a Donate button to somebody
earning a modest temple wage, or a *sign up for seva* link to somebody already working a double,
is tone-deaf — and a tone-deaf screen reached by typing the URL is still tone-deaf. So the page
refuses them too.

So `VOLUNTEER` is not "a staff member who also helps" — it is a different kind of person, and the two
sets do not overlap. Everything giving-shaped or seva-shaped narrows to `VOLUNTEER` alone, page guard
and menu row together:

| Surface | Was | Becomes |
|---|---|---|
| `app/donate/page.tsx` guard | `TEMPLE_ADMIN, KITCHEN_MANAGER, KITCHEN_STAFF, VOLUNTEER` | `VOLUNTEER` |
| `nav.ts` `/donate` | `ADMIN, VOLUNTEER, MANAGER, KITCHEN` | `VOLUNTEER` |
| `app/my-shifts/page.tsx` guard | `VOLUNTEER, KITCHEN_MANAGER, KITCHEN_STAFF` | `VOLUNTEER` |
| `nav.ts` `/my-shifts` | `VOLUNTEER, MANAGER, KITCHEN` | `VOLUNTEER` |
| `app/shifts/page.tsx` and its row | `VOLUNTEER` | unchanged, already right |

**Subsumes D-8**, which reached the same place for donations by a narrower argument.

**Answers the `/my-shifts` question** left open from wave 1 — the row goes — but for a better reason
than the work manager's. Its argument was "the page is structurally empty for them", which is true
and is a symptom. The rule is that it was never theirs.

**The backend is already right for volunteering** and needs no change: `SIGN_UP_FOR_SHIFTS` is
granted to `VOLUNTEER` alone in `RolePermissions.java`. The UI was offering what the server would
have refused. Giving stays on `isAuthenticated()` per D-8 — a staff member who insists on donating
through the API harms nobody, and minting a permission to stop them is ceremony.

**Staff are not left with nothing.** Their own rostered work is a different screen on a different
permission — `VIEW_OWN_SHIFTS`, which admins, managers and cooks all hold, served today by
`GET /staff/schedule/me` with no caller. T-006 builds it in wave 2. *My shifts* means seva; *My
schedule* means your work. Removing the row does not need to wait for T-006, because the row was
never theirs to begin with.

> **These two are findings for the permissions review, not work to schedule.** Rajeev said on
> 2026-09-07 that he intends a **comprehensive permissions review once the planned build series is
> finished**, and adjusting roles piecemeal across waves would be worse than one deliberate pass.
> Nothing here goes into a wave. It is written down so the review starts with the evidence rather
> than rediscovering it. Waiting costs nothing: staging carries no real temple.

## D-11 · What a cook may actually do — a narrowing, and the domain fact behind it

**Ruled by Rajeev, 2026-09-07**, from two annotated screenshots of a real cook's sidebar. He signed
in as `ikms.kitchen-staff.1` (Gopal Das) and struck out every menu row a cook has no business seeing.

**A cook keeps:** Today, Vaishnava calendar, Recipes, Ingredients, Inventory, Equipment — and the
**Meal planner, read-only**: "that will only benefit them. Cant hurt anything."

**A cook loses:** Reuse a plan, My shifts, Donate, the whole Ordering section (Shopping list,
Purchase orders, Vendors, Vendor performance, Invoices), Ingredient requests, Issued from store,
Cost per serving, Volunteer shifts, Donations.

`RolePermissions.java` today grants `KITCHEN_STAFF` nine permissions including `MANAGE_MEAL_PLANS`,
`MANAGE_VENDORS`, `MANAGE_PURCHASE_ORDERS` and `MANAGE_VOLUNTEER_SHIFTS`. **A cook can raise a
purchase order.** This is a permissions defect, not a menu one — hiding rows would leave every URL
working, exactly as in D-10.

### The read-only planner needs a permission split

`MANAGE_MEAL_PLANS` gates **40 endpoints across 13 controllers — 32 reads, 8 writes.** So: a new
`VIEW_MEAL_PLANS` for reads a cook should have, `MANAGE_MEAL_PLANS` keeps the writes.

**Not mechanical.** Cost per serving (`MaterialsCostController`), Issued from store
(`IssuedFromStoreController`) and the crew endpoints (`MealCrewController`, `CrewCoverageController`)
are all on `MANAGE_MEAL_PLANS` today and were all struck out — so each of the 32 reads needs a
judgement, not a find-and-replace. The planner page also needs a genuine read-only mode: composer,
edit affordances and Record actuals all conditional. That UI half is the larger one and wants
Rajeev's eye before it ships.

### Why ingredient requests are not a cook's screen — the domain fact

**Not previously written down anywhere, and it changes what the feature is.** A temple runs 3–5
kitchens, sometimes 10 or more, all drawing from **one common store — the temple's grocery shop**.
Not every kitchen will use the app: some want ingredients and no part of meal plans or head counts.
**Ingredient requests are that kitchen's door to the store.**

A cook executing a job card never needs it, because planning, the shopping list and issuing already
guarantee the ghee is there.

The application already models this and it is load-bearing: the kitchen's *"This kitchen plans its
meals here"* flag drives `MealPlannerAdoption`, the costing split (`IssuedFromStore`,
`KitchenIssueCost`) and `IngredientRequestService` — a planning kitchen draws `CONSUMPTION`, a
store-only kitchen raises requests, and an error code refuses a request from a planning kitchen.

## D-12 · Every kitchen has a manager, because the dropdown will only offer managers

**Ruled by Rajeev, 2026-09-07**, answering the objection that removing `REQUEST_INGREDIENTS` from
cooks would shut the store-only kitchen's door.

*"When we register a kitchen, we are asking, who runs it. That drop down should ONLY show people
with Kitchen manager roles. That way we can ensure EVERY kitchen gets a manager assigned."*

`KitchenForm.tsx:169` filters candidates on `u.status === "ACTIVE"` — every active user of any role.
It must filter to the `KITCHEN_MANAGER` **role**. Then every kitchen is run by somebody holding
`REQUEST_INGREDIENTS` by construction, and a cook never needs it.

**Two things to settle when this is built:**
- **Existing assignments break.** Diatee Kitchen is run by Gopal Das, whose role is `KITCHEN_STAFF`.
  The form already keeps the incumbent visible (`|| u.id === initial?.inChargeUserId`), so it will
  not silently drop him — but somebody must be promoted or reassigned. Related: there is still no
  screen to change a role (T-018).
- **May a Temple Admin run a kitchen?** In a small temple the admin may well be the person. The
  ruling says managers only; worth one line of confirmation before it is enforced.

### The trap this all came from

**"Kitchen Manager" names two unrelated things.** `staff_profiles.job_title` is one of 17 job titles
and is what the staff register displays. `users.role` is what `navForRole` draws the menu from and
what `RolePermissions` grants against. Hiring Gopal Das with the *job title* Kitchen Manager left his
*role* at `KITCHEN_STAFF`. The register says Kitchen Manager and he has a cook's permissions, and
nothing on screen explains why. Worth fixing in the words on the screen, not only in the data.

---

## D-13 · A temple's profile is edited by the operator alone. Question 7 is closed.

**Ruled by Rajeev, 2026-09-07** — *"operator only"* — against the recommendation, which had proposed
splitting the fields so a temple admin could correct its own address and contact details.

So **T-008 builds exactly what its row already assumed**: the new `PATCH /api/v1/tenants/{id}` sits
behind `MANAGE_TENANTS`, no new permission is created, and the screen stays at
`/tenants/[id]/edit` — operator territory, beside the provisioning flow — rather than appearing
anywhere under `/settings`. Nothing in the row changes; the "pending Question 7" caveat simply
resolves in favour of the default it named.

What the recommendation had argued for, recorded so the trade is not re-litigated from memory: that
address, coordinates and contact details are the temple's own facts, are what testers most often get
wrong, and that routing a typo in a street name through an operator is friction. That was heard and
declined.

What it means in practice, stated plainly because it is the cost of the ruling: **a temple cannot fix
its own address.** Every correction — a moved kitchen, a new phone number, a misspelled street — is an
operator ticket. That is a smaller product than the split would have been, and it is deliberate. The
counterweight is that the ruling keeps a single, auditable answer to *"who may change what a temple
is"*, and it keeps the two genuinely dangerous fields on the operator's side without having to
justify a field-by-field permission boundary: **timezone**, which silently rewrites `calendar_days`
and makes every "today" in the product disagree with the panchanga until the precompute re-runs, and
**`is_80g_approved`**, which is a legal status no temple should be able to assert about itself.

Widening this later is additive — a second, narrower endpoint over the safe fields — and costs
nothing that is built now.

## D-14 · A shift says which meal it is for. Question 9 is closed, against the recommendation.

**Ruled by Rajeev, 2026-09-07**, overruling a recommendation to keep time-window matching:

> *"Having enough raw ingredients and enough people at the right time are the two main things the
> Kitchen Management App must ACE. Everything we built around it is functionality that makes it a
> feature rich system. When it comes to the 2 main core things it has to ace, we cant leave ANYTHING
> on the table no matter how hard it is. So explicit link it is."*

The recommendation had argued the link was not worth a migration. That weighed the cost correctly and
the stake wrongly: this is not a convenience on the planner, it is one of the two things the product
exists to get right, and a crew figure that is quietly wrong is worse than one that is missing,
because nobody goes looking for it.

**The ruling is also more accurate than the recommendation admitted, in both directions.** Today a
volunteer signed up 06:00–10:00 to cut vegetables for lunch counts toward *breakfast*, because
breakfast is what is due at 08:00. So the clock rule does not merely miss lunch — it **inflates
breakfast** with hands that are committed elsewhere. The explicit link fixes an under-count and an
over-count at once. That is the case for it and it should have been the recommendation.

### What a shift is linked to, and why it cannot be a foreign key

Found while designing this, and it changes `V105`'s shape entirely: **there is no meal table.**
`ServedMeal`'s own doc says it — *"There is no meal-line table: one `meal_plans` row is one dish, and
a lunch of three dishes is three rows carrying the same date, kind, head count and ready-by."* A meal
is an inference, assembled by `ServedMealService.list` by grouping dish rows on
`Key(plan_date, meal_kind, event_name)`.

There is a `meal_services` row, and it is tempting, but it is **null exactly when we need it**:
`ServedMeal.serviceId` is documented as null *"when the meal has neither been carded nor recorded and
so has no row of its own yet."* A shift is posted while planning — weeks before carding. So at the
moment of linking there is nothing to point at.

So the link is the **natural key, carried on `shifts`**: `meal_date`, `meal_kind` and
`meal_event_name`, all nullable, with a `CHECK` making them all-present or all-absent. No FK is
possible for date or event name; `meal_kind` may reference `meal_kinds` if that table's key allows.

**The trap that will silently break this if it is missed.** `Key.of` normalises the event name —
null or blank becomes `""`, and the rest is `trim().toLowerCase(Locale.ROOT)`. Matching a linked
shift to a meal must apply the identical normalisation. Get it wrong and the link matches nothing,
the count reads zero, and it looks exactly like a shift nobody signed up for.

### The two rules, and why the second is not a compatibility hack

A **linked** shift counts toward its meal and toward no other, whatever the clock says. An
**unlinked** shift keeps today's behaviour: it counts toward every meal whose ready-by time its
window spans.

That is not a fallback bolted on to protect old rows, though it does protect them — every shift that
exists today is unlinked, and every shift posted from the Volunteers page will be. It is a real
distinction with a name: **an unlinked shift is a general offer of hands, matched by the clock; a
linked shift is hands committed to one meal.** A devotee who says "I can help Saturday morning" is
the first. A devotee called in for Janmashtami lunch prep is the second. Both are true things a
temple says, and the model should hold both.

It also keeps the festival all-dayer working: a shift 06:00–22:00 left unlinked counts toward all
three meals, which is correct, because the person really is there for all three.

### What this costs, stated plainly

`MealMoment(date, readyBy)` no longer carries enough to answer the question — it needs the meal's kind
and event name too. That record lives in the `staff` package deliberately (*"it is the question the
roster is asked"*), and it stays there; it just gets asked a fuller question. The change ripples
through `WorkforceService.countAt`, `MealCrewService.crewFor` and `crewIfAway`.

**Numbers Rajeev has already seen will move.** Where a lunch-prep shift overlaps breakfast, breakfast's
volunteer count drops once those shifts are linked. That is the over-count being corrected, not a
regression, and it is worth expecting rather than discovering.

### Consequence for the plan

This is no longer the frontend task in wave 8 that its row describes. It is a migration plus a change
to the core crew calculation and its tests, and T-019's planner layer sits on top of it. It is split
in two and the model half is promoted out of wave 8 — see the ledger.

## D-15 · Scrapping stays terminal, and is reversible exactly once, by name, with a reason

**Ruled by Rajeev, 2026-09-07**, answering Question 13 and adding to it:

> *"Scrapped items should not be able to have condition change Or anything done to them. Also, if
> some one accidentally scraps an item OR wants to bring back a scrapped item because they cant find
> a replacement, that should be possible and a reason recorded and visible in the audit trail."*

Read as one rule rather than two, this is the same shape as every other correction in the product:
**the ordinary path stays closed, and there is one explicit, named, audited way back.** It is not a
loosening of terminality — `changeCondition` goes on refusing every post-scrap edit with
`EQUIPMENT_SCRAPPED` **`KMS-400043`**, unchanged. Reinstatement is a different act with a different
name, and it has to be asked for deliberately.

**T-009 (wave 2) is unaffected and correct as built.** It adds the confirmation that stops an
accidental scrapping; this adds the way back when the confirmation was clicked through anyway. The
two are complementary and neither replaces the other.

### It needs no migration, which was not obvious

`equipment_state_changes` (`V16__equipment.sql:54-70`) already permits `from_condition = 'SCRAPPED'`
— the CHECK lists all four conditions on both ends — and already has `reason TEXT NOT NULL`,
`actor_user_id` and `created_at`. So a reinstatement is **representable today** as a state change
from `SCRAPPED` to a live condition with a reason. Nothing about the trail has to change; the only
thing standing in the way is the service's guard, and the guard is right where it is.

### The shape

A separate endpoint — `POST /api/v1/equipment/{id}/reinstate` — taking the condition it comes back
as and a **required** reason. Permitted only from `SCRAPPED`; refused with a new code when the item
is not scrapped, mirroring `EMPLOYMENT_NOT_ENDED` on T-014. It writes the state-change row and
audits under its **own** `AuditAction`, not `EQUIPMENT_CONDITION_CHANGED` — Rajeev asked for it to be
visible in the audit trail, and a reinstatement filed under the same action name as an ordinary
repair is visible only to somebody already looking for it.

Why a separate endpoint and not a flag on `changeCondition`: the guard in `changeCondition` is the
thing keeping scrapped items inert, and a request body that can switch it off is a guard in name
only. Two endpoints means the refusal stays unconditional in the code that enforces it.

**Permission: `REINSTATE_SCRAPPED_EQUIPMENT`, `TEMPLE_ADMIN` alone**, by the same reasoning D-4 gave
for `VOID_DONATION` and `CORRECT_RECORDED_MEAL` — undoing a disposal is a different kind of act from
recording one, temples build habits around whoever can do it, and widening later is one line while
narrowing later is a conversation with every temple. **Confirmed by Rajeev, 2026-09-07** — it was
flagged as an assumption and he ruled it explicitly: Temple Admin alone.

The register keeps hiding scrapped items by default (`list(includeScrapped=false)`), so the way to a
reinstatement is through the scrapped filter — the reader has to go and find the thing they scrapped,
which is the right amount of friction.

---

## Still open

Tracked here so the count is honest; the full thirteen are in `INTAKE.md`.

- Temple-health indicator: what sits behind the dot, and where it lives.
- Day-one dataset: still parked?
- Operator audit drill-in: Rajeev asked to see the existing `/audit` first.
- ~~Who edits a temple's profile — operator or temple admin?~~ **Closed 2026-09-07 by D-13:
  operator only.**
- ~~Planner shift: match by time window, or an explicit link?~~ **Closed 2026-09-07 by D-14:
  explicit link, with the clock rule kept for unlinked shifts.**
- B8: leave the recorded decision against a `CANCELLED` request state, or overrule it?
- ~~Equipment `SCRAPPED` stays terminal, confirmation only?~~ **Closed 2026-09-07 by D-15: terminal,
  plus a named audited reinstatement.**
- ~~**Should kitchen staff and managers keep `/my-shifts` in their menu?**~~ **The question is
  answered — by D-10, outright and for a better reason: the row was never theirs.** What is *not*
  settled is whether that half of D-10 may be built now; see the scheduling question below. The
  original entry described it as "reverted pending your answer", which was true when written and
  stopped being true when D-10 was ruled the same day.
- **Does D-10's closing blockquote park the whole of D-10, or only D-11 and D-12?** It says *"These
  two are findings for the permissions review, not work to schedule… Nothing here goes into a wave"*
  and it sits physically at the end of D-10 — but D-8, which D-10 subsumes, says its donate change is
  *"Scheduled into wave 2"*, and that change shipped as T-030. The two records disagree. My reading is
  that the blockquote belongs to **D-11 and D-12** and is misplaced: it talks about *"adjusting roles
  piecemeal"*, which describes those two permission-model changes and does not describe D-10's page
  guards and menu rows. Under that reading D-10's `/my-shifts` half is buildable now, is the same
  shape as T-030, and leaving it undone means the tree carries a guard and a nav row that agree with
  each other and disagree with D-10. **Rajeev's to settle.**

---

## A blocker found while trying to smoke-test wave 1

**Wave 1 could not be hand smoke-tested, and neither can most of the UAT pack, for the same reason:
there is no way to sign in as kitchen staff.**

Those accounts have app records but **no Firebase account** — they were hired through `/staff`, which
creates a `pending:` user that binds on first Google sign-in (E1-S6 claim-on-match). Nobody has ever
signed in with those Google addresses, so `ikms.kitchen-staff.1…5` exist on the staff register and
cannot be authenticated as. The two original accounts (`ikms.temple-admin.1`, `ikms.volunteer.1`) are
Google-only and have no password. Only the volunteer and donor accounts created on 2026-08-19 take
`!kms1234`.

**Scale of it: 31 of the 47 UAT stories in `docs/uat/README.md` are assigned to Kitchen staff.**
Against 8 for Temple admin and 3 for Volunteer. So roughly two-thirds of the formal pack is written
for a role nobody can be. The docket records that "the formal UAT pack has never been run by a human"
without giving a cause; this is a large part of the cause.

It bites wave 1 directly: four of T-002's five refusing controls are kitchen-staff or manager cases,
and T-003's headline case needs a *disabled* account. All of them are verified by automated tests
against a real database, and the shipped bundle was grepped for the new strings — but Commandment 5
wants eyes on a screen, and for these roles there are none to be had.

**Not acted on deliberately.** Fixing it means either creating Firebase accounts for those addresses
or signing in once with each Google address, both of which change the UAT environment, and neither
was asked for. Rajeev can smoke-test the temple-admin cases as himself in under a minute; the rest
needs a decision about those accounts.

*The main session also declined to drive Rajeev's own Chrome for the volunteer-only slice of this:
signing in as a test volunteer would replace whatever session he already has on staging, and he would
wake up logged in as somebody else. Not worth it for one branch of one empty state.*

