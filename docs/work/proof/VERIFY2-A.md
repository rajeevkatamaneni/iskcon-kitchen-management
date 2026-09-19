# VERIFY2-A — round 2: duplicates, ingredients, vendors (§9A, §5, AC-3.1/3.2, G1 ingredient page, G2 close matches)

Verified 2026-09-19, about 16:50–17:15 UTC, on the local stack (web :3000, API :8080, database `kms_verify`).
Headless Playwright Chromium (chromium-1134), not Rajeev's Chrome. Signed in by minting Firebase custom tokens
and writing them into the page's IndexedDB. Accounts:

- Temple Admin: ikms.temple-admin.1
- Kitchen Manager: ikms.kitchen-staff.5 (whoami says KITCHEN_MANAGER)
- Kitchen Staff: ikms.kitchen-staff.3
- Volunteer (the user with no permission): ikms.volunteer.3

Scripts and screenshots are in the session scratchpad under `va2/` and `va2/shots/`, not in the repo. Geometry means
`documentElement.scrollWidth` against `clientWidth`, every element that clips its content or sits past `main`'s right
edge, canvas-measured text against every input and select, and a Range check for any word split across two lines.

Backend, my run under `tools/work-lock.sh`: DuplicateIngredientIT, RecipeImportPreparationIT, RecipeImportCloseMatchIT,
PackSizeIT, MarketRateIT, VendorOnboardingIT, VendorPriceHistoryIT, OnePreferredVendorIT, InventoryOpeningCountIT,
ingredient.merge.*, ProcurementRowLevelSecurityIT, ProcurementDataModelMigrationIT, ErrorCodeTest and
IngredientNameMatcherTest came to **1143 run, 1143 passed, 0 skipped, BUILD SUCCESSFUL**. The frontend suite was not
re-run by me.

## Results per AC: 12 PASS, 1 FAIL

- **AC-3.1 RLS — PASS.** I connected as `kms_app`, which is not a superuser and has no BYPASSRLS, and counted
  `ingredient_pack_sizes`, `vendor_price_history`, `ingredient_aliases` and `ingredient_market_rate_history`:
  - no tenant set: 0 / 0 / 0 / 0
  - another tenant's id: 0 / 0 / 0 / 0
  - own tenant: 20 / 56 / 8 / 47

  ProcurementRowLevelSecurityIT passed.
- **AC-3.2 — PASS (test only).** ProcurementDataModelMigrationIT passed. There has been no migration since round 1.
- **R-DUP-1 — PASS.** I copied the library recipe "Ambali" as Temple Admin, using Add to my recipes; it had no close
  matches.
  - The temple's ingredient count was 275 before and 275 after.
  - In the database the lines are Green chilli with the note `slit`, and Buttermilk with the note `sour`.
  - The recipe page reads "Green chilli · slit" and "Buttermilk · sour" at 1280 and at 390. Nothing is clipped and
    no word is split.
  - The merged recipe "VERIFY2-A Jaggery drink" reads "VERIFY2-A Jaggery · grated".
- **R-DUP-2 — PASS.**
  - **Temple Admin, Kitchen Manager and Kitchen Staff, at 1280 and 390, on /ingredients/new:**
    - "Tomatos" is stopped with "Did you mean Tomato? / Use Tomato, or add a preparation note instead. / Use Tomato /
      It’s a different ingredient".
    - "Curd sour" is stopped with "Did you mean Curd?", in the same wording.
    - Buttons at 1280: 114 and 202 wide, side by side. At 390: 308 wide, stacked. At the same size they can't sit
      side by side in 308px.
  - **"It’s a different ingredient"** opens "Keep “VERIFY2-A Jaggery, powdered” separate from VERIFY2-A Jaggery?".
    Choosing to keep it writes INGREDIENT_ADDED with the reason "Added although it looks like …: confirmed as a
    different ingredient." and `confirmedDifferentFrom`.
  - **Rename path (API):** the rename got 409 KMS-400156. With `confirmDifferent` it got 204, audited as
    INGREDIENT_UPDATED "Renamed although it looks like …".
  - **Through the API:**
    - Kitchen Manager: "Tomatos" as a supply → 409 KMS-400156; "Curd sour" → 409; "Cashews" → 409.
    - Kitchen Staff: "Tomatto" → 409.
