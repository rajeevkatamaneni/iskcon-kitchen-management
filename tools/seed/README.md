# Seeding a temple's operations

Scripts that drive the application's own API, as the right person for each act, until a temple
looks like one that has been running for a month. They exist because a demo, a UAT round and
every screen that shows a trend all need a temple with a history, and typing one in takes days.

The brief is `docs/work/SEEDING-BRIEF.md` — Rajeev's words, kept verbatim, because these scripts
will be re-run many times and the intent must not drift.

## The one rule

**Everything goes through the API, as the user who would really do it.** Opening stock is counted
by the Temple Admin, an order is raised by the Kitchen Manager, a volunteer signs themselves up. So
every permission check, every stock movement, every audit row and every error the real app would
raise is the real one, and a screen built on this data is showing something the application
actually produced.

SQL appears exactly three times, each clearly named, each explaining every statement:

- `day1-reset.sql` — puts a temple back to its first morning. The app has no path for this on
  purpose; history is append-only and corrections are compensating entries, never deletions.
- `backdate.sql` — moves finished orders, bills and payments into the past, so a month of seeded
  work reads as history instead of as one busy afternoon. This is the case the brief names:
  *"Direct SQL only where the app has no path (e.g. back-dating created_at)."* Without it nothing
  is ever late — `POST /api/v1/purchase-orders` refuses an order needed before it was raised, so
  every needed-by has to be clamped to tomorrow — and every bill sits in the same aging bucket.
- `day1-counts.sql` — read-only, counts rows per table so a reset can be shown rather than claimed.

Anything else that seems to need SQL is a missing endpoint. Stop the phase and say which one.

Both writing scripts run as `kms_migration`, refuse to run as a superuser (a superuser bypasses
the row-level security that confines them to one temple), and do everything in one transaction.

## The window: 29 August → 26 September 2026

Two 15-day blocks, ending the Saturday Rajeev demos.

**Four weeks, not the three months the brief asked for, and not the eight weeks that replaced
them.** Rajeev cut it on 2026-09-19: *"ok do 4 weeks then. I need it sooner than 8 hours."* The
window is time-boxed by how long the seeding takes to run, and four weeks still carries the
festivals that make the simulation worth looking at:

```
Sri Krsna Janmastami         2026-09-04   2000 servings
Srila Prabhupada Appearance  2026-09-05    800
Radhastami                   2026-09-19    800
Vamana Dvadasi               2026-09-23
Ananta Caturdasi             2026-09-25
Visvarupa Mahotsava          2026-09-26
Ekadashi                     2026-09-07 and 2026-09-22
```

Janmastami is the largest cooking day of the year and it sits in the first block. Balarama Purnima
(28 August) falls one day outside the window.

### Why it could never have started on 27 June

The calendar does not reach back that far, and this is the constraint to remember if the window is
ever widened again.

`GET /api/v1/calendar/2026-06-27` answers 204 No Content; the rows begin at 2026-08-01. Nothing
computes astronomy on request — `CalendarPrecomputeJob` fills a forward-rolling 550-day horizon and
there is no backfill — so before 1 August there is no Ekadashi flag, no festival text, `day_type`
can never resolve to `FESTIVAL`, and `GET /api/v1/occasions/resolved` returns nothing. The brief
asks for festivals planned "exactly on the day when an actual festival is happening", and in that
stretch it is not possible.

Widening the window past 1 August 2026 therefore means building a backwards calendar precompute
first, which is a product change, not a seeding one.

## Running it

Everything takes the API and the temple as arguments, so the same scripts can later run against
staging without editing:

```bash
tools/seed/run-all.sh --api http://localhost:8091 --tenant f935450b-1b7c-4b2c-a7e3-73e40c7e31e3
```

`run-all.sh` runs the phases **in dependency order, which is not numeric order** — every ordering
round finishes before any cooking is recorded, for the reason in the comment at the top of it. Add
`--groups 3,4` to run only part of it, and `--rounds N` to change how many times the temple shops.
A phase that reports a problem does not stop the run; the problems are listed together at the end.

One phase on its own, which is how they are normally run while building:

```bash
python3 tools/seed/phase01_opening_stock.py \
  --api http://localhost:8091 --tenant f935450b-1b7c-4b2c-a7e3-73e40c7e31e3
```

