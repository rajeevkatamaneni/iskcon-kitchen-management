# UAT-084: When the grinder is due

| | |
|---|---|
| **Feature area** | Inventory — equipment servicing |
| **Technical stories** | E3-S10 (equipment servicing, and the record of it) |
| **Roles exercised** | Temple admin, kitchen staff |
| **Depends on** | UAT-027 (the equipment register), UAT-085 (the screen these rules are read on) |
| **Environment needs** | None |

## What this feature is for

The register UAT-027 built says what the temple owns and what state it is in. It does not say when
the wet grinder was last opened up, how often it is supposed to be, or who comes to do it. So the
temple finds out that the grinder was overdue by discovering it has stopped, in the middle of cooking
for four hundred people.

This adds three facts to each machine — **how often**, **when it last was**, and **who does it** —
and works the fourth out for itself: **when it is next due**.

## How it is supposed to work

- **A service is an event, not a date field.** Recording one writes a row — the date, who serviced
  it, what was done, what it cost — and that row cannot afterwards be edited or deleted. It is the
  stock ledger's philosophy and the condition trail's, applied to a third register.
- **Last serviced is read, never typed.** It is always the newest service row. There is no box
  anywhere that sets it directly, and there deliberately is not one: the moment somebody types over
  it, the service before has never happened.
- **The interval is a number and a unit** — days, weeks, months or years. *Every six months* and
  *every ninety days* are both things a real maintenance contract says. Months are **30 days** and
  years **365**, which is the arithmetic a contract means and not the arithmetic a calendar means.
- **The next date is worked out and never stored.** Newest service plus the interval. Where nothing
  has ever been serviced it counts from the **purchase date** instead — and says so, in as many
  words: *due 12 Mar 2027, from purchase, never serviced*, so nobody reads it as a service that
  happened. Where there is neither, it reads **Not scheduled**.
- **Two states, and the amber one is the one that does the work.** Past the date is **red**; inside
  the temple's warning horizon is **amber**. Red on the morning a service falls due would be a fire
  alarm — the point is to book the engineer while there is still time. The horizon is the temple's
  own setting, sitting beside the low-stock and expiry horizons UAT-080 covers, and it starts at
  **30 days**.
- **A scrapped machine is not overdue.** `SCRAPPED` is terminal. It drops out of every service
  calculation and out of the count on Today, whatever its dates say. A screen that nags every morning
  about a grinder thrown away last year is a screen its reader learns to ignore.
- **The service company is stored once**, in its own small list, and one company serves as many
  machines as it likes. Its phone number is typed once and read everywhere.
- **The serial number is optional, and unique when it is there.** Furniture has none. Two rows
  claiming the same serial are one machine entered twice.
- **Recording a service is an administrator's act; finding a broken machine is not.** Kitchen staff
  go on registering equipment, reading it and changing its condition — they are the ones standing in
  front of the grinder when it stops. Setting the interval, recording a service and keeping the
  company list belong to a Temple Admin.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin). Steps 27–31 need
  `ikms.kitchen-staff.1@trading4good.org` (kitchen staff).
- **Start at:** **/equipment** (menu: **Kitchen → Equipment**)
- **Set the scene:** UAT-027's three items should already be there — *Wet Grinder 10L*, *Steam
  Cauldron 200L* and the scrapped *Serving Trolley*. If they are not, register them first.
- **Write down today's date.** Every date in this test is arithmetic against it, and every date on
  screen should read the Indian way — *12 Sept 2026*, never *Sep 12, 2026*.

## Steps

### The service company, typed once

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Wet Grinder 10L** and start **Record a service** | A form: the date, the service company, what was done, what it cost |
| 2 | In **Service company**, look for `Bengaluru Kitchen Engineering` | It is not there — nobody has added it. There is a way to **add one without leaving the screen** |
| 3 | Add it: name `Bengaluru Kitchen Engineering`, phone `+91 98450 12345` | It is created and selected, and you are still on the grinder's page |
| 4 | Open **Steam Cauldron 200L** and start a service on it | `Bengaluru Kitchen Engineering` is offered in the list, **with its phone number already on it**. You are not asked to type the number a second time |

### A service is an event

