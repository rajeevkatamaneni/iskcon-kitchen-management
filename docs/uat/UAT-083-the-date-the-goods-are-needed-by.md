# UAT-083: The date the goods are needed by

| | |
|---|---|
| **Feature area** | Ordering — the needed-by date on a purchase order |
| **Technical stories** | E5-S3 D1–D5 (an editable needed-by date on a draft, and what it refuses) · E5-S9 D6 (why it freezes when the order is sent) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-038 (the shopping list), UAT-039 (turning it into purchase orders), UAT-040 (sending and cancelling), UAT-077 (vendor performance) |
| **Environment needs** | None |

## What this feature is for

Every purchase order carries the date the temple needs the goods by. The shopping list works one out
— two days before the meal that needs the ingredient — and until now that was the end of it: nobody
could change it, however wrong it was for the real order.

Now it can be set by hand while the order is still a draft. Once the order goes to the vendor it
freezes, and that is not tidiness about editing. The vendor has been **told** that date, so moving it
afterwards changes what they were asked for without telling them; and it is the line the vendor
scorecard measures on-time delivery against (UAT-077), so leaving it open would let anybody rewrite a
supplier's record after the deliveries had already happened.

## How it is supposed to work

- **On a draft** the date is a field you can edit, arriving with whatever the shopping list worked
  out. Its date picker will not offer a day **before the order's own date**.
- A date **before the order date** is **refused** — `KMS-4014`. Asking a vendor for something
  yesterday is not a request, and it would score them late from the moment the order was raised.
- A date **sooner than the two days a vendor usually gets** is **warned about and allowed**, in gold
  under the box. It is a real thing a temple sometimes has to do, and being warned is different from
  being stopped.
- **Clearing it is allowed.** An order with nothing to meet is a real order. On the vendor
  performance report those are counted aside and named, never scored as late and never scored as on
  time.
- **Once the order is sent there is no field at all** — a readout, and under it *Fixed when the order
  was sent*. The server refuses a change with `KMS-4919`, so hiding the field is not the only guard.
- The date the shopping list **works out** is not held to the same rule as a date somebody **types**:
  a meal planned for tomorrow legitimately produces a needed-by date in the past, and refusing that
  would break the shopping list rather than protect anything.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Set the scene:**
  1. Make the shopping list produce something (UAT-038): plan a meal that goes short, or drop an item
     below its reorder threshold.
  2. On **/shopping-list**, press **Regenerate**, then generate purchase orders (UAT-039). You need
     **two** drafts to work with — if only one vendor is suggested, add a second short ingredient
     that a different vendor supplies.
- **Start at:** **/orders**, and open one of the drafts.
- **Write down today's date**, and the **order's own date** shown on the order. Every step is
  arithmetic against those two.

## Steps

### On a draft

| # | Do this | You should see |
|---|---|---|
| 1 | Look under the vendor's name at the top of the order | A line reading **Needed by** and a date, written the Indian way — *12 Sept 2026*, never *Sep 12, 2026* |
| 2 | Look for the words *Fixed when the order was sent* | **Not there.** This order has not been sent |
| 3 | Press **Edit lines** | The **Edit this draft** panel opens, and the **first** thing in it is a **Needed by** date box |
| 4 | Look at what is in the box | The date from step 1, already filled in — not blank, and not today |
| 5 | Read the line under the box | *Leave it blank if there is no date to meet* |
| 6 | Open the date picker and try to choose a day **before the order's own date** | The picker will not offer it — the days before the order are unavailable |
| 7 | **Type** a date before the order's own date straight into the box (some browsers let you past the picker), and press **Save changes** | **Refused**, without the order changing: *That date is before the order was raised. Choose a day on or after it.* If it reaches the server you get **`KMS-4014`** — *That date is before the order was raised.* Either way the draft is untouched |
| 8 | Set the date to **tomorrow** | The line under the box turns **gold** and reads **Sooner than the 2 days a vendor usually gets** |
| 9 | Press **Save changes** anyway | **Accepted.** A warning is not a refusal. The header now reads **Needed by** tomorrow's date |
| 10 | Press **Edit lines** again and set the date to **ten days from today** | The gold line is gone, replaced by *Leave it blank if there is no date to meet*. Ten days is more notice than the vendor usually gets |
| 11 | Save, and check the header | **Needed by**, ten days out, in the Indian form |
| 12 | Edit once more, **clear the box completely**, and save | Accepted. The header now reads **No needed-by date** — a sentence, not a blank space and not a dash on its own |
| 13 | Reload the page | Still **No needed-by date**. It was really cleared, not just forgotten by the screen |
| 14 | Edit, put a date **five days out** back in, and save | **Needed by**, five days out |

