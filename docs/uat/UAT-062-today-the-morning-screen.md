# UAT-062: Today — the temple's morning screen

| | |
|---|---|
| **Feature area** | Daily operations |
| **Technical stories** | E4-S8 (this screen) · E4-S7 (meals and ready-by times) · E3-S3 (reorder levels) · E6-S2 (volunteer shifts) · E7-S7 (donations ledger) · E5-S2 (purchase orders) |
| **Roles exercised** | Temple Admin · Kitchen Staff · Volunteer (refusal) |
| **Depends on** | Run **after** UAT-032 (meal planning), UAT-023 (stock), UAT-048 (shifts) — this screen summarises what those create |
| **Environment needs** | A temple with at least one meal planned for today, one stock item below its reorder level, and one volunteer shift today or tomorrow |

## What this feature is for

This is the screen a temple admin or kitchen staff member sees first thing in the morning. Everything
on it already existed somewhere in the product — meals, stock, shifts, giving, deliveries — but you
had to visit five screens to assemble a picture of the day. Now it is one.

It **reads and never writes**. Every action on it is a link to the screen that owns the action, so the
dashboard can never disagree with the page it links to.

## How it is supposed to work

- Signing in as a temple admin or kitchen staff member lands you here.
- Four tiles answer four questions: **how much are we cooking**, **what are we about to run out of**,
  **who is missing from a shift**, and **what has come in**. Each tile is a link to the screen that
  acts on it.
- A **fasting day today or tomorrow** shows as a banner across the top, because it changes every menu
  on that day. Tomorrow counts too — menus are settled the day before.
- **Today's meals** are listed in the order they must be ready, with their state.
- **Deliveries expected today** are listed with their purchase order.
- Kitchen staff do **not** see the giving figure — they hold no donations permission. It is absent,
  not zero.

## Before you start

- **Sign in as** `ikms.temple-admin.1@trading4good.org`.
- Have ready: a meal planned for today (UAT-032), a stock item below its reorder level (UAT-023), and
  a shift today or tomorrow that is not full (UAT-048).

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Sign in | You land on **Today**, not your profile |
| 2 | Read the top line | Today's date, and a summary — how many plates across how many meals |
| 3 | Look at the four tiles | Plates today, Items below par, Shifts unfilled, Given this month. Each shows a real figure from your temple |
| 4 | Check the figures against the source screens: plates against Meal plan, items against Inventory, spots against Volunteer shifts, giving against the Donations ledger | Every figure agrees with the screen it came from |
| 5 | Click each tile | Each takes you to the screen that acts on it |
| 6 | Look at **Meals in the kitchen** | Today's meals, earliest ready-by first, each with its time and state (Planned / Cooked) |
| 7 | Mark a meal cooked in the planner, come back and reload | Its state here has changed |
| 8 | Look at **Deliveries** | Purchase orders due today, with vendor and PO number |
| 9 | Find a date where today or tomorrow is a fasting day (the planner shows which), and open Today on it | A banner across the top names it and offers a link to the plan |
| 10 | Sign in as kitchen staff (`ikms.kitchen-staff.1@trading4good.org`) | Same screen, but **no giving tile** — and nothing that reads as "₹0" |
| 11 | Sign in as a volunteer and type **/today** in the address bar | A plain refusal; you are not shown the screen |
| 12 | Open Today on a phone | The tiles stack, everything is readable, nothing is cut off |

## It passes if

- [ ] Admins and kitchen staff land here after signing in; volunteers do not.
- [ ] Every figure matches the screen it summarises.
- [ ] Every tile links to the screen that acts on it.
- [ ] Meals are in ready-by order and show the right state.
- [ ] A fasting day today or tomorrow shows as a banner.
- [ ] Kitchen staff see no giving figure at all.
- [ ] The screen works on a phone.

## Watch out for

- **A figure that disagrees with its own screen.** That is the most important defect this test can
  find — record both numbers and where you saw them.
- A tile that is not clickable, or one that leads somewhere unrelated.
- A zero that should be an absence — kitchen staff must not see "Given this month ₹0".
- A meal that is missing from the list, or listed in the wrong order.
- Slowness. This screen loads once each morning, often on a phone: if it takes several seconds,
  record it with a rough count of seconds.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT062-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
