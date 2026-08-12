# UAT-003: View a temple, and permanently delete one

| | |
|---|---|
| **Feature area** | Platform foundation — tenant administration |
| **Technical stories** | E1-S15 (view a temple, permanent delete)¹, E1-S6 (tenant provisioning) |
| **Roles exercised** | Platform operator (Super-admin) |
| **Depends on** | UAT-002 |
| **Environment needs** | None |

¹ *E1-S15 was built (commit `ab7e073`) but has no written story in `docs/stories/`. See TRACEABILITY.md — that is itself a finding.*

## What this feature is for

An operator needs to look at one temple — confirm what it was set up as, and hand its public web
address to the temple — and, occasionally, remove a temple entirely: a test tenant, a duplicate, or a
temple that has left the platform. Deletion is genuinely permanent and takes everything with it, so
the screen is built to make an accident hard.

## How it is supposed to work

- The temple page shows what it was provisioned as: status, how many people have accounts, timezone,
  currency, 80G, address, and the date it was added.
- It shows the temple's **public web address** — the page devotees visit to donate — with a **Copy** button.
- Deleting asks you to **type the temple's own name** to confirm. A generic "type DELETE" becomes muscle
  memory; typing the name forces you to look at which temple you are erasing.
- Deletion removes the temple and all of its data — recipes, inventory, orders, staff, donations,
  history. There is no undo.

## Before you start

- **Sign in as:** `ikms.super-admin.1@trading4good.org` (platform operator)
- **Start at:** **/tenants**
- **You will need:** a *throwaway* temple to delete. Create one first — name it `Delete Me Temple`,
  Bengaluru coordinates, administrator `ikms.super-admin.2@trading4good.org`.
  **Do not delete the two temples from UAT-002; every later test needs them.**

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | On **Temples**, click the name **Sri Sri Radha Govinda Temple** | Its page: the name, *Added <date>*, and a panel showing Status **Active**, People with accounts **1**, Timezone, Currency, 80G **Approved**, Address |
| 2 | Look at the **Web address** panel | The full public address, ending `/t/sri-sri-radha-govinda-temple`, with a **Copy** button |
| 3 | Click **Copy**, then paste into a new browser tab | The button briefly reads *Copied*; the pasted address opens the temple's public donation page (this is UAT-054's page — just confirm it loads) |
| 4 | Return to **/tenants** and open **Delete Me Temple** | Its page, with a red-bordered panel at the bottom: *Delete this temple* |
| 5 | Click **Delete temple** | A dialog: *Delete Delete Me Temple?* explaining this erases everything permanently, showing the name to type, and a **Delete temple** button that is greyed out |
| 6 | Type `wrong name` into the confirmation box | The **Delete temple** button stays greyed out — you cannot proceed |
| 7 | Click **Cancel** | The dialog closes; nothing is deleted; the temple is still in the list |
| 8 | Open it again, click **Delete temple**, and type `Delete Me Temple` exactly | The button becomes active |
| 9 | Press **Delete temple** | A brief *Deleting Delete Me Temple…*, then the Temples list with a banner: *Delete Me Temple was deleted, along with all of its data* |
| 10 | Check the list | Only the two temples from UAT-002 remain |
| 11 | Sign out; sign in as `ikms.super-admin.2@trading4good.org` (who was that temple's administrator) | They are still a platform operator and land on Temples — deleting a temple did not break the operator account |

## It passes if

- [ ] A temple's page shows what it was set up as, including its 80G state and person count.
- [ ] The public web address is shown and can be copied, and it opens a working public page.
- [ ] Deletion cannot proceed until the temple's exact name is typed.
- [ ] Cancelling leaves the temple untouched.
- [ ] Confirming deletes the temple and says so plainly; the list no longer shows it.

## Watch out for

- **Do not delete the wrong temple.** If you do, re-create it with UAT-002 before continuing, and log it — a screen that made it easy to erase the wrong thing is itself a finding.
- The confirmation should be case- and space-exact. If `delete me temple` in lower case is accepted, note it as Minor.
- If deletion fails part-way — the banner says deleted but the temple is still listed, or an error code appears — record the exact `KMS-` code. Deleting a temple touches permanent records (audit, stock, donations) that are normally impossible to remove, so a half-deletion matters.
- Check the temple is gone from the list *after a page refresh*, not just visually.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT003-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