| # | Do this | You should see |
|---|---|---|
| 5 | On **Wet Grinder 10L**, record a service dated **200 days ago**: company `Bengaluru Kitchen Engineering`, work done `Bearings replaced, drum realigned`, cost `4500` | It is accepted, and appears in a **Service history** section on the item |
| 6 | Read the history row | The date, the company, what was done, what it cost, **and who recorded it** — you |
| 7 | Look at the row for a way to **edit** or **delete** it | There is none. If you find one, that is the defect this whole design exists to prevent — record it as **Major** and write down exactly where the control is |
| 8 | Look at **Last serviced** on the record | **200 days ago** — the date of the row you just wrote |
| 9 | Hunt the whole item page, and the edit form, for a box that sets **Last serviced** directly | There is none. It is a readout only |
| 10 | Record a **second** service on the same grinder, dated **60 days ago**, work done `Annual check, no parts` | Both rows are in the history, **newest first**, and **Last serviced** now reads 60 days ago |
| 11 | Record a **third**, dated **300 days ago** — older than both | It is accepted and slots into the history in date order. **Last serviced** still reads **60 days ago**, because it is the newest row and not the last one entered |

### Six months, and ninety days

| # | Do this | You should see |
|---|---|---|
| 12 | Edit **Wet Grinder 10L** and set its service interval to **6 months** | Saved, and shown back to you as *every 6 months* — the unit you chose, not 180 days |
| 13 | Work out the next date yourself: last serviced 60 days ago, plus 6 months at 30 days each = 180 days | The screen reads a date **120 days from today**, and a status of **OK** — outside the 30-day horizon |
| 14 | Edit **Steam Cauldron 200L**, set its interval to **90 days**, and record a service dated **85 days ago** | Next service falls **5 days from today**. Both intervals are honoured on their own terms; ninety days has not been rounded into three months |

### Red, amber, and the temple's own horizon

| # | Do this | You should see |
|---|---|---|
| 15 | Look at the **Steam Cauldron 200L** row on **/equipment** | **Amber**, reading **Due in 5 days** — a sentence, not just a colour |
| 16 | Edit **Wet Grinder 10L** again and change its interval to **90 days** | Last serviced 60 days ago plus 90 is **30 days out** |
| 17 | Look at the grinder's row | Still **amber**, at the very edge of the 30-day horizon — **Due in 30 days** |
| 18 | Edit **Wet Grinder 10L** once more and set its interval to **30 days** | Last serviced 60 days ago plus 30 days is **30 days in the past**. The row goes **red** and reads **Overdue by 30 days** |
| 19 | Go to the temple's settings and find **how much notice you want before a service is due** (UAT-080 covers the two horizons beside it) | It reads **30 days**, and takes 1 to 365 |
| 20 | Set it to **3 days** and come back to **/equipment** | The **Steam Cauldron**, due in 5 days, is no longer amber — it is plain. The red grinder is still red: past the date is past the date, whatever the horizon says |
| 21 | Set the horizon to **60 days** and look again | The cauldron is amber again, and so is anything else falling inside two months. Changing the number really does change which machines are warned about |
| 22 | Put the horizon back to **30 days** | The list returns to what it read at step 18 |

### Never serviced, and never scheduled

| # | Do this | You should see |
|---|---|---|
| 23 | Register `Dough Kneader 25kg`, category `Machine`, location `Main kitchen`, condition **Good**, source **Purchased**, **acquired 170 days ago**, interval **6 months**, and record **no service at all** | Its next service is **10 days from today** — 170 days ago plus 180 |
| 24 | Read the next-service line on its item page, word for word | It says the date **and that it counted from the purchase**: *from purchase, never serviced*, or words that say the same. It must not read as though a service happened |
| 25 | Register `Prep Table 6ft`, category `Furniture`, location `Main kitchen`, **no acquired date** and **no interval** | Its service status reads **Not scheduled** — not a blank, not a dash, and not *Overdue* |
| 26 | Check the **Due soon** and **Overdue** filters, and the Today count (UAT-085) | `Prep Table 6ft` is in **neither**. A machine nobody has scheduled is not late |

### What kitchen staff can and cannot do

| # | Do this | You should see |
|---|---|---|
| 27 | Sign out. Sign in as `ikms.kitchen-staff.1@trading4good.org` and open **/equipment** | The screen is there, the red and amber rows are there, and the next-service dates are readable |
| 28 | Open **Wet Grinder 10L** | The full record and the service history are readable |
| 29 | Look for **Record a service** | **It is not offered.** Not greyed out with a shrug — not there |
| 30 | Press **Change condition**, set **Needs repair**, reason `Motor running hot` | **Accepted.** This is exactly what kitchen staff are for — they are the ones in front of the machine |
| 31 | Register a new piece of equipment as kitchen staff, and look at the form for a **service interval** box | Registering works. The interval box is **not** on the form for staff. Note whether anything else on the servicing side leaks through |

