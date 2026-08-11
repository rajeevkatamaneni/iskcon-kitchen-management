# User Acceptance Testing

How we run UAT, per Commandment 6 (as amended 2026-08-09). Read that commandment first; this file is the working catalogue.

## The model

- **Coding stories** close on automated tests + review + design-doc conformance (see Commandment 5). They are not held open waiting on UAT.
- **UAT stories** are separate, and scoped to a **demonstrable capability** — the smallest slice a person can actually drive end to end — not one-per-coding-story. A capability usually spans several coding stories; pure-infrastructure stories (RLS, the audit kernel, jobs, observability) have no manual surface and carry no UAT story, only a one-line verification note on the coding story itself.
- **Traceability runs both ways.** Each UAT story lists the coding stories it exercises; each of those coding stories links back to its UAT story. Nothing is silently untested.
- **GitHub:** UAT stories are issues labelled `uat`. A coding story still awaiting its UAT pass carries `needs-uat`.

## UAT story template

```
## UAT-<n> — <capability name>

Exercises: <coding stories, e.g. E1-S4, E1-S5, E1-S6>
Status: DRAFT | READY | IN PROGRESS | PASSED | BLOCKED

### Preconditions / setup
- <environment, accounts, data needed before starting>

### Steps
| # | Do this | Expect |
|---|---------|--------|
| 1 | ... | ... |

### Acceptance criteria
- [ ] <the pass/fail bar>

### What to look out for
- <edge cases, and the specific KMS-nnnn codes that should appear>

### Defects
- UAT<n>-D<k> (<severity>): <what's wrong> → <linked issue / fix>
```

## Index

| UAT | Capability | Exercises | Status |
|-----|-----------|-----------|--------|
| UAT-1 | Temple onboarding & first sign-in | E1-S4, E1-S5, E1-S6 | READY (UAT1-D1 fixed) |
| UAT-2 | Profile & communication consent | E1-S8 (+ E1-S10 for effect) | READY (partial — see note) |
| UAT-3 | Notifications delivered | E1-S10 | BLOCKED (needs a real channel provider) |
| UAT-4 | Operations & health visibility | E1-S11 | READY (partial — external monitor & Sentry are staging) |
| UAT-5 | Temple staffing | E1-S12 (+ E1-S7 role change) | READY (needs frontend wired) |
| UAT-6 | Recipe book: ingredients, recipes, search & scaling | E2-S1, E2-S2, E2-S3, E2-S7 | READY |
| UAT-7 | Sattvic policy enforcement | E2-S4 (+ E2-S1 flag) | READY |
| UAT-8 | Recipe document & translation | E2-S5, E2-S6 | READY (partial — renderer & translation providers) |
| UAT-9 | Consumable inventory & the stock ledger | E3-S1, E3-S2, E3-S7 | READY |
| UAT-10 | Reorder thresholds & low-stock alerts | E3-S3 | READY (partial — digest needs a channel) |
| UAT-11 | Equipment register | E3-S4 | READY |
| UAT-12 | In-kind donation intake | E3-S5 | READY |
| UAT-13 | Vaishnava calendar & festival occasions | E4-S1, E4-S2, E4-S3 | READY |
| UAT-14 | Meal planning & cooking | E4-S4, E4-S5, E3-S6 | READY |
| UAT-15 | Ekadashi enforcement | E4-S6 | READY |
| UAT-16 | Vendors & the auto order list | E5-S1, E5-S2 | READY |
| UAT-17 | Purchase orders & receiving | E5-S3, E5-S6, E5-S4 | READY (partial — PDF renderer is staging) |
| UAT-18 | PO translation & WhatsApp delivery | E5-S5, E5-S7 | BLOCKED (needs translation + WhatsApp providers) |
| UAT-19 | Vendor invoices & payables | E5-S8, E7-S8 | READY |
| UAT-20 | Staff schedule | E6-S1 | READY (partial — change notice needs a channel) |
| UAT-21 | Volunteer shifts: post, sign up, release, waitlist | E6-S2, E6-S3, E6-S4, E6-S5 | READY |
| UAT-22 | Shift reminders & broadcasts | E6-S6, E6-S7 | BLOCKED (delivery needs a channel) |
| UAT-23 | Public donation & 80G capture | E7-S1, E7-S2, E7-S4, E7-S9 | READY (partial — Razorpay test mode) |
| UAT-24 | Recurring donations | E7-S3 | READY (partial — Razorpay test mode) |
| UAT-25 | Wish list & sponsorship | E7-S5, E7-S6 | READY (partial — Razorpay test mode) |
| UAT-26 | Donations ledger & accounting | E7-S7 | READY |

---

## UAT-1 — Temple onboarding & first sign-in

Exercises: E1-S4 (Firebase auth), E1-S5 (RBAC), E1-S6 (tenant provisioning)
Status: READY — UAT1-D1 fixed; awaiting a manual pass

The first genuinely demonstrable capability: a platform operator brings a temple onto the platform, its first administrator signs in, lands in an empty-but-working workspace, and the role boundaries hold.

### Preconditions / setup
- Backend and frontend running (staging preferred; local per `docs/DEPLOYMENT.md`).
- A `SUPER_ADMIN` account that can sign in.
- An email address and an Indian (+91) phone number you control, to act as the new temple's first admin.

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Sign in as the Super-Admin | The platform view loads with the **Temples** list |
| 2 | Open **Add a temple**; fill name, web address (slug), address, latitude/longitude, timezone, currency, 80G flag, and the first admin's name / email / phone | The form accepts valid input; required fields are enforced |
| 3 | Submit with everything valid | Success; the new temple appears in the list as **Active**, user count **1** |
| 4 | Sign out. As the **new admin**, sign in with the registered email (Firebase-verified) or phone OTP | You land in the temple workspace — empty but working — with the temple nav; `GET /api/v1/whoami` returns `TEMPLE_ADMIN` and the new tenant. First sign-in binds your Firebase identity to the account (the "claim") |
| 5 | As the new admin, attempt a platform action (e.g. open **Add a temple** / `POST /api/v1/tenants`) | **403** — `KMS-4301`, and an audit-relevant log line for the denied attempt |

### Acceptance criteria
- [ ] Super-Admin can provision a temple + first admin from the screen.
- [ ] Invalid coordinates / timezone / duplicate web address / duplicate admin email are refused with actionable messages.
- [ ] The newly provisioned admin can sign in immediately and reach an empty-but-working workspace.
- [ ] A non-super-admin is refused all provisioning endpoints (403).
- [ ] Provisioning wrote an audit event (actor = the Super-Admin, tenant = the new temple).

