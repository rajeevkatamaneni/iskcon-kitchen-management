# UAT-003: View a temple, and permanently delete one

| | |
|---|---|
| **Feature area** | Platform foundation — tenant administration |
| **Technical stories** | [E1-S15](../stories/EPIC-1-platform-foundation.md#e1-s15--temple-detail-data-export-and-permanent-deletion) (temple detail, data export, permanent deletion), E1-S6 (tenant provisioning) |
| **Roles exercised** | Platform operator (Super-admin) |
| **Depends on** | UAT-002 |
| **Environment needs** | None. The export is built and streamed in the request — no background worker required |
| **You will need** | A spreadsheet program (Excel, Numbers, LibreOffice or Google Sheets) to open the export |

## What this feature is for

An operator needs to look at one temple — confirm what it was set up as, and hand its public web
address to the temple — and, occasionally, remove a temple entirely: a test tenant, a duplicate, or a
temple that has left the platform.

Deletion is genuinely permanent. It erases the stock ledger, the audit trail, vendor invoices and
every donation record, including the ones behind 80G certificates already issued to donors — records
that are otherwise impossible to delete by design. Because it is unconditional, the safeguard is a
**data export**: a spreadsheet of everything the temple holds, which the operator must take before the
system will let them delete anything.

## How it is supposed to work

- The temple page shows what it was provisioned as: how many people have accounts, timezone,
  currency, 80G, address, and the date it was added.
- It shows the temple's **public web address** — the page devotees visit to donate — with a **Copy** button.
- **Data export**: one Excel workbook with a tab per kind of record. Each tab has the column names as
  its first row, a filter on that row, and the header frozen — so anyone can sort and filter it later
  without knowing anything about this system. The file is named after the temple.
- **Deleting requires a recent export.** With no export taken in the last 24 hours, the delete is
  refused — by the system itself, not merely by the screen (`KMS-4941`).
- Deleting then asks you to **type the temple's own name**. A generic "type DELETE" becomes muscle
  memory; typing the name forces you to look at which temple you are erasing.
- Deletion removes the temple and all of its data. There is no undo — the export is the only copy.

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
| 4 | Return to **/tenants** and open **Delete Me Temple** | Its page, with a **Data export** panel and, below it, a red-bordered *Delete this temple* panel |
| 5 | Read the **Data export** panel | It explains what the export is, and says **Never exported** |
| 6 | Click **Delete temple** | A dialog opens. It carries an amber warning — *Take the data export first* — and the **Delete temple** button is greyed out |
| 7 | Type `Delete Me Temple` correctly into the confirmation box | The button is **still** greyed out — the name alone is not enough without an export |
| 8 | Click **Download data export** inside the dialog | A file downloads, named `Delete Me Temple - Data Export - <today>.xlsx` |
| 9 | Look at the dialog again | The warning has turned green: *Data export taken \<date and time\>*, and the confirmation box is still there |
| 10 | **Open the downloaded file** in a spreadsheet program | It opens without warnings or repairs |
| 11 | Look at the tabs along the bottom | One tab per kind of record. The first is **tenants** — this temple's own row. Others are named after what they hold (`users`, `ingredients`, `donations`, `stock_movements`, and so on) |
| 12 | On any tab, look at row 1 | The column names, in bold, with a **filter arrow** on each one |
| 13 | Scroll down a tab with several rows | The header row **stays visible** — it is frozen |
| 14 | Use a filter arrow to sort or filter a column | It works, straight away, with no setup |
| 15 | Open the **users** tab | The temple's people are there — the accounts you would lose |
| 16 | Check a tab for something this temple never had, e.g. **donations** | The tab exists with its column headings and no rows. An empty tab is correct: it says "nothing was held here" |
| 17 | Back in the dialog, type `wrong name` into the confirmation box | The **Delete temple** button is greyed out again |
| 18 | Click **Cancel** | The dialog closes; nothing is deleted; the temple is still in the list |
| 19 | Open it again, click **Delete temple**, and type `Delete Me Temple` exactly | The button becomes active — you have both the export and the name |
| 20 | Press **Delete temple** | A brief *Deleting Delete Me Temple…*, then the Temples list with a banner: *Delete Me Temple was deleted, along with all of its data* |
| 21 | Check the list | Only the two temples from UAT-002 remain |
| 22 | Open your downloaded export again | It still opens, and still holds everything the deleted temple had. This file is now the only copy in existence |
| 23 | Sign out; sign in as `ikms.super-admin.2@trading4good.org` (who was that temple's administrator) | They are still a platform operator and land on Temples — deleting a temple did not break the operator account |
| 24 | Open **Sri Sri Radha Govinda Temple** and press **Download data export** (do **not** delete it) | The export works on a live temple too, and downloads named after that temple. An operator can take a copy without deleting anything |

## It passes if

- [ ] A temple's page shows what it was set up as, including its 80G state and person count.
- [ ] The public web address is shown and can be copied, and it opens a working public page.
- [ ] The export downloads as a spreadsheet named after the temple.
- [ ] It has a tab per kind of record, the temple's own row first, each tab with column headings, filters, and a frozen header.
- [ ] Deletion is refused until an export has been taken — the correct name alone is not enough.
- [ ] Deletion also cannot proceed until the temple's exact name is typed.
- [ ] Cancelling leaves the temple untouched.
- [ ] Confirming deletes the temple and says so plainly; the list no longer shows it.
- [ ] The export can be taken on a live temple without deleting it.

## Watch out for

- **Do not delete the wrong temple.** If you do, re-create it with UAT-002 before continuing, and log it — a screen that made it easy to erase the wrong thing is itself a finding.
- **The export is the whole safety net.** If the delete button ever arms without one, that is a **Blocker** — say exactly what you did to get there.
- Open the file properly, don't just check it downloaded. A workbook that Excel offers to "repair", or one whose tabs are empty when the temple had data, is a Major defect.
- Compare a tab against what you know: the **users** tab should hold the people you added, the **ingredients** tab the ingredients. A tab of the right name with the wrong contents matters more than a missing one.
- Check the export holds **only this temple's** records — no rows belonging to the other temple.
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
