# UAT-089: Onboarding a vendor's price list

| | |
|---|---|
| **Feature area** | Vendors — what they sell, in what pack, at what price |
| **Technical stories** | Procurement release 2026-09-19: R-VEN-1 (bulk onboarding table, "List price"), R-VEN-2 (one preferred vendor), R-VEN-3 (arrows), R-VEN-4 (a price typed by hand), and Rajeev's answer to Q-3 (a vendor named "Market"). Tasks T-252, T-256, T-258 |
| **Roles exercised** | Temple admin, kitchen manager, volunteer (to prove the refusal) |
| **Depends on** | UAT-037 (vendors), UAT-088 (UAT Bulk Rice and the two UAT vendors) |
| **Environment needs** | None |

## What this feature is for

When the temple takes on a vendor, the vendor hands over a price list: two hundred lines. Until now
each line had to be added one at a time on a form nobody found, and only 4 of 232 ingredients ever got a
price. The vendor page now carries a second table, **Other ingredients**, where the admin ticks what the
vendor sells, types the price beside it, and saves the lot at once.

## How it is supposed to work

- The vendor page keeps its **Supplies** table: Ingredient, Sells it as, **List price**, Lead time,
  Preferred, and **Edit** on each row. The old column name "Last price" is gone everywhere.
- Below it, **Other ingredients** lists every ingredient this vendor does not supply yet, with a search
  box, a category filter, and on each row a tick, **Sells it as**, **List price (₹)** per that pack,
  **Lead time (days)** and **Preferred**. The price per stock unit is worked out beside the price.
- **Save** moves the ticked rows up into Supplies. A price is optional.
- **Sells it as** is one of the ingredient's pack sizes or its stock unit. **Add a pack size…** at the
  foot of that list defines a new pack there and then, and it is saved on the ingredient (UAT-088).
- One preferred vendor per ingredient: a tick that takes the preference from another vendor says so
  before saving, **Preferred (replaces <vendor>)**.
- Every price change is kept in the price history and shown with the arrow beside it (UAT-088).
- **Cash buys at the market:** there is no separate market list. Rajeev's answer (2026-09-19) is to add a
  vendor named **Market** and buy from it like any other vendor.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Add these ingredients** on **/ingredients** (UAT-038 onwards uses them):

| Ingredient | Category | Unit | Pack sizes (on its page, UAT-088) |
|---|---|---|---|
| UAT Groundnut Oil | Oils (or any) | L | Tin = 15 L |
| UAT Curry Leaves | Vegetables | gm | none yet |
| UAT Coconut | Vegetables | pieces | none |
| UAT Tea | Beverages (or any) | gm | 250 gm, 500 gm, 1 Kg — and **do not link it to any vendor**; UAT-038 does that |
| UAT Jeera | Spices | gm | none — and **do not link it to any vendor**; UAT-038 does that |

- **Start at:** **/vendors** → **UAT Rice Traders**

## Steps

### The two tables

| # | Do this | You should see |
|---|---|---|
| 1 | Read the **Supplies** section | One row, **UAT Bulk Rice** · Bag = 25 Kg · **₹1,450 / bag · ₹58 / Kg** · 2 days · Preferred, with an **Edit** button. The column says **List price**. The sentence *Edit a row to change any of the three.* is **not** there |
| 2 | Search the whole page for **Last price** | Not found |
| 3 | Read the **Other ingredients** section | A search box (placeholder **Ingredient name**), a category list starting **All categories**, a **Save** button with **None ticked** beside it, and a table of every ingredient this vendor does not supply. UAT Bulk Rice is not in it |
| 4 | Type `UAT` in the search box | Only the UAT ingredients remain: UAT Groundnut Oil, UAT Curry Leaves, UAT Coconut, UAT Tea, UAT Jeera |
| 5 | Choose the category **Spices** | Only UAT Jeera. Set it back to **All categories** |

### Tick three, price two, save

