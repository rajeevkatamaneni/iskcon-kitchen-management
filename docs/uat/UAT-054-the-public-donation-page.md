# UAT-054: The public donation page

| | |
|---|---|
| **Feature area** | Donations — public page |
| **Technical stories** | E7-S1 (public temple donation page) |
| **Roles exercised** | The public — no account at all |
| **Depends on** | UAT-002 |
| **Environment needs** | None to view the page. Completing a donation needs the payment provider (UAT-055) |

## What this feature is for

This is the address a temple puts on a festival banner and shares on WhatsApp. It is the product's
public face: a devotee opens it on a phone, sees the temple they know, and gives in under a minute
without making an account.

## How it is supposed to work

- The page lives at the temple's own web address: **/t/{temple-web-address}/donate**.
- It is **fully public** — no sign-in, no account.
- It shows the temple's identity, suggested amounts in rupees (₹51, ₹501, ₹1,001 by convention) and a
  box for any other amount.
- The temple is worked out **from the address on the server**, never from anything a visitor can tamper
  with.
- An unknown or inactive temple address gets a clean *Temple not found*, not an error.

## Before you start

- **Do not sign in.** Use a private/incognito window, or a browser you have never signed into.
- **Start at:** **/t/sri-sri-radha-govinda-temple/donate**
- Test on a **phone** as well as a computer — this page will mostly be opened on a phone.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open the page while completely signed out | It loads. *Donate to* **Sri Sri Radha Govinda Temple** — the temple's own name, not a generic title |
| 2 | Time how long it takes to become usable | It should feel immediate — a second or two at most on a normal connection |
| 3 | Look at the amounts offered | Preset buttons (₹51, ₹501, ₹1,001) and a box for any other amount |
| 4 | Tap a preset | It is selected and the donate button shows that amount |
| 5 | Type `250` into the other-amount box | The donate button reads *Donate ₹250* |
| 6 | Clear the amount entirely | The donate button is disabled — you cannot give nothing |
| 7 | Type `0`, then `-100` | Neither is accepted |
| 8 | Look at the donor choices | *Give with my name*, *Give with an 80G tax certificate* (this temple is 80G-approved), *Give anonymously* |
| 9 | Now open the **other** temple's page: **/t/iskcon-chowpatty/donate** | It shows **ISKCON Chowpatty**, and — because that temple is **not** 80G-approved — the 80G option is **absent** |
| 10 | Open **/t/no-such-temple/donate** | A clean *Temple not found* page |
| 11 | Try to change the temple by tampering with the address — add something like `?tenant=` and another temple's identifier | The page still shows the temple named in the web address, and nothing else |
| 12 | Open the page on a **phone** | Everything fits, the buttons are big enough to tap, nothing needs sideways scrolling |
| 13 | Share the address to yourself on WhatsApp and open it from there | It opens correctly from the messaging app |
| 14 | Open the wish-list page too: **/t/sri-sri-radha-govinda-temple/wishlist** | It loads publicly (UAT-058) |

## It passes if

- [ ] The page loads with no account, quickly, showing the right temple.
- [ ] Preset and custom amounts work; zero, empty and negative are refused.
- [ ] The 80G option appears only for the 80G-approved temple.
- [ ] An unknown web address gives a clean *Temple not found*.
- [ ] The temple cannot be changed by tampering with the address.
- [ ] The page is comfortable on a phone.

## Watch out for

- The temple's name or presets belonging to the **wrong temple** — a Blocker.
- Any personal information appearing in the web address.
- A page that needs a sign-in, or nudges towards one before letting you give. Giving must be possible without an account.
- **What the page does not offer:** look for a **one-time / recurring choice**. If there is no way to set up regular monthly giving from this page, record it here — UAT-056 follows it up.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT054-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
