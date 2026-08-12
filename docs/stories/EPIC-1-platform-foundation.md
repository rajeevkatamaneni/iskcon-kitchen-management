# EPIC 1 — Platform Foundation

**Goal:** Everything every other epic stands on: repo/CI, cloud infrastructure, multi-tenancy with RLS, authentication, RBAC, audit logging, background jobs, notifications, and observability.
**Depends on:** nothing. **Blocks:** all other epics.
**Labels:** `epic:foundation`

---

## E1-S1 — Project scaffolding and CI pipeline

**Verified by:** automated tests only — infrastructure story with no manual UAT surface.

**As a** developer, **I want** a monorepo with backend and frontend skeletons and a CI pipeline, **so that** every subsequent story lands on tested, deployable rails.

**Assumptions:** Monorepo (single repo `iskcon-kitchen-management`) with `/backend` (Spring Boot 3.x, Java 21, Gradle) and `/frontend` (Next.js, TypeScript). GitHub Actions per TECH_STACK.md v1.0.

**Requirements:**
- Spring Boot skeleton: health endpoint (`/health`), layered structure (controller/service/repository), Testcontainers-based integration test harness.
- Next.js skeleton: Tailwind + Radix configured, one placeholder page, Vitest + RTL harness.
- GitHub Actions: on PR — build both apps, run all tests, fail on test failure; on merge to `main` — build container images.
- README with local dev setup (Docker Compose for local Postgres).

**Acceptance criteria:**
- [ ] `./gradlew test` and `npm test` pass locally and in CI on a fresh clone.
- [ ] A PR with a failing test cannot be merged (branch protection).
- [ ] `docker compose up` gives a working local Postgres; backend connects to it.
- [ ] CI produces runnable container images for backend and frontend on merge to `main`.

---

## E1-S2 — GCP infrastructure baseline

**Verified by:** automated tests only — infrastructure story with no manual UAT surface.

**As a** platform operator, **I want** the production and staging environments provisioned as code, **so that** deploys are repeatable and nothing is hand-configured.

**Assumptions:** GCP `asia-south1`, per TECH_STACK.md v1.0. Terraform for IaC (industry default; no competing constraint recorded).

**Requirements:**
- Terraform config: Cloud Run services (backend API, worker, frontend), Cloud SQL Postgres (regional HA prod / single-zone staging), GCS bucket, Secret Manager, service accounts with least privilege.
- Cloudflare in front (DNS, CDN, WAF, rate limiting) — free tier.
- Staging and production as separate GCP projects or clearly separated resource sets.
- Budget alarm at $150/month on the GCP billing account.

**Acceptance criteria:**
- [ ] `terraform apply` from clean state provisions a working environment; documented in `/infra/README.md`.
- [ ] Backend health endpoint reachable through Cloudflare over HTTPS in staging.
- [ ] App's DB role has no DDL and no BYPASSRLS (verified by an automated check or documented manual test).
- [ ] Budget alarm fires a notification at the threshold (test with a low temporary threshold).

---

## E1-S3 — Tenant model and Row-Level Security

**Verified by:** [UAT-002](../uat/UAT-002-bring-a-temple-onto-the-platform.md), [UAT-006](../uat/UAT-006-one-temple-cannot-see-another.md)

**As a** platform operator, **I want** every tenant-owned table isolated by Postgres RLS, **so that** cross-temple data leakage is impossible at the database layer.

**Assumptions:** Shared schema + `tenant_id` column + RLS per SYSTEM_DESIGN.md §3. Flyway for migrations.

**Requirements:**
- `tenants` table: name, slug (unique, URL-safe), address, latitude, longitude, timezone, currency (default INR), locale, `is_80g_approved`, status.
- Migration template/convention: every tenant-owned table gets `tenant_id` FK + RLS policy filtering on `current_setting('app.tenant_id')`.
- Spring filter/interceptor + Hibernate connection hook: resolve tenant from authenticated principal, `SET LOCAL app.tenant_id` per transaction. Requests without a resolvable tenant (other than super-admin paths and public slug-scoped pages) are rejected.
- Integration tests (Testcontainers) proving: tenant A cannot read/write tenant B's rows even with a buggy repository query.

