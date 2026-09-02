# UAT-037: Vendors and what they supply

| | |
|---|---|
| **Feature area** | Ordering — vendor management |
| **Technical stories** | E5-S1 (vendor management, amended 2026-08-31) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-013 (ingredients) |
| **Environment needs** | None |

## What this feature is for

Vendors do not log in to this system — Indian temple procurement happens by phone and WhatsApp, and
the temple's staff keep the records. So the vendor list has to hold everything needed to order: who
they are, what they sell, the number a purchase order goes to, and which language they read.

And it has to hold **why they were dropped**. Months later somebody stands in front of the same list
wondering whether this supplier can be used again, and the only honest answer is the one whoever
dropped them wrote at the time.

## How it is supposed to work

- A vendor has a name, contact person, **phone with country code** (this is the WhatsApp destination),
  optional email, address, GSTIN, a **preferred language** for documents, notes, and a
  **contract end date**.
- Each vendor is mapped to the **ingredients they supply**, with a last-known price, and one vendor can
  be marked **preferred** for an ingredient — which is what the shopping list suggests.
- A vendor can be **deactivated**: they vanish from new orders but their history stays readable.
- **Deactivating requires a reason.** The button that commits it stays refused until there are words in
  the box, and the server refuses a blank one too (`KMS-4011`). **Bringing a vendor back** accepts a
  reason and never demands one.
- Every one of those changes is kept as **history** on the vendor's page — what changed, who did it,
  when, and why — **newest first, never edited and never removed**. An entry written without a reason
  reads *No reason given*, not as a blank.
- **The contract end date only warns.** Within the temple's own contract horizon — **30 days** unless
  it has been changed on Settings → Warnings (UAT-080) — or any time after it, the vendor's
  page and the vendors list say so — and the vendor stays **fully active, fully selectable and still
  the preferred source**. **Nothing switches off on that date, ever.**

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/vendors** (menu: **Vendors**)
- **Create these two vendors.** Later tests order from both:

| Vendor | Phone | Language | Supplies |
|---|---|---|---|
| Sri Balaji Provisions | +919900000001 | Hindi | Rice (preferred), Toor Dal (preferred), Sugar |
| Nandini Dairy Agency | +919900000002 | Kannada | Ghee (preferred) |

## Steps

### The vendor and what they sell

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Vendors** | *Who the temple buys from — the WhatsApp number a purchase order goes to.* An empty list with **Add a vendor** |
| 2 | Press **Add a vendor** | Fields: Name, Phone (with country code), Contact person, Email, GSTIN, **Contract ends**, Preferred language, Address, Notes |
| 3 | Read the hint under **Contract ends** | *Only a reminder. Nothing switches off on this date.* |
| 4 | Add a vendor with the phone `9900000001` (no country code) | Refused, with advice to include the country code (`KMS-4003`) |
| 5 | Add **Sri Balaji Provisions** properly, with `+919900000001`, contact `Balaji`, GSTIN `29ABCDE1234F1Z5`, language **Hindi**, and **no** contract end date | It appears in the list: Vendor, Phone, Language, **Contract ends** (reading **—**), Status **Active** |
| 6 | Add **Nandini Dairy Agency**, `+919900000002`, language **Kannada** | Two vendors |
| 7 | Try to add a third vendor also called `Sri Balaji Provisions` | Refused: *A vendor with that name already exists* (`KMS-4918`) |
| 8 | Open **Sri Balaji Provisions** | Its page: Details (editable), a **Supplies** section, and an **Active and inactive** section |
| 9 | Add supplies: `Rice` with last price `52`, `Toor Dal` with `140`, `Sugar` with `46`; mark Rice and Toor Dal **Preferred** | Three rows in Supplies, two marked preferred |
| 10 | Open **Nandini Dairy Agency** and add `Ghee` at `620`, marked **Preferred** | Recorded |
| 11 | Edit Sri Balaji's preferred language to **Telugu**, save, and reload | The change persists (change it back to Hindi afterwards) |

### Dropping a vendor needs a reason

