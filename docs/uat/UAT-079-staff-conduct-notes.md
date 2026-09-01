# UAT-079: Conduct notes on a staff record

| | |
|---|---|
| **Feature area** | Workforce — conduct notes |
| **Technical stories** | E6-S16 (conduct notes on an employment record) · E6-S8 (the employment record they sit on) |
| **Roles exercised** | Temple admin, kitchen manager, kitchen staff |
| **Depends on** | UAT-008 and UAT-064 (somebody hired to write about) |
| **Environment needs** | None |

## What this feature is for

Something happens in a kitchen — somebody walks out mid-shift, somebody covers for a colleague
without being asked — and six months later it matters. The staff form already had a **Notes** box,
but it is one line with no author and no date that anybody may overwrite, so what it holds is the
current opinion of whoever typed last, not a record of what happened.

A conduct note is the other thing: **written by the person who saw it, on the day they saw it, and
still there when it matters.** It cannot be edited and it cannot be deleted, by anybody, ever.

Because these are notes about colleagues who work in the same room, **only a Temple Admin can write
them or read them** — and the reading is the sensitive half. Somebody who may hire, pay and dismiss
does not get them thrown in with the roster.

## How it is supposed to work

- A **Conduct notes** panel on a staff member's record, newest first, each note showing **who wrote
  it and the moment they wrote it**, in the temple's own clock.
- **The permanence is stated above the Save control, before anything is pressed** — while somebody is
  still choosing their words, not after.
- **There is no edit and no delete.** A note written in error is corrected the way every other
  permanent record here is corrected: by adding another note that says so.
- **A note is a dated, attributed sentence and nothing else.** No rating, no severity, no category, no
  warning type, no acknowledgement.
- **An empty or whitespace-only note is refused** (`KMS-4012`).
- The old **Notes** field on the staff form is left exactly as it was, for the reminders and
  preferences it has always held. Nothing here reads it or writes it.
- The audit log records **that** a note was written and by whom — **never its words**, because the log
  answers to a different permission and a different audience.
- **Nothing connects a conduct note to the employment ban** raised when somebody is let go, in either
  direction.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/staff** (menu: **People** → **Staff**), then open **Gopal Das**
- **You will need a kitchen manager and a kitchen staff account.** On **/staff**, edit somebody's
  **App access** to **Kitchen manager** if the temple has none. Steps 14–16 use them.

## Steps

### Writing one

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Gopal Das's** staff record | Their details, and a panel headed **Conduct notes** |
| 2 | Read the panel before typing anything | **No conduct notes on this record** — said plainly, not an empty box with nothing above it |
| 3 | Look at what the panel offers | An **Add a note** box and a **Save note** button, and **no** rating, severity, category, warning type or acknowledgement anywhere |
| 4 | **Read what is written between the box and the button, before you press it** | *Once saved, a note can't be edited or deleted.* and *Your name and the date are saved with it.* **It must be above the button, not below it and not after saving.** If you have to press Save to learn this, write it down |
| 5 | Leave the box empty and look at **Save note** | It is **disabled** — there is nothing to save |
| 6 | Type **three spaces** and look at **Save note** | Still **disabled**. Whitespace is not a note, and nothing is sent to the server |
| 7 | Type `Left the evening shift an hour early on 29 August without telling the head cook.` and press **Save note** | The note is saved, **the box clears**, and the note appears in the list above |
| 8 | Read the saved note | **Your own name**, then the date and time it was written (*31 August 2026, 18:08*), then the words you typed, unchanged |
| 9 | Check the time | It is the **temple's** clock (India), not your computer's. If you are testing from outside India, this is the step that catches it |
| 10 | Add a second note: `Covered Yamuna's morning shift at no notice on 30 August.` | Two notes, **newest first** |
| 11 | Look for any way to **change** or **remove** either note | **There is none** — no edit link, no delete, no menu, nothing behind a long press. Look properly; this is the point of the feature |
| 12 | Reload the page, then sign out and back in | Both notes are exactly as written |
| 13 | Open **Gopal Das's** **Edit** screen | The same panel, with the same two notes, on that screen too |

