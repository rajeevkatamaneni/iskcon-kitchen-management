# UAT-072: A kitchen starts planning its own meals

| | |
|---|---|
| **Feature area** | Kitchens — the meal-planner flag, and what it settles |
| **Technical stories** | E10-S4 (a kitchen starts planning its own meals, and the cascade) |
| **Roles exercised** | Temple admin, kitchen staff |
| **Depends on** | UAT-067, UAT-068, UAT-069, UAT-070 (you need requests in every state, including one that has been issued) |
| **Environment needs** | None |

## What this feature is for

There is one store room and there are two doors out of it. A kitchen whose cooking is recorded in the
meal planner has its ingredients taken off the books when the meal is marked cooked. A kitchen that
asks the store for ingredients has them taken off the books when the storekeeper hands them over. If a
kitchen ever used **both** doors for the same food, the temple's stock would leave the books twice and
the figures would quietly stop meaning anything.

The temple has been told about that risk and has said it will be careful. That is not a guarantee, so
the system enforces it instead: **each kitchen uses one door or the other, and says which.**

**This is the most consequential test in the kitchens pack.** Turning this switch on is the only act in
the whole product that **permanently deletes somebody else's work** and **reverses a decision somebody
already made** — and it does both without asking twice. Run every step, and check every request
individually rather than glancing at the list.

## How it is supposed to work

- Every kitchen answers one question: **"Does this kitchen plan its meals here, using recipes and the
  meal planner?"** It can change its answer later.
- **Yes** → its cooking is recorded as meals, its stock leaves as consumption, and it **may not raise
  ingredient requests at all**.
- **No** → the ingredient request is its only door.
- Turning it **on** settles every request already in flight for that kitchen, and the dividing line is
  the request's **needed-on date**, against today in the temple's own day (India):

| Needed on | Status | What must happen |
|---|---|---|
| **before today** | **any** | **Untouched.** It is history, and history is not rewritten |
| today or later | Draft | **Deleted permanently** |
| today or later | Awaiting review | **Denied** |
| today or later | Approved | **Denied** |
| today or later | Denied | Nothing. It has already been answered |
| today or later | Issued | **Untouched.** The goods have left the shelf; the movements cannot be unwritten |

- **It warns first, with counts**, and waits to be confirmed. Ticking a box that silently deletes
  somebody's drafts is not acceptable.
- The **administrator who flipped the switch is named** as the person who denied them, and the note says
  why — the kitchen and the date. An automatic denial with nobody's name on it is one nobody can ask
  about.
- **Every row it touches is written to the audit log, deletions included.**
- Turning it **off** again restores the ability to request. **Nothing already recorded changes.**

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- The kitchen under test is **Sweets Kitchen**, which does not plan its own meals today.
- **Put 10 Kg of Sugar on the shelf** (a gift, UAT-028) so that request **S8** can actually be issued.
- **Build these eight requests, all for the Sweets Kitchen.** Dates are relative to the day you run the
  test. Every one of them needs at least one dish line to be submittable — `Halwa 20 Kg` will do for all
  of them. Raise the ones marked *(Gopal)* as `ikms.kitchen-staff.1@trading4good.org` so the cascade is
  deleting somebody else's work, not only your own.

| Tag | Needed on | Ingredients | Raised by | Leave it in this state |
|---|---|---|---|---|
| **S1** | **tomorrow** | Sugar 6 Kg | *(Gopal)* | **Draft** |
| **S2** | **today** | Ghee 2 L | *(Gopal)* | **Draft** |
| **S3** | **in 3 days** | Sugar 4 Kg | *(Gopal)* | **Awaiting review** |
| **S4** | **in 5 days** | Sugar 3 Kg | *(Gopal)* | **Approved** (approve it as the admin, note `Approved for Sunday`) |
| **S5** | **in 6 days** | Ghee 1 L | *(Gopal)* | **Denied** (deny it as the admin, note `Congregation is bringing sweets`) |
| **S6** | **yesterday** | Sugar 2 Kg | *(Gopal)* | **Awaiting review** |
| **S7** | **two days ago** | Ghee 1 L | *(Gopal)* | **Draft** |
| **S8** | **in 4 days** | Sugar 2 Kg | *(Gopal)* | **Issued** (approve it, then record the issue of 2 Kg — UAT-070) |

