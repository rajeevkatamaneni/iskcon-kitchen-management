# UAT-044: The Deliveries screen — expected, part delivered, received

| | |
|---|---|
| **Feature area** | Ordering — receiving what vendors bring |
| **Technical stories** | E5-S6 (receiving); procurement release 2026-09-19: R-DEL-1 (menu and access), R-DEL-2 (three tabs and the summary), R-DEL-3 (recording a delivery across a vendor's orders), R-DEL-4 (delivery history), R-DEL-5 (dates, pills, no price), AC-DEL, and Rajeev's answer to Q-1 (kitchen staff record deliveries). Tasks T-261, T-262, T-266, T-285, T-299, T-343 |
| **Roles exercised** | Kitchen staff, kitchen manager, temple admin, volunteer (to prove the refusal) |
| **Depends on** | UAT-039 and UAT-040 (order A, sent) |
| **Environment needs** | None. Steps 22–25 are run **the next day** |

> **Rewritten 2026-09-19.** The earlier script received goods from a **Receive delivery** button on the
> order, typed a **Price paid** on each line, and wrote that price back to the vendor. All three are gone:
> deliveries are recorded on this screen, and **the price now comes only from the invoice** (UAT-045). If
> you see *Price paid* anywhere, that is a defect.

## What this feature is for

The person at the gate needs one place that says who is coming today, what they still owe, and what
already came. And when the van arrives with goods from three different orders, they record it once, for
that vendor, not order by order. There are no prices on this screen: the gate counts sacks; the bill
comes later.

## How it is supposed to work

- **Deliveries** is in the menu under **Ordering**, right after **Purchase orders**. Temple admin,
  kitchen manager and kitchen staff have it. Nobody else sees it or can open it.
- A small summary at the top — **Due today**, **Overdue**, **Partly delivered**, **Received this week** —
  then three tabs:
  - **Expected**: sent orders not fully delivered, one card per vendor, with what is still to come and
    when it was needed by.
  - **Partly delivered**: what is still owed, and for how long.
  - **Received**: a dated history of the last 30 days — date, vendor, items, received by, rejected,
    returned — with **Show older deliveries** for more.
- **One Record a delivery button per vendor.** It lists everything that vendor still owes across all
  their open orders, each line with its order number in small text. Per line: **Received now**,
  **Rejected on delivery** with a **Reason**, and **Expiry**. Amounts are typed in the unit the line was
  ordered in (2 bags), and stock receives the stock-unit amount (50 Kg). **Everything arrived** fills in
  whatever is still to come.
- **Rejected goods stay owed.** The rest of a part delivery is recorded exactly the same way.
- Each item's history opens from a quiet **▸ N deliveries**: `12 Sept · 30 Kg received · 2 Kg rejected
  (spoiled) · Received by: Karuna Murti Das`, then `Received 30 of 50 Kg ordered`, or when complete
  `Received 50 of 50 Kg ordered · complete 15 Sept`. No order number in these lines.
- **Dates are always dates.** Today's gets a blue **Today** pill beside it; a late one gets an amber pill.
  The word "Today" never replaces the date.
- **No prices anywhere on this screen.**

## Before you start

- **Sign in as:** `ikms.kitchen-staff.5@trading4good.org` (kitchen manager) and, on **/orders/new**
  (UAT-039), create **order C**: vendor **UAT Rice Traders**, **UAT Groundnut Oil** × Tin (15 L)
  quantity `1`, **Needed by today**. Open it and press **Mark sent**.
- Order A (UAT Bulk Rice 4 × Bag (25 Kg), UAT Tea 1 × 500 gm) is already sent (UAT-040).
- On **/inventory**, write down UAT Bulk Rice's on-hand figure (20 Kg if you followed UAT-090).
- **Then sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)

## Steps

### The screen, and who can open it

| # | Do this | You should see |
|---|---|---|
| 1 | Look at the menu under **Ordering** | Shopping list, Purchase orders, **Deliveries**, Vendors … — Deliveries straight after Purchase orders |
| 2 | Open **Deliveries** | Heading **Deliveries** and *What vendors are bringing, and what has come in.* A small summary: **Due today**, **Overdue**, **Partly delivered**, **Received this week**, quieter than the page heading. Three tabs: **Expected**, **Partly delivered**, **Received** |
| 3 | Read the **Expected** tab | A **UAT Rice Traders** card with **one** **Record a delivery** button and a table Item · Order · Still to come · Needed by: UAT Bulk Rice · order A · **4 bags (100 Kg)** · its date; UAT Tea · order A · **1 × 500 gm**; UAT Groundnut Oil · order C · **1 tin (15 L)** or similar · **today's date with a blue Today pill beside it** |
| 4 | Search the whole page for **₹**, **price**, **rate** and **paid** | None of them |

### The first part of the delivery

| # | Do this | You should see |
|---|---|---|
| 5 | Press **Record a delivery** on UAT Rice Traders | A panel listing **all three lines from both orders**, each with its order number in small text under the item. Columns: Item · Still to come · Received now · Rejected on delivery · Reason · Expiry. **No Price paid.** Every Received now box is **empty**, and UAT Bulk Rice's box is labelled in **Bag (25 Kg)** |
| 6 | Press **Save delivery** without typing anything | **Type what arrived on at least one line, or press Everything arrived.** Nothing saved |
| 7 | Look at the **Reason** list on UAT Bulk Rice before typing a rejected amount | It can't be chosen yet |
| 8 | On UAT Bulk Rice type Received now `5` | Refused beside the line: **Only 4 bags (100 Kg) is still to come** (or the same amount written that way) |
| 9 | Received now `4`, Rejected on delivery `1` | Refused: **Received and rejected add up to more than the … still to come** |
| 10 | Received now `2`, Rejected on delivery `1`, Reason **Damaged**, Expiry six months from today. Leave the other two lines empty. Press **Save delivery** | A **green** line: **Delivery from UAT Rice Traders recorded. 1 item went into stock. 3 lines are still to come from them.** |
| 11 | Look at UAT Bulk Rice on the Expected tab | Still to come **2 bags (50 Kg)**, with a note under it that it **includes** the rejected amount, dated today. **Rejected goods are still owed** |
| 12 | Open **/inventory** → UAT Bulk Rice | Up by **exactly 50 Kg** — not 75, not 100 — in a new batch carrying the expiry you gave. The movement history has one delivery row of +50 Kg and **nothing** for the rejected bag |

### Part delivered

| # | Do this | You should see |
|---|---|---|
| 13 | Open the **Partly delivered** tab | Order A's UAT Bulk Rice: Ordered **4 bags (100 Kg)** · Received **2 bags (50 Kg)** · Still owed **2 bags (50 Kg)** · Owed for (how long). Every figure in the same unit, the bags with Kg in brackets |
| 14 | Press **▸ 1 delivery** under UAT Bulk Rice | It opens and reads *&lt;today's date&gt;* **Today** **· 50 Kg received · 25 Kg rejected (damaged) · Received by:** *&lt;your name&gt;*, and under it **Received 50 of 100 Kg ordered**. No order number. It is small and quiet, not a heading |
| 15 | Close and open it again with the keyboard (Tab to it, Enter) | It opens and closes; a screen reader hears whether it is expanded |

### The rest, recorded the same way

| # | Do this | You should see |
|---|---|---|
| 16 | Sign in as the **kitchen manager**. On **Partly delivered**, press **Record a delivery** for UAT Rice Traders | The same panel, with **Received now empty** again, listing what is still owed: UAT Bulk Rice 2 bags (50 Kg), UAT Tea, UAT Groundnut Oil |
| 17 | Press **Everything arrived** | Every Received now box fills with what is still to come: **2** bags, **1**, **1** |
| 18 | Clear the **UAT Groundnut Oil** box, and press **Save delivery** | Green: **Delivery from UAT Rice Traders recorded. 2 items went into stock.**, naming **UAT Bulk Rice is complete: 100 of 100 Kg.**, and ending **1 line is still to come from them.** |
| 19 | Open the **Received** tab | Two rows for today: date, **UAT Rice Traders**, the items, **Received by** your two names, and the damaged bag in **Rejected** |
| 20 | Open UAT Bulk Rice's history on the Received tab | Two lines, oldest first: the first as in step 14, then *&lt;today&gt;* **Today · 50 Kg received · Received by:** *&lt;kitchen manager's name&gt;*, and the closing line **Received 100 of 100 Kg ordered · complete** *&lt;today&gt;* **Today** |
| 21 | Open order A on **/orders** | **Received**. Its table shows Delivered **100 Kg** and two lines of history (UAT-040 step 11 onwards) |

### The next day: late

| # | Do this | You should see |
|---|---|---|
| 22 | **The next day**, open **Deliveries** | The summary reads **Overdue 1 vendor** (or one more than before), in amber |
| 23 | Read UAT Groundnut Oil on **Expected** | Its needed-by date — yesterday's — written as a date, with an amber **1 day late** pill beside it. The same size and shape as the Today pill |
| 24 | Record it (Record a delivery → Everything arrived → Save delivery) | Green, ending **Nothing more is owed by them.** UAT Rice Traders leaves the Expected tab |
| 25 | On **Received**, scroll to the foot | If the temple has deliveries older than 30 days, **Show older deliveries** loads the 30 days before. If it has none, the button is not there |

### Widths and access

| # | Do this | You should see |
|---|---|---|
| 26 | Measure every tab at **1280px**, **1024px** and **390px**, with the panel open and with every history open | Nothing truncated, nothing scrolls sideways. At 1024 an opened history does not squeeze the short columns beside it into several lines. At 390 the panel lines become cards and **Everything arrived** and **Save delivery** can be reached |
| 27 | Sign in as the **temple admin** | Deliveries is in the menu and works the same |
| 28 | Sign in as `ikms.volunteer.1@trading4good.org` | **No Deliveries** in the menu. Typing **/deliveries** shows **Not your page**, with nothing from the screen behind it |
| 29 | *(Technical tester only.)* As the volunteer, call `GET /api/v1/deliveries` on the system address in README §2 with the volunteer's token | **403**, `KMS-400021`. The server refuses, not just the menu |

## It passes if

- [ ] Deliveries sits under Ordering after Purchase orders, and only temple admin, kitchen manager and kitchen staff can see or open it; the server refuses anyone else.
- [ ] The three tabs and the summary show what the requirements say, with no price, rate or ₹ anywhere.
- [ ] One Record a delivery per vendor lists every line that vendor owes across all their orders, each with its order number.
- [ ] Amounts are typed in the ordered unit (bags) and stock receives the stock-unit amount (Kg), exactly.
- [ ] A rejection needs a reason, stays owed, and never enters stock.
- [ ] Everything arrived fills in what is still to come; the rest of a part delivery is recorded the same way.
- [ ] The history reads in the exact wording, with no order number, and completes with `Received N of N … ordered · complete <date>`.
- [ ] Dates are always dates; today's has a blue Today pill, a late one an amber pill of the same size.
- [ ] Nothing is truncated or scrolls sideways at 1280, 1024 or 390.

## Watch out for

- **Stock that rose by 75 or 100 Kg at step 12.** The shelf and the records would disagree. Blocker.
- **Price paid, or any ₹, on this screen.** The price belongs to the invoice now. Major.
- **"Today" in place of a date**, or a Today pill in amber or a late pill in blue. Minor, but note it.
- **The same delivery recorded twice** by a double press of Save delivery: stock must rise once.
- A line from a **different** vendor in the panel, or a line from a cancelled order.
- `KMS-400164` — *One of these items isn’t on an open order from this vendor any more.* — is what you meet
  if someone else closed or recorded the order while your panel was open. Reload and record again.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT044-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
