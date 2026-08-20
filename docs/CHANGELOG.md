# Project Changelog — ISKCON Kitchen Management System

Tracks version history for the project's governing documents. Each locked version has an immutable snapshot in `docs/versions/`. The root-level copy (`REQUIREMENTS.md`, `SYSTEM_DESIGN.md`, etc.) always reflects the current approved version; edit it only alongside a new entry here and a new snapshot.

Per Commandment 8, no document is edited post-lock without the user's explicit sign-off — a lock is a decision, not a formatting convenience.

---

## PROJECT_COMMANDMENTS.md

### v1.1 — 2026-08-09 — Amended (approved by Rajeev)

Commandments 5 and 6 revised to separate a coding story's definition of *done* from user acceptance testing. The original text read Commandment 6 as "UAT every feature before closing its story," which assumed every story is a self-contained, independently demonstrable feature. Foundation work is not: tenant isolation, the audit kernel, background jobs, and observability have no manual surface and are verified by automated tests, while user-facing capabilities routinely span several coding stories (onboarding is E1-S4 + E1-S5 + E1-S6 together). Forcing a one-to-one UAT story onto that shape produces hollow tests and stalls coding stories behind acceptance passes that cannot yet run.

Now: a coding story is done on automated tests + review + design-doc conformance (plus a hand smoke-test where it has a surface); UAT is a separate activity scoped to a demonstrable capability, batched at capability and release boundaries, with a defined story template and two-way traceability between coding stories and the UAT story that covers them. This is the first recorded amendment to the commandments; the original stands as v1.0. (The commandments have not carried `docs/versions/` snapshots as the three core specs do — worth deciding separately whether they should.)

---

## DESIGN_SYSTEM.md

### v1.2 — 2026-08-19 — A fourth semantic colour, and Ekadasi off the accent (requested by Rajeev)

Rajeev, on the calendar: *"Can we use the color we used for Ekadasi for Fasting day and use #edf7fc for Ekadasi instead."*

Two things follow. First, an **`info`** family joins danger, warning and success — `bg #EDF7FC` as he
specified, with `#356780` as the saturated member for dots and text, chosen at the same lightness as
`success #3E6B48` so the four read as one set. Second, Ekadasi moves onto it and the ordinary fasting
day takes the terracotta wash Ekadasi used to have.

Worth recording the tension rather than burying it: this document's own rule is that the terracotta
accent has *one job only* — the primary action on a screen — and Ekadasi has been quietly breaking
that since the calendar was built. Moving Ekadasi off the accent fixes the older violation; moving
the fasting day onto it re-creates a milder one, since what appears on a day cell is the pale wash
(`accent-bg`) rather than the saturated accent, and a pale wash on a calendar square is not going to
be mistaken for a button. Flagged for Rajeev on 2026-08-19; his call, and he made it.

The planner's Ekadashi badge was gold (`warning`) while the calendar's was terracotta — the two
screens had disagreed about the same day since they were built. Both are now `info`.

### v1.1 — 2026-08-10 — Palette revised (approved by Rajeev)

The colour palette changed from the Cocoon-derived olive-on-beige to a terracotta-on-warm-grey scheme, at Rajeev's request. Rationale: the olive greens weren't growing on him, and ISKCON's own saffron-orange identity (per iskconsv.com) is a better fit. The accent is a **softened/desaturated terracotta** (`#BE6444`) so it stays flat and calm rather than loud; text is **warm charcoal** (`#2B2621`); neutrals are a **near-neutral warm-grey** (`raised #FAF8F7`, `sunken #F1EDEB`) rather than the earlier warm cream, so the orange never overwhelms the surfaces. Semantic **warning shifts to gold** (`#8F6A1C`) so it can't be mistaken for the orange accent. Only colour tokens changed — spacing, type, radius, and every structural rule (incl. "one accent, one job") are unchanged. Applied centrally in `tailwind.config.ts`, so all screens re-coloured through tokens; the backend recipe-PDF template and the design-reference page were updated by hand (the only places colours were hardcoded). No `docs/versions/` snapshot: DESIGN_SYSTEM.md was never under that regime, unlike the three core specs.

