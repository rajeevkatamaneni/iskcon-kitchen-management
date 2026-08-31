# ISKCON Kitchen Management System — System Design & Architecture

**Status:** LOCKED — v1.2, approved 2026-08-30. Stage 2 complete.
**Version:** 1.2
**Approved:** 2026-08-03 by Rajeev (v1.1 §10 revision approved 2026-08-11)
**Last updated:** 2026-08-30 (§5 gains kitchens and ingredient requests — see CHANGELOG.md)
**Inputs:** REQUIREMENTS.md v1.0 (locked), design context: 1–5 temples in year 1 (pilot), solo builder + AI assistance, $50–200/month cloud budget, 99.9% availability target, India-first (data and users in India).

> This is a living reference document tracking the current approved version. Point-in-time snapshots of each locked version are kept in `docs/versions/`. Material changes after lock require a version bump and a note in `docs/CHANGELOG.md` — see Commandment 8: a change here is a requirement change, not a typo fix, and should be discussed before being silently edited.

---

## 1. Design Principles

1. **Right-sized for the pilot, ready for growth.** Architecture decisions must serve 1–5 tenants today without blocking 100+ later. We buy scale headroom through clean boundaries, not premature infrastructure.
2. **Operable by one person.** Every component must be managed-service or near-zero-ops. No self-hosted Kafka, no Kubernetes, no fleet of microservices.
3. **Tenant isolation is a security property, not a feature.** Isolation is enforced in the database layer, not just application code.
4. **India-resident data.** All primary data stores and compute in an India region.

---

## 2. High-Level Architecture

**Decision: modular monolith.** One deployable backend application, internally organized into modules that mirror the four product domains plus shared kernel. Not microservices.

*Why:* a solo builder cannot operate a distributed system; at pilot scale there is no load justification; a well-modularized monolith extracts into services later along module boundaries if ever needed. Trade-off accepted: a single bad deploy affects all modules — mitigated by CI, staged deploys, and fast rollback.

```
                        ┌──────────────────────────────┐
                        │   Users (web browsers)       │
                        │   admin/staff/volunteer/donor│
                        └──────────────┬───────────────┘
                                       │ HTTPS
                        ┌──────────────▼───────────────┐
                        │   CDN + WAF (static assets,  │
                        │   TLS, rate limiting)        │
                        └──────────────┬───────────────┘
                                       │
        ┌──────────────────────────────▼──────────────────────────────┐
        │                 Application (modular monolith)              │
        │                                                             │
        │  ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌──────────────┐   │
        │  │ Kitchen  │ │ Ordering │ │ Workforce │ │ Payments &   │   │
        │  │ module   │ │ module   │ │ module    │ │ Donations    │   │
        │  └──────────┘ └──────────┘ └───────────┘ └──────────────┘   │
        │  ┌───────────────────────────────────────────────────────┐  │
        │  │ Shared kernel: auth/tenancy, audit, notifications,    │  │
        │  │ calendar engine, translation, PDF generation          │  │
        │  └───────────────────────────────────────────────────────┘  │
        │  ┌───────────────────────────────────────────────────────┐  │
        │  │ Background workers (same codebase, separate process): │  │
        │  │ reminders, WhatsApp sends, PDF/translation jobs,      │  │
        │  │ calendar precompute, order-list generation            │  │
        │  └───────────────────────────────────────────────────────┘  │
        └───────┬───────────────┬──────────────────┬─────────────────┘
                │               │                  │
     ┌──────────▼─────┐ ┌───────▼────────┐ ┌───────▼────────────────┐
     │ PostgreSQL     │ │ Object storage │ │ External integrations  │
     │ (managed,      │ │ (PDFs, images, │ │ • Payment gateway (UPI)│
     │  multi-AZ, RLS)│ │  invoices)     │ │ • WhatsApp BSP         │
     └────────────────┘ └────────────────┘ │ • Translation API      │
                                           │ • Email/SMS provider   │
                                           └────────────────────────┘
```

The frontend is a separate single-page/server-rendered web app served via CDN, calling the backend REST API. (Framework choice is Stage 3.)

---

## 3. Multi-Tenancy Model

**Decision: shared database, shared schema, `tenant_id` column on every tenant-owned table, enforced by PostgreSQL Row-Level Security (RLS).**

