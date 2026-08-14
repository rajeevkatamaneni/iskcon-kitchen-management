# UAT-004: Platform operations and health

| | |
|---|---|
| **Feature area** | Platform foundation — observability |
| **Technical stories** | E1-S11 (observability baseline), E1-S9 (background jobs) |
| **Roles exercised** | Platform operator (Super-admin) |
| **Depends on** | UAT-001 |
| **Environment needs** | None. The worker reading tells you whether the background worker service is alive — that is the point of it |

## What this feature is for

The platform is run by one person, part-time. The likeliest incident at this scale is not a crash —
it is **silent failure**: messages quietly not being delivered while everything looks fine. This page
is the minute-a-day check: is the platform up, and is anything failing to reach people?

## How it is supposed to work

- **System health** is read live: is the database reachable, and is a **background worker** alive. Jobs run in their own service, so the API cannot answer that from itself — it reads the worker's check-in from the shared job store.
- **Notification metrics** are platform-wide totals — how many messages were sent and how many failed
  today — each with a seven-day pulse, split into two-hour windows, so *when* something failed is
  visible, not just how many.
- These are aggregate counts across all temples, deliberately **not** a per-temple drill-in: a
  platform operator has no business reading one temple's messages. Deeper trends and alerting live in
  the cloud monitoring tools, not here.

## Before you start

- **Sign in as:** `ikms.super-admin.1@trading4good.org` (platform operator)
- **Start at:** **/operations**
- **Ask the environment owner:** is the background worker service deployed and running? Write the
  answer down before you start — step 3 should agree with it.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Operations** from the menu | A page headed *Operations* with two areas: **System health** and **Notification metrics** |
| 2 | Read **Database** | **Reachable**, in green |
| 3 | Read **Background worker** | **Running** when a worker is alive. *Not responding* means it has stopped checking in — that is the silent failure this page exists to catch. *Never started* means one has never run |
| 4 | Read the small print under System health | It says the reading is live from `/health`, and that trends and alerts live in Cloud Monitoring |
| 5 | Look at **Sent today** and **Failed today** | Each shows a number (or a dash if unknown) above a small pixel chart, with weekday labels underneath ending in **Today** |
| 6 | Read the one-line hint under each tile | *Sent* = handed off to a channel. *Failed* = no channel accepted it, so the person was not reached |
| 7 | Hover or tab onto a chart | It is described for a screen reader — daily totals, and that the last column is today |
| 8 | Refresh the page | The numbers reload without error |
| 9 | Confirm what is **not** here | No per-temple picker, no list of individual failed messages, no donation or money figures |

## It passes if

- [ ] The page loads for a platform operator and shows both system health and notification metrics.
- [ ] Database reads *Reachable*; the background worker reads *Running* when one is deployed.
- [ ] Both metric tiles show a number and a seven-day pulse ending at *Today*.
- [ ] The explanations of *Sent* and *Failed* are in plain language, with no internal jargon (no story numbers, no "Epic", no class names).
- [ ] Nothing on this page exposes a single temple's business data.

## Watch out for

- The message "Couldn't reach the health endpoint. The API may be down." — if you see that while the
  rest of the app works, note it as a Major defect.
- Any figure that is obviously wrong: a *Failed today* count higher than *Sent today*, or numbers that
  change on every refresh without anything happening.
- Internal vocabulary leaking into the copy. This page has had that fault before; it should read as
  ordinary English throughout.
- If the environment has never sent a message (likely, if channels are stubbed), both counts being
  **0** with an empty pulse is correct — an empty field should read as *quiet*, not broken.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| **UAT004-1** | 2–4 | To see the worker's state at a glance | The state is present but reads as noise; the explanatory line is too long to follow | Minor — **OPEN**, analysis below |

**UAT004-1 — System health is hard to read.** Reported by Rajeev, 2026-08-14, on the live site.
Two separate problems, both in presentation rather than data:

1. **The pairing is invisible.** Database and Background worker sit in a wrapping list, so the eye
   reads a row of labels (*Database   Background worker*) and then a row of values (*Reachable
   Running*). Rajeev looked at a screen that said "Background worker — Running" and reported not
   seeing it at all. The information is there; the layout does not deliver it. Fix: pair each label
   with its value as one unit, so each reads as a single fact rather than a column of headings.

2. **The explanatory line is too long.** Current text: *"Live from /health. The worker is what runs
   reminders, digests and the calendar; if it stops, nothing scheduled happens. Trends and alerts
   live in Cloud Monitoring."* Three ideas in one sentence, and the third is internal plumbing an
   operator learns once and never needs on screen. Proposed replacement, pending Rajeev's approval:
   **"Live from `/health`. If the worker stops, nothing scheduled runs."** — the one consequence
   that matters, in eleven words. The Cloud Monitoring sentence is dropped.

*Analysis saved 2026-08-14; implementation deliberately deferred until the Meal plan / calendar
discussion concludes, so both sets of changes ship together.*

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
