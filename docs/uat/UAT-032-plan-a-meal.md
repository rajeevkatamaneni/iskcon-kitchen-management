# UAT-032: Plan a meal

| | |
|---|---|
| **Feature area** | Meal planning |
| **Technical stories** | E4-S7 (the planner as it now is) · E4-S4 (the original) · E4-S3 (correcting a date) · E4-S5 (sufficiency) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-015 (recipes), UAT-029 (the calendar) |
| **Environment needs** | Background worker on, for the calendar context |

## What this feature is for

The temple's cooking, visible in one place: what is being made, on which day, and by when it has to
be ready. It is the hinge of the whole product — the meal plan is what tells the store room what will
be consumed and the shopping list what must be bought.

## How it is supposed to work

The calendar is deliberately quiet: it shows the days, what is planned on each, and a small mark for
a fasting day or a festival. Everything else happens **inside a day**.

- **Click a day** and it opens full-screen. The top panel is what the calendar says about that day —
  and a Temple Admin can correct it there. Below it are the meals already planned, then the form to
  plan another.
- A planned meal is a **kind of meal** (Breakfast, Lunch, Dinner, Deity Offering, Catering order,
  Outside event), **how many people are expected**, one or more **preparations**, and **the time the
  food must be ready**. The form asks for them in that order, in four numbered steps: **1 what kind
  of meal**, **2 who is expected**, **3 preparations**, **4 who will run it**.
- **The head count starts at nothing, and the application never invents one.** All three counters —
  **Adults**, **Children**, **Seniors** — open at **0**. Until one of them is typed, a preparation's
  quantity box stays **empty**: an empty box, not a nought, because a nought is an answer and this is
  the absence of one. Type a head count and every quantity fills in and rescales as you change it.
- **A meal that is cooking something cannot be saved without a head count** (`KMS-4989`). Everything
  the plan is worth is worked out from that number — how much of each preparation to make, what the
  day's food costs, what a serving costs, how many plates the job card says — and until 2026-08-31
  the form supplied 100 adults of its own when nobody had said. A meal with **nothing in it yet** is
  a different thing and is fine: nobody has said what, or for how many.
- Children count **0.6** of a portion and seniors **0.8**, so the readout **Cooking for** is a
  weighted figure, not the three counters added up.
- **Everyday meals arrive with their time already filled in** — the temple's own lunch and dinner
  hours — and you can change it. **The occasional ones do not**: a deity offering, a catering order or
  food going out to an event must be given a time, because guessing one is worse than asking.
- Nobody is asked what *sort* of day it is. Weekend follows from the date, festival from the calendar,
  and catering is a kind of meal rather than a kind of day.
- A catering order asks who it is for and where it is going; an outside event asks where.
- The day's meals are listed **in the order they must be ready**, which is the order the kitchen works
  in, each with whether there is stock for it.
- **Past days are read-only.** You can look at what was cooked; you cannot plan into yesterday.
- **Day / Week / Month** switch the view. **Today** returns to the current day.
- **Duplicate last week**, in the **Week** view, copies last week's meals onto this one — kinds, preparations, ready-by
  times, crew figures and **head counts**. What is *not* copied is the day itself: this Wednesday is
  not last week's festival, so the calendar facts are worked out afresh. A source meal carrying no
  head count **refuses the copy**, naming its date and kind, rather than being quietly dropped from a
  week you would then believe had come across whole.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/planner** (menu: **Meal plan**)
- You need at least one recipe (UAT-015). With none, the day view says so and offers to add one.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Meal plan** | A clean month grid. Today is marked. Days carry a small dot where there is a fast or a festival — no wall of text |
| 2 | Switch to **Week**, then **Day**, then back to **Month** | Each view shows the same cooking, at a different range. **Today** always returns you to today |
| 3 | Click **today's** cell | The day opens full-screen: the date at the top, then a panel of what the calendar says (tithi, month, sunrise), then the meals, then the form |
| 4 | Look at the **Meal** list in step 1 of the form | Breakfast, Lunch, Dinner, Deity Offering, Catering order, Outside event |
| 5 | Choose **Lunch** | **Ready by** fills in with the temple's lunch time |
| 6 | Choose **Deity Offering** | **Ready by** is *empty* and must be given — this is deliberate |
| 7 | Choose **Lunch**, then look at step 2, **Who is expected**, before touching anything | **Adults**, **Children** and **Seniors** all read **0**, and **Cooking for** reads **0 people**. Nothing has been assumed on your behalf |
| 8 | Go to step 3, **Preparations**, and tick **Khichdi** without going back for a head count | Khichdi's quantity box is **empty** — no figure, and no nought |
| 9 | Look at the **Save this meal** button | It is **dead**, and beside it: **Say how many people are expected** |
| 10 | Go back to step 2 and type **150** into **Adults** | Khichdi's quantity box **fills in by itself**, and **Cooking for** reads **150 people** |
| 11 | Change Adults to **300** | The quantity **doubles**, live, without you touching it |
| 12 | Put **150** back, then add **10 Children** and **10 Seniors** | **Cooking for** reads **164 people** — 150 + (10 × 0.6) + (10 × 0.8) — not 170. Do that sum yourself |
| 13 | Press **Save this meal** | The meal appears on the day, showing its time, kind, preparations and head count, with a stock badge |
| 14 | Plan a second meal the same day — **Dinner**, **150** adults, **Khichdi** | Both appear, **earliest ready-by first**, whichever order you entered them in |
| 15 | Change one meal's **Ready by** to earlier than the other and re-check the order | The list re-orders by time |
| 16 | Choose **Catering order** | You are asked for the client and the venue as well as a time; leaving either out is refused (`KMS-4944`, `KMS-4945`) |
| 17 | Choose **Outside event** | You are asked for the venue, but not a client |
| 18 | Press **Cancel** on a planned meal | It is no longer counted as cooking to be done |
| 19 | Try to save a meal with **no preparation at all** | Refused, in plain language — and note that the reason given is about the preparation, not about the head count |
| 20 | Close the day (**Close**, or the Escape key) and look at the cell | The day now shows what is planned on it |
| 21 | Click a **past** day | It opens, shows what was planned, and offers no way to add — read-only by design |
| 22 | Click a **fasting day** (one with a mark) and try to plan something with grains | You are warned and asked to confirm (`KMS-4917`) — UAT-036 covers this properly |
| 23 | As a **temple admin**, open a day and press **Correct this date** | You can change what the calendar says for that day, with a reason. As kitchen staff, that button is not there (UAT-031) |
| 24 | Page to the next month and back | Your planned meals are still there |
| 25 | Fill a whole week — two meals a day for seven days, each with a head count | The month view stays readable and quick |
| 26 | Open the planner on a **phone** | The month is readable; a day opens full-screen and the form is usable one-handed |