| Option | Verdict | Reason |
|---|---|---|
| Database-per-tenant | Rejected for pilot | Operational cost ×N, migrations ×N — hostile to a solo operator; overkill at 1–5 tenants |
| Schema-per-tenant | Rejected | Same migration burden, tooling friction; middle ground with the drawbacks of both ends |
| Shared schema + RLS | **Chosen** | One database to operate; isolation enforced by the DB engine itself, not just app code; scales comfortably to hundreds of tenants |

Mechanics:

- Every request resolves the authenticated user → tenant, and sets `SET LOCAL app.tenant_id` on the connection's transaction. RLS policies on every tenant-owned table filter rows to that tenant. Application bugs cannot leak cross-tenant data because the database refuses to return it.
- The application connects as a role **without** `BYPASSRLS`. A separate migration/admin role exists for schema changes only.
- Platform super-admin operations (tenant provisioning) run through a dedicated, audited code path.
- Tenant provisioning captures: temple name, address, **coordinates + timezone** (required by the calendar engine per REQUIREMENTS.md), currency (default INR), locale, 80G approval status.

*Escape hatch:* if a future large temple demands stronger isolation, RLS-on-shared-schema can be selectively promoted to a dedicated database for that tenant; module boundaries and repository-layer data access keep this feasible.

---

## 4. Identity & Access Management

- **Authentication: managed identity provider** (concrete pick in Stage 3; requirements below). Solo-operated systems should never hand-roll password storage, session hardening, or OTP delivery.
  - Must support: email+password, phone OTP (volunteers/donors in India often have no email habit), optional Google sign-in.
  - Must allow India-region data or at minimum store only identifiers, not PII beyond login credentials.
- **Authorization: application-layer RBAC** with roles from REQUIREMENTS.md — Platform Super-Admin, Temple Admin, Kitchen Staff/Manager, Volunteer. Donors are registered users; the guest donor was withdrawn on 2026-08-29.
  - A user belongs to exactly one tenant (except super-admin). Role checks happen in a single middleware layer; module code declares required permissions, never re-implements checks.
  - JWT/session carries `user_id`, `tenant_id`, `role`. `tenant_id` in the token is the *only* source for the RLS variable — never a request parameter.
- **There are no public surfaces — reversed 2026-08-29.** Wish list and donation pages were unauthenticated and tenant-scoped by URL slug (`/t/{temple-slug}/donate`) until that date, when Rajeev withdrew unauthenticated giving: a temple asks a supporter to register and give from inside the application, and the product publishes no web address for a temple, because temples have their own websites. Every request now carries a verified token, and `tenant_id` comes from it as it does everywhere else. Guest and anonymous online donor records went with it; every online donation carries a name. Anonymity survives only in office in-kind intake, where a staff member records a gift somebody brought in person.

---

## 5. Data Architecture

- **PostgreSQL (managed, single primary, multi-AZ standby)** — system of record for everything: relational domain data, audit log, and background job queue (below). One database engine keeps the operational surface minimal.
- **Object storage (S3-compatible)** — generated PDFs (recipes, purchase orders), uploaded invoice scans, temple logos. Private buckets; access via short-lived signed URLs.
- **No Redis / no separate cache at pilot scale.** Postgres comfortably serves this load. Caching is added only when measurements demand it (see §9). Sessions live in the IdP token, not server memory.
- **Background jobs: Postgres-backed queue** (e.g. SKIP LOCKED pattern via an established library — Stage 3 pick). Used for: scheduled shift reminders, one-off broadcasts, WhatsApp/SMS/email sends, PDF generation, translation calls, nightly calendar precompute, auto order-list generation.
  - *Why not SQS/Rabbit:* one fewer system; at pilot volume (hundreds of jobs/day) Postgres is beyond sufficient; the worker process is the same codebase deployed with a different entrypoint.
