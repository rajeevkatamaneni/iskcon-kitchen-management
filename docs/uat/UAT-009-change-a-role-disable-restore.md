# UAT-009: Change a role; disable and restore someone

| | |
|---|---|
| **Feature area** | Platform foundation — temple user management |
| **Technical stories** | E1-S12 (temple user management), E1-S7 (audit log framework) |
| **Roles exercised** | Temple admin, kitchen staff |
| **Depends on** | UAT-008 |
| **Environment needs** | None (a change-notification message only arrives if a channel is live) |

## What this feature is for

People move on. A volunteer becomes a cook; a cook leaves. A temple administrator must be able to
change what someone can do and to stop access when they leave — without deleting them, because the
temple's history refers to them. This test also proves an administrator cannot accidentally lock
themselves out.

## How it is supposed to work

- Changing a role takes effect on the person's next request, and immediately on their next sign-in.
- Disabling is a **status change, never a deletion**: their past work, movements and audit history stay
  intact and still name them.
- Four guards protect this screen: you cannot change your own role, you cannot disable yourself, you
  cannot promote anyone to platform operator, and you cannot touch anyone at another temple.
- Every add, role change and disable is written to the audit trail — including refused attempts.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/users** (menu: **People**)
- You will need a second browser (or private window) to keep a kitchen-staff session open.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | On **People**, find **Nitai Das** (volunteer) and change his role to **Kitchen staff** | The list updates; his role now reads Kitchen staff |
| 2 | Sign out; sign in as `ikms.volunteer.1@trading4good.org` (Nitai Das) | He now lands on the kitchen menu — Recipes, Inventory and the rest — and still sees **My shifts** |
| 3 | Sign out; sign back in as the temple admin. Change Nitai Das back to **Volunteer** | The change is accepted |
| 4 | Try to change **your own** role | The screen does not offer it. If you can reach it another way, the system refuses with `KMS-4302` — *You can't change your own role* |
| 5 | Try to disable **your own** account | Not offered; if forced, refused with `KMS-4304` — *You can't disable your own account* |
| 6 | Look for a way to make anyone a platform operator | There is none. (If one exists, that is a Blocker; the refusal code would be `KMS-4303`) |
| 7 | Disable **Gopal Das** (`ikms.kitchen-staff.1@…`) | His row shows **Disabled** |
| 8 | In a private window, sign in as `ikms.kitchen-staff.1@trading4good.org` | Refused. The message says the account has been disabled and to ask the temple administrator (`KMS-4103`), or he is treated as having no account — either way he cannot get in |
| 9 | As the admin, re-enable Gopal Das | His row shows **Active** again |
| 10 | Sign in as Gopal Das again | He gets in normally, and his earlier work (if any) is still attributed to him |
| 11 | Go to **/audit** | Entries for the role changes and the disable/enable, each naming you as the actor, with a timestamp |

## It passes if

- [ ] A role change takes effect for that person on their next sign-in.
- [ ] A disabled person cannot sign in; re-enabling restores access.
- [ ] Disabling never removes the person or their history.
- [ ] You cannot change your own role or disable yourself.
- [ ] There is no path to create a platform operator.
- [ ] Every one of these actions appears in the temple's audit log.

## Watch out for

- A disabled person who can still use an *already open* session for a long time. A short delay (up to about an hour, while their existing sign-in token lives) is expected for some checks; being able to keep working indefinitely is a Major defect. Note how long you could still act.
- The person's name still appearing correctly on old records after being disabled — if past entries suddenly read "unknown", that is a Major defect.
- If a role change silently does nothing, check by signing in as that person rather than trusting the list.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT009-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
