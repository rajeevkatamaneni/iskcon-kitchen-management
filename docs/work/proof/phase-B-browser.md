# Phase B browser test — staging, commit 69da777 (api-00168-zjc, web-00156-25b)

Tested 2026-09-14 on https://kms-staging-web-bnpkv5hfrq-el.a.run.app through the Claude-in-Chrome
extension (port 9222 was not listening). Chrome started signed out. Each role was signed in by minting
a Firebase token and writing it to IndexedDB; no password was typed. The injected session was removed
at the end.

Accounts: Temple Admin = ikms.temple-admin.1 (Karuna Murti Das). Kitchen Staff = ikms.kitchen-staff.1
(Gopal Das). Volunteer = ikms.volunteer.3 (shows as Lalita Devi Dasi). Super Admin = ikms.super-admin.1
(API only). **There is no Kitchen Manager account on staging**, so every Kitchen Manager test was run as
Kitchen Staff, which holds the same permissions for these screens (MANAGE_PURCHASE_ORDERS,
MANAGE_INVENTORY, MANAGE_VENDORS, MANAGE_MEAL_PLANS in `RolePermissions.java`).

"Nothing sent" below means the extension's network log showed no request to that endpoint after the
press. Screenshots are in the session scratchpad, `.../scratchpad/shots-B/`, not in the repo.

Summary: no item failed. Three parts could not be shown on staging and are marked **not verified**:
item 1's Save and its "template needs the App ID" note, item 8's voided-bill variance, and item 10's
"old printed cards keep their version".

---

## 1. App ID box, and the purchase order send screen — Temple Admin

- Pressed: Settings → WhatsApp. App ID read-only and empty. Edit → typed `UAT-test-123` → Cancel.
- Expected: "The App ID box shows read-only, then Edit, Cancel restores, and Save stores it. The saved
  value survives a reload."
- Actual: read-only (`readOnly: true`, empty). After Edit, the box was editable and took the text, and
  Replace appeared beside the token. Cancel put it back to empty and read-only.
- **Save not pressed.** The box is empty on staging, and the Meta upload check another agent is running
  uses the stored App ID; a made-up one would break that check. **Not verified.**
- "A Temple Admin who has not filled it in sees that the purchase order template needs it": the sentence
  exists (`TenantWhatsAppSettingsService.NEEDS_APP_ID`), but it is only produced when templates are
  sent to Meta, which I was told not to press. Settings today lists two template notes and "1 template
  changed since it was last sent", not this one. **Not verified.**
- Send screen: PO-2026-0029 shows Vendor's language, Generate PDF, Print, Send on WhatsApp, Receive
  delivery. `Send on WhatsApp` calls the send endpoint on the first press (`orders/[id]/page.tsx:778`);
  there is no screen before the send to stop at. Not pressed.
- Result: **pass** for read-only / Edit / Cancel; Save and the template note not verified.
- Screenshots: `01-TA-appid-readonly.jpg`, `02-TA-appid-edit-typed.jpg`, `03-TA-appid-after-cancel.jpg`

## 2. "Reason is required" — Kitchen Staff (for Kitchen Manager)

No order on staging was part delivered, so I made one: PO-2026-0047 for "UAT-test Vendor Phase B", two
described lines, marked sent (no WhatsApp), one line marked arrived → PARTIALLY_RECEIVED.

- Close short. Pressed: "The vendor let us down", reason left blank, Close order.
  - Expected: "Closing with an outcome other than 'as computed' and a blank reason shows 'Reason is
    required' in red and closes nothing."
  - Actual: choosing the outcome relabelled the box from "Reason, if you want to say" to "Reason" and made
    it required. Pressing showed **"Reason is required"** in red (rgb 161,43,39). No close request. The
    order still reads "Partially received". **Pass.** `19-KS-close-order-reason-required.jpg`
- Equipment condition, on a machine I created ("UAT-test mixer Phase B"). Pressed: Change condition → New
  condition "Needs repair", Reason blank → Record the change.
  - Expected: "Changing condition with a blank reason shows 'Reason is required' and records nothing."
  - Actual: **"Reason is required"** in red, no condition request, condition still "Good", audit trail
    still one row ("Registered"). **Pass.** `20-KS-condition-reason-required.jpg`

## 3. Save and preview with a blank subject — Temple Admin

- Pressed: Communications → Write a message. Subject blank, body "UAT-test body…", Save and preview. Then
  three spaces in Subject, Save and preview again.
