# UAT-078: Where the schedule is short of hands

| | |
|---|---|
| **Feature area** | Workforce — crew coverage on the staff schedule |
| **Technical stories** | E6-S15 (where the schedule is short of hands) · E6-S1 (the grid it changes) · E4-S7 (the crew figure the planner records) |
| **Roles exercised** | Temple admin, kitchen manager, kitchen staff (to prove the refusal) |
| **Depends on** | UAT-008 (hired staff), UAT-047 (the weekly grid), UAT-032 (planning meals), UAT-048 and UAT-049 (a volunteer on a shift) |
| **Environment needs** | None. The notification when somebody's hours change needs a live channel; nothing in this test does |

## What this feature is for

The staff schedule used to show only **who is in**. What each day actually **needs** sat on the meal
planner, where a gap was visible only to somebody who happened to open the right meal on the right
day. So a Thursday three weeks out with nobody to cook dinner was something you discovered rather
than something you saw.

Both halves are now on one screen: every column of the week says how many people short it is, and the
short days in the next thirty are listed underneath, so a gap three weeks away can be seen without
paging forward to it.

## How it is supposed to work

- A new **Short of hands** row sits in the foot of the grid, **above** *In that day* — what the day
  needs first, then who is in, because that is the order the question is asked in.
- The column headers carry **the date** under the weekday name, because a shortfall somebody is about
  to act on wants a date, not a weekday.
- Every day is in exactly one of **four** states, and each is a different sentence:

  | The day says | What it means |
  |---|---|
  | **No meals** | Nothing is planned that day |
  | **Crew not set** | Meals are planned, but nobody has said how many people they take. **This is not covered** |
  | **Covered** | The meals asked for a number and there are at least that many people in |
  | **N short** | The worst-off meal on that day is N people short, and the cell names it |

- **The day's shortfall is the deepest meal's, not the sum.** Two cooks splitting the morning and the
  evening make a comfortable-looking day and can still leave dinner three short.
- **Colour follows shortfall, never head count**, and it never carries the meaning alone: every
  coloured cell also says its shortfall in words and names the meal.
- **Staff and volunteers count the same** towards the shortfall — a meal is satisfied when enough
  people are there, whichever kind they are — and the two are still reported apart underneath.
- Under the grid, a **Short of hands in the next 30 days** list, each entry naming the day, how many
  short, and the meal with what it has against what it asked for. It also says out loud how many days
  ahead have meals with **no crew figure**, rather than staying quiet and reading as an all-clear.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/staff-schedule** (menu: **People** → **Staff schedule**)
- **Set the scene:**

  1. On the grid, give **Gopal Das** and **Yamuna Devi Dasi** a weekly template of `06:00`–`14:00`
     **Monday to Saturday**, and **Sunday off** (UAT-047). That is two people in on a weekday and
     nobody in on Sunday.
  2. On **/planner**, plan these meals. The **People needed** box is **step 4 of the composer, "Who
     will run it"** — after the preparations:

     | When | Kind | Preparation | People needed |
     |---|---|---|---|
     | The next **Tuesday** | Lunch | Khichdi | **2** |
     | The next **Wednesday** | Lunch | Khichdi | **6** |
     | The same **Wednesday** | Dinner | Khichdi | **4** |
     | The next **Sunday** | Lunch | Khichdi | **3** |
     | The next **Thursday** | Lunch | Khichdi | **leave it empty** |
     | Leave **Friday** with nothing planned at all | | | |
  3. Plan one more short day about **three weeks out**: any weekday, Lunch, **People needed 8**.

## Steps

### The four states, in one week

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Staff schedule** and page to the week you planned | The grid as before, with each person's hours |
| 2 | Look at the **column headers** | The weekday **and the date under it** — `Wed` with `3 Sep` beneath, not `Wed` on its own |
| 3 | Look at the **foot** of the grid | Two rows: **Short of hands** first, then **In that day**. In that order |
| 4 | **Tuesday** — 2 people in, 2 needed | **Covered** |
| 5 | **Wednesday** — 2 people in; Lunch needs 6 and Dinner needs 4 | **4 short**, and under it **Lunch — 2 of 6**. Not 6 short (the two meals are not added together), and not 2 short (the shallower meal does not win) |
| 6 | **Thursday** — a meal is planned, but nobody said how many people it takes | **Crew not set**. **It must not read as Covered**, and it must not be coloured as though the day were fine |
| 7 | **Friday** — nothing planned | **No meals** |
| 8 | **Sunday** — 3 needed, everybody off | **3 short**, with **Lunch — 0 of 3**, and drawn in the strongest tone on the row. A meal that named a number and has nobody at all is a different thing from a meal that is short |
| 9 | Compare each coloured cell with its words | Every coloured cell **also says the shortfall in words and names the meal**. Turn the screen greyscale in your head — nothing is lost |
| 10 | Compare the **Short of hands** row with the **In that day** row underneath | The rostered figure in a cell (*2 of 6*) agrees with the staff plus volunteers the row below reports for that day. **If the two rows disagree about the same day, that is a defect** |

