# UAT-070: Issue the ingredients, and watch the stock fall

| | |
|---|---|
| **Feature area** | Kitchens — issuing, and the stock ledger |
| **Technical stories** | E10-S7 (recording what was issued), E10-S10 (the request record) |
| **Roles exercised** | Temple admin, kitchen staff |
| **Depends on** | UAT-069 (request A must be approved), and stock on the shelf — UAT-028 (a gift) or UAT-044 (a delivery) |
| **Environment needs** | None |

## What this feature is for

Approval is a decision. Issuing is a physical event: sacks leave the shelf and go to a kitchen. The
store's books have to follow the sacks, not the paperwork — the same distinction the system already
draws between *sending* a purchase order and *receiving* one.

This is the second door out of the store room. Until now stock could only leave by being cooked into a
planned meal. Now it can also be handed to a kitchen that does its own planning on paper.

## How it is supposed to work

- The storekeeper records **what was actually handed over**, line by line, pre-filled with what was
  approved. Handed over less? Change the number. Handed over none of it? Put zero, and **no movement is
  written for that line at all**.
- Each line is drawn from batches **soonest-expiry-first**, so the oldest goods leave the shelf first —
  the same rule cooking a meal already follows.
- **All or nothing.** If any single line is short, the whole issue is refused, the shortfalls are named,
  and **not one gram** is deducted. The books can never go negative.
- The kitchen is **not written onto the stock movement**. The movement points at the request, and the
  request names the kitchen — so the two can never disagree.
- Issuing closes the request. It becomes **Issued**, and that is terminal.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin — the storekeeper's job, see
  the note in UAT-069 about Kitchen Manager)
- **Start at:** **/inventory**
- **Set the store up exactly like this.** Use gifts (UAT-028) or deliveries (UAT-044) to get there, and
  write down what you actually end up with before you start:

| Item | Batches | Total |
|---|---|---|
| **Rice** | 25 Kg expiring **30 September 2026** · 60 Kg expiring **31 March 2027** | **85 Kg** |
| **Toor Dal** | 20 Kg, one batch | **20 Kg** |
| **Ghee** | 20 L, one batch | **20 L** |
| **Cardamom** | 400 gm, one batch | **400 gm** |

- Request **A** from UAT-068, approved in UAT-069, asks for **Rice 40 Kg · Toor Dal 14 Kg · Ghee 6 L ·
  Cardamom 250 gm** for the Prasadam Kitchen on 2 September 2026.

## Steps

### Recording the issue

| # | Do this | You should see |
|---|---|---|
| 1 | Write down the four totals and both Rice batch quantities from the table above | Noted. You will be checking arithmetic, not impressions |
| 2 | Open request **A** and press **Record what was issued** | A line per ingredient, each **pre-filled** with the approved quantity — 40, 14, 6, 250 — and its unit |
| 3 | Leave every figure as it is and confirm | The request's status becomes **Issued**. The history gains *"Issued by [your name] · 30 Aug"* |
| 4 | Look at the actions now offered on the record | Nothing further. It is a closed record you can read and print |
| 5 | Go to **/inventory** → **Rice** | Total is **45 Kg** — 85 less 40 |
| 6 | Look at Rice's **batches** | The batch expiring **30 September** is **gone or at zero** — all 25 Kg of it went first — and the batch expiring **31 March** holds **45 Kg**. The oldest goods left the shelf first |
| 7 | Check **Toor Dal**, **Ghee** and **Cardamom** | **6 Kg**, **14 L**, **150 gm** |
| 8 | Open Rice's **movement history** | **Two** new rows, both of type **Issue**, both negative — one of 25 against the September batch, one of 15 against the March batch — each naming request **A** |
| 9 | Click through from a movement to what it refers to | It takes you to request A, which names **Prasadam Kitchen**. The kitchen is not written on the movement itself |
| 10 | Look at the **storage location** on those issue rows | Empty, exactly as it is on a consumption row. The store room's rice has **not** relocated itself to "Prasadam Kitchen" |
| 11 | Add up every movement in Rice's history by hand | The total equals **45 Kg**, the figure at the top of the page, exactly |

### It cannot be done twice

| # | Do this | You should see |
|---|---|---|
| 12 | Press **Record what was issued** on request A again, or paste its address | Refused: *The store has already issued against this request* (`KMS-4982`) — *raise a new request if the kitchen needs more* |
| 13 | Check the stock again | **Unchanged.** Still 45 Kg of rice |