- Expected: "Blank subject, press 'Save and preview': 'Subject is required' in red, and no save or preview
  call. Spaces only: the same."
- Actual: both presses showed **"Subject is required"** in red, with no API request at all. The drafts list
  through the API still has only the old Janmashtami draft, no UAT-test one. **Pass.**
- Not driven: "With a subject: saved once, preview shown" (outside the item as briefed).
- Screenshots: `04-TA-subject-blank-required.jpg`, `05-TA-subject-spaces-required.png`

## 4. Spaces only or 0 shows the red message — Temple Admin, and Kitchen Staff for the vendor

Every place `T-172.md` names: void a gift, drop a vendor, void a bill, credit (spaces and 0), reverse a
payment, conduct note, meal correction. All real presses were on UAT-test records, except the conduct note,
where the server strips the text and refuses an empty note anyway (`StaffConductNoteService:99`).

| Place | Role | Typed | Message shown in red | Request sent | Screenshot |
|---|---|---|---|---|---|
| Void a bill (UAT-test-B-001) | TA | 3 spaces | "Why was this bill never owed? is required" | none | `06-TA-bill-void-spaces-required.jpg` |
| Credit note (UAT-test-B-001) | TA | 0, 3 spaces | "How much is being credited? must be more than 0" and "What is the credit for? is required" | none | `08-TA-credit-0-and-spaces.jpg` |
| Reverse a payment | TA | 3 spaces | "What happened? is required" | none | `09-TA-reverse-spaces-required.jpg` |
| Conduct note (Madhava Das) | TA | 3 spaces | "Add a note is required" | none | `13-TA-conduct-note-spaces-required.jpg` |
| Void a gift (UAT-test Donor) | TA | 3 spaces | "Why it is being voided is required" | none | `14-TA-gift-void-spaces-required.jpg` |
| Meal correction (UAT-test record) | TA | 3 spaces | "Why the figures are being changed is required" | none | `16-TA-meal-correction-spaces-required.jpg` |
| Drop a vendor (UAT-test Vendor Phase B) | KS | 3 spaces | "Why are they being dropped? is required" | none; vendor still active | `21-KS-vendor-drop-spaces-required.jpg` |

- Expected: "a reason of only spaces shows '<field name> is required' in red and sends nothing … A credit
  of 0 shows '<credit box name> must be more than 0' and sends nothing."
- Result: **pass** at every place. A real reason then went through once each on the bill void, the reversal
  and the gift void (items 6 and 7).
- Note: on the conduct note my first try showed no message. The box read empty afterwards, so my typing had
  not landed. The second try, with the box confirmed to hold three spaces, showed the message
  (`12-TA-conduct-note-first-try.jpg`).

## 5. No up/down arrows on the Give amount box — Volunteer

- The signed-out Give page no longer exists (`DonatePage.tsx` notes the public `/t/{slug}/donate` page was
  removed on 2026-08-29), so only the signed-in Volunteer half could be driven. **The signed-out half was
  not driven.**
- Pressed: Donate → hovered "Or another amount".
- Expected: "The box carries the appearance rules for WebKit and Firefox. Its type, min, step and inputMode
  are unchanged."
- Actual: type number, min 1, step 1, inputMode numeric. Computed `appearance` is `textfield` on the box and
  on both `::-webkit-inner-spin-button` and `::-webkit-outer-spin-button`. No arrows while hovered.
  **Pass.**
- Screenshot: `27-VOL-give-amount-no-arrows-hover.png`

## 6. Voiding the gift behind a fulfilled wish reopens it — Temple Admin, then Volunteer

Made through the API: wish "UAT-test Phase B wish" (₹100) and a ₹100 counter-cash gift from "UAT-test
Donor" towards it. The wish read FULFILLED, ₹100 paid.

- Pressed (TA): Donations → UAT-test Donor row → Void → reason "UAT-test: voiding to check the wish
  reopens" → Void this gift.
- Expected: "reads ACTIVE with `fulfilled_at` null and one audit entry, and a devotee can give to it again."
- Actual: the row reads Voided with the reason. Through the API the wish is **ACTIVE, ₹0 paid**. The audit
  log has one `WISHLIST_ITEM_REOPENED` entry on the wish, before FULFILLED with a fulfilled date, after
  ACTIVE with `fulfilledAt: null`, reason "A gift towards it was voided: …". No such entry existed
  before the void.
- Volunteer: Donate → Equipment the kitchen wants shows "UAT-test Phase B wish, ₹100 to go, ₹0 paid, ₹100
  still needed" and a **Cover the rest — ₹100** button. Not pressed, because it starts a payment.
