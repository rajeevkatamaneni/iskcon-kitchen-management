# UAT-025: Large corrections need a Temple Admin

| | |
|---|---|
| **Feature area** | Inventory — adjustment approval |
| **Technical stories** | E3-S7 (manual stock adjustment), E1-S7 (audit log) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-024 |
| **Environment needs** | None |

## What this feature is for

Small corrections are ordinary kitchen life. A correction that wipes out a fifth of the store room is
a different thing — it is either a serious loss or a serious mistake, and either way temple leadership
should be the ones to sign it. The threshold is a fifth of what is on hand.

## How it is supposed to work

- An adjustment larger than the temple's configured share of current stock (**20% by default**) is
  **refused for kitchen staff** and allowed for a **Temple Admin**.
- The refusal tells staff what to do: ask an admin, or split it into smaller corrections they can
  explain.
- A large adjustment made by an admin is written to the audit trail as well as the movement history.

## Before you start

- **You will use two accounts:** `ikms.kitchen-staff.1@trading4good.org` and
  `ikms.temple-admin.1@trading4good.org`.
- **Start at:** **/inventory** → **Rice**
- **Set up the numbers:** make sure Rice has a round, easily-divided quantity — say **50 Kg**. Then
  20% of it is 10 Kg, and the boundary is easy to reason about.
- Write down the exact starting quantity.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | As **kitchen staff**, open Rice (50 Kg) and adjust by `-5` (10% — below the threshold), reason **Spoilage** | Accepted; stock is 45 |
| 2 | Adjust by `-20` (well over a fifth of 45), reason **Spoilage** | **Refused:** *This adjustment is large enough that a Temple Admin has to approve it* (`KMS-4305`), advising you to ask an admin or split it |
| 3 | Check the stock and history | Unchanged — nothing was recorded, not even partially |
| 4 | Try the same as four separate `-5` adjustments | Each is accepted individually. **Record this**: whether splitting is an intended escape hatch (the refusal message itself suggests it) or a hole worth closing is a question for the team |
| 5 | Bring Rice back to 50 Kg (`+20`, reason *Count correction* — note whether **this** large increase is also refused for staff, and record the answer) | Record what happened |
| 6 | Sign out. Sign in as the **temple admin**, open Rice, adjust by `-20`, reason **Spoilage**, note `Sack found spoiled after monsoon damp` | Accepted |
| 7 | Look at the item's **Movement history** | The large adjustment is there with its reason and note |
| 8 | Go to **/audit** | The large adjustment appears, naming you as the actor, with the reason |
| 9 | As the admin, try to adjust below zero | Still refused (`KMS-4910`) — an admin can approve a large correction, not an impossible one |

## It passes if

- [ ] A small adjustment succeeds for kitchen staff.
- [ ] A large one is refused for kitchen staff with `KMS-4305` and useful advice.
- [ ] A refused adjustment changes nothing at all.
- [ ] A Temple Admin can make the same adjustment.
- [ ] The large adjustment appears in both the movement history and the audit log.
- [ ] Nobody, at any level, can drive stock negative.

## Watch out for

- **Step 4 and step 5 are the findings to chase.** Whether the rule applies to increases as well as decreases, and whether repeated small adjustments defeat it, are exactly the kind of thing a written story can leave ambiguous. Record what you observe precisely — the answer decides whether this is R1 (story unclear) or R3 (oversight).
- The threshold behaving differently from 20% of *current* stock. Work out the arithmetic for the exact quantity you had and note any disagreement.
- An admin's large adjustment that does **not** reach the audit log — a Major defect; the whole point of requiring an admin is that it be on the record.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT025-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
