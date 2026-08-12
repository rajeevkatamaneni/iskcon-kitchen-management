# UAT-044: Receiving — full, short and rejected

| | |
|---|---|
| **Feature area** | Ordering — receiving |
| **Technical stories** | E5-S6 (receiving: full, partial and rejected deliveries) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-040 (a sent order) |
| **Environment needs** | None |

## What this feature is for

The truck rarely brings exactly what was ordered. Sacks are short, some goods arrive spoiled, and
occasionally the wrong thing turns up. Inventory must reflect **the truck, not the order** — so
receiving records what actually arrived, what was refused and why, and what is still owed.

## How it is supposed to work

- Receiving is line by line: **received now**, **rejected** with a reason, plus **batch and expiry** for
  what is accepted.
- **Only received goods enter stock.** Rejected goods never do.
- What is still outstanding keeps the order **Partially received**, and comes back onto the next
  suggested order list, marked with where it came from.
- A second delivery completing the order flips it to **Received**.
- Submitting the same delivery twice must not book the stock twice.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/orders** → the **Sent** order to Sri Balaji Provisions
- **Set it up to match the worked example:** the order should have a line for **36 Kg of Rice** (edit it
  while it is still a draft, or create a fresh order).
- Write down Rice's current stock before you begin.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open the sent order and press **Receive delivery** | A line-by-line form: Item, Ordered, Received so far, **Received now**, **Rejected**, reason, **Batch**, **Expiry** |
| 2 | Read the note above the form | *Enter what actually arrived. Rejected goods need a reason and never enter stock.* |
| 3 | On the Rice line, enter **received 30**, **rejected 2** with reason **Spoiled**, batch `BAL-2026-11`, expiry six months out | Accepted |
| 4 | Press **Record delivery** | The delivery is recorded; the order's status becomes **Partially received** |
| 5 | Go to **/inventory** → **Rice** | Stock is up by **30 Kg exactly** — not 32, not 36 — in a new batch `BAL-2026-11` with the expiry you entered |
| 6 | Look at Rice's **Movement history** | One row of +30, referencing this purchase order. **No row** for the 2 rejected |
| 7 | Back on the order, look at the lines | Rice shows 30 received of 36, with 2 rejected recorded and **6 still outstanding** |
| 8 | Go to **/order-list** and press **Regenerate** | The 6 Kg shortfall reappears as a line, with its reason showing it came from this order |
| 9 | Return to the order and record a second delivery of **6 Kg**, batch `BAL-2026-12` | The order flips to **Received** |
| 10 | Check inventory again | Two batches now, 30 Kg and 6 Kg, each with its own expiry and received date |
| 11 | Press **Record delivery** twice in quick succession on a fresh order (double-click the button) | Stock increases **once**, not twice |
| 12 | Try to record a delivery with **nothing** in either column on any line | Refused: *A delivery line must record something received or something rejected* (`KMS-4922`) |
| 13 | Try to reject a quantity **without** giving a reason | Refused |
| 14 | Try to receive against a **draft** order | Refused — you can only receive against a sent order |

## It passes if

- [ ] Received quantities enter stock; rejected quantities never do.
- [ ] The batch and expiry entered at receiving appear on the stock batch.
- [ ] The order becomes **Partially received** with the correct outstanding quantity.
- [ ] The outstanding quantity comes back onto the order list, with its provenance.
- [ ] A second delivery completes the order.
- [ ] A duplicate submission does not double-count stock.
- [ ] An empty line and a reasonless rejection are both refused.

## Watch out for

- **Step 5 is the one that matters most.** If stock rose by 32 or 36, the temple's records now disagree with its shelves. Blocker.
- The expiry date not making it onto the batch — the first-expiring-first logic in UAT-035 depends on it.
- Receiving more than was ordered: try entering 50 against a 36 Kg line. Record what happens; deliveries genuinely do overshoot sometimes, and how the product handles it is worth knowing.
- The rejection reason not being stored — it is meant to accumulate towards judging vendors later.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT044-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
