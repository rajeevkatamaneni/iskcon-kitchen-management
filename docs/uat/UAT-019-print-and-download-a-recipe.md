# UAT-019: Print and download a recipe card

| | |
|---|---|
| **Feature area** | Recipes — documents |
| **Technical stories** | E2-S5 (recipe PDF and print) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-017 |
| **Environment needs** | **Background worker on** (the PDF is built by a job) **and a real document renderer**. With the stub renderer you get a placeholder file, not a real recipe card — see §4 of the README |

## What this feature is for

The kitchen works from paper, not from a phone held over a hot stove. Any recipe, at whatever size it
is being cooked today, has to come out as a clean printed card.

## How it is supposed to work

- **Download PDF** asks the system to build the document in the background; when it is ready the file
  downloads.
- The document reflects the **scale currently on screen** — if you scaled to 1,200 servings, the card
  prints 1,200-serving quantities.
- It carries the temple's name, the recipe name, the yield, the ingredient table, the method, and the
  date it was generated.
- A browser print view gives the same thing instantly, without navigation clutter, on A4.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/recipes** → open **Khichdi**
- **Confirm with the environment owner** that the background worker and the real document renderer are
  both switched on. If they are not, run steps 1–3 only and mark the rest *blocked by environment*.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Khichdi** | The recipe at base yield, with a **Download PDF** action |
| 2 | Press **Download PDF** without scaling | The button shows it is working, then a PDF file downloads |
| 3 | Open the PDF | The temple's name, the recipe name, base yield, the ingredient table with the same quantities as the screen, the method, and a generated-on date |
| 4 | Check the ingredient table in the PDF against the screen, line by line | Identical — same ingredients, same quantities, same units, same order |
| 5 | Back on the recipe, scale to `1200` and press **Download PDF** again | A second PDF, this one showing 1,200-serving quantities (Rice 96 Kg, Toor Dal 36 Kg, Ghee 12 L) |
| 6 | Compare the two PDFs | They differ only by scale; both are complete |
| 7 | Use **Print** (or your browser's print preview on the recipe page) | A clean A4 layout: no menu, no buttons, nothing cut off at the page edge |
| 8 | Print (or preview) a long recipe — add ten more ingredient lines to a test recipe first | The table breaks across pages sensibly; no row is sliced in half; headings are not orphaned |
| 9 | Download the same recipe twice in a row | Both attempts work — a second request does not fail because the first one already ran |

## It passes if

- [ ] A PDF downloads and opens.
- [ ] Its contents match the recipe on screen exactly, including at a scaled size.
- [ ] The temple name, recipe name, yield, ingredients, method and generation date are all present.
- [ ] The print view is clean A4 with no navigation.
- [ ] A recipe long enough to need two pages breaks cleanly.

## Watch out for

- **The most likely failure here is environmental**, not a product fault: with the background worker off, the button will spin and then fail; with the stub renderer, the file downloads but contains a placeholder. In both cases write *environment* in your report and name which one — that is root cause R5, not a bug.
- The message *"The PDF couldn't be generated"* — record what you had done just before it.
- A PDF that shows base quantities when you had scaled the screen. That is a real product defect (Major).
- Garbled or boxed characters in the PDF. English should be clean here; the same check for Indian scripts is UAT-020.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT019-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
