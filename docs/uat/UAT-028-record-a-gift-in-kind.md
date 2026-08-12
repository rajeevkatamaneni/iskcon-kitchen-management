# UAT-028: Record a gift of goods

| | |
|---|---|
| **Feature area** | Inventory — in-kind donation intake |
| **Technical stories** | E3-S5 (in-kind donation intake) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-022 (items are tracked) |
| **Environment needs** | The **thank-you message** needs a live channel; everything else works without one |

## What this feature is for

Devotees turn up with a sack of rice or a tin of ghee. Those gifts are real inventory and real
donations: the store room must know about them, the accounts must value them, and the donor should be
thanked. This one screen does all three.

## How it is supposed to work

- A gift can be **food** (which becomes stock, with its batch and expiry), **equipment** (which becomes
  a register entry, UAT-027), or both in one visit.
- The donor may be named — with contact details, so they can be thanked — or **anonymous**, in which
  case **no personal details are kept at all**.
- An estimated value in rupees is recorded for the temple's accounts, and the gift appears in the
  donations ledger (UAT-059) marked as in-kind.
- Where the donor gave contact details, a thank-you can be sent. This is an acknowledgement, not an 80G
  tax certificate.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/donations** (menu: **Donations**)
- Make sure Rice, Ghee and Sugar are tracked (UAT-022) — a gift of an untracked ingredient is worth
  trying too, at step 9.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Donations** | *Record an in-kind donation* form and a **Recent donations** list |
| 2 | Read the form | Anonymous donor tick; donor name; phone (for thank-you); email; date; estimated value; notes; a **Food** section; an **Equipment** section |
| 3 | Enter donor `Ramesh Sharma`, phone `+919812345678`, today's date, estimated value `4500` | Accepted |
| 4 | In **Food**, add `Rice` quantity `50` Kg with an expiry six months out, and `Ghee` `5` L | Two food lines |
| 5 | Press **Record donation** | *Donation recorded. Thank you for logging it.* and a new row in Recent donations showing the donor, items, estimated value, and whether a thank-you was sent |
| 6 | Go to **/inventory** → **Rice** | Stock has risen by **50 Kg**, as a **new batch** with the expiry you entered and today as its received date |
| 7 | Look at Rice's **Movement history** | A row of type *gift / donation in kind*, +50, referencing this donation |
| 8 | Record a second gift, this time ticking **Anonymous donor**, 10 Kg Sugar, value `800` | Recorded; the list shows **Anonymous** with no name, phone or email anywhere |
| 9 | Record a gift of an ingredient that is **not** yet tracked in inventory | Record what happens — is the ingredient offered at all, is it tracked automatically, or is it refused? |
| 10 | Record a gift of **equipment**: a `Stainless Steel Cauldron 100L`, and no food lines | Recorded. Go to **/equipment** — it is registered with source **Donated**, linked back to the donation |
| 11 | Press **Record donation** with neither food nor equipment | Refused: *Add at least one food item or piece of equipment* |
| 12 | *(Channel live)* Check whether Ramesh Sharma's thank-you was sent | The list shows **Sent** against that donation, and the message arrives on the contact given |
| 13 | Go to **/ledger** as the temple admin | Both gifts appear, typed **In-kind**, with their estimated values; the anonymous one shows *Anonymous* |

## It passes if

- [ ] A gift of food increases stock immediately, as a batch with the right expiry and received date.
- [ ] The movement history shows the gift and links back to the donation.
- [ ] An anonymous gift stores no donor details anywhere on any screen.
- [ ] A gift of equipment lands in the equipment register with source **Donated**, linked to the donation.
- [ ] An empty donation is refused.
- [ ] The gifts appear in the donations ledger marked in-kind, with their estimated values.
- [ ] *(If a channel is live)* A named donor with contact details gets a thank-you.

## Watch out for

- Stock rising by the wrong amount, or in the wrong unit (50 Kg becoming 50 gm).
- An anonymous gift that still records something identifying — check the ledger and the donation list carefully. Any trace is a Blocker.
- The estimated value being lost between this screen and the ledger.
- **Step 9 is deliberately open.** How the product handles a gift of something not yet in inventory is a real operational question (devotees bring what they bring). Record exactly what happened; there may be no story covering it, which would be root cause R6.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT028-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
