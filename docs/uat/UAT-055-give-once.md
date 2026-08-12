# UAT-055: Give once — named, anonymous, or with 80G

| | |
|---|---|
| **Feature area** | Donations — one-time giving and donor data |
| **Technical stories** | E7-S2 (one-time donation), E7-S4 (80G capture and anonymity), E7-S9 (payment webhooks) |
| **Roles exercised** | The public; temple admin for the checks |
| **Depends on** | UAT-054 |
| **Environment needs** | **The payment provider in test mode.** Without it no checkout opens and the donation is never confirmed — the record stays pending and never reaches the ledger. Ask the environment owner first |

## What this feature is for

The act of giving itself, and the choice that goes with it: a donor decides whether to be named, to be
anonymous, or to hand over the details the law requires for a tax certificate. India's rules make this
consequential — an anonymous donor cannot receive an 80G certificate, and PAN is required for one.

## How it is supposed to work

- Three paths, chosen before payment:
  - **Anonymous** — **no personal details are kept at all**, and a warning that no 80G certificate is possible.
  - **Named** — name and contact, so the temple can thank them. No tax certificate without PAN.
  - **80G** — name, address and **PAN**, shown only at a temple approved for 80G. PAN is stored encrypted and only a Temple Admin can see it.
- Agreeing to the data-use notice is required on the two named paths.
- The donation is recorded **before** checkout as pending, and only becomes complete when the payment
  provider confirms it — never on the strength of the browser saying so.
- A confirmation and thanks go to the donor. That is an acknowledgement, not the 80G certificate.

## Before you start

- **No sign-in.** Private window.
- **Start at:** **/t/sri-sri-radha-govinda-temple/donate** (the 80G-approved temple)
- **Ask the environment owner** for the payment provider's state and, if it is in test mode, the test
  card or test UPI details.
- You will need `ikms.temple-admin.1@trading4good.org` for the ledger checks at the end.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Choose ₹501 and select **Give with my name** | Name, Phone, Email fields and a data-use agreement tick |
| 2 | Press donate **without** ticking the agreement | Refused: *Please agree to the data-use notice to continue with your details* (`KMS-4937`), offering the anonymous path instead |
| 3 | Fill name `Test Donor One`, phone, email; tick the agreement; donate | *(With the provider live)* the payment checkout opens. *(Without it)* you go straight to a thank-you screen and the donation stays unconfirmed — record which happened |
| 4 | *(Provider live)* Complete the payment with the test details | *Hare Krishna 🙏* and a thank-you naming the temple |
| 5 | Choose **Give anonymously** and give ₹101 | **No** name or contact fields appear at all, and a warning that an anonymous gift keeps no personal details and cannot receive an 80G certificate |
| 6 | Complete it | Thank-you screen |
| 7 | Choose **Give with an 80G tax certificate** and give ₹1,001 | Name, Phone, Email, **Address** and **PAN** fields, with a line explaining PAN is required by law and stored encrypted |
| 8 | Enter an invalid PAN such as `12345` | Refused: *That PAN doesn't look right — a PAN is ten characters, like ABCDE1234F* (`KMS-4004`) |
| 9 | Enter a valid-format PAN (`ABCDE1234F`), an address, and complete | Thank-you screen |
| 10 | Go to the **other** temple's page, **/t/iskcon-chowpatty/donate** | The 80G option is **not offered** — and if forced, refused (`KMS-4936`) |
| 11 | Sign in as `ikms.temple-admin.1@trading4good.org` and open **/ledger** | The completed donations appear: ₹501 named, ₹101 as **Anonymous**, ₹1,001 named |
| 12 | Look at the anonymous row closely | **No name, no phone, no email, nothing identifying** — anywhere on the row or its detail |
| 13 | Look at the 80G row's detail | The donor's name, address and PAN are visible **to the admin**; note whether the PAN is masked or shown in full |
| 14 | *(Provider live)* Check the donor's phone or email | A confirmation and thanks arrived |
| 15 | *(Provider live)* Ask the environment owner to replay the payment confirmation | The donation is **not** recorded twice, and no amount doubles |

## It passes if

- [ ] All three donor paths work and capture exactly their own fields.
- [ ] An anonymous gift stores nothing identifying, anywhere.
- [ ] The data-use agreement is required on the named paths (`KMS-4937`).
- [ ] PAN format is validated (`KMS-4004`) and only offered at an 80G-approved temple.
- [ ] Completed donations reach the ledger with the right amounts and donor display.
- [ ] A replayed payment confirmation does not double-count.

## Watch out for

- **Environment first.** With the stub payment provider, pressing donate takes you to a thank-you screen with **no checkout at all**, and the donation is recorded as unconfirmed — it will never appear in the ledger. If that is what you see, write *"no checkout opened; donation not confirmed — payment provider not configured"* and mark steps 4, 11–15 blocked. That is root cause R5, not a product defect.
- Any trace of the anonymous donor's identity. Check the ledger, the export (UAT-059) and any donor list. Any trace at all is a Blocker.
- A thank-you screen appearing when the payment actually failed or was abandoned. Try closing the checkout without paying and then check the ledger — the donation must **not** show as complete.
- PAN visible to anyone other than a Temple Admin — check as kitchen staff (they should not reach the ledger at all).

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT055-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
