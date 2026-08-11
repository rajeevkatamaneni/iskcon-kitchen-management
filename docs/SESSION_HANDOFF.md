# Session Handoff — 2026-08-11

Built from evidence, not memory. Each claim cites its source: a commit SHA, a `file:line`, a GitHub issue number, or the exact command whose output it came from. Where I believe something but cannot cite it, it is in **Section 7** and flagged unverified.

Snapshot at write time:
- Branch `main`, HEAD **`dfe45c2`**, working tree clean (`git status --short` → empty).
- 110 commits total (`git rev-list --count HEAD` → 110).

---

## 1. What actually landed (git log)

History is **linear on `main` — there are no merge commits**, so "since the Epic 1 merge" means "every commit after the last Epic 1 story." Epic 1 concluded at `e06611d` (E1-S13); everything below is Epics 2–7 + UAT + deploy work. Source: `git log --oneline`.

**Epic 2 — Recipes** (`082acb0`..`7df4c82`, `28cb5af`): E2-S1 ingredient master, S2 recipe CRUD, S3 scaling, S4 sattvic enforcement, S5 PDF/print, S6 translation, S7 browse/search; recipe/ingredient/glossary UIs (`7df4c82`); palette switch to terracotta (`28cb5af`, DESIGN_SYSTEM v1.1).

**Epic 3 — Inventory** (`a84f83c`..`a1d536e`, `58d7a7f`): S1 items+stock view, S2 append-only movements ledger, S3 reorder thresholds + nightly digest, S4 equipment, S5 in-kind donation intake, S6 consumption on production, S7 manual adjustment; inventory/equipment/donation screens (`58d7a7f`).

**Epic 4 — Meal planning & Vaishnava calendar** (`bf147f2`..`f39a293`): S1 astronomical calendar engine, S2 festival occasions, S3 admin override, S4 meal plan CRUD, S5 sufficiency/shortfall, S6 Ekadashi flagging; planner UI (`f39a293`).

**Epic 5 — Ordering & vendors** (`f847f2e`..`c153850`): S1 vendors, S2 order list, S3 PO lifecycle, S4 PO PDF, S5 PO translation, S6 receiving, S7 WhatsApp PO delivery, S8 invoice capture; E5 frontend (`45f2456`); all-22-languages PO labels + CI fix (`c153850`).

**Epic 6 — Workforce** (`d87c690`..`34f9a41`): S1 staff schedule, S2 shift posting, S3 signup (atomic capacity), S4 release, S5 waitlist auto-promotion, S6 scheduled reminders, S7 broadcast; E6 frontend (`34f9a41`).

**Epic 7 — Payments & donations** (`508c788`..`f1649e9`): S9 Razorpay webhook infra + payment-gateway port, S2 one-time donation, S4 80G/PAN encryption, S5 wishlist mgmt, S6 public wishlist/sponsorship, S8 invoice payment, S3 recurring, S7 ledger, S1 public donation page + Epic 7 frontend (`f1649e9`).

**UAT authoring** (`3b42ec5`, `3ca25de`, `ed4b8a6`): full UAT suite for Epics 2–7 in `docs/stories/UAT.md`; reverse UAT back-links on every coding story; UAT-1 execution record.

**UAT-prep / deployment fixes this session** (`11ef384`..`dfe45c2`) — all made while standing up the live environment:
- `11ef384` role-based navigation; `17e77fe` volunteers land on My shifts.
- `60f26f4` inline Firebase web config + API URL into the frontend build; `7cefdd4` fix invalid font package (`fonts-noto-devanagari`→`fonts-indic`).
- `6ded7b9` fill UAT site URL; `990fc89` set backend CORS_ALLOWED_ORIGINS; `02cef54` enable Firebase token verification; `7a9d944` make revocation check best-effort.
- `6c40b25` ops copy + no-account polish; `004c77b` remove "Epic 4" jargon; `dfe45c2` rename ops section to "Notification metrics".

---

## 2. Open issues, and "closed but not really done"

**Open** (`gh issue list --state open`): only 5, all label `uat` — the UAT execution trackers, not code:
- #57 UAT-1 · #58 UAT-2 · #59 UAT-3 · #60 UAT-4 · #61 UAT-5.

