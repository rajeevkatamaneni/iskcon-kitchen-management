# UAT-002: Bring a temple onto the platform

| | |
|---|---|
| **Feature area** | Platform foundation — tenant provisioning |
| **Technical stories** | E1-S6 (tenant provisioning), E1-S3 (tenant model), E1-S7 (audit log), E2-S1 (ingredient seed), E2-S2 (recipe categories), E4-S2 (festival occasions), E4-S4 (meal slots) |
| **Roles exercised** | Platform operator (Super-admin) |
| **Depends on** | UAT-001 |
| **Environment needs** | None. (The temple's Vaishnava calendar is built by the background worker — if the worker is off, see UAT-029.) |

## What this feature is for

Temples do not sign themselves up. A platform operator brings each one on deliberately, creating its
workspace and its first administrator in a single act — because a temple nobody can sign into is a
dead record. This is the front door for the entire product: everything in every later test lives
inside the temple created here.

## How it is supposed to work

- One form, not a wizard: who the temple is, where it is, and who runs it.
- The **web address** is not typed. It is derived from the temple's name and previewed underneath it,
  because a hand-typed address is a support problem waiting to happen.
- **Location is required and load-bearing.** The Vaishnava calendar is computed from the temple's own
  latitude, longitude and timezone — tithi is determined at *local* sunrise, so two temples in
  different cities can legitimately have different Ekadashi dates.
- **80G** is a per-temple setting. It decides whether donors are later offered the tax-certificate path.
- A new temple does not start empty: the prohibited (non-sattvic) ingredients, the common Ekadashi
  grains and beans, recipe categories, the pan-ISKCON festival occasions, and default meal slots are
  all seeded, so nobody has to remember to flag garlic.
- The act is written to the new temple's audit trail.

## Before you start

- **Sign in as:** `ikms.super-admin.1@trading4good.org` (platform operator)
- **Start at:** **/tenants**
- **You will create:** a demo temple whose first administrator is `ikms.temple-admin.1@trading4good.org`.
  Everything after this test uses it, so use exactly that address.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | On **Temples**, click **Add a temple** | The form, in three sections: *The temple*, *Where it's located*, *Who runs it* |
| 2 | In **Name**, type `Sri Sri Radha Govinda Temple` | Under the field, faintly: *Short name: sri-sri-radha-govinda-temple*, forming as you type. It is an internal identifier — it names the temple's data export, and no longer any public web address |
| 3 | Fill **Address** with `Bengaluru, Karnataka` | Accepted |
| 4 | Press **Add temple** without filling latitude, longitude or the administrator | The form refuses and marks the missing fields. Nothing is created |
| 5 | Enter **Latitude** `999` and **Longitude** `77.5946` | On submit, a clear message that latitude must be between -90 and 90, against that field |
| 6 | Correct latitude to `12.9716`. Leave **Timezone** as *Asia/Kolkata (IST)* and **Currency** as *Indian rupee (INR)*. Tick **Approved for 80G receipts** | Accepted |
| 7 | Under *Who runs it*, enter full name `Radha Govinda Das`, email `ikms.temple-admin.1@trading4good.org`, phone `+919876543210` | Accepted |
| 8 | Press **Add temple** | A cooking animation reading *Setting up Sri Sri Radha Govinda Temple…*, then the Temples list with a green banner: *sri-sri-radha-govinda-temple is ready. Its administrator can sign in with the email address you entered.* |
| 9 | Look at the new row in the list | Temple name, web address `sri-sri-radha-govinda-temple`, timezone `Asia/Kolkata`, **People 1**, 80G **Approved** |
| 10 | Click **Add a temple** again and create a second temple named `Sri Sri Radha Govinda Temple` (same name) | Refused, with a message on the **Name** field: another temple already uses a very similar name — make it more specific. (The code behind this is `KMS-4901`) |
| 11 | Now create the second temple properly: name `ISKCON Chowpatty`, address `Mumbai, Maharashtra`, latitude `18.9548`, longitude `72.8127`, timezone *Asia/Kolkata*, currency INR, **80G left unticked**, administrator `ikms.temple-admin.2@trading4good.org`, name `Chowpatty Das`, phone `+919876543211` | Created. Two temples now in the list; the second shows 80G **Not approved** |
| 12 | Enter a phone number with spaces, e.g. `+91 98765 43212`, on a third attempt | It is accepted — spaces are cleaned up rather than rejected. (Cancel this third temple; you only need two) |

## It passes if

- [ ] A temple and its first administrator are created together from one screen.
- [ ] The web address is derived from the name and shown as a preview, never typed by hand.
- [ ] Missing required fields, an impossible latitude, and a duplicate name are each refused with a message that says what to fix.
- [ ] The new temple appears in the list with the right timezone, one person, and the right 80G state.
- [ ] Two temples can exist side by side with different settings.

## Watch out for

- Nothing technical on screen. A refusal should read like a sentence, with a `KMS-nnnn` code you can quote — never a stack trace or a database message.
- The 80G tick matters later: UAT-055 checks that the 80G donation path appears on the *approved* temple's page and is **absent** on the other one. If the tick is ignored here, that test will fail for this reason.
- Watch the *People* count. It should be **1** — the administrator you just named.
- If the temple is created but the success banner never appears, or the list does not refresh, note it: the record may be fine while the screen is wrong.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT002-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