### What to look out for
- Invalid latitude/longitude → `KMS-4002`; unusable timezone → `KMS-4001`; duplicate web address → `KMS-4901`; admin email already registered at that temple → `KMS-4902`.
- Nothing technical should ever reach the screen — every failure shows plain language plus a `KMS-nnnn` code.
- The audit trail: the new temple's own audit log (once its viewer is exercised) should show "temple provisioned by \<super-admin\>" with a null before-state.

### Defects
- **UAT1-D1 (Blocker) — RESOLVED 2026-08-09:** A freshly provisioned admin could not sign in. Provisioning stores `firebase_uid = "pending:<uuid>"`, and no code linked that pending record to the person's real Firebase uid on first sign-in — `AuthenticationFilter` looked up strictly by the real uid, found nothing, and treated them as having no account. E1-S6's test had masked this by manually running `UPDATE users SET firebase_uid = …`.
  Fixed by first–sign-in **claim-on-match** (approved mechanism: match by Firebase-verified contact, gated on verification): on a uid miss, if the token carries a verified email or an OTP-verified phone, the app adopts the real uid onto the single pending row whose email/phone matches. Narrow by construction — only `pending:` rows, only an exact verified-contact match (new `app.claim_contact` RLS escape, migration V4), refuses ambiguity, and cannot touch an already-active account. Proven by `PendingAccountClaimIT` (6 cases), and `TenantProvisioningIT` now signs the admin in through the real claim rather than a manual update. Issue #6 can close once this UAT-1 passes a manual run.

---

## UAT-2 — Profile & communication consent

Exercises: E1-S8 (profile & consent). The "takes effect on next notification" half needs E1-S10 (notification service) and is verified with that capability.
Status: READY (partial) — the profile management and consent are testable now; the notification effect awaits E1-S10. Note: like all UAT here, a manual run needs the frontend wired to the backend and the app running.

A person manages how their temple reaches them and records their consent to be contacted.

### Preconditions / setup
- A signed-in user of any role, with an account that has an email and phone.

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Open **Profile** | Your name, email and phone are shown read-only; your preferred channel; and, if you have not consented, a consent prompt |
| 2 | Change the preferred channel (e.g. WhatsApp → SMS) and reload | The new channel persists and is returned by the profile |
| 3 | Read the consent text and choose **I agree** | Consent is recorded; the prompt goes away; the profile shows you have consented |

### Acceptance criteria
- [ ] Contact details are shown but not editable here.
- [ ] A preferred-channel change persists and is returned by the profile API.
- [ ] Consent is recorded with a timestamp and the version of the wording accepted.
- [ ] (E1-S10) A change of channel takes effect on the next notification; an unconsented user is not sent notifications.

### What to look out for
- An unrecognised channel is refused as ordinary validation (`KMS-4001`).
- The consent text states the purpose (reminders and service messages), the channels, and the right to withdraw.
- If the consent wording is later revised, a previously-consented user is asked again (version mismatch).

### Defects
- _None yet._

---

## UAT-3 — Notifications delivered

Exercises: E1-S10 (notification service, fallback cascade, delivery webhook).
Status: BLOCKED — the machine is built and automated-tested, but a manual "did it actually arrive?" pass needs at least one real channel provider connected (email is the lowest-friction; WhatsApp needs Meta setup, see docs/META_WHATSAPP_SETUP.md). Ready to run the moment one adapter is live.

A person is actually reached — a message leaves the system on the right channel, falls back when the first fails, and its delivery status comes back.

### Preconditions / setup
- At least one real channel adapter wired (email recommended first) and configured.
- A signed-in user with a verified contact and consent given (UAT-2).

### Steps
1. Trigger a notification to yourself (e.g. a shift reminder once E6 exists, or a test send) → the message arrives on your preferred channel.
2. Force the preferred channel to fail (config) and trigger again → it arrives on the next channel in the cascade; both attempts are recorded on the message.
3. Trigger a send to a raw vendor phone (no account) → it arrives.
4. Observe that after delivery, the message's status becomes DELIVERED (via the provider webhook).

### Acceptance criteria
- [ ] A message is received on the preferred channel; delivery status lands on the record via webhook.
- [ ] A forced failure of the preferred channel falls back and records both attempts.
- [ ] A vendor send (raw phone, no account) works.
- [ ] A user who has not consented is not sent to.

### What to look out for
- A duplicate delivery webhook must not move the status twice (providers retry).
- An unsigned/wrongly-signed webhook is refused (403).
- Nothing sends on the request thread — a send always goes through the background worker.

### Automated coverage (already green)
The cascade, fallback (both attempts), vendor send, consent suppression, webhook signature + idempotency, and the full enqueue-and-send path are covered by `NotificationIT`, `NotificationFallbackIT`, and `NotificationSendE2EIT`. What UAT-3 adds is the one thing tests cannot: a message a human actually receives.

### Defects
- _None yet._

---

## UAT-4 — Operations & health visibility

Exercises: E1-S11 (structured logs, /health, metrics, ops page).
Status: READY (partial) — the in-app ops drill-in and /health are testable once the frontend is wired; the external monitor, its phone/WhatsApp alert, and Sentry need real accounts (staging).

A solo operator can see, in minutes, that the platform is healthy and no temple's messages are silently failing.

### Preconditions / setup
- App running; Super-Admin signed in. For the external-alert steps: an uptime monitor pointed at `/health`, a Sentry project (SENTRY_DSN set), and Cloud Monitoring scraping `/actuator/prometheus`.

### Steps
1. Open **Operations** → system health shows database Reachable and scheduler Running.
2. Pick a temple → see its notifications sent / failed today; a message that failed on every channel appears under recent failed sends.
3. (Staging) Stop the database → `/health` returns 503 and the uptime monitor alerts by phone/WhatsApp.
4. (Staging) Trigger a test exception → it appears in Sentry within a minute, with the request's id.
5. Follow one request through the logs → the API line and the worker's send line share a `request_id`.

### Acceptance criteria
- [ ] Ops page reflects a deliberately failed send within one refresh (per-temple drill-in).
- [ ] `/health` reports DB + scheduler and goes 503 when the DB is unreachable.
- [ ] A thrown exception reaches Sentry with request context (staging).
- [ ] One request's lines share a `request_id` across API and worker.

### What to look out for
- Platform-wide totals and the job-failure-rate alert live in Cloud Monitoring, not the in-app page.
- "Last calendar precompute" reads "not available yet" until Epic 4.

