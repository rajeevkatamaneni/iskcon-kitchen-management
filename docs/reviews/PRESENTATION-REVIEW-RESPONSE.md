# Response to the presentation review

**Source:** `KMS Review.md`, comments from the viewers of the two demo videos.
**Written:** 2026-08-30. **Status:** for Rajeev. Nothing here changes a locked document.

Eighteen comments, in the reviewers' own order and numbering. For each: what they said,
what I think of it, and — where it is worth building — what it takes and how big it is.

Every claim about what the code does or does not do was checked against the code, and the
file and line are given. Where the reviewers are right I say so plainly; where I think they
are wrong I say that too, with the reason.

**Sizes.** XS: under a day. S: a day or two. M: about a week. L: two to three weeks.
XL: a month or more, and it wants its own epic.

## The short version

| | Comment | Verdict | Size |
|---|---|---|---|
| MP1 | Ekadashi-only recipe filter | Built in the backend, never wired to the screen | S |
| MP2 | Filter recipes by stock | Right need, wrong mechanism — a filter would starve the purchase plan | M as a flag |
| MP3 | Inventory status, red on risk | Already built, per preparation. Two real gaps behind it | M |
| MP4 | Price per meal by category | Valid, and it means revisiting E3-S8 D3 | L |
| INV1 | Moving-average cost from invoices | The best comment in the document. I'd do something better than a moving average | M then L |
| INV2 | Utilities and maintenance in the meal price | Valid ambition, no expense ledger exists to build it on | XL |
| INV3 | Market prices via agentic AI | Push back. Right instinct, wrong place to put the number | M as an advisory |
| INV4 | Cost centres, not kitchens | Premise is a misreading; the report they want is a week's work | M |
| INV5 | The Deity kitchen buys its own things | Correct, and it puts a condition on INV4 | XS |
| OL1 | Rename Order List to Purchase Plan | Agree with the reasoning, would offer a better word | M |
| OL2 | Does the plan use future meal plans? | Yes, already. But it uncovered a docs-vs-code divergence | S |
| V1 | Validity dates and reactivation remarks | Half already there and broken; half I'd argue against | S |
| V2 | Vendor performance report | Valid, wanted, and needs no new data capture at all | M |
| INVOICE1 | Import payments from Accounts | Valid, and it is the first file import in the whole system | L |
| DEV1 | Integrate Zoho CRM for devotees | Already formally rejected once. One question worth asking back | XL if built |
| STAFF1 | Remarks to record behaviour | Valid gap, and the obvious fix is the wrong one | M |
| STAFF2 | Import salaries and advances from Finance | Decline until somebody can name the reader | L on top of INVOICE1 |
| SS1 | A heatmap of the gaps | The sharpest comment here. Both halves exist and have never met | M |

## Two things the review found that it was not looking for

**The documents promise a setting that does not exist.** `REQUIREMENTS.md:146` says the order
list is computed "over a configurable horizon". `EPIC-5-ordering-vendors.md:36` promises a safety
factor and a lead buffer as "tenant config, default 1.2×" and "default 2 days".
`EPIC-4-meal-planning-calendar.md:145` promises "a configurable horizon (default: through 14 days
+ any festival within 30)". None of it is configurable. All four numbers are `private static final`
in Java — `SufficiencyService.java:35-36` and `OrderListService.java:44-45`. A temple that buys
weekly and one that buys monthly get fourteen days each. This is the kind of divergence
Commandment-level document discipline is supposed to prevent, and it slipped through.

**There is no file import anywhere in the application.** Not "no importer for invoices" — no
upload of any kind. Zero occurrences of `MultipartFile`, of `multipart/form-data`, of
`<input type="file">`; no multipart configuration. Data leaves (the donations CSV) and never
arrives. Even the delivery-note scan on a goods receipt is a text box where somebody types the
name of a file nothing uploads (`ReceiveDeliveryRequest.java:16`). Two separate comments
(INVOICE1, STAFF2) walked into this independently, which is usually the sign of a real hole
rather than a wish.

---

# Video 1 — Meal Planner

## MP1. A checkbox to display only Ekadashi meals

**What they said.** On a fasting day, let the planner see only the preparations that are allowed.

