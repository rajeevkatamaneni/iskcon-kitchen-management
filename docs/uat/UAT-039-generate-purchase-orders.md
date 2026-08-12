# UAT-039: Turn the order list into purchase orders

| | |
|---|---|
| **Feature area** | Ordering — purchase order generation |
| **Technical stories** | E5-S3 (purchase order generation and lifecycle), E5-S2 (order list) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-038 |
| **Environment needs** | None |

## What this feature is for

A shopping list is not an order. Purchase orders are what the temple actually sends: one per vendor,
with a number, so that what was asked for, from whom, by when, is never in doubt.

## How it is supposed to work

- Ticked lines are grouped **by vendor** — three lines across two vendors become exactly two draft
  purchase orders.
- Each order gets a **number** of its own, unique to the temple, that is never reused.
- A draft holds a header (vendor, dates, delivery location) and its lines (ingredient, quantity, unit,
  optional expected price). **Only a draft can be edited.**
- An order can also be created by hand, without going through the list.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/order-list**, with lines for at least two different vendors (Rice and Toor Dal from
  Sri Balaji Provisions; Ghee from Nandini Dairy Agency). If Ghee is not on the list, lower its
  threshold or plan a meal that needs it.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | On **Order list**, tick the lines for Rice, Toor Dal (Sri Balaji) and Ghee (Nandini) | Three lines ticked |
| 2 | Press **Generate purchase orders** | Confirmation, and you are taken to (or can go to) **Purchase orders** |
| 3 | Open **/orders** (menu: **Purchase orders**) | **Exactly two** purchase orders — one per vendor — each in status **Draft** |
| 4 | Open the Sri Balaji order | Its number, the vendor, and **two** lines: Rice and Toor Dal, with the quantities from the list |
| 5 | Open the Nandini order | One line: Ghee |
| 6 | Note both order numbers | They differ, and follow a readable pattern |
| 7 | While it is a **Draft**, change a quantity on a line | Accepted — drafts are editable |
| 8 | Go back to **/order-list** | The lines you turned into orders are gone (or marked as ordered) — they should not be offered for ordering twice |
| 9 | Generate orders again with **no** lines ticked | Nothing is created, with a message rather than an empty order |
| 10 | Create a purchase order **by hand** — look for a way to raise one without the order list | Record what you find. If a manual order is possible, create one for Sri Balaji with 5 Kg of Sugar |
| 11 | Look at the **/orders** list | All orders shown with vendor, status, needed-by and ordered date, and a status filter |
| 12 | Use the status filter (**Draft**) | Only drafts remain |

## It passes if

- [ ] Ticked lines become one draft order per vendor — no more, no fewer.
- [ ] Each order carries its own unique number.
- [ ] Draft orders can be edited.
- [ ] Ordered lines leave the order list.
- [ ] Generating with nothing ticked creates nothing and says why.
- [ ] The purchase-order list can be filtered by status.

## Watch out for

- **Two orders to the same vendor** where one was expected. That is a Major defect — the vendor gets two sheets for one shop.
- Quantities that differ between the order list and the resulting order.
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
