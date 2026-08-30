# UAT-055: Give once — with your name, or with 80G

| | |
|---|---|
| **Feature area** | Donations — one-time giving and donor data |
| **Technical stories** | E7-S2 (one-time donation), E7-S4 (80G donor data capture), E7-S9 (payment webhooks) |
| **Roles exercised** | A signed-in donor; temple admin for the checks |
| **Depends on** | UAT-008 (the donor has an account at the temple) |
| **Environment needs** | **The payment provider in test mode.** Without it no checkout opens and the donation is never confirmed — the record stays pending and never reaches the ledger. Ask the environment owner first |

## What this feature is for

The act of giving itself, and the one choice that goes with it: whether to hand over the details the
law requires for a tax certificate. India's rules make that consequential — a PAN is required for an
80G certificate, and a gift given without one is thanked but never certificated.

**Giving requires an account.** As of 2026-08-29 there is no public donation page and no guest
checkout: a devotee signs in, gives from inside the application, and every donation carries their
name. Anonymous online giving was withdrawn on that date. (Anonymity survives in one place only —
a staff member recording a gift somebody brought to the temple in person, which is UAT-028.)

## How it is supposed to work

- Two paths, chosen before payment:
  - **With my name** — the name and contact already on the donor's account, confirmed rather than
    typed again. No tax certificate without a PAN.
  - **80G** — additionally address and **PAN**, shown only at a temple approved for 80G. PAN is
    stored encrypted and only a Temple Admin can see it.
- Agreeing to the data-use notice is required before details are stored.
- The donation is recorded **before** checkout as pending, and only becomes complete when the payment
  provider confirms it — never on the strength of the browser saying so.
- A confirmation and thanks go to the donor. That is an acknowledgement, not the 80G certificate.

## Before you start

- **Sign in as:** `ikms.donor.1@trading4good.org`, who must already belong to **Sri Sri Radha Govinda
  Temple** (the 80G-approved one). Add them in UAT-008 if they do not.
- **Start at:** **/donate**
- **Ask the environment owner** for the payment provider's state and, if it is in test mode, the test
  card or test UPI details.
- You will need `ikms.temple-admin.1@trading4good.org` for the ledger checks at the end.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | While completely signed out, type **/donate** into the address bar | You are sent to sign in, not to a giving screen. There is no way to give without an account |
| 2 | Sign in as `ikms.donor.1@trading4good.org` and open **/donate** | The giving screen, naming **Sri Sri Radha Govinda Temple** |
| 3 | Choose ₹501 and select **Give with my name** | Your own name, phone and email, already filled from your account, and a data-use agreement tick |
| 4 | Press donate **without** ticking the agreement | Refused: *Please agree to the data-use notice to continue with your details* (`KMS-4937`) |
| 5 | Tick the agreement and donate | *(With the provider live)* the payment checkout opens. *(Without it)* you go straight to a thank-you screen and the donation stays unconfirmed — record which happened |
| 6 | *(Provider live)* Complete the payment with the test details | *Hare Krishna 🙏* and a thank-you naming the temple |
| 7 | Choose **Give with an 80G tax certificate** and give ₹1,001 | Your name, phone and email as before, plus **Address** and **PAN**, with a line explaining PAN is required by law and stored encrypted |
| 8 | Enter an invalid PAN such as `12345` | Refused: *That PAN doesn't look right — a PAN is ten characters, like ABCDE1234F* (`KMS-4004`) |
| 9 | Enter a valid-format PAN (`ABCDE1234F`), an address, and complete | Thank-you screen |
| 10 | Look for any way to give **without** being named — an anonymity tick, a "give anonymously" option, a guest link | There is none. If you find one, record it: anonymous online giving was withdrawn on 2026-08-29 |
| 11 | Sign out. Sign in as a donor who belongs to **ISKCON Chowpatty** and open **/donate** — this step checks that a donor gives to the temple they are signed in at, and to no other, and that the 80G option follows that temple's own approval. Arrange such an account beforehand if there is not one | The screen names **ISKCON Chowpatty**, and the 80G option is **not offered** — that temple is not 80G-approved — and if forced, refused (`KMS-4936`) |
| 12 | Sign in as `ikms.temple-admin.1@trading4good.org` and open **/ledger** | The completed donations appear against the donor's name: ₹501 and ₹1,001 |
| 13 | Look at the 80G row's detail | The donor's name, address and PAN are visible **to the admin**; note whether the PAN is masked or shown in full |
| 14 | *(Provider live)* Check the donor's phone or email | A confirmation and thanks arrived |
| 15 | *(Provider live)* Ask the environment owner to replay the payment confirmation | The donation is **not** recorded twice, and no amount doubles |

## It passes if

- [ ] Giving is impossible without signing in, and every donation carries the donor's name.
- [ ] Both donor paths work and capture exactly their own fields.
- [ ] The data-use agreement is required before details are stored (`KMS-4937`).
- [ ] PAN format is validated (`KMS-4004`) and only offered at an 80G-approved temple.
- [ ] Completed donations reach the ledger with the right amounts and the right donor.
- [ ] A replayed payment confirmation does not double-count.

## Watch out for

- **Environment first.** With the stub payment provider, pressing donate takes you to a thank-you screen with **no checkout at all**, and the donation is recorded as unconfirmed — it will never appear in the ledger. If that is what you see, write *"no checkout opened; donation not confirmed — payment provider not configured"* and mark steps 6, 12–15 blocked. That is root cause R5, not a product defect.
- **Any surviving way in without an account** — a public donation address, a guest checkout, an anonymity tick, a donate link in an email. All of these were withdrawn on 2026-08-29; any that still works is a defect, and a public one is a Blocker.
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
