# UAT-036: The Ekadashi guard

| | |
|---|---|
| **Feature area** | Meal planning — Ekadashi violation flagging |
| **Technical stories** | E4-S6 (Ekadashi violation flagging, amended 2026-08-31) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-029 (a working calendar), UAT-015 (recipes), UAT-032 (the planner as it now is) |
| **Environment needs** | **Background worker on** — without the calendar there are no Ekadashi days to guard |

## What this feature is for

On Ekadashi, devotees fast from grains and beans. Planning khichdi on that day is a mistake that ought
to be caught while planning, not discovered while serving. But it is a **warning, not a bar**: temples
legitimately cook grains on Ekadashi for children and non-fasting visitors. The temple decides — the
system makes sure the decision is deliberate and recorded.

The guard now works in **two places, answering two different questions**. The preparations list
decides **what is offered** on a fasting day; the check when the meal is saved decides **what is
acknowledged**. Not offering the wrong thing is kinder than objecting to it after somebody has
finished their work — but a preparation can be edited after it was chosen, so the check at save stays
as the backstop.

## How it is supposed to work

- **On a fasting day the composer's preparations list opens already filtered** to preparations that
  suit the fast. A line above the list says why it is short, naming the day in the calendar's own
  words, and beside it is a button reading **Show grain preparations too**.
- Pressing that button puts the **whole** list back; the button then reads **Hide grain preparations**
  and puts it away again. The escape is beside the list, not behind a menu.
- **A preparation already on the meal stays visible whatever the filter says.** A meal being corrected
  may hold a grain dish somebody deliberately confirmed, and hiding it would put its amount, and the
  block that amount can place on saving, out of reach.
- **On a day that is not a fast there is no filter and no control.** Nothing new appears at all.
- **The check at save remains.** Saving a meal that holds a grain or bean preparation on an Ekadashi
  raises a confirmation naming the preparation and the offending ingredients (`KMS-4917`), with two
  ways out: proceed, or leave it out.
- Proceeding records an **acknowledgement** on the plan and badges the dish.
- **There is no silent bypass**: no route plans a grain dish on Ekadashi without passing that
  confirmation.
- Preparations that suit the fast pass without any warning at all.
- If a Temple Admin corrects the date (UAT-031), both halves follow the correction immediately.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/planner** — page forward to the next **Ekadashi** day (marked on the cell)
- You need **Khichdi** (rice and dal — a violation) and **Sabudana Khichdi** (suits the fast) from
  UAT-015.
- The meal form has four numbered steps: **1 what kind of meal**, **2 who is expected**,
  **3 preparations**, **4 who will run it**. Everything in this test happens in step 3 and at the
  **Save this meal** button.

## Steps

### The list opens filtered

| # | Do this | You should see |
|---|---|---|
| 1 | Find the next Ekadashi in the planner | The cell is marked as a fasting day |
| 2 | Open that day and start a new meal | The day's own name is on the screen — the calendar's words for it, such as *Pandava Nirjala Ekadasi* |
| 3 | Choose kind **Lunch** and put **150** adults in step 2 | The form fills in as usual |
| 4 | Go to step 3, **Preparations**, and **read the line above the list** | It names the day and then says **Grain and bean preparations are hidden.** — so a planner is told why the list is short rather than left to wonder |
| 5 | Look for **Khichdi** in the list | It is **not there** |
| 6 | Look for **Sabudana Khichdi** | It **is** there — a preparation that suits the fast is offered normally |
| 7 | Look beside that line | A button reading **Show grain preparations too** — in plain sight next to the list, not hidden in a menu |
| 8 | Press it | The **whole** list comes back, Khichdi included; the line now reads **Every preparation is listed.**; and the button now reads **Hide grain preparations** |
| 9 | Press it again | Back to the short list, and the button says **Show grain preparations too** again. It works both ways round |
| 10 | Look near the **Save this meal** button | A warning badge: *Fasting day — grain preparations will ask you to confirm* |