### Who can see them

| # | Do this | You should see |
|---|---|---|
| 14 | Sign out; sign in as the **Kitchen manager** and open **/staff** | **Not your page** — a kitchen manager reaches no staff record at all, so there is no panel to see |
| 15 | Still as the kitchen manager, paste the address of **Gopal Das's** staff record | **Not your page** again. No note text is shown, not even for a moment while the page loads |
| 16 | Sign out; sign in as `ikms.kitchen-staff.1@trading4good.org` and do the same | The same refusal |
| 17 | Sign back in as the **temple admin**. Go to **/staff-schedule** and look at Gopal Das's row and his template page | **No conduct notes anywhere.** They live on the staff record and nowhere else |
| 18 | Look at the **staff register list** on **/staff** | No notes, no count of notes, no badge — nothing that leaks that somebody has one |

### What it is *not* connected to

| # | Do this | You should see |
|---|---|---|
| 19 | On Gopal Das's **Edit** screen, find the old one-line **Notes** field | It is still there, still editable, and still holds whatever it held (*prefers the early shift*). It is a **separate** thing from the panel, and changing it changes no conduct note |
| 20 | Open **/audit** and find the newest rows | A row per note, reading **`STAFF_CONDUCT_NOTE_ADDED`**, naming **you** and the time, and pointing at **Gopal Das** |
| 21 | Read that audit row carefully, including anything it expands to | **The words of the note are not in it.** Not in the note column, not in the before/after detail. If you can read the sentence you typed anywhere in the audit log, stop and write it down |
| 22 | **End Gopal Das's employment as Dismissed** (UAT-064) and read the form before you confirm | It asks for its **own** reason, in its own box. **It does not offer, suggest, quote or pre-fill any conduct note**, and there is no "include the conduct notes" option |
| 23 | Open his record again under **Former staff** | The two conduct notes are still there, unchanged, and nothing about the dismissal was written into them or taken out of them |

## It passes if

- [ ] A staff member with no notes says so plainly.
- [ ] A note comes back **dated, attributed and newest first**, in the temple's clock.
- [ ] **The permanence is stated above the Save control, before it is pressed.**
- [ ] An empty or whitespace-only note cannot be saved.
- [ ] A saved note **cannot be edited or deleted by anybody**, on either screen.
- [ ] Saving clears the box and re-reads the list.
- [ ] The panel offers a dated note and nothing else — no rating, severity, category or type.
- [ ] A **Kitchen manager** and **Kitchen staff** see no panel and are refused the record.
- [ ] Notes appear nowhere in the roster, the register list or the schedule.
- [ ] The old **Notes** field is untouched and unrelated.
- [ ] The audit log records that a note was written, and never its words.
- [ ] Letting somebody go picks up no conduct note.

## Watch out for

- **Any edit or delete route at all**, including one that only appears to the author, or only for a
  minute after saving. A note that can be changed is worth nothing, and finding one is a Blocker.
- The permanence warning appearing **after** the save, or as a confirmation dialog. It has to change
  what somebody writes, which means it has to be read first.
- **`KMS-4012`** — *A conduct note needs something written in it.* The screen is meant to make this
  message unreachable by refusing an empty note before it is sent. If you ever manage to see it on
  screen, write down exactly what you did — that is worth knowing either way.
- A note's text turning up somewhere it should not: the audit log, a staff export, an email, a
  dismissal form, another temple.
- The time on a note being your own computer's rather than the temple's. Check step 9 from outside
  India if you can.
- A note longer than about 4,000 characters. Paste a very long one and record what happens — it is
  meant to be refused rather than silently cut, because these records are permanent.
- Whether the **Temple Admin who wrote a note about themselves** is treated any differently. Record
  what you find; nothing says it should be.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT079-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
