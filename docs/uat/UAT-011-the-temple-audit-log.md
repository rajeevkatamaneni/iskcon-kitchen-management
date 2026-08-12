# UAT-011: The temple audit log

| | |
|---|---|
| **Feature area** | Platform foundation — audit trail |
| **Technical stories** | E1-S7 (audit log framework) |
| **Roles exercised** | Temple admin |
| **Depends on** | UAT-009 (so there is history to read) |
| **Environment needs** | None |

## What this feature is for

Some actions carry weight: changing what someone can do, overriding a religious rule, correcting
stock, recording money. The audit log is the temple's permanent, unalterable record of those acts —
who did what, when, and what it was before. It exists so that anything sensitive is always
explainable, and so nobody has to take anyone's word for it.

## How it is supposed to work

- The log records the actor, the action, what was affected, the before and after state, and the time.
- It **cannot be edited or deleted**, by anyone, through any screen — including by the temple
  administrator reading it.
- It is scoped to your own temple. A platform operator has no cross-temple feed; if one ever reads a
  temple's log, that act is itself recorded in that temple's log.
- It can be filtered by date range and by action.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/audit** (menu: **Audit log**)
- The more of UAT-002, 008, 009, 018, 025 and 031 you have done, the richer the log will be. At
  minimum, finish UAT-009 first.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Audit log** | A table with columns **When**, **Who**, **Action**, **Details**, most recent first, and a note that narrowing the date range shows older entries |
| 2 | Look for the oldest entry | *Temple provisioned* — recorded when the platform operator created this temple in UAT-002, naming them as the actor |
| 3 | Look for the entries from UAT-009 | *Role changed* entries naming you as the actor, the person affected, and the role before and after |
| 4 | Use the **Action** filter and choose one action, then press **Apply** | Only entries of that action remain |
| 5 | Set the **From** date to today and apply | Only today's entries |
| 6 | Set **From** to a date before the temple existed and apply | The full history returns, including *Temple provisioned* |
| 7 | Look for any way to edit or delete an entry | There is none — no edit button, no delete, no inline editing |
| 8 | If you have done UAT-018 (sattvic override), look for that entry | An override entry naming the recipe, the prohibited ingredient and the reason given |
| 9 | If you have done UAT-025 (large stock adjustment), look for it | An adjustment entry with the reason recorded |
| 10 | Sign out; sign in as `ikms.kitchen-staff.1@trading4good.org` and type **/audit** | *Not your page* — the log is for temple leadership |

## It passes if

- [ ] The log shows who did what, when, for the sensitive actions taken so far.
- [ ] Temple provisioning appears as the earliest entry, attributed to the platform operator.
- [ ] Role changes show the role before and after.
- [ ] Filtering by date and by action works.
- [ ] There is no way to change or remove an entry.
- [ ] Kitchen staff and volunteers cannot open the log.

## Watch out for

- **Missing entries.** If you made a change in an earlier test and it is not here, that is the defect worth finding — name the exact action and the test it came from.
- Entries that show internal identifiers (long strings of letters and numbers) instead of a person's name or a recipe's name. Note as Minor; the log should be readable.
- An empty log when you know changes have been made — a Blocker.
- Refused actions should also be recorded (for instance, an attempt to change your own role). If you tried that in UAT-009, look for it here.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT011-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
