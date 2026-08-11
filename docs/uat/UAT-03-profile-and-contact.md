# UAT-03 — Your profile and how the temple reaches you

> New here? Read `README.md` first. This test needs the people added in **UAT-02**.

## Objective

Everyone at the temple decides for themselves how they want to be reached — WhatsApp, SMS, or email — and gives their own consent to be contacted. Nobody is messaged until they've agreed. This test checks that each person controls their own contact settings.

## What we're testing

- A person can see their own contact details (name, email, phone).
- They can choose their preferred channel, and the choice saves straight away.
- They must give consent before the temple will send them anything, and once given, it's clearly recorded.

## Built from

Coding story `E1-S8` (contact channel and consent to be contacted).

## Before you start

- **UAT-02 is done** — the volunteers exist and have signed in at least once.
- **Sign in as a volunteer** (`ikms.volunteer.1@trading4good.org`).
- Open the side menu and click **Your account** (or go to `__TEST_SITE_URL__/profile`).

---

## Part A — See your details

| Step | What to do | What you should see |
|---|---|---|
| A1 | Look at the **Contact details** box. | Your name, email (`ikms.volunteer.1@trading4good.org`), and the phone the admin entered in UAT-02 (`+919000000021`). |
| A2 | Notice the note under the heading. | It says these are set when your account is created, and to ask your temple administrator to change them. You can't edit them here — that's expected. |

## Part B — Choose how you're reached

| Step | What to do | What you should see |
|---|---|---|
| B1 | In **Preferred channel**, select **SMS**. | The choice is selected. It saves on its own — there's no separate Save button. |
| B2 | Now select **WhatsApp**. | The selection moves to WhatsApp and saves. |
| B3 | Leave the page and come back to **Your account**. | WhatsApp is still selected — your choice was remembered. |

## Part C — Give your consent

| Step | What to do | What you should see |
|---|---|---|
| C1 | Read the **Consent to be contacted** box. | A short plain-language consent statement. Because you haven't agreed yet, there's an **I agree** button and a note that until you agree, no reminders will be sent. |
| C2 | Click **I agree**. | The button is replaced by a green confirmation — "You agreed on \<today's date\>". |
| C3 | Leave the page and return. | It still shows you've agreed, with the date. You aren't asked again. |

**When you're done:** sign out. The next test tells you who to sign in as.

---

## Did it pass?

- [ ] Your name, email and phone showed correctly, and were read-only with a note to ask the admin to change them.
- [ ] You could pick a preferred channel and it saved by itself and survived leaving and returning.
- [ ] Before agreeing, the page clearly said no reminders would be sent; after clicking **I agree**, it showed your agreement with the date, and didn't ask again.

## If something looks wrong

- **Your phone or email is wrong or blank.** Note what it shows versus what the admin entered in UAT-02.
- **The channel choice doesn't stick** after you leave and return. Report it — the preference should be saved immediately.
- **You could send yourself reminders (or the system did) before agreeing**, or there was no consent step at all. Report it — consent must come first.

## Report anything odd

| ID | What you did | What you expected | What actually happened | How bad? |
|---|---|---|---|---|
| | | | | |

*(For us, later: each defect gets a **root cause & lesson** — was the story too vague, did we read it wrong, or did we miss something? — recorded so we don't repeat it.)*
