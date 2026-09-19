# Procurement — requirements (PO → delivery → invoice → payment)

**Status:** approved in discussion by Rajeev, 2026-09-19. **Binding** for the session that builds it.
**Source:** Rajeev's conversation with Claude on 2026-09-19. The running decision record is
`docs/work/PROCUREMENT-REDESIGN.md`, and this document supersedes it wherever they differ.
**Mocks (specifications, not suggestions):** `docs/work/mocks/dev-po.page.tsx`,
`dev-deliveries.page.tsx`, `dev-invoices.page.tsx`. To view one, copy it temporarily to
`frontend/app/<name>/page.tsx` and open `http://localhost:3000/<name>`. **Delete it from `app/` before
any commit or deploy** (`deploy.sh` ships the working tree). Where a mock and this document differ,
this document wins. Where this document is silent, the mock wins. Where both are silent, ask (§12).

**The mocks predate the alternate-units decision** (bags, tins, packs). They show plain Kg/L. Where a
line is sold in packs, follow R-ING-1, R-VEN-1, R-SL-3 and R-INV-4, keeping the mock's layout and
styling. **The vendor onboarding table (R-VEN-1) has no mock:** build it in the same style as the
mocks' tables, then ask Rajeev to review it on the Desk before it is called done.

Every requirement has an ID (`R-…`) and acceptance criteria (`AC`). The verifying agents check
the ACs, and nothing else counts as done.

---

## 0. Why this exists (read first)

The "Issued from the temple store" report showed the rice the Deity Kitchen received at **₹0**.
Tracing it showed that the app has only one price per vendor per ingredient (`vendor_supplies.last_price`),
typed by hand on a vendor page nobody finds. Only 4 of 232 ingredients had any price. Invoices hold only
a total, with no items. The price paid at a delivery is hidden, and not shown again afterwards. Nothing ties
an invoice to what was delivered. This work makes prices come from the bills the temple actually pays,
item by item, and ties **order → delivery → invoice → payment** together with real database links.

---

## 1. Rules that apply to everything here

- `CLAUDE.md` top sections, especially **"Verify, never estimate — MANDATORY"** and the rules under
  it. Measure every visual claim with DOM geometry or pixel analysis, or write "not verified".
- `docs/DESIGN_SYSTEM.md` **v1.13**:
  - controls and pills use `rounded-control`
  - **colour rule:** amber = warning, red = serious/blocking, green = only confirming the user's
    own action or input is right, blue (info) = "note this"
  - **table rule §5:** every column left-aligned (headers too), nothing truncated, spare width
    shared evenly between the gaps, tables become cards below 1024px
  - secondary buttons are **style E**
  - every button has the press effect
  - **no new row when things fit side by side**
- Copy: the `.claude/skills/ux-writing` skill with `better-writing`. Use the exact wording given
  here wherever wording is given.
- Tenant isolation: every new table calls `enable_tenant_rls()`, and `tenant_id` never comes from a
  request. Endpoints declare a **permission**, never a role (`RolePermissions.java`). Every new
  user-facing failure gets a permanent `KMS-nnnnnn` code (`ErrorCode.java`, `ErrorCodeTest`).
- Quantities are shown in readable units: kg/L from 1,000 g/ml up (existing `format.ts` helpers,
  backend `Unit.baseFactor()` / `InventoryUnits`). Add no new conversion tables.

---

## 2. Scope

**In:** duplicate-ingredient prevention (§9A); market rate (R-ING-3); shopping list amounts and pack sizes; linking ingredients to vendors; list price and price
history with arrows; creating and viewing a purchase order; the new Deliveries screen and permission;
invoices with item lines, uploads and totals; paying from the invoice with proof; removing the
Payments page.

**Out (do not build):**
- the price trend chart (it comes after UAT, once real data exists)
- the 1–3 month data simulation (a separate task, after this)
- a Storekeeper role
- market/cash purchase lists (open question Q-3)
- invoice OCR

---

## 3. Data model (database foreign keys)

Keep all existing links. They are verified in migrations V24, V26–V28 and V40. Add:

