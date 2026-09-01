# EPIC 2 (continued) — The shared recipe library

**Status: BUILT 2026-08-22.** All nine stories, E2-S9 to E2-S17. Every question in §10 was answered
on 2026-08-21 and each answer is recorded there with the argument that produced it. §12 records what
the building itself changed, which was four things the design had wrong.

**Origin:** Rajeev, 2026-08-21 — a redesign brief for the Recipes page. A platform-wide master
recipe collection that every temple can search and pull from, a single search box spanning that
collection and the temple's own recipes, and a New Recipe page carrying every field the source data
holds.

**Source:** `github.com/kranthimj23/ikms`, branch `ikms-rbac-role-based-access-control`,
folder `recipe_books/`.

---

## 1. What is actually in that repository

I cloned it and read all of it. The brief describes it from the outside; here is what it is from the
inside, because three of the decisions below turn on these numbers.

### The folders

| Path | What it holds |
|---|---|
| `recipe_books/states/` | 109 files. Nine per state: one `<state>.py` holding that book's UI strings, category names and front matter, and five `<code>_r1..r5.py` holding the recipes — `ap_r1.py`, `kn_r3.py`. Three dishes per category. The first generation. |
| `recipe_books/eight/<state>/p1..p5.py` | 32 state packages. The five more dishes per category that take each category from three to eight. `eight/__init__.py` says why: *"A kitchen that opens Sabji's, Wet on a Tuesday and finds the same three it cooked last Tuesday goes back to the list it already knew. Eight is a fortnight of them."* |
| `recipe_books/vegan_jain/<state>.py` | The vegan and Jain categories, same shape, added per state. |
| `recipe_books/extract.py`, `build.py`, `eight/apply.py` | The pipeline. `apply.py` merges the three sources and writes the result. |
| **`ikms/data/recipe_books/<state>.json`** | **32 files, 23 MB. The built output — every book, merged, in one uniform JSON schema.** |

**We do not need to read a single `.py` file.** The pipeline in that repo has already done the
extraction the brief asks for; `ikms/data/recipe_books/*.json` is its output and the three source
folders all flow into it. Parsing Python source to recover data that is already sitting beside it as
JSON would be work done twice and wrong the second time. We ingest the 32 JSON files.

### The numbers

- **5,376 recipes.** 32 states × 168 each, exactly — 21 categories × 8 dishes, with no gaps.
- **21 categories:** beverages, breakfast-items, chutneys, dal, economical-recipes, ekadashi,
  fried-items-farsan-snacks, jam-pickles, kadhi-raita, khichadi, masalas, rice, rotis, sabji-s-dry,
  sabji-s-wet, soups-salads, sweets, sweets-sustainable, fhc-sabjis, **vegan**, **jain**.
  (Vegan and Jain are categories in the data, not flags — 256 dishes each.)
- **16 languages**, one per book: Telugu, Kannada, Tamil, Malayalam, Marathi, Gujarati, Punjabi,
  Bengali, Odia, Assamese, Hindi, Konkani, Nepali, Khasi, Mizo, Meiteilon, Nagamese.
- **4,104 distinct recipe names.** 1,272 are repeats across states — *Sabudana Khichdi* appears in
  17 books, *Kuttu ki Puri* in 16. **A search result showing only a name is ambiguous 24% of the
  time.** This is Q3 below.
- **2,238 distinct ingredient names** across 46,337 ingredient lines.
- **Stripped of the local language, the whole library is 8.95 MB** — about 1.7 KB a recipe. This
  number decides §4.

### The per-recipe schema

Every one of the 5,376 recipes carries these 15 keys, with no exceptions:

```json
{
  "slug": "majjige",
  "name": "Majjige",              "name_l": "ಮಜ್ಜಿಗೆ",
  "cat": "beverages",
  "badge": "Everyday",
  "sub": "Spiced buttermilk",     "sub_l": "ಮಸಾಲೆ ಮಜ್ಜಿಗೆ",
  "yield": "20 L",
  "per": "200 ml",
  "cost": 1450,
  "ing":    [ { "name": "Curd, fresh", "name_l": "ತಾಜಾ ಮೊಸರು", "qty": "8 L", "qty_l": "ಲೀ.",
                "scaled": { "50": "4 L", "100": "8 L", "250": "20 L", "500": "40 L" } } ],
  "method": [ { "en": "Whisk the curd smooth…", "l": "ಉಳಿದ ನೀರು ಹಾಕುವ…" } ],
  "catering": null,
  "why": "The everyday summer drink of Karnataka…",  "why_l": "ಕರ್ನಾಟಕದ ನಿತ್ಯದ…"
}
```

And four more on some of them, in exactly three tiers — the data is not ragged, it is layered:

| Tier | Count | Extra keys |
|---|---|---|
| The original three-per-category | 3,192 | none |
| The `vegan_jain` additions | 168 | `region` |
| The `eight` additions | 2,016 | `region`, `notes` (`{start, vessel, season}`, each `{en, l}`), `tags`, `serve_with` |

- **`badge`** — 5 values: Everyday (3,449), Moderate (1,018), Festival (665), Sustainable (175),
  Economical (69).
- **`tags`** — 40 values, and the most useful thing in the file for searching: *Gluten-free* (1,764),
  *Jain-safe* (1,683), *Not for Ekadashi* (1,635), *Vegan* (1,070), *Contains dairy* (875),
  *Contains nuts* (314), *Travels well* (162), *Ekadashi-safe* (119), *Rock salt only*, *Keeps 6
  months*, *No root vegetables*, and so on.
- **`serve_with`** — three dish names from the same book. A menu suggestion, already written.
- **`cost`** — an integer, rupees, for the whole yield at 100 devotees. Indicative bulk rates.
- **`scaled`** — 19,356 of the 46,337 ingredient lines carry quantities pre-computed at 50 / 100 /
  250 / 500 devotees.

### Two things that will bite, found by reading the data rather than the brief

**Quantities are strings, not numbers.** `"qty": "8 L"`, `"200 gm"`, `"20 nos"`. Our
`recipe_ingredients` table wants `NUMERIC(12,3)` and a unit from a fixed vocabulary. I ran the parse
across all 46,337 lines: **every one parses**, and the unit tokens are only ever `gm` (29,409),
`Kg` (10,829), `L` (3,548), `ml` (2,107), `nos` (404), `Nos` (21), `pieces` (16), `pcs` (3) —
which map onto our `GM/KG/L/ML/PIECES` with nothing left over. This one is fine, and I checked
rather than assumed.

