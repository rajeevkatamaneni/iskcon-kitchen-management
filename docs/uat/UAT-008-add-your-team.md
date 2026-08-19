# UAT-008: Add your team

| | |
|---|---|
| **Feature area** | Platform foundation — the two ways a person joins a temple |
| **Technical stories** | E1-S12 (devotee register), E1-S17 (registering yourself), E6-S8 (hiring) |
| **Roles exercised** | Temple admin (hires), devotees (register themselves), kitchen staff and volunteers (sign in) |
| **Depends on** | UAT-007 |
| **Environment needs** | None |

## What this feature is for

A temple arrives with exactly one person: its administrator. Until it has a cook and a volunteer,
the product is unusable. **Everything from here on depends on the people created in this test** — the
later tests call them by name.

**Rewritten 2026-08-19.** This test used to run entirely through an *Add someone* form on the People
page, where an administrator typed everybody else's name, email and phone. That form is gone. There
are now two roads and they are deliberately different:

## How it is supposed to work

- **Devotees register themselves.** They choose the temple, give their own name and contact details,
  and consent for themselves. An administrator cannot create one — an account whose contact details
  nobody confirmed and whose consent nobody gave is exactly what this closes.
- **Staff are hired**, on the new **Staff** page. Hiring is the *only* way anybody is given access to
  the app, and ending their employment is the only way it is taken away.
- The **Devotees** page (was "People") lists the community and offers one decision: whether someone
  may still sign in. There is no role dropdown, because a devotee holds one role by definition.
- A hire carries two separate fields that are easy to confuse: a **job title** (what they are called
  — a label, granting nothing) and **app access** (what they may do). Choosing a title suggests the
  access it usually needs.
- Somebody can be employed with **no login at all**. A janitor does not need an app account.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **You will create these six people.** Later tests use them by name, so create all six:

| Name | Email | Phone | How they are created | Job title | Access |
|---|---|---|---|---|---|
| Gopal Das | `ikms.kitchen-staff.1@trading4good.org` | +919000000001 | **Hired** | Head Cook | Kitchen staff |
| Yamuna Devi Dasi | `ikms.kitchen-staff.2@trading4good.org` | +919000000002 | **Hired** | Store Manager | Kitchen staff |
| Ramesh Kumar | — | +919000000006 | **Hired** | Housekeeping | **No login** |
| Nitai Das | `ikms.volunteer.1@trading4good.org` | +919000000003 | **Registers himself** | — | — |
| Gaura Das | `ikms.volunteer.2@trading4good.org` | +919000000004 | **Registers himself** | — | — |
| Lalita Devi Dasi | `ikms.volunteer.3@trading4good.org` | +919000000005 | **Registers herself** | — | — |

Do **not** create `ikms.temple-admin.2@…` here — that account runs the second temple.

## Steps

### The devotee register has no way to create a devotee

| # | Do this | You should see |
|---|---|---|
| 1 | Look at the menu | Under **People**: **Devotees**, **Staff**, **Staff schedule**, **Volunteer shifts**. The money screens are under **Giving** |
| 2 | Open **Devotees** | *Everyone who has registered at your temple…* The list is **empty** — you are an administrator, not a devotee |
| 3 | Look for a way to add someone | There is none: no *Add someone* form, no *Add person* button, and **no role dropdown** on any row |

### Devotees register themselves

| # | Do this | You should see |
|---|---|---|
| 4 | Sign out. Go to the registration page and register **Nitai Das** with his email and phone, choosing **Sri Sri Radha Govinda Temple** | The account is created and he lands inside the temple, on **My shifts**, with only My shifts, Available shifts, Donate and Profile in the menu |
| 5 | Register **Gaura Das** and **Lalita Devi Dasi** the same way | Both land inside the temple as volunteers |
| 6 | Sign back in as the temple admin and open **Devotees** | All three listed, with email, phone, the date they registered, and **Active**. A count reads *3 active* |
| 7 | Type `nitai` in the search box | Only Nitai Das. Try part of a phone number too |
| 8 | Type something that matches nobody | *Nobody matches "…"* — not an empty table |

### Staff are hired