### Automated coverage (already green)
`/health` DB+scheduler reporting (`HealthControllerIT`), the ops drill-in and its permission gate (`OpsIT`), and request-id propagation into a job (`BackgroundJobIT`). What UAT-4 adds: the external monitor/alert and Sentry, which are real-account staging concerns.

### Defects
- _None yet._

---

## UAT-5 — Temple staffing

Exercises: E1-S12 (add / list / disable users) and E1-S7 (role change).
Status: READY — testable once the frontend is wired to the API; the backend supports it now.

A temple with one administrator can staff itself: add people, adjust what they can do, and remove access when someone leaves.

### Preconditions / setup
- A signed-in Temple Admin, and an email/phone you control for the person you add.

### Steps
1. Open **People** → add a person (name, email, phone, role) → they appear in the list.
2. Sign in as that person for the first time (their registered email/phone) → they reach the workspace with the role you gave them (first-sign-in claim, UAT-1).
3. Change their role → the change takes effect on their next request.
4. Disable them → their next request is refused; they are still listed (not deleted). Re-enable → access returns.

### Acceptance criteria
- [ ] A Temple Admin can add a user who can then sign in with the assigned role.
- [ ] Adding a `SUPER_ADMIN`, or a duplicate email at the temple, is refused with a clear code.
- [ ] Role change and disable/re-enable each take effect within one token lifetime, immediately for new sign-ins.
- [ ] A disabled user cannot access the app but their history and audit references remain.
- [ ] Every add / role-change / disable is on the audit trail with actor, target, and before/after.

### What to look out for
- You cannot disable your own account (`KMS-4304`) or change your own role (`KMS-4302`).
- A user in another temple is simply not found (RLS), not an error you can probe.

### Automated coverage (already green)
`UserManagementIT` (add, duplicate-email, super-admin refused, list scoping, disable/enable, self-disable guard, cross-tenant, permission gate) and `RoleChangeIT` (the four role-change guards). What UAT-5 adds: a real person actually signing in, and the change actually taking effect for them.

### Defects
- _None yet._

---

## UAT-6 — Recipe book: ingredients, recipes, search & scaling

Exercises: E2-S1 (ingredient master), E2-S2 (recipe CRUD), E2-S3 (recipe scaling), E2-S7 (browse & search)
Status: READY

A temple builds its recipe book — a shared ingredient catalogue and recipes with quantities — then finds a recipe and scales it to the day's headcount.

### Preconditions / setup
- App running; a signed-in Temple Admin or Kitchen Staff member.

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Open **Ingredients**; add a few (e.g. Rice, Toor Dal, Ghee) with category and canonical unit (KG / L / PIECES) | They appear in the catalogue |
| 2 | Add an ingredient with a name that already exists | Refused — `KMS-4903` |
| 3 | Open **Recipes**; create a category (e.g. "Rice"), then a recipe (name, category, base yield, method) and add ingredient lines with quantities | The recipe saves and shows its lines |
| 4 | Create a second recipe with the same name | Refused — `KMS-4905` |
| 5 | Use **browse / search** — filter by category, by an ingredient, and by a name fragment | Results narrow correctly; an archived recipe is hidden unless "include archived" is on |
| 6 | Open a recipe and **scale** it to a target yield (e.g. 100 → 500 servings) | Every quantity scales proportionally with sensible units; the base recipe is unchanged |
| 7 | Try to delete an ingredient that a recipe uses | Refused — `KMS-4904` (remove it from recipes first) |
| 8 | Archive a recipe | It disappears from the default list but remains in history |

### Acceptance criteria
- [ ] Ingredients and recipes can be created, edited, and archived; changes persist.
- [ ] Duplicate ingredient / recipe / category names are refused with actionable codes.
- [ ] Search filters (category, ingredient, text) return the right recipes.
- [ ] Scaling multiplies quantities correctly and does not mutate the base recipe.
- [ ] An ingredient in use cannot be deleted.

### What to look out for
- Duplicate names: ingredient `KMS-4903`, recipe `KMS-4905`, category `KMS-4907`. Ingredient-in-use `KMS-4904`.
- A volunteer (no `MANAGE_RECIPES`) is refused these screens (`KMS-4301`).
- Scaling of a "pieces" ingredient rounds sensibly; masses/volumes convert cleanly.
- Every create/edit/archive should be on the temple's audit trail.

### Defects
- _None yet._

---

## UAT-7 — Sattvic policy enforcement

Exercises: E2-S4 (sattvic enforcement + Temple-Admin override), building on the E2-S1 sattvic flag.
Status: READY

The temple's food discipline is enforced by the system: a recipe cannot quietly include a prohibited ingredient, and only a Temple Admin can override — on the record, with a reason.

### Preconditions / setup
- A signed-in Temple Admin, and (for step 4) a Kitchen Staff member.
- At least one ingredient flagged sattvic-prohibited (e.g. Onion or Garlic) — set the flag on the ingredient.

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | On an ingredient, set the **sattvic-prohibited** flag | The flag saves and shows on the ingredient; the change is audited |
| 2 | As Kitchen Staff, create/edit a recipe that includes a prohibited ingredient | **Blocked** — `KMS-4906`; the recipe is not saved |
| 3 | As **Temple Admin**, save the same recipe and supply an override **reason** | Saved; the recipe shows a "sattvic override" note carrying the reason |
| 4 | Open the audit log | An override event with the actor and the reason is recorded |
| 5 | Clear the sattvic flag on the ingredient, then re-save an ordinary recipe using it | No block |

### Acceptance criteria
- [ ] A prohibited ingredient blocks a normal recipe save (`KMS-4906`).
- [ ] Only a Temple Admin can override, and only with a reason.
- [ ] The override, and every change to a sattvic flag, is on the audit trail with who and why.

### What to look out for
- Kitchen Staff cannot override (they lack `OVERRIDE_SATTVIC_ENFORCEMENT`) — the block holds for them.
- The prohibited-ingredient badge is visible on the recipe's lines so a cook can see it.
- Setting/clearing the flag is itself a religious-compliance decision — confirm it is audited (`INGREDIENT_SATTVIC_FLAG_CHANGED`).

### Defects
- _None yet._

---

## UAT-8 — Recipe document & translation

Exercises: E2-S5 (recipe PDF & print, English), E2-S6 (translation + translated PDF/print)
Status: READY (partial) — the request→document→download flow is fully testable now; a *real* rendered PDF needs the Playwright/Chromium renderer (worker image), and *real* translated text needs the Google translation provider. With the default stub renderer/provider the mechanism is exercised end to end (placeholder PDF; `[hi]`-tagged text).

A cook prints a clean recipe card at the scale they're cooking, and a temple serving a regional community prints it in the local language.