### Nobody is counted — the refusal

| # | Do this | You should see |
|---|---|---|
| 27 | Start a new meal: kind **Lunch**, leave all three counters at **0**, tick **Khichdi** | The quantity box is empty and **Save this meal** is dead, with **Say how many people are expected** beside it |
| 28 | Try to save it any way you can find — the Enter key, a second click, the keyboard | It does not save. If it ever does, the server refuses it with **`KMS-4989`**: *This meal has something being cooked, so it needs to know how many people are expected.* Write down exactly how you got it through |
| 29 | Now put **1** into **Children** and nothing else | **Cooking for** reads **1** — 0.6 of a portion, rounded — and **Save this meal** comes alive. One child is a head count somebody made, and must not be rounded away to nobody |
| 30 | Save it, then re-open it | It opens on **its own** figures — 0 adults, 1 child, 0 seniors — not on 100 adults and not on nothing |
| 31 | Open a meal planned **before today's date** that has a head count, and read it | Its figures are unchanged. This rule does not reach backwards and rewrite what was already planned |

### Duplicating last week

| # | Do this | You should see |
|---|---|---|
| 32 | Make sure **last week** has meals on it, each with a head count. Switch to **Week** view — the **Duplicate last week** button lives there and nowhere else — move to **this** week and press it | Last week's meals appear on this week's matching days, carrying their kinds, preparations, ready-by times and **head counts** |
| 33 | Open one of the copies | The **same** head count as the meal it came from |
| 34 | Check what did **not** carry across | The day's calendar facts are worked out afresh for the new date — this Wednesday is not last week's festival |
| 35 | In **Week** view, move to a week where **nothing** was planned the week before, and press **Duplicate last week** | *Nothing was planned last week, so there was nothing to copy.* — a sentence, not an error |
| 36 | If the temple has any meal planned **without** a head count — one written before this rule — try to copy the week it is in | The copy is **refused**, and the refusal **names the date and the kind** of the meal at fault (`KMS-4989`). It must not quietly drop that meal and copy the rest, leaving you believing the week came across whole |

## It passes if

- [ ] The calendar shows what is planned and nothing else; the detail is inside a day.
- [ ] A day opens full-screen with the calendar facts, the day's meals, and the form, in that order.
- [ ] Everyday meals arrive with a ready-by time; occasional ones insist on being given one.
- [ ] The day's meals list in ready-by order, not the order they were entered.
- [ ] Catering asks for a client and a venue; an outside event asks for a venue.
- [ ] Nobody is asked what sort of day it is.
- [ ] All three counters open at **0**, and nothing is assumed on the planner's behalf.
- [ ] A preparation's quantity box stays **empty** until a head count is typed, then fills and rescales live.
- [ ] A meal with a preparation and nobody counted **cannot be saved**, in the form or by the server (`KMS-4989`).
- [ ] A single child is a head count, and saves.
- [ ] Duplicating a week carries the head counts, and refuses rather than quietly skipping a meal that has none.
- [ ] A planned meal can be cancelled; a past day cannot be planned into.
- [ ] A meal with no preparation is refused, and says so in its own words.
- [ ] Day / Week / Month all work, and **Today** returns to today.
- [ ] It works on a phone.

## Watch out for

- **A date that lands a day out.** Check the cell the meal appears on, especially near a month boundary
  and late in the evening. Everything in this product runs on the *temple's* day, in India.
- **Any head count you did not type.** The counters open at 0 and stay there. If a form ever arrives
  carrying **100 adults**, or fills a quantity box before you have said who is coming, that is the
  exact fault this change removed — record it as Major, with the screen you found it on.
- **A quantity box showing `0` rather than being empty.** A nought is an answer; an unanswered
  question is not a nought. Minor, but write it down.
- A ready-by time that pre-fills for a kind that should ask, or an empty one for Lunch or Dinner.
- The day view opening but leaving you unsure how to get out of it.
- The meal list ordered by entry rather than by time.
- Whether an already-planned meal can be **edited** (servings, recipe, time) rather than only cancelled
  and re-created. Record what you find.
- Recipes that were archived or belong to another temple appearing in the picker.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT032-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