**What I think.** Valid, and embarrassing in a small way: we built the engine and left the switch
off. `RecipeService.list(..., ekadashiCompatibleOnly)` exists and works —
`RecipeService.java:87-92` excludes any recipe with a line whose ingredient is
`is_ekadashi_prohibited`. The controller accepts it (`RecipeController.java:82`). There is an
integration test for it (`EkadashiFlaggingIT.java:199`). The frontend simply cannot send it:
`RecipeFilters` in `lib/api.ts:501-506` has no such field, and all four callers pass `{}`.

Where I would not follow them is the control. A checkbox is the wrong shape on a day the calendar
already knows is Ekadasi. Asking a planner to remember to tick a box is asking them to remember
the fast. On a fasting day the picker should open already filtered, with a way out
("Show grain preparations too") for the person who knows what they are doing.

What we do today is the opposite and it is worse: show everything, let them pick a grain dish,
then challenge it afterwards (`MealComposer.tsx:458`, `api.ekadashiCheck`). Confirmation after
the fact makes somebody undo work they have already done. Not offering the wrong thing is kinder
than objecting to it.

**The fix.** Add `ekadashiCompatible` to `RecipeFilters` and to `listRecipes`; in `MealComposer`,
default it on when the day is a fasting day and offer the escape hatch. Keep the post-hoc check
as a backstop — a recipe can be edited after it was chosen.

**Size: S.** Frontend only. The backend and its test are already paid for.

## MP2. Auto-filter to recipes whose stock is or will be present

**What they said.** Only show me the dishes I can actually cook.

**What I think.** The need is real. The mechanism is wrong, and I would argue against it fairly
hard, for three reasons — the third of which is a genuine defect rather than a preference.

*One.* Sufficiency in this system is not a property of a recipe. `SufficiencyService` is explicit
about why: a meal is sufficient only if stock covers it **after earlier uncooked meals in the
horizon have taken their share**, so two meals can never both read "sufficient" against one sack
of rice. "Can I cook this recipe?" has no answer until you say how many servings, on what date,
behind what else. A row in a picker cannot honestly carry a green tick.

*Two.* Filtering hides the food you most need to see. If rice is short, hiding every rice dish
means the planner never learns rice is short at the one moment they could do something about it.
They just find a shorter menu and no explanation. Silence is the worst available way to report a
shortage.

*Three, and this is the real problem.* The purchase plan is **driven** by planned-meal shortfall —
it is stream one of three in `OrderListService.java:102-105`. If you cannot plan a meal you are
short for, you never generate the demand that would have bought the rice. The filter would quietly
starve the ordering system that fixes the very shortage it is hiding. That is circular, and it
would be very hard to notice from inside.

**The fix.** Reject the filter; take the need and fold it into MP3 — flag on the row, never hide
the row. A coarse, honest, clearly-indicative flag computed against current stock at the recipe's
default yield.

**Size: M** as a flag. It needs a per-recipe stock read for a date, which wants the shared
"scaled ingredient basket" that `EPIC-3-inventory-management.md` D5 already admits is duplicated
between `SufficiencyService` and `MaterialsCostService` and ought to be extracted.

## MP3. Inventory status in the meal planner; red where there is a risk of under-stock

**What they said.** Show me stock risk while I plan, in red.

**What I think.** This is built, and built as asked. Per preparation, in red:
`MealServices.tsx:336-341` renders `<Badge tone="danger">Short of ingredients</Badge>` for `SHORT`,
success for `SUFFICIENT`, neutral while planning. The double-booking guard is in place. The order
list carries the aggregate as well (`app/order-list/page.tsx:149`).

So the comment is answered — but they did not see it, and that is worth understanding rather than
waving away. Two reasons they might not have:

- It appears only on **saved** meals. Nothing shows sufficiency while a meal is being composed,
  which is exactly when the planner can still change their mind.
- The catch-up screen passes an empty map (`planner/catch-up/page.tsx:70`), so it shows nothing at
  all there.

**The fix.** (a) Populate sufficiency on catch-up — that is a one-line omission. (b) Show it live
in the composer, which needs a what-if variant of the endpoint that costs an unsaved draft rather
than a saved meal id.

