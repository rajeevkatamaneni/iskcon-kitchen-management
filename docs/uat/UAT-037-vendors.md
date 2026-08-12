# UAT-037: Vendors and what they supply

| | |
|---|---|
| **Feature area** | Ordering — vendor management |
| **Technical stories** | E5-S1 (vendor management) |
| **Roles exercised** | Kitchen staff, temple admin |
| **Depends on** | UAT-013 (ingredients) |
| **Environment needs** | None |

## What this feature is for

Vendors do not log in to this system — Indian temple procurement happens by phone and WhatsApp, and
the temple's staff keep the records. So the vendor list has to hold everything needed to order: who
they are, what they sell, the number a purchase order goes to, and which language they read.

## How it is supposed to work

- A vendor has a name, contact person, **phone with country code** (this is the WhatsApp destination),
  optional email, address, GSTIN, a **preferred language** for documents, and notes.
- Each vendor is mapped to the **ingredients they supply**, with a last-known price, and one vendor can
  be marked **preferred** for an ingredient — which is what the order list suggests.
- A vendor can be **deactivated**: they vanish from new orders but their history stays readable.

## Before you start

- **Sign in as:** `ikms.kitchen-staff.1@trading4good.org` (kitchen staff)
- **Start at:** **/vendors** (menu: **Vendors**)
- **Create these two vendors.** Later tests order from both:

| Vendor | Phone | Language | Supplies |
|---|---|---|---|
| Sri Balaji Provisions | +919900000001 | Hindi | Rice (preferred), Toor Dal (preferred), Sugar |
| Nandini Dairy Agency | +919900000002 | Kannada | Ghee (preferred) |

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Open **Vendors** | *Who the temple buys from — the WhatsApp number a purchase order goes to.* An empty list with **Add a vendor** |
| 2 | Press **Add a vendor** | Fields: Name, Phone (with country code), Contact person, Email, GSTIN, Preferred language, Address, Notes |
| 3 | Add a vendor with the phone `9900000001` (no country code) | Refused, with advice to include the country code (`KMS-4003`) |
| 4 | Add **Sri Balaji Provisions** properly, with `+919900000001`, contact `Balaji`, GSTIN `29ABCDE1234F1Z5`, language **Hindi** | It appears in the list: Vendor, Phone, Language, Status **Active** |
| 5 | Add **Nandini Dairy Agency**, `+919900000002`, language **Kannada** | Two vendors |
| 6 | Try to add a third vendor also called `Sri Balaji Provisions` | Refused: *A vendor with that name already exists* (`KMS-4918`) |
| 7 | Open **Sri Balaji Provisions** | Its page: Details (editable) and a **Supplies** section |
| 8 | Add supplies: `Rice` with last price `52`, `Toor Dal` with `140`, `Sugar` with `46`; mark Rice and Toor Dal **Preferred** | Three rows in Supplies, two marked preferred |
| 9 | Open **Nandini Dairy Agency** and add `Ghee` at `620`, marked **Preferred** | Recorded |
| 10 | Edit Sri Balaji's preferred language to **Telugu**, save, and reload | The change persists (change it back to Hindi afterwards) |
| 11 | **Deactivate** Nandini Dairy Agency | Its status reads **Inactive** |
| 12 | Untick **Active vendors only** | Inactive vendors are shown again |
| 13 | **Reactivate** it | Active again — you will need it in UAT-039 |
| 14 | Press **Remove** on one supply line | It is removed from that vendor's supplies |

## It passes if

- [ ] Both vendors can be created with contact details, GSTIN and a preferred language.
- [ ] A phone without a country code is refused.
- [ ] A duplicate vendor name is refused with `KMS-4918`.
- [ ] Supplies can be mapped with prices, and a preferred vendor set per ingredient.
- [ ] A vendor can be deactivated and reactivated, and the filter works.

## Watch out for

- Whether a **deactivated** vendor still appears in the order list's vendor suggestions or in the new-purchase-order flow. They should not — check in UAT-038 and note it back here.
- The **Recheck WhatsApp** control on the list: what does it do, and when does a vendor get flagged? Try it and record the behaviour — it is meant to mark a number that failed to deliver (UAT-043).
- Two vendors both marked preferred for the same ingredient. Record what the system does — it is a question the story leaves open.
- GSTIN accepted in any format at all. Note whether it is validated; it appears on purchase orders.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT037-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
