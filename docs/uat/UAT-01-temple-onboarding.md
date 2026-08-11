# UAT-01 — Bring a temple onto the platform, and sign in for the first time

> New here? Read `README.md` first (it takes two minutes and tells you how to sign in). Then come back.

## Objective

Every temple on this system is its own private workspace. Someone who runs the *platform* (not any one temple) creates that workspace and names the first person who will run the temple. This test does that — and then checks that the new temple's administrator can actually sign in and get to work.

**This is the first test for a reason:** the temple you create here, *Sri Sri Radha Govinda Temple*, is the temple every later test uses. Nothing else works until this one passes.

## What we're testing

- A platform operator can create a temple and its first administrator together.
- The new temple shows up as active, with one person in it.
- That new administrator can sign in straight away and lands in their (empty) temple.
- The system refuses obviously wrong input (a bad web address, an impossible location) with a clear message.
- A temple administrator **cannot** do platform-operator things — the walls between the two hold.

## Built from

Coding stories `E1-S6` (creating a temple), `E1-S4` (signing in), `E1-S5` (who's allowed to do what), `E1-S13` (the very first operator). *If this test finds a defect, that's where we go back and look.*

## Before you start

- Nothing — this is the first test. The site should be freshly set up with no temples yet.
- **Sign in as the Platform operator** (`ikms.super-admin.1@trading4good.org` — see the accounts table in `README.md`).

---

## Part A — Create the temple

| Step | What to do | What you should see |
|---|---|---|
| A1 | In your browser go to **`https://kms-staging-web-bnpkv5hfrq-el.a.run.app`**. If you're not already signed in, it sends you to the sign-in page (`https://kms-staging-web-bnpkv5hfrq-el.a.run.app/sign-in`). Click **Continue with Google** and choose **`ikms.super-admin.1@trading4good.org`**. | You land on the **Temples** page — the address bar now shows `https://kms-staging-web-bnpkv5hfrq-el.a.run.app/tenants`. The list is empty on a fresh site (or shows temples from an earlier run). |
| A2 | Click **Add a temple**. | A form with three sections: *The temple*, *Where it is*, *Who runs it*. |
| A3 | Fill in **The temple**: Name = `Sri Sri Radha Govinda Temple`, Web address = `radha-govinda`, Address = `Bengaluru, Karnataka`. | The text appears as you type. The web address only accepts lowercase letters, numbers and hyphens. |
| A4 | Fill in **Where it is**: Latitude = `12.9716`, Longitude = `77.5946`, Timezone = `Asia/Kolkata (IST)`, Currency = `Indian rupee (INR)`. Tick **Approved for 80G receipts**. | The values appear. The timezone and currency are drop-downs. |
| A5 | Fill in **Who runs it**: Full name = `Karuna Murthy Das`, Email = **`ikms.temple-admin.1@trading4good.org`** (the *Temple admin* account from the README), Phone = `+919876543210`. | The email must be the real Temple-admin Google account — you'll sign in as this person in Part C. |
| A6 | Click **Add temple**. | A green message: **"radha-govinda is ready."** with a note that its administrator can sign in with the email you entered. |
| A7 | Click **Temples** (top of the form) to go back to the list. | *Sri Sri Radha Govinda Temple* is now in the list, marked **Active**, with a person count of **1**. |

## Part B — The system refuses bad input

Do these quick checks. Each one should be **refused with a plain message** — nothing should crash, and nothing should be silently accepted.

| Step | What to do | What you should see |
|---|---|---|
| B1 | Click **Add a temple** again. In *Web address*, type `radha-govinda` (the one you just used), fill the rest with anything valid, and submit. | Refused. A message that another temple is already using that web address. *(You may also see a code like `KMS-4901` — note it if you report anything.)* |
| B2 | On the same form, set **Latitude** to `200` and submit. | Refused. A red message under the Latitude box: **"Latitude must be between -90 and 90."** |
| B3 | Leave the **Name** blank and submit. | Refused, with a message asking for the temple's name. The form does not submit. |
| B4 | Click **Cancel** to leave the form without creating anything. | Back to the Temples list, still showing just the one temple from Part A. |

## Part C — The new administrator signs in

| Step | What to do | What you should see |
|---|---|---|
| C1 | **Sign out** (top-corner menu → Sign out). | You're back at the sign-in page. |
| C2 | Sign in with **Continue with Google**, this time as the **Temple admin** (`ikms.temple-admin.1@trading4good.org`). | This is their first sign-in, so the account you created is now claimed by them. You land on **Your account** — the address bar shows `https://kms-staging-web-bnpkv5hfrq-el.a.run.app/profile` — with a menu of temple sections down the left. |
| C3 | Look at the side menu on the left. | It lists temple sections — **Recipes, Ingredients, Inventory, Meal plan, Vendors, People**, and so on. It is **not** the platform menu (which shows only *Temples* and *Operations*). This person runs one temple, not the platform. |

## Part D — The walls hold

| Step | What to do | What you should see |
|---|---|---|
| D1 | While still signed in as the **Temple admin**, type `https://kms-staging-web-bnpkv5hfrq-el.a.run.app/tenants/new` directly into the address bar and press Enter. | You are **not** allowed in — you should be turned away (a "not your page" message or a bounce back to your temple). A temple admin cannot create temples. |

---

## Did it pass?

Tick each. If all are true, UAT-01 passed.

- [ ] The platform operator created *Sri Sri Radha Govinda Temple* and it shows as **Active** with **1** person.
- [ ] A duplicate web address, an impossible latitude, and a blank name were each refused with a clear message — nothing crashed.
- [ ] The temple admin signed in with Google and landed in their own (empty) temple with the temple menu.
- [ ] The temple admin could **not** reach the "Add a temple" page.

## If something looks wrong

- **You can't sign in as the temple admin in Part C.** This is the single most important thing to report. It means a newly created administrator is locked out. Note exactly what the screen said.
- **The Google account you used for the temple admin is different from the email you typed in step A5.** They must match — the system links you to the account by that email. If you used a different Google account, redo Part A with the right email.
- **Bad input in Part B is silently accepted** (no message, and the temple is created anyway). Report it — the system should never quietly accept a wrong location or a duplicate address.
- **In Part D you *are* allowed onto the page.** Report it — that's a wall that should have held.

## Report anything odd

| ID | What you did | What you expected | What actually happened | How bad? |
|---|---|---|---|---|
| | | | | |

*(For us, later: each defect gets a **root cause & lesson** — was the story too vague, did we read it wrong, or did we miss something? — recorded so we don't repeat it.)*