Add `--dry-run` to any phase to see what it would do and write nothing.

### Signing in

The scripts sign in to real Firebase with the UAT accounts. **No password is in this repository and
none may be added.** They come from the environment:

```bash
export KMS_SEED_PASSWORD='…'                      # the shared one
export KMS_SEED_PASSWORD_TEMPLE_ADMIN_1='…'       # this one differs
```

The Firebase web API key is not a secret — it is already committed in
`frontend/.env.local.example` — and is read from there unless `KMS_SEED_FIREBASE_API_KEY` is set.

### Re-running

Every phase is re-runnable. It cannot come from the API, because most endpoints have no
"create if absent" and several things are legitimately duplicable — two orders to one vendor on one
day is a normal Tuesday. So each phase writes what it made into a ledger keyed by API and temple
(`tools/seed/.state/`, gitignored), and skips anything already there. A second run prints
`created 0, reused 412`, which is the proof it is safe to run again.

To make a phase do its work afresh, delete its keys from the ledger rather than editing it:

```bash
python3 tools/seed/phase01_opening_stock.py --forget --api … --tenant …
```

## The local stack, and how to keep it away from everything else

`docs/work/LOCAL-STACK.md` describes the API on :8080. **Do not seed against it** — it is somebody
else's working copy. Make a database of your own and run a second API on its own port:

```bash
docker exec kms-postgres psql -U kms -d postgres \
  -c "CREATE DATABASE kms_seed TEMPLATE kms_verify;"
SEED_PORT=8091 SEED_DB=kms_seed tools/seed/local-seed-backend.sh
curl localhost:8091/health
```

`local-seed-backend.sh` carries the same safety switches as `tools/local-backend.sh`: no scheduler,
no email, no tenant secrets, no paid or external providers.

## The phases

Each is one file, runs on its own, and prints what it created.

| | phase | what it does |
|---|---|---|
| 00 | preflight | checks the API, the accounts and the temple; refuses to go on if anything is missing |
| 01 | opening stock | what a real temple has on the shelf on day one — ingredients **and** supplies, with realistic quantities and today's prices |
| 01a | size the stock to the plan | run after 05: measures what the planned month actually needs and corrects the opening count and the reorder thresholds to match |
| 01c | whole stock on counted things | repairs a temple holding 400.98 coconuts: recounts each lot down to a whole number, by reversing the fractional figures somebody typed before the whole-counts rule and posting what the lot really holds. A fraction the application worked out for itself is left alone and reported |
| 02 | recipes | imports the curated catalogue; ingredients arrive with it |
| 02a | curation fix-ups | the corrections to Rajeev's curated files (typos, water, coconut, commas) — run before the catalogue is built |
| 02b | build the catalogue | turns his one-file-per-recipe curation into the loader's one-file-per-book shape, and replaces `backend/src/main/resources/recipe-library/` |
| 02c | drop the old library | removes the vendored books from `master_recipes` after the curated ones are loaded — the loader upserts and never deletes |
| 03 | complete the ingredients | alias, unit, Ekadashi flag, category, pack sizes, price, a preferred vendor and that vendor's details |
| 04 | staff and schedules | checks the roster and the shift template are sane before anything is planned against them |
| 05 | first 15 days | three meals a day, festivals on their real dates, temple events, outside events both delivered and collected |
| 06 | shopping list to orders | the list, then purchase orders, supplies included |
| 07 | deliveries | full, partial, late, some quantity rejected for poor quality, and returns after the fact |
| 08 | invoices and payments | uploaded receipts, a credit note for a return and for a late delivery, a void, then UPI and cash with proof |
| 09 | sister kitchens | five of them — two that plan their own meals, three that only ask for ingredients |
| 10 | ingredient requests | raised, approved, denied, then issued and received |
| 11 | the next blocks | 05 to 10 again, with some festival meals cooked jointly across kitchens |
| 12 | cooking | what was actually made — as planned, over, short, and with leftovers |
| 13 | volunteers | shifts filled, part-filled and empty, and people signing up |
| 14 | giving | wish list, sponsorship of a listed item, general donations and cash taken in person |
| 15 | equipment | the register, service schedules, services with their cost, scrapped and in-repair |

### The order they are run in

