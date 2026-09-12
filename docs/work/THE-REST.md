# What else is left

**Rewritten 2026-09-11, at Rajeev's instruction, after Wave F consumed every item the previous
version held.** This is the answer to *"what else is left?"* — a file rather than a fresh sweep of
three lists every time he asks.

**It holds only work that is NOT built.** Anything already built and awaiting his test is in
`docs/OUTSTANDING_BUILD_LIST.md` and `docs/WORK_QUEUE.md`; it is deliberately not repeated here,
because mixing "not written" with "written but unseen" is what made the last version go stale.

**The rule for working it, which is his:** an item that turns out not to be a real problem is
**dropped with a sentence saying why** — that is a finding, not a failure. An item that needs a
ruling **comes back to him** rather than being guessed.

---

## 1 · Features that were asked for and never built

| | What is missing |
|---|---|
| **P6** | **A volunteer shortfall cannot raise a shift from the planner.** When the crew a meal needs exceeds the staff on duty, there should be a link that opens *Post a shift* as a layer over the planner — title derived from the date and meal (*"Lunch preparation on September 1 2026"*), date and capacity pre-filled — and lands the admin back where they were. |
| **P7** | **An existing shift request is invisible from the planner.** *"Who will run it"* must show when a request already exists for that day and time, and let the admin open, edit and save it, or close without changes, without leaving the planner. |
| **Audit log** | **No operator view.** Narrower than `TRACEABILITY.md` G9 — a `/audit` screen, per-tenant. Operators read audit per tenant, never as a cross-tenant firehose. |
| **Kitchen staff** | **The least finished role in the product.** Two of its five gaps shipped; still open: they hold `REQUEST_OWN_LEAVE` with **no menu route to it**, and Download and Print are unresolved. Note the wider hole recorded on 2026-09-01: a **Temple Admin** holds `VIEW_OWN_SHIFTS` too and has no *My shifts* entry either. |
| **Blank fields** | **Required fields are refused by the browser's own grey bubble.** Ruled by Rajeev 2026-09-11: `required` stays on the element, every form gains `noValidate`, and the message is per-field — *"Quantity is required"*, in red. See §3 for why the wording is the free half. |

---

## 2 · Decisions waiting on Rajeev — nothing proceeds without him

**Five carried from before the run:**

1. **An order of only described lines can never be closed** and is late for ever (T-066).
2. **A `FULFILLED` wish-list item whose gift is voided** (T-069's second half). **The do-nothing
   option is not the cheap one:** it reads *"FULFILLED — ₹0 of ₹15,000"* publicly, `KMS-400068`
   refuses every further gift because checkout tests status before money, and the archive sweep runs
   on a clock that started before the void. **The temple silently loses the wish.** Four options in
   `docs/work/proof/T-069.md`.
3. **Voiding a bill that has already been paid is allowed.** Not silent — the dialog says so — but he
   may want it refused until the payment is reversed.
4. **A vendor refund has nowhere to live.**
5. **D-7 promises "return with the vendor selected" and then rejects the obvious mechanism** without
   saying what replaces it (T-067). Two readers have tripped on it. `DECISIONS.md` is his file.

**Five from the 2026-09-11 run:**

6. **Should a late-sent order also leave the vendor's *fill rate*?** It already leaves *on-time*, and
   the scorecard says so in words. The case for: giving a dairy one day when they need three makes a
   short delivery partly ours. The case against: fill rate asks whether they could supply the
   quantity, and short notice does not make curd appear. **Left as-is** — his own rule is that a
   percentage never moves silently, so excluding it needs its own visible count too. One predicate
   either way.
7. **Should *"The vendor let us down"* count against them immediately?** Today it changes no number —
   the missing quantity is already in the percentage and the label's job is the record. The only
   thing it could do and does not is judge the order before its needed-by date passes. In practice
   such an order is closed after that date anyway. **Left as-is**; one branch in `countOrders`.
8. **The geocoding port keeps a richer method whose screen was deleted.** Nothing is dead —
   `GoogleGeocodingProvider.locate()` still delegates to `describe()` — so it costs nothing.
   **Recommendation: leave it.** Shrinking an interface in a cleanup wave is how a reason nobody
   wrote down gets broken.
9. **`var.api_base_url` could become required** now the example file is repaired and CI-checked. The
   looseness existed only because the example was stale. **Recommendation: leave it** — small gain,
   and it touches the deploy path.
10. **Two money records that cannot be tidied, and the one he most needs to rule on.** A **cash gift
    no gateway ever handled** shows as a reconciliation mismatch nobody can clear, because
    reconciliation asks about every gift and there is nothing on the bank's side to match. And a
    **voided bill still shows its original variance**, so the invoice keeps displaying a discrepancy
    for money nobody owes. Neither breaks anything; both leave a permanent untidy row in front of
    whoever balances the books. **The right answer depends on how he actually reconciles.**

---

## 3 · Findings from the 2026-09-11 run — recorded, none blocking

**Two that are the same defect in a second place, which is why they are first:**

- **The recipe scale preview still says *"1 pieces"*.** `RecipeScaler` ships `unit.label()` as a
  **wire field** and the recipe screen prints it beside a number — invisible to T-108's guard (it
  never calls `unitLabel`) and to T-144's fix (the word comes from Java). **Not a one-liner: it
  changes an API response and its screen together.**
- **`components/ds/Button.tsx` does not default `type`.** It spreads onto a bare `<button>`, so every
  caller that omits it inherits HTML's `submit`. This is the application-wide form of the defect that
  **removed an order line and saved the order with the line still on it** — one line closes it, but
  the sweep of callers inside forms is the real work.

