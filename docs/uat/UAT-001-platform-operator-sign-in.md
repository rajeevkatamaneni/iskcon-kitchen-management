# UAT-001: Sign in as the platform operator

| | |
|---|---|
| **Feature area** | Platform foundation — authentication |
| **Technical stories** | E1-S4 (Firebase authentication), E1-S5 (role-based access control), E1-S13 (platform super-admin bootstrap) |
| **Roles exercised** | Platform operator (Super-admin) |
| **Depends on** | Nothing — this is the first test |
| **Environment needs** | None |

## What this feature is for

The platform operator is the person who runs the installation itself: they bring temples onto the
platform and watch its health. They are deliberately **not** part of any temple — they cannot read a
temple's recipes, donations or people. This test proves an operator can get in, and that getting in
puts them exactly where they belong.

## How it is supposed to work

- Operator accounts are **created out of band**, never inside the app. There is no screen anywhere
  that mints a platform operator — that is by design, because it is the most privileged account in
  the system.
- The first time an operator signs in, the system binds their Google identity to the account waiting
  for their email address. From then on it is simply their account.
- Signing in lands them on **Temples**, and the menu shows only two destinations: *Temples* and
  *Operations*. No recipes, no inventory, no donations — running the platform is not running a temple.

## Before you start

- **Sign in as:** `ikms.super-admin.1@trading4good.org` (platform operator)
- **Start at:** **/sign-in**
- Use a fresh browser window, or sign out of Google first, so you can choose the account.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **/sign-in** | A page headed *Sign in* with a **Continue with Google** button, and below it a choice of **Email** or **Phone** |
| 2 | Click **Continue with Google** and choose `ikms.super-admin.1@trading4good.org` | You are returned to the app and land on **Temples** (address ends `/tenants`) |
| 3 | Look at the menu down the left | Exactly two entries: **Temples** and **Operations**. The heading above them reads *Platform* |
| 4 | Check the menu does **not** offer | Recipes, Ingredients, Inventory, Equipment, Meal plan, Vendors, Order list, Purchase orders, Invoices, Donations, Volunteers, Donations ledger, Wish list, Staff schedule, Payments, People, Audit log, My shifts, Available shifts, Profile |
| 5 | In the address bar, type **/recipes** and press Enter | You are refused — a page reading *Not your page* — not a broken screen and not a recipe list |
| 6 | Go back to **/tenants**, then sign out from the menu | You are returned to the sign-in page |
| 7 | Sign in again as the second operator, `ikms.super-admin.2@trading4good.org` | Same result as step 2 — a second operator works exactly like the first |
| 8 | Sign out, and sign in with a Google account that nobody has added (use your own personal Google account if you have one) | A calm page: *"You're signed in — but this Google account isn't linked to a temple yet. Ask your temple administrator to add you."* with a **Sign out** button. Not an error, not a blank screen |

## It passes if

- [ ] Both platform operators can sign in with Google.
- [ ] Signing in lands on **Temples**, not on a temple workspace.
- [ ] The menu offers only **Temples** and **Operations**.
- [ ] Typing a temple address by hand is refused with *Not your page*.
- [ ] An unknown Google account gets the polite "not linked to a temple yet" page, never a crash or a blank screen.

## Watch out for

- Being sent round in a redirect loop after Google returns you — that is a fault, note it as a Blocker.
- Any technical wording on screen (a stack trace, a URL, a Java or database word). Nothing technical should ever reach a person.
- If sign-in fails outright, note whether the screen shows a `KMS-` code. `KMS-4101` means the system did not see you as signed in; `KMS-4104` means you are signed in but have no account here.
- The operator's menu has **no Profile** entry. That is deliberate — an operator has no temple to be contacted by — so its absence is correct.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT001-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
