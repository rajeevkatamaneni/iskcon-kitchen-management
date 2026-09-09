# Outstanding build list — Rajeev's review of 2026-08-23

**Status: OPEN. This file is binding on every session until Rajeev says otherwise.**

This is the list Rajeev compiled screen by screen ahead of the 2026-08-23 10:00 IST demo,
working down the temple-admin navigation from Today to Inventory. It was written under a
four-hour deadline, so items were cut to make the deploy — **cut means not yet built, never
dropped.**

## The rule

**Amended by Rajeev on 2026-09-07, and this replaces the original rule.** It used to be that an
item left this file only when he had seen it working and said so. He was the only verifier, which
made him the bottleneck and let unverified work pile up — eleven screens deep at one point. It was
also written when there was no way to sign in as most roles; all seventeen test accounts work now
([[uat-test-accounts]] in the session memory), so a session can be each role and press the real thing.

**The two passes, in his words:** *"you do the first verification and fix any issues you find and
when you are happy, mark it done. I will do my own testing after you and report back any issues I
find."*

So:

1. **First pass — the session.** Drive the deployed application as the role the item is written for.
   Fix what you find, ship the fix, verify the fix. When it genuinely does what the item asks, mark
   the item **`DONE — verified <date>`** and write underneath *what you actually did to check it*,
   in enough detail that Rajeev can repeat it or disagree with it. One line of evidence beats a tick.
2. **Second pass — Rajeev.** He tests after you and reports anything you missed. An item he reopens
   goes back to unverified with his finding recorded against it.

**Do not delete an item's block when you mark it done.** He needs the description to test against.
The block stays, marked, until he has had his pass; the file goes when he says it goes, along with
the pointer at the top of `CLAUDE.md`.

**What you must not mark done.** Anything whose test is whether it *feels* right — wording, density,
spacing, whether a chosen word is the right word. Those are his and stay his. Say plainly that the
thing is built and reads sensibly, and leave the judgement open. Where an item is explicitly
addressed to him — P1 says *"Rajeev to accept or reject that one-line change"* — that is not yours
whatever it looks like on screen.

**Still do not remove an item** because it looks stale, because a later conversation did something
adjacent, or because you cannot reproduce it. That half of the original rule stands.

If you are a new session and this file still exists: read it in full before you plan anything
else, and offer the outstanding items to Rajeev before starting new work.

---

## Navigation

### N1 — Menu scroll position on login · **DONE — verified 2026-09-08**
*Signed in as Temple Admin: the sidebar opened scrolled to the top with Today selected. Scrolled it down to the People/Giving sections, clicked Donations, and after navigation the sidebar held that position with Donations highlighted. Both halves.*
On login the sidebar must be scrolled to the top, where Today lives. Today's dashboard is
correctly selected, but the menu keeps the scroll position from the previous session. Once
logged in, the scroll position must then persist wherever the admin scrolls to, along with
the menu option they have picked.

### N2 — Meal planner navigation · **BUILT 2026-09-08 (T-049) — NOT YET SEEN WORKING BY RAJEEV**
***Built, not accepted.*** *The missing **Today** button now exists on the planner. It was not added a fifth
time beside the four that already existed — it went into the shared `PeriodNav`, which the planner, the
calendar and three report screens all use, so there is now **one** implementation and it cannot drift apart
again. The calendar's own header button is deleted in favour of it. The three report screens do not show it,
by leaving the new prop off rather than by a flag. Ships in wave 4e-2. **This entry stays here until Rajeev
has navigated the planner two days forward and pressed Today himself.**


***The root cause you named is fixed.*** *The planner's middle control now names the period — "Sat, 12 Sept 2026" in day view, "September 2026" in month view — instead of saying "Today". The rounded Day/Week/Month box is on both screens, and the Vaishnava calendar has the matching Month/Week/Year box.*
***What was still wrong, on the morning of 2026-09-08, and is what T-049 built:*** *the calendar has a **Today** button beside its period control; **the planner has none**. Navigated the planner forward two days and there is no way back to today — the highlighted pill on the middle control is a current-period indicator, not a button (clicking it does nothing), and the only "Today" on the page is the sidebar link to the dashboard. Browser back or editing the URL are the only routes home on a screen used daily.*
*This entry asked for the planner's navigation to be a shared copy of the calendar's — "same functionality, look and feel, and placement" — and this is the one control where they differ. Raised for the next wave.*
Broken for the third or fourth time. The permanent fix is that the planner's navigation is a
*shared* copy of the Vaishnava calendar's — same functionality, look and feel, and placement.
The one thing to carry the other way, into both, is the rounded box around Day / Week / Month
/ Year. (Root cause found: the planner's middle control was both the period label and the
Today button, so in the current period it said "Today" and never named the month.)

---

## Today dashboard