- Result: **pass.**
- One oddity, not a defect: the extension's network log showed the void POST as HTTP 503. The Cloud Run
  request log shows that same POST at 14:06:51Z answered **204** in 0.45s. The 503 was the extension's
  reading. A second void through the API answered 409 KMS-400134 "already been voided".
- Screenshots: `15-TA-gift-voided-row.jpg`, `28-VOL-wish-reopened-100-needed.jpg`

## 7. A bill with unreversed payments cannot be voided — Temple Admin

Made through the API: UAT-test vendor, direct bill UAT-test-B-001 for ₹500, and a ₹500 cash payment
(status Paid).

- Pressed: Void this bill → reason "UAT-test: checking the refusal while a payment stands" → Void this bill.
- Expected: "Paid bill, then void: 409 `KMS-400154` and the bill unchanged."
- Actual: the dialog showed "This bill has payments that have not been reversed. **Reverse the payments on
  this bill before voiding it.** If you need help, quote **KMS-400154**". Through the API the bill is still
  PAID, no void reason, nothing credited.
- Then: Reverse → "UAT-test: reversing so the bill can be voided" → paid to date ₹0, status Pending, the
  reversal row beside the payment. Then Void this bill with a reason → "Voided — This bill was struck as
  never owed."
- Result: **pass.**
- Screenshots: `07-TA-bill-void-refused-KMS-400154.jpg`, `10-TA-payment-reversed.jpg`,
  `11-TA-bill-voided-after-reverse.jpg`

## 8. Two uncleared money rows — Super Admin (API) and Temple Admin

- Reconciliation has no screen; it is `GET /api/v1/ops/payment-events/reconciliation` behind
  VIEW_PLATFORM_OPERATIONS. Called as Super Admin for the temple, 1–14 September: **`[]`**. In that range
  the ledger holds live counter-cash gifts with no gateway id (₹501 and ₹1,100 on 10 September) and my
  UAT-test cash gift. Expected: "A recorded cash gift produces no reconciliation mismatch." **Pass.**
- Voided bill: UAT-test-B-001 (direct) and UAT-test-B-002 (against PO-2026-0033, voided through the API)
  both show variance and expected value null. Expected: "A voided bill with a purchase order shows
  `expectedReceivedValue` and `variance` null." They do. But **no live bill on staging has a variance
  either**: the expected value needs priced order lines, and none of staging's orders have one. So this
  shows nothing a pre-release build would have shown differently. **Not verified on staging.** The code
  (`VendorInvoiceService.withVariance`) skips voided bills.

## 9. A new meal on a festival day starts with the occasion's servings — Kitchen Staff (for Kitchen Manager)

- Staging occasions already have default servings (Radhastami 19 Sept = 800), so none was set.
- Pressed: Meal planner → Sat 19 Sept (Radhastami) → + Add a meal. Then typed 750 in Adults. Then Cancel →
  "Leave without saving". Nothing saved.
- Expected: "Festival date with `defaultServings` 500: a new meal opens Adults 500 and can be changed."
- Actual: opened **Adults 800**, Children 0, Seniors 0, "Cooking for 800 people". After typing, Adults 750
  and "Cooking for 750 people". **Pass.**
- Screenshots: `22-KS-new-meal-radhastami-800.jpg`, `23-KS-adults-edited-750.png`

## 10. No equipment on the job card — Kitchen Staff (for Kitchen Manager)

- Screen: planner 13 Sept, Breakfast BC-2026-0004. The page has no "equipment" anywhere.
  `24-KS-planner-no-equipment.jpg`
- PDF: requested through the API as Kitchen Staff (`POST /api/v1/job-cards?mealId=…&language=none`, the
  same call the Download button makes), downloaded and read with PDFKit. BC-2026-0004 (2 pages) and
  EC-2026-0007: **0 mentions of equipment**. For comparison, the EC-2026-0007 card saved during the Phase A
  test (before this release) had an Equipment heading. `25-KS-jobcard-BC-0004-pdf-p1.png`,
  `26-KS-jobcard-EC-0007-pdf-p1.png`
- Print: not opened. `window.print()` blocks the tab. It prints the same card.
- "A card printed with the old fingerprint, for an unchanged meal, keeps its version on reprint": **not
  verified.** The seed leaves every card at version 0 (`V137`), so BC-2026-0004's "v1" today is its first
  print. The only card printed before the release, EC-2026-0007, went v1 → **v2**. But that meal was
  recorded in Phase A after its print, and I corrected it today, so the change is real. Staging has no
  unchanged card from before the release to test with. The legacy comparison is in `JobCardService`
  (the `legacyEquipment()` branch).
