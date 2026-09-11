# The rest — what is left when the run plan is done

**This is the list to pull out when Rajeev asks "what else is left?"** It was separated from the
run plan on 2026-09-10 at his request, so the answer to that question is a file rather than a fresh
sweep of the ledger every time.

**What is NOT in here:**

- **Anything in the run plan** at the top of `DISPATCH.md` — the ordering rebuild, T-058's ten
  cleanups, and the three tester-facing fixes he pulled forward (T-105, T-118, T-080).
- **Phase 2 work.** T-091, secondary preferred vendor, is parked there and stays parked.

**Every row below has a full entry in `DISPATCH.md`** with its evidence, its reasoning and its
traps. This file is the index, not the detail.

## ⚠ This is Wave F. It is work, not a parking lot.

**Rajeev ruled later the same day that the session working the run plan picks this list up
immediately after finishing Wave E** — not after UAT, and not only if somebody asks for it. **Work
it the same way as every other wave:** unit tests until green, verify the merged tree, commit by
named path, push, CI green, deploy, and drive it in a browser.

**This supersedes what he said an hour earlier**, which is kept because it explains why the file
exists at all and what he thinks these items are worth:

> *"The rest I'd genuinely leave. Your testers will rank them for you, and several will turn out not
> to matter at all."*

**Both things can be true.** He does not rate them highly — so **an item that turns out not to be a
real problem is dropped with a sentence saying why**, exactly as T-058 is briefed. Low confidence
that they matter is not a reason to leave them undone; it is a reason to be willing to close them
cheaply.

---

## A person would see these — 2

| | What happens |
|---|---|
| **T-108** | **"1 pieces"** prints across the application. One fixed plural in one shared label; the fix is one place, the sightings are everywhere. |
| **T-077** | A language picker can **silently read English for ever** in one case, because its state is seeded once from a prop that later changes. |

## Money and records — 3

| | What happens |
|---|---|
| **T-070** | A donor's own list of charges shows **a struck charge as an ordinary one**. |
| **T-071** | A credit note settles a variance, and **the invoice goes on showing the full discrepancy**. |
| **T-072** | A voided gift becomes **a permanent reconciliation mismatch nobody can clear**. |

## Only we would ever notice — 6

| | What happens |
|---|---|
| **T-123** | A donor's history has **no limit and no paging** — every gift, for ever, rendered on one screen. Nobody has measured it; it is recorded so it is not rediscovered as a surprise. |
| **T-097** | The temple's name is looked up **once per recipient**, so a 400-person send makes 400 identical queries. |
| **T-117** | **Nothing checks that the Terraform example matches the real variables.** It had drifted badly once already. |
| **T-063** | A rounding promise is kept by **each provider separately instead of once at the boundary**, so a new provider can quietly not keep it. |
| **T-064** | **88 test classes each build their own Spring context**, which is why CI has run out of heap more than once. |
| **T-065** | **A closed Spring context is never released** — of 81 alive in a worker, almost none should have been. |

## Loose ends with no row of their own — 1

| | What happens |
|---|---|
| **T-143** | The **"never needs servicing" tick box is not on the equipment registration form**, only on the servicing form. So declaring sixty stools un-serviced means sixty visits to sixty pages. Found by T-120's builder, left because the form was outside its contract. One small change to `frontend/components/EquipmentForm.tsx`. |

---

**Three of these have a shape worth noticing, because it recurs.** T-070, T-071 and T-072 are all the
same defect: **something was struck, voided or credited, and a screen that sums or lists goes on
counting it.** Whoever takes one should look at the other two on the same pass — and at
[[adding-a-state-to-a-summed-table]] in the session memory, which is the general form of it.
