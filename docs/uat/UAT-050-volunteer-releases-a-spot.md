# UAT-050: A volunteer releases a spot

| | |
|---|---|
| **Feature area** | Workforce — signup release |
| **Technical stories** | E6-S4 (signup release) |
| **Roles exercised** | Volunteer |
| **Depends on** | UAT-049 |
| **Environment needs** | None to release; the waitlist notification is UAT-051 |

## What this feature is for

Life happens. A volunteer who cannot come should be able to say so easily — because the alternative is
a silent no-show, and the temple finding out at 6am that it is short-handed. Releasing early is always
better than not turning up, so there is no penalty and no lock-out window.

## How it is supposed to work

- **Can't make it? Release my spot** sits on My shifts, with a confirmation that explains the spot goes
  to the waitlist.
- Releasing frees the capacity immediately and triggers the waitlist (UAT-051).
- It can be done any time up until the shift starts. Afterwards it is refused, gently.
- The release is visible to the person who posted the shift on the roster.

## Before you start

- **Sign in as:** `ikms.volunteer.1@trading4good.org` (volunteer)
- **Start at:** **/my-shifts** (menu: **My shifts**)
- You should be signed up for at least two shifts from UAT-049.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **My shifts** | *The seva you've signed up for, and where you're waiting.* Your shifts listed |
| 2 | Find **Morning vegetable chopping** | A clear action: *Can't make it? Release my spot* |
| 3 | Press it | A confirmation explaining the spot will go to the waitlist |
| 4 | Cancel the confirmation | You are still signed up; nothing changed |
| 5 | Press it again and confirm | The shift disappears from My shifts immediately |
| 6 | Open **/shifts** | The shift shows one more spot free (2 of 3 filled instead of 3 of 3, or 0 of 3 instead of 1 of 3) and offers **Sign up** again |
| 7 | Sign up again, then release again | It works repeatedly — no lock-out |
| 8 | Sign in as `ikms.kitchen-staff.1@trading4good.org`, open **/volunteers** and the shift's roster | The release shows on the roster, and the filled count reflects it |
| 9 | As the volunteer, try to release a spot on a shift that has **already started** | Refused, gently: *This shift has already started* (`KMS-4929`) |
| 10 | Try to release a shift you are **not** signed up for (open one from Available shifts) | There is nothing to release; if forced, refused with `KMS-4932` |
| 11 | Release a spot on a shift with people on its **waitlist** | The first waitlisted volunteer is promoted — check this in UAT-051 |

## It passes if

- [ ] Releasing is offered on My shifts with a confirmation that explains what happens.
- [ ] Cancelling the confirmation changes nothing.
- [ ] Releasing removes the shift from My shifts immediately and frees the spot.
- [ ] A volunteer can sign up and release repeatedly with no penalty.
- [ ] Releasing after the shift has started is refused politely (`KMS-4929`).
- [ ] The poster can see the release on the roster.

## Watch out for

- The spot **not** actually freeing up — the shift still showing as full after a release. Major defect: the seva goes unfilled.
- A released shift still appearing in My shifts until a refresh.
- Wording that shames the volunteer for releasing. The tone should be neutral and easy; if it reads as a telling-off, note it as Minor — it affects whether people release or just fail to turn up.
- Whether the shift's poster is told about a release. Record what you observe; the story asks for it to be visible on the roster, not necessarily messaged.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT050-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
