# UAT-006: One temple cannot see another

| | |
|---|---|
| **Feature area** | Platform foundation — tenant isolation |
| **Technical stories** | E1-S3 (tenant model and row-level security), E1-S5 (role-based access control) |
| **Roles exercised** | Two temple admins |
| **Depends on** | UAT-002 (two temples exist), UAT-013 or later (so there is data to *not* see) |
| **Environment needs** | None |

## What this feature is for

Every temple on this platform is a sealed room. One temple's recipes, stock, people, orders and
donations must be invisible to every other temple — not because the screens hide them, but because
the database itself refuses to hand them over. This test is the human check on that promise.

## How it is supposed to work

- Which temple you belong to comes from your **verified sign-in**, never from anything in the address
  bar or the page. There is no way to ask for another temple's data by changing a number in a URL.
- Isolation is enforced at the database, underneath the application, so even a mistake in the app
  cannot leak across temples.
- **There is no exception.** Donation and wish-list pages used to be public, identified by the
  temple's own web address; that carve-out was withdrawn on 2026-08-29, when giving was made
  signed-in only. Every screen in the product now sits behind a verified sign-in, and every one of
  them is scoped to the temple that sign-in belongs to.

## Before you start

- **You will sign in as two people:** `ikms.temple-admin.1@trading4good.org` (Sri Sri Radha Govinda
  Temple) and `ikms.temple-admin.2@trading4good.org` (ISKCON Chowpatty).
- **Best run after** some data exists in temple 1 — recipes (UAT-015), inventory (UAT-022), people
  (UAT-008). The more there is in temple 1, the more meaningful "temple 2 sees none of it" becomes.
- Have a notepad: you will copy some web addresses containing identifiers.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Sign in as `ikms.temple-admin.1@…` and go to **/recipes** | The recipes you created for Sri Sri Radha Govinda Temple |
| 2 | Open one recipe and **copy the full address** from the address bar (it ends in a long identifier) | Noted down |
| 3 | Go to **/users** and note the names of the people at this temple | Noted down |
| 4 | Go to **/inventory** and note what is tracked | Noted down |
| 5 | Sign out completely. Sign in as `ikms.temple-admin.2@trading4good.org` | You land in ISKCON Chowpatty's workspace |
| 6 | Go to **/recipes** | **Empty** — *No recipes found*. None of temple 1's recipes appear |
| 7 | Paste the recipe address you copied in step 2 into the address bar | You are **not** shown the recipe. A "couldn't find it" message is correct; the recipe's contents appearing is a critical failure |
| 8 | Go to **/users** | Only ISKCON Chowpatty's own people — `ikms.temple-admin.2@…` and anyone you add there. None of temple 1's staff or volunteers |
| 9 | Go to **/inventory**, **/equipment**, **/vendors**, **/orders**, **/invoices**, **/planner**, **/ledger**, **/audit** in turn | Each is empty, or shows only ISKCON Chowpatty's own data. Nothing from temple 1 anywhere |
| 10 | Open **/donate** while signed in as temple 2's admin | The giving screen for **ISKCON Chowpatty**, temple 2's own temple. Nothing of temple 1's appears |
| 11 | Try **/t/sri-sri-radha-govinda-temple/donate** — the old public address | Nothing there. That address was withdrawn on 2026-08-29; a page that still loads temple 1's details is a defect |

## It passes if

- [ ] Signed in as temple 2, no screen shows any of temple 1's recipes, people, stock, vendors, orders, invoices, plans, donations or audit entries.
- [ ] Pasting a direct link to one of temple 1's records while signed in as temple 2 does **not** reveal it.
- [ ] Each temple's own data is fully visible to its own admin.
- [ ] The withdrawn public addresses no longer serve any temple's details to anyone.

## Watch out for

- **Any** leak here is a Blocker, however small — a name in a dropdown, a count in a summary card, a single row in a list. Note exactly which screen and what you saw.
- Look closely at *pickers and dropdowns* (choose an ingredient, choose a vendor, choose a person). They are the easiest place for cross-temple data to slip through.
- Counts and totals count as data: a "3 recipes" figure on temple 2 that reflects temple 1's recipes is a leak even if no names show.
- If a screen errors out entirely rather than showing an empty list, that is a defect too — but a *safe* one. Record it as Major, not Blocker.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT006-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