Not the numeric order. The conductor set this on 2026-09-19 so that staging becomes usable early
rather than all at the end, and so that if time runs out what is missing is the least interesting
part:

1. **Makes the app usable** — 00 preflight, 01 opening stock, 02 catalogue, 03 complete the
   ingredients. Until these are done every other screen is empty.
2. **The story he demos** — 05 the meal plan, 06 list to orders, 07 deliveries, 08 invoices and
   payments. This is the end-to-end path the procurement work was built for.
3. **Breadth** — 09 sister kitchens, 10 requests and issuing, 11 the second block with joint
   festival meals, 12 cooking and actuals.
4. **Last, and cheapest to drop** — 13 volunteer shifts, 14 giving, 15 equipment servicing.

04 runs inside group 1 as a check rather than a build; it writes nothing unless the roster is
wrong. If a group overruns, move to the next rather than stalling in it.

### What each phase assumes

- **00** assumes nothing. Run it first; it is the one that tells you what is wrong.
- **01** assumes a temple reset to day one, or an empty catalogue. It creates ingredients directly
  rather than waiting for recipes, because a temple counts its shelves before it plans a menu —
  that is the order Rajeev asked for.
- **02** assumes the curated catalogue is loaded into `master_recipes`. It falls back to the
  library's Karnataka and Andhra books if the curated set is absent.
- **03** assumes 01 and 02 have both run, because it completes ingredients from both sources.
- **05 onwards** assume the calendar covers the dates being planned. See the window above.
- **06** assumes 05, and every phase after assumes the one before it.

### Two things the brief asks for that the application does not have

**A "receive at the sister kitchen" step.** The ingredient-request lifecycle is
`DRAFT → SUBMITTED → APPROVED → ISSUED`, with `SUBMITTED → DENIED` off the side. **Issuing is the
last event** — it is what moves the stock out of the store — so "record deliveries to the
individual kitchens" is the issue itself, not a step after it. There is no partial state either: a
kitchen that gets less than it asked for raises a second request, which is what phase 10 does.

**An equipment service request, and a payment for it.** There is no request entity and no payable.
A service is recorded *after* it happened, with its cost written on the record, and that cost never
reaches the invoice or payment tables — it cannot be paid, part-paid or chased. The nearest thing
to a request is the **service schedule**: set how often a machine needs looking at and the
equipment screen shows it as `DUE_SOON` or `OVERDUE`. Phase 15 builds that, and says so rather than
faking a request. If Rajeev wants a real one with an approval and a payment, it is a feature to
build, not data to seed.

### Water, and the one place the mark has to be set

`ingredients.is_not_bought` landed in V153, so the shopping list now excludes water by itself and
phase 06's per-list `PATCH` is a no-op left in place for a temple seeded before that.

**But the mark has to be set when the ingredient is created, and nowhere else.** Copying a library
recipe sets it only on an ingredient the *import creates*; it will not touch one the temple already
has. That is deliberate and tested: copying a recipe is `MANAGE_RECIPES`, which a Kitchen Manager
holds, and the buying policy is `MANAGE_BUYING_POLICY`, the Temple Admin's alone, so the import
refuses to change a row it does not own and records `notBoughtNotApplied` in its audit entry
instead. This toolkit creates the whole catalogue in phase 01 before any recipe is imported, so
**every mark would be refused if phase 01 did not send it** — which is exactly what happened the
first time, and left water on the temple's shopping list with fifteen audit rows saying so. Phase
01 now sends `notBought` from the catalogue. `03a-mark-not-bought.py` repairs a temple seeded
before that fix, reading the library first so it can only ever copy a mark that is really there.

Two other small things follow from the same run, for whoever picks this up:

**An ingredient the temple never buys still counts as low stock.** `InventoryItemService.lowStock()`
filters on `belowThreshold` alone and knows nothing about `is_not_bought`. "Low" is
`available < threshold`, where available is what is on the shelf minus what the plan has committed;
water is committed by every recipe that uses it and is never received against, so it falls further
behind every time a meal is planned. On staging it reached **−1,358 L**.

Its own comment says that method is *"the single source of what's low"*, and that is the size of
the problem — **one predicate, four places**:

