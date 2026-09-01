# UAT-007: First sign-in as a new temple admin

| | |
|---|---|
| **Feature area** | Platform foundation — authentication and onboarding |
| **Technical stories** | E1-S4 (Firebase authentication), E1-S6 (tenant provisioning), E1-S13 (first-sign-in claim) |
| **Roles exercised** | Temple admin |
| **Depends on** | UAT-002 |
| **Environment needs** | None |

## What this feature is for

The moment a temple's own administrator first gets in. The platform operator created their account
before they had ever visited the site, so on that first sign-in the system has to recognise them by
the email address it was given and bind their real identity to the waiting account. If this does not
work, a temple is provisioned and locked out — which has happened before, so it is worth checking
carefully.

## How it is supposed to work

- The account is created **ahead of** the person's first visit, holding a placeholder identity.
- On first sign-in, the system matches the verified email address (or verified phone number) to that
  waiting account and adopts it. That match must be exact, and the account must still be unclaimed.
- After that the person is an ordinary temple administrator: they land in their temple's workspace,
  which is empty but fully working, and the menu is the temple menu.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` — the administrator named when Sri Sri Radha
  Govinda Temple was created in UAT-002. **This account has never signed in before.**
- **Start at:** **/sign-in**
- Sign out of Google entirely first, so you can pick this account deliberately.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **/sign-in** and click **Continue with Google** | The Google account chooser |
| 2 | Choose `ikms.temple-admin.1@trading4good.org` | You are returned to the app and land on **Your account** (`/profile`) — signed in, inside the temple |
| 3 | Look at the top of the menu | It names the temple, and lists the full temple menu (Recipes, Ingredients, Inventory, Equipment, Meal plan, Vendors, Shopping list, Purchase orders, Invoices, Donations, Volunteers, Donations ledger, Wish list, Staff schedule, Payments, People, Audit log, Profile) |
| 4 | On **Your account**, read your details | Your name as the operator typed it, `ikms.temple-admin.1@trading4good.org`, and the phone number entered in UAT-002 — all shown but not editable here |
| 5 | Open **/recipes** | An empty recipe list with an invitation to add one — *empty but working*, not an error |
| 6 | Open **/inventory**, **/planner**, **/vendors**, **/users** in turn | Each loads. Each is empty except **People**, which shows you |
| 7 | Open **/ingredients** | **Not empty** — Onion, Garlic, Mushroom and Egg are already flagged as prohibited, and the common grains and pulses (Rice, Wheat Flour, Semolina, Toor Dal, Moong Dal, Chana Dal, Urad Dal) are present |
| 8 | Open **/tenants** by typing it in the address bar | *Not your page* — a temple administrator is not a platform operator |
| 9 | Sign out, then sign in again as the same person | You are recognised immediately and land in the same workspace. The first sign-in was a one-time binding, not something that repeats |

## It passes if

- [ ] The administrator created in UAT-002 can sign in on the very first attempt, with no intervention.
- [ ] They land inside their own temple with the temple menu.
- [ ] Their name, email and phone are exactly what the operator entered.
- [ ] An empty temple's screens load as empty-but-working, never as errors.
- [ ] The seeded ingredients and categories are present, so the temple is usable from minute one.
- [ ] Platform screens are refused.

## Watch out for

- **The historical failure mode:** first sign-in appearing to work at Google but landing on *"this
  Google account isn't linked to a temple yet"*. If you see that page for this account, that is a
  **Blocker** — the account exists but was not matched.
- A mismatch between the email typed in UAT-002 and the Google account you sign in with (a typo, a
  different domain) will legitimately produce that same page. Check the address carefully before
  logging the defect.
- Any screen showing "Loading…" forever. Note which one.
- If the temple's name does not appear at the top of the menu (it may read *Your temple* instead),
  note it as Minor — it should say which temple you are in.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT007-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
