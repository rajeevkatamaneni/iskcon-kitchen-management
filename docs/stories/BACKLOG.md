# Backlog — recorded, not yet scheduled

Nice-to-have work captured so it isn't lost, deliberately **out of the current build**. Each item
says where it came from and why it was deferred. Nothing here is committed scope; promoting an item
to an epic is a separate decision.

An item that gets built is **not deleted**. It keeps its entry, gains a bold `CLOSED` line at the
top saying what closed it and where the work landed, and keeps the original text underneath — the
argument for why something was deferred is worth reading beside what was eventually done about it.
Closed so far: **BL-4**, **BL-6**, **BL-9**.

---

## BL-1 — Temple System Health Dashboard

**Origin:** Operations-page redesign, 2026-08-11. When the Super-Admin Operations page was trimmed to
platform-wide vitals (system health + total notifications sent/failed today with a 7-day trend), three
things it used to carry were pulled off it because they are **one temple's** operational detail, not a
platform operator's concern:

- the **per-temple notification breakdown** (sent / failed / suppressed for a chosen temple),
- the **recent failed sends** list (who wasn't reached, on which template), and
- **last calendar precompute** for that temple.

**As a** Temple Admin, **I want** a health view for *my* temple — were my reminders and receipts
actually delivered, what failed and to whom, and is my calendar up to date — **so that** I can debug
site-specific delivery problems without a platform operator, and without seeing any other temple.

**Why deferred:** not needed for the pilot. The platform operator's silent-failure guardrail (the
E1-S11 intent) is met by the platform-wide totals + trend; per-temple debugging can wait until a temple
is live and actually hits a delivery problem.

**Notes for when it's picked up:**
- The backend already supports the drill-in: `GET /api/v1/ops/tenants` and
  `GET /api/v1/ops/tenants/{id}` (behind `VIEW_PLATFORM_OPERATIONS`) return exactly this per-temple
  data — `OpsService.tenantOperations(...)` / `TenantOps`. They were left in place for this reason.
- For a **Temple Admin** (not the platform operator) the permission must change: this reads the temple's
  own data under its own RLS context, so it belongs behind a temple permission (e.g. a new
  `VIEW_TEMPLE_OPERATIONS`) in `RolePermissions.java`, and the query should read the caller's own tenant
  from context rather than take a `tenantId` path variable.
- The suppressed-count and recent-failures copy already exists in git history (pre-redesign
  `app/operations/page.tsx`) and can be lifted from there.


---

## BL-2 — Clone a day's meal plan to another day

**Origin:** Meal plan redesign discussion, 2026-08-14. Rajeev, on past days being read-only: *"As a
later feature, we can allow the user to clone a day's meal plan to a future day as a convenience
feature."*

**As a** Kitchen Staff member, **I want** to copy a day's meals onto another date, **so that** a
repeating pattern — the same weekday menu, last year's festival — does not have to be retyped.

**Why deferred:** E4-S7 is already a substantial redesign; cloning is a convenience on top of it and
needs its own thinking (does it copy servings? recipes that have since been archived? does it merge
with meals already planned on the target day?).

---

## BL-3 — Turnout outlook on the Today screen

**Origin:** Today-screen prototype review, 2026-08-15. Rajeev, on the panel in the prototype: *"Dont
include the Turnout outlook because that is not fully flushed out. We will do that as a future
enhancement."*

**As a** Kitchen Staff member, **I want** the Today screen to tell me how many people to expect,
**so that** I can cook to the day's real turnout rather than to last week's guess.

**Why deferred:** the idea is drawn but not designed. What the number is derived from is the open
question — served counts from past comparable days, the Vaishnava calendar (an Ekadashi and a
Janmashtami do not draw the same crowd), festival occasions already on the calendar, a manual
override from the temple, or some combination. Until that is settled, a number on the Today screen
would carry more authority than it has earned.

**Notes for when it's picked up:**
- The prototype's placement on Today is agreed; only the substance is missing.
- The inputs it would need mostly exist already: meals cooked and their served counts (E4), festival
  occasions and the calendar (E4-S8/S9), and the temple's own attendance records if any are kept.