**Size: XS for (a), M for both.**

## MP4. Price per meal for categories of preparation — public prasadam, inmate, festival, Sunday feast

**What they said.** A daily total is not enough; we need to compare what a plate costs across the
kinds of meal we serve.

**What I think.** They are right about the granularity, and this one asks us to revisit a decision
we locked. `E3-S8 D3` says, in as many words: *"For the day, not per meal. A per-meal figure would
need the same estimate cut three ways with nothing gained."*

I think D3 is now wrong, and for a specific reason: it was written to answer a different question.
D3's question was *"what is today's food costing us"* — a number for the Today screen, where a
daily total is exactly right. The reviewers' question is *"what does a public-prasadam plate cost
against a Sunday feast plate"*, which is a **comparison**, and a comparison between categories is
precisely the thing a single daily total can never give you. Same data, different question, and
the answer to the second one is not a worse version of the first.

Note what does *not* need revisiting. `D1` — an estimate, honestly labelled, because much of the
store is donated and a gift in kind has a value and no price — stays true and stays right. `D4` —
labour deliberately absent, because a cook on a 6am–2pm shift is making breakfast *and* lunch and
their pay can only be allocated, never measured — stays right, and it also answers INV2 below.

The granularity is not blocked by any of that. `MaterialsCostService.mealsOn(date)` already builds
the per-meal baskets and then **throws the split away** before returning one total. Meals already
carry a kind (`meal_kinds`: Breakfast, Lunch, Dinner, Deity offering, Catering order, Outside
event, Festival feast). The categories the reviewers named are the categories the table already
holds.

**The fix.** Extract the shared scaled-basket code that D5 flags. Key it by meal plan. Cost each
basket. Aggregate by meal kind over a period, and divide by servings for a per-serving figure. New
screen. Every figure carries "estimated, materials only" and the count of unpriced ingredients,
exactly as the daily one does.

Changing D3 needs Rajeev's sign-off and a changelog entry.

**Size: L.** New report, a refactor two services want, and a servings denominator that has to cope
with meals recorded without a headcount.

---

# Video 2 — Inventory

## INV1. Cost at moving average, from the incoming invoices. "ERP has this out of the box."

**What they said.** Value stock at a weighted moving average maintained from the bills we receive,
rather than a hand-typed price. It is for budgeting and for pricing external cooking orders, not
just analytics.

**What I think.** This is the strongest comment in the document, and the one I most want to agree
with. The framing has one piece of sand in it, and the answer I would give is better than the one
they asked for.

**Where they are right, and I would not defend our position.** `vendor_supplies.last_price` is
maintained by hand and has exactly one writer in the entire codebase — `VendorService.setSupply`
(`VendorService.java:131-145`). Receiving writes no price: `grep -n price` in `ReceivingService.java`
returns nothing, and `goods_receipt_lines` has no price column. Invoicing writes nothing back —
`VendorInvoiceService` has one write statement and it is the insert. So the number ages the moment
somebody stops typing it, and nothing in the system notices or says so. Their two use cases are
the right ones: budgeting, and **pricing external catering orders**. We take money for catering.
Pricing it off a stale hand-typed figure is how a temple loses money quietly, and that is a much
better argument than the analytics one.

**Where the sand is.** "The incoming invoices can be input and will be the basis for the
calculation" describes a switch we do not have. We hold the invoice as a document; we do not hold
a price per ingredient on any line of it. ERP has it out of the box because ERP makes the
storekeeper type a rate on every goods receipt line. Somebody types it either way. The question is
who, and when, and we should say so rather than let "out of the box" imply it is free.

**What I would build instead, and this is a real disagreement worth putting to them.** Two steps,
and the second is not a moving average.

*Step one — capture the price where the goods arrive.* Add `unit_price` to `goods_receipt_lines`,
prefilled from the purchase order's `expected_price`, which we already carry, and editable by
whoever is receiving. Write it back to `vendor_supplies.last_price`. That alone kills the
staleness, needs no valuation model, and is the prerequisite for everything else on this list.