- **R-DUP-3 — PASS on all five bullets.** Temple Admin, on my own pair: "VERIFY2-A Jaggery, grated" (4 Kg on hand,
  1 recipe line, a supply at Vendor One for ₹50 / Kg, a Bag = 25 Kg pack) into "VERIFY2-A Jaggery" (10 Kg, a supply at
  Vendor One for ₹1,400 / bag). The merge screen proposed it. The preview said "On hand after 14 Kg" and asked "VERIFY2-A
  Vendor One has a different price for two of these. Which price should it keep?". "Merge into" stayed disabled until
  I chose. The confirmation said "Merge 1 ingredient into VERIFY2-A Jaggery? … This can’t be undone.", and the result
  said "Merged into VERIFY2-A Jaggery."
  1. **Stock:** 10 + 4 before; afterwards the kept ingredient's movements sum to 14.000 KG, over 2 movements, neither
     deleted.
  2. **Recipes:** the recipe line is now VERIFY2-A Jaggery with the note `grated`.
  3. **Shopping list:** before the merge there were two hand-added lines (50 Kg and 3 Kg); afterwards there is one,
     "VERIFY2-A Jaggery 53 KG". Round 1 could not verify this bullet.
  4. **Supplies, packs and prices:**
     - Vendor One kept ₹1,400 / bag (the ₹56 / Kg one I chose). Vendor Two is still the only Preferred vendor.
     - The packs are Bag = 25 Kg and 500 gm, with no duplicate Bag.
     - The market rate is kept.

  **No reference left:** a query over every foreign key that points at `ingredients` returns 0 rows for the merged-away
  id, and the ingredient row is gone. **Audit:** INGREDIENT_MERGED "Merged VERIFY2-A Jaggery, grated into VERIFY2-A
  Jaggery.", plus INGREDIENT_DELETED "Merged into VERIFY2-A Jaggery.". **Alias:** a search for the old name returns
  the kept ingredient. Creating the old name again gets 409 KMS-400156. The merge screen at 1280, 1024 and 390 has no
  clipping and no split words. The preview at 390 has none either.
- **R-ING-1 (on the new /ingredients/[id]) — PASS, with defect N1.** I reached the page from the name link on
  /ingredients.
  - **Adding sizes:** Bag 25 Kg, then 500 gm, then 1 Kg. The chips read "500 gm · 1 Kg · Bag = 25 Kg". The Unit list
    offers only Kg and gm for a Kg ingredient.
  - **Refusals on screen:**
    - Sack of 25000 gm (the same size as the bag) → "This ingredient already has a pack of that size." KMS-400157.
    - 0 and −5 → "Size must be more than 0".
  - **Removing:** "Remove 1 Kg" removed it.
  - **The hint:** on hover and on keyboard focus it reads exactly "The pack sizes vendors sell this in. The shopping
    list suggests whole packs."
  - **Refusals through the API:**
    - L or pieces on a Kg ingredient → 400 KMS-400013.
    - 0 or −5 → 400 KMS-400001.
    - The 9th pack → 409 KMS-400158.
  - **By role:** Kitchen Manager adds (201), Kitchen Staff gets 409 KMS-400157 on a duplicate, Volunteer gets 403
    KMS-400021.
  - **Not verified by me:** "the shopping list uses them". It is group B's (R-SL-2/3), and a hand-typed line correctly
    has `buyPacks: []`.
- **R-ING-2 — PASS.** Temple Admin, on the ingredient page, Link a vendor:
  - I linked VERIFY2-A Vendor One as Bag = 25 Kg, ₹1,500, 2 days, Preferred. The row reads "₹1,500 / bag · ₹60 / Kg".
    In the database: `vendor_supplies` last_price 60.0000, price_per_pack 1500.00, pack Bag 25 KG, lead 2 days. This is
    the same row shape the vendor page writes, and the history row's source is ONBOARDING.
  - The vendor names in the table link to the vendor page.
  - A Kitchen Manager at 390 linked Vendor Two to VERIFY2-A Cardamom as Preferred, and it saved.