- Decide first whether the outlook is a *forecast* (we compute it) or an *expectation* (the temple
  enters it and we show variance against what was actually served). Those are different features.

---

## BL-4 — Employee types below the role

**CLOSED 2026-08-20 — built as `KITCHEN_MANAGER`.** See **E6-S12** in
`EPIC-6-workforce-management.md`; migration `V61__kitchen_manager_role.sql`, policy in
`RolePermissions.java`. The build brief's §5 took this item's own recommendation unchanged — *more
roles, not a second concept beside them* — because "the kitchen manager can approve leave" would
otherwise have collided with E6-S8's rule that a job title is a label and gates nothing. The new
role holds everything kitchen staff hold plus `MANAGE_STAFF_SCHEDULE`, `APPROVE_LEAVE` and
`REQUEST_OWN_LEAVE`, and deliberately not `MANAGE_STAFF`, which is what gates hiring, salary and
PAN. The note below about seniority within the same permissions still stands and is still on the
staff profile, not in the role. `HEAD_COOK` and `COOK` were **not** added: nobody has asked for
them, and the argument for adding a role is a permission set somebody needs, not a job somebody
holds.

**Origin:** Login/registration design conversation, 2026-08-15. Rajeev: *"Can we add employee types
which are different from the roles OR it could be derived from roles… Temple Admin… Kitchen
Manager… Head cook and other Cooks."*

**As a** Temple Admin, **I want** staff to hold the job they actually do, **so that** a cook sees the
job cards and the recipes and nothing about money, and a kitchen manager can plan, order and check
the roster without being given the run of the temple.

**Why deferred:** flagged by Rajeev himself as "for the future, just FYI". It is also a change to the
authorisation policy, which is the one document in this codebase that must stay legible.

**Notes for when it's picked up:**
- The three jobs named map onto permissions we already have. *Kitchen Manager* is close to today's
  `KITCHEN_STAFF` plus `MANAGE_VOLUNTEER_SHIFTS` (to see whether a shift is covered). *Head Cook* and
  *Cook* are narrower than anything we have: view the plan, open and print a job card, read and print
  a recipe in any language — and nothing that writes.
- My recommendation is **more roles, not a second concept beside them**. A "type" that sits next to a
  role means two things to check before every action, and two places for them to disagree.
  `RolePermissions.java` is designed to absorb this: add `KITCHEN_MANAGER`, `HEAD_COOK`, `COOK`, give
  each its set, and the policy stays one readable document.
- The one thing a role cannot express is *seniority within the same permissions* — a head cook and a
  cook may hold identical rights and differ only in who the job card is addressed to. If that
  distinction matters operationally, it belongs on the staff profile (E6-S1), not in the role.

---

## BL-5 — Knowing who someone is: photographs and identity documents

**Origin:** Requirements gathering with the temple admin, relayed 2026-08-15. ISKCON welcomes
everyone and extends trust by default; that trust has been abused more than once, by devotees and by
staff.

**As a** Temple Admin, **I want** to know that the person in front of me is the person in the record,
**so that** an open door is not an unguarded one.

**Why deferred:** it needs research before it needs building — the liveness check especially, which
is the difference between a photograph and a photograph of a photograph.

**Notes for when it's picked up:**
- **Every devotee: a photograph, taken live.** Not an upload — a capture, from the phone or laptop
  camera, checked to be a live human face rather than a duck, a flower, or a picture held to the
  lens. Rajeev's candidate for the check is Gemma; whatever the model, this is the part to prototype
  first, because a check that is easy to fool is worse than none: it manufactures confidence.
- **Staff, additionally: Aadhaar and PAN**, scanned or photographed at onboarding, read into
  structured data by OCR, verified against the government source where that is possible and simply
  kept on file where it is not.
- These are identity documents belonging to real people. Wherever this lands, the raw files need a
  retention rule, an access rule (who in a temple may open them, and when), and an audit entry for
  every read — the same standard as the donations ledger, not the same as a recipe photo.

---

## BL-6 — A platform-wide notice, and the ban that travels with it

