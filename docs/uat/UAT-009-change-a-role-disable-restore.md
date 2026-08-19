# UAT-009: Change a role; disable and restore someone

| | |
|---|---|
| **Feature area** | Platform foundation — temple user management |
| **Technical stories** | E1-S12 (devotee register), E6-S8 (hiring), E1-S7 (audit log framework) |
| **Roles exercised** | Temple admin, kitchen staff |
| **Depends on** | UAT-008 |
| **Environment needs** | None (a change-notification message only arrives if a channel is live) |

## What this feature is for

People move on. A volunteer becomes a cook; a cook leaves. A temple administrator must be able to
change what someone can do and to stop access when they leave — without deleting them, because the
temple's history refers to them. This test also proves an administrator cannot accidentally lock
themselves out.

## How it is supposed to work

**Revised 2026-08-19.** There is no longer a role dropdown anywhere, and that is deliberate: a
devotee holds one role by definition, so there was never a choice to make. What used to be a role
change is now two named acts on the **Staff** page — being hired, and employment ending — and those
are covered in depth by UAT-064. What is left here is the decision this screen still owns:
**whether somebody may sign in.**

- Disabling is a **status change, never a deletion**: their past work, movements and audit history stay
  intact and still name them.
- It takes effect on the person's next request.
- Guards: you cannot disable yourself, you cannot end your own employment, and you cannot touch
  anyone at another temple.
- There is no path to a platform operator, from any screen.
- Every one of these is written to the audit trail — including refused attempts.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/users** (menu: **People → Devotees**)
- You will need a second browser (or private window) to keep a kitchen-staff session open.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | On **Devotees**, look for any way to change what somebody can do | There is none — no role dropdown, no add form. Becoming staff is being hired (UAT-064) |
| 2 | Open **Staff** and confirm the same for former staff | Their rows offer neither Edit nor End employment |
| 3 | *(Covered fully in UAT-064)* Confirm a devotee promoted to staff keeps the one account they had | One row, one history |
| 4 | Try to change **your own** access on the Staff page | Refused with `KMS-4302` — *You can't change your own role* |
| 5 | Try to disable **your own** account | Not offered on Devotees (you are not one); on Staff, ending your own employment is refused with `KMS-4304` |
| 6 | Look for a way to make anyone a platform operator | There is none. (If one exists, that is a Blocker; the refusal code would be `KMS-4303`) |
| 7 | On **Devotees**, disable **Nitai Das** (`ikms.volunteer.1@…`) | His row shows **Disabled** |
| 8 | In a private window, sign in as `ikms.volunteer.1@trading4good.org` | Refused. The message says the account has been disabled and to ask the temple administrator (`KMS-4103`), or he is treated as having no account — either way he cannot get in |
| 9 | As the admin, re-enable Nitai Das | His row shows **Active** again |
| 10 | Sign in as Nitai Das again | He gets in normally, and his earlier work — shift signups, donations — is still attributed to him |
| 11 | Go to **/audit** | Entries for the disable and the enable, each naming you as the actor, with a timestamp |

## It passes if

- [ ] There is no role dropdown on any screen.
- [ ] A disabled person cannot sign in; re-enabling restores access.
- [ ] Disabling never removes the person or their history.
- [ ] You cannot remove your own access or end your own employment.
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
