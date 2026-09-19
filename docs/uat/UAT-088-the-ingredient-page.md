# UAT-088: The ingredient page — pack sizes, market rate, vendors and prices

| | |
|---|---|
| **Feature area** | Ingredients — one page per ingredient |
| **Technical stories** | Procurement release 2026-09-19: R-ING-1 (pack sizes), R-ING-2 (vendor from the ingredient side), R-ING-3 (market rate, on the page), R-VEN-2 (one preferred vendor), R-VEN-3 (price arrows). Tasks T-252, T-253, T-254, T-258, T-286, Q-10 |
| **Roles exercised** | Temple admin, kitchen manager, kitchen staff, volunteer (to prove the refusal) |
| **Depends on** | UAT-013 (ingredients), UAT-037 (vendors) |
| **Environment needs** | None |

## What this feature is for

A bag of rice is 25 Kg, a tin of oil is 15 L, and the store counts in Kg and L. Vendors sell in bags
and tins. Each ingredient now has one page that says how it is sold, what it costs today, and who sells
it at what price, so the shopping list, the order and the bill can all speak in bags while stock and
costing stay in Kg.

## How it is supposed to work

- Every ingredient name on **/ingredients** opens its own page. It shows the facts (Category, Stock
  unit, Ekadashi, Other names, Added on), then **Pack sizes**, **Price** and **Vendors**.
- **Pack sizes** are the ingredient's alternate units: an optional name (Bag, Tin, Pack…), a size, and a
  unit **of the same kind** as the stock unit. They show as chips such as **5 Kg** and **Bag = 25 Kg**.
  Sizes must be above zero, no two the same size, at most 8.
- **Market rate** is what the ingredient would cost to buy today, per stock unit, with its date and
  where it came from (typed in by hand, set at a stock count, or set from an invoice).
- **Vendors** lists who sells it: **Sells it as** (a pack or the stock unit), **List price** per that
  pack with the price per stock unit worked out beside it (**₹1,500 / bag · ₹60 / Kg**), **Lead time**
  and **Preferred**. A vendor can be linked from here; it is the same record the vendor's own page shows.
- Only **one vendor is preferred** for an ingredient. Ticking Preferred on a second says, before saving,
  **Preferred (replaces <first vendor>)**.
