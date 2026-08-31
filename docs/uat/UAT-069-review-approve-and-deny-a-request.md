# UAT-069: Review, approve and deny a request

| | |
|---|---|
| **Feature area** | Kitchens — reviewing an ingredient request |
| **Technical stories** | E10-S6 (approve, deny, withdraw), E10-S10 (the request record) |
| **Roles exercised** | Temple admin, kitchen staff |
| **Depends on** | UAT-068 (requests A and B must be awaiting review) |
| **Environment needs** | None |

## What this feature is for

A request is a question, and somebody with authority has to answer it. This is that answer: yes, with
a note about which sack to take; or no, with a reason the kitchen can read. Once the answer is given
it stands — a refusal that can be edited and re-shown is not a refusal.

The other half of this test is the record itself: one screen that shows anybody where a request stands
and offers only the acts *that person* may perform *in that state*. Nobody should have to know the
rules in order to follow them.

## How it is supposed to work

- **Approving and denying** belong to a Temple Admin or a Kitchen Manager — the role a temple's
  storekeeper is appointed to. Kitchen staff raise requests; they do not answer them.
- Either answer can carry a **note**, and the note is part of the record.
- While a request is **awaiting review** and undecided, its author or a Temple Admin may still **edit**
  it, or **withdraw** it back to draft.
- **Denied is terminal.** It cannot be edited, deleted, re-approved or withdrawn. If the kitchen still
  needs the food, it raises a fresh request, and the denial stays on the record with its note.
- **Approved** is fixed too, except for the one thing still to come: what the store actually hands over
  (UAT-070).
- **Approving your own request is allowed**, because forbidding it would deadlock a temple whose
  administrator is its only approver. It is recorded, and it is printed on the work order.
- Every step is written into an **event trail** on the record, readable as a sentence per event.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Start at:** **/ingredient-requests**, filtered to **Awaiting review**
- You should see request **A** (Prasadam Kitchen, 2 Sep, raised by Gopal Das) and request **B**
  (Sweets Kitchen, 3 Sep, raised by Yamuna Devi Dasi) from UAT-068.
- You will also need Gopal's session (`ikms.kitchen-staff.1@trading4good.org`) in a private window.
- **A note on who can approve.** The design gives approval to Temple Admin *and* Kitchen Manager. At
  the time of writing there is **no screen anywhere that grants somebody Kitchen Manager access** — the
  Staff form offers only *No login*, *Kitchen staff* and *Temple admin*. So this test exercises approval
  as the Temple Admin, and step 6 checks that kitchen staff are refused. If you find a way to make
  somebody a Kitchen Manager, run steps 7–12 as them too and say so in your report.

## Steps

### The record, read by three different people

| # | Do this | You should see |
|---|---|---|
| 1 | Open request **A** | One screen: kitchen, reference, date needed, reason, who asked and when, the ingredient lines, **the dish lines**, and a history |
| 2 | Read the history | Sentences, not codes — *"Raised by Gopal Das · 30 Aug"*, *"Submitted · 30 Aug"* — each with a name and a time |
| 3 | Note which actions this screen offers **you**, the admin | **Approve** and **Deny**. Also **Edit** and **Withdraw**, because it is still undecided |
| 4 | In a private window as **Gopal Das** (who wrote it), open the same request | **Edit** and **Withdraw** are offered. **Approve** and **Deny** are **not** |
| 5 | As **Yamuna Devi Dasi** (a bystander on this one), open it | She can read every part of it and is offered **nothing** to do |
| 6 | As Gopal, force it — paste the approve address, or use whatever control you can reach | Refused: *You don't have permission to do that* (`KMS-4301`), telling him to ask his temple administrator |

### Withdrawing, and editing while undecided

