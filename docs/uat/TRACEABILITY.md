# Traceability — technical stories ↔ UAT tests

Why this file exists: a defect found in UAT should point straight back at the story that produced it,
so we can ask *why* it went wrong and not merely fix it. It also proves nothing is silently untested.

Each UAT test is mirrored to a GitHub issue labelled `uat` — **#64 (UAT-001) … #124 (UAT-061)**, in
order, so the issue number is always 63 + the test number.

Root-cause codes used throughout the pack (defined in [README](README.md) §7):
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
| E1-S5 | Role-based access control | UAT-001, **UAT-005**, UAT-006. *UAT-005 step 5 was written before T-031 took My shifts away from kitchen staff, so it now expects the wrong menu* |
| E1-S6 | Tenant provisioning | **UAT-002**, UAT-003, UAT-007 |
| E1-S7 | Audit log framework | UAT-009, **UAT-011**, UAT-014, UAT-025 |
| E1-S8 | Contact channels and communication preference | **UAT-010** |
| E1-S9 | Background job infrastructure | UAT-004 (scheduler health), **UAT-082** (a deleted temple's work stops); its effects in UAT-019, 023, 029, 052 |
| E1-S10 | Notification service | Delivery is exercised by UAT-023, 028, 043, 047, **UAT-052**, 053, 055 |
| E1-S11 | Observability baseline | **UAT-004** |
| E1-S12 | Temple user management | **UAT-008**, UAT-009, UAT-005 |
| E1-S13 | Platform super-admin bootstrap | UAT-001, UAT-007 |
| E1-S14 | Platform-level audit log | *No operator screen was built — deferred inside the story itself. See gap G9* |
| E1-S15 | Temple detail, data export, permanent deletion | **UAT-003**; D13 (deletion takes the schedule with it) in **UAT-082** |
| E1-S16 | Signing out, and idle sign-out | **UAT-063** |
| E1-S17 | Registering yourself at a temple | **UAT-008**, UAT-012 |
| E4-S8 | Today — the temple's morning screen | **UAT-062**. *The script predates E4-S14's rewrite of this screen and the D-27 meal rebuild* |
| E2-S1 | Ingredient master | **UAT-013**; ~~UAT-014~~ *(withdrawn 2026-09-08 with the sattvic flag — D-18. See gap G12: nothing now tests that only a Temple Admin may change the surviving **Ekadashi** flag.)* |
| E2-S2 | Recipe CRUD | **UAT-015**, UAT-016 |
| E2-S3 | Recipe scaling | **UAT-017** |
| ~~E2-S4~~ | ~~Sattvic enforcement~~ — **withdrawn 2026-09-08 (D-18)**, the sattvic flag, its hard block and its admin override were deleted from the product; **Ekadashi is now the only dietary restriction the product enforces** | *No test — ~~UAT-014~~ and ~~UAT-018~~ are withdrawn with it. Both files are kept, marked, as a record of what was tested* |
| E2-S5 | Recipe PDF and print | **UAT-019** |
| E2-S6 | Recipe translation and glossary | **UAT-020**, UAT-021 |
| E2-S7 | Recipe browse and search | **UAT-016** |
| E2-S8 | Removing a recipe: delete one never cooked, archive and restore one that was | *Automated only — `RecipeIT` and `recipe-detail.test.tsx`. The story itself says "UAT to be written"; none has been* |
| E2-S9 | The shared recipe library table and its loader | *Automated only — no manual surface* |
| E2-S10 | Searching the library | *No UAT test.* UAT-016 predates the library and searches the temple's own recipes only |
| E2-S11 | Widening the yield vocabulary | UAT-074 steps 33–34 (a library recipe measured in litres keeps its per-head portion) |
| E2-S12 | Adding a library recipe to a temple | *No UAT test* |
| E2-S13 | The Recipes page rebuilt over both kinds | *No UAT test.* UAT-016 predates it |
| E2-S14 | Full screen for a temple recipe and a library recipe | UAT-074 step 34 opens a library recipe; nothing tests the screen itself |
| E2-S15 | The super-admin's library | *No UAT test* |
| E2-S16 | A head count is not a yield | *No UAT test* |
| E2-S17 | No preparation leaves without a quantity | *No UAT test* |
| E3-S1 | Consumable inventory and stock view | **UAT-022**; the expiry warning horizon in **UAT-080** |
| E3-S2 | Stock movements ledger | **UAT-026** |
| E3-S3 | Reorder thresholds and low-stock alerts | **UAT-023** |
| E3-S4 | Equipment inventory | **UAT-027** |
| E3-S5 | In-kind donation intake | **UAT-028** |
| E3-S6 | Consumption on meal production | **UAT-035**. *The script still presses a per-dish Cook; it predates E4-S10 (recording a meal), T-087 (recording never refuses on stock) and D-27* |
| E3-S7 | Manual stock adjustment | **UAT-024**, UAT-025 |
| E3-S8 | What the day's food is costing (the materials figure on Today) | UAT-075 checks only that its own figures do not change this one. *No test of the figure itself* |
| E3-S9 | What a serving costs, by kind of meal | **UAT-075** |
| E3-S10 | Equipment servicing, and the record of it | **UAT-084**; the screen it is read on in UAT-085 |
| E3-S11 | The equipment screen | **UAT-085**; the rules it shows in UAT-084 |
| E4-S1 | Calendar engine | **UAT-029** |
| E4-S2 | Festival occasion catalogue | **UAT-030** |
| E4-S3 | Admin calendar override | **UAT-031** |
| E4-S4 | Meal plan across four contexts | **UAT-032**, UAT-035; the outside-event half of it now in **UAT-086**. *Both scripts predate the D-27 meal rebuild — see the T-195 row* |
| E4-S7 | The planner redesigned: meal kinds, ready-by times, the day view | **UAT-032** (including the head-count rule and duplicating a week), UAT-086. *UAT-032 predates Reuse a plan and the D-27 meal rebuild* |
| E4-S5 | Ingredient sufficiency and shortfalls | **UAT-034** |
| E4-S6 | Ekadashi violation flagging | **UAT-036** |
| E4-S9 | The Vaishnava calendar as its own screen | *No UAT test.* UAT-029 (2026-08-11) predates the screen |
| E4-S10 | Recording a meal, not a dish | *No UAT test.* UAT-035 still describes the per-dish Cook this replaced |
| E4-S11 | The job card; since made a worksheet the kitchen fills in (`bf506c5`), a pack of four sheets (`6db8fb8`), and stripped of equipment (T-209) | UAT-086 step 10 (an event gets its own card). *No test of the card itself* |
| E4-S12 | Swapping or editing a planned dish | *No UAT test* |
| E4-S13 | What an outside event is for | *Superseded by E4-S15* — **UAT-086** |
| E4-S14 | Today, rewritten around the meal, including what is waiting for you (`fb52eba`) | *No UAT test.* UAT-062 (2026-08-14) predates it |
| E4-S15 | Events, and the end of catering; since 2026-09-19 an event repeats as a series (D8b, replacing D8's copies) | **UAT-086**; the meal-kind picker it changes in UAT-032. Repeating and cancelling a series: **UAT-086 steps 45–70**, rewritten 2026-09-19 (they tested unlinked copies before) |
| E4-S16 | Travel time for a delivered event | **UAT-086** §travel; the van-loading warning, reworded 2026-09-19, in steps 81–86 |
| E5-S1 | Vendor management | **UAT-037**; D2, the contract-end horizon as a temple setting, in **UAT-080**; supplies, list prices and onboarding (R-VEN-1 to 4) in **UAT-089** and UAT-088 — see §1a |
| E5-S2 | Auto-generated shopping list | **UAT-038** (rewritten 2026-09-19 for R-SL-1 to 4), UAT-039 |
| E5-S3 | Purchase order generation and lifecycle | **UAT-039** (rewritten 2026-09-19: the tile and the create form, R-PO-1 to 3), **UAT-040** (rewritten: the order page, R-PO-4); D1–D5, the needed-by date, in **UAT-083** |
| E5-S4 | PO document: PDF and print | **UAT-041** (amended 2026-09-19: packs, rates, dates); the real PDF on staging in UAT-092 |
| E5-S5 | PO translation | **UAT-042** |
| E5-S6 | Receiving | **UAT-044** (rewritten 2026-09-19 as the Deliveries screen, R-DEL-1 to 5); returns in UAT-040 steps 15–18 |
| E5-S7 | WhatsApp PO delivery | **UAT-043** (amended 2026-09-19); on staging with the PDF in UAT-092 step 6 |
| E5-S8 | Vendor invoice capture | **UAT-045** (rewritten 2026-09-19 for R-INV-1 to 6) |
| E5-S9 | Vendor performance | **UAT-077**; D6, an order with no needed-by date, also in UAT-083. *UAT-077 still says on-time is scored per order; T-124 made it per item* |
| E6-S1 | Staff profiles and weekly schedule | **UAT-047**. *Its step 16 asks whether staff can see their own schedule; T-006 built that answer after the script was written* |
| E6-S2 | Volunteer shift posting | **UAT-048** |
| E6-S3 | Volunteer signup | **UAT-049** |
| E6-S4 | Signup release | **UAT-050** |
| E6-S5 | Waitlist with auto-promotion | **UAT-051** |
| E6-S6 | Scheduled shift reminders | **UAT-052** |
| E6-S7 | One-off reminder broadcast | **UAT-053** |
| E6-S8 | Hiring, employment records, letting go | **UAT-064**, UAT-008 |
| E6-S9 | Is this Aadhaar card real? | *Specified, not built — needs a real Aadhaar QR to verify against* |
| E6-S10 | Leave: time off, sick and unpaid | *No UAT test* |
| E6-S11 | The week grid, edited where it is read | UAT-078 works on the grid as it now is. *UAT-047 (2026-08-19) still describes the per-date exceptions this replaced* |
| E6-S12 | A fifth role: Kitchen Manager | UAT-064 (the hire form offers it), **UAT-069**, UAT-070 step 25 |
| E6-S13 | Staff pay: salary, payments, advances and docking | *No UAT test* |
| E6-S14 | Workforce: how much of a kitchen there is today | *No UAT test* |
| E6-S15 | Where the schedule is short of hands | **UAT-078**, and the grid it changes in UAT-047 |
| E6-S16 | Conduct notes on an employment record | **UAT-079** |
| E8-S1 | Communication categories and devotee preferences | **UAT-065** |
| E8-S2 | Compose, preview, and test | **UAT-066** |
| E8-S3 | Send it, and know it went | **UAT-066** |
| E8-S4 | A temple's message on WhatsApp | *Not built — blocked on WhatsApp credentials and Meta approving a MARKETING template* |
| ~~E7-S1~~ | ~~Public temple donation page~~ — **withdrawn 2026-08-29** with unauthenticated giving; UAT-054 was deleted with it | *No test — the story no longer exists* |
| E7-S2 | One-time donation | **UAT-055** |
| ~~E7-S3~~ | ~~Recurring donation~~ — **withdrawn 2026-09-10**; recurring donations left Phase 1 entirely and are now a Phase 2 Backlog item (REQUIREMENTS.md §4). The `recurring_plans` table, the plan endpoints and the donor-facing recurring screen are removed; **Phase 1 giving is one-time only** | *No test — ~~UAT-056~~ is withdrawn with it. The file is kept, marked, as a record of what was scoped and deferred* |
| E7-S4 | 80G donor data capture | **UAT-055** |
| E7-S5 | Wish list management | **UAT-057** |
| E7-S6 | Wish list and sponsorship | **UAT-058** |
| E7-S7 | Donations ledger | **UAT-059** — *amended 2026-09-10, not withdrawn: the ledger aggregates three kinds of gift, not four, and step 6 (the Recurring filter) is struck out* |
| E7-S8 | Vendor invoice payment recording | **UAT-046** (rewritten 2026-09-19: paid from the invoice, with proof; R-INV-7, R-INV-8, R-PAY-1 to 4) |
| E7-S9 | Payment webhook infrastructure | UAT-055 (replay/idempotency), UAT-058 |
| E7-S10 | The donations ledger by period, against the same point last year | *No UAT test.* UAT-059 has no step for periods or the comparison |
| E9-S1 | A notice board that spans the platform | *No UAT test* |
| E9-S2 | The record raised at a dismissal, and the check run at a hire | *No UAT test.* UAT-064's dismissal steps do not raise the record or run the check |
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
| E10-S13 | What the store issued to each kitchen, costed | **UAT-076**, reading back the issuing UAT-070 records |
| E11-S1 | `to_base_qty()` replaces the seven hand-written CASE fragments | *Automated only — `BaseQuantityIT`. No manual surface; the story changes no behaviour* |
| E11-S2 | One unit vocabulary; `YieldUnit` retired; `LITRES → L` | **UAT-074** (steps 33–34, 42–51); unit-family validation, closing BACKLOG **BL-9**, in **UAT-081** |
| E11-S3 | One way to say a quantity, with the rounding ladder and the cook's/ledger split | **UAT-074** (steps 1–27) |
| E11-S4 | Every screen says it the same way | **UAT-074** (steps 28–34) |
| E11-S5 | Documents and emails say it the same way | **UAT-074** (steps 35–41) |
| E11-S6 | Every dropdown offers the one list | **UAT-074** (steps 42–51) |
| E12-S1 | The kitchen on every dish: a section per kitchen on each meal (`meal_kitchens`), dishes and staff given a kitchen, the per-temple backfill to the main kitchen, the provisioning seed, the reset keep-list | **UAT-093** steps 1–2 (every staff member has a kitchen after the backfill), 26 (each dish under one kitchen). The migration itself automated only (`WhichKitchenMigrationIT`). *Built 2026-09-19, not deployed* |
| E12-S2 | The planner asks which kitchen, and refuses without an answer; reuse and series carry the kitchens | **UAT-093** steps 14–25. `KMS-400180`, `400181` and `400182` cannot be reached from the screens: automated only. *Built 2026-09-19, not deployed* |
| E12-S3 | The composer: a section per kitchen, **+ Add another kitchen**, × with an inline confirm, People needed per kitchen, own kitchen first | **UAT-093** steps 14–25, 37–39. *Built 2026-09-19, not deployed* |
| E12-S4 | Every surface says whose meal it is: one card with a section per kitchen, per-kitchen crew, the week tile, Today | **UAT-093** steps 26–30, 36, 39. *Built 2026-09-19, not deployed* |
| E12-S5 | One job card per kitchen, one card number per meal | **UAT-093** steps 31–34. `KMS-400186` and `400187` automated only. *Built 2026-09-19, not deployed* |
| E12 staff kitchen | Every staff member belongs to one kitchen, required on add and edit; the Temple Admin's **Check these kitchen assignments** list; a kitchen with staff cannot be archived (added to Epic 12 by Rajeev 2026-09-19) | **UAT-093** steps 1–13 (`KMS-400184`, `KMS-400185`), 48. *Built 2026-09-19, not deployed* |
| E12 planner access | Only the Temple Admin, and people whose kitchen plans its meals here, can open the meal planner; hidden from the menu and refused by the server (`KMS-400183`) (added to Epic 12 by Rajeev 2026-09-19) | **UAT-093** steps 35, 40–48. *Built 2026-09-19, not deployed* |
| Brief 2026-08-21, groups 1, 2, 7 (`42982bb`, `b84dcd0`, `0a7d6c9`) | The kit every form and table is built from, the focus screen, and one voice for the site's words | UAT-073 uses the focus-screen add. *No test of the kit as such* |
| Brief 2026-08-21, group 3 (`6839960`) | The staff register, and a record for people who have left (E6-S8) | **UAT-064** step 17 (Former staff) |
| Brief 2026-08-21, groups 4, 5 (`858b867`, `6df10ca`) | How many hands a meal takes; the planner plans meals, moves through time, and can name a feast (E4-S7) | **UAT-032**, UAT-078 |
| Review of 2026-08-23 (`d192168`, `21eef91`, `b9af9e7`, `111e85f`, `a40da63`, `f395989`, `55ba323`) | What the deadline reached of Rajeev's review list: inventory on one page that asks what is on the shelf, a date span that cannot run backwards, the sign-in picture, a recipe on its own screen that remembers the search, yield and per-head portion shown | UAT-022 (amended 2026-08-30) and UAT-073 (written 2026-08-30) cover the inventory add. The rest are tracked in `docs/OUTSTANDING_BUILD_LIST.md`, *not by UAT scripts* |
| Themes (`56d369c` to `f6654fc`, `0639c75`, `4926054`, `691b07f`, `074e153`, `d714fce`, `121d545`) | A temple chooses its own colours from fifteen theme packs, with depth, lift and materials (DESIGN_SYSTEM v1.5, v1.6) | *No UAT test* |
| `9e39bac`, `cc28eb0` | The sub-text under a form control moves into an "i" beside its label | *No UAT test* |
| `433407e`, `660a6cb`, `ab801c0`, `743b4ff` | Reuse a plan (copy any stretch of days); planning and correcting a meal are one screen; the planner marks the current period; one month grid (E4-S7) | *No UAT step.* UAT-032 steps 32–35 test *Duplicate last week*, written before Reuse a plan |
| `610ee60` | A purchase order carries all three of its dates everywhere it appears (E5-S3) | UAT-083 covers the needed-by date only |
| `64a9cce`, `89777ec` | Every date written in the temple's own clock, one clock per temple | *No UAT test* |
| `701e582` | The Kannada word-order reversal fixed in our own catalogue; the language picker opens quiet (E2-S6, E10-S11) | *No UAT step.* UAT-020 and UAT-071 translate, but neither checks Kannada word order |
| `5f3d177`, `5f06b2f`, `4afcac0`, `5a3d7f5`, `057e270` | A Settings screen for the number an error sends people to; recipe lists ordered state then name; the library browse error; one message per dot on the Operations pulse; the wish list counts money only | *No UAT step* |
| `229a36a`, `7493f0e`, `fa7d07b`, `b38e920`, `9f3af90` | The deploy pipeline (25 minutes to 5m49s), a CI guard against ignored source files, and the agent roles | *Automated / deployment — no manual surface* |
| T-000 | Every error code renumbered to six digits | Every script's codes were renumbered with it (`ad509f7`); **UAT-060** reads them |
| T-003, T-021, T-095, T-098, T-105, T-116, T-216 | Refusals speak plainly: a 401 says which 401 it is; a malformed phone is `KMS-400003`; a next step never names a door its reader cannot open; field errors are written for a person; an unreadable body names its field; the words written for a refusal reach the person; a required query value left out is `KMS-400001` naming the field (T-216) | UAT-060 covers the principle; *no step exercises these cases.* T-095, T-098, T-116 and T-216 are held by automated guards |
| T-002, T-035 | Four screens stop offering what the server refuses; a refused page keeps the menu and a way out | *No UAT step.* UAT-005 predates both |
| T-029 | Continue with Google always asks which Google account (E1-S4) | *No UAT step.* UAT-012 and UAT-063 predate it |
| T-037, T-114, T-118 | Registration remembers the credential it made, stops giving one answer to two different people, and keeps the temple somebody chose (E1-S17) | *No UAT step.* UAT-008 predates all three |
| T-030, T-031 | Giving is for volunteers only; My shifts is the volunteer's seva board and staff no longer get it (D-8, D-10, D-16) | *No UAT step.* UAT-005 step 5 now expects the wrong menu for kitchen staff |
| T-006, T-032 | A cook, a manager and an admin each see their own rostered days, with the leave they were given (E6-S1, E6-S10) | **UAT-047 step 16** asks the question this answers; *no step for the leave on it* |
| T-039, T-040, T-046 | A refused attempt to change your own access or end your own employment is recorded; the unused role-change endpoint is deleted (E1-S12, E6-S8) | UAT-064 checks you cannot end your own employment. *No step reads the audit entry* |
| T-014 | Reinstating someone whose employment was ended (E6-S8) | *No UAT test* |
| T-184, T-194 | Staff withdraw their own leave before it begins and their manager is told; leave rows read "3 to 4 September" (E6-S10) | *No UAT test* — E6-S10 has none |
| T-190, T-191 | A person's own accounts at other temples stop showing in this temple; an unused lookup removed (E1-S12) | *No UAT step.* UAT-006 covers one temple against another, not this case. T-191 automated only |
| T-192, T-193 | The platform audit log refuses an entry that names someone other than its author (E1-S14) | *Automated only — no manual surface (gap G9)* |
| T-008, T-041 | A temple's profile can be corrected and 80G approval recorded; the edit narrows to name, address and 80G (D-17) (E1-S15) | *No UAT step.* UAT-003 reads the profile but predates editing |
| T-042, T-044, T-048, T-053, T-054, T-059, T-176 | Places: provisioning picks the temple from Google Places, OpenStreetMap removed, a coordinate cut to six decimals, 0,0 refused; editing a delivery event no longer re-pins it to 0,0, and the events already pinned there are unpinned (D-17, D-19) (E1-S6, E4-S16) | *No UAT step.* UAT-002 predates Places; UAT-086 §travel predates the fixes |
| T-052, T-057, T-115, T-117 | Terraform describes the variables the services carry, and the example file is checked against them (E1-S2) | *Automated / deployment — no manual surface* |
| T-004 | A Temple Admin screen to add, rename and remove the temple's own festival occasions (E4-S2) | **UAT-030 step 7**, written for "if a screen exists" |
| T-005, T-038, T-047 | The meal-kinds screen; renaming a kind carries its meals with it; deleting one in use is refused; a duplicate name is refused plainly (E4-S7) | *No UAT step.* UAT-032 uses meal kinds but never renames or deletes one |
| T-049 | The planner's Today control (E4-S7) | UAT-032 steps 1–2 |
| T-208 | A new meal on a festival day opens with the occasion's default servings as Adults (E4-S2, E4-S7) | UAT-030 step 7 asks whether the default is visible when planning. *No step checks the prefill* |
| T-050, T-051, T-055, T-056 | The sattvic flag, its seed and its controls deleted; the documents withdrawn in place; its permission renamed (D-18, D-20, D-21) | **UAT-013**, UAT-002, UAT-007, UAT-011, UAT-015, UAT-016, UAT-038, UAT-073 amended with it (`02b0b88`); UAT-014 and UAT-018 withdrawn. T-056 automated only |
| T-045, T-119, T-121 | Ekadashi-prohibited set from the list and the create form; ingredients a recipe import created are marked, filterable and counted (E2-S1, E2-S12) | UAT-013 says the flag is set by hand. *No step for the import marking; who may set the flag is gap G12* |
| T-001, T-036 | A stock movement can be corrected and an item can stop being tracked; the dialog reads the unit and month back (E3-S2, E3-S7) | *No UAT step.* UAT-024 and UAT-026 predate it |
| T-023, T-024, T-089 | Supplies are a flag on the ingredient catalogue with a menu item of their own; a purchase-order line can name something not in the catalogue (D-1) | *No UAT test* |
| T-086, T-087, T-122 | Inventory shows on hand, committed and available; recording a meal never refuses on stock; on hand stops at zero and the shortfall is recorded (E3-S1, E3-S6) | *No UAT step.* UAT-022 and UAT-035 predate them |
| T-009, T-033, T-120 | An equipment record can be edited; scrapping asks first and can be undone once, by name, with a reason; a machine can say it never needs servicing (D-15) (E3-S4, E3-S10, E3-S11) | *No UAT step.* UAT-027, UAT-084 and UAT-085 predate them |
| T-150, T-154 | Stock adjustment, gift of goods and ingredient request refuse a unit the ingredient cannot be measured in, and name the ingredient (E11-S2) | **UAT-081** for the adjustment. *No step for the gift or the request* |
| T-108 (with T-077, T-143), T-125, T-127, T-128, T-144, T-148, T-179b | "1 piece" not "1 pieces" on screens and printed sheets; "0 L" on both; the never-serviced tick on the registration form; the language picker; movement labels, donation screens and an invoice fix found by driving (T-125); the backend copies of the unit rule removed (E11-S3 to E11-S5) | UAT-074 covers how quantities read. *No step for these cases* |
| T-007, T-043, T-083, T-088 | A recorded meal can be corrected, reversing every dish before any is re-drawn; an event meal can be recorded again and a refusal is shown; the planner badge belongs to the meal (E4-S10, E3-S6) | *No UAT test* — E4-S10 has none |
| T-019, T-034, T-147, T-155, T-158 | A shift says which meal it is for; the planner asks for volunteers through the real shift form; editing a shift keeps its meal; the crew breakdown moves into an "i" (D-14) (E6-S2, E6-S15) | *No UAT step.* UAT-048 and UAT-078 predate them, and D-27 has since changed the link |
| T-146 | A shift can run past midnight (E6-S2) | *No UAT test* |
| T-016, T-079, T-080, T-085, T-099, T-106, T-149, T-175 | A roster records who turned up and can take a named volunteer off; a mark can be corrected, only after the shift, only by a Temple Admin or Kitchen Manager, and the roster shows who changed it; a volunteer taken off is told and sees it on My shifts (E6-S2, E6-S4) | *No UAT test* |
| T-015, T-084, T-094, T-096, T-097 (with T-063), T-100, T-102, T-104 | A sent message can be sent again to the addresses it failed for; a send that reached nobody says so; a refused retry is explained; a letter is sent once (E8-S3, E6-S7) | UAT-066 step 21 reads each recipient's status. *No step for sending again.* T-096, T-097, T-102, T-104 automated only |
| T-151, T-159, T-168, T-169 (T-169a, T-169b), T-173, T-177, T-178, T-180, T-185, T-188 | WhatsApp settings and templates: the Test button sends a real message to a number; refused templates reworded and refusals stored truthfully; templates go to Meta only when something changed; Settings and Language open read-only with Edit, Cancel and Save; Super Admins read every template and Meta's status per temple (E1-S10) | *No UAT test.* No script covers WhatsApp settings or templates |
| T-200 (with T-182) | The purchase order PDF goes with the vendor's WhatsApp message; Settings → WhatsApp gains an App ID box (E5-S7) | UAT-043 step 4 expects the sheet "as a document or a link". *No step for the App ID box* |
| T-202 | Save and preview with a blank subject is refused in red (E8-S2) | *No UAT step* |
| T-157, T-186, T-187 | A phone typed with spaces is accepted; a counter gift's phone is saved as +91; a donor's old and new phone forms group together (E1-S8, E7-S7) | *No UAT test* |
| T-156, T-160, T-161, T-162, T-163, T-164, T-165, T-166, T-167, T-170, T-171, T-172, T-174, T-201, T-203 | Every form names a refused box in red, by its label; a button no longer submits by accident; a "(required)" box is really required; buttons greyed by a blank box now press; "Why" becomes "Reason" on the close-order and change-condition forms; a reason of only spaces or a credit of 0 is refused in red | UAT-060 covers errors in general. *No step for the red naming.* T-167 is an automated guard |
| T-204 | The Give page's other-amount box has no spinner arrows (E7-S2) | *No UAT step* |
| T-025, T-060, T-061 | A vendor needs no phone and a WhatsApp send without one is refused; a described line stops dragging fill rate down; the operator's temple list shows the true number of people (E5-S1, E5-S9) | *No UAT step.* UAT-037 predates the optional phone |
| T-026, T-027, T-028, T-132 (with T-133), T-134, T-135 (with T-136), T-139, T-140, T-141, T-153 | Ordering: an order raised by hand, vendor first; a shopping-list line added by hand; editing a line keeps its vendor; the shopping list worked out on every read; a tile per vendor with the order written in a panel; the order screen's buttons, with WhatsApp only where it works; the generate endpoint retired (D-7, D-24) (E5-S2, E5-S3) | **UAT-039**, UAT-040, UAT-077, UAT-081, UAT-083 amended for T-134 and T-153 (`dd0d765`); UAT-039 step 10 asks for an order by hand. *No step for a hand-added shopping-list line.* T-139 to T-141 automated only |
| T-090, T-130, T-131, T-137 | A vendor's lead time is recorded per supply, is one promise enforced on Mark sent, and a shortage says the day it must be ordered (D-25) (E5-S1, E5-S2) | *No UAT test* |
| T-013, T-066, T-103, T-107, T-124, T-126, T-129, T-142 | Goods can go back to a vendor; described lines can be acknowledged so an order can close; on-time scored per item; a cancelled, sent order can name the vendor who never came; closing a part-delivered order releases the rest, and the score is shown but never edited (D-26) (E5-S6, E5-S9) | *No UAT step.* UAT-077 still says on-time is per order |
| T-082 | The invoice form picks the vendor, then that vendor's own open order (E5-S8) | Superseded by R-INV-3: the form picks the vendor, then its unbilled deliveries — **UAT-045** steps 3–4, 14 |
| T-010, T-071, T-206, T-207 | A bill can be voided or credited and a payment reversed; a credit note settles its variance; a bill with unreversed payments cannot be voided; counter cash and voided bills stop showing as mismatches (E5-S8, E7-S8) | **UAT-046** steps 25–29 (void refused with a live payment, reverse, void, credit note). *Counter-cash mismatches: no step* |
| T-012, T-068, T-069, T-070 (with T-072), T-081, T-205, `0337924` | A hand-recorded donation can be voided with its stock reversed; struck bills and struck gifts stop counting; a wish-list gift that no longer fits is split; voiding the gift behind a fulfilled wish reopens it; a draft order nobody sent is not money spent (E3-S5, E7-S5, E7-S6) | *No UAT test* |
| T-110 (with T-020, T-073) | A donation opens on its own page with its 80G receipt and the donor's other gifts (E7-S4, E7-S7) | *No UAT test.* UAT-059 predates it |
| T-179 | My donations: a volunteer lists their own gifts and downloads each receipt (E7-S2) | *No UAT test* |
| T-111, T-112, T-113 | Recurring donations leave Phase 1 (D-23) (E7-S3) | **UAT-059** step 2 checks the Recurring filter is gone (`9458969`); UAT-056 withdrawn |
| T-212 | Costing follows what was cooked: a recorded meal is costed at its actuals, the rest at the plan, on Cost per serving and Today, with counts of each (E3-S8, E3-S9) | *No UAT step.* UAT-075 predates it |
| T-195, T-196, T-197, T-198, T-199 | The D-27 meal rebuild: a meal is a row of its own with its volunteer shift saved alongside it; the planner, recording, job cards, Today and Volunteer shifts moved onto it; the old meal data reset (E4-S7, E4-S10, E4-S11, E4-S14, E6-S2) | *No UAT test.* UAT-032, UAT-035, UAT-048, UAT-062, UAT-078 and UAT-086 were written for the old model. T-195's reset automated only |
| T-213, T-214, T-215 | Phase A fixes: every shift form box named by its label; Today names an event meal by its own name; a new, unsaved meal counts who is rostered so Volunteers requested prefills | *No UAT test* |
| T-209 | Equipment taken off the job card, on screen, print and PDF (E4-S11) | *No UAT test* — E4-S11 has none |
| T-307, T-308, T-310 | An event repeats once every 1 to 12 weeks until a date, as a linked series: a live count and the skipped dates before pressing, the series line on every occurrence, and cancel *just this event* or *this and all later ones*; no copy on a past date (E4-S15 D8b) | **UAT-086** steps 45–56 (repeat, preview, skips, `KMS-400175`–`400177`), 57–61 (the copies, the series line, editing one alone), 62–69 (both cancels, the edited one named, volunteers, `KMS-400179`, no question when nothing is later), 70 (kitchen staff). `KMS-400178` and "never a cooked or recorded one" automated only (`MealSeriesIT`) |
| T-245 to T-306, T-310 to T-312, T-343 | The procurement release (R-… in `docs/work/PROCUREMENT-REQUIREMENTS.md`) and its fixes | **§1a** below maps every requirement; UAT-038 to UAT-046 rewritten or amended, UAT-087 to UAT-092 new |
| T-350 | V150: a section per kitchen on each meal, the dish and staff kitchen columns, the per-temple backfill to the main kitchen, the viewer's section order, the provisioning seed (E12-S1) | **UAT-093** steps 1–2, 26, 36, 39. The migration automated only (`WhichKitchenMigrationIT`, `KitchenOrderTest`) |
| T-351 | The composer: **What each kitchen cooks**, a section per kitchen, **+ Add another kitchen**, × with *Remove … and its N dishes?*, People needed per kitchen (E12-S3) | **UAT-093** steps 14–25, 37–38 |
| T-352 | The meal's card with a section per kitchen, each with its own People needed, rostered names and **Download job card**; the week tile grouped by kitchen; Today names the kitchens (E12-S4, E12-S5) | **UAT-093** steps 26–34, 36 |
| T-353 | Staff belong to a kitchen; **Check these kitchen assignments**; the planner hidden from the menu and refused at its address (E12 staff kitchen, E12 planner access) | **UAT-093** steps 1–11, 13, 35, 40–48 |
| T-354 | Meals saved and read by kitchen, reuse and series carry the kitchens, V151 moves People needed to each kitchen, `KMS-400180`–`400182` (E12-S2) | **UAT-093** steps 14–26. The three codes automated only |
| T-356 | One job card per kitchen, V152 moves the card's version to each kitchen, `KMS-400186`–`400187` (E12-S5) | **UAT-093** steps 31–34. The two codes automated only |
| T-357 | The staff kitchen API, the check list, the planner access rule and its sign-in fields, a kitchen with staff cannot be archived, `KMS-400183`–`400185` (E12 staff kitchen, E12 planner access) | **UAT-093** steps 1–13, 40–48 |
| T-358 | People needed and rostered staff per kitchen, volunteers counted once in the main kitchen's section; Today's kitchen names (E12-S4) | **UAT-093** steps 15, 21, 27, 29–30 |
| T-355 | *No entry in the Epic 12 dispatch. Not used by this stream as far as the dispatch records* | *Nothing to cover* |
| T-022, T-062, T-064 (with T-065), T-075, T-076, T-078, T-092, T-093, T-145, T-152, T-189, T-210 | Tests for five screens that had none; CI heap and Spring context counts; flaky tests fixed; the linter, and rules-of-hooks linting; local development migrates as `kms_migration` | *Automated tests only — no manual surface* |
| T-058 | Held cleanups: four fixed, five dropped, three sent back to Rajeev | *No UAT step* |
| T-017, T-018, T-091 | Stopped before building; nothing shipped | *Nothing built — no test* |
| T-011, T-067, T-074, T-101, T-109, T-123, T-138, T-181, T-183, T-211 | Ids with no work of their own: T-067, T-074 and T-123 dropped; T-101 built as T-106; T-109 dissolved by T-124; T-138 folded into T-137; T-011, T-181 and T-183 never used. T-211 is this table | *Nothing to cover* |

Bold marks the test that covers the story most directly. Every story with a user-facing surface is
covered by at least one test; the ones with none are marked as such and were accepted on automated
tests alone, per Commandment 6. **That sentence held to 2026-08-20 and no longer does:** most rows
added since say *No UAT test* or *No UAT step*, which means a surface nobody has scripted, not one
accepted on automated tests.

Complete to 2026-09-14, and for the procurement release of 2026-09-19 (§1a): every story in `docs/stories/`, every task from T-000 to T-216 (Phase B's
T-200 to T-210, T-212 and T-216 were committed in `69da777` and released to staging on 2026-09-14, after Phase A in `74d3535` and its fixes in `6dd436c`), and the work between 2026-08-20 and 2026-09-06 that
had no task id. Commits in that stretch that only fixed a defect in a story already listed have no row
of their own.

Added 2026-09-19: Epic 12's stories and its tasks T-350 to T-358, mapped to UAT-093. They were built in
an unmerged worktree and are not deployed, so the step numbers are written against the build, not
against anything a tester has run.

Work done by task id has no story of its own, and the table's only precedent (a decision added to its
story's row) does not scale to two hundred tasks, so tasks are rows keyed by task id, naming the story
each changes. Work with neither a story nor a task id is keyed by its commit or the brief it came from.

Cross-cutting tests: **UAT-060** (error presentation) and **UAT-061** (phone usability) apply to every
epic and belong to no single story.

---

## 1a. The procurement release (2026-09-19) — every requirement and what covers it

The requirements are `docs/work/PROCUREMENT-REQUIREMENTS.md`; Rajeev's rulings on its open questions are
in `docs/work/PROCUREMENT-PROGRESS.md` under *Decisions*. Every `R-…` and every numbered AC maps to at
least one test. Steps are the test's own numbers. The tests describe the app as built and ruled: kitchen
staff record deliveries (Q-1), one preferred vendor with "Preferred (replaces …)" (Q-2), a vendor named
**Market** for cash buys (Q-3), red up and green down for price trends (Q-4), list price **before GST**
(Q-5), no record button on the order page (Q-8), price only on the invoice (Q-9), only payers see the
total owed (Q-17), the invoice page's allowed extras (Q-19), and the first count on a new item needing no
Temple Admin (Q-22).

| Requirement | What it asks | Covered by |
|---|---|---|
| AC-3.1 | Each new table is tenant-isolated | **UAT-092** Part C (a second temple opens none of the first's ingredient page, order, invoice, vendor, deliveries or total owed). The database proof is automated (`ProcurementRowLevelSecurityIT`) |
| AC-3.2 | Existing data survives the upgrade unchanged | **UAT-092** Part A (orders, invoices, payments and on-hand read the same before and after the deploy). Automated in `ProcurementDataModelMigrationIT` |
| R-SL-1 | Readable amounts: nothing of 1,000 gm/ml or more in gm/ml, on the list, the order, the PDF, WhatsApp | **UAT-038** steps 6–7, 8–9 (typed in the unit shown); UAT-041 steps 6–7; UAT-043 step 10; UAT-092 steps 3–6 |
| R-SL-2 | Buying amount rounded up: packs (fewest, least left over), else the step table; a hand edit never re-rounded | **UAT-038** steps 5 (1 × 500 gm for 416 gm), 6 (2,792 gm → 3 Kg), 8–9 (edited, not re-rounded), 18. The boundaries (999 gm, 1 Kg, 10 Kg, 100 Kg) are automated (`BuyingAmountTest`) |
| R-SL-3 | Order in the vendor's pack: "4 × Bag (25 Kg)" on the list, the order, the PDF, WhatsApp, keeping 100 Kg for stock | **UAT-038** step 3; **UAT-039** steps 2, 7, 15; UAT-040 step 5; UAT-041 step 6; UAT-043 step 10; UAT-044 steps 5, 10–12 (entered in bags, stock in Kg) |
| R-SL-4 | "No vendor yet" lines: Choose vendor, Use this vendor next time, bulk Order these from | **UAT-038** steps 4, 11–16 |
| R-ING-1 | Pack sizes on the ingredient: chips, hint, validation (positive, same family, no duplicate, at most 8), used by the list | **UAT-088** steps 2–10, 26; UAT-089 steps 8–9, 12 (a pack made on the vendor page); UAT-045 steps 23–25 (made from a bill); UAT-038 step 5 |
| R-ING-2 | Link a vendor from the ingredient page, the same record as the vendor page | **UAT-088** steps 14–20, 27 |
| R-ING-3 | Market rate on the page; required value at stock-take and on any correction that adds stock; set by invoices; costing falls back to it; never ₹0 | **UAT-090** (all); **UAT-088** steps 11–13, 27–28; UAT-045 steps 13, 21; UAT-075 step 17a; UAT-076 setup; UAT-024 step 5; UAT-092 steps 2, 14–16 |
| R-VEN-1 | Vendor page: Supplies kept, "Edit a row…" removed, "List price" everywhere, the Other ingredients table with search, category filter, ticks, Sells it as, price, lead time, Preferred; nothing truncated at 1280 and 390 | **UAT-089** steps 1–13, 21–22; UAT-037 steps 8–10, 34; UAT-092 step 1 |
| R-VEN-2 | One preferred vendor, with "Preferred (replaces A)" before saving | **UAT-088** steps 17–19; **UAT-089** steps 16–18 |
| R-VEN-3 | Price history and the red-up / green-down / grey-dash marker with the previous price and date, by mouse and keyboard | **UAT-088** steps 16, 21–25, 30; UAT-089 steps 11, 14–15; UAT-045 steps 13, 21, 31; UAT-092 step 14 |
| R-VEN-4 | A list price comes from typing it (vendor or ingredient page) or from each saved invoice line; never from the delivery | **UAT-089** steps 6–15 (typed); **UAT-088** steps 16, 21–25 (typed); **UAT-045** steps 13, 21, 30–31 (from the bill, before GST, a late bill not overwriting); UAT-044 steps 4–5 (no price at delivery) |
| R-PO-1 | "Create a purchase order" goes straight to the form; "Raise an order" gone | **UAT-039** steps 8–9 |
| R-PO-2 | Vendor beside Needed by; no Deliver to; growing note; "Items"; no "Nothing on this order yet." | **UAT-039** steps 10–12, 27 |
| R-PO-3 | The items table and its type-ahead: vendor's items first with list price, Other ingredients, one-off items, keyboard and mouse, live totals, not clipped at 390 | **UAT-039** steps 13–27, 29; UAT-087 step 28 |
| R-PO-4 | The order page's merged table, "Rejected on delivery", "▸ N deliveries", link to Deliveries, Return to vendor kept | **UAT-040** steps 4–6, 11–18, 21–22 |
| R-DEL-1 | Deliveries in the menu after Purchase orders; `RECEIVE_DELIVERIES` (temple admin, kitchen manager, kitchen staff); no prices | **UAT-044** steps 1, 4, 27–29; UAT-005 step 5 |
| R-DEL-2 | Expected, Partly delivered, Received (30 days and older), and the summary strip | **UAT-044** steps 2–3, 13, 19, 22, 25 |
| R-DEL-3 | One Record a delivery per vendor across all their open orders; Received now, Rejected on delivery + reason, Expiry; ordered unit in, stock unit to stock; Everything arrived; green confirmation; rejected stays owed; the rest the same way | **UAT-044** steps 5–12, 16–18, 24 |
| R-DEL-4 | The per-item history and its exact wording, no order number, the same component on every tab and on the order page, keyboard accessible | **UAT-044** steps 14–15, 20; **UAT-040** steps 13–14 |
| R-DEL-5 | Dates always dates; blue Today pill; amber late pill of the same size; no Price paid | **UAT-044** steps 3, 5, 14, 22–23 |
| AC-DEL | A partial then the rest, with both parts in the history; the menu and API refuse anyone without the permission; nothing truncated or sideways at 1280 and 390 | **UAT-044** steps 10–20, 26, 28–29; UAT-092 steps 7–8 |
| R-INV-1 | "Create an invoice" opens the form directly | **UAT-045** steps 1–2 |
| R-INV-2 | The bill is an upload, photo or PDF, required, with the camera on a phone | **UAT-045** steps 10–12, 20, 35; UAT-092 steps 9–11 (kept in cloud storage) |
| R-INV-3 | Billed per delivery: unbilled deliveries offered, lines pulled in and locked; direct invoices by hand with the same type-ahead | **UAT-045** steps 3–4, 14–15, 26–30; UAT-046 step 28 (a void releases the delivery) |
| R-INV-4 | Item · Ordered · Delivered · Billed qty · Amount · Rate; billed defaults to delivered; amber over-billing line; packs and rates per pack and per unit; a new pack defined on the spot | **UAT-045** steps 4–5, 15–18, 22–25, 28–29 |
| R-INV-5 | The totals block, right-aligned; the check with a green tick and no sentence, or the red line and no save; Other charges with a note; the tooltip fixed in the shared component | **UAT-045** steps 5–9, 19, 34 |
| R-INV-6 | Saving writes the lines, links the deliveries and updates list price and history | **UAT-045** steps 12–14, 21, 30–31 |
| R-INV-7 | The invoice page: summary, items, totals, Invoiced vs received from the lines, bill thumbnail, Payments; the allowed extras (credit note, void, reverse, Delivery billed, Billed qty) | **UAT-046** steps 10–13, 25–29 |
| R-INV-8 | Filters Unpaid · Due this week · 1–30 · 31+ with the total owed; others see the plain filters | **UAT-046** steps 2–9, 31–32 |
| R-PAY-1 | Pay this invoice on the page, amount defaults to what is left, only for those who could pay before (the Temple Admin) | **UAT-046** steps 14, 22–24, 33–36 |
| R-PAY-2 | Proof required: one upload for UPI / bank / cheque; Received by, signed note and photo for cash; no ID-type field; the method swaps the fields | **UAT-046** steps 14–16, 18–21, 30; UAT-092 steps 12–13 |
| R-PAY-3 | "Paid by", and proof thumbnails (two for cash) | **UAT-046** steps 17, 21 |
| R-PAY-4 | No Payments menu item; /money redirects to /invoices?filter=unpaid; nothing links to /money | **UAT-046** steps 1–2, 35; UAT-005 steps 3, 5–6 |
| R-DUP-1 | The preparation note on a recipe line, printed everywhere; the library import maps "Cashew, halved" to Cashew with the note | **UAT-087** steps 13–27 |
| R-DUP-2 | A near-duplicate name stops the save with "Did you mean …?", Use / It's a different ingredient (confirmed and audited), on every path that creates or renames an ingredient | **UAT-087** steps 1–12, 20–24, 28; UAT-091 step 15 (after a merge); UAT-073 step 8 |
| R-DUP-3 | The merge tool (Temple Admin only): proposals, preview, price choice, unit clash, all five effects, alias, audit | **UAT-091** (all). Merging real ingredients on staging is held by Rajeev (Q-20); the test merges only a pair it makes |
| R-DUP-4 | The order of work: stop new duplicates before building on the data; run the merge before the end-to-end check | A build-order rule with no screen of its own. Its effect is tested: new duplicates stopped (**UAT-087**) and existing ones merged (**UAT-091**), both before the chain in **UAT-092** |
| §10 | The end-to-end story: packs → order → send → two deliveries → two invoices → one UPI and one cash payment → arrow → a real ₹ on Issued from the temple store and Cost per serving | **UAT-092** Part B (on staging); the same chain in parts in UAT-038 → 039 → 040 → 044 → 045 → 046 |
| Van warning (Rajeev, 2026-09-19, `MealComposer`) | "You’ll only have N minutes to load the van and set up for service at the destination." | **UAT-086** steps 81–86 |
| Repeating events (T-307, T-308, T-309, T-340, T-341) | A series, and cancelling just this one or this and all later ones | **UAT-086** steps 45–70 (already rewritten by T-309) |

**Checked only on staging** (the local stack uses stand-ins): the real PO PDF (UAT-041, UAT-092 step 5),
WhatsApp with the PDF attached (UAT-043, UAT-092 step 6), uploads kept in cloud storage and opened again
from another device (UAT-045, UAT-046, UAT-092 steps 9–13), a phone's camera from the upload
(UAT-045 step 35, UAT-092 step 13), and existing data surviving the deploy (UAT-092 Part A).

---

## 2. Gaps found while writing this pack

These were found by reading the code and the stories side by side, **before any tester ran anything**.
They are recorded here rather than in the tester-facing documents, so that testers approach each screen
without being told what to expect. Each corresponding UAT test asks the tester to *look* for the thing
and write down what they find.

| # | What is missing | Story | Where the test looks | Likely root cause |
|---|---|---|---|---|
| **G1** | ~~**E1-S15 has no written story.**~~ **CLOSED 2026-08-11.** The story was written retrospectively (twelve numbered decisions, including the deliberate choice to keep deletion unconditional), and the export it was missing was built with it: a temple cannot be deleted without a data export taken in the last 24 hours (`KMS-400081`). | E1-S15 | UAT-003 | R6 / process |
| **G2** | ~~**No screen creates a calendar override.**~~ **CLOSED 2026-08-11.** A day panel in the planner now explains what the engine computed for any day, and lets a Temple Admin correct it — fasting flag, Ekadashi name, tithi by name, festival note, mandatory reason — with an undo. Staff see the panel and the hand-corrected badge, but no controls. | E4-S3 | UAT-031 | R6 |
| **G3** | **No screen manages festival occasions.** `OccasionController` supports the catalogue and the seed runs at provisioning, but a temple cannot add its own occasion — and "Temple Anniversary" is the story's own example of why it must. | E4-S2 | UAT-030 step 6 | R6 |
| **G4** | ~~**Recurring giving has no donor-facing surface.**~~ **CLOSED 2026-09-10 — by withdrawal, not by being built, and the distinction matters.** The gap asked why the ledger had a *Recurring* filter and the API had plans and cancellation while no donor could start or stop one. A donor screen was subsequently built for it; then on 2026-09-10 Rajeev took recurring donations out of Phase 1 altogether — *"needs to be researched properly and built. we will do it later as an engagement once the app is live"* — so the plans, the endpoints, the `recurring_plans` table, the donor screen **and** the ledger filter all go together, and **Phase 1 giving is one-time only**. Nothing is left half-present, which is what this row was really complaining about. Recurring giving returns as a Phase 2 Backlog item (REQUIREMENTS.md §4), and E7-S3 is kept withdrawn rather than deleted as the specification it will start from. | ~~E7-S3~~ *(withdrawn)* | ~~UAT-056~~ *(withdrawn)*; UAT-059 step 2 now checks the filter is **absent** | R6 → out of scope |
| **G5** | **The broadcast daily limit cannot be changed from any screen.** `KMS-400065` tells the poster "ask a Temple Admin to raise the limit", and `GET/PUT /api/v1/settings` supports it — but no screen exposes it, so the error message promises something the product does not offer. | E6-S7 | UAT-053 step 10 | R6 (and R1 — the story says "tenant config" without saying where) |
| **G6** | **Kitchen staff cannot see their own schedule.** The story asks for "staff see their own schedule" and no screen provides one. **Cause corrected 2026-09-06 — the original reading is wrong in both halves.** `MANAGE_STAFF_SCHEDULE` is not admin-only: `KITCHEN_MANAGER` holds it too (`RolePermissions.java`). And not every endpoint demands it — `GET /api/v1/staff/schedule/me` is guarded by `VIEW_OWN_SHIFTS`, which kitchen staff *do* hold, and it has a client method (`api.mySchedule`) that nothing in the app calls. So the backend for this is finished and only the screen is missing, which makes it a much cheaper job than the row implied. Note that the same hole is wider than kitchen staff: a Temple Admin holds `VIEW_OWN_SHIFTS` as well and has no *My shifts* menu entry either. | E6-S1 | UAT-047 step 16 | R6 (screen missing over a working backend) |
| **G7** | **Wish-list items cannot be edited, reordered or given an image.** The screen offers add and archive only, though the story asks for CRUD, image upload and manual ordering of what devotees see, and the API supports update and reorder. | E7-S5 | UAT-057 steps 7–9 | R3 |
| **G8** | **No purchase order can be raised by hand.** `POST /api/v1/purchase-orders` exists, but the only route in the app is "generate from the shopping list". The story asks for manual creation as well. | E5-S3 | UAT-039 step 10 | R3 |
| **G9** | **No operator screen for the platform audit log.** Acknowledged and deferred inside E1-S14 itself, so this is a known deferral rather than a surprise — recorded for completeness. | E1-S14 | — | Deferred by design |
| **G10** | ~~**Registering yourself at a temple has no written story.**~~ **CLOSED 2026-08-18.** The registration screen, the public temple list, `POST /api/v1/temples/{id}/join` and the one-person-many-temples migration all shipped unrecorded, and the code cited E1-S16 — which is sign-out. Written up retrospectively as **E1-S17** and the citations repointed. Found while making self-registration the *only* way a devotee joins (E1-S12), which left that story depending on one that did not exist. | E1-S17 | UAT-008, UAT-012 | R6 / process |
| **G11** | ~~**Nobody can be made a Kitchen Manager from any screen.**~~ **CLOSED 2026-08-30** (`7fdab32`). The Staff form's **App access** list now offers *Kitchen manager* alongside *No login*, *Kitchen staff* and *Temple admin*, so a temple can appoint the storekeeper the design assumes (D4) and both halves of E10's permission rule have a manual surface. E6-S12's own D5 had said the hire form would offer it; it never did, and E10 is what made the omission bite. **The row stood open for a week after the fix** — found 2026-09-06 while sweeping for gaps, along with the tester-facing note in UAT-069 that told testers to work around it. | E10-S6, E10-S7 | UAT-069 (the note under *Before you start*, and the last bullet of *Watch out for*), UAT-070 step 25 | R3 / R1 |
| **G12** | **Nothing tests that the Ekadashi flag is admin-only.** Opened 2026-09-08 by decision D-18. UAT-014 covered the admin-only rule and its audit entry for the dietary flag, and it is withdrawn with the sattvic feature it was named for — but the rule itself survives on the **Ekadashi** flag, which is now **the only dietary restriction the product enforces** and is set entirely by hand. UAT-036 tests the *guard* at planning time, not who may set the flag. Needs either a short replacement script or a step added to UAT-013. **Not a defect** — a hole in the test pack opened by a withdrawal, recorded so it is not mistaken for coverage. | E2-S1, E4-S6 | ~~UAT-014~~ (withdrawn); UAT-036 covers the guard only | R6 / process |

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
| Background worker | UAT-019, 020, 023, 029, 030, 031, 032, 034, 036, 038, 041, 052, **071**, **074** (steps 35–41 only), **086** |
| Document renderer | UAT-019, 020, 041, 042, **071**, **074** (steps 35–41 only), **092** |
| Translation provider | UAT-020, 021, 042, **071** (steps 15–20 only) |
| Message channels | UAT-009, 023, 028, 043, 047, 052, 053, 055, **074** (step 40 only), **092** |
| File storage (uploads) | **045**, **046**, **092** |
| Payment provider (test mode) | UAT-055, 056, 058, 059 |

Fully runnable **today**, with no environment changes: UAT-001–018, 021, 022, 024–028, 035, 037,
039, 040, 044–051, 057, 060, 061, **067–070**, **072**, **073**, **075–079**, **084**, **085**, and all of **074** except
steps 35–41. None of UAT-075–079 depends on a switch, though UAT-076 needs UAT-070's issuing to have
happened and UAT-077 has one step (the on-time case) that can only be finished after a few days pass.
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
| INT-1 | (found in worker logs, not a UAT run) | Minor | E1-S15, E4-S1 | R3 (developer oversight) | OPEN — queued | A tenant deleted between a job being queued and the worker running it makes that job fail with `KMS-400029 We couldn't find that temple` and park as failed, with a stack trace in the logs. Seen the moment the worker first started: two calendar-precompute jobs, queued at provisioning for temples since deleted. Deleting a temple should cancel its pending jobs, and a job whose temple has gone should finish quietly rather than raise — a job can always race a deletion. Log noise today; it pollutes exactly the failed-job signal E1-S9/E1-S11 exist to make trustworthy. |
| INT-11 | (found deploying, not a UAT run) | Blocker | E4-S7 | R3 (developer oversight) | FIXED 2026-08-14 | V48 seeded meal kinds and backfilled `ready_by` with plain cross-tenant DML. Tenant-owned tables force RLS on their owner, and on staging the schema owner runs Flyway — so the INSERT was refused and the UPDATEs would silently have matched nothing. **The API and worker both crash-looped on boot**; the site served the previous API build for hours while `main` looked healthy. The migration now adopts each tenant in turn. Root cause behind the root cause: the suite ran Flyway as the Testcontainers superuser, which bypasses RLS entirely — the same trap already guarded against for the application role, and the one that produced V45 and V46. Migrations now run as an unprivileged schema-owning role in tests. |
| INT-12 | Today / Meal plan | Major | E4-S8, E4-S7 | R3 | FIXED 2026-08-14 | Two screens disagreed about what day it is: Today took the temple's day (IST) while the planner took the device's, so a reader outside India saw meals planned "for today" appear on neither. `/money` sliced a UTC ISO string — a third day again for half of every IST evening — and the staff schedule started its week from the device. The client now uses the temple's day everywhere, tested at the boundary. Found by driving the live site in a browser. |
| INT-13 | Donations ledger | Minor | E7-S7 | R3 | FIXED 2026-08-14 | Month-to-date and the financial-year boundary were computed in the server's timezone. The service runs in UTC, so between midnight and 05:30 IST a temple's month-to-date was missing the gifts of what it still called today. Surfaced by the Today screen's own test. |
| UAT003-1 | UAT-003 | Minor | E1-S15 | R3 (developer oversight) | FIXED 2026-08-11 — awaiting re-test | Export downloaded under the client's fallback name: CORS exposed only `X-Request-Id`, so the browser hid `Content-Disposition` from the page. Header now exposed (asserted in `CorsIT`), name is `<temple-web-address>-ikms-data-export.xlsx`, and the client fallback matches. |
