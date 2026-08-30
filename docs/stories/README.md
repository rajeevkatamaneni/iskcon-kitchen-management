# Stage 4 — Epics & User Stories (Index)

**Status:** DRAFT — refined during implementation, as `docs/CHANGELOG.md` records. Written 2026-08-03 against REQUIREMENTS.md v1.0, SYSTEM_DESIGN.md v1.0, TECH_STACK.md v1.0; last extended 2026-08-20 against REQUIREMENTS.md v1.1.
**GitHub import:** pending resolution of API access. Each story maps 1:1 to a GitHub issue — title = story heading, body = story content, labels as noted per epic + `story`, epics as milestones (or a `type:epic` issue linked per story). Import is mechanical once access exists.

## Epics

Counts are the stories in each file today, not the counts this document was written with — the set
has grown as building one story revealed a gap in an adjacent one.

| Epic | File | Stories | Depends on |
|---|---|---|---|
| 1 — Platform Foundation | `EPIC-1-platform-foundation.md` | 17 | — |
| 2 — Recipe Management | `EPIC-2-recipe-management.md` | 8 | E1 |
| 3 — Inventory Management | `EPIC-3-inventory-management.md` | 8 | E1, E2 (+E5-S1 prices, E3-S8) |
| 4 — Meal Planning & Calendar | `EPIC-4-meal-planning-calendar.md` | 14 | E1–E3 |
| 5 — Ordering & Vendors | `EPIC-5-ordering-vendors.md` | 8 | E1–E4 |
| 6 — Workforce Management | `EPIC-6-workforce-management.md` | 14 | E1 |
| 7 — Payments & Donations | `EPIC-7-payments-donations.md` | 10 | E1 (+E5-S8, E3-S5 feeds) |
| 8 — Devotee Communications | `EPIC-8-devotee-communications.md` | 4 | E1 |
| 9 — What Crosses Between Temples | `EPIC-9-cross-temple-notices-DESIGN.md` | 2 | E1, E6-S8 |
| 2 (cont.) — The shared recipe library | `EPIC-2-recipe-library-DESIGN.md` | 9 | E2 |
| **Total** | | **94** | |

**`EPIC-2-recipe-library-DESIGN.md` is a design, not yet built.** A platform-wide catalogue of 5,376
recipes every temple can search and copy from, and the widening of yield and portion that made it
possible. Its nine stories are E2-S9 to E2-S17; every design question it turned on was answered on
2026-08-21 and each answer is recorded in §10 with the argument that produced it, including the two
where Rajeev's reasoning overturned the recommendation. **The source data is vendored** at
`backend/src/main/resources/recipe-library/`. E2-S16 and E2-S17 fix live defects in shipped code and
do not depend on the library at all.

**Epic 9 is the exception to every rule in this set.** It holds the only two features that
deliberately cross tenant isolation, and its file keeps the design and the six questions it turned
on alongside the two stories built from them. Read it before touching either. (Its filename still
says DESIGN; it is left alone because the 2026-08-20 build brief and BL-6 both name that path.)

## Suggested implementation order

1. **E1 fully** (foundation; E1-S10's Meta WhatsApp verification has external lead time — start its checklist immediately, it runs in parallel).
2. **E2 → E3 → E4 → E5** (the kitchen value chain, in dependency order).
3. **E6 anytime after E1** (independent — good "second track" when a kitchen-chain story blocks on review).
4. **E7-S9 → E7-S2/S4 → rest of E7** (webhook infra first; donations are how release 1 asks a devotee for support, so schedule before pilot launch. E7-S1, the public donation page, was withdrawn on 2026-08-29 — giving is signed-in only).
5. **E9 last, and E9-S1 before E9-S2** (the notice board depends on nothing else and is useful the day it lands; the ban record depends on E6-S8's employment record and on the PAN machinery).

## Conventions used in the stories

- Every story: user-story statement, explicit assumptions (per Commandment 4 — assumptions are decisions the user can veto at review), requirements, checkbox acceptance criteria.
- Where a story makes a judgment call not already locked in the v1.0 docs, the assumption block says so and why (e.g. E4-S6 warn-vs-block on Ekadashi, E6-S5 no accept/decline handshake, E7-S3 recurring requires account). **These are the review hotspots.**
- Real temple artifacts are load-bearing: E2 models RM 2019_v2.xlsx's yield scaling; E4 seeds the ICC workbook's 17 named festivals; E5-S6's example mirrors the locked partial-delivery scenario.
- Phase 2 groundwork is deliberate but minimal: movement reason categories (waste report), rejection reasons per vendor (scorecard), release tracking (reliability), 10BD-shaped donation data (80G export).
- **A story written after the work is a record, not a brief**, and reads like one: status, what it replaced, the decisions with the rejected alternative named, and acceptance criteria already ticked. The 2026-08-20 build's stories (E3-S8, E4-S10 to S14, E6-S10 to S14, E7-S10, E9-S1, E9-S2) are all of this kind, and `BUILD-BRIEF-2026-08-20.md` is the specification they were written from.
- **Where a later story overturns an earlier one, the earlier one says so in place** rather than being edited into agreement — see E4-S4, E4-S8, E6-S1 and E6-S8. A decision that was reversed is more useful than one that appears never to have been made.
