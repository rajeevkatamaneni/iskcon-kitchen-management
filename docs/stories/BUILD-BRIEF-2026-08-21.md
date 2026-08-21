# Build brief — 2026-08-21 (UAT round 2)

**Status: CLOSED 2026-08-21. Ready to build on Rajeev's go-ahead.**

26 items. Twenty from his first real drive of the built app, six found or added since. Thirteen
questions, all answered, recorded under **Decisions**.

Everything here was checked against the running app at
`https://kms-staging-web-bnpkv5hfrq-el.a.run.app`, signed in as `ikms.temple-admin.1`, and against
`main` `2743fca`. Not from memory.

---

## A standing rule over everything below

**Decided once, applied everywhere.** Not per screen. Not per group. Not per page.

A volunteer learns this app once, at the counter, between two meals. Every place the site behaves
differently is a second thing to learn. Consistency buys muscle memory. It is not tidiness.

A pattern settled in group 1 or 2 goes to all fifty pages in that pass. Not only to the screens that
group touches. Each rule is written into `DESIGN_SYSTEM.md`, so the next screen inherits it. Each
gets a CI check, so it stays.

### Case

`Pick the time this must be ready` and `a full portion` sit under two fields in the same form.
Rajeev is right that they do not match. The survey found worse. Hints are written three ways.

| Written as | Found |
|---|---|
| lowercase fragment | `a full portion`, `ready by`, `not yet recorded`, `struck out` |
| sentence case | `Pick the time this must be ready`, `Where the receipt goes` |
| sentence case, full stop | `At least eight characters.`, `Needs both an email and a phone number.` |

**Cause: `DESIGN_SYSTEM.md` has no capitalisation rule.** It settles colour, type, spacing, icons and
error messages. Not case. So fifty pages each chose. Buttons came out consistent by habit
(`Save changes`, `Record delivery`). Labels and hints did not.

**The rule** (settled in Q12):

- Sentence case everywhere. Buttons, labels, hints, headings, table headers, nav, empty states.
- Capital on the first word and on names only.
- Full stop only on a complete sentence. Under a field, almost never.
- No ALL CAPS in content. The sidebar eyebrows stay. They are CSS, not markup.
- One word per thing, site-wide. Staff, never employees. Preparation, never dish or item.

### Grammar and punctuation

Item 13 audits both. Real defects on live:

| Found | Wrong because |
|---|---|
| `Marking somebody off is not an option that writes here.` | Unreadable. |
| `Only urgent is loud, and the discipline is the design.` | Says nothing to a user. |
| `Concrete needs devotees can fund; fulfilled items retire automatically.` | Semicolon. Two sentences in a hint. |
| `Post seva shifts; volunteers see them the moment they're created.` | Same. |
| `Add your first ingredient above.` vs `No donations in this period` | Same class of text. One has a full stop. |
| `they&rsquo;re` vs `haven&apos;t` | Two apostrophe encodings. Use one character. |
| `All vendor invoices are paid. 🙏` | The only emoji in the app. |

**Rules for the pass:** one clause per line of copy. Twelve words maximum. No semicolons. No em dash
where a full stop works. Cut any sentence that explains the screen it is on.

---

## The list, regrouped

His numbers are kept so nothing is lost. The groups are the order it gets built in.

| Group | Items | What it is |
|---|---|---|
| **1. The kit** | 10, 11, 12, 18, 8, 23 | Shared form, table and colour primitives. Everything else sits on these. |
| **2. The focus screen** | 5, 6, 7 | One pattern for a screen that does one task, and the conversion of every screen that should be one. |
| **3. Staff and former staff** | 1, 2, 3, 4, 9 | The register, the former-staff table, the record view, bans, struck payments. |
| **4. Leave and the workforce count** | 19 | A half-day person stops counting. The count gains a meal grain. |
| **5. The planner** | 14, 15, 16, 24, 25, 26 | A meal is the unit of planning in all three views, editable as one, saying how many people it takes. All three views can move. A festival feast becomes a kind of meal. |
| **6. The job card** | 17 | Rebuild of the sheet. |
| **7. Words** | 13 | Site-wide copy audit: meaning, case, grammar, punctuation. |
| **8. Already built** | 20 | The ten-year ban fade. Nothing to do but fix a stale comment. |
| **9. Two defects** | 21, 22 | The idle sign-out never fires. Back leaves the screen. |

---

## Group 1 — the kit (10, 11, 12, 18, 8, 23)

Small, shared, first. Groups 2–7 all write forms and tables. Without this they each invent their own.

**10 · Text above and below a box lines up with the text in it.** An input sets its inner text 13px
from the box edge — `px-3` plus the 1px border every variant carries. The label, the hint and the
error sit at 0. All three move to 13px. Applies to `components/Field.tsx` and to every ad-hoc
`<label>` in the app: roughly 250 across 38 pages. Converted to `Field` where the shape allows,
given the same inset where it does not. Confirmed in Q8.

**11 · Labels get weight.** `text-sm font-medium text-ink` becomes the one label style. Ad-hoc labels
are `text-sm text-ink-secondary` today, a step lighter and a step greyer. Hints stay
`text-ink-secondary`. Errors stay `text-danger`.

**12 · Table rows respond to the pointer.** One `hover:bg-raised/60` on every `<tbody> <tr>` in the 24
files that carry a table, read-only and editable alike. One step of tone. No border. No cursor change
on a row that is not clickable.

