# UAT-047: Staff profiles and the weekly schedule

| | |
|---|---|
| **Feature area** | Workforce — staff scheduling |
| **Technical stories** | E6-S1 (staff profiles and weekly schedule) |
| **Roles exercised** | Temple admin, kitchen staff |
| **Depends on** | UAT-008 |
| **Environment needs** | The **change notification** to the affected staff member needs a live channel; the schedule itself works without one |

## What this feature is for

Full-time kitchen staff work a pattern — Head Cook on mornings, Prep on afternoons — and everyone
needs to know who is on when. This is that pattern, plus the exceptions that real life produces: a day
off, a swapped shift, a festival week.

## How it is supposed to work

- Only people with the **Kitchen staff** role can have a work schedule.
- Each staff member has an **employment record** carrying their job title (E6-S8) and a **weekly
  template**: for each day, either working hours or Off.
- **Per-date exceptions** override the template for a single day, without disturbing the pattern.
- The affected staff member is notified when their schedule changes.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/staff-schedule** (menu: **Staff schedule**)
- You need the staff hired in UAT-008 (Gopal Das — Head Cook, Yamuna Devi Dasi — Store Manager, and
  Ramesh Kumar — Housekeeping, who has no app account at all).

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Staff schedule** | *Who works when — the weekly pattern, with per-date exceptions.* Everyone hired in UAT-008 is already on the grid with their job title |
| 2 | Look for an **Add staff** form | There is **none** — a **Staff register** link instead. Somebody comes to be on this grid by being hired (UAT-008), and one screen owns that |
| 3 | Check who is on the grid | Gopal Das (Head Cook), Yamuna Devi Dasi (Store Manager), Ramesh Kumar (Housekeeping) — and yourself, since provisioning employed you |
| 4 | Note that **Ramesh Kumar is there** even though he has no app account | An employment record does not require a login; his hours are still the temple's business |
| 5 | Follow **Staff register**, hire somebody new, and come back | They appear on the grid, with seven days already there to edit |
| 8 | Open **Gopal Das** | A **Weekly template**: each day with a Working tick and a start and end time |
| 9 | Set Monday–Friday `06:00`–`14:00`, Saturday `06:00`–`11:00`, Sunday **Off**, and **Save template** | *Schedule saved; the staff member was notified* |
| 10 | Go back to the grid | The week view shows his hours across the days, and Sunday blank |
| 11 | Add a **date exception**: next Wednesday, `14:00`–`20:00`, note `Swapped with Yamuna for the festival` | *Exception saved; the staff member was notified* |
| 12 | Look at the grid for that week | Wednesday shows the exception hours, and the **template is unchanged** for every other Wednesday |
| 13 | **Remove** the exception | *Exception removed*, and the template hours return for that day |
| 14 | Page forward with **Next →** across a month boundary | The template and any exceptions render correctly on both sides |
| 15 | *(Channel live)* Check Gopal Das's messages | He was told about the change; Yamuna was **not** |
| 16 | Sign out; sign in as `ikms.kitchen-staff.1@trading4good.org` (Gopal Das) and look for his schedule | Record what you find — can a staff member see their own schedule anywhere, and where? |

## It passes if

- [ ] The grid shows everyone currently employed — including staff with no app account — and nobody who has left.
- [ ] This screen creates nobody; hiring is on the staff register.
- [ ] A weekly template can be set and shows correctly in the week grid.
- [ ] A date exception overrides one day only and can be removed.
- [ ] The grid renders correctly across a month boundary.
- [ ] *(If a channel is live)* Only the affected staff member is notified.

## Watch out for

- **Step 16 is a real question.** The story says staff should see their own schedule; if there is nowhere for Gopal Das to look, record it plainly — it is a coverage finding, not a bug.
- An exception that silently changes the template (check the following week's same weekday).
- Times stored in the wrong timezone — check that 06:00 shows as 06:00, not 00:30 or 11:30.
- A notification going to everyone rather than the person affected.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT047-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
