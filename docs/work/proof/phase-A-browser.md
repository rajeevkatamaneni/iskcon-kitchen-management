# Phase A browser test — staging, commit 74d3535 (api-00166-8j8, web-00154-874)

Tested 2026-09-14 on https://kms-staging-web-bnpkv5hfrq-el.a.run.app, driving Chrome through the
Claude-in-Chrome extension (port 9222 was not listening). Sessions were signed in by minting a Firebase
token for each test account and writing it to IndexedDB; no password was typed. Screenshots are in the
session scratchpad, `.../scratchpad/shots/` (not in the repo); paths below are relative to that folder.

Test data created, all named "UAT-test": an Event meal "UAT-test Phase A" on Monday 21 September 2026
(id c8c18171-b244-4cac-a2bf-97d00bd1b20c) with shift "UAT-test Phase A preparation on Monday,
21 September 2026" (id b4e4ad6b-0db1-4606-8c71-476af21e6664), location "UAT-test kitchen".

Accounts: Temple Admin = ikms.temple-admin.1; Kitchen Staff = ikms.kitchen-staff.1; Volunteer =
ikms.volunteer.3. **There is no Kitchen Manager account on staging** (the users list has none), so that
role was not driven. In `RolePermissions.java` Kitchen Manager and Kitchen Staff both hold
MANAGE_MEAL_PLANS and MANAGE_VOLUNTEER_SHIFTS, so the Kitchen Staff runs cover the same screens.

## Results, step by step

1. **Plan a meal, ask for volunteers — Temple Admin. Pass.** Planner → 21 Sept → Add a meal → Event,
   name "UAT-test Phase A", 50 adults, Akki Rotti, People needed 5. "Ask for volunteers" appeared in
   section 4 as soon as People needed was set. Pressed it: layer "Post a shift / For UAT-test Phase A ·
   Monday, 21 September 2026", "Saved when you save the meal.", no meal checkbox, Volunteers requested
   prefilled 5, date locked ("The day of the meal. It cannot be changed here."), buttons Cancel / Done.
   Filled 10:00–12:00, location, pressed Done: section 4 showed "View volunteer shift" and "Not saved
   yet. It is saved with this meal." `01-TA-ask-for-volunteers-layer.jpg`, `02-TA-view-volunteer-shift-after-done.jpg`
2. **Abandon without saving — Temple Admin. Pass.** With the meal and shift unsaved, pressed the page's
   Cancel: in-app layer "Leave without saving? Nothing you changed here has been saved, including any
   volunteer shift." with Stay on this page / Leave without saving. Pressed Leave. 21 Sept still says
   "Nothing planned for this day", and `GET /api/v1/shifts` listed only the three seeded shifts — no
   orphan. `03-TA-leave-without-saving.jpg`
3. **Save meal + shift together — Temple Admin. Pass.** Re-did step 1, pressed View volunteer shift
   (values kept, button Done), Done, Save this meal. Planner shows the meal with "0 of 5 signed up";
   the API shows one shift with `mealId` c8c18171…, Event / UAT-test Phase A, 10:00–12:00, capacity 5.
   `04-TA-reopened-layer-values-kept.jpg`, `05-TA-meal-saved-with-shift.jpg`
4. **View/edit the shift from the planner — Temple Admin. Pass.** Edit on the saved meal: section 4
   shows "2 staff · 0 volunteers · 2 of 5", "View volunteer shift", "0 of 5 signed up". Pressed it:
   layer titled "Edit a shift", button Done, date locked. Cancel closed it; page Cancel left without a
   prompt (nothing changed). `06-TA-edit-meal-rostered-2-of-5.jpg`, `07-TA-edit-shift-layer-from-planner.jpg`
5. **Sign up — Volunteer. Pass.** Available shifts listed the new shift (10:00–12:00, UAT-test kitchen,
   0/5). Pressed Sign up: "You're signed up." Roster API: Lalita Devi Dasi, SIGNUP. `08-VOL-signed-up.jpg`
6. **Change times with a signup, from the planner — Kitchen Staff. Pass.** Edit meal → View volunteer
   shift → End time 12:30 → Done ("1 of 5 signed up. Your changes are saved with this meal.") → Update
   this meal. Warning layer "The volunteer shift has new times / 1 volunteer is signed up. They'll be
   told the new times." with Go back / Update this meal. Confirmed: "UAT-test Phase A was saved."
   Roster API: times 10:00–12:30, Lalita still signed up, broadcast message exactly
   `The times changed to 10:00 to 12:30.` sent by Gopal Das, EMAIL, SENT. No "moved".
   `09-KS-times-changed-in-layer-no-warning.jpg`, `10-KS-after-done-draft.jpg`, `11-KS-times-changed-warning-on-update.jpg`, `12-KS-meal-updated.jpg`
