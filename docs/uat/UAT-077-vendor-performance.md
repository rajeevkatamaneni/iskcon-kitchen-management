# UAT-077: Vendor performance — who actually delivers

| | |
|---|---|
| **Feature area** | Ordering — vendor performance |
| **Technical stories** | E5-S9 (vendor performance) · E5-S6 (the receipts it reads) · E5-S1 (the vendors it judges) |
| **Roles exercised** | Kitchen staff, temple admin, volunteer (to prove the refusal) |
| **Depends on** | UAT-037 (vendors), UAT-038 (the shopping list), UAT-039 and UAT-040 (orders), **UAT-044 (receiving)** |
| **Environment needs** | None |

## What this feature is for

Deciding who to keep buying from should be a reading, not a recollection. Everything this screen
needs was already being recorded — the date an order was wanted by, the moment a delivery was booked,
the quantities that turned up, the reasons anything was refused — and nobody had ever asked it a
question.

Two figures sit beside each other on purpose. **On time** tells you the lorry arrived. **Fill rate**
tells you whether it brought everything. A supplier who is always punctual and always short is
exactly the one you want to find, and neither figure alone finds him.

## How it is supposed to work

- **Every percentage is shown with the counts behind it** — *4 of 5*, never a bare *80%*. "50% on
  time" means something different about a supplier with two orders and one with forty.
- **On time is per order, not per ingredient.** The needed-by date is on the order, so an order of
  eight things is one late order whichever of the eight was late.
- **The clock stops at the first delivery**, and an order is judged only once its needed-by date has
  **strictly passed**. An order due today can still arrive today. An order with **no** needed-by date
  can never be late, so it is counted aside rather than scored a silent hundred per cent.
- **Fill rate** is worked out per line as a fraction and averaged — accepted quantity only. Rejected
  goods were delivered and fed nobody; they are counted again, by reason, in their own column.
- **Drafts and cancelled orders count nowhere.** A draft never reached a vendor, and a cancellation
  was the temple's own decision.
- **Open orders are today's position**, not the period's, and are aged into the payables screen's own
  three buckets: current, 1–30 days overdue, 31+ days overdue.
- A vendor with **fewer than five judged orders** is marked *Too few orders to rank* and sorted below
  the ranked ones — with the figure still shown.
- A **deactivated** vendor stays on the report, marked *No longer used*: their record is exactly what
  somebody reads before deciding to bring them back.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/vendor-performance** (menu: **Ordering** → **Vendor performance**, directly under
  **Vendors**)
- **Two things about dates, before you build anything.** They decide what this test can and cannot
  show you in one sitting:
  - Since 2026-09-01 a **draft** order's **Needed by** date can also be set by hand before it is sent
    (UAT-083), which is a quicker way to build the cases below than moving meals about. Either route
    is fine; the dates the steps ask for are what matters.
  - The shopping list sets an order's **Needed by** to **two days before** the meal that needs it. So
    a meal planned for **tomorrow** produces an order that was needed **yesterday** — already judged,
    and already late.
  - A delivery is booked at the moment you record it, which is always **today**. So **an order you
    build today can only ever come out late.** Part C says how to see the on-time case, and it needs
    you to come back after a few days.
- **Set the scene:**

  1. Make sure **Sri Balaji Provisions** (Rice, Toor Dal) and **Nandini Dairy Agency** (Ghee) exist
     with preferred supplies and prices (UAT-037).
  2. On **/planner**, plan a meal for **tomorrow** using **Khichdi** for **300** people, so Rice and
     Toor Dal go short.
  3. On **/inventory**, take **Sugar** below its reorder threshold (UAT-023). Sugar is on **no** meal
     plan — that is deliberate; it produces the order with no needed-by date that Part B needs.

## Steps

### Part A — an order that was late, short, and partly refused

| # | Do this | You should see |
|---|---|---|
| 1 | Open **/shopping-list** and press **Regenerate** | Rice and Toor Dal appear with a **Needed by** date of **yesterday**; Sugar appears with **Needed by —** |
| 2 | Generate purchase orders (UAT-039) and **send** the one to **Sri Balaji Provisions** | It is **Sent**, with **Needed by** yesterday |
| 3 | Open **/vendor-performance** | Heading **Vendor performance**, and under it: *Whether each supplier delivers when they said, brings what was ordered, and what is still outstanding with them* |
| 4 | Read the notice above the table **before** reading a number | It explains: on time is per **whole order**, not per ingredient; it is measured at the **first delivery**, so the fill rate beside it is what says whether everything came; and drafts and cancelled orders are left out, while the open column is **today's** position whenever the order was placed |
| 5 | Look at the columns | Vendor · Orders on time · Fill rate · Rejected · Open now |
| 6 | Look at Sri Balaji's **Orders on time** cell | A percentage **and, underneath it, the counts** — *0 of 1*. **A percentage with no counts under it is a defect** |
| 7 | Look at the **Open now** cell | **1**, with an amber badge reading **1 1–30 days overdue** — the order is sent, unfilled, and its needed-by date was yesterday |
| 8 | Go back to the order and **record a delivery** (UAT-044): on the Rice line, received **30** of 36, **rejected 2** with reason **Spoiled**; leave Toor Dal empty | Recorded; the order goes **Partially received** |
| 9 | Reload **/vendor-performance** | **Orders on time** still *0 of 1* — the delivery came after the day it was wanted. **Fill rate** is now a figure with *across 2 lines* under it, and it is well under 100%: one line part-filled, one line not filled at all |
| 10 | Check the fill-rate arithmetic by hand | Per **line**, as a fraction, then averaged: Rice 30 of 36 and Toor Dal 0 of its quantity → about **42%**. It is **not** kilos added to kilos, and the 2 rejected sacks are **not** in it |
| 11 | Look at the **Rejected** cell | **1**, with **Spoiled 1** underneath — grouped by reason, commonest first |
| 12 | Go back to the order and **cancel** a different, still-draft order to Sri Balaji, then reload | Neither the draft nor the cancellation appears anywhere in the counts |