### The check at save

| # | Do this | You should see |
|---|---|---|
| 11 | With the filter **on**, choose **Sabudana Khichdi** and press **Save this meal** | It saves with **no warning at all** |
| 12 | Start another meal on the same day, press **Show grain preparations too**, choose **Khichdi**, and press **Save this meal** | **Blocked by a warning**: *Khichdi has grains or beans, and this is a fasting day*, and underneath **Contains Rice, Toor Dal** — the offending ingredients named. Two ways out: **Plan it anyway** and **Leave it out** |
| 13 | Press **Leave it out** | The warning closes and **nothing is planned** |
| 14 | Look at the day's meals | No Khichdi on it |
| 15 | Do it again and this time press **Plan it anyway** | The meal is saved |
| 16 | Look at the saved dish | It carries **grains on a fasting day, acknowledged** — informational, not accusatory |

### Correcting a meal that already holds a grain dish

| # | Do this | You should see |
|---|---|---|
| 17 | Open that saved meal to **correct** it | The form comes back with Khichdi on it |
| 18 | Look at the preparations list, with the filter **on** | **Khichdi is still visible**, because it is already on this meal — with its amount reachable. A preparation you cannot see is one you cannot correct |
| 19 | Change its amount and save | It saves, passing the confirmation again |

### An ordinary day, and a corrected one

| # | Do this | You should see |
|---|---|---|
| 20 | Plan Khichdi on an **ordinary** (non-Ekadashi) day | **No filter line, no button, no badge and no warning.** Nothing new appears on the screen at all |
| 21 | Ask an admin to run UAT-031 and mark an ordinary day as Ekadashi. Then open that day and start a meal | The list opens **filtered**, the line names the day, and the button is there |
| 22 | Try to save Khichdi on it | The confirmation appears on that day too |
| 23 | Ask the admin to remove the override, then open the day again | No filter, no button, no warning — the guard follows the corrected calendar both ways |
| 24 | Look for any way to plan a grain dish on Ekadashi **without** seeing the confirmation | There should be none |

## It passes if

- [ ] On a fasting day the preparations list **opens filtered**, and says why, naming the day as the calendar names it.
- [ ] **Show grain preparations too** restores the whole list, and the button says so both ways round.
- [ ] A grain preparation already on the meal being corrected stays visible while the filter is on.
- [ ] An ordinary day gets no filter, no explanation line and no button.
- [ ] Saving a grain or bean preparation on Ekadashi raises a confirmation naming the ingredients (`KMS-4917`).
- [ ] Declining plans nothing.
- [ ] A preparation that suits the fast raises no warning.
- [ ] Proceeding plans the meal and records the acknowledgement visibly.
- [ ] A calendar correction changes both the filter and the confirmation immediately, in both directions.
- [ ] There is no route that plans a grain dish on Ekadashi silently.

## Watch out for

- **The filter being the only guard.** Press **Show grain preparations too**, plan Khichdi, and the
  confirmation must still appear. If the filter has replaced the check rather than sitting in front of
  it, that is a Major defect — a recipe can be edited after it was chosen.
- **The escape button missing on a fasting day.** A filter with no way out would turn a warning into a
  bar, which is exactly what this feature is designed not to be.
- The line above the list naming the wrong day, or saying nothing about why the list is short.
- The warning listing ingredients that are **not** grains or beans, or missing one that is. The seeded
  list is Rice, Wheat Flour, Semolina, Toor Dal, Moong Dal, Chana Dal, Urad Dal.
- **If the list ever fails to load**, the design says the whole list is shown **and the escape button
  is withheld** — a picker that has silently stopped filtering must not also offer to unfilter. You
  cannot easily force this by hand; if you happen to see a full list on a fasting day with no button,
  that is the intended behaviour and not a defect.
- The acknowledgement badge not surviving a reload.
- Whether a **Temple Admin** sees different behaviour from kitchen staff here. They should not; this
  rule is the same for both.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT036-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