7. **Volunteer shifts list labels — Kitchen Staff. Pass.** "Kitchen help for Lunch … For Lunch,
   15 September" and "UAT-test Phase A preparation … For UAT-test Phase A, 21 September"; plain shifts
   have no label. `13-KS-shift-list-meal-labels.jpg`
8. **Post a shift — Kitchen Staff. Pass.** Fields in order Title, Date, Volunteers requested, Start
   time, End time, Location, Reminder hours before, Description; no meal checkbox; sentence "Kitchen
   help for a meal? Ask from that meal in the planner." with "in the planner" a link to /planner.
   Posted "UAT-test plain shift", 22 Sept 09:00–11:00, 2 people: "UAT-test plain shift is posted."
   `14-KS-post-a-shift-form.jpg`, `15-KS-plain-shift-posted.jpg`, `16-KS-plain-shift-edit.jpg`
9. **Edit a meal shift from the list, times changed — Kitchen Staff. Pass.** Edit on the UAT-test meal
   shift: Date shows "21 September" read-only, "For UAT-test Phase A. The date and the meal cannot be
   changed here. Open this meal" (link to /planner/meal/c8c18171…); no control to make it a plain
   shift. Start 10:30 → Save changes → "This shift has new times / 1 volunteer is signed up. They'll be
   told the new times." → Save changes → back on the list, saved at once, 10:30–12:30, 1/5. Roster API:
   Lalita still signed up; message `The times changed to 10:30 to 12:30.`, WHATSAPP, PENDING.
   `17-KS-meal-shift-edit-from-list.jpg`, `18-KS-list-edit-times-warning.jpg`, `19-KS-list-edit-saved.jpg`
10. **Cancel a meal that has a shift — Temple Admin. Pass.** Planner 21 Sept → Cancel this meal: layer
    "Cancel UAT-test Phase A? / This meal has a volunteer shift. 1 volunteer is signed up. Cancelling
    the meal cancels the shift too, and they will be told." with Keep it / Cancel this meal. Confirmed:
    "UAT-test Phase A was cancelled. 1 volunteer was told." and the day is empty again. API: shift
    CANCELLED, reason "The meal this shift was for has been cancelled."; the volunteer's
    `/api/v1/my-shifts` is empty. (No admin API shows the cancellation message itself, so its delivery
    was not checked.) `20-TA-cancel-meal-warning.jpg`, `21-TA-meal-and-shift-cancelled.jpg`
11. **Today — Temple Admin. Pass.** Monday 14 September, 405 servings across 3 meals (Breakfast 97,
    Lunch 184, Dinner 124), each meal with its dishes and "Not yet recorded", Working today "2 · 0,
    Lunch 2 of 6". `22-TA-today.jpg`
12. **Cost per serving — Temple Admin. Pass.** September 2026: Breakfast 9, Dinner 9, Lunch 9, Event 1 =
    28 meals, which is the reseed count; the cancelled UAT-test event is not counted. `23-TA-cost-per-serving.jpg`
13. **Shopping list — Temple Admin. Pass (loads, grouped by vendor).** `24-TA-shopping-list.jpg`
14. **Record a meal — Temple Admin. Pass.** Planned Event "UAT-test record" today (14 Sept, ready 09:00,
    20 adults, Akki Rotti 40 pieces), Save this meal, Record actuals: panel with Planned / Cooked /
    Consumed prefilled 40, note field, "Recording draws the ingredients from stock, against what was
    cooked." Pressed Record this meal: "Event is recorded. The ingredients have been drawn from stock
    against what was cooked.", "Recorded by Karuna Murti Das.", "Correct the figures" offered.
    `25-TA-record-panel-open.jpg`, `26-TA-record-panel.jpg`, `27-TA-meal-recorded.jpg`
15. **Job card — Temple Admin. Pass.** Fetched `/api/v1/job-cards/print?mealId=6c9a2865…` (the print
    view the Download button uses): HTTP 200, card number EC-2026-0007 (seed recorded six meals, so the
    counter restarted as ruled), event name, date, head count, Akki Rotti 40 pieces, staff on duty,
    equipment, signatures, recipe. Saved as `31-jobcard-UAT-test-record.html`. The print window itself
    was not opened (it blocks the browser session).
