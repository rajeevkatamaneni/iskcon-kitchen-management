# Outstanding build list — Rajeev's review of 2026-08-23

**Status: OPEN. This file is binding on every session until Rajeev says otherwise.**

This is the list Rajeev compiled screen by screen ahead of the 2026-08-23 10:00 IST demo,
working down the temple-admin navigation from Today to Inventory. It was written under a
four-hour deadline, so items were cut to make the deploy — **cut means not yet built, never
dropped.**

## The rule

Do not remove an item from this file because it looks stale, because a later conversation
did something adjacent, or because you cannot reproduce it. An item leaves this file only
when **Rajeev has seen it working and said so**. When he does, delete that item's block and
note it in `docs/CHANGELOG.md`. When every item is gone, delete this file and the pointer at
the top of `CLAUDE.md`.

If you are a new session and this file still exists: read it in full before you plan anything
else, and offer the outstanding items to Rajeev before starting new work.

---

## Navigation

### N1 — Menu scroll position on login · **BUILT 2026-08-23, unverified**
On login the sidebar must be scrolled to the top, where Today lives. Today's dashboard is
correctly selected, but the menu keeps the scroll position from the previous session. Once
logged in, the scroll position must then persist wherever the admin scrolls to, along with
the menu option they have picked.

### N2 — Meal planner navigation · **BUILT 2026-08-23, unverified**
Broken for the third or fourth time. The permanent fix is that the planner's navigation is a
*shared* copy of the Vaishnava calendar's — same functionality, look and feel, and placement.
The one thing to carry the other way, into both, is the rounded box around Day / Week / Month
/ Year. (Root cause found: the planner's middle control was both the period label and the
Today button, so in the current period it said "Today" and never named the month.)

---

## Today dashboard

### T1 — "Record them in the planner" goes somewhere useless · **BUILT 2026-08-23, unverified**
(`/planner/catch-up`, reachable only from the Today nudge.)
Today says "10 meals from earlier this week haven't been recorded yet"; the link opens today's
meal plan, which does not help — the admin does not remember which meals are outstanding.

What it must do instead: open a **special planner view**, identical in appearance to the
current planner, but:
- no Day / Week / Month navigation — it says **"Meals that were not recorded"**;
- one card per day, containing **only** the meals of that day that were not recorded;
- as many day cards as there are days with unrecorded meals, **oldest to newest**;
- recording a day's actuals shows a success message and that day vanishes;
- when none are left, a "you are all caught up" and thank-you message;
- reachable **only** through the "Record them in the planner" link on Today.

---

## Meal planner

### P1 — Day view, first card · **BUILT 2026-08-23, unverified**
The site-wide sweep found 12 more instances of the hover-only button. Rather than edit twelve
files, the `ghost` button variant was given a resting hairline border, so every one of them now
looks like a control at rest. **Rajeev to accept or reject that one-line change.**
Date, day, staff and volunteer availability on the left are perfect. The rest is a mess.
- Remove the "Open this day" and "Open the calendar" buttons on the right.
- Those two look like plain text until hover, when they grow a rounded box and suddenly look
  like buttons. **Sweep the whole site for this pattern and report every other instance** —
  all of them to be fixed or removed.
- Move tithi / nakshatra / masa to the top right corner, where the festival-and-fast line
  currently sits.
- Sunrise/sunset goes below that.
- The festival-and-fast line (or "No festival or fast on this day") goes below that.

### P2–P3 — The individual meal section · **BUILT 2026-08-23, unverified**
The time is qualified as "Ready by 12:00". Note: the day view now says "servings" where the week
and month tiles still say "plates" — one of them should change.
Keeping it as its own section is right; its contents are cluttered and duplicated.
- Meal name first, then the time (today it reads "07:30 Breakfast").
- Qualify the time in one to three words — is it when cooking starts or when service starts?
  e.g. "Service starts 7:30" or "Ready by 7:30". Pick something simple and elegant.
- Below the meal name: people expected, then number of servings, in the same colour as the date.
- Below that: any additional note ("Sunday feast — expect walk-ins."), keeping its current
  colour and weight.

### P4 — The meal's buttons and the job card · **MOSTLY BUILT 2026-08-23, unverified**
Built: Job card button removed, one "Download job card" button, record button renamed "Record
actuals", both acts moved top right with the state above them, all 23 languages offered and
translated live on demand. **Still outstanding: why the print path is 5–10× faster than the PDF.**
- Keep Edit and the recording button; **remove the Job card button**.
- Move both to the top right, where "Not yet recorded" sits, ordered: record button, then Edit.
- "Not yet recorded" moves above the record button.
- **Rename "Record what went out"** — too long, and wrong: it records planned vs reality for
  both what was cooked and what was consumed. Find one or two words meaning "record the ground
  reality / what actually happened / what came back from the trenches".
- The job card exists twice: a fast printable version in a new tab, and a slow PDF. Keep both
  capabilities but drive everything from the Download PDF area, with one button.
  - Rename "Download PDF" to **"Download Job Card"** and make it a proper button.
  - For now, clicking it downloads the PDF.
  - Investigate why the print version is 5–10× faster than the PDF for identical output.
- The language dropdown offers only English. It must offer all Indian languages plus English.
  (Decided 2026-08-23: offer all 22 scheduled languages and translate live on demand — the
  recipe catalogue's shipped translations are not to be relied on, because there is no rule
  that a cook in a Kannada temple reads Kannada.) **Backend built 2026-08-23; UI not built.**

### P5 — The preparations under a meal · **BUILT 2026-08-23, unverified**
- Preparation name is good. Make it **clickable to read the recipe** in a panel/overlay above
  the planner, closable with X (and Escape), returning to the planner.