**Acceptance criteria:**
- [ ] RLS integration test suite passes, including a deliberate "forgot the WHERE clause" test that returns only the current tenant's rows.
- [ ] Connecting as the app role without setting `app.tenant_id` returns zero rows from tenant-owned tables.
- [ ] Tenant record captures coordinates + timezone (required, validated) per calendar engine needs.
- [ ] Migration docs describe the RLS convention so every future table follows it.

---

## E1-S4 — Firebase Authentication integration

**Verified by:** [UAT-001](../uat/UAT-001-platform-operator-sign-in.md), [UAT-007](../uat/UAT-007-first-sign-in-as-temple-admin.md), [UAT-012](../uat/UAT-012-ways-to-sign-in.md)

**As a** user, **I want** to sign in with email/password or phone OTP, **so that** I can access the app the way that suits me (many volunteers have phones, not email habits).

**Assumptions:** Firebase Auth per TECH_STACK.md v1.0. Backend verifies Firebase ID tokens; app-level user record links `firebase_uid` → `user_id`, `tenant_id`, `role`.

**Requirements:**
- Frontend: Firebase SDK sign-in flows — email+password, phone OTP, Google sign-in.
- Backend: token verification filter; maps verified token to app user; rejects unknown/disabled users.
- `users` table: firebase_uid, tenant_id, name, email, phone, role, communication preference (see E1-S8), status.
- Session handling per SYSTEM_DESIGN.md §4: JWT carries user/tenant/role claims; `tenant_id` never accepted from request parameters.

**Acceptance criteria:**
- [ ] A user can sign up / sign in via email+password and via phone OTP in staging against real Firebase.
- [ ] Backend rejects: missing token, expired token, valid-Firebase-but-unknown-app-user.
- [ ] Disabling a user in the app blocks access within one token lifetime, and immediately for new sign-ins, even with valid Firebase credentials.

---

## E1-S5 — Role-based access control

**Verified by:** [UAT-001](../uat/UAT-001-platform-operator-sign-in.md), [UAT-005](../uat/UAT-005-who-can-see-what.md), [UAT-006](../uat/UAT-006-one-temple-cannot-see-another.md)

**As a** Temple Admin, **I want** each role to see and do only what it's allowed, **so that** volunteers can't touch finances and staff can't manage tenants.

**Assumptions:** Roles fixed for release 1: `SUPER_ADMIN`, `TEMPLE_ADMIN`, `KITCHEN_STAFF`, `VOLUNTEER` (donors are unauthenticated or lightweight accounts). One tenant per user except SUPER_ADMIN.

**Requirements:**
- Central authorization: endpoint declares required permission via annotation; single enforcement point (no per-controller ad-hoc checks).
- Permission → role mapping in one place, documented.
- Frontend route guards + navigation reflecting role (donor/volunteer/staff/admin nav per approved wireframe).
- 403 responses are clean and logged with actor + attempted action.

**Acceptance criteria:**
- [ ] Matrix test: for each (role × representative endpoint) pair, access matches the documented permission map.
- [ ] A VOLUNTEER token calling a payments endpoint gets 403 and an audit-relevant log line.
- [ ] Adding a permission to a new endpoint requires only the annotation + one map entry (demonstrated in test).

---

## E1-S6 — Tenant provisioning (Super-Admin)

**Verified by:** [UAT-002](../uat/UAT-002-bring-a-temple-onto-the-platform.md), [UAT-003](../uat/UAT-003-view-and-delete-a-temple.md), [UAT-007](../uat/UAT-007-first-sign-in-as-temple-admin.md)

**As a** Platform Super-Admin, **I want** to onboard a new temple with its first admin account from a screen, **so that** provisioning doesn't mean SQL by hand.

**Assumptions:** Super-Admin screens live in the Next.js app behind `SUPER_ADMIN` role, per TECH_STACK.md §2 decision.

