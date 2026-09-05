# UAT-086: An event of its own

| | |
|---|---|
| **Feature area** | Meal planning — events, and the end of catering |
| **Technical stories** | E4-S15 (events, and the end of catering) · E4-S16 (travel time for a delivered event, in **§travel**) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-032 (plan a meal), UAT-015 (recipes), UAT-034 (sufficiency) |
| **Environment needs** | None for steps 1–48. **§travel** is written to be run **twice** — once with no map provider configured, once with one |

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
  nobody counted is still refused with `KMS-4989`.
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
- **Upcoming outside commitments** lists everything going out of the temple — future only, in date
  order, with cancelled ones dropping out. It is keyed off *is this going outside*, not off
  *is this catering*, so the school delivery and the community programme are in it too. Those are
  exactly as easy to forget on the morning as a wedding.
- **An event repeats forward** for a chosen number of weeks. What that makes is **copies, not a
  series** — each one is a plan in its own right and can be edited or cancelled without touching the
  others.
- **`KMS-4944` is retired, not reused.** Somebody may still quote it from an old screenshot, so it
  keeps its old meaning and is never handed to anything new.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff). Steps 43–48 are easier as
  `ikms.temple-admin.1@trading4good.org`.
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
| 13 | Force it through if you can find a way | **`KMS-4989`**. The rule that events are exempt from does **not** loosen for the three main meals |
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
| 21 | Leave the contact **name blank** and try to save | **Refused.** Normally the screen stops you first — a dead *Save* button beside *Say who to contact, and their number*, the same guard UAT-032 step 12 describes for `KMS-4989`. **`KMS-4991`** is what the server answers if a save ever reaches it |
| 22 | Put a name in, leave the **phone** blank, and try again | **Still refused**, same guard and same code. Both halves are needed — a contact you cannot ring is not a contact |
| 23 | Fill in `Mrs Latha Rao` and `+91 98862 30011`, and save | Accepted |
| 24 | Start another Event, answer **yes**, and switch to **Delivery** | **Two more fields again**: the **address**, and **the time the guests eat** |
| 25 | Fill in the contact but leave the **address** blank, and try to save | **Refused** — the screen guards it, and **`KMS-4992`** is the server's answer behind that guard |
| 26 | Put an address in, leave **the time the guests eat** blank, and try again | **Still refused**, same code. A delivery with no address or no serving time is not a delivery |
| 27 | Fill both in — address `ISKCON Bangalore, Hare Krishna Hill, Rajajinagar 560010`, guests eat at `13:00` — and save | Accepted |
| 28 | Start an Event and try to save it with **no name at all** | **Refused** — the guard first, `KMS-4990` behind it. The name is the whole point of splitting events out |
| 29 | Go back to an **in-house** event and hunt the form for a contact, an address, a handover or a serving time | **None of them are there.** If any appear on an in-house event, record it — the form must not ask a question that has no answer |

### The name that remembers

| # | Do this | You should see |
|---|---|---|
| 30 | On a later date, start an **Event** and begin typing `Vidya…` | The name **autocompletes** to `Vidyaranyapura School Gita Reading` from the one you planned at step 23 |
| 31 | Take the suggestion | *Going outside* is already **yes**, and the **contact name and phone come forward** with it — `Mrs Latha Rao`, `+91 98862 30011` |
| 32 | Change the phone number on this one and save | Accepted, and it is **this** event's number. It must not reach back and rewrite the earlier one |
| 33 | Begin typing `Children…` | `Children's Bhagavad-gita Reading` is suggested, and taking it brings **no** contact forward — that one is in-house and never had one |

### Upcoming outside commitments

| # | Do this | You should see |
|---|---|---|
| 34 | Find the **Upcoming outside commitments** list | Your pickup event and your delivery event are both on it, **in date order** |
| 35 | Look for the in-house `Children's Bhagavad-gita Reading` on that list | **Not there.** The list is about what leaves the temple |
| 36 | Plan an outside event on a date **in the past** *(if the planner lets you — past days are read-only, so use one already there)*, and reload the list | **No past commitments.** *Upcoming* means upcoming |
| 37 | Add a third outside event on a date **between** the other two | It appears **between** them, not at the bottom |
| 38 | **Cancel** one of the events | It **drops out of the list**. A cancelled commitment is not a commitment |
| 39 | Read the columns on the list | Enough to act on without opening anything: the date, the event's name, who to ring, and where it is going |

