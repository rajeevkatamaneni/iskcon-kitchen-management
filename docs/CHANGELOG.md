# Project Changelog — ISKCON Kitchen Management System

Tracks version history for the project's governing documents. Each locked version has an immutable snapshot in `docs/versions/`. The root-level copy (`REQUIREMENTS.md`, `SYSTEM_DESIGN.md`, etc.) always reflects the current approved version; edit it only alongside a new entry here and a new snapshot.

Per Commandment 8, no document is edited post-lock without the user's explicit sign-off — a lock is a decision, not a formatting convenience.

---

## REQUIREMENTS.md

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

## Versioning convention

- Version bumps to a **locked** document require the user's explicit approval, per the Ten Commandments (never silently edit an approved decision).
- **Patch-level** (v1.0 → v1.1): typo/clarity fixes with no requirement or design change.
- **Minor** (v1.0 → v1.1... or v1.x → v1.y): scoped additions or clarifications that don't invalidate prior decisions.
- **Major** (v1.x → v2.0): a decision reversal, scope change, or anything that would require re-reviewing earlier-dependent work (e.g. Stage 3 tech choices made against SYSTEM_DESIGN v1.0).
- Every bump gets a snapshot in `docs/versions/` and an entry here.
