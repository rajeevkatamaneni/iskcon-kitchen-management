# Stage 4 — Epics & User Stories (Index)

**Status:** DRAFT — pending user review. Written 2026-08-03 against REQUIREMENTS.md v1.0, SYSTEM_DESIGN.md v1.0, TECH_STACK.md v1.0.
**GitHub import:** pending resolution of API access. Each story maps 1:1 to a GitHub issue — title = story heading, body = story content, labels as noted per epic + `story`, epics as milestones (or a `type:epic` issue linked per story). Import is mechanical once access exists.

## Epics

| Epic | File | Stories | Depends on |
|---|---|---|---|
| 1 — Platform Foundation | `EPIC-1-platform-foundation.md` | 11 | — |
| 2 — Recipe Management | `EPIC-2-recipe-management.md` | 7 | E1 |
| 3 — Inventory Management | `EPIC-3-inventory-management.md` | 7 | E1, E2 |
| 4 — Meal Planning & Calendar | `EPIC-4-meal-planning-calendar.md` | 6 | E1–E3 |
| 5 — Ordering & Vendors | `EPIC-5-ordering-vendors.md` | 8 | E1–E4 |
| 6 — Workforce Management | `EPIC-6-workforce-management.md` | 7 | E1 |
| 7 — Payments & Donations | `EPIC-7-payments-donations.md` | 9 | E1 (+E5-S8, E3-S5 feeds) |
| 8 — Devotee Communications | `EPIC-8-devotee-communications.md` | 4 | E1 |
| **Total** | | **55** | |

## Suggested implementation order

1. **E1 fully** (foundation; E1-S10's Meta WhatsApp verification has external lead time — start its checklist immediately, it runs in parallel).
2. **E2 → E3 → E4 → E5** (the kitchen value chain, in dependency order).
3. **E6 anytime after E1** (independent — good "second track" when a kitchen-chain story blocks on review).
4. **E7-S9 → E7-S1/S2/S4 → rest of E7** (webhook infra first; donations are release 1's public face, schedule before pilot launch).

## Conventions used in the stories

- Every story: user-story statement, explicit assumptions (per Commandment 4 — assumptions are decisions the user can veto at review), requirements, checkbox acceptance criteria.
- Where a story makes a judgment call not already locked in the v1.0 docs, the assumption block says so and why (e.g. E4-S6 warn-vs-block on Ekadashi, E6-S5 no accept/decline handshake, E7-S3 recurring requires account). **These are the review hotspots.**
- Real temple artifacts are load-bearing: E2 models RM 2019_v2.xlsx's yield scaling; E4 seeds the ICC workbook's 17 named festivals; E5-S6's example mirrors the locked partial-delivery scenario.
- Phase 2 groundwork is deliberate but minimal: movement reason categories (waste report), rejection reasons per vendor (scorecard), release tracking (reliability), 10BD-shaped donation data (80G export).
