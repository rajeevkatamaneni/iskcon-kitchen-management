# User Acceptance Testing — ISKCON Seva Kitchen

Thank you for testing. **You do not need to know anything about this system to run these tests.**
Everything you need is here and in each test document. Start at the top and work down.

---

## 1. How this pack works

There are **61 tests**. Each one covers a single feature, end to end, and is written so that
somebody who has never seen the product can run it. Every test document has the same shape:

| Section | What it gives you |
|---|---|
| **What this feature is for** | Why the feature exists, in plain language. |
| **How it is supposed to work** | The designed behaviour — the rules the test is checking against. |
| **Before you start** | Which test must be done first, who to sign in as, and where to start. |
| **Steps** | Numbered actions, each with exactly what you should see. |
| **It passes if** | The checklist. Every box true → the test passed. |
| **Watch out for** | Edge cases and the specific `KMS-nnnn` codes you should see. |
| **Report anything wrong** | Where you write down what went wrong. |
| **Root cause** | Filled in *after* the fix, by the team — see §6. |

Tests are numbered in **running order**, because later ones use what earlier ones created (you can't
plan a meal before there is a recipe). If a test says "before you start, finish UAT-015", that is
because it needs what UAT-015 left behind.

---

## 2. The test site

| | Address |
|---|---|
| **The site you test** | https://kms-staging-web-bnpkv5hfrq-el.a.run.app |
| The system behind it (only used where a test says so) | https://kms-staging-api-bnpkv5hfrq-el.a.run.app |

Every URL in these tests is written relative to the test site. Where a test says
"go to **/recipes**", the full address is
`https://kms-staging-web-bnpkv5hfrq-el.a.run.app/recipes`.

Use a normal browser (Chrome or Safari). A phone is fine for most tests, and UAT-061 asks for one
specifically.

---

## 3. The accounts

You sign in with **Continue with Google**. All accounts are on the `trading4good.org` domain; the
passwords are given to you separately (they are deliberately not in this pack).

| Who they are | Sign in as | First used in |
|---|---|---|
| **Platform operator** — runs the whole platform, belongs to no temple | `ikms.super-admin.1@trading4good.org` | UAT-001 |
| **Second platform operator** — used only to prove a second operator works | `ikms.super-admin.2@trading4good.org` | UAT-001 |
| **Temple admin** — runs our demo temple | `ikms.temple-admin.1@trading4good.org` | UAT-002 |
| **Second temple admin** — runs a *second* temple, so we can prove temples are separate | `ikms.temple-admin.2@trading4good.org` | UAT-006 |
| **Kitchen staff** — cooks and storekeepers | `ikms.kitchen-staff.1@trading4good.org` … `.5@…` | UAT-008 |
| **Volunteers** — devotees who offer seva | `ikms.volunteer.1@trading4good.org` … `.5@…` | UAT-008 |
| **Donors** — members of the public with an account | `ikms.donor.1@trading4good.org`, `ikms.donor.2@…` | UAT-056 |

**Important:** except for the two platform operators, an account can only sign in *after* somebody
has added it to a temple. That is what UAT-002 (the temple admin) and UAT-008 (everyone else) do. If
you sign in with an account nobody has added yet, you get a polite page saying *"You're signed in,
but this Google account isn't linked to a temple yet."* — that is correct behaviour, not a fault.

**To switch person:** sign out from the menu, then sign in again as the account the next step names.
Signing out fully (or using a private/incognito window) avoids Google silently reusing the last
account.

---

## 4. Before UAT can start — environment readiness

Some features cannot work until the environment they run on is switched on. **Check this table with
the person who set up the environment before you begin.** If something here is off, the tests marked
with it will fail for a reason that is nothing to do with the feature, and logging those as defects
wastes everyone's time.

