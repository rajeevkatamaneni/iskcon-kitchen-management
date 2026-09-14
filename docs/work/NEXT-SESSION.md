# Next session — the build to finish before UAT

Written 2026-09-13 by the session that settled every decision below with Rajeev, so the next
session needs nothing from that conversation. **Everything here is decided. Do not reopen it, and do
not ask Rajeev again.** If a builder finds something new, it is one line in `docs/work/AFTER-UAT.md`
unless it is a real safety problem.

Staging deploys for this series are pre-approved (`.claude/settings.local.json`, staging only — never
production). Protocol is `docs/work/README.md`: work-manager plans and dispatches, builders build, one
release agent commits, CI, deploys. Stage named paths only. Deploy only from a clean tree at a
CI-green commit (`deploy.sh` ships the working tree).

---

## How to run this without losing resolution — MANDATORY

This will run for a long time. Compaction loses detail, so **the main session stays small**:

1. **The main session is a conductor, not a worker.** It never reads source files, proofs, or
   `DISPATCH.md` in full (it is 15,000+ lines — `grep` a section if needed). It delegates reading,
   building, testing and browser driving to agents and keeps only their short conclusions.
2. **Every agent prompt ends with: "Report in at most 150 words: what was done, what failed, file
   paths of the proof. Put detail in the proof file, not in the report."**
3. **Never `Read` or `tail` an agent's `.output` file** — it is a whole transcript.
4. **Browser testing is done by an agent**, not the main session. Screenshots are the largest thing
   that can enter a context. The agent drives Chrome (his Chrome listens on 9222; see memory
   `driving-chrome-for-uat` and `uat-test-accounts`), writes what it pressed and saw to
   `docs/work/proof/<task>-browser.md`, and reports in 150 words.
5. **Long commands run in the background**; read only their last lines.
6. **Checkpoint after every phase and every release**: update the *Progress* section at the bottom
   of this file (a line or two), so a compaction or a new session resumes from the file, not memory.
7. **Talk to Rajeev in plain English, shortest form that keeps the details** (`CLAUDE.md`). Ping him
   only for a real blocker; he is not needed for anything below except the Meta webhook step.

---

## Phase A — the meal rebuild, and the planner and shift screens

**The decision, in full and in his words: `docs/work/DECISIONS.md`, section D-27, to the end** —
including answers 1–9 and the ON HOLD note. **Rajeev lifted the hold for the next session
(2026-09-13): build it first.**

**The map:** `docs/work/intake/D-27-rebuild-map.md` — every table, class, page and test it touches,
the purge order, and the risks. Five builder tasks, in this order:

1. **Schema + purge + reseed** (V135 onward). New tables with `enable_tenant_rls()`: meal plan day,
   meals (FK to day, FK to `meal_kinds.id`, event name, every per-meal field incl. card/recording/
   correction), dishes (FK to meal, **keep dish ids stable** — stock movements and corrections point
   at them). `shifts.meal_id` nullable FK replaces `meal_date`/`meal_kind`/`meal_event_name`; partial
   unique index: one non-cancelled shift per meal. One meal per date + kind + lower(event name).
   Purge loops per tenant with `app.tenant_id` set (otherwise RLS deletes nothing and reports success).
   One `ADJUSTMENT` stock movement per ingredient equal to the net of deleted meal movements, so every
   on-hand figure is identical before and after — **prove it with a test**. Reseed per D-27 answer 9.
   `TenantLoopMigrationIT` and `BaseQuantityIT` stay green.
2. **Backend meal core** (meal, job cards, documents, today, costing, stock, shopping list, calendar).
3. **Backend shifts and crew** — parallel with 2.
4. **Frontend planner, recording, today** — owns `lib/api.ts`.
5. **Frontend shifts** — after 4.