*Step two — cost at the batch, not at an average.* We already track batches, because FEFO needs
them: we know **which batch** each issue and each consumption came out of, because the allocator
chose it. Costing that draw at its own batch's price is not an approximation of a moving average —
it is more accurate than one, and it needs no averaging model at all. Most ERPs reach for a moving
average precisely because they have lost track of which lot the food came from. We have not.

That also makes MP4 exact for bought food rather than estimated, which is a bigger prize than it
looks.

The one thing a batch price cannot do is value a donation, and it should not try. A donated batch
carries no price and the report says how much of the basket that was — which is what
`MaterialsCostService` already does with unpriced ingredients today.

**Size: M for step one, L for step two.** Step one first, on its own, because three other comments
get better for free once it lands.

## INV2. Utilities — gas, water, electricity — and maintenance, in the price per meal

**What they said.** A meal costs more than its ingredients.

**What I think.** True, and it is the same problem as labour, which `E3-S8 D4` already refused
with a reason I still think holds: a gas bill is **allocated, never measured**. Nobody can know
how much gas the Sunday feast burned. You can only divide the month's bill by something — meals,
servings, cooking hours — and every divisor is an assumption wearing the clothes of a measurement.

But I would not stop where D4 stops, because there is a defensible middle it did not consider. A
**fully-absorbed** figure that shows its own working:

> ₹41 per serving — ₹28 materials, ₹9 utilities allocated by servings, ₹4 labour allocated by
> rostered hours.

That is honest in exactly the way D4 demands. It says which part is measured and which part is
shared out, and it names the rule. It is also the number you actually need to price a catering
order, which is the use case that makes this worth doing at all.

The prerequisite is the problem. There is **no expense ledger of any kind** in the schema — no
utilities, no maintenance, no operating supplies, nothing. So this is two pieces of work: build a
small expense capture nobody has asked for yet, then build an allocation on top of it.

**Size: XL,** and it must come after INV1 and MP4. Allocating overheads onto a materials figure
that is itself a hand-typed estimate is precision laid over guesswork, and it would make the
number look more trustworthy while making it no more true.

## INV3. Current wholesale market price, through agentic AI

**What they said.** Have an agent fetch today's market rate.

**What I think.** I would decline this, and not on cost grounds. Ask what the number would be
**used for**, because it has three possible jobs and it is wrong for two of them.

*Valuing stock we already hold* — wrong. Stock is worth what we paid. If market price fed the
valuation, our food cost would move on a day when nobody bought anything and nobody cooked
anything, and somebody would spend an afternoon working out why.

*Pricing a catering order* — reasonable as an input, but a human sets the margin, so it belongs
beside the decision, not inside the ledger.

*Checking whether our vendor is charging fairly* — genuinely useful, and it is a completely
different feature from the one they described. It is an advisory, not a source.

There is also a sourcing problem the phrase "agentic AI" skates over. Indian wholesale prices are
published per mandi, per grade, per day. The **grade is what sets the price**. An agent that
browses for "rice price" and returns a number has an error bar wider than the thing we are trying
to measure, and it would write that number into a financial record with no human in between. That
is where I would expect this project's first serious data-quality incident.

**What I would offer instead.** A price advisory on the ingredient screen: last paid, when, and a
market reference with its source and its date, read-only, never feeding the ledger. If a feed is
wanted, take it from Agmarknet — government-published, per-mandi, per-grade, and citable — rather
than from an agent reading web pages.

**Size: M for the advisory.** The version they asked for I would not size, because I would not
build it.

## INV4. Call the meal category a cost centre rather than a kitchen

**What they said.** Do not mix the physical kitchen with the abstract meal category. Make the
Deity Kitchen a cost centre, and let store issues land against it.

**What I think.** The premise is a misreading of the demo. The conclusion is half right, and the
right half is worth building.

**The misreading.** We do not call the meal category a kitchen. They are two separate things and
both already exist. A `meal_kind` is Breakfast, Lunch, Deity offering, Catering order, Outside
event, Festival feast — that is the category of preparation. A `kitchen` (E10) is a physical place
with a door, a person in charge, a phone number and a location, and its only behavioural flag is
whether it plans its own meals. The E10 design says it outright: *is_main is a label;
uses_meal_planner is the behaviour*. There is nothing here to rename.