- **R-ING-3 — PASS.**
  - **The ingredient page:**
    - It shows "Market rate · ₹55 / Kg · 19 Sept" and "Typed in by hand". Blank and 0 get "Market rate must be more
      than 0". The label is "Market rate (₹ per Kg)".
    - Kitchen Staff changed it to 52 at 390; the line reads "Market rate · ₹52 / Kg · 19 Sept".
    - Kitchen Manager and Kitchen Staff get 400 KMS-400161 through the API for a bad value. Volunteer gets 403.
  - **Add to inventory, VERIFY2-A Jaggery:**
    - The label reads "What it would cost to buy today (₹ per Kg)". It pre-fills 58, the preferred vendor's (Vendor
      Two's) list price.
    - Blank → "… is required". 0 → "… must be more than 0". Same at 1280 and 390.
    - I saved it with 10 Kg: one ADJUSTMENT of 10 KG, and the market rate is 58, STOCK_TAKE, 2026-09-19.
  - **VERIFY2-A Sona masoori, with no vendor and no market rate:** the box starts empty. I saved it at 390 with 20 Kg
    at 50, and the market rate is 50 STOCK_TAKE.
  - **Through the API:** a missing, 0 or −3 value gets 400 KMS-400161 for Temple Admin and for Kitchen Staff, and no
    inventory item is created.
  - **The report:** I issued 10 Kg of Sona masoori to the Deity Kitchen (IR-2026-0004, submit → approve → issue).
    "Issued from the temple store", September, shows Deity Kitchen ₹1,100. It was ₹600 before, and the difference is
    10 Kg × ₹50. The API gives ingredientsWithoutPrice 0 for 19 Sept.
- **R-VEN-1 — PASS.** Temple Admin, VERIFY2-A Vendor One, Other ingredients (275 rows).
  - Searching "VERIFY2-A" left 3 rows, and the Spices filter left 1.
  - I ticked Jaggery, powdered (₹50), Sona masoori (₹48, 3 days, Preferred) and Cardamom (no price). It read "3
    ticked". Then I pressed Save.
  - The notice said "3 ingredients added to Supplies.". Supplies holds all three, and Other ingredients, still searched
    for "VERIFY2-A", has none left.
  - History: 2 ONBOARDING rows. None for Cardamom, which has no price.
  - Geometry with rows ticked and after saving, at 1280 and 1024: document 1280/1280 and 1024/1024, nothing clipped,
    no split words. At 390 the document is 390/390. The only things past `main` are in the table heading, which is
    hidden by clip in card layout.
- **R-VEN-2 — PASS.**
  - **The ingredient page:** with Vendor One preferred, ticking Preferred for Vendor Two read "Preferred (replaces
    VERIFY2-A Vendor One)" before saving. After Link, the database has Vendor Two t and Vendor One f.
  - **The vendor page, as Kitchen Manager:** Edit on Cardamom with Preferred ticked read "Preferred (replaces
    VERIFY2-A Vendor Two)". I pressed Cancel.