| Switch | What breaks while it is off | Tests affected |
|---|---|---|
| **Background worker** (`KMS_WORKER_ENABLED`) | Nothing scheduled runs: Vaishnava calendar build, PDF generation, shift reminders, low-stock digest, order-list refresh, payment reconciliation | UAT-019, 020, 023, 029, 030, 031, 032, 034, 036, 038, 041, 052 |
| **Document renderer** (`DOCUMENTS_RENDERER`) | PDFs come out as placeholders, not real documents | UAT-019, 020, 041, 042 |
| **Translation provider** (`TRANSLATION_PROVIDER`) | "Translated" text comes back tagged, not really translated | UAT-020, 021, 042 |
| **Message channels** (WhatsApp / SMS / email adapters) | No message ever actually arrives — sends are only recorded | UAT-009, 028, 043, 047, 052, 053, 055 |
| **Payment provider** (`PAYMENTS_PROVIDER`, Razorpay test mode) | No checkout opens; a donation is recorded but never confirmed, so it never reaches the ledger | UAT-054, 055, 056, 058, 059 |

Each affected test repeats this under **Before you start**, so you always know. Where a feature can
be *partly* tested without the switch, the test says which steps to run and which to skip.

---

## 5. How to report something wrong

When a step does not do what the test says it should, **do not try to fix it or work around it —
write it down**. Each test has a table at the bottom; fill in a row:

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT015-1 | 4 | The recipe saves and appears in the list | Nothing happened; screen showed `KMS-5001` | Blocker |

**Severity:**

- **Blocker** — you cannot continue the test, or the feature is unusable.
- **Major** — the feature is wrong or broken, but you can carry on.
- **Minor** — wording, layout, or a small annoyance.

If you ever see a code like **`KMS-4917`** on screen, **write it down exactly**. It points straight
at one specific failure in the system's records.

---

## 6. Why each test names its technical stories — root-cause analysis

Every test lists the **technical stories** (`E2-S4`, `E6-S5`, …) that built the feature it covers.
That link exists so a defect found here can be traced back to the story that produced it, and we can
ask *why* it went wrong — not just fix it.

When a defect is fixed, the team fills in the **Root cause** table at the bottom of the test, using
one of these:

| Code | Root cause | What it means |
|---|---|---|
| **R1** | Story unclear or incomplete | The story did not say what should happen here. The gap is in the writing. |
| **R2** | Story misread | The story was clear; the implementation understood it differently. |
| **R3** | Developer oversight | The story was clear and understood; the code simply missed it. |
| **R4** | Conflicts with a locked document | The build follows the story but contradicts REQUIREMENTS / SYSTEM_DESIGN / DESIGN_SYSTEM. |
| **R5** | Environment or configuration | The code is right; the deployment isn't. |
| **R6** | Never built | No story covered this. A scope gap, not a bug. |
| **R7** | The test was wrong | The app is right; this UAT step expected the wrong thing. Fix the test. |

Findings are collected in `TRACEABILITY.md`, which also shows, for every technical story, which
tests cover it — so nothing is silently untested.

---

## 7. The running order

### Part 1 — The platform operator

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-001](UAT-001-platform-operator-sign-in.md) | Sign in as the platform operator | Super-admin | E1-S4, E1-S5, E1-S13 |
| [UAT-002](UAT-002-bring-a-temple-onto-the-platform.md) | Bring a temple onto the platform | Super-admin | E1-S6, E1-S3, E1-S7, E2-S1, E2-S2, E4-S2, E4-S4 |
| [UAT-003](UAT-003-view-and-delete-a-temple.md) | View a temple, and permanently delete one | Super-admin | E1-S15, E1-S6 |
| [UAT-004](UAT-004-platform-operations-and-health.md) | Platform operations and health | Super-admin | E1-S11, E1-S9 |
| [UAT-005](UAT-005-who-can-see-what.md) | Who can see what — role boundaries | All four | E1-S5, E1-S12 |
| [UAT-006](UAT-006-one-temple-cannot-see-another.md) | One temple cannot see another | Two temple admins | E1-S3, E1-S5 |