**Where they are right, and we have missed it.** An issue to a kitchen **is already a cost
attribution and we are not using it as one.** Every `ISSUE` movement records which kitchen it went
to. "Whatever the main store issues will be against this cost centre" is not a design change — it
is a report nobody has written.

And notice what it does that per-meal costing cannot. The Deity kitchen does not use the meal
planner; that is the entire point of E10 D5, one kitchen one door. So its cost can **only** be
measured through issues. A kitchen that plans meals is costed through consumption. Two doors, two
paths, one report.

**What I would resist** is introducing a third noun. A "cost centre" entity alongside kitchen and
meal kind would have to be created, maintained and mapped, and on the first day it would map
one-to-one onto kitchens. E10's own D1 and the standing rule about unnamed abstractions point the
same way: let the kitchen be the cost centre until somebody can name a case where the two differ.

**The fix.** A cost-by-kitchen report over `ISSUE` movements, valued however everything else is
valued. **Size: M** — and it becomes accurate for nothing extra once INV1 step one lands.

## INV5. The Deity Kitchen's menu is dynamic, and the mathajis sometimes buy things themselves

**What they said.** Not everything that kitchen cooks came from the main store.

**What I think.** Correct, and our design already accepts it on purpose — E10 D2: issuing takes
food off the temple's books, and what the kitchen does afterwards is its own business. A kitchen
that does not run this application will not record its own purchases in it, and giving it a second
stock balance would produce a fiction within a month.

But they have spotted a consequence we should say out loud rather than discover later.
**Any per-kitchen cost figure is a floor, not a total.** The Deity kitchen's real food cost is
what the store issued *plus* what the mathajis bought, and we will only ever see the first. So the
INV4 report must be labelled **"issued from the temple store"** and never "Deity kitchen food
cost", or it will be quoted as the second one inside a week.

One thing worth offering them: if those purchases physically land in the temple store, they are
already recordable — `MovementType.DONATION_IN_KIND` is batch-establishing and expects no purchase
price.

**Size: XS.** It is a labelling condition on INV4 and one sentence in the E10 design, not a piece
of work.

---

# Video 2 — Order List

## OL1. Rename "Order List" to "Purchase Plan"

**What they said.** The name is wrong.

**What I think.** Agree with the reasoning, and I would offer a different word.

Their point is sound and it is a real naming defect. The screen is not a list of orders. It is a
**proposal** of what to buy, computed from demand and fully editable before anything is committed.
Purchase orders are a different screen, at `/orders`. Calling this one "order list" invites exactly
the confusion they walked into, and the confusion is between the two screens most likely to be
confused anyway.

My reservation about "Purchase Plan": this application's vocabulary is deliberately the temple's,
not an ERP's. The design system and the error-code rules both push plain language, and the people
using this screen include storekeepers who do not work in procurement. "Purchase plan" is a
finance word. **"Shopping list"** is what the thing actually is, is what the person carrying it
would call it, and cannot be mistaken for a purchase order.

Either beats what we have. I would rather Rajeev picked than that I did.

**The fix.** Around 35 files touch the name — route, nav entry, page title, service, controller,
job, table. The table rename is optional in the sense that nobody sees `order_list_lines`, but
leaving the code saying one thing and the screen another is how the next person gets confused, so
I would do both in one pass. The route change needs a redirect or bookmarks break.

**Size: M.** Mechanical, but wide.

## OL2. "Hope the purchase plan will factor in future meal plans, even though not issued."

**What they said.** A hope, phrased as a question.

**What I think.** It does, and exactly as hoped. `OrderListService` merges three demand streams and
the first is forward-looking meal-plan shortfall (`OrderListService.java:102-105`), fed by
`SufficiencyService.shortfallFeed()` — fourteen days ahead, stretched to thirty for any festival in
that window (`SufficiencyService.java:35-36`). It takes the **max** of the three streams rather
than the sum (`OrderListService.java:142`), so a shortfall and a low threshold cannot double-order.
Need-by is the earliest demanding meal date minus a two-day lead buffer.

The gap behind the reassurance is the one in "Two things the review found" above: all four of
those numbers are hardcoded, and three separate documents promise they are configurable.

