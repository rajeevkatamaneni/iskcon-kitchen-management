# UAT-043: Send an order on WhatsApp

| | |
|---|---|
| **Feature area** | Ordering — WhatsApp delivery |
| **Technical stories** | E5-S7 (WhatsApp PO delivery) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-041 |
| **Environment needs** | **A live WhatsApp channel.** Without it nothing is actually sent — the send is only recorded. Ask the environment owner before starting |

## What this feature is for

This is how Indian temple procurement actually happens. Staff do not email vendors; they WhatsApp
them. Sending the order from inside the app — rather than downloading it and switching to another
program — is what makes the ordering flow usable in practice.

## How it is supposed to work

- **Send on WhatsApp** goes to the vendor's recorded number. If the order is still a draft, sending it
  moves it to Sent, with a confirmation first.
- The translated sheet is sent when one exists; otherwise the English one.
- The send and its delivery status are recorded on the order's activity trail.
- A number that fails is surfaced on the order, with the fallback of downloading and sharing by hand,
  and the vendor is flagged for a phone recheck.
- Resending is allowed, but rate-limited, so a vendor is never spammed by an anxious sender.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/orders** → open the order to **Sri Balaji Provisions**
- **Set the vendor's phone to a number you control** (edit the vendor in UAT-037) so you can actually
  see the message arrive.
- If no WhatsApp channel is live, run steps 1, 2, 6, 7 and 8 only; mark the rest *blocked by
  environment*.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | On the order, find **Send on WhatsApp** | The action is offered on a draft or sent order |
| 2 | Press it on a **draft** order | You are told it will also mark the order as sent, and asked to confirm |
| 3 | Confirm | The order becomes **Sent**, and the activity trail records the WhatsApp send with who and when |
| 4 | Check the phone you set as the vendor's number | The order sheet arrives on WhatsApp — as a document or a link that opens one |
| 5 | Open what arrived | It is this order: the right number, the right vendor, the right lines |
| 6 | Press **Send on WhatsApp** again immediately | Refused: *This purchase order was just sent on WhatsApp* (`KMS-4925`), asking you to give the vendor a moment |
| 7 | Change the vendor's phone to a number that cannot receive WhatsApp (for example `+919999999999`) and send again after the rate limit passes | A clear failure on the order — not a crash — with guidance to download the sheet and share it by hand (`KMS-5201`) |
| 8 | Go to **/vendors** | That vendor is flagged for a WhatsApp recheck |
| 9 | Try to send a **cancelled** order | Refused: *This purchase order can't be sent to a vendor* (`KMS-4924`) |
| 10 | Read the activity trail on the order | Every send attempt is there, successful or not, with its outcome |

## It passes if

- [ ] The order can be sent to the vendor's WhatsApp number from inside the app.
- [ ] Sending a draft moves it to Sent, after a confirmation.
- [ ] The message actually arrives and contains the right order.
- [ ] An immediate resend is rate-limited (`KMS-4925`).
- [ ] An undeliverable number produces a clear failure with a usable fallback, and flags the vendor.
- [ ] A cancelled order cannot be sent.
- [ ] Every attempt is on the activity trail.

## Watch out for

- **Environment first.** With no channel configured, the app may report success while nothing was sent. That is the single most misleading state in the product — if you cannot confirm arrival on a real phone, write *"send reported success; arrival not verified"* rather than passing the test.
- A failure that produces no visible state on the order — staff would assume the vendor has the order.
- The rate limit being so tight that a legitimate resend after a genuine failure is blocked. Note how long you had to wait.
- The message arriving with the **English** sheet when a translated one exists (UAT-042).

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT043-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
