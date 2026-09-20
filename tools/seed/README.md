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
| 02 | recipes | imports the curated catalogue; ingredients arrive with it |
| 02a | curation fix-ups | the corrections to Rajeev's curated files (typos, water, coconut, commas) — run before the catalogue is built |
| 02b | build the catalogue | turns his one-file-per-recipe curation into the loader's one-file-per-book shape, and replaces `backend/src/main/resources/recipe-library/` |
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

### One thing 06 has to do that it should not have to

Water is marked "not bought" in Rajeev's curated recipes, but **the application has nowhere to keep
that**: `ingredients` has no such column, and `ingredients.supply` means "not food", which is a
different question. So phase 06 keeps water off each list by hand, with
`PATCH /api/v1/shopping-list/{ingredientId}` and `{"included": false}`, once per list.

**TODO — delete this section and the PATCH when the `not_bought` flag lands** (a column on
`ingredients` with the shopping list excluding it, V153, being built separately). Until then a
shopping list built by any other route will order water.

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
