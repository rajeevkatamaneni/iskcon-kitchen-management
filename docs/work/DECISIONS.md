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

---

## Still open

Tracked here so the count is honest; the full thirteen are in `INTAKE.md`.

- Temple-health indicator: what sits behind the dot, and where it lives.
- Day-one dataset: still parked?
- Operator audit drill-in: Rajeev asked to see the existing `/audit` first.
- Who edits a temple's profile — operator or temple admin?
- `SESSION_EXPIRED`: carve "expired" out of the deliberately-opaque token verifier, or delete KMS-400018 (was KMS-4102)?
- Planner shift: match by time window, or an explicit link?
- B8: leave the recorded decision against a `CANCELLED` request state, or overrule it?
- Equipment `SCRAPPED` stays terminal, confirmation only?
- **Should kitchen staff and managers keep `/my-shifts` in their menu?** Raised by the work manager
  during wave 1, and **reverted pending your answer** — it had narrowed the entry to volunteers only.
  Its reasoning is sound: every write behind that screen needs `SIGN_UP_FOR_SHIFTS`, which
  `RolePermissions` grants to `VOLUNTEER` alone, so the page is structurally and permanently empty
  for a cook or a manager, and the menu offers them a destination that can never hold anything.
  Against it: `nav.test.ts` asserts the opposite with a deliberate comment ("kitchen staff can offer
  seva too"), T-002 had already answered the same finding by rewriting the empty state to say why it
  is empty, and removing a menu entry a real person uses is your call rather than an agent's. Note
  this resolves cleanly if T-006 lands — *My shifts* becomes volunteer sign-ups and *My schedule*
  becomes rostered staff work, which are genuinely two screens.

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