1. the **low-stock list** on the store screen,
2. the **count on the dashboard** (`itemsBelowThreshold` in `/api/v1/today`),
3. the **nightly low-stock digest** (E3-S3), which mails it out,
4. the **reorder suggestions** (E5-S2).

The seeding answer is to stop tracking water as a stock item at all —
`DELETE /api/v1/inventory/items/{id}`, which removes the shelf record and leaves the ledger
untouched — because a temple does not count tap water into its store. The product answer, if Rajeev
wants one, is to exclude never-bought ingredients from that query, which fixes all four at once.

**A stale platform notice is not the reset's to clear, and the next person will assume it is.**
`platform_notices` (V66) carries **no `tenant_id` on purpose** — the whole value of a notice is that
one raised in Bengaluru is read in Mayapur — so a per-tenant reset cannot reach it and should not.
The same migration says a notice is **never deleted, only withdrawn**, and only by the raising
temple or a platform operator. So a notice left over from testing survives a Day-1 reset by design,
and taking it down is an operator act through `POST /api/v1/notices/{id}/withdraw`, not a line in
`day1-reset.sql`. Worth checking after any reset: a withdrawn notice still appears in
`/api/v1/notices`, but only `/api/v1/notices/feed` is what a person sees on Today, and the feed is
per-person — somebody who dismissed a notice sees nothing while everybody else still sees it.

### What a library recipe cannot say about an ingredient

Worth recording because it is a gap in the product, not in the seeding, and it is a Rajeev decision
rather than something to fix in passing. A library ingredient line carries a name, a quantity, a
unit, a preparation note and the never-bought mark — and that is all. It cannot say that something
is a **supply** rather than food, cannot say it is **prohibited on Ekadashi**, cannot give it a
**category**, and cannot carry **aliases**. These are not dropped in transit the way `prep` once
was; there is no field for them, so the book has no way to express them. The consequence is that an
ingredient the import creates arrives as food, allowed on Ekadashi, with a category guessed from a
keyword map that openly cannot name two dozen common items — water among them, which is how water
came to be categorised "Other". Closing it would mean four fields on the line, four on the loader,
and a decision about who is allowed to set them on import, since the same permission argument that
restricts the never-bought mark applies to every one of them.

## Turning the curated recipes into a library the loader can read

Rajeev's curation tool writes **one file per recipe**. The loader reads **one file per book**, with
the recipes nested inside and a category index at the top. `02b-build-catalogue.mjs` is the
converter between them, and it is deliberately a committed part of this toolkit rather than
something improvised at load time — the staging load should not depend on a script in a temp
directory.

```bash
node tools/seed/02b-build-catalogue.mjs --dry-run   # says what it would write, writes nothing
node tools/seed/02b-build-catalogue.mjs             # replaces the recipe-library directory
```

It **refuses to write unless the output round-trips**: every approved recipe present, every field
it went in with still on it, every ingredient line matching value for value. That check is worth
more than it looks, because the one thing this converter exists to preserve — `prep` and
`not_bought` — is exactly what the old loader dropped, and a converter that lost them again would
put the same hole in a different place. Proven by breaking it on purpose: strip `prep` on the way
through and it reports all 83 losses by recipe and line, exits 1, and writes nothing.

Measured on the current set: **44 approved recipes** (one is still marked `needs-work`),
454 ingredient lines, 83 preparations and 15 not-bought marks, into 2 books — Karnataka with 42
and Andhra Pradesh with 2.

**It removes the 33 vendored books.** That ends the rule the loader's own comment states — that
the books are byte-for-byte copies so they can be diffed against upstream. Rajeev's decision,
2026-09-19: his approved recipes are the master catalogue now and there is no upstream left to
diff against. `--keep-old` leaves them for a side-by-side look.

**Building the books does not load them.** Nothing changes in a temple until somebody calls
`POST /api/v1/library/recipes/load` as the super admin, and that must wait for the loader fix that
carries `prep` and `not_bought` — loading the curated catalogue with the old loader throws away
the very fields it exists to carry.

## Who does what, and why it is resolved rather than named

Every act is done by the person who would really do it, and **who that is differs between
environments**, so the scripts work it out at run time instead of hardcoding an account.

`common/config.py`'s `kitchen_manager()` takes two questions rather than one:

- **`needs_approval`** — approving or denying an ingredient request needs a real KITCHEN_MANAGER or
  the Temple Admin. Nobody else can.
