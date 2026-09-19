# VERIFY-A — duplicates, ingredients, vendors (§9A, §5, AC-3.1/3.2)

Verified 2026-09-19, 07:50–08:30 PDT, on the local stack (web :3000, API :8080, database `kms_verify`).
Headless Playwright Chromium (chromium-1134), not Rajeev's Chrome. Signed in by minting a Firebase
custom token per account and writing it into the page's IndexedDB (the password endpoint hit
QUOTA_EXCEEDED because several verifiers share it). Temple Admin = ikms.temple-admin.1, Kitchen Staff =
ikms.kitchen-staff.5. **Kitchen Manager: not verified in the running app.** There is no Kitchen Manager
account in `kms_verify`, and making one means changing another staff record's role, which other
verifiers may be using. What covers it instead: `RolePermissions.java` (Kitchen Manager has
MANAGE_VENDORS and MANAGE_INVENTORY, not MERGE_INGREDIENTS) and `IngredientMergeIT` ("a Kitchen
Manager is refused all three endpoints"), which passed in my run.

Screenshots are in the session scratchpad, `.../79bf5e51-.../scratchpad/va/shots/`, not in the repo.
Geometry: `measure.js` in the same folder checks page scrollWidth against clientWidth, every element
whose content overflows a clipping box, every element past `main`'s right edge, and every input or
select whose text (canvas-measured) is wider than the box.

Backend tests (my run, under `tools/work-lock.sh`): ProcurementRowLevelSecurityIT,
ProcurementDataModelMigrationIT, DuplicateIngredientIT, RecipeImportPreparationIT,
IngredientNameMatcherTest, MarketRateIT, PackSizeIT, VendorOnboardingIT, VendorPriceHistoryIT,
OnePreferredVendorIT, ingredient.merge.* came to **239 run, 239 passed, BUILD SUCCESSFUL**. A later run by
another agent at 15:23Z shows one RecipeImportPreparationIT case failing (the Q-11 close-match test,
expected 409, got 500). That is work in progress on Q-11, not what I verified.

The API restarted at least three times while I worked (ECONNREFUSED for 10–30 s, because other agents'
edits were reloading). One UI failure below (D5) happened during one of those restarts.

## Results, one line per AC

- **AC-3.1 RLS on new tables — PASS.** All 7 new tables have `relrowsecurity` and `relforcerowsecurity`
  on and a `tenant_isolation` policy (ingredient_pack_sizes, vendor_price_history, vendor_invoice_lines,
  vendor_invoice_deliveries, attachments, ingredient_aliases, ingredient_market_rate_history). Checked
  read-only as `kms_app` (not superuser, no BYPASSRLS): no tenant set = 0 rows in all 7; another
  tenant's id = 0 in all 7; own tenant = 11 / 29 / 10 / 4 / 10 / 7 / 22. ProcurementRowLevelSecurityIT
  passed.
- **AC-3.2 existing data survives — PASS (test only).** ProcurementDataModelMigrationIT passed in my
  run. I did not replay the migration on data myself.
- **R-DUP-1 AC — PASS.** Temple Admin, Recipes → library "Majjiga" → "Add to my recipes". The
  temple's ingredient count was 252 before and 252 after. In the database the line is ingredient
  `Green chilli` (0ca54763…, the existing one) with `preparation_note = 'slit'`. The recipe page shows
  "Green chilli · slit". Also "Curd · fresh", "Water · chilled" and "Ginger · grated" landed on the
  existing base ingredients. The recipe edit form shows the notes in "Preparation" boxes. At 1280 and
  390: no clipping, no sideways scroll. Shots: `dup1-imported-majjiga-{1280,390}.png`,
  `dup1-recipe-edit-{1280,390}.png`. (The recipe is named "Majjiga", because an import can't be
  renamed.)
- **R-DUP-2 AC — PASS.** Temple Admin, Ingredients → Add an ingredient:
  - "Tomatos" was stopped with "Did you mean Tomato?" / "Use Tomato, or add a preparation note
    instead." / buttons "Use Tomato" and "It’s a different ingredient".
  - "Curd sour" was stopped with "Did you mean Curd?", worded the same way.
  - "Tomato ripe" was stopped with "Did you mean Tomato, ripe?".
  - The wording matches the document, apart from the curly apostrophe, which the conductor ruled on.

  At 1280 the card is 640 wide and the two buttons sit side by side (114 and 202 wide, 44 tall). At
  390 they stack at 308 wide each. They cannot fit side by side there: 114 + 12 + 202 = 328, against
  310 inside the card.

  Rename path: I renamed "VERIFY-A Tomatos" to "Tomatos" and got the same prompt. "It’s a different
  ingredient" opened a second question, "Keep “Tomatos” separate from Tomato?", with Go back / Keep it
  separate. After saving, the audit has INGREDIENT_UPDATED with `confirmedDifferentFrom {name:
  Tomato, kind: EXACT}` and the reason "Renamed although it looks like “Tomato”: confirmed as a
  different ingredient." I then renamed it back.

  Supplies → new "Tomatos" gets the same prompt. Through the API, Kitchen Staff creating "Curd sour"
  gets 409 KMS-400156. Close spellings are caught: "Tomatto" → Tomato, "Tomatoes ripe" → Tomato, ripe,
  "Cashews" → Cashew, "Green chillies slit" → 409. The recipe form has no inline "add an ingredient",
  so that path doesn't exist. The library import's close-match path is held on Q-11. Shots:
  `dup2-*-{1280,390}.png`, `dup2-rename-confirm-1280.png`.
- **R-DUP-3 AC (curd group) — PASS on bullets 1, 2, 4 and 5. Bullet 3 is not verified on screen.**
  Temple Admin, Ingredients → Merge duplicates. The group is proposed as "Curd, fresh / Curd, sour /
  Curd, whisked → Curd" with the notes fresh / sour / whisked. Preview showed "On hand after 1,757.44 L".
  Merge → confirmation "Merge 3 ingredients into Curd? … This can’t be undone." → "Merged into Curd."
  1. **Stock: PASS.** Before: Curd 6 L, Curd, sour 774.475 L, Curd, whisked 976.965333 L, total
     1,757.440333 L. After: Curd movements total L 1960.000 plus ML −202559.667, which is
     1,757.440333 L. Inventory shows 1,757.44 L. No movement was deleted (9 re-pointed).
  2. **Recipes: PASS.** Rave Idli → Curd, note "sour". Majjige Huli → Curd, "whisked". Majjiga →
     Curd, "fresh".
  3. **One shopping-list line: not verified on screen.** Before the merge I hand-added Curd, sour
     (5 L) and Curd, whisked (3 L), and the list showed those 2 lines. After the merge the store holds
     exactly one Curd row (8 L). The screen shows no Curd line at all, because Curd is on a live order
     (PO-2026-0029, SENT, 18 L) and the list leaves out anything a live order covers. Raising Curd's
     reorder level to 2,000 L didn't bring it back (I restored it to 15). There are no duplicate lines.
     I checked a second group (Ginger) as well: 2 lines before, 0 after, because the combined stock
     covers the need.
  4. **Supplies and prices: PASS.** Heritage Fresh Dairy's 4 supply rows became 1, keeping ₹62 and
     Preferred. The Ginger group was merged to test a price clash: VERIFY-A Onboarding Vendor had
     0.30/gm on Ginger, grated and 0.26/gm on Ginger. The preview asked "VERIFY-A Onboarding Vendor has
     a different price for two of these. Which price should it keep?" and "Merge into Ginger" stayed
     disabled until a price was chosen. I chose 0.26, and 0.26 was kept. Only one vendor stayed
     Preferred.

  **No reference left: PASS.** A query over all 13 foreign-key columns that point at ingredients
  (goods_receipt_lines, ingredient_request_lines, inventory_items, purchase_order_lines,
  recipe_ingredients, shopping_list_lines, stock_movements, vendor_supplies, ingredient_pack_sizes,
  ingredient_market_rate_history, vendor_invoice_lines, vendor_price_history, ingredient_aliases),
  plus `ingredients` itself, returns 0 for all three merged-away ids. The same query for Ginger also
  returns 0.

  **Audit: PASS.** INGREDIENT_MERGED "Merged Curd, fresh, Curd, sour, Curd, whisked into Curd."
  (onHand 1757.44), plus 3 × INGREDIENT_DELETED "Merged into Curd."

  **Aliases:** Curd's alias list is {Curd, fresh; Curd, sour; Curd, whisked}. Searching "Curd, sour"
  or "Curd, whisked" returns Curd, and creating "Curd, whisked" is stopped with 409.

  **Permission:** Kitchen Staff sees no "Merge duplicates" link, gets "Not your page" on
  /ingredients/merge, and gets 403 KMS-400021 from GET merge-proposals, POST merges/preview and POST
  merges. Shots: `dup3-curd-group-{1280,390}.png`, `dup3-ginger-preview-1280.png`,
  `dup3-ks-merge-page.png`. SQL output: scratchpad `va/pre-merge.txt`, `post-merge.txt`,
  `pre-ginger.txt`, `post-ginger.txt`.
- **R-ING-1 pack sizes — PARTIAL.**
  - **Not built:** the ingredient page and edit form have no pack sizes (held on Q-10).
  - **Checked on the vendor page:** "Sells it as → Add a pack size…" shows Pack name (optional),
    Size, and a Unit list offering only Kg and gm for a Kg ingredient.
  - **Saved and validated:** "Bag = 25 Kg" and "500 gm" saved to `ingredient_pack_sizes`.
    - A second pack of 25 Kg was refused with KMS-400157. So was 25000 gm, the same size in grams.
    - 0 was refused in the form ("Size must be more than 0"). −5 was refused by the API (400).
    - L and pieces on a Kg ingredient were refused with KMS-400013.
    - The 9th pack was refused with KMS-400158.
    - Test packs 101–106 gm were removed afterwards.
  - **"The shopping list uses them":** not verified by me (group B, R-SL-2/3). A hand-typed 60 Kg line
    came back with `buyPacks: []`, which is right: R-SL-2 says a typed amount is never re-rounded.
- **R-ING-2 vendor from the ingredient side — HELD (Q-10), backend only.**
  `GET /api/v1/vendors/supplies?ingredientId=` returns each vendor with list price, previous price and
  date, pack and Preferred. No screen uses it.
- **R-ING-3 AC — PASS.** Setup: I created "VERIFY-A Rice" (Kg, no vendor, no market rate).
  - **The value is asked for.** Inventory → Add to inventory: once the ingredient is chosen, the box
    label reads exactly "What it would cost to buy today (₹ per Kg)". It starts empty. Blank is refused
    with "…is required", 0 with "…must be more than 0". Same at 390.
  - **Pre-fill works.** For VERIFY-A Tomatos, whose preferred supply is ₹1,500 / bag of 25 Kg, the box
    pre-fills "60".
  - **Saving sets the market rate.** The first save half-failed (see D5), so the count went in from the
    item page instead: "Record what's on the shelf" → Count correction → 50 Kg. That asked for the same
    value box, blank was refused, and I entered 60. After saving: market_rate 60.0000, date 2026-09-19,
    source STOCK_TAKE, plus a history row.
  - **Kitchen Staff can't skip it either.** Through the API, a count correction without a value is 400
    KMS-400161, and so is a Spoilage correction that adds stock. From Temple Admin, 0 and −5 get
    400161 too.
  - **The report shows a real figure.** Ingredient request IR-2026-0003 (Deity Kitchen, VERIFY-A Rice
    10 Kg) → Submit → Approve → Record the issue. "Issued from the temple store", September: **Deity
    Kitchen ₹600**, which is 10 Kg × ₹60. The API for 19 Sept alone gives estimatedTotal 600 and
    ingredientsWithoutPrice 0.
  - Ingredient-page display of the market rate is not built (held on Q-10). Shots:
    `ing3-value-required-{1280,390}.png`, `ing3-count-value-required-{1280,390}.png`,
    `ing3-issued-from-store-{1280,390}.png`.
- **R-VEN-1 AC — PASS, with layout issues marked F5.** Temple Admin, new vendor "VERIFY-A Onboarding
  Vendor". In Other ingredients (246 rows), searching "ginger" gave 7 rows. I ticked Ginger (0.25),
  Ginger, grated (0.3) and Ginger, paste (no price); the status read "3 ticked"; then Save. The
  Supplies table now has all 3 ("₹250 / Kg", "₹300 / Kg", "—") and Other ingredients has 4 ginger rows
  left, the three gone. Notice "3 ingredients added to Supplies." History has 2 ONBOARDING rows.
  - **Nothing truncated:** measured after saving, at 1280 and 390 — no clipping, doc 1280/1280 and
    390/390. The only elements past `main` at 390 are inside the visually hidden table header (see D7).
  - **Pack-sold supply:** VERIFY-A Tomatos sold as "Bag = 25 Kg" at 1500 shows "₹1,500 / bag · ₹60 /
    Kg" (exact), and the entry row shows "= ₹60 / Kg" beside the box.
  - **Filter and wording:** the category filter works (Dairy → 11 rows). "Edit a row to change any of
    the three." is gone. No "Last price" wording is left in the frontend or backend user text.
  - Shots: `ven1-other-empty-{1280,390}.png`, `ven1-saved-supplies-{1280,390}.png`,
    `ven1-bag-saved-{1280,390}.png`.
- **R-VEN-2 — PASS (Q-2 provisional).** Ginger was preferred at Kalasipalya Vegetable Mandi. On the
  VERIFY-A vendor I pressed Edit on Ginger and ticked Preferred; before saving, the row reads exactly
  "Preferred (replaces Kalasipalya Vegetable Mandi)" at 1280 and at 390. After Save, the database has
  VERIFY-A t, Kalasipalya f. I restored Kalasipalya afterwards. With no current holder, the label is
  plain "Preferred".
- **R-VEN-3 arrows — PASS on the manual path. The invoice AC was not run by me** (the ₹60 → ₹64
  invoices belong to the invoice flow, group D). I edited Ginger's price 0.25 → 0.28:
  - The marker is `data-direction="up"`, class text-danger, rgb(131,74,67) = #834A43, the danger token.
  - The screenshot pixels inside the marker box are red-brown: darkest 136,82,75.
  - Hovering shows the tooltip "₹250 on 19 Sept". So does keyboard focus, at 1280 and at 390.

  Then 0.28 → 0.26: `down`, text-success rgb(56,105,68) = #386944 (pixels 56,105,68), tooltip "₹280 on
  19 Sept". A row with no earlier price has no marker. The flat dash was not observed: a re-save at the
  same price writes no history row (conductor ruling), so it only appears through invoices. The
  DESIGN_SYSTEM.md record waits on Q-4. Shots: `ven3-hover-{1280,390}.png`.
- **R-VEN-4 manual and onboarding source — PASS.** `vendor_price_history` for the VERIFY-A vendor:
  0.30 ONBOARDING (Ginger, grated), 0.25 ONBOARDING, 0.28 MANUAL, 0.26 MANUAL, all per stock unit
  (gm). The invoice source belongs to group D.

## §13 point 1: stock added without a price

- **Count corrections and the opening count ask for a value**, for any reason, whenever the count adds
  stock. Checked above, for Temple Admin and Kitchen Staff.
- **Undoing a movement doesn't ask.** `POST /api/v1/inventory/movements/{id}/compensate`
  (MANAGE_INVENTORY), in `StockMovementService.compensate`, writes an ADJUSTMENT / COUNT_CORRECTION
  that puts back stock taken out (a spoilage, an issue, a consumption) and asks no value. It restores
  stock rather than creating new stock, but it adds stock for an ingredient that may have no price.
  **For the conductor to decide.**
- **Known and by design:** deliveries (PO_RECEIPT, price comes from the invoice, R-DEL-5); donations in
  kind (DONATION_IN_KIND, deferred, §13 point 2); automatic meal-correction returns (conductor ruling).

## Defects

1. **(F5) Vendor Supplies table squeezes the Ingredient column.**
   - Measured width of the first column: 211px at 1440; 179px at 1280. With a row in edit, 107px at
     1280 ("Ginger, grated" wraps). At 1024, 45px with no row in edit.
   - Repro: Vendors → VERIFY-A Onboarding Vendor → Supplies → Edit on Ginger → tick Preferred. At 1280
     the header and names break in the middle of words: "INGRE/DIENT", "Ging/er", "VERI/FY-A/Tom/atos".
     Shot `ven2-replaces-1280.png`.
   - Also: the Other ingredients header "LEAD TIME (DAYS)" wraps to 3 lines at 1280 with no search
     typed (`ven1-other-empty-1280.png`).
2. **The pack-size error stays after the entry changes.** Vendor page → Other ingredients → search
   "VERIFY-A" → Sells it as → Add a pack size… → Bag, 25, Kg → Add pack size. Then add a second pack:
   Sack, 25 Kg → the red box says "This ingredient already has a pack of that size. … KMS-400157". Now
   change it to Box, 0 → press Add pack size: "Size must be more than 0" shows, and the old duplicate
   box is still there under it. Shot `ing1-pack-zero-error-1280.png`.
3. **The same price is written two ways.** Ingredients → Merge duplicates → a group with a price clash
   → Preview. The choice reads "₹0.30/gm · Ginger, grated", but the vendor page shows that same price
   as "₹300 / Kg" (§1 readable units; same label in every view).
4. **Add to inventory at 390 cuts off the ingredient name.** Inventory → Add to inventory at 390 wide
   → choose "VERIFY-A Rice — kept in Kg". The select shows "VERIFY-A Rice — kept": the text is 157px in
   a 125px box, because the form stays two columns at phone width. Shot `ing3-value-required-390.png`.
5. **Add to inventory can half-save.** It sends two requests: create the item, then the opening count.
   When the second failed, the page said "We couldn’t add that to your inventory. … KMS-0000", but the
   item already existed with no stock and no value. It disappeared from the Add list, so the only way
   to finish was to open the item and use "Record what's on the shelf".
   - Seen once, during an API restart (item 12d8eeda…, VERIFY-A Rice, 15:18:20Z).
   - Repro: stop the API between the two calls, or make the adjustment fail.
6. **Out-of-date wording on "Issued from the temple store".** It says "Banana, Beaten rice are left out
   until a vendor price is recorded." A market rate prices them now too (R-ING-3), so the sentence
   names only one of the two ways to fix it.
7. **Invisible focus stop at 390 on the vendor page.** The "More about Lead time (days)" hint button
   sits inside the table header, which is hidden on phones (position absolute, clip rect(0,0,0,0),
   1px). The button still takes keyboard focus (`document.activeElement` confirmed), so focus lands on
   something nobody can see.
8. **Unlabelled Preferred value on phone cards.** Vendor Supplies at 390: the card's second line reads
   "Lead time — —". The second dash is Preferred, with no label (§5 rule 6). Shot
   `ven1-saved-supplies-390.png`.

Outside group A, for B: adding Curd to the shopping list while Curd is on a live order returns 404
KMS-400030 "We couldn't find what you were looking for." The refusal is right, but the wording is wrong.

## Test data left in kms_verify

- Vendor "VERIFY-A Onboarding Vendor", with supplies Ginger, VERIFY-A Tomatos (Bag = 25 Kg, ₹1,500,
  Preferred).
- Ingredients "VERIFY-A Tomatos" (packs Bag = 25 Kg and 500 gm; shopping-list line 60 Kg) and
  "VERIFY-A Rice" (40 Kg on hand, market rate 60).
- IR-2026-0003 (issued).
- The recipe "Majjiga", imported.
- Two groups merged: curd and ginger. They can't be undone.
- A hand-added Curd shopping-list row of 8 L, hidden while PO-2026-0029 is live.