**The fix.** Move horizon, festival lookahead, safety factor and lead buffer into tenant settings
with the current values as defaults, and a section on the settings screen.

**Size: S.**

---

# Video 2 — Vendors

## V1. Validity dates, and remarks visible when reactivating

**What they said.** Beyond active/inactive, give vendors validity dates; and let us log why we
deactivated somebody, so we can read it when we come back to them.

**What I think.** Two requests. One is already there and broken; the other I would argue against.

**The remarks: the field exists, the workflow does not.** `vendors.notes TEXT` is in the schema
(`V24__vendors.sql`), is on create and update, and is shown on the detail page. But it is one
overwritable line, with no author and no date, rendered as a bare input labelled "Notes" — and,
the actual defect, the deactivate and reactivate endpoints do not touch it. So the scenario they
described genuinely does not work.

What is wanted is not a notes field. It is **a reason captured at the moment of deactivation, kept
as history**. That shape already exists twice in this codebase: `staff_profiles.end_reason`, and
the audit trail, which is already recording `VENDOR_DEACTIVATED` and `VENDOR_REACTIVATED`
(`VendorService.java:122-127`). Make the reason required on deactivate, put it on the audit event,
and show the vendor's deactivation history on their page. Free-text notes stay for everything else.

**The validity dates: I would push back.** A date-bounded vendor sounds tidy and behaves badly. It
needs a scheduled job to flip the flag, and one morning it will silently drop a vendor out of the
preferred-vendor lookup that feeds the purchase plan. The plan then suggests a different vendor and
nobody knows why, because the cause was a date somebody set months ago and forgot. Active/inactive
plus a dated, attributed reason gives the whole audit trail with none of the time bomb.

If the real need is *"our contract with this vendor runs to March"*, then say that: a **contract
end date, recorded and warned about**, never an automatic switch-off. I would offer that instead.

**Size: S for the reason-with-history. S for the contract date and its warning.** The automatic
validity window I recommend against.

## V2. A vendor performance report — requested vs actual delivery, open POs, open PO aging

**What they said.** Basic supplier scorecarding.

**What I think.** Valid, wanted, and the good news is that it needs **no new data capture at all**.
I checked all three:

- Requested: `purchase_orders.needed_by` (`V26__purchase_orders.sql:31`).
- Actual: `goods_receipts.received_at` and `goods_receipt_lines.received_date`
  (`V27__goods_receipts.sql:34, 74`). Both tables are append-only, so the history can be trusted.
- The join already exists — `OrderListService.poOutstandingByIngredient()` writes exactly it.
- Aging has a pattern to copy: `InvoicePaymentService.payables()` with its
  CURRENT / DUE_1_30 / OVERDUE_31_PLUS buckets.

We also kept the rejection reasons specifically so this could be built one day.
`RejectReason.java:6` says so: *"the reason is kept so a vendor's reliability can be read back
(Phase 2 scorecard)"*. This is Phase 2 work the codebase was deliberately shaped for.

One modelling limit to state before starting: `needed_by` is on the purchase order header, not per
line. So on-time is measured per order, not per ingredient. For a scorecard that is fine, and I
would not change the model to improve it.

**One thing to add that they did not ask for:** fill rate. On-time tells you the lorry arrived; it
does not tell you whether it brought everything. `received_qty` against ordered quantity is
already on the line, and a vendor who is always punctual and always short is a vendor you want to
know about.

**Size: M.** One query, one screen, no migration.

---

# Video 2 — Invoices

## INVOICE1. A data import for payments, from a file Accounts sends periodically

**What they said.** Accounts owns payments. Let us load their file rather than retype it.

**What I think.** The need is valid, and it lands on the structural gap described at the top:
**there is no file import anywhere in this application.** So this is not "add an importer to
invoices". It is "build the first import in the system", and a first one pays for everything a
first one needs — upload handling, size and type limits, a parse-and-preview step, per-row
validation with errors an accounts clerk can act on, idempotency so a file sent twice does not pay
everything twice, and an audit record of who imported what.

And the parsing is not the hard part. **Matching is.** A payment file from Accounts identifies
invoices by *their* reference, not ours. Every import of this kind lives or dies on that one join,
and it is usually discovered late.

