# Traceability — technical stories ↔ UAT tests

Why this file exists: a defect found in UAT should point straight back at the story that produced it,
so we can ask *why* it went wrong and not merely fix it. It also proves nothing is silently untested.

Each UAT test is mirrored to a GitHub issue labelled `uat` — **#64 (UAT-001) … #124 (UAT-061)**, in
order, so the issue number is always 63 + the test number.

Root-cause codes used throughout the pack (defined in [README](README.md) §6):
**R1** story unclear · **R2** story misread · **R3** developer oversight · **R4** conflicts with a locked
document · **R5** environment/configuration · **R6** never built · **R7** the test was wrong.

---

## 1. Every technical story, and what covers it

| Story | What it built | Covered by |
|---|---|---|
| E1-S1 | Project scaffolding and CI | *Automated tests only — no manual surface* |
| E1-S2 | GCP infrastructure baseline | *Automated / deployment — no manual surface* |
| E1-S3 | Tenant model and row-level security | UAT-002, **UAT-006** |
| E1-S4 | Firebase authentication | UAT-001, UAT-007, **UAT-012** |
| E1-S5 | Role-based access control | UAT-001, **UAT-005**, UAT-006 |
| E1-S6 | Tenant provisioning | **UAT-002**, UAT-003, UAT-007 |
| E1-S7 | Audit log framework | UAT-009, **UAT-011**, UAT-014, UAT-025 |
| E1-S8 | Contact channels and communication preference | **UAT-010** |
| E1-S9 | Background job infrastructure | UAT-004 (scheduler health); its effects in UAT-019, 023, 029, 052 |
| E1-S10 | Notification service | Delivery is exercised by UAT-023, 028, 043, 047, **UAT-052**, 053, 055 |
| E1-S11 | Observability baseline | **UAT-004** |
| E1-S12 | Temple user management | **UAT-008**, UAT-009, UAT-005 |
| E1-S13 | Platform super-admin bootstrap | UAT-001, UAT-007 |
| E1-S14 | Platform-level audit log | *No operator screen was built — deferred inside the story itself. See gap G9* |
| E1-S15 | Temple detail, data export, permanent deletion | **UAT-003** |
| E1-S16 | Signing out, and idle sign-out | **UAT-063** |
| E1-S17 | Registering yourself at a temple | **UAT-008**, UAT-012 |
| E4-S8 | Today — the temple's morning screen | **UAT-062** |
| E2-S1 | Ingredient master | **UAT-013**, UAT-014 |
| E2-S2 | Recipe CRUD | **UAT-015**, UAT-016 |
| E2-S3 | Recipe scaling | **UAT-017** |
| E2-S4 | Sattvic enforcement | UAT-014, **UAT-018** |
| E2-S5 | Recipe PDF and print | **UAT-019** |
| E2-S6 | Recipe translation and glossary | **UAT-020**, UAT-021 |
| E2-S7 | Recipe browse and search | **UAT-016** |
| E3-S1 | Consumable inventory and stock view | **UAT-022** |
| E3-S2 | Stock movements ledger | **UAT-026** |
| E3-S3 | Reorder thresholds and low-stock alerts | **UAT-023** |
| E3-S4 | Equipment inventory | **UAT-027** |
| E3-S5 | In-kind donation intake | **UAT-028** |
| E3-S6 | Consumption on meal production | **UAT-035** |
| E3-S7 | Manual stock adjustment | **UAT-024**, UAT-025 |
| E4-S1 | Calendar engine | **UAT-029** |
| E4-S2 | Festival occasion catalogue | **UAT-030** |
| E4-S3 | Admin calendar override | **UAT-031** |
| E4-S4 | Meal plan across four contexts | **UAT-032**, UAT-033, UAT-035 |
| E4-S7 | The planner redesigned: meal kinds, ready-by times, the day view | **UAT-032**, UAT-033 |
| E4-S5 | Ingredient sufficiency and shortfalls | **UAT-034** |
| E4-S6 | Ekadashi violation flagging | **UAT-036** |
| E5-S1 | Vendor management | **UAT-037** |
| E5-S2 | Auto-generated order list | **UAT-038**, UAT-039 |
| E5-S3 | Purchase order generation and lifecycle | UAT-039, **UAT-040** |
| E5-S4 | PO document: PDF and print | **UAT-041** |
| E5-S5 | PO translation | **UAT-042** |
| E5-S6 | Receiving | **UAT-044** |
| E5-S7 | WhatsApp PO delivery | **UAT-043** |
| E5-S8 | Vendor invoice capture | **UAT-045** |
| E6-S1 | Staff profiles and weekly schedule | **UAT-047** |
| E6-S2 | Volunteer shift posting | **UAT-048** |
| E6-S3 | Volunteer signup | **UAT-049** |
| E6-S4 | Signup release | **UAT-050** |
| E6-S5 | Waitlist with auto-promotion | **UAT-051** |
| E6-S6 | Scheduled shift reminders | **UAT-052** |
| E6-S7 | One-off reminder broadcast | **UAT-053** |
| E6-S8 | Hiring, employment records, letting go | **UAT-064**, UAT-008 |
| E6-S9 | Is this Aadhaar card real? | *Specified, not built — needs a real Aadhaar QR to verify against* |
| E8-S1 | Communication categories and devotee preferences | **UAT-065** |
| E8-S2 | Compose, preview, and test | **UAT-066** |
| E8-S3 | Send it, and know it went | **UAT-066** |
| E8-S4 | A temple's message on WhatsApp | *Not built — blocked on WhatsApp credentials and Meta approving a MARKETING template* |
| ~~E7-S1~~ | ~~Public temple donation page~~ — **withdrawn 2026-08-29** with unauthenticated giving; UAT-054 was deleted with it | *No test — the story no longer exists* |
| E7-S2 | One-time donation | **UAT-055** |
| E7-S3 | Recurring donation | **UAT-056** |
| E7-S4 | 80G donor data capture | **UAT-055** |
| E7-S5 | Wish list management | **UAT-057** |
| E7-S6 | Wish list and sponsorship | **UAT-058** |
| E7-S7 | Donations ledger | **UAT-059** |
| E7-S8 | Vendor invoice payment recording | **UAT-046** |
| E7-S9 | Payment webhook infrastructure | UAT-055 (replay/idempotency), UAT-058 |
| E10-S1 | Requirements amendment: a temple has kitchens | *Documents only — no manual surface* |
| E10-S2 | The kitchens register | **UAT-067** |
| E10-S3 | The kitchens page | **UAT-067** |
| E10-S4 | A kitchen starts planning its own meals, and the cascade | **UAT-072** |
| E10-S5 | Asking the store for ingredients | **UAT-068**, UAT-069 |
| E10-S6 | Review: approve, deny, withdraw | **UAT-069** |
| E10-S7 | Recording what was issued | **UAT-070**, UAT-072 (the issued request that must survive) |
| E10-S8 | The requests list | **UAT-068** |
| E10-S9 | The request form | **UAT-068** |
| E10-S10 | The request record | **UAT-069**, UAT-070 |
| E10-S11 | The work order | **UAT-071** |
| E10-S12 | Ingredients and Inventory adopt the focus-screen add | **UAT-073**, and assumed by UAT-013 and UAT-022 |
| E11-S1 | `to_base_qty()` replaces the seven hand-written CASE fragments | *Automated only — `BaseQuantityIT`. No manual surface; the story changes no behaviour* |
| E11-S2 | One unit vocabulary; `YieldUnit` retired; `LITRES → L` | **UAT-074** (steps 33–34, 42–51) |
| E11-S3 | One way to say a quantity, with the rounding ladder and the cook's/ledger split | **UAT-074** (steps 1–27) |
| E11-S4 | Every screen says it the same way | **UAT-074** (steps 28–34) |
| E11-S5 | Documents and emails say it the same way | **UAT-074** (steps 35–41) |
| E11-S6 | Every dropdown offers the one list | **UAT-074** (steps 42–51) |

