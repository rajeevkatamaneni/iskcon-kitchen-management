# UAT-042: The order in the vendor's language

| | |
|---|---|
| **Feature area** | Ordering — purchase order translation |
| **Technical stories** | E5-S5 (PO translation) |
| **Roles exercised** | Kitchen staff |
| **Depends on** | UAT-041, UAT-021 (glossary) |
| **Environment needs** | **A real translation provider**, plus the worker and renderer from UAT-041 |

## What this feature is for

Many shopkeepers in India cannot reliably read English ingredient names, and the temple cannot depend
on the vendor translating for themselves. A wrong dal delivered because the sheet was in English is a
real and frequent failure. So the sheet can be produced in the vendor's own language.

## How it is supposed to work

- The **vendor's preferred language** (set in UAT-037) is the default, and can be overridden for one
  document.
- The **ingredient names, notes and the sheet's own labels** are translated. **Numbers, dates and the
  order number are not** — they must be identical in every language.
- The temple's **glossary** (UAT-021) is consulted before the machine, so the temple's own words for its
  ingredients are used.
- The translated sheet exists alongside the English one; both can be downloaded.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/orders** → open the **Sent** order to **Sri Balaji Provisions** (preferred language
  Hindi)
- Make sure your glossary has `Toor Dal → तूर दाल` in Hindi (UAT-021).
- **Have a Hindi reader available** for step 5, and ideally a Kannada reader for step 8.

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | On the order, find the language control | It is labelled as the vendor's language, and **Hindi** is already selected — the vendor's own preference |
| 2 | Generate the sheet in that language and download it | A sheet in Devanagari script |
| 3 | Check the ingredient names | Translated — and **Toor Dal appears as तूर दाल**, your glossary's wording |
| 4 | Check the quantities, units, dates and order number | **Unchanged** — the same digits as the English sheet |
| 5 | **Ask a Hindi reader:** could a shopkeeper fill this order from this sheet? | Record their answer, and any word they say is wrong |
| 6 | Check the sheet's own labels — "Quantity", "Needed by", "Vendor" | Translated too, not left in English |
| 7 | Change the language on this document to **Kannada** and generate again | A Kannada sheet; the vendor's stored preference is unchanged for next time |
| 8 | **Ask a Kannada reader** the same question as step 5 | Record their answer |
| 9 | Open the **Nandini Dairy Agency** order (preferred language Kannada) | Kannada is pre-selected for that vendor |
| 10 | Check **Documents** on the order | Both the English and the translated sheets are listed and downloadable |
| 11 | Look at the script rendering closely | No empty boxes, no question marks, no letters overlapping or breaking apart |

## It passes if

- [ ] The vendor's preferred language is selected by default and can be overridden per document.
- [ ] Ingredient names, notes and labels are translated; numbers, dates and the order number are not.
- [ ] Glossary terms beat the machine's translation.
- [ ] Both scripts render correctly in the PDF.
- [ ] English and translated sheets both remain available.
- [ ] A native reader judges the sheet usable by a shopkeeper.

## Watch out for

- **Environment first:** with a stub translator you will see language-tagged English, not a translation. Record it as *environment* (R5).
- A **quantity** that changed between languages. That is a Blocker — it would cause a wrong delivery.
- Empty boxes in the PDF: the script's font is missing (Major, with a screenshot).
- The glossary being ignored here even though it works for recipes (UAT-020). Both are meant to use the same glossary; a difference is a real finding.
- A translated sheet that quietly replaces the English one rather than sitting beside it.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT042-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