- **`needs_planner`** — touching `/api/v1/meals`, `/meal-plans`, `/meal-crew` or `/job-cards` needs
  an account whose **kitchen** has `uses_meal_planner`. `PlannerKitchenGuard` lets a Temple Admin
  through always and everyone else only on that basis, so **the role is not enough**. `whoami`
  answers it directly with `canPlanMeals`.

Both matter, and both were learned from staging refusing something local never did. Locally
`ikms.kitchen-staff.5` holds KITCHEN_MANAGER; on staging it is plain KITCHEN_STAFF and **no account
holds the manager role at all**, which `docs/uat/README.md` confirms is correct — it lists all five
as kitchen staff. A hardcoded account would have planned, ordered and received perfectly well and
then failed only at phase 10's approvals, four groups deep. And the first version of the resolver,
which checked the role but not the kitchen, picked somebody in a restaurant kitchen that does not
plan and got **403 KMS-400183** on phase 05's first call.

Where nobody suitable exists the Temple Admin stands in **and the phase says so in its output**,
because a silent fallback would hide exactly the permission split the product is built around.

## Near-duplicate ingredient names, and where they are dealt with

Rajeev's 45 approved recipes name 101 distinct ingredients, and about fourteen of those are the
same thing written two ways. Left alone the temple ends up holding one item twice — two lemons,
two ravas, two sagos — which is the defect the no-duplicate-ingredients work exists to stop.

They are handled in two different places, on purpose:

- **Two are outright misspellings** — `Lemo` for Lemon, `Upma Ravva` for `Upma Rava` — and
  `02a-curation-fixups.mjs` corrects them in the curated files. A misspelling has no reason to
  survive.
- **The other twelve are real alternative names** — Sago and Sabudana, Cashew and Cashew nuts,
  Semolina and Upma Rava, Mustard and Mustard seeds — and those are **aliases**, declared in
  `data/catalogue.json` and seeded by phase 01. The application matches on aliases, so the recipe
  import finds the existing ingredient. Renaming them would throw away a word the cooks use.

The application's own matcher singularises before comparing, so Lemon/Lemons and Raisin/Raisins
would have resolved without help. Sago/Sabudana and Cashew/Cashew nuts never would, because they
are different words.

**Worth doing upstream:** the curation tool could flag near-duplicates as the list is built —
comparing each new name against the ones already approved, the way the application does — so these
never reach a catalogue. It is cheaper to catch at the point somebody is already looking at the
name.

## Day one

`day1-reset.sql` puts a temple back to its first morning: it keeps the people, the settings, the
kitchens, the meal kinds, the calendar, the staff, the wish list and the vendors, and clears
everything the temple has *done*. The script explains every table it touches and why, and lists the
five judgement calls it made. Read it before running it.

```bash
docker exec -i kms-postgres psql -U kms_migration -d kms_seed \
  -v tenant=f935450b-1b7c-4b2c-a7e3-73e40c7e31e3 -f - < tools/seed/day1-counts.sql   # before
docker exec -i kms-postgres psql -U kms_migration -d kms_seed \
  -v tenant=f935450b-1b7c-4b2c-a7e3-73e40c7e31e3 -f - < tools/seed/day1-reset.sql
docker exec -i kms-postgres psql -U kms_migration -d kms_seed \
  -v tenant=f935450b-1b7c-4b2c-a7e3-73e40c7e31e3 -f - < tools/seed/day1-counts.sql   # after
```

It also removes the thirteen test vendors — `VERIFY-…`, `VERIFY2-…`, `UAT-test…`, `E2E…` — and
keeps the eight real ones without their prices. That is the only place it deletes *some* rows of a
table rather than all of them, so it prints every vendor by name: read the list rather than the
count.

It runs as `kms_migration` and **refuses to run as a superuser**, because a superuser bypasses
row-level security and RLS is the thing confining it to one temple.

## Where the facts came from

`API-NOTES.md` is the working reference for writing a phase — the calls, their permissions, their
exact field names, and a numbered list of the things that will otherwise cost a phase a rewrite.
`reference/` holds the two long surveys it summarises. All of it was read from the controllers and
migrations, so when a call refuses, believe the API and correct the notes.
