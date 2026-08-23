# Working on this project

> ## ⛔ STOP — there is an open build list
>
> **`docs/OUTSTANDING_BUILD_LIST.md` is open and binding.** It holds the defects and changes
> Rajeev found reviewing the application on 2026-08-23, several of which were cut to make a
> demo deadline and are **not built yet**. Read it in full before planning anything, and put
> its outstanding items to Rajeev before you start on anything new.
>
> Nothing leaves that file until **Rajeev has seen it working and said so** — not because it
> looks stale, not because a later session did something nearby, not because you cannot
> reproduce it. When the last item goes, delete the file and this banner.


Read `docs/PROJECT_COMMANDMENTS.md` first. It is the governing agreement for how this project is run, and it takes precedence over anything here.

## What this is

A multi-tenant web application for managing ISKCON temple food service operations — recipes, inventory, meal planning, procurement, volunteer coordination, and donations. Each temple is an isolated tenant. India-first: INR, UPI, WhatsApp, Vaishnava calendar.

## Where the decisions live

Read these before proposing anything that touches them. All are locked at v1.0 and versioned in `docs/versions/`; changing a locked document requires Rajeev's explicit sign-off and a changelog entry.

| Document | Covers |
|---|---|
| `docs/PROJECT_COMMANDMENTS.md` | The nine rules governing how we work |
| `docs/REQUIREMENTS.md` | What the product does, Phase 1 vs Phase 2 |
| `docs/SYSTEM_DESIGN.md` | Architecture, multi-tenancy, security, cost |
| `docs/TECH_STACK.md` | Every technology choice, with the reasoning and what was rejected |
| `docs/DESIGN_SYSTEM.md` | Colour, type, spacing, icons, error messages |
| `docs/DEPLOYMENT.md` | How to get it running on GCP, and the outstanding follow-ups |
| `docs/stories/` | 9 epics, 85 stories. The first 55 are mirrored to GitHub Issues; `github-import/` has been behind since E1-S12 and is a job of its own |
| `docs/CHANGELOG.md` | Version history of the locked documents |

## Stack

Java 21 / Spring Boot 3.3 / PostgreSQL with Row-Level Security · Next.js 14 / TypeScript / Tailwind · GCP `asia-south1` · Firebase Auth · Razorpay · Meta WhatsApp Cloud API · Bhashini

## The three things most likely to be got wrong

**Tenant isolation is enforced by the database, not by application code.** Every tenant-owned table calls `enable_tenant_rls()` in its migration. The application connects as an unprivileged role that has neither DDL nor BYPASSRLS. Never add a tenant-owned table without the RLS policy, and never resolve `tenant_id` from a request parameter — it comes from the verified token only.

Two traps already found the hard way, both covered by tests in `RowLevelSecurityIT`: PostgreSQL superusers bypass RLS entirely regardless of `FORCE ROW LEVEL SECURITY`, so tests must run as an unprivileged role or they prove nothing; and `RESET` leaves a custom setting as an empty string rather than null, so the policy uses `NULLIF(..., '')` to fail closed quietly instead of raising.

**Firebase authenticates; it does not authorise.** A valid token proves someone controls an email or phone number. Role and tenant come from our own `users` table, which is why disabling someone takes effect on their next request.

**Endpoints declare a permission, never a role.** `@PreAuthorize("hasAuthority('MANAGE_VENDOR_PAYMENTS')")`. The whole policy lives in `RolePermissions.java` and is meant to be readable as a document.

## Errors

Every user-facing failure has a permanent `KMS-nnnn` code in `ErrorCode.java`, plain-language text, and a next step. Codes are never reused or renumbered — someone may quote one from an old screenshot. Nothing technical reaches the user; the detail goes to the logs with an incident id. Tests in `ErrorCodeTest` enforce this.

## Before pushing

Run the same checks CI runs:

```bash
cd backend  && ./gradlew test
cd frontend && npx tsc --noEmit && npm test
```

Backend tests need Docker running — Testcontainers starts a real PostgreSQL, because Row-Level Security is a database behaviour and mocking it would prove nothing.

## Working style

Rajeev wants to be challenged, not agreed with. Commandment 9 is explicit about this: push back when something is not the most logical option, explain why, and propose an alternative. Assumptions get stated and flagged rather than made silently. When a requirement is unclear or contradictory, ask — do not guess.

Real temple artifacts live in the repo root (`RM 2019_v2.xlsx`, the ICC menu workbook, the Janmashtami operations plan). They contain actual recipes, real festival scale, and the temple's own working practices. Prefer them over invented examples.
