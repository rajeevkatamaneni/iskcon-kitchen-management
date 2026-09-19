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
  moves it to Sent at once. *(Amended 2026-09-19: there is no confirmation step, and the order page has
  no activity trail any more; it shows **Sent <date>** in its header.)*
- The translated sheet is sent when one exists; otherwise the English one.
- The order's PDF sheet goes with the message (T-200), written the way the screens write it: packs as
  **4 × Bag (25 Kg)**, dates like **20 Sept 2026**.
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
| 2 | Press it on a **draft** order | It is sent straight away, with no question first |
| 3 | Read the order's header | The chip reads **Sent** and the header **Sent** *&lt;today&gt;* |
| 4 | Check the phone you set as the vendor's number | The order sheet arrives on WhatsApp — as a document or a link that opens one |
| 5 | Open what arrived | It is this order: the right number, the right vendor, the right lines |
| 6 | Press **Send on WhatsApp** again immediately | Refused: *This purchase order was just sent on WhatsApp* (`KMS-400056`), asking you to give the vendor a moment |
| 7 | Change the vendor's phone to a number that cannot receive WhatsApp (for example `+919999999999`) and send again after the rate limit passes | A clear failure on the order — not a crash — with guidance to download the sheet and share it by hand (`KMS-500002`) |
| 8 | Go to **/vendors** | That vendor is flagged for a WhatsApp recheck |
| 9 | Try to send a **cancelled** order | Refused: *This purchase order can't be sent to a vendor* (`KMS-400055`) |
| 10 | Send order A (UAT-039) to your own number and open the PDF that arrives | UAT Bulk Rice reads **4 × Bag (25 Kg)** with **₹1,450 / bag · ₹58 / Kg**; dates read like **20 Sept 2026**; no figure of 1,000 gm or more |

## It passes if

- [ ] The order can be sent to the vendor's WhatsApp number from inside the app.
- [ ] Sending a draft moves it to Sent.
- [ ] The message actually arrives and contains the right order.
- [ ] An immediate resend is rate-limited (`KMS-400056`).
- [ ] An undeliverable number produces a clear failure with a usable fallback, and flags the vendor.
- [ ] A cancelled order cannot be sent.
- [ ] The PDF that arrives writes packs, prices and dates as the screens do.

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