- **Before you touch the switch, write all of this down.** You will be checking it afterwards line by
  line, and memory is not good enough:
  - the **reference** of every one of the eight;
  - S5's denial note, who denied it and when;
  - S8's issued date and **the Sugar balance on /inventory** after S8 was issued (it should be **8 Kg**).
- If the request form will not accept a date in the past, **S6 and S7 cannot be built**. Say so in your
  report and run the rest — but flag it, because the past/future line is the whole basis of this rule.

## Steps

### The warning comes first, and it counts correctly

| # | Do this | You should see |
|---|---|---|
| 1 | Open **/ingredient-requests**, filter to **All**, and confirm all eight requests are present in the states above | Eight rows, the states as you left them |
| 2 | Open **/kitchens** → **Edit** on **Sweets Kitchen** | The form, with **"Does this kitchen plan its meals here?"** switched **off** |
| 3 | Tick it, and save | **A confirmation appears before anything happens.** It says what is about to be done, with counts: **2 drafts will be deleted and 2 requests will be denied** |
| 4 | Read the counts carefully against your list | **2 and 2.** S1 and S2 are the drafts; S3 and S4 are the denials. S5 is already denied, S8 has been issued, and S6 and S7 are in the past — none of those four is counted |
| 5 | **Cancel** the confirmation | Nothing happens |
| 6 | Go back to **/ingredient-requests** and check all eight | **All eight are exactly as they were.** S1 and S2 are still drafts. Nothing was deleted by the warning itself |
| 7 | Return to Sweets Kitchen, tick it again, save, and this time **confirm** | The save completes and you land back on the kitchens list. Sweets Kitchen now shows that it plans its own meals |

### Now check every one of the eight, individually

| # | Do this | You should see |
|---|---|---|
| 8 | Look for **S1** (draft, tomorrow) | **Gone.** Not in any filter, not under Draft, not anywhere. Paste its address: *We couldn't find that request* (`KMS-4977`) |
| 9 | Look for **S2** (draft, **today**) | **Gone** as well. "Today or later" includes today |
| 10 | Open **S3** (was awaiting review, in 3 days) | **Denied** |
| 11 | Read S3's decision | Denied **by you, by name** — the administrator who flipped the switch — with a note saying it was denied automatically when **Sweets Kitchen** started using the meal planner, **and on what date** |
| 12 | Open **S4** (was approved, in 5 days) | **Denied**, with the same kind of note and your name. An approval was reversed |
| 13 | Read S4's history | It reads in order: raised, submitted, approved by you with `Approved for Sunday`, then denied. The earlier approval is still visible — it was not erased |
| 14 | Open **S5** (already denied, in 6 days) | **Still denied**, and **completely unchanged**: the same note `Congregation is bringing sweets`, the same person, the same time. It was not re-denied and its note was not overwritten |
| 15 | Open **S6** (awaiting review, **yesterday**) | **Still awaiting review.** Untouched. It is history, and history is not rewritten |
| 16 | Open **S7** (draft, **two days ago**) | **Still a draft, and still there.** A past-dated draft is not deleted |
| 17 | Open **S8** (issued, in 4 days) | **Still Issued.** Not denied, not reversed |
| 18 | Go to **/inventory** → **Sugar** | **Still 8 Kg.** Its movement history is unchanged — no compensating movement was written to "give back" what S8 issued. Goods that have left the shelf stay gone |

### The door is now shut

| # | Do this | You should see |
|---|---|---|
| 19 | As Gopal, open **/ingredient-requests/new** and open the **Kitchen** dropdown | **Sweets Kitchen is not in it.** Deity, Prasadam and Food for Life still are |
| 20 | Force it — take a request that names Sweets Kitchen and try to submit a copy for it, or paste an address that selects it | Refused: *This kitchen plans its meals here, so its ingredients are drawn when a meal is recorded* (`KMS-4976`), telling you to pick a kitchen that only asks for ingredients, or to turn the meal planner off for this one |
| 21 | As Gopal, open **S7** (the surviving past-dated draft) and try to **submit** it | Record what happens. It names a kitchen that may no longer be asked for ingredients, and the answer should be `KMS-4976` rather than a submission that quietly goes through |