**CLOSED 2026-08-20 — built, in two halves and one short of what this item asked for.** See **E9-S1**
(the notice board) and **E9-S2** (the record at a dismissal and the check at a hire) in
`EPIC-9-cross-temple-notices-DESIGN.md`; migrations `V65__employment_bans.sql` and
`V66__platform_notices.sql`, code under `backend/src/main/java/org/iskcon/kms/notice/` and
`.../ban/`, screens at `/notices` and `/staff/bans`.

**What was dropped: the broadcast about a person, in both forms.** This item asked for a dismissal
to be *"passed on to other temple sites"* and seen *"the next day on their Today dashboard"*. The
named version was never available — an accusation about a private individual published permanently
to every organisation on the platform, on one administrator's say-so, is a defamation exposure and
DPDP processing the person never consented to. The unnamed version was killed by Rajeev's own
argument, which is the better one: *an unnamed notice is a rumour with no handle on it, useful to
nobody and corrosive anyway.* So the identity does not travel at all. It is asked for, once, by the
one temple with a reason to ask, at the moment it is hiring — which is the same protection at a
fraction of the exposure.

Everything else this item called for is built: the generic carrier it insisted on (a recall or a
festival advisory rides the same rails), the ban record raised on a termination, the exact
cross-temple signal on the PAN blind index, the probabilistic layer over name and address, and the
failure mode it named — *a confident false positive against a devotee who has done nothing* —
answered by a check that flags and never blocks. The photograph is still missing (BL-5), and it is
still the only durable signal against somebody who changes their name, email and number.

**Superseded, 2026-08-19 → 2026-08-20** — Rajeev asked for it directly, it was promoted to a design
carrying six open questions, and all six were answered in `BUILD-BRIEF-2026-08-20.md` §10 and §11.

**Origin:** Same conversation, 2026-08-15. *"IF a temple admin fires a staff member due to
misconduct… that information MUST be passed on to other temple sites… the temple admin should see it
the next day on their Today dashboard."*

**As a** Temple Admin, **I want** to hear from the rest of the platform when someone has been barred
elsewhere, **so that** the person another temple dismissed for cause is not quietly hired by mine.

**Why deferred:** it is two features, and the smaller one has to exist first.

**Notes for when it's picked up:**
- **The carrier is generic.** A platform-wide notice — from any temple, or from the operator — that
  lands on other temples' Today screens. Barring someone is only its first use; a Janmashtami advisory
  or a food-safety recall would ride the same rails. Build the carrier, then the use.
- **The ban itself** is recorded on a termination: the reason, who decided, and whether the person is
  not to be engaged elsewhere. Tenant isolation is the obstacle worth thinking about — this is
  deliberately cross-tenant, so what crosses must be the minimum that lets another temple recognise
  the person, and it must be recorded as having crossed.
- **Matching is the hard half, and it is probabilistic.** Someone evading detection changes a name, an
  email, a number. Score across everything known, including the photograph, and put anything above
  50% in front of the admin as a question rather than a verdict — every candidate shown, the decision
  theirs, the outcome recorded. For a devotee re-registering under a new identity, the photograph is
  the only durable signal: at a solid 80% or better, tell the temples they have registered with and
  let them deal with it.
- The failure mode to design against is not a miss. It is a confident false positive against a devotee
  who has done nothing, in a community whose stated posture is to welcome everyone.

---

## BL-7 — "Ingredients the kitchen is short of", and the pledge that has to be reconciled

**Origin:** Donate-page design, 2026-08-15. The third tab of the devotee's donation page in the
prototype. Rajeev, on reading what it implies: *"needs a lot more thought on how it is supposed to
work… add that to the todo list and tackle it later."*

**As a** devotee, **I want** to see what the kitchen is actually short of this week and take part of
it — pay for it, or bring it to the gate myself — **so that** giving is a sack of rice the cooks are
waiting for rather than a number.

**Why deferred:** the display is easy and the promise is not. Two of the three states on that bar
are settled facts; the third is a promise that can fail.

**Notes for when it's picked up:**
- **The ask** most naturally comes from the shopping list — what the planner already says the week is
  short of — with the per-unit price from the vendor's own rate, so the page never invents a number.
