# UAT-044: Receiving — full, short and rejected

| | |
|---|---|
| **Feature area** | Ordering — receiving |
| **Technical stories** | E5-S6 (receiving: full, partial and rejected deliveries, amended 2026-08-31) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-040 (a sent order), UAT-037 (vendor prices) |
| **Environment needs** | None |

## What this feature is for

The truck rarely brings exactly what was ordered. Sacks are short, some goods arrive spoiled, and
occasionally the wrong thing turns up. Inventory must reflect **the truck, not the order** — so
receiving records what actually arrived, what was refused and why, and what is still owed.

It is also **the one moment somebody knows what the food actually cost**. The lorry brings a bill,
and the storekeeper standing in front of it is the only person in the building who can see it. Until
now, a vendor's price was whatever somebody last typed on the vendor screen — and the shopping list,
every costing figure and the price the temple quotes for outside catering all read that number as if
it were current.

## How it is supposed to work

- Receiving is line by line: **received now**, **rejected** with a reason, an **expiry** for what is
  accepted, and the **price paid**.
- **Only received goods enter stock.** Rejected goods never do.
- Each delivery of an ingredient becomes its own **lot** on the inventory screen, identified by the
  date it arrived and the expiry you gave it — the store reads its lots soonest-to-expire first.
- **Price paid** is per line, in rupees **per the line's own unit**, pre-filled from what the order
  expected and hinted underneath with *expected ₹45 / Kg*. It is **editable, and optional**.
  - A price you give is written back as **the vendor's last-known price** for that ingredient.
  - **Leaving it blank stores nothing and overwrites nothing.** "The bill hasn't come yet" and "this
    costs nothing" are different statements.
  - A price is **not** a gate. Nothing is flagged, and nothing needs approving, however far it is from
    what was expected.
  - A line **rejected in full** teaches nothing about what the vendor charges and writes no price back.
- What is still outstanding keeps the order **Partially received**, and comes back onto the next
  suggested shopping list, marked with where it came from.
- A second delivery completing the order flips it to **Received**.
- Submitting the same delivery twice must not book the stock twice — or re-price anything.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/orders** → the **Sent** order to Sri Balaji Provisions
- **Set it up to match the worked example:** the order should have a line for **36 Kg of Rice** (edit it
  while it is still a draft, or create a fresh order).
- On **/vendors**, make sure Sri Balaji's **Rice** supply has a **Last price** of **`45`**. That is the
  figure the form should pre-fill and hint with, and the one step 10 checks has changed.
- Write down Rice's current stock before you begin.

## Steps

### The delivery

| # | Do this | You should see |
|---|---|---|
| 1 | Open the sent order and press **Receive delivery** | A line-by-line form headed **Record a delivery**: Item, Ordered, Received so far, **Received now**, **Rejected**, Reason, **Expiry**, **Price paid** |
| 2 | Read the note above the form | *Rejected goods need a reason and never enter stock. The price is what the bill says — correct it if it differs, or leave it blank for a delivery that came without one.* |
| 3 | Look at the **Price paid** box on the Rice line **before typing anything** | It is **already filled in with `45`**, and underneath it says **expected ₹45 / Kg** — what the order budgeted, still visible rather than replaced |
| 4 | On the Rice line, enter **received 30**, **rejected 2** with reason **spoiled**, expiry six months out, and change **Price paid** to `52` | Accepted. Nothing warns you, nothing asks for approval, and no flag appears — a delivery that came in dearer is a fact, not an exception |
| 5 | Press **Record delivery** | The delivery is recorded; the order's status becomes **Partially received** |
| 6 | Go to **/inventory** → **Rice** | Stock is up by **30 Kg exactly** — not 32, not 36 — in a **new lot** carrying the expiry you entered and today as its arrival date |
| 7 | Look at Rice's **Movement history** | One row of +30, referencing this purchase order. **No row** for the 2 rejected |
| 8 | Back on the order, look at the lines | Rice shows 30 received of 36, with 2 rejected recorded and **6 still outstanding** |
| 9 | Go to **/shopping-list** and press **Regenerate** | The 6 Kg shortfall reappears as a line, with its reason showing it came from this order |

### The price goes where it is meant to, and only where it is meant to

