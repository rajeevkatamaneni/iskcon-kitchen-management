# UAT-085: The equipment screen

| | |
|---|---|
| **Feature area** | Inventory — the equipment screen |
| **Technical stories** | E3-S11 (the equipment screen) |
| **Roles exercised** | Kitchen staff, temple admin, volunteer |
| **Depends on** | UAT-027 (the equipment register), UAT-084 (the servicing rules this screen shows), UAT-062 (Today) |
| **Environment needs** | None |

## What this feature is for

The equipment register has been recording things since August and **nobody has ever been able to look
at it**. There was no page, no menu entry and no way in. This is the screen — a list, an item page,
and one line on the morning screen when something is past its service date.

## How it is supposed to work

- **Five columns on the list**, and only five: **Name**, **Location**, **Status**, **Next service**
  and **Service company**. The other eleven fields live on the item's own page. Everything visible at
  once would need a horizontal scroll on a laptop, which is the density complaint that was raised
  against the recipe list — so the list stays readable and the detail moves one click away.
- **It is modelled on Inventory, not Ingredients**: a list, a detail page at `/equipment/[id]`, and
  registering on its own screen at `/equipment/new`. Four fields or more get their own URL, and this
  has twelve. There is no inline row editing, deliberately.
- **The service state is on the row in words as well as colour** — *Overdue by 12 days*, *Due in
  9 days*. Colour alone fails anybody who cannot see the difference, and a bare date makes the reader
  do the arithmetic the screen exists to do for them.
- **Today counts only what is overdue, and only for an administrator.** *3 machines are past their
  service date*, linking to those machines. Amber stays on the Equipment screen: a morning screen
  that warns a month early, every month, is one an admin learns to scroll past.
- **An admin with nothing overdue sees no nudge at all** — not a zero, not an empty panel. The line
  is absent.
- **Menu and page agree.** The Kitchen menu shows **Equipment** to exactly the roles the page lets
  in — Temple Admin, Kitchen Manager and Kitchen Staff — and to nobody else. A menu entry that leads
  to a refusal is a worse fault than no menu entry.
- **Scrapped equipment is out of the default list** and never counted as overdue, and can be brought
  back into view when it is asked for.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin) for most of it. Steps 6–8 and
  27–31 need `ikms.kitchen-staff.1@trading4good.org`, step 4 needs
  `ikms.volunteer.1@trading4good.org`.
- **Start at:** **/equipment**
- **Set the scene:** run **UAT-084** first, or at least its steps 1–26. This test reads what that one
  wrote — you need at least one **red** machine, one **amber**, one that has never been serviced, one
  reading **Not scheduled** and one that is **scrapped**.
- Use a **laptop-sized window** for steps 9–13. The horizontal-scroll check means nothing on a phone.

## Steps

### Getting to it at all

| # | Do this | You should see |
|---|---|---|
| 1 | Look at the left-hand menu under **Kitchen** | **Equipment**, sitting **after Inventory** |
| 2 | Press it | **/equipment**, the list. Not a 404, and not a blank page |
| 3 | Type **/equipment** into the address bar directly | The same screen |
| 4 | Sign out. Sign in as `ikms.volunteer.1@trading4good.org` (a devotee) and look at the menu | **No Equipment entry.** Then type **/equipment** into the address bar: you are refused. The menu and the page must agree — a link that leads to a refusal is the defect to record here |
| 5 | Sign back in as the temple admin | Equipment is in the menu again |

### What kitchen staff see

| # | Do this | You should see |
|---|---|---|
| 6 | Sign in as `ikms.kitchen-staff.1@trading4good.org` and open the menu | **Equipment** is there — kitchen staff are one of the three roles |
| 7 | Open the list and find the red row from UAT-084 | It is **red**, and says **how overdue**. Staff are not shielded from the state of the machines |
| 8 | Open that item and look for **Record a service** | Not offered (UAT-084 step 29). Everything else on the page is readable |

### The list, and what fits on it

| # | Do this | You should see |
|---|---|---|
| 9 | Sign back in as the temple admin. Count the columns on **/equipment** | **Five**: Name, Location, Status, Next service, Service company. No more |
| 10 | Widen and narrow the browser window down to a laptop width | The **page** never scrolls sideways. If the table itself scrolls inside its own box, that is fine; if the whole page does, record it |
| 11 | Look for serial number, purchase cost, warranty or the interval on the list | **Not there.** They are on the item page. If the five turn out to be the wrong five, say so here — that is a real finding, not a complaint |
| 12 | Read the **Next service** cell on the red machine | **Overdue by *n* days**, in words, with the number. Not just a red date and not just a red dot |
| 13 | Read it on the amber machine | **Due in *n* days**, amber, in words |
| 14 | Read it on the machine that has never been serviced | The date **and** that it came from the purchase (UAT-084 step 24) |
| 15 | Read it on `Prep Table 6ft` | **Not scheduled** — a phrase, not a blank cell |

### The three filters

| # | Do this | You should see |
|---|---|---|
| 16 | Filter by **service status → Overdue** | Only the red machines. Count them and write the number down — step 24 uses it |
| 17 | Filter by **service status → Due soon** | Only the amber ones. The red ones are not in here as well |
| 18 | Clear that, and filter by **condition → Needs repair** | Only items in that condition, whatever their service dates say |
| 19 | Clear that, and filter by **location → Main kitchen** | Only that location |
| 20 | Now **combine** them: location **Main kitchen** *and* service status **Overdue** | The intersection — machines that are both. Not the union, and not the filter you set last winning outright |
| 21 | Add **condition → Good** to that pair | All three narrow together. If a third filter resets one of the first two, record it |
| 22 | Clear everything | The full default list is back |