- **Three states, and the colour is the design** (confirmed against `DESIGN_SYSTEM.md`): *paid* is
  success green, money in hand or goods in the store, and the kitchen may spend against it;
  *pledged* is warning gold, promised and not yet at the gate, and the kitchen may **not**; the rest
  is the grey track. The amount of gold on the page is a measure of how much the kitchen is relying
  on trust.
- **The hard half is reconciliation.** Somebody promises 10 kg. It arrives late, or as 5 kg, or not
  at all. The store keeper needs to turn gold into green when it lands, and gold back into grey when
  it doesn't — which means a pledge has an owner, an expected date, and an end. Nothing else in the
  product has that shape yet; goods receipts come from purchase orders, not from devotees.
- Decide too whether an unfulfilled pledge quietly lapses or is chased, and who does the chasing. A
  promise nobody follows up teaches devotees the promise does not matter.

---

## BL-8 — Message templates a temple admin can write

**Origin:** Settings / per-temple messaging design, 2026-08-15. Rajeev, having agreed to keep the
channels to WhatsApp with SMS as the fallback: *"We have to give the temple admin an option to
create message templates though which is not present at this time."*

**As a** temple admin, **I want** to write the messages my temple sends — reminders, purchase orders,
thank-yous — in my own words and my own languages, **so that** devotees and vendors hear from the
temple rather than from software.

**Why deferred:** the editor is the easy tenth of it. Today `NotificationTemplate` is a Java enum —
templates are code, identical for every temple, and changing one is a deploy. Admin-authored
templates make template content per-tenant data with a lifecycle, and that lifecycle is owned by
somebody outside this system.

**Notes for when it's picked up:**
- **Neither channel lets you just send text.** A WhatsApp template must be submitted to Meta and
  approved *per WhatsApp Business Account* before it can be sent — so per temple, not once for the
  platform. Commercial SMS in India must likewise be registered on a DLT platform before it will
  deliver. So the screen is not an editor, it is a **submission and approval workflow**: draft,
  submit, pending, approved or rejected with the provider's reason, and a version history — because
  an approved template cannot be edited in place, only replaced by a new submission.
- **The state to design for is "rejected on a Friday".** A temple whose reminder template is pending
  or rejected still has shifts tomorrow. Every admin-authored template needs a fallback: the
  platform's own approved template, or the SMS/email leg of the cascade. Sending nothing is not an
  option the kitchen can absorb.
- **Variables are a contract.** A reminder must carry the shift time; a purchase order must carry the
  vendor and the PDF link. So an admin picks from named placeholders the system provides rather than
  typing `{{1}}`, and a template that drops a required one is refused before it ever reaches Meta.
- **Translation already exists** and templates are per-language — Meta approves each language
  separately. Decide whether an admin writes each language or writes one and approves a translation.