- Put the "short of ingredients" label immediately beside the name, not away from it.
- Show the planned quantity and unit on the right, in the same size and colour as the name.
- **Remove the "Swap or edit" button and the Cancel button.** Cancel deletes a preparation with
  no confirmation, which is unacceptable; both must go.

### P6 — Volunteer shortfall → post a shift · **NOT BUILT**
When the crew a meal needs exceeds the staff working that day, show a link to create a
volunteer shift request. It opens the "Post a shift" form as a layer over the planner, with the
title derived from date and meal ("Lunch preparation on September 1 2026") and the date and
capacity pre-filled. The admin completes it, posts, and lands back in the planner where they were.

### P7 — Show an existing shift request · **NOT BUILT**
"Who will run it" must indicate when a volunteer request already exists for that day and time,
and let the admin open and edit it in a layer over the planner — view, edit and save, or close
without changes — without leaving the planner.

### P8 — Recording actuals: planned / cooked / consumed · **MOSTLY BUILT 2026-08-23, unverified**
**Still outstanding: reopening a recorded meal to correct it.** The three boxes, the success
message and the auto-close are built. Correcting a recording means unwinding and redrawing the
stock the meal drew, and that was judged too risky to write blind an hour before the demo deploy.
The returned job card carries how much was cooked and how much was actually served; there is
nowhere to record either. Three boxes per preparation — **Planned, Cooked, Consumed** — each
pre-filled with the planned amount, in that preparation's own unit. Recording must show a
success or error message and close itself on success. The admin must be able to reopen a
recorded meal and correct mistakes (agreed: a correction supersedes the original and the
history survives).

---

## Recipes

### R1 — Swap the Edit and Delete buttons in the recipe detail view · **BUILT 2026-08-23, unverified**

### R2 — Recipe list density · **BUILT 2026-08-23, unverified**
Built directly rather than mocked up, for time: three columns on a laptop, four on a wide screen,
with the name and category stacked. If the density is wrong, that is the thing to say.
Two columns with a large gap between the recipe name and the type tag. Try three or four
columns without crowding. Mock up different column counts and any other space-efficient
arrangement, keeping it easy to read and use.

### R3 — Recipe detail from search · **BUILT 2026-08-23, unverified**
Searching shows new recipes (with a +) and existing ones (without). Clicking either opens the
detail in a new page. It should instead open as a layer over the search results, closable with
a button or X, and with Escape, returning to the results. (Shares the overlay with P5.)

---

## Inventory

### I1 — "Reorder threshold" · **BUILT 2026-08-23, unverified**
The field never said what unit it was in, and "Reorder threshold" is robotic. Show the unit and
let the user change it, and label the field in language a human relates to. (Built as "Tell me
when stock drops below", with the ingredient's unit on the field and a unit selector. Note: it
is *not* labelled "how much do you have on hand" — on-hand is the sum of the ledger and cannot
be typed in; that confusion is what caused I2.)

### I2 — Stock that exists but is not tracked · **BUILT 2026-08-23, unverified**
Rice was untracked; adding it showed 652 kg on hand, with +245 kg and +62 kg movements
predating the record. Anything received as a delivery or an in-kind donation must be added to
inventory automatically, and an ingredient never held before must start being tracked
automatically. Nothing already held, bought or donated may remain untracked. (Built:
`StockMovementService.track()` plus backfill migration `V70`.)

---

---

## D1 — Wipe the tenant and seed a realistic day-one dataset · **NOT STARTED**

Asked for 2026-08-23 at 08:55 IST, an hour before the demo. **Deliberately not started then**:
it destroys the only populated data the demo had, and a wipe that is not followed by a complete
reseed leaves an empty application in front of the guests. It is hours of work, not minutes.

**Delete everything ISKCON South Bangalore holds, except:** staff profiles, staff work schedules
(drop former staff), devotee profiles, the vendor list, the Razorpay configuration and the email
configuration.

**So: clear** audit data, donations, inventory, planned meals, recipes belonging to this temple,
volunteer shifts, leave, wishlist, notices, payments, invoices, purchase orders, ingredients.

**Then seed it as a real Bangalore temple would on day one:**

1. **Inventory first.** Imagine the temple walked its whole store on **9 August** and typed in what
   was on the shelves. Real quantities, real consumables, real storage locations.
2. **Fifteen days of meal plans from 10 August.** Gauge plausible foot traffic (Google Maps
   popular-times trends or similar) and set adults, children and seniors per meal from it.
3. **Purchase orders for the shortfall** against that fifteen-day plan, sent to the preferred
   vendors.
4. **Deliveries that behave like deliveries** — same-day from some vendors, next-day from others,
   several days from the rest. Not everything arrives at once.
5. **Receiving with rejections.** Accept most, reject a few with reasons; the vendor redelivers on
   a later date and those are accepted.
6. **Vendor payments**, mostly by cheque, a few in cash.
7. **Volunteer shift requests** wherever the plan is short of people, with volunteers taking
   60–70% of them and the rest left partly unfilled.
8. **Cash donations.**
9. **At least five equipment wish-list items**, with donations towards some of them.
10. **A well-made HTML email** to devotees about the coming Janmashtami festivities, calling for
    donations and volunteers — **saved as a draft**, not sent. Rajeev previews it at a demo and
    sends it live himself.
11. **Staff salary payments** — mostly cheque, some cash — plus a couple of cash advances.
12. **Leave requests** in all three states: pending, approved, denied.
13. **Two back-dated hires, both terminated**, one of them marked ineligible for rehire at any
    ISKCON temple with a convincing reason.

**Do this against a written plan, in dependency order, and check the result screen by screen** —
it is the dataset every future demo and every piece of UAT will run on.

## Still to come

Rajeev's review stopped at Inventory. Everything below Inventory in the navigation has not been
reviewed yet, and more items are expected.
