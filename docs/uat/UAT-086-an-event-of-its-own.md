# UAT-086: An event of its own

| | |
|---|---|
| **Feature area** | Meal planning — events, and the end of catering |
| **Technical stories** | E4-S15 (events, and the end of catering) · E4-S16 (travel time for a delivered event, in **§travel**) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-032 (plan a meal), UAT-015 (recipes), UAT-034 (sufficiency) |
| **Environment needs** | None for steps 1–70, or for the van warning (steps 81–86). Steps 64–67 need a volunteer account and a second browser window. **§travel** is written to be run **twice** — once with no map provider configured, once with one |

> **This test replaces UAT-033**, which tested a *Catering order* kind that no longer exists. If you
> are holding a printed pack with UAT-033 in it, throw that page away.

## What this feature is for

There are **three main meals a day** — Breakfast, Lunch and Dinner — and the temple cooks them 365
days a year for a small army. Everything else is an **event**: a Bhajan Prasadam, a Saturday
Bhagavad-gita reading for the children, food going out to a school, a delivery to a community
programme.

Until now those had nowhere to go, so they were folded into whichever meal they were nearest. That
destroys the information three ways over: thirty children averaged into two hundred residents makes
the head count meaningless; the stock the event drew becomes invisible because it was consumed as
*breakfast*; and afterwards nobody can ever answer what the Saturday readings cost. Adding them
together does not muddy the data, it destroys it.

So an event is now its own planned preparation, with its own name, its own dishes, its own quantities
and its own job card. And the *Catering order* kind is gone: a temple that does catering plans a
**catering event**, and gains six fields by it.

## How it is supposed to work

- **One new kind: Event.** It absorbs both *Outside event* and *Catering order*, which no longer
  exist. The picker now reads **Breakfast, Lunch, Dinner, Festival feast, Deity Offering, Event**.
- **An event is quantified by how much to make; the head count is context.** *Thirty laddus and some
  chiwda* is a real thing a temple cooks — the temple's own `FHC Sabjis` sheet plans bulk
  distribution in gross kilograms per dish with **no head count at all**. So an event saves with an
  amount and no head count. The three main meals are unchanged: a Breakfast with something in it and
  nobody counted is still refused with `KMS-400080`.
- **An event has a name**, and the name **autocompletes from names used before**, bringing that
  event's contact forward with it, so the second School Bhagavad-gita Reading is one keystroke. This
  is not a convenience. If entering a Saturday reading costs three minutes it will stop being
  entered, and then the data is worse than if events had never been split out at all.
- **What is asked, and only when.** The Event kind reveals the **event name** and **is this going
  outside?**. Answering **yes** reveals **pickup or delivery** and a **contact name and phone**.
  Answering **delivery** additionally reveals the **address** and **the time the guests eat**. An
  in-house event stops at its name — it is never asked for a contact, an address or a handover,
  because a Bhajan Prasadam in the temple hall has no client and a form should not ask a question
  with no answer.
- **Breakfast, Lunch and Dinner see none of it** and ask exactly what they asked before.
- **Going out of the temple**, on Today, lists what leaves the temple in the fortnight ahead —
  soonest first, from tomorrow, with cancelled ones dropping out. It is keyed off *is this going
  outside*, not off *is this catering*, so the school delivery and the community programme are in it
  too. Those are exactly as easy to forget on the morning as a wedding. *(Until 2026-09-20 this was
  an "Upcoming outside commitments" card at the foot of the planner. It carried no date, so it sat
  under whichever day you were looking at and appeared to put an event on the wrong day, and its
  rows did not open. The events themselves are in the day list now, like any other meal.)*
