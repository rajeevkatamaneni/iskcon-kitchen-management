# UAT-049: A volunteer signs up for seva

| | |
|---|---|
| **Feature area** | Workforce — volunteer signup |
| **Technical stories** | E6-S3 (volunteer signup) |
| **Roles exercised** | Volunteer |
| **Depends on** | UAT-048 |
| **Environment needs** | The **confirmation message** needs a live channel; signing up works without one |

## What this feature is for

Offering seva should take seconds on a phone. A volunteer sees what is open, taps once, and is on the
roster — and the temple knows exactly how many hands it has for tomorrow morning.

## How it is supposed to work

- Volunteers browse **Available shifts**, grouped by date, each showing how full it is.
- Signing up is **atomic**: if two people tap at the same moment for the last spot, exactly one gets it
  and the other is offered the waitlist. Nobody is ever over-booked.
- A confirmation goes out on the volunteer's preferred channel.
- The shift then appears in **My shifts**.
- Signing up for two shifts that overlap in time is allowed, with a warning — families share duties, so
  it is not blocked.

## Before you start

- **Sign in as:** `ikms.volunteer.1@trading4good.org` (volunteer)
- **Start at:** **/shifts** (menu: **Available shifts**)
- For step 8 you will also need `ikms.volunteer.2@trading4good.org` in a second browser.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Available shifts** | *Offer seva — claim a spot in seconds.* Cards grouped by date: title, time, location, and how many spots are filled |
| 2 | Find **Morning vegetable chopping** (capacity 3) | It shows 0 of 3 filled, with a **Sign up** action |
| 3 | Press **Sign up** | *You're signed up. Thank you for your seva!* and the card now shows **You're in** and 1 of 3 |
| 4 | Open **/my-shifts** | The shift is listed with its date, time and location |
| 5 | *(Channel live)* Check your messages | A confirmation naming the shift, its date and time |
| 6 | Go back to **Available shifts** and press **Sign up** on the same shift again | Refused: *You're already signed up for this shift* (`KMS-4930`), pointing you to My shifts |
| 7 | Sign up for **Prasadam serving** (capacity 1) | You're in; it now shows 1 of 1 — full |
| 8 | In a second browser, sign in as `ikms.volunteer.2@trading4good.org` and open the same shift | It shows **full**, and offers **Join waitlist** instead of Sign up (that is UAT-051) |
| 9 | As volunteer 2, force a signup on the full shift if you can find any way | Refused: *This shift is already full* (`KMS-4931`), suggesting the waitlist |
| 10 | As volunteer 1, sign up for a shift whose time **overlaps** one you already have | Allowed, with a warning: *You're signed up — note this overlaps another shift you're on* |
| 11 | Ask staff to cancel a shift you are signed up for, then try to sign up again | Refused (`KMS-4928`) |
| 12 | Try to sign up for a shift that has already **started** | Refused: *This shift has already started* (`KMS-4929`) |
| 13 | Sign in as `ikms.kitchen-staff.1@trading4good.org` and open **/shifts** | Not available to staff — they see **My shifts** but signing up for more is a volunteer action |
| 14 | As staff, open **/volunteers** and the shift's roster | Your signups are visible to the person who posted the shift |

## It passes if

- [ ] Available shifts show date, time, location and how full each is.
- [ ] Signing up works in one tap and puts the shift in My shifts.
- [ ] A second signup for the same shift is refused (`KMS-4930`).
- [ ] A full shift offers the waitlist instead of signup, and cannot be over-booked (`KMS-4931`).
- [ ] An overlapping signup warns but is allowed.
- [ ] Cancelled and already-started shifts cannot be joined.
- [ ] The poster can see who signed up.

## Watch out for

- **The capacity count going wrong.** Sign up and release a few times and check the "filled" figure each time. A count that drifts is a Major defect — the temple would plan on the wrong number of hands.
- Two people getting the last spot. Try step 8 genuinely simultaneously if you can (two phones, count to three).
- The confirmation arriving on the wrong channel — compare against the preference set in UAT-010.
- A shift appearing in Available shifts after it has started.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT049-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
