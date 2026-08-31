# EPIC 10 — Kitchens under one temple, and issuing to them

**Status: DESIGN, awaiting Rajeev's review. Nothing here is built.**
**Written:** 2026-08-30, from Rajeev's brief of the same day.
**Depends on:** E1 (tenancy, RBAC, audit), E2 (ingredient master), E3 (stock ledger), E4-S11 (the document pipeline).
**Labels:** `epic:kitchens`

---

## 1. What this is, and what it changes

A temple is not one kitchen. It is three to five, sometimes ten or more, under one roof: the Deity
kitchen, the prasadam kitchen, a restaurant, a Food-for-Life kitchen, a guest-house kitchen. They
share one store room. Most of them will never open this application — they want ingredients, not
software.

Today the system has no idea any of that exists, and stock leaves the store by exactly one door:
a meal recorded in the planner. This epic adds the second door — a kitchen asks the store for
ingredients, somebody with authority answers, and the storekeeper records what actually went out.

### It amends two locked documents

`REQUIREMENTS.md` v1.1 and `SYSTEM_DESIGN.md` v1.1 contain **no** notion of more than one kitchen and
**no** notion of stock moving anywhere except out of the temple by consumption. This was checked, not
assumed:

- `REQUIREMENTS.md:64-66` closes the stock model: *"Stock increases from: purchase order receipt, and
  in-kind donation intake."* Decreases are consumption and adjustment, nothing else.
- `MovementType` says so in code: *"Every operational stock change resolves to one of these"* —
  `PO_RECEIPT`, `DONATION_IN_KIND`, `CONSUMPTION`, `ADJUSTMENT`.
- `REQUIREMENTS.md` §5 Open Questions reads *"None outstanding. Section 5 is closed as of this round."*

So this is an **extension, not a contradiction** — but it is a requirements change, and by Commandment 8
it needs Rajeev's sign-off, a `REQUIREMENTS.md` v1.2, a snapshot in `docs/versions/`, and a
`CHANGELOG.md` entry. Those are part of this epic, not paperwork to be done later.

### It also overturns one earlier decision, deliberately

`EPIC-3-inventory-management.md:20` (E3-S1) already met this idea once and reduced it:

> Multi-store-room support (Deity kitchen vs main kitchen vs catering, per the prior proposal's
> multi-store requirement) is modeled as an optional `storage_location` on items/movements — locations
> are a simple tenant-scoped list, not a full warehouse hierarchy, for release 1.

That reduction was right at the time and is **not** what we are undoing. `storage_location` describes
*where in the store room a thing sits*. A kitchen is *who asked for it and where it went*. They are
different questions, and §4 below keeps them apart rather than overloading one field with both.

---

## 2. The seven decisions this design turns on

Each of these is a decision Rajeev can veto. Where the brief was ambiguous or where I disagree with it,
that is said plainly rather than resolved quietly.

### D1 — One level: temple → kitchens. Exactly one of them is flagged as the main kitchen.

**Settled by Rajeev, 2026-08-30.** The draft of this design asked whether the shape was a two-level tree
(temple → main kitchen → child kitchens) or a flat list, because the brief could be read either way.
It is flat:

> Under 1 Temple, there will be ONLY 1 Main Kitchen and N number of child kitchens. The Main Kitchen and
> Child Kitchens are direct children to the Temple (Same Level). There is no further hierarchy […] Only
> one of those children is marked as the main kitchen.

So: **no `parent_kitchen_id`.** Every kitchen hangs off the tenant, and `is_main` is a flag on one row,
not a level in a tree. A kitchen is never inside another kitchen.

**`is_main` is a single boolean with a database-enforced "at most one".** A partial unique index does it
in one line and cannot be got wrong by application code:

```sql
CREATE UNIQUE INDEX kitchens_one_main_per_tenant ON kitchens (tenant_id) WHERE is_main;
```

At most one, not exactly one, because a freshly onboarded temple has zero kitchens and there is no row to
put the flag on. The first kitchen a temple creates is marked main by default; after that, marking a
different kitchen main clears the previous one in the same transaction, so the two acts can never be
half-done. **The store is still not a kitchen** — it remains the temple's single stock ledger that every
kitchen, main included, draws from.

**`is_main` is a label, not a behaviour — and that is deliberate.** An earlier draft of this design tried
to make it carry the double-count rule, and D5 has since taken that job away from it with a separate,
better-defined flag. What is left is a temple saying which of its kitchens is the principal one, which is
worth recording because people ask, and which changes nothing the system computes. It is the one field
here allowed in on Rajeev's say-so rather than on a behaviour it drives.

### D2 — Inventory stays one temple-level store. A kitchen never gets its own stock balance.

Issuing ingredients to the Deity kitchen **removes them from the temple's books.** It does not move
them into a second balance called "Deity kitchen stock".