- **R-VEN-3 — PASS, with minor defect N2.** Ingredient page, Vendor One row:
  - **1,500 → 1,600 per bag:** the marker has `data-direction="up"`, class text-danger, rgb(131,74,67). The darkest
    pixel in the marker box is 136,81,75. The tooltip, on hover and on keyboard focus, says "₹60 on 19 Sept", and the
    aria-label is "Up from ₹60 on 19 Sept".
  - **1,600 → 1,400:** `down`, text-success rgb(56,105,68), and the tooltip says "₹64 on 19 Sept".
  - **The invoice AC** (group D's invoices, ₹60 on 15 Sept then ₹64 on 18 Sept for VERIFY-D Rice): both the ingredient
    page and the vendor page show "₹64 / Kg", a red up arrow, and the tooltip "₹60 on 15 Sept".
- **R-VEN-4 (manual and onboarding sources) — PASS.** For VERIFY2-A Jaggery at Vendor One, `vendor_price_history`
  holds 60 ONBOARDING, 64 MANUAL and 56 MANUAL, all per stock unit, with price_per_pack 1500, 1600 and 1400. The
  INVOICE source belongs to group D, and its rows exist.
- **G2 close-match screen / R-DUP-1 on the close-match path — FAIL (defect N5).** The screen itself works as
  specified. "Allam Uragaya", at 390 and 1280:
  - **The dialog:** "Did you mean these ingredients?", with two rows: "Ginger, peeled / Did you mean Ginger?" and
    "Mustard, split / Did you mean Mustard?".
  - **Focus and answers:**
    - Focus starts on "Use Ginger".
    - Add to my recipes with nothing answered says "Choose an answer for every ingredient first.", with focus on
      "Use Ginger".
    - "It’s a different ingredient" opens "Keep “Mustard, split” separate from Mustard?". Go back returns to the
      question.
  - **Result:** answering Use for both created no ingredient (275 → 275), and RECIPE_IMPORTED records `closeMatches`
    as USED_EXISTING.
  - **Geometry:** dialog 358×617 at 390 and 640×493 at 1280. Nothing overflows it, and no word is split.

  The failure is in what the copy saves; see N5.

## Round-1 defects

- **A1 (vendor Supplies squeeze, words split mid-word) — FIXED.** Ingredient column 233 px at 1280, and 141 px with a
  row in edit and Preferred ticked. At 1024: 194 px, and 106 px in edit. There are 0 split words at 1280 and 1024, in
  edit or not. The Other ingredients heading "LEAD TIME (DAYS)" is 187 px at 1280 and 104 px at 1024.
- **A2 (the pack error stays after the entry changes) — FIXED on the vendor page.**
  - What I did: Vendor Two → Jaggery, powdered → Add a pack size… → Bag 25 Kg, then Sack 25 Kg. That showed KMS-400157.
    Then I typed Box, 0.
  - What happened: the old error cleared on typing. Pressing Add then showed only "Size must be more than 0".
  - **It recurs on the new ingredient page (N1).**
- **A3 (the same price written two ways) — FIXED.** The merge preview reads "₹56 / Kg · Bag = 25 Kg · VERIFY2-A
  Jaggery" and "₹50 / Kg · VERIFY2-A Jaggery, grated", in the same per-Kg form as the vendor page.
- **A4 (Add to inventory cuts the name at 390) — FIXED.** The form is one column at 390 (six fields, each 358 px),
  and the select text fits: the canvas check finds nothing, including for "VERIFY2-A Sona masoori — kept in Kg".
- **A5 (Add to inventory half-saves) — FIXED.** As Kitchen Manager (1280) and Kitchen Staff (390), with a count of
  5 Kg and ₹40, the page showed KMS-400025, and `inventory_items` has 0 rows for that ingredient. Nothing is left
  half-made. That the first count needs a Temple Admin is Q-22, known.
- **A6 (Issued notice wording) — FIXED.** It now reads "Banana, Beaten rice are left out until they have a vendor’s
  list price or a market rate."
- **A7 (an invisible focus stop at 390) — FIXED.** I pressed Tab 120 times through the vendor page at 390 and never
  landed on an element that was 1 px or inside a clipped ancestor. There is no "i" in the hidden heading at 390; at
  1024 the heading shows it.
- **A8 (unlabelled Preferred on phone cards) — FIXED on the vendor page.** It reads "Lead time — Preferred —".
  **It recurs on the new ingredient page (N3).**

## New defects

- **N1. The ingredient page keeps a stale pack-size error.**
  - Role and page: Temple Admin, /ingredients/25daf3b2-… (VERIFY2-A Jaggery), at 1280.
  - Steps: Pack sizes → Sack, 25000, gm → Add pack size. That shows "This ingredient already has a pack of that
    size. … KMS-400157". Then type Box, 0, Kg → Add pack size.
  - Observed: "Size must be more than 0", with the KMS-400157 box still under it. The same happens for −5.
  - Expected: the old error clears, as it now does on the vendor page (A2).
  - Cause: `components/ingredient/PackSizes.tsx` `add()` returns on a bad size before `setError(null)`, and typing
    doesn't clear it either.
  - Shot: `va2/shots/ing1-zero-after-dup-1280.png`.
- **N2 (minor). The trend tooltip gives the previous price without its unit on a pack-sold supply.**
  - Role and page: Temple Admin, ingredient page, Vendor One row, sold as Bag = 25 Kg.
  - Observed: the cell reads "₹1,600 / bag · ₹64 / Kg", and the tooltip reads "₹60 on 19 Sept".
  - Expected: a unit with the figure, for example "₹1,500 / bag · ₹60 / Kg on 19 Sept", or at least "₹60 / Kg". A
    bare "₹60" beside "₹1,600 / bag" reads as a price per bag.
- **N3. The ingredient page's phone cards show an unlabelled Preferred value (A8 again).**
  - Role and page: Temple Admin, ingredient page, at 390.
  - Observed: the Vendor One card reads "Lead time 2 days —". The dash is Preferred, with no label, because the cell in
    `components/ingredient/IngredientVendors.tsx` has no `data-label`.
  - Expected: the vendor page's fix, "Preferred —".
  - Shot: `va2/shots/ing-jag-390.png`.
- **N4 (layout). Link a vendor at 1024 takes three rows where two fit.**
  - Role and page: Temple Admin, ingredient page, at 1024.
  - Measured: the form is 630 px wide.
    - Row 1: Vendor 340, Sells it as 114, List price 112.
    - Row 2: Lead time (days) on its own, at x 337, 96 px wide, with 534 px empty beside it.
    - Row 3: the Preferred tick and Link vendor, 222 px together.
  - The tick and the button would fit beside the Lead time box: 433 + 16 + 222 = 671, and the row ends at 967. That
    is an unneeded row and a block of empty space, against the rule about rows.
  - At 1280 (two rows) and at 390 (they can't fit: 96 + 16 + 222 > 308) the layout is right.
- **N5. Copying a library recipe with "Use <existing>" on a close match drops the preparation words.**
  - Role and page: Temple Admin, Recipes → library "Allam Uragaya" → Add to my recipes → "Use Ginger" for "Ginger,
    peeled" and "Use Mustard" for "Mustard, split" → Add to my recipes.
  - Observed: the recipe lines are Ginger and Mustard with `preparation_note` NULL. The dialog showed the note as none,
    because "peeled" and "split" are not in the preparation-word list.
  - Expected (R-DUP-1: the base ingredient plus the preparation as a note): Ginger · peeled and Mustard · split. As it
    stands, the recipe loses "peeled" and "split"; "split" changes what is being bought.
  - This touches the held preparation-word list (Q-12), so it is the conductor's call: carry the text after the comma
    as the note when "Use" is chosen, or wait for Q-12.
  - Other recipes affected the same way: "Curd, thick", "Semolina, fine", "Jaggery, dark", "Cashew, broken", "Sugar,
    powdered" and "Ghee, hot".

## Observations, not counted as defects

- **The Volunteer sometimes gets 400 before 403.** With a malformed body, the Volunteer gets 400 KMS-400001 instead of
  403 on pack-sizes, supplies/bulk, supplies PUT and inventory/items: body validation runs before the permission check.
  With a valid body every one is 403 KMS-400021, and nothing is written. No 500 anywhere.
- **The full refusal list.** Every group-A endpoint refuses the Volunteer with 403 KMS-400021: ingredient read, list
  and create; market rate; stock-value suggestion; the supplies reads; the vendor read; the three merge endpoints; and
  close-matches. The Kitchen Manager and Kitchen Staff get 403 KMS-400021 on all three merge endpoints.
- **The pages each role sees:**
  - **Kitchen Manager and Kitchen Staff:** the ingredient page with every control (they hold all three permissions). No
    "Merge duplicates" link, and "Not your page" on /ingredients/merge.
  - **Volunteer:** "Not your page" on /ingredients, /ingredients/[id], /ingredients/merge, /vendors/[id] and
    /inventory/new.
- **Known, left for Rajeev by T-293:** on a gm ingredient, the List price box is per gm ("/ gm"; I typed 3 and it
  shows "₹3,000 / Kg"), while the market-rate box on the same page is per Kg.
- **Known, not a defect:** Q-22 (the first count needs a Temple Admin).

## Test data left in kms_verify

- Vendors: VERIFY2-A Vendor One and VERIFY2-A Vendor Two.
- Ingredients:
  - VERIFY2-A Jaggery: 14 Kg; packs Bag = 25 Kg and 500 gm; market rate 58; alias "VERIFY2-A Jaggery, grated", which
    was merged in and can't be undone.
  - VERIFY2-A Sona masoori: 10 Kg left after the issue; market rate 52.
  - VERIFY2-A Cardamom.
- Recipe: VERIFY2-A Jaggery drink.
- Shopping-list line: VERIFY2-A Jaggery, 53 Kg.
- Request: IR-2026-0004 (issued).
- Library copies: "Ambali" and "Allam Uragaya".
- I also created "VERIFY2-A Should not exist" (Kitchen Staff through the API), then deleted it.
