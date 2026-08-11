# UAT-02 — Add your team: kitchen staff and volunteers

> New here? Read `README.md` first. Then do **UAT-01** — this test needs the temple it created.

## Objective

A temple runs on its people — cooks, storekeepers, and volunteers. The temple administrator adds each person, decides what they're allowed to do, and can disable anyone who leaves without ever losing the record of what they did. This test fills *Sri Sri Radha Govinda Temple* with the team that every later test relies on.

**Why this comes second:** almost every test after this one signs in as one of the people you add here. So we add all of them now — five kitchen staff and five volunteers.

## What we're testing

- The temple admin can add people and give each a role (kitchen staff or volunteer).
- Each new person can sign in for the first time and lands in a workspace that shows only what their role allows.
- The system refuses a second person with the same email.
- Roles can be changed; nobody can be made a platform operator from here; the admin can't change their own role or lock themselves out.
- Someone who leaves can be disabled and later re-enabled — never deleted.

## Built from

Coding stories `E1-S12` (managing a temple's people), `E1-S5` (who's allowed to do what), and `E1-S6` (a new person claiming their account on first sign-in).

## Before you start

- **UAT-01 is done** — *Sri Sri Radha Govinda Temple* exists.
- **Sign in as the Temple admin** (`ikms.temple-admin.1@trading4good.org`).
- Open the side menu and click **People** (or go to `https://kms-staging-web-bnpkv5hfrq-el.a.run.app/users`).

---

## Part A — Add your kitchen staff

You'll add five cooks/storekeepers. In the **Add someone** box, enter each row below and click **Add person**. The form clears after each one.

> **Important:** the **Email** must be typed *exactly* as shown — that's how each person signs in later. The **Full name** can be anything readable.

| Full name | Role | Email | Phone |
|---|---|---|---|
| Kitchen Staff One | Kitchen staff | `ikms.kitchen-staff.1@trading4good.org` | `+919000000011` |
| Kitchen Staff Two | Kitchen staff | `ikms.kitchen-staff.2@trading4good.org` | `+919000000012` |
| Kitchen Staff Three | Kitchen staff | `ikms.kitchen-staff.3@trading4good.org` | `+919000000013` |
| Kitchen Staff Four | Kitchen staff | `ikms.kitchen-staff.4@trading4good.org` | `+919000000014` |
| Kitchen Staff Five | Kitchen staff | `ikms.kitchen-staff.5@trading4good.org` | `+919000000015` |

**After each add:** the person appears in the table below with role **Kitchen staff** and status **Active**.

## Part B — Add your volunteers

Same box, five more people — this time choose the **Volunteer** role.

| Full name | Role | Email | Phone |
|---|---|---|---|
| Volunteer One | Volunteer | `ikms.volunteer.1@trading4good.org` | `+919000000021` |
| Volunteer Two | Volunteer | `ikms.volunteer.2@trading4good.org` | `+919000000022` |
| Volunteer Three | Volunteer | `ikms.volunteer.3@trading4good.org` | `+919000000023` |
| Volunteer Four | Volunteer | `ikms.volunteer.4@trading4good.org` | `+919000000024` |
| Volunteer Five | Volunteer | `ikms.volunteer.5@trading4good.org` | `+919000000025` |

**After Part B:** the People table shows **11** people in total — you (the admin) plus 5 kitchen staff and 5 volunteers.

## Part C — The system refuses a duplicate

| Step | What to do | What you should see |
|---|---|---|
| C1 | In **Add someone**, enter any name, role Volunteer, and the email `ikms.kitchen-staff.1@trading4good.org` (one you already used), a phone, and click **Add person**. | Refused, with a clear message that someone with that email is already at your temple. Nobody is added. *(You may see a code like `KMS-4902` — note it if you report anything.)* |

## Part D — Roles and access

| Step | What to do | What you should see |
|---|---|---|
| D1 | In the **Role** dropdown for *Volunteer One*, look at the options. | Only three: **Temple admin**, **Kitchen staff**, **Volunteer**. There is **no** "platform operator" option — you cannot create one from here. |
| D2 | Change *Volunteer One* from **Volunteer** to **Kitchen staff**, then back to **Volunteer**. | The role updates each time and the change sticks after the list refreshes. |
| D3 | Look at your own row (the admin you're signed in as). | Your Role shows as plain text and your Actions cell says **You** — you can't change your own role or disable yourself. |

## Part E — Someone leaves, and comes back

| Step | What to do | What you should see |
|---|---|---|
| E1 | On *Kitchen Staff Five*, click **Disable**. | Their status changes to **Disabled**. They're still listed — the record isn't deleted. |
| E2 | On the same person, click **Enable**. | Status returns to **Active**. |

## Part F — The new people can sign in, and see only what they should

| Step | What to do | What you should see |
|---|---|---|
| F1 | **Sign out.** Sign in with **Continue with Google** as **`ikms.kitchen-staff.1@trading4good.org`**. | You land inside *Sri Sri Radha Govinda Temple*. This is their first sign-in — the account you created is now claimed by them. |
| F2 | Look at the side menu. | You see kitchen sections (Recipes, Inventory, Planner…). You do **not** see **People** — a cook doesn't manage the temple's users. |
| F3 | In the address bar, go to `https://kms-staging-web-bnpkv5hfrq-el.a.run.app/users` directly. | You're turned away — a kitchen staff member can't open the People page. |
| F4 | **Sign out.** Sign in as **`ikms.volunteer.1@trading4good.org`**. | You land in the temple with a **volunteer's** view — mainly shifts / "My shifts". No admin or kitchen-management sections. |
| F5 | Go to `https://kms-staging-web-bnpkv5hfrq-el.a.run.app/users` directly. | Turned away again. Volunteers can't manage users. |

**When you're done:** sign out. The next test (UAT-03) will tell you who to sign in as.

---

## Did it pass?

- [ ] All 10 people were added — 5 kitchen staff and 5 volunteers — each Active with the right role.
- [ ] Adding a second person with an already-used email was refused with a clear message.
- [ ] The role dropdown offered only Temple admin / Kitchen staff / Volunteer — never a platform operator.
- [ ] You could not change your own role or disable yourself (your row said "You").
- [ ] Disabling and re-enabling a person worked, and never removed them from the list.
- [ ] A kitchen staff member and a volunteer each signed in on their own for the first time and saw only what their role allows; neither could open the People page.

## If something looks wrong

- **A new person can't sign in in Part F.** Most likely the email you typed when adding them doesn't exactly match their Google account email. Check the spelling and, if needed, disable the wrong entry and add them again with the exact email.
- **The duplicate in Part C was accepted** (a second copy appears). Report it — two people with the same email should never both exist at one temple.
- **A kitchen staff member or volunteer *can* open the People page in Part F.** Report it — that's a wall that should have held.
- **You could change your own role or disable yourself.** Report it — an admin locking themselves out is exactly what this guard prevents.

## Report anything odd

| ID | What you did | What you expected | What actually happened | How bad? |
|---|---|---|---|---|
| | | | | |

*(For us, later: each defect gets a **root cause & lesson** — was the story too vague, did we read it wrong, or did we miss something? — recorded so we don't repeat it.)*