**Yields do not fit our schema.** `recipes.base_yield_unit` has `CHECK (base_yield_unit IN
('SERVINGS','LITRES'))`. The library's yields are free text — `"20 L"`, `"12 Kg"`, `"300 idlis (3 per
devotee)"`, `"~4 Kg (about 3 batches)"`. Normalised, all 5,376 resolve cleanly: **2,918 LITRES, 1,619
KG, 839 PIECES**, none left over, and the 208 written with a `~` are one character from a number. But
**2,458 of them, 46% of the library, cannot be expressed in the recipes table as it stands**, and not
one recipe in the library yields in `SERVINGS`. Full working in §4. This is Q1.

**And one worry that turned out to be nothing.** I grepped all 46,337 ingredient lines for onion,
garlic, mushroom and egg. Two hits, and both are *`Onion-free chaat masala`* and *`Garlic-free
panch phoron`*. The books are sattvic as they claim. But note what those two names prove: a sattvic
check written as a substring match would refuse both of them, which is the opposite of correct.
Enforcement must match the *ingredient row* by name, not the letters inside it.

---

## 2. What I would push back on

Commandment 9. Five of these, and Q1–Q7 in §10 are the ones I cannot settle alone.

### 2.1 — We do not need new infrastructure, and I would not add any

The brief asks what GCP infrastructure suits storing JSON and searching it quickly. The honest
answer is that this dataset is far too small to justify a second system.

**8.95 MB. 5,376 rows. 1.7 KB each.** That is smaller than the meal-plan data one temple will
accumulate in a year. Here is the comparison, done properly rather than dismissed:

| Option | Fits? | What it costs us |
|---|---|---|
| **Cloud SQL Postgres — the instance we already run** | Yes | Nothing new. One table, one GIN index. Searches at this size land in single-digit milliseconds. |
| Firestore | Yes | A second database with its own IAM, its own backups, its own consistency story, and no joins to the tenant data the same page renders. Its query model cannot do "any word in name, subtitle, ingredients or tags" without a hand-built inverted index — we would be writing search from scratch on top of a document store. |
| Cloud Storage + Vertex AI Search | Yes | A managed search product priced per query, an indexing lag between a super-admin's edit and the temple seeing it, and semantic matching where the brief asks for literal prefix matching. Roughly $30–60/mo against a $150 budget alarm. |
| Elasticsearch / OpenSearch on GKE | Yes | The smallest sane cluster costs more per month than the entire current platform, and it is a second thing to patch, back up and be woken up by. |
| BigQuery | No | An analytics warehouse. Seconds per query, priced per byte scanned. Wrong tool. |
| Memorystore / Redis | No | A cache, not a store. SYSTEM_DESIGN §12 already rejected it with a named revisit trigger. |

SYSTEM_DESIGN §5 is explicit: *"One database engine keeps the operational surface minimal."* §12
lists the trade-offs we accepted and the measurement that would reopen each. Nothing here trips one.

**Recommendation: the master library is a table in the Postgres we already have.** Full-text search
uses `tsvector` — built into Postgres, no extension — with a GIN index and prefix queries
(`majjig:*`) for the type-ahead. I deliberately am *not* proposing `pg_trgm`: it would buy typo
tolerance we have not been asked for, and it is an extension, and our Flyway runs as an
**unprivileged** migration role in tests, so an extension is a thing that can fail on a deployment
having passed CI. If typo tolerance is wanted later, `pg_trgm` is trusted from PG13 and is a
one-line migration then.

**The revisit trigger, written down so it is not a matter of opinion later:** move the library out
of Postgres when it passes ~250,000 recipes or when p95 search latency passes 300 ms on the real
instance. At 5,376 rows we are two orders of magnitude away.

**The one piece of genuinely new plumbing** is not storage, it is the loader: a Spring Boot
`ApplicationRunner` behind a flag, run as a Cloud Run **job** against the same image, that reads the
32 JSON files and upserts them. Same image, same VPC, same database. No new service.

### 2.2 — Do not throw away the translations *(WITHDRAWN 2026-08-21 — see Q5)*

**This argument was wrong and is kept only because the reasoning matters.** It assumed the reader
speaks the language of the state the recipe came from. Rajeev's counter closed it: a Bengali cook in
Bangalore needs Bengali, and the Karnataka book carries Kannada. Read Q5 first; what follows is the
case that lost, and one narrower use of the data that survives it.

The brief says strip the local language and keep only English. In the UI and in search, yes,
absolutely — that is the right call, and one language in one search index is what makes the
type-ahead honest.

But not in the *store*. We have already built E2-S6: recipe translation into Indian languages, with
a per-tenant glossary that exists *because* machine translation mangles culinary terms — "Toor Dal"
comes back as a transliteration, not तूर दाल. Bhashini calls cost money and every one of them is a
guess.

These books contain **16 languages of human-written, kitchen-register translation** — of the recipe
name, its subtitle, every ingredient, every method step, and the "why". `karnataka_recipes_kn.py` in
the sibling experiment folder says it plainly: *"Register is instructional kitchen Kannada — the way
a head cook tells a junior what to do next, not textbook prose."* Bhashini will not produce that.

Keeping them costs 14 MB of disk. Discarding them costs a translation quality we cannot buy back.

**Recommendation: store the `_l` fields in the master row. Never show them in search or in the
English UI. On import, seed `recipe_translations` from them, marked `provider = 'book'`,** so a
temple that presses Translate on an imported recipe gets the human translation instead of a machine
one — and the existing translate/print pipeline needs no change to benefit.

*Superseded. What survives is not display text but glossary seed data: 5,193 distinct
(language, ingredient) pairs, 90.3% with one agreed translation across every book using them. The
honest shape of it — 2,238 distinct ingredients, median **one** language each, 67% in a single
language — means the long tail is worthless, and the value is entirely in the head: of the 100
most-used ingredients the median coverage is **14 languages**. Getting "toor dal" right in Bengali
improves every Bengali translation the Bihari cook reads, including of recipes from outside these
books. Two caveats: `translation_glossary` is tenant-scoped with RLS, so it needs either per-tenant
seeding at provisioning or a new platform-level table; and it is a one-time extraction. **Open —
Rajeev's call.** If no, the translations are simply dropped and nothing is mined.*

### 2.3 — "Search any key in the recipe" is not what you want

