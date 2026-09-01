# UAT-023: Get warned before you run out

| | |
|---|---|
| **Feature area** | Inventory — reorder thresholds and low-stock alerts |
| **Technical stories** | E3-S3 (reorder thresholds and low-stock alerts) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-022, and stock in the system (UAT-028 or UAT-044) |
| **Environment needs** | The **daily digest** needs the background worker **and** a live message channel. The on-screen part works without either |

## What this feature is for

Shortages should be discovered by the store manager on a screen, not by the cook at the stove. Each
item carries the level below which it needs reordering; once it dips, the temple is told — once a day,
in one digest, not with a stream of alerts nobody reads.

## How it is supposed to work

- Each tracked item has a **reorder threshold** in its own unit.
- Anything below it is marked **Low** in the stock view.
- Once a day, a digest goes to kitchen staff and the temple admin listing what is below threshold and
  what is expiring soon — **only if there is something to say**. An empty digest is not sent.
- The digest reaches each person on the channel they chose in UAT-010.
- The same below-threshold list feeds the suggested shopping list (UAT-038).

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/inventory**
- **You need some stock.** The simplest way is UAT-028: record a gift of 25 Kg Rice and 3 L Ghee.
- **Ask the environment owner** whether the background worker and any message channel are live. If not,
  run steps 1–6 and mark steps 7–9 *blocked by environment*.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Inventory** with Rice at 25 Kg and a threshold of 20 | Rice shows **On hand 25**, **Reorder at 20**, and **no** Low badge |
| 2 | Open Rice and use **Adjust stock** to reduce it to 15 Kg (reason: *Count correction*) | Stock now 15 |
| 3 | Return to **Inventory** | Rice is badged **Low** |
| 4 | Raise Ghee's threshold above its current stock (edit the item, or adjust stock down) | Ghee becomes **Low** too |
| 5 | Filter the list to show only low items, if a filter exists; otherwise count the badges | Both items are marked |
| 6 | Bring Rice back above its threshold (adjust +20 Kg, reason *Count correction*) | The **Low** badge clears |
| 7 | *(Worker + channel live)* Leave at least one item below threshold overnight, or ask the environment owner to run the digest job | A digest arrives listing the low items with their current quantity and threshold, plus anything expiring soon |
| 8 | *(Worker + channel live)* Check the channel it arrived on | It matches the preference you set in UAT-010 for that person |
| 9 | *(Worker + channel live)* Bring everything above threshold and let the digest run again | **No digest is sent** — an empty digest is suppressed |

## It passes if

- [ ] An item below its threshold is badged **Low**; above it, the badge clears.
- [ ] Changing either the stock or the threshold changes the badge correctly.
- [ ] *(If testable)* A daily digest lists the low and expiring items with quantities.
- [ ] *(If testable)* The digest reaches each person on their chosen channel.
- [ ] *(If testable)* Nothing is sent when there is nothing to report.

## Watch out for

- A **Low** badge that only appears after a manual page refresh — note it as Minor, but note it.
- Thresholds compared in the wrong unit (a threshold of 20 Kg compared against 20,000 gm of stock). Check the numbers, not just the badge.
- An item at *exactly* its threshold: is it Low or not? Record the behaviour — this is a boundary the story does not spell out, and it is worth pinning down.
- A digest that arrives more than once a day, or one that arrives with nothing in it. Both are defects.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT023-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
