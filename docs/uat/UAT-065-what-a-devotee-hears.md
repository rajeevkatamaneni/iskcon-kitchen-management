# UAT-065: What a devotee hears, and what they can switch off

| | |
|---|---|
| **Feature area** | Communications — categories and preferences |
| **Technical stories** | E8-S1 (categories and preferences) |
| **Roles exercised** | Volunteer (devotee), temple admin |
| **Depends on** | UAT-008, UAT-066 |
| **Environment needs** | Email must be live to see a real unsubscribe link. Everything else can be checked without any channel |

## What this feature is for

Before this, a devotee had one switch: contacted, or not at all. That is one setting too few. Somebody
who does not want the newsletter still wants to be told their shift moved — and making them choose
teaches them to turn *everything* off, which silences the reminders the kitchen depends on.

So every message now has a **kind**, and a devotee can decline the kinds the temple chose to send
while still getting the ones that follow from something they did themselves.

## How it is supposed to work

- **Five kinds can be declined:** Newsletter · Festivals and events · Seva opportunities · Appeals for
  support · Temple notices.
- **One kind cannot:** *Reminders and receipts* — your shifts, changes to them, and confirmations of
  what you have given. It is shown on the preferences screen anyway, so nobody has to wonder.
- There is also a single **"nothing optional at all"** switch. It overrides the individual ones while
  it is on; turning it back off restores what you had chosen before, rather than turning everything on.
- Every optional email carries an **unsubscribe link that works without signing in**, and the mail
  client's own Unsubscribe button works too.
- A message that is *not sent* because of a preference is recorded as suppressed, with the reason —
  it is never silently dropped.

## Before you start

- You need at least one **sent** communication from UAT-066, or run this alongside it.
- **Devotee account:** `ikms.volunteer.1@trading4good.org`
- **Admin account:** `ikms.temple-admin.1@trading4good.org`

## Steps

### The devotee's own screen

| # | Do this | You should see |
|---|---|---|
| 1 | Sign in as the devotee. Open **Profile** (your account) | A **Communications** section under the channel and consent settings |
| 2 | Read the list | All six kinds, each with a line saying what it is. Five have a switch; **Reminders and receipts** says it is always sent and has no switch |
| 3 | Turn **Newsletter** off | It saves immediately — no separate Save button to forget |
| 4 | Reload the page | Still off |
| 5 | Turn on **"Stop all optional messages"** | Every optional kind now shows as off |
| 6 | Turn that blanket switch **back off** | **Newsletter stays off**, and the other four come back on. Your earlier choice was not thrown away — this is the step most likely to be wrong |
| 7 | Check that **Reminders and receipts** was never affected by any of it | Correct — it cannot be turned off from anywhere |

### It actually stops the message

| # | Do this | You should see |
|---|---|---|
| 8 | Leave **Newsletter** off. Sign in as the admin and send a **Newsletter** (UAT-066) | The audience count **excludes** this devotee |
| 9 | Open the sent message's recipient list | The devotee is not on it |
| 10 | Now send a **Temple notice** (a kind the devotee did not decline) | The devotee **is** in the count and on the list |
| 11 | As the admin, cause an operational message: edit a shift the devotee signed up for, or cancel it (UAT-048) | They are notified. A declined newsletter must never silence this |
| 12 | Have the devotee turn **everything** off, then repeat step 11 | They are **still** notified |

### The link in the email

| # | Do this | You should see |
|---|---|---|
| 13 | *(Email live)* Open the newsletter the devotee received | A footer: who it is from, a **Stop receiving these** link, and a line saying shift reminders and receipts are not affected |
| 14 | Look at the mail client's own header area | Gmail shows its own **Unsubscribe** next to the sender — that comes from a header, not the footer link |
| 15 | Click the footer link **while signed out** | A page naming exactly what it will stop, with a button. It does **not** unsubscribe merely by being opened |
| 16 | Confirm | It says what was stopped. Check the devotee's preferences screen — that kind is now off |
| 17 | Edit the link — change one character of the token — and open it | Refused. It must not unsubscribe anybody |
| 18 | Take the link from *this* devotee's email and try it while signed in as a **different** devotee | It affects the person it was issued for, never whoever is holding it |

### What the temple can see

| # | Do this | You should see |
|---|---|---|
| 19 | As the admin, open the sent newsletter's recipient list | Anybody suppressed is shown with a reason — *opted out* is different from *never consented* |
| 20 | Open **Audit log** | The send is recorded. Nobody's individual preference is — that is the devotee's business, not the log's |

## It passes if

- [ ] A devotee can decline each of the five optional kinds, and cannot decline reminders and receipts.
- [ ] The blanket switch overrides the individual ones; turning it off restores the earlier choices rather than everything.
- [ ] A declined kind is genuinely not sent, and is not counted in the audience.
- [ ] An operational message reaches somebody who has turned off everything else.
- [ ] The unsubscribe link works with no session, and a tampered one is refused.
- [ ] An unsubscribe link only ever affects the person it was issued for.
- [ ] Suppressions are shown with a reason, not silently dropped.

## Watch out for

- **Step 6 is the one to look at hardest.** Turning the blanket switch off and having *everything* come back on — including the newsletter they declined separately — would put somebody back on a list they had left.
- **Step 12 is the one that matters most.** If a devotee who muted everything stops getting shift reminders, that is a **Blocker**: the kitchen finds out by being short-handed.
- Step 15: a page that unsubscribes on load rather than on the button. Mail scanners follow links.
- The footer link and the mail client's own Unsubscribe button should agree about what they stop.
- Turning a switch and navigating away immediately — is it saved?

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT065-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