---

## REQUIREMENTS.md

### v1.1 — 2026-08-20 — Payroll and leave move from Phase 2 into Phase 1 (approved by Rajeev)

A requirement change from the customer, not scope creep. Rajeev, 2026-08-20: *"The temple came back
and wanted it in Phase 1. So had to pivot and include it in."* That statement is the sign-off
Commandment 8 asks for, and this entry is the record of it.

What moved, and what deliberately did not:

- **In**: time off and sick leave, as a request-and-approve log — types time off / sick / unpaid,
  half-days, approved by the temple admin or by a Kitchen Manager where a temple has appointed one,
  back-datable and revocable. Approved leave drops the person out of the schedule grid and the
  workforce count.
- **In**: staff payments for salaried staff — an optional monthly salary, payments by cheque, cash
  or payroll, cash advances, and deductions that recover them. The app records; it does not compute
  what is owed. That line is the whole of the boundary: computing salary owed needs a pay period, a
  start date and a ledger of settled periods, which is payroll, and nobody asked for payroll.
- **In**, arriving with the meal-recording change rather than with payroll: **actual servings**,
  previously listed in the Phase 2 backlog as "waste and actual-vs-planned tracking". Recording a
  meal now captures what actually went out, per dish. Leftovers and waste weight stay in Phase 2.
- **Out, and stated so it is not assumed back in**: **leave-balance accrual** — never asked for, and
  a balance nobody reconciles is a number that misleads. **Attendance** — hourly pay was dropped, and
  hours worked were the only thing that would have required recording attendance, so nothing is left
  for it to serve.

Two consequences worth recording here rather than leaving to be rediscovered. A fifth role,
`KITCHEN_MANAGER`, joins the authorisation policy, because "the kitchen manager approves leave"
would otherwise collide with E6-S8's rule that a job title is a label and gates nothing; the
resolution is the one already on record in BL-4 — more roles, not a second concept beside them. And
the temple gains a currency, used properly by everything built for staff pay, while the existing
rupee-named columns across donations, wish list, invoices and purchase orders stay exactly as they
are: retrofitting them for a temple that does not exist is churn for a guess.

E6-S1's assumption line is amended in step, in both `docs/stories/EPIC-6-workforce-management.md` and
its GitHub-import body.

### v1.0 — 2026-08-03 — LOCKED
Approved by Rajeev. Stage 1 (Requirements & Wireframes) complete.

- All four core modules (Kitchen Management, Ordering System, Workforce Management, Payments & Donations) specified.
- Six Phase 1 gap features added: recipe scaling, sattvic ingredient enforcement, in-kind donation intake, partial/rejected delivery handling, volunteer waitlist with auto-promotion, Vaishnava calendar integration.
- Six Phase 2 backlog items recorded: cost-per-plate analytics, sponsor-a-day/feast, volunteer reliability tracking, vendor scorecards, waste tracking, multilingual UI.
- India regulatory research completed: FSSAI/BHOG (voluntary, deferred to Phase 2) and 80G donation receipting (donor data captured Phase 1, Form 10BD/10BE filing deferred to Phase 2).
- Recipe/purchase-order translation and PDF/print, WhatsApp PO delivery, and India-specific workforce reminder design (WhatsApp-first, per-event timing config, per-user channel preference) added.
- Vaishnava calendar resolved to astronomical computation (post-2006/Hari-bhakti-vilasa schema), not calendar import.
- Snapshot: `docs/versions/REQUIREMENTS_v1.0.md`

---

## SYSTEM_DESIGN.md

### v1.1 — 2026-08-11 — Observability surface split by audience (approved by Rajeev)

