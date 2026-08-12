# UAT-012: Ways to sign in, and the locked door

| | |
|---|---|
| **Feature area** | Platform foundation — authentication |
| **Technical stories** | E1-S4 (Firebase authentication) |
| **Roles exercised** | Any |
| **Depends on** | UAT-008 |
| **Environment needs** | Phone sign-in needs SMS quota on the environment — ask the environment owner before step 5 |

## What this feature is for

Many devotees in India live on a phone number rather than an email address, and asking someone to
remember a password for an app they open twice a year is a good way to lose them. So there are three
ways in — Google, email and password, and a code sent by SMS — and they all have to work.

## How it is supposed to work

- **Continue with Google** is the recommended route and the one the rest of this pack uses.
- **Email and password** works for anyone who has set a password.
- **Phone** sends a six-digit code by SMS to a number entered with its country code.
- Failure messages are deliberately vague about *which* credential was wrong — saying "no account with
  that email" would tell a stranger which addresses are registered at a temple.
- Signing in with an identity nobody has added gets a calm explanation, not an error.

## Before you start

- **Start at:** **/sign-in**
- Use a private/incognito window so previous sign-ins do not interfere.
- Ask the environment owner whether SMS sending is available and how many messages are left today.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **/sign-in** | *Sign in — ISKCON Seva Kitchen*, a **Continue with Google** button, and below it tabs for **Email** and **Phone** |
| 2 | On the **Email** tab, enter a real account address with a deliberately wrong password | *That email address and password don't match. Check both and try again.* — it does **not** say whether the address exists |
| 3 | Enter an email address that has never existed, with any password | The **same** message as step 2 — no hint that the account is unknown |
| 4 | Sign in with Google as `ikms.volunteer.1@trading4good.org` | You land on **My shifts** |
| 5 | Sign out. Switch to the **Phone** tab and enter a phone number **without** a country code | *We couldn't send a code to that number. Check the country code and try again.* |
| 6 | *(Only if SMS is available)* Enter a real number with country code and press **Send code** | A code arrives by SMS and the screen asks for it |
| 7 | *(Only if SMS is available)* Enter a wrong code | *That code isn't right. Check it and try again.* |
| 8 | *(Only if SMS is available)* Enter the correct code | You are signed in |
| 9 | Press **Use a different number** during the code step | You return to entering a number |
| 10 | Sign out. Sign in with any Google account nobody has added to a temple | *You're signed in — but this Google account isn't linked to a temple yet. Ask your temple administrator to add you with this email address, then sign in again.* with a **Sign out** button |
| 11 | Press that **Sign out** | You return to the sign-in page cleanly |
| 12 | Visit **/recipes** while signed out entirely | You are sent to the sign-in page, not shown a broken screen |

## It passes if

- [ ] All three sign-in methods are offered.
- [ ] A wrong password and an unknown address give the *same* message.
- [ ] A phone number without a country code is refused with advice about the country code.
- [ ] *(If SMS is available)* A real code signs you in; a wrong code is refused politely.
- [ ] An identity with no account gets the "not linked to a temple yet" page and can sign out from it.
- [ ] Visiting a protected page while signed out sends you to sign-in.

## Watch out for

- Any message that reveals whether an email address is registered. That is a Major finding even though nothing "breaks".
- The SMS step silently doing nothing. If **Send code** appears to work but no message arrives and no error shows, note it — and check with the environment owner whether the daily SMS quota is exhausted before logging it as a product defect.
- Being bounced between the sign-in page and the landing page repeatedly (a loop) — Blocker.
- If sign-in shows *Sign-in isn't configured on this environment*, stop and tell the environment owner. That is a configuration fault, not a product defect (root cause R5).

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT012-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
