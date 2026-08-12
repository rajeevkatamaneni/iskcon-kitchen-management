# UAT-040: Send and cancel a purchase order

| | |
|---|---|
| **Feature area** | Ordering — purchase order lifecycle |
| **Technical stories** | E5-S3 (purchase order generation and lifecycle) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-039 |
| **Environment needs** | None (sending on WhatsApp is UAT-043) |

## What this feature is for

A purchase order moves through a life: drafted, sent to the vendor, partly delivered, complete — or
cancelled. Each step has to be deliberate and recorded, so that "did we actually order that?" always
has an answer.

## How it is supposed to work

- The states are **Draft → Sent → Partially received → Received**, or **Cancelled**.
- An order can be edited **only while it is a draft**.
- Cancelling requires a **reason**.
- Every step records who did it and when, and the order carries an activity trail you can read.
- Steps that make no sense — receiving a draft, editing a sent order — are refused.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/orders** (menu: **Purchase orders**), with the two draft orders from UAT-039.
- **Keep the Sri Balaji order for UAT-044** (you will receive a delivery against it). Use the Nandini
  order, or a fresh one, for the cancellation steps.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open the **Sri Balaji Provisions** draft order | Its number, vendor, lines, and actions including **Mark sent**, **Cancel**, **Print**, **Generate PDF** |
| 2 | Edit a line quantity while it is a draft | Accepted |
| 3 | Press **Mark sent** | Status becomes **Sent**; the activity trail records who sent it and when |
| 4 | Try to edit a line now | Refused: *This purchase order can no longer be edited* (`KMS-4919`) |
| 5 | Look at the **Activity** section | Entries for creation and for sending, each with an actor and a time |
| 6 | Look at **Documents** | Either a sheet generated on sending, or a note that none exists yet (UAT-041) |
| 7 | Open the **Nandini** draft order and press **Cancel** | You are asked for a **reason** before anything happens |
| 8 | Try to cancel with the reason blank | Refused — a cancellation must be explained |
| 9 | Cancel with the reason `Vendor closed for the festival week` | Status becomes **Cancelled**; the reason is on the record |
| 10 | Try to **Mark sent** the cancelled order | Refused (`KMS-4920` or `KMS-4924`) — a cancelled order is closed |
| 11 | Try to **Receive delivery** on the cancelled order | Refused |
| 12 | Go to **/orders** and filter by status | Draft, Sent and Cancelled orders each appear under their own filter |
| 13 | Check the order list columns | Vendor, Status, Needed by, Ordered — enough to see at a glance what is outstanding |

## It passes if

- [ ] A draft can be edited; a sent order cannot (`KMS-4919`).
- [ ] Marking sent changes the status and records who and when.
- [ ] Cancelling requires a reason and records it.
- [ ] A cancelled order cannot be sent or received against.
- [ ] The activity trail reads as a history of the order.
- [ ] The status filter works.

## Watch out for

- An order that can be edited after sending — that means the vendor and the temple hold different documents. Blocker.
- A cancellation with no reason recorded, or a reason that vanishes from the trail.
- Status changing without the activity trail gaining an entry.
- Whether an order can go **backwards** (Sent → Draft). Try it; record what happens.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT040-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