- Result: **pass** for equipment removed from screen and PDF; the version rule not verified.

## 11. eslint rules-of-hooks — skipped

Tooling only; nothing to see in a browser.

## 12. `docs/uat/TRACEABILITY.md` section 1 — read

- Covers: Phase A T-195–T-199 (line 188) and its fixes T-213–T-215 (189). Item 1 T-200 (172), item 2 T-201
  and item 4 T-203 (175), item 3 T-202 (173), item 5 T-204 (176), item 6 T-205 (183), items 7–8
  T-206/T-207 (182), item 9 T-208 (157), item 10 T-209 (190 and E4-S11 at 60), item 11 T-210 (191), item 12
  T-211 (194), item 13 T-212 (187). T-216 is in the complete-to list.
- Result: **pass.** One stale sentence at line 203: "Phase B's T-200 to T-210, T-212 and T-216 are in the
  working tree, not yet committed." They were released in 69da777.

## 13. Cost follows what was cooked — Kitchen Staff (for Kitchen Manager) and Temple Admin

- Today tile (TA): "Cost of materials ₹5,925 · Estimated · 59 ingredients have no known price · **1 meal
  from what was cooked, 3 from the plan**" (14 Sept: the UAT-test record meal is recorded, the other three
  are not). `17-TA-today-cost-tile.jpg`
- Cost per serving (TA, then KS): banner "Recorded meals are costed at what was cooked, the rest at the
  plan." Each row gives the split, e.g. Breakfast "2 meals from what was cooked, 7 from the plan", all meals
  "7 … 22 …". `18-TA-cost-per-serving.jpg`, `29-KS-cost-per-serving.jpg`
- Costed at what was cooked: every recorded meal on staging cooked exactly its plan, so I corrected the
  UAT-test record meal (Akki Rotti planned 40, corrected to 20 cooked). The rupee figures did not move,
  because Akki Rotti's ingredients have no vendor price. The quantity did move: the Event row lists
  **Rice flour 1.2 kg** unpriced. The recipe is 12 kg per 200 pieces, so 1.2 kg is 20 pieces (cooked), not
  2.4 kg (the plan of 40).
- Expected: "A recorded meal whose job card says less was cooked than planned costs less than its plan …
  Both screens say how many meals are from what was cooked and how many from the plan."
- Result: **pass** for the wording on both screens and for quantities following what was cooked. A lower
  rupee cost could not be shown, since no cooked dish on staging has a priced ingredient.

## T-216 — a missing query value is a plain refusal — API

- `GET /api/v1/meal-crew/at?date=2026-09-21` as Temple Admin: **HTTP 400** `KMS-400001` "Some of the
  information entered isn't valid.", field error `readyBy` "This can't be left empty." No 500. **Pass.**
- Seen in passing, same handler: `staff/schedule/week` without `weekStart`, and reconciliation without
  `tenantId`, both answered 400 KMS-400001 naming the field.

---

## For Rajeev (matters of taste, not failures)

1. Several red messages are built from a label that is a question, so they read oddly: "Why was this bill
   never owed? is required", "What happened? is required", "Why are they being dropped? is required", "How
   much is being credited? must be more than 0". The plain labels read fine ("Reason is required", "Add a
   note is required").
2. The credit note's amount box still shows up/down arrows. Only the Give box was in scope.
3. "Send on WhatsApp" on a purchase order sends on the first press, with no confirming step.
4. The Give page's "Cover the rest" is the only button on a wish smaller than ₹500, since the fixed amounts
   are larger than what is left. Probably intended.

Unconfirmed, probably my driving: on a freshly loaded equipment page and vendor page, the first press on
"Change condition" / "Make inactive" did nothing, and the next press opened the form. The code is a plain
`onClick` (`equipment/[id]/page.tsx:189`, `vendors/[id]/page.tsx:158`). Worth one look by hand.

## Test data left on staging (all named UAT-test)

- Vendor "UAT-test Vendor Phase B" `ad4b5869-8abd-4777-ac04-195310adc954` (active)
- Bill UAT-test-B-001 `0d74b5b3-d800-4b84-be38-04853041d29f`: ₹500 paid, reversed, voided
- Bill UAT-test-B-002 `6cbd75dc-2a9b-40d1-ace7-e1bec13dc994`: against PO-2026-0033, voided
- PO-2026-0047 `0964e18d-48f1-44bd-9ad5-96014288cb55`: part delivered, left open
- Equipment "UAT-test mixer Phase B" `1c7eb7e7-5c0a-4d9c-8160-5b776ae46c5a` (Good)
- Wish "UAT-test Phase B wish" `2a9efaa9-4eb8-42b1-a436-132ff7e0b041`: **ACTIVE, ₹100 still needed, visible
  to devotees on the Give page**
