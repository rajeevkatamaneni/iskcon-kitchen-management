# Backlog — recorded, not yet scheduled

Nice-to-have work captured so it isn't lost, deliberately **out of the current build**. Each item
says where it came from and why it was deferred. Nothing here is committed scope; promoting an item
to an epic is a separate decision.

---

## BL-1 — Temple System Health Dashboard

**Origin:** Operations-page redesign, 2026-08-11. When the Super-Admin Operations page was trimmed to
platform-wide vitals (system health + total notifications sent/failed today with a 7-day trend), three
things it used to carry were pulled off it because they are **one temple's** operational detail, not a
platform operator's concern:

- the **per-temple notification breakdown** (sent / failed / suppressed for a chosen temple),
- the **recent failed sends** list (who wasn't reached, on which template), and
- **last calendar precompute** for that temple.

**As a** Temple Admin, **I want** a health view for *my* temple — were my reminders and receipts
actually delivered, what failed and to whom, and is my calendar up to date — **so that** I can debug
site-specific delivery problems without a platform operator, and without seeing any other temple.

**Why deferred:** not needed for the pilot. The platform operator's silent-failure guardrail (the
E1-S11 intent) is met by the platform-wide totals + trend; per-temple debugging can wait until a temple
is live and actually hits a delivery problem.

**Notes for when it's picked up:**
- The backend already supports the drill-in: `GET /api/v1/ops/tenants` and
  `GET /api/v1/ops/tenants/{id}` (behind `VIEW_PLATFORM_OPERATIONS`) return exactly this per-temple
  data — `OpsService.tenantOperations(...)` / `TenantOps`. They were left in place for this reason.
- For a **Temple Admin** (not the platform operator) the permission must change: this reads the temple's
  own data under its own RLS context, so it belongs behind a temple permission (e.g. a new
  `VIEW_TEMPLE_OPERATIONS`) in `RolePermissions.java`, and the query should read the caller's own tenant
  from context rather than take a `tenantId` path variable.
- The suppressed-count and recent-failures copy already exists in git history (pre-redesign
  `app/operations/page.tsx`) and can be lifted from there.


---

## BL-2 — Clone a day's meal plan to another day

**Origin:** Meal plan redesign discussion, 2026-08-14. Rajeev, on past days being read-only: *"As a
later feature, we can allow the user to clone a day's meal plan to a future day as a convenience
feature."*

**As a** Kitchen Staff member, **I want** to copy a day's meals onto another date, **so that** a
repeating pattern — the same weekday menu, last year's festival — does not have to be retyped.

**Why deferred:** E4-S7 is already a substantial redesign; cloning is a convenience on top of it and
needs its own thinking (does it copy servings? recipes that have since been archived? does it merge
with meals already planned on the target day?).

---

## BL-3 — Turnout outlook on the Today screen

**Origin:** Today-screen prototype review, 2026-08-15. Rajeev, on the panel in the prototype: *"Dont
include the Turnout outlook because that is not fully flushed out. We will do that as a future
enhancement."*

**As a** Kitchen Staff member, **I want** the Today screen to tell me how many people to expect,
**so that** I can cook to the day's real turnout rather than to last week's guess.

**Why deferred:** the idea is drawn but not designed. What the number is derived from is the open
question — served counts from past comparable days, the Vaishnava calendar (an Ekadashi and a
Janmashtami do not draw the same crowd), festival occasions already on the calendar, a manual
override from the temple, or some combination. Until that is settled, a number on the Today screen
would carry more authority than it has earned.

**Notes for when it's picked up:**
- The prototype's placement on Today is agreed; only the substance is missing.
- The inputs it would need mostly exist already: meals cooked and their served counts (E4), festival
  occasions and the calendar (E4-S8/S9), and the temple's own attendance records if any are kept.
- Decide first whether the outlook is a *forecast* (we compute it) or an *expectation* (the temple
  enters it and we show variance against what was actually served). Those are different features.

---

## BL-4 — Employee types below the role

**Origin:** Login/registration design conversation, 2026-08-15. Rajeev: *"Can we add employee types
which are different from the roles OR it could be derived from roles… Temple Admin… Kitchen
Manager… Head cook and other Cooks."*

