# UAT-036: The Ekadashi guard

| | |
|---|---|
| **Feature area** | Meal planning — Ekadashi violation flagging |
| **Technical stories** | E4-S6 (Ekadashi violation flagging) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-029 (a working calendar), UAT-015 (recipes) |
| **Environment needs** | **Background worker on** — without the calendar there are no Ekadashi days to guard |

## What this feature is for

On Ekadashi, devotees fast from grains and beans. Planning khichdi on that day is a mistake that ought
to be caught while planning, not discovered while serving. But it is a **warning, not a bar**: temples
legitimately cook grains on Ekadashi for children and non-fasting visitors. The temple decides — the
system makes sure the decision is deliberate and recorded.

## How it is supposed to work

- Planning a recipe containing a grain or bean on an Ekadashi raises a **confirmation naming the
  offending ingredients**.
- Proceeding records an **acknowledgement** on the plan — who decided, and when — and the meal is
  badged.
- There is **no silent bypass**: you cannot plan a grain dish on Ekadashi without passing the
  confirmation.
- Recipes in the Ekadashi category pass without any warning.
- If a Temple Admin corrects the date (UAT-031), the guard follows the correction immediately.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/planner** — page forward to the next **Ekadashi** day (marked on the cell)
- You need **Khichdi** (rice and dal — a violation) and **Sabudana Khichdi** (Ekadashi category — fine)
  from UAT-015.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Find the next Ekadashi in the planner | The cell is tinted and labelled **Ekadashi** |
| 2 | Press **+** on that day | The panel heading names the date and says **(Ekadashi)** |
| 3 | Choose recipe **Khichdi**, servings `150`, and press **Add to plan** | **Blocked by a warning:** *This recipe has grains or beans, and <date> is Ekadashi*, listing the ingredients (Rice, Toor Dal), with two choices: **Plan it anyway** and **Choose another recipe** |
| 4 | Press **Choose another recipe** | The warning closes and nothing is planned |
| 5 | Check the day's cell | No Khichdi on it |
| 6 | Press **+** again, choose **Sabudana Khichdi**, and add it | Planned with **no warning at all** — an Ekadashi-category recipe passes cleanly |
| 7 | Press **+** again, choose **Khichdi**, and this time press **Plan it anyway** | The meal is planned |
| 8 | Look at the planned meal on the cell | It carries a marker showing the grain decision was acknowledged |
| 9 | Plan the same Khichdi on an **ordinary** (non-Ekadashi) day | No warning — the rule applies only to fasting days |
| 10 | Ask an admin to run UAT-031 and mark an ordinary day as Ekadashi. Then plan Khichdi on it | The warning now appears on that day too |
| 11 | Ask the admin to remove the override, then plan Khichdi there again | No warning — the guard follows the corrected calendar both ways |
| 12 | Look for any way to plan a grain dish on Ekadashi **without** seeing the warning | There should be none |

## It passes if

- [ ] A grain or bean recipe on Ekadashi raises a confirmation naming the ingredients (`KMS-4917`).
- [ ] Declining plans nothing.
- [ ] An Ekadashi-category recipe raises no warning.
- [ ] Proceeding plans the meal and records the acknowledgement visibly.
- [ ] Ordinary days raise no warning.
- [ ] A calendar correction changes the guard's behaviour immediately, in both directions.
- [ ] There is no route that bypasses the warning silently.

## Watch out for

- The warning listing ingredients that are **not** grains or beans, or missing one that is. The seeded list is Rice, Wheat Flour, Semolina, Toor Dal, Moong Dal, Chana Dal, Urad Dal.
- The warning appearing as a hard block with no way to proceed. That would be wrong: the design is a warning requiring acknowledgement, because a temple legitimately cooks for non-fasting visitors. Record it as Major if you cannot proceed at all.
- The acknowledgement not being visible afterwards. It should be — informational in tone, not accusatory.
- Whether a **Temple Admin** sees a different behaviour from kitchen staff here. They should not; this rule is the same for both.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT036-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
