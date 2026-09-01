# UAT-033: An outside catering commitment

| | |
|---|---|
| **Feature area** | Meal planning — catering context |
| **Technical stories** | E4-S4 (meal plan across four contexts) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-032 |
| **Environment needs** | None beyond UAT-032's |

## What this feature is for

Temples cook for outside events — a wedding, a corporate order, a community programme. Those meals use
the same kitchen and the same store room as the temple's own cooking, so they belong in the same plan,
but they carry something extra: a client, a venue, and a delivery commitment somebody has promised.

## How it is supposed to work

- Catering is a **day type you choose explicitly** — it is never guessed, because a catering booking is
  a commitment, not a calendar consequence.
- A catering meal carries **client details**: who it is for, and where it is going.
- Upcoming catering commitments are listed together, so nobody discovers a booking on the morning.
- Its ingredients count against stock exactly like temple cooking does.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/planner**

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Press **+** on a date about ten days out | The *Plan a meal* panel |
| 2 | Set Day type to **Catering** | The Catering client and Venue fields become the relevant ones |
| 3 | Choose slot **Lunch**, recipe **Khichdi**, servings `400`, client `Sharma Family Wedding`, venue `Jayanagar Community Hall` | Accepted |
| 4 | Press **Add to plan** | The meal appears on the day, marked as catering |
| 5 | Scroll below the month grid | An **Upcoming catering** table: Date, Client, Recipe, Servings, Venue — with your booking in it |
| 6 | Add a second catering booking for a different date and client | Both appear in the table, in date order |
| 7 | Plan an ordinary (non-catering) meal on the same day as a catering booking | Both show on the cell; only the catering one appears in the Upcoming catering table |
| 8 | Set Day type to **Catering** but leave the client blank | Record what happens — is it refused, or does a catering commitment save with no client? |
| 9 | Cancel one catering booking | It leaves the Upcoming catering table |
| 10 | Check a **past** catering date | Record whether past commitments still show in the table. They should not clutter "upcoming" |
| 11 | Check the ingredient sufficiency badge on the 400-serving catering meal | It is calculated the same way as any other meal (UAT-034) |

## It passes if

- [ ] Catering is chosen explicitly and captures a client and venue.
- [ ] Catering commitments are listed together, in date order, and only future ones.
- [ ] A cancelled catering booking leaves the list.
- [ ] Catering meals count against stock like any other meal.

## Watch out for

- **Step 8 matters.** A catering commitment with no client name is not much of a commitment. If it saves without one, record it as Major and note that the story asks catering to carry client details.
- Catering meals not appearing in the shortfall/order calculations. A 400-serving booking that the shopping list ignores is a serious defect — cross-check in UAT-038.
- The Upcoming catering table showing bookings from a past date, or from another temple.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT033-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