| # | Do this | You should see |
|---|---|---|
| 6 | On **UAT Groundnut Oil**: tick it, Sells it as **Tin = 15 L**, List price (₹) `2400`, Lead time `1` | Beside the price, the price per litre worked out, ending **= ₹160 / L**. The counter reads **1 ticked** |
| 7 | On **UAT Coconut**: tick it, Sells it as its stock unit (pieces), List price `35`, lead time `1` | **2 ticked** |
| 8 | On **UAT Curry Leaves**: tick it, and open **Sells it as** | The stock unit and, last, **Add a pack size…** |
| 9 | Choose **Add a pack size…**, name `Bundle`, size `500`, unit gm, **Add pack size** | **Bundle = 500 gm** is now chosen in Sells it as. Leave the price **blank**, lead time `1`. **3 ticked** |
| 10 | Press **Save** | A green line: **3 ingredients added to Supplies.** All three are in Supplies; none of them is left in Other ingredients (search `UAT` again to check) |
| 11 | Read the new Supplies rows | UAT Groundnut Oil **₹2,400 / tin · ₹160 / L**; UAT Coconut **₹35 / piece**; UAT Curry Leaves with **no price** and nothing beside it. No arrows: none has a previous price |
| 12 | Open **UAT Curry Leaves**' own page (UAT-088) | Its pack sizes now include **Bundle = 500 gm**. The pack made on the vendor page was saved on the ingredient |
| 13 | Back on the vendor page, **Edit** UAT Curry Leaves: Sells it as **Bundle = 500 gm**, List price `30`, **Save** | **₹30 / bundle · ₹60 / Kg**. No arrow: there was no earlier price |
| 14 | **Edit** UAT Groundnut Oil: list price `2550`, **Save** | **₹2,550 / tin · ₹170 / L** and a **red up arrow**; its tip reads **₹2,400 on** *&lt;today&gt;* |
| 15 | **Edit** it back to `2400` | A **green down arrow**, tip **₹2,550 on** *&lt;today&gt;* |

### One preferred vendor

| # | Do this | You should see |
|---|---|---|
| 16 | On **UAT Rice Traders**, **Edit** UAT Groundnut Oil, tick **Preferred**, **Save** | UAT Groundnut Oil is now preferred at UAT Rice Traders. It had no preferred vendor before, so the tick's label was plain **Preferred** |
| 17 | Open **UAT Grain House**. In Other ingredients, tick **UAT Groundnut Oil** and tick its **Preferred** | The label now reads **Preferred (replaces UAT Rice Traders)**, before anything is saved |
| 18 | Untick both and leave the page without saving | Nothing changed. Later tests expect UAT Rice Traders to stay preferred for UAT Groundnut Oil and UAT Bulk Rice |

### A vendor named Market

| # | Do this | You should see |
|---|---|---|
| 19 | On **/vendors**, press **Add a vendor**, name it `Market`, no phone. Save | **Market** is in the list, **Active** |
| 20 | On Market's page, tick **UAT Curry Leaves** and **UAT Coconut** in Other ingredients with **no prices**, and **Save** | **2 ingredients added to Supplies.** A cash buy at the market now goes through the same order, delivery and invoice as any vendor (UAT-045, UAT-046 use it) |

### Widths and roles

| # | Do this | You should see |
|---|---|---|
| 21 | On UAT Rice Traders, tick three rows in Other ingredients and measure the page at **1280px** and **390px** | Nothing is cut off or truncated at either width and the page never scrolls sideways. At 390 each row is a card and every value is labelled, including **Preferred**. Untick the rows |
| 22 | Sign in as `ikms.kitchen-staff.5@trading4good.org` (kitchen manager). Open UAT Grain House, tick **UAT Coconut** at `36`, **Save** | **1 ingredient added to Supplies.** Allowed |
| 23 | Sign in as `ikms.volunteer.1@trading4good.org` and type a vendor page's address | **Not your page** |

## It passes if

- [ ] The Supplies table stays, says **List price**, and the sentence *Edit a row to change any of the three.* is gone.
- [ ] Other ingredients lists every ingredient not supplied, with a working search and category filter.
- [ ] Ticking three, pricing two and pressing Save moves all three into Supplies and out of Other ingredients.
- [ ] The price per stock unit is shown beside the pack price, before and after saving.
- [ ] A pack defined with **Add a pack size…** is saved on the ingredient.
- [ ] A price is optional; a supply saved without one shows no price and no arrow.
- [ ] A tick that takes the preference from another vendor reads **Preferred (replaces …)**.
- [ ] A changed list price shows the arrow and the previous price.
- [ ] A vendor named **Market** can be added and given supplies like any other.
- [ ] Nothing is truncated at 1280 or 390.

## Watch out for

- **A row that is saved but still shows in Other ingredients**, or vanishes from both tables. Search for
  it by name to be sure. Major.
- **A price saved against the wrong pack.** ₹250 typed beside **500 gm** is ₹500 / Kg; if the page says
  ₹250 / Kg, the price was read against the wrong unit. Major.
- **About ~200 rows.** With the real catalogue, scroll the Other ingredients table at 390px and note
  whether it is usable. That is a finding worth writing down either way.
- **Known, left for Rajeev:** on an ingredient counted in gm and sold in gm (no pack), the List price box
  is per gm, while the market rate is written per Kg. The steps above avoid it by selling in packs. If you
  meet it, record what you typed and what the page then showed.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT089-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