### Volunteers count, and changing the roster moves the figure

| # | Do this | You should see |
|---|---|---|
| 11 | Post a volunteer shift on the short **Wednesday** and have `ikms.volunteer.1@trading4good.org` sign up for it (UAT-048, UAT-049). Come back to the grid | Wednesday reads **3 short**, not 4 — a volunteer counts towards the meal exactly as a staff member does |
| 12 | Look at **In that day** for that Wednesday | It still reports **2 staff** and **1 volunteer** separately. One shortfall number, the two kinds of person still shown apart |
| 13 | Click **Gopal Das's** Wednesday cell and **mark him off** | *Recorded as approved leave*, and **both** rows move together: the shortfall goes up by one and the head count goes down by one. Neither is left on the old figure |
| 14 | Undo it (revoke the leave) | Both rows go back |
| 15 | Page **forward** and **back** across a month boundary | The footer follows the week on screen; the dates under the day names follow it too |

### The thirty-day list

| # | Do this | You should see |
|---|---|---|
| 16 | Scroll below the grid | A section headed **Short of hands in the next 30 days** |
| 17 | Read the entries | Each names the day in full (*Wednesday, 3 September*), **how many short**, and the meal with its figures (*Lunch — 2 of 6*) |
| 18 | Find the day you planned **three weeks out** | It is in the list, **without you having paged the grid to it**. That is the whole point of the list |
| 19 | Check the day where nobody at all is rostered | It is marked more strongly than the merely short ones |
| 20 | Read the line under the list | It says how many **other** days ahead have meals planned with **no crew figure set**, so there is nothing to measure their rosters against. Your Thursday should be counted there |
| 21 | Give every short day enough people (raise the roster, or lower **People needed** on the planner) and reload | **No day in the next 30 is short of the crew its meals ask for** — and the "no crew figure" line is still there if any such day remains |
| 22 | Look for a **month** view of coverage | There is none, and there should not be. The question is *where am I short*, and the week's footer plus the thirty-day list is the answer |
| 23 | Sign out; sign in as a **Kitchen manager** (appoint one on **/staff** if you have not) and open **/staff-schedule** | The manager sees the grid, the footer and the list — this is their screen too |
| 24 | Sign out; sign in as `ikms.kitchen-staff.1@trading4good.org` and type **/staff-schedule** | **Not your page** |

## It passes if

- [ ] The **Short of hands** row is in the foot of the grid, **above** *In that day*.
- [ ] The column headers carry the date under the weekday.
- [ ] A day with **no meals** is its own state and is not drawn as covered.
- [ ] A day whose meals carry **no crew figure** reads **Crew not set** and never as covered.
- [ ] A day with enough people reads **Covered**, and never shows a negative shortfall.
- [ ] The day's shortfall is the **deepest meal's**, and the meal is named with its rostered and required figures.
- [ ] A day where a meal has **nobody at all** is distinguished from a day that is merely short.
- [ ] A volunteer counts towards the shortfall exactly as a staff member does, and the two are still reported apart.
- [ ] Every coloured cell also states its shortfall in words.
- [ ] Marking somebody off moves the shortfall **and** the head count together.
- [ ] The rostered figures agree with the *In that day* row.
- [ ] The thirty-day list names the day, the shortfall and the meal, and finds a day the grid is not showing.
- [ ] The list says how many days ahead have no crew figure, rather than staying silent.
- [ ] Kitchen staff cannot reach the screen.

## Watch out for

- **A "Crew not set" day drawn as covered, or in a reassuring colour.** Nobody has said what those
  meals take; a month of unplanned days that looks green is the exact failure this design exists to
  prevent. Major.
- The day's shortfall being the **sum** of its meals' shortfalls (Wednesday reading *6 short*). The
  same cook can be short at two meals.
- The **In that day** row and the **Short of hands** row disagreeing about the same day, especially
  after marking somebody off. There is meant to be exactly one answer to "how many are in on
  Thursday".
- A day's colour tracking **how many people are in** rather than how short it is. A busy day with
  eight cooks and a quiet day with eight cooks should not look the same as each other only by
  accident — they should look the same because neither is short.
- Dates under the column headers going missing on a narrow window or a phone.
- The thirty-day list starting from the **week on screen** rather than from today. Page the grid back
  into last month and check the list has not moved.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT078-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
