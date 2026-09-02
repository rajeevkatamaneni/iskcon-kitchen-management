# UAT-082: A deleted temple stops making work

| | |
|---|---|
| **Feature area** | Platform foundation — deletion takes the schedule with it |
| **Technical stories** | E1-S15 D13 (a deleted temple's scheduled jobs go with it) · E1-S9 (background jobs) |
| **Roles exercised** | Platform operator (Super-admin) |
| **Depends on** | UAT-002 (provisioning), UAT-003 (the export-then-delete procedure), UAT-004 (the Operations screen) |
| **Environment needs** | **Background worker on.** With it off there is no scheduled work to leave behind, and this test proves nothing. You also need **the person who set up the environment** for steps 9 and 13 — the worker's own log is the only place part of this is visible |
| **You will need** | A spreadsheet program, because deleting a temple requires taking its data export first |

## What this feature is for

Deleting a temple used to erase every row it owned and leave its **schedule** behind. The work a
temple's life queues up — build its calendar, generate that document, send that reminder — is stored
in a database of its own, and none of it carried the temple's name in a way the purge could see. So
after a deletion the worker went on picking those jobs up, failing them because the temple was gone,
and parking each failure in the job log. Permanently. Noise that reads exactly like a live incident
to whoever is on call at three in the morning.

Deleting a temple now takes its scheduled work with it, in the same breath — either the rows and the
schedule both go, or neither does.

## How it is supposed to work

- Deletion is unchanged in every way a person can see: export first, type the temple's own name,
  and it is gone (UAT-003). This adds one thing that happens underneath.
- The temple's **queued and scheduled work goes with it**, in the same transaction as its data. A
  temple whose rows went but whose schedule stayed is the defect, not a lesser kind of success.
- **The platform's own scheduled work is untouched.** The jobs that sweep every temple — the nightly
  shopping-list refresh, the low-stock digest, the reminder sweep — belong to no temple and must
  survive every deletion. This is the half of the behaviour most likely to break quietly, and most of
  this test is about it.
- **Other temples are untouched.** A deletion takes one temple's work, never a neighbour's.
- A job already **in flight** when the deletion happened stops on its own without recording a
  failure. Nobody needs to be told that a temple that no longer exists could not be worked on.
- Only a platform operator can delete a temple (`DELETE_TENANT`).

## Before you start

- **Sign in as:** `ikms.super-admin.1@trading4good.org` (platform operator)
- **Start at:** **/tenants**
- **Tell the person who set up the environment you are starting**, and ask them for two things:
  1. To have the **worker's log** open while you run this.
  2. The exact **time now**, by the server's clock, written down. Everything they check afterwards is
     "after this moment".
- **Do not delete the two temples from UAT-002.** Every other test in this pack needs them.

## Steps

### Make a temple that has work scheduled

| # | Do this | You should see |
|---|---|---|
| 1 | Create a temple (UAT-002): name `Job Sweep Temple`, Bengaluru coordinates, administrator `ikms.super-admin.2@trading4good.org` | It appears on **/tenants** |
| 2 | Note the moment it was created | Provisioning queues that temple's calendar to be built. That is the scheduled work this test is about |
| 3 | Sign out; sign in as `ikms.super-admin.2@trading4good.org` and open **/planner** for **Job Sweep Temple** | Within a few minutes the month grid carries **tithi names** under the dates. That is the proof the temple's scheduled work ran, or is queued to |
| 4 | Page forward two or three months | Calendar information there too — more of the same temple's work, still to come |

### Delete it

| # | Do this | You should see |
|---|---|---|
| 5 | Sign back in as `ikms.super-admin.1@trading4good.org` and open **Job Sweep Temple** on **/tenants** | Its page, with the **Data export** panel and the red *Delete this temple* panel |
| 6 | Take the data export, type `Job Sweep Temple` into the confirmation box, and delete it (UAT-003, steps 4–7) | The temple is deleted and you land back on **/tenants** |
| 7 | Look at **/tenants** | **Job Sweep Temple** is gone. The two temples from UAT-002 are still there, untouched |
| 8 | Note the exact time you pressed delete | Step 9 and step 13 both measure from it |

### Nothing of its keeps firing

| # | Do this | You should see |
|---|---|---|
| 9 | **Ask the environment owner** to search the worker's log for anything mentioning the deleted temple, from the moment in step 8 onward | **Nothing.** No `calendar-precompute` for it, no `generate-document`, no send. In particular **no repeating failure quoting `KMS-4401`** (*temple not found*) — that is the exact noise this change removes, and seeing it is the finding |
| 10 | Open **/operations** and read **System health** | **Database — Reachable**. **Background worker — Running.** Deleting a temple does not disturb the worker |
| 11 | Write down **Failed today** | The figure, now |
| 12 | Leave it **fifteen minutes**, doing nothing, then reload **/operations** | **Background worker** still **Running**, and **Failed today** has not climbed on its own |
| 13 | **Ask the environment owner** again, an hour or so later | Still nothing for the deleted temple. This matters because the failures used to come back on a retry, and then on the next schedule, forever |

### The platform's own work survives

| # | Do this | You should see |
|---|---|---|
| 14 | Sign in as `ikms.temple-admin.1@trading4good.org` and open **/planner** | The calendar is still there for the surviving temple, this month and the next |
| 15 | Open **/shopping-list** and press **Regenerate** (UAT-038) | It rebuilds. The shopping-list work still runs |
| 16 | Print or download a recipe card (UAT-019) | The PDF is generated. Document work still runs |
| 17 | Check that a shift reminder is still scheduled (UAT-052) | Still scheduled and still arriving |
| 18 | Sign in as `ikms.temple-admin.2@trading4good.org` — the **second** temple — and open **/planner** | Its calendar is intact too. One temple's deletion took nothing from another's |

### Do it again

| # | Do this | You should see |
|---|---|---|
| 19 | Create a second throwaway temple, `Job Sweep Temple Two`, wait until step 3's calendar appears, and delete it the same way | Deleted cleanly |
| 20 | Repeat steps 9 to 18 | The same answers. Once is luck; twice is the behaviour |
| 21 | Look at **/tenants** one last time | Both throwaway temples are gone; the two real ones are exactly as they were |

## It passes if

- [ ] A temple can be deleted exactly as UAT-003 describes — nothing about that is different.
- [ ] After the deletion, nothing scheduled for that temple ever fires again, at any point.
- [ ] No failure quoting `KMS-4401` appears for a deleted temple, once or repeatedly.
- [ ] The background worker stays **Running** through and after a deletion.
- [ ] The platform's own sweeping jobs — shopping list, documents, reminders, calendar — all still run afterwards.
- [ ] Other temples' scheduled work is untouched.
- [ ] It behaves the same the second time.

## Watch out for

- **A worker that stops running after a deletion.** That is the opposite failure — the sweep taking
  something it should not have. **Blocker.** Everything scheduled on the whole platform stops with
  it, so check step 10 immediately and steps 14–17 carefully.
- **A surviving temple losing its calendar, its reminders or its documents.** Also a Blocker, and the
  reason step 18 is in this test rather than left to trust.
- **The failures arriving late.** The old fault came back on a retry — sometimes minutes later,
  sometimes at the next scheduled hour. A clean log five minutes after the deletion is not the
  answer; step 13 is.
- **A deletion that half-succeeds.** If the temple disappears from **/tenants** but the environment
  owner still finds its work firing, that is the exact defect this change was for. Record the time of
  the deletion and the time of each firing.
- **`KMS-4941`** — *Take a data export before deleting this temple.* Correct behaviour, not a fault;
  it means you tried to delete before exporting.
- Steps 9 and 13 cannot be done from a screen. **The application has no page listing failed jobs**,
  so if the environment owner is unavailable, mark those two steps *not run* rather than passed. The
  rest of the test still stands on its own.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT082-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