**18 · The primary button label reaches AA.** `accent #BE6444` on `ink-inverse #FCF8F5` measures
**3.90:1**. The floor for button text at this size is 4.5. `accent.DEFAULT` moves to **`#AE5838`**
(4.68:1), `accent.hover` to **`#94482D`**. Measured, not guessed. `DESIGN_SYSTEM.md` is locked, so
this needs sign-off and a `CHANGELOG.md` entry.

**8 · A success banner clears itself.** Five seconds, then it fades. **Success only.** Errors and
warnings never auto-dismiss, because the person still has something to do about them. `InlineNotice`
gains an `autoDismiss` prop. The leave queue, the staff register and every other green banner use it.
The component's docstring — *"a message that disappears on its own is a message somebody misses"* —
is right about errors and wrong about confirmations. It gets corrected, not deleted.

### 23 · The Scales-to pill is misaligned, and has been fixed twice already

Measured on `Add a meal`, step 2:

```
                 outer top   pill top   pill bottom   pill height
Adults               421        445          497           52
Children             421        445          497           52
Seniors              421        445          497           52
Scales to            421        421          481           60      <- 24px high, 16px shallow
```

**Cause: the four things in that row are not the same shape.** A `Counter` is three stacked parts —
label, sunken box, hint. `Scales to` is one part: the sunken box *is* the outer element, and its
label lives inside it. `align-items` can only line up the outer edges. The outer edges are not what
anybody is looking at. The boxes are.

So both settings are wrong, and both have shipped as the fix:

- **`items-end`**, the original. Lines up the bottoms. That puts the counters' hint text level with
  the readout's box, and pushes any field without a hint down a whole line.
- **`items-start`**, applied twice — `cd5f46f` for step 2, `e7e4b67` for step 1, each with a comment
  explaining why it was right. Lines up the tops. That puts the readout's box 24px above its
  neighbours'. This is the state on live now.

A third patch on the same wound is already in the file. Step 1's "Who is it for?" field carries
`<span class="text-xs">&nbsp;</span>` — an empty hint, typed by hand, to reserve the row. It works
until somebody adds a field and does not know to do it.

**The fix: the row becomes three shared tracks.** A new `FieldRow` in this kit. The parent declares
three row tracks — label, control, hint. Every child is `grid grid-rows-subgrid row-span-3`, so all
four take their rows from the parent instead of their own content. The label track is as tall as the
tallest label. The control track is one track. The hint track is one track. A child with no hint
leaves an empty cell rather than shortening itself. Tailwind 3.4.13 ships `grid-rows-subgrid`.

**`FieldRow` wraps its children itself.** It does not ask them to carry the classes. That is what
makes it stay fixed: a caller cannot opt a field out of the tracks. The `&nbsp;` spacers get deleted.
`Scales to` loses its inside label and takes the same shape as its neighbours, so it is a peer rather
than a special case.

**Where it applies.** Both rows in `MealComposer`, then every other label/control/hint row in the
app, as the `Field` conversion goes through. Items 10 and 11 are about the text inside a field. Item
23 is about the row those fields sit in. Same pass.

**The test.** jsdom has no layout, so a pixel assertion proves nothing. Two that work:

1. Structural. Render both steps. Assert every direct child of a `FieldRow` carries the subgrid span.
   Fails today, because the readout is not in a row primitive at all.
2. Grep-style. No `&nbsp;` spacer, and no `items-end` or `items-start` on a field row anywhere in
   `app/` or `components/`. Cheap, and it catches the regression that has now happened twice.

Verified afterwards the way it was diagnosed. Reopen the composer on live and read the four
rectangles back.

Mock: https://claude.ai/code/artifact/c0ec96db-2d03-4c23-9997-4533a512c887

---

## Group 2 — the focus screen (5, 6, 7)

Settled in Q1. Mock: https://claude.ai/code/artifact/842e437f-f60f-4a09-9f84-21e0ef8cfa7f

**The pattern.** One screen, one task.

1. Its own URL. Linkable, reloadable, and back does the obvious thing.
2. The sidebar stays. Trapping someone on a form is worse than the distraction.
3. The task is the `h1`. One line under it says whose record this is.
4. Actions top right, together: `[Cancel] [Primary]`. Secondary first. This is item 7 verbatim.
5. **The header is sticky.** The one thing added to what he asked for. Approved.
6. No second copy of the buttons at the foot. One place to commit.
7. No `← Back to` link anywhere. Item 5's arrow disappears rather than being restyled.
8. Committing returns to the list, with the confirmation from item 8 waiting there.

**`Cancel`, not `Close`.** His word. It is already the one every form in the app uses, and it says
what happens to what you typed. `Close` survives only on a read-only record such as `/staff/[id]`,
where there is nothing to cancel. Two words, because they are two different acts.

**Why the header is sticky.** Measured on live, terminating Madhava Das with a ban ticked:

| | |
|---|---|
| Heading top | 180px |
| Terminate button bottom | 1232px |
| Window height | 836px |
| Scroll needed to reach the button | 396px |
| Where the name is by then | 216px above the fold |

There is no scroll position where the person's name and the button that ends their employment are
both on screen.

**Screens converted.** Every place a button opens a form on top of a list:

| Screen | Today | Becomes |
|---|---|---|
| Hire someone | inline panel over the register | `/staff/new` |
| Update staff | inline panel over the register | `/staff/[id]/edit` |
| Terminate | inline panel over the register | `/staff/[id]/terminate` |
| Pay | own page, back-link top left | keeps the page, gains the header pair |
| Record leave | inline panel, tabs still showing | `/leave/record` |
| Edit a meal | inline per-dish rows | `/planner/[date]/[kind]` (group 5) |
| Add a vendor · invoice · inventory item · notice · broadcast · donation in kind · shift | inline `showAdd` panels | own routes |

**Where it stops: four.** A form of four fields or more becomes a screen. Three or fewer stays
inline. The counted survey is under Q1. Only the glossary term stays inline today.

---

## Group 3 — staff and former staff (1, 2, 3, 4, 9)

**1 · Row actions read `Pay · Update · Terminate`.** Terminate stays last and stays the danger
variant.

**2 · "Records we have raised about former staff" comes off the top of the register.** In its place, a
banned former employee is drawn differently in the Former staff table: the name in `text-danger` with
a small `Banned` pill. A normal termination and a termination with a ban are then one glance apart.
The register's backend view gains a `banned` flag per former row. It sits behind `MANAGE_STAFF`,
which both the register and the ban list already require, so no new permission.

**3 · The Former staff table stops being a different table.** `Left` shows the date and nothing else.
The reason moves onto the record. The actions column carries `View` and `Pay`, in the same buttons
the current-staff rows use. The wrapped Pay button under the reason text goes.

**Current staff do not get `View`** (Q6). `Update` already opens the whole record in an editable
form. A fourth button would be a second door to the same room.

**4 · A former employee's record shows the ban.** New read-only screen `/staff/[id]`. Only
`/staff/[id]/pay` exists today, so this is genuinely new. It carries the whole record: job,
employment type, dates, contact, access, salary, PAN behind a reveal, how the employment ended and
why, the settlement paid. And if one was raised, the ban: its category, the words written, when it
was raised, when it fades, and the buttons to amend or retract it.

**`/staff/bans` is kept, demoted and made read-only** (Q5). Off the top of the register. Reached from
Former staff as a quiet line, `Records we have raised · 2`. Each row links to the person. Amend and
retract live only on `/staff/[id]`, so nothing can be changed in two places.

**9 · A struck payment is struck.** The `struck out` label goes. The row is drawn `line-through` and
`text-ink-muted`, with an `sr-only` "struck out" so a screen reader is still told. Same for struck
advances.

---

## Group 4 — leave and the workforce count (19)

Settled by Q2. Much smaller than first proposed. Three changes:

1. **A half-day person stops counting.** `ScheduleResolver` keeps them in today and says so in a
   comment. Both the comment and the behaviour go.
2. **They stay visible.** The week grid and any roster list still show the name, still marked half
   day. Only the count changes. `WorkforceService` changes. The grid does not.
3. **The count gains a meal grain.** A person counts towards a meal if their working window covers
   that meal's `ready_by`.

**Not being built**, cut by Rajeev as too much machinery for the size of the feature: a time or a
side on half-day leave, a kitchen window per meal kind, and any notion of a shift object.

---

## Group 5 — the planner (14, 15, 16, 24, 25, 26)

**14 · The Today hover gets room.** The meal rows are `py-3` with no horizontal padding, so the hover
tone hugs the text. They become `-mx-3 px-3 rounded`. The highlight gains 12px each side and a
radius. The text does not move.

**15 · A meal is what gets planned, in all three views.** What is there today:

| View | Today | Becomes |
|---|---|---|
| Day tab | one card per dish, each with its own `Open` | renders `MealServices` |
| Week tile | one line per dish: `12:00 Lunch · 133 servings`, three times | `12:00 Lunch · 3 dishes · 133 plates`, dish names beneath |
| Month cell | the same | one line per meal kind, no dish names — no room |
| Day modal | already groups correctly | unchanged |

`components/planner/MealServices.tsx` is the right answer already. The Day tab simply does not use
it.

**The data model does not change.** `meal_services` (V64) is already the meal, keyed
`(tenant, date, meal_kind)`. `meal_plans` rows are its dishes. `MealComposer` already plans a whole
meal in one act. The core is right. Two of the three views ignore it.

**16 · A meal is editable as one.** `Edit` on each meal block opens `/planner/[date]/[kind]`. That
meal and nothing else: change a dish's servings, swap a dish, remove one, add one, change the
ready-by and the head count. It is `MealComposer` in the focus-screen frame from group 2, opened on
an existing meal. `Job card` and `Record what went out` move up beside `Edit` on the meal header.
Both exist already.

### 24 · How many people it takes to cook the meal

His words: the planner should carry the number of people needed to execute the meal. At execution
time that can be any mix of staff and volunteers. It is satisfied when **staff + volunteers ≥ the
planned number**.

**What exists.** `WorkforceCount(date, staffIn, volunteers)` already counts staff and volunteers
separately and sums them. That is exactly the any-ratio rule. The Today tile, the week grid and the
planner pebbles all read it. Its grain becomes per-meal under group 4.

**What does not.** The required side. No column, no field, no API. A meal knows how many it will
*feed* and nothing about how many it takes to *make*.