Taken literally, every key includes `method` and `why` — a paragraph a recipe. Type "boil" and
roughly three thousand of the 5,376 match, because nearly every recipe boils something. The filter
stops filtering exactly when the list is longest.

**Recommendation: a weighted search document over the fields that identify a dish** — name and
subtitle (weight A), ingredient names and tags (B), category, state and badge (C) — ranked so a name
match always outranks an ingredient match. Method prose and the "why" are excluded. That gives you:
"paneer" → dishes containing paneer; "jain" → the Jain category and everything tagged *Jain-safe*;
"maj" → Majjige, Majjige Huli. Which is what the brief actually wants.

If you do want method text searchable, it goes in at weight D, below everything, and I would add it
in a second pass once the ranking has been seen working.

### 2.4 — Removing the archived checkbox strands archived recipes

The brief removes *Show archived recipes*. But `app/recipes/page.tsx` carries this comment, and it
was right when it was written:

> *Archiving would otherwise be a disappearance: an archived recipe is off this list by design, and
> without a way to see one there is no way back to it to restore it.*

Delete the checkbox and nothing else, and every archived recipe becomes permanently unreachable —
there is no other route to `/recipes/{id}` and no way to press Restore.

**Recommendation: remove the checkbox, and make archived recipes appear in the search results.**
They are the temple's own recipes; they carry the existing *Archived* badge; they open in full
screen where Restore already lives. The checkbox goes, the capability stays, and nothing on the
screen has to explain itself. Archived recipes stay out of the default empty-search list, so nothing
changes for someone who has never archived anything.

### 2.5 — A tooltip on a disabled button is not reachable *(AGREED 2026-08-21)*

Rajeev: *"I agree. Do what you proposed. I just want that behaviour. How you make it do that is at
your own discretion."* The behaviour is the contract — the explanation must reach a mouse, a
keyboard and a touch screen. `aria-disabled` and a `Tooltip` primitive are how, and may change if a
better how appears; the three acceptance criteria in E2-S14 are what must not.

The brief wants the Edit button on a library recipe greyed out with a tooltip explaining why. A
genuinely `disabled` button fires no pointer events in Safari and receives no focus anywhere, so
neither a hover tooltip nor a keyboard one ever appears — the explanation exists in the markup and
is invisible to the person who needs it. And DESIGN_SYSTEM has no tooltip primitive yet.

**Recommendation:** build `components/ds/Tooltip.tsx` once, and render the button as
`aria-disabled="true"` rather than `disabled` — it looks identical, still refuses the click, but
stays focusable and hoverable, so the tooltip reaches both a mouse and a keyboard. It needs a touch
answer too: on a touch device the first tap shows the tooltip rather than doing nothing.

---

## 3. Where the library lives

One new platform-scoped table, `master_recipes`. Not tenant-owned: its entire purpose is that a
recipe written for Vijayawada is read in Bangalore. This is the second table in the product to cross
tenant isolation on purpose, after `platform_notices` (V66), and it follows that precedent exactly.

```
master_recipes
  id                 UUID PK
  state_slug         TEXT NOT NULL          -- 'karnataka'
  state              TEXT NOT NULL          -- 'Karnataka'
  book_language      TEXT NOT NULL          -- 'Kannada'
  recipe_slug        TEXT NOT NULL          -- 'majjige'
  name               TEXT NOT NULL          -- as the book wrote it: 'Sabudana Khichdi'
  display_name       TEXT NOT NULL          -- after the Q3 ladder: 'Sabudana Khichdi (Maharashtra)'
  -- Which rung the ladder stopped on: 0 name alone, 1 + state, 2 + state and category. Lets the
  -- search row decide whether to print the state again, without sniffing for a bracket in a string.
  disambiguated_by   SMALLINT NOT NULL DEFAULT 0
  subtitle           TEXT
  category_key       TEXT NOT NULL          -- 'beverages'
  category_name      TEXT NOT NULL          -- 'Beverages'
  badge              TEXT NOT NULL          -- Everyday|Moderate|Festival|Sustainable|Economical
  yield_text         TEXT NOT NULL          -- '20 L', kept verbatim
  yield_qty          NUMERIC(12,3) NOT NULL -- 20, parsed; all 5,376 resolve (§4)
  yield_unit         TEXT                   -- 'LITRES'
  per_head_text      TEXT                   -- '200 ml'; null on 703
  indicative_cost    NUMERIC(10,2)          -- rupees, whole yield
  region             TEXT                   -- 'Statewide', 'Rohtak'
  why                TEXT NOT NULL
  catering_note      TEXT
  note_start         TEXT
  note_vessel        TEXT
  note_season        TEXT
  tags               TEXT[] NOT NULL DEFAULT '{}'
  serve_with         TEXT[] NOT NULL DEFAULT '{}'
  ingredients        JSONB  NOT NULL        -- [{name, qty, qty_value, qty_unit, scaled}]
  method             JSONB  NOT NULL        -- ["step", "step"]
  source_ref         TEXT NOT NULL          -- 'kranthimj23/ikms@<sha>:karnataka.json'
  search_doc         tsvector GENERATED ALWAYS AS (...) STORED
  created_at, updated_at, updated_by_user_id
```

Named columns rather than one JSONB grab-bag, deliberately — a `data JSONB` holding fourteen things
nobody has named is the unnamed abstraction we agreed not to build. `ingredients` and `method` are
JSONB because they are ordered lists whose only consumer is this table; a `master_recipe_ingredients`
side table would buy nothing since nothing joins to it.

**Uniqueness:** `UNIQUE (state_slug, recipe_slug)` — the reload key, so re-running the loader
updates in place instead of duplicating 5,376 rows.

**Indexes:** GIN on `search_doc`; btree on `(category_key)` and `(state_slug)`.

**RLS, following V66 rather than inventing a second pattern:**

- `ENABLE` + `FORCE ROW LEVEL SECURITY`, as every table gets.
- **SELECT** — any connection whose verified identity resolves to an ACTIVE user, whatever their
  temple or role. Same `app.auth_uid` read escape (V2), same `NULLIF(…, '')` so a `RESET` fails
  closed rather than raising.
- **INSERT / UPDATE / DELETE** — only where that user's `role = 'SUPER_ADMIN'`. In the database, not
  just in the service.

The service layer enforces it a second time with a new permission, **`MANAGE_RECIPE_LIBRARY`**, held
by `SUPER_ADMIN` alone in `RolePermissions.java`. Endpoints declare the permission, never the role.
Two locks on the same door, which is what V66 does and why it is worth copying.

