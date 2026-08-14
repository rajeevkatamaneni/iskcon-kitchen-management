# UAT-032: Plan a meal

| | |
|---|---|
| **Feature area** | Meal planning |
| **Technical stories** | E4-S7 (the planner as it now is) · E4-S4 (the original) · E4-S3 (correcting a date) · E4-S5 (sufficiency) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-015 (recipes), UAT-029 (the calendar) |
| **Environment needs** | Background worker on, for the calendar context |

## What this feature is for

The temple's cooking, visible in one place: what is being made, on which day, and by when it has to
be ready. It is the hinge of the whole product — the meal plan is what tells the store room what will
be consumed and the order list what must be bought.

## How it is supposed to work

The calendar is deliberately quiet: it shows the days, what is planned on each, and a small mark for
a fasting day or a festival. Everything else happens **inside a day**.

- **Click a day** and it opens full-screen. The top panel is what the calendar says about that day —
  and a Temple Admin can correct it there. Below it are the meals already planned, then the form to
  plan another.
- A planned meal is a **kind of meal** (Breakfast, Lunch, Dinner, Deity Offering, Catering order,
  Outside event), a **recipe**, a **serving count**, and **the time the food must be ready**.
- **Everyday meals arrive with their time already filled in** — the temple's own lunch and dinner
  hours — and you can change it. **The occasional ones do not**: a deity offering, a catering order or
  food going out to an event must be given a time, because guessing one is worse than asking.
- Nobody is asked what *sort* of day it is. Weekend follows from the date, festival from the calendar,
  and catering is a kind of meal rather than a kind of day.
- A catering order asks who it is for and where it is going; an outside event asks where.
- The day's meals are listed **in the order they must be ready**, which is the order the kitchen works
  in, each with whether there is stock for it.
- **Past days are read-only.** You can look at what was cooked; you cannot plan into yesterday.
- **Day / Week / Month** switch the view. **Today** returns to the current day.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/planner** (menu: **Meal plan**)
- You need at least one recipe (UAT-015). With none, the day view says so and offers to add one.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Meal plan** | A clean month grid. Today is marked. Days carry a small dot where there is a fast or a festival — no wall of text |
| 2 | Switch to **Week**, then **Day**, then back to **Month** | Each view shows the same cooking, at a different range. **Today** always returns you to today |
| 3 | Click **today's** cell | The day opens full-screen: the date at the top, then a panel of what the calendar says (tithi, month, sunrise), then the meals, then the form |
| 4 | Look at the **Meal** list | Breakfast, Lunch, Dinner, Deity Offering, Catering order, Outside event |
| 5 | Choose **Lunch** | **Ready by** fills in with the temple's lunch time |
| 6 | Choose **Deity Offering** | **Ready by** is *empty* and must be given — this is deliberate |
| 7 | Choose **Lunch**, recipe **Khichdi**, servings `150`, and press **Add to the plan** | The meal appears above, showing its time, kind, recipe and servings, with a stock badge |
| 8 | Plan a second meal the same day — **Dinner**, servings `150` | Both appear, **earliest ready-by first**, whichever order you entered them in |
| 9 | Change one meal's **Ready by** to earlier than the other and re-check the order | The list re-orders by time |
| 10 | Choose **Catering order** | You are asked for the client and the venue as well as a time; leaving either out is refused (`KMS-4944`, `KMS-4945`) |
| 11 | Choose **Outside event** | You are asked for the venue, but not a client |
| 12 | Press **Cancel** on a planned meal | It is no longer counted as cooking to be done |
| 13 | Try to add a meal with **no recipe**, then with servings `0` | Both refused, in plain language |
| 14 | Close the day (**Close**, or the Escape key) and look at the cell | The day now shows what is planned on it |
| 15 | Click a **past** day | It opens, shows what was planned, and offers no way to add — read-only by design |
| 16 | Click a **fasting day** (one with a mark) and try to plan something with grains | You are warned and asked to confirm (`KMS-4917`) — UAT-036 covers this properly |
| 17 | As a **temple admin**, open a day and press **Correct this date** | You can change what the calendar says for that day, with a reason. As kitchen staff, that button is not there (UAT-031) |
| 18 | Page to the next month and back | Your planned meals are still there |
| 19 | Fill a whole week — two meals a day for seven days | The month view stays readable and quick |
| 20 | Open the planner on a **phone** | The month is readable; a day opens full-screen and the form is usable one-handed |

## It passes if

- [ ] The calendar shows what is planned and nothing else; the detail is inside a day.
- [ ] A day opens full-screen with the calendar facts, the day's meals, and the form, in that order.
- [ ] Everyday meals arrive with a ready-by time; occasional ones insist on being given one.
- [ ] The day's meals list in ready-by order, not the order they were entered.
- [ ] Catering asks for a client and a venue; an outside event asks for a venue.
- [ ] Nobody is asked what sort of day it is.
- [ ] A planned meal can be cancelled; a past day cannot be planned into.
- [ ] No recipe and zero servings are both refused.
- [ ] Day / Week / Month all work, and **Today** returns to today.
- [ ] It works on a phone.

## Watch out for

- **A date that lands a day out.** Check the cell the meal appears on, especially near a month boundary
  and late in the evening. Everything in this product runs on the *temple's* day, in India.
- A ready-by time that pre-fills for a kind that should ask, or an empty one for Lunch or Dinner.
- The day view opening but leaving you unsure how to get out of it.
- The meal list ordered by entry rather than by time.
- Whether an already-planned meal can be **edited** (servings, recipe, time) rather than only cancelled
  and re-created. Record what you find.
- Recipes that were archived or belong to another temple appearing in the picker.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT032-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
