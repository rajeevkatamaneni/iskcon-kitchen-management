# UAT-039: Turn the shopping list into purchase orders

| | |
|---|---|
| **Feature area** | Ordering — purchase order generation |
| **Technical stories** | E5-S3 (purchase order generation and lifecycle), E5-S2 (shopping list) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-038 |
| **Environment needs** | None |

> **Partly withdrawn 2026-09-12 (T-153). Read this before running.** This script was written around
> ticking lines on the shopping list and pressing one **Generate purchase orders** button, which
> raised a draft for every vendor at once. That flow no longer exists. T-134 replaced the single
> button with a **Generate purchase order** button on each vendor's tile on **/shopping-list**, and
> on 2026-09-12 Rajeev ruled to remove the server endpoint behind the old button too (*"Go ahead and
> clean it up"*), because nothing called it any more.
>
> The steps and pass criteria that test the removed flow are struck through in place, not deleted,
> so step numbers do not shift and the record of what was tested stays readable. Steps 6–8 and 10–12
> still stand. For the orders they need, raise them from each vendor tile's **Generate purchase
> order** button. **No steps have been written for the tile flow yet. This script needs a rewrite.**

## What this feature is for

A shopping list is not an order. Purchase orders are what the temple actually sends: one per vendor,
with a number, so that what was asked for, from whom, by when, is never in doubt.

## How it is supposed to work

- ~~Ticked lines are grouped **by vendor** — three lines across two vendors become exactly two draft
  purchase orders.~~ — **withdrawn 2026-09-12 (T-153):** there is no longer one button for every
  vendor; each vendor tile raises its own order.
- Each order gets a **number** of its own, unique to the temple, that is never reused.
- A draft holds a header (vendor, dates, delivery location) and its lines (ingredient, quantity, unit,
  optional expected price). **Only a draft can be edited.**
- An order can also be created by hand, without going through the list.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/shopping-list**, with lines for at least two different vendors (Rice and Toor Dal from
  Sri Balaji Provisions; Ghee from Nandini Dairy Agency). If Ghee is not on the list, lower its
  threshold or plan a meal that needs it.

## Steps

| # | Do this | You should see |
|---|---|---|
| ~~1~~ | ~~On **Shopping list**, tick the lines for Rice, Toor Dal (Sri Balaji) and Ghee (Nandini)~~ — **withdrawn 2026-09-12 (T-153), do not run** | ~~Three lines ticked~~ |
| ~~2~~ | ~~Press **Generate purchase orders**~~ — **withdrawn 2026-09-12 (T-153), do not run.** The button and the endpoint behind it are gone | ~~Confirmation, and you are taken to (or can go to) **Purchase orders**~~ |
| ~~3~~ | ~~Open **/orders** (menu: **Purchase orders**)~~ — **withdrawn 2026-09-12 (T-153), do not run** | ~~**Exactly two** purchase orders — one per vendor — each in status **Draft**~~ |
| ~~4~~ | ~~Open the Sri Balaji order~~ — **withdrawn 2026-09-12 (T-153), do not run** | ~~Its number, the vendor, and **two** lines: Rice and Toor Dal, with the quantities from the list~~ |
| ~~5~~ | ~~Open the Nandini order~~ — **withdrawn 2026-09-12 (T-153), do not run** | ~~One line: Ghee~~ |
| 6 | Note both order numbers | They differ, and follow a readable pattern |
| 7 | While it is a **Draft**, change a quantity on a line | Accepted — drafts are editable |
| 8 | Go back to **/shopping-list** | The lines you turned into orders are gone (or marked as ordered) — they should not be offered for ordering twice |
| ~~9~~ | ~~Generate orders again with **no** lines ticked~~ — **withdrawn 2026-09-12 (T-153), do not run** | ~~Nothing is created, with a message rather than an empty order~~ |
| 10 | Create a purchase order **by hand** — look for a way to raise one without the shopping list | Record what you find. If a manual order is possible, create one for Sri Balaji with 5 Kg of Sugar |
| 11 | Look at the **/orders** list | All orders shown with vendor, status, needed-by and ordered date, and a status filter |
| 12 | Use the status filter (**Draft**) | Only drafts remain |

## It passes if

- [ ] ~~Ticked lines become one draft order per vendor — no more, no fewer.~~ — **withdrawn 2026-09-12 (T-153)**
- [ ] Each order carries its own unique number.
- [ ] Draft orders can be edited.
- [ ] Ordered lines leave the shopping list.
- [ ] ~~Generating with nothing ticked creates nothing and says why.~~ — **withdrawn 2026-09-12 (T-153)**
- [ ] The purchase-order list can be filtered by status.

## Watch out for

- ~~**Two orders to the same vendor** where one was expected. That is a Major defect — the vendor gets two sheets for one shop.~~ — **withdrawn 2026-09-12 (T-153)** with the one-button flow it described.
- Quantities that differ between the shopping list and the resulting order.
- Duplicate order numbers, or numbers that restart. Generate several and look at the sequence.
- **Step 10:** if there is no way to raise a purchase order by hand, record it. A temple that needs a one-off order should not have to fake a shortfall to get one — and the story does ask for it.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT039-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
