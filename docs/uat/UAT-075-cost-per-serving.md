# UAT-075: What a serving costs, by kind of meal

| | |
|---|---|
| **Feature area** | Inventory — costing |
| **Technical stories** | E3-S9 (cost per serving, by kind of meal) · E3-S8 (the day's materials cost, which this must not change) |
| **Roles exercised** | Temple admin, kitchen staff, volunteer (to prove the refusal) |
| **Depends on** | UAT-015 (recipes), UAT-032 (planned meals), UAT-037 (vendor prices), UAT-062 (the Today screen) |
| **Environment needs** | None |

## What this feature is for

A temple wants to know what it costs to put one plate in front of one person — and, more to the
point, how a public prasadam plate compares with a Sunday feast plate. The Today screen already says
what the day's food costs in total, but a single daily total cannot answer a comparison between
kinds of meal however it is presented. This screen asks the same data that different question.

Everything it says is an **estimate of materials only**. Much of a temple store is donated and has
no purchase price at all, and labour is deliberately left out: a cook on a 06:00–14:00 shift makes
breakfast *and* lunch, so their pay can only ever be split by guesswork.

## How it is supposed to work

- One row per **kind of meal the temple actually cooked** in the period — the temple's own kinds, not
  a fixed list. A kind nobody cooked does not appear at all.
- A **meal is a date and a kind**. A lunch of three preparations is *one* meal costing the sum of its
  three baskets, fed to one head count — not three meals.
- Rows are sorted **dearest serving first**, so reading top to bottom is the answer.
- **Every figure says it is an estimate of materials only**, and ingredients with no known price are
  **counted and named** rather than quietly costed at zero.
- Where nobody counted the people at a meal, that meal is in the kind's **total** but out of the
  **cost per serving**, and the number of meals left out is said beside the figure. A kind with no
  head count anywhere shows **—** and sorts to the foot.
- The **All meals** row deliberately leaves the per-serving cell **blank**: an average across every
  kind would read as a fact about none of them.
- Cancelled preparations contribute nothing. A meal already cooked still does.
- **The Today screen is not changed by this report existing.** The day's total is the same figure it
  always was.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/cost-per-serving** (menu: **Kitchen** → **Cost per serving**)
- **Set the scene**, because a fresh temple has nothing to compare:

  1. On **/vendors** (UAT-037) make sure **Rice**, **Toor Dal** and **Ghee** each have a **Last price**
     against a vendor. Without prices every figure here is zero and the test proves nothing.
  2. On **/ingredients** add **Rock Salt** (category *Spices*, unit `gm`) and give it **no vendor at
     all**. This is the deliberately unpriced ingredient.
  3. On **/recipes** open **Khichdi** (UAT-015) and add a line of **Rock Salt `200` gm**.
  4. On **/planner**, click **today** and plan **two meals**, in the composer:

     | Kind | Preparation | Adults | Children | Seniors |
     |---|---|---|---|---|
     | Lunch | Khichdi | 150 | 0 | 0 |
     | Breakfast | Sabudana Khichdi | 60 | 10 | 0 |

- **Write down** what the **Cost of materials** tile on **/today** says before you go any further —
  the figure and the line underneath it. Step 12 compares against it.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open the menu and find **Cost per serving** | It is in the **Kitchen** group, next to **Issued from store** |
| 2 | Open it | Heading **Cost per serving**, and under it: *What a serving costs at each kind of meal, so a prasadam plate can be read against a feast plate* |
| 3 | Look at the period control | **Week / Month / Year**, opening on **Month**, with arrows to step back and forward. It opens on this month |
| 4 | Read the notice above the table | It begins **Estimated, materials only**, and goes on: *Labour, fuel and the rest of what a meal costs are not in these figures.* |
| 5 | Read the rest of that notice | It says **1 ingredient has no known price**, and names **Rock Salt** as left out until a vendor price is recorded |
| 6 | Look at the columns | Kind of meal · Meals · Servings · Estimated materials · Cost per serving |
| 7 | Find the **Lunch** and **Breakfast** rows | Both are there, with the temple's own names for them. The dearer serving is **above** the cheaper one |
| 8 | Under the **Lunch** kind name | A small line: **1 ingredient has no known price** — Rock Salt is named, not silently costed at ₹0 |
| 9 | Do the arithmetic on one row by hand | **Estimated materials ÷ Servings = Cost per serving**, to the nearest paisa. Breakfast's Servings should read **66** (60 adults + 10 children at 0.6 of a portion) |
| 10 | Add a second preparation to **today's Lunch** — open the meal, add **Sabudana Khichdi**, save | The **Meals** count for Lunch is **still 1**. A lunch of two preparations is one meal. Its **Estimated materials** goes up; its **Servings** does not |
| 11 | Look at the **All meals** row at the foot | Meals, Servings and Estimated materials are the column totals — and the **Cost per serving** cell is **empty**. Not a dash, not an average |
| 12 | Go to **/today** and read the **Cost of materials** tile | **Exactly what you wrote down before you started**, plus today's new meals. This report has changed nothing about it, and there is no per-meal breakdown on Today |
| 13 | Back on **/planner**, plan a third meal today: kind **Dinner**, preparation **Khichdi**, and set **Adults**, **Children** and **Seniors** all to **0** | *Cooking for 0 people*. Save it |
| 14 | Reload **/cost-per-serving** | A **Dinner** row, with an **Estimated materials** figure and **—** under **Cost per serving**. It sits at the **foot** of the table, below every kind that has a figure |
| 15 | Cancel that Dinner meal on the planner and reload the report | The Dinner row is **gone** — a cancelled meal contributes nothing, and a kind nobody cooked does not appear |
| 16 | Switch to **Week**, then **Year**, then back to **Month** | The same kinds over a different range; the figures grow with the range, never shrink |
| 17 | Step **back** with the arrow until you reach a month before the temple cooked anything | **Nothing was cooked in this period** — a sentence, not a table of zeroes |
| 18 | Sign out and sign in as `ikms.volunteer.1@trading4good.org`. Look at the menu | There is **no Cost per serving** item |
| 19 | While still signed in as the volunteer, type **/cost-per-serving** into the address bar | **Not your page** — *You don't have access to this part of the app. Ask your temple administrator.* No figures are shown, even for a moment |

## It passes if

- [ ] The screen lists the temple's own kinds of meal, one row each, dearest serving first.
- [ ] Every figure is labelled **Estimated, materials only**, above the table and against the kinds.
- [ ] The unpriced ingredient is **counted and named**, both overall and against the kind that used it.
- [ ] A meal of several preparations counts as **one** meal, with one head count.
- [ ] Estimated materials ÷ Servings gives the Cost per serving on each row.
- [ ] The **All meals** row leaves the per-serving cell blank.
- [ ] A kind with nobody counted shows **—** and sorts to the foot.
- [ ] A cancelled meal contributes nothing; a kind nobody cooked is absent.
- [ ] The **Cost of materials** tile on Today is unchanged, and Today still shows no per-meal split.
- [ ] A period with nothing cooked says so rather than showing a table of zeroes.
- [ ] A devotee is neither offered the screen nor allowed onto it.

## Watch out for

- **Rock Salt being costed at ₹0 instead of being named.** That is the failure this whole design
  exists to prevent: a total that quietly omits part of the basket cannot be acted on. Major.
- **A figure appearing anywhere without the word "estimated" beside it.** Check the row-level notes
  as well as the notice at the top.
- The **All meals** per-serving cell showing an average. It must be blank.
- **The "N meals not counted" line.** The report is meant to say how many meals it had to leave out
  of the division. Every meal planned in the composer carries a head count, so on a fresh temple you
  will most likely never see this line — a meal with **no** head count at all is different from one
  counted as zero people, which is what step 13 produces. If you do see it, check the arithmetic:
  such meals belong in the total and out of **both halves** of the per-serving figure.
- **A period longer than a year, or backwards.** The screen only offers whole weeks, months and
  years, so you should not be able to build one. If you ever see `KMS-4988` — *That period doesn't
  work* — write down exactly what you had selected; that is a finding.
- The Servings column counting *preparations* or *litres* rather than people. Servings is a number of
  people: 60 adults and 10 children is 66, not 70.
- Whether a **kitchen staff** member sees the same figures as the admin. They should — it is the same
  fact about the same cooking.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT075-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
