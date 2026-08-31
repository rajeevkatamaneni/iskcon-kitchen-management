# UAT-071: The work order, printed and translated

| | |
|---|---|
| **Feature area** | Kitchens — the work order document |
| **Technical stories** | E10-S11 (the work order: template, PDF, print, 23 languages) |
| **Roles exercised** | Temple admin, kitchen staff |
| **Depends on** | UAT-069 (a request must be approved), UAT-070 (so you know what issuing actually draws) |
| **Environment needs** | **Background worker on** (the PDF is built by a job) **and a real document renderer**. For the language steps, **a real translation provider** as well. With the stub renderer you get a placeholder file, not a work order; with the stub translator, text comes back tagged rather than translated — see §4 of the README |

## What this feature is for

The storekeeper does not walk the store room holding a phone. They walk it holding one sheet of A4
that says what to pick, **which lot to pick it from**, and who asked for it — and they get it signed
by the person who takes delivery. That signed sheet is what goes in the folder, and it is what an
auditor reads six months later.

## How it is supposed to work

- A work order exists **only for an approved request**. There is nothing to pick against a question
  nobody has answered.
- It is **computed when you ask for it**, not frozen at approval. An afternoon's cooking can empty the
  lot the sheet names, so the sheet is built from what is on the shelf **now**.
- It carries: the temple and the kitchen · the reference and the date wanted · the reason · **the dishes
  and how much of each** · every ingredient line with its quantity and **the batches to pick from, in
  expiry order** · the requester and the approver by name and date · and **two ruled signature boxes**,
  one for the person issuing and one for the person taking delivery.
- Quantities on it are **cook's figures** — the way a person says them. `120 gm`, never `0.12 Kg`.
- One control gives both paths: a **print view** that works immediately, and a **PDF** built in the
  background.
- It can be produced in **any of the 23 languages**, with the labels and the ingredient names
  translated and the numbers, dates and reference left exactly as they are.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Confirm with the environment owner** that the background worker and the real document renderer are
  both on, and whether the translation provider is real. If the worker or renderer is off, run steps
  1–4 and 14 only, and mark the rest *blocked by environment*.
- **Give Rice a second batch**, so the sheet has something to put in expiry order. Record a gift
  (UAT-028) of **15 Kg of Rice expiring 31 October 2026**. After UAT-070 that leaves Rice holding
  **15 Kg expiring 31 Oct 2026** and **30 Kg expiring 31 Mar 2027**.
- **Create request W** as Gopal Das and approve it as the admin — leave it **approved, not issued**:

| | |
|---|---|
| Kitchen | Prasadam Kitchen |
| Needed on | 8 September 2026 |
| Reason | Radhashtami feast |
| Ingredients | Rice `20` **Kg** · Ghee `500` **ml** · Cardamom `0.12` **Kg** |
| Dishes | Khichdi `250` **servings** · Kesar Bhat `40` **L** |
| Approved by you, with the note | `Older rice first` |

- **Have a Hindi reader available** for step 18, and ideally a reader of a southern script for step 19.

## Steps

### There is no sheet without an answer

| # | Do this | You should see |
|---|---|---|
| 1 | Open a **draft** request (C from UAT-068) | No **Download work order** anywhere on it |
| 2 | Open a request that is **awaiting review** | Still none |
| 3 | Open a **denied** request (B from UAT-069) | Still none. A refusal has nothing to pick |
| 4 | Open the approved request **W** | **Download work order** is offered |

### What is on the sheet

| # | Do this | You should see |
|---|---|---|
| 5 | Press **Download work order** and wait | It shows it is working, then a PDF downloads |
| 6 | Open the PDF and check the head of the sheet | The temple's name · **Prasadam Kitchen** · the reference (`IR-2026-…`) · **needed 8 September 2026** · the reason `Radhashtami feast` |
| 7 | Find the **dish list** | **Khichdi — 250 servings** and **Kesar Bhat — 40 L**, on the same page as the ingredients. If the dishes are not on the sheet, that is a Major defect: an auditor needs both halves of the comparison — what was drawn, and what it was drawn to cook |
| 8 | Read the ingredient lines | **Rice 20 Kg**, **Ghee 500 ml**, **Cardamom 120 gm**. Note the last two: the ghee must **not** read `0.5 L`, and the cardamom — which you entered as `0.12` **Kg** — must **not** read `0.12 Kg`. These are figures somebody weighs against |
| 9 | Read the batches under the **Rice** line | **Two**, in this order: the batch expiring **31 October 2026** for 15 Kg, then the batch expiring **31 March 2027** for the remaining 5 Kg. Oldest expiry first, and it adds to 20 |
| 10 | Find the names and the boxes at the foot | *Requested by Gopal Das* with the date · *Approved by [you]* with the date and the note `Older rice first` · and **two ruled signature boxes**, one for issuing and one for taking delivery |
| 11 | Check the sheet against **/inventory** | The batches named are the batches actually on the shelf, in the same order, with the same expiry dates |