- **An event repeats as a series**: once every 1 to 12 weeks, until a date no more than a year from
  today. Each occurrence is a plan in its own right and can be edited or cancelled on its own, but the
  occurrences stay linked, so cancelling one that has later ones asks whether to cancel **just this
  event** or **this and all later ones**. A date where the event is already planned, or where a dish
  doesn't suit a fasting day, is skipped and named.
  *Amended 2026-09-19.* This bullet used to say that repeating makes **copies, not a series**, with
  no link between them and no question on cancelling. Rajeev replaced that decision on 2026-09-19,
  after temple users asked for it: *"Let us change for many weeks to 'until' a date and also give them
  the option to pick the duration between repeats, and a cancel of this repeating event should ask
  JUST this event OR all events from this point onwards."* The story's record of both decisions is
  E4-S15 D8 and D8b.
- **`KMS-400073` is retired, not reused.** Somebody may still quote it from an old screenshot, so it
  keeps its old meaning and is never handed to anything new.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff). Steps 43–69 are easier as
  `ikms.temple-admin.1@trading4good.org`; step 70 goes back to kitchen staff.
- **Start at:** **/planner** (menu: **Meal plan**)
- You need at least one recipe (UAT-015). Use **Khichdi** where a preparation is called for, and
  anything sweet you have for the laddus.
- **Note whether this temple had catering plans before.** If it did, step 40 is the one that matters
  most in this whole test.

## Steps

### The Saturday reading, as its own thing

| # | Do this | You should see |
|---|---|---|
| 1 | Open a **Saturday** about two weeks out and look at the **Meal** list in step 1 of the form | **Breakfast, Lunch, Dinner, Festival feast, Deity Offering, Event.** Six kinds |
| 2 | Read that list twice, looking for the words *Catering order* and *Outside event* | **Neither is there.** Not greyed, not at the bottom, not hidden behind a *more* — gone |
| 3 | Choose **Event** | Two new things appear: an **event name** box, and **is this going outside?** |
| 4 | Type the name `Children's Bhagavad-gita Reading` and leave *going outside* as **no** | **Nothing else is asked.** No contact, no phone, no address, no pickup-or-delivery, no serving time |
| 5 | Go to step 2, **Who is expected**, and leave all three counters at **0** | They read 0, as they do everywhere (UAT-032) |
| 6 | Go to step 3, **Preparations**. Tick a sweet and type the amount you want made — say **30 pieces** — and tick **Chiwda** with its own amount | The quantities are yours. Nothing has been scaled from a head count you did not give |
| 7 | Give it a **ready-by** time — an event is an occasional kind, so it asks (UAT-032) | Accepted |
| 8 | Press **Save this meal** | **It saves.** An event with an amount and nobody counted is a complete plan |
| 9 | Look at the day | The reading is there **as its own line**, under its own name — not folded into breakfast, not shown as *Event* with no further identity |
| 10 | Open the job card for the day (UAT-035) | The reading has **its own card**, with its own dishes and its own quantities |
| 11 | Check the stock badge on it (UAT-034) | It draws on the same pool as everything else, and says so |

### But a Breakfast still refuses

| # | Do this | You should see |
|---|---|---|
| 12 | On the same day, start a **Breakfast**, leave the three counters at **0**, and tick **Khichdi** | The quantity box is **empty**, and **Save this meal** is dead with **Say how many people are expected** beside it — exactly as UAT-032 describes |
| 13 | Force it through if you can find a way | **`KMS-400080`**. The rule that events are exempt from does **not** loosen for the three main meals |
| 14 | Put **150** into Adults and save | Accepted, and it behaves precisely as it did before this change |

### Breakfast, Lunch and Dinner ask what they always asked

| # | Do this | You should see |
|---|---|---|
| 15 | Start a **Lunch** and read every field on the form | Head count, preparations, ready-by pre-filled with the temple's lunch hour, who will run it. **No event name. No *going outside*. No contact, address or serving time** |
| 16 | Do the same for **Dinner** and **Breakfast** | The same. Nothing from the event block has leaked into them |
| 17 | Start a **Deity Offering** | Ready-by is **empty** and must be given, as before. No event fields |
| 18 | Start a **Festival feast** | It asks which occasion, as before |