### What is refused

| # | Do this | You should see |
|---|---|---|
| 32 | Sign back in as the temple admin. Edit **Steam Cauldron 200L** and give it serial number `SB-200-2024` | Saved |
| 33 | Register a new item `Steam Cauldron 200L (spare)` and give it the **same** serial `SB-200-2024` | **Refused**, in plain language, quoting **`KMS-4015`**. Two rows claiming one serial are one machine entered twice |
| 34 | Register it again with the serial **left blank** | **Accepted.** A blank serial is allowed on any number of rows — furniture has none |
| 35 | Register `Prep Table 8ft` with the serial **also blank** | **Accepted.** Two blanks are not a duplicate |
| 36 | On **Steam Cauldron 200L**, record a service dated **tomorrow** | **Refused**, quoting **`KMS-4016`**. A service booked for next Tuesday has not happened |
| 37 | Record one on the cauldron dated **today** | **Accepted.** Today is not the future — and its next service moves out to 90 days from now. Leave the red grinder alone; steps 38–42 need it still red |

### A scrapped machine is not overdue

| # | Do this | You should see |
|---|---|---|
| 38 | Find the scrapped **Serving Trolley** (tick **Show scrapped items**), and give it an interval and a purchase date far enough back that it would be years overdue | Record what the form even lets you do here |
| 39 | Untick **Show scrapped items** and read the list | The trolley is not on it, and nothing about it is counted |
| 40 | Filter by service status **Overdue** | The trolley is **not** in the results, however overdue its arithmetic makes it |
| 41 | Look at the overdue count on **Today** (UAT-085) | The trolley is not in the number. Count the red rows on **/equipment** by hand and check the two agree |
| 42 | Now **scrap** the red **Wet Grinder 10L** — change its condition to **Scrapped**, reason `Replaced by the new machine` — and reload **/equipment** and **Today** | The overdue count **goes down by one**. A machine that has been thrown away stops being nagged about the moment it is thrown away |

## It passes if

- [ ] Recording a service writes a row that cannot afterwards be edited or deleted, carrying who recorded it.
- [ ] **Last serviced** always equals the newest service row, and there is no way to set it directly.
- [ ] An interval of six months and one of ninety days both produce the right next date, and neither is rounded into the other.
- [ ] A machine never serviced derives its next date from the purchase date **and says that is what it did**.
- [ ] A machine with neither a service nor a purchase date reads **Not scheduled**, and is in no warning count.
- [ ] Past the date is red; inside the temple's horizon is amber; **changing the horizon changes which**.
- [ ] A `SCRAPPED` machine is in no service calculation and no overdue count, whatever its dates say.
- [ ] One service company serves several machines, and its phone number is typed once.
- [ ] A duplicate serial number is refused with `KMS-4015`; a blank one is allowed on any number of rows.
- [ ] A service dated in the future is refused with `KMS-4016`; one dated today is not.
- [ ] Kitchen staff can register equipment and change its condition, and are offered no way to record a service or set an interval.

## Watch out for

- **Any way at all to change a service row after it is written.** An edit pencil, a delete, an
  undo, a re-save that overwrites — this is the one thing the design is built to prevent, and any
  route through it is a **Major** defect. Write down exactly how you did it.
- **A next-service date that reads as a service.** Step 24 is the whole point: *due 12 Mar 2027* on
  a machine nobody has ever opened up is a lie by omission unless the screen says where the date came
  from. If the words are missing, record it as Major, not cosmetic.
- **Months quietly becoming calendar months.** Six months from 31 December should land 180 days
  later, not on 30 June. If the arithmetic disagrees with yours, write down both dates — the story
  is explicit that contract months are 30 days.
- **A machine that goes red on the morning it falls due, with no amber before it.** The amber state
  is the one that gets the engineer booked; red on the day is too late to be useful.
- **A scrapped machine still counted anywhere** — the list, a filter, the Today nudge, an export.
  Steps 38–42 look for it in four places; check any fifth you can think of.
- **The service company list growing a duplicate** every time somebody adds one from a different
  machine. Add the same company twice from two items and see what happens.
- Whether a service can be recorded against a machine with **no interval set**. It is a reasonable
  thing to do — the temple serviced it, interval or not — but record what the product does.
- Dates reading *Sep 12, 2026* or `2026-09-12` rather than *12 Sept 2026*, and money without Indian
  digit grouping — **₹4,500**, not ₹4500 in a list of thousands.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT084-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