| New table / column | Links | Notes |
|---|---|---|
| `ingredient_pack_sizes` | → ingredients | The ingredient's **alternate units**: an optional name (Pack, Bag, Tin, Bundle, Box, Bottle…) and the quantity it holds in the ingredient's canonical unit (for example "Bag = 25 Kg", or a plain "500 g"). Positive, no duplicates, at most 8, same unit family |
| `ingredients` new columns | — | **Market rate**: ₹ per stock unit, with its date and source (`STOCK_TAKE` / `INVOICE` / `MANUAL`), recorded in the price history as well |
| `vendor_supplies` new columns | → ingredient_pack_sizes (nullable) | **"Sells it as"**: the pack this vendor sells the ingredient in, and the **price per pack**. The per-canonical-unit list price is derived from it, never typed twice |
| `vendor_price_history` | → vendor_supplies (vendor+ingredient), → vendor_invoice_lines (nullable, for prices entered by hand at onboarding) | One row per price change: price per canonical unit, effective date, source (`ONBOARDING` / `MANUAL` / `INVOICE`), who set it |
| `vendor_invoice_lines` | → vendor_invoices, → goods_receipt_lines (nullable, for direct invoices), → ingredients (nullable for one-off lines) | Billed quantity, unit, line amount (₹, required), derived rate; description for one-off lines |
| `vendor_invoice_deliveries` (join) | → vendor_invoices, → goods_receipts | An invoice bills one or more deliveries |
| `vendor_invoices` new columns | — | Sub total, GST, other charges (+ note), discount, grand total; an upload is required |
| `attachments` (or reuse the existing document storage) | → vendor_invoices / invoice_payments | Bill upload; payment proof; cash signed note; photo of the person who took cash |
| `invoice_payments` new columns | — | For cash: `received_by_name` (required) |
| permission `RECEIVE_DELIVERIES` | `RolePermissions.java` | Temple Admin and Kitchen Manager; Kitchen Staff optional (Q-1) |

- The **shopping list is derived** (V121) and stored nowhere, so nothing points back to it. That is by design.
- Rename in the UI only: `vendor_supplies.last_price` is shown as **"List price"**. Whether the column
  is renamed is the builder's choice, recorded in the proof.
- **AC-3.1:** each new table has RLS enabled, and `RowLevelSecurityIT`-style tests run as the
  unprivileged role and show another tenant's rows are invisible.
- **AC-3.2:** a migration IT proves existing data survives unchanged: POs, receipts, invoices, payments,
  on-hand stock.

---

## 4. Shopping list

**R-SL-1: Readable amounts.** The "Suggested" box and all quantities on the list show kg/L from 1,000 up
(2792 gm shows as kg), and what is typed is read in the unit shown beside the box.
- **AC:** no quantity ≥ 1,000 g/ml shows in g/ml anywhere on the list, the PO it creates, the PO PDF or the WhatsApp text.

**R-SL-2: Buying amount.** The suggested amount is rounded **up**, never down:
- **With pack sizes (R-ING-1):** the fewest packs covering the need with the least left over. Ties go to
  fewer packs. Record the exact rule, with examples, in the proof. Example: tea 416 g with
  {250 g, 500 g, 1 kg} → **1 × 500 g**.
- **Without pack sizes:** round up to a step:
  - under 1 kg/L: next 50 g/ml
  - 1–10 kg: next ½ kg
  - 10–100 kg: next 1 kg
  - over 100 kg: next 5 kg
  - pieces: next whole number
  - Example: 2792 g → **3 kg**.
- The step table lives in **one place**, commented as Rajeev's rule of 2026-09-19.
- A quantity edited by hand is never re-rounded.
- A reverted, unverified partial implementation of the step rounding is kept in
  `docs/work/reference/` (`t243-partial.patch`, `BuyingAmount.java`, `BuyingAmountTest.java`). It can be reused, but must be re-verified.
- **AC:** unit tests at each boundary (999 g, 1,000 g, 10 kg, 100 kg exactly), a test proving it never
  rounds down, and pack-size examples. On screen, Kalasipalya's curry leaves read "3 Kg", with no "2792".