16. **Volunteer after the meal was cancelled. Pass.** My shifts: "No upcoming shifts"; under "Taken off
    in the last week": the UAT-test shift, 21 Sept 10:30–12:30, "Reason: the shift was cancelled."
    `28-VOL-my-shifts-after-cancel.jpg`
17. **Volunteer cannot reach the planner. Pass.** /planner as Volunteer: "Not your page". `29-VOL-planner-refused.jpg`
18. **Today — Kitchen Staff.** Loads with 425 servings across 4 meals and the recorded event marked
    "Recorded". See defect 2 for how the event is named. `30-KS-today-event-unnamed.jpg`

Not driven: Kitchen Manager (no account, see top). Menu history was not in the list and was not opened.

## Defects

**1. Shift form fields have no accessible names.**
- Role: Kitchen Staff (Post a shift, Edit a shift) and Temple Admin (planner shift layer).
- Steps: open Volunteer shifts → Post a shift; or planner → Ask for volunteers.
- Expected: each input is named by its visible label (Title, Date, Start time, End time, Location,
  Description), as Volunteers requested and Reminder hours before already are.
- Actual: the accessibility tree shows those six as unnamed text boxes (for example `textbox [ref_19]`
  for Title, `textbox [ref_22] type="time"` for Start time). A screen reader hears "edit text" with no
  label. Not a D-27 wording item; found while driving the form.
- Screenshot: `14-KS-post-a-shift-form.jpg` (labels are visible, the association is what is missing).

**2. Today names an event meal "Event", not its name.**
- Role: Kitchen Staff (Today).
- Steps: plan an Event named "UAT-test record" for today, record it, open Today.
- Expected: the event shown by its name. The spec has no Today wording, but the code's own rule for
  shift titles (`derivedTitle` in `ShiftLayer.tsx`) says an event is "named by its own name rather than
  as another 'Event'", and the planner and job card both show "UAT-test record".
- Actual: the meal row reads "09:00 Event, 20 servings" and the summary reads "Breakfast 97 · Event 20 ·
  Lunch 184 · Dinner 124". Two events on one day would be indistinguishable.
- Screenshot: `30-KS-today-event-unnamed.jpg`

No departure from "The screens, exactly as ruled" was found.

## For Rajeev (judgement, no mockup)

- **Volunteers requested on a brand-new meal is the full People needed, not needed minus rostered.** On
  an unsaved meal Rostered reads "Not counted yet" (deliberate: `MealComposer.tsx` says a meal the
  server has not seen has no crew figure). So the layer prefilled 5. After saving, the same meal read
  "2 staff · 0 volunteers · 2 of 5", so the shift asked for 5 volunteers where 3 were short. Worth
  deciding whether the composer should count the day's staff before the first save.
- **The times-changed warning comes at "Update this meal", not inside the layer at Done.** That meets
  "warn before saving"; say if you wanted it in the layer as well.
- **Two times-changed messages to the same volunteer went by different channels** two minutes apart:
  the first EMAIL / SENT, the second WHATSAPP / PENDING (the account's number is a test number). Not a
  D-27 rule; flagging in case the channel choice is not what you expect.
- **Cancel on the meal edit screen goes to `/planner/2026-09-21`** (the day page with "The week around
  it") rather than back to the Day view it was opened from.
- **Volunteer's Available shifts: a long meal-shift title squeezes "Sign up" onto two lines.**
  `08-VOL-signed-up.jpg` shows the card before signup.
- **Shopping list shows Curry leaves suggested as "3104 gm"** beside "on hand 3.5 Kg" and "shortfall 3
  Kg". Not part of the rebuild; mixed units in one row.

## Test data left on staging

- "UAT-test Phase A" Event, 21 Sept: cancelled, with its shift cancelled (Lalita Devi Dasi was told).
- "UAT-test plain shift", 22 Sept 09:00–11:00, UAT-test hall, open, nobody signed up.
- "UAT-test record" Event, 14 Sept: **recorded**, so 40 Akki Rotti worth of ingredients were drawn from
  stock, and job card EC-2026-0007 was issued for it.
- Two times-changed messages to ikms.volunteer.3 and one shift cancellation message.
