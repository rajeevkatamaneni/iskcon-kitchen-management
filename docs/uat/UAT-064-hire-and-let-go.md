# UAT-064: Promote someone, and let someone go

| | |
|---|---|
| **Feature area** | Workforce — the employment lifecycle |
| **Technical stories** | E6-S8 (hiring, employment records, and letting go) |
| **Roles exercised** | Temple admin |
| **Depends on** | UAT-008 |
| **Environment needs** | None. Messaging is not involved |

## What this feature is for

UAT-008 covers getting people in. This one covers everything after: a devotee who starts working
here, a cook who becomes the kitchen manager, and — the part nobody enjoys — somebody leaving, whether
they resigned or were dismissed.

The rule underneath all of it: **hiring is the only way anyone is given access to the app, and
employment ending is the only way it is taken away.** There is no third road, and there is deliberately
no delete.

## How it is supposed to work

- **Hiring a devotee promotes the account they already have.** It does not make a second one, because
  their shift history, their donations and the audit entries that name them all point at the first.
- **A promotion is an edit**: change the job title, change the access, or both. Granting access to
  somebody who had none creates their login there and then.
- **Ending employment forks**, and the fork is real:
  - **Resigned** or **contract ended** → they go back to being an ordinary devotee of your temple.
    They can still sign up for seva and still give. They appear on the Devotees page again.
  - **Dismissed** → the form offers to take their sign-in away entirely, ticked by default.
- **Nothing is deleted.** The record moves to *Former staff* with how and when they left, and every
  shift, order and adjustment that names them is untouched.
- **A former record is read-only.** Bringing someone back is a fresh hire.
- **You cannot end your own employment.** The last administrator of a temple doing that would leave
  nobody able to undo it.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org`
- **Start at:** **/staff** (menu: **People → Staff**)
- UAT-008 must have run: Gopal Das and Yamuna Devi Dasi are hired, Nitai Das is a registered devotee,
  Ramesh Kumar is employed with no login.

## Steps

### Promoting a devotee keeps the person whole

| # | Do this | You should see |
|---|---|---|
| 1 | Before anything, open **Devotees** and note that **Nitai Das** is there. Have him sign up for a volunteer shift (UAT-049) so he has some history | He is on that shift's roster |
| 2 | Back on **Staff**, press **Hire someone**. At the top, open **Already registered here?** | The devotees of your temple are listed by name and email |
| 3 | Choose **Nitai Das**, title **Cook**, access **Kitchen staff**, joining today. Hire | He appears under Current staff |
| 4 | Open **Devotees** | He is **gone** from it — he is staff now, not a devotee |
| 5 | Open the shift he signed up for | He is **still on the roster**, under the same name. His history did not fork |
| 6 | Have Nitai sign in with the account he registered with | Same password, same account — he now lands on the **kitchen** menu, not the volunteer one |

### A promotion

| # | Do this | You should see |
|---|---|---|
| 7 | On **Staff**, press **Edit** on Nitai Das | The form opens with every field already filled in |
| 8 | Change his job title to **Kitchen Manager** and save | The register shows the new title |
| 9 | Press **Edit** on **Ramesh Kumar** (the one with no login). Give him access **Kitchen staff**, and add an email | Saved. The register now shows *Kitchen staff* against his name |
| 10 | Try the same on somebody with an email but **no phone number** | Refused (`KMS-4950`) |
| 11 | Open **Audit log** | A `STAFF_UPDATED` entry for each, naming the before and after — job title and access. It must **not** contain a PAN or an address |

### Withdrawing access without ending employment

| # | Do this | You should see |
|---|---|---|
| 12 | Edit Ramesh Kumar again and set access back to **No login**. Save | He stays on Current staff, showing *No login* |
| 13 | Open **Devotees** | He is **not** there — he never registered, and taking an app account away does not make somebody a devotee |

### Letting someone go

| # | Do this | You should see |
|---|---|---|
| 14 | Press **End employment** on **Yamuna Devi Dasi** | A panel that says plainly that nothing is deleted |
| 15 | Look at the **Take their sign-in away** tick box with *Resigned* selected | **Unticked** |
| 16 | Change the reason to **Dismissed** | It **ticks itself** — the default follows the reason |
| 17 | Change it back to **Resigned**, set the last working day to today, reason `Moved to Mayapur`, leave the tick box **unticked**, and confirm | She moves to a **Former staff** section below, showing *Resigned — Moved to Mayapur* and the date |
| 18 | Open **Devotees** | She is **back on it**, Active — still a devotee of your temple |
| 19 | Have her sign in | She can. She lands on the **volunteer** menu, and can still sign up for seva and give |
| 20 | Look at the Former staff section | No **Edit** and no **End employment** on those rows |
| 21 | Open **Staff schedule** | She is **gone** from the grid. The week shows only current staff |
| 22 | Open a purchase order or stock adjustment she made, if any | Her name is still on it, unchanged |

### A dismissal

| # | Do this | You should see |
|---|---|---|
| 23 | End **Gopal Das**'s employment as **Dismissed**, last working day today, reason `Repeated absence`, leaving the tick box ticked | He moves to Former staff |
| 24 | Try to sign in as `ikms.kitchen-staff.1@trading4good.org` | Refused — the account is disabled (`KMS-4103`) |
| 25 | Open **Devotees** | He is listed but **Disabled**. You can re-enable him from there if this was a mistake |

### The guard that matters

| # | Do this | You should see |
|---|---|---|
| 26 | Find **your own** row on the staff register and press **End employment** | Refused (`KMS-4304`) — you cannot lock your own temple out of itself |
| 27 | Try **Edit** on your own row and set your access to **No login** | Refused (`KMS-4302`) |
| 28 | Open **Audit log** one last time | `STAFF_EMPLOYMENT_ENDED` for both departures, each naming how it ended and whether the sign-in was revoked |

## It passes if

- [ ] Hiring a registered devotee promotes their existing account — one account, one history, one roster entry.
- [ ] A promotion changes the job title and/or access, and granting access to someone who had none creates their login.
- [ ] Withdrawing access without ending employment leaves them employed and does not make them a devotee.
- [ ] A resignation returns them to being a devotee, still able to sign in and serve.
- [ ] A dismissal offers to disable the account, ticked by default, and does so.
- [ ] Former staff move to their own section with how and when they left, and cannot be edited.
- [ ] A former member of staff disappears from the schedule grid but stays named on everything they did.
- [ ] An admin cannot end their own employment (`KMS-4304`) or remove their own access (`KMS-4302`).
- [ ] Every hire, edit and ending is on the audit log, and none of them carries a PAN or an address.

## Watch out for

- **Step 5 is the whole point of step 3.** If hiring a devotee makes a *second* account, their shift
  signups, donations and audit history quietly split in two and nothing will ever tell you. Look at
  the roster, not just the staff list.
- **Step 16, the defaulting tick box.** It must follow the reason as you change it, not stick at
  whatever it was when the panel opened.
- Step 21: a former member of staff still appearing on the schedule grid is a Major finding.
- Someone dismissed who can still sign in is a **Blocker**.
- The audit entries: check what is *in* them. A before/after that carries an address or a PAN is a
  Major finding — the log is read by more people than the staff record is.
- Try ending employment with **no last working day**. A former employee without one is a record
  nobody can use.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT064-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
