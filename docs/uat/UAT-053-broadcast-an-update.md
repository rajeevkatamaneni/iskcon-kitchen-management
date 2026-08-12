# UAT-053: Broadcast an update to a shift

| | |
|---|---|
| **Feature area** | Workforce — one-off broadcast |
| **Technical stories** | E6-S7 (one-off reminder broadcast) |
| **Roles exercised** | Kitchen staff, temple admin, volunteers |
| **Depends on** | UAT-049 |
| **Environment needs** | **A live message channel** for delivery. The compose, rate-limit and record steps work without one |

## What this feature is for

Plans change at the last minute: the gate is different, the start is delayed an hour, bring a knife.
Waiting for the next scheduled reminder is useless — the poster needs to reach everyone who is signed
up, right now.

## How it is supposed to work

- **Send update to all** sits on the shift's roster. You compose a short message, preview it, and send.
- It goes to everyone **signed up**, each on their preferred channel. The **waitlist is included only if
  you choose** — off by default.
- There is a **daily cap per shift** (three by default) so volunteers are not bombarded by an anxious
  poster. A Temple Admin can raise the limit.
- The message, who sent it, and each recipient's delivery status are recorded on the roster.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/volunteers** → open a shift with **at least two volunteers signed up and one on the
  waitlist** (set this up with UAT-049 and UAT-051)

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open the shift's roster | The volunteers signed up, the waitlist, and a **Send update to all** action |
| 2 | Press **Send update to all** | A compose box, an **Also send to the waitlist** tick that is **off**, and a **Send now** action |
| 3 | Type `Gate B today, not A. Come to the side entrance.` and send | Confirmation that it was sent |
| 4 | Look at the roster | An **Updates sent** count against the shift, and per-volunteer delivery status |
| 5 | *(Channel live)* Check the signed-up volunteers' phones | The message arrives, on each person's preferred channel |
| 6 | *(Channel live)* Check the **waitlisted** volunteer | They did **not** receive it — the tick was off |
| 7 | Send a second update with **Also send to the waitlist** ticked | Everyone, including the waitlist, receives it |
| 8 | Send a third update | Accepted |
| 9 | Send a **fourth** update on the same shift the same day | Refused: *This shift has reached today's limit for update messages* (`KMS-4935`), explaining the cap and that a Temple Admin can raise it |
| 10 | Sign in as the **temple admin** and look for where that daily limit is set | **Record what you find.** If there is no screen for it, say so plainly and list where you looked |
| 11 | Try to send a very long message (several hundred characters) | Record what happens — is it capped, refused, or truncated? |
| 12 | Try to send an **empty** message | Refused |
| 13 | Go to **/audit** as the temple admin | The broadcast appears with who sent it, when, and its content |
| 14 | As a **volunteer**, look for any way to send a broadcast | There is none — this belongs to the poster |

## It passes if

- [ ] A broadcast reaches everyone signed up, on their own channels.
- [ ] The waitlist is excluded by default and included when chosen.
- [ ] Delivery status per recipient is visible on the roster.
- [ ] The fourth broadcast in a day is refused with `KMS-4935` and an explanation.
- [ ] An empty message is refused.
- [ ] The broadcast is in the audit log with its content and its sender.
- [ ] Volunteers cannot broadcast.

## Watch out for

- **Step 10 is a real gap to check.** The refusal message tells the poster that a Temple Admin can raise the limit. If there is no screen where an admin can actually do that, the message is promising something the product does not offer — record it as Major, with the note "no screen found to change the broadcast limit". That is likely root cause R6.
- A broadcast that reaches volunteers on **other** shifts, or at another temple. Blocker.
- The rate limit counting across days rather than per day.
- A message sent successfully but with no record on the roster — the poster cannot then tell who was reached.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT053-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