### Going outside: what is asked, and what is refused

| # | Do this | You should see |
|---|---|---|
| 19 | Start an **Event** on a date about ten days out, name it `Vidyaranyapura School Gita Reading`, and answer **yes** to *is this going outside* | Two more things appear: **pickup or delivery**, and a **contact name and phone** |
| 20 | Choose **Pickup** | You are asked for the contact, and **not** for an address or a serving time — somebody is coming to collect it |
| 21 | Leave the contact **name blank** and try to save | **Refused.** Normally the screen stops you first — a dead *Save* button beside *Say who to contact, and their number*, the same guard UAT-032 step 12 describes for `KMS-400080`. **`KMS-400076`** is what the server answers if a save ever reaches it |
| 22 | Put a name in, leave the **phone** blank, and try again | **Still refused**, same guard and same code. Both halves are needed — a contact you cannot ring is not a contact |
| 23 | Fill in `Mrs Latha Rao` and `+91 98862 30011`, and save | Accepted |
| 24 | Start another Event, answer **yes**, and switch to **Delivery** | **Two more fields again**: the **address**, and **the time the guests eat** |
| 25 | Fill in the contact but leave the **address** blank, and try to save | **Refused** — the screen guards it, and **`KMS-400077`** is the server's answer behind that guard |
| 26 | Put an address in, leave **the time the guests eat** blank, and try again | **Still refused**, same code. A delivery with no address or no serving time is not a delivery |
| 27 | Fill both in — address `ISKCON Bangalore, Hare Krishna Hill, Rajajinagar 560010`, guests eat at `13:00` — and save | Accepted |
| 28 | Start an Event and try to save it with **no name at all** | **Refused** — the guard first, `KMS-400075` behind it. The name is the whole point of splitting events out |
| 29 | Go back to an **in-house** event and hunt the form for a contact, an address, a handover or a serving time | **None of them are there.** If any appear on an in-house event, record it — the form must not ask a question that has no answer |

### The name that remembers

| # | Do this | You should see |
|---|---|---|
| 30 | On a later date, start an **Event** and begin typing `Vidya…` | The name **autocompletes** to `Vidyaranyapura School Gita Reading` from the one you planned at step 23 |
| 31 | Take the suggestion | *Going outside* is already **yes**, and the **contact name and phone come forward** with it — `Mrs Latha Rao`, `+91 98862 30011` |
| 32 | Change the phone number on this one and save | Accepted, and it is **this** event's number. It must not reach back and rewrite the earlier one |
| 33 | Begin typing `Children…` | `Children's Bhagavad-gita Reading` is suggested, and taking it brings **no** contact forward — that one is in-house and never had one |

### An outside event opens, adjusts and prints, like any other meal

| # | Do this | You should see |
|---|---|---|
| 34 | Open the planner on the day of your delivery event | The event is **in the day's list** with the ordinary meals, tagged **Event**, with a blue **We deliver it** pill |
| 35 | Look at the pickup event's day | Same, with **They collect it** instead. An in-house event such as `Children's Bhagavad-gita Reading` carries **no pill** — nobody was asked about handover |
| 36 | On the event, press **Edit**, change something and save | It saves, and the day shows the change |
| 37 | Press **Download job card** on the event | A job card prints for the event, as it does for a meal |

### Going out of the temple, on Today

| # | Do this | You should see |
|---|---|---|
| 38 | Open **Today** and find **Going out of the temple** | Your pickup and delivery events are both on it, **soonest first**, each with its date and its pill |
| 39 | Look for an outside event happening **today** | **Not there.** Today's meals are already in the meals card above; listing one twice would say the temple has two |
| 40 | Look for the in-house `Children's Bhagavad-gita Reading` | **Not there.** The card is about what leaves the temple |
| 41 | Look for anything **more than a fortnight away**, and anything in the **past** | Neither is there. It is the days ahead, not the whole diary |
| 42 | **Cancel** one of the events and reload | It **drops off**. A cancelled commitment is not a commitment |
| 43 | Press the event's name | The **planner opens on that date**, with the event in the day's list |

