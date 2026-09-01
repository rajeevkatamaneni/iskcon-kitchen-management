# UAT-076: What the store issued to each kitchen

| | |
|---|---|
| **Feature area** | Kitchens and issuing — costing what left the store |
| **Technical stories** | E10-S13 (what the store issued to each kitchen, costed) · E3-S9 (the costing it borrows) |
| **Roles exercised** | Temple admin, kitchen staff, volunteer (to prove the refusal) |
| **Depends on** | UAT-067 (the kitchens), UAT-068 and UAT-069 (requests), **UAT-070 (issuing — this test reads back what that one recorded)** |
| **Environment needs** | None |

## What this feature is for

A temple has one store and several kitchens. When the storekeeper hands a sack of rice over the
counter to the Deity kitchen, the food comes off the temple's books — and until now nobody had ever
asked the ledger where it went, even though it has recorded the answer since issuing was built.

This screen asks it. One row per kitchen, costed, so a temple can see where its food is going
without keeping a second set of books.

**But it can only ever see half the picture, and it has to say so.** The mathajis of the Deity
kitchen sometimes buy food themselves, and the temple accepts that on purpose — once food is issued,
what a kitchen does next is its own business. A kitchen that buys its own vegetables records nothing
here. So the figure beside a kitchen's name is a **floor**: the real cost of feeding that kitchen is
that much *at least*, and probably more.

## How it is supposed to work

- The heading is **Issued from the temple store**, and it says on the page that a kitchen may also
  buy food itself and that each figure is **a floor, not a total**.
- One row per kitchen the store issued to in the period, **dearest first**. A kitchen the store
  issued nothing to does not appear — a row of zeroes is not a finding.
- Each row carries how many **requests** were filled, how many distinct **ingredients** went over the
  counter, the **estimate**, and how many of its ingredients have **no known price**.
- Several requests to one kitchen make **one row**, not several.
- The costing is the same estimate as everywhere else: **materials only**, at vendors' last-known
  prices, with unpriced ingredients counted and named rather than costed at zero.
- **Consumption is not an issue.** A kitchen that plans its meals in this application draws its stock
  as consumption and is costed on the *Cost per serving* report instead; its row here, if it has one,
  is from before it opted in, and says so.
- The period runs on **the temple's own days**: food handed over at 9pm belongs to that day.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/issued-from-store** (menu: **Kitchen** → **Issued from store**)
- **You need issuing to have happened.** Finish **UAT-070** first. If you have not, do this much:

  1. On **/vendors** (UAT-037), make sure **Rice**, **Toor Dal** and **Ghee** have a **Last price**.
     Without prices every row here is ₹0 and the test proves nothing.
  2. On **/ingredients**, add **Rock Salt** (category *Spices*, unit `gm`) with **no vendor** — the
     deliberately unpriced ingredient.
  3. As kitchen staff, raise **two** ingredient requests from **Deity Kitchen** (UAT-068) and **one**
     from **Prasadam Kitchen**, each asking for Rice and Toor Dal, and one of the Deity requests also
     asking for **Rock Salt**.
  4. As the admin, approve them (UAT-069) and **issue** all three (UAT-070).
  5. Leave **Sweets Kitchen** with no request at all — step 9 needs a kitchen that was issued nothing.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open the menu and find **Issued from store** | It is in the **Kitchen** group, next to **Cost per serving** |
