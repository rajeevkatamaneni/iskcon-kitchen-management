# UAT-080: How much notice you want

| | |
|---|---|
| **Feature area** | Temple settings — the two warning horizons |
| **Technical stories** | E5-S1 D2 (the contract-end horizon, and why both moved together) · E3-S1 (stock expiry warning) |
| **Roles exercised** | Temple admin; kitchen staff (to prove the refusal) |
| **Depends on** | UAT-022 (stock), UAT-028 (a gift of goods, which is how you get a batch with an expiry date), UAT-037 (vendors and their contract end dates) |
| **Environment needs** | None |

## What this feature is for

Two things in this application warn you before a date arrives: a **batch of food nearing its use-by
date**, and a **vendor agreement running out**. How much notice is useful is not the same answer for
both, and it is not the same answer for every temple. Seven days is enough warning to cook a sack of
flour before it turns. It is nowhere near enough to renegotiate a supply agreement, get a quote from
anybody else, or take the decision to a committee that meets once a month.

Until now both were fixed at seven days in the code, so no temple could say otherwise. Now the temple
says.

## How it is supposed to work

- **Settings** carries a **Warnings** section with two boxes, both in days:
  - **Notice before stock expires** — **7** unless the temple has changed it.
  - **Notice before a vendor contract ends** — **30** unless the temple has changed it.
- **Both are 1 to 365.** Anything outside that is refused, on the screen *and* by the server. Zero
  would warn on the morning the thing has already expired, which is not advance notice; a year warns
  about everything a temple holds or has signed, which is the same as warning about nothing.
- They are **one section and one Save**, because they were one shared number until now. Moving one
  and forgetting the other is exactly what that arrangement was avoiding.
- **Both only put a badge on a screen.** No vendor is dropped from a picker, nothing is removed from
  the shopping list's suggestions, and no batch is written off. Changing either number changes which
  rows are badged, and nothing else.
- Only a **Temple Admin** can see or change them.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/settings** (menu: **Temple** → **Settings**)
- **Set the scene**, because a fresh temple has no dated stock and no dated agreement:

  1. Record a **gift of goods** (UAT-028) of **Rice**, with an **Expiry** date **5 days from
     today**. Note today's date, and work out that expiry date on a calendar before you type it.
  2. Record a **second** gift of **Toor Dal**, with an **Expiry** date **20 days from today**.
  3. On **/vendors**, open **Sri Balaji Provisions** and set **Contract ends** to a date **20 days
     from today**. Save.
  4. Open **Nandini Dairy Agency** and set **Contract ends** to a date **300 days from today**. Save.

- **Write down the four dates you used.** Every step below is arithmetic against them.

## Steps

### The section itself

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Settings** and scroll down | A section headed **Warnings**, under it: *How much notice you want before a date runs out on you.* |
| 2 | Read the two boxes | **Notice before stock expires**, with **7** in it, and the word **days** beside it. **Notice before a vendor contract ends**, with **30** in it |
| 3 | Read the hint under each | Stock: *Batches closer than this are badged on Inventory.* Contract: *Enough time to renegotiate, or to find somebody else.* |
| 4 | Read the line under both boxes | *Both only put a badge on a screen. Nothing is dropped or written off.* |

### What it refuses

| # | Do this | You should see |
|---|---|---|
| 5 | Put **0** in the stock box | Under it, in red: **A warning is between 1 and 365 days.** The **Save** button goes dead |
| 6 | Put **366** in it | The same message, and Save still dead |
| 7 | Put **-5** in it | The same message |
| 8 | Type the word `seven` into it | The same message. A warning horizon is a whole number of days, not a word |
| 9 | Put **7** back, then do steps 5 to 8 again on the **contract** box | The same message under that box, and Save dead each time. The two are checked the same way |
| 10 | Put valid numbers back in both | Save comes alive again |

### It changes which batches are badged

