# UAT-031: Correct a calendar date

| | |
|---|---|
| **Feature area** | Meal planning — admin calendar override |
| **Technical stories** | E4-S3 (admin calendar override) |
| **Roles exercised** | Temple admin |
| **Depends on** | UAT-029 |
| **Environment needs** | **Background worker on** (so there is a computed calendar to override, and a nightly recompute to survive) |

## What this feature is for

Even a correct astronomical calculation occasionally needs a human correction — an unusual lunar month,
or a local GBC ruling that differs. A temple must never be forced to work around its own system on
something as important as a fasting day. So a Temple Admin can correct any single date, with a reason,
and that correction sticks.

## How it is supposed to work

- A **Temple Admin only** may override a date's Ekadashi or festival marking. Kitchen staff may not.
- A **reason is required**, and the change is written to the audit trail.
- The override **wins** over the computed value everywhere — the planner display, and the Ekadashi
  warning when planning a meal.
- The overridden date is visibly badged, so everyone can see it was corrected by hand.
- The nightly recompute must never quietly wipe an override; removing the override restores the
  computed truth.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/planner**
- Pick a date next month that is **not** currently Ekadashi, and note it. You will mark it as Ekadashi
  and then undo it.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Meal plan** and find your chosen date | It shows an ordinary tithi and **no** Ekadashi mark |
| 2 | Look for the way to correct that date — clicking the day, a menu on the cell, an edit control anywhere on the planner | **Record exactly what you find.** If there is no way to do it from any screen, write that down plainly and note every place you looked |
| 3 | *(If a control exists)* Mark the date as **Ekadashi**, giving the reason `Corrected per local GBC ruling` | The change is accepted |
| 4 | Look at the day cell | It is now marked **Ekadashi**, with a visible indication that it was overridden |
| 5 | Press **+** on that day and try to plan **Khichdi** (rice and dal) | The Ekadashi warning appears — the override changed how the rule behaves, not just the display (this is UAT-036's warning) |
| 6 | Sign out; sign in as `ikms.kitchen-staff.1@trading4good.org` and open the same date | The override is visible to staff too, badged the same way |
| 7 | As kitchen staff, try to override a date yourself | Refused — or the control is not offered at all. Kitchen staff cannot correct the calendar (`KMS-4301` if a message appears) |
| 8 | As the admin, go to **/audit** | The override appears, with the date, what changed, and your reason |
| 9 | As the admin, **remove** the override on that date | The computed value returns; the badge disappears; the Ekadashi warning no longer applies |
| 10 | Wait for (or ask the environment owner to run) the nightly recompute, then re-check a date you left overridden | The override is still there — the recompute did not wipe it |

## It passes if

- [ ] A Temple Admin can correct a single date, with a mandatory reason.
- [ ] The correction changes both what is displayed and how the Ekadashi rule behaves.
- [ ] The corrected date is badged so everyone can see it was changed by hand.
- [ ] Kitchen staff cannot make the correction.
- [ ] The correction is in the audit log with its reason.
- [ ] Removing it restores the computed value, and a recompute never silently wipes it.

## Watch out for

- **Step 2 is the real test.** The planner shows an *·override* marker on a day that carries one, which tells us the display side exists — but if there is no screen anywhere to *create* one, then the safety net the temple was promised is not reachable by the person meant to use it. Record it as a Major finding with the note "no override screen found", and list where you looked. That is likely root cause R6 (never built) rather than a bug.
- An override applied without a reason.
- An override that changes the display but not the Ekadashi warning (or the reverse) — the two must agree.
- An override that a kitchen-staff account can make.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT031-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