**Why.** A kitchen that only wants ingredients is, by definition, not running this application. Nothing
would ever draw that second balance down. Within a month the number would be an ever-growing fiction
that says the Deity kitchen is holding 400 kg of rice it ate in September. `REQUIREMENTS.md` v1.1
already refused exactly this mistake once, keeping leave-balance accrual out of Phase 1 because
*"a balance nobody reconciles is a number that misleads."*

**The technical corroboration.** `inventory_items` is `UNIQUE (tenant_id, ingredient_id)` — one row per
ingredient per temple (`V15__inventory_items.sql`). Per-kitchen balances would mean changing that index
and everything that assumes 1:1: `InventoryItemService.create`, `StockMovementService.track()`'s
`ON CONFLICT (tenant_id, ingredient_id)`, the `V70` backfill, and five separate copies of the on-hand
SQL. That is a rewrite of Epic 3, not an addition to it.

So: **an issue is a drawdown, shaped exactly like consumption.** One new `MovementType` value, `ISSUE`,
negative, referencing the request. Everything else in the ledger is untouched.

### D3 — Stock is debited when the storekeeper records what went out. Not on approval.

Approval is a decision; issuing is a physical event. The system already draws this distinction and
should keep drawing it: sending a purchase order changes no stock, *receiving* it does.

This also settles what the work order is (§6): a **picking list computed live**, not a frozen
allocation. If we froze the FEFO lot choice at approval, a meal cooked that afternoon could empty the
lot before the storekeeper walks to the shelf, and the sheet would name a lot that no longer exists.

### D4 — There is no Storekeeper role. The storekeeper is a Kitchen Manager.

The brief says *"The temple Admin OR the storekeeper can review the request."* There is no such role
in the system: `User.Role` is `SUPER_ADMIN, TEMPLE_ADMIN, KITCHEN_MANAGER, KITCHEN_STAFF, VOLUNTEER`.

`BACKLOG.md` already reasoned this out for a near-identical request (BL-3, on Head Cook and Cook):
job titles map onto **permissions**, not onto new roles. Adding a role costs a migration, a CHECK
constraint change, a nav entry, a `RequireRole` audit on every page, and a new row in every ITs' fixture.

**Recommendation:** `APPROVE_INGREDIENT_REQUESTS` and `ISSUE_INGREDIENTS` go to `TEMPLE_ADMIN` and
`KITCHEN_MANAGER`. A temple that has a storekeeper appoints them Kitchen Manager, which is what that
role is for.

### D5 — A kitchen opts in to the meal planner, and opting in closes the ingredient-request door.

**Settled by Rajeev, 2026-08-30**, and this is a better answer than any of the three the draft offered.

The hazard is real: one store, two doors. If a kitchen's meals are recorded in the planner *and* somebody
raises an ingredient request covering the same food, the stock leaves the books twice. The temple has been
told about the risk and has said it will be careful — which, as Rajeev put it, is not a guarantee. So the
system enforces it rather than trusting it.

**Every kitchen answers one question at registration, and can change the answer later: does this kitchen
use recipe management and the meal planner?**

- **Yes** → its cooking is recorded as meals, its stock leaves as `CONSUMPTION`, and it **may not raise
  ingredient requests.**
- **No** → it has no meals in the system, and the ingredient request is its only door. Which is most
  child kitchens, and is the whole reason this epic exists.

One kitchen, one door, decided by the kitchen itself. There is no case where both are open, so there is no
case where the double-count can happen.

**The flag is `uses_meal_planner`, and it is separate from `is_main`.** They will usually agree — the main
kitchen is typically the one planning meals — but they answer different questions, and tying them together
would mean a temple could not have a child kitchen that plans its own meals, or a main kitchen that only
draws stock. `is_main` says which kitchen is the principal one; `uses_meal_planner` says which door its
stock leaves by.

### D6 — Opting in takes effect immediately, and settles the requests already in flight.

Turning the flag on is not just a switch; there may already be requests for that kitchen on the books.
Rajeev's rule, and the reasoning is his:

> All requests with delivery dates before will live as recorded data. All requests that are dated the day
> of opt-in or any time in the future — if already approved, will be moved to denied state automatically;
> if in draft, delete permanently; if already in denied state, no-op.

The dividing line is the request's **`needed_on`**, against today in the temple's own timezone
(`Asia/Kolkata`, as `InventoryItemService` already resolves "today"):

