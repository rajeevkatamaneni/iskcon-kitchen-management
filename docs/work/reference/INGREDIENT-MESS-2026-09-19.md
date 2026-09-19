# Dirty ingredients: facts (read-only, 2026-09-19)

Sources: the 32 vendored books in `backend/src/main/resources/recipe-library/*.json` (same as
`master_recipes` in kms_verify: 5,376 recipes, 46,337 lines, 2,238 distinct names), and the local
`ingredients` table (a staging copy plus today's local VERIFY rows; staging itself was not queried).

How counted: "base + preparation" uses the real `IngredientNameMatcher` (compiled from the working
tree and run over every name). Every other bucket is my own regex sort, checked by eye, so treat those
counts as close, not exact. Each name is put in one bucket only, first match wins in the order below.
Working files: `split.tsv`, `cats.json`, `tsplit.tsv`, `tcats.json` in this scratchpad.

## 1. The master library: 2,238 distinct names

| Bucket | Names | Recipe lines | Examples |
|---|---|---|---|
| Water, not bought | 20 | 1,383 | Water (1,201), Water, chilled (64), Water, boiling (31), Water, hot (19), Ice (15), Hot water (8), Water, for the syrup |
| By-product / not food | 11 | 12 | Toor dal, cooked water; Rice water (maand); Kat (water drained from cooked chana dal); Firewood ash, sieved; Muslin cloth |
| Another recipe used as an ingredient | 38 | 79 | Cooked rice (12), Hari chutney, Leftover roti, Dosa batter, Toor dal (cooked, thin), Huli pudi (see Masalas), Coconut milk, first extract |
| Combined lines ("A and B", lists) | 261 | 631 | Panch phoran and dry chilli (32), Turmeric and coriander powder (24), Mustard, cumin, asafoetida (19), Cashew and raisins (17). 7 are cut off mid-list: "Raw banana, pumpkin, papaya," |
| Alternatives ("A or B") | 42 | 51 | Jaggery or sugar, Butter or hot oil, Saffron or kesari colour, Eno / soda |
| Optional | 4 | 5 | Milk (optional), Rice (optional, for binding), Pineapple, diced (optional), Jaggery, optional |
| Base + preparation, split by the matcher | 367 | 3,470 | Coconut, grated (430), Ginger, grated (423), Grated coconut (161), Green chilli, slit (113), Curd, sour (54), Ghee, for the dough |
| Base + preparation the matcher leaves whole | ~153 | ~744 | Cumin, roasted (180), Sesame seeds, roasted (68), Potato, boiled (47), Ginger, julienned (24), Almond, slivered (22). The matcher's list has no "roasted", "boiled", "julienned", "slivered", "peeled" or "mashed". Some are debatable: Sugar, powdered; Black pepper, ground |
| Grade or variety, a separate item or not? | ~252 | ~1,146 | Moong dal, split (79), Curd, thick (74), Milk, full cream (55), Tomato, ripe (48), Semolina, fine (38), Rice, broken |
| Base, with a local name in brackets | 266 | 517 | Wheat flour (atta), Besan (gram flour) and Gram flour (besan), Makhana (fox nut) and Makhana (fox nuts) |
| Plain base | 824 | 38,299 | Salt (4,144), Green chilli (2,206), Turmeric, Ghee. There is still a little junk here: "Ghee for frying", "Milk to bind", "Oil for frying", "Earthen pots" |

**Near-duplicates (bucket d) cut across all of the above:**
- The matcher's `normalise` folds the 2,238 names into **1,849 keys**. 192 of those keys have more than one spelling, covering 581 names. Ghee alone has 16 ("Ghee, for the hands", "Ghee, for dunking" and so on). Coconut has 16, Water 12, Ginger 9.
- **47 groups differ only by plural, case or "paste"**: Curry leaves / Curry leaf, Almond / Almonds, Cloves / Clove, Peanuts / Peanut.
- **Synonyms the matcher cannot see** (no rule catches them, because the names share no words):
  - Besan / Gram flour / Bengal gram flour
  - Maida / Refined flour
  - Amchur / Dry mango powder
  - Dry red chilli / Red chilli, dry / Dried red chilli / Dry chilli
  - Toor dal / Tuvar dal / Arhar dal / Pigeon pea dal
  - Panch phoron / Panch phoran / Pancha phutana
  - Sago / Sabudana
  - Ajwain / Carom seeds
  - Kalonji / Nigella seeds
- 163 names with a local name in brackets also exist without it, e.g. "Wheat flour (atta)" beside "Wheat flour".

## 2. The temple's ingredients (local copy): 248 rows, 1 temple (ISKCON South Bengaluru)

**Where they came from:**
- **At least 192 came from the library.**
  - 61 are flagged `library_derived`.
  - Another 131 were created on 2026-08-15, all with vendor supplies attached. 130 of them are exact Karnataka-book names, such as "Bisi bele bath pudi (see Masalas)" and "Rice (optional, for binding)". The 131st started as the book's "Water, hot" and was renamed (see §3).
  - These 131 carry no flag because the flag only arrived in V69, applied 2026-08-22, with a default of false.
  - About 19 more created later match library names exactly (Potato, Tomato and so on), so they could be either.
- **Typed by staff: about 15.**
  - The 9 capitalised ones from 2026-08-12, such as "Chana Dal", "Toor Dal", "Wheat Flour".
  - Avalakki (poha), Copra (dry coconut), Rice (sona masoori), Sunflower Oil.
  - LPG and Leaf plates, which are supplies.
- **22 local test rows** (VERIFY-A to VERIFY-D, created today by verify agents).

**The same buckets, over the 226 rows that are not test rows:**
- **Water: 4.** Water (10 recipe lines), Water(Hot), Water, boiling, Water, chilled.
- **Another recipe used as an ingredient: 3.** Bisi bele bath pudi (see Masalas), Huli pudi (see Masalas), Toor dal (cooked, thin).
- **Combined: 3.** Bay leaf and clove; Mustard, fenugreek, curry leaves; Coriander leaves and lemon.
- **Alternatives: 5.** Eno / soda, Saffron or kesari colour, Butter or hot oil, Rice, broken or regular, Ash gourd or cucumber, cubed.
- **Optional: 3.**
- **Preparation, split by the matcher: 47.** Coconut, grated (14 lines), Cashew, halved, Green chilli, slit, Ghee, for finishing.
- **Preparation the matcher misses: 4.**
- **Grade or variety: 13.**
- **Local name in brackets: 17.**
- **Plain: 127.**

**Near-duplicates:**
- The matcher groups **28 sets covering 68 names**: Cashew ×3, Coconut ×4, Groundnut ×4, Water ×3, Mustard seed ×3.
- Synonym sets it cannot see:
  - Maida / Refined flour / Refined flour (maida)
  - Sago / Sago (sabudana) / Sabudana (sago)
  - Semolina / Fine rava / Rava (fine) / Rava (coarse)
  - Chilli powder / Red chilli powder / Dry red chilli powder
  - Dry chilli / Dry red chilli / Red chilli, dry
  - Copra (dry coconut) / Dry coconut, grated / Coconut, dry grated / Coconut, dry grated (kopra)
  - Beaten rice / Avalakki (poha) / Avalakki (thick poha)
  - Cumin / Cumin seed / Cumin seeds
  - Amchur / Dry mango powder

## 3. Hot water downstream, and why it reaches the shopping list

- **Where "Hot Water" came from.** The book line "Water, hot" (Ragi Rotti) was created as an ingredient. The audit log then shows it renamed on 2026-08-23 to "Hot Water", and the same day to "Water(Hot)". Its category is Vegetables.
- **What it is attached to now:**
  - Water: 10 recipes, 6 stock movements, a vendor supply at Kalasipalya Vegetable Mandi, an inventory item, and a received PO line (PO-2026-0024, 286 L, `PO_RECEIPT`). Stock was then adjusted by −598,491.857 ml and −286,000 ml.
  - Water(Hot): 2 recipes, 6 movements, a vendor supply at Kalasipalya, and an inventory item in Main store with a reorder level of 70.
  - Water, chilled: a vendor supply at Kalasipalya.
  - Water, boiling: a vendor supply at Ganesh Oil & Provisions.
  - Upcoming planned meals use Water (9 dish-lines) and Water(Hot) (4).
- **Why it reaches the list.** `ShoppingListService` merges three streams per ingredient and has no exception:
  - the meal-plan shortfall
  - stock below its reorder level
  - the balance a vendor never delivered on a closed order

  Any recipe line produces demand, and Water(Hot) also has a reorder level.
- **Nothing marks an ingredient as not bought.**
  - The only flag is `is_supply` (V99). It means the opposite: bought, but not food. It only hides the item from the recipe picker.
  - The merge tool's EXACT grouping would fold Water, boiling and Water, chilled into Water. Water would still be bought.

## 4. How the library is loaded

- **The loader.** `LibraryLoader` reads the vendored JSON. It runs from `LibraryLoadRunner` when `kms.recipe-library.load-on-start=true` (a Cloud Run job), or from `POST /api/v1/.../load`, which needs `MANAGE_RECIPE_LIBRARY`.
  - It keeps each ingredient name verbatim in `ingredients` jsonb and in `ingredient_names`.
  - It upserts on `(state_slug, recipe_slug)` and overwrites every column. Any Super-Admin edit made through `PUT` would be lost; there are none today (0 rows with `updated_by_user_id`).
- **The vendored JSON is not meant to be edited.** Its README says it is byte-for-byte so it can be diffed against upstream (`kranthimj23/ikms`).
- **Re-running the loader would bring back the old names** unless the change is made upstream or in the loader. It never touches a temple's own copies: temple ingredients are created only when a temple imports a recipe (`RecipeImportService`).
- **The import now splits preparations.** Since T-250, uncommitted and not on staging, the import splits a preparation into a note, so "Cashew, halved" creates Cashew. For water, combined lines, alternatives, optional lines, the roughly 153 missed preparations, grades and synonyms, it still creates a new ingredient each time.
- **Other work in the tree.** R-DUP-2 (T-251) blocks duplicates at creation. The merge tool (T-270 and T-276, V147) is built and uncommitted, and has not been checked on screen.

## 5. Ways to clean it up

**A. Clean the library once and re-load it.**
- **Where the cleaning lives:** either upstream in the teammate's repo followed by a re-vendor, or a mapping table in the loader. The loader route keeps the diff-against-upstream rule; editing the vendored JSON breaks it.
- **Fixes:** future imports and the library browse, for every bucket, including water wording, combined lines and synonyms.
- **Leaves:** all 248 temple rows as they are. Preparation wording would disappear from library views unless the master line gains a note field, which T-250 does not have for master lines.
- **Effort:** the 367 matcher-split names are automatic. Roughly 900 names need a human decision: 153 missed preparations, 252 grades, 261 combined, 42 alternatives, 69 water or recipe lines, and the synonyms.
- **Combined lines can't be split automatically.** One quantity covers several things, so each of the 631 lines needs a decision about its quantity.
- **Reversibility:** a loader map is fully reversible, because the original JSON can be re-loaded. Editing upstream is reversible through git.

**B. A "not bought" flag** (water, ice, and possibly by-products).
- **Fixes:** Hot water and the other waters on the shopping list, reorder alerts, and vendor pickers. Costing would be ₹0 for them.
- **Effort:** small. One column, filters on the three shopping-list streams, the low-stock list and the vendor pickers, one form checkbox, and flagging 4 local rows.
- **Leaves:** the existing water PO line and stock movements as history, and all the duplicates.
- **Trap:** flagging a recipe used as an ingredient (Cooked rice, Toor dal cooked, chutneys) as not bought loses the demand for the raw rice or dal it is made from, so the temple under-orders. Those need a recipe-inside-a-recipe, which isn't modelled, or a rewrite to the raw ingredient.
- **Reversibility:** easy. It is a flag.

**C. The merge tool for the temple's existing copies** (built).
- **Fixes:** on local data it would propose 28 groups covering 68 names. The admin can build synonym groups by hand.
- **Leaves:**
  - Synonyms are not proposed.
  - Coconut's group will be refused on unit type: Coconut is kept in pieces, the grated forms in Kg.
  - Water stays a bought item.
  - Combined and alternative lines can't be handled, because a merge can't split one ingredient into two.
  - "Water(Hot)" is not grouped with Water, because it normalises to "water hot".
- **Effort:** commit, screen-check, back up staging, then an admin session of about 30 groups.
- **Hard to reverse:** merged-away ingredients are deleted, and their history is repointed in one transaction per group. The old name is kept only as an alias. There is no un-merge.

**D. Combined approach.** Keep the import split and the duplicate block that are already built. Add B for water. Run C on the temple's data after a staging backup. Add A as a loader-side map for what the matcher can't do: combined lines, alternatives, optional lines, missed preparations, synonyms as aliases.
- **Fixes:** both the source and the existing copies.
- **Effort:** the largest, dominated by the roughly 900 hand decisions in A.
- **Reversibility:** everything can be undone except the merges.
- **Order matters:** if C runs before A, a re-import brings back only names that aren't aliased yet. Aliases catch the merged-away names, but not new variants.

**Open questions any option runs into:**
- Whether grades are separate purchasable items: Curd, thick; Tomato, ripe; Semolina, fine; Rice, broken.
- Whether "roasted", "boiled" and "julienned" join the matcher's preparation list. This is pending Q-12.
- What to do with "Ghee for frying" and "Milk to bind", which have a purpose but no comma, so the matcher does not split them.