| 2 | **Read the heading, word for word** | **Issued from the temple store**. Not "kitchen food cost", not "Deity kitchen spend" — the name is the honesty of the report |
| 3 | **Read the sentence under the heading, word for word** | It says what the screen is, **and** that *a kitchen may also buy food itself, and that never reaches these figures*, **and** that *each one is a floor, not a total*. **All three parts must be there.** If any is missing, stop and write it down — this is the most important check in this test |
| 4 | Read the notice above the table | **Estimated, materials only**, and: *This is what left the temple store, and nothing else. A kitchen that buys food itself keeps no record of it here, so its real food cost is higher than the figure beside its name* |
| 5 | Read the line **below** the table | It tells you what to do about food a kitchen bought itself: if it is carried into the temple store, record it as a **donation in kind**, and it is then issued back out like anything else |
| 6 | Look at the columns | Kitchen · Requests · Ingredients · Estimated materials |
| 7 | Find **Deity Kitchen** and **Prasadam Kitchen** | Both are there, **dearest first** |
| 8 | Check Deity Kitchen's **Requests** column | It reads **2** — its two requests are **one row**, not two rows |
| 9 | Look for **Sweets Kitchen** | It is **not on the table at all**. A kitchen the store issued nothing to is absent, not a row of zeroes |
| 10 | Look under **Deity Kitchen**'s name | **1 ingredient has no known price** — Rock Salt was issued and is counted and named, never costed at ₹0 |
| 11 | Read the notice at the top again | Rock Salt is **named** there too, as left out until a vendor price is recorded |
| 12 | Look at the **All kitchens** row at the foot | Requests and Estimated materials are the totals. The **Ingredients** cell is **blank** — two kitchens issued the same rice were issued one ingredient between them, and adding the columns would say two |
| 13 | Switch the period between **Week**, **Month** and **Year** | The same kitchens over a different range; step back to a month before the temple existed |
| 14 | On an empty period | **The store issued nothing in this period** — a sentence, not a table of zeroes |
| 15 | Cook a meal on **/planner** (UAT-035), so stock comes down as **consumption**, then reload this report | **Nothing changes.** Cooking is not issuing, and it is counted nowhere here |
| 16 | On **/kitchens**, edit **Prasadam Kitchen** and switch on **Does this kitchen plan its meals here?** (UAT-072). Come back here | Prasadam Kitchen is still listed for what it was issued before, and now carries a line: *Now plans its meals here, so its newer food is counted as consumption* |
| 17 | Issue something to **Deity Kitchen** late in the evening (after 21:00 India time, if you are testing then) and reload | It falls on **today**, the day the storekeeper handed it over — not tomorrow, and not yesterday |
| 18 | Sign out; sign in as `ikms.kitchen-staff.1@trading4good.org` and open the screen | The storekeeper can read the report about their own issuing — the same figures |
| 19 | Sign out; sign in as `ikms.volunteer.1@trading4good.org`. Look at the menu, then type **/issued-from-store** | No menu item; the address gives **Not your page** |

## It passes if

- [ ] **The heading reads *Issued from the temple store*.**
- [ ] **The screen states, in words, that a kitchen may buy food itself and that each figure is a floor, not a total.**
- [ ] One row per kitchen the store issued to, dearest first, with requests, ingredients and the estimate.
- [ ] Several requests to one kitchen make one row.
- [ ] A kitchen issued nothing is absent; an empty period says so rather than showing zeroes.
- [ ] Unpriced ingredients are named above the table and against the kitchen that was issued them.
- [ ] Every figure says it is an estimate of materials only.
- [ ] Consumption is counted nowhere here.
- [ ] A kitchen that now plans its meals here is marked as such and keeps its earlier row.
- [ ] The day an issue belongs to is the temple's day.
- [ ] A storekeeper can read it; a devotee is neither offered it nor allowed onto it.

## Watch out for

- **Any wording that turns a floor into a total.** A heading, a column, a tooltip or a summary line
  that reads as *what this kitchen's food cost* is a defect even if every number under it is right —
  the whole design of this screen is a decision about what it must not be mistaken for. Major.
- The estimate being presented without the word **estimated**, anywhere on the page.
- **A corrected issue.** A mistake in the ledger is undone by a compensating correction, and a
  corrected issue is meant to drop out of these figures entirely. **There is no screen that corrects
  a stock movement**, so you cannot produce one by hand — record that you could not test it rather
  than passing the line silently.
- A kitchen appearing twice, or the same request counted twice, after a second issue against a
  partly-issued request.
- The **Ingredients** total at the foot being filled in. It must be blank.
- `KMS-4988` — *That period doesn't work* — appearing from the Week/Month/Year control. It should not
  be reachable that way; if you see it, write down what you had selected.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT076-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
