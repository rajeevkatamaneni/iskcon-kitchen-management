# User Acceptance Testing — ISKCON Seva Kitchen

Welcome, and thank you for testing. **You do not need to know anything about this system to run these tests.** Everything you need is written down here and in each test. Just start at the top and work down.

## How this works

These tests are a **guided tour**. They are meant to be run **in order**, because each one builds on the one before it. In the first test you bring a new temple onto the system; in the next you add its people; then its recipes, its stock, its meal plans, and so on — the same way a real temple would set itself up. By the time you reach the end, you will have used almost every part of the product.

So: **don't skip around.** If a test says "before you start, you should have finished UAT-02," that's because it needs what UAT-02 left behind.

Each test is one short document that tells you:

- **Objective** — what this part of the system is for, in one or two plain sentences.
- **What we're testing** — the specific things to check.
- **Before you start** — which earlier test must be done first, and who to sign in as.
- **Steps** — exactly what to do, one row at a time, and what you should see after each.
- **Did it pass?** — a short checklist. If every box is true, the test passed.
- **If something looks wrong** — the kinds of problems to watch for.
- **Report anything odd** — where you write down what went wrong (see below).

## Before you begin (you only read this once)

You will be given:

1. **A web address** for the test site — written here: **`__TEST_SITE_URL__`** *(the person who set up the environment fills this in)*.
2. **A set of sign-in accounts**, one for each kind of person in the system. You'll be told in each test which one to use.

### The accounts

You sign in with **"Continue with Google"** — no passwords to type into the app. You've been given the password for each account separately (it is not in this pack). All accounts are on the `trading4good.org` domain.

**The people who run things** — used from the very first test:

| Who they are | Sign in as | First used in |
|---|---|---|
| **Platform operator** (runs the whole platform) | `ikms.super-admin.1@trading4good.org` | UAT-01 |
| **Temple admin** (runs our demo temple) | `ikms.temple-admin.1@trading4good.org` | UAT-01 |

**Kitchen staff** (cooks and storekeepers) — used from UAT-02. Any of:

`ikms.kitchen-staff.1@trading4good.org` … `ikms.kitchen-staff.5@trading4good.org`

**Volunteers** (sign up for seva) — used from UAT-02. Any of:

`ikms.volunteer.1@trading4good.org` … `ikms.volunteer.5@trading4good.org`

**Two spares, used only where a test says so:**

| Account | What it's for |
|---|---|
| `ikms.super-admin.2@trading4good.org` | Proving a *second* platform operator can also sign in and work |
| `ikms.temple-admin.2@trading4good.org` | Runs a *second* temple, so we can prove one temple can't see another's data |

**Donors** (members of the public) — used from UAT-22. Giving from the public donation page needs **no** sign-in, so most donation tests use no account. Two dedicated donor accounts exist for the tests that need a signed-in donor — monthly/recurring giving (UAT-23) and two donors racing for the last wish-list item (UAT-24):

`ikms.donor.1@trading4good.org` and `ikms.donor.2@trading4good.org`

> **To sign out and switch to a different person:** open the menu in the top corner and choose **Sign out**, then sign in again as the account the next step names. Some tests ask you to switch accounts partway through — they'll tell you exactly when.

### How to report something wrong

When a step doesn't do what the test says it should, **don't try to fix it or work around it** — just write it down. At the bottom of each test there's a **Report anything odd** table. Fill in a row:

| ID | What you did | What you expected | What actually happened | How bad? |
|---|---|---|---|---|
| e.g. UAT01-1 | Clicked "Add temple" with no name | A message telling me the name is required | The page did nothing / crashed | Blocker |

**How bad?** — use one of: **Blocker** (can't continue), **Major** (feature is broken but I can move on), **Minor** (wrong wording, layout, small annoyance).

If you ever see a code like **`KMS-1234`** on screen, **write it down** in "What actually happened" — it helps us find the problem fast.

That's everything. You don't need any other knowledge. Start with UAT-01.

---

## The running order

Run these top to bottom. The left column is the order; the "Builds on" column shows what it reuses.

### Part 1 — For everyday users (functional)

| # | Test | Builds on |
|---|---|---|
| UAT-01 | Bring a temple onto the platform, and sign in for the first time | — |
| UAT-02 | Add your team: kitchen staff and volunteers | UAT-01 |
| UAT-03 | Your profile and how the temple reaches you | UAT-02 |
| UAT-04 | Build the ingredient list | UAT-01 |
| UAT-05 | Write recipes and scale them for a crowd | UAT-04 |
| UAT-06 | Keep the kitchen sattvic | UAT-05 |
| UAT-07 | Print and translate a recipe | UAT-05 |
| UAT-08 | Track what's in the store room | UAT-04 |
| UAT-09 | Get warned before you run out | UAT-08 |
| UAT-10 | Keep a register of equipment | UAT-01 |
| UAT-11 | Record a gift of goods (donation in kind) | UAT-08 |
| UAT-12 | The Vaishnava calendar: Ekadashi and festivals | UAT-01 |
| UAT-13 | Plan meals and cook from stock | UAT-05, UAT-08, UAT-12 |
| UAT-14 | The Ekadashi guard | UAT-13 |
| UAT-15 | Vendors and the shopping list | UAT-08, UAT-13 |
| UAT-16 | Purchase orders and receiving deliveries | UAT-15 |
| UAT-17 | Send a purchase order to a vendor (translated / WhatsApp) | UAT-16 |
| UAT-18 | Vendor bills and paying them | UAT-16 |
| UAT-19 | The staff weekly schedule | UAT-02 |
| UAT-20 | Volunteer shifts: post, sign up, release, waitlist | UAT-02 |
| UAT-21 | Shift reminders and broadcasts | UAT-20 |
| UAT-22 | Receive a donation from the public (with 80G) | UAT-01 |
| UAT-23 | Monthly giving (recurring donations) | UAT-22 |
| UAT-24 | The wish list and sponsoring an item | UAT-01 |
| UAT-25 | The donations ledger and accounts | UAT-22, UAT-23, UAT-24 |
| UAT-26 | Operations and health (platform operator) | UAT-01 |

### Part 2 — For a technical tester (technical)

These check things that don't have an ordinary screen — data isolation between temples, the audit trail, payment webhooks, and so on. Hand these to someone comfortable with a browser's developer tools or an API tool. They are listed in `TECHNICAL.md` *(added after the functional pack is approved)*.

---

*Built and maintained alongside the coding stories in `docs/stories/`. Each test names the story it came from, so a defect here points straight back to the story we built it from.*
