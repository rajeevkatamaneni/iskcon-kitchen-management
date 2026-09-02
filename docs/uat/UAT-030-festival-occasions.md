# UAT-030: Festival occasions

| | |
|---|---|
| **Feature area** | Meal planning — festival occasion catalogue |
| **Technical stories** | E4-S2 (festival occasion catalogue) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-029 |
| **Environment needs** | **Background worker on** (the occasions are placed on computed calendar dates) |

## What this feature is for

"Janmashtami" is not just a date — it is a scale of cooking, a menu the temple has done before, and a
crowd. The system knows the named occasions so that planning one carries that meaning, instead of the
planner showing an ordinary Tuesday.

## How it is supposed to work

- A new temple is seeded with the common pan-ISKCON occasions, placed on the dates the calendar engine
  computes.
- Some occasions are **computed** (Janmashtami moves each year); some are **fixed** and local — a
  temple's own anniversary — which the temple adds itself.
- Each occasion can carry a **default expected serving count**, recorded against the occasion.
- **Since 2026-08-31 nothing pre-fills a head count on the meal form.** All three counters — Adults,
  Children, Seniors — open at **0**, because the application inventing a number nobody chose is the
  fault that change removed (UAT-032). So an occasion's stored default is a fact about the occasion,
  and this test asks you to find out whether it reaches the form at all. Record what you see; do not
  assume either answer.
- An occasion appears on the planner's calendar and sets the day type to **Festival**.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff), then repeat the admin steps
  as `ikms.temple-admin.1@trading4good.org`.
- **Start at:** **/planner**

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | In **Meal plan**, page forward to a month containing a major festival (Janmashtami, Gaura Purnima, Radhastami, Govardhan Puja) | The festival's name appears on that day's cell |
| 2 | Press **+** on that day to plan a meal | The **Day type** already reads **Festival**, and the festival's name is shown beside it |
| 3 | Look at step 2 of the form, **Who is expected** | **Adults**, **Children** and **Seniors** all read **0**, and **Cooking for** reads **0 people** — the same as on any other day. **Record whether the festival's own default serving count appears anywhere on this form**, and where |
| 4 | Close the panel and press **+** on an ordinary weekday | Day type **Regular**, and the counters again at **0** |
| 5 | Press **+** on a Saturday or Sunday | Day type **Weekend** |
| 6 | Now look for a screen where the temple can **manage its own occasions** — for instance to add "Temple Anniversary" on a fixed date each year | Search the menu and the planner. **Record exactly what you find.** If there is no such screen, say so plainly in your report |
| 7 | If a screen exists: add `Temple Anniversary` on a fixed date, with a default of 800 servings | It appears on the planner on that date and sets day type **Festival**. Note whether the 800 is visible anywhere when you plan a meal on it — the counters themselves will still open at 0 |
| 8 | If a screen exists: page forward a year | The anniversary appears again on the same date next year |
| 9 | Check the ICC festival list the temple actually uses (Nityanand Trayodasi, Gaurpurnima, Ram Navami, Narsimha Chaturdasi, Snana Yatra, Panihati, Ratha Yatra, Balaram Purnima, Janmastami, SP Appearance Day, Radhastami, Sharad Purnima, Govardhan Puja) against what appears in the planner over a year | List any that never appear |

## It passes if

- [ ] Seeded festivals appear on the planner on the correct computed dates.
- [ ] Planning on a festival day sets the day type to **Festival** and names the festival.
- [ ] The head-count counters open at **0** on a festival day as on any other, and what becomes of the occasion's stored default is recorded either way.
- [ ] Ordinary days and weekends are typed correctly.
- [ ] The temple can add its own fixed-date occasion, and it recurs annually.
- [ ] The festivals the temple actually cooks for are all present.

## Watch out for

- **Step 6 is the point of this test.** The design says a temple extends the catalogue itself — a temple anniversary is inherently local. If there is no screen to do it, do not hunt for a workaround: write *"no screen found to add or edit occasions"* in your report, and note which menu entries you checked. That is a coverage finding (root cause R6), and it is more valuable than a workaround.
- A festival appearing on the wrong date — that belongs to UAT-029; cross-reference it there.
- **A head count pre-filled with anything at all** — 100, 800, or the occasion's default. Since
  2026-08-31 nothing may fill those counters but a person (UAT-032). If a festival day arrives with a
  number in them, record it as Major, whatever the number is: a figure nobody typed is the fault,
  not a wrong figure.
- Whether the occasion's **default expected serving count** is now stored and never used. If nothing
  on the form or the day panel shows it, say so — it is a coverage finding, not a bug to work around.
- Festivals from the temple's real list that never appear anywhere in the year.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT030-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