**Where it is stored.** On `meal_plans`, beside `head_count`, `ready_by`, `adults`, `children` and
`seniors`. All of those are already whole-meal facts carried on each dish row. V64 defends that
shape as load-bearing. Following it means no change to the rule that a `meal_services` row is only
created when a card is printed. If `meal_services` is ever promoted to exist from planning time,
`crew_required` moves there with the other four, as one migration.

**Where the field goes: a new step 4, "Who will run it", after Preparations** (Q10). After, not
before. The crew a meal takes depends on what is being cooked as much as on how many are eating.
Three dishes for 133 and eight dishes for 133 are not the same morning's work.

| | Step 2 — who is expected | Step 4 — who will run it |
|---|---|---|
| Left | Adults · Children · Seniors | **People needed** — one counter |
| Right | `Scales to · 100 servings` | `Rostered · 3 staff · 2 volunteers · 5 of 8` |

Both right-hand slots are the readout item 23 fixes.

- The readout uses the group 4 rule: a person counts if their working window covers the meal's
  `ready_by`. Volunteers the same way, against their shift window. A shift posted 11:00–14:00 falls
  to lunch without anybody linking it.
- Short of the number, the readout takes a quiet warning tone. **It never blocks saving.** A meal is
  planned weeks before anybody is rostered.
- **One counter, not two.** The mix does not matter. Splitting it would invent a constraint the
  temple does not have.

**The default: the median of the last three** (Q11). See the decision for the full rule and the
thin cases.

**Where the number is then used:**

- **The planner meal block** — a crew pebble beside the servings: `5 of 8`.
- **Today** — `Working today · 7` becomes `Breakfast 4 of 4 · Lunch 5 of 8 · Dinner 6 of 6`. The
  short one stands out.
- **Approving leave** — the approver is told what it costs. *"Approving this leaves Lunch on 24 Aug
  at 4 of 8."* Told, not stopped.
- **The job card** — the sheet prints the planned number above the names.
- **Volunteer shifts** — a meal short of hands is the reason to post one, and the number says how
  many places to open.

### 25 · Week and Month have no way to move

Reproduced in his browser:

```
view=Day    prev ✓  today ✓  next ✓
view=Week   prev ✗  today ✗  next ✗
view=Month  prev ✗  today ✗  next ✗
```

The week grid can only ever show *this* week. The month grid only *this* month. Week view is the one
a person switches to because they are planning ahead.

**Cause: one gate.** `frontend/app/planner/page.tsx:175` wraps the whole control in
`{view === "day" && (…)}`. Week view gets `Duplicate last week` in that space. Month view gets
nothing. The state already exists and is already shared. `anchor` is one string all three views
read, and `rangeFor(view, anchor)` already widens it per view.

**The fix.** The control comes out of the gate and steps by the unit of the view it is in: a day in
Day, a week in Week, a month in Month. The middle button reads `Today` when the anchor is in the
current period, and the period's own name otherwise — `Aug 17–23`, `September`. Pressing it returns
to today. `Duplicate last week` stays in week view, beside the control rather than instead of it.

This also completes half of item 22. The anchor and the view go into the URL, so
`/planner?view=week&date=2026-09-15` is a real address.

### 26 · A festival meal is a kind of meal

His words: festival meals have lots of dishes. They are the most complicated and intense to plan and
execute. The temple takes great pride in them. Today the planner has no way to say a meal *is* the
festival feast.

**What exists.** More than it looks:

- `meal_plans.day_type` is set to `FESTIVAL` by `deriveDayType`, and `occasion_name` is filled from
  the calendar. Both derived, neither chosen.
- Named occasions exist and are tenant-extendable (E4-S2), seeded from the ICC workbook's seventeen
  named occasions plus the pan-ISKCON days.
- Festival days already pre-fill expected servings from the occasion's default.

**What does not.** `What kind of meal` offers Breakfast, Lunch, Dinner, Deity Offering, Outside
event, Catering order. There is no feast. So the biggest meal of the year is planned as a Lunch that
happens to fall on a marked day.

**Why a *kind* is right, and not a re-run of a mistake V48 fixed.** V48 separated two overlapping
ideas on purpose. A *kind* says when in the day a meal happens and what it needs. A *day type* says
what sort of day it is, derived, never chosen. Catering was moved out of day type into kinds
precisely because catering is a property of the **meal**, not the day. A temple must be able to
cater on a festival.

A feast is the same shape. The day being a festival is the calendar's business. Whether *this meal*
is the feast is the planner's. On Janmashtami the temple serves an ordinary breakfast and then a
feast. One day, two meals, one of them the big one. Only a per-meal fact can say which.

**How it is built.** `meal_kinds` already carries flags rather than hardcoded names, so the
application never has to recognise a kind by its name:

| Flag | Means |
|---|---|
| `needs_client` | someone outside asked for this food and is paying |
| `needs_venue` | the food leaves the temple |
| **`needs_occasion`** | **new.** The plan must name which festival it is for |

`needs_occasion` defaults to whatever the calendar says for that date, and is pickable. So a temple
anniversary, or a local festival the calendar does not carry, can still be planned as a feast.

A seed migration adds the kind per tenant, one temple at a time, in the shape V48 used.
`meal_kinds` is tenant-owned and carries `FORCE ROW LEVEL SECURITY`, so a plain cross-tenant insert
is refused and a plain cross-tenant update silently matches nothing. `default_ready_time` is left
null. A feast is never at the same hour twice, and V48's rule is that occasional kinds always ask.