### It is computed now, not remembered

| # | Do this | You should see |
|---|---|---|
| 12 | Go to **/inventory** → Rice and record a **spoilage adjustment** of `-15` against the batch expiring 31 October (UAT-024) | The October batch is emptied. Rice is 30 Kg, all in the March batch |
| 13 | Download the work order for request **W** again | The new sheet names **only the March batch**, for the full 20 Kg. The old lot is gone from it — the sheet was rebuilt, not remembered |
| 14 | Use **Print** (or your browser's print preview) on the same request | A clean A4 layout: no menu, no buttons, nothing cut off. **This path should work even with the background worker off** — say in your report which of the two you were able to use |

### In the kitchen's own language

| # | Do this | You should see |
|---|---|---|
| 15 | Find the language control on the work order | A list of languages — English and the 22 scheduled Indian languages — offered immediately, not after a wait |
| 16 | Choose **Hindi** and produce the sheet | Devanagari script, properly rendered: no empty boxes (□□□), no question marks, no overlapping letters |
| 17 | Check what did **not** change | The quantities (20, 500, 120), the dates, and the reference number are **identical** to the English sheet |
| 18 | **Ask a Hindi reader:** do the labels and the ingredient names read as a store sheet somebody could work from? | Record their verdict verbatim, including any word that is wrong |
| 19 | Repeat in **Telugu**, **Tamil** or **Kannada** | The same, in that script |
| 20 | If you set a glossary term for an ingredient in UAT-021, check it is used here | The temple's own wording, not the machine's guess |
| 21 | Produce the sheet for the **self-approved** request from UAT-069 step 19 | The paper says so — the same person asked and approved. The fact sits on the sheet, not only in a log |
| 22 | Download the same work order twice in a row | Both attempts work; the second does not fail because the first already ran |

## It passes if

- [ ] A work order is offered on an approved request and on nothing else.
- [ ] The sheet carries the temple, the kitchen, the reference, the date wanted and the reason.
- [ ] **The dish list is on the sheet**, with quantities.
- [ ] Every ingredient line names the batches to pick from, oldest expiry first, and they match what issuing would actually draw.
- [ ] Quantities are cook's figures — `500 ml` and `120 gm`, never `0.5 L` or `0.12 Kg`.
- [ ] The requester and the approver are named with dates, and there are two ruled signature boxes.
- [ ] Changing the stock and re-downloading produces a different batch list.
- [ ] Both the print view and the PDF produce the same sheet, and the print view works without the worker.
- [ ] It renders in Devanagari and in a southern script without missing characters, with numbers and the reference untouched.

## Watch out for

- **The most likely failure here is environmental**, not a product fault: with the worker off, the button spins and fails; with the stub renderer, a file downloads containing a placeholder; with the stub translator, "translated" text comes back tagged. In each case write *environment* and name which one — that is root cause R5, not a bug.
- A sheet that names a batch that **is not there any more**. Step 13 is the test for it. If the second download names the October batch, the sheet is being remembered rather than computed, and a storekeeper will be sent to an empty shelf. Major.
- **Missing dishes.** This is the half of the sheet most likely to be dropped, because it is the half a picker does not strictly need. It is also the half the audit turns on.
- Batches listed in received-date order, or in no order at all, rather than by expiry.
- Empty boxes or question marks in a non-Latin script — a missing font. Major, and worth a screenshot.
- Numbers "translated" into another numeral system, or a reference number that changes between languages. Blocker.
- A signature box that is a tick-box or a field to type in. Signing here is paper, deliberately; the system never learns what was written in it.
- A work order that still downloads on an **issued** request. That is not necessarily wrong — a filed copy is useful — but record what it shows and whether it still names live batches.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT071-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
