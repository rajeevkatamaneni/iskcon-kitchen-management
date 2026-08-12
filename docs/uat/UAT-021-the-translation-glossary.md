# UAT-021: The translation glossary

| | |
|---|---|
| **Feature area** | Recipes — translation glossary |
| **Technical stories** | E2-S6 (recipe translation and glossary) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-013 |
| **Environment needs** | The glossary itself works without a translation provider; seeing it *beat* the machine needs UAT-020's provider |

## What this feature is for

Generic machine translation mangles culinary vocabulary. "Toor Dal" can come back as something that
means nothing to a cook, and an ingredient nobody recognises is worse than English. The glossary is
the temple's own dictionary: for these terms, in this language, use exactly this wording.

## How it is supposed to work

- The glossary holds entries of the form **language + English term → preferred translation**.
- It is consulted **before** the machine, so the temple's wording always wins.
- The same glossary serves recipes (UAT-020) and purchase orders (UAT-042) — a vendor and a cook use
  the same words for the same dal.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/recipes** → **Glossary** (or go directly to **/glossary**)

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Glossary** | *Translation glossary*, an empty list, and an **Add a term** form with Language, English term, Preferred translation |
| 2 | Add: language **Hindi**, English term `Toor Dal`, preferred `तूर दाल` | The entry appears in the table under English / Preferred |
| 3 | Add: language **Hindi**, English term `Ghee`, preferred `घी` | Added |
| 4 | Add a term for a southern-script language, e.g. Telugu `Rice` → `బియ్యం` | Added |
| 5 | Add the same English term twice for the same language | Record what happens — is it refused, or does it create a duplicate? |
| 6 | Press **Delete** on one entry | It is removed |
| 7 | Go to **/recipes** → **Khichdi** and translate to **Hindi** (needs UAT-020's provider) | *Toor Dal* appears as `तूर दाल` — your wording, not the machine's |
| 8 | Delete the `Toor Dal` glossary entry and translate again | The machine's own rendering appears instead — proving the glossary was what changed it |
| 9 | Re-add the entry (you will want it for UAT-042) | Restored |
| 10 | Sign out; sign in as the **temple admin** and open **/glossary** | The same glossary, with the same entries — it belongs to the temple, not to a person |

## It passes if

- [ ] Terms can be added for more than one language, and deleted.
- [ ] A glossary term is used in place of the machine's translation for that word.
- [ ] Removing a term restores the machine's rendering.
- [ ] The glossary is shared across the temple's staff and admins.

## Watch out for

- Whether the glossary is applied to **purchase orders** as well as recipes. UAT-042 checks it; if the same term is honoured there, this feature is doing its job.
- Case sensitivity: if `toor dal` in a recipe is not matched by a glossary entry for `Toor Dal`, record it as Major — cooks type inconsistently.
- Duplicate entries (step 5) that silently both exist. Which one wins? Note the behaviour.
- If the glossary screen is reachable by kitchen staff but the design intended it to be admin-only (or vice versa), record what you found — it is a policy question worth settling.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT021-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