**I would also challenge the direction.** If Accounts is the system of record for payments, the
cheaper and more reliable integration usually runs the other way: we export our payables — we
already compute the aging — they pay, and they send back something much smaller. Invoice
reference, date paid, amount. Insisting on consuming their full file means we track their format
changes forever, and we will not be told when it changes.

**The fix.** Agree the file *contract* with Accounts first — three columns and a reference both
sides accept — then build a narrow importer for exactly that. Do not build a general import
framework on the strength of one requester; STAFF2 is already queueing behind it.

**Size: L** for the narrow version. **XL** if it becomes a general facility.

---

# Video 2 — Devotees

## DEV1. Integrate with Zoho CRM, which manages the devotee database and service allocations

**What they said.** Do not duplicate a devotee database that exists elsewhere.

**What I think.** This is the most consequential comment in the document and it deserves a firm
answer rather than a polite one.

**First, a fact worth putting in front of them.** ERPNext + Zoho CRM + Power BI was formally
evaluated for this project and rejected, and it is recorded — `CHANGELOG.md:303`. This is not new
ground, and the reviewers should know it was walked once.

**Second, on the merits: we already agree, and the screen already reflects it.** Our devotee page
has **no create form**, deliberately. Devotees register themselves, choose their temple, and
consent for themselves, because an admin creating an account on somebody's behalf produced a
person who had agreed to nothing and a set of contact details nobody had confirmed — decided
2026-08-18 and written into the page's own header comment. There is no role control either,
because a devotee holds one role by definition. What is left is the single decision an admin
genuinely makes: whether this person may still sign in. That is not a CRM and never wanted to be.

**Third, on the integration itself: what would we actually pull?** Our devotee record exists for
one reason — to sign in and take a volunteer shift. Identity comes from Firebase, which proves
somebody controls an email or a phone number. Authorisation comes from our own `users` table,
deliberately, because a token proves control of an address and nothing more. **A Zoho record can
supply neither.** So the integration would import contact details we do not need, for people who
must still register before they can sign in, and we would own a sync forever.

**Fourth, the part that is worth taking seriously,** and it is not the part they emphasised. If
Zoho is genuinely where **volunteer service is allocated** today, then our shift signups are a
second place, and that *is* duplication. But the duplication runs the opposite way from the one
they named. The answer would be to stop allocating service here — a scope conversation about Epic
7 — not to import contact details.

**The fix.** Put one question back to them precisely: *is Zoho where volunteer service is allocated
today, and would the temple stop doing it there?* If yes, that is a scope decision for Rajeev about
Epic 7. If no, decline, and record the second refusal beside the first.

**Size: XL if built,** and I recommend it is not. The question back is free.

---

# Video 2 — Staff

## STAFF1. Remarks, to record behaviour

**What they said.** We need somewhere to note how a staff member conducts themselves.

**What I think.** Valid gap, and the obvious fix is the wrong one.

We have `staff_profiles.notes` — an unlabelled `TEXT` (`V57__staff_employment.sql`), presented as a
single-line input labelled "Notes" (`StaffForm.tsx:300-303`), with no author, no date, no history,
overwritable by anyone holding `MANAGE_STAFF`. Using that to record conduct is **worse than not
recording it**: it is an employment record about a real person, nobody knows who wrote it or when,
and the next edit silently destroys what was there.

What is needed is a **dated, attributed, append-only staff note** — author, timestamp, body. That
shape exists in this codebase already: `make_append_only()` on stock movements, and the audit
trail. Append-only is not fussiness here; an employment note that can be edited after the fact is
worth nothing the day it matters.

**Two things I would settle with Rajeev before building.**

*Access.* Conduct notes are not general staff data. Today anyone with `MANAGE_STAFF` sees
everything on a profile. A conduct note needs its own permission and probably its own visibility
rule, or the first thing that happens is a kitchen manager reading their colleague's warning.

*Scope.* "Behaviours" slides into appraisals, ratings and formal warnings very fast. I would build
the dated note and nothing else, and refuse the rating field until somebody can say what reads it.

**One flag.** We already hold a cross-temple employment ban mechanism (`V65__employment_bans.sql`).
A conduct note must not quietly become an input to a ban without an explicit decision to make it
one.