### It freezes when the order is sent

| # | Do this | You should see |
|---|---|---|
| 15 | Press **Send** on that order (UAT-040) | The order is **Sent** |
| 16 | Read the header | **Needed by**, still five days out — and under it **Fixed when the order was sent** |
| 17 | Look for **Edit lines** | **Gone.** A sent order has no edit panel and no date box |
| 18 | Prove the server refuses it, not just the screen. Open the **second** draft in **two browser tabs**. In tab 1 press **Edit lines** and change the needed-by date. In tab 2, **send** that order. Now go back to tab 1 and press **Save changes** | **Refused**: *A sent purchase order can't be changed.* — *Raise a new one for the difference.*, quoting **`KMS-4919`** |
| 19 | Reload tab 1 | The order is **Sent**, with the needed-by date it had **when it was sent** — not the one you typed in tab 1 |
| 20 | Look at a **cancelled** order, and one that has been **received** (UAT-044) | Both show the readout and *Fixed when the order was sent*, and neither offers a field |

### What the vendor scorecard does with it

| # | Do this | You should see |
|---|---|---|
| 21 | Go to **/vendor-performance** (UAT-077) | The order you sent at step 15 is not judged at all yet — its needed-by date has not passed |
| 22 | Raise one more draft, **clear** its needed-by date, and **send** it | It is Sent, header reading **No needed-by date** |
| 23 | Reload **/vendor-performance** and read the notice above the table | It ends with a count of orders that **have no needed-by date, so there is nothing to be late against**, and says they are outside the figures |
| 24 | Look at that vendor's **Orders on time** cell | The dateless order is shown separately — *… · 1 with no date*. It is **not** counted as delivered on time, and not counted as late |

## It passes if

- [ ] A draft's needed-by date is editable, pre-filled, and the picker will not offer a day before the order's own date.
- [ ] A date before the order date is refused, and the draft is left alone.
- [ ] A date inside the two-day lead buffer is warned about in gold and **saved anyway**.
- [ ] The date can be cleared, and the header then says **No needed-by date**.
- [ ] Once sent, there is no field — only the readout and *Fixed when the order was sent*.
- [ ] The server refuses a change to a sent order with **`KMS-4919`**, not merely the screen.
- [ ] An order with no needed-by date is counted aside on the vendor performance report, never scored.
- [ ] Every date on these screens reads *12 Sept 2026*, never *Sep 12, 2026* and never `2026-09-12`.

## Watch out for

- **A sent order whose date can still be moved by any route.** That is the whole point of the freeze,
  and a **Blocker** if you find one — write down exactly how you did it.
- **The gold warning turning into a refusal.** A temple that needs flour tomorrow needs to be able to
  say so. If step 9 is refused, that is a Major defect.
- **A cleared date coming back** on reload, or turning into today's date. Clearing means clearing.
- **The order date itself.** It should be the **temple's** day, in India — so an order raised late in
  the evening carries today's date, not yesterday's. If you are testing after about 6:30pm Indian
  time, look at this specifically.
- **"That day has already gone"** — a different warning, for a draft raised on an earlier day whose
  needed-by date is now in the past. It warns; it does not refuse. If you see it, note which draft.
- A **`KMS-4014`** you did not expect: on an order generated from the shopping list, the date is
  worked out from the meal that needs it and can legitimately land in the past. It should generate
  without complaint. Being refused there is a defect, not correct strictness.
- Money on these screens carrying Indian digit grouping — **₹1,15,000**, not ₹115,000.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT083-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
