# Phase A fixes browser retest — staging, commit 6dd436c (api-00167-6xb, web-00155-9h6)

Tested 2026-09-14 on https://kms-staging-web-bnpkv5hfrq-el.a.run.app. Both services were confirmed
serving those revisions (`gcloud run services describe`, 100% traffic) before starting.

Port 9222 was not listening, so Chrome was driven through the Claude-in-Chrome extension. That means
the accessibility checks use the extension's accessibility tree (`read_page`), not CDP
`Accessibility.getFullAXTree`. This is the same tree the original defect was found in. On the edit
page I also checked the DOM: every box has exactly one `<label for>` holding its words, and none is
wrapped in a label.

Sign-in: a Firebase token was minted for each account and written to IndexedDB. No password was typed.
Temple Admin = ikms.temple-admin.1 (Karuna Murti Das). Kitchen Staff = ikms.kitchen-staff.1 (Gopal Das).
Screenshots are in the session scratchpad at `.../scratchpad/shots-fixes/`, not in the repo.

## Result: all three fixes pass. The regression run passes too.

### 1. Every shift form box has a name — Temple Admin and Kitchen Staff. Pass.

In each place below, the tree listed the same eight named boxes: `textbox "Title"`, `textbox "Date"
type="date"`, `textbox "Volunteers requested" type="number"`, `textbox "Start time" type="time"`,
`textbox "End time" type="time"`, `textbox "Location"`, `textbox "Reminder hours before"` and
`textbox "Description"`. Each label also shows up as `label "<words>"`.

- Temple Admin, Post a shift (`/volunteers/new`): all 8 named, inside `form "Post a shift"`. `13-TA-post-a-shift.jpg`
- Temple Admin, Edit a plain shift ("UAT-test plain shift"): all 8 named. `14-TA-edit-plain-shift.jpg`
- Temple Admin, Edit a meal shift (from the list): 7 named boxes. Date is plain text reading
  "14 September" under the visible word "Date", followed by the lock line and "Open this meal". There
  is no Date box here by design, as in T-213's fifth test case. `15-TA-edit-meal-shift.jpg`
- Temple Admin, planner layer (new event → Ask for volunteers): `dialog [Post a shift]` → `form "Post a
  shift"`, all 8 named. Date is read-only, followed by "The day of the meal. It cannot be changed here."
  `04-TA-new-event-layer-prefilled-3.jpg`
- Kitchen Staff, Post a shift: all 8 named. `16-KS-post-a-shift.jpg`
- Kitchen Staff, Edit a plain shift: all 8 named.
- Kitchen Staff, Edit a meal shift: 7 named, Date as plain text as above. `17-KS-edit-meal-shift.jpg`
- Kitchen Staff, planner layer (Edit meal → View volunteer shift): `dialog "Edit a shift"`, all 8 named.
  `19-KS-planner-edit-shift-layer.jpg`

### 2. Today names an event by its name — Temple Admin. Pass.

- Before creating anything, Today showed the recorded event "UAT-test record". The meal row heading was
  "UAT-test record" and its link was named `UAT-test record at 09:00`. Servings line: "Breakfast 97 ·
  UAT-test record 20 · Lunch 184 · Dinner 124". `01-TA-today-event-named.jpg`
- That event has no People needed, so it had no crew line. I saved "UAT-test fixes event" for today at
  12:00 with People needed 5 (step 3) and reopened Today. The crew line read "Lunch 2 of 6 · UAT-test
  fixes event 2 of 5". Servings line: "… Lunch 184 · UAT-test fixes event 30 · Dinner 124". Row link:
  `UAT-test fixes event at 12:00`. `12-TA-today-crew-line-event-named.jpg`

### 3. A new unsaved meal counts who is rostered — Temple Admin. Pass.

1. Planner, 14 Sept → Add a meal → Event, name "UAT-test fixes event", 30 adults, Akki Rotti,
   People needed 5. With no Ready by, Rostered read "Not counted yet".
   `02-TA-new-event-not-counted-before-readyby.jpg`
2. Typed Ready by 12:00 PM. Rostered became "2 staff · 0 volunteers · 2 of 5". The API gives the same
   figure: `GET /api/v1/meal-crew/at?date=2026-09-14&readyBy=12:00` returned `staffIn 2, volunteers 0,
   rostered 2`. `03-TA-new-event-rostered-2-of-5.jpg`
3. It re-counts when the time changes. Ready by 05:00 AM gave "0 staff · 0 volunteers · 0 of 5", and
   setting it back to 12:00 PM gave "2 of 5" again.
4. Pressed Ask for volunteers. The layer read "Post a shift / For UAT-test fixes event · Monday,
   14 September 2026" and "Saved when you save the meal.", with **Volunteers requested prefilled 3**
   (5 − 2). End time was prefilled 12:00 PM.