### Part 2 — Setting up a temple

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-007](UAT-007-first-sign-in-as-temple-admin.md) | First sign-in as a new temple admin | Temple admin | E1-S4, E1-S6, E1-S13 |
| [UAT-008](UAT-008-add-your-team.md) | Add your team | Temple admin | E1-S12 |
| [UAT-009](UAT-009-change-a-role-disable-restore.md) | Change a role; disable and restore someone | Temple admin | E1-S12, E1-S7 |
| [UAT-010](UAT-010-your-profile-and-consent.md) | Your profile and how the temple reaches you | Everyone | E1-S8 |
| [UAT-011](UAT-011-the-temple-audit-log.md) | The temple audit log | Temple admin | E1-S7 |
| [UAT-012](UAT-012-ways-to-sign-in.md) | Ways to sign in, and the locked door | Everyone | E1-S4 |

### Part 3 — The recipe book

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-013](UAT-013-build-the-ingredient-list.md) | Build the ingredient list | Kitchen staff | E2-S1 |
| [UAT-014](UAT-014-the-prohibited-flag-is-admin-only.md) | The prohibited flag is admin-only | Staff + admin | E2-S1, E2-S4 |
| [UAT-015](UAT-015-write-a-recipe.md) | Write a recipe | Kitchen staff | E2-S2 |
| [UAT-016](UAT-016-find-a-recipe.md) | Find a recipe | Kitchen staff | E2-S7, E2-S2 |
| [UAT-017](UAT-017-scale-a-recipe.md) | Scale a recipe for a festival | Kitchen staff | E2-S3 |
| [UAT-018](UAT-018-sattvic-block-and-override.md) | Keeping the kitchen sattvic | Staff + admin | E2-S4 |
| [UAT-019](UAT-019-print-and-download-a-recipe.md) | Print and download a recipe card | Kitchen staff | E2-S5 |
| [UAT-020](UAT-020-translate-a-recipe.md) | Translate a recipe | Kitchen staff | E2-S6 |
| [UAT-021](UAT-021-the-translation-glossary.md) | The translation glossary | Kitchen staff | E2-S6 |

### Part 4 — The store room

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-022](UAT-022-track-a-consumable.md) | Track a consumable and read the stock view | Kitchen staff | E3-S1 |
| [UAT-023](UAT-023-reorder-thresholds-and-low-stock.md) | Get warned before you run out | Kitchen staff | E3-S3 |
| [UAT-024](UAT-024-adjust-stock-with-a-reason.md) | Adjust stock with a reason | Kitchen staff | E3-S7 |
| [UAT-025](UAT-025-large-adjustments-need-an-admin.md) | Large corrections need a Temple Admin | Staff + admin | E3-S7 |
| [UAT-026](UAT-026-the-movement-history.md) | The movement history is a permanent record | Kitchen staff | E3-S2 |
| [UAT-027](UAT-027-the-equipment-register.md) | The equipment register | Kitchen staff | E3-S4 |
| [UAT-028](UAT-028-record-a-gift-in-kind.md) | Record a gift of goods | Kitchen staff | E3-S5 |

### Part 5 — The calendar and the meal plan

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-029](UAT-029-the-vaishnava-calendar.md) | The Vaishnava calendar in the planner | Kitchen staff | E4-S1 |
| [UAT-030](UAT-030-festival-occasions.md) | Festival occasions | Staff + admin | E4-S2 |
| [UAT-031](UAT-031-correct-a-calendar-date.md) | Correct a calendar date | Temple admin | E4-S3 |
| [UAT-032](UAT-032-plan-a-meal.md) | Plan a meal | Kitchen staff | E4-S4 |
| [UAT-033](UAT-033-outside-catering.md) | An outside catering commitment | Kitchen staff | E4-S4 |
| [UAT-034](UAT-034-do-we-have-the-ingredients.md) | Do we have the ingredients? | Kitchen staff | E4-S5 |
| [UAT-035](UAT-035-cook-a-meal.md) | Cook a meal — stock comes down | Kitchen staff | E3-S6, E4-S4 |
| [UAT-036](UAT-036-the-ekadashi-guard.md) | The Ekadashi guard | Kitchen staff | E4-S6 |