§10 "Business observability" revised. The single Super-Admin ops page described at v1.0 mixed platform-operator concerns with one-temple detail. It is now split by audience: the **Super-Admin Operations page** carries platform vitals only — system health, plus platform-wide notifications sent/failed today rendered as a **seven-day pulse of two-hour buckets** (each day split into twelve slots, so *when* sends and failures cluster is visible, not just how many). **Per-temple** detail (a temple's own sent/failed/suppressed breakdown, recent failed sends, last calendar precompute) moves to a proposed **Temple System Health Dashboard** (`docs/stories/BACKLOG.md`, BL-1), deferred out of the pilot.

Rationale: the platform-operator role deliberately holds no temple permissions, so the operator's cross-tenant view is deliberately limited to **aggregate counts that carry no temple business data** — distinct from the audit drill-in, whose per-record before/after would leak donation/payment detail through a side door. A count of sends is a legitimate operator vital sign; a temple's records belong to that temple's admin. The counts are still assembled from properly RLS-scoped per-tenant reads summed in app code, never a BYPASSRLS query. Two-hour buckets are computed in Asia/Kolkata (India-first display timezone).

Minor (v1.x): no reversal of an earlier decision and no re-review of dependent work — the ops page was always "lightweight business observability"; this refines what it shows and to whom. Snapshot: `docs/versions/SYSTEM_DESIGN_v1.1.md`

---

### v1.0 — 2026-08-03 — LOCKED
Approved by Rajeev. Stage 2 (System Design & Architecture) complete.

- Modular monolith architecture (not microservices) — sized for solo-operator pilot scale.
- Multi-tenancy via shared schema + PostgreSQL Row-Level Security.
- Postgres-only data layer (no Redis/broker at pilot scale) doing triple duty as system of record, audit log, and job queue.
- Managed IdP for auth (phone-OTP required), REST API, UPI-first payment integration, WhatsApp BSP integration, translation API — all left as named but unpicked in Stage 3.
- Cost envelope estimated at $80–180/month against a $50–200 budget.
- 8 open items explicitly carried to Stage 3 (Tech Stack).
- Snapshot: `docs/versions/SYSTEM_DESIGN_v1.0.md`

---

## TECH_STACK.md

### v1.0 — 2026-08-03 — LOCKED
Approved by Rajeev. Stage 3 (Technology Stack Selection) complete.

- All 8 open items from SYSTEM_DESIGN.md v1.0 §13 resolved: GCP (`asia-south1`/Mumbai); Spring Boot + Spring Data JPA backend; Next.js (React/TS) frontend; Firebase Authentication; Razorpay; Meta WhatsApp Cloud API direct (not a BSP — cost correction from the design-stage placeholder); Bhashini with Google Cloud Translation as an abstracted fallback; Quartz Scheduler (JDBC store) + Playwright (Java)/Chromium for PDF generation; GitHub + GitHub Actions + GitHub Issues/Projects; JUnit 5 + Mockito + Testcontainers, Vitest + React Testing Library, Playwright for testing.
- Pre-existing ERPNext + Zoho CRM + Power BI proposal formally evaluated and rejected in favor of the custom build (see memory: ERPNext Proposal Superseded).
- Backend revised during review from Django to Java/Spring Boot at Rajeev's request (existing Java expertise). Consequences worked through explicitly: Procrastinate → Quartz Scheduler; WeasyPrint → Playwright (Java)/Chromium for PDF generation, after Apache PDFBox was evaluated and rejected specifically for lacking GPOS support (no vowel-sign positioning for Devanagari); Django's free admin panel has no Java equivalent — resolved by building a small set of Super-Admin/Temple-Admin screens into the existing Next.js app rather than standing up a second frontend stack.
- Revised cost envelope: ~$90–195/month (was ~$85–185 under Django), still inside the $50–200 budget with reduced margin.
- Snapshot: `docs/versions/TECH_STACK_v1.0.md`

---

## docs/stories/

The story set is marked DRAFT (see `docs/stories/README.md`) and refined during implementation as building a story reveals gaps in an adjacent one. Changes are logged here for the record (Commandment 8) but do not carry `docs/versions/` snapshots while in draft.

### 2026-08-09 — EPIC-1, during E1-S7 implementation

**E1-S7 (Audit log framework) — Super-Admin viewing reinterpreted.** Approved by Rajeev. The story previously read "Super-Admin sees platform-level events." With `audit_events` decided as fully tenant-owned (RLS, `tenant_id NOT NULL`, no BYPASSRLS for the app role — consistent with every other tenant table), a Super-Admin cannot read across tenants at all. Reinterpreted to a **drill-in model**: a Super-Admin reads one temple's log at a time by selecting that tenant. Rationale beyond uniformity: a cross-tenant firehose would be a backdoor around E1-S5 RBAC — the Super-Admin role is deliberately denied `VIEW_DONATIONS` and `MANAGE_VENDOR_PAYMENTS`, but audit `before/after` values carry donation amounts and payment records, which a firehose would expose through a side door. Cross-tenant operator visibility lives in Cloud Logging, not the audit table. Added: a Super-Admin's drill-in is itself audited so the capability is not silent. Acceptance criteria updated accordingly.

**E1-S12 (Temple user management) — new story added.** Approved by Rajeev. E1-S7 surfaced that `MANAGE_USERS` exists and E1-S6's UI promises the first administrator can "add everyone else," yet no story built the surface — a temple with one admin and no way to add a cook is unusable. The role-change endpoint seeded in E1-S7 is the starting point; invite-user and disable-user belong in the new story. Epic 1 story count 11 → 12.

### 2026-08-10 — EPIC-1, E1-S8 scope decisions

Approved by Rajeev. E1-S8's "registration/profile" was reinterpreted for this application's reality and its dependencies:

- **"Registration" is not a signup form.** There is no open self-registration — users are created by provisioning (E1-S6) or invites (E1-S12), where email and phone are already collected and validated. E1-S8 therefore delivers the **self-service profile** (view contact details, change preferred channel) plus **communication consent**, not a registration screen. The "cannot register without valid email and phone" criterion is met by the provisioning/invite paths.
- **Consent is the user's own act, soft-gated.** The DPDP consent to be contacted must come from the person, not the super-admin who typed their details — so it is captured on first sign-in / from the profile, and gates *notifications* (E1-S10), never app access.
- **Contact details are read-only in the profile for now** (changing a phone needs re-OTP; changing an email collides with the sign-in identity) — a later increment / admin action.
- **Added `consent_version`** (migration V5) beside `contact_consent_at`, so a bare timestamp can prove *what* wording was agreed to and people can be re-asked when it changes.
- **Deferred to E1-S10:** "preference takes effect on next notification" and the first-notification verification message need the notification service, which does not exist yet. Verified as part of a later notifications capability (see UAT-010).

### 2026-08-10 — EPIC-1, E1-S10 scope decisions

Approved by Rajeev (WhatsApp Business API and Razorpay both unavailable, timelines unknown). The notification service is built in full behind channel **adapters**, so procurement does not block it:

- **All three channels are dev adapters** (log + can be forced to fail) — no real provider is wired, because none is ready. A real Meta WhatsApp / SMS / email adapter is a drop-in replacement for the one class of its channel. Meta's external setup is tracked in `docs/META_WHATSAPP_SETUP.md` (the long pole).
- **`notify()` is asynchronous and consent-gated:** it records the message and enqueues a send job (E1-S9), never sends inline; a user who has not consented (E1-S8) is recorded SUPPRESSED and not sent to. Vendors (raw phone, no account) carry no consent gate.
- **Fallback cascade** preferred → SMS → email, each attempt recorded.
- **Delivery webhook** is signature-verified (HMAC-SHA256) and idempotent (advances status only out of a non-terminal state, so Meta's retries are harmless). It finds a message pre-tenant via a new `app.webhook_message_id` RLS escape, mirroring the `auth_uid` / `claim_contact` escapes.
- **Deferred (external-blocked):** the real provider adapters and the "a test user receives a real WhatsApp message in staging" criterion — verified with the dev adapters and a simulated webhook for now (UAT-052). The **email-first** decision (making email the primary channel to ship without Meta) remains open; the adapter design commits us to nothing until it is made.

### 2026-08-10 — EPIC-1, E1-S11 scope decisions

Approved by Rajeev. Observability split along the two layers §10 already implies:

- **Structured JSON logs** carry `request_id` / `tenant_id` / `user_id` on every line (JSON under the `json` profile for Cloud Logging; readable locally). The `request_id` is **propagated into background jobs**, so a request and the send job it queued share one id across the API/worker boundary.
- **`/health`** now does real DB + scheduler checks (200/503) for the external uptime monitor.
- **Metrics → `/actuator/prometheus`** (job + notification + webhook counters), for Cloud Monitoring to scrape — this is where platform-wide aggregates, trends, and the job-failure-rate alert live.
- **Ops page reinterpreted:** §10's "jobs sent/failed today" as literal in-app platform totals hits two walls — RLS (the Super-Admin holds no BYPASSRLS, so no cross-tenant DB aggregate) and per-instance metrics (job counts live on the worker, not the API). So the in-app ops page is **system health + a per-temple operational drill-in** (consistent with the audit drill-in), and platform-wide aggregates are Cloud Monitoring's job. "Last calendar precompute" is deferred to E4 (no `calendar_days` table yet), shown as "not available yet".
- **Sentry:** backend wired via the Spring starter, inert until `SENTRY_DSN` is set. **Frontend Sentry deferred** to the frontend-integration effort — it is near-valueless on the current static shells (no live errors to catch until the UI is wired to the API), and an npm cache permission fault in this environment blocked a clean install; it will be added with `@sentry/nextjs` when the frontend is connected.
- **Deferred (external / ops setup, not code):** the external uptime monitor + phone/WhatsApp alerting, and Cloud Monitoring dashboards/alerts. The ACs "a test exception appears in Sentry" and "killing the DB fires the external alert" are staging steps against real accounts (UAT-004).

### 2026-08-11 — EPIC-1, E1-S15 written and completed (temple detail, data export, deletion)

Rajeev's decisions, recorded in the story as twelve numbered choices. The view-and-delete halves had
shipped in `ab7e073` **with no story at all** — found by the UAT pack's traceability pass (gap G1) —
so the story was written retrospectively and the one thing it was missing was built with it.

- **Deletion stays unconditional.** Considered and rejected: refusing to delete a temple holding
  completed donations or vendor payments. Rajeev's call — the safeguard is the export, not a guard on
  what the data contains. The trade-off is stated in the story rather than left implicit: an operator
  can destroy donation and audit history that no other code path can touch.
- **Export before delete, enforced by the API.** A temple cannot be deleted unless it was exported in
  the last 24 hours (`KMS-4941`). The `TENANT_EXPORTED` platform-audit event is both the record and
  the check, so what the log says and what the guard allows cannot drift apart.
- **The export is an Excel workbook** — a tab per table, raw rows, column headings, an autofilter and a
  frozen header, named after the temple. Excel rather than CSV or JSON because the likely reader is a
  temple accountant. Tables are discovered by "has a `tenant_id` column", the same rule the purge
  uses, so anything the purge destroys the export contains.
- **Encrypted values stay encrypted** in the file: a platform operator is denied `VIEW_DONATIONS`, and
  an export must not become a side door to plaintext donor PII.

Verified by 21 tests (5 workbook, 4 filename, 6 export integration, 6 deletion integration incl. the
stale-export and wrong-temple cases). Suite: 676 passed / 2 skipped backend, 134 frontend.

---

### 2026-08-10 — EPIC-1, E1-S12 implemented (Epic 1 complete)

Approved by Rajeev. Temple user management, completing Epic 1's foundation:

- **Add a person** — created pending their first sign-in (a `pending:` uid, claimed via E1-S6, own consent via E1-S8); `SUPER_ADMIN` refused (`KMS-4303`); a duplicate email at the same temple refused (`KMS-4902`).
- **Change role** — reuses E1-S7's guarded endpoint, now exposed in the People screen.
- **Disable / re-enable** — a status flip that blocks access on the next request (E1-S4), never a hard delete; you cannot disable your own account (`KMS-4304`).
- All three are audited with before/after and RLS-scoped to the acting admin's temple.

Notes: **"last activity"** in the user list is omitted — nothing records a last-seen time yet (a small future column), so it is left out rather than faked. The **People** and **Audit log** nav entries sit in the shared temple nav; splitting the temple nav by permission needs the frontend wired to the signed-in user's role, so it is deferred to the frontend-integration effort (verify: UAT-008).

### 2026-08-20 — Stories written for the 2026-08-20 build (EPIC-3, 4, 6, 7 and a new EPIC-9)

Written after the work, from `docs/stories/BUILD-BRIEF-2026-08-20.md`, which is the record of the
2026-08-19/20 conversation and the specification the build was made from. The scope change itself is
recorded separately under REQUIREMENTS.md v1.1 above and is not repeated here; this entry is about
the stories.

**Thirteen stories added.** Epic 3: **E3-S8** cost of materials. Epic 4: **E4-S10** per-meal
recording, **E4-S11** the job card, **E4-S12** dish swap-or-edit, **E4-S13** the outside-event
purpose, **E4-S14** the Today rewrite. Epic 6: **E6-S10** leave, **E6-S11** the editable week grid,
**E6-S12** the `KITCHEN_MANAGER` role, **E6-S13** staff pay, **E6-S14** the workforce count. Epic 7:
**E7-S10** the donations period control and year-on-year comparison. Epic 9: **E9-S1** the platform
notice board and **E9-S2** the ban record and the check at hire. The set goes from 8 epics and 70
stories to **9 epics and 84 stories**; `docs/stories/README.md` carries the table, whose per-epic
figures had drifted from the files since v1.0 and are now the real counts.

**EPIC-9 stops being a design.** It carried six open questions, all now answered (build brief §10 and
§11). The questions and their answers are kept in the file rather than deleted, because two of them
were decided against the recommendation and the argument is worth more than the conclusion:
**Q1**, the broadcast naming a person, was **dropped in both forms** — the named version on
defamation and DPDP grounds, the unnamed version on Rajeev's, that a notice nobody can act on is a
rumour with no handle on it, useful to nobody and corrosive anyway; and **Q3** reversed the
recommendation that the subject be shown what was recorded, because disclosure at the moment of
firing invites retaliation, and DPDP's right here is to information on request. The design's E9-S2
and E9-S3 collapse into one story, since a record raised at a dismissal and a check run at a hire
are one act once nothing is broadcast between them.

**Four earlier stories say plainly that they were overturned**, rather than being edited into
agreement. **E6-S1** loses per-date exceptions to E6-S11. **E6-S8**'s D8 — *"salary is not
collected"* — is reversed by E6-S13, and its D9 permission split is recorded as having held exactly
as designed when E6-S12 finally exercised it. **E4-S4** loses per-dish *Mark as cooked*. **E4-S8**'s
tiles are partly superseded by E4-S14. **E3-S6**'s consumption is now drawn against actual servings
rather than planned. A decision that was reversed is more useful on the record than one that appears
never to have been made.

**BL-4 and BL-6 are closed in `docs/stories/BACKLOG.md`**, in a convention the file now states: a
built item keeps its entry and gains a `CLOSED` line saying what closed it and where the work
landed. BL-4 was closed by the `KITCHEN_MANAGER` role — its own recommendation, *more roles, not a
second concept beside them*, taken unchanged. BL-6 was closed by the notice board and the check at
hire, one item short of what it asked for: the broadcast about a person is not built and will not
be.

**Two judgement calls worth naming.** Cost of materials went to Epic 3 rather than Epic 4, because
every argument that shapes it is an inventory argument — the reason it can never become exact is
that donated stock has an estimated value and no purchase price. And staff payments went to Epic 6
rather than Epic 7, because what the temple pays its own cook is a fact about a person it employs,
not about a donor or a vendor; putting it in Epic 7 would have put salary on a screen behind
`VIEW_DONATIONS`, which is what E6-S8's D9 split exists to prevent.

No UAT stories were written with these. The build brief's §14 makes Rajeev's own verification pass
the next step, and the UAT pack is written from what that pass finds.

---

## Build & tooling

Not governing documents, but recorded here because both items were E1-S1 acceptance criteria that had been marked done on CI evidence alone.

### 2026-08-10 — Frontend-integration prep (CORS, Firebase, secrets)

Groundwork before wiring the frontend to the API:

- **CORS** added to the backend, env-driven (`CORS_ALLOWED_ORIGINS`, default `http://localhost:3000`, never a wildcard); exposes `X-Request-Id`; preflight from an unknown origin is refused. Bearer-token auth means no credentials/cookie handling.
- **Firebase Admin project id pinned** — a latent bug: the SDK was initialized with no project id, so it would default to the runtime credentials' GCP project (`iskcon-kms-2026`) while tokens are issued by the separate Firebase project (`iskcon-kms-2026-620ee`), and every real token would be rejected on an audience mismatch. Now set via `FIREBASE_PROJECT_ID` (default `iskcon-kms-2026-620ee`). Surfaced only because Firebase had never been enabled end-to-end.
- **Secrets in GCP Secret Manager:** Razorpay test key + secret added (`kms-staging-razorpay-*`, runtime SA granted access) — the last app secret that had been only local. Created via `gcloud`; to be brought into Terraform (`import`) when E7 consumes them.
- **Firebase runtime access:** the Cloud Run runtime SA granted `roles/firebaseauth.viewer` on the Firebase project (the cross-project step in `docs/DEPLOYMENT.md`), so `checkRevoked` token verification works when deployed.

### 2026-08-09 — Gradle wrapper restored; E1-S1 fresh-clone criterion actually verified

The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) was missing from the repository — only `gradle-wrapper.properties` had been committed. So `cd backend && ./gradlew test`, the command in CLAUDE.md, could not work in a fresh clone. It went unnoticed because CI runs `gradle build` via `gradle/actions/setup-gradle`, not `./gradlew`, and so never exercised the wrapper. E1-S1's criterion — "`./gradlew test` passes on a fresh clone" — had therefore never been verified; it was green on CI, which took a different path. Wrapper regenerated at Gradle 8.10 and the criterion verified for real by cloning the repo and running `./gradlew test` in the clone.

### 2026-08-09 — Testcontainers 1.20.1 → 1.21.4 (Docker Engine version drift)

The integration suite passed on CI but failed on a developer machine for the same commit. Cause: Testcontainers drives Docker through docker-java, which negotiates the Docker Engine API version at runtime; 1.20.1 defaulted to API 1.32, which Docker Engine 29 (minimum 1.40) refuses, while CI's older runner engine still accepted it. Rather than pin the API version by environment variable — which hides the skew until the next person hits it — Testcontainers was moved to a current 1.x that negotiates correctly across the engine versions in play (local 29, CI's `ubuntu-latest`). A `docker version` step was added to the CI backend job so the runner's engine version is always visible in the log, making any future drift diagnosable rather than mysterious.

---

## Versioning convention

- Version bumps to a **locked** document require the user's explicit approval, per the Ten Commandments (never silently edit an approved decision).
- **Patch-level** (v1.0 → v1.1): typo/clarity fixes with no requirement or design change.
- **Minor** (v1.0 → v1.1... or v1.x → v1.y): scoped additions or clarifications that don't invalidate prior decisions.
- **Major** (v1.x → v2.0): a decision reversal, scope change, or anything that would require re-reviewing earlier-dependent work (e.g. Stage 3 tech choices made against SYSTEM_DESIGN v1.0).
- Every bump gets a snapshot in `docs/versions/` and an entry here.
