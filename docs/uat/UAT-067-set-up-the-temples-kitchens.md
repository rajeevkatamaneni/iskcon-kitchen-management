# UAT-067: Set up the temple's kitchens

| | |
|---|---|
| **Feature area** | Kitchens — the register |
| **Technical stories** | E10-S2 (the kitchens register), E10-S3 (the kitchens page) |
| **Roles exercised** | Temple admin, kitchen staff |
| **Depends on** | UAT-008 (the team must exist, so a kitchen can have somebody in charge) |
| **Environment needs** | None |

## What this feature is for

A temple is not one kitchen. It is three to ten under one roof — the Deity kitchen, the prasadam
kitchen, a sweets kitchen, a Food-for-Life kitchen, a guest-house kitchen — and they all share one
store room. Until the system knows they exist, it cannot tell them apart when they ask the store for
ingredients. This screen is where a temple writes down which kitchens it runs.

## How it is supposed to work

- A kitchen has a **name**, a **description**, a **location** in the temple's own words, **who runs it**,
  a **contact phone**, and two questions:
  - **"This is the temple's main kitchen"** — a label saying which one is the principal kitchen. At most
    one kitchen can hold it, and the database enforces that, not the screen.
  - **"Does this kitchen plan its meals here?"** — the question that changes behaviour. Leave it off for
    now; UAT-072 is the test for it.
- Kitchens are **flat**. A kitchen is never inside another kitchen. All of them hang off the temple.
- A temple's **first** kitchen is made the main one automatically — there is nothing else for the flag
  to sit on.
- Marking a **second** kitchen main **names the kitchen that will lose it** and waits to be confirmed.
  It is never a silent overwrite.
- **Delete when nothing refers to it, archive when something does.** A kitchen named on six months of
  requests cannot be deleted without hollowing that history out, so the confirmation offers Archive
  instead and says why.
- Only a **Temple Admin** manages kitchens. Kitchen staff never see the screen — but they do see the
  kitchen list inside the request form, because you cannot ask for ingredients without naming a kitchen.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/kitchens** (menu: **Kitchen → Kitchens**, last in the group)
- **You will create these five kitchens.** UAT-068 to UAT-072 use them by name, so create all five:

| Name | Location | In charge | Contact phone | Main? | Plans its own meals? |
|---|---|---|---|---|---|
| Deity Kitchen | Behind the Deity hall | Gopal Das | +91 80 2345 6001 | created main | No |
| Prasadam Kitchen | Ground floor, east wing | Yamuna Devi Dasi | +91 80 2345 6002 | you will move it here | No |
| Sweets Kitchen | Beside the prasadam counter | Gopal Das | +91 80 2345 6003 | No | No |
| Food for Life Kitchen | Outbuilding, near the gate | — (leave blank) | +91 80 2345 6004 | No | No |
| Guest House Kitchen | Guest house, first floor | — (leave blank) | — (leave blank) | No | No |

- Leave **"Does this kitchen plan its meals here?"** switched **off** on every one of them. UAT-072 turns
  it on and checks what happens.

## Steps

### The register

| # | Do this | You should see |
|---|---|---|
| 1 | Look at the menu | Under **Kitchen**: Recipes, Ingredients, Inventory, **Ingredient requests**, **Kitchens**. Kitchens is last — it is setup, not daily work |
| 2 | Open **Kitchens** | An empty state explaining what a kitchen is here for, and **Add a kitchen** at the **top right** — not a form sitting on the page |
| 3 | Press **Add a kitchen** | A screen of its own: the task is the heading, the menu is still on the left, and **Cancel** and **Add kitchen** sit together at the top right. No second button at the foot, no *← Back* |
| 4 | Read the two checkboxes | **"This is the temple's main kitchen"** is **ticked and greyed out**, with a line saying why — this is the temple's first kitchen. **"Does this kitchen plan its meals here?"** is off and can be changed |
| 5 | Fill in **Deity Kitchen** from the table and save | You land back on the **list**, with a green line confirming Deity Kitchen was added. It appears in the table, badged **Main** |
| 6 | Refresh the page | The green line does **not** come back |
| 7 | Add **Prasadam Kitchen** | On this one the main checkbox is **enabled and unticked**. Save without ticking it |
| 8 | Add **Sweets Kitchen**, **Food for Life Kitchen** and **Guest House Kitchen** | Five kitchens listed, exactly one badged **Main**. Guest House Kitchen saves with no person in charge and no phone — both are optional |

### Exactly one main kitchen