**The screens, exactly as ruled** (D-27): "Ask for volunteers" inline in the composer's section 4 the
moment People needed > Rostered; layer with no meal checkbox, **Volunteers requested** prefilled as
needed − rostered, date read-only, button **Done**; nothing saved until **Save this meal / Update this
meal**, then meal + shift in one transaction; **View volunteer shift** after Done or when one exists;
"Leave without saving?" on leaving dirty; cancelling a meal with a shift warns with the count, then
cancels both (existing `shift_cancelled` message). **Post a shift**: Title, Date, Volunteers
requested, Start time, End time, Location, Reminder hours before, Description; no meal checkbox; the
sentence *"Kitchen help for a meal? Ask from that meal in the planner."* with "in the planner" a link.
Meal shifts in the list labelled like *"For Lunch, 15 September"*; editing one there saves at once,
date and meal read-only with a link, never convertible to a plain shift. **Times changed with
signups** (either screen): warn before saving (*"3 volunteers are signed up. They'll be told the new
times."*), keep places, send the approved `shift_broadcast` with *"The times changed to <start> to
<end>."* — never the word "moved".

**Release rule for Phase A:** before deploying the purge, the release agent takes an **on-demand
backup of the staging Cloud SQL database** and records its id in `DISPATCH.md`. No backup, no deploy.
After deploy it reports the before/after on-hand stock check and the reseed result.

**Then test thoroughly in the browser** (an agent, per the rules above), as Temple Admin, Kitchen
Manager, Kitchen Staff and Volunteer: plan a meal, ask for volunteers, abandon without saving (no
orphan shift), save, view/edit the shift from the planner (Done then Update), change times with a
signup (warning + message), cancel a meal with a shift, Post a shift (no checkbox, pointer sentence),
edit a meal shift from the list (date/meal read-only), sign up as a volunteer, record a meal, job
card, cost per serving, shopping list, Today. Fix what it finds, re-release, re-test. Then commit,
CI green, deploy to staging.

---

## Phase B — the 13 items (all answered by Rajeev, 2026-09-13)

Plan them into waves with no two builders on one file. Items 9 and 13 touch code Phase A rewrites
(`MealComposer.tsx`, the costing services), so they run after Phase A.

1. **Attach the purchase order PDF to the vendor's WhatsApp message** (T-182, "MUST"). A Meta **App
   ID** box in Settings → WhatsApp (same read-only / Edit / Save pattern; not a secret). The
   `purchase_order` template gets a DOCUMENT header; template creation needs a sample via Meta's
   resumable upload (`POST /<APP_ID>/uploads`, then the handle). At send, upload the PDF to
   `/<PHONE_NUMBER_ID>/media` (or a link) and send it as the header document. Send the PDF as is:
   translated if the order was translated, else the English original. Fold in: stored template
   reasons that say "Press Reload…" when no button is labelled Reload. **Builders never call Meta,
   press Reload or send.** Whether the system-user token is accepted for the upload is unknown — the
   main session verifies on staging, with Rajeev if a credential is needed (never handle tokens).
2. **"Why" → "Reason"** on two forms, so the message reads *"Reason is required"*: closing a
   part-delivered purchase order (outcome other than "as computed") and changing equipment condition.
3. **Messages composer:** "Save and preview" with a blank subject shows *"Subject is required"* and
   saves nothing (today it saves a subject-less draft; see `docs/work/proof/T-165.md`).
4. **Spaces-only or 0 shows the red message** instead of silently doing nothing: every place
   `docs/work/proof/T-172.md` lists as stopped silently — invoice void and credit note (spaces reason,
   credit of 0), reverse payment, drop a vendor, staff conduct note, and the rest it names.
   Wording follows `components/ds/formMessages.ts` (e.g. *"Amount must be more than 0"*).
5. **Hide the up/down arrows** on the public Give amount box (`components/give/DonatePage.tsx`).
6. **Voiding the gift behind a FULFILLED wish reopens the wish**: back to accepting gifts, needing the
   amount again, `fulfilled_at` cleared, an audit entry. Background and options:
   `docs/work/proof/T-069.md`, `DISPATCH.md` "T-069's deferred half".
7. **Refuse voiding a vendor bill that has unreversed payments**, with a new permanent error code and
   *"Reverse the payments on this bill before voiding it."* (`VendorInvoiceService.voidInvoice`).
8. **Two uncleared money rows:** `DonationReconciliationService` skips gifts with no
   `provider_payment_id` (counter cash — Rajeev's ruling overrides T-072's old acceptance line); a
   voided bill shows no variance (`VendorInvoiceService.withVariance`).
9. **A new meal on a festival day starts with that occasion's `defaultServings` as adults**,
   editable (`MealComposer.tsx` opens at 0 today; occasions carry the number).
10. **Remove equipment from the job card entirely** — screen, print and PDF. Rajeev: *"It does not
    belong there. The Kitchen staff know about their equipment better than ANY APP or Job card will
    ever know."* Equipment is in the card fingerprint (`JobCardService`), so check printed cards are
    not all flagged as changed.
11. **Add `eslint-plugin-react-hooks` with `rules-of-hooks` only** — not `exhaustive-deps`. Fix only
    what that rule flags.
12. **Update `docs/uat/TRACEABILITY.md` section 1** for everything built since it was written,
    including Phase A and items 1–11 and 13.
13. **Cost per serving follows what was actually cooked.** Rajeev: *"Costing follows actuals, option
    1."* A meal that has been recorded is costed at what the job card says was cooked; a meal not yet
    recorded is costed at what was planned; the screen says which each figure is. Applies to the cost
    per serving report and the Today food cost tile alike (today both use the planned amount on
    purpose — `MealKindCostService.dishesIn`, `MaterialsCostService` — so update their comments and
    tests with the rule). Stock already draws on what was cooked, so this makes the two agree.

**Not code, with Rajeev (about five minutes):** switch on WhatsApp delivery receipts. Settings →
WhatsApp shows the callback URL and a "show verify token" button; he pastes both into the Meta app
dashboard (Webhooks, subscribe to `messages`). Guide him; he handles the token.

**Then test all 13 in the browser** (an agent, as the role each is for), fix, commit, CI green,
deploy to staging, and tell Rajeev in a few lines what is live.

---

## Told to Rajeev, not to build unless he says

- Staging email is still the Mailgun free sandbox (only pre-authorised addresses receive mail).
- WhatsApp is Meta's test number, which only reaches a few verified recipient numbers.

---

## Progress

- 2026-09-13 — brief written. Nothing started. Item 13 (costing follows actuals) added at Rajeev's request.
- 2026-09-13 — Phase A started: work-manager dispatched to plan and build the five D-27 tasks (no release yet).
- 2026-09-14 — Phase A built (T-195..T-199), all green locally (backend 2,671, frontend 1,850, tsc/eslint/next build). Release dispatched: staging backup, commit, CI, deploy. Browser test follows.