**`delete_tenant_cascade` (V44) finds tables to purge by looking for a `tenant_id` column.**
`master_recipes` has none, so deleting a temple leaves the library untouched — correct, and worth
stating because the reverse would be catastrophic.

---

## 4. How a temple gets its own copy

A temple's copy is a **full, independent row set in the existing `recipes` and `recipe_ingredients`
tables** — not a pointer, not a view, not a materialised overlay. Everything downstream (scaling,
meal plans, sufficiency checks, order generation, PDF, translation) already reads those tables, and
a second kind of recipe that half of them understand is how you get a bug in the shopping list six
weeks from now.

Pressing **+** does this, in one transaction:

1. **Resolve the category.** Map the master's `category_key` to one of the temple's own
   `recipe_categories`, creating it if absent. A temple starts with nine seeded categories
   (`TenantProvisioningService`); the library has 21. The mapping is a fixed table in code —
   `sabji-s-dry` and `sabji-s-wet` both land on *Sabji*, `fried-items-farsan-snacks` on *Snacks*,
   `ekadashi` on the seeded fasting-compatible *Ekadashi*, and eight new ones (Chutneys, Khichadi,
   Masalas, Kadhi & Raita, Jam & pickles, Soups & salads, Vegan, Jain) are created on first use.
2. **Resolve every ingredient** against `ingredients` for that tenant, by `lower(name)`, creating
   what is missing with `canonical_unit` from the parsed qty token and `category` from a curated
   keyword map, defaulting to `Other`. Created rows carry `library_derived = true`. Full rules in Q4.
3. **Run sattvic enforcement** — the existing service path, unchanged. Matching resolved ingredient
   *rows*, never substrings, so *Onion-free chaat masala* passes. If a temple has flagged something
   the recipe needs, the import is refused with the ingredient named. **No override on import**: an
   override needs a reason and a Temple Admin, and the place to give one is the recipe form, not a
   plus icon.
4. **Insert the recipe**, `tenant_id` taken from the verified token — never from the request, never
   from the master row. `master_recipe_id` records where it came from.
5. **Insert the ingredient lines**, quantities parsed to `NUMERIC`, in the book's order.
6. **Seed `recipe_translations`** from `localised`, at `recipe_version = 1`, `provider = 'book'` (§2.2).
7. **Write the audit event.** `RECIPE_IMPORTED`, with the master id and the state.

**Tenant isolation, stated plainly:** the copy lands in tables that both call `enable_tenant_rls()`.
The application connects as `kms_app`, which holds neither DDL nor `BYPASSRLS`, so the policy is not
advisory. `tenant_id` is set from the verified Firebase token by `TenantAwareDataSource` before the
statement runs. **Temple A cannot see, read, edit or even count temple B's imported recipes**, for
exactly the same reason it cannot see their donations — the database refuses, not the code. An edit
made to an imported copy touches one tenant's row and is invisible to every other temple and to the
library itself. `RowLevelSecurityIT` gets a case for it.

**The reverse direction does not exist.** A temple can never write to `master_recipes`. Its RLS
write policies name `SUPER_ADMIN` and nothing else.

### The yield problem (Q1)

Step 5 cannot run for nearly half the library without a decision.

**What we have.** `recipes.base_yield_qty NUMERIC(12,3)` plus `base_yield_unit TEXT` with
`CHECK (base_yield_unit IN ('SERVINGS','LITRES'))`, mirrored by the two-constant `YieldUnit` enum
that `RecipeService.parseYieldUnit()` validates against. The unit is a *label*, not arithmetic:
`RecipeScaler.ratio()` is `target / base` and never inspects or converts the unit, so scaling only
requires base and target to be the same thing.

**What the library has.** Free-text strings — `"20 L"`, `"12 Kg"`, `"300 idlis (3 per devotee)"`,
`"~4 Kg (about 3 batches)"`. Normalised by one rule (mass token → KG, volume token → LITRES, any
other noun → PIECES), **all 5,376 resolve, with nothing left over**:

| | Count | Share |
|---|---|---|
| LITRES | 2,918 | 54.3% |
| KG | 1,619 | 30.1% |
| PIECES | 839 | 15.6% |
| unresolved | 0 | — |

The `~` prefix on 208 of them is one character to strip, not a parsing problem. The 137 distinct
count-nouns (`idlis`, `mudde`, `pakore`, `laddu`, `bobbatlu`) are all PIECES wearing a local name.
**Not one recipe in the library yields in `SERVINGS`.** Instead each carries `per` — the per-head
portion, `"200 ml"` — present on 4,673 of 5,376, which our schema has no home for. The 703 without
one are exactly the things nobody serves by the head: masalas, pickles, sustainable sweets, farsan.

**The merge.** Widen the vocabulary; convert nothing.

```sql
ALTER TABLE recipes DROP CONSTRAINT recipes_yield_unit_valid;
ALTER TABLE recipes ADD CONSTRAINT recipes_yield_unit_valid
    CHECK (base_yield_unit IN ('SERVINGS', 'LITRES', 'KG', 'PIECES'));
ALTER TABLE recipes ADD COLUMN yield_note TEXT;
```

Two more constants in `YieldUnit`. Existing rows are untouched and both old values stay legal, so no
temple's recipe changes and `RecipeScalerTest` must not need editing — if it does, something is
broken. `yield_note` carries the book's verbatim string, which is what makes PIECES tolerable: "839
pieces" is meaningless, "300 idlis (3 per devotee)" is a recipe.

**The three touch points, traced rather than assumed:**

| Consumer | Uses the unit for | Change needed |
|---|---|---|
| `RecipeScaler`, `RecipeService.scale()` | nothing — pure ratio | none |
| `InventoryConsumptionService`, `MaterialsCostService`, `JobCardService` | pass-through label | none |
| `DocumentGenerationService:322` | `"SERVINGS".equals(u) ? "servings" : "litres"` | **yes** — a Kg recipe would print "litres" |
| `app/recipes/page.tsx:169`, `[id]/page.tsx:221` | `.toLowerCase()` on whatever it is given | none |

This widens an assumption written into E2-S2 ("servings or litres, per RM 2019 both occur"), so it
needs sign-off and a `docs/CHANGELOG.md` entry. The alternative is dropping 2,458 recipes, or forcing
Kg and piece yields into `LITRES` — a lie in the database that the PDF then prints.

### The per-head portion, and a defect it uncovers

`recipes` should also gain `per_head_qty` + `per_head_unit`, nullable. This is not decoration, and it
is the more consequential half of the change.

