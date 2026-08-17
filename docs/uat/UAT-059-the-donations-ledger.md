# UAT-059: The donations ledger and export

| | |
|---|---|
| **Feature area** | Donations — ledger and accounting |
| **Technical stories** | E7-S7 (donations ledger and accounting view) |
| **Roles exercised** | Temple admin |
| **Depends on** | UAT-028 (in-kind), UAT-055 (one-time), UAT-058 (wish list); UAT-056 if recurring works |
| **Environment needs** | The monetary gifts need the payment provider to have confirmed them (UAT-055) |

## What this feature is for

"Properly accounted for" has to be a screen someone can open, not an aspiration. Every gift the temple
receives — online, regular, wish-list, or a sack of rice carried through the door — belongs in one
place the temple's accountant can filter, total and export.

## How it is supposed to work

- The ledger aggregates **all four kinds**: one-time, recurring, wish-list and in-kind.
- It can be filtered by **date range and type**, shows the donor (honouring anonymity), the amount, the
  payment mode, and what each gift is **linked to** — its wish-list item, its recurring plan, or its
  in-kind intake. A gift earmarked to none of those is not blank: it reads **General kitchen**, which
  is what the donate page promises a general gift does.
- **CSV export** matches what is on screen — the accountant's real interface.
- Summary totals are aligned to the **Indian financial year (April–March)**, because that is what 80G
  reporting runs on.
- Anonymous donors are shown as *Anonymous*, with no personal information anywhere, export included.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/ledger** (menu: **Donations ledger**)
- Have as many of the earlier donation tests done as possible. At minimum, UAT-028 (in-kind) works
  without any payment provider.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Donations ledger** | *Every gift — online, recurring, wish-list, in-kind — in one place*, with filters and an **Export CSV** action |
| 2 | Look at the type filter | **All**, One-time, Recurring, Wish list, In-kind |
| 3 | Filter to **In-kind** | The gifts from UAT-028, with their estimated values |
| 4 | Filter to **One-time** | The confirmed donations from UAT-055 |
| 5 | Filter to **Wish list** | The sponsorships from UAT-058, each showing which item it is linked to |
| 6 | Filter to **Recurring** | Whatever UAT-056 produced. If nothing, record that |
| 7 | Set a date range covering only today, then only last month | The rows change accordingly |
| 8 | Read a row in full | Date, Type, Donor, Amount, Mode, Linked to |
| 8a | Look at a plain money gift with no earmark — one-time or hand-recorded cash | **Linked to** reads *General kitchen*, never a dash |
| 8b | On **/donations**, record cash of `5000` with **Towards** set to a wish-list item that still needs more than that | The row reads **Linked to** *Wish list: (that item)*, and on **/wishlist** the item has moved on by ₹5,000 but is still wanted |
| 8c | Record cash covering the item's whole remaining cost | The item flips to **Fulfilled** — cash finishes it exactly as an online gift does |
| 9 | Find the anonymous gifts | Shown as **Anonymous** — no name, phone, email or PAN anywhere |
| 10 | Check the summary totals | They match the rows shown. Add a few by hand |
| 11 | Press **Export CSV** and open the file | It contains the same rows as the screen, with the same filters applied |
| 12 | Search the CSV for the anonymous donors' details | **Nothing identifying is in the file** |
| 13 | Check the financial-year boundary: if you can, record a gift dated 31 March and one dated 1 April | They fall into **different** financial years in the summary |
| 14 | Sign in as `ikms.kitchen-staff.1@trading4good.org` and type **/ledger** | *Not your page* — donations are for temple leadership |
| 15 | Compare the ledger's in-kind values with what you entered in UAT-028 | They match exactly |

## It passes if

- [ ] All four kinds of gift appear, filterable by type and date.
- [ ] Each row shows donor, amount, mode and what it is linked to — *General kitchen* when it is
      earmarked to nothing, never an empty cell.
- [ ] Cash handed over towards a wish-list item is linked to it, counts towards it, and can complete it.
- [ ] Anonymous gifts show as *Anonymous*, on screen and in the export.
- [ ] Totals reconcile with the rows.
- [ ] The CSV matches the filtered screen.
- [ ] The Indian financial year boundary is handled correctly.
- [ ] Kitchen staff cannot open the ledger.

## Watch out for

- **Money that is missing.** A donation you completed that never appears here is the most serious defect in this test. Before logging it, check whether the payment was actually confirmed (UAT-055) — an unconfirmed donation legitimately never reaches the ledger, and that is an environment issue, not a lost gift.
- Any personal information in the CSV for an anonymous donor — Blocker.
- Totals that do not add up. Do the arithmetic on a small filtered set.
- A donation from the **other temple** appearing here — Blocker (see UAT-006).
- Amounts shown without currency, or in the wrong currency for the temple.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT059-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