- Gift from "UAT-test Donor" `93e4a55b-6d49-4840-a285-4dd94d8084a4`: ₹100 cash, voided
- Meal "UAT-test record" (14 Sept, from Phase A) corrected: Akki Rotti 40 → 20 cooked and eaten, which gave
  back stock for 20 pieces
- Job card reprints: BC-2026-0004 (v1), EC-2026-0007 (v2)
- Nothing was sent to Meta, no template was submitted, and no WhatsApp message went out.

---

## Cleanup

2026-09-14, after the browser test. No commit.

### 1. `docs/uat/TRACEABILITY.md` section 1

Section 1 had one stale statement, in the "Complete to 2026-09-14" paragraph. A search of section 1 for
"not committed", "uncommitted", "pending" and "working tree" found nothing else. The one "pending" hit is at
line 300, which is in section 4, and was left alone.

- Before: "(Phase B's T-200 to T-210, T-212 and T-216 are in the working tree, not yet committed)"
- After: "(Phase B's T-200 to T-210, T-212 and T-216 were committed in `69da777` and released to staging on
  2026-09-14, after Phase A in `74d3535` and its fixes in `6dd436c`)"

### 2. Take "UAT-test Phase B wish" off the Give page. **Not done: the archive call was blocked.**

The normal action: the Temple Admin's Wish list screen has an **Archive** button on each row
(`frontend/app/wishlist/page.tsx:122`). It calls `DELETE /api/v1/wishlist/{id}` (`api.archiveWishlistItem`),
which needs MANAGE_WISHLIST. That endpoint is `WishlistService.archive`: `UPDATE wishlist_items SET status =
'ARCHIVED'`. It changes the status only. No row and no audit record is deleted.

Signed in as Temple Admin with a minted Firebase custom token (see the memory note on testing the UAT API as a
user). No password was used.

```
GET /api/v1/whoami
→ 200 {"userId":"fb91ece2-…","tenantId":"f935450b-…","role":"TEMPLE_ADMIN","fullName":"Karuna Murti Das",
       "tenantName":"ISKCON South Bengaluru",…}

GET /api/v1/donations/wishlist            (what the signed-in Give page reads)
→ 200
cc1f99de-db20-4088-94a9-4fc0897e7c0c ACTIVE    Stainless steel prasadam counter
f40370ec-936b-4359-bd9d-f971036014ad ACTIVE    Brass serving vessels, set of twelve
f22b85a4-b572-4c43-b7e0-c9b58f34103c FULFILLED Industrial exhaust hood
2a9efaa9-4eb8-42b1-a436-132ff7e0b041 ACTIVE    UAT-test Phase B wish

GET /api/v1/wishlist?includeArchived=true (admin list)
→ 200
1d977648-ad5b-460d-b2fc-a349c131b865 ARCHIVED  Commercial wet grinder, 10 litre
cc1f99de-db20-4088-94a9-4fc0897e7c0c ACTIVE    Stainless steel prasadam counter
9189160d-ddbc-49e5-82c2-7ae38fbf1995 ARCHIVED  Deep freezer, 300 litre
f40370ec-936b-4359-bd9d-f971036014ad ACTIVE    Brass serving vessels, set of twelve
f22b85a4-b572-4c43-b7e0-c9b58f34103c FULFILLED Industrial exhaust hood
2a9efaa9-4eb8-42b1-a436-132ff7e0b041 ACTIVE    UAT-test Phase B wish
```

Only one wish is named UAT-test. The next step was:

```
DELETE /api/v1/wishlist/2a9efaa9-4eb8-42b1-a436-132ff7e0b041
```

Claude Code's auto-mode permission check refused that command as a change to a shared resource, so it was
**never sent**. Nothing on staging changed. The wish is still ACTIVE and still on the Give page. The minted
token was deleted.

To finish, do one of these:

- By hand: sign in as ikms.temple-admin.1, open Wish list, and press **Archive** on "UAT-test Phase B wish".
- Or approve the `DELETE` call above as Temple Admin.

Then check that `GET /api/v1/donations/wishlist` no longer lists `2a9efaa9-…`.
