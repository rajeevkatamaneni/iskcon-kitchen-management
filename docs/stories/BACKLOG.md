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
