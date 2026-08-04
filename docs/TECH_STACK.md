# ISKCON Kitchen Management System — Technology Stack

**Status:** LOCKED — v1.0, approved 2026-08-03. Stage 3 complete.
**Version:** 1.0
**Approved:** 2026-08-03 by Rajeev
**Last updated:** 2026-08-03
**Inputs:** REQUIREMENTS.md v1.0 (locked), SYSTEM_DESIGN.md v1.0 (locked). This document resolves the 8 open items carried from SYSTEM_DESIGN.md §13, plus SCM/CI/issue-tracker/testing per Commandment 3. Every pick is justified against the same constraints throughout: solo builder + AI assistance, $50–200/month, 99.9% availability, India-first.

> This is a living reference document tracking the current approved version. Point-in-time snapshots of each locked version are kept in `docs/versions/`. Material changes after lock require a version bump and a note in `docs/CHANGELOG.md`. Note: this v1.0 already incorporates one substantive revision (Django → Spring Boot/Java, and the PDFBox evaluation) made during the review-before-lock conversation — that history is preserved in this file's §2 rather than needing a separate v1.1, since the document was still in DRAFT/pending-approval status when the change was made.

---

## 1. Cloud Provider — GCP (Mumbai, `asia-south1`)

| Option | Verdict |
|---|---|
| **GCP** | **Chosen.** ~15% cheaper compute than AWS for equivalent VMs, no cross-AZ data transfer surcharge (AWS charges $0.01/GB — meaningful for a chatty app+DB pair), simpler pricing, an always-free tier that never expires. Mumbai (`asia-south1`) and Delhi regions available. |
| AWS (Mumbai/Hyderabad) | Rejected — not wrong, just costlier for this profile. Larger ecosystem and consultant pool matter for a big team; less relevant for a solo builder using AI assistance, since Claude's knowledge of AWS and GCP is comparably deep. |
| Azure (Central India) | Rejected — no specific advantage for this stack; India presence is fine but doesn't beat GCP on cost or simplicity here. |
| Lean PaaS (Render/Railway/Fly.io) | Considered, rejected for the primary database. Fly.io has a Mumbai region and is worth it for very early prototyping, but Cloud SQL's managed-Postgres maturity (automated multi-AZ failover, mature backup/PITR tooling) is worth the small extra complexity for a system holding donor financial data. |

**Services selected, all within one GCP project (single IAM model, single bill — deliberately minimizing dashboards for a solo operator):**

- **Cloud Run** — hosts both the Django backend (API + worker process) and the Next.js frontend as separate services. Scales to zero on true idle, `min-instances=1–2` keeps it warm for availability and avoids cold-start latency for users. Pay-per-use fits a bursty, pilot-scale traffic pattern far better than always-on VMs.
- **Cloud SQL for PostgreSQL** — regional (multi-zone) HA, automated backups + PITR.
- **Cloud Storage (GCS)** — generated PDFs, uploaded invoice scans, temple logos. Private buckets, signed URLs.
- **Secret Manager** — credentials, API keys.
- **Cloud Logging / Cloud Monitoring** — structured logs and metrics, built in, cheap at this volume.
- **Cloudflare (free tier)**, not Cloud Armor, sits in front of Cloud Run for CDN, WAF, rate limiting, and TLS/DNS. Free, excellent India PoPs, and consolidates DNS+CDN+WAF into one dashboard instead of paying for and configuring Cloud Armor separately.

---

## 2. Backend — Java, Spring Boot + Spring Data JPA

**Revised 2026-08-03** — switched from Django to Java/Spring Boot at Rajeev's request, on the strength of his existing Java backend experience. Solo-builder velocity from working in a language you're already fluent in outweighs the framework conveniences below in most cases, so this is a sound trade, made with eyes open about what it costs.