**As a** Temple Admin, **I want** staff to hold the job they actually do, **so that** a cook sees the
job cards and the recipes and nothing about money, and a kitchen manager can plan, order and check
the roster without being given the run of the temple.

**Why deferred:** flagged by Rajeev himself as "for the future, just FYI". It is also a change to the
authorisation policy, which is the one document in this codebase that must stay legible.

**Notes for when it's picked up:**
- The three jobs named map onto permissions we already have. *Kitchen Manager* is close to today's
  `KITCHEN_STAFF` plus `MANAGE_VOLUNTEER_SHIFTS` (to see whether a shift is covered). *Head Cook* and
  *Cook* are narrower than anything we have: view the plan, open and print a job card, read and print
  a recipe in any language — and nothing that writes.
- My recommendation is **more roles, not a second concept beside them**. A "type" that sits next to a
  role means two things to check before every action, and two places for them to disagree.
  `RolePermissions.java` is designed to absorb this: add `KITCHEN_MANAGER`, `HEAD_COOK`, `COOK`, give
  each its set, and the policy stays one readable document.
- The one thing a role cannot express is *seniority within the same permissions* — a head cook and a
  cook may hold identical rights and differ only in who the job card is addressed to. If that
  distinction matters operationally, it belongs on the staff profile (E6-S1), not in the role.

---

## BL-5 — Knowing who someone is: photographs and identity documents

**Origin:** Requirements gathering with the temple admin, relayed 2026-08-15. ISKCON welcomes
everyone and extends trust by default; that trust has been abused more than once, by devotees and by
staff.

**As a** Temple Admin, **I want** to know that the person in front of me is the person in the record,
**so that** an open door is not an unguarded one.

**Why deferred:** it needs research before it needs building — the liveness check especially, which
is the difference between a photograph and a photograph of a photograph.

**Notes for when it's picked up:**
- **Every devotee: a photograph, taken live.** Not an upload — a capture, from the phone or laptop
  camera, checked to be a live human face rather than a duck, a flower, or a picture held to the
  lens. Rajeev's candidate for the check is Gemma; whatever the model, this is the part to prototype
  first, because a check that is easy to fool is worse than none: it manufactures confidence.
- **Staff, additionally: Aadhaar and PAN**, scanned or photographed at onboarding, read into
  structured data by OCR, verified against the government source where that is possible and simply
  kept on file where it is not.
- These are identity documents belonging to real people. Wherever this lands, the raw files need a
  retention rule, an access rule (who in a temple may open them, and when), and an audit entry for
  every read — the same standard as the donations ledger, not the same as a recipe photo.

---

## BL-6 — A platform-wide notice, and the ban that travels with it

**Origin:** Same conversation, 2026-08-15. *"IF a temple admin fires a staff member due to
misconduct… that information MUST be passed on to other temple sites… the temple admin should see it
the next day on their Today dashboard."*

**As a** Temple Admin, **I want** to hear from the rest of the platform when someone has been barred
elsewhere, **so that** the person another temple dismissed for cause is not quietly hired by mine.

**Why deferred:** it is two features, and the smaller one has to exist first.

**Notes for when it's picked up:**
- **The carrier is generic.** A platform-wide notice — from any temple, or from the operator — that
  lands on other temples' Today screens. Barring someone is only its first use; a Janmashtami advisory
  or a food-safety recall would ride the same rails. Build the carrier, then the use.
- **The ban itself** is recorded on a termination: the reason, who decided, and whether the person is
  not to be engaged elsewhere. Tenant isolation is the obstacle worth thinking about — this is
  deliberately cross-tenant, so what crosses must be the minimum that lets another temple recognise
  the person, and it must be recorded as having crossed.
- **Matching is the hard half, and it is probabilistic.** Someone evading detection changes a name, an
  email, a number. Score across everything known, including the photograph, and put anything above
  50% in front of the admin as a question rather than a verdict — every candidate shown, the decision
  theirs, the outcome recorded. For a devotee re-registering under a new identity, the photograph is
  the only durable signal: at a solid 80% or better, tell the temples they have registered with and
  let them deal with it.
- The failure mode to design against is not a miss. It is a confident false positive against a devotee
  who has done nothing, in a community whose stated posture is to welcome everyone.