### 26b · Menu history

Settled in Q13. The kind alone does not touch what he described. Today a twenty-dish feast means
scrolling a flat grid of every recipe the temple has and ticking twenty boxes, with nothing saying
which dishes belong together. E4-S2 promised that planning "Janmashtami" would carry menu history.
Nothing reads it.

**When a festival meal names its occasion, the composer offers what was cooked for it last time, and
puts the whole menu in with one press.**

> Last Janmashtami, 26 August 2025 — 18 dishes. **Use this menu.**

**It reads data that is already there.** `meal_plans` carries `occasion_name`, denormalized on
purpose — *"so removing an occasion never orphans the plan"* (V22) — beside `recipe_id` and the meal
kind. The lookup is the most recent festival meal with the same occasion name, and its dishes.
Matching on the text rather than an occasion id is the choice V48 made for `meal_kind`, for the same
reason: a temple may delete an occasion, and the feasts cooked under it must keep reading as what
they were.

| | |
|---|---|
| The dish list | **carries.** That is the part that takes an hour to reassemble. |
| Servings | **do not.** They follow this year's head count from step 2. |
| Last year's per-dish overrides | **do not.** An override was a judgement about last year's crowd. Re-applying it against a different head count would be wrong in a way nobody would notice. |
| A dish whose recipe was archived or deleted | **skipped, and said out loud.** *"2 of last year's 18 dishes are no longer in your recipes."* Recipes became removable in `cf629fe`. |
| Anything automatic | **no.** The menu is offered, not applied. One press puts it in. Everything stays editable. |
| The first ever Janmashtami | nothing to offer, and the control is absent. |

**Left to a build of its own:** a Preparations step that can cope with twenty dishes, a job card
running to several pages, and crew planning at feast scale.

---

## Group 6 — the job card (17)

A rebuild of `JobCardTemplate` and additions to `JobCardService`.

### Page 1 — the worksheet

1. **Header.** The ISKCON lotus emblem (`frontend/public/brand/iskcon-icon.svg`, inlined — the
   renderer has no network) beside the temple's name. On the right, in the size and weight the card
   number carries today: **`Dinner · Friday 21 August 2026`**. The card number drops to small grey
   type beneath it.
2. **Why that is a fix.** `DC-2026-0003` is the card's filing reference. `D` for Dinner, `C` for
   card, the year, a per-temple counter. It exists so a signed sheet in a folder can be traced back
   to its record six months later (V64). It is a reference, not a heading, and it is currently set
   in 14pt bold in the corner where the eye lands first. That is the bug.
3. **Facts.** Ready by, head count, scales to, occasion, venue, purpose. As now.
4. **Warnings.** The fast and any prohibited ingredient. As now.
5. **The servings table.** The heart of the change. One row per dish:

   | Dish | Planned | Cooked | Served |
   |---|---|---|---|
   | Jolada Rotti | 133 | ▢ | ▢ |
   | Bendekayi Huli | 133 | ▢ | ▢ |

   `Planned` printed. `Cooked` and `Served` ruled boxes, filled in with a pen. This is the
   expected-versus-actual data the temple is after, and it is why the sheet comes back to the office.

   **Dish names print both ways** — the Latin name with the local script beneath (Q3). A cook has to
   match a row here to a page in the appendix. The app already does this elsewhere:
   `Khichdi · खिचड़ी · కిచిడీ · கிச்சடி`.
6. **Who is on.** Staff: a count, then each name and phone. Volunteers: a count, then each name, the
   job they signed up for, and their phone. The planned crew from item 24 prints above the names.
7. **Two signature boxes.** *Kitchen manager / head cook*, signing that the cooked figures were
   checked. *Serving staff*, signing that the served figures were recorded. The current three —
   `Cooked by · Checked by · Served by` — collapse into these two.

### Pages 2+ — the recipes

Ingredients and method for every dish, one per page-break-avoiding block. After the worksheet, not
woven through it. **Optional** — the person printing chooses.

**Language is chosen per print** (Q3). The worksheet is always English, matching the app's
English-only Phase 1 UI. The recipes are the printer's choice.

- `tenants.locale` is the **default**, not the rule. It was made settable in Settings by `0f9ba23`.
- The list offers English always, since it is the source text. Plus every language
  `recipe_translations` actually holds for the dishes on this card. Offering a language with no
  translation behind it would print an English appendix under a Kannada heading.
- A dish with no translation in the chosen language prints in English, with one line saying so.
  Three recipes of four in Kannada beats none.

**The renderer can already draw these scripts.** `backend/Dockerfile` installs `fonts-noto-core` and
`fonts-indic`. The CSS stack in `JobCardTemplate` names only Devanagari and Kannada, so Telugu and
Tamil currently reach the page through Chromium's codepoint fallback rather than the declared stack.
It works. The stack is still extended to name what the image supports.

### Print

Two real bugs:

- The document is `body{margin:0;padding:0}` with `@page{margin:16mm}`. The PDF is fine. The window
  the Print button opens has no padding at all, which is the edge-to-edge he saw. A screen-only rule
  gives that window an A4-shaped padded page on a grey ground, so it previews as what it prints.
- The Print button opens the window and stops. It will call `print()` once the document has laid out.

### Typeface

