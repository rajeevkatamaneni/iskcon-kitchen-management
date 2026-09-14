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