| Option | Verdict |
|---|---|
| **Spring Boot + Spring Data JPA (Hibernate)** | **Chosen.** Mature, enterprise-grade ORM and Postgres support; Spring Security is an equally (arguably more) mature RBAC/auth framework than Django's; huge ecosystem and extremely well documented, so AI-assisted development is just as effective as it would have been in Django. |
| Django + DRF | No longer chosen — see the honest trade-offs below rather than a rejection table, since nothing here was *wrong*, it's a preference call. |

**What carries over unchanged:** GCP/Cloud Run, Cloud SQL for PostgreSQL, Cloud Storage, Firebase Auth, Razorpay, Bhashini, Next.js frontend, Cloudflare, GitHub — none of this depended on the backend language. Spring Boot deploys to Cloud Run as a container exactly as Django would have.

**Row-Level Security integration:** same approach, different mechanism — a Spring `Filter`/interceptor resolves `tenant_id` from the authenticated request and issues `SET LOCAL app.tenant_id` at the start of each transaction, via a Hibernate `ConnectionProvider` hook or a thin JDBC interceptor. Equally well-documented pattern in the Spring/Postgres RLS world; no loss here.

**Background job queue — reconsidered.** Procrastinate was Python-only. Two real Java options, no perfect match:
- **Quartz Scheduler with a JDBC job store on Postgres** — decades-mature, the default answer in the Java world for persistent scheduled/background work, extremely well documented. Slightly heavier configuration than Procrastinate was.
- **db-scheduler** — a lighter, more modern Postgres-native (`SKIP LOCKED`-based) Java library, closer in spirit to Procrastinate's simplicity. Actively maintained per prior knowledge, though I couldn't independently re-verify its current release cadence in this research pass — confirm on its GitHub repo before committing.
- **Recommendation: start with Quartz + JDBC store.** It's the safer, unambiguously well-supported choice for a solo builder; db-scheduler is worth a spike if Quartz's configuration overhead proves annoying in practice.

**PDF generation — Playwright (Java bindings) driving headless Chromium.** WeasyPrint was Python-only. Three Java-native options were considered:

| Option | Verdict |
|---|---|
| iText (with `pdfCalligraph` add-on) | Rejected — the add-on is the only way iText shapes Indic scripts correctly, and it's a commercial license, a recurring cost this budget would rather avoid. |
| **Apache PDFBox** | **Checked specifically at Rajeev's request — rejected.** PDFBox only added any Devanagari/Gujarati/Bengali shaping support in version 3.0.2, and the gap that matters most is real: PDFBox implements GSUB (glyph substitution — conjunct formation) but has **no GPOS support at all** (glyph positioning — this is what correctly places and reorders vowel signs/matras in Devanagari and related scripts). Without GPOS, Devanagari text is at real risk of rendering with vowel signs in the wrong position, which is a visible, embarrassing failure mode for a temple-facing document, not a cosmetic one. There's also a documented font-disambiguation bug (PDFBox can pick the wrong script's font when a font like Mangal claims support for more than one language). This isn't a maturity gap that time alone fixes soon — it's a missing rendering subsystem. |
| **Playwright (Java bindings) + headless Chromium** | **Chosen.** Confirmed real and documented, including a Spring Boot + Thymeleaf pattern found in research. Chromium ships a complete, production-grade text shaping engine (the same one that renders Devanagari correctly in every Chrome browser in India today) — GSUB and GPOS both fully implemented, because this is Chromium's actual day job. The script-shaping work happens inside Chromium itself, not in the Java or Python binding layer, so rendering quality is identical to what SYSTEM_DESIGN.md already validated for the Python path. |

**Trade-off to accept knowingly:** Chromium is heavier than a native PDF library would have been — the Cloud Run image now bundles a full browser binary. Budget somewhat more container memory/cold-start time for the worker service; the Noto font-bundling requirement from the original research is unchanged and still mandatory.

**The one real, un-replaceable loss: Django's free admin panel.** There's no clean Java equivalent — JHipster generates scaffolding rather than Django's always-on introspective admin, and hand-building an admin UI in Spring (e.g. Thymeleaf screens) means a second frontend stack alongside Next.js. **Recommendation: don't replicate it — build the handful of internal admin screens (tenant provisioning, master data, audit-log viewer) as Super-Admin/Temple-Admin-gated routes inside the same Next.js app**, calling the same REST API everything else uses. This is more upfront work than Django gave away for free, but it's bounded (a handful of screens, not a product surface), and it's arguably a cleaner architecture than maintaining two frontends — one fewer moving part for a solo operator to context-switch between.

---

## 3. Frontend — Next.js (React + TypeScript)

| Option | Verdict |
|---|---|
| **Next.js** | **Chosen.** SYSTEM_DESIGN.md §9 already committed to "server-side or static rendering for public donation pages" as a performance requirement, given non-technical users on mid-range Android phones over variable networks. Next.js delivers that directly (SSR/SSG for public wish-list/donation pages) while using the same React codebase for the authenticated app views. |
| Plain React (Vite SPA) | Rejected — would require bolting on separate SSR tooling to hit the public-page performance budget already committed to. |

- **Styling:** Tailwind CSS, paired with Radix UI primitives (accessible, unstyled) for interactive components — gives the "very clean, minimal color, color-directs-attention" design mandate fine-grained control without a heavy design-system dependency.
- **Hosting:** containerized on Cloud Run alongside the backend, per §1 — one provider, one bill, rather than splitting the frontend onto Vercel.
- Budget discipline from SYSTEM_DESIGN.md §9 carries forward as a hard constraint: <200KB initial JS, aggressive CDN caching via Cloudflare.

---

## 4. Identity & Access — Firebase Authentication

| Option | Verdict |
|---|---|
| **Firebase Auth** | **Chosen.** Free up to 50K MAU — comfortably covers pilot scale at zero cost beyond SMS. Documented India phone-OTP delivery reliability (99%+ via Google's SMS infrastructure) with a real-world India case study showing dramatically lower cost than Auth0 at scale ($2K/mo vs $14K/mo at 8M users). Phone-OTP was flagged in SYSTEM_DESIGN.md §4 as the differentiator for volunteers/donors who live on phone numbers, not email — this is exactly what Firebase Auth is strongest at in India. |
| Auth0 | Rejected — meaningfully more expensive ($0.07/MAU vs effectively free at our scale), no particular India advantage. |
| Clerk | Rejected — good product, but positioned for MAU-cost efficiency at scale we won't reach for years; no India-specific edge over Firebase. |
| Supabase Auth | Considered — cheapest per-MAU after free tier, but bundling a database we're not using (we already picked Cloud SQL) adds nothing; no documented India OTP track record to match Firebase's. |

Firebase Auth issues the JWT; the token carries `user_id`, `tenant_id`, `role` per SYSTEM_DESIGN.md §4 — it does not touch application data, so this pick doesn't entangle the rest of the stack with Google beyond auth and (coincidentally) the cloud provider itself.

---

## 5. Payment Gateway — Razorpay

| Option | Verdict |
|---|---|
| **Razorpay** | **Chosen.** Full support for UPI Autopay and e-mandate (eNACH) — both needed for donor-chosen-frequency recurring donations per REQUIREMENTS.md. Described as "the most complete option for recurring billing" among Indian gateways researched. Standard pricing: 2% + GST on domestic methods, 0.9% over the platform fee specifically for subscriptions. Extremely well documented — a real advantage for AI-assisted solo development — and widely adopted among Indian nonprofits, which matters for donor trust and for finding prior art on donation-specific integration patterns. |
| Cashfree | Rejected, narrowly — lower published transaction fee (1.75%) and the stronger payouts product, but Razorpay's recurring-billing tooling is the more complete match for the specific requirement (donor-chosen frequency, UPI Autopay + eNACH both needed). Worth re-quoting both at implementation time since pricing shifts. |
| PayU | Not selected — no specific advantage surfaced in research over Razorpay for this use case. |

Never touch raw card/UPI credentials — provider-hosted checkout keeps the system out of PCI scope, per SYSTEM_DESIGN.md §6.

---

## 6. WhatsApp Integration — Meta Cloud API direct, not a BSP subscription

**This is a course correction on SYSTEM_DESIGN.md §11's cost estimate, and worth flagging plainly.**

Research surfaced BSP pricing meaningfully higher than the design doc's placeholder ($0.004–0.01/message): Interakt ₹8,000–30,000/month, Gupshup ₹10,000–50,000/month, Twilio ₹16,000–82,000/month — these are largely marketing-platform subscription tiers, not pure transactional per-message pricing, and at the low end alone they'd consume roughly a third to all of the entire monthly infra budget on WhatsApp before any hosting cost.

Our actual need is narrow: transactional utility-category messages (shift reminders, PO delivery), not marketing broadcast. Meta's own direct per-message rate for utility messages is ~₹0.115 and authentication ~₹0.135 — no platform subscription fee at all, since we're not using a BSP's dashboard/campaign tooling.

| Option | Verdict |
|---|---|
| **Meta Cloud API, direct integration** | **Chosen.** No monthly platform fee — pay only Meta's per-message rate (~₹0.115–0.135 for our utility/auth-category use). Requires Meta Business verification and a registered WhatsApp number, which is more setup work than a BSP's guided onboarding, but it's a one-time cost, not a recurring one, and well-documented for solo developers. |
| BSP (Interakt / Gupshup / Twilio) | Rejected as primary, at least for release 1 — the monthly subscription tiers are built for marketing use cases we don't have and would strain the budget. Worth revisiting if operational complexity of direct integration proves higher than expected, or if a BSP's low-commitment pay-as-you-go tier (e.g. AiSensy, flagged in research as "best overall" but not price-verified here) turns out cheaper than assumed — confirm actual pay-as-you-go pricing before implementation. |

**Action item:** validate this at implementation time before building against it — BSP marketing pages are not reliable pricing sources, and Meta's own direct rates should be confirmed on Meta's official developer pricing page.

---

## 7. Translation — Bhashini (Government of India)

**Confirmed as primary**, per the direction already flagged in SYSTEM_DESIGN.md §6. Bhashini is India's national AI translation platform (Ministry of Electronics & IT), covers all 22 constitutionally recognized Indian languages, and is **free for low-volume use** with paid tiers for higher commitment/concurrency — this fits both the India-first mandate and the tight budget better than any commercial alternative.

- **Fallback:** Google Cloud Translation, kept as a documented alternative behind an abstraction layer in code (translate via an internal interface, not a direct Bhashini SDK call scattered through the codebase) — if Bhashini's quality proves insufficient for specific culinary/ingredient vocabulary during Stage 5 testing, swapping providers doesn't mean rewriting the feature.
- Domain-term accuracy (ingredient names, units) still needs a human-reviewed glossary regardless of provider, per the caution already recorded in REQUIREMENTS.md.

---

## 8. SCM, CI/CD, Issue Tracking, Testing

| Concern | Choice | Why |
|---|---|---|
| **SCM** | GitHub (private repo) | Free private repos, ubiquitous, zero learning curve. |
| **CI/CD** | GitHub Actions | Free tier comfortably covers pilot-scale build/test/deploy volume; keeps CI in the same platform as source control — one less dashboard. |
| **Issue tracking** | GitHub Issues + Projects (board view) | Commandment 4 requires uploading stories to an issue tracker. A dedicated tool (Jira/Linear) adds cost and a second system to operate for a one-person team; GitHub Issues + Projects is free, sits next to the code, and is entirely sufficient at this scale. Revisit only if a real team forms. |
| **Backend testing** | JUnit 5 + Mockito + Testcontainers | Standard modern Java stack; Testcontainers specifically matters here — it spins up a real ephemeral Postgres for integration tests, which is important for testing RLS policies against real database behavior rather than mocking the tenant-isolation boundary away. |
| **Frontend unit/component testing** | Vitest + React Testing Library | Vite-native (fast), Jest-compatible API, standard pairing with Next.js/React in 2026. |
| **End-to-end testing** | Playwright | Industry-standard E2E tool, independent of the PDF-generation decision in §2 — different job, same underlying browser-automation technology, so tooling knowledge transfers. |

---

## 9. Summary — Open Items from SYSTEM_DESIGN.md §13, Resolved

| # | Item | Resolution |
|---|---|---|
| 1 | Cloud provider + region | GCP, `asia-south1` (Mumbai) |
| 2 | Backend / frontend framework | Spring Boot + Spring Data JPA (revised 2026-08-03, was Django); Next.js (React + TS) |
| 3 | Managed IdP | Firebase Authentication |
| 4 | Payment gateway | Razorpay |
| 5 | WhatsApp BSP | Meta Cloud API direct (not a BSP) — see §6 flag |
| 6 | Translation provider | Bhashini, with Google Cloud Translation as an abstracted fallback |
| 7 | Postgres job queue / PDF library | Quartz Scheduler (JDBC store); Playwright (Java) + Chromium (+ Noto fonts bundled) |
| 8 | SCM, CI/CD, issue tracker, testing | GitHub, GitHub Actions, GitHub Issues/Projects, pytest, Vitest, Playwright |

---

## 10. Revised Cost Envelope

| Component | Est. USD/mo |
|---|---|
| Cloud Run (backend + worker + frontend, min-instances warm) | 30–65 — nudged up from the Django estimate: JVM memory footprint and a Chromium-bundling worker image both cost a bit more than their Python equivalents would have |
| Cloud SQL (PostgreSQL, regional HA) | 40–80 |
| Cloud Storage + egress | 3–8 |
| Cloudflare (CDN/WAF) | 0 (free tier) |
| Firebase Auth (MAU free tier + SMS OTP) | 5–15 |
| Sentry + uptime monitor | 0 (free tiers) |
| WhatsApp (Meta direct, per-message, utility/auth category) | 10–30 at pilot volume |
| **Total** | **~$90–195/mo** — still inside the $50–200 budget, but with less margin than the Django estimate had. Worth watching the Cloud Run bill after real usage data comes in, not just estimating once and forgetting it. |

Razorpay and translation costs are usage/transaction-based and sit outside this infra line, as in SYSTEM_DESIGN.md v1.0.

---

## 11. Points Where I'd Welcome Your Challenge

Per Commandment 9 — the calls I'm least certain about, in order:

1. **Meta direct vs. BSP for WhatsApp (§6).** I'm confident on cost; less confident on how much onboarding friction Meta Business verification adds for a solo builder without prior Meta Business Manager experience. If that turns out to be a real time sink, a low-tier BSP might be worth the premium.
2. **GCP over AWS (§1).** Cost and simplicity favor GCP clearly, but AWS's larger ecosystem is a real asset if you ever want to bring on contractors familiar with one platform but not the other.
3. **Playwright + Chromium for PDF generation (§2).** I'm confident the script-shaping quality is fine — Chromium does the rendering regardless of which language drives it. Less confident about the operational cost of a Chromium-bundling container on Cloud Run at pilot scale; worth measuring actual cold-start and memory numbers early rather than assuming the estimate in §10 is right.
4. **Quartz vs. db-scheduler (§2).** I recommended Quartz as the safer default, but you're the Java engineer here — if you have a strong prior opinion on either from production experience, that should probably outweigh my research-based guess.
5. **No free admin panel (§2).** Building tenant-provisioning/audit-log screens into Next.js instead of getting them for free from Django is the right call architecturally, but it is genuinely more upfront work than the Django version of this document promised. Flagging so it doesn't get lost as a Stage 4/5 estimation surprise.
