# EPIC 1 — Platform Foundation

**Goal:** Everything every other epic stands on: repo/CI, cloud infrastructure, multi-tenancy with RLS, authentication, RBAC, audit logging, background jobs, notifications, and observability.
**Depends on:** nothing. **Blocks:** all other epics.
**Labels:** `epic:foundation`

---

## E1-S1 — Project scaffolding and CI pipeline

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

**As a** solo operator, **I want** errors, logs, metrics, uptime, and job health visible in minutes per day, **so that** silent failure — the likeliest incident at this scale — can't hide.

**Assumptions:** Sentry (free tier) + Cloud Logging/Monitoring + external uptime ping, per SYSTEM_DESIGN.md §10.

**Requirements:**
- Structured JSON logs everywhere with `tenant_id`, `user_id`, `request_id`.
- Sentry wired into backend and frontend; unhandled exceptions alert.
- `/health` checks DB + Quartz scheduler liveness; external monitor pings it with phone/WhatsApp alerting.
- Admin ops page (Super-Admin): last calendar precompute per tenant, jobs sent/failed today, recent webhook failures — per SYSTEM_DESIGN.md §10 "business observability."

**Acceptance criteria:**
- [ ] A thrown test exception appears in Sentry with request context within a minute.
- [ ] Killing the DB in staging turns `/health` unhealthy and triggers the external alert.
- [ ] Ops page reflects a deliberately failed job within one refresh.
- [ ] Log lines for one request share a `request_id` across API and worker boundaries (traceable in Cloud Logging).

---

## E1-S12 — Temple user management

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