**Requirements:**
- Screen: create tenant (all fields from E1-S3, with map-assisted or manual lat/long + timezone entry, validation) + create first TEMPLE_ADMIN user (email/phone).
- Provisioning runs through a dedicated audited code path (SYSTEM_DESIGN.md §3).
- Tenant list screen: status, created date, basic health (user count) — read-only for release 1.
- Newly provisioned tenant gets calendar precompute queued automatically (dependency for E4).

**Acceptance criteria:**
- [ ] End-to-end: Super-Admin creates a tenant + admin; that admin can immediately sign in and sees an empty-but-working temple workspace.
- [ ] Invalid coordinates/timezone rejected with actionable messages.
- [ ] Provisioning writes an audit event with actor, tenant, timestamp.
- [ ] Non-super-admin roles get 403 on all provisioning endpoints.

---

## E1-S7 — Audit log framework

**Verified by:** [UAT-009](../uat/UAT-009-change-a-role-disable-restore.md), [UAT-011](../uat/UAT-011-the-temple-audit-log.md), [UAT-014](../uat/UAT-014-the-prohibited-flag-is-admin-only.md), [UAT-025](../uat/UAT-025-large-adjustments-need-an-admin.md)

**As a** Temple Admin, **I want** sensitive actions recorded immutably, **so that** finances, inventory corrections, and overrides are always explainable.

**Assumptions:** Append-only `audit_events` per SYSTEM_DESIGN.md §5. Written from the shared kernel, not per-module ad-hoc.