### No catering, anywhere

| # | Do this | You should see |
|---|---|---|
| 40 | If this temple had **catering plans before this change**, find one — check the month it was in, and the past days | It is **still there**, as an **Event marked as going outside**, carrying its old client as the **contact**, its old phone as the **contact phone** and its old venue as the **address**. Its old free-text purpose has become its **name** where the name was otherwise empty. **A missing catering plan is a Blocker** — these are meals the temple actually cooked |
| 41 | Open it and check nothing was invented | The head count, dishes, quantities and ready-by are the ones it always had |
| 42 | Hunt the whole product for the word **Catering** as a *kind* or a *day type* — the meal-kind picker, any day-type filter, the costing report by kind (UAT-075), the served-meals report, any export | The kind is gone everywhere. A temple's **own** kind named *Catering event*, typed by hand, is fine and expected — what must not exist is one the product seeded |
| 43 | As the temple admin, provision or open a **brand-new** temple and look at its meal-kind list | **Breakfast, Lunch, Dinner, Festival feast, Deity Offering, Event.** No *Catering order* and no *Outside event* on a fresh temple either |
| 44 | Look at any place that shows a **day type** — the day panel, a filter, a report | **CATERING** appears nowhere in the vocabulary. Days that used to carry it now read as an ordinary weekday or weekend |

### Repeating it forward, as a series

> *Amended 2026-09-19.* Until this date the section tested **copies, not a series**: repeat for a
> number of weeks, and a cancel that never asked about the others. Rajeev replaced that on
> 2026-09-19 (see *How it is supposed to work*). The old four steps are in git history; the steps
> below test what was built instead (T-307, T-308, T-310).
>
> Do these as the **temple admin**. Call the date of the reading you saved at step 8 **R**. **R+2**
> is the same weekday two weeks later, **R+4** four weeks later, and so on. Dates on screen are
> written like `Sat 26 Dec 2026`, and the end date in the series line like `31 Dec 2026`. Words in
> `code` are the exact words on screen; *&lt;angle brackets&gt;* stand for a number or a date.

