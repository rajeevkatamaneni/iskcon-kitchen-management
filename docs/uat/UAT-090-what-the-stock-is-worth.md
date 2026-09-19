# UAT-090: What the stock is worth — the first count and the market rate

| | |
|---|---|
| **Feature area** | Inventory — stock-take value and costing |
| **Technical stories** | Procurement release 2026-09-19: R-ING-3 (market rate at stock-take, costing fallback, never ₹0), and Rajeev's answer to Q-22 (the first count on a new item is not an adjustment). Tasks T-254, T-257, T-305 |
| **Roles exercised** | Kitchen manager, kitchen staff, temple admin, volunteer (to prove the refusal) |
| **Depends on** | UAT-022 (inventory), UAT-088 (UAT Bulk Rice with its list price), UAT-067 to UAT-070 (kitchens, requests and issuing) |
| **Environment needs** | None |

## What this feature is for

The "Issued from the temple store" report showed the rice the Deity Kitchen received at **₹0**, because
nobody had ever typed a price for rice. The person counting the shelf knows roughly what a sack costs
today, so the count now asks for it. That figure becomes the ingredient's **market rate**, and costing
falls back to it when no vendor has a price, so a real figure appears instead of ₹0.

## How it is supposed to work

- **Add to inventory** asks **How much is on the shelf now**. When that is more than zero it also asks
  **What it would cost to buy today (₹ per Kg)** — per the ingredient's own stock unit.
- The box is pre-filled with the **preferred vendor's list price**, or else the **market rate**. It can't
  be blank or 0. Saving it sets the ingredient's market rate, marked **Set at a stock count**.
- Any later correction that **adds** stock asks the same question. A correction that takes stock away does
  not.
- **The first count on a new item is not an adjustment** (Rajeev, 2026-09-19): anyone who manages
  inventory — kitchen staff, kitchen manager, temple admin — can enter it, however large. Later
  corrections keep the rule that a change of more than a fifth needs a Temple Admin (UAT-025).
- Costing uses the preferred vendor's list price, then any vendor's, then the market rate. It never
  costs anything at ₹0: an ingredient with none of the three is **named** as having no known price.

## Before you start

- **Sign in as:** `ikms.temple-admin.1@trading4good.org` and add an ingredient **UAT Sona Masoori**
  (category *Grains*, unit **Kg**). Give it **no vendor and no market rate**.
- Check **UAT Bulk Rice** on its page (UAT-088): preferred vendor UAT Rice Traders at **₹1,450 / bag ·
  ₹58 / Kg**, and **not yet in inventory**. If it is already tracked, use a fresh ingredient and link it
  to a vendor the same way.
- On **/issued-from-store**, write down the **Deity Kitchen** figure for this month (or "not listed").
- **Then sign in as:** `ikms.kitchen-staff.5@trading4good.org` (kitchen manager)
- **Start at:** **/inventory**

## Steps

### The first count

| # | Do this | You should see |
|---|---|---|
| 1 | Press **Add to inventory** | Fields: Ingredient, Storage location, **How much is on the shelf now** (with a unit), **Tell me when stock drops below**, **What it would cost to buy today (₹)**, Notes. The value box is greyed, reading **Choose an ingredient first** |
| 2 | Choose **UAT Bulk Rice** | The label becomes **What it would cost to buy today (₹ per Kg)** and the box is filled in with **58** — the preferred vendor's list price per Kg |
| 3 | Press the **i** beside it | **The price of one Kg today. We fill in the preferred vendor’s list price, or else the market rate. Saving it updates the market rate.** |
| 4 | Location `Main store`, on the shelf **20** Kg, warn below **100** Kg. Clear the value box and press **Add to inventory** | Refused on the form, naming the box: **What it would cost to buy today (₹ per Kg) is required** |
| 5 | Type `0` and try again | **… must be more than 0** |
| 6 | Type `58` and press **Add to inventory** | You land on **/inventory** with the green line **UAT Bulk Rice is now in your inventory.** On hand **20 Kg**. A 20 Kg first count by a kitchen manager was **not** sent to a Temple Admin |
| 7 | Open UAT Bulk Rice's ingredient page | **Market rate · ₹58 / Kg ·** *&lt;today&gt;* and **Set at a stock count** |
| 8 | **Add to inventory** again: **UAT Sona Masoori** | The box is **empty**: no vendor, no market rate, nothing to suggest |
| 9 | On the shelf **20** Kg, value `50`, save | **UAT Sona Masoori is now in your inventory.** Its market rate reads **₹50 / Kg** · Set at a stock count |
| 10 | Sign in as `ikms.kitchen-staff.1@trading4good.org` (kitchen staff) and add any new ingredient with a count of **40** and a value | Accepted. The first count on a new item needs no Temple Admin, whoever enters it |
| 11 | Add another new ingredient with **nothing** on the shelf | The value box is not required and it saves. The question is only asked when stock is added |