### No catering, anywhere

| # | Do this | You should see |
|---|---|---|
| 40 | If this temple had **catering plans before this change**, find one — check the month it was in, and the past days | It is **still there**, as an **Event marked as going outside**, carrying its old client as the **contact**, its old phone as the **contact phone** and its old venue as the **address**. Its old free-text purpose has become its **name** where the name was otherwise empty. **A missing catering plan is a Blocker** — these are meals the temple actually cooked |
| 41 | Open it and check nothing was invented | The head count, dishes, quantities and ready-by are the ones it always had |
| 42 | Hunt the whole product for the word **Catering** as a *kind* or a *day type* — the meal-kind picker, any day-type filter, the costing report by kind (UAT-075), the served-meals report, any export | The kind is gone everywhere. A temple's **own** kind named *Catering event*, typed by hand, is fine and expected — what must not exist is one the product seeded |
| 43 | As the temple admin, provision or open a **brand-new** temple and look at its meal-kind list | **Breakfast, Lunch, Dinner, Festival feast, Deity Offering, Event.** No *Catering order* and no *Outside event* on a fresh temple either |
| 44 | Look at any place that shows a **day type** — the day panel, a filter, a report | **CATERING** appears nowhere in the vocabulary. Days that used to carry it now read as an ordinary weekday or weekend |

### Repeating it forward

| # | Do this | You should see |
|---|---|---|
| 45 | Open the in-house `Children's Bhagavad-gita Reading` and find the option to **repeat it forward**, giving a number of weeks — say **6** | Six copies land on the next six matching weekdays, each with the name, the dishes, the amounts and the ready-by time |
| 46 | Open the third copy and change its amount — 50 pieces instead of 30 | Saved |
| 47 | Open the first, second and fourth copies | **Unchanged at 30.** These are copies, not a series: editing one must not edit the rest, and no *this one or all of them?* question should appear |
| 48 | **Cancel** the fifth copy | It goes, and the other five stay. Check the sixth especially |

### §travel — Leave the temple by

> Run this section **twice**: once with **no map provider configured** (steps 49–51), and once with
> one (steps 52–58). The first run is the more important of the two — it proves a map service the
> temple does not have cannot stop a cook planning a meal.

**With no provider configured**

| # | Do this | You should see |
|---|---|---|
| 49 | Plan a **delivered** event exactly as at step 27 | It saves, in the same number of clicks and the same time as it did before any of this existed |
| 50 | Open it and look where the travel estimate would be | **A quiet line saying it is unavailable** — one sentence, in the ordinary text colour. Not a red error, not a spinner that never stops, and not a blank space where something clearly should be |
| 51 | Plan a pickup event, an in-house event, a Lunch and a Deity Offering | All four behave exactly as they always have. **Nothing about the planner is different** because a map service is absent |

**With a provider configured**

| # | Do this | You should see |
|---|---|---|
| 52 | Open the delivered event from step 27 — guests eat at **13:00**, address in Rajajinagar | A line reading **Leave the temple by _HH:MM_**, and a **range** beside it — *35 to 45 minutes*, or whatever the drive actually is |
| 53 | Do the arithmetic: guests' serving time minus the slower end of the range | The leave-by time agrees with your sum. It is worked **backwards from when the guests eat**, not forwards from the ready-by |
| 54 | Read what the estimate says about itself | It is plainly an estimate with a range, not a single confident number. A driver can act on *leave by 11:15*; nobody can act on *37 minutes* |
| 55 | Open a **pickup** event, and an **in-house** one | **No estimate on either**, and neither was ever asked for an address. Nothing to travel to |
| 56 | Plan a delivery to a **nonsense address** — `Zzzz Qqqq, 999999` | The plan **still saves**. You are told the address could not be found, quoting **`KMS-4993`** — the one failure worth telling somebody about, because they can fix it |
| 57 | Re-open that plan | It is there, whole, with everything you typed. A map service that could not find a street has not cost you the meal plan |
| 58 | Plan a delivery to somewhere **hours away** — another city — and save | **Accepted.** The estimate is advice, never a rule. A temple that wants to send food two hours away may. If a long drive is ever *refused*, that is a **Major** defect |