| # | Do this | You should see |
|---|---|---|
| 45 | Plan `Children's Bhagavad-gita Reading` by hand on **R+4**, exactly as at steps 3–8 (the name autocompletes) | Saved. R+4 has one reading on it |
| 46 | Go back to **R**. Look at the reading's card, and at the Breakfast card from step 14 | The reading's card has a button `Repeat this event`. The Breakfast card has none: only an event repeats |
| 47 | Press `Repeat this event` | One sentence opens: `Repeat Children's Bhagavad-gita Reading` `once every` **[1]** `week` `until` **[a date]**, then an **i**, `Repeat` and `Close`. The box holds **1** and the word after it is `week`, singular. The end date is six weeks after R. Under the sentence: `Makes 5 copies · last one` *&lt;R+6&gt;*, and `Skips` *&lt;R+4&gt;*`: this event is already planned that day.` |
| 48 | Press the **i** | `Each copy can be edited or cancelled on its own. The copies stay linked, so you can cancel the later ones together.` |
| 49 | Change **1** to **2** | The word changes to `weeks`. You have not picked an end date yet, so it moves out to twelve weeks after R, and the line reads `Makes 5 copies · last one` *&lt;R+12&gt;*, still skipping R+4. **If an Ekadashi falls on one of those dates** (check the Vaishnava calendar) it is skipped too, because Chiwda is a grain: `Skips` *&lt;date&gt;*`: a dish doesn’t suit the fasting day.` Take one off every count below for each such date |
| 50 | Pick an end date yourself: the **day before** R+12 | The count changes as you pick, without pressing anything: `Makes 4 copies · last one` *&lt;R+10&gt;*. The last copy is never after the end date |
| 51 | Now pick **R+12** itself. Then change the gap to 1 and back to 2 | `Makes 5 copies · last one` *&lt;R+12&gt;*: an end date that lands on a copy's date includes it. Changing the gap does **not** move a date you picked yourself |
| 52 | Type **0** in the gap box, then **13** | Both times, in red text under the sentence (not a banner at the top of the page): `An event can repeat every 1 to 12 weeks. Choose a number from 1 to 12.` `Repeat` is dead. The server's code for this is **`KMS-400175`** |
| 53 | Put the gap back to **2** and clear the end date | `Choose the date to repeat until.` `Repeat` is dead |
| 54 | Set the end date to **R+1**, before the first copy could land | `No copies fit before that end date. Choose a later end date, or repeat more often.` `Repeat` is dead. Code **`KMS-400177`** |
| 55 | Set the end date to **exactly one year from today**, then to **one day more** (type it if the date box won't let you pick it) | One year from today is accepted and counted. One day more: `The end date has to be within a year from today. Choose an earlier end date.` `Repeat` is dead. Code **`KMS-400176`** |
| 56 | Set the end date back to **R+12**, gap **2**, and press `Repeat` | The panel closes and a green notice stays on the card: `Made 5 copies · last one` *&lt;R+12&gt;*`.` and under it `Skipped` *&lt;R+4&gt;*`: this event is already planned that day.` The same numbers and dates the preview promised |
| 57 | Open **R+2, R+6, R+8, R+10 and R+12** | The reading is on each one, with the same name, dishes, 30 pieces and ready-by time. **R+4 still has exactly one reading**, the one you planned at step 45, with nothing added to it |
| 58 | Read the line under the reading's name on the **R+6** card, then on the **R** card | Grey text with a repeat icon, not a coloured pill: `Repeats every 2 weeks until` *&lt;R+12&gt;* `· event 3 of 6` on R+6, and `· event 1 of 6` on R |
| 59 | Open **R+6** to edit it | Under the heading: `Repeats every 2 weeks until` *&lt;R+12&gt;* `· event 3 of 6. Changes here apply to this date only.` |
| 60 | Change the sweet from 30 pieces to **50** and save | Saved. **No question** about the other dates |
| 61 | Open **R+2 and R+8** | **Still 30.** Editing one occurrence changes that one only |
| 62 | On **R+10**, press **Cancel** | The dialog asks `This event repeats. Which do you want to cancel?` with two choices, `Just this event` (already chosen) and `This and all later ones`. The focus is on `Keep it`. The body reads `Its preparations come off the plan.` and the button `Cancel this meal`. Press it: `Children's Bhagavad-gita Reading was cancelled.` |
| 63 | Look at **R+8 and R+12** | Both still planned. The count only counts what still stands: R+12 now reads `· event 5 of 5`, and R+10's line has no *event N of M* at all |
| 64 | Post a volunteer shift on **R+12** and have `ikms.volunteer.1@trading4good.org` sign up for it (UAT-048, UAT-049) | The shift shows one volunteer |
| 65 | On **R+2**, press **Cancel** and choose `This and all later ones`. **Do not confirm yet** | The body changes to three lines: `Cancels this event and 3 later ones, the last on` *&lt;R+12&gt;*`.` / *&lt;R+6&gt;* `was changed on its own and will be cancelled too.` / `1 volunteer is signed up or waiting across these events, and will be told.` The button reads `Cancel 4 events`. R+10, cancelled already, is not counted |
| 66 | Leave that dialog open. In a **second browser window**, signed in the same way, cancel **R+8** alone with `Just this event`. Go back to the first window and press `Cancel 4 events` | The dialog **stays open** and says `The later events changed while you were deciding, so nothing was cancelled.` `Look at the list again, then confirm.` `If you need help, quote KMS-400179`. The body now reads `Cancels this event and 2 later ones, …` and the button `Cancel 3 events`. Check in the second window: **R+2 is still planned** |
| 67 | Press `Cancel 3 events` | `Children's Bhagavad-gita Reading was cancelled on 3 dates. 1 volunteer was told.` R+2, R+6 and R+12 are cancelled. **R is untouched**: nothing before the one you started from is ever cancelled. R+4, planned by hand, was never part of the series and is still planned. The volunteer is told the shift is off, as UAT-048 describes. R's series line now ends at R's own date: the end date shrinks to the last one still standing |
| 68 | Cancel the `Vidyaranyapura School Gita Reading` you saved at step 32, which was never repeated | **No question.** The dialog is the one it always was: `Its preparations come off the plan.` and `Cancel this meal` |
| 69 | Cancel the reading on **R**, now the only one left standing in its series | **No question** either: there is nothing later to ask about |
| 70 | Sign in as **kitchen staff**. Open the reading on **R+4**, repeat it `once every` **1** `week` until R+6, then cancel the first copy with `This and all later ones` | Both work exactly as they did for the temple admin, with the same sentences. Kitchen staff plan meals, so they hold the same permission. A refusal here is **Major** |

### §travel — Leave the temple by

> Run this section **twice**: once with **no map provider configured** (steps 71–73), and once with
> one (steps 74–80). The first run is the more important of the two — it proves a map service the
> temple does not have cannot stop a cook planning a meal.

**With no provider configured**

| # | Do this | You should see |
|---|---|---|
| 71 | Plan a **delivered** event exactly as at step 27 | It saves, in the same number of clicks and the same time as it did before any of this existed |
| 72 | Open it and look where the travel estimate would be | **A quiet line saying it is unavailable** — one sentence, in the ordinary text colour. Not a red error, not a spinner that never stops, and not a blank space where something clearly should be |
| 73 | Plan a pickup event, an in-house event, a Lunch and a Deity Offering | All four behave exactly as they always have. **Nothing about the planner is different** because a map service is absent |

**With a provider configured**

| # | Do this | You should see |
|---|---|---|
| 74 | Open the delivered event from step 27 — guests eat at **13:00**, address in Rajajinagar | A line reading **Leave the temple by _HH:MM_**, and a **range** beside it — *35 to 45 minutes*, or whatever the drive actually is |
| 75 | Do the arithmetic: guests' serving time minus the slower end of the range | The leave-by time agrees with your sum. It is worked **backwards from when the guests eat**, not forwards from the ready-by |
| 76 | Read what the estimate says about itself | It is plainly an estimate with a range, not a single confident number. A driver can act on *leave by 11:15*; nobody can act on *37 minutes* |
| 77 | Open a **pickup** event, and an **in-house** one | **No estimate on either**, and neither was ever asked for an address. Nothing to travel to |
| 78 | Plan a delivery to a **nonsense address** — `Zzzz Qqqq, 999999` | The plan **still saves**. You are told the address could not be found, quoting **`KMS-400078`** — the one failure worth telling somebody about, because they can fix it |
| 79 | Re-open that plan | It is there, whole, with everything you typed. A map service that could not find a street has not cost you the meal plan |
| 80 | Plan a delivery to somewhere **hours away** — another city — and save | **Accepted.** The estimate is advice, never a rule. A temple that wants to send food two hours away may. If a long drive is ever *refused*, that is a **Major** defect |

### §travel — Time to load the van

*Added 2026-09-19.* Rajeev had the warning reworded that day. It works from what is typed, so it needs
**no map provider**: run it in either §travel run. The ready-by time, the guests' serving time and the
**Estimated travel time** box (minutes, which you can type yourself) decide it. The food must arrive
before the guests eat, and anything under **30 minutes** spare for loading and setting up is a warning.

| # | Do this | You should see |
|---|---|---|
| 81 | Open the delivered event from step 27 to edit it. **Ready by** `12:00`, guests eat at `13:00`, **Estimated travel time** `45` | Under **Ready by**, in amber, and the box edged amber: **You’ll only have 15 minutes to load the van and set up for service at the destination.** **Save** still works: it is a warning |
| 82 | Change the travel time to `59` | **You’ll only have 1 minute to load the van and set up for service at the destination.** — *minute*, singular |
| 83 | Change it to `30` | No warning at all. Thirty minutes spare is enough |
| 84 | Change it to `70` | In red, under Ready by: **The food arrives 10 minutes late. Make it ready earlier, or change when guests eat.** **Save** is dead, with **The delivery cannot arrive in time** beside it. (`KMS-400079` is the server's answer behind it) |
| 85 | Change **Ready by** to `11:00`, keeping 70 minutes | The red line goes and no amber warning shows either (120 − 70 = 50 minutes spare). Save |
| 86 | Look at the same fields on a **pickup** event and an **in-house** event | Neither shows the warning or the late refusal; there is no van to load |

## It passes if

- [ ] A Saturday reading is planned as its own preparation, with its own name, dishes and job card.
- [ ] An event saves with an amount and **no head count**; a Breakfast still does not (`KMS-400080`).
- [ ] The event name autocompletes from names used before and brings that event's contact forward.
- [ ] An in-house event is **never** asked for a contact, an address or a handover.
- [ ] An outside event cannot be saved without a contact **name and phone** — guarded by the screen, `KMS-400076` behind it.
- [ ] A delivered event cannot be saved without an **address and a serving time** — guarded by the screen, `KMS-400077` behind it.
- [ ] An event with no name cannot be saved — guarded by the screen, `KMS-400075` behind it.
- [ ] Breakfast, Lunch and Dinner ask exactly what they asked before, and nothing more.
- [ ] An outside event sits in the planner's day list, opens, edits and prints a job card, and carries **We deliver it** or **They collect it**.
- [ ] **Going out of the temple** on Today lists the fortnight ahead from tomorrow, soonest first, drops cancelled ones, shows nothing past, and its rows open the planner on that date.
- [ ] A pre-existing catering plan survives as an outside event with its client, contact and venue intact.
- [ ] No *Catering order* kind exists for an existing temple **or a newly provisioned one**, and `CATERING` appears nowhere as a day type.
- [ ] An event repeats **once every 1 to 12 weeks until a date**, the sentence says `week` at 1 and `weeks` above it, and the count and last date shown before pressing `Repeat` are what it makes.
- [ ] A date where the event is already planned, or a fasting day a dish doesn't suit, is skipped and named, before and after `Repeat`.
- [ ] A gap outside 1 to 12, an end date past a year from today, or one that fits no copy is refused beside the control and `Repeat` stays dead (`KMS-400175`, `KMS-400176`, `KMS-400177` behind it).
- [ ] Every occurrence shows `Repeats every … until … · event N of M`, on its card and on its edit page, and **each occurrence is still editable on its own**.
- [ ] Cancelling an occurrence that has later ones asks `Just this event` or `This and all later ones`; "later" names the count, the last date, the ones changed on their own and the volunteers, and never touches an earlier occurrence.
- [ ] If the later ones change while the question is open, nothing is cancelled and the fresh list is shown (`KMS-400179`).
- [ ] An event never repeated, or the last one standing, cancels with no question.
- [ ] Kitchen staff can repeat and cancel a series as the temple admin can.
- [ ] **§travel** — a delivered event shows a leave-by time and a range, derived from the guests' serving time.
- [ ] **§travel** — a pickup event and an in-house event show no estimate and are asked for no address.
- [ ] **§travel** — with no provider configured, the planner works exactly as before and shows a quiet unavailable line.
- [ ] **§travel** — an address that cannot be found reports `KMS-400078` and **the plan still saves**.
- [ ] **§travel** — the estimate is never a reason a plan is refused.
- [ ] **§travel** — a delivery with under 30 minutes spare shows the amber *You’ll only have N minutes to load the van and set up for service at the destination.* (singular at 1) and still saves; one that arrives after the guests eat is refused in red with Save dead.

## A note on how a refusal shows itself

The composer guards these three rules **before** the server sees them: *Save* goes dead and a hint
says what is missing, which is the pattern the planner has used since UAT-032. So a tester working
by hand will usually see the hint, not the code. The codes are real and are what the server answers —
they are covered by automated tests, and they appear on screen if a save ever reaches the server
without them. **Record the hint you saw; do not log a defect merely because no `KMS-` code appeared.**

The repeat control works the same way (steps 52–55): its refusals show as red text under the
sentence with `Repeat` dead, in the server's words where the server has answered, and with no code on
screen. `KMS-400175`, `KMS-400176` and `KMS-400177` are what the server answers behind them. The one
repeat-series refusal that does show its code is `KMS-400179` (step 66), because it happens after you
confirm.

## Watch out for

- **A catering plan that did not survive.** Step 40 is the highest-stakes step in this test. Those
  rows are meals the temple actually cooked, and their client, phone and venue are what somebody
  would go looking for a year later. Anything missing or blank is a **Blocker**, and write down which
  plan and which field.
- **An in-house event asked for a client.** That is the exact mistake the design set out to avoid —
  a form asking a question with no answer, which then either gets a made-up answer or blocks the
  save. Record it as Major.
- **A head count quietly invented for an event.** Events are exempt from `KMS-400080`; they are not
  exempt from UAT-032's rule that the application never supplies a number nobody typed. If a
  quantity box fills in before you have said anything, that is the same defect UAT-032 hunts.
- **`KMS-400073` or `KMS-400074` appearing anywhere.** Those were the catering refusals. They are
  retired, not reused — if one comes back attached to an event, something has been renumbered, and
  a code quoted from an old screenshot will now mean the wrong thing. Record it as Major and quote
  the exact message.
- **An event that saves with a name and nothing else.** Note what happens: no preparation at all is
  a different refusal (UAT-032 step 19) and should say so in its own words.
- **A cancel that reaches too far.** `This and all later ones` must cancel only the occurrence you
  started from and the ones after it that are still to cook. An earlier occurrence cancelled, a date
  cancelled that the dialog did not count, or a meal already cooked or recorded coming off the plan is
  a **Blocker**: it is food the kitchen was going to make, or made. Write down the dates.
  *Amended 2026-09-19.* This point replaces one that said the opposite: that a cancel asking *this one or all of
  them?* was a defect, because copies had been chosen over a series. Rajeev reversed that decision on
  2026-09-19, and that question is now what was built.
- **Editing one occurrence changing the others.** The series links the dates for cancelling; it does
  not make them one plan. If an edit on one date shows up on another, record it as Major.
- **A count that lies.** The number in `Makes … copies`, `Made … copies` and `Cancel … events` has to
  match what you then find on the planner, date by date. Count them.
- **A travel estimate that blocks or slows the save.** Step 71 with no provider is the check that
  matters: if planning a delivery is any slower than planning a Lunch, or ever hangs, the map call
  is standing between a cook and a meal plan. Time both and write the two numbers down.
- **A travel estimate that never changes.** Look at the same delivery on two different days at two
  different hours. If the range is byte-for-byte identical every time, note it — the estimate is
  supposed to be recomputed each time it is shown, not remembered.
- Times reading in a 12-hour form in one place and 24-hour in another. Pick one and note where it
  differs.
- **Going out of the temple** showing another temple's commitments. Check it as the second temple
  admin if you have one to hand (UAT-006).

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT086-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
