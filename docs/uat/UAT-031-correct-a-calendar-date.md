# UAT-031: Correct a calendar date

| | |
|---|---|
| **Feature area** | Meal planning — admin calendar override |
| **Technical stories** | E4-S3 (admin calendar override) |
| **Roles exercised** | Temple admin |
| **Depends on** | UAT-029 |
| **Environment needs** | **Background worker on** (so there is a computed calendar to correct, and a nightly recompute to survive). Step 18 needs the operator to run the recompute |

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
| 1 | Open **Meal plan** and find your chosen date | The day shows its tithi under the date number, and no Ekadashi mark |
| 2 | Click the **tithi** text on that day | A **day panel** opens below the calendar, headed with the full date |
| 3 | Read the panel | What the engine worked out for that day: Tithi, Month, Gaurabda year, Ekadashi, Fast, Maha-Dvadashi, Sunrise, Sunset, Festivals |
| 4 | Click **Correct this date** | A form: an *Ekadashi fasting day* tick, Ekadashi name, a **Tithi** dropdown listing names (Gaura Ekadasi, Krsna Dvitiya, Purnima…) rather than numbers, a festival note, and a required reason |
| 5 | Tick **This is an Ekadashi fasting day**, leave the reason empty, and press **Save correction** | It refuses to submit — the reason is required |
| 6 | Enter the reason `Corrected per local GBC ruling` and save | The panel closes the form; the day now shows **Ekadashi** in the calendar |
| 7 | Look at the day panel again | An amber block: *This date was corrected by hand*, with your reason |
| 8 | Press **+** on that day and try to plan **Khichdi** (rice and dal) | The Ekadashi warning appears — the correction changed the rule, not just the display (this is UAT-036's warning) |
| 9 | Sign out; sign in as `ikms.kitchen-staff.1@trading4good.org` and open the same day's panel | Staff see the day's facts **and** that it was corrected by hand, with the reason — but **no** *Correct this date* button and **no** undo |
| 10 | As kitchen staff, look for any way to correct a date | There is none |
| 11 | Sign back in as the admin, open the day panel, and press **Undo the correction** | The day returns to its computed value; the amber block disappears; the Ekadashi mark goes |
| 12 | Plan Khichdi on that day again | No warning — the guard followed the undo |
| 13 | Now correct a date the **other** way: find a real **Ekadashi** and turn the fast **off**, reason `Local ruling — the fast is observed on the following day` | The Ekadashi mark disappears from that day |
| 14 | Plan Khichdi on it | No warning — a correction works in both directions |
| 15 | Change the **Tithi** on any day using the dropdown, with a reason | The day panel and the calendar show the tithi you chose |
| 16 | Go to **/audit** as the admin | Both the corrections and the undo are listed, with the reason and what changed |
| 17 | Click the tithi on a date far in the future (two years out) | The panel says the date hasn't been calculated yet, and offers no correction |
| 18 | Ask the environment owner to run the nightly recompute, then re-check a date you left corrected | The correction is still there — the recompute did not wipe it |

## It passes if

- [ ] Clicking a day's tithi opens a panel explaining what the engine computed for that day.
- [ ] A Temple Admin can correct a single date, with a mandatory reason, in both directions.
- [ ] The tithi is chosen by name, never as a number.
- [ ] The correction changes both what is displayed and how the Ekadashi rule behaves.
- [ ] The corrected date is badged, with its reason, for **all** staff — not only admins.
- [ ] Kitchen staff can read the panel but cannot correct or undo.
- [ ] Both the correction and the undo are in the audit log with the reason.
- [ ] Undoing restores the computed value, and a recompute never silently wipes a correction.
- [ ] An uncomputed date says so instead of offering a form.

## Watch out for

- The correction is the most consequential thing a Temple Admin can do to the calendar: it decides what day the temple fasts. If the reason is ever optional, or the badge does not show, say so — the point is that a correction is never silent.
- An override applied without a reason.
- An override that changes the display but not the Ekadashi warning (or the reverse) — the two must agree.
- An override that a kitchen-staff account can make.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| **UAT031-1** | 2 | Clicking the tithi opens the day panel | Nothing appears to happen; the label looks like a link but the page doesn't change | Major — **OPEN**, analysis below |
| **UAT031-2** | 3 | The day label to be understandable | "Krsna"/"Gaura" appear on 28 of 30 cells with no explanation, and vanish on two | Minor — **OPEN**, design decision pending |

**UAT031-1 — the day panel opens off-screen.** Reported by Rajeev, 2026-08-14, on the live site.
The panel does open; it is rendered *after* the month grid, which is about a full screen tall, and
nothing scrolls it into view (verified: no `scrollIntoView` anywhere in the planner or the panel).
So a click roughly a thousand pixels above the panel produces no visible change. The hover underline
makes it worse by promising navigation. Two fixes needed: the panel must announce itself when it
opens, and it should surface near the day that was clicked rather than at the foot of the page —
which is a design decision, since the "add a meal" panel has the identical problem. Introduced by
the E4-S3 screen work, 2026-08-11.

**UAT031-2 — the day label is unexplained.** "Krsna" and "Gaura" name the two halves of the lunar
month and prefix the day's name; the two days that carry their own names (Amavasya, Purnima) take no
prefix. The data is correct — the *presentation* is not: an unfamiliar word appears on nearly every
cell, disappears on two, and the screen offers no way to find out what it means. Solution is a design
decision (legend, tooltip, relabelling), deferred to the Meal plan discussion.

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