- **Audit log:** append-only `audit_events` table (actor, tenant, action, entity, before/after, timestamp, reason). Written by the shared kernel for: financial records, inventory adjustments, sattvic overrides, calendar date overrides, role changes. No deletes; retention indefinite at pilot scale.
- **Backups & DR:** automated daily snapshots + WAL-based point-in-time recovery (PITR). RPO ≤ 5 minutes, RTO ≤ 4 hours (restore-from-backup runbook; acceptable within 99.9%). Backup restores tested quarterly.
- **Key entity groups** (full ERD in Stage 4 stories): tenants/users/roles; ingredients/recipes/recipe_items; kitchens (the several a temple runs, flat under the tenant — v1.2); inventory_items/stock_movements (every stock change is a movement row — receipts, consumption, in-kind donations, adjustments, and ingredients issued to another of the temple's kitchens — giving audit-friendly inventory by construction); ingredient_requests/lines/dishes (a kitchen asking the store, and what it was given — v1.2); meal_plans/planned_meals; vendors/purchase_orders/po_lines/receipts/invoices; shifts/signups/waitlist_entries/reminder_configs; donations/donors/wishlist_items/recurring_plans; calendar_days (precomputed per tenant-year, with override rows).

---

## 6. API & Integration Design

- **REST JSON API**, versioned under `/api/v1`. Resource-oriented, predictable; no GraphQL (no client diversity to justify it).
- Standard envelope for errors; idempotency keys on mutation endpoints that money or stock depend on (donations, PO receipt) so retries never double-post.
- **Payment gateway (UPI-first):** integration via provider-hosted checkout (never touch raw card/UPI credentials — keeps us out of PCI scope). Webhooks with signature verification + idempotent processing record payment events. Recurring donations use the gateway's mandate/subscription primitives (UPI Autopay/e-mandate). Provider pick is Stage 3.
- **WhatsApp Business API via a BSP** (Business Solution Provider — Stage 3 pick): template messages for shift reminders and PO delivery (PDF attached or linked). Fallback cascade per user preference: WhatsApp → SMS → email. Delivery status webhooks recorded per message.
- **Translation API** (Bhashini or commercial — Stage 3): called from background jobs, results cached per (document, language) in Postgres so a recipe is translated once, not per download.
- **Calendar engine:** in-process library (MIT-licensed `gaurabda-calendar` as reference/base), run as a nightly job that precomputes `calendar_days` per tenant for the coming 18 months; the planner reads rows, never computes live. Admin overrides write override rows on top.

---

## 7. Security

- TLS everywhere; HSTS. CDN/WAF layer gives basic DDoS and bot protection plus rate limiting on the endpoints anyone can reach before signing in — sign-in, self-registration and the payment webhooks.
- OWASP baseline: parameterized queries only, output encoding, CSRF protection on session flows, strict CORS, security headers, dependency scanning in CI.
- Secrets in the platform's managed secret store; never in code or env files committed anywhere.
- PII minimization: donor PAN stored encrypted at column level (app-layer encryption); access restricted to Temple Admin role and logged to audit.
- Webhook endpoints verify provider signatures; all third-party callbacks are idempotent.
- Least privilege: app DB role has no DDL, no BYPASSRLS; object storage locked to the app's identity; per-environment credentials.
- India's **DPDP Act** (Digital Personal Data Protection) applies as baseline personal-data hygiene: consent at collection (volunteer contact info, donor details), purpose limitation, deletion on request — folded into Stage 4 stories rather than a separate compliance project.

---

## 8. Availability & Environments

- Target **99.9%** (≈ 43 min/month error budget). Achieved with: multi-AZ managed Postgres (automatic failover), at least 2 stateless app instances behind the platform load balancer, health checks + auto-restart, CDN-served frontend (static assets survive backend outages).
- **Festival days are peak days** — see §9 for the load argument; availability posture is unchanged but a pre-festival checklist (deploy freeze, backup verification) becomes an operational runbook item.
- **Environments:** `dev` (local), `staging` (small, same topology, seeded synthetic tenant), `production`. Staging exists primarily to rehearse migrations against realistic data before prod.
- **Deploys:** CI runs tests → build → deploy to staging → one-click promote to prod with instant rollback to previous image. Database migrations are backward-compatible (expand/contract pattern) so rollback never fights the schema.

---

## 9. Performance & Scale Estimate

Honest load math for the pilot:

- 5 temples × (~10 staff/admin + ~200 active volunteers + donor traffic) → **low thousands of monthly active users**, peak concurrent users in the low hundreds on a festival day.
- API load: well under 50 req/s even at festival peak. A single modest app instance handles this; we run 2 for availability, not capacity.
- Heaviest operations are all **background jobs** (PDF render, translation, reminder fan-out, order-list generation) — precisely why they're off the request path.
- Frontend performance matters more than backend at this scale, given non-technical users on mid-range Android phones over variable networks: budget <200KB initial JS, server-side rendering where it earns its keep, aggressive CDN caching. The giving and wish-list screens are the most performance-sensitive of them, being the ones a devotee reaches for once, in a moment of goodwill.
- **Scale path (when >50 tenants):** add read replica → add Redis for hot reads → extract worker fleet → only then consider service extraction. Each step is triggered by measurement, not calendar.

---

## 10. Observability

All solo-operator friendly, free-tier-first:

- **Error tracking:** hosted error tracker (e.g. Sentry free tier) wired into backend and frontend; every unhandled exception alerts.
- **Structured logs:** JSON logs with `tenant_id`, `user_id`, `request_id` on every line; shipped to the platform's log service; 30-day retention.
- **Metrics & dashboards:** platform-native metrics (CPU, memory, DB connections, p95 latency, queue depth, job failure rate). One dashboard, six panels — reviewable in two minutes daily.
- **Uptime monitoring:** external ping monitor on `/health` (checks DB + queue reachability) with phone/WhatsApp alert.
- **Business observability:** two lightweight views, split by audience. The **Super-Admin Operations page** shows platform vitals — system health, plus platform-wide notifications sent/failed today as a seven-day pulse of two-hour buckets — because at pilot scale *silent* job failure is the likeliest incident, and *when* sends cluster is what an operator debugs. **Per-temple** operational detail (a temple's own sent/failed/suppressed breakdown, recent failed sends, last calendar precompute) belongs to that temple's admin on a Temple System Health Dashboard (`docs/stories/BACKLOG.md`, BL-1), not the platform operator — keeping the operator's cross-tenant view to aggregate counts that carry no temple business data. *(v1.1 — see CHANGELOG.md.)*

---

## 11. Cloud & Cost Envelope

Cloud **provider selection is Stage 3** per the Commandments; the architecture requires only: an India region (data residency + latency), managed Postgres with multi-AZ, a container-or-PaaS app runtime, S3-compatible storage, CDN, and a secret store. AWS (Mumbai/Hyderabad), Azure (Central India), and GCP (Mumbai/Delhi) all satisfy this; so do leaner PaaS options (e.g. DigitalOcean Bangalore) with trade-offs to weigh in Stage 3.

Indicative monthly envelope at pilot scale (provider-neutral estimates):

| Component | Est. USD/mo |
|---|---|
| App runtime (2 small instances + worker) | 30–60 |
| Managed Postgres, multi-AZ, ~2 vCPU | 40–80 |
| Object storage + egress | 3–8 |
| CDN + WAF | 0–15 |
| Error tracking / uptime monitor | 0 (free tiers) |
| Email/SMS provider | 5–15 |
| WhatsApp BSP conversation fees | usage-based; ~$0.004–0.01/message in India — tens of USD at pilot volume |
| **Total** | **~$80–180/mo** — inside the $50–200 budget |

Cost controls: no idle-expensive services (no NAT-heavy private topology at pilot; use the platform's egress-included runtime), storage lifecycle rules on generated PDFs, budget alarm at $150.

---

## 12. Key Trade-offs (summary)

| Decision | Chosen | Rejected | Cost of choice | Revisit when |
|---|---|---|---|---|
| App topology | Modular monolith | Microservices | One deploy unit; blast radius | >5 devs or clear module scaling asymmetry |
| Tenancy | Shared schema + RLS | DB/schema-per-tenant | Careful RLS discipline required | A tenant demands hard isolation |
| Queue | Postgres-backed | SQS/Rabbit/Redis | Queue competes with OLTP under extreme load | Sustained >100 jobs/sec |
| Cache | None | Redis | Some repeated reads hit DB | Measured p95 regression |
| Auth | Managed IdP | Self-built | Vendor dependency, some cost | Never willingly |
| API | REST | GraphQL | Some over-fetching | Multiple divergent clients |
| Availability | Multi-AZ, single region | Multi-region | Region-wide outage = downtime | International expansion |

---

## 13. Open Items Carried to Stage 3 (Tech Stack)

1. Cloud provider + region (AWS vs Azure vs GCP vs lean PaaS; Mumbai vs Hyderabad vs Bangalore).
2. Backend language/framework; frontend framework; component library.
3. Managed IdP pick (phone-OTP support in India is the differentiator).
4. Payment gateway (UPI Autopay/e-mandate support, wish-list checkout, fees).
5. WhatsApp BSP (Gupshup vs Interakt vs Twilio vs Meta direct).
6. Translation provider (Bhashini vs Google vs Azure) + culinary-term glossary strategy.
7. Postgres job-queue library; PDF generation library (Indic script rendering quality is the hard requirement — Devanagari/Telugu/Tamil shaping in PDFs is a known pitfall).
8. SCM, CI/CD, issue tracker, testing frameworks.
