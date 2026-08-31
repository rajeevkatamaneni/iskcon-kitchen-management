# UAT-068: Ask the store for ingredients

| | |
|---|---|
| **Feature area** | Kitchens — the ingredient request |
| **Technical stories** | E10-S5 (the request and the draft lifecycle), E10-S8 (the requests list), E10-S9 (the request form) |
| **Roles exercised** | Kitchen staff, temple admin, volunteer |
| **Depends on** | UAT-067 (the kitchens must exist), UAT-013 (the ingredients must exist) |
| **Environment needs** | None |

## What this feature is for

Most of a temple's kitchens will never open this application. They do not want software; they want
rice, dal and ghee, on the morning they are cooking. This is the door they knock on: a written request
saying which kitchen, what it needs, how much, when — **and what it is cooking**.

That last part is the point of the screen. Writing down "200 servings of khichdi" before you ask for
40 kg of rice is what stops "let me get that too, just in case"; and months later, an auditor reading
the filed sheets can see at a glance whether a kitchen has been drawing more than it cooks.

## How it is supposed to work

- **Any** staff member may raise a request — a cook, a store manager, the administrator.
- A request names one **kitchen**, a **date it is needed on**, and a **reason**.
- **Ingredient lines**: an ingredient from the temple's catalogue, a quantity, and a unit. The unit
  choices are filtered to that ingredient's own family — you can ask for rice in kilos or grams, never
  in litres.
- **Dish lines**: a name, a quantity and a unit. Free text and numbers only — no recipe is attached,
  because most of these kitchens cook things this temple has never written down.
- A request gets a **reference** like `IR-2026-0041`, so somebody can say it down a phone.
- A **draft** can be as rough as its author likes. **Submitting** is the moment the discipline applies:
  at least one ingredient line, and at least one dish.
- A draft is **private to change and public to read**. Anyone on staff can read anyone's draft — the
  alternative is two people separately drafting a request for the same feast — but only the person who
  wrote it can edit it. A Temple Admin can delete anybody's.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (Gopal Das, kitchen staff)
- **Start at:** **/ingredient-requests** (menu: **Kitchen → Ingredient requests**)
- **Check your ingredient list first.** You need **Rice**, **Toor Dal**, **Ghee**, **Sugar** and
  **Cardamom**. If Cardamom is not there, add it on **/ingredients** — category `Spices`, unit `gm`.
- You will also need a second staff session at step 16: `ikms.kitchen-staff.2@trading4good.org`
  (Yamuna Devi Dasi). Use a private window so Google does not silently reuse the first account.
- **You will create these requests.** UAT-069 to UAT-072 use them, so create all of them:

| # | Kitchen | Needed on | Reason | Ingredients | Dishes | Leave it as |
|---|---|---|---|---|---|---|
| **A** | Prasadam Kitchen | 2 September 2026 | Sunday Feast | Rice 40 Kg · Toor Dal 12 Kg · Ghee 6 L · Cardamom 250 gm | Khichdi 400 servings · Aam Ras 200 servings | **Submitted** |
| **B** | Sweets Kitchen | 3 September 2026 | Sweets for the Sunday Feast | Sugar 15 Kg · Ghee 4 L | Halwa 30 Kg · Sweet Rice 50 L | **Submitted** |
| **C** | Deity Kitchen | 4 September 2026 | Daily bhoga offerings | Rice 5 Kg · Ghee 500 ml | Bhoga rice 40 servings | **Draft** |

## Steps

### The list, before there is anything on it

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Ingredient requests** | An empty state that reads sensibly — not an empty table — and **New request** at the **top right** |
| 2 | Look at the filters | A single row of choices: **All · Draft · Awaiting review · Approved · Denied · Issued** |
| 3 | Click through each filter | Each shows its own empty wording — *no drafts*, *nothing awaiting review* — rather than the same line five times |
| 4 | Look at the address bar as you click | The chosen filter is **in the URL**. Copy that address, refresh, and you land back on the same filter |

### Raising a request

| # | Do this | You should see |
|---|---|---|
| 5 | Press **New request** | A screen of its own: task as the heading, menu still on the left, **Cancel** and the save actions together at the top right |
| 6 | Open the **Kitchen** dropdown | The kitchens from UAT-067 — Deity, Prasadam, Sweets, Food for Life. No kitchen from another temple |
| 7 | Choose **Prasadam Kitchen**, needed on **2 September 2026**, reason `Sunday Feast` | Accepted |
| 8 | Add an ingredient line: **Rice**, quantity `40`. Open the **unit** dropdown | It offers **Kg** and **gm** only. Not L, not ml, not pieces, not servings — rice is a weight |
| 9 | Add **Ghee**, and open its unit dropdown | **L** and **ml** only |
| 10 | Add **Cardamom** `250`, unit **gm** | Accepted — 250 gm against a gram ingredient |
| 11 | Add a **spare** line for **Rice** and try to save it as `3` **L** | Refused with a plain message before it goes anywhere — a litre of rice is not a thing you can ask for |
| 12 | Change that spare line to **Rice** `500` **gm** — an ingredient whose own unit is Kg — and save | **Accepted.** Grams and kilos are the same family; a smaller unit is a legitimate way to ask. **Now remove the spare line**, so request A is left with exactly the four lines in the table above |
| 13 | Add the dish lines: **Khichdi** `400` **servings**, **Aam Ras** `200` **servings**. Open a dish unit dropdown | All six: Kg, gm, L, ml, pieces, **servings**. A dish is genuinely made in any of them |
| 14 | Press **Save as draft** | You land back on the **list**, with a green line confirming it. The row shows a **reference** like `IR-2026-0001`, the kitchen, the date needed, your name, and **Draft** |
| 15 | Open the draft again and press **Submit for review** | Status becomes **Awaiting review**, and it moves under that filter |

