# UAT-051: The waitlist promotes automatically

| | |
|---|---|
| **Feature area** | Workforce — waitlist and auto-promotion |
| **Technical stories** | E6-S5 (waitlist with auto-promotion) |
| **Roles exercised** | Volunteers (three of them), kitchen staff |
| **Depends on** | UAT-049, UAT-050 |
| **Environment needs** | The **"you're in" message** needs a live channel; the promotion itself works without one |

## What this feature is for

Without a waitlist, a released spot quietly becomes an empty shift — the release does the temple no
good at all. So when a shift is full, volunteers can queue, and the moment a spot opens the person at
the front is put in automatically and told. No approval step, no waiting for someone to notice.

## How it is supposed to work

- A full shift offers **Join waitlist** instead of Sign up, and the volunteer can see their **position**.
- When capacity frees — by a release, or by the capacity being raised — the **first person in the queue
  is promoted immediately**, and told they are in. There is no accept/decline step: it is automatic,
  because same-day backfills cannot wait for a handshake.
- The rest of the queue moves up.
- Someone who leaves the waitlist stops being eligible at once.
- Waitlisted people get **no shift reminders**; a promoted person joins the normal reminder flow.

## Before you start

- **You need three volunteers:** `ikms.volunteer.1@`, `.2@` and `.3@trading4good.org`, ideally in three
  separate browsers or private windows.
- **Use the capacity-1 shift**, **Prasadam serving**, from UAT-048.
- **Start at:** **/shifts**

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | As **volunteer 1**, sign up for **Prasadam serving** (capacity 1) | You're in; the shift is now full |
| 2 | As **volunteer 2**, open the same shift | It offers **Join waitlist**, not Sign up |
| 3 | As volunteer 2, press **Join waitlist** | You are on the waitlist; your **position** is shown |
| 4 | As **volunteer 3**, join the waitlist too | Position 2 |
| 5 | As volunteer 2, open **/my-shifts** | The shift appears under **On the waitlist**, with a **Leave waitlist** action |
| 6 | As volunteer 2, press **Join waitlist** again on the same shift | Refused: *You're already on the waitlist for this shift* (`KMS-4933`) |
| 7 | As **volunteer 1**, go to **My shifts** and **release** the spot | Released |
| 8 | As **volunteer 2**, refresh **My shifts** | You have been **promoted** — the shift now shows as a confirmed signup, not a waitlist entry |
| 9 | *(Channel live)* Check volunteer 2's messages | A clear "you're in" message naming the shift |
| 10 | As **volunteer 3**, check your position | You have moved up to position 1 |
| 11 | As staff, open the shift's roster in **/volunteers** | The roster shows who is signed up **and** who is waiting, in order |
| 12 | As volunteer 3, press **Leave waitlist** | You are removed |
| 13 | As volunteer 2, release the spot again | With nobody left on the waitlist, the spot simply becomes free — no error, and volunteer 3 is **not** promoted, having left |
| 14 | Try to join the waitlist for a shift that still has **free spots** | Refused: *This shift still has open spots* (`KMS-4934`) — sign up directly instead |
| 15 | Two people at once: with capacity 1 free, have volunteer 2 sign up while volunteer 3 releases another spot at the same moment | Exactly the right number of people end up on the shift. Never more than capacity, never two people promoted into one spot |

## It passes if

- [ ] A full shift offers the waitlist, and shows the volunteer their position.
- [ ] Releasing a spot promotes exactly the first person in the queue, automatically.
- [ ] The promoted volunteer's shift moves from waitlist to confirmed, and they are told.
- [ ] The remaining queue moves up.
- [ ] Leaving the waitlist removes eligibility immediately.
- [ ] Joining the waitlist for a shift with free spots is refused (`KMS-4934`).
- [ ] Capacity is never exceeded, even under simultaneous actions.

## Watch out for

- **Two promotions into one spot** — the worst failure here. Check the roster count after every promotion.
- A promotion that happens but is never communicated: the volunteer does not know they are expected. If no channel is live, note it as *unverified* rather than passing it.
- Positions that do not renumber after a promotion or a departure.
- A waitlisted volunteer receiving shift **reminders** (UAT-052). They should not — only confirmed volunteers do.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT051-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