| # | Do this | You should see |
|---|---|---|
| 9 | **Edit** Prasadam Kitchen and tick **This is the temple's main kitchen** | A warning that names **Deity Kitchen** — by name, not "the current main kitchen" — and says it will stop being the main one. Nothing has moved yet |
| 10 | Cancel out of that warning, then leave the screen without saving | Deity Kitchen is still badged **Main** |
| 11 | Do it again and **confirm** this time | Back on the list: **Prasadam Kitchen** is badged **Main** and Deity Kitchen is not. Exactly one badge, not two, not none |
| 12 | Open **/audit** | Two rows for that one act — the kitchen that gained the flag and the kitchen that lost it — both naming you and the time |
| 13 | Open Prasadam Kitchen's edit screen in **two browser tabs**, tick main in both, save the first, then save the second | The second save is refused: *Somebody else changed your temple's main kitchen a moment ago* (`KMS-4985`), telling you to look at the list and set it again if you still want to. **If you cannot make this happen, write that down** — it is a rare race and worth knowing whether the guard is reachable |

### Names

| # | Do this | You should see |
|---|---|---|
| 14 | Add a kitchen called `sweets kitchen` — lower case, same words | Refused: *Your temple already has a kitchen with that name* (`KMS-4972`), suggesting a name that tells them apart |
| 15 | Add a kitchen with the name box left empty | Refused, in the form, before anything is sent |
| 16 | Edit Guest House Kitchen, change its location to `Guest house, ground floor`, and save | The change is kept and you land back on the list |

### Delete, or archive

| # | Do this | You should see |
|---|---|---|
| 17 | Press **Delete** on **Guest House Kitchen** — nothing refers to it yet | A confirmation, then it is gone from the list |
| 18 | Open the address of the kitchen you just deleted (paste the URL you had open in step 16) | *We couldn't find that kitchen* (`KMS-4974`), suggesting it may have been archived and to pick from the list |
| 19 | *(Come back after UAT-068)* Press **Delete** on **Prasadam Kitchen**, which requests now name | Refused: *This kitchen has asked for ingredients before, so it can't be removed* (`KMS-4973`), and the confirmation **offers Archive instead** and says why |
| 20 | *(After UAT-068)* Archive it | It stops appearing as a choice on a new request, its past requests still read correctly and still say "Prasadam Kitchen" |
| 21 | *(After UAT-068)* Restore it from the list | It is selectable again |
| 22 | *(After UAT-068)* Archive **Food for Life Kitchen**, then try to raise a request naming it by pasting its address | Refused: *That kitchen has been archived* (`KMS-4975`), telling you to restore it or pick a different one |

### Who may see this

| # | Do this | You should see |
|---|---|---|
| 23 | Sign out; sign in as `ikms.kitchen-staff.1@trading4good.org` | The menu has **Ingredient requests** but **no Kitchens** entry |
| 24 | Type **/kitchens** into the address bar as that person | Refused — you are told you do not have permission (`KMS-4301`), not shown a broken page and not shown the kitchens |
| 25 | Open **/ingredient-requests/new** as that same person | The **Kitchen** dropdown lists the temple's kitchens. Reading the list rides on being able to raise a request; managing it does not |
| 26 | Sign in as `ikms.temple-admin.2@trading4good.org` (the second temple) and open **/kitchens** | **None** of these five kitchens is there. That temple sees only its own |
| 27 | As the second temple's admin, add a kitchen also called `Deity Kitchen` | **Accepted** — the same name in a different temple is not a duplicate |

## It passes if

- [ ] Five kitchens can be recorded with name, description, location, who runs it and a phone.
- [ ] The first kitchen a temple creates is main, ticked and greyed, with a line saying why.
- [ ] Exactly one kitchen is main at any moment, and moving the flag names the losing kitchen first and waits.
- [ ] A duplicate name in the same temple is refused (`KMS-4972`); the same name in another temple is not.
- [ ] An unreferenced kitchen deletes; a referenced one is refused (`KMS-4973`) and offers Archive.
- [ ] An archived kitchen cannot be chosen (`KMS-4975`); a deleted one gives `KMS-4974`.
- [ ] The list → form → list transition matches Recipes, and the green line appears once and does not replay on refresh.
- [ ] Kitchen staff cannot reach `/kitchens` but can still choose a kitchen on a request.
- [ ] One temple's kitchens are invisible to another.

## Watch out for

- **Two kitchens both badged Main, or none at all.** That is a Blocker — the rule is supposed to be enforced by the database, so if the screen can produce it, something deeper is wrong. Note exactly what you did.
- The main-kitchen warning saying *"the current main kitchen"* instead of naming **Deity Kitchen**. The design is explicit that it names the kitchen; a generic warning is a Major finding.
- **Delete that silently succeeds on a referenced kitchen.** Check step 19 again after UAT-068 even if you already ran it — the refusal only becomes possible once something refers to the kitchen.
- A kitchen form that asks for a head count, a cuisine, an opening time or a photo. Those were deliberately left out; if you see one, record it — an unread field is a field somebody fills in every time for no one.
- Any hint of a kitchen sitting **inside** another kitchen — a parent field, an indent, a tree. There is no hierarchy here; one level only.
- Whether **"Does this kitchen plan its meals here?"** is explained on the form. It is the one field that changes behaviour, and a person ticking it without understanding it is what UAT-072 exists to catch.
- Archiving that hides a kitchen's **past** requests as well as itself. History must stay readable.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT067-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