### Preconditions / setup
- A recipe with ingredient lines and a method (UAT-6).
- For real output on staging: `kms.documents.renderer=playwright` and `kms.translation.provider=google` with credentials; otherwise the stub demonstrates the flow.

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | On a recipe, request a **PDF** at the base yield | A document is queued; its status moves PENDING → READY |
| 2 | Download the READY document | A PDF downloads through the app (no public link); it opens |
| 3 | Request a PDF at a **scaled** yield (e.g. 500) | The card shows the scaled quantities |
| 4 | Request a **translated** card (e.g. Hindi) | The labels and ingredient names render in the chosen language (glossary terms preferred); numbers, and the recipe's structure, are intact |
| 5 | Use the **browser print view** | An A4-clean layout suitable for printing |

### Acceptance criteria
- [ ] A recipe PDF can be requested, polled to READY, and downloaded through the authorized app (no public URL).
- [ ] A scaled PDF shows scaled quantities; the base recipe is unchanged.
- [ ] A translated card renders in the chosen language with glossary terms honoured (real text with the provider wired; `[lang]`-tagged with the stub).
- [ ] The print view is A4-sane.

### What to look out for
- On staging, confirm the *real* renderer produces a genuine PDF (the default stub is a placeholder) and the *real* provider produces genuine translated text.
- A generation failure lands as FAILED on the document with a reason, not a hang.
- Devanagari/other scripts shape correctly in the PDF (fonts installed in the worker image).

### Defects
- _None yet._

---

## UAT-9 — Consumable inventory & the stock ledger

Exercises: E3-S1 (tracked consumables & stock view), E3-S2 (append-only movements + corrections), E3-S7 (manual adjustment + large-adjustment approval)
Status: READY

The temple's stock is a truth computed from an immutable ledger — every receipt, correction, and adjustment is a signed entry, and a big write-off needs an admin.

### Preconditions / setup
- A signed-in Kitchen Staff member (and a Temple Admin for the large-adjustment step).
- A few ingredients in the catalogue (UAT-6).

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Open **Inventory**; start tracking an ingredient (storage location, reorder threshold) | It appears with on-hand 0 |
| 2 | Record a receipt (e.g. +50 KG) | On-hand becomes 50; a movement appears in the ledger |
| 3 | Make a small manual adjustment (e.g. −2 KG, reason spoilage) | On-hand updates; the reason is recorded |
| 4 | Try to adjust below zero | Refused — `KMS-4910` |
| 5 | Correct a wrong movement | A compensating entry appears referencing the original; the original is untouched; correcting it twice is refused (`KMS-4908`) |
| 6 | As Kitchen Staff, attempt a **large** adjustment (over the approval fraction of on-hand) | Refused — `KMS-4305` (needs a Temple Admin) |
| 7 | As **Temple Admin**, make the same large adjustment with a reason | Allowed; recorded on the audit trail |

### Acceptance criteria
- [ ] On-hand is always the sum of ledger movements; nothing sets stock directly.
- [ ] A correction is a compensating entry that references the original; the ledger is never edited.
- [ ] A movement can be corrected only once.
- [ ] An adjustment that would go negative is refused; a large adjustment needs a Temple Admin and is audited.

### What to look out for
- Negative stock `KMS-4910`; already-corrected `KMS-4908`; large adjustment without admin `KMS-4305`.
- Batch/expiry/received-date are captured on receipts (Phase-2 food-safety groundwork) — confirm they persist.
- On-hand shown in the canonical unit; KG/L convert cleanly.

### Defects
- _None yet._

---

## UAT-10 — Reorder thresholds & low-stock alerts

Exercises: E3-S3 (reorder thresholds, low-stock view, nightly digest)
Status: READY (partial) — the threshold, the low-stock view, and the on-demand digest content are testable now; the digest actually *arriving* needs a channel provider (see UAT-3).

The kitchen sees at a glance what's running low, and leadership gets a nightly nudge.

### Preconditions / setup
- Tracked inventory items with reorder thresholds set (UAT-9).

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Set a reorder threshold on an item, then draw its stock below it (a consumption or adjustment) | The item shows a **Low** badge; the inventory header shows a low-stock count |
| 2 | Filter the inventory to "below reorder level only" | Only low items are shown |
| 3 | Raise the stock back above the threshold | The Low badge clears |
| 4 | (Staging, with a channel wired) Let the nightly low-stock digest run, or trigger it | A digest listing the low items is sent to the temple's leadership on their preferred channel |

### Acceptance criteria
- [ ] An item below its threshold is flagged Low and counted; above, it is not.
- [ ] The low-stock filter shows exactly the low items.
- [ ] The digest lists the correct items (content verifiable now; delivery needs a channel).

### What to look out for
- Threshold comparison is in the canonical unit.
- The digest is a background job (nightly, IST) — it must never block a request.
- An item with no threshold set is never "low".

### Defects
- _None yet._

---

## UAT-11 — Equipment register

Exercises: E3-S4 (equipment inventory & condition lifecycle)
Status: READY

The temple tracks its assets — vessels, burners, mixers — and their condition through repair and retirement.

### Preconditions / setup
- A signed-in Kitchen Staff member or Temple Admin.

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Open **Equipment**; register an item (name, details, condition) | It appears in the register |
| 2 | Change its condition — send for repair, then return to service | The condition history updates; the change is recorded |
| 3 | Scrap the item | It is marked scrapped |
| 4 | Try to change the condition of a scrapped item | Refused — `KMS-4912` |

### Acceptance criteria
- [ ] Equipment can be registered and edited.
- [ ] Condition changes are tracked over time.
- [ ] A scrapped item's condition cannot change; scrapping is on the audit trail.

### What to look out for
- Scrapped is terminal (`KMS-4912`) — the suggested next step is to register a replacement.
- A donated asset (UAT-12) points back to its donation.

### Defects
- _None yet._

---

## UAT-12 — In-kind donation intake

Exercises: E3-S5 (in-kind donation intake → stock / equipment)
Status: READY

Someone gifts goods — a sack of rice, a new vessel — and the temple records it so both the gift and the goods are accounted for.

### Preconditions / setup
- A signed-in Kitchen Staff member; a couple of ingredients and (optionally) the equipment register.

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Record an in-kind donation of a **consumable** (donor, item, quantity, estimated value) | Stock rises by the donated quantity via a donation-referenced movement; the donation is listed |
| 2 | Record an in-kind donation of **equipment** | A new equipment item is created, pointing back at the donation |
| 3 | Record an **anonymous** in-kind gift | It is captured with no donor identity |
| 4 | Provide donor contact and observe the thank-you (staging, with a channel) | A thank-you is queued to the donor |

