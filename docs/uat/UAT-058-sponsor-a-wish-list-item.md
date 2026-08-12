# UAT-058: Sponsor a wish-list item

| | |
|---|---|
| **Feature area** | Donations — public wish list and sponsorship |
| **Technical stories** | E7-S6 (public wish list and sponsorship checkout) |
| **Roles exercised** | The public, two donors, temple admin |
| **Depends on** | UAT-057 |
| **Environment needs** | **The payment provider in test mode** — same caveat as UAT-055 |

## What this feature is for

The other half of the wish list: a devotee browses what the temple needs, picks one, and funds it.
Sponsorship rides the same payment machinery as an ordinary donation — one pipeline, not two — so
everything true of UAT-055 is true here as well.

## How it is supposed to work

- The public page shows each item with its price and, for multi-quantity items, how much is already
  sponsored.
- A donor picks how many units to sponsor, gives their details (the same three paths as UAT-055 —
  named, anonymous, 80G), and pays.
- The item's progress updates and the gift appears in the ledger **linked to the item**.
- If two people go for the last unit at once, the first confirmed payment gets it and the second is
  gracefully turned into a general donation, with the donor told — never a failed charge for money
  already taken.
- Named sponsors may be shown on the item; anonymous ones never are.

## Before you start

- **No sign-in** for the main flow (private window). For step 9 you need two browsers, ideally
  `ikms.donor.1@` and `ikms.donor.2@trading4good.org`.
- **Start at:** **/t/sri-sri-radha-govinda-temple/wishlist**
- Have the three items from UAT-057 in place, including the **single-unit trolley**.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open the public wish list | *Fund a concrete need — you'll know exactly what your gift provides.* The three items with prices |
| 2 | Look at a multi-quantity item (Sack of rice, 10 wanted) | Its progress is visible — 0 of 10 sponsored |
| 3 | Press **Sponsor** on the sacks of rice and choose **2** units | The amount comes to ₹2,600 |
| 4 | Choose **Give with my name**, fill the details, agree to the notice, and pay with the test details | *Thank you 🙏 Your sponsorship is being processed* |
| 5 | Reload the public page | The rice shows **2 of 10** sponsored |
| 6 | Sponsor 8 more units | The item becomes fully sponsored and shows **Fulfilled 🙏** |
| 7 | Try to sponsor the fulfilled item again | Refused: *This wish-list item is no longer available to sponsor* (`KMS-4938`) |
| 8 | Sponsor the **trolley** (single unit) anonymously | Completed; no personal details captured |
| 9 | **The race:** with a single-unit item still open, have two donors press Sponsor and pay at the same moment | One becomes the sponsor. The other's money is **not** lost — it is turned into a general donation and they are told. Neither sees a failed charge for money taken |
| 10 | Look at the item's public page | Named sponsors may be shown; the anonymous sponsor is **not** named anywhere |
| 11 | Sign in as `ikms.temple-admin.1@trading4good.org` and open **/ledger** | The sponsorships appear, typed **Wish list**, each **linked to its item** |
| 12 | Check the ledger entry for the anonymous sponsorship | Shows *Anonymous*, with nothing identifying |
| 13 | Check an archived item's address directly | It cannot be sponsored |

## It passes if

- [ ] The public wish list shows items, prices and sponsorship progress.
- [ ] A donor can sponsor one or several units and pay.
- [ ] Progress updates and a fully sponsored item becomes Fulfilled.
- [ ] A fulfilled or archived item cannot be sponsored (`KMS-4938`).
- [ ] The race for the last unit leaves nobody out of pocket and nobody without an explanation.
- [ ] Sponsorships appear in the ledger linked to their item, honouring anonymity.

## Watch out for

- **Environment first**, as in UAT-055: with the stub payment provider no checkout opens and nothing is confirmed, so progress will never move. Record it as *environment*.
- Progress that moves **before** payment is confirmed — a page showing 2 of 10 for a payment that was abandoned. Major defect.
- The race (step 9) producing two sponsors for one unit, or a charge with nothing to show for it. Either is a Blocker.
- An anonymous sponsor named anywhere — on the item, in the ledger, in the export. Blocker.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT058-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
