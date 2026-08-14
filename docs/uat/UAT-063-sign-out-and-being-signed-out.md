# UAT-063: Signing out, and being signed out

| | |
|---|---|
| **Feature area** | Sessions on a shared device |
| **Technical stories** | E1-S16 (this feature) · E1-S2 (sign-in) · E1-S4 (a disabled account is refused on its next request) |
| **Roles exercised** | Temple Admin · Kitchen Staff · Volunteer · Platform Operator |
| **Depends on** | Nothing. Best run near the end of a session — it signs you out |
| **Environment needs** | Two browser tabs. One check needs an hour of patience, or a second tester |

## What this feature is for

A temple kitchen runs on shared devices: a tablet by the store room, a phone passed between cooks.
Before this, whoever signed in first stayed signed in for good — and everything the next person did
was recorded as them. Since every stock adjustment, override and payment names an actor, that quietly
corrupts the record the temple relies on to know what happened.

So there are two ways to stop being the signed-in person: sign out, or walk away.

## How it is supposed to work

- **Sign out** sits at the foot of the menu, next to your name — on every screen, for every role.
- **Sixty minutes of no activity signs you out** by itself.
- **A minute before that, a warning appears** offering to keep you signed in, so nobody loses a
  half-typed delivery.
- **Activity means activity** — a tap, a key, a scroll. A screen that refreshes itself on a timer does
  not count as you being there.
- **All tabs agree.** Working in one tab keeps the others alive; signing out in one signs out all.
- **The sign-in screen says why**, when the app was the one that signed you out.

## Before you start

- **Sign in as** `ikms.temple-admin.1@trading4good.org` (repeat the first three steps as kitchen staff
  and as a volunteer — the control must be there for every role).
- Open the site at the staging URL.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Look at the foot of the left-hand menu | Your name, your role, and a sign-out control beside it |
| 2 | Click sign out | You land on the sign-in screen |
| 3 | Press the browser's Back button | You do **not** see temple data — you stay on, or return to, sign-in |
| 4 | Sign in again, then open a second tab of the same site | Both tabs are signed in |
| 5 | Sign out in one tab, then look at the other | The other tab is signed out too (it may take a moment) |
| 6 | Sign in. Leave the tab open and untouched for **59 minutes** — do not move the mouse over it, do not type. A page that refreshes itself is fine to leave open | At 59 minutes a dialog appears: you will be signed out in about a minute |
| 7 | Click **Stay signed in** | The dialog goes; you are still signed in and everything you had typed is still there |
| 8 | Now leave it untouched for a full **60 minutes** | You are signed out, and the sign-in screen explains that it was because of 60 minutes without activity |
| 9 | Sign in again, and look at the sign-in screen once more after a normal sign-out (step 2) | The explanation appears only when the app signed you out — not when you did |
| 10 | Sign in on a tablet or phone if you have one, and repeat steps 1–2 | The sign-out control is reachable and comfortable to tap |

**If you do not have two hours:** steps 6–8 can be split across a working morning — start step 6, go and
do something else, and come back. What matters is that nothing touches the tab in between.

## It passes if

- [ ] Every role can sign out from any screen, and lands on the sign-in screen.
- [ ] After signing out, going back in the browser shows no temple data.
- [ ] An untouched session warns at 59 minutes and signs out at 60.
- [ ] **Stay signed in** keeps the session, and nothing typed is lost.
- [ ] A tab left open while you work in another tab does **not** get signed out.
- [ ] Signing out in one tab signs out the others.
- [ ] The sign-in screen explains an automatic sign-out, and stays quiet about a deliberate one.

## Watch out for

- A sign-out that appears to work but leaves you signed in when you press Back — that is a real defect,
  not a browser quirk. Record it.
- Being signed out **while you were working**. Note exactly what you were doing: it means something is
  not counting as activity.
- The warning arriving with no way to stay, or arriving on top of a form and losing what was typed.
- The idle sign-out never happening at all. Leaving a tab on a page that refreshes itself is the case
  most likely to be wrong — that must not hold the session open.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT063-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