**R-SL-3: Order in the vendor's pack.** When the vendor's supply has a "Sells it as" pack, the suggested
amount is in that pack (100 Kg short, sold as Bag = 25 Kg → **4 bags**), and the PO line, the PDF and the
WhatsApp text read "4 × Bag (25 Kg)". The line also keeps its stock-unit quantity (100 Kg) for
stock and costing. Without a vendor pack, the ingredient's generic pack sizes or the step table apply
(R-SL-2).

**R-SL-4: "No vendor yet" lines.**
- Each line gets a **Choose vendor** dropdown.
- Choosing a vendor moves the line into that vendor's tile.
- A **"Use this vendor next time"** tick, on by default, creates the vendor↔ingredient link (R-VEN-1).
- Bulk: tick several lines, then **"Order these from [vendor]"**.
- **AC:** after choosing, the line appears under the vendor with a Generate purchase order button. On
  reload with the tick on, it stays there.

---

## 5. Ingredients and vendors

**R-ING-1: "Sold in": pack sizes and alternate units on each ingredient.** This follows the standard
pattern (Tally "alternate units", Odoo units of measure, SAP alternative units): **one stock unit per
ingredient** (what is counted and cooked, such as Kg), plus **conversions defined once** (Bag = 25 Kg,
Tin = 15 L, Bundle = 10 pieces, or plain sizes like 500 g). Everything downstream (stock, costing,
price history) runs in the stock unit. (Rajeev, 2026-09-19.)
- **Where:** the ingredient page and edit form, **per ingredient**, not per vendor (Rajeev).
- **How it's entered:** add or remove sizes as an optional name (Pack, Bag, Tin, Bundle…), a number and a unit from the same family.
- **How it's shown:** chips such as "250 g · 500 g · 1 Kg · Bag = 25 Kg".
- **Hint (ⓘ):** "The pack sizes vendors sell this in. The shopping list suggests whole packs."
- **AC:** the sizes persist and are validated (positive, same family, no duplicates, at most 8), and the shopping list uses them.

**R-ING-3: Market rate** (Rajeev, 2026-09-19).
- Each ingredient has a **market rate**: what it would cost to buy today, per stock unit. It's shown and
  editable on the ingredient page ("Market rate · ₹60 / Kg · 12 Sept").
- **At stock-take:** "Add to inventory" (the "How much is on the shelf now" form) and any count
  correction that *adds* stock gain a required box, **"What it would cost to buy today (₹ per Kg)"**.
  It's pre-filled from the preferred vendor's list price, else the market rate. **It can't be blank or
  0.** Saving it sets the market rate.
- **Every saved invoice line** also refreshes the ingredient's market rate (the same step as R-VEN-4).
- **Costing fallback:** `BasketCostingService` uses the preferred vendor's list price, then any vendor's,
  then the **market rate**, and never ₹0. "n ingredients without a price" then only counts ingredients
  with none of the three.
- Donated food will be valued at the market rate when the donations work is built. It's deferred and
  not in this build.
- **AC:** adding rice to inventory with no vendor price asks for the value. After saving, "Issued from the
  temple store" shows a real ₹ figure for rice issued to the Deity Kitchen, not ₹0.

**R-ING-2: Vendor from the ingredient side.** The ingredient page has a vendor dropdown that links the
ingredient to a vendor, with List price, Lead time and Preferred. This creates the same
`vendor_supplies` row as the vendor page.