### Scrapped

| # | Do this | You should see |
|---|---|---|
| 23 | Look for the scrapped **Serving Trolley** on the default list | **Not there** |
| 24 | Tick **Show scrapped items** | It reappears, marked scrapped |
| 25 | With scrapped items shown, filter by service status **Overdue** | The count still matches what you wrote down at step 16. A scrapped machine is never overdue, even when you are looking at it |

### Registering, and the message that goes away

| # | Do this | You should see |
|---|---|---|
| 26 | Press **Register equipment** | You go to **/equipment/new** — its own page, its own address, not a cramped panel over the list |
| 27 | Fill it in: `Idli Steamer 6-tray`, category `Machine`, location `Prasadam kitchen`, condition **Good**, source **Purchased**, acquired today, and a service interval of **1 year** | Everything is on one screen and the form is readable |
| 28 | Save | You land **back on the list**, with a success message naming what you just registered |
| 29 | Wait, and watch the message | **It dismisses itself.** You should not have to close it |
| 30 | Reload the page | The message is **gone and stays gone**. It must not come back on every reload, and the page must not lock up or spin — if the screen becomes unresponsive after saving, that is a **Blocker**, and note whether it happened on the first save or a later one |
| 31 | Press the browser **Back** button | You do not get the success message a second time |

### The item page

| # | Do this | You should see |
|---|---|---|
| 32 | Open **Wet Grinder 10L** | **/equipment/[id]** — its own address, which you can copy and come back to |
| 33 | Read the record | Every field: name, category, location, condition, source, acquired on, purchase cost, warranty expiry, serial number, service interval, last serviced, next service, and the service company **with its phone number** |
| 34 | Find the **service history** | Every service, **newest first**, each with its date, company, work done, cost and who recorded it |
| 35 | Find the **condition trail** (UAT-027) | The condition changes and their reasons, still there, in their own section and not muddled in with the services. Two different kinds of event, two lists |
| 36 | Press **Record a service**, date it **today**, and save | The service appears at the top of the history |
| 37 | Look at **Next service** **without reloading the page** | It has **already moved** — today plus the interval. You should not have to refresh to see the consequence of what you just did |
| 38 | Look at the row for that machine back on **/equipment** | The same new date, and the row's colour has changed with it |

### Today

| # | Do this | You should see |
|---|---|---|
| 39 | Make sure at least one machine is **overdue** (UAT-084 steps 16–18 give you one), then open **/today** as the temple admin | A line reading **_n_ machines are past their service date**, with *n* matching what you counted at step 16 |
| 40 | Press it | **/equipment**, already filtered to the overdue ones. Not the unfiltered list, and not a dead line of text |
| 41 | Read the line again and check what it does **not** say | It counts **only overdue**. The amber due-soon machines are not in the number — they live on the Equipment screen |
| 42 | Now clear the overdue state: on each red machine, record a service dated **today** | Every row goes out of red |
| 43 | Reload **/today** | **The line is gone entirely.** Not *0 machines are past their service date*, not an empty panel with a heading — nothing at all where it used to be |
| 44 | Sign in as `ikms.kitchen-staff.1@trading4good.org` and open **/today**, with a machine overdue again | **No servicing line for staff.** The count belongs to whoever books the engineer |

## It passes if

- [ ] The Kitchen menu shows **Equipment** to exactly the three roles the page admits, and to nobody else.
- [ ] The list shows the five columns and the **page** never scrolls sideways.
- [ ] An overdue row is red and says **how overdue**; a due-soon row is amber and says **how soon**.
- [ ] Filtering by service status, condition and location each work, and **combine** rather than replacing one another.
- [ ] Registering lands back on the list with a success message that **dismisses itself** and does not return on reload.
- [ ] The item page shows every field, the service history newest first, and the condition trail as a separate list.
- [ ] A service recorded from the item page moves the next date **immediately**, without a reload.
- [ ] Kitchen staff see the screen and the red rows, and are offered no way to record a service.
- [ ] A Temple Admin with overdue equipment sees the count on Today, and it links to **those** machines.
- [ ] An admin with none sees **no nudge at all** — not a zero.
- [ ] Scrapped equipment is out of the default list until asked for, and never counted as overdue.

## Watch out for

- **A zero where there should be nothing.** Step 43 is the point of this whole section. *0 machines
  are past their service date* is a line that teaches its reader to ignore the panel, which is
  exactly what the design is trying to avoid. Record it as **Major**, not cosmetic.
- **The page scrolling sideways.** Check at a laptop width with the browser window not maximised —
  that is where it shows. A table that scrolls inside its own frame is acceptable; a page that does
  is not.
- **Filters that replace each other** rather than combining. Step 20 is the test: set two and see
  whether you get the intersection or whichever you touched last.
- **The success message that will not go away**, or comes back on every reload, or takes the page
  down with it. This exact pattern has caused a page to lock up before now — if the screen becomes
  unresponsive after registering, stop and write down what you did.
- **A Today count that disagrees with the list.** Count the red rows by hand at step 16 and compare.
  If they differ, say which is higher — a nudge that overcounts and a nudge that undercounts are
  different defects.
- **The link from Today landing on an unfiltered list.** It should arrive with the overdue filter
  already applied; sending the reader to look for them is not much of a nudge.
- **The service history and the condition trail run together into one list.** They are two different
  kinds of event and the story asks for two sections. Note it if they are merged.
- Whether an equipment record can be **deleted** from this screen. It should not be — the register is
  a history (UAT-027). If a delete exists, record it.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT085-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