### Acceptance criteria
- [ ] A consumable gift raises stock through a movement that references the donation.
- [ ] An equipment gift creates an asset linked to the donation.
- [ ] An anonymous gift stores no donor identity; a named gift can be thanked.
- [ ] Donations appear in the ledger (also see UAT-26).

### What to look out for
- The donated *goods* are the movement/equipment; the donation row is the intake event — confirm the linkage.
- Reading the donations list needs `VIEW_DONATIONS` (a leadership permission).

### Defects
- _None yet._

---

## UAT-13 — Vaishnava calendar & festival occasions

Exercises: E4-S1 (astronomical calendar engine), E4-S2 (festival occasion catalog), E4-S3 (admin calendar override)
Status: READY

The temple plans by the Vaishnava calendar: Ekadashis and festivals are computed for its location, the temple adds its own occasions, and an admin can override a computed date for a GBC ruling or an astronomical edge case.

### Preconditions / setup
- A signed-in Temple Admin. The temple's latitude/longitude/timezone are set (from onboarding).
- Cross-check dates against published ISKCON calendar for the temple's city (see `docs/CALENDAR-CORRECTNESS.md`).

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Open the **calendar / planner** and view the coming weeks | Ekadashis and major festivals are marked, matching the published calendar for the city |
| 2 | Add a temple **occasion** (name, date) | It appears on the calendar; a duplicate name is refused (`KMS-4913`) |
| 3 | **Override** a computed date with a reason | The date shows the override and the reason; the whole temple now plans by it |
| 4 | Revert the override | The date returns to the computed value; both actions are audited |

### Acceptance criteria
- [ ] Computed Ekadashis/festivals match the published ISKCON calendar for the temple's location (spot-check several, including at least one Ekadashi and one major festival).
- [ ] A temple occasion can be added/edited/removed; duplicate names refused.
- [ ] An admin override (and its revert) changes the effective date and is audited with the reason.

### What to look out for
- One known drik-vs-GCAL Ekadashi divergence is documented — see `docs/CALENDAR-CORRECTNESS.md`; note any *other* mismatch as a defect.
- Overriding a date needs `OVERRIDE_CALENDAR_DATE` (Temple Admin only).
- Occasion duplicate `KMS-4913`.

### Defects
- _None yet._

---

## UAT-14 — Meal planning & cooking

Exercises: E4-S4 (meal plan CRUD across contexts), E4-S5 (sufficiency & shortfall), E3-S6 (consumption on production)
Status: READY

A cook plans the day's meals, sees whether there's enough stock, and marks a meal cooked — which draws its ingredients from inventory.

### Preconditions / setup
- Recipes with ingredient lines (UAT-6) and tracked inventory with some stock (UAT-9).

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Open the **planner**; plan a meal (date, slot, recipe, target servings) | It appears on the plan as PLANNED |
| 2 | Check **sufficiency** for the planned range | Each meal shows SUFFICIENT or SHORT with the shortfall per ingredient |
| 3 | Plan a meal that needs more than is on hand | It shows SHORT with the exact shortfall |
| 4 | Mark a sufficient meal **cooked** | Its ingredients are drawn from stock (consumption movements); on-hand drops accordingly |
| 5 | Try to cook a meal that is short | Refused — `KMS-4911` (cook a smaller quantity or receive/adjust stock) |
| 6 | Try to cancel a meal that is already cooked | Refused — `KMS-4914` |
| 7 | Edit a meal that is already cooked/cancelled | Refused — `KMS-4915` |

### Acceptance criteria
- [ ] Meals can be planned across the four contexts and show correct status.
- [ ] Sufficiency shows the right shortfall per ingredient.
- [ ] Cooking a meal consumes exactly its scaled ingredients from stock.
- [ ] Cooking short stock, cancelling a cooked meal, and editing a closed meal are each refused with the right code.

### What to look out for
- Insufficient stock `KMS-4911`; cancel-cooked `KMS-4914`; edit-closed `KMS-4915`; duplicate slot `KMS-4916`.
- Consumption movements reference the meal plan; on-hand equals the ledger sum afterwards.
- A shortfall here re-appears on the order list (UAT-16).

### Defects
- _None yet._

---

## UAT-15 — Ekadashi enforcement

Exercises: E4-S6 (Ekadashi violation flagging), building on E4-S1 (calendar) and the ingredient Ekadashi flag
Status: READY

On Ekadashi the system warns before a grain- or bean-bearing recipe is cooked, and lets a cook proceed knowingly for non-fasting visitors.

### Preconditions / setup
- An ingredient flagged Ekadashi-prohibited (a grain or bean), used in a recipe (UAT-6).
- A date the calendar marks as Ekadashi (UAT-13).

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Plan a recipe containing grains/beans on an **Ekadashi** date and try to cook it | Flagged — `KMS-4917`; not cooked without acknowledgement |
| 2 | Acknowledge ("cook anyway for non-fasting visitors") and proceed | The meal cooks; the acknowledgement is recorded |
| 3 | Plan the same recipe on a **non-Ekadashi** date | No flag |
| 4 | Toggle an ingredient's Ekadashi-prohibited flag | The change is audited (religious-compliance decision) |

### Acceptance criteria
- [ ] A grain/bean recipe on Ekadashi is flagged before cooking (`KMS-4917`).
- [ ] Explicit acknowledgement lets it proceed and is recorded.
- [ ] Non-Ekadashi days are unaffected.

### What to look out for
- The flag keys off the *effective* calendar date, so an override (UAT-13) shifts it.
- Setting/clearing the Ekadashi flag is audited (`INGREDIENT_EKADASHI_FLAG_CHANGED`).

### Defects
- _None yet._

---

## UAT-16 — Vendors & the auto order list

Exercises: E5-S1 (vendors & supplies), E5-S2 (auto-generated order list)
Status: READY

The temple records who it buys from, and the system proposes what to order from data — meal-plan shortfalls plus low stock — suggesting the preferred vendor.

### Preconditions / setup
- Ingredients, some tracked inventory below threshold (UAT-9/10), and at least one planned meal driving a shortfall (UAT-14).

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Open **Vendors**; add a vendor (name, phone with country code, language) | Listed; a duplicate name is refused (`KMS-4918`) |
| 2 | On a vendor, set the ingredients it **supplies** and mark one **preferred** | The preferred supply is recorded (only one preferred per ingredient) |
| 3 | Open the **order list** and **regenerate** | Lines appear for shortfalls + low stock, with provenance (shortfall / top-up / PO-short) and the suggested preferred vendor |
| 4 | Edit a line's quantity or uncheck it; regenerate again | Your edit survives regeneration |
| 5 | Confirm a sattvic-prohibited ingredient never appears via the low-stock stream | It is absent (unless a legitimate shortfall put it there) |

