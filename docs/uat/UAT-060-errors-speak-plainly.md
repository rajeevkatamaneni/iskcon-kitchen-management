# UAT-060: Errors speak plainly and carry a code

| | |
|---|---|
| **Feature area** | Across the whole product — error presentation |
| **Technical stories** | Cross-cutting: every epic. The rule lives with E1-S5/E1-S6 and is applied by all |
| **Roles exercised** | All |
| **Depends on** | Run this **last**, after everything else — it collects what you saw along the way |
| **Environment needs** | None |

## What this feature is for

Temple staff are not engineers, and the person they ring for help may be a volunteer with no access to
the system. "It didn't work" is not diagnosable. So every failure in this product is supposed to say,
in plain language, **what happened** and **what to do next**, plus a short code that support can trace
to one exact event. Nothing technical is ever supposed to reach the screen.

## How it is supposed to work

- Every user-facing failure has a permanent code of the form **KMS-nnnn**.
- The message says what happened, without blame or jargon, and offers a next step that is not merely
  "contact support".
- Codes are permanent: one quoted from a screenshot a year old still means the same thing.
- No stack trace, no database message, no internal name, no address of an internal system ever appears.

## Before you start

- **Sign in as** whichever role each check needs.
- Keep this test open **while you run the whole pack**, and note every error message you meet. Then come
  back and fill in the table below.

## Steps

Deliberately provoke each of these and record what you see.

| # | Do this | Expected code | You should see |
|---|---|---|---|
| 1 | As kitchen staff, type **/ledger** in the address bar | `KMS-4301` (or the *Not your page* screen) | A plain refusal, no technical detail |
| 2 | As kitchen staff, save a recipe containing Garlic (UAT-018) | `KMS-4906` | Names the ingredient; offers removing it or asking an admin |
| 3 | As kitchen staff, adjust stock by more than a fifth (UAT-025) | `KMS-4305` | Explains an admin must approve, and suggests splitting it |
| 4 | Adjust stock below zero (UAT-024) | `KMS-4910` | Suggests checking against the real count |
| 5 | Cook a meal without enough stock (UAT-035) | `KMS-4911` | Names what is short |
| 6 | Plan khichdi on Ekadashi (UAT-036) | `KMS-4917` | Explains the fast and offers both choices |
| 7 | Edit a sent purchase order (UAT-040) | `KMS-4919` | Explains only drafts can be changed |
| 8 | Record a delivery line with nothing in it (UAT-044) | `KMS-4922` | Asks for a received or rejected quantity |
| 9 | Record a direct invoice with no description (UAT-045) | `KMS-4923` | Asks for a description or a purchase order |
| 10 | Overpay an invoice (UAT-046) | `KMS-4939` | States the outstanding balance |
| 11 | Sign up for a full shift (UAT-049) | `KMS-4931` | Offers the waitlist |
| 12 | Send a fourth broadcast in a day (UAT-053) | `KMS-4935` | Explains the cap and who can raise it |
| 13 | Donate with a bad PAN (UAT-055) | `KMS-4004` | Shows the expected shape, `ABCDE1234F` |
| 14 | Donate with details but without agreeing to the notice (UAT-055) | `KMS-4937` | Offers the anonymous path |
| 15 | Add a person with an email already used at the temple (UAT-008) | `KMS-4902` | Suggests a different address |
| 16 | Sign in with an account nobody has added (UAT-012) | — | The calm "not linked to a temple yet" page |
| 17 | Turn off your internet and press any save button | `KMS-5001` or similar | Something that tells you to try again — never a blank screen or a frozen button |

Then judge every message you collected against these four questions:

- Could a temple cook, with no computer background, understand it?
- Does it say what to do next?
- Is there a code you can write down or read out over the phone?
- Is there anything technical in it — a class name, a URL, a database word, a stack trace?

## It passes if

- [ ] Every failure above produces a message in plain language with a next step.
- [ ] Every one carries a quotable `KMS-nnnn` code (or, for the pure screens, a clear plain-English page).
- [ ] No message anywhere contains technical detail.
- [ ] No action ever fails silently — a button that does nothing at all is as bad as a bad message.

## Watch out for

- **Silence.** The worst failure is a button that does nothing: no message, no code, nothing. Record every instance with what you had just done.
- A code shown on screen that does not match the code listed above. Record both — the code numbering is permanent, so a mismatch matters.
- A message that blames the user ("you entered invalid data").
- Anything that says only "contact support" with no other route forward.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT060-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