`MealComposer.tsx:274` defaults every dish's target to the meal's head count, and line 658 labels the
box **"servings"** whatever the recipe yields in. Plan a 20-litre rasam for 300 people **today** and
it scales 15× — 300 litres, a litre a head.

That defect exists now, for LITRES recipes. It is not caused by this change. But KG and PIECES take
it from rare to 46% of the library, so shipping the widening without answering it would be knowingly
making it common. `per_head` is the answer the library hands us: 300 × 200 ml = 60 L, for any unit.
Where it is absent (the 703), the planner behaves as it does today and asks.

**Scoped as E2-S16 below rather than folded into E2-S11** — it touches the planner, and it is worth
doing whether or not the library is ever built.

### Who owns the per-head portion

Three layers, two of which already exist. No new table and no settings screen.

1. **The book sets it on import.** It is present for **5,032 of 5,376 recipes (93.6%)** — 4,563 in
   the `per` field, 359 only inside the yield string's `(3 per devotee)` parenthetical, and 110 in
   both, where **all 110 agree**, so `per` wins with no conflict to resolve. The source files confirm
   the meaning directly: the books label it `per_person` — *"ఒక్కరికి"*, for one person.
2. **The temple owns it afterwards, by editing its own copy.** An imported recipe is the temple's own
   editable row, so a hall that eats four idlis changes the field on the recipe form behind the
   ordinary `MANAGE_RECIPES` permission. Their change is invisible to every other temple, like any
   other edit. This is why no "portion settings" screen is needed — the override *is* the field.
3. **The planner may override for one meal**, which already works: typing over the number marks the
   dish *set by hand*.

For a hand-written recipe, whoever writes it sets it. Optional; blank means the planner asks.

**Rejected: a single temple-wide portion setting.** The portion is a property of the dish, not of the
temple — 200 ml of rasam, 3 idlis, 150 gm of rice and 20 gm of pickle are not one number, and any
setting that tried to be would be wrong for everything but the dish it was written for.

**The 344 recipes with no portion (6.4%)** are almost all honest absences — 114 masalas, 108 pickles,
73 sustainable sweets, 27 farsan. Nobody serves a per-head portion of lime pickle. Their box cannot
pre-fill, which is what E2-S17 exists to catch.

### Every preparation must carry a quantity (Rajeev, 2026-08-21)

A masala or a pickle has no per-head portion, so its box arrives blank. If it is saved blank the
kitchen is handed a plan with a hole in it and adjusts on the fly, which is the thing meal planning
exists to prevent.

The API rule already exists — `CreateMealPlanRequest` and `UpdateMealPlanRequest` both declare
`@NotNull @Positive BigDecimal targetServings`, so null, zero and negatives are refused. What
defeats it is on the client:

```js
// MealComposer.tsx:278, fed by Number(e.target.value)
{ ...d, servings: Math.max(1, servings), overridden: true }   // Number("") -> 0 -> 1
```

**A cleared box silently becomes 1.** Today no box is ever cleared, because every one is pre-filled
with the head count; under the new model a blank box is the normal arrival state for those 344
recipes. The result would be a plan reading *1 Kg of Idli Milagai Podi* for a festival — and no
server validation can catch it, because 1 is a legal quantity. The client must stop inventing it.

**Only the number is validated. The unit is never entered** — it comes from the recipe, so the box
shows a fixed `Kg` suffix and nobody can plan ten litres of a dry podi.

---

## 5. What the Recipes page becomes

As close to the current page as it can be. Three things go, one thing arrives.

**Gone:** the *Show archived recipes* label and checkbox (§2.4); the category chip row and the `Chip`
component; the `category` and `archived` URL parameters.

**Stays:** the header, *Glossary* and *New recipe*; the search box in its current position; the
two-column result grid; the existing badges; `?q=` in the address bar, still `replace`d per keystroke.

**Arrives:** one result list, drawn from both sources, ordered by rank.

```
┌────────────────────────────────────────────────────────────┐
│  Recipes                          [ Glossary ] [ New recipe ]│
├────────────────────────────────────────────────────────────┤
│  [ maj                                                    ] │
├────────────────────────────────────────────────────────────┤
│  Majjige                                                    │   ← already the temple's: no +
│  Beverages · base 20 litres                                 │
├────────────────────────────────────────────────────────────┤
│  Majjige Huli                            Karnataka      [+] │   ← library
│  Kadhi & Raita · Everyday                                   │
├────────────────────────────────────────────────────────────┤
│  Majjiga Pulusu                          Andhra Pradesh [+] │
│  Kadhi & Raita · Everyday                                   │
└────────────────────────────────────────────────────────────┘
```

No explanatory text anywhere. Nothing under the box saying what it searches.

- **A row the temple already has** shows no `+`. Matched on `master_recipe_id` first, and on
  `lower(name)` as a fallback — a temple that typed *Majjige* in by hand before the library existed
  must not be offered a + that would then collide with the `recipes_active_name_per_tenant` unique
  index and fail in their face.
- **The `+` is a sibling of the row link, not nested inside it** — 44 px, its own hit target, its own
  accessible name (`Add Majjige to your recipes`). It adds in place: the row loses its `+` where it
  stands, no navigation, no toast.
- **Clicking the row opens full screen**, from either source, at `/recipes/{id}` for the temple's own
  and `/recipes/library/{id}` for the library's.
- **The state name is the disambiguator** (§1) — it is the only thing distinguishing 17 rows called
  *Sabudana Khichdi*, and it sits at the right of the row where the category currently sits.
- **Empty search** shows the temple's own recipes, exactly as today. The library appears when
  somebody types. A temple with four recipes is not shown 5,376 on arrival.

**Full screen, library recipe:** every field the book holds — subtitle, badge, state and region,
yield, per head, indicative cost, ingredients, method, why, catering note, the start/vessel/season
notes, tags, serve with. Header carries **Add** (primary) and **Edit** (`aria-disabled`, with the
tooltip from §2.5: *"Add this recipe to your temple before editing it."*). Pressing Add navigates to
the new copy.

**Full screen, the temple's own:** unchanged from today — Edit live, plus scale, translate, PDF,
archive, delete. Everything already built keeps working, because the copy is an ordinary recipe.