Bold marks the test that covers the story most directly. Every story with a user-facing surface is
covered by at least one test; the ones with none are marked as such and were accepted on automated
tests alone, per Commandment 6.

Cross-cutting tests: **UAT-060** (error presentation) and **UAT-061** (phone usability) apply to every
epic and belong to no single story.

---

## 2. Gaps found while writing this pack

These were found by reading the code and the stories side by side, **before any tester ran anything**.
They are recorded here rather than in the tester-facing documents, so that testers approach each screen
without being told what to expect. Each corresponding UAT test asks the tester to *look* for the thing
and write down what they find.

| # | What is missing | Story | Where the test looks | Likely root cause |
|---|---|---|---|---|
| **G1** | ~~**E1-S15 has no written story.**~~ **CLOSED 2026-08-11.** The story was written retrospectively (twelve numbered decisions, including the deliberate choice to keep deletion unconditional), and the export it was missing was built with it: a temple cannot be deleted without a data export taken in the last 24 hours (`KMS-4941`). | E1-S15 | UAT-003 | R6 / process |
| **G2** | ~~**No screen creates a calendar override.**~~ **CLOSED 2026-08-11.** A day panel in the planner now explains what the engine computed for any day, and lets a Temple Admin correct it — fasting flag, Ekadashi name, tithi by name, festival note, mandatory reason — with an undo. Staff see the panel and the hand-corrected badge, but no controls. | E4-S3 | UAT-031 | R6 |
| **G3** | **No screen manages festival occasions.** `OccasionController` supports the catalogue and the seed runs at provisioning, but a temple cannot add its own occasion — and "Temple Anniversary" is the story's own example of why it must. | E4-S2 | UAT-030 step 6 | R6 |
| **G4** | **Recurring giving has no donor-facing surface.** The ledger has a *Recurring* filter and the API has plans and cancellation, but the giving screen offers no one-time/recurring choice and there is no page where a donor sees or cancels a plan. | E7-S3 | UAT-056 steps 1–2, 6 | R6 |
| **G5** | **The broadcast daily limit cannot be changed from any screen.** `KMS-4935` tells the poster "ask a Temple Admin to raise the limit", and `GET/PUT /api/v1/settings` supports it — but no screen exposes it, so the error message promises something the product does not offer. | E6-S7 | UAT-053 step 10 | R6 (and R1 — the story says "tenant config" without saying where) |
| **G6** | **Kitchen staff cannot see their own schedule.** Every staff-schedule endpoint requires `MANAGE_STAFF_SCHEDULE`, which only a Temple Admin holds, and there is no staff-facing destination. The story asks for "staff see their own schedule". | E6-S1 | UAT-047 step 16 | R3 / R1 |
| **G7** | **Wish-list items cannot be edited, reordered or given an image.** The screen offers add and archive only, though the story asks for CRUD, image upload and manual ordering of what devotees see, and the API supports update and reorder. | E7-S5 | UAT-057 steps 7–9 | R3 |
| **G8** | **No purchase order can be raised by hand.** `POST /api/v1/purchase-orders` exists, but the only route in the app is "generate from the order list". The story asks for manual creation as well. | E5-S3 | UAT-039 step 10 | R3 |
| **G9** | **No operator screen for the platform audit log.** Acknowledged and deferred inside E1-S14 itself, so this is a known deferral rather than a surprise — recorded for completeness. | E1-S14 | — | Deferred by design |
| **G10** | ~~**Registering yourself at a temple has no written story.**~~ **CLOSED 2026-08-18.** The registration screen, the public temple list, `POST /api/v1/temples/{id}/join` and the one-person-many-temples migration all shipped unrecorded, and the code cited E1-S16 — which is sign-out. Written up retrospectively as **E1-S17** and the citations repointed. Found while making self-registration the *only* way a devotee joins (E1-S12), which left that story depending on one that did not exist. | E1-S17 | UAT-008, UAT-012 | R6 / process |
| **G11** | **Nobody can be made a Kitchen Manager from any screen.** E10 gives `APPROVE_INGREDIENT_REQUESTS` and `ISSUE_INGREDIENTS` to Temple Admin **and** Kitchen Manager — the role the design says a temple's storekeeper is appointed to (D4, which chose that over adding a Storekeeper role). But the Staff form's **App access** list offers only *No login*, *Kitchen staff* and *Temple admin*, and there is no role control anywhere else. So half of this epic's permission rule has no manual surface at all: every approval and every issue in UAT is done by the Temple Admin. The same gap already shrank E6-S1 (see G6). | E10-S6, E10-S7 | UAT-069 (the note under *Before you start*, and the last bullet of *Watch out for*), UAT-070 step 25 | R3 / R1 |