| # | Do this | You should see |
|---|---|---|
| 7 | As **Gopal**, press **Withdraw** on request A | It goes back to **Draft**, and it leaves the *Awaiting review* filter |
| 8 | As Gopal, edit it — change Toor Dal from `12` Kg to `14` Kg — and submit it again | Back to **Awaiting review**, showing 14 Kg. The history now carries the withdrawal and the re-submission, in order |
| 9 | As the **admin**, edit request **B** while it is awaiting review — add a note to a line | Allowed. An admin may correct a submitted request before answering it |
| 10 | As **Yamuna**, try to edit request **A** (Gopal's) | Refused: `KMS-4978` |

### The answer

| # | Do this | You should see |
|---|---|---|
| 11 | As the admin, open request **A** and press **Approve**, with the note `Take from the older sack of rice first` | Status becomes **Approved**. The history gains *"Approved by [your name] — 'Take from the older sack of rice first' · 30 Aug"* |
| 12 | Look at the actions now offered | No Edit, no Withdraw, no Deny. What appears instead is the store's job: **Record what was issued** and **Download work order** (UAT-070, UAT-071) |
| 13 | Open request **B** and press **Deny**, with the note `Sweets are being brought by the Bengaluru congregation this week` | Status becomes **Denied**, and the note is shown on the record with your name and the date |
| 14 | On the denied request **B**, look for Edit, Delete, Withdraw, Approve | **None of them is offered**, to anybody — not to Yamuna who wrote it, not to you |
| 15 | Force each one in turn by address | Each refused. Editing gives *This request can no longer be changed* (`KMS-4979`); answering it again gives *Somebody has already answered this request* (`KMS-4980`) |
| 16 | Try to answer the already-approved request **A** again — approve it, then deny it | Both refused with `KMS-4980`, telling you to open it and see the answer and who gave it |
| 17 | Try to **record an issue** against request **C** (still a draft) or a request you have just denied | Refused: *This request hasn't been approved yet* (`KMS-4981`) — *it has to be approved before the store can issue against it* |
| 18 | As Yamuna, raise a **fresh** request for the Sweets Kitchen covering what B asked for | Allowed. A denial is answered with a new request, not by reopening the old one |

### Approving your own

| # | Do this | You should see |
|---|---|---|
| 19 | As the **admin**, raise a request yourself: Deity Kitchen, needed 5 September 2026, reason `Ekadashi bhoga`, Ghee `1` L, dish `Sabudana Khichdi` `60 servings`. Submit it | It appears under **Awaiting review** in your own name |
| 20 | Approve it yourself | **Allowed.** The record shows the same person asked and answered — it does not hide it |
| 21 | Open **/audit** | Rows for the submission, the approval, the denial and the self-approval, each naming the person and the time. The self-approval is identifiable as such |

### Nothing crosses a temple

| # | Do this | You should see |
|---|---|---|
| 22 | Note the address of request A. Sign in as `ikms.temple-admin.2@trading4good.org` and paste it | *We couldn't find that request* (`KMS-4977`) — not the request, and not a permissions message that admits it exists |

## It passes if

- [ ] A Temple Admin can approve and deny with a note; kitchen staff cannot (`KMS-4301`).
- [ ] The same request shows different controls to its author, an approver and a bystander.
- [ ] An undecided request can be withdrawn to draft by its author or an admin, edited, and re-submitted.
- [ ] A denied request cannot be edited (`KMS-4979`), deleted, withdrawn or re-answered (`KMS-4980`).
- [ ] An approved request cannot be answered a second time (`KMS-4980`).
- [ ] Issuing against anything not approved is refused (`KMS-4981`).
- [ ] Approving your own request works and is visible on the record and in the audit log.
- [ ] The event trail reads as a sentence per event, with who and when.
- [ ] A request from another temple is simply not found (`KMS-4977`).

## Watch out for

- **Every illegal act must give its own code.** `4979`, `4980`, `4981` and `4978` each say a different thing. If two of them come back as the same generic message, record which — a message that does not say what is actually wrong sends the person back to the same dead end.
- A denial that can be **edited into a different request and re-shown**. That is the single most important thing on this page. Blocker if you can do it.
- An approval note that vanishes, or shows without the approver's name.
- The **Approve** button appearing for kitchen staff even greyed out. It should not be there at all; if it is, note whether pressing it is actually refused.
- The history showing raw status names (`SUBMITTED`, `DENIED`) instead of sentences.
- The record offering **Record what was issued** before approval, or **Download work order** on an unapproved request. Both are UAT-070 and UAT-071's business, but if the buttons are visible here, say so.
- **Say plainly whether you could test a Kitchen Manager at all.** If there is no way to make one, the second half of this feature's permission rule has been accepted on automated tests only, and that is worth recording.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT069-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