**New/Edit recipe form** gains the fields the library holds, so a hand-written recipe and an imported
one are the same kind of thing: subtitle, badge, yield note, per head, indicative cost, why, catering
note, region, sub-region, start/vessel/season notes, tags, serve with. Grouped, not appended in a
column — *Ingredients* and *Method* stay where a cook expects them, and the descriptive fields sit
below in their own section. Every one optional; the form must not become a wall for someone entering
a two-line chutney.

---

## 6. The stories

Sized so each is a working slice. In order; each depends on the one before.

### E2-S9 — The recipe library table and its loader
Platform-scoped `master_recipes` with the V66 RLS pattern, `MANAGE_RECIPE_LIBRARY` in
`RolePermissions`, and the loader that reads the 32 JSON files and upserts 5,376 rows. Idempotent —
running it twice leaves 5,376 rows, not 10,752. Runs as a Cloud Run job off the same image.

- [ ] 5,376 rows land; per-state counts match the source files exactly.
- [ ] The Q3 ladder runs as two passes and yields **5,376 distinct `display_name` values**: 3,504 bare,
      1,870 with the state, 2 with state and category. Asserted over the real books, not a fixture.
- [ ] The first *Sabudana Khichdi* encountered is suffixed like the other sixteen — the test shuffles
      file order to prove the result does not depend on it.
- [ ] Both *Alugadde Palya* rows survive with distinct names, and the Ekadashi one keeps its category.
- [ ] Re-running changes no row count and updates changed rows in place.
- [ ] Every `qty` parses to a value and one of the five units; the loader fails loudly on any that does not, naming the file and the recipe.
- [ ] A `KITCHEN_STAFF` and a `TEMPLE_ADMIN` can SELECT; both are refused INSERT, UPDATE and DELETE **by the database**, tested as the unprivileged role.
- [ ] Deleting a tenant leaves the library intact.

### E2-S10 — Searching the library
`GET /api/v1/recipes/search?q=` returning both sources ranked, `origin` on each row, and `alreadyAdded`.

- [ ] "maj" returns Majjige before Majjiga Pulusu before a recipe merely containing buttermilk.
- [ ] "jain" returns the Jain category and everything tagged *Jain-safe*.
- [ ] "paneer" returns recipes containing paneer.
- [ ] A recipe the temple already holds is returned once, marked as theirs.
- [ ] Archived recipes are returned for a non-empty query, badged, and absent from the empty-query list.
- [ ] p95 under 300 ms at 5,376 library rows plus 500 tenant rows.
- [ ] Two temples searching the same term see the same library rows and only their own recipes.

### E2-S11 — Widening the yield vocabulary
The V-migration for `KG` and `PIECES` plus `yield_note` and the per-head columns, the two new
`YieldUnit` constants, the `DocumentGenerationService` fix, the form and detail changes, and the
CHANGELOG entry. **Blocked on Q1.**

- [ ] An existing SERVINGS/LITRES recipe is untouched, and `RecipeScalerTest` needs no edit.
- [ ] A Kg-yield recipe saves, scales, and prints **"Yields 12 Kg"** — not "litres".
- [ ] A PIECES recipe shows its `yield_note` ("300 idlis (3 per devotee)") on detail and on the card.
- [ ] All 5,376 library yields map to a unit and a positive quantity, proven over the real files.

### E2-S12 — Adding a library recipe to a temple
The seven-step import of §4, in one transaction, audited. Adds `ingredients.library_derived`.

- [ ] Categories and ingredients are created as needed; a second import of a different recipe reuses them rather than duplicating.
- [ ] Every created ingredient gets a unit and a non-null category; the keyword map is asserted to
      cover at least 95% of the library's ingredient lines, with the rest landing on `Other`.
- [ ] A recipe needing a prohibited ingredient is refused, naming the ingredient, with nothing written.
- [ ] *Onion-free chaat masala* imports cleanly.
- [ ] The copy is a full independent row set; editing it changes nothing in `master_recipes` and nothing in another temple.
- [ ] A name already held is refused before the unique index sees it, with a message that says so.
- [ ] Importing the same master recipe twice is refused the second time.
- [ ] Translations are seeded and the existing Translate button returns the book's Kannada, not Bhashini's.

### E2-S13 — The Recipes page rebuilt
The single box, the merged list, the `+`, the removals of §5.

- [ ] No archived checkbox, no category chips, no explanatory text.
- [ ] Typing filters as you type; the debounce does not drop the last keystroke.
- [ ] The `+` is 44 px, separately focusable, and reachable by keyboard.
- [ ] `?q=` still survives a reload and a paste into a colleague's chat.

### E2-S14 — Full screen, both kinds
The library detail route, the `Tooltip` primitive, `aria-disabled` Edit, and the extra fields on the
temple's own detail page.

- [ ] Every library field renders; absent ones leave no empty heading behind.
- [ ] The tooltip appears on hover, on keyboard focus, and on first tap.
- [ ] Add from full screen lands on the new copy.
- [ ] The temple's own detail page keeps scale, translate, PDF, archive, delete.

### E2-S15 — The super-admin's library
A `/library` destination for `SUPER_ADMIN` only (Q7), in the first nav group beside Temples,
Operations and Notices: browse, search, create, edit, delete. Every write
audited to `platform_audit_events`.

- [ ] The destination is absent from every other role's menu and refused by the API to every other role.
- [ ] Deleting a library recipe leaves temples' existing copies untouched.
- [ ] Editing one does not change any copy already taken — a temple's copy is theirs.

### E2-S16 — A head count is not a yield
Derive each dish's target from the head count **and the recipe's per-head portion**, in the recipe's
own unit, instead of assuming servings. Independent of the library.

- [ ] A 20 L rasam planned for 300 people targets 60 L, not 300 L.
- [ ] 100 adults with Karnataka Rave Idli (3 a head) targets 300 idlis, ratio 1.0, ingredients as written.
- [ ] A SERVINGS recipe behaves exactly as it does today.
- [ ] The box is labelled in the recipe's own unit, not "servings"; step 2's readout reads "100 people".
- [ ] A recipe with no per-head portion leaves the box blank rather than guessing.

### E2-S17 — No preparation leaves without a quantity
The validation above, in both editors — the composer and the single-dish editor on the day view.

- [ ] Clearing a quantity leaves it **empty**, never 1 — the `Math.max(1, …)` coercion is gone.
- [ ] Saving is blocked while any picked preparation has no quantity, and the hint **names the dish**:
      "Say how much Idli Milagai Podi to make".