### Whose draft is whose

| # | Do this | You should see |
|---|---|---|
| 16 | Sign in (private window) as `ikms.kitchen-staff.2@trading4good.org` and open **Ingredient requests** | Gopal Das's requests are **all there and readable**, including drafts. A draft here is not private |
| 17 | As Yamuna, open Gopal's **draft C** and look for Edit or Delete | Neither is offered |
| 18 | Force it: paste the edit address of Gopal's draft into the bar | Refused: *This request belongs to somebody else* (`KMS-4978`) — *you can read it, but only the person who wrote it can change it* |
| 19 | As Yamuna, raise **request B** from the table above and submit it | Saved under her name, not Gopal's |
| 20 | Sign in as `ikms.temple-admin.1@trading4good.org`, open Gopal's draft **C**, and delete it | **Allowed.** A Temple Admin can clear anybody's draft |
| 21 | Sign back in as Gopal and re-create draft **C** | It is needed by UAT-072 |

### What a submission has to contain

| # | Do this | You should see |
|---|---|---|
| 22 | Start a new request, choose a kitchen and a date, add **no** ingredient lines and no dishes, and press **Submit for review** | Refused: *This request doesn't ask for anything yet* (`KMS-4983`) — *add at least one ingredient before sending it for review* |
| 23 | Add one ingredient line, still no dishes, and submit | Refused: *Say what the kitchen is cooking before sending this for review* (`KMS-4984`) — *list each dish and how much of it, so whoever reviews this can judge the amounts.* **The form should stop you here, before the request is sent anywhere** |
| 24 | Now press **Save as draft** on that same incomplete request | **Accepted.** A draft is allowed to be rough; only submitting demands the dishes |
| 25 | Come back to that draft later, add a dish, and submit | Accepted. Delete it afterwards — the other tests do not need it |
| 26 | Try to save an ingredient line with quantity `0`, and again with `-5` | Both refused |

### Finding things again

| # | Do this | You should see |
|---|---|---|
| 27 | Look at the list with **All** | Newest first. Each row: reference, kitchen, needed on, who asked, status |
| 28 | Filter to **Awaiting review** | Requests A and B, and not draft C |
| 29 | Filter to **Draft** | Draft C only |
| 30 | Send the **Awaiting review** address to yourself and open it in a fresh window | It opens on that filter. The view is linkable |
| 31 | Sign in as `ikms.volunteer.1@trading4good.org` and open **/ingredient-requests** | Refused (`KMS-4301`), and the menu never offered it. A volunteer has no business in the store's paperwork |

## It passes if

- [ ] Any staff member can raise a request naming a kitchen, a date, a reason, ingredient lines and dish lines.
- [ ] Each request gets a human-readable reference that is unique in the temple.
- [ ] The unit dropdown offers only units that can be true for the chosen ingredient; a litre of rice is refused and 500 gm of rice is accepted.
- [ ] Dish lines take a name, a quantity and any of the six units, and are text and numbers only.
- [ ] Submitting with no ingredients is refused (`KMS-4983`) and with no dishes is refused (`KMS-4984`); a draft may be saved incomplete.
- [ ] Anybody on staff can read anybody's draft; only its author can edit it (`KMS-4978`); a Temple Admin can delete it.
- [ ] The six filters work, read sensibly when empty, and are carried in the address bar.
- [ ] A volunteer cannot reach the page.

## Watch out for

- **The dish list being optional in practice** — for instance, a draft that submits with an empty dish table because the check only fires when the table has been touched. That is the whole reason this screen exists, so treat it as a Major finding and say exactly how you got past it.
- Two requests with the **same reference**. Blocker. Note both.
- A unit dropdown that offers **servings** on an *ingredient* line. An ingredient can never be measured in servings.
- A unit dropdown that offers every unit regardless of the ingredient — check Rice, Ghee and Cardamom separately, not just one of them.
- A request form that lets you pick a kitchen that **plans its own meals** (none do yet — UAT-072 makes one, and re-checks this).
- Quantities that come back different from what you typed: `40` saved and re-read as `40.000`, or `0.5` saved and shown as `0.5 Kg` where it should read `500 gm`. The second is a display rule and belongs to UAT-074, but record it here too if you see it.
- Whether you can raise a request for a date **in the past**. UAT-072 needs to be able to, so if the form refuses it, write that down — it makes that test unrunnable as written.
- The green confirmation line replaying when you refresh the list.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT068-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