### The audit log has the whole of it

| # | Do this | You should see |
|---|---|---|
| 22 | Open **/audit** | Rows for the kitchen's own change (it joined the meal planner) **and one row per affected request** |
| 23 | Count them | **Two deletion rows** (S1, S2) and **two denial rows** (S3, S4), each naming **you** as the person, with the time. A permanent delete that leaves no trace is exactly what this log exists to prevent |
| 24 | Check the audit for S5, S6, S7 and S8 | **Nothing new** on any of them. The cascade did not touch them, and it did not log as if it had |

### Turning it back off

| # | Do this | You should see |
|---|---|---|
| 25 | Edit **Sweets Kitchen** and switch the meal-planner question back **off**, and save | Saved. **No warning this time** — turning it off destroys nothing |
| 26 | As Gopal, open a new request form | **Sweets Kitchen is back in the dropdown**, and a request for it can be raised and submitted |
| 27 | Check S3 and S4 again | **Still denied.** Nothing recorded changes when the flag comes back off. Denials are not undone |
| 28 | Check whether S1 and S2 came back | **They did not.** Deleted is deleted |
| 29 | Open **/audit** | A row for the kitchen leaving the meal planner |

### A kitchen with nothing in flight

| # | Do this | You should see |
|---|---|---|
| 30 | Edit **Food for Life Kitchen**, which has no requests at all, and tick the meal-planner question | Either no confirmation, or one that plainly says **nothing will be affected** — never a warning quoting counts of zero as though something were about to happen |
| 31 | Save, then look at **/ingredient-requests** | Nothing changed anywhere |
| 32 | Turn it off again | Back as it was |

## It passes if

- [ ] Saving with the flag newly on **warns first**, with the right counts, and waits to be confirmed.
- [ ] Cancelling the warning changes nothing at all.
- [ ] A **draft** dated today or later is deleted permanently — both of them, including the one dated **today**.
- [ ] An **awaiting review** request dated in the future is denied.
- [ ] An **approved** request dated in the future is denied, and its earlier approval is still visible in its history.
- [ ] An **already denied** request is untouched — same note, same person, same time.
- [ ] An **issued** request is untouched, and no stock movement is reversed or compensated.
- [ ] A request dated **before today** is untouched, whatever state it is in — including a past-dated draft.
- [ ] Every denial names the administrator who flipped the switch, with a note giving the kitchen and the date.
- [ ] There is an audit row for every denial **and every deletion**, and none for the requests that were not touched.
- [ ] The kitchen then cannot be chosen on a new request, and forcing it gives `KMS-4976`.
- [ ] Turning the flag off restores the ability to request and changes nothing already recorded.

## Watch out for

- **Check each of the eight on its own screen.** A list view can be right while a record is wrong, and the four requests that must be *untouched* are exactly the ones nobody thinks to look at.
- **Counts in the warning that do not match what happens.** If it says 2 and 3 and then deletes 2 and denies 2, the person confirming it was told something untrue about work that cannot be recovered. Blocker.
- **A denial with nobody's name on it** — "System", "Automatic", or blank. The design is explicit: the person who flipped the switch caused it, and their name goes on it.
- **A note that does not say why.** A denial reading only "Denied" leaves the kitchen with no idea what happened to their request.
- **S5 being re-denied**, overwriting the original note or decider. Its original refusal is the record somebody will want to point at.
- **Any movement written against S8's stock.** If Sugar moves at all in step 18, that is a Blocker — the ledger is append-only and this switch has no business writing to it.
- The **today** boundary. S2 is dated today deliberately. If it survives, the line is being drawn in the wrong place, and the temple's own day (India) is what should decide it — if you are testing from outside India, run steps 1–9 again late in your evening and record what happens.
- Whether the confirmation appears when the flag is **already on** and you save the form for some other reason — changing the phone number, say. It should not: nothing is in flight to settle.
- Two kitchens both planning their own meals. The meal planner is temple-wide today, so the system cannot yet say which of them cooked what. That is a known limitation rather than a defect — but record what the product does if you try it.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT072-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
