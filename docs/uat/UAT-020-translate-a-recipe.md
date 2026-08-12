# UAT-020: Translate a recipe

| | |
|---|---|
| **Feature area** | Recipes — translation |
| **Technical stories** | E2-S6 (recipe translation and translated PDF) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-015, UAT-019 |
| **Environment needs** | **A real translation provider**, and for the PDF steps, the **background worker and real renderer**. With the stub, translated text comes back tagged rather than genuinely translated |

## What this feature is for

Many temple cooks do not read English. A recipe they cannot read is a recipe they cannot follow. So
any recipe can be turned into a chosen Indian language — on screen and on the printed card — with the
quantities and units left alone.

## How it is supposed to work

- Choose a language and press **Translate**. Ingredient names and method steps are translated;
  **numbers and units are not touched**.
- Culinary vocabulary is where machine translation fails, so the temple's own glossary (UAT-021) is
  consulted first and beats the machine.
- The translated version can be printed or downloaded like the English one, in the correct script.
- Editing the recipe invalidates the old translation, so a stale translation is never served.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/recipes** → open **Khichdi**
- **Ideally have a reader** of Hindi and of one southern-script language (Telugu, Tamil or Kannada)
  available for step 6. Their judgement is the point of this test — a machine cannot tell you whether
  "Toor Dal" came out as something a cook would recognise.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Khichdi** and find the **Translate** control | A language choice — Hindi, Kannada, Telugu, Tamil, Marathi |
| 2 | Choose **Hindi** and translate | The recipe name, ingredient names and method appear in Devanagari script. A control appears to return to the **Original** |
| 3 | Check the quantities | **Unchanged** — 8, 3, 1 and their units, exactly as in English |
| 4 | Press **Original** | The English version returns |
| 5 | Translate to **Telugu** | Telugu script this time |
| 6 | **Ask a reader of each script:** does it read as a recipe a cook could follow? Are the ingredient names right? | Record their verdict verbatim, including any word that is wrong |
| 7 | With the Hindi translation on screen, press **Download PDF** | A PDF in Devanagari, with the script rendered properly — no empty boxes, no question marks, no overlapping letters |
| 8 | Repeat for the southern-script language | The same, in that script |
| 9 | Go back, **edit** the recipe (change a quantity), save, and translate to Hindi again | The translation reflects the edit — you are not served the old one |
| 10 | Translate a recipe with an ingredient you gave a glossary term to in UAT-021 | The glossary's preferred wording is used, not the machine's guess |

## It passes if

- [ ] A recipe translates into Hindi and into a southern-script language.
- [ ] Quantities and units are untouched by translation.
- [ ] You can switch back to the original.
- [ ] The translated PDF renders both scripts correctly.
- [ ] Editing the recipe does not leave a stale translation in place.
- [ ] A native reader of each script judges the result usable.

## Watch out for

- **Environment first:** if the translation provider is stubbed, translated text comes back tagged with a language marker rather than translated. That is root cause R5 — record it as *environment* and do not raise it as a product defect.
- Empty boxes (□□□) or question marks in the PDF mean the script's font is missing. That is a real defect (Major) and worth a screenshot.
- Numbers being "translated" into another numeral system, or units being changed. Blocker if it happens — a mistranslated quantity ruins a batch.
- A translation that is served instantly for a recipe you just edited — check step 9 carefully.
- If translation fails, record the code. `KMS-5202` means the translation service could not be reached and the English version is still available.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT020-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