| # | Do this | You should see |
|---|---|---|
| 10 | Go to **/vendors** → **Sri Balaji Provisions** → **Supplies** | Rice's **Last price** now reads **52** — the delivery corrected it, without anybody retyping it on this screen |
| 11 | Check the **Preferred** ticks on that page | **Unchanged.** A delivery says what a thing cost; it does not say who the temple would rather buy from |
| 12 | Look at **Toor Dal**'s price on the same page | **Unchanged** — nothing was received against it |
| 13 | Return to the order and record a second delivery of **6 Kg** — but **clear the Price paid box** and leave it empty | The order flips to **Received** |
| 14 | Go back to the vendor's Supplies | Rice's Last price is **still 52**. **A blank price stored nothing and overwrote nothing** — it did not become ₹0 |
| 15 | Check inventory again | Two lots now, 30 Kg and 6 Kg, each with its own expiry and arrival date |
| 16 | On a fresh order, receive a line where you **reject the whole quantity** and still type a price | The vendor's last price for that ingredient is **unchanged** — nothing was bought, at that price or any other |
| 17 | On a fresh order, receive an ingredient this vendor has **never supplied before**, giving a price | A **new supply row** appears on that vendor's page for it, with the price. They have now demonstrably supplied it |
| 18 | Look at what a price is per | The hint says the **line's own unit** — *expected ₹45 / Kg*, not "per gram" or "per order". If a line is in `gm`, the price is per gram |

### The refusals

| # | Do this | You should see |
|---|---|---|
| 19 | Press **Record delivery** twice in quick succession on a fresh order (double-click the button) | Stock increases **once**, not twice — and the vendor's price is written **once**, not twice |
| 20 | Try to record a delivery with **nothing** in any received or rejected box | Refused: *Enter what arrived on at least one line.* (the server's own answer is `KMS-4922`, *A delivery line must record something received or something rejected*) |
| 21 | Try to reject a quantity **without** giving a reason | Refused |
| 22 | Type a **negative** price, or letters, in **Price paid** | Refused: *A price is an amount in rupees. Leave it blank if the bill hasn't arrived.* |
| 23 | Try to receive against a **draft** order | Refused — you can only receive against a sent order |

## It passes if

- [ ] Received quantities enter stock; rejected quantities never do.
- [ ] The expiry entered at receiving appears on the lot, and each delivery makes its own lot.
- [ ] The order becomes **Partially received** with the correct outstanding quantity.
- [ ] The outstanding quantity comes back onto the shopping list, with its provenance.
- [ ] A second delivery completes the order.
- [ ] **Price paid is pre-filled from the order, hinted with what was expected, editable, and optional.**
- [ ] A price given **lands on the vendor's supply row** for that ingredient.
- [ ] **A blank price changes no existing price**, and never becomes zero.
- [ ] A line rejected in full writes no price back.
- [ ] A price is per the line's own unit.
- [ ] A price never touches the **Preferred** flag.
- [ ] A duplicate submission does not double-count stock or re-price anything.
- [ ] An empty line, a reasonless rejection and a nonsense price are all refused.

## Watch out for

- **Step 6 is the one that matters most.** If stock rose by 32 or 36, the temple's records now disagree
  with its shelves. Blocker.
- **A blank price becoming ₹0 on the vendor's page.** That is the failure this design exists to
  prevent: it would tell the shopping list, every costing screen and the temple's catering quote that
  an ingredient is free. Blocker.
- The expiry date not making it onto the lot — the first-expiring-first logic in UAT-035 depends on it.
- A price being written back for goods that were **rejected**.
- **Units.** If the Rice line were in `gm`, ₹0.05 per gram is ₹50 a kilo, and the vendor's per-Kg
  price must read 50 and not 0.05. If you can arrange an order line in grams for an ingredient held in
  kilos, do it and check — being wrong by a factor of a thousand here is worth a Blocker.
- Any warning, flag or approval step appearing because the price differs from what was expected. There
  should be none.
- Receiving more than was ordered: try entering 50 against a 36 Kg line. Record what happens;
  deliveries genuinely do overshoot sometimes, and how the product handles it is worth knowing.
- The rejection reason not being stored — it is read back per vendor and by reason on
  **/vendor-performance** (UAT-077).

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT044-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
