# User Acceptance Testing — ISKCON Seva Kitchen

Thank you for testing. **You do not need to know anything about this system to run these tests.**
Everything you need is here and in each test document. Start at the top and work down.

---

## 1. How this pack works

There are **63 tests**. Each one covers a single feature, end to end, and is written so that
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
| **Kitchen staff** — cooks and storekeepers, **hired** on the Staff page | `ikms.kitchen-staff.1@trading4good.org` … `.5@…` | UAT-008 |
| **Volunteers** — devotees who **register themselves** | `ikms.volunteer.1@trading4good.org` … `.5@…` | UAT-008 |
| **Donors** — devotees who give from inside the application; since 2026-08-29 there is no other way to give | `ikms.donor.1@trading4good.org`, `ikms.donor.2@…` | UAT-055 |

**Important:** except for the two platform operators, an account can only sign in *after* it belongs
to a temple — and as of 2026-08-19 there are exactly **two** ways that happens, neither of which is an
administrator typing somebody's details:

- a **devotee registers themselves**, choosing the temple, giving their own contact details and their
  own consent; or
- a **member of staff is hired** on the Staff page, which is also the only act that grants app access.

The temple's first administrator is the one exception: provisioning creates them (UAT-002), and now
employs them too. UAT-008 walks both roads. If you sign in with an account that belongs to no temple
yet, you get a polite page saying *"You're signed in, but this Google account isn't linked to a temple
yet."* — that is correct behaviour, not a fault.

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
| **Payment provider** (`PAYMENTS_PROVIDER`, Razorpay test mode) | No checkout opens; a donation is recorded but never confirmed, so it never reaches the ledger | UAT-055, 056, 058, 059 |

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

