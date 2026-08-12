# UAT-048: Post a volunteer shift

| | |
|---|---|
| **Feature area** | Workforce — volunteer shift posting |
| **Technical stories** | E6-S2 (volunteer shift posting with reminder configuration) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-008 |
| **Environment needs** | Posting works without any channel. Reminders actually going out is UAT-052 |

## What this feature is for

Every day's seva needs hands: chopping, cooking, serving, washing. Posting a shift is how the temple
says what it needs and how many people it needs — and volunteers see it immediately.

## How it is supposed to work

- A shift has a **title**, a **date**, a **time window**, a **capacity**, a **location**, a description,
  and its own **reminder timings** — how many hours before it starts volunteers should be reminded.
  Reminder timing belongs to the shift, not to a global setting, because a 5am shift needs a different
  warning from an afternoon one.
- **Creating is publishing.** There is no separate publish step; volunteers see it at once.
- A shift can be **duplicated** to another date, carrying its settings, which is how a daily seva gets
  posted quickly.
- **Cancelling** a shift notifies everyone signed up and closes signups.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/volunteers** (menu: **Volunteers**)
- **Post these three shifts** — later tests use them:

| Title | Date | Time | Capacity | Reminders |
|---|---|---|---|---|
| Morning vegetable chopping | tomorrow | 06:00–09:00 | 3 | 24 |
| Prasadam serving | tomorrow | 11:30–14:00 | **1** | 48, 24 |
| Evening pot washing | day after | 19:00–21:00 | 2 | 24 |

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Volunteers** | *Post seva shifts; volunteers see them the moment they're created.* *No shifts posted* |
| 2 | Press **Post a shift** | A form: Title, Date, Capacity, Start, End, Location, **Reminder hours before (comma-separated)**, Description |
| 3 | Post **Morning vegetable chopping** from the table, location `Main kitchen`, reminder `24` | It appears in the list: Shift, When, Filled (0 of 3), Actions |
| 4 | Post **Prasadam serving**, capacity **1**, reminders `48, 24` | Posted, showing 0 of 1 |
| 5 | Post **Evening pot washing** | Three shifts listed |
| 6 | Try to post a shift with capacity `0` | Refused |
| 7 | Try to post one with the end time **before** the start time | Record what happens — refused, or accepted? |
| 8 | Try to post a shift **in the past** | Record what happens |
| 9 | Press **Duplicate** on Morning vegetable chopping and give a new date | A second shift on that date with the same title, time, capacity, location and reminder settings |
| 10 | Sign out; sign in as `ikms.volunteer.1@trading4good.org` and open **/shifts** | All the shifts you posted are visible immediately, with their capacity — no publish step was needed |
| 11 | Sign back in as staff. **Cancel** a shift; you are asked why | Give the reason `Kitchen closed for maintenance`; the shift is cancelled |
| 12 | As the volunteer again, look at that shift | It is gone from the available list, or clearly marked cancelled; you cannot sign up (`KMS-4928`) |
| 13 | As staff, edit a shift's reminder hours from `24` to `48, 24` | Accepted (whether the reminders are really rescheduled is UAT-052) |
| 14 | Sign in as `ikms.temple-admin.1@trading4good.org` and open **/volunteers** | The temple admin can post and manage shifts too |

## It passes if

- [ ] A shift can be posted with title, date, time, capacity, location and reminder hours.
- [ ] Volunteers see it immediately, with no publish step.
- [ ] Capacity of zero is refused.
- [ ] Duplicating carries all settings to a new date.
- [ ] Cancelling requires a reason and closes signups.
- [ ] Both kitchen staff and temple admin can post shifts.

## Watch out for

- **Steps 7 and 8 are deliberately unspecified.** A shift ending before it starts, or posted in the past, are things a tired person will do. Record exactly what the system does; if it accepts them, that is worth a Minor or Major finding depending on the consequences.
- Reminder hours entered as `24, 48` (out of order) or `abc`. Try both.
- A duplicated shift carrying over the **signups** as well as the settings — it should not.
- Cancelling a shift that people have already joined: do they get told? Note it here and confirm in UAT-053.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT048-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