**Closed** (`gh issue list --state closed`): **every coding story** — E1-S1..S14 (#1–11, #56, #62, #63), E2 (#12–18), E3 (#19–25), E4 (#26–31), E5 (#32–39), E6 (#40–46), E7 (#47–55).

**Closed but NOT actually done** — a story being closed means the code merged and its automated tests pass; it does **not** mean the feature works in the deployed environment. These have real gaps (evidence: deployed API env from `gcloud run services describe`, which sets **none** of the worker/provider vars, so all default to stub/off — see §4):
- **E1-S2 / E1-S13 (infra & bootstrap):** the intended separate DDL role is not wired — migrations run as the app role `kms_app`, which therefore **owns the schema** (`application.yml` flyway comment; deployed `DB_USER=kms_app`). See §5. Also `DEPLOYMENT.md` Step 5's seed procedure is wrong for Cloud SQL (§6, live-4).
- **E1-S10 notifications, E5-S7 WhatsApp PO, E6-S6/S7 reminders/broadcast:** no real channel adapter exists — stub/logging only. And the **background worker is off** on deploy, so even the scheduled ones never fire. "Delivered" is unproven.
- **E2-S5/S6, E5-S4/S5 documents & translation:** deployed on the **stub** renderer/translator; real Chromium-PDF and Google Translate are not exercised (the only two skipped backend tests are exactly these smoke tests — §3).
- **E7-S1..S9 payments/donations:** deployed on the **stub** payment gateway (`PAYMENTS_PROVIDER` unset); Razorpay never exercised against the live app.
- **E4-S1 calendar engine:** worker off → precompute doesn't run on deploy; correctness not independently validated (§7).
- **E1-S4 Firebase auth:** revocation check degraded to best-effort on the live env (`7a9d944`, §6 live-3).
- **E1-S11 observability / ops page:** the ops page is mid-redesign from live UAT (§6).
- **All Epic 2–7 frontend:** built and unit-tested, but never exercised against the live backend — only the super-admin sign-in has been verified live (§7).

---

## 3. Test suite — actual counts

Run 2026-08-11 on HEAD `dfe45c2`.

**Backend** (`cd backend && ./gradlew test`, JDK 21 + Docker/Testcontainers): **BUILD SUCCESSFUL**. Authoritative counts from the JUnit XML (`build/test-results/test/*.xml`, 68 classes): **648 tests — 646 passed, 0 failed, 0 errors, 2 skipped.** No failing tests.
- The **2 skipped** are external-service smoke tests, skipped without credentials by design:
  1. `org.iskcon.kms.translation.GoogleTranslationSmokeIT` → "translates English to Kannada via the real Cloud Translation API"
  2. `org.iskcon.kms.document.GcsDocumentStorageSmokeIT` → "stores and reads back bytes against the real GCS bucket"

**Frontend** (`cd frontend && npm test`): **33 files, 123 tests, 123 passed, 0 failed, 0 skipped.**

**CI** (`gh run list --branch main`): the 6 most recent runs are all `completed / success`, including run `31477457906` on HEAD `dfe45c2`.

---

## 4. Deployed Cloud Run revisions vs `main`

From `gcloud run services describe` (project `iskcon-kms-2026`, region `asia-south1`):

| Service | Active revision | Image | Corresponds to |
|---|---|---|---|
| `kms-staging-api` | `kms-staging-api-00007-9qw` | `…/kms/api:authfix-014236` | backend at commit `7a9d944` |
| `kms-staging-web` | `kms-staging-web-00005-d4t` | `…/kms/web:opscopy-022307` | frontend at commit `dfe45c2` |

**Code:** both match `main`. The web image was built from HEAD (`dfe45c2`). The api image was built from `7a9d944`; the 3 commits after it (`6c40b25`, `004c77b`, `dfe45c2`) are **all frontend**, so the deployed backend equals `main`'s backend. *(Caveat: image tags are timestamps, not SHAs — this mapping is from deploy history, not cryptographic — see §7.)*

**Config differs from a clean checkout.** The live API revision carries env the repo alone doesn't fully reproduce (from `gcloud … --format 'value(...env)'`): `KMS_FIREBASE_ENABLED=true`, `CORS_ALLOWED_ORIGINS=<web urls>`, `DB_USER=kms_app`, `SPRING_PROFILES_ACTIVE=staging`, `DOCUMENTS_BUCKET=…`, `DB_PASSWORD`←secret. **`CORS_ALLOWED_ORIGINS` and `keep_alive=true` live in `infra/environment/terraform.tfvars`, which is git-ignored** — so a fresh clone cannot reproduce the running deployment without re-supplying them.

**Not set → default to stub/off** (`gcloud … env[].name` shows none of these): `KMS_WORKER_ENABLED` (worker off — no scheduled jobs), `PAYMENTS_PROVIDER` (stub), `DOCUMENTS_RENDERER` (stub), `TRANSLATION_PROVIDER` (stub), `RAZORPAY_*`, `WHATSAPP_APP_SECRET`, `SENTRY_DSN`. `/health` reports `scheduler: STANDBY`.

---

## 5. Contradictions with the locked docs

> This is a **focused review of deviations I can concretely cite**, not a line-by-line conformance audit of all three documents. "A full locked-doc audit has not been done" is itself listed in §7.

**5.1 — The app DB role has DDL and owns the schema. → CODE/CONFIG BUG.**
- Locked docs say the opposite, explicitly and in three places:
  - `CLAUDE.md:30` — "The application connects as an unprivileged role that has neither DDL nor BYPASSRLS."
  - `docs/SYSTEM_DESIGN.md:83` — "A separate migration/admin role exists for schema changes only."
  - `docs/SYSTEM_DESIGN.md:135` — "Least privilege: app DB role has no DDL, no BYPASSRLS."
- Reality: `backend/src/main/resources/application.yml` (flyway block, ~lines 12–24) states in its own comment *"migrations currently run as the application role … the app role is intentionally denied DDL. Wiring [the separate role] … tracked as part of the E1-S2 deployment follow-ups."* The deployed `DB_USER=kms_app` (§4), and during the live super-admin seed the table owner was observed to be `kms_app` (`SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname='users'` → `kms_app`, run by Rajeev in Cloud SQL Studio). So the app role created and **owns** the tables → it has DDL.
- **Why code, not doc:** the design (separate `kms_migration` DDL role) is the right intent and is correct as written; the implementation simply never wired the dedicated Flyway datasource. Consequence: on the deployed DB, RLS isolation holds **only because `FORCE ROW LEVEL SECURITY` is on** (an owner is otherwise exempt) — a fragile single point rather than the intended defense-in-depth. Fix belongs in code/deploy (the E1-S2 follow-up), not the docs.

**5.2 — Immediate token revocation is degraded to best-effort. → CODE/CONFIG BUG (narrow).**
- `7a9d944` changed `FirebaseTokenVerifier` so that if the cross-project revocation lookup fails (it currently 403s — §6 live-3), the token is accepted on offline verification alone and a warning is logged. A Firebase-side revocation (password reset / explicit revoke) therefore persists until token expiry (~1h) instead of the next request.
- I did **not** find an explicit promise of immediate *Firebase-side* revocation in the three locked docs (the intent lives in code comments and `DEPLOYMENT.md`), so I am not certain this contradicts a *locked* document — flagged accordingly in §7. Note the separate, stronger guarantee in `CLAUDE.md` ("disabling someone takes effect on their next request") is about **our own** `users.status` check in `AuthenticationFilter`, which still holds and is unaffected.

**Stub-vs-real integrations (WhatsApp per `TECH_STACK.md`, Razorpay, Bhashini/Google translate, Chromium PDF) are gaps, not contradictions** — the stack *choices* match the docs; the live adapters just aren't wired/enabled (§2, §4). Listed there rather than here.

---

## 6. UAT defects and status

**Logged in `docs/stories/UAT.md` (UAT-1 story):**
- **UAT1-D1 (Blocker) — RESOLVED** (`4054ecf`): a freshly provisioned admin couldn't sign in; fixed by first-sign-in claim-on-match. Proven by `PendingAccountClaimIT` and re-run 52/52 green during UAT-1 execution (`ed4b8a6`).
- **UAT1-D2 (Minor) — OPEN:** invalid latitude/longitude returns `KMS-4001` (bean-validation), not the `KMS-4002` the story expects; `INVALID_COORDINATES` (4002) is declared but thrown nowhere. Test-text vs product-behaviour mismatch; product behaviour is fine.
- **UAT1-D3 (Minor) — OPEN:** duplicate-admin-email `KMS-4902` is unreachable via provisioning (unique index is per-tenant; provisioning always makes a new tenant). The check belongs to UAT-5 / `UserManagementIT`.

**Found during the live UAT pass (this session):**
- **live-1 — FIXED** (`004c77b`): Operations page rendered internal jargon "…the calendar engine arrives in Epic 4." *(I initially, wrongly, dismissed this as a browser artifact — corrected.)*
- **live-2 — FIXED** (`6c40b25`, `dfe45c2`): Operations "A temple's messages" was unclear → renamed "Notification metrics"; the "Suppressed" hint wrongly said "no usable channel" → corrected to consent-only (`NotificationService` marks SUPPRESSED only when the recipient hasn't consented).
- **live-3 — FIXED (as graceful degradation)** (`7a9d944`): sign-in 401'd because the cross-project Firebase revocation lookup returns **HTTP 403** (`checkRevoked`). Revocation is now best-effort; **the 403's exact cause (Identity Toolkit API not enabled on the runtime project vs. a permission/quota gap) is unconfirmed — §7.** Follow-up: restore strict revocation.
- **live-4 — FIXED (workaround)**: several deploy-blocking config defects the placeholder image had hidden — no Firebase web config in the frontend build (`60f26f4`), `CORS` unset (`990fc89`), `KMS_FIREBASE_ENABLED` unset (`02cef54`), the font-package build break (`7cefdd4`). Also `DEPLOYMENT.md` Step 5 (super-admin seed via the `postgres` admin) is **wrong for Cloud SQL** — the seed had to run as the owner `kms_app` with `FORCE` toggled. That doc correction is **still open**.

**Redesign requested, NOT yet done:** Rajeev asked to rework the Operations page — remove the Suppressed metric, the per-temple filter, and the "Recent failed sends" list; make Sent/Failed global with a 7-day trend; and move per-temple health to a proposed new **Temple-Admin health dashboard** (new story). Not started.

---

## 7. Things I believe but cannot cite — UNVERIFIED

*The important section. Exhaustive to the best of my ability. Treat every line as a claim needing independent confirmation.*

**A. Live behaviour never exercised (built + unit-tested ≠ works in the deployed env):**
1. Only **`ikms.super-admin.1`'s sign-in** has been verified live (Rajeev did it). `super-admin.2`, both temple-admins, all 5 kitchen-staff and 5 volunteers signing in on the live env: **unverified.**
2. **No temple has been provisioned on the live env.** UAT-01 Part A onward, and everything that depends on a temple existing, is unrun live.
3. Claim-on-match for **temple users** (not super-admin) on the live DB: unverified. (`PendingAccountClaimIT` passes locally, but the live DB has different table ownership — §5.1.)
4. **None of UAT-01..26 has had a live human pass.** UAT-1's *backend* was run via integration tests locally; that is not a browser run.
5. Every **Epic 2–7 frontend screen** (recipes, ingredients, inventory, equipment, planner, vendors, order list, POs, receiving, invoices, staff schedule, shifts, donations, wishlist, ledger, payments) rendering/working against the **live backend**: unverified. Only super-admin's Temples/Operations pages have been seen live.
6. **Role-based nav** for kitchen-staff/volunteer on the live env: unverified (only super-admin's menu seen).

**B. Features that are stub/off in the deployed env (believed non-functional live):**
7. **Notifications never send** — stub adapter; no real WhatsApp/SMS/email. Any "delivered/failed/suppressed" behaviour live is unproven.
8. **Background worker is off** (`KMS_WORKER_ENABLED` unset) → scheduled jobs do not run live: calendar precompute, shift reminders, low-stock digest, donation/payment sweeps, PO nightly refresh. Anything depending on them is dormant.
9. **Payments/donations run on the stub gateway** — Razorpay (even test mode) never exercised against the live app.
10. **Documents/PDFs are stub placeholders** (no Chromium worker); real rendering unverified (the 2 skipped smoke tests confirm GCS + Cloud Translation aren't exercised).
11. **Translation is stub** ("[lang]"-tagged, not real MT).

**C. Correctness I cannot back:**
12. **Vaishnava calendar accuracy** (Ekadashi/festival dates) — reference tests exist (`__tests__/calendar-names.test.ts`, `blr-reference-2025.json`), and `docs/CALENDAR-CORRECTNESS.md` documents a known drik-vs-GCAL divergence, but I have **not** validated computed dates against a published ISKCON panchanga, and none of it has run live (worker off).
13. **RLS tenant isolation holds on the deployed DB** — `RowLevelSecurityIT` passes locally as an unprivileged role, but the live DB has `kms_app` owning the tables with `FORCE` on (§5.1). I re-enabled `FORCE` after seeding but **did not test cross-tenant isolation on the live DB**. Believed to hold; unverified.
14. **The `checkRevoked` 403 root cause** is my hypothesis (Identity Toolkit API not enabled on the runtime project, or a permission/quota gap on `…-620ee`). I confirmed the 403; I did **not** confirm which.
15. The claim that our own user-disable still takes effect immediately on the live env (`AuthenticationFilter` `isActive` check): tested locally, unverified live.

**D. Deployment/config claims:**
16. The **image→commit mapping** in §4 is from deploy history, not verifiable from the tags (they're timestamps).
17. **`keep_alive=true` truly makes the env persistent / survives teardown** — inferred from the Terraform plan (`deletion_protection: true`), not tested by attempting a destroy.
18. **Cost ~$25–40/mo** — from `docs/DEPLOYMENT.md` / `infra/README.md` estimates, not measured against billing.
19. The **temporary `postgres` and `kms_migration` passwords were rotated** — I ran the rotation command, but did not verify the old values now fail.
20. The **`firebaseauth.viewer` binding** I added on `…-620ee` is present and sufficient — the command returned success, but with revocation now bypassed I never confirmed it actually enables the lookup.
21. The Firebase project's **display name is "iskcon-kms-2026" while its ID is `…-620ee`** — asserted from screenshots; consistent, but I never queried the project metadata directly.

**E. Documentation/artifact state:**
22. **Two UAT artifacts exist and diverge:** `docs/stories/UAT.md` (old format, 26 stories, `3b42ec5`) and `docs/uat/` (new self-contained format, **only README + UAT-01/02/03 written** — UAT-04..26 do **not** exist yet despite the README index listing them). Which is canonical is a decision not yet made.
23. The two "minor" UAT-1 defects (D2/D3) being genuinely minor is **my judgment**, not Rajeev's ruling.

**F. Inherited from the pre-compaction summary (this conversation was summarized once):**
24. Claims about *how* earlier work happened (e.g., "a background agent produced nothing so I built it myself", specific mid-session decisions) come from the summary and are **not independently re-verified** — though the commits and green suites corroborate the *outcomes*.
25. Any error-code number, concurrency mechanism, or behavioural detail I stated in earlier turns that isn't reflected in a citation here should be re-checked against the code before relying on it.

**G. Not audited at all:**
26. A **full conformance pass** of `REQUIREMENTS.md` / `SYSTEM_DESIGN.md` / `TECH_STACK.md` against the code has **not** been done (§5 covers only deviations I already knew to look for). Other contradictions may exist.
27. **Security posture of the live env** beyond what's above (secret handling, IAM least-privilege, the public donation/wishlist endpoints' exposure) has not been reviewed this session.

---

## Immediate next actions (for the fresh session)

1. Finish the Operations redesign Rajeev asked for (§6, "redesign requested").
2. Resume the UAT pack: write `docs/uat/UAT-04..26` in the new format (task #72), and decide the canonical UAT artifact (§7-22).
3. Post-UAT engineering follow-ups: wire the `kms_migration` Flyway role (§5.1), restore strict Firebase revocation (§6 live-3), correct `DEPLOYMENT.md` Step 5 (§6 live-4).
4. Do a real live pass of UAT-01→03 to convert §7-A items from "unverified" to tested.

*Memory files `uat-environment`, `dont-dismiss-user-observations`, `super-admin-creation-out-of-band`, and `running-backend-tests-locally` carry the operational detail behind several sections above.*