- **Depends on the BSP decision**, still open in `SYSTEM_DESIGN.md` ("Gupshup vs Interakt vs Twilio vs
  Meta direct"). Most BSPs offer a template-management API and absorb some of the approval dance; Meta
  direct means building all of it. Picking the BSP first will change how much of this there is to
  build, so this should not start before that is settled.

---

## BL-9 — Nothing checks that a purchase-order line's unit belongs to the ingredient's family

**CLOSED 2026-08-31.** One rule, in `IngredientUnits.requireSameFamily` (`ingredient` package),
called from `StockMovementService.validate` and from `PurchaseOrderService.insertLines` — the ledger
because every writer passes through it, the order lines because that is where somebody can still fix
the line. Same family, not same unit: an order in grams against a Kg-held ingredient still works,
and so does issuing and cooking, which post in the family's base unit. `PIECES` is
`Unit.Family.COUNT` and converts to nothing, which needed no special case. Refusal is
**KMS-4013**, carrying the ingredient's name and both units so a twenty-line order says which line.
Write-only, so reports over older rows still render, and `compensate` deliberately bypasses the check
so a row written before the rule can still be corrected away. No migration. Tests:
`PurchaseOrderIT` (cross-family refused, pieces, same-family-different-unit accepted, a refused edit
leaves the draft alone), `StockMovementLedgerIT` (refused at the ledger posted directly; a same-family
unit adds up in the base; a pre-existing bad row is still correctable), `ReceivingIT` (a delivery
against a hand-written cross-family line is refused and nothing is booked).

Three older copies of the same comparison remain, in `InventoryItemService.adjust`,
`DonationRecorder` and `IngredientRequestService`, each refusing with the generic `KMS-4001` and each
with its own passing test. They fire before the ledger does and are correct, so they were left alone
— `InventoryItemService` was owned by another agent at the time, and moving one without the others
would have left adjustments, donations and requests saying different things about one rule. Pointing
all four at `requireSameFamily` is a tidy-up for whoever next has those files.

**Origin:** Building the price capture at goods receipt (INV1, 2026-08-31). Found while writing the
conversion in `ReceivingService.pricePerUnit`, which declines to write a price back when the receipt
line's unit and the ingredient's canonical unit are in different families. Asking *how would they
ever differ?* turned up a hole that predates that work.

**As a** Temple Admin, **I want** the system to refuse a purchase-order line measured in something
the ingredient cannot be measured in, **so that** a delivery against it cannot book a quantity that
means nothing.

**Why deferred:** it is a pre-existing hole rather than a regression, no path in the application
creates one today, and closing it properly is a validation decision about two services rather than a
line of code. INV1 works around it safely — it logs and declines rather than guessing a density —
and the fix belongs with somebody's attention on the ordering path.

**Notes for when it's picked up:**
- **Where the gap is.** `PurchaseOrderService` copies whatever unit it is given onto the line and
  validates nothing about it; every path that creates a line today copies the ingredient's canonical
  unit, so in practice the two agree. Manual PO creation accepts any of the five units.
- **`StockMovementService.record` does not check either.** Its `validate` covers quantity, the
  adjustment reason and the note for `OTHER`, and nothing about the unit. So a hand-posted
  cross-family PO line — ₹ per litre against an ingredient held in kilos — already books a nonsense
  stock movement today, and `to_base_qty()` will convert it into litres of a thing measured in
  grams without complaint.
- **The ledger is the place to close it**, not the ordering screen alone: a check in
  `StockMovementService.record` catches every writer at once, and the PO line check is then a kinder,
  earlier version of the same refusal. It needs a `KMS-nnnn` code saying which unit was given and
  which the ingredient is held in.
- Worth checking at the same time whether any existing tenant data has such a line, because a check
  added to the ledger will start refusing whatever wrote it.

---

## BL-10 — `vendor_supplies.last_price` is too narrow for a price converted down to a small unit

**Origin:** Building the price capture at goods receipt (INV1, 2026-08-31). The conversion is exact
in the arithmetic and lossy in the column.

**As a** temple pricing an outside catering order, **I want** a converted price to come back as the
price that was paid, **so that** the quote is not built on a figure that drifted by rounding.

**Why deferred:** widening a money column is a decision rather than a fix, and it touches three
columns that were deliberately made to match each other.

**Notes for when it's picked up:**
- **The arithmetic.** `vendor_supplies.last_price` is `NUMERIC(12, 2)`, as are
  `purchase_order_lines.expected_price` and `goods_receipt_lines.unit_price` — matched on purpose, so
  a figure can travel expected → received → last price with no rounding step in the chain. But a
  price *converted* down to a small canonical unit lands below the second decimal: ₹57 per Kg is
  ₹0.057 per gram, which stores as ₹0.06 and reads back as ₹60 per Kg. Five per cent, silently, on
  every ingredient whose canonical unit is grams or millilitres and whose bill is written per Kg or
  per litre.
- **It only bites where the units differ**, which is the case BL-9 says nothing prevents and nothing
  creates today. So this is not urgent; it is the kind of thing that becomes urgent the week
  somebody starts entering prices per sack.
- **The options are not equal.** Widening the scale to four or six decimals is the obvious move and
  changes every screen that formats these three columns as rupees. Storing the price *per the unit it
  was given in* — a price with its own unit column — is the more honest model and a much larger
  change. Rounding the *displayed* figure while keeping a wider stored one is the middle path.
  Whichever is chosen, the three columns move together or the chain stops matching.