**Noto stays** (Q4). No Anek embedded, no font file added to the backend, no ~80KB per PDF. Colour,
rules, the type scale, section headings and hairlines all move to the app's tokens. That is enough.

---

## Group 7 — the words (13)

A pass over all fifty pages. Meaning, case, grammar and punctuation, against **A standing rule over
everything below**. One pass, so the site reads as one hand wrote it.

- **Delete** anything that describes what is already on the screen. *"Everyone your temple
  employs."* under a page titled Staff, above a table of staff, tells nobody anything.
- **Delete** reassurance. *"You are not removing anyone. Madhava Das moves to Former staff, and
  everything they worked on here — shifts, stock entries, orders — stays on the record exactly as it
  is."* becomes **"Their record and their work stay. Only their employment ends."**
- **Keep, and cut to one line,** anything that changes what somebody does. *"Leave this unticked and
  they stay a devotee of your temple — they can still sign up for seva and give. Tick it and they
  cannot sign in at all."* becomes **"They keep their devotee account unless you tick this."**
- **Twelve words** is the ceiling on any hint that survives.
- **Empty states keep one sentence.** An empty screen with no words looks broken.

**Four kinds of text are exempt and never deleted** (Q7). They are tightened only where tightening
costs nothing: consent wording on the devotee register; the DPDP line on PAN; the warning above a
ban; and payment confirmations.

Done last, so it edits final wording rather than wording two other groups are still writing.

---

## Group 8 — already built (20)

The ten-year fade is in the code. `EmploymentBanService.BAN_LIFETIME = Period.ofYears(10)`, passed to
`match_employment_bans` as `p_raised_after`, surfaced to the admin as `fadesOn` on every ban. Nothing
to build.

Two comments call the figure "provisional, Rajeev to confirm". Those become "confirmed 2026-08-20,
revisit if the temple objects".

---

## Group 9 — two defects, both reproduced on live

### 21 · The 60-minute idle sign-out does not fire when it matters

He is right to be worried. It is a real bug.

**Reproduced.** Signed in as the temple admin, I backdated the shared idle clock by three hours —
what a laptop asleep overnight leaves behind — then dispatched a genuine mouse press on a menu item:

```
start path: /planner
backdate clock 3h:  ok
after real click -> path: /recipes | clock age(s): 2
after 10s more   -> path: /recipes | STILL SIGNED IN
```

Three hours idle. One click. The session renewed itself.

**Root cause.** `SessionGuard` listens for `pointerdown`, `keydown`, `touchstart` and `wheel`. Every
one runs `recordActivity(Date.now())` unconditionally. Separately a `setInterval` checks every five
seconds whether the clock has run out. `pointerdown` fires on the mouse going *down*, before the
interval's next tick and before the click has navigated. So the first act of returning to the device
stamps the clock fresh, and the expiry check never sees the stale value it exists to catch.

A sleeping laptop makes it certain rather than likely. The OS suspends `setInterval` while the lid is
shut, so on wake there is a window with no tick at all, and the first thing in it is the user's click.

**Net effect:** the rule only fires on a tab left visibly open and untouched with the timer running —
the one case where somebody is there to see it. It never fires in the case it exists for, a device
picked up later. E1-S16's D2 and its third acceptance criterion are not held.

**The fix:**

1. `markActive` decides before it records. If the session has expired it signs out instead of
   renewing. The click that wakes the device is judged, not obeyed.
2. The listeners move to the **capture** phase, so nothing in the app acts on the press first.
3. The clock is also evaluated on `visibilitychange`, `focus` and `pageshow`. A woken laptop is
   judged the instant its tab is shown, rather than waiting up to five seconds for a tick a click
   can beat.
4. The interval stays, as belt and braces.
5. A test that fails on today's code: a press after the limit signs out rather than renewing.

**What the server enforces, and does not.** There is no server-side idle limit at all, and E1-S16's
D7 says so on purpose. The timeout is a shared-device courtesy, not the security boundary. Spring is
`STATELESS`. The only per-request checks are the Firebase token's signature and expiry, and that
`users.status` is still active. A Firebase ID token lives an hour, but the SDK refreshes it silently
and indefinitely from a long-lived refresh token. **Token expiry is not a bound on session length.**

**Nothing else is added** (Q9). No server-side idle rule. No session table. No re-authentication in
this build.

### 22 · The browser back button leaves the screen instead of going back within it

The cause is one line of policy, not a bug in one page.

**Only two of fifty pages put anything in the URL** — `/tenants` and `/unsubscribe`. Everywhere else,
what you are looking at is React state: which view, which date, which day is open, which form is
showing. The address bar never moves. The browser has nothing to go back to *within* the screen, so
back throws you out of it.

```
Scenario A — planner
  on planner:            /planner | history=50
  after clicking Week:   /planner | history=50   <- whole screen changed, URL did not
  after picking a day:   /planner | history=50   <- again
  after BACK:            /recipes                <- thrown out of the planner entirely

Scenario B — leave
  form open:             /leave   | history=50
  after BACK:            /recipes                <- form and everything typed in it, gone
```

**A second, separate bug found while testing this.** The planner never reads its own query string.
`/planner?date=2026-09-15` renders Friday 21 August. `anchor` is `useState(todayIso())` and nothing
consults `useSearchParams`. So the Today screen's "click a meal to open that day in the planner" has
been landing on today all along. It looked correct only because it was tested on today.