Each test is also a GitHub issue labelled `uat` (**#64–#124**), linked from its row below. Record the
run there, or on paper and then there — whichever suits you.

### Part 1 — The platform operator

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-001](UAT-001-platform-operator-sign-in.md) · [#64](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/64) | Sign in as the platform operator | Super-admin | E1-S4, E1-S5, E1-S13 |
| [UAT-002](UAT-002-bring-a-temple-onto-the-platform.md) · [#65](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/65) | Bring a temple onto the platform | Super-admin | E1-S6, E1-S3, E1-S7, E2-S1, E2-S2, E4-S2, E4-S4 |
| [UAT-003](UAT-003-view-and-delete-a-temple.md) · [#66](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/66) | View a temple, and permanently delete one | Super-admin | E1-S15, E1-S6 |
| [UAT-004](UAT-004-platform-operations-and-health.md) · [#67](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/67) | Platform operations and health | Super-admin | E1-S11, E1-S9 |
| [UAT-005](UAT-005-who-can-see-what.md) · [#68](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/68) | Who can see what — role boundaries | All four | E1-S5, E1-S12 |
| [UAT-006](UAT-006-one-temple-cannot-see-another.md) · [#69](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/69) | One temple cannot see another | Two temple admins | E1-S3, E1-S5 |

### Part 2 — Setting up a temple

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-007](UAT-007-first-sign-in-as-temple-admin.md) · [#70](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/70) | First sign-in as a new temple admin | Temple admin | E1-S4, E1-S6, E1-S13 |
| [UAT-008](UAT-008-add-your-team.md) · [#71](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/71) | Add your team | Temple admin | E1-S12 |
| [UAT-009](UAT-009-change-a-role-disable-restore.md) · [#72](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/72) | Change a role; disable and restore someone | Temple admin | E1-S12, E1-S7 |
| [UAT-010](UAT-010-your-profile-and-consent.md) · [#73](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/73) | Your profile and how the temple reaches you | Everyone | E1-S8 |
| [UAT-011](UAT-011-the-temple-audit-log.md) · [#74](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/74) | The temple audit log | Temple admin | E1-S7 |
| [UAT-012](UAT-012-ways-to-sign-in.md) · [#75](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/75) | Ways to sign in, and the locked door | Everyone | E1-S4 |

### Part 3 — The recipe book

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-013](UAT-013-build-the-ingredient-list.md) · [#76](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/76) | Build the ingredient list | Kitchen staff | E2-S1 |
| [UAT-014](UAT-014-the-prohibited-flag-is-admin-only.md) · [#77](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/77) | The prohibited flag is admin-only | Staff + admin | E2-S1, E2-S4 |
| [UAT-015](UAT-015-write-a-recipe.md) · [#78](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/78) | Write a recipe | Kitchen staff | E2-S2 |
| [UAT-016](UAT-016-find-a-recipe.md) · [#79](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/79) | Find a recipe | Kitchen staff | E2-S7, E2-S2 |
| [UAT-017](UAT-017-scale-a-recipe.md) · [#80](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/80) | Scale a recipe for a festival | Kitchen staff | E2-S3 |
| [UAT-018](UAT-018-sattvic-block-and-override.md) · [#81](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/81) | Keeping the kitchen sattvic | Staff + admin | E2-S4 |
| [UAT-019](UAT-019-print-and-download-a-recipe.md) · [#82](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/82) | Print and download a recipe card | Kitchen staff | E2-S5 |
| [UAT-020](UAT-020-translate-a-recipe.md) · [#83](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/83) | Translate a recipe | Kitchen staff | E2-S6 |
| [UAT-021](UAT-021-the-translation-glossary.md) · [#84](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/84) | The translation glossary | Kitchen staff | E2-S6 |

### Part 4 — The store room

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-022](UAT-022-track-a-consumable.md) · [#85](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/85) | Track a consumable and read the stock view | Kitchen staff | E3-S1 |
| [UAT-023](UAT-023-reorder-thresholds-and-low-stock.md) · [#86](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/86) | Get warned before you run out | Kitchen staff | E3-S3 |
| [UAT-024](UAT-024-adjust-stock-with-a-reason.md) · [#87](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/87) | Adjust stock with a reason | Kitchen staff | E3-S7 |
| [UAT-025](UAT-025-large-adjustments-need-an-admin.md) · [#88](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/88) | Large corrections need a Temple Admin | Staff + admin | E3-S7 |
| [UAT-026](UAT-026-the-movement-history.md) · [#89](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/89) | The movement history is a permanent record | Kitchen staff | E3-S2 |
| [UAT-027](UAT-027-the-equipment-register.md) · [#90](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/90) | The equipment register | Kitchen staff | E3-S4 |
| [UAT-028](UAT-028-record-a-gift-in-kind.md) · [#91](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/91) | Record a gift of goods | Kitchen staff | E3-S5 |

### Part 5 — The calendar and the meal plan

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-029](UAT-029-the-vaishnava-calendar.md) · [#92](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/92) | The Vaishnava calendar in the planner | Kitchen staff | E4-S1 |
| [UAT-030](UAT-030-festival-occasions.md) · [#93](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/93) | Festival occasions | Staff + admin | E4-S2 |
| [UAT-031](UAT-031-correct-a-calendar-date.md) · [#94](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/94) | Correct a calendar date | Temple admin | E4-S3 |
| [UAT-032](UAT-032-plan-a-meal.md) · [#95](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/95) | Plan a meal | Kitchen staff | E4-S7, E4-S4, E4-S3, E4-S5 |
| [UAT-033](UAT-033-outside-catering.md) · [#96](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/96) | An outside catering commitment | Kitchen staff | E4-S4 |
| [UAT-034](UAT-034-do-we-have-the-ingredients.md) · [#97](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/97) | Do we have the ingredients? | Kitchen staff | E4-S5 |
| [UAT-035](UAT-035-cook-a-meal.md) · [#98](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/98) | Cook a meal — stock comes down | Kitchen staff | E3-S6, E4-S4 |
| [UAT-036](UAT-036-the-ekadashi-guard.md) · [#99](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/99) | The Ekadashi guard | Kitchen staff | E4-S6 |

### Part 6 — Buying and receiving

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-037](UAT-037-vendors.md) · [#100](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/100) | Vendors and what they supply | Kitchen staff | E5-S1 |
| [UAT-038](UAT-038-the-order-list.md) · [#101](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/101) | The suggested order list | Kitchen staff | E5-S2 |
| [UAT-039](UAT-039-generate-purchase-orders.md) · [#102](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/102) | Turn the order list into purchase orders | Kitchen staff | E5-S3, E5-S2 |
| [UAT-040](UAT-040-purchase-order-lifecycle.md) · [#103](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/103) | Send and cancel a purchase order | Kitchen staff | E5-S3 |
| [UAT-041](UAT-041-the-po-sheet.md) · [#104](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/104) | The purchase-order sheet: print and PDF | Kitchen staff | E5-S4 |
| [UAT-042](UAT-042-po-in-the-vendors-language.md) · [#105](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/105) | The order in the vendor's language | Kitchen staff | E5-S5 |
| [UAT-043](UAT-043-send-a-po-on-whatsapp.md) · [#106](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/106) | Send an order on WhatsApp | Kitchen staff | E5-S7 |
| [UAT-044](UAT-044-receiving-a-delivery.md) · [#107](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/107) | Receiving: full, short and rejected | Kitchen staff | E5-S6 |
| [UAT-045](UAT-045-record-a-vendor-invoice.md) · [#108](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/108) | Record a vendor's bill | Kitchen staff | E5-S8 |
| [UAT-046](UAT-046-pay-a-vendor-invoice.md) · [#109](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/109) | Pay a vendor's bill | Temple admin | E7-S8 |

### Part 7 — People and seva

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-047](UAT-047-staff-schedule.md) · [#110](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/110) | Staff profiles and the weekly schedule | Temple admin | E6-S1 |
| [UAT-048](UAT-048-post-a-volunteer-shift.md) · [#111](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/111) | Post a volunteer shift | Staff + admin | E6-S2 |
| [UAT-049](UAT-049-volunteer-signs-up.md) · [#112](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/112) | A volunteer signs up for seva | Volunteer | E6-S3 |
| [UAT-050](UAT-050-volunteer-releases-a-spot.md) · [#113](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/113) | A volunteer releases a spot | Volunteer | E6-S4 |
| [UAT-051](UAT-051-the-waitlist.md) · [#114](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/114) | The waitlist promotes automatically | Volunteers | E6-S5 |
| [UAT-052](UAT-052-shift-reminders.md) · [#115](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/115) | Shift reminders | Volunteer | E6-S6 |
| [UAT-053](UAT-053-broadcast-an-update.md) · [#116](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/116) | Broadcast an update to a shift | Staff + admin | E6-S7 |

### Part 8 — Donations

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-055](UAT-055-give-once.md) · [#118](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/118) | Give once: with your name, or with 80G | Donor | E7-S2, E7-S4, E7-S9 |
| [UAT-056](UAT-056-monthly-giving.md) · [#119](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/119) | Monthly giving | Donor | E7-S3 |
| [UAT-057](UAT-057-manage-the-wish-list.md) · [#120](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/120) | Manage the wish list | Temple admin | E7-S5 |
| [UAT-058](UAT-058-sponsor-a-wish-list-item.md) · [#121](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/121) | Sponsor a wish-list item | Donor | E7-S6 |
| [UAT-059](UAT-059-the-donations-ledger.md) · [#122](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/122) | The donations ledger and export | Temple admin | E7-S7 |

### Part 9 — Across the whole product

| # | Test | Roles | Technical stories |
|---|---|---|---|
| [UAT-060](UAT-060-errors-speak-plainly.md) · [#123](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/123) | Errors speak plainly and carry a code | All | Cross-cutting (all epics) |
| [UAT-061](UAT-061-it-works-on-a-phone.md) · [#124](https://github.com/rajeevkatamaneni/iskcon-kitchen-management/issues/124) | It works on a phone | All | E2-S7, E7-S2, E7-S6 |
| [UAT-062](UAT-062-today-the-morning-screen.md) | Today — the temple's morning screen | Temple admin, Kitchen staff | E4-S8, E4-S7, E3-S3, E6-S2, E7-S7 |
| [UAT-063](UAT-063-sign-out-and-being-signed-out.md) | Signing out, and being signed out | All | E1-S16, E1-S2, E1-S4 |
| [UAT-064](UAT-064-hire-and-let-go.md) | Promote someone, and let someone go | Temple admin | E6-S8 |
| [UAT-065](UAT-065-what-a-devotee-hears.md) | What a devotee hears, and what they can switch off | Volunteer, Temple admin | E8-S1 |
| [UAT-066](UAT-066-write-to-the-community.md) | Write to the community | Temple admin | E8-S2, E8-S3 |

---

*Written against the coding stories in `docs/stories/`, the locked documents in `docs/`, and the code
as deployed. Two-way traceability, and the known coverage gaps, are in [TRACEABILITY.md](TRACEABILITY.md).*
