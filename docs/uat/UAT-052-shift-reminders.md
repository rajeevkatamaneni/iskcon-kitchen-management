# UAT-052: Shift reminders

| | |
|---|---|
| **Feature area** | Workforce — scheduled reminders |
| **Technical stories** | E6-S6 (scheduled shift reminders) |
| **Roles exercised** | Volunteer, kitchen staff |
| **Depends on** | UAT-049 |
| **Environment needs** | **Background worker on AND a live message channel.** Without both, no reminder can be sent — run only the roster-status steps and mark the rest *blocked by environment* |

## What this feature is for

A volunteer who signs up two weeks before a festival will not remember on the morning. The reminder is
what turns a signup into a person who actually arrives — which is the whole point of the volunteer
module.

## How it is supposed to work

- Reminders are scheduled per signup, at the offsets configured on the shift (UAT-048) — for example
  48 hours and 24 hours before.
- They go out on **each volunteer's own preferred channel** (UAT-010), with fallback to another channel
  if the first fails.
- Releasing a spot **cancels** the pending reminders. Changing the shift's time **reschedules** them.
- Someone who signs up after an offset has already passed simply misses that one, silently.
- The shift's roster shows the poster whether each volunteer's reminder was **sent, delivered, or
  failed** — this is the answer to "did people actually get told?".

## Before you start

- **Confirm with the environment owner:** is the background worker running, and is at least one message
  channel live? Write the answer in your report.
- **Sign in as:** `ikms.volunteer.1@trading4good.org`
- **Set up a short-notice shift** (as kitchen staff): post a shift starting in **about three hours**,
  with reminder hours `2`. That way you can see a reminder inside one test session rather than waiting
  a day.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | As **kitchen staff**, post a shift starting in ~3 hours with reminder offset `2` | Posted |
| 2 | As **volunteer 1**, sign up for it | You're in |
| 3 | As staff, open the shift's **roster** in **/volunteers** | The volunteer is listed, with a **Reminders** column showing the reminder is pending or scheduled |
| 4 | Wait until the offset passes (about an hour) | A reminder arrives on the volunteer's preferred channel, naming the shift, its date and time, and where to go |
| 5 | Check the roster again | The reminder shows as **sent** (and **delivered** once the channel confirms) |
| 6 | As the volunteer, change your preferred channel in **/profile** and sign up for a second shift with an offset ahead | The next reminder arrives on the **new** channel |
| 7 | Sign up for a shift, then **release** it before the offset | **No reminder is sent** for that shift |
| 8 | As staff, change a shift's **start time** after someone has signed up | The reminder is rescheduled to match the new time — check the roster, then check when it arrives |
| 9 | Sign up for a shift whose offset has **already passed** (a shift in one hour with a 24-hour offset) | You are signed up; no reminder for the missed offset, and no error |
| 10 | Join a **waitlist** rather than signing up, and wait past an offset | **No reminder** — waitlisted volunteers are not reminded |
| 11 | Be promoted from a waitlist before a later offset (UAT-051) | You now receive the remaining reminders like anyone else |
| 12 | Look at the roster for a volunteer whose message failed (use a bad phone number) | The failure is visible on the roster, with the fallback attempt recorded |

## It passes if

- [ ] A reminder arrives at the configured offset, on the volunteer's chosen channel.
- [ ] The roster shows sent / delivered / failed per volunteer.
- [ ] Releasing cancels the reminder.
- [ ] Changing the shift time reschedules it.
- [ ] A missed offset is skipped silently.
- [ ] Waitlisted volunteers get nothing; promoted ones get the remaining reminders.
- [ ] A failure is visible and a fallback is attempted.

## Watch out for

- **Do not pass this test on the roster alone.** The roster saying *sent* while no message arrives is precisely the silent failure the whole system is built to prevent. Verify on a real phone or inbox, or record it as unverified.
- Reminders arriving at the wrong time — check the offset arithmetic against the shift's start time and the temple's timezone.
- Duplicate reminders for one signup.
- A reminder that does not say **where** to go, or which shift it is for.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT052-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