- [ ] The offending row is marked, so eight preparations do not need hunting through.
- [ ] The same rule holds in `MealServices` when one dish of a saved meal is edited.
- [ ] No `KMS-nnnn` code: this never leaves the browser, so it joins the four existing `blockedHint`
      cases rather than the error catalogue (DESIGN_SYSTEM §7).

### UAT
One UAT story per Commandment 6, covering the demonstrable capability end to end: search finds a
library recipe, + adds it, the copy is editable, a second temple sees none of it.

---

## 7. Error codes

Next free is 4968 (`ErrorCode.java` ends at 4967).

| Code | When |
|---|---|
| `KMS-4968` | The recipe is already in your list. |
| `KMS-4969` | A recipe of that name already exists here — rename yours, or edit the one you have. |
| `KMS-4970` | This recipe needs an ingredient your temple does not allow: **{name}**. |
| `KMS-4971` | That library recipe no longer exists. |
| `KMS-4972` | Only a platform operator can change the recipe library. |

Plain language, a next step, never reused.

---

## 8. What this does not change

Worth listing, because "no change" is a claim that should be checked rather than assumed:
scaling, meal planning, sufficiency checks, order generation, PDF, the glossary, inventory,
Ekadashi flagging. All of them read `recipes` and `recipe_ingredients`, and an imported recipe is an
ordinary row in both. The only schema change they can see is the widened yield vocabulary (E2-S11),
and I traced each of them against it before proposing it.

---

## 9. Rough shape of the work

| Story | Backend | Frontend | Notes |
|---|---|---|---|
| E2-S9 | Large | — | Table, RLS, loader, 32 files |
| E2-S10 | Medium | — | One endpoint, one tsvector |
| E2-S11 | Small | Small | Blocked on Q1 |
| E2-S12 | Large | Small | The seven steps; most of the risk |
| E2-S13 | — | Medium | |
| E2-S14 | Small | Medium | Tooltip primitive |
| E2-S15 | Medium | Medium | |
| E2-S16 | Small | Medium | Independent of the library; worth doing anyway |
| E2-S17 | — | Small | Rides with E2-S16; removes a live silent-1 defect |

---

## 10. The seven open questions

**Q1 — Widen the yield vocabulary to Kg and pieces? ANSWERED 2026-08-21: yes, with the per-head
portion, in full.** `base_yield_unit` becomes `SERVINGS | LITRES | KG | PIECES`, recipes gain a
per-head portion, and the planner multiplies rather than copies (§4, E2-S11, E2-S16). Still needs a
`docs/CHANGELOG.md` entry when E2-S11 is built, because it widens an assumption written into E2-S2.

**Q2 — Do we have the right to use this data? ANSWERED 2026-08-21: yes.** Rajeev: *"This is also
our code written by a teammate. All good to use."* The absent LICENSE file is a housekeeping matter
in that repo, not a rights question here. Closed.

**Q2a — vendor the data or fetch it? ANSWERED 2026-08-21: vendor, and DONE.** The 32 books now live
at `backend/src/main/resources/recipe-library/`, byte-for-byte from
`kranthimj23/ikms@41cf173`, with a provenance note beside them. A loader that reaches across to
GitHub during a deployment is a deployment that can fail on somebody else's branch name or outage.

*Vendored verbatim rather than pre-stripped*, which departs slightly from what was proposed: the
translations are stripped **by the loader on the way into the database**, not in the checked-in copy.
A transformed copy cannot be diffed against upstream, so the next time the books change there would
be no way to see what changed. It costs 23 MB against 9 MB, once. Reversible if that trade is not
wanted.

`tools/check-ignored-sources.sh` now covers `.json` as well — it inspected only compiled-language
extensions, so one book swallowed by an ignore rule would have loaded 5,208 recipes instead of 5,376,
quietly, and only on a fresh checkout. Nothing in `.gitignore` matches these paths today; checked
with `git check-ignore`, and the guard passes.

**Q3 — Two rows called "Sabudana Khichdi", from Bihar and from Uttar Pradesh. ANSWERED 2026-08-21:
disambiguate at ingestion, with a three-level ladder.** Rajeev's shape — suffix with the state, but
surgically, only where a name actually repeats — plus the fix for the hole he spotted in it.

*Why this is not a matter of taste.* The three are different dishes wearing one name: Bihar 5 Kg sago
and no sugar, Maharashtra 7 Kg soaked overnight **with 300 gm sugar**, Uttar Pradesh potato-heavy and
yielding 20 **Kg** rather than 22 L. Blanket-suffixing every import was rejected: 3,504 of the 5,376
names are unique library-wide, so 65% would carry a suffix clarifying nothing and every meal-planner
dropdown would grow for no reason.

**The hole, and why counting first closes it.** "Suffix it if you find a duplicate" is a *streaming*
decision — it asks "have I seen this before?", and the first Sabudana Khichdi has not. Two passes
removes the question: **pass 1 counts every name across all 32 books and decides nothing; pass 2
suffixes every recipe whose name is held by more than one, the first included.** Nothing depends on
arrival order.

**The second hole, which the data found and the rule above does not close.** Adding the state fixes
1,870 of 1,872. Two survive:

```
Alugadde Palya (Karnataka)   —  Karnataka / Ekadashi
Alugadde Palya (Karnataka)   —  Karnataka / Sabji's, Dry
```

Same name, same state, two different dishes — and a known trap. The earlier Karnataka experiment in
`~/Workspace/recipes-experiment/` left the note: *"'Alugadde Palya' appears twice — once under
Ekadashi with rock salt and no mustard, once under Sabji's, Dry with a full tempering... Keying by
name would have silently given a cook the wrong method on a fast day."*

**The ladder, each rung used only when the one above is not enough:**

| Rung | Recipes | Result |
|---|---|---|
| 1 — name alone | 3,504 | `Majjige` |
| 2 — + state | 1,870 | `Sabudana Khichdi (Maharashtra)` |
| 3 — + category | 2 | `Alugadde Palya (Karnataka, Ekadashi)` |

Validated end to end against the vendored books: **5,376 recipes, 5,376 distinct names, zero
collisions.** It terminates because state and category together are unique per recipe within a book.

**What the ladder cannot close: the temple collision.** Ingestion dedupes the library against
*itself* and knows nothing about temples. A temple that hand-wrote its own *Majjige* last year still
meets its own unique index when it presses `+`, because *Majjige* is unique library-wide and ingests
bare. That case keeps the import-time prompt — but the ladder shrinks it from 1,872 recipes to only
those a temple happens to have already named:

> **You already have a recipe called Majjige.**
> Name this one: `[ Majjige (Karnataka) ]`  **Add**