**And one that makes the required-fields task cheaper than it looks:**

- **`Field.tsx` is the shared field component and most screens do not use it.** Only 10 of the 50
  files with required inputs; only 7 of 86 hints went through it. **It already renders a red error,
  already wires `aria-invalid`/`aria-describedby`, and already holds the label** — so *"Quantity is
  required"* costs nothing over *"Required"*. **The cost is submit-time state in the 42 forms that
  hand-roll their controls**, and only 6 of those 42 associate their label with `htmlFor`.
  **Recommended: one shared form wrapper, not 42 rewrites.** Doing it is also the natural moment to
  consolidate the hand-rolled markup this file has wanted consolidated since 2026-09-01.

**The rest:**

- **A volunteer taken off a roster has nowhere to look.** `myShifts` filters released rows out, there
  is no past-shifts surface, and a send failure is swallowed into a `log.warn`. **Mailgun's sandbox
  fails in exactly this shape on staging**, so a removal whose message does not land leaves the
  person with no way at all to find out. Fix: a *"taken off in the last week"* list on My Shifts —
  one relaxed `WHERE` and a list on a screen that exists.
- **The WhatsApp *Test* button does not send a message** — it verifies credentials. So a temple using
  WhatsApp only for purchase orders never earns the Send button. Rajeev was told a test-send existed
  when he asked for it to be the trigger; making it send one is the faithful build.
- **`POST /purchase-orders/generate` now has no caller in the app.** Retire or keep is a decision, and
  a removal's blast radius includes the documents that promised it.
- **The 90 remaining Spring test-context collapses.** 119 contexts → 12 is achievable and was proved
  on `staff/` (9 → 1). **Its own wave, needing three consecutive full runs**, because two beans hold
  mutable state for a context's life and JUnit's class order is not fixed. The suite's live set is
  **1187 MB against a 2 GB ceiling**, where the build comment still says 886 MB.
- **`NotificationSendE2EIT` is a real flake with a real mechanism**, papered over once already with a
  timeout rise. It has failed two different ways.
- **Two files carry `eslint-disable` comments for `react-hooks/exhaustive-deps`**, a rule nothing in
  the repo defines. Adding `eslint-plugin-react-hooks` with `rules-of-hooks` is worth a task.
- **`"₹80 / pieces"`** on the generated sheet — *per* wants the singular and there is no count to
  pass, so it is a different fix from T-144's.

---

## 4 · Raised 2026-09-01, seen by Rajeev, never ordered

**He has seen each of these and has not said where they sit. Ask before assuming any of them
outranks the rest.** Two are worth reading first:

- **⚠ Local development bypasses row-level security.** `application.yml` defaults `DB_USER` to `kms`,
  the compose superuser, and `V1`'s grants for the unprivileged `kms_app` role are gated on
  `IF EXISTS` — which locally it does not. **So the application runs locally as a superuser and RLS
  does nothing** — the exact trap `CLAUDE.md` warns about. Symptom: other temples' rows appear in
  `/users` and `/staff`. **Production and CI are unaffected.** Fix: a local `kms_app` role plus a
  separate migrator role for Flyway.
- **⚠ A shift crossing midnight is refused** (`20:00`→`02:00`, `KMS-500001`) — **for a temple whose
  largest festival is at midnight.** Found while seeding Janmashtami.

- **A festival occasion's `defaultServings` no longer reaches the meal composer.** The head-count
  change made all three counters open at zero unconditionally. His rule was that the application does
  not *guess* a head count — but an occasion's stored default is a figure the temple typed for that
  festival, which is arguably not a guess. **His call.** `UAT-030` currently asks the tester to record
  what happens rather than asserting either behaviour.
- **`docs/uat/TRACEABILITY.md` §1 is stale** for everything after 2026-08-20 — E3-S8, E4-S9 onward and
  E6-S10 onward are missing. One pass of its own.
- **`docs/stories/github-import/` has been behind since E1-S12**, and `CLAUDE.md` calls it a job of
  its own.
- **A settings test has been passing for the wrong reason.** `settings-payments`' *"connects to
  WhatsApp"* only passed because a **hint string** contained the words "message templates". The
  connected panel it appears to assert never renders: `SettingsView`'s fetch effect depends on
  `getToken`, the `useAuth` mock returns a fresh one every render, so the effect re-runs and
  overwrites the just-saved settings with the stub. **The connect-then-render path is untested.**
- **`CrewPebble` uses a native `title=` tooltip** (`MealServices.tsx`) — hover-only, no keyboard and
  no touch route, which is the failure `InfoHint` exists to avoid. One small conversion.
- **The job card no longer filters equipment by kind.** It used to print only MACHINE and TOOL to keep
  trestle tables off the sheet; the category went on 2026-09-04 at his instruction, so the card now
  lists everything not scrapped. **If that is noise on a real card, the fix is a flag on the
  equipment, not the category coming back.**
- **Three inline copies of the unit-family rule** remain in `InventoryItemService.adjust`,
  `DonationRecorder` and `IngredientRequestService`, each refusing with the generic `KMS-400001`
  rather than `IngredientUnits.requireSameFamily`'s `KMS-400013`. Recorded under `BL-9`.

---

## 5 · Deliberately last

**D1 — wipe the tenant and seed a realistic day-one dataset.** Not started, and it stays last on
purpose: **never raise it until nothing else is left**, because it destroys the state every other
item is verified against. It is also what the UAT plan wants before testers arrive — *"an empty app
produces a hundred reports of 'this screen says nothing here', which buries the real defects"* — so
it is last, but it is not optional.