### Part 6 — Buying and receiving

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-037](UAT-037-vendors.md) | Vendors and what they supply | Kitchen staff | E5-S1 |
| [UAT-038](UAT-038-the-order-list.md) | The suggested order list | Kitchen staff | E5-S2 |
| [UAT-039](UAT-039-generate-purchase-orders.md) | Turn the order list into purchase orders | Kitchen staff | E5-S3, E5-S2 |
| [UAT-040](UAT-040-purchase-order-lifecycle.md) | Send and cancel a purchase order | Kitchen staff | E5-S3 |
| [UAT-041](UAT-041-the-po-sheet.md) | The purchase-order sheet: print and PDF | Kitchen staff | E5-S4 |
| [UAT-042](UAT-042-po-in-the-vendors-language.md) | The order in the vendor's language | Kitchen staff | E5-S5 |
| [UAT-043](UAT-043-send-a-po-on-whatsapp.md) | Send an order on WhatsApp | Kitchen staff | E5-S7 |
| [UAT-044](UAT-044-receiving-a-delivery.md) | Receiving: full, short and rejected | Kitchen staff | E5-S6 |
| [UAT-045](UAT-045-record-a-vendor-invoice.md) | Record a vendor's bill | Kitchen staff | E5-S8 |
| [UAT-046](UAT-046-pay-a-vendor-invoice.md) | Pay a vendor's bill | Temple admin | E7-S8 |

### Part 7 — People and seva

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-047](UAT-047-staff-schedule.md) | Staff profiles and the weekly schedule | Temple admin | E6-S1 |
| [UAT-048](UAT-048-post-a-volunteer-shift.md) | Post a volunteer shift | Staff + admin | E6-S2 |
| [UAT-049](UAT-049-volunteer-signs-up.md) | A volunteer signs up for seva | Volunteer | E6-S3 |
| [UAT-050](UAT-050-volunteer-releases-a-spot.md) | A volunteer releases a spot | Volunteer | E6-S4 |
| [UAT-051](UAT-051-the-waitlist.md) | The waitlist promotes automatically | Volunteers | E6-S5 |
| [UAT-052](UAT-052-shift-reminders.md) | Shift reminders | Volunteer | E6-S6 |
| [UAT-053](UAT-053-broadcast-an-update.md) | Broadcast an update to a shift | Staff + admin | E6-S7 |

### Part 8 — Donations

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-054](UAT-054-the-public-donation-page.md) | The public donation page | Public | E7-S1 |
| [UAT-055](UAT-055-give-once.md) | Give once: named, anonymous, or with 80G | Public | E7-S2, E7-S4, E7-S9 |
| [UAT-056](UAT-056-monthly-giving.md) | Monthly giving | Donor | E7-S3 |
| [UAT-057](UAT-057-manage-the-wish-list.md) | Manage the wish list | Temple admin | E7-S5 |
| [UAT-058](UAT-058-sponsor-a-wish-list-item.md) | Sponsor a wish-list item | Public | E7-S6 |
| [UAT-059](UAT-059-the-donations-ledger.md) | The donations ledger and export | Temple admin | E7-S7 |

### Part 9 — Across the whole product

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-060](UAT-060-errors-speak-plainly.md) | Errors speak plainly and carry a code | All | Cross-cutting (all epics) |
| [UAT-061](UAT-061-it-works-on-a-phone.md) | It works on a phone | All | E2-S7, E7-S1, E7-S6 |

---

*Written against the coding stories in `docs/stories/`, the locked documents in `docs/`, and the code
as deployed. Two-way traceability, and the known coverage gaps, are in [TRACEABILITY.md](TRACEABILITY.md).*