### Later corrections

| # | Do this | You should see |
|---|---|---|
| 12 | As the kitchen manager, open the **UAT Bulk Rice** item and press **Adjust stock**. Type a change of `+2`, reason **Count correction** | A value box appears, **What it would cost to buy today (₹ per Kg)**, filled with **58** |
| 13 | Change it to `-2` | The value box goes away. Taking stock off asks no price |
| 14 | Put it back to `+2`, value `60`, save | Stock is **22 Kg**. The ingredient's market rate now reads **₹60 / Kg** · Set at a stock count |
| 15 | As kitchen staff, adjust UAT Bulk Rice by `+10` (more than a fifth of 22) | Refused: **This adjustment is large enough that a Temple Admin has to approve it.** (`KMS-400025`). The first-count exemption is for a new item only. Then adjust by `-2`, reason **Count correction**, to bring it back to **20 Kg**: UAT-038 counts on 20 |

### Costing no longer says ₹0

| # | Do this | You should see |
|---|---|---|
| 16 | As kitchen staff, raise an ingredient request from **Deity Kitchen** for **UAT Sona Masoori 10 Kg** (UAT-068). As the temple admin, approve it (UAT-069). As the kitchen manager, record it as issued (UAT-070) | Issued. UAT Sona Masoori has no vendor at all |
| 17 | Open **/issued-from-store** for this month | Deity Kitchen is **exactly ₹500 more** than you wrote down (10 Kg × ₹50, the market rate). UAT Sona Masoori is **not** among the ingredients with no known price |
| 18 | Read the notice above the table | If any ingredient still has no price, it is named: *… left out until it has a vendor’s list price or a market rate.* If none, the heading reads **Estimated, materials only — from list prices, or the market rate** |
| 19 | Sign in as `ikms.volunteer.1@trading4good.org` and type **/inventory/new** | **Not your page** |

## It passes if

- [ ] A first count above zero asks **What it would cost to buy today (₹ per <unit>)**, pre-filled from the preferred vendor's list price, else the market rate.
- [ ] The value can't be blank or 0, and saving sets the market rate as **Set at a stock count**.
- [ ] A first count on a new item is accepted from kitchen staff and the kitchen manager, whatever its size.
- [ ] A correction that adds stock asks the value; one that removes stock does not.
- [ ] A later large correction by kitchen staff is still refused (`KMS-400025`).
- [ ] An ingredient priced only by its market rate is costed at it on "Issued from the temple store", never at ₹0.

## Watch out for

- **Any ₹0.** An ingredient that has a market rate but shows ₹0, or is named as having no price, on
  Issued from the temple store, Cost per serving or Today. Major.
- **An item removed from inventory and added back** is not new: it has stock history, so the 20% rule
  applies to its count. If kitchen staff can re-add a removed item with a large count, record it.
- **Gifts of goods (UAT-028) do not ask a value yet.** Valuing donated food at the market rate is
  deferred to the donations work. Until then, ingredients that came only as gifts may be named as having
  no known price. That is expected, and the notice must keep saying it aloud.
- `KMS-400161` — *Enter what it would cost to buy this today.* — is the server's answer behind the form.
  You should only see the form's own words.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT090-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
