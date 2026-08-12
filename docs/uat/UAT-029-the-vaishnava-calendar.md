# UAT-029: The Vaishnava calendar in the planner

| | |
|---|---|
| **Feature area** | Meal planning — the calendar engine |
| **Technical stories** | E4-S1 (calendar engine: astronomical computation) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-002 (the temple, with its coordinates) |
| **Environment needs** | **Background worker on.** The calendar is built by a job when a temple is created and refreshed nightly. With the worker off, the planner will show no calendar information at all |

## What this feature is for

The temple's cooking year is governed by the Vaishnava calendar: Ekadashi fasting days, Kartik, and
the major festivals, all on lunar dates that move each year. The system works these out itself, from
the temple's own coordinates, rather than importing a published list — because tithi is determined at
*local* sunrise, so a temple in Bengaluru and one in Mumbai can legitimately differ.

## How it is supposed to work

- For each date, the calendar knows the **tithi**, the paksa, whether it is **Ekadashi** (including the
  postponement rule that can move the fast to the next day), and any **festival**.
- It is computed for about 18 months ahead and refreshed nightly.
- Every screen reads the stored calendar — nothing is calculated while you wait.
- Two temples in different cities can show different Ekadashi dates when the astronomy says so.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/planner** (menu: **Meal plan**)
- **Bring a published ISKCON calendar** for the same city and year — for example vaisnavacalendar.info
  for Bengaluru. This test is only meaningful if you compare against a real one.
- **Ask the environment owner** whether the background worker is on. If it is off, the calendar may be
  empty; say so in your report as *environment*, not as a wrong calculation.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Meal plan** | A month grid. Each day shows its date and, underneath, the tithi name for that day |
| 2 | Find the days marked **Ekadashi** | They are visually distinct — a tinted cell and an **Ekadashi** label |
| 3 | Open your published calendar for the same month and city | Compare **every** Ekadashi date in the month |
| 4 | Note any date where they differ | Write down both: what the app says, what the published calendar says |
| 5 | Move forward with the **→** arrow through the next six months | Each month loads with tithi and Ekadashi marks; nothing is blank |
| 6 | Check the major festivals — Janmashtami, Gaura Purnima, Radhastami, Govardhan Puja | Each appears on the day the published calendar gives, named on the cell |
| 7 | Move back to a past month | The calendar is there too |
| 8 | Move a long way forward — 18 months or more | Record where it stops. Beyond about 18 months, empty is expected |
| 9 | Sign in as `ikms.temple-admin.2@trading4good.org` (the Mumbai temple) and open its planner for the same month | Compare its Ekadashi dates with Bengaluru's. They may be the same or differ by a day; either is possible — record what you see, with the dates |
| 10 | Check a day where the fast is postponed to Dvadashi, if the published calendar shows one this year | The app marks the same day the published calendar marks |

## It passes if

- [ ] Every day in the visible month shows a tithi.
- [ ] Ekadashi days are marked and are visually obvious.
- [ ] Ekadashi dates match a published ISKCON calendar for the same city, for at least a full year — **including postponements**.
- [ ] Named festivals fall on the published dates.
- [ ] The calendar is populated for months ahead, and for past months already covered.

## Watch out for

- **This is the test where being precise pays.** A one-day difference is not a rounding error; it is a temple fasting on the wrong day. Record every mismatch as a table of *date — app — published*.
- An empty calendar. First ask whether the worker is running. If it is, and the calendar is still empty for a temple created days ago, that is a Blocker.
- Tithi names that look wrong or untranslated (raw codes rather than names).
- The two temples showing *identical* calendars in every case may be correct, or may mean the location is being ignored. Note it either way; the team can check against the coordinates.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT029-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
