# UAT-061: It works on a phone

| | |
|---|---|
| **Feature area** | Across the whole product — responsive design |
| **Technical stories** | E2-S7 (recipe browse UX), E7-S1 (public donation page), E7-S6 (public wish list); the design rules come from DESIGN_SYSTEM.md |
| **Roles exercised** | All |
| **Depends on** | Everything else — run it last |
| **Environment needs** | A real phone. A desktop browser's narrow window is a poor substitute |

## What this feature is for

Almost everyone who uses this product will use it on a mid-range Android phone: a cook checking a
recipe with wet hands, a volunteer signing up on the bus, a devotee donating from a WhatsApp link.
Native mobile apps are deliberately out of scope, so the website has to be genuinely usable on a small
screen — not merely readable.

## How it is supposed to work

- Every screen works at phone width without sideways scrolling.
- Tap targets are big enough to hit with a thumb.
- The public donation and wish-list pages are the most performance-sensitive: they must load fast on a
  phone connection.
- Text is legible without zooming; Indian scripts (from UAT-020) render properly.

## Before you start

- **Use a real phone**, ideally a mid-range Android one, on mobile data rather than fast wi-fi.
- **Start at:** the test site's sign-in page.
- Run this as a sweep: visit each screen and answer four questions — does it fit, can I tap it, can I
  read it, does it load quickly?

## Steps

| # | Do this on the phone | You should see |
|---|---|---|
| 1 | Open **/t/sri-sri-radha-govinda-temple/donate** without signing in | Loads quickly; amounts are easy to tap; nothing needs sideways scrolling |
| 2 | Open **/t/sri-sri-radha-govinda-temple/wishlist** | Items readable, images (if any) sized sensibly, **Sponsor** easy to tap |
| 3 | Sign in as `ikms.volunteer.1@trading4good.org` | The sign-in page and Google flow work on the phone |
| 4 | Open **My shifts**, release a spot | The action is reachable and tappable without zooming |
| 5 | Open **Available shifts** and sign up | Cards readable; **Sign up** easy to hit |
| 6 | Sign in as `ikms.kitchen-staff.1@trading4good.org` and open **Recipes** | Search box usable; category chips wrap rather than overflow |
| 7 | Open a recipe and scale it | The scale control is usable one-handed; the ingredient table fits or scrolls **inside itself**, not by moving the whole page |
| 8 | Open **Inventory**, then an item | Tables fit or scroll within their own area |
| 9 | Open **Meal plan** | The month grid is the hardest screen on a phone. Record honestly whether it is usable, or whether it needs sideways scrolling |
| 10 | Open **Purchase orders** and one order, and start a receiving entry | The line-by-line delivery form is workable on a phone — this is a screen someone genuinely fills in while standing next to a truck |
| 11 | Sign in as the **temple admin** and open **People**, **Payments** and **Donations ledger** | Each is usable; wide tables scroll within themselves |
| 12 | Turn the phone to landscape and back on two or three screens | Nothing breaks or loses your place |
| 13 | View a translated recipe (UAT-020) on the phone | The Indian script renders correctly and is legible |
| 14 | Note any screen where you had to pinch-zoom to use it | List them all — that list is the finding |

## It passes if

- [ ] Every screen fits phone width; the page itself never scrolls sideways.
- [ ] Wide tables scroll inside their own area.
- [ ] Buttons and links can be tapped accurately with a thumb.
- [ ] The public donation and wish-list pages load quickly on mobile data.
- [ ] Text is legible without zooming, in English and in Indian scripts.
- [ ] Nothing breaks on rotation.

## Watch out for

- The **meal planner** and the **receiving form** are the two most likely to be cramped. Judge them as a user would, not charitably.
- Buttons that are too close together to hit reliably — record which screen.
- The left-hand menu on a small screen: does it take over the page, or is there a sensible arrangement? Record what you find.
- A page that loads slowly on mobile data even though it is fast on wi-fi. Note roughly how long.
- Text so small you instinctively zoom. That is a defect even if everything technically fits.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT061-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
