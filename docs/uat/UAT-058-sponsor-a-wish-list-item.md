# UAT-058: Sponsor a wish-list item

| | |
|---|---|
| **Feature area** | Donations — the wish list and sponsorship |
| **Technical stories** | E7-S6 (wish list and sponsorship checkout) |
| **Roles exercised** | Two signed-in donors, temple admin |
| **Depends on** | UAT-057 |
| **Environment needs** | **The payment provider in test mode** — same caveat as UAT-055 |

## What this feature is for

The other half of the wish list: a devotee browses what the temple needs, picks one, and funds it.
Sponsorship rides the same payment machinery as an ordinary donation — one pipeline, not two — so
everything true of UAT-055 is true here as well, including that the donor must be signed in and is
always named.

## How it is supposed to work

- The wish list is not a page of its own: it is the **Equipment** tab of the giving screen at
  **/donate**, next to the tab for giving money. A signed-in devotee sees each item with its price
  and, for multi-quantity items, how much is already sponsored.
- They pick how many units to sponsor, confirm their details (the same two paths as UAT-055 — with
  their name, or with 80G), and pay.
- The item's progress updates and the gift appears in the ledger **linked to the item**.
- If two people go for the last unit at once, the first confirmed payment gets it and the second is
  gracefully turned into a general donation, with the donor told — never a failed charge for money
  already taken.
- Sponsors are shown on the item by name. There is no anonymous sponsorship: it went on 2026-08-29
  with the rest of unauthenticated giving.

## Before you start

- **Sign in as:** `ikms.donor.1@trading4good.org`. For step 9 you need a second browser signed in as
  `ikms.donor.2@trading4good.org`. Both must belong to **Sri Sri Radha Govinda Temple**.
- **Start at:** **/donate**, then the **Equipment** tab. (**/wishlist** is a different screen — that is the admin's, from UAT-057.)
- Have the three items from UAT-057 in place, including the **single-unit trolley**.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | While signed out, open **/donate** | You are sent to sign in. There is no public wish-list address to open, and no way to reach the Equipment tab without an account |
| 2 | Sign in as `ikms.donor.1@…`, open **/donate** and press the **Equipment** tab | *Fund a concrete need — you'll know exactly what your gift provides.* The three items with prices |
| 3 | Look at a multi-quantity item (Sack of rice, 10 wanted) | Its progress is visible — 0 of 10 sponsored |
| 4 | Press **Sponsor** on the sacks of rice and choose **2** units | The amount comes to ₹2,600 |
| 5 | Choose **Give with my name**, agree to the notice, and pay with the test details | *Thank you 🙏 Your sponsorship is being processed* |
| 6 | Return to the **Equipment** tab | The rice shows **2 of 10** sponsored |
| 7 | Sponsor 8 more units | The item becomes fully sponsored and shows **Fulfilled 🙏** |
| 8 | Try to sponsor the fulfilled item again | Refused: *This wish-list item is no longer available to sponsor* (`KMS-4938`) |
| 9 | **The race:** with a single-unit item still open, have both donors press Sponsor and pay at the same moment | One becomes the sponsor. The other's money is **not** lost — it is turned into a general donation and they are told. Neither sees a failed charge for money taken |
| 10 | Look at the item again | Its sponsors are shown by name. Look for any way to have sponsored it without being named — there should be none |
| 11 | Sign in as `ikms.temple-admin.1@trading4good.org` and open **/ledger** | The sponsorships appear, typed **Wish list**, each **linked to its item** and to a named donor |
| 12 | Have the admin archive an item, then look for it on the Equipment tab | It is gone, and cannot be sponsored by any route |

## It passes if

- [ ] The Equipment tab of **/donate** is reachable only when signed in, and shows items, prices and sponsorship progress.
- [ ] A donor can sponsor one or several units and pay.
- [ ] Progress updates and a fully sponsored item becomes Fulfilled.
- [ ] A fulfilled or archived item cannot be sponsored (`KMS-4938`).
- [ ] The race for the last unit leaves nobody out of pocket and nobody without an explanation.
- [ ] Sponsorships appear in the ledger linked to their item and to a named donor.

## Watch out for

- **Environment first**, as in UAT-055: with the stub payment provider no checkout opens and nothing is confirmed, so progress will never move. Record it as *environment*.
- **A wish list that still opens without signing in**, at `/t/{temple-web-address}/wishlist` or anywhere else. That address was withdrawn on 2026-08-29; if it still answers, it is a Blocker.
- Progress that moves **before** payment is confirmed — a page showing 2 of 10 for a payment that was abandoned. Major defect.
- The race (step 9) producing two sponsors for one unit, or a charge with nothing to show for it. Either is a Blocker.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT058-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
