# UAT-010: Your profile and how the temple reaches you

| | |
|---|---|
| **Feature area** | Platform foundation — contact channels and consent |
| **Technical stories** | E1-S8 (contact channels and communication preference) |
| **Roles exercised** | Temple admin, kitchen staff, volunteer |
| **Depends on** | UAT-008 |
| **Environment needs** | None to set a preference. Whether a message actually arrives on that channel is UAT-052 |

## What this feature is for

Every person the temple contacts — for shift reminders, low-stock digests, receipts — chooses **where**
those messages reach them, and records their agreement to be contacted at all. In India that choice
matters: WhatsApp is where most people actually look, but some prefer SMS or email. This is also the
temple's record of consent, which the law expects it to hold.

## How it is supposed to work

- Your name, email and phone are shown but **not editable here** — they were set when your account was
  created, and changing them is the administrator's job.
- You pick one preferred channel: **WhatsApp**, **SMS** or **Email**. WhatsApp rides your phone number.
- Until you agree to be contacted, the temple does not send you reminders. Consent is recorded with the
  date and the wording you agreed to.
- The choice takes effect on the next message sent to you.

## Before you start

- **Sign in as:** `ikms.volunteer.1@trading4good.org` (volunteer). Then repeat as
  `ikms.kitchen-staff.1@trading4good.org` and `ikms.temple-admin.1@trading4good.org`.
- **Start at:** **/profile** (menu: **Profile**)

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Profile** | *Your account — how your temple reaches you, and your consent to be contacted* |
| 2 | Read the **Contact details** panel | Your name, email and phone, with a line saying they were set when your account was created and the administrator can change them. The fields cannot be typed into |
| 3 | Read the **Preferred channel** section | Three choices — WhatsApp (with a note that it rides your phone number), SMS, Email — one of them already selected |
| 4 | Change the channel to **Email** | The choice is saved without needing a separate Save button, or with a clear confirmation |
| 5 | Reload the page | **Email** is still selected — it was really saved, not just shown |
| 6 | Change it back to **WhatsApp** and reload | WhatsApp persists |
| 7 | Read the **Consent to be contacted** section | Plain wording saying what the temple will send (reminders and service messages), on which channels, and that you may withdraw. Until you agree, a line reads *Until you agree, we won't send you reminders* |
| 8 | Choose **I agree** | The consent is recorded; the prompt is replaced by confirmation that you have agreed |
| 9 | Reload | Consent is still recorded |
| 10 | Sign out; repeat steps 1–9 as kitchen staff and as a temple admin | The same screen works identically for all three roles |
| 11 | Sign in as `ikms.super-admin.1@trading4good.org` and look for **Profile** in the menu | It is **absent**, and typing **/profile** is refused — a platform operator has no temple to be contacted by |

## It passes if

- [ ] Contact details are shown and cannot be edited on this page.
- [ ] A preferred channel can be chosen from the three options and survives a reload.
- [ ] The consent wording explains purpose, channels and the right to withdraw, in plain English.
- [ ] Agreeing is recorded and survives a reload.
- [ ] The page works for temple admin, kitchen staff and volunteer, and is absent for the platform operator.

## Watch out for

- A choice that appears to save but reverts on reload — check every time.
- Consent wording written in legal or technical language. It is meant to be readable by a devotee with no computer background.
- Any way to edit your own email or phone here. If you can, note it as Major — those are the addresses the temple verified.
- If the page shows an error code, record it. `KMS-4001` would mean the channel you picked was not recognised.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT010-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
