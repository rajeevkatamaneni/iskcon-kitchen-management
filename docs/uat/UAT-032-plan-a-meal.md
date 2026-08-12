# UAT-032: Plan a meal

| | |
|---|---|
| **Feature area** | Meal planning |
| **Technical stories** | E4-S4 (meal plan across four contexts) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-015 (recipes), UAT-029 (the calendar) |
| **Environment needs** | Background worker on, for the calendar context |

## What this feature is for

The week's cooking, visible in one place: what is being made, on which day, in which slot, for how
many. It is the hinge of the whole product — the meal plan is what tells the store room what will be
consumed and the order list what must be bought.

## How it is supposed to work

- A planned meal is a **date**, a **meal slot** (Lunch, Dinner, Deity Offering), a **recipe**, a
  **target serving count**, and a **day type**.
- The day type is suggested for you — Festival if there is an occasion, Weekend by the day of the week,
  otherwise Regular — and you can override it. Catering is always chosen explicitly (UAT-033).
- A planned meal can be cancelled while it is still planned; once cooked, it cannot (UAT-035).
- The month view shows the whole month's cooking against the Vaishnava calendar.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/planner** (menu: **Meal plan**)

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Meal plan** | The current month, one cell per day, with tithi and any festival marked |
| 2 | Press **+** on tomorrow's date | A *Plan a meal* panel with Slot, Recipe, Servings, Day type, Catering client, Venue |
| 3 | Look at **Slot** | The temple's seeded slots — Lunch, Dinner, Deity Offering |
| 4 | Look at **Day type** and **Servings** | Already filled in for that date — Regular or Weekend, with a suggested serving count |
| 5 | Choose slot **Lunch**, recipe **Khichdi**, servings `150`, and press **Add to plan** | The panel closes; the meal appears on tomorrow's cell showing the recipe name, the slot and the servings |
| 6 | Look at the small badge on the planned meal | A status marker — *planning*, *ok* or *short* depending on stock (that is UAT-034) |
| 7 | Plan a second meal on the same day: slot **Dinner**, recipe **Aam Ras**, servings `150` | Both meals show on the cell |
| 8 | Plan a meal on a **festival** day | Day type pre-fills as **Festival**, with a larger suggested serving count |
| 9 | Plan a meal on a **Saturday** | Day type pre-fills as **Weekend** |
| 10 | Change a suggested day type by hand (Festival → Regular) before saving | The change is accepted — the suggestion is not a lock |
| 11 | Press **Cancel** on one of the planned meals | It is marked cancelled and no longer counted as cooking to be done |
| 12 | Try to plan a meal with **no recipe** chosen | Refused |
| 13 | Try servings of `0` | Refused |
| 14 | Page to the next month and back | Your planned meals are still there |
| 15 | Fill a whole week — two meals a day for seven days | The month view stays readable and does not become slow or jumbled |

## It passes if

- [ ] A meal can be planned with a slot, recipe, servings and day type.
- [ ] The day type and serving count are suggested from the calendar and can be overridden.
- [ ] Several meals can be planned on one day, in different slots.
- [ ] A planned meal can be cancelled.
- [ ] A meal with no recipe or zero servings is refused.
- [ ] A busy month renders cleanly, including on a phone.

## Watch out for

- The day type suggestion being wrong — Regular on a festival day, or Weekend on a Wednesday.
- A meal saved against the wrong date (off by one day). Check the cell it lands on carefully, especially around month boundaries.
- Whether an already-planned meal can be **edited** (changing servings or the recipe) rather than only cancelled and re-created. Record what you find — the story asks for create/edit/cancel, so an edit that is missing is a finding.
- Recipes that were archived or belong to another temple appearing in the picker.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT032-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