Offered and pre-filled, never silently applied, and never a dead end — the same shape as `KMS-4967`,
which refuses a delete and offers Archive rather than leaving the person at a wall.

**Q4 — Auto-creating ingredients. ANSWERED 2026-08-21: create silently.** A review step before every
import is the friction that stops a feature being used, and refusing until the temple types the
ingredients itself is worse. The rows are marked library-derived (`ingredients.library_derived`) so
the ingredients page can later offer to hide the ones a temple has never used — that marking is the
cheap half of the recommendation and can be dropped without changing the decision.

**The three fields a created row needs, and where each comes from:**

- **`name`** — the book's, matched against the tenant's catalogue on `lower(name)` first, so an
  existing *Rice* is reused rather than duplicated.
- **`canonical_unit`** — from the parsed qty token, which resolves for **all 46,337 lines**:
  `gm|Kg → KG/GM`, `L|ml → L/ML`, `nos|Nos|pcs|pieces → PIECES`.
- **`category`** — `NOT NULL`, and the books do not carry one. A curated keyword map supplies it.
  Measured against the real data, a ~40-rule map covers **82% of the 2,238 distinct names and 95.3%
  of all ingredient lines**, bucketing into the vocabulary the seeds already use (Spices 20,913
  lines, Vegetables 7,595, Dairy 3,108, Nuts & seeds 2,427, Grains 2,092, Pulses 1,998, Sweeteners
  1,631, Oils & fats 1,433, Fruit 1,301). The remainder is a regional tail — *timur*, *jakhya*,
  *perilla seeds*, *jambu*, *pancha phutana* — and falls to **`Other`**, which `seedProhibitedIngredients`
  already uses, so it is not a new word in the vocabulary. The map lives in code and is reviewable
  as a document, like `RolePermissions`.

**Sattvic enforcement runs on the resolved rows, never on substrings.** *Onion-free chaat masala* and
*Garlic-free panch phoron* are the two names in the whole library that would fail a substring test,
and both are sattvic. A recipe needing a genuinely prohibited ingredient is refused with `KMS-4970`
naming it, and nothing is written — no half-created ingredients left behind, because the whole import
is one transaction.

**Q5 — Keep the 16 languages? ANSWERED 2026-08-21: no. Rajeev's argument, and he is right.**

> *"Take ISKCON South Bangalore. They are in Karnataka where Kannada is the local language. HOWEVER,
> there is no guarantee that the kitchen staff / cooks are Kannada speaking. Many are not. There are
> Bengali cooks and Bihari cooks. So the recipe has to be converted to a language that this person
> understands."*

The flaw in §2.2 is that **a book's language follows the state the recipe came from, never the person
reading it.** The bundled translation only lands when the cook's language happens to match the source
state's — and since the entire point of this feature is *cross-state* cooking, Bangalore importing a
Bihari litti or an Assamese xaak, the bundled text is in the wrong language by construction in the
common case. The one case where it does line up, a Kannada cook reading the Karnataka book, is the
case where the library adds least, because that book was already theirs. §2.2 generalised from it.

**The `localised` JSONB column is dropped from `master_recipes` (§3). Nothing translated is stored
per recipe.** The Bengali cook is served by E2-S6 translating on demand into *his* language, which is
what that story was built for.

**Q6 — Method prose in the search index? ANSWERED 2026-08-21: leave it out.** The weighted document
of §2.3 — name and subtitle at weight A, ingredient names and tags at B, category, state and badge at
C. Method and "why" prose are not indexed.

**Q7 — Where does the super-admin's library live? ANSWERED 2026-08-21: its own destination.**
`/library`, in the first (untitled) nav group beside Temples, Operations and Notices —
`lib/nav.ts`, `roles: [OPERATOR]`, and `RequireRole roles={["SUPER_ADMIN"]}` on the page, the two
kept in step as that file's own comment requires. Operations is a health dashboard; a 5,376-row
catalogue is not health, and burying it as a tab there would make both harder to read.

---

## 11. What I would do first

Before any of the stories, one afternoon of proving:

1. ~~**Q2.** Ask the author.~~ Answered 2026-08-21: the books are ours. Closed.
2. **Load 168 recipes for one state into a local database**, end to end, and import three of them
   into a test tenant. That exercises the qty parse, the ingredient resolution, the sattvic check and
   the yield widening against real rows rather than against my reading of them. If something in §4 is
   wrong, it is wrong there, and it is cheap to find out on day one.

---

## 12. What the building changed

Four things the design did not know, found by writing it rather than by thinking about it.

**A recipe measured in servings already states its portion: one serving.** §4 said the planner asks
where there is no per-head portion. That would have been a regression for every recipe a temple has
already written, since they are all SERVINGS and all worked before. So `targetFor` treats a servings
recipe with no stated portion as one serving a head, which reproduces the old behaviour exactly for
them and changes it only for the units that made it wrong. Nothing a temple has today moves.

**The loader needed a third writer, and the flag has to be set before the transaction.** The library
belongs to no tenant and no user, so neither the tenant context nor the auth escape could carry the
loader's writes. `app.library_load` is the answer, following V66's automation case, and every policy
branch honouring it also requires `app.auth_uid` to be absent — a signed-in caller cannot use it even
if it leaked onto their thread. The bug worth recording: `@Transactional` takes its connection before
the method body runs, so a flag set inside the method reaches nothing, and the load was refused by
its own policy. `LibraryLoader.load()` is deliberately not transactional for that reason.

**`array_to_string` is STABLE, not IMMUTABLE**, so PostgreSQL refuses it inside a generated column.
The tsvector needs the ingredient names and the tags, and pushing the join into the loader would have
lost the weighting. `kms_join_text_array` declares the promise once — true for `text[]`, which is the
only type it takes.

**Two error codes were one too many.** The design proposed `KMS-4969` for a name already taken.
`KMS-4905` has meant exactly that since E2-S2, and its next step — *"Choose a different name, or edit
the existing recipe"* — is the right one for an import too. Two codes for one failure is worse than a
code that has to serve two callers, so 4969 was never shipped.

**And one number the design had wrong.** §4 says 5,032 recipes carry a per-head portion. 5,032 books
*state* one; 5,031 are kept. Delhi's Papdi is made by the kilo and served by the piece, and no
arithmetic takes a head count from one to the other, so its portion is stored as text and withheld
from the arithmetic. `BookParserTest.mismatchedFamily` is that one recipe.