| # | Do this | You should see |
|---|---|---|
| 12 | Open **Nandini Dairy Agency** and read the **Active and inactive** section | *This vendor has never been made inactive*, above an explanation that nothing there is ever edited or removed |
| 13 | Press **Make inactive** | A panel: **Make Nandini Dairy Agency inactive?**, saying they come off the pickers and off the shopping list's suggestions, and that every order and invoice already on their name stays exactly as it is. Underneath: **Why are they being dropped?** |
| 14 | Leave the box **empty** and look at the **Make inactive** button | It is **refused — you cannot press it**. Dropping a supplier is not a one-click act |
| 15 | Type **three spaces** and look again | Still refused. **Whitespace is not a reason** |
| 16 | Type `Milk quality slipped through August; two deliveries turned back.` and press **Make inactive** | The vendor's status reads **Inactive** |
| 17 | Read the **Active and inactive** section now | One entry: **Made inactive**, **your name**, the date and time in the temple's clock, and **your reason, word for word** |
| 18 | Go to **/vendors** and look at the **Active vendors only** checkbox | It is **unticked**, and **Nandini Dairy Agency** is in the list, marked **Inactive**. Inactive vendors are shown until you ask for them not to be |
| 19 | **Tick** it | Nandini disappears from the list, and only Active vendors remain. Count the rows before and after |
| 20 | **Untick** it again | Nandini is back, still marked **Inactive**. The checkbox works in both directions — until 2026-08-31 it did nothing at all and inactive vendors were unreachable, so this step matters more than it looks |
| 21 | Open Nandini again and press **Bring back**. Read what it asks for | **Anything to add? (optional)** — a reason is offered, not demanded |
| 22 | Leave it empty and press **Bring back** | Accepted. The vendor is **Active** again — you will need them in UAT-039 |
| 23 | Read the history again | **Two** entries, **newest first**: *Brought back*, with **No reason given** in place of a blank; and below it the original *Made inactive*, its reason **unchanged** |
| 24 | Drop them a second time with a **different** reason, then bring them back again | **Four** entries, newest first. Neither earlier entry has been edited or overwritten by the later one |
| 25 | Look for any way to **edit or delete** a history entry | There is none, on any of them |
| 26 | Check the **Notes** field on the vendor's Details form | It is **unchanged** by any of this. The reason went to the history, not into Notes |

### The contract date warns and does nothing else

| # | Do this | You should see |
|---|---|---|
| 27 | Edit **Sri Balaji Provisions** and set **Contract ends** to a date **four days from today**. Save | A warning on the vendor's page: **Contract ends in 4 days** — and beneath it, *They are still active and can still be ordered from. Renew the agreement, or make them inactive and say why* |
| 28 | Go back to **/vendors** | The same warning is on their row, and the **Contract ends** column shows the date |
| 29 | Check their **status** | Still **Active**. Still ticked as preferred for Rice and Toor Dal |
| 30 | Set the date to **yesterday** and save | The warning reads **Contract ended** with the date. **The vendor is still Active** |
| 31 | Go to **/shopping-list** and press **Regenerate** (UAT-038) | Rice and Toor Dal still suggest **Sri Balaji Provisions**. An expired contract changes nothing about who the shopping list picks |
| 32 | Set the date to **six months out** and save | **No warning at all**, on the page or in the list. Six months is well past the temple's contract horizon — 30 days unless it has been changed (UAT-080) |
| 33 | Clear the date entirely and save | No warning, and the column reads **—** |
| 34 | Press **Remove** on one supply line | It is removed from that vendor's supplies |

## It passes if

- [ ] Both vendors can be created with contact details, GSTIN, a preferred language and a contract end date.
- [ ] A phone without a country code is refused; a duplicate vendor name is refused with `KMS-4918`.
- [ ] Supplies can be mapped with prices, and a preferred vendor set per ingredient.
- [ ] **Deactivating cannot be committed without a reason**, and whitespace does not count as one.
- [ ] The reason comes back on the vendor's page with the **author's name** and the **moment** it was written.
- [ ] Bringing a vendor back **accepts** a reason and does not demand one; without one it reads **No reason given**.
- [ ] A vendor dropped twice reads as two entries, newest first, and neither is edited by the other.
- [ ] No history entry can be edited or removed.
- [ ] The reason does **not** land in the Notes field.
- [ ] A contract that has ended, or ends within the temple's contract horizon, **warns** on the vendor's page and in the list.
- [ ] A warned vendor stays **active**, stays listed, and stays the preferred source on the shopping list.
- [ ] A contract ending far off does not warn, and the date is editable like any other field.
- [ ] A vendor can be deactivated and reactivated, and the **Active vendors only** filter works **both ways** — ticking hides inactive vendors, unticking reveals them, marked.

## Watch out for

- **Anything at all switching off on the contract end date.** Check the day after step 30: the vendor
  must still be active, still selectable in a new purchase order, and still the shopping list's
  suggestion. A vendor that quietly dropped out would be a Blocker — nobody could say why the list
  had started suggesting somebody else.
- **The **Active vendors only** checkbox doing nothing.** That was the fault until 2026-08-31: the
  filter was silently dropped and inactive vendors could not be reached at all — one of them with a
  live purchase order against it. If ticking or unticking it changes nothing, that is a **Major**
  defect, and count the rows to be sure rather than judging by eye.
- **`KMS-4011`** — *A vendor can't be made inactive without a reason.* The screen is meant to make this
  message unreachable by refusing the button first. If you ever see it on screen, write down exactly
  what you did.
- A history entry losing its author (it should read the person's name; *Someone since removed* is
  correct only for somebody deleted from the temple) or showing a time in **your** timezone rather
  than the temple's.
- Whether a **deactivated** vendor still appears in the shopping list's vendor suggestions or in the
  new-purchase-order flow. They should not — check in UAT-038 and note it back here.
- The **Recheck WhatsApp** control on the list: what does it do, and when does a vendor get flagged?
  It is meant to mark a number that failed to deliver (UAT-043).
- Two vendors both marked preferred for the same ingredient. Record what the system does — it is a
  question the story leaves open.
- GSTIN accepted in any format at all. Note whether it is validated; it appears on purchase orders.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT037-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