**The fix, in two parts:**

1. **What you are looking at goes in the URL.** The planner's view, date and open day. The leave
   queue's tab. The donations period. The calendar's date. The recipe list's search and filters.
   `push` for a change of *what* is shown, so back returns to the previous one. `replace` for a
   filter narrowing the same thing, so it does not fill history with keystrokes. Deep links, reloads
   and sharing a screen all start working as a side effect.
2. **A full-screen overlay becomes a route.** The planner's day modal becomes `/planner/[date]`, so
   back closes it instead of leaving the page. Most of this falls out of group 2 for free.

Checked afterwards by driving the live site the same way, not by reading the code.

**Two things confirmed while testing:** signing out and pressing back still shows the sign-in screen
and no temple data (E1-S16 holds). And item 20 needed nothing — verified in the code, not assumed.

---

## Order of work

1. **Group 1, alone.** Everything else writes forms and tables against it.
2. **Groups 2 + 3 together** — staff and the focus screen are the same files. Then **4**, **5**, **6**
   and **9** as parallel tracks on non-overlapping files.
3. **Group 7**, alone, last.
4. Full test run. Deploy. Self-test all 26 items on the live site. Fix, redeploy, repeat.
5. Hand over the checklist.

---

## Decisions

Thirteen questions, settled with Rajeev on 2026-08-21, one at a time. Each records what was decided.
Where he overruled a proposal of mine, the argument is kept, so none of it is re-opened later from
memory.

### Q1 — the focus-screen pattern (items 6 and 7)

Mock: https://claude.ai/code/artifact/842e437f-f60f-4a09-9f84-21e0ef8cfa7f

**Approved as put, with one change of his: `Cancel`, not `Close`.** The eight rules and the
measurement behind the sticky header are in group 2.

**The threshold is four.** His number, not mine. Four fields or more becomes a screen. Three or fewer
stays inline. Every inline add form on live was then counted, in the browser:

| Form | Fields | |
|---|---|---|
| Record a donation | 9 | screen |
| Add a vendor | 8 | screen |
| Post a shift | 8 | screen |
| Record an invoice | 7 | screen |
| Record leave | 6 | screen |
| Add a wish-list item | 5 | screen |
| Raise a notice | 5 | screen |
| Track an item (inventory) | 4 | screen — exactly on the line |
| Add a glossary term | 3 | **stays inline** |

Hire, Update and Terminate are all well over four.

**One form in the whole app stays inline today.** That is not an argument against the rule. The rule
is for the small adders yet to be written, and it means a form that grows a fourth field converts on
its own rather than by anybody's judgement. Recorded as noticed, not as a doubt.

`/equipment` has no add button on live. Nothing to count.

### Q2 — item 19, what is a "shift"?

**Scope cut. The half-day feature stays exactly as it is.** No time, no side, no midpoint default, no
kitchen windows, no Settings screen. His judgement: too much machinery for one small feature. He is
right. The proposal was four changes to buy a fraction.

**A half-day person does not count towards the available head count.**

His reason: an extra pair of hands does not hurt. Being short when you need more does. The asymmetry
decides it.

A second reason, independent of that one: **the record does not say which half.** `half_day` is a
boolean with no time beside it. Counting the person as available claims a certainty the record does
not hold. They may be gone by noon, and lunch is the meal that needed them. Zero is the only number
the data supports.

This reverses a deliberate decision. `ScheduleResolver` line 35 reads *"Half-day leave leaves the
person in. They are in the kitchen for half of it, which is more use to a head count than pretending
they are not there at all."* That comment is wrong and goes.

**Counted out, not hidden.** The count excludes them. The week grid and any roster list still show
them, marked half day. Somebody looking for who is around today must see the name.

**Per-meal availability, with no new schema.** Both halves already exist:

- `staff_schedule_template.start_time / end_time` — a working window per person per weekday.
- `meal_plans.ready_by` — every meal already has one.

**A person counts towards a meal if their working window covers that meal's ready-by time.** Someone
on 06:00–14:00 counts for breakfast and lunch, not for dinner at 18:30. Volunteers are judged the
same way against their shift window. No migration, no Settings page, no invented kitchen hours.

### Q3 — item 17, the recipes appendix and its language

**Separate.** The worksheet prints in English. The recipes print in a language the printer chooses.

My proposal was one language for the whole sheet. He overruled it, correctly. The two halves have
two readers. The office reads the worksheet. The cooks read the recipes, and `DESIGN_SYSTEM.md` §1
already says the cooks do not read English comfortably and work from translated printed recipes.

**The temple's locale is the default, not the rule.** Whoever prints picks. A cook printing for a
Kannada kitchen and an admin printing a copy for a Hindi-speaking guest cook each get what they need,
off the same meal.

The full rule, the language list, the untranslated-dish case and the dish-name-both-ways consequence
are in group 6.

### Q4 — item 17, the typeface on paper

**Noto stays.** No Anek embedded, no font file added to the backend, no ~80KB per PDF. The card
matches the app on colour, on the type scale and on rhythm. That is enough. A printed sheet in a
folder does not need to be the same typeface as the screen it came from.

### Q5 — item 2, the ban list page

**Kept, demoted, read-only.**

I proposed deleting it and argued myself out of it. A ban is looked at through the person almost
always. But not when somebody asks what this temple has ever said about anybody, or audits before one
fades at ten years. That job needs a list, and finding them one former employee at a time is bad at
it.