**Two caveats on this list.** First, these are reading findings, not test results: a tester may find a
route I did not. Second, several are *screens missing over working backends*, which is a much cheaper
class of defect to fix than a wrong rule — worth knowing before the pack is scheduled.

---

## 3. Environment readiness — what must be on before UAT means anything

Repeated from the README because it decides how much of this pack can be run at all. At the time of
writing, the deployed environment runs with the background worker off and stub providers for payments,
documents, translation and messaging. Anything found because of that is root cause **R5** and should
not be raised as a product defect.

| Switch | Tests that cannot pass while it is off |
|---|---|
| Background worker | UAT-019, 020, 023, 029, 030, 031, 032, 034, 036, 038, 041, 052, **071**, **074** (steps 35–41 only) |
| Document renderer | UAT-019, 020, 041, 042, **071**, **074** (steps 35–41 only) |
| Translation provider | UAT-020, 021, 042, **071** (steps 15–20 only) |
| Message channels | UAT-009, 023, 028, 043, 047, 052, 053, 055, **074** (step 40 only) |
| Payment provider (test mode) | UAT-055, 056, 058, 059 |

Fully runnable **today**, with no environment changes: UAT-001–018, 021, 022, 024–028, 033, 035, 037,
039, 040, 044–051, 057, 060, 061, **067–070**, **072**, **073**, and all of **074** except steps 35–41.
UAT-071 is the only one of the new tests that is environment-bound end to end; its print path (step 14)
is the part that still works with the worker down.

