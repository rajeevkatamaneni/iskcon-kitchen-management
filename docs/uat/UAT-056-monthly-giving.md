# ~~UAT-056: Monthly giving~~ · WITHDRAWN 2026-09-10

> ## ⛔ WITHDRAWN 2026-09-10 — do not run this script
>
> **The feature this script tests is not being built for release 1.** Recurring donations left Phase 1
> entirely on Rajeev's ruling of 2026-09-10 — *"needs to be researched properly and built. we will do
> it later as an engagement once the app is live"* — and now sit in the **Phase 2 Backlog**
> (REQUIREMENTS.md §4). What had been built of them is being removed: the `recurring_plans` table and
> the plan endpoints, and the donor-facing recurring screen. The story behind this script, **E7-S3**,
> is withdrawn with it.
>
> **What is true now:** **Phase 1 giving is one-time only.** A devotee who wants to give every month
> gives every month, one gift at a time. Nothing registers a UPI Autopay or eNACH mandate, no
> subscription is created at the gateway, and no cycle-charge webhook reaches the product. The
> temple's ledger has three kinds of gift, not four — one-time, wish-list and in-kind — and its type
> filter no longer offers *Recurring* (UAT-059). One-time giving is tested by **UAT-055** and is
> unaffected.
>
> **Gap G4 closes by withdrawal, not by being built.** The traceability pack recorded that recurring
> giving had no donor-facing surface; the answer turned out to be that there is no recurring giving.
>
> **The script below is left exactly as written, and that is deliberate.** A UAT script is a record of
> what was tested, and deleting it would lose the fact that recurring giving was scoped, specified and
> consciously deferred rather than forgotten — and it is the readiest description of the behaviour the
> Phase 2 engagement will have to deliver. The banner is here to stop somebody running it for a
> feature that is not in release 1. Marked under Rajeev's sign-off of 2026-09-10.


| | |
|---|---|
| **Feature area** | Donations — recurring giving |
| **Technical stories** | E7-S3 (recurring donation) |
| **Roles exercised** | Donor (with an account), temple admin |
| **Depends on** | UAT-055 |
| **Environment needs** | **The payment provider in test mode**, with subscriptions/mandates enabled |

## What this feature is for

Steady support matters more to a temple than occasional generosity. A donor sets up a regular gift once
— weekly, monthly, quarterly or yearly — and it continues without them having to remember. Because a
mandate has to belong to somebody, recurring giving requires an account, unlike a one-off gift.

## How it is supposed to work

- The donation page offers a choice between **giving once** and **giving regularly**, and a frequency.
- Setting one up creates a mandate with the payment provider and a plan record here; each charge
  afterwards is recorded as a donation attached to that plan.
- The donor can **see their plan, its payment history, and cancel it** themselves. Cancelling stops
  future charges.
- A failed charge is recorded, and the donor is told with guidance on what to do.
- The temple's ledger distinguishes recurring gifts from one-off ones.

## Before you start

- **Sign in as:** `ikms.donor.1@trading4good.org` (a donor with an account)
- **Start at:** **/donate**
- **Ask the environment owner** whether the payment provider supports subscriptions in this environment.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open the donation page | Read it carefully. **Look for a choice between a one-time and a recurring gift** |
| 2 | **Record exactly what you find.** If there is no such choice anywhere on the page, write that down and go to step 8 | — |
| 3 | *(If the choice exists)* Choose **recurring**, frequency **monthly**, amount ₹501 | A frequency choice: weekly / monthly / quarterly / yearly |
| 4 | Try to set up a recurring gift **without** signing in | You are asked to sign in or create an account first, with a one-line explanation of why regular giving needs one |
| 5 | Signed in as the donor, complete the mandate with the provider's test details | Confirmation that the regular gift is set up |
| 6 | Find where the donor can **see their plan** — its status, its payment history, and a way to cancel | Record where it is. If there is nowhere, write that down |
| 7 | Cancel the plan | Future charges stop; the plan shows as cancelled; a confirmation is sent |
| 8 | Sign in as `ikms.temple-admin.1@trading4good.org` and open **/ledger** | Filter by **Recurring**. Record whether any recurring donation appears, and whether it is linked to a plan |
| 9 | Ask the environment owner to trigger a **failed** charge in test mode | The failure is recorded, and the donor is notified with guidance |
| 10 | Check the ledger again | One-time and recurring gifts are distinguishable |

## It passes if

- [ ] A donor can choose to give regularly, at a frequency they pick.
- [ ] Recurring giving requires an account, with the reason explained.
- [ ] The donor can see the plan's status and history, and cancel it themselves.
- [ ] Cancelling stops future charges.
- [ ] Each cycle's charge is recorded against the plan and appears in the ledger as recurring.
- [ ] A failed charge is recorded and the donor is told.

## Watch out for

- **Steps 1–2 and 6 are the point of this test.** The donation page's donor-facing choice and the
  donor's self-service view of their plan are what a giver actually touches. If either is missing, do
  not improvise a way round it: write down precisely what you looked for and where you looked. A
  "recurring" filter existing on the temple's ledger while a donor has no way to start or manage one is
  exactly the kind of half-built capability this pack exists to find, and it points at root cause R6.
- A recurring gift that charges immediately as well as on schedule (double charge on day one).
- Cancellation that stops the local record but not the mandate at the provider — ask the environment owner to confirm on the provider's side.
- The frequency shown to the donor not matching what was actually set up.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT056-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
