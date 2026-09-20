# UAT-093: Kitchens in the meal planner

| | |
|---|---|
| **Feature area** | Meal planning — which kitchen is cooking; staff belong to a kitchen; who may open the planner |
| **Technical stories** | E12-S1 (the kitchen on every dish) · E12-S2 (the planner asks, and refuses without an answer) · E12-S3 (the composer's kitchen sections) · E12-S4 (every surface says whose meal it is) · E12-S5 (one job card per kitchen) · E12 staff kitchen · E12 planner access |
| **Roles exercised** | Temple Admin, Kitchen Manager, Kitchen Staff, Volunteer |
| **Depends on** | UAT-067 (the kitchens exist), UAT-072 (Sweets Kitchen plans its meals here), UAT-008 (the team is hired), UAT-015 (recipes), UAT-032 (plan a meal), UAT-047 or UAT-078 (somebody rostered on the day) |
| **Environment needs** | **Epic 12 deployed to the test site.** As of 2026-09-19 it is built but not deployed, so none of this can be run until it is. The job card steps (31–34) need the document renderer (README §4) |

## What this feature is for

A temple has several kitchens under one roof. Until now a meal belonged to the temple and to nobody in
particular, so "Lunch on Friday" did not say who was cooking it. Rajeev's own sentence for what it
should say: *"Main kitchen is making the meal and Sweets Kitchen is making the sweets."*

That is **one Lunch cooked by two kitchens**, not two Lunches. So a meal now has a section per
kitchen. Each section has its own dishes, its own **People needed**, its own rostered staff and its
own job card. And because rostering per kitchen only works if people belong to a kitchen, **every
staff member now belongs to exactly one kitchen**, and only people whose kitchen plans its meals here
can open the meal planner.

## How it is supposed to work

- **A meal has one section per kitchen cooking it.** The composer starts with one section and offers
  **+ Add another kitchen**. That list offers only kitchens that plan their meals here and are not
  already on the meal, and it can be searched.
- **The first section is your own kitchen**, the one on your staff record. If you have no kitchen, or
  your kitchen doesn't plan meals here, it is the main kitchen.
- **Each section has its own dishes and its own People needed.** A dish belongs to one kitchen. A
  recipe already in one section is not offered in another.
- **Removing a section with dishes asks first, inside the section:** *Remove Sweets Kitchen and its 1
  dish?* with **Remove** and **Keep it**. An empty section goes at once. The only section left has no
  ×. There is no "move" anywhere.
- **Saved, it is one Lunch card with a section per kitchen.** Your own kitchen's section comes first.
  If your kitchen isn't on the meal, the main kitchen comes first. The rest follow the order the
  kitchens are listed on **/kitchens**.
- **Each section has its own Download job card.** The card lists only that kitchen's dishes and names
  the kitchen. Both cards carry the meal's one card number.
- **The meal's volunteers are counted once**, in the main kitchen's section. If the main kitchen is
  not on the meal, they are counted in the first section. *(An assumption made while building, not
  yet confirmed by Rajeev. See "Watch out for".)*
- **Every staff member has a kitchen.** Adding or editing a staff member cannot be saved without
  one. When this shipped, everyone already on staff was put in the main kitchen, and the Temple
  Admin gets a **Check these kitchen assignments** list to move anyone who belongs elsewhere.
- **Who may open the meal planner.** The Temple Admin always can, for every kitchen. Anyone else
  needs the planner permission they already had **and** a current staff record in a kitchen that
  plans its meals here. Everyone else doesn't see **Meal planner** in the menu, and typing the
  address gets **Not your page**. The server refuses them with `KMS-400183` behind that screen.
  Volunteers never had the planner and still don't.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (Temple Admin), unless a step says otherwise.
- **Kitchens.** Open **/kitchens** and check, without changing anything else:
  - the main kitchen (**Prasadam Kitchen** if you ran UAT-067) **plans its meals here**. If it
    doesn't, turn it on. Turning it on settles that kitchen's open ingredient requests, exactly as
    UAT-072 describes, and asks you first;
  - **Sweets Kitchen** plans its meals here (UAT-072 turned it on);
  - **Food for Life Kitchen** does **not** plan its meals here, and is active.
  Below, "the main kitchen" means Prasadam Kitchen. Use your own main kitchen's name if it differs.
- **Recipes.** You need a rice dish, a dal and a halwa. Any three will do, as long as the halwa is a
  sweet. The steps call them **Rice**, **Dal** and **Halwa**.
- **Staff.** You need `ikms.kitchen-staff.1`, `.2`, `.3` and `.4` hired (UAT-008), and at least
  `ikms.kitchen-staff.1` and `.2` rostered on the day you plan the Lunch, across its ready-by time
  (**/staff-schedule**).
- **The date.** Plan the Lunch for **today** if today has no Lunch yet, because steps 29–30 read the
  **Today** screen. If today's Lunch is already planned, use the first date with no Lunch, and run
  steps 29–30 on that date.
- **Do not turn Sweets Kitchen's planner switch off and on again** during this test. Turning it on
  settles its ingredient requests (UAT-072), and that cannot be undone.

## Steps

### Staff belong to a kitchen (Temple Admin)

| # | Do this | You should see |
|---|---|---|
| 1 | Open **/staff** | Above the register, a card headed **Check these kitchen assignments**, with one row per person who was put in the main kitchen when this shipped: name, job title and a kitchen dropdown set to **Prasadam Kitchen** |
| 2 | Look at the register below it | A **Kitchen** column, filled in for every current staff member. No row is blank |
| 3 | In the check list, move `ikms.kitchen-staff.2` to **Sweets Kitchen** | That row **leaves the list** at once, and the register now shows Sweets Kitchen against them |
| 4 | Move `ikms.kitchen-staff.3` to **Food for Life Kitchen** | That row leaves too. The dropdown offered every active kitchen, including ones that don't plan meals here |
| 5 | Press **These are right** | The card **disappears**. Everyone left on it stays in Prasadam Kitchen |
| 6 | Reload the page | The card does **not** come back. It shows only while somebody is still unchecked |
| 7 | Above the register, use **Filter by kitchen** (it shows only when the temple has more than one active kitchen) and choose **Sweets Kitchen** | Only `ikms.kitchen-staff.2` is listed |
| 8 | Open `ikms.kitchen-staff.2`'s record | **Kitchen: Sweets Kitchen** among the employment facts |
| 9 | Press **Hire someone**. Fill in a name and job title and leave **Kitchen** on **Choose a kitchen**. Try to hire | **Refused on the form:** *Kitchen is required.* Nobody is added. (`KMS-400184` is the server's answer if a save ever reaches it) |
| 10 | Choose **Sweets Kitchen** and hire | Added. The register shows Sweets Kitchen. The new person does **not** appear on a check list, because you chose their kitchen yourself |
| 11 | Edit `ikms.kitchen-staff.3`'s record | The **Kitchen** dropdown opens on **Food for Life Kitchen**, and offers no way to leave it empty. Save without changing it |
| 12 | Open **/kitchens** and try to archive **Food for Life Kitchen** | **Refused with `KMS-400185`**, because `ikms.kitchen-staff.3` belongs to it. The message tells you to move them first. Leave both as they are |
| 13 | Edit `ikms.kitchen-staff.4`: set **App access** to **Kitchen manager** and **Kitchen** to **Food for Life Kitchen**. Save | Saved. They are now a Kitchen Manager in a kitchen that does not plan meals here |

### Plan one Lunch cooked by two kitchens (Temple Admin)

| # | Do this | You should see |
|---|---|---|
| 14 | Open **/planner** (menu: **Meal planner**) and start a **Lunch** on your date | Step 3 of the form is headed **What each kitchen cooks**, with **one section**: **Prasadam Kitchen**. It has no ×, because it is the only one |
| 15 | In Prasadam Kitchen's section, use **Add a dish to Prasadam Kitchen** to add **Rice** and **Dal**. Set **People needed** in that section to `4` | Both dishes sit in Prasadam Kitchen's section, each with its own amount. The heading reads **Prasadam Kitchen · 2 preparations**. Under People needed, a readout of the staff rostered at ready-by and the Lunch's volunteers, for example *3 staff · 0 volunteers* |
| 16 | Press **+ Add another kitchen** | A search box, **Search kitchens**, and a list. It offers **Sweets Kitchen**, each option showing how many staff it has (for example *2 staff*). It does **not** offer Prasadam Kitchen (already on the meal), Food for Life Kitchen or any other kitchen that doesn't plan meals here |
| 17 | Type `zzz` | *No kitchen matches “zzz”.* Nothing is added |
| 18 | Clear it, type `swe`, and press **Enter** | **Sweets Kitchen** is added as a second section, **below** Prasadam Kitchen. The meal does **not** save on Enter. **+ Add another kitchen** disappears if no other kitchen plans meals here |
| 19 | Both sections now have an ×. Press the × on **Sweets Kitchen** while it has no dishes | It goes **at once**, with no question. Prasadam Kitchen loses its × again. Add Sweets Kitchen back |
| 20 | In Sweets Kitchen's section, search for **Rice** | **Not offered.** A recipe already in one kitchen's section is not offered in another |
| 21 | Add **Halwa** to Sweets Kitchen. Set its **People needed** to `2` | Halwa sits under Sweets Kitchen only. The Sweets readout counts only Sweets Kitchen's rostered staff, and **no volunteers**. Volunteers are counted in Prasadam Kitchen's section |
| 22 | Press the × on Sweets Kitchen | Inside the section: **Remove Sweets Kitchen and its 1 dish?** with **Remove** and **Keep it**. Not a browser pop-up |
| 23 | Press **Keep it** | The question goes. Sweets Kitchen, Halwa and its People needed are all still there |
| 24 | Press × again, then **Remove** | Sweets Kitchen and Halwa are gone. Nothing offers to move Halwa elsewhere. Add Sweets Kitchen back, add Halwa, and set People needed to `2` again |
| 25 | Press **Save this meal** | Saved. **One** Lunch on the day, not two |

### One Lunch card, a section per kitchen

| # | Do this | You should see |
|---|---|---|
| 26 | Open the day and look at the Lunch card | One card. Its fact line, after the servings, names both kitchens: *Prasadam Kitchen and Sweets Kitchen*. Below that, a headed section per kitchen, **Prasadam Kitchen first** (you are the Temple Admin). Rice and Dal under Prasadam Kitchen, Halwa under Sweets Kitchen, and **no dish in both** |
| 27 | Read the foot of each section | Prasadam Kitchen: **People needed 4** and a pebble *N of 4 rostered*, with the rostered names under it, plus the Lunch's volunteers if any have signed up. Sweets Kitchen: **People needed 2**, *N of 2 rostered*, and only Sweets Kitchen staff by name. `ikms.kitchen-staff.2` is named under Sweets Kitchen if rostered, and **never** under Prasadam Kitchen. A pebble is **amber only when that kitchen is short**; the other stays neutral |
| 28 | Open **/planner** in the week view and find the Lunch's tile | The dishes are grouped under **Prasadam Kitchen** (Rice, Dal) and **Sweets Kitchen** (Halwa). A one-kitchen meal on another day names **no** kitchen on its tile |
| 29 | Open **/today** (on the Lunch's date) | The Lunch's quiet sub-line, beside its servings, names **both kitchens** |
| 30 | Check any other meal on Today that one kitchen cooks | It names that one kitchen |

### One job card per kitchen

| # | Do this | You should see |
|---|---|---|
| 31 | On the Lunch card, in **Prasadam Kitchen's** section, press **Download job card** | A PDF. It names **Prasadam Kitchen** at the top with the meal and date, and lists **Rice and Dal only**. No Halwa anywhere on it. The file name ends in `-prasadam-kitchen.pdf` |
| 32 | In **Sweets Kitchen's** section, press **Download job card** | A second PDF. It names **Sweets Kitchen** and lists **Halwa only**. No Rice, no Dal. The file name ends in `-sweets-kitchen.pdf`, so it does not overwrite the first |
| 33 | Compare the card numbers on the two sheets | **The same number** on both. It is one meal, and each sheet says which kitchen it belongs to |
| 34 | Tick **Include the recipes** in one section only and download again | Only that section's card carries recipes, and only for its own dishes |

### The Sweets Kitchen cook's view (Kitchen Staff)

| # | Do this | You should see |
|---|---|---|
| 35 | Sign out. Sign in as `ikms.kitchen-staff.2` (Kitchen Staff, Sweets Kitchen) | **Meal planner** is in the menu |
| 36 | Open the same Lunch | The same one card, but **Sweets Kitchen's section is first**, then Prasadam Kitchen. The dishes, People needed and names are the same as step 27 |
| 37 | Open the Lunch to edit it | The sections open **Sweets Kitchen first**, each with its own dishes and People needed as saved. Change nothing and leave |
| 38 | Start a **new** meal on any free date | Its one starting section is **Sweets Kitchen**, your own kitchen. Leave without saving |
| 39 | Sign out. Sign in as `ikms.kitchen-staff.1` (Kitchen Staff, Prasadam Kitchen) and open the Lunch | **Prasadam Kitchen first**. A new meal starts on Prasadam Kitchen |

### Who cannot open the planner

| # | Do this | You should see |
|---|---|---|
| 40 | Sign out. Sign in as `ikms.kitchen-staff.3` (Kitchen Staff, Food for Life Kitchen, which doesn't plan meals here) | **No Meal planner** in the menu, and nothing else under it |
| 41 | Type **/planner** into the address bar | **Not your page**: *You don’t have access to this part of the app. Ask your temple administrator.* No meal, no day grid, not even briefly |
| 42 | Try **/planner/reuse**, and the address of the Lunch's day | **Not your page** on each. `KMS-400183` is what the server answers if a request reaches it |
| 43 | Open **/today** | It opens as usual. Only the planner is shut |
| 44 | Sign out. Sign in as `ikms.kitchen-staff.4` (Kitchen Manager, Food for Life Kitchen) | **No Meal planner** in the menu, and **/planner** gives **Not your page**. The rule is about the kitchen, not the role |
| 45 | As the same Kitchen Manager, open **/staff** | Refused, as before this change. The check list is the Temple Admin's alone |
| 46 | **Only if the team has given you one:** sign in as a Kitchen Staff login with **no staff record at all** | No Meal planner in the menu, and **/planner** gives **Not your page**. Having no kitchen is not the same as belonging to every kitchen. *No screen we know of makes such a login. If you have none, mark this step N/A. It is covered by automated tests* |
| 47 | Sign out. Sign in as `ikms.volunteer.1` | No Meal planner, and **/planner** gives **Not your page**, exactly as before this change |
| 48 | Sign out. Sign in as the Temple Admin and move `ikms.kitchen-staff.3` to **Prasadam Kitchen**. Sign out, sign back in as `ikms.kitchen-staff.3` | **Meal planner** is back in the menu, and **/planner** opens. The change takes effect on their next sign-in or reload |

## It passes if

- [ ] Every current staff member has a kitchen, shown in the register's **Kitchen** column and on their record.
- [ ] **Check these kitchen assignments** lists the people put in the main kitchen at release. Moving one takes them off the list, **These are right** clears the rest, and the card stays gone.
- [ ] Adding or editing a staff member cannot be saved without a kitchen.
- [ ] A kitchen with somebody in it cannot be archived (`KMS-400185`).
- [ ] The composer starts with one section, the planner's own kitchen, or the main kitchen for someone with none.
- [ ] **+ Add another kitchen** offers only kitchens that plan meals here and aren't on the meal. It can be searched, and says so when nothing matches.
- [ ] Each section has its own dishes and its own **People needed**. A recipe is never offered in a second section.
- [ ] × on a section with dishes asks **Remove … and its N dishes?** inside the section; **Keep it** keeps everything. An empty section goes at once. The only section has no ×. Nothing offers to move a dish.
- [ ] Saved, it is **one** Lunch card with a section per kitchen. The planner's own kitchen is first; the Temple Admin sees the main kitchen first.
- [ ] Each section shows its own People needed, its own rostered staff by name, and a pebble that is amber only when that kitchen is short.
- [ ] Each section's **Download job card** gives a card naming that kitchen and listing **only its dishes**. Both cards carry the same card number.
- [ ] The week tile groups a two-kitchen meal's dishes by kitchen. Today names the kitchens.
- [ ] Kitchen Staff or a Kitchen Manager in a kitchen that doesn't plan meals here don't see **Meal planner** and get **Not your page** at any planner address.
- [ ] A volunteer still has no planner.

## A note on how a refusal shows itself

Most of these rules are guarded on the screen before the server sees them, so a tester usually sees
the guard, not the code:

- **Staff without a kitchen:** *Kitchen is required* on the form. `KMS-400184` is behind it.
- **The planner shut to you:** **Not your page**. `KMS-400183` is behind it.
- **A meal with no kitchen** (`KMS-400180`), **a kitchen that doesn't plan meals here** (`KMS-400181`),
  **a dish under a kitchen that isn't on the meal** (`KMS-400182`), **a job card asked for without a
  kitchen on a meal two kitchens cook** (`KMS-400186`) and **a job card for a kitchen not on the meal**
  (`KMS-400187`) cannot be reached from the screens at all. They are covered by automated tests.

Record what you saw. Do not log a defect only because no `KMS-` code appeared.

## Watch out for

- **A dish on the wrong kitchen's card.** A Sweets Kitchen cook handed a card with the main kitchen's
  rice on it is worse than no card. Any dish on the wrong card, or on both, is a **Blocker**. Write
  down the dish and the card.
- **Two Lunches on one day.** Saving a two-kitchen Lunch must never make a second Lunch card. Record
  it as a **Blocker**.
- **Volunteers in the wrong section.** The build counts the Lunch's volunteers once, in the main
  kitchen's section. **Rajeev has not confirmed this yet.** If it reads wrong to you, for example if
  the volunteers were really coming to help with the sweets, write it down as a question, not a
  defect.
- **A kitchen's planner switch turned off.** By the rule as built, turning off "plans its meals here"
  on a kitchen **shuts its staff out of the planner** on their next page load, even for meals they
  planned. **Rajeev has not confirmed this yet.** Do not test it on Sweets Kitchen (see *Before you
  start*). If you see it happen elsewhere, note it as a question.
- **A section order that changes by itself.** The order is fixed by who is looking. The same person
  should see the same order every time, in the card, the edit screen and after saving.
- **Names in the wrong section.** A cook's name should appear under their own kitchen only. A name
  under two sections, or under a kitchen they don't belong to, is **Major**.
- **The planner flickering open** for somebody it is shut to, even for a moment, before **Not your
  page** appears. Record it as **Major**: a meal on screen, even briefly, is a meal shown.
- **Somebody new on the check list.** Someone you hired yourself, choosing their kitchen, should never
  appear there. If they do, record who.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT087-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