### A line the store did not fill

| # | Do this | You should see |
|---|---|---|
| 14 | As Gopal Das, raise and submit a new request: **Prasadam Kitchen**, needed 6 September 2026, reason `Monday breakfast`, **Rice `5` Kg** and **Ghee `2` L**, dish **Upma `120 servings`**. Approve it as the admin | Approved |
| 15 | Record the issue, but set **Ghee** to `0` and leave Rice at `5` | Accepted |
| 16 | Check **Ghee** | **Still 14 L.** No movement was written for a line where nothing was handed over — the same rule a dish that was not made follows |
| 17 | Check Ghee's movement history | **No** zero-quantity Issue row sitting in it. A movement of nothing is not a movement |
| 18 | Check **Rice** | **40 Kg**, and one more Issue row |

### The store cannot give what it has not got

| # | Do this | You should see |
|---|---|---|
| 19 | Raise, submit and approve a request for **Prasadam Kitchen**: **Rice `500` Kg**, **Toor Dal `2` Kg**, dish **Khichdi `5000 servings`** | Approved — approval is a decision, and the store's shelves are not consulted yet |
| 20 | Record the issue with the pre-filled figures | Refused: *There isn't enough stock to cook this* (`KMS-4911`), **itemising what is short** — 500 Kg of rice wanted, 40 Kg on hand |
| 21 | Read what it tells you to do next | It should point you at correcting the count or receiving stock — the storekeeper's real next move is a count correction on **/inventory** (UAT-024) if the shelf disagrees with the books |
| 22 | **Check every ingredient on that request, not just Rice** | **Nothing moved.** Rice still 40 Kg, Toor Dal still 6 Kg. The Toor Dal line was coverable and was still not written |
| 23 | Reduce the Rice figure to `10` and record the issue again | Accepted. Rice **30 Kg**, Toor Dal **4 Kg** |
| 24 | Try to record an issue for an ingredient the temple tracks but holds **none** of | Refused the same way, naming that ingredient |

### Who may do it

| # | Do this | You should see |
|---|---|---|
| 25 | As `ikms.kitchen-staff.1@trading4good.org`, open an approved request | **Record what was issued** is not offered |
| 26 | Force it by address | Refused (`KMS-4301`) |
| 27 | As the admin, try to record an issue against a **draft** and against a **denied** request | Both refused: *This request hasn't been approved yet* (`KMS-4981`) |

## It passes if

- [ ] Recording an issue reduces each ingredient by exactly what was handed over.
- [ ] Batches are drawn soonest-expiry-first, across more than one batch where a line needs it.
- [ ] Every movement is of type **Issue**, is negative, references the request, and carries **no** storage location.
- [ ] The movements still add up exactly to the quantity shown at the top of the item.
- [ ] A line issued as zero writes no movement at all.
- [ ] A shortfall on any line refuses the whole issue (`KMS-4911`), names what is short, and changes nothing.
- [ ] An approved request cannot be issued twice (`KMS-4982`).
- [ ] Nothing can be issued against a request that is not approved (`KMS-4981`), and kitchen staff cannot issue at all (`KMS-4301`).

## Watch out for

- **Step 22 is the critical one.** A partial deduction on a refused issue would corrupt the store room silently and nobody would find it for months. Check every line, not just the one that was short, and check the batch quantities as well as the totals.
- The receiving kitchen appearing in the **storage location** column on an inventory item — "Rice · Prasadam Kitchen". That would corrupt where the system thinks the rice is kept. Blocker.
- A second stock balance appearing anywhere, per kitchen. There is one store and one set of books; issuing removes goods from the temple's stock, it does not move them into "Prasadam Kitchen stock".
- Whether you can **choose a batch by hand** instead of taking the oldest. The design allows it. If there is no way to, record it — a storekeeper may know the older sack is at the back and unreachable today.
- Quantities on the issue form being **rounded** — the form should carry the exact approved figure. Rounding belongs on the printed work order, not in the ledger. If `250 gm` comes back as `0.25 Kg` or `0.3 Kg`, note it against UAT-074 as well.
- An issue recorded for a date, or a request, in another temple. Try pasting one; it must be *not found*.
- Whether a **cooked meal** and an **issue** can both draw the same stock on the same day for the same kitchen. They should not be able to — that is what UAT-072 closes off.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT070-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