**R-VEN-1: Vendor page, bulk onboarding.**
- **Keep** the existing table of this vendor's supplies.
- **Remove** the text "Edit a row to change any of the three."
- **Rename** "Last price" to **"List price"** everywhere, including the shopping list, the PO and costing labels.
- **Add** a second table below it, **"Other ingredients"**: every ingredient this vendor does not supply,
  each row with a tick, **Sells it as** (one of the ingredient's pack sizes, or the stock unit itself),
  **List price (₹)** per that pack, **Lead time (days)** and **Preferred**, entered inline. The supplies
  table shows and edits the same columns. The price per stock unit is shown beside it, derived
  ("₹1,500 / bag · ₹60 / Kg"). A new pack can be defined from here and is saved on the ingredient (R-ING-1).
- It needs a **search box** and a **category filter** (about 200 rows).
- **Save** moves the ticked rows into the supplies table.
- List price is optional. When a vendor is onboarded, they give their list and the Temple Admin types the prices.
- **AC:** tick 3 ingredients, fill two prices, press Save, and all 3 are in the top table and gone from
  the bottom one. Measured at 1280 and 390, nothing is truncated.

**R-VEN-2: One preferred vendor per ingredient.** Ticking Preferred on vendor B for an ingredient that is
preferred at vendor A makes B preferred and A not, and the row says so before saving:
**"Preferred (replaces A)"**. This is Claude's recommendation; confirm with Q-2.

**R-VEN-3: Price history and arrows.**
- Every change to a list price writes a `vendor_price_history` row.
- Beside the List price there is a marker:
  - **up arrow in red** when the price rose from the previous one
  - **down arrow in green** when it fell
  - **a flat dash** in the neutral colour when it is unchanged
  - **nothing** when there is no previous price
- The red/green pair is an **approved exception** to the colour rule, for price trends only (Q-4 confirms).
  Record it in DESIGN_SYSTEM.md.
- Hovering the marker, or focusing it by keyboard, shows the previous price and date.
- **No trend chart** (out of scope).
- **AC:** entering two invoices at ₹60 then ₹64 shows ₹64 with a red up arrow, and the tooltip shows "₹60 on <date>".

**R-VEN-4: Where list price comes from.** A price is set by:
1. typing it on the vendor page or the ingredient page (source `MANUAL` / `ONBOARDING`), and
2. **each saved invoice line** (source `INVOICE`). The line's derived **rate** becomes the vendor's
   list price for that ingredient: per pack when the supply is sold in packs, and always stored in
   the history per stock unit too.

The price is **no longer taken from the delivery.** Delivery price entry is removed (R-DEL-5).
Costing (`BasketCostingService`) keeps reading the list price (preferred vendor first).

---

## 6. Purchase orders

**R-PO-1: The button.** "Raise an order" becomes **"Create a purchase order"**. It goes **straight to
the create form**, and the intermediate page with only a vendor dropdown and "Add vendor" is removed.

**R-PO-2: The create form header.**
- A **Vendor** dropdown sits **beside "Needed by"**, on one row.
- The **"Deliver to"** box is **removed**.
- **"Note for the vendor"** is an expandable textarea that grows as the user types.
- The lines section is labelled **"Items"**.
- **No "Nothing on this order yet."** text when it's empty.

**R-PO-3: Adding items, design A** (`dev-po` mock, design A):
- An inline table with columns Item · Quantity · Unit · Expected price (₹) · Line total · remove.
- There is always one empty row at the bottom. Filling it adds another.
- **The Item cell is a type-ahead combobox** (accessible: combobox/listbox, arrow keys, Enter, Escape).
  - Typing lists the **chosen vendor's items first**, with their list price ("Tomato, ripe · ₹32/Kg").
  - Then other ingredients follow, under an "Other ingredients" divider.
  - Picking one fills the Unit and Expected price.
- **A one-off item:** if the typed text matches nothing, the last dropdown line reads
  **"Add '<text>' as a one-off item"**. Picking it creates a free-text line, with the unit (a small select) and price typed by hand.
- **Hint above the table:** "Start typing an ingredient, or type anything to add a one-off item."
- **AC:** both paths work by keyboard and by mouse. Line totals and the order total update as quantities are typed. The dropdown isn't clipped at 390.

**R-PO-4: An existing order's page.**
- **Remove** the line "Fixed when the order was sent".
- Replace the two tables (items, and deliveries received) with **one merged table**:
  - columns: **Item · Ordered · Delivered · Rejected on delivery · Returned**, plus actions
  - each item has a collapsed **"▸ N deliveries"** history (R-DEL-4)
  - it shows the order's full order and delivery history in one place
- **"Rejected at the gate" becomes "Rejected on delivery"** everywhere.
- Deliveries are **recorded on the Deliveries screen** (R-DEL-2). This page shows the history and links
  across with "Record a delivery on the Deliveries screen →", or opens the same panel filtered to this
  order. The builder chooses; the choice must not create a second recording code path.
- Returns to the vendor must remain possible. The mock lacks it; keep the existing "Return to vendor" action on the merged table.
- **AC:** an order with two partial deliveries shows the right Delivered totals and two history lines, and no separate "Deliveries received" table remains.

---

## 7. Deliveries (new screen)

**R-DEL-1: Menu and access.**
- A **"Deliveries"** menu item sits in the Ordering group, right after Purchase orders.
- The page requires the new permission **`RECEIVE_DELIVERIES`**: Temple Admin and Kitchen Manager, and optionally Kitchen Staff (Q-1).
- **No prices anywhere on this screen.**

**R-DEL-2: The three tabs** (`dev-deliveries` mock):
- **Expected:** sent orders not fully delivered, grouped by vendor, with what is still to come and the needed-by date.
- **Partly delivered:** what's still owed, and for how long.
- **Received:** a dated history of date, vendor, items, received by, rejected and returned.
- A **small summary strip** at the top, for example "3 vendors due today · 1 overdue · 2 partly delivered", in the existing stat style and not dominant.

**R-DEL-3: Recording a delivery.**
- There is **one "Record a delivery" button per vendor**, in both Expected and Partly delivered.
- It lists **everything that vendor still owes across all their open orders**: the item, the order number in small secondary text, and the amount still to come, pre-filled as the amount expected.
- Per line: **Received now**, **Rejected on delivery** plus a reason, and **Expiry** (for perishables).
  Quantities are entered in the unit the line was ordered in ("4 bags"), and stock receives the
  converted stock-unit amount (100 Kg).
- An **"Everything arrived"** shortcut fills Received = still to come.
- Saving creates the goods receipts and lines per order, as the existing `ReceivingService` does. Stock movements and batches behave as today.
- It shows a green confirmation (the user's own action worked).
- Rejected goods stay owed.
- The rest of a partial delivery is recorded **the same way**. Nothing new to learn.

**R-DEL-4: Delivery history per item.**
- A collapsed line reads **"▸ N deliveries"**. Opened, each part appears in date order as
  `12 Sept · 30 Kg received · 2 Kg rejected (spoiled) · Received by: Karuna Murti Das`.
- **No order number** appears in these lines.
- A closing line reads **"Received 30 of 50 Kg ordered"**, or when complete
  **"Received 50 of 50 Kg ordered · complete 15 Sept"**.
- It uses the same component on Partly delivered, Received and the PO page.
- It is keyboard accessible (`aria-expanded`), and visually quiet.

**R-DEL-5: Dates and price.**
- Dates always show as dates ("19 Sept"). **Today's gets a blue "Today" pill** in the info tone, the same component and size as the pill for late ones. **Late ones get an amber pill.** The word "Today" never replaces the date.
- Remove **"Price paid"** from delivery recording. Price belongs to the invoice (R-VEN-4).

**AC-DEL:**
- Record a partial delivery, then the rest. The item completes, and its history shows both parts with the exact wording above.
- A user without `RECEIVE_DELIVERIES` can't see the menu item, and the API refuses them.
- Measured at 1280 and 390: nothing is truncated and nothing scrolls sideways.

---

## 8. Invoices

**R-INV-1: The button and form.** "Raise…" becomes **"Create an invoice"**, which opens the form directly.

**R-INV-2: Upload, mandatory.**
- "Scan reference" becomes an **upload**, accepting image or PDF, and opening the camera on a phone.
- Hint: **"Upload a copy of the bill — a photo, PDF or scan."**
- It's **required**, using the standard required message.
- Store it with the app's existing document storage. It isn't a text reference.

**R-INV-3: Linked to deliveries** (vendors usually bill **per delivery**, Rajeev):
- After choosing the vendor, pick **the delivery or deliveries** being billed, for example
  "PO-2026-0044 · delivered 12 Sept · received by Govinda Das". Only deliveries not yet billed are offered.
- The lines are **pulled from those deliveries and locked**: no adding and no removing.
- **Direct invoices** (no order) add items by hand, with the same combobox as R-PO-3.

**R-INV-4: The items table** (`dev-invoices` mock, **design A**):
- Columns: Item · Ordered · Delivered · **Billed qty** · **Amount (₹, required)** · **Rate** (read-only,
  calculated as Amount ÷ Billed qty and shown like "₹100 / Kg").
- Billed qty defaults to the delivered quantity. An item not billed stays at **0**.
- **Billed more than delivered** gives an amber warning on the line: **"Billed 50 Kg, 45 Kg delivered"**.
- **Billed in packs:** lines are billed in the unit the order used ("4 × Bag (25 Kg)", Amount ₹6,000). The
  rate shows per pack **and** per stock unit ("₹1,500 / bag · ₹60 / Kg"). If a bill uses a unit the
  ingredient doesn't know yet, it's defined on the spot as a new pack on the ingredient (R-ING-1)
  before the line can be saved. A price never floats untied to a unit.

**R-INV-5: Totals, a bill-style block right-aligned like a real bill.**
- **Sub total** (the sum of line amounts) · **GST** · **Other charges** (plus a note) · **Discount** · **Grand total** (typed from the bill).
- Figures are tabular, and all end on one right edge.
- These labels are used everywhere.
- **Check:** Sub total + GST + Other charges − Discount must equal the Grand total.
  - When it matches: a **green tick** beside the Grand total, with the accessible name and tooltip "Adds up to the grand total", and **no sentence**.
  - When it doesn't: one short red line under the Grand total, **"Adds up to ₹1,030, not ₹1,000"**, and **saving is blocked**.
- Anything the vendor bills that isn't on the delivery goes in **Other charges with its note**, or on a separate direct invoice.
- The shared tooltip component ran off-screen beside the Grand total in the mock. Fix the shared component, and don't work around it locally.

**R-INV-6: Saving an invoice** writes its lines, links its deliveries, and updates each billed ingredient's list price and price history (R-VEN-4).

**R-INV-7: An existing invoice's page** (mock design D):
- Summary, the items table, the totals block, **"Invoiced vs received"**, the bill thumbnail (opens the file), and **Payments**.
- The existing "difference" (`withVariance`) is recomputed from the invoice lines.
- **Allowed differences from mock D** (Rajeev left it to Claude on the Desk, Q-19, 2026-09-19; Claude kept all four): the existing **Record a credit note**, **Void this bill** and per-payment **Reverse** actions stay; the summary keeps a **"Delivery billed"** line; the items heading reads **"Billed qty"**, as on the form.

**R-INV-8: The Invoices list gains filters.** **Unpaid · Due this week · 1–30 days overdue · 31+ days
overdue**, with the **total owed** at the top. These replace the Payments page.

---

## 9. Payments

**R-PAY-1: Pay from the invoice.**
- A **"Pay this invoice"** button on the invoice page opens the payment form inline. Its amount defaults to what's left; it also has date, method and reference fields.
- The button is shown **only to people allowed to pay**: the same permission the Payments page used, which is the Temple Admin today. **Who can pay must not widen.**

**R-PAY-2: Proof is mandatory.**
- **UPI, bank transfer or cheque:** one upload, with the hint "Upload proof of payment — a receipt, UPI screenshot or bank confirmation." It's required.
- **Cash:** three required fields:
  - **Received by** (a name)
  - **a photo of the signed note**, with the hint "A note signed by the person who received the cash, e.g. 'Received ₹1,030 in cash from ISKCON South Bengaluru'"
  - **a photo of the person who took the cash**, with the hint "Their ID card, or a photo of them, so we can recognise who took the money."
- There is **no ID-type field.** Staff are told this in training (Rajeev).
- Changing the method swaps these fields.

**R-PAY-3:** in the payment list, **"Recorded by" becomes "Paid by"**. Each payment row shows thumbnails of its proof (two for cash).

**R-PAY-4: Remove the Payments menu item and the `/money` page.**
- Its only job (unpaid invoices grouped by age, and the total owed) now lives in R-INV-8.
- The route redirects to `/invoices?filter=unpaid`.
- **AC:** nothing links to `/money`, and the menu has no Payments item.

---

## 9A. No duplicate ingredients (in scope: Rajeev, 2026-09-19)

**Why:** duplicate ingredients split stock, prices and shopping-list lines ("Curd", "Curd, fresh", "Curd,
sour", "Curd, whisked"), so everything downstream is dirty. **The root cause:** the recipe library import
turned *preparations* into separate ingredients ("Cashew, halved", "Green chilli, slit", "Ginger, paste",
"Coconut, fresh grated"). Seven tables point at `ingredients` today (migrations V11, V14, V15, V24–V27,
V77): recipe lines, stock movements, inventory items, vendor supplies, the order list, PO lines,
goods receipt lines, and ingredient requests.

**R-DUP-1: The ingredient is separate from its preparation.**
- A recipe line gets a **preparation note** ("halved", "slit", "paste", "fresh grated", "sour").
- Stock, prices, ordering, shopping list and costing all sit on the **base ingredient** (Cashew).
- The note prints wherever the recipe line prints: the recipe page, job card, recipe PDF, translations.
- The **library import** must stop creating preparation ingredients. It maps "Cashew, halved" to
  ingredient *Cashew* with the note *halved*. Record the parsing rule and its test cases in the proof.
- **AC:** importing a library recipe containing "Green chilli, slit" creates no new ingredient when
  "Green chilli" exists, and the recipe line shows "Green chilli · slit".

**R-DUP-2: Duplicates are blocked when an ingredient is created or renamed.**
- The name is normalised: lower case, trimmed, punctuation and plurals removed, and any preparation
  word dropped.
- It's compared with existing ingredients by exact normalised match and close spelling ("Tomatos",
  "Tomato ripe", "Curd sour").
- A match **stops the save** and asks: **"Did you mean Curd? Use Curd, or add a preparation note
  instead."**, with buttons **Use Curd** and **It's a different ingredient**. The second needs a
  deliberate confirmation, and is audited.
- This applies everywhere ingredients are created: the ingredient form, the recipe form's inline
  add, the library import, and the "No vendor yet" or one-off paths.
- **AC:** creating "Tomatos" when "Tomato, ripe" exists is stopped with the prompt, and so is
  "Curd sour" when "Curd" exists.

**R-DUP-3: A one-time merge tool for existing duplicates** (Temple Admin only; new permission
`MERGE_INGREDIENTS`):
- It **proposes** merge groups (for example "Curd, fresh / Curd, sour / Curd, whisked → Curd",
  "Cashew, halved → Cashew"), each with the preparation note it would move onto the recipe lines.
- The admin approves or edits each group.
- An approved merge:
  1. repoints every reference in the seven tables to the kept ingredient
  2. moves the preparation text onto the recipe lines
  3. combines on-hand stock (stock movements keep their history, re-pointed, never deleted)
  4. merges vendor supplies (a conflict on the same vendor asks which price to keep), pack sizes,
     market rate and price history
  5. writes an audit record
- The merged-away name is kept as an **alias**, so searching for it, or re-importing it, finds the
  kept ingredient.
- It runs in one transaction per group.
- **AC:** after merging the curd group:
  - on-hand stock for Curd equals the sum before the merge
  - every recipe that used "Curd, sour" now uses Curd with the note "sour"
  - the shopping list shows one Curd line
  - no reference to a merged-away ingredient remains (a query proves it)
  - the audit shows the merge

**R-DUP-4: The order of work.** Build R-DUP-1 and R-DUP-2 before the procurement screens, so new
dirty data stops. Run R-DUP-3 on local data (and on staging, after a backup) before the end-to-end
check in §10.

---

## 10. Verification checklist (the verifying agents own this)

For each R-… above:
1. Its ACs are met, **measured**.
2. Full frontend `npx tsc --noEmit && npx vitest run` and eslint pass, and so do backend `./gradlew test`, `ErrorCodeTest` and the RLS ITs.
3. The page is checked at 1280 and 390 wide, signed in locally, **by the role it's for** (Temple Admin, Kitchen Manager, Kitchen Staff, and a user without the permission).
4. The end-to-end story works on the local stack, and again on staging after deploy:
   **shopping list (packs) → create PO → send → partial delivery → rest of delivery → invoice for delivery 1 → invoice for delivery 2 → both paid (one by cash with proof) → the list price and its arrow updated → Cost per serving and "Issued from the temple store" show a real ₹ figure for that ingredient.**

---

## 11. How to run the build (for the conductor session)

- **The main session is a conductor, not a worker.** It never reads large files or agent transcripts. It keeps progress in `docs/work/PROCUREMENT-PROGRESS.md`: one line per requirement, with its status and proof path. That lets a compaction or a new session resume from the file.
- **Three kinds of agent:**
  - **Dev agents (`builder`):** one requirement group each, with no two on the same file at the same time. Each writes `docs/work/proof/<task>.md` with real command output, and reports in at most 150 words.
  - **Verify agents:** fresh agents that did not build the work. Each checks one group against §10 and this document only, measures everything, and reports pass or fail per AC.
  - **One clarifier agent:** answers builders' and verifiers' questions **using only this document** (and the mocks). When the document is silent or ambiguous, it says so, and the conductor puts the question to Rajeev. **It never invents an answer.**
- **Rajeev decides:** questions go on the **Decisions Desk** (https://claude.ai/artifact/EZBQnhDRRX8w2fLV15v2yr) in the shape CLAUDE.md requires. A session cron polls it every 5 minutes; recreate that in the new session.
- **The order of work:** §3 data model, then §9A R-DUP-1 and R-DUP-2 (stop new duplicates), then §5 (ingredients and vendors, and price history), then §4 (shopping list), then §6 (POs), then §7 (Deliveries), then §8 and §9 (invoices and payments), then §9A R-DUP-3 (merge existing duplicates), then the end-to-end check in §10. Parallelise where files don't overlap.
- **Releases:** follow CLAUDE.md, `docs/DEPLOYMENT.md` and the memory notes:
  - commit straight to main
  - verify the committed tree
  - CI green
  - **take a staging database backup before any migration deploy**
  - deploy from a clean tree
  - staging only
- **Timing:** the demo is Sun 20 Sep, 9 AM IST, which is Sat 19 Sep 20:30 PDT. Anything not verified by the freeze Rajeev sets stays off staging for the demo.

---

## 12. Open questions (only Rajeev can answer; don't guess)

- **Q-1:** Does Kitchen Staff get `RECEIVE_DELIVERIES` by default, or only named staff?
- **Q-2:** One preferred vendor per ingredient, with "replaces A" shown (R-VEN-2)?
- **Q-3:** Does the temple buy some items at the market for cash, with no vendor (a market list instead of a PO)?
- **Q-4:** Confirm red-up / green-down as the one written exception to the colour rule (R-VEN-3).
- **Q-5:** Is the invoice line **rate** stored **before or after GST**? The list price should probably be pre-GST, with GST held separately. It needs his call because it changes every costed figure.
- **Q-6:** *Answered:* market rate (R-ING-3).
- **Q-7:** Donated food (see §13).
- **Q-8:** Does the PO page keep a per-item "Record delivery" button (opening the same panel as the Deliveries screen, filtered to this order), or only a link to the Deliveries screen? Rajeev first proposed a per-item button on the merged table, before the Deliveries screen existed.
- **Q-9:** Price at the gate removed, price on the invoice only (R-DEL-5, R-VEN-4). This was Claude's recommendation, reflected in the invoice designs Rajeev chose; confirm explicitly.

---

## 13. Known gaps: where ₹0 can still happen after this build

This build makes a real price the normal outcome. These cases remain (Rajeev's rulings, 2026-09-19):

1. **Stock with no price** is closed by **R-ING-3, Market rate**: a value is required at stock-take, and
   costing falls back to it. Only stock added outside those paths could still lack a price; the
   verifying agents must look for any such path.
2. **Donated food is valued at market value, never ₹0** (Rajeev). This is deferred to the donations
   work, which hasn't started. Until then, the costing's "n ingredients without a price" notice must
   stay visible.
3. **Costing uses the current list price, not what the issued stock actually cost** (₹60 rice issued
   after the price moved to ₹64 is costed at ₹64). Cost per batch (average or FIFO) comes after UAT.
4. **Near-duplicate ingredients are prevented.** In scope, see §9A (Rajeev, 2026-09-19).
5. **Bills in bags, tins or bundles resolve to a per-unit price.** This is solved by the standard
   alternate-units pattern (R-ING-1, R-VEN-1, R-SL-3, R-INV-4). In scope.

The costing screens must keep saying **"n ingredients without a price"** out loud, and never show a silent ₹0.
