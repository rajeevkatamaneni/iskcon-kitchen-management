# Working on this project

> ## ⛔ STOP — there is an open build list
>
> **`docs/OUTSTANDING_BUILD_LIST.md` is open and binding.** It holds the defects and changes
> Rajeev found reviewing the application on 2026-08-23, several of which were cut to make a
> demo deadline and are **not built yet**. Read it in full before planning anything, and put
> its outstanding items to Rajeev before you start on anything new.
>
> **Verification is two passes, amended by Rajeev 2026-09-07.** The session does the first: drive
> the deployed app as the role the item is written for, fix what you find, and mark the item
> `DONE — verified <date>` with a line saying what you actually pressed. Rajeev tests after you and
> reopens anything you missed. **Leave the item's block in place when you mark it** — he needs it to
> test against — and leave anything that is a matter of taste to him. The file goes when he says so,
> and this banner with it.
>
> Still true: do not remove an item because it looks stale, because a later session did something
> nearby, or because you cannot reproduce it.

> ## ➡️ Then read the work queue
>
> **`docs/WORK_QUEUE.md` is ordered, and item 1 is what to build next.** Rajeev asked
> (2026-09-01) that it be read without him having to say so again, so read it and put item 1 to
> him before proposing anything of your own. It is not the backlog: `docs/stories/BACKLOG.md` is
> explicitly work that is *not* scheduled, and this is work that is.
>
> **The deployment pipeline is done** (2026-09-05): ~25 minutes became **5m49s** on a warm cache.
> Items 1 and 2 — equipment servicing with its screen (E3-S10, E3-S11), and events with the travel
> estimate and catering removed (E4-S15, E4-S16) — are **built and on staging awaiting Rajeev's
> test**. Item 3 is the Kannada word-order defect, not yet started.


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
| `docs/WORK_QUEUE.md` | **Ordered.** What to build next, item 1 first |
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

Every user-facing failure has a permanent `KMS-nnnnnn` code in `ErrorCode.java`, plain-language text, and a next step. Codes are never reused or renumbered — someone may quote one from an old screenshot. *(Renumbered once, on 2026-09-07, from the four-digit scheme whose bands mirrored the HTTP status and had overflowed; six digits were chosen so the two namespaces are disjoint and no code ever means two things. The full old→new table is in `docs/ERROR-CODE-RENUMBER-2026-09-07.md`, because git history and old notes still quote four-digit codes. That was a one-time, pre-release amendment and the rule stands as written from here.)* Nothing technical reaches the user; the detail goes to the logs with an incident id. Tests in `ErrorCodeTest` enforce this.

## Before pushing

Run the same checks CI runs:

```bash
cd backend  && ./gradlew test
cd frontend && npx tsc --noEmit && npm test
```

Backend tests need Docker running — Testcontainers starts a real PostgreSQL, because Row-Level Security is a database behaviour and mocking it would prove nothing.

## How to talk to Rajeev — MANDATORY

**This is a rule, not a preference. It applies to every agent, every reply, every proof and
every report.** Rajeev has asked for it repeatedly and it has been ignored repeatedly.

**Write plain English. Say the thing. Stop.**

- **Length is the shortest that keeps the details.** Not a word more. If a reply can be three
  sentences, it is three sentences.
- **No essays.** No throat-clearing, no restating the question, no summarising what you just did
  at the end of doing it.
- **No tables, no bold-heavy headers, no ceremony** unless the content is genuinely tabular.
- **No literary tricks.** No em-dash asides stacked three deep, no "and that is the tell", no
  building to a point. Lead with the point.
- **Use an example when it makes the answer shorter or clearer.** A concrete case beats a
  paragraph of explanation.
- **Answer first, reasoning after, and only if he needs it to decide.**

Bad:

> Confirmed against the tree rather than taking it on the description. `correct:411` loops
> `applyCorrection`, which reverses at `:461` and immediately re-draws at `:464` before the next
> dish is touched at all — and the allocator sums `stock_movements`, so it does see the
> uncommitted reversal, which means the shortfall is real to the allocator and imaginary in fact.

Good:

> Confirmed. A correction fixes each dish one at a time, so dish 1's re-draw happens before dish
> 2 gives its stock back. Correct two dishes in opposite directions and it refuses for stock the
> temple is holding. Fix: reverse all dishes, then re-draw all dishes.

**One exception:** the ledger and decision records in `docs/work/` are written to be read cold by
a session with no context, so they carry their reasoning on purpose. That is the only place.
Everything said *to Rajeev* follows the rule above.

## Working style

Rajeev wants to be challenged, not agreed with. Commandment 9 is explicit about this: push back when something is not the most logical option, explain why, and propose an alternative. Assumptions get stated and flagged rather than made silently. When a requirement is unclear or contradictory, ask — do not guess.

Real temple artifacts live in **`reference/recipes/`** — `RM 2019_v2.xlsx` (the temple's own recipe
master, 20 sheets) and `Karnataka_Temple_Recipes.pdf`. They are **gitignored**, so a fresh clone will
not have them. Prefer them over invented examples, and check they are present before citing them.

*Corrected 2026-09-04: this paragraph used to say the files were in the repo root and to name an ICC
menu workbook and a Janmashtami operations plan. Neither of those exists anywhere in the tree.*

What the workbook actually shows, since it is easy to over-read: the temple classifies recipes by
**dish type** (Rice, Dal, Sweets, Breakfast as a course), expresses occasion as a **suffix on a
duplicated recipe** ("Sunday Khichadi", "Varai Halva For Janmastami", "FFL Rava Halva"), and keeps
**no event register at all**. Its `FHC Sabjis` sheet — bulk distribution — is the one structurally
different thing in it: gross kilograms per dish, no ingredients, no head count.
