# UAT-008: Add your team

| | |
|---|---|
| **Feature area** | Platform foundation — temple user management |
| **Technical stories** | E1-S12 (temple user management) |
| **Roles exercised** | Temple admin (adds), kitchen staff and volunteers (sign in) |
| **Depends on** | UAT-007 |
| **Environment needs** | None |

## What this feature is for

A temple arrives with exactly one person: its administrator. Until they can add a cook and a
volunteer, the product is unusable. This screen is how a temple staffs itself — and everything from
here on depends on the people added in this test.

## How it is supposed to work

- The administrator enters a person's name, email, phone and role. That creates their account
  **before** they have ever visited, exactly as the platform operator did for the administrator.
- The roles offered are the fixed set: **Temple admin**, **Kitchen staff**, **Volunteer**. A platform
  operator can never be created here — that account type is deliberately unreachable from inside the app.
- The person then signs in with that email address and is recognised.
- Two people at the same temple cannot share an email address.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/users** (menu: **People**)
- **You will add these five accounts.** Later tests use them by name, so add all five:

| Name | Email | Phone | Role |
|---|---|---|---|
| Gopal Das | `ikms.kitchen-staff.1@trading4good.org` | +919000000001 | Kitchen staff |
| Yamuna Devi Dasi | `ikms.kitchen-staff.2@trading4good.org` | +919000000002 | Kitchen staff |
| Nitai Das | `ikms.volunteer.1@trading4good.org` | +919000000003 | Volunteer |
| Gaura Das | `ikms.volunteer.2@trading4good.org` | +919000000004 | Volunteer |
| Lalita Devi Dasi | `ikms.volunteer.3@trading4good.org` | +919000000005 | Volunteer |

Do **not** add `ikms.temple-admin.2@…` here — that account runs the second temple.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **People** | *Everyone at your temple, and what they can do.* The list shows just you, marked **You**, with status **Active** |
| 2 | Open **Add someone** | A form: Full name, Role, Email, Phone — and a line explaining they'll be able to sign in with the email or phone entered |
| 3 | Look at the **Role** choices | Exactly three: Temple admin, Kitchen staff, Volunteer. **No** platform-operator option |
| 4 | Add the first person from the table (Gopal Das, kitchen staff) | The list refreshes and shows Gopal Das, Kitchen staff, **Active** |
| 5 | Add the same email a second time | Refused: *Someone at this temple is already registered with that email address* (`KMS-4902`) |
| 6 | Add a person with a phone number missing its country code, e.g. `9876543210` | Refused with a message about the phone format, naming the country code (`KMS-4003` or a field-level message) |
| 7 | Add the remaining four people from the table | All five appear in the list with the right roles |
| 8 | Sign out. Sign in as `ikms.kitchen-staff.1@trading4good.org` | Recognised on first sign-in; you land inside **Sri Sri Radha Govinda Temple** with the kitchen menu and **My shifts** |
| 9 | Sign out. Sign in as `ikms.volunteer.1@trading4good.org` | You land on **My shifts**, with only My shifts, Available shifts and Profile in the menu |
| 10 | Sign out. Sign back in as the temple admin and open **People** | All six people listed with the right roles and statuses |

## It passes if

- [ ] A temple admin can add people with a name, email, phone and one of the three roles.
- [ ] The platform-operator role is not offered anywhere on this screen.
- [ ] A duplicate email at the same temple is refused with `KMS-4902`.
- [ ] A malformed phone number is refused with a message naming the country code.
- [ ] Every person added can sign in on their first attempt and lands in the right temple with the right menu.

## Watch out for

- A person added here who then cannot sign in is a **Blocker** — the same failure mode UAT-007 guards for.
- Check the role that was actually assigned after sign-in matches what you chose. A volunteer who lands on the kitchen menu is a Major defect.
- The list should show each person's status. Nobody should be able to be added as already-disabled.
- The same email address at a *different* temple is legitimate — a devotee may serve at two temples. Only a duplicate *within* this temple is refused.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT008-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