- Beside a list price, a marker shows how it moved from the previous price: a **red up arrow** if it
  rose, a **green down arrow** if it fell, a **flat grey dash** if unchanged, and **nothing** if there is
  no previous price. Hovering or tabbing to the marker shows the previous price and its date, for example
  **₹1,500 on 19 Sept**. (Red for up and green for down is the one approved exception to the colour
  rule, for price trends only.)

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` (temple admin)
- **Create the test data** (it is used again in UAT-089, UAT-090, UAT-038 to UAT-046 and UAT-092):
  - On **/ingredients**, add **UAT Bulk Rice** (category *Grains*, unit **Kg**).
  - On **/vendors**, add two vendors: **UAT Rice Traders** and **UAT Grain House** (a name is enough; the
    phone is optional).
- **Start at:** **/ingredients**

## Steps

### The page

| # | Do this | You should see |
|---|---|---|
| 1 | On **/ingredients**, press the name **UAT Bulk Rice** | Its own page, headed **UAT Bulk Rice**, with **← All ingredients** above. A card with Category *Grains*, Stock unit *Kg*, Ekadashi, Other names, Added on. Then **Pack sizes**, **Price** (reading **Market rate · not set** and a **Set market rate** button) and **Vendors** (reading **No vendor sells this yet. Link one below.**) |
| 2 | Press the **i** beside **Pack sizes**, then tab to it with the keyboard | Both times: **The pack sizes vendors sell this in. The shopping list suggests whole packs.** |

### Pack sizes

| # | Do this | You should see |
|---|---|---|
| 3 | Open the **Unit** list in the pack-size form | Only **Kg** and **gm** — units of the same kind as the stock unit. No L, no pieces |
| 4 | Pack name `Bag`, size `25`, unit Kg. Press **Add pack size** | A chip **Bag = 25 Kg** |
| 5 | Leave the name blank, size `5`, unit Kg. **Add pack size** | A chip **5 Kg**. The chips read **5 Kg · Bag = 25 Kg** |
| 6 | Name `Sack`, size `25000`, unit gm. **Add pack size** | Refused: **This ingredient already has a pack of that size.** (`KMS-400157`). The same amount under another name is still a duplicate |
| 7 | Now type size `0` and press **Add pack size** | Only **Size must be more than 0**. The earlier refusal has gone; the two never show together |
| 8 | Type `-5` and press **Add pack size** | Still only **Size must be more than 0** |
| 9 | Add sizes until there are eight (for example 1 Kg, 2 Kg, 10 Kg, 50 Kg, 100 Kg, 500 gm), then try a ninth | Refused: **An ingredient can have at most 8 pack sizes.** (`KMS-400158`) |
| 10 | Remove every size except **5 Kg** and **Bag = 25 Kg** with their **Remove** buttons | Removed. The chips read **5 Kg · Bag = 25 Kg** again |

### Market rate

| # | Do this | You should see |
|---|---|---|
| 11 | Press **Set market rate** | A box labelled **Market rate (₹ per Kg)** with **Save** and **Cancel** side by side |
| 12 | Save it blank, then with `0` | Both times: **Market rate must be more than 0**. Nothing saved |
| 13 | Type `55` and **Save** | **Market rate · ₹55 / Kg ·** *&lt;today, e.g. 19 Sept&gt;*, and under it **Typed in by hand**. The button now reads **Change market rate** |

### Vendors and prices

| # | Do this | You should see |
|---|---|---|
| 14 | Under **Link a vendor**, press **Link vendor** without choosing one | **Choose a vendor to link.** |
| 15 | Open the **Vendor** list | Active vendors that do not sell this yet, including **UAT Rice Traders** and **UAT Grain House** |
| 16 | Vendor **UAT Rice Traders**, Sells it as **Bag = 25 Kg**, List price (₹) `1500`, Lead time (days) `2`, tick **Preferred**. Press **Link vendor** | A row: **UAT Rice Traders** · Bag = 25 Kg · **₹1,500 / bag · ₹60 / Kg** · 2 days · **Preferred**. **No arrow or dash** beside the price: there is no previous one |
| 17 | Link **UAT Grain House**, Sells it as **Kg**, List price `62`, lead time `3`. Tick **Preferred** but do not press Link yet | The tick's label reads **Preferred (replaces UAT Rice Traders)** |
| 18 | Press **Link vendor** | UAT Grain House is **Preferred** and UAT Rice Traders is **no longer** preferred. Only one row carries it |
| 19 | Press **Edit** on UAT Rice Traders, tick **Preferred**, **Save** | Preferred moves back to UAT Rice Traders. Leave it there: later tests expect it |
| 20 | Press the name **UAT Rice Traders** in the table | You land on that vendor's page. Its **Supplies** table has the same row: Bag = 25 Kg, ₹1,500 / bag · ₹60 / Kg, 2 days, Preferred |
| 21 | Back on the ingredient page, **Edit** UAT Rice Traders: list price `1600`, **Save** | **₹1,600 / bag · ₹64 / Kg** and a **red up arrow** beside it |
| 22 | Hover over the arrow; then tab to it with the keyboard | Both times the tip reads **₹1,500 on** *&lt;today&gt;*. A screen reader names it **Up from ₹1,500 on** *&lt;today&gt;* |
| 23 | **Edit** again: list price `1400`, **Save** | **₹1,400 / bag · ₹56 / Kg** and a **green down arrow**; the tip reads **₹1,600 on** *&lt;today&gt;* |
| 24 | **Edit** again: Sells it as **5 Kg**, list price `280` (still ₹56 / Kg), **Save** | **₹280 / 5 Kg · ₹56 / Kg** and a **flat grey dash**, named **Unchanged from …** |
| 25 | Put it back: Sells it as **Bag = 25 Kg**, list price `1450`, **Save** | **₹1,450 / bag · ₹58 / Kg** with a green down arrow. UAT-089 and UAT-092 start from ₹1,450 |
| 26 | Try to remove the pack **Bag = 25 Kg** now | Refused: **That pack size is in use, so it can’t be removed.** (`KMS-400159`), saying a vendor sells it in that pack |

### Roles and widths

| # | Do this | You should see |
|---|---|---|
| 27 | Sign in as `ikms.kitchen-staff.5@trading4good.org` (kitchen manager). Open the page, add a pack of `1 Kg`, change the market rate to `57`, then remove the `1 Kg` pack | All allowed. **Market rate · ₹57 / Kg ·** *&lt;today&gt;* · Typed in by hand |
| 28 | Sign in as `ikms.kitchen-staff.1@trading4good.org` (kitchen staff). Change the market rate to `58` | Allowed |
| 29 | Measure the page at **1280px** and at **390px** wide (UAT-061) | Nothing cut off, no sideways scrolling. At 390 each vendor is a card and every value has its label, including **Preferred** |
| 30 | At 390, hover or tap the price arrow | The tip stays inside the screen |
| 31 | Sign in as `ikms.volunteer.1@trading4good.org` and type the page's address | **Not your page**. Nothing about the ingredient shows, even for a moment |

## It passes if

- [ ] Each ingredient opens its own page from its name, with facts, pack sizes, price and vendors.
- [ ] Pack sizes are added and removed, show as chips, and are refused when zero or less, a duplicate size, or a ninth; the unit list only offers the same kind of unit.
- [ ] The pack-size hint reads exactly as specified.
- [ ] The market rate is set here, refused when blank or 0, and shows its date and where it came from.
- [ ] A vendor can be linked from the ingredient with Sells it as, list price, lead time and Preferred, and the vendor's own page shows the same row.
- [ ] The price shows per pack and per stock unit together.
- [ ] Only one vendor is preferred, and the tick says whom it replaces before saving.
- [ ] Red up, green down, grey dash, and nothing when there is no previous price; the tip gives the previous price and date by mouse and by keyboard.
- [ ] Kitchen manager and kitchen staff can use every control; a volunteer cannot open the page.

## Watch out for

- **A price per Kg that does not match the pack.** ₹1,500 for a 25 Kg bag is ₹60 / Kg. Work one out by
  hand every time you change a price.
- **Two preferred vendors at once.** Major: the shopping list would not know whom to suggest.
- **An arrow in a colour other than red (up) or green (down).** This is the only place those colours
  mean price direction.
- There is no price-history chart. That is deliberate and comes after UAT.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT088-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
