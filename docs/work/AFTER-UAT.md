# After UAT

Rajeev, 2026-09-13, binding until the current list is finished: *"I want to finish the list you gave me 'CLEANLY' without creating another list as a biproduct of building this list."*

One line per thing noticed while building that its task did not ask for. Not tasks, not questions, not interruptions. Read after UAT. A real safety problem (one temple seeing another's data, a security hole) is **not** written here: it goes to Rajeev at once. Builders put their lines in their proof under `## After-UAT lines`; the work manager copies them here, so this file has one writer.

- (T-186) The phone separator pattern is copied in `PhoneNumberDeserializer`, `MyDonationsService` and `CounterPhone`.
- (T-186) The donations banner fades after five seconds, taking the "Saved as" line with it; its fade key is built from its text.
- (T-170) The WhatsApp composer textarea keeps its accent border while focused instead of the red refused border (`app/communications/composer.tsx:240`).
- (T-180) `TenantWhatsAppSettingsService.sendTestMessage` calls Meta directly, so it skips the WhatsApp line-break flattening (harmless today).
- (T-180) The ops failure list shows a failed notification's template name but has no reason column.
- (T-187) A landline typed two ways (`022 2345 6789`, `022-2345-6789`) does not group in donor history.
- (T-189) CI's backend job took 10m44s on `df8ef93` against 9m43s before, although local full runs fell from 5m02s to 3m48s; watch CI's backend time over the next few runs.
- (T-191) `AuthenticationFilter.java:122-123` still says the uid escape permits "reading exactly this user's row", which is false since V52 (one row per temple).
- (T-191) `WhoAmIController.temples()` Javadoc says the V2 policy "exposes rows carrying the caller's own verified uid, and nothing else"; it also shows this temple's rows, and the query's own uid condition does the narrowing.
- (T-194) `frontend/app/staff-schedule/page.tsx:145` prints "Week of {weekStart}" as a raw ISO date.
- (T-194) `frontend/lib/format.ts` has an orphaned doc comment above `longDay` that describes `longDate` and says "reader's own locale", which is no longer true.
- (T-194) `frontend/__tests__/profile.test.tsx` logs React `act(...)` warnings from `CommunicationPreferences` on every test (pre-existing; tests pass).
- (Rajeev 2026-09-13) A vendor refund has no record: a credit that would leave the temple owed is refused (`VendorInvoiceService.creditInvoice`), so an overpaid bill stays overpaid. Build a refund record on the bill after UAT.
- (Rajeev 2026-09-13) The GitHub issues copy of the stories (`docs/stories/github-import/`) holds 55 of 118 stories. Bring it up to date after UAT.
- (Rajeev 2026-09-13) Meta: move WhatsApp to the temple's real number and account, complete business verification, set the India time zone. After UAT; the current account is his own test account.
- (T-195) Job card PDF files stay in object storage after V135 deletes their document rows; nothing references them.
- (T-195) A notification already queued for a deleted shift (e.g. a reminder created before the reset) still sends; V135 deletes reminder jobs, not notification send jobs.
- (T-195) The seeded volunteer signup has no reminder job, because SQL cannot write Quartz's serialized job data.
- (T-195) `meals` has no check that `meal_plan_day_id` / `meal_kind_id` belong to the same temple, the same as every other foreign key in the schema today.
- (T-195) A meal-reading test whose `@AfterEach` fails leaves its temple behind and turns ~90 unrelated classes red; a cleanup that deletes the temple first would contain it.
- (T-196) The job card's volunteer section lists everyone on any shift that day; with meal shifts linked by id it could list only this meal's shift plus plain shifts covering the ready-by.
- (T-196) `ServedMeal.plates` falls back to the largest dish's `targetYield` when a meal has no head count; since V80 that is kilograms or litres, not people (the giving page already refuses that fallback).
- (T-196) `ShoppingListService.earliestDemandByIngredient` compares against `CURRENT_DATE` (the database's day), not the temple's day.
- (T-196) A meal kind renamed between a reuse preview and its commit drops out of the kinds chosen by name; reuse could carry kind ids.
- (T-196) `MealFixture.ensureKind` lets fixtures invent kinds; a test class that forgets `MealFixture.deleteAll` leaves kinds that hold its temple down.
- (T-196) `GET /api/v1/meals` reads open shifts for the range plus a day each side; a very long range reads every open shift in it.
- (T-196) Renaming an event onto another event's name that day is refused with the generic `KMS-400001`, not a code of its own.
- (T-197) The times-changed notice is recorded as a broadcast, so it uses one of the coordinator's daily broadcast allowance for that shift, though it is never refused by it.
- (T-197) On an overnight shift the notice reads "The times changed to 20:00 to 02:00." with no "(next day)", because the sentence was ruled word for word.
- (T-197) A planner save that raises capacity promotes the waitlist and schedules that volunteer's reminder job at once; if the meal save rolls back, the job later fires, finds no signup and does nothing.
- (T-197) Editing a plain shift with a `mealId` answers the generic `KMS-400001`; no screen sends that.
- (T-197) `suggestedCrew` now takes the last three ordinary meals of the kind, not the last three days that had one; the same unless a day has two events of one kind.
- (T-197) If a meal were ever moved to another day without saving its shift again, the shift would keep its old `shift_date`; nothing in D-27 moves a meal's day today.
- (T-198) A shift drafted in the planner cannot be thrown away on its own; the only way is to leave the meal without saving.
- (T-198) The browser's Back button inside the app is not caught by "Leave without saving?" (the App Router has no hook before a history pop).
- (T-198) The staff schedule still names the short meal by kind; `shortAtMealId` is not on the frontend type, because `staff-schedule.test.tsx` builds that type by hand.
- (T-198) Today's meal card shows the kind rather than an event's own name, and links to the day rather than the meal.
- (T-198) `meal-composer.test.tsx` logs about a hundred React act() warnings from the composer's async effects.
- (T-199) `/my-shifts` and `/shifts` cannot label a meal shift until the volunteer-side endpoints send the meal's id, kind and event name.
- (T-199) Cancelling a shift from the Volunteer shifts list still uses `window.prompt` for the reason.
- (T-199) A meal shift's list row shows the date twice: in the label and in the When column.