### Acceptance criteria
- [ ] Vendors and their supplies can be managed; one preferred vendor per ingredient.
- [ ] The order list merges shortfall + low-stock with visible provenance and suggests the preferred vendor.
- [ ] Human edits to a line survive regeneration; unedited lines refresh.
- [ ] Deactivating a vendor hides it from new orders but keeps history.

### What to look out for
- Duplicate vendor name `KMS-4918`; phone must be E.164 (`+91…`).
- The order list is also refreshed by a nightly job — regenerate on demand to see it now.
- A sattvic-prohibited ingredient is excluded from the threshold stream by design.

### Defects
- _None yet._

---

## UAT-17 — Purchase orders & receiving

Exercises: E5-S3 (PO generation & lifecycle), E5-S6 (receiving: full/partial/rejected), E5-S4 (PO document)
Status: READY (partial) — the PO lifecycle, receiving, and versioned documents are testable now; a *real* rendered PO PDF needs the Playwright renderer on staging (default stub is a placeholder).

Approved order lines become purchase orders per vendor; goods are received against them — including short and rejected deliveries — and stock reflects the truck, not the order.

### Preconditions / setup
- An order list with checked lines that have a vendor (UAT-16).

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | From the order list, **generate purchase orders** for the checked lines | One draft PO per distinct vendor, with the right lines |
| 2 | Open a draft PO; edit it; then **mark sent** | Editable only while draft; after sending, an edit is refused (`KMS-4919`) |
| 3 | View/download the PO **document** | A versioned PO sheet (temple identity, vendor block with GSTIN, lines, price column only if priced) |
| 4 | **Receive** a delivery: some received, some rejected (with a reason), some short | Received quantities add stock (PO_RECEIPT movements with batch); rejected never touch stock; the PO becomes PARTIALLY_RECEIVED |
| 5 | Regenerate the order list | The outstanding (ordered − received) re-appears, traceable to the PO |
| 6 | Receive the balance | The PO flips to RECEIVED |
| 7 | Submit the same receipt twice (double-click) | Stock is not double-booked (idempotency) |

### Acceptance criteria
- [ ] Checked lines across N vendors generate exactly N correct draft POs.
- [ ] A PO is editable only as a draft; illegal transitions are refused (`KMS-4919` / `KMS-4920`).
- [ ] Received goods raise stock with batch; rejected goods are recorded but never enter stock; status auto-derives.
- [ ] The outstanding balance re-feeds the order list; a duplicate receipt does not double-book.

### What to look out for
- Edit-after-send `KMS-4919`; bad transition `KMS-4920`; receipt line not on the PO `KMS-4921`; empty receipt line `KMS-4922`.
- Cancelling a PO closes it and notifies (delivery of the notice needs a channel).
- On staging, confirm the *real* PO PDF renders (default stub is a placeholder), and long line-sets paginate.

### Defects
- _None yet._

---

## UAT-18 — PO translation & WhatsApp delivery

Exercises: E5-S5 (PO translation), E5-S7 (WhatsApp PO delivery)
Status: BLOCKED — needs the translation provider (Google) for real translated text and a live WhatsApp provider (Meta) for the vendor to actually receive the PO. The KMS-side flow (translate, send, record on the trail, webhook status, vendor flag on failure) is built and automated-tested; this UAT is the "a vendor actually got it" pass.

A shopkeeper who doesn't read English receives the purchase order in his language, on WhatsApp, the way Indian vendors actually communicate.

### Preconditions / setup
- A vendor with a preferred non-English language and a real WhatsApp number (staging).
- Translation provider and WhatsApp provider wired (see `docs/META_WHATSAPP_SETUP.md`).

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Open a SENT PO for a Hindi-preferring vendor; **print / view** it in the vendor's language | Labels and item names render in Hindi (glossary terms preferred); PO number, dates, amounts untouched |
| 2 | **Send on WhatsApp** | The vendor receives the PO (attachment or link); the send is recorded on the PO's activity trail and audited |
| 3 | Observe delivery status | The trail shows delivered (via webhook); a failed number flags the vendor for a phone recheck and offers the download/manual-share fallback |
| 4 | Immediately try to resend | Rate-guarded — `KMS-4925` |

### Acceptance criteria
- [ ] A test vendor number receives the PO on WhatsApp; delivery status lands on the trail via webhook.
- [ ] An undeliverable number shows a clear failure on the PO, flags the vendor, and leaves the download/manual path usable — no crash.
- [ ] Send and resend are audited; resend is rate-guarded.

### What to look out for
- A cancelled/received PO can't be sent (`KMS-4924`); rapid resend `KMS-4925`.
- Translated labels are curated/MT per language; check a native reader finds them acceptable.

### Defects
- _None yet._

---

## UAT-19 — Vendor invoices & payables

Exercises: E5-S8 (vendor invoice capture), E7-S8 (invoice payment recording & payables)
Status: READY

Staff capture a vendor's invoice into a clean payables queue; leadership records payments (made outside the app) until the books balance. The system never moves money.

### Preconditions / setup
- A received PO (UAT-17) and a vendor; a signed-in Temple Admin (payments) and Kitchen Staff (capture).

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Capture an invoice against a PO (number, date, amount, due date) | It appears PENDING in the payables queue |
| 2 | Capture a **direct** (no-PO) invoice with a description | Accepted; direct invoices without a description are refused (`KMS-4923`) |
| 3 | Where the PO lines have prices, observe the **variance** indicator | Shown when the invoiced amount differs from the received-quantity expectation; informational, never blocking |
| 4 | Capture a duplicate invoice number for the same vendor | Recorded with a soft **duplicate warning** (not blocked) |
| 5 | On the **payables** view, record a **partial** payment, then the balance | Paid-to-date tracks; the invoice flips PAID only at the full amount |
| 6 | Try to overpay | Refused — `KMS-4939`; paying an already-paid invoice — `KMS-4940` |
| 7 | Check the **aging** buckets | Invoices bucket as current / 1–30 / 31+ days overdue |

### Acceptance criteria
- [ ] Invoices (PO and direct) are captured into a PENDING payables queue; variance shows when prices exist.
- [ ] Full and partial payments work; PAID flips only at the full amount; overpayment refused.
- [ ] A payment is recorded (never executed), audited, and immutable (corrections via a compensating entry).
- [ ] Aging buckets are correct.