### T1 — "Record them in the planner" goes somewhere useless · **DONE — verified 2026-09-08, all six claims**
*Followed the Today nudge. It opens `/planner/catch-up` headed **"Meals that were not recorded"**, with no Day/Week/Month navigation, one card per day, only that day's unrecorded meals, and Saturday 5 September ahead of Sunday 6 — oldest to newest. Four of the six claims hold.*
*The other two were blocked this morning and are **now verified**, after T-043 shipped in wave 4c. Originally: pressing Record this meal did nothing at all — it sent `POST /meal-services/record` without `eventName`, got a 404, and showed none of it.*

***Re-tested after the wave-4c deploy.*** *The same press now answers. First it refused, visibly and correctly — "There isn't enough stock to cook this… quote `KMS-400042`" — which is true: the planner had already flagged two of those preparations "Short of ingredients", and the store never held them. Recorded them as **not made**, which is the truthful record for a meal whose ingredients the temple never had.*

*- **The day vanished.** Saturday 5 September disappeared from the list, leaving only Sunday.*
*- **The success message appears:** "Sunday, 6 September is recorded. The store room now knows what those preparations actually drew."*
*- **The all-caught-up message appears**, with the thank-you this entry asked for: "You are all caught up. Every meal of the last week has been written down, and the store room agrees with the kitchen. Thank you — this is the part nobody sees and everything else rests on."*
*- **And the nudge on Today is gone** — the "2 meals from earlier this week haven't been recorded yet" banner that this whole entry is about no longer appears.*
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

### P1 — Day view, first card · **DONE (layout) — verified 2026-09-08 · the ghost-button change is still Rajeev's to accept**
*Planner day view, 5 September: "Open this day" and "Open the calendar" are gone; tithi/nakshatra/masa sit top right; sunrise/sunset below them; the festival badges below that; date, day and the staff/volunteer counts on the left. Every layout instruction met.*
***Not mine to close:*** *the resting hairline border given to every `ghost` button site-wide — this entry says "Rajeev to accept or reject that one-line change", so it stays open for him.*
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

### P2–P3 — The individual meal section · **DONE (structure) — verified 2026-09-08 · the wording is Rajeev's call**
*Reads "Bhagavad Gita Parayanam · Event · Ready by 15:30" — meal name first, then the qualified time. Below it "50 adults, 200 children, 10 seniors expected · 178 servings" — people expected, then servings. The note sits below that in its own weight. Structure is as asked.*
***Left open for Rajeev:*** *whether "Ready by" is the right qualification, and the servings/plates inconsistency — the month tiles show neither word, so the day-vs-tile mismatch could not be observed and may already be gone.*
The time is qualified as "Ready by 12:00". Note: the day view now says "servings" where the week
and month tiles still say "plates" — one of them should change.
Keeping it as its own section is right; its contents are cluttered and duplicated.
- Meal name first, then the time (today it reads "07:30 Breakfast").
- Qualify the time in one to three words — is it when cooking starts or when service starts?
  e.g. "Service starts 7:30" or "Ready by 7:30". Pick something simple and elegant.
- Below the meal name: people expected, then number of servings, in the same colour as the date.
- Below that: any additional note ("Sunday feast — expect walk-ins."), keeping its current
  colour and weight.

### P4 — The meal's buttons and the job card · **VERIFIED 2026-09-08 except the print/PDF question, which is still open**
*On the 12 September meal: the Job card button is gone, there is one **Download job card** button, and **Record actuals** then **Edit** sit top right in that order with "Not yet recorded" above them. Ticking "Include the recipes" reveals the language picker, and it offers **exactly 23** — English plus all 22 scheduled Indian languages, opening on English.*
*Edit is hidden on past days, which is why it is absent from the catch-up screen and from 5 September; that looks deliberate rather than missing.*
***Still outstanding, unchanged:*** *why the print path is 5–10× faster than the PDF for identical output. Nobody has investigated it, and I did not — it is a question, not a check.*
***Left open for Rajeev:*** *whether "Record actuals" is the one or two words you wanted.*
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
  that a cook in a Kannada temple reads Kannada.) **BUILT — backend and UI both.** The picker
  renders all 23 from the application's own list (`MealServices.tsx`), deliberately *not* from the
  server's answer, so a slow or failed call cannot silently shrink it back to English; the server is
  asked only which one to open on. `JobCardService.translateAll` produces each recipe on demand and
  caches it, so only the first card in a language costs a round trip, and a recipe that cannot be
  produced prints in English with a line saying so.
  *(Corrected 2026-09-06. This bullet said "UI not built" and contradicted the summary four lines
  above it in the same entry. Rajeev re-made the same decision today from the stale text, and a
  stale javadoc on `JobCardService` said the opposite of its own code — the three together turned a
  finished feature into a reported gap.)*

### P5 — The preparations under a meal · **DONE — verified 2026-09-08**
*Clicked "Akki Rotti" on the 12 September meal: a recipe panel opens over the planner with ingredients, quantities and method, scaled to the meal (200 pieces, 2 per devotee). Closes on X and on **Escape**, returning to the planner with focus back on the link that opened it. "Ingredients ready" sits immediately beside the name; "80 pieces" on the right. **"Swap or edit" and the unconfirmed Cancel are both gone** — that Cancel deleted a preparation with no confirmation, and it is the thing I most wanted to see absent.*
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

