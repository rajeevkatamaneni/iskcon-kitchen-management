# UAT-005: Who can see what — role boundaries

| | |
|---|---|
| **Feature area** | Platform foundation — access control |
| **Technical stories** | E1-S5 (role-based access control), E1-S12 (temple user management) |
| **Roles exercised** | All four: platform operator, temple admin, kitchen staff, volunteer |
| **Depends on** | UAT-008 (the team must exist before you can sign in as staff and volunteers) |
| **Environment needs** | None |

## What this feature is for

Four kinds of people use this system and they must not be able to do each other's jobs: a volunteer
must not reach the temple's money, kitchen staff must not change who works there, and a platform
operator must not read a temple's donations. This test walks the whole boundary, from all four sides.

## How it is supposed to work

- Every screen declares which roles may open it, and every request to the system behind it is checked
  independently. **The menu is not the security boundary** — it is a courtesy, so nobody is offered a
  destination they would only be refused at. Both must agree.
- Refusals are plain: a page saying *Not your page*, or, from the system, `KMS-4301` — *You don't have
  permission to do that.*
- The role map is fixed for this release: Super-admin, Temple admin, Kitchen staff, Volunteer.

## Before you start

- **You will sign in as four different people**, in turn. Sign out fully between each.
- **Start at:** **/sign-in**
- Have this table beside you. It is what the answer should be:

| Destination | Operator | Temple admin | Kitchen staff | Volunteer |
|---|---|---|---|---|
| /tenants (Temples) | ✅ | ❌ | ❌ | ❌ |
| /operations | ✅ | ❌ | ❌ | ❌ |
| /recipes, /ingredients, /inventory, /equipment, /planner, /vendors, /order-list, /orders, /invoices, /donations, /volunteers | ❌ | ✅ | ✅ | ❌ |
| /ledger (Donations ledger) | ❌ | ✅ | ❌ | ❌ |
| /wishlist | ❌ | ✅ | ❌ | ❌ |
| /staff-schedule | ❌ | ✅ | ❌ | ❌ |
| /money (Payments) | ❌ | ✅ | ❌ | ❌ |
| /users (People) | ❌ | ✅ | ❌ | ❌ |
| /audit (Audit log) | ❌ | ✅ | ❌ | ❌ |
| /my-shifts | ❌ | ❌ | ✅ | ✅ |
| /shifts (Available shifts) | ❌ | ❌ | ❌ | ✅ |
| /profile | ❌ | ✅ | ✅ | ✅ |

## Steps

| # | Do this | You should see |
|---|---|---|
| 1 | Sign in as `ikms.super-admin.1@trading4good.org` | Menu shows **Temples**, **Operations** only |
| 2 | Type **/ledger** into the address bar | *Not your page* |
| 3 | Sign out. Sign in as `ikms.temple-admin.1@trading4good.org` | Menu shows the full temple list: Recipes, Ingredients, Inventory, Equipment, Meal plan, Vendors, Order list, Purchase orders, Invoices, Donations, Volunteers, Donations ledger, Wish list, Staff schedule, Payments, People, Audit log, Profile |
| 4 | Type **/tenants** | *Not your page* |
| 5 | Sign out. Sign in as `ikms.kitchen-staff.1@trading4good.org` | Menu shows the kitchen destinations **and My shifts** — but **no** Donations ledger, Wish list, Staff schedule, Payments, People, Audit log |
| 6 | Type **/money** into the address bar | *Not your page* |
| 7 | Type **/users** | *Not your page* |
| 8 | Type **/audit** | *Not your page* |
| 9 | Sign out. Sign in as `ikms.volunteer.1@trading4good.org` | You land on **My shifts**. The menu offers only **My shifts**, **Available shifts**, **Profile** |
| 10 | Type **/recipes** | *Not your page* |
| 11 | Type **/ledger** | *Not your page* |
| 12 | Type **/inventory** | *Not your page* |
| 13 | Compare every row of the table above against what you actually saw | Every ✅ opens; every ❌ is refused |

## It passes if

- [ ] Each of the four roles sees exactly the menu in the table — nothing extra, nothing missing.
- [ ] Every destination marked ❌ is refused when typed into the address bar, with *Not your page*.
- [ ] A refusal never shows a broken or half-loaded screen, and never briefly flashes the real content before refusing.
- [ ] A volunteer lands on **My shifts** after signing in; a platform operator lands on **Temples**.

## Watch out for

- **Content flashing before the refusal.** If you see the real page for a moment before *Not your page* replaces it, that is a Major defect: the data reached the screen.
- A menu entry that leads to a refusal — the menu and the guard disagreeing.
- Kitchen staff seeing **Available shifts**: staff can view their own shifts but signing up for more is a volunteer action, so *My shifts* yes, *Available shifts* no.
- If any refusal shows a `KMS-` code, note it. `KMS-4301` is the expected one.

## Report anything wrong

| ID | Step | What you expected | What actually happened | Severity |
|---|---|---|---|---|
| UAT005-1 | | | | |

## Root cause (team fills in after the fix)

| Defect | Technical story | Root cause (R1–R7) | Note |
|---|---|---|---|
| | | | |