| Status | `needed_on` | What happens |
|---|---|---|
| `DRAFT` | **any date at all** | Deleted permanently. See the amendment below. |
| `SUBMITTED` | today or later | Denied. *(Rajeev's rule names approved and draft; submitted is the third live state and gets the same answer as approved — it is a request awaiting an answer, and the answer is now no.)* |
| `APPROVED` | today or later | Denied. |
| `SUBMITTED` or `APPROVED` | before today | Untouched. Somebody asked and somebody answered, and what happened last week happened. |
| `DENIED` | any | No-op. Already answered. |
| `ISSUED` | any | Untouched. *(Not in Rajeev's list because it is not really in flight: the goods have left the shelf and the stock movements are append-only. Reversing one would mean writing compensating movements for food that is already cooked.)* |

**Amended 2026-08-31 by Rajeev: every draft goes, whatever date it carries.** The first version kept
past-dated drafts, on the same "history is not rewritten" reasoning that protects a submitted or
approved one. Rajeev's objection: *"It is a DRAFT. What is stopping someone from opening a draft,
changing the date and submitting it?"*

He is right, and the reason is sharper than the exploit. **A draft holds no history** — nobody has
answered it, nothing was issued against it — and **the date on it is not a fact about the past but a
field its author can still edit.** Filtering drafts by that date filters on something the person can
change, which makes it a speed bump rather than a rule.

The exploit as literally described turns out to be closed already, and this was checked against the
running application rather than assumed: while the flag is on, `IngredientRequestService` refuses to
update *or* submit anything naming that kitchen (`KMS-4976`), so a leftover draft cannot be dated
forward and sent. **But the guard only holds while the flag is on.** Opt the kitchen back out — which
D6 explicitly allows — and every stale draft comes back to life carrying a date nobody has looked at
since. Deleting them closes that, and removes a list of things that look actionable and can never go
anywhere.

The date still decides for `SUBMITTED` and `APPROVED`, and must: those were asked and answered by
people, and that is history.

**Three things this cascade must do, none of them optional:**

1. **Warn before it runs.** The flag is a checkbox on a form, and ticking it silently deleting somebody's
   drafts is unacceptable. Saving with the flag newly on first says exactly what is about to happen —
   *"2 drafts will be deleted and 3 requests will be denied"* — and waits to be confirmed.
2. **Name a human as the decider.** `decided_by` is set to the administrator who flipped the switch,
   because they did in fact cause it, with `decision_note` reading *"Denied automatically when Deity
   Kitchen started using the meal planner on 30 August 2026."* An auto-denial that nobody's name is on is
   an auto-denial nobody can ask about.
3. **Audit every row it touches**, deletions included. A permanent delete that leaves no trace is exactly
   what the audit log exists to prevent.

**Opting back out** is the trivial direction: the kitchen may raise requests again from that moment, and
nothing already recorded changes.

**One honest limitation.** The meal planner is temple-wide today, not per-kitchen — a meal belongs to the
temple, not to a kitchen. So two kitchens both opting in would share one planner and the system could not
say which of them cooked what. That is not wrong, and it does not reintroduce the double-count, but it is
the thing that eventually forces a kitchen onto every planned meal. Recorded here, not built here.

### D7 — The unit vocabulary is E11's problem now, not this epic's.

**Superseded 2026-08-30.** This decision began as "add grams and millilitres to `YieldUnit`", at Rajeev's
instruction. Sweeping the codebase to size that change turned up something bigger: two overlapping unit
enums, the same display rule implemented three times and cut in half twice, eight copies of a label map,
several screens printing raw enum names at the user (`652 KG`, `Ghee (173542 ML)`), and seven
hand-written copies of a SQL conversion whose `ELSE 1` turns an unrecognised unit into a silent 1000×
stock error.

That is its own epic — **`EPIC-11-units-and-quantities-DESIGN.md`** — and it **blocks this one**, because
ingredient-request lines and dish quantities both pick from the vocabulary and both print quantities.
Building E10 first would mean writing that display code twice.

What E10 takes from it, and depends on:

- **One `Unit`** — `KG`, `GM`, `L`, `ML`, `PIECES`, plus `SERVINGS` where a yield is being named. Request
  lines offer the five physical units, filtered to the ingredient's own family. Dish quantities offer all
  six, because a dish is genuinely made in litres, kilos, pieces or servings.
- **One display rule** — a quantity is shown in whichever unit of its family reads naturally, and rounded
  on E11's magnitude ladder. So a work order asks for **135 gm** of cardamom, never `0.1344 Kg`.
- **The work order is a cook's figure, not a ledger figure** (E11 D4b), so its quantities are rounded. The
  stock movements the issue writes are exact, as every movement is.

---

## 3. Data model

Three new tables. All tenant-owned, all `enable_tenant_rls()`, house style throughout (`JdbcTemplate`,
no JPA — the codebase has exactly one `@Entity` and it is `User`).

### `kitchens` — migration `V74`

```sql
CREATE TABLE kitchens (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    name             TEXT        NOT NULL,
    description      TEXT,
    location         TEXT,
    is_main          BOOLEAN     NOT NULL DEFAULT false,
    uses_meal_planner BOOLEAN    NOT NULL DEFAULT false,
    in_charge_user_id UUID       REFERENCES users(id) ON DELETE RESTRICT,
    contact_phone    TEXT,
    status           TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_by       UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT kitchens_name_present CHECK (length(name) > 0),
    CONSTRAINT kitchens_status_valid CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE UNIQUE INDEX kitchens_name_per_tenant ON kitchens (tenant_id, lower(name));

-- At most one main kitchen per temple, enforced by the database rather than by hope. Partial, so the
-- many child kitchens do not collide with each other on a shared `false`.
CREATE UNIQUE INDEX kitchens_one_main_per_tenant ON kitchens (tenant_id) WHERE is_main;

SELECT enable_tenant_rls('kitchens');
```

**The intake form** is name, description, location, who runs it, a contact phone, a
**"This is the temple's main kitchen"** checkbox, and the question D5 turns on —
**"Does this kitchen plan its meals here, using recipes and the meal planner?"** Rajeev asked for "any other pertinent information you
can think of" — I have deliberately stopped there. A head-count, a cuisine, an opening time and a photo
all suggested themselves and none of them is read by anything, and an unread field is a field somebody
has to fill in every time for no one.

**The main-kitchen checkbox is never a silent overwrite.** Ticking it on a second kitchen tells the
person, by name, which kitchen is about to stop being the main one, and moves the flag only when they
confirm. The first kitchen a temple creates has it ticked and disabled, with a line saying why — a
temple's first kitchen is its main one, and there is nothing for it to take the flag from.

**Delete.** The brief asks for Delete. A kitchen named on six months of issued requests cannot be
deleted without orphaning that history, and `stock_movements` is append-only so the movements survive
regardless. So it follows the house pattern that `IngredientService.delete()` and `RecipeService.archive()`
already set: **delete when nothing references it, archive when something does.** The button says Delete;
if the kitchen is referenced, the confirmation offers Archive instead and says why.

### `ingredient_requests` — migration `V75`

```sql
CREATE TABLE ingredient_requests (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    reference     TEXT        NOT NULL,
    kitchen_id    UUID        NOT NULL REFERENCES kitchens(id) ON DELETE RESTRICT,
    needed_on     DATE        NOT NULL,
    purpose       TEXT,
    status        TEXT        NOT NULL DEFAULT 'DRAFT',
    requested_by  UUID        NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    submitted_at  TIMESTAMPTZ,
    decided_by    UUID        REFERENCES users(id) ON DELETE RESTRICT,
    decided_at    TIMESTAMPTZ,
    decision_note TEXT,
    issued_by     UUID        REFERENCES users(id) ON DELETE RESTRICT,
    issued_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ingredient_requests_status_valid CHECK (
        status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'DENIED', 'ISSUED'))
);

CREATE UNIQUE INDEX ingredient_requests_reference_per_tenant ON ingredient_requests (tenant_id, reference);
CREATE INDEX ingredient_requests_tenant_status ON ingredient_requests (tenant_id, status, needed_on);
SELECT enable_tenant_rls('ingredient_requests');
```

`reference` is a human-readable number (`IR-2026-0041`), minted per tenant like `purchase_orders.po_number`.
It exists so somebody can say it down a phone.

### `ingredient_request_lines`

```sql
CREATE TABLE ingredient_request_lines (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    request_id      UUID        NOT NULL REFERENCES ingredient_requests(id) ON DELETE CASCADE,
    line_no         INTEGER     NOT NULL,
    ingredient_id   UUID        NOT NULL REFERENCES ingredients(id) ON DELETE RESTRICT,
    quantity        NUMERIC(14,3) NOT NULL,
    unit            TEXT        NOT NULL,
    issued_quantity NUMERIC(14,3),
    issued_unit     TEXT,
    note            TEXT,

    CONSTRAINT ingredient_request_lines_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ingredient_request_lines_unit_valid CHECK (unit IN ('KG','GM','L','ML','PIECES')),
    CONSTRAINT ingredient_request_lines_issued_nonnegative CHECK (
        issued_quantity IS NULL OR issued_quantity >= 0)
);
```

**Units.** `Unit` already exists with families — `KG/GM` are MASS, `L/ML` are VOLUME, `PIECES` is COUNT —
and `InventoryUnits.toBase()/fromBase()` already convert within a family. A request line's unit must be
in the **same family** as the ingredient's canonical unit; asking for 3 litres of rice is refused with
`VALIDATION_FAILED`, exactly as `InventoryItemService.adjust()` refuses it today. `issued_quantity` may
be zero — the storekeeper handed over nothing for that line — and a zero line writes no movement, the
same rule a not-made dish follows in meal recording.

### `ingredient_request_dishes`

```sql
CREATE TABLE ingredient_request_dishes (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID    NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    request_id UUID    NOT NULL REFERENCES ingredient_requests(id) ON DELETE CASCADE,
    line_no    INTEGER NOT NULL,
    dish_name  TEXT    NOT NULL,
    quantity   NUMERIC(14,3) NOT NULL,
    unit       TEXT    NOT NULL,

    CONSTRAINT ingredient_request_dishes_name_present CHECK (length(dish_name) > 0),
    CONSTRAINT ingredient_request_dishes_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ingredient_request_dishes_unit_valid CHECK (
        unit IN ('SERVINGS', 'LITRES', 'KG', 'PIECES'))
);
```

**Settled by Rajeev, 2026-08-30.** The draft asked whether dishes carry a quantity or are bare context.
They carry a quantity, and the reason is not bookkeeping:

> when we force the requester to list down what they are cooking as part of this request, that will make
> them think what ingredients they need and how much they need. That way, they will have everything they
> need and they don't resort to "oh, let me get that too just in case" […] when someone looks at the filed
> inventory reports during an audit, they can see issues if someone is over-provisioning ingredients for
> the amount of food they are actually preparing.

Two consequences follow, and both are requirements rather than nice-to-haves:

**The dish list is mandatory to submit.** A request with no dishes cannot leave `DRAFT` —
`INGREDIENT_REQUEST_NEEDS_DISHES`. A draft may be as incomplete as its author likes; submitting is the
moment the discipline applies, because the discipline is the point of the field. Making it optional would
leave it blank on exactly the requests where it matters most.

**The dish list travels with the request wherever it is read** — on the record, and on the work order
(§6). The audit Rajeev describes is *the comparison* between "40 kg of rice" and "we are cooking 200
servings of khichdi", and a sheet carrying only one half of that comparison cannot be audited at all.

**The unit is the common `Unit`** that E11 establishes — `KG`, `GM`, `L`, `ML`, `PIECES`, plus
`SERVINGS`. A dish is genuinely made in any of them: a sweet in litres, a meal counted in servings, a
pickle in kilos, idlis in pieces, a spice mix in grams. Reusing that one list rather than inventing a
dish-specific one is the whole point of E11 — see D7.

**No `recipe_id`.** The draft proposed linking a dish to a catalogue recipe so the form could offer to
fill the ingredient lines from it. Rajeev's answer — *"This is JUST text and numbers"* — rules it out, and
he is right: most child kitchens cook things this temple has never written a recipe for, and a dish field
that wants to be a recipe reference would either block them or quietly train them to pick the nearest
wrong thing from a dropdown. It is a name and a number because that is all it can honestly be.

### Widening the ledger — same migration

```sql
ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_type_valid;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_type_valid CHECK (
    movement_type IN ('PO_RECEIPT','DONATION_IN_KIND','CONSUMPTION','ADJUSTMENT','ISSUE'));

ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_reference_valid;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_reference_valid CHECK (
    reference_type IS NULL OR reference_type IN (
        'PURCHASE_ORDER','MEAL_PLAN','DONATION','CORRECTION','INGREDIENT_REQUEST'));
```

Plus `MovementType.ISSUE` and `MovementReference.INGREDIENT_REQUEST` in Java, with the Javadoc the other
constants carry.

---

## 4. Where a kitchen is recorded on a movement

An `ISSUE` movement must say which kitchen received the goods. There are two candidate homes and one of
them is a trap.

**Rejected: reuse `storage_location`.** It is free text, it means *where in the store the thing sits*,
and `V70` already derives an item's location from *the most recent non-null value on its movements*.
Writing a receiving kitchen into that field would corrupt that derivation the first time an issue was
recorded — the store room's rice would relocate itself to "Deity kitchen".

**Chosen: the reference columns already on the row.** An issue movement carries
`reference_type = 'INGREDIENT_REQUEST'` and `reference_id = <the request>`, and the request carries the
kitchen. That is exactly how a consumption movement points at its meal plan, it needs no new column, and
it means the kitchen can never disagree with the request it came from. `storage_location` stays `null`
on issue movements, as it is on every consumption movement today.

---

## 5. The lifecycle

```
                    ┌──────────────────────────────────────┐
   create           │                                      │  approve
  ────────► DRAFT ──┴──► SUBMITTED ──┬──► APPROVED ────────────► ISSUED
             │  ▲                    │                            (terminal)
   delete ◄──┘  └── withdraw?        └──► DENIED (terminal)
                    (Q2)
```

`IngredientRequestStatus` is five values and the enum will carry the reasoning in prose, as
`LeaveStatus` does.

### Who may do what, in which state

Straight from the brief, with the two gaps marked. Enforced in **two layers**, the way `LeaveService`
does it — the permission on the endpoint decides *which kind of person*, an ownership check in the
service decides *which rows*, each with its own `KMS` code.

| State | Read | Edit | Delete | Transition |
|---|---|---|---|---|
| `DRAFT` | any staff | **creator only** | creator, **or any Temple Admin** | creator submits |
| `SUBMITTED` | any staff | creator **or** Temple Admin | *nobody — see Q2* | approver approves or denies |
| `APPROVED` | any staff | nobody — except recording issued quantities | nobody | storekeeper records the issue |
| `DENIED` | any staff | nobody | nobody | none. Terminal. |
| `ISSUED` | any staff | nobody | nobody | none. Terminal. |

Two notes on the brief's rules, both of which I think are right and neither of which is obvious:

- **A denied request is a dead end on purpose.** A refusal that can be edited into a different request
  and re-shown is not a refusal. The requester raises a fresh request; the denial stays on the record
  with its note, which is what someone will want to point at later. This mirrors `LeaveStatus.DECLINED`:
  *"Never re-answered — a fresh request is a fresh record."*
- **Everyone can read everyone's drafts.** The brief says so explicitly. It is the right call for a
  temple kitchen — the alternative is two people separately drafting a request for the same feast — but
  it is worth stating out loud that a draft here is not private.

### Recording the issue

One act, all-or-nothing, inside one transaction, mirroring `ServedMealService.record()`:

1. The storekeeper enters an actual quantity per line, pre-filled with the approved quantity.
2. Each non-zero line is allocated across batches by **FEFO** — `InventoryConsumptionService`'s existing
   comparator, expiry-nulls-last then received-date — with the storekeeper able to pin a batch, which
   `BatchOverride` already supports.
3. If **any** line is short, the whole issue is refused with `INSUFFICIENT_STOCK` naming the shortfalls.
   No partial writes. This matches consumption, and it means the books can never go negative.
4. One negative `ISSUE` movement per batch drawn, referencing the request.
5. The request becomes `ISSUED`. Audit event written.

**On refusing an issue the store cannot cover:** this is arguably the wrong behaviour, because the
storekeeper is recording something that already physically happened. But allowing it would drive stock
negative, which `InventoryItemService.adjust()` explicitly forbids and `E3-S7` has an acceptance
criterion against. The honest resolution is that a store whose books say 2 kg while its shelf holds 20 kg
has a counting problem, and the fix is a `COUNT_CORRECTION` adjustment — a screen that already exists —
before the issue is recorded. The error message will say that in those words.

---

## 6. The work order

Approval makes a work order available. It is a **rendered view of the request, produced on demand** —
not a stored snapshot (D3).

**Contents**, per the brief: the temple and kitchen, the reference number and the date wanted, the
reason, **the dishes being cooked and how much of each**, every line with ingredient, approved quantity,
unit and **the batches to pick from in expiry order**, the requester and the approver by name and date,
and two ruled signature boxes — one for the storekeeper issuing, one for the person taking delivery.
Signing is paper, as `E4-S11` D1 settled for the job card.

The dish list is on the sheet for the reason Rajeev gave in §3: the sheet is what gets filed, and an
auditor spotting over-provisioning needs both halves of the comparison — what was drawn, and what it was
drawn to cook — on the same page.

**How it is built** — the pipeline exists and is reused wholesale:

- A `WorkOrderTemplate` beside `JobCardTemplate`, same hand-written HTML/CSS, same Noto font stack.
- `documents.kind = 'WORK_ORDER_PDF'` (a migration widens that CHECK), rendered by
  `PlaywrightPdfRenderer` through headless Chromium — chosen originally because Indic shaping needs a
  real browser engine, which is exactly what this sheet needs too.
- **Both paths, one control.** A synchronous `GET …/work-order/print` returning `text/html` (works with
  the worker down), and a queued, versioned PDF. Per outstanding item **P4**, the UI drives both from a
  single **Download work order** button. Note P4's unresolved question — *why the print path is 5–10×
  faster than the PDF* — applies here identically, and this epic does **not** answer it.
- **Language.** All 23 (English + the 22 scheduled), offered from the client's own list so a slow call
  cannot shrink the picker — the correction `JobCardService` records in its own class doc. Fixed labels
  go through `DocumentLabelTranslator` with a new `WORK_ORDER` label set; ingredient names go through
  the glossary first, then the provider, which is what culinary vocabulary needs.
- **The cache is free here.** An approved request is immutable, so its translation can be cached on
  `(request, language)` and never invalidated.

---

## 7. Permissions, errors, audit

**Four new permissions** in `Permission.java` / `RolePermissions.java`. No migration — roles are a
CHECK constraint, permissions are not.

| Permission | Temple Admin | Kitchen Manager | Kitchen Staff |
|---|---|---|---|
| `MANAGE_KITCHENS` | ✓ | — | — |
| `REQUEST_INGREDIENTS` | ✓ | ✓ | ✓ |
| `APPROVE_INGREDIENT_REQUESTS` | ✓ | ✓ | — |
| `ISSUE_INGREDIENTS` | ✓ | ✓ | — |

`MANAGE_KITCHENS` is admin-only because which kitchens a temple runs is a structural fact about the
temple, like its settings, not a daily kitchen act. Reading the kitchen list rides on
`REQUEST_INGREDIENTS`, since you cannot raise a request without choosing one.

**Error codes** start at **4972** — 4971 is the highest in use, and **4927 and 4969 are burned and stay
unused**. One code per illegal transition, so each message can say the actual thing that is wrong:

`KITCHEN_NAME_TAKEN`, `KITCHEN_IN_USE`, `KITCHEN_NOT_FOUND`, `KITCHEN_ARCHIVED`,
`INGREDIENT_REQUEST_NOT_FOUND`, `NOT_YOUR_INGREDIENT_REQUEST`, `INGREDIENT_REQUEST_NOT_EDITABLE`,
`INGREDIENT_REQUEST_ALREADY_DECIDED`, `INGREDIENT_REQUEST_NOT_APPROVED`,
`INGREDIENT_REQUEST_ALREADY_ISSUED`, `INGREDIENT_REQUEST_EMPTY`,
`INGREDIENT_REQUEST_NEEDS_DISHES` (§3 — nothing to submit without them),
`KITCHEN_PLANS_ITS_OWN_MEALS` (D5 — this kitchen's stock leaves through the planner).

All must pass `ErrorCodeTest`: both sentences end in a full stop, no exclamation marks, no jargon from
its banned list (note "server" and "database" are banned words), and `number/1000 == httpStatus/100`.

**Audit.** New `AuditAction` values — `KITCHEN_CREATED/UPDATED/ARCHIVED/DELETED`,
`KITCHEN_JOINED_MEAL_PLANNER` and `KITCHEN_LEFT_MEAL_PLANNER`,
`INGREDIENT_REQUEST_SUBMITTED/APPROVED/DENIED/ISSUED/DELETED` — and `AuditEntityType.KITCHEN` and
`INGREDIENT_REQUEST`. Every write records before/after state, as everything else does.

**The D6 cascade audits every row it touches**, using the ordinary `INGREDIENT_REQUEST_DENIED` and
`INGREDIENT_REQUEST_DELETED` actions with a reason naming the opt-in, rather than inventing a second
vocabulary for the same two facts. A permanent delete that leaves no trace is precisely what the audit
log exists to prevent.

**A per-request event trail**, copying `po_events`: the detail screen shows *"Raised by Gopal · 28 Aug,
Submitted · 28 Aug, Approved by Radha — 'take from the older sack' · 29 Aug"*. Audit is the tamper-evident
record; this is what a human reads.

---

## 8. Screens

Two new entries in the **Kitchen** group of `frontend/lib/nav.ts`, after Inventory:

```
KITCHEN
  Recipes · Ingredients · Inventory · Ingredient requests · Kitchens
```

Requests sit above Kitchens because one is daily work and the other is setup — the same reasoning that
puts Settings last in its group. `__tests__/nav.test.ts` asserts exact href arrays per role and will
fail until updated, by design.

Every page follows the recipes shape the brief asks for, which is also the locked
`DESIGN_SYSTEM.md` §"One screen, one task": **the form is its own URL behind a top-right button**,
sidebar stays, task is the `h1`, actions top right `[Cancel] [Primary]` in a sticky header, no second
button at the foot, no `← Back`.

| Route | What it is |
|---|---|
| `/kitchens` | Table of kitchens, Edit and Delete per row, **Add a kitchen** top right |
| `/kitchens/new`, `/kitchens/[id]/edit` | `FocusScreen` with the intake form |
| `/ingredient-requests` | Table of all requests, filter chips, **New request** top right |
| `/ingredient-requests/new`, `/[id]/edit` | `FocusScreen`; lines, dishes, kitchen, date, reason |
| `/ingredient-requests/[id]` | The record: submit, approve, deny, record the issue, download the work order |

**Filters.** The brief names approved, draft and denied. That leaves out the one an approver most needs —
requests waiting on them — and the one that closes the loop. Proposed set: **All · Draft · Awaiting
review · Approved · Denied · Issued**, as a `SegmentedControl`, with the choice in the address bar so the
view is linkable and survives a refresh.

**Committing returns to the list**, with the confirmation waiting there, per `DESIGN_SYSTEM.md` rule 8
and the brief's own words. Worth noting Recipes is actually the *deviation* from that rule — it lands on
the new record — so "just like Recipes" is being read as *the button, the focus screen and the transition*,
not the destination.

### The unrelated change: Ingredients and Inventory adopt the same shape

Both pages carry an always-visible add form between the header and the list. They become a top-right
button and a `FocusScreen`, and the list gains a `?added=` flash on return — the `captured`-ref pattern
from `app/tenants/page.tsx:47-67`, which is guarded that way because an unguarded object-state flash
effect loops.

**This reverses a deliberate earlier decision**, and the reversal is better founded than the decision was.
`app/inventory/page.tsx:102-108` records that the inline panel *replaced* a `/inventory/new` focus screen
in order to match Ingredients. But `DESIGN_SYSTEM.md:281-284` is unambiguous:

> **Where it stops: four.** A form of four fields or more becomes a screen. Three or fewer stays inline.
> […] a form that grows a fourth field converts on its own rather than by anybody's opinion.

Ingredients has four to five fields; Inventory has five. Both were already in breach. The earlier change
bought consistency between two pages at the price of disagreeing with Recipes and with the rule; this one
makes all three agree with each other *and* with the document. The story will say so, so nobody flips it
back a third time.

---

## 9. The stories

Twelve, sized to be built one at a time. **E11 (units) builds first** — see D7.

| Story | What |
|---|---|
| **E10-S1** | Requirements amendment: `REQUIREMENTS.md` v1.2, snapshot, `CHANGELOG.md` |
| **E10-S2** | The kitchens register — `V74`, `is_main`, `uses_meal_planner`, `MANAGE_KITCHENS`, `KitchenIT` |
| **E10-S3** | The kitchens page — list, add, edit, delete-or-archive |
| **E10-S4** | Opting a kitchen into the meal planner, and the D6 cascade |
| **E10-S5** | The request model and the draft lifecycle — `V75`, create, edit, delete, submit |
| **E10-S6** | Review — approve and deny, with the two-layer permission and ownership rules |
| **E10-S7** | Recording the issue — `ISSUE` movements, FEFO, all-or-nothing, `ISSUED` |
| **E10-S8** | The requests list page — table, filters in the address bar, empty state |
| **E10-S9** | The request form — ingredient lines with units, the mandatory dish list |
| **E10-S10** | The request record — submit, approve, deny, record issue, event trail |
| **E10-S11** | The work order — template, PDF, print, 23 languages |
| **E10-S12** | Ingredients and Inventory adopt the focus-screen add |

**E10-S4 is the one to build carefully.** It is the only story in this epic that deletes a person's work
and reverses a decision somebody already made, and it does both without being asked twice. Its acceptance
criteria carry the whole of the D6 table, each row with a test, plus the confirmation step and the audit
row per affected request.

**UAT** (Commandment 6 — scoped to demonstrable capability, not to each story), from **UAT-067**. Note
UAT-074 belongs to E11 and is listed in that epic.

- **UAT-067** — Set up the temple's kitchens (E10-S2, S3)
- **UAT-068** — Ask the store for ingredients (E10-S5, S8, S9)
- **UAT-069** — Review, approve and deny a request (E10-S6, S10)
- **UAT-070** — Issue the ingredients and watch the stock fall (E10-S7)
- **UAT-071** — The work order, printed and translated (E10-S11)
- **UAT-072** — A kitchen starts planning its own meals (E10-S4) — the cascade, every row of it
- **UAT-073** — Adding an ingredient and adding stock (E10-S12)

E10-S1 has no manual surface of its own and is accepted on its automated tests, recording the one line
Commandment 6 asks for.

### Build order and where agents run in parallel

The shared files are the collision risk — `Permission.java`, `RolePermissions.java`, `ErrorCode.java`,
`AuditAction.java`, `AuditEntityType.java`, `MovementType.java`, `YieldUnit.java`, the migrations,
`lib/nav.ts`, `lib/api.ts`. Every one is touched by more than one story, so **I edit all of them myself in
one pass first**, and the agents that follow only touch files nobody else has.

- **Pass 0 (me):** all migrations, every enum and policy edit, the nav entries, the API client methods.
- **Pass 1 (three agents, disjoint):** backend `kitchen` package including the D6 cascade · backend
  `ingredientrequest` package · frontend Ingredients/Inventory focus screens (E10-S12 touches nothing the
  others do).
- **Pass 2 (three agents, disjoint):** frontend kitchens pages · frontend request pages · the work-order
  document code.
- **Pass 3 (me):** `./gradlew test`, `tsc --noEmit`, `npm test`, then run it locally and hand it over.

---

## 10. Questions

### Answered by Rajeev, 2026-08-30

**Q1 — Dishes.** They carry a name and a quantity, they are text and numbers only, and listing them is
mandatory. See §3 `ingredient_request_dishes` for the rule and the reasoning in his own words.

**Q3 — The double-count.** Settled better than any option offered: a kitchen declares whether it uses
recipes and the meal planner, and that declaration closes the other door. See D5 and D6.

**Q6 — The outstanding build list.** This epic lands first, then `D1`'s wipe-and-reseed runs once, with
kitchens and a few issued requests in the seed.

**Q7 — The yield vocabulary.** Grams and millilitres are added. See D7.

### Proceeding on my recommendation unless told otherwise

**Q2 — Withdrawing a submitted request.** The creator, or a Temple Admin, may withdraw an undecided
request back to `DRAFT`. It is strictly less power than the edit they already have, and it stops an
approver reviewing a request the requester is halfway through rewriting.

**Q4 — Self-approval.** Allowed, because forbidding it deadlocks a temple whose admin is its only
approver. Recorded in the audit, and printed on the work order as *"Requested and approved by Gopal Das"*
so the fact sits on the paper rather than in a log nobody opens.

**Q5 — Issuing in one visit.** Recording the issue is a single act that closes the request. A store that
can only fill six of eight lines today fills six and the kitchen raises a second request for the rest.
`PARTIALLY_ISSUED` and repeat visits are real work that nothing in the brief asked for.

### Open, and answerable later without blocking

**Q8 — Two kitchens on one planner. ANSWERED 2026-08-30 — it became E12.** The meal planner is
temple-wide, so nothing stopped two kitchens both declaring that they use it while the system could not
say which of them cooked what. Rajeev's answer was to put a mandatory kitchen on every planned meal and
show it prominently, and to take Option B: **the kitchen tags the preparation, defaulted from a
meal-level picker**, so one lunch can have its rice from the main kitchen and its sweets from the sweets
kitchen without becoming two lunches. That is
**`EPIC-12-which-kitchen-is-cooking-DESIGN.md`**. It depends on E10-S2 and blocks nothing.