5. Filled Start 10:00 AM and Location "UAT-test kitchen", then pressed Done. Section 4 read "View
   volunteer shift / Not saved yet. It is saved with this meal." `05-TA-layer-filled.png`,
   `06-TA-after-done-not-saved-yet.jpg`
6. While the layer was open and after Done, the API still showed only the 4 existing meals for today and
   the 4 existing shifts. Nothing had been saved.
7. **Abandon:** pressed the page's Cancel. The layer said "Leave without saving? Nothing you changed
   here has been saved, including any volunteer shift." I pressed Leave without saving. The API still
   showed 4 meals and 4 shifts, so nothing was left behind. `07-TA-leave-without-saving.jpg`
8. **Save:** repeated steps 1–5 (Rostered "2 of 5", prefill 3), then pressed Save this meal. The API
   showed meal a337ad73… (Event, UAT-test fixes event, 12:00, PLANNED) and shift 2a2ff935… "UAT-test
   fixes event preparation on Monday, 14 September 2026", 10:00–12:00, OPEN, mealId a337ad73…,
   **capacity 3**. The planner card showed "2 of 5" and "0 of 3 signed up".
   `08-TA-second-run-rostered-2-of-5-save-enabled.jpg`, `10-TA-after-done-second-run.jpg`,
   `11-TA-planner-event-0-of-3-signed-up.jpg`
9. **Also a new main meal (Kitchen Staff, same permission).** On 21 Sept, a day with no meals, I opened
   Add a meal → Lunch. Ready by showed 12:00, and I typed People needed 6. Rostered read "2 staff · 0
   volunteers · 2 of 6", and Ask for volunteers prefilled **4**. I cancelled the layer and then the page
   (Leave without saving). The API then showed no new meal on 21 Sept (only the earlier cancelled
   "UAT-test Phase A") and no new shift. `24-KS-new-lunch-rostered-2-of-6.jpg`

### Regression: save with a shift, Done then Update, cancel — Kitchen Staff. Pass.

- The save with a shift is step 3.8 above, done as Temple Admin.
- As Kitchen Staff I opened `/planner/meal/a337ad73…`. Section 4 read "2 staff · 0 volunteers · 2 of 5",
  with View volunteer shift and "0 of 3 signed up". I pressed View volunteer shift, and the layer "Edit a
  shift" opened with the saved values. I typed the Description "UAT-test regression note" and pressed
  Done. The page read "0 of 3 signed up. Your changes are saved with this meal." I pressed Update this
  meal and got "UAT-test fixes event was saved." The API showed the shift's description saved, still
  capacity 3, still one shift for the meal. `18-KS-edit-meal-rostered-0-of-3.jpg`,
  `20-KS-after-done-changes-saved-with-meal.jpg`, `21-KS-meal-updated.jpg`
- I pressed Cancel this meal on the event card. The dialog read "Cancel UAT-test fixes event? / This meal
  has a volunteer shift, and nobody has signed up yet. Cancelling the meal cancels the shift too." with
  Keep it / Cancel this meal. I confirmed and got "UAT-test fixes event was cancelled." The card is gone.
  The API shows the meal CANCELLED and the shift CANCELLED with reason "The meal this shift was for has
  been cancelled."; it is no longer in the open shifts list. `22-KS-cancel-meal-warning.jpg`,
  `23-KS-meal-cancelled.jpg`

## Things noticed that are not failures of these fixes

- **Right after Save this meal, the planner day showed only "+ Add a meal" for a moment.** The meals
  appeared about 3 seconds later. `09-TA-after-save-day-empty.jpg`. It looks like the day was still
  loading; worth a look only if Rajeev sees it too.
- **The first click on a meal kind straight after the compose page loads did not register** (it
  happened twice, Event and Lunch). This was probably the page not being ready yet under automation. A
  second click worked both times.
- **On the meal edit page, "Notes for the kitchen" and "Notes for the servers" have no name from their
  label.** The tree names them by their example text ("Cook the kheer thin — …"). This is the same
  wrapped-label pattern T-213 lists as an after-UAT sweep. It is outside the shift form.
- **On the first attempt, Save this meal stayed disabled.** That run ticked Akki Rotti with the
  extension's form tool, which left no quantity box. With a real click, the quantity filled in (60
  pieces) and Save was enabled. Put down to how the checkbox was ticked, not to the app.

## Test data left on staging

- "UAT-test fixes event", Event, 14 Sept 12:00: **cancelled**. Its shift "UAT-test fixes event
  preparation on Monday, 14 September 2026" is **cancelled**. Nobody had signed up, so nobody was told.
- Nothing else was created. The abandoned event and the abandoned 21 Sept Lunch left no rows.