## It passes if

- [ ] A Saturday reading is planned as its own preparation, with its own name, dishes and job card.
- [ ] An event saves with an amount and **no head count**; a Breakfast still does not (`KMS-4989`).
- [ ] The event name autocompletes from names used before and brings that event's contact forward.
- [ ] An in-house event is **never** asked for a contact, an address or a handover.
- [ ] An outside event cannot be saved without a contact **name and phone** — guarded by the screen, `KMS-4991` behind it.
- [ ] A delivered event cannot be saved without an **address and a serving time** — guarded by the screen, `KMS-4992` behind it.
- [ ] An event with no name cannot be saved — guarded by the screen, `KMS-4990` behind it.
- [ ] Breakfast, Lunch and Dinner ask exactly what they asked before, and nothing more.
- [ ] Upcoming outside commitments lists future ones in date order, drops cancelled ones, and shows no past ones.
- [ ] A pre-existing catering plan survives as an outside event with its client, contact and venue intact.
- [ ] No *Catering order* kind exists for an existing temple **or a newly provisioned one**, and `CATERING` appears nowhere as a day type.
- [ ] An event repeats forward for a chosen number of weeks and **each copy is independently editable**.
- [ ] **§travel** — a delivered event shows a leave-by time and a range, derived from the guests' serving time.
- [ ] **§travel** — a pickup event and an in-house event show no estimate and are asked for no address.
- [ ] **§travel** — with no provider configured, the planner works exactly as before and shows a quiet unavailable line.
- [ ] **§travel** — an address that cannot be found reports `KMS-4993` and **the plan still saves**.
- [ ] **§travel** — the estimate is never a reason a plan is refused.

## A note on how a refusal shows itself

The composer guards these three rules **before** the server sees them: *Save* goes dead and a hint
says what is missing, which is the pattern the planner has used since UAT-032. So a tester working
by hand will usually see the hint, not the code. The codes are real and are what the server answers —
they are covered by automated tests, and they appear on screen if a save ever reaches the server
without them. **Record the hint you saw; do not log a defect merely because no `KMS-` code appeared.**

## Watch out for

- **A catering plan that did not survive.** Step 40 is the highest-stakes step in this test. Those
  rows are meals the temple actually cooked, and their client, phone and venue are what somebody
  would go looking for a year later. Anything missing or blank is a **Blocker**, and write down which
  plan and which field.
- **An in-house event asked for a client.** That is the exact mistake the design set out to avoid —
  a form asking a question with no answer, which then either gets a made-up answer or blocks the
  save. Record it as Major.
- **A head count quietly invented for an event.** Events are exempt from `KMS-4989`; they are not
  exempt from UAT-032's rule that the application never supplies a number nobody typed. If a
  quantity box fills in before you have said anything, that is the same defect UAT-032 hunts.
- **`KMS-4944` or `KMS-4945` appearing anywhere.** Those were the catering refusals. They are
  retired, not reused — if one comes back attached to an event, something has been renumbered, and
  a code quoted from an old screenshot will now mean the wrong thing. Record it as Major and quote
  the exact message.
- **An event that saves with a name and nothing else.** Note what happens: no preparation at all is
  a different refusal (UAT-032 step 19) and should say so in its own words.
- **Repeat-forward growing a series.** If cancelling one copy offers *this one or all of them?*, or
  if editing one changes the others, that is not what was built — copies were chosen deliberately
  over a recurrence rule. Record which behaviour you saw.
- **A travel estimate that blocks or slows the save.** Step 49 with no provider is the check that
  matters: if planning a delivery is any slower than planning a Lunch, or ever hangs, the map call
  is standing between a cook and a meal plan. Time both and write the two numbers down.
- **A travel estimate that never changes.** Look at the same delivery on two different days at two
  different hours. If the range is byte-for-byte identical every time, note it — the estimate is
  supposed to be recomputed each time it is shown, not remembered.
- Times reading in a 12-hour form in one place and 24-hour in another. Pick one and note where it
  differs.
- The **Upcoming outside commitments** list showing another temple's commitments. Check it as the
  second temple admin if you have one to hand (UAT-006).

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT086-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