### What to look out for
- Direct-invoice needs description `KMS-4923`; overpayment `KMS-4939`; already-paid `KMS-4940`.
- The system records payments — confirm it never claims to *send* money anywhere.
- Duplicate invoice number is a soft warning, deliberately not a block.

### Defects
- _None yet._

---

## UAT-20 — Staff schedule

Exercises: E6-S1 (staff profiles, weekly template, per-date exceptions, week grid)
Status: READY (partial) — the schedule is fully testable; the *change notice* to the affected staff member arriving needs a channel provider (see UAT-3).

Everyone can see who works when: a weekly pattern per staff member, with one-off exceptions, and staff notified when their schedule changes.

### Preconditions / setup
- A signed-in Temple Admin; at least one Kitchen Staff user (UAT-5).

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Open **Staff schedule**; add a staff profile for a Kitchen Staff member (designation) | Created; adding a profile for a non-kitchen-staff user is refused (`KMS-4927`); a second profile for the same person (`KMS-4926`) |
| 2 | Set their **weekly template** (working days + hours, days off) | Saved; the week grid shows it |
| 3 | Add a **per-date exception** (a day off, or swapped hours) | That date reflects the exception; the template is unchanged |
| 4 | View the **week grid** across a month boundary | Template + exceptions render correctly for every staff member |
| 5 | As that staff member, open **My schedule** | Their own resolved schedule is shown |
| 6 | (Staging, with a channel) Change a schedule | Only the affected staff member is notified |

### Acceptance criteria
- [ ] A profile exists only for a kitchen-staff user, once.
- [ ] The weekly template + per-date exceptions resolve correctly on the grid, across a month boundary.
- [ ] An exception leaves the template untouched.
- [ ] A schedule change notifies the affected staff member and no one else (delivery needs a channel).

### What to look out for
- Non-kitchen-staff `KMS-4927`; duplicate profile `KMS-4926`.
- A working day needs a start-before-end; a day off carries no hours.

### Defects
- _None yet._

---

## UAT-21 — Volunteer shifts: post, sign up, release, waitlist

Exercises: E6-S2 (shift posting), E6-S3 (signup, atomic capacity), E6-S4 (release), E6-S5 (waitlist auto-promotion)
Status: READY

The full seva loop: a poster puts up a shift, volunteers claim spots, a full shift builds a waitlist, and a released spot is promoted automatically.

### Preconditions / setup
- A signed-in poster (Temple Admin or Kitchen Staff) and two or three Volunteer accounts (UAT-5).

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | As the poster, **post a shift** (title, date, time, capacity 1, reminder offsets) | It's immediately visible to volunteers with a live capacity count |
| 2 | As Volunteer A, open **Available shifts** and **sign up** | Confirmed; it appears in A's **My Shifts**; capacity shows full |
| 3 | As Volunteer B, view the now-full shift → **join waitlist** | B is queued with a position; signing up directly is offered only when there's room (`KMS-4934` if forced) |
| 4 | As Volunteer C, also join the waitlist | C is position 2 |
| 5 | As Volunteer A, **release** the spot | A leaves; the first waitlister (B) is **auto-promoted** and notified; C moves to position 1 |
| 6 | Sign up for a **time-overlapping** shift | Allowed, with an overlap warning |
| 7 | Duplicate a shift onto another date | All settings carry over |
| 8 | Cancel a shift | Everyone signed up / waitlisted is notified (delivery needs a channel); signups close |

### Acceptance criteria
- [ ] A posted shift is visible with a live capacity count.
- [ ] Capacity is atomic: the last spot goes to exactly one of two simultaneous sign-ups; the other gets a friendly full/waitlist response.
- [ ] Release frees the spot and auto-promotes the head of the waitlist (FIFO), who is notified.
- [ ] Overlap warns but is allowed; duplicate carries settings; cancel notifies and closes.

### What to look out for
- Full shift `KMS-4931`; double signup `KMS-4930`; release-when-not-on `KMS-4932`; already-waitlisted `KMS-4933`; join-waitlist-with-room `KMS-4934`; act-after-start `KMS-4929`; cancelled shift `KMS-4928`.
- Leaving the waitlist removes promotion eligibility immediately.

### Defects
- _None yet._

---

## UAT-22 — Shift reminders & broadcasts

Exercises: E6-S6 (scheduled shift reminders), E6-S7 (one-off broadcast)
Status: BLOCKED — needs a channel provider for a volunteer to actually receive a reminder/broadcast. The scheduling, rescheduling, per-signup reminder status on the roster, rate guard, and audit are built and tested; this UAT is the "did people actually get told?" pass.

Volunteers are reminded of shifts they signed up for, on their preferred channel, and a poster can blast a last-minute update to everyone.

### Preconditions / setup
- A channel provider wired (staging); a shift with volunteers signed up (UAT-21), reminder offsets set so one falls due soon.

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Sign up for a shift, then let a reminder offset fall due | The reminder arrives on the volunteer's preferred channel; the roster shows per-volunteer delivery status |
| 2 | Release before the offset | No reminder is sent (the job is cancelled) |
| 3 | Edit the shift time | Pending reminders reschedule to the new times |
| 4 | As the poster, **send an update to all** signed-up (optionally include waitlist) | Everyone selected receives it; per-recipient status shows on the roster; it's audited with the content |
| 5 | Send updates until the daily cap, then one more | The extra is blocked — `KMS-4935`; a Temple Admin can raise the limit in settings |

### Acceptance criteria
- [ ] A due reminder is received on the preferred channel; status shows on the roster.
- [ ] Release before the offset sends no reminder; a time edit reschedules pending ones.
- [ ] A broadcast reaches all signed-up (optionally waitlist) with per-recipient status, audited.
- [ ] The daily broadcast cap is enforced (`KMS-4935`) and admin-raisable.

### What to look out for
- A volunteer who signs up after an offset has passed simply skips that offset.
- Failed WhatsApp with SMS fallback records both attempts on the roster.

### Defects
- _None yet._

---

## UAT-23 — Public donation & 80G capture

Exercises: E7-S1 (public donation page), E7-S2 (one-time donation), E7-S4 (80G donor capture & anonymity), E7-S9 (webhook confirmation)
Status: READY (partial) — the public page, donor paths, order creation, and webhook confirmation are testable in Razorpay **test mode** (`kms.payments.provider=razorpay` + test keys, already in Secrets Manager); the default stub lets the flow be driven without the checkout widget.

A donor gives in under a minute on their phone, chooses how their identity is handled, and the gift is confirmed by the provider — not by the client.