**Size: M.** Migration, append-only trigger, a permission, a panel on the staff page.

## STAFF2. Periodic import from Finance — salaries, advances, and so on

**What they said.** Load the pay data rather than keep it by hand.

**What I think.** Same structural answer as INVOICE1 — no import exists — plus a scope question
that matters more than the plumbing.

We hold `staff_profiles.monthly_salary` (V63) and we encrypt PAN. We do not hold advances,
deductions, payslips or a pay ledger, and nothing in Phase 1 says we should. "Import salaries and
advances" is really "become a partial payroll system", and a **partial payroll record is one people
will trust**. If we hold an advance figure that Finance changed last week and our file is a month
old, somebody reads a stale number off our screen and acts on it.

So the question is what the data is *for*, on our screens:

- To show a staff member their own pay? That is employee self-service, and Finance's own system
  almost certainly does it better.
- To cost meals with labour? `E3-S8 D4` already says labour can only be allocated, not measured,
  and a salary figure does not change that.
- "So it is all in one place"? That is the weakest of the three reasons to hold sensitive pay data,
  and the data-protection cost is real rather than theoretical: salary and advances sitting beside
  an encrypted PAN in one row raises the stakes of any leak.

**The fix.** Decline until somebody can name the reader. If it does go ahead: reuse whatever
importer INVOICE1 builds, keep it read-only, and stamp every figure *"as supplied by Finance on
<date>"* so nobody mistakes it for live.

**Size: L on top of INVOICE1's importer. XL standalone.**

---

# Video 2 — Staff Schedule

## SS1. A heatmap highlighting the gaps, for the week / day / month

**What they said.** The table is less usable than a picture of where the holes are.

**What I think.** The sharpest comment in the document, and the reason it is right is more specific
than "a heatmap is nicer to look at".

**The schedule screen shows supply only.** It is a seven-column table of who works which hours,
with one footer row: *"In that day — N staff, M volunteers"*. Nowhere does it show what was
**needed**. And we compute need: `MealCrewService` knows how many people a meal takes and how many
are rostered, and it warns on the planner when `crew.rostered < crewRequired`
(`MealComposer.tsx:776-781`). So both halves of the answer exist in the system and have never been
put on the same screen. Today a gap is visible only to somebody who happens to open the right meal
on the planner.

**That is the fix, and it is not really a heatmap.** It is **coverage against requirement on the
schedule grid** — each day carrying its shortfall, and how many people short. The distinction
matters and it is not pedantry: a heatmap of raw headcount would be decorative, and the design
system's rule is that status colour is never decorative. Colour by shortfall passes that rule.
Colour by "how many people are in" fails it, because a busy day and a well-staffed day would look
the same.

**I would also resist the month view to begin with.** A month of days by staff is a wall, and the
question being asked — *where am I short?* — is answered better by a compact week strip plus a
"next thirty days, the days you are short" list than by thirty columns of colour.

**Size: M.** The data exists; it is a new aggregate endpoint (need against rostered, per day) and a
rework of the grid's colour and its footer.

---

# If it were up to me, in this order

1. **INV1 step one** — capture the price where the goods arrive. Small, and MP4, INV4 and every
   costing figure in the system get better for free the day it lands.
2. **MP1** and **MP3(a)** — the switch we never wired, and the empty map on catch-up. Both are
   under a day and both are things a reviewer noticed.
3. **V2** and **SS1** — two reports whose data is already sitting there, and the two comments most
   likely to change somebody's mind about the application.
4. **OL2's settings** and **V1's deactivation reason** — small, and both close a gap between what
   a document promises and what the code does.
5. **INV4**, then **MP4** — cost by kitchen, then cost by meal kind. In that order, because the
   first one is a join and the second one is a refactor.
6. **OL1** — the rename, once nothing else is moving through those files.
7. **STAFF1** — after the access question is settled, not before.
8. **INVOICE1** — after the file contract is agreed with Accounts, not before.
9. **INV2** — last, and only once the materials figure underneath it is worth building on.

Not recommended: **MP2** as a filter, **INV3** as a source, **V1's** automatic validity dates,
**DEV1**, **STAFF2**.