**Requirements:**
- `audit_events`: actor, tenant, action type, entity type/id, before/after (JSONB), reason (optional text), timestamp. No UPDATE/DELETE grants for the app role on this table.
- Simple API for modules to emit events; wired into: role changes, tenant provisioning (E1-S6) now; consumed by later epics (overrides, adjustments, payments).
- Temple Admin screen: filterable audit log viewer (date range, action type, actor), scoped to their own tenant by RLS.
- Super-Admin has no cross-tenant firehose. `audit_events` is tenant-owned and the app role holds no BYPASSRLS, so a Super-Admin views a temple's history by **drilling into one tenant at a time** — select a temple, its context is set, they read only its rows. A global feed is deliberately absent: audit `before/after` values carry donation amounts and payment records, so a firehose would expose data the Super-Admin role is denied by E1-S5 (`VIEW_DONATIONS`, `MANAGE_VENDOR_PAYMENTS`) through a side door. Cross-tenant operator visibility lives in Cloud Logging (structured logs carry `tenant_id`/`user_id`/`request_id`), not here.
- A Super-Admin drilling into a temple's log is itself an audited action ("platform operator viewed this log"), recorded in that tenant's log so the capability is never silent.
- Role change is the one Epic 1 path with a real prior state, so it is the exemplar of before/after capture. This story ships a tightly-scoped seed endpoint — `PATCH /api/v1/users/{id}/role`, `MANAGE_USERS` — with no UI (E1-S6's screens suffice for now); the full user-management surface is E1-S12. Because it is a privilege-escalation surface it carries four guards: a user cannot change their own role; no one may be promoted to `SUPER_ADMIN` through it (that role is minted only by provisioning); cross-tenant changes are impossible (RLS, asserted in test); and every change is audited — including **rejected** attempts, since a refused escalation is exactly what a log reviewer wants to see.

**Acceptance criteria:**
- [ ] App role cannot UPDATE or DELETE audit rows (enforced by DB grants, proven in test).
- [ ] Role change and tenant provisioning produce correct before/after entries (provisioning's `before` is null — a creation).
- [ ] Role-change guards hold: self-change refused, promotion to `SUPER_ADMIN` refused, cross-tenant target refused; each refusal writes an audit event.
- [ ] Temple Admin viewer is RLS-scoped to their own tenant; a Super-Admin can only read a temple's log by drilling into that tenant, and doing so writes an audit event in that tenant's log.
- [ ] Viewer paginates 10k+ events without degradation (seeded test data).

---

## E1-S8 — User accounts: contact channels and communication preference

**Verified by:** [UAT-010](../uat/UAT-010-your-profile-and-consent.md)

**As a** user, **I want** to register my email and phone and pick my preferred channel, **so that** reminders reach me where I actually look.

**Assumptions:** Per locked requirement: both email and phone collected at account creation; user selects one default channel — WhatsApp, SMS, or email. WhatsApp rides the phone number.

**Requirements:**
- Registration/profile: email + phone both required (E.164 validation for phone), preference selector (default WhatsApp per India-first).
- Phone verified via Firebase OTP when phone sign-in used; otherwise a verification message on first notification attempt.
- Preference changeable in profile; consumed by notification service (E1-S10).
- DPDP-consistent consent text at collection (purpose: reminders and service communication).

**Acceptance criteria:**
- [ ] Cannot complete registration without valid email and phone.
- [ ] Preference persists and is returned by the profile API; changing it takes effect on next notification.
- [ ] Consent text shown at collection; acceptance recorded with timestamp.

---

## E1-S9 — Background job infrastructure (Quartz)

**Verified by:** [UAT-004](../uat/UAT-004-platform-operations-and-health.md)

**As a** developer, **I want** a persistent job system, **so that** reminders, PDFs, calendar precompute, and sends run off the request path and survive restarts.

**Assumptions:** Quartz with JDBC job store on the same Postgres, per TECH_STACK.md v1.0. Worker runs as a separate Cloud Run service from the API (same image, different entrypoint).

**Requirements:**
- Quartz configured with JDBC store, misfire handling, and per-job retry policy (max attempts + backoff).
- Job types registered by later epics; this story ships the harness + one demo job (e.g. nightly no-op heartbeat).
- Failed-job visibility: failures logged with context and countable (feeds E1-S11 ops page).
- Idempotency convention documented: every job must be safe to run twice.

**Acceptance criteria:**
- [ ] A scheduled job fires on time in staging; killing the worker mid-run results in re-execution after restart without duplicate side effects (demonstrated with the demo job).
- [ ] A job that throws is retried per policy, then parked as failed and visible in logs/metrics.
- [ ] Job execution records include tenant context where applicable.

---

## E1-S10 — Notification service (WhatsApp / SMS / email)

**Verified by:** [UAT-023](../uat/UAT-023-reorder-thresholds-and-low-stock.md), [UAT-043](../uat/UAT-043-send-a-po-on-whatsapp.md), [UAT-052](../uat/UAT-052-shift-reminders.md), [UAT-053](../uat/UAT-053-broadcast-an-update.md)

**As a** system, **I want** one internal API to send a message to a user or vendor via the right channel with fallback, **so that** every module sends notifications the same way.

**Assumptions:** WhatsApp via Meta Cloud API direct (utility templates); SMS + transactional email provider chosen at implementation (any commodity provider; not architecturally significant). Fallback cascade: preferred channel → SMS → email, per SYSTEM_DESIGN.md §6.

**Requirements:**
- Internal API: `notify(recipient, template, params, channelOverride?)` — resolves user preference (E1-S8) or explicit vendor phone; renders approved template; sends via channel adapter; records delivery status.
- Meta setup: Business verification, WhatsApp number registration, utility template approval (shift reminder, PO delivery) — tracked as a checklist inside this story since it has external lead time. **Start this early; Meta approval is the long pole.**
- Delivery-status webhook endpoint (signature-verified, idempotent) updating message records.
- All sends via background jobs (E1-S9), never inline in requests.

**Acceptance criteria:**
- [ ] A test user receives a real WhatsApp template message in staging; delivery status lands on the message record via webhook.
- [ ] Forced WhatsApp failure falls back per cascade and records both attempts.
- [ ] Vendor sends (no user account) work with a raw phone number.
- [ ] Duplicate webhook deliveries do not duplicate status transitions.

---

## E1-S11 — Observability baseline

**Verified by:** [UAT-004](../uat/UAT-004-platform-operations-and-health.md)

**As a** solo operator, **I want** errors, logs, metrics, uptime, and job health visible in minutes per day, **so that** silent failure — the likeliest incident at this scale — can't hide.

**Assumptions:** Sentry (free tier) + Cloud Logging/Monitoring + external uptime ping, per SYSTEM_DESIGN.md §10.

**Requirements:**
- Structured JSON logs everywhere with `tenant_id`, `user_id`, `request_id`.
- Sentry wired into backend and frontend; unhandled exceptions alert.
- `/health` checks DB + Quartz scheduler liveness; external monitor pings it with phone/WhatsApp alerting.
- Admin ops page (Super-Admin): system health + platform-wide notifications sent/failed today as a 7-day pulse of 2-hour buckets — per SYSTEM_DESIGN.md §10 "business observability" (v1.1). Per-temple detail (breakdown, recent failed sends, last calendar precompute) is deferred to a Temple System Health Dashboard (docs/stories/BACKLOG.md BL-1).

**Acceptance criteria:**
- [ ] A thrown test exception appears in Sentry with request context within a minute.
- [ ] Killing the DB in staging turns `/health` unhealthy and triggers the external alert.
- [ ] Ops page reflects a deliberately failed job within one refresh.
- [ ] Log lines for one request share a `request_id` across API and worker boundaries (traceable in Cloud Logging).

---

## E1-S12 — Temple user management

**Verified by:** [UAT-005](../uat/UAT-005-who-can-see-what.md), [UAT-008](../uat/UAT-008-add-your-team.md), [UAT-009](../uat/UAT-009-change-a-role-disable-restore.md)

**As a** Temple Admin, **I want** to add people to my temple, change what they can do, and disable those who leave, **so that** a temple with one administrator can actually staff itself.

**Assumptions:** Surfaced by E1-S7: `MANAGE_USERS` exists and E1-S6's UI tells the first administrator they "can add everyone else," but no story built the surface — a temple with one admin and no way to add a cook isn't usable. The role-change endpoint seeded in E1-S7 is the starting point; the rest belongs here. Roles remain fixed per E1-S5 (no custom roles). Adding a user creates the app-side `users` record ahead of their first Firebase sign-in, exactly as provisioning creates the first admin (`pending:` firebase_uid until they authenticate).

**Requirements:**
- Invite/add a user: name, email, phone (E.164), role (from the fixed set, `SUPER_ADMIN` excluded), preferred channel — validated as at provisioning. Duplicate email per tenant rejected (`KMS-4902`).
- Change a user's role: reuses the E1-S7 endpoint and its four guards; exposed in the UI here.
- Disable / re-enable a user: `status` flip that blocks access on next request (per E1-S4), never a hard delete — history and audit references must survive.
- All three actions run through the shared audit kernel (E1-S7) with before/after.
- Temple Admin screen: user list (name, role, status, last activity if available) with add / change-role / disable actions, all behind `MANAGE_USERS`.

**Acceptance criteria:**
- [ ] A Temple Admin can add a user who can then sign in and lands in the correct temple with the assigned role.
- [ ] Changing a role and disabling a user take effect within one token lifetime, immediately for new sign-ins.
- [ ] All four role-change guards from E1-S7 hold when exercised through this UI.
- [ ] A disabled user cannot access the app but their audit history and past references remain intact.
- [ ] Every add / role-change / disable writes an audit event with actor, target, and before/after.

## E1-S13 — Platform super-admin bootstrap

**Verified by:** [UAT-001](../uat/UAT-001-platform-operator-sign-in.md), [UAT-007](../uat/UAT-007-first-sign-in-as-temple-admin.md)

**As a** platform operator, **I want** a defined, safe way to create the first (and any later) platform super-admin, **so that** a freshly deployed installation can actually be operated — someone has to be able to sign in and provision the first temple.

**Assumptions:** Surfaced while standing up the backend for UAT. Epic 1 assumes super-admins exist (E1-S6 provisioning, E1-S11 ops, the ops UI) but **no story defines how one comes to exist** — E1-S7's note even says the role is "minted only by provisioning," which is inaccurate: provisioning creates a *temple* admin. Two concrete gaps, both invisible until a real boot because local/dev connect as a DB superuser that bypasses RLS:
1. **No creation path.** A super-admin has `tenant_id IS NULL`. The app role (`kms_app`) cannot INSERT such a row — the write policy's `WITH CHECK tenant_id = app.tenant_id` can never be satisfied for a null tenant — and there is deliberately no app endpoint to mint operators.
2. **No claim path.** Even given a manually-seeded `pending:` super-admin row, first sign-in cannot bind it: `PendingAccountClaim.adopt` runs an ordinary tenant-scoped UPDATE, and V4 added only a *read* escape. The bind is refused, so the operator can never sign in.

**Design:** creating a platform operator is a privileged, rare, out-of-band act — never reachable through the running application. So the *creation* stays a documented, operator-run SQL insert via the privileged Cloud SQL admin connection (the only role that bypasses RLS), and the *claim* is made to work by adding the missing write escape, mirroring V4's read escape exactly.

**Platform-level audit → E1-S14.** Building the claim surfaced a second gap: `audit_events` is tenant-scoped by design (E1-S7: `tenant_id NOT NULL`, per-tenant RLS, read per-tenant per [[super-admin-audit-drill-in]]), so a super-admin's `ACCOUNT_CLAIMED` event has nowhere to be stored. Resolved by giving tenantless actions their own home — a `platform_audit_events` table, specced as **E1-S14** and a dependency of this story. The claim records there via `AuditService.recordPlatform`, so an operator's first sign-in is audited, not merely logged.

**Requirements:**
- A migration adds a narrow, permissive `FOR UPDATE` RLS policy on `users` that permits binding a real Firebase uid onto a row that is still `pending:`, tenantless (`role = 'SUPER_ADMIN'`), and whose email or phone equals `app.claim_contact` (set by the auth filter alone, only during a claim). Being `FOR UPDATE` it widens no INSERT/DELETE — the app still cannot create a super-admin — and it is single-use, since the row is no longer pending after adoption.
- A documented bootstrap procedure in `DEPLOYMENT.md`: as the privileged DB role, insert one `pending:<uuid>` super-admin row for the operator's verified email/phone; the operator then signs in with that identity and is claimed. The same procedure adds later operators.
- CI guard against the class of bug that hid this: integration tests run Hibernate `ddl-auto=validate` (not `none`) so entity/schema drift fails the suite instead of only a production boot.

**Acceptance criteria:**
- [ ] Running as the unprivileged app role, a seeded `pending:` super-admin is bound to their real uid on first sign-in and `whoami` returns `SUPER_ADMIN` with a null tenant.
- [ ] The write escape cannot INSERT a tenantless row, and cannot bind a row whose contact does not match the verified `app.claim_contact`.
- [ ] After adoption the row is no longer claimable through the escape.
- [ ] The claim writes an `ACCOUNT_CLAIMED` event to the platform audit log (E1-S14), since a super-admin belongs to no temple.
- [ ] `DEPLOYMENT.md` documents seeding the first operator; the app exposes no endpoint that mints a super-admin.
- [ ] The integration suite runs under `ddl-auto=validate`.

## E1-S14 — Platform-level audit log

**Verified by:** automated tests only — infrastructure story with no manual UAT surface.

**As a** platform operator, **I want** platform-level actions — ones that belong to no single temple — recorded in an immutable log only operators can read, **so that** onboarding an operator and other platform acts are as explainable as anything inside a temple, without weakening tenant isolation.

**Assumptions:** Surfaced by E1-S13. `audit_events` (E1-S7) is tenant-owned by deliberate design: `tenant_id NOT NULL`, per-tenant RLS, and read only per-tenant ([[super-admin-audit-drill-in]]). A super-admin sits in no tenant, so their actions cannot be stored there. Rather than make `audit_events.tenant_id` nullable — which would weaken the "every audit row belongs to a temple" invariant every existing read assumes — platform events get their own named table ([[no-unnamed-abstractions]]).

**Requirements:**
- A `platform_audit_events` table: the shape of `audit_events` minus `tenant_id`, append-only (`make_append_only`), written only by the shared audit kernel.
- Isolation by role, not tenant: RLS admits read and append only when the connection's verified identity (`app.auth_uid`) resolves to a `SUPER_ADMIN` user row. A temple user — valid token, tenant set — sees nothing; an unauthenticated connection matches nothing. No `BYPASSRLS`, no cross-tenant feed.
- `AuditService.recordPlatform(...)`: the tenantless counterpart to `record`, used by any platform-level action (first: the E1-S13 super-admin claim; later: platform ops).
- A super-admin read surface for the platform log. *(Backend + policy land with E1-S13; the operator-facing screen is deferred until there is more than sign-in events to show — tracked here.)*

**Acceptance criteria:**
- [ ] A super-admin action with no tenant records a `platform_audit_events` row; no tenant-scoped `audit_events` row is written for it.
- [ ] A super-admin can read the platform log; a temple user (valid token, tenant set) reads zero rows; an unauthenticated connection reads zero rows — all enforced by RLS, proven as the unprivileged app role.
- [ ] The table is append-only: the app role holds no `UPDATE`/`DELETE`.
- [ ] Writing is confined to the shared kernel (`AuditService.recordPlatform`); no module inserts directly.

## E1-S15 — Temple detail, data export, and permanent deletion

**Verified by:** [UAT-003](../uat/UAT-003-view-and-delete-a-temple.md)

**As a** Platform Super-Admin, **I want** to open one temple, take a complete copy of its data, and — when it should no longer exist — erase it permanently, **so that** test tenants, duplicates and departed temples can be removed without leaving orphaned data, and without anything being destroyed that nobody kept a copy of.

**Written retrospectively (2026-08-11).** The view and delete halves shipped in `ab7e073` with fixes in `137442e` and `43fe528` before any story existed for them — found by the UAT pack's traceability pass (TRACEABILITY.md gap G1). This story states what was built and why, and adds the one thing it was missing: a data export taken before the erasure. Recording it late is worse than writing it first; leaving it unwritten would have been worse still.

---

### Assumptions and decisions

Each of these is a choice already baked into the shipped code or made when this story was written. They are listed so that a future reader does not have to reverse-engineer them.

**D1 — Deletion is permanent, and permitted regardless of what the temple holds.** Considered and rejected: refusing to delete a temple that has completed donations or recorded vendor payments (which would forbid the irreversible act on exactly the records whose loss matters most, and force a suspend-instead lifecycle). **Rajeev's decision, 2026-08-11:** keep deletion unconditional; the safeguard is the export (D6), not a guard on what the data contains. The trade-off is recorded rather than hidden — an operator can destroy a temple's donation and audit history, and the export is what makes that recoverable.

**D2 — It is a separate permission.** `DELETE_TENANT` is held by `SUPER_ADMIN` alongside `MANAGE_TENANTS` but declared separately, so the graver capability can be withheld without also withholding provisioning.

**D3 — The confirmation is the temple's own name.** A generic "type DELETE" becomes muscle memory; typing *this* temple's name forces the operator to look at which temple they are about to erase. Enforced in the screen; the API takes the operator's word, because the API is already gated on the rarest permission in the system.

**D4 — The record of the deletion is written before the deletion, on the platform log.** The temple's own `audit_events` are erased with it, so a tenant-scoped record would destroy itself. `TENANT_DELETED` goes to `platform_audit_events` (E1-S14), which carries no `tenant_id` and survives. Its `before` is the temple's identity snapshot; its `after` is null — a removal.

**D5 — The erasure is one database function, not application code.** Every tenant-owned table references `tenants` with `ON DELETE RESTRICT`, and nine are append-only with the app role's `DELETE` revoked (`make_append_only`, V3). `delete_tenant_cascade` (V44, fixed in V45/V46) is the single audited path allowed to cross both guards, and only ever for a whole-tenant purge. It runs `SECURITY DEFINER` as the schema owner; sets `app.tenant_id` transaction-locally so RLS confines the deletes; temporarily re-grants itself `DELETE` on the append-only tables and revokes it again in the same transaction, so no other connection ever observes the guard down and any rollback restores it; and retries passes until one deletes nothing new, rather than hardcoding a delete order across dozens of interlocking tables. Tables are discovered by "has a `tenant_id` column", so a table added later is purged without anyone remembering to update a list.

**D6 — A data export must be taken before a temple can be deleted, and it is enforced by the API, not by the screen.** Deletion is refused unless a `TENANT_EXPORTED` event exists for that temple **within the last 24 hours** (`KMS-4941`). An export from last month is not a safeguard; a window makes the guarantee testable and explainable. The same audit event is both the record and the check, so the two cannot drift apart.

**D7 — The export is an Excel workbook, one sheet per table, raw rows.** Not CSV (a folder of files nobody can navigate), not JSON (unreadable to the temple accountant who is the likely reader). Each sheet is one table: the header row carries the column names, an autofilter is applied to it, and the header is frozen — so anyone opening it later can sort and filter without knowing anything about the system. Values are written as stored.

**D8 — The export covers every table carrying a `tenant_id`, discovered the same way the purge discovers them, plus the temple's own row.** Anything the purge destroys, the export contains, by construction rather than by a maintained list.

**D9 — Column-encrypted values are exported as stored, still encrypted.** A donor's PAN is ciphertext in the database (E7-S4) and stays ciphertext in the workbook. A platform operator is denied `VIEW_DONATIONS` by the permission model, and an export must not become a side door to plaintext donor PII; the archive is still complete for whoever holds the key. Recorded here because it is a deliberate limitation of the archive, not an oversight.

**D10 — The export is generated and streamed in the request, not queued as a background job.** It has to be in the operator's hands *before* the delete they are about to perform, and a job plus a signed URL adds a race and a failure mode for no benefit at the data sizes involved. Revisit if a temple's export ever grows past what a request can carry comfortably.

**D11 — The file is named after the temple**: `<Temple Name> — Data Export — <YYYY-MM-DD>.xlsx`, so a file sitting in a folder a year later still says whose data it is.

**D12 — The export rides `DELETE_TENANT`, not `MANAGE_TENANTS`.** It exists to make deletion safe and it hands over the temple's entire business in one file, so it belongs with the graver permission rather than with routine platform administration.

---

**Requirements:**

- **Temple detail screen** (`/tenants/{id}`): name, when it was added, how many people have accounts, timezone, currency, 80G, address; the temple's public web address with a copy action; and the two destructive-area actions below.
- **Data export**: `GET /api/v1/tenants/{id}/export` behind `DELETE_TENANT`, returning an `.xlsx` workbook per D7–D11 and writing `TENANT_EXPORTED` to the platform audit log with the per-table row counts it wrote.
- **Deletion**: `DELETE /api/v1/tenants/{id}` behind `DELETE_TENANT`, refusing with `KMS-4941` when no export was taken for that temple in the last 24 hours; otherwise recording `TENANT_DELETED` on the platform log and running `delete_tenant_cascade`.
- **The screen reflects the rule**: the delete dialog states when the last export was taken, offers the export if there is none recent, and keeps the delete action disabled until both the export exists and the temple's name has been typed exactly.
- **Deleting a temple leaves the platform intact**: the operator who deleted it, and any other temple, are unaffected.

**Acceptance criteria:**
- [ ] A super-admin can open a temple and see what it was provisioned as, plus its public web address.
- [ ] The export downloads as an `.xlsx` named after the temple, with one sheet per tenant-owned table plus the temple's own row.
- [ ] Every sheet has its column names as the header row, an autofilter on that row, and the header frozen.
- [ ] The export contains the temple's rows and no other temple's rows, proven as the unprivileged app role.
- [ ] Taking an export writes `TENANT_EXPORTED` to the platform audit log with per-table row counts.
- [ ] Deleting without a recent export is refused with `KMS-4941`, and nothing is deleted.
- [ ] Deleting after an export erases every tenant-owned row and the temple, and writes `TENANT_DELETED` to the platform audit log *before* the purge.
- [ ] The append-only guard is restored after the purge, and a rollback mid-purge leaves it restored too.
- [ ] A non-super-admin is refused both endpoints (403).