### P8 — Recording actuals: planned / cooked / consumed · **DONE — verified 2026-09-08. The reopen is now BUILT and UNVERIFIED: on `main`, not yet deployed, nobody has pressed it.**
***The three boxes are exactly as asked:*** *Planned / Cooked / Consumed per preparation, each pre-filled with the plan, each in that preparation's own unit (pieces, L). A "Not made" checkbox per row, a note field, and the honest line "Recording draws the ingredients from stock, against what was cooked." Setting Consumed to 460 against Cooked 500 immediately showed **"Puran Poli: 40 pieces left over"** — live arithmetic nobody asked for and worth keeping.*
***The save was broken and is now fixed.*** *Recording an event meal used to 404 silently; T-043 shipped in wave 4c and it was re-tested after the deploy. **The success message and the close both work** — see T1 for the wording. A refusal is now shown at the button too, with its code, where before there was nothing.*
***The reopen is now built and awaiting your test — 2026-09-08.*** ***T-007** shipped to `main` in wave 8 and this item stays open until you have pressed it. **It is not on staging yet**: that release was commit-and-push only, so there is nothing to test on the live site until the next deploy. What to expect when there is: as a Temple Admin, open a recorded meal in the planner and press **Correct the figures**. The stock is reversed and redrawn in the same transaction that marks the meal, so neither half can land without the other; the line then reads "640 kg cooked, corrected from 400 kg by \<name\> on \<date\>" with the original recording still underneath. A second correction is refused (KMS-400137). Kitchen staff who may record a meal see no such button. **One thing to know before you judge it:** cost per serving deliberately does **not** move — dishes are costed at what was planned, and your ruling today that costing should follow actuals is recorded as a separate unbuilt decision, not shipped here.*
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

### R1 — Swap the Edit and Delete buttons in the recipe detail view · **DONE — verified 2026-09-08**
*Recipe detail for Akki Rotti: Edit first, then Delete, top right, with Delete in the warning colour. The destructive one is now second and visually distinct.*

### R2 — Recipe list density · **BUILT, and the judgement is yours — looked at 2026-09-08**
*At a 1512px window the list renders **three columns**, each card carrying the recipe name, its category beneath, and a one-line description. It is not the two-column layout with a wide gap between name and tag that this entry complained about, so the change was made.*
*Whether the density is right is exactly what this entry says is the thing to say, and it is not mine to say. Left open.*
Built directly rather than mocked up, for time: three columns on a laptop, four on a wide screen,
with the name and category stacked. If the density is wrong, that is the thing to say.
Two columns with a large gap between the recipe name and the type tag. Try three or four
columns without crowding. Mock up different column counts and any other space-efficient
arrangement, keeping it easy to read and use.

### R3 — Recipe detail from search · **CLOSED 2026-08-23, verified by Rajeev**
Built as an overlay, then withdrawn the same day at his instruction: a recipe read in a floating
panel cannot be edited or deleted, because those controls live on the recipe's own screen, and
reading one is usually the step before changing it. What survives is the part the overlay was for
— not losing your place. A search result opens the full page, and the way back reads **"Back to
search"** with the term carried in the address when the search box had text, **"Recipes"** when it
did not. The rule follows where the reader came from, not what kind of recipe they landed on.

The meal planner keeps its overlay, deliberately: there you are half-way through building a day.

---

## Inventory

### I1 — "Reorder threshold" · **DONE — verified 2026-09-08**
*On Add to inventory the field is labelled **"Tell me when stock drops below"** with an info icon — the human wording, not "Reorder threshold". Choosing an ingredient enables a unit **selector** beside "How much is on the shelf now" (showed Kg for Onion), and the ingredient dropdown names each unit too — "Onion — kept in Kg". Unit shown and changeable, as asked.*
*Worth knowing: the inline editor on the inventory list shows the unit as fixed text with no selector. Its accessible label is "Tell me when Almond drops below", so the wording is there too. Changing an ingredient's unit once stock exists is a conversion problem, so read-only there looks deliberate rather than missed.*
The field never said what unit it was in, and "Reorder threshold" is robotic. Show the unit and
let the user change it, and label the field in language a human relates to. (Built as "Tell me
when stock drops below", with the ingredient's unit on the field and a unit selector. Note: it
is *not* labelled "how much do you have on hand" — on-hand is the sum of the ledger and cannot
be typed in; that confusion is what caused I2.)

### I2 — Stock that exists but is not tracked · **DONE — verified 2026-09-08**
*Confirmed Onion was untracked — it appeared in Add to inventory's list of ingredients not yet held. Recorded an in-kind donation of 25 Kg of Onion, then opened Inventory: **Onion is now listed at 25 Kg on its own**, with no manual step. An ingredient never held before starts being tracked automatically, which is what this item asked for.*
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
