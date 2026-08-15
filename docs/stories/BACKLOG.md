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