### Part B — the order nobody can be late for, ranking, and a dropped supplier

| # | Do this | You should see |
|---|---|---|
| 13 | Generate and **send** the Sugar order (the one with **Needed by —**) | It is Sent |
| 14 | Reload **/vendor-performance** and read the notice again | It now ends with: *1 order has no needed-by date, so there is nothing to be late against and it is outside these figures* |
| 15 | Look at that vendor's **Orders on time** counts | The order with no date is shown separately — *… · 1 with no date*. It is **not** counted as delivered on time |
| 16 | Look at that vendor's **Open now** cell | The order is counted as open, in the **current** bucket — there is no date for it to be overdue against, so no amber badge |
| 17 | Look at the badges under each vendor's name | Every vendor here carries **Too few orders to rank** — under five judged orders. **The figures are still shown**; they are not hidden |
| 18 | *(Takes a few minutes.)* Build Sri Balaji up to **five** judged orders: press **Regenerate** on the shopping list — the unreceived Rice and Toor Dal come back as outstanding — then generate and send again. Repeat until five of their orders are Sent with yesterday's date | Sri Balaji's **Too few orders to rank** badge is **gone**, they sort **above** the vendors that still carry it, and the counts read *0 of 5* |
| 19 | Go to **/vendors** and **make Nandini Dairy Agency inactive**, with a reason (UAT-037) | Recorded |
| 20 | Reload **/vendor-performance** | Nandini is **still on the report**, with a **No longer used** badge and every figure they had. Their record is exactly what somebody reads before bringing them back |
| 21 | Look at the **All vendors** row at the foot | The totals, with the counts under the on-time percentage there too |
| 22 | Step the period back to a month before the temple ordered anything | **No orders with any supplier in this period** — a sentence, not a table of dashes |
| 23 | Look for a vendor you have never ordered from in this period | They are **absent** from the table, unless they have an order still open — the open columns are unfiltered by period and say so in the notice |

### Part C — the on-time case, which needs you to come back

| # | Do this | You should see |
|---|---|---|
| 24 | Plan a meal on **/planner** for **five days from today**, big enough to go short. Regenerate the shopping list | The new lines carry a **Needed by** date **three days from today** |
| 25 | Generate the order, **send** it, and **record the delivery in full today** | The order goes **Received** |
| 26 | Reload **/vendor-performance** today | That order is **not judged at all** — its needed-by date has not passed. It appears in **Open now** as *current* only until it was received, and it moves no percentage |
| 27 | **Come back four days later** and reload | The order is now judged, and it counted **on time** — the delivery was booked before the day it was wanted. The on-time counts go up by one on both sides |
| 28 | Sign out; sign in as `ikms.volunteer.1@trading4good.org`. Look at the menu, then type **/vendor-performance** | No menu item; the address gives **Not your page** |

## It passes if

- [ ] **No percentage appears anywhere without the counts behind it**, on any row or in the footer.
- [ ] A punctual supplier who delivers short would read as on time **and** short, in two columns.
- [ ] An order where nothing arrived counts as late.
- [ ] Drafts and cancelled orders are counted nowhere.
- [ ] An order whose needed-by date has not passed is not judged.
- [ ] An order with no needed-by date is counted aside and named in the notice, never scored on time.
- [ ] Fill rate is per line, averaged, on accepted quantity only — not a sum of quantities.
- [ ] Rejections are grouped by reason, commonest first.
- [ ] Open orders use the payables screen's three buckets and its words.
- [ ] Under five judged orders a vendor is marked and sorted below, with the figure still shown.
- [ ] A deactivated vendor stays on the report, marked **No longer used**.
- [ ] A vendor with no orders in the period is absent; an empty period says so rather than showing dashes.
- [ ] The screen says what on time actually measured before anybody reads a number.
- [ ] A devotee is neither offered the screen nor allowed onto it.

## Watch out for

- **A bare percentage anywhere.** This is the column somebody quotes in a meeting, and *80%* off three
  orders is a statement about the sample and not about the supplier. Major.
- **Colour on the percentages.** There should be none. The only coloured thing is an open order
  genuinely past the day it was wanted — a fact, not somebody's idea of a failing score.
- Fill rate adding kilos to litres. Check the arithmetic on one vendor by hand (step 10); a fill rate
  that changes when an ingredient's unit changes is a real defect.
- Rejected quantity being counted as filled, or being left out of the **Rejected** column.
- A vendor's row vanishing when they are deactivated.
- **Needed by can now be set by hand — but only while the order is a draft** (UAT-083, built
  2026-09-01). So the way to build the cases in this test is: edit the draft's needed-by date, *then*
  send it. Once an order is **Sent** the date is frozen and there is no field for it, which is
  deliberate — the vendor was told that date and this scorecard is measured against it. If you find
  any way to move a sent order's needed-by date, that is a **Blocker** here as well as in UAT-083:
  it would let anybody rewrite a supplier's record after the deliveries had happened.
- `KMS-4988` — *That period doesn't work* — appearing from the Week/Month/Year control. It should not
  be reachable that way.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT077-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