| # | Do this | You should see |
|---|---|---|
| 9 | Open **Staff** | *Everyone your temple employs…* **You are already on it** as Temple Administrator — provisioning employed you when the temple was made |
| 10 | Press **Hire someone** | A form. Note the two separate fields: **Job title** and **App access** |
| 11 | Open the **Job title** list | Grouped: Administration, Kitchen, Store, Support, Other. **Not** a free-text box |
| 12 | Choose **Head Cook** | **App access** changes by itself to *Kitchen staff* |
| 13 | Choose **Driver** | Access changes to *No login* — a driver needs no account |
| 14 | Now set access to *Kitchen staff* by hand, then change the title again | The access **stops** changing itself. Once you have said what you want, the title no longer overrules you |
| 15 | Choose title **Other** | A box appears asking what your temple calls the job. Try saving with it empty | 
| 16 | Hire **Gopal Das** — Head Cook, Kitchen staff, joining today, with his email and phone. Fill in an emergency contact | He appears under **Current staff** |
| 17 | Hire **Yamuna Devi Dasi** — Store Manager, Kitchen staff | Two current staff, plus you |
| 18 | Hire **Ramesh Kumar** — Housekeeping, **No login**, phone only, no email | Hired, showing **No login** under Access |
| 19 | Try to hire someone with access **Kitchen staff** but no email | Refused: *Someone can only be given a sign-in if we have both their email address and their phone number* (`KMS-4950`) |
| 20 | Try to hire Gopal Das a second time | Refused (`KMS-4926`) |
| 21 | Hire someone with a PAN of `ABCDE1234F` | The register shows it masked, as `••••••234F` — never in full |
| 22 | Click the masked PAN | It opens out in full. **This is recorded** — you will check that in step 27 |
| 23 | Try a PAN of `NOTAPAN` | Refused with a message about the shape of a PAN (`KMS-4001`) |

### The hired people can sign in

| # | Do this | You should see |
|---|---|---|
| 24 | Sign out. Sign in as `ikms.kitchen-staff.1@trading4good.org` | Recognised on first sign-in; lands inside **Sri Sri Radha Govinda Temple** with the kitchen menu and **My shifts** |
| 25 | Check what that account can reach | **No** Devotees, Staff, Staff schedule, Audit log, Payments or Wish list |
| 26 | Sign back in as the temple admin; open **Staff schedule** | Gopal Das and Yamuna Devi Dasi are on the grid with their job titles. Ramesh Kumar too. There is **no Add staff form** — a *Staff register* link instead |
| 27 | Open **Audit log** | Entries for each hire (`STAFF_HIRED`), and one for the PAN you opened (`STAFF_PAN_VIEWED`) |

## It passes if

- [ ] The Devotees page offers no way to create a person and no role dropdown.
- [ ] A devotee can register themselves and lands inside the right temple as a volunteer.
- [ ] Search on the devotee register matches name, email and phone.
- [ ] A temple's founding administrator is already on the staff register.
- [ ] Job title is a grouped picklist, and it suggests app access without locking it.
- [ ] Somebody can be hired with no login at all.
- [ ] Access without both an email and a phone is refused with `KMS-4950`.
- [ ] Hiring the same person twice is refused with `KMS-4926`.
- [ ] A PAN is masked in the list, and opening it in full is on the audit log.
- [ ] Everyone hired with access can sign in on their first attempt, into the right temple with the right menu.

## Watch out for

- **Step 14 is the one most likely to be wrong.** The title suggesting the access is a convenience; it must never override a choice the admin has already made by hand.
- A person hired here who then cannot sign in is a **Blocker** — the same failure mode UAT-007 guards for.
- The staff register showing somebody twice, or a hire creating a **second account** for a devotee who was already registered. Their seva history must stay with them. (You will test that road properly in UAT-064.)
- A hire with **no login** should still appear on the staff schedule grid — but nothing should try to notify them.
- Anyone flagged **job not recorded** on the register: that is V57's honest gap for staff who predate employment records. On a temple made after this change, nobody should carry it.
- The same email at a *different* temple is legitimate — a devotee may serve at two. Only a duplicate *within* this temple is refused.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT008-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