### Preconditions / setup
- The public page URL `/t/{slug}/donate` for a temple (resolve by slug; the temple's `is_80g_approved` set for the 80G path).
- Razorpay test mode wired on staging for a real checkout; otherwise the stub confirms the flow.

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Open the donation page at 360px width, from a cold load | The temple's name/branding and preset amounts render fast; no login required |
| 2 | Try to tamper the tenant (a different slug / id) | The tenant is resolved server-side only; you cannot donate to a temple by supplying an id |
| 3 | Give **anonymously** | No personal details are captured; a clear "no 80G certificate" note is shown |
| 4 | Give **with your name** (no PAN) | Name (+ optional contact) captured; no PAN |
| 5 | Give **for 80G** — name, address, PAN | PAN format validated (`KMS-4004`); on a non-80G temple the 80G path is not offered (`KMS-4936` if forced); consent is required (`KMS-4937`) |
| 6 | Complete the payment in test mode | Only the signed **webhook** marks the donation COMPLETED; a client "success" without the webhook does **not** |
| 7 | As a Temple Admin, view the 80G donation and reveal the **PAN** | PAN decrypts for the admin and the access is audited; a non-admin cannot read it |

### Acceptance criteria
- [ ] The page renders tenant-correct branding/presets fast; all flows work without login.
- [ ] Tenant resolution is server-side only (id tampering has no effect).
- [ ] The three donor paths store exactly their fields — anonymous keeps zero PII; PAN is stored encrypted, admin-readable and audited.
- [ ] A donation is COMPLETED only via the signed webhook; a client-only "success" is not enough.

### What to look out for
- PAN format `KMS-4004`; non-80G temple `KMS-4936`; missing consent `KMS-4937`.
- Abandoned checkouts expire (PENDING → EXPIRED) on the hourly sweep.
- (Super-admin ops) A malformed/failed webhook is dead-lettered and replayable; the daily reconciliation flags a local/remote mismatch.

### Defects
- _None yet._

---

## UAT-24 — Recurring donations

Exercises: E7-S3 (recurring donation via subscription)
Status: READY (partial) — testable in Razorpay test mode; the mandate/checkout is the provider's, so a real run is a staging pass.

A donor sets up steady support at a frequency they choose, and manages it themselves.

### Preconditions / setup
- A **signed-in** donor account (recurring requires an account); Razorpay test mode on staging.

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | As a signed-in donor, set up a recurring donation (frequency, amount) | A plan is created ACTIVE with a subscription; a guest is refused (recurring needs an account) |
| 2 | Let a cycle charge (test mode) | A COMPLETED donation is recorded and attached to the plan; it shows in the plan's history |
| 3 | View **my plans** and payment history | Status and cycles are shown |
| 4 | **Cancel** the plan | Future charges stop (verify in test mode); the local status becomes CANCELLED |
| 5 | Force a failed cycle (test mode) | The failure is recorded and the donor is notified with retry guidance; plan status reflects the provider |

### Acceptance criteria
- [ ] A signed-in donor can set up a plan; a guest is gated behind account creation.
- [ ] Each cycle's charge creates a COMPLETED donation attached to the plan (idempotent).
- [ ] Cancellation stops future charges and updates the local status.
- [ ] A failed cycle is recorded and triggers a donor notification.

### What to look out for
- The ledger distinguishes recurring-cycle donations from one-time (UAT-26).
- Duplicate charge webhooks must not double-record a cycle.

### Defects
- _None yet._

---

## UAT-25 — Wish list & sponsorship

Exercises: E7-S5 (wish-list management), E7-S6 (public wish list & sponsorship checkout)
Status: READY (partial) — sponsorship checkout runs in Razorpay test mode; the admin side and the fulfilment/oversubscription logic are testable now.

The temple publishes concrete needs; a donor sponsors an item and knows exactly what their money provides.

### Preconditions / setup
- A signed-in Temple Admin (manage) and the public page `/t/{slug}/wishlist`.

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | As admin, add wish-list items (title, price, category, quantity wanted); set the manual order | They appear; the public page matches the manual order |
| 2 | On the public page, **sponsor** an item (full or partial units of a multi-quantity item) | A sponsorship donation is created and, on webhook confirmation, updates the item's progress |
| 3 | Sponsor the **final** unit | The item flips **Fulfilled 🙏**, stays visible briefly, then auto-archives (per tenant config) |
| 4 | Two donors race for the **last** unit | Exactly one sponsorship wins; the later completed payment becomes a general donation (never a failed charge), and that donor is notified |
| 5 | Check **sponsor recognition** | Named sponsors may be listed; an anonymous sponsor is never shown |

### Acceptance criteria
- [ ] Items CRUD with manual ordering reflected publicly.
- [ ] A sponsorship updates item progress and appears in the ledger linked to the item.
- [ ] Sponsoring the last unit flips/auto-archives per config; a race yields one sponsorship + one converted general donation, no orphaned charge.
- [ ] Anonymity controls public recognition.

### What to look out for
- Sponsoring an unavailable/closed item `KMS-4938`.
- Archived items vanish publicly but remain in ledger history.

### Defects
- _None yet._

---

## UAT-26 — Donations ledger & accounting

Exercises: E7-S7 (donations ledger & accounting view)
Status: READY

Every donation — online, recurring, wish-list, in-kind — in one filterable ledger the accountant can actually use.

### Preconditions / setup
- Seeded donations of each type (from UAT-12, 23, 24, 25); a signed-in Temple Admin (`VIEW_DONATIONS`).

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Open the **ledger** | All four types appear with correct category, amount, mode, and linkage (wish-list item / recurring plan / in-kind intake) |
| 2 | Filter by date range, type, and status | Rows narrow correctly |
| 3 | Read the **summary cards** | Month-to-date and FY-to-date (Apr–Mar) totals by type; a Mar-31 vs Apr-1 gift lands in the right FY |
| 4 | **Export CSV** | The CSV matches the on-screen filters; totals reconcile with the cards |
| 5 | Confirm anonymity | An anonymous donation shows as "Anonymous" with no PII anywhere — export included |
| 6 | Drill into a donor | Their giving history is grouped (by account, else PAN, else exact contact) |

### Acceptance criteria
- [ ] All four donation types appear with correct linkage and are filterable.
- [ ] CSV export matches filters; totals reconcile with the summary cards.
- [ ] Anonymous donations never leak PII in any surface, export included.
- [ ] The Indian FY boundary buckets correctly.

### What to look out for
- The ledger shows completed gifts by default; PENDING/FAILED appear only when filtered.
- No PAN or contact ever appears in the ledger or its export (PAN is admin-only via UAT-23).

### Defects
- _None yet._