`employment_bans.staff_profile_id` is `NOT NULL`, so every ban belongs to a former staff member of
this temple. The list is complete by construction.

### Q6 — item 3, does current staff get `View` too?

**No. Current staff stay at three: `Pay · Update · Terminate`.** Former staff get `View · Pay`.

`Update` already opens the whole record in an editable form. A fourth button would be a second door
to the same room. Former staff need `View` because they have no editable form and would otherwise
have no way in.

**Considered and rejected:** `View` on both rows, with `Update` moving inside the record. Tidier, and
it would make one row action mean one thing on both tables. But it puts an extra click in front of
the most common edit in the app.

### Q7 — item 13, the line the copy cut must not cross

Four kinds of text are **exempt from the twelve-word ceiling and never deleted**. They are tightened
only where tightening costs nothing.

1. **Consent wording on the devotee register.** What somebody is agreeing to.
2. **The DPDP line on PAN.** Why a tax number is asked for and what becomes of it.
3. **The warning above a ban.** Barely shortened at all. It is the gravest thing the app does. It is
   read once. Twelve words cannot carry it.
4. **Payment confirmations.** His addition. Anything stating that money moved, or is about to. A
   payment recorded against the wrong person cannot be undone here. It can only be struck out and
   typed again. Text that prevents that is worth its length.

Everything else in item 13 is in scope.

### Q8 — item 10, the 13px inset

Mock: https://claude.ai/code/artifact/c0ec96db-2d03-4c23-9997-4533a512c887

**Confirmed.** Labels, hints and errors all indent to 13px — `px-3` plus the 1px border — so they line
up with the text *inside* the box rather than the box's outer edge. Applies to `Field` and to every
ad-hoc label in the app, roughly 250 fields across 38 pages.

Item 23's `FieldRow` fix was shown on the same page and approved with it.

### Q9 — item 21, how far to go

**Fix the client guard. Add nothing else.** No server-side idle rule. No session table. No
re-authentication in this build.

The threat is physical: a tablet on a counter in a temple kitchen. A client-side idle sign-out is the
right answer to it, once it works. A server-side rule would mean a write on every request and the end
of `STATELESS`, for a threat the guard already covers.

**Recorded so it is not re-argued later:** somebody who takes the device *and* can read the browser
profile still holds a valid refresh token. No client-side timeout has ever prevented that. Device
passcodes do. If that ever needs answering, the step is re-authentication before money moves —
recording a payment, deleting a temple. A later build.

### Q10 — item 24, where the crew field goes

**A new step 4, "Who will run it", after Preparations.** The layout, the readout and the reasoning
are in group 5.

### Q11 — item 24, the default

**The median of the last three non-festival meals of that kind.** No Settings field. Nothing for the
temple to maintain. It learns the kitchen's real practice rather than asking for it.

**Not a formula off servings.** Plates divided by a ratio is guesswork dressed as arithmetic, and it
would be wrong in a way that looks authoritative.

**"Non-festival" is already a column.** `meal_plans.day_type` holds `REGULAR / WEEKEND / FESTIVAL /
CATERING`. The lookup is the most recent meals of the same `meal_kind` with a `day_type` of
`REGULAR` or `WEEKEND`. No calendar query. No new field.

**Why the median of three and not the last one.** The festival guard does not catch an unusual
*ordinary* day — a visiting sannyasi, a wedding party. That meal is stored `REGULAR` and would
otherwise become the default for the next ordinary lunch.

| Meals to draw on | Default |
|---|---|
| Three or more | the middle value |
| Two | the mean, **rounded up**. Being short is worse than being over — the Q2 asymmetry |
| One | that one |
| None | the field opens empty. Honest. A made-up number would not be |

**A correction to what the migration says.** V22's comment calls `day_type` "auto-suggested at
creation, overridable". It is not overridable. `MealPlanView` states it correctly — *"derived from
the date and the calendar, never chosen by a person"* — and `deriveDayType` runs on update as well as
create, overwriting whatever was there. The stale comment is fixed as part of this work.

**Why the stored column beats a calendar lookup**, recorded so it is not undone later. `day_type` is
what was true on the day. `calendar_overrides` lets a temple mark a day differently, and the
precompute runner refreshes days. Re-deriving at read time would let a lunch that was ordinary when
it was cooked become a festival lunch months later, moving the default underneath the planner for no
visible reason. The column is a record. The calendar is a current opinion.

### Q12 — the case rule

**Sentence case, everywhere.** Buttons, labels, hints, headings, table headers, nav, empty states.
Capital on the first word and on names only. `a full portion` becomes `A full portion`.

He expected title case and accepted the argument against it. The two hints he compared differ in two
ways, and only one of them is case: one is a sentence, the other a fragment with no capital. The
buttons are already consistent in sentence case, so title case for labels would mean re-casing every
button, or living with the site reading two ways.

This goes into `DESIGN_SYSTEM.md` as a written rule with a CI check, alongside the full-stop rule,
the no-ALL-CAPS rule and the one-word-per-thing glossary.

### Q13 — item 26, how far the festival feast goes

**Add the kind and menu history now.** Both are in group 5.

The rest of what makes a festival feast hard is left to a build of its own: a Preparations step that
can cope with twenty dishes, a job card running to several pages, and crew planning at feast scale.