| # | Do this | You should see |
|---|---|---|
| 11 | Set **Notice before stock expires** to **3**, leave the contract box alone, and press **Save** | **Saved.** appears under the section |
| 12 | Go to **/inventory** | **Neither** Rice nor Toor Dal is badged **Expiring soon** — 5 days and 20 days are both further out than 3 |
| 13 | Go back to **/settings**, set it to **7**, Save, and return to **/inventory** | **Rice** is now badged **Expiring soon**. **Toor Dal** is not |
| 14 | Read the summary line above the list | It counts what is badged — *1 with stock expiring soon* |
| 15 | Open **Rice** (**/inventory** → **Rice**) | The badge is on the item, and on the batch itself in the batch list |
| 16 | Set the box to **30**, Save, and return to **/inventory** | **Both** Rice and Toor Dal are badged, and the summary line now says **2** |
| 17 | Check what the badge has *not* done | Both batches are still counted in the on-hand total, still usable, still cookable. A warning is a warning |

### It changes which vendors are badged

| # | Do this | You should see |
|---|---|---|
| 18 | With the contract box still at **30**, go to **/vendors** | **Sri Balaji Provisions** carries an amber badge reading **Contract ends in 20 days**. **Nandini Dairy Agency** carries none — 300 days is well outside the horizon |
| 19 | Open **Sri Balaji Provisions** | The same warning on the vendor's own page, and under it: *They are still active and can still be ordered from. Renew the agreement, or make them inactive and say why* |
| 20 | Go back to **/settings**, set the contract box to **10**, Save, and return to **/vendors** | The badge on **Sri Balaji Provisions** is **gone**. Twenty days is further out than ten |
| 21 | Set it to **330**, Save, and look again | **Both** vendors are now badged, Nandini with **Contract ends in 300 days** |
| 22 | While both are badged, check what the badge has *not* done | Both vendors are still **Active**. Both still appear in the vendor picker on a purchase order, and the shopping list still suggests them (UAT-038, step 5) |
| 23 | Set the contract box back to **30** and Save | **Saved.** |

### It sticks, and it is the admin's

| # | Do this | You should see |
|---|---|---|
| 24 | Reload **/settings** | The two boxes read what you last saved — **7** and **30** — not the defaults |
| 25 | Sign out; sign in as `ikms.kitchen-staff.1@trading4good.org` and look at the menu | There is **no Settings** item |
| 26 | While still signed in as kitchen staff, type **/settings** into the address bar | **Not your page** — *You don't have access to this part of the app. Ask your temple administrator.* No horizons are shown, even for a moment |
| 27 | Still as kitchen staff, open **/inventory** and **/vendors** | The badges the admin's settings produced are **there** — a horizon is the temple's, not one person's view of it |

## It passes if

- [ ] Settings carries a **Warnings** section with both boxes, opening at **7** and **30** days.
- [ ] Anything outside 1–365 — including a negative number and a word — is refused, and Save is dead while it stands.
- [ ] Changing the stock horizon changes which batches are badged **Expiring soon**, and the count above the list agrees.
- [ ] Changing the contract horizon changes which vendors carry the contract warning.
- [ ] Nothing is dropped, filtered, removed or written off by either number — a badge appears or it does not.
- [ ] Both values survive a reload.
- [ ] Kitchen staff are neither offered Settings nor allowed onto it, but do see the badges.

## Watch out for

- **A badge that changes something.** This is the one thing worth watching for hardest. If a vendor
  stops appearing in a picker, or drops off the shopping list's suggestions, because their contract
  end date came inside the horizon, that is a **Blocker** — a date somebody typed months ago must
  never be what silently changes tomorrow's shopping.
- **A batch disappearing from stock** because its expiry came inside the horizon. Same fault, same
  severity. The badge says *look at this*, never *this is gone*.
- **The two numbers moving together.** They are separate settings that share one Save. Change one,
  save, and check the other did not follow.
- **A refusal that only the screen makes.** If you can get a value outside 1–365 saved by any route
  — pasting, the arrows on the number box, a phone keyboard — write down exactly how. The server
  should refuse it with **`KMS-4001`** (*Some of the information entered isn't valid*) even if the
  screen lets it through.
- **Batches with no expiry date at all.** Most stock has none. Those must never be badged, at any
  horizon.
- A vendor with **no contract end date**: never badged, at any horizon.
- The word **days** missing beside a box. `30` alone could be anything.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT080-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