---

## 4. Defect register

Filled in as UAT runs. One row per defect, carried over from the individual test documents.

| Defect | Test | Severity | Technical story | Root cause | Status | Note |
|---|---|---|---|---|---|---|
| UAT031-1 | UAT-031 | Major | E4-S3 | R3 (developer oversight) | OPEN — analysed | The day panel renders below a full-screen month grid with no scroll-into-view, so clicking a day appears to do nothing. |
| UAT031-2 | UAT-031 | Minor | E4-S1 | R1 (story unclear) | OPEN — design decision | Day labels carry an unexplained prefix on most cells and none on two; no legend or affordance to find out what it means. |
| UAT004-1 | UAT-004 | Minor | E1-S11 | R3 (developer oversight) | OPEN — analysed, not yet fixed | System health reads as a row of labels above a row of values, so the worker's state does not register; and the explanatory line packs three ideas into one sentence. Layout fix plus shorter copy proposed in the test's defect note. |
| INT-2 | Meal plan | Major | E4-S4 | R1 (story unclear) | OPEN — redesign | The Recipe dropdown is empty at a new temple, so no meal can be planned at all, and nothing says why or points at Recipes. The screen named Meal plan is a dead end until someone happens to add recipes first. |
| INT-3 | All screens | Minor | E1-S6 | R3 (developer oversight) | FIXED 2026-08-14 — `whoami` now carries the temple's name, read per request so a rename shows immediately, and the sidebar reads it itself rather than 29 pages passing a placeholder | The sidebar read "Your temple" — a placeholder — so the app never says which temple you are working in. With more than one temple that is a real hazard. |
| INT-4 | Meal plan | Minor | E4-S4 | R3 | OPEN — resolved by redesign | Today is not marked on the month grid. |
| INT-5 | Meal plan | Minor | E4-S4 | R3 | OPEN — resolved by redesign | Subtitle reads "The week's cooking" above a month view. |
| INT-6 | Meal plan | Minor | E4-S1 | R3 | FIXED 2026-08-15 — the separator is rendered as a dash, and the Vaishnava calendar screen (E4-S9) gives festival names the room to be read | Festival names were truncated mid-word and carried the source's raw `--` separator, so the day could not be identified from the cell. |
| INT-7 | Meal plan | Minor | E4-S1 | R1 | OPEN — resolved by redesign | Only the first festival on a day is shown; extras vanish with no indication. |
| INT-8 | Meal plan | Minor | E4-S1 | R3 | OPEN | Sunrise and sunset are shown to the second (06:07:51) — false precision. |
| INT-9 | Meal plan | Minor | E4-S3, E4-S4 | R1 | OPEN — resolved by redesign | Two click targets on a day cell (the label opens information, the + plans a meal) with nothing to distinguish them; only one looks clickable, and only on hover. |
| INT-10 | Meal plan | Trivial | E4-S4 | R3 | OPEN | An empty `role="alert"` region renders on the plan-a-meal form; a screen reader may announce an empty alert. |
| INT-1 | (found in worker logs, not a UAT run) | Minor | E1-S15, E4-S1 | R3 (developer oversight) | OPEN — queued | A tenant deleted between a job being queued and the worker running it makes that job fail with `KMS-4401 We couldn't find that temple` and park as failed, with a stack trace in the logs. Seen the moment the worker first started: two calendar-precompute jobs, queued at provisioning for temples since deleted. Deleting a temple should cancel its pending jobs, and a job whose temple has gone should finish quietly rather than raise — a job can always race a deletion. Log noise today; it pollutes exactly the failed-job signal E1-S9/E1-S11 exist to make trustworthy. |
| INT-11 | (found deploying, not a UAT run) | Blocker | E4-S7 | R3 (developer oversight) | FIXED 2026-08-14 | V48 seeded meal kinds and backfilled `ready_by` with plain cross-tenant DML. Tenant-owned tables force RLS on their owner, and on staging the schema owner runs Flyway — so the INSERT was refused and the UPDATEs would silently have matched nothing. **The API and worker both crash-looped on boot**; the site served the previous API build for hours while `main` looked healthy. The migration now adopts each tenant in turn. Root cause behind the root cause: the suite ran Flyway as the Testcontainers superuser, which bypasses RLS entirely — the same trap already guarded against for the application role, and the one that produced V45 and V46. Migrations now run as an unprivileged schema-owning role in tests. |
| INT-12 | Today / Meal plan | Major | E4-S8, E4-S7 | R3 | FIXED 2026-08-14 | Two screens disagreed about what day it is: Today took the temple's day (IST) while the planner took the device's, so a reader outside India saw meals planned "for today" appear on neither. `/money` sliced a UTC ISO string — a third day again for half of every IST evening — and the staff schedule started its week from the device. The client now uses the temple's day everywhere, tested at the boundary. Found by driving the live site in a browser. |
| INT-13 | Donations ledger | Minor | E7-S7 | R3 | FIXED 2026-08-14 | Month-to-date and the financial-year boundary were computed in the server's timezone. The service runs in UTC, so between midnight and 05:30 IST a temple's month-to-date was missing the gifts of what it still called today. Surfaced by the Today screen's own test. |
| UAT003-1 | UAT-003 | Minor | E1-S15 | R3 (developer oversight) | FIXED 2026-08-11 — awaiting re-test | Export downloaded under the client's fallback name: CORS exposed only `X-Request-Id`, so the browser hid `Content-Disposition` from the page. Header now exposed (asserted in `CorsIT`), name is `<temple-web-address>-ikms-data-export.xlsx`, and the client fallback matches. |
