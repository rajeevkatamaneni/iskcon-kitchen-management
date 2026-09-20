# KMS REST API reference — procurement → receiving → invoicing → payment, plus requests, donations, equipment, attachments

All paths are relative to the backend root. Every endpoint below is authenticated with `Authorization: Bearer <Firebase ID token>`; when a person belongs to more than one temple, add `X-KMS-Temple: <tenantId>` (constant `AuthenticationFilter.TEMPLE_HEADER`, `backend/src/main/java/org/iskcon/kms/auth/AuthenticationFilter.java:60`). The tenant is never taken from a request body or path.

Permission → role map (`backend/src/main/java/org/iskcon/kms/auth/RolePermissions.java`):
- TEMPLE_ADMIN: everything used below, incl. MANAGE_VENDOR_PAYMENTS, VIEW_DONATIONS, VOID_DONATION, MANAGE_WISHLIST, MANAGE_EQUIPMENT_SERVICING, REINSTATE_SCRAPPED_EQUIPMENT, APPROVE_INGREDIENT_REQUESTS, ISSUE_INGREDIENTS.
- KITCHEN_MANAGER: MANAGE_INVENTORY, MANAGE_MEAL_PLANS, MANAGE_VENDORS, MANAGE_PURCHASE_ORDERS, RECEIVE_DELIVERIES, REQUEST_INGREDIENTS, APPROVE_INGREDIENT_REQUESTS, ISSUE_INGREDIENTS. **No** MANAGE_VENDOR_PAYMENTS, no VIEW_DONATIONS, no MANAGE_WISHLIST, no equipment-servicing.
- KITCHEN_STAFF: MANAGE_INVENTORY, MANAGE_MEAL_PLANS, MANAGE_VENDORS, MANAGE_PURCHASE_ORDERS, RECEIVE_DELIVERIES, REQUEST_INGREDIENTS. (Can record donations — MANAGE_INVENTORY — but cannot read the ledger.)
- VOLUNTEER: VIEW_OWN_SHIFTS, SIGN_UP_FOR_SHIFTS, **VIEW_OWN_DONATIONS** (only the volunteer role holds this).

So for a simulation: one TEMPLE_ADMIN account can drive everything except "my donations"; use a VOLUNTEER account for `/api/v1/my-donations`.

Error envelope: `{ code: "KMS-400168", message, action, fieldErrors: [{field, message}] }`. Codes cited below are from `error/ErrorCode.java` (number is literal, permanent).

---

## 1. shoppinglist — `/api/v1/shopping-list`
`ShoppingListController.java`. **The list is derived on every GET and nothing regenerates it.** `POST /regenerate` and `POST /purchase-orders/generate` were removed; do not call them.

**GET /api/v1/shopping-list** — `MANAGE_PURCHASE_ORDERS` — no body.
Response: `ShoppingListLineView[]`:
`ingredientId` UUID, `ingredientName` string, `currentStock` decimal, `unit` string (KG|GM|L|ML|PIECES), `suggestedQty` decimal, `neededBy` date, `orderBy` date|null, `leadTimeDays` int|null, `orderUrgency` enum|null, `suggestedVendorId` UUID|null, `suggestedVendorName` string|null, `shortfall` decimal, `thresholdTopUp` decimal, `poOutstanding` decimal, `shortPurchaseOrders` string[], `included` bool, `edited` bool, `excludedSince` date|null, `buyPacks` `BuyPackView[]`, `packFromVendor` bool.
Chaining: `ingredientId`, `suggestedQty`, `unit`, `suggestedVendorId`, `neededBy` are what you carry to the PO.

**POST /api/v1/shopping-list** — `MANAGE_PURCHASE_ORDERS` — `AddShoppingListLineRequest`:
- `ingredientId` UUID **required**
- `suggestedQty` decimal **required, > 0**
- `suggestedVendorId` UUID, nullable (no screen sends it; server falls back to the ingredient's preferred vendor)
- **There is deliberately no `unit`** — the ingredient's `canonical_unit` is used.
→ 201 with the created `ShoppingListLineView`. Conflict `KMS-400131 ALREADY_ON_THE_SHOPPING_LIST` (409) if a line already exists; `KMS-400030 RESOURCE_NOT_FOUND` for an unknown ingredient.

**PATCH /api/v1/shopping-list/{ingredientId}** — `MANAGE_PURCHASE_ORDERS` — `UpdateShoppingListLineRequest`:
- `suggestedQty` decimal, nullable, must be > 0 when present
- `included` Boolean **required** (`@NotNull`; omitting it is `KMS-400001` naming the field, not a silent false)
→ 204. 404 `KMS-400030` for an unknown ingredient.

---

## 2. purchaseorder — `/api/v1/purchase-orders`
`PurchaseOrderController.java`. Everything is `MANAGE_PURCHASE_ORDERS`.

**GET /api/v1/purchase-orders?status=&vendorId=&openOnly=false** → `PurchaseOrderView[]`.
**GET /api/v1/purchase-orders/{id}** → `PurchaseOrderDetailView { order: PurchaseOrderView, lines: PurchaseOrderLineView[], events: PoEventView[], templeWhatsappEverSent: bool, deliveryScore: OrderDeliveryScore }`.

`PurchaseOrderView`: `id, poNumber, vendorId, vendorName, status (PoStatus), orderDate, neededBy, deliveryLocation, notes, cancelReason, vendorAbandoned, autoCancelled, sentAt, cancelledAt, createdAt, leadTimeDays, orderBy, orderUrgency, sentAfterLeadTime, closedAt, closeOutcome, closeNote`.
`PurchaseOrderLineView`: `id, ingredientId, ingredientName, description, quantity, unit, expectedPrice, arrivedOn, packSizeId, packLabel, packQuantity, packCount`. **`id` here is the `poLineId` you send to receiving/arrivals.**
`PoEventView`: `eventType, detail, actorName, createdAt`.

**`PoStatus` exact strings: `DRAFT`, `SENT`, `PARTIALLY_RECEIVED`, `RECEIVED`, `CLOSED`, `CANCELLED`.**

**POST /api/v1/purchase-orders** — `CreatePurchaseOrderRequest`:
- `vendorId` UUID **required**
- `neededBy` date, nullable
- `deliveryLocation` string ≤300, nullable
- `notes` string ≤1000, nullable
- `lines` `PoLineInput[]` **required, non-empty**

`PoLineInput`:
- `ingredientId` UUID, nullable
- `description` string ≤200, nullable
- **Exactly one of `ingredientId`/`description`** — neither is bean-validated; the service refuses both/neither with `KMS-400128 PURCHASE_LINE_NEEDS_A_SUBJECT` (400)
- `quantity` decimal **required, > 0**
- `unit` string **required, non-blank**
- `expectedPrice` decimal, nullable, ≥ 0
- `packSizeId` UUID, nullable; `packCount` decimal, nullable, > 0 — **both or neither**. When sent, the pack decides: stored `quantity = packCount × pack size`, in the pack's unit, and the `quantity` you sent is ignored (but still required). `unit` on a pack line is only the unit `expectedPrice` is per.
→ **201 `{ "id": UUID, "poNumber": "PO-2026-0041" }`.**
Other errors: `KMS-400162 SUPPLY_PACK_NOT_THIS_INGREDIENT` (400), `KMS-400014 NEEDED_BY_BEFORE_ORDER_DATE` (400), `KMS-400030` unknown vendor.

**PUT /api/v1/purchase-orders/{id}** — `UpdatePurchaseOrderRequest` = same minus `vendorId` (`neededBy`, `deliveryLocation`, `notes`, `lines` required non-empty). Lines are replaced wholesale. DRAFT only → else `KMS-400050 PO_NOT_EDITABLE` (409). → 204.

**POST /api/v1/purchase-orders/{id}/send** — body **optional**, `SendPurchaseOrderRequest { sendAnyway: boolean }` (primitive; absent = false). DRAFT only, else `KMS-400051 PO_INVALID_TRANSITION` (409). If the order is past the vendor's lead time (`orderUrgency == TOO_LATE`) and `sendAnyway` is false → **409 `KMS-400148 ORDER_PAST_VENDOR_LEAD_TIME`** with detail `{purchaseOrderId, vendorName, leadTimeDays, orderBy, neededBy}`. Repost with `{"sendAnyway": true}` to force; the row is then stamped `sent_after_lead_time = true`. → 204. Status becomes `SENT`.

**POST /api/v1/purchase-orders/{id}/cancel** — `CancelPoRequest`:
- `reason` string **required non-blank** ≤500
- `vendorAbandoned` boolean (primitive, absent = false — "Vendor Never Delivered this Order")
→ 204. Refusals: `KMS-400051` (wrong status), `KMS-400150 PO_PART_DELIVERED_CANNOT_CANCEL` (409 — use /close), `KMS-400147 PO_NEVER_SENT_TO_VENDOR` (409 when marking a no-show on an order never sent), `KMS-400149 PO_SENT_AFTER_LEAD_TIME` (409).

**POST /api/v1/purchase-orders/{id}/close** — for a `PARTIALLY_RECEIVED` order — `ClosePoRequest`:
- `outcome` **required**, one of `VENDOR_LET_US_DOWN` | `SHORTFALL_EXCUSED` | `AS_COMPUTED`
- `note` string ≤500 — **required (non-blank) unless `outcome == AS_COMPUTED`** (class-level `@AssertTrue`, reported as a field error)
→ 204. Status becomes `CLOSED` (terminal); the undelivered remainder returns to the shopping list.

**POST /api/v1/purchase-orders/{id}/arrivals** — `RecordArrivalsRequest { poLineIds: UUID[] }` **required non-empty**. Only for **described** lines (`ingredientId == null`) that have not already arrived, on a `SENT`/`PARTIALLY_RECEIVED` order. No date field — the temple's today is written to `arrived_on`. → 204. Errors: `KMS-400051`, `KMS-400030` naming the offending `poLineId`. Moves the order to `RECEIVED` if it was the last thing outstanding.

**POST /api/v1/purchase-orders/{id}/whatsapp** → 202 `{ "notificationId": UUID }`. `KMS-400130 VENDOR_HAS_NO_WHATSAPP_NUMBER`, `KMS-400056 PO_WHATSAPP_RATE_LIMITED`.

PO sheet (document package, same permission):
- **POST /api/v1/purchase-orders/{poId}/pdf?language=** → `{documentId,…}`; **GET /api/v1/purchase-orders/{poId}/documents**; **GET …/documents/{documentId}**; **GET …/documents/{documentId}/download** (streams); **GET /api/v1/purchase-orders/{poId}/print?language=** (HTML). `KMS-400155 PO_SHEET_NOT_READY`.

---

## 3. receiving

### 3a. Against one order — `ReceivingController`
**GET /api/v1/purchase-orders/{poId}/receipts** — `MANAGE_PURCHASE_ORDERS` → `GoodsReceiptView[]`.

**POST /api/v1/purchase-orders/{poId}/receipts** — **`RECEIVE_DELIVERIES`** — `ReceiveDeliveryRequest`:
- `idempotencyKey` string **required non-blank** ≤100 (a repeat returns the same receipt, 201 again)
- `deliveryNoteRef` string ≤500, nullable
- `note` string ≤1000, nullable
- `lines` `ReceiptLineInput[]` **required non-empty**

`ReceiptLineInput`:
- `poLineId` UUID **required**
- `receivedQty` decimal **required, ≥ 0**
- `rejectedQty` decimal **required, ≥ 0**
- `rejectReason` enum, nullable — **must be present iff `rejectedQty > 0`**
- `expiryDate` date, nullable
- `receivedDate` date, nullable — **this is the backdating field**; null means the temple's today. (A `unitPrice` field used to exist; it is accepted and ignored — no price at delivery, R-DEL-5.)

**`RejectReason` exact strings: `DAMAGED`, `SPOILED`, `WRONG_ITEM`, `OTHER`.**

→ **201 `GoodsReceiptView { id, purchaseOrderId, deliveryNoteRef, note, receivedByName, receivedAt, lines: GoodsReceiptLineView[] }`**, where `GoodsReceiptLineView = { id, poLineId, ingredientId, ingredientName, receivedQty, rejectedQty, rejectReason, unit, batchId, expiryDate, receivedDate, unitPrice, returnedQty }`. **`GoodsReceiptLineView.id` is the `goodsReceiptLineId` the invoice needs, and the `receiptLineId` a return needs.**

Validation/errors:
- order must be `SENT` or `PARTIALLY_RECEIVED` → else 409 `KMS-400051 PO_INVALID_TRANSITION`
- `KMS-400052 RECEIPT_LINE_NOT_ON_PO` (409)
- `KMS-400053 RECEIPT_LINE_EMPTY` (409) — both quantities zero, **or** a rejection with no reason, **or** a reason with no rejected quantity
- `KMS-400129 CANNOT_RECEIVE_A_DESCRIBED_LINE` (409) — a described (non-catalogue) line; use `/arrivals`
Side effects: a `PO_RECEIPT` stock movement + new batch per line with `receivedQty > 0`; rejected quantity **never enters stock**; PO status flips to `RECEIVED` when every line is fully accounted for, else `PARTIALLY_RECEIVED`.

### 3b. The Deliveries screen (one van, several orders) — `DeliveriesController`
**GET /api/v1/deliveries** — `RECEIVE_DELIVERIES` → `DeliveriesView { today, open: DeliveryLineView[], received: DeliveryReceiptView[], receivedLines: DeliveryLineView[], receivedFrom, hasOlder }`.
`DeliveryLineView`: `poLineId, poId, poNumber, vendorId, vendorName, ingredientId, itemName, unit, orderedQty, receivedQty, rejectedQty, returnedQty, stillToCome, neededBy, completedOn, packLabel, packQuantity, packCount, parts: DeliveryPartView[]`.
`DeliveryPartView`: `receiptId, receivedOn, receivedQty, rejectedQty, rejectReason, receivedByName`.
`DeliveryReceiptView`: `receiptId, poId, poNumber, vendorId, vendorName, receivedOn, receivedByName, lines: DeliveryReceiptLineView[]`; line = `poLineId, itemName, unit, receivedQty, rejectedQty, rejectReason, returnedQty, returns: DeliveryReturnView[]`; return = `quantity, reason, returnedOn`.

**GET /api/v1/deliveries/received?before=YYYY-MM-DD** — `RECEIVE_DELIVERIES` → `OlderDeliveriesView { received, receivedLines, receivedFrom, hasOlder }`.

**POST /api/v1/deliveries** — `RECEIVE_DELIVERIES` — `RecordDeliveryRequest`:
- `vendorId` UUID **required**
- `idempotencyKey` string **required non-blank** ≤100 (server derives `"<key>:<poId>"` per order)
- `lines` `RecordDeliveryLineInput[]` **required non-empty**: `poLineId` **required**, `receivedQty` **required ≥ 0**, `rejectedQty` **required ≥ 0**, `rejectReason` nullable (same iff rule), `expiryDate` nullable. **No `receivedDate` here** — the date is always the temple's today.
→ **201 `{ "receiptIds": [UUID, …] }`**, one per order the van touched, in line order.
Errors: `KMS-400164 DELIVERY_LINE_NOT_THIS_VENDOR` (409) when a line's order belongs to another vendor or is not `SENT`/`PARTIALLY_RECEIVED`; then everything `ReceivingService` raises.

### 3c. Returns after the fact — `GoodsReturnController`
**GET /api/v1/goods-receipts/{receiptId}/returns** — **`MANAGE_INVENTORY`** (not MANAGE_PURCHASE_ORDERS) → `GoodsReturnView[]`.
**POST /api/v1/goods-receipts/{receiptId}/returns** — **`MANAGE_INVENTORY`** — `ReturnGoodsRequest`:
- `idempotencyKey` string **required non-blank** ≤100
- `receiptLineId` UUID **required** (a `GoodsReceiptLineView.id`)
- `quantity` decimal **required, > 0** (positive; stored as a negative movement)
- `reason` **required**, `ReturnReason`
- `note` string ≤1000, nullable
**`ReturnReason` exact strings: `DAMAGED`, `SPOILED`, `WRONG_ITEM`, `NOT_DELIVERED`, `OTHER`.** (`NOT_DELIVERED` has no counterpart in `RejectReason`.)
→ **201 `GoodsReturnView { id, receiptId, receiptLineId, ingredientId, ingredientName, quantity, unit, reason, note, returnedByName, returnedAt, stockMovementId }`.**
Errors: `KMS-400140 RETURN_EXCEEDS_RECEIVED` (400) with detail `{receiptLineId, receivedQty, alreadyReturned, remaining, requested}`; `KMS-400030` for an unknown line. One line per request — no list.

---

## 4. invoice

### 4a. Capture — `VendorInvoiceController` (`/api/v1/vendor-invoices`)
**GET /api/v1/vendor-invoices?status=&overdue=false&owed=false** — `MANAGE_PURCHASE_ORDERS` → `VendorInvoiceView[]`.
**GET /api/v1/vendor-invoices/{id}** — `MANAGE_PURCHASE_ORDERS` → `VendorInvoiceDetailView`.
**GET /api/v1/vendor-invoices/billable-deliveries?vendorId=UUID** — `MANAGE_PURCHASE_ORDERS` → `BillableDeliveryView[]`:
`{ receiptId, purchaseOrderId, poNumber, receivedOn, receivedByName, lines: [{ goodsReceiptLineId, ingredientId, itemName, orderedQty, deliveredQty, unit, packSizeId, packLabel, packQuantity }] }`. This is the call that gives you the `receiptIds` and `goodsReceiptLineId`s the invoice must carry.

**POST /api/v1/vendor-invoices** — `MANAGE_PURCHASE_ORDERS` — `RecordInvoiceRequest`:
- `vendorId` UUID **required**
- `description` string ≤500, nullable
- `invoiceNumber` string **required non-blank** ≤100
- `invoiceDate` date **required**
- `dueDate` date, nullable
- `receiptIds` UUID[], **nullable/empty ⇒ a "direct" invoice** with hand-typed lines
- `lines` `InvoiceLineInput[]` **required non-empty**
- `gstAmount` decimal **required, ≥ 0** (send 0)
- `otherCharges` decimal **required, ≥ 0**
- `otherChargesNote` string ≤500, nullable
- `discount` decimal **required, ≥ 0**
- `grandTotal` decimal **required, > 0**
- `billAttachmentId` UUID **required** — an unclaimed `INVOICE_BILL` upload
- **No `amount`, no `scanRef`, no `purchaseOrderId`, no `subTotal`** (the server sums lines; the PO is set automatically when every billed delivery is on one order).

`InvoiceLineInput`:
- `goodsReceiptLineId` UUID, nullable — set on a delivered line, **must be null on a direct invoice**
- `ingredientId` UUID, nullable
- `description` string ≤200, nullable
- `billedQty` decimal **required, ≥ 0** (0 = "not billed"; may exceed delivered)
- `unit` string **required non-blank**
- `packSizeId` UUID nullable, `packCount` decimal nullable > 0 — when sent, `packCount × pack size` must equal `billedQty`
- `amount` decimal **required, ≥ 0**
- **No rate** — it is derived as amount ÷ billed qty.
Three shapes: delivered line (`goodsReceiptLineId`, no description), direct ingredient (`ingredientId` alone), one-off (`description` alone). Exactly one subject.

Rules:
- **For a delivery-backed invoice the lines must be exactly the delivered lines** — one per `goods_receipt_lines` row with `received_qty > 0` across the given receipts, none added, none missing, none duplicated → else 409 `KMS-400170 INVOICE_LINES_DONT_MATCH_DELIVERIES`.
- Totals must be exact: `subTotal + gstAmount + otherCharges − discount == grandTotal` → else 400 `KMS-400168 INVOICE_TOTALS_DONT_ADD_UP` with `{subTotal, addsUpTo, grandTotal}`.
- A delivery already billed by a standing invoice is refused: 409 `KMS-400169 INVOICE_DELIVERY_NOT_BILLABLE` (also raised on the concurrent-save race). Voiding an invoice releases its deliveries.
- `KMS-400013 INCOMPATIBLE_UNIT`, `KMS-400162 SUPPLY_PACK_NOT_THIS_INGREDIENT`, `KMS-400167 ATTACHMENT_NOT_USABLE` (bad/claimed/foreign upload) roll the whole save back.
→ **201 `RecordInvoiceResponse { invoice: VendorInvoiceView, duplicateWarning: boolean }`** (`duplicateWarning` = another *standing* invoice with the same number for this vendor).

`VendorInvoiceView`: `id, vendorId, vendorName, purchaseOrderId, poNumber, direct, description, invoiceNumber, invoiceDate, amount, dueDate, scanRef, status, expectedValue, variance, overdue, voidedAt, voidReason, creditedAmount, createdAt`.
`VendorInvoiceDetailView` = those plus `lines: InvoiceLineView[]`, `deliveries: InvoiceDeliveryView[]`, `subTotal, gstAmount, otherCharges, otherChargesNote, discount, grandTotal, bill: AttachmentView`.
`InvoiceLineView`: `id, goodsReceiptLineId, ingredientId, itemName, orderedQty, deliveredQty, billedQty, unit, packSizeId, packLabel, packQuantity, packCount, amount, rate, ratePerPack`.

**`InvoiceStatus` exact strings: `PENDING`, `PAID`, `VOIDED`.** A credited invoice is *not* a status — it stays PENDING/PAID with `creditedAmount` set.

**POST /api/v1/vendor-invoices/{id}/void** — **`MANAGE_VENDOR_PAYMENTS`** (stricter than capture) — `VoidInvoiceRequest { reason: string required non-blank ≤500 }` → 204. Refused with `KMS-400132 INVOICE_ALREADY_VOIDED` (409) and **`KMS-400154 INVOICE_HAS_UNREVERSED_PAYMENTS` (409)** when `paidToDate > 0` — reverse the payments first. Voiding releases the billed deliveries (`released_at`), so they become billable again.

**POST /api/v1/vendor-invoices/{id}/credit** — **`MANAGE_VENDOR_PAYMENTS`** — `CreditInvoiceRequest`:
- `amount` decimal **required, > 0** (the amount the bill goes *down* by)
- `reason` string **required non-blank** ≤500
→ 204. Refused with `KMS-400132` on a voided bill, and with `KMS-400001 VALIDATION_FAILED` (field `amount`, detail `maximum`) when the credit would take what is owed below what has already been paid. Credits accumulate into `creditedAmount`; the status is restated immediately (can flip to `PAID`).

### 4b. Payments — `InvoicePaymentController`
**POST /api/v1/vendor-invoices/{id}/payments** — **`MANAGE_VENDOR_PAYMENTS`** — `RecordInvoicePaymentRequest`:
- `paidOn` date **required**
- `amount` decimal **required, must be > 0** (service check → 400 `KMS-400174 PAYMENT_AMOUNT_NOT_POSITIVE`; negatives are no longer a correction mechanism)
- `method` **required**, `RecordInvoicePaymentRequest.PaymentMethod` — **exact strings `BANK_TRANSFER`, `UPI`, `CHEQUE`, `CASH`** (mirrored by V40's CHECK on `invoice_payments.method`)
- `reference` string ≤100, nullable
- `note` string ≤500, nullable
- `proofAttachmentId` UUID — **required for UPI / BANK_TRANSFER / CHEQUE**, kind `PAYMENT_PROOF`
- `receivedByName` string ≤200 — **required for CASH**
- `signedNoteAttachmentId` UUID — **required for CASH**, kind `CASH_SIGNED_NOTE`
- `receiverPhotoAttachmentId` UUID — **required for CASH**, kind `CASH_RECEIVER_PHOTO`
None of the four is `@NotNull`; the method-conditional check is in `InvoicePaymentService.proofFor` and answers `KMS-400001 VALIDATION_FAILED` with `fieldErrors` naming exactly `receivedByName` / `signedNoteAttachmentId` / `receiverPhotoAttachmentId` / `proofAttachmentId`. Files for the *other* method are silently ignored (left unclaimed).
→ **201 `{ "id": UUID }`**.
Other refusals: `KMS-400030` unknown invoice, `KMS-400132 INVOICE_ALREADY_VOIDED`, `KMS-400070 INVOICE_ALREADY_PAID` (409), `KMS-400069 INVOICE_OVERPAYMENT` (409, detail `outstanding`), `KMS-400167 ATTACHMENT_NOT_USABLE`. "Owed" = `amount − creditedAmount`, so a credit reduces the payable ceiling. Status is restated to `PAID` when paid == owed.

**POST /api/v1/vendor-invoices/{invoiceId}/payments/{paymentId}/reverse** — `MANAGE_VENDOR_PAYMENTS` — `ReverseInvoicePaymentRequest { reason: required non-blank ≤500 }` → 204. Appends a compensating **negative** row dated the same day as the original (the table is append-only; nothing is marked). Only a positive payment can be reversed → else 409 `KMS-400133 PAYMENT_ALREADY_VOIDED`.

**GET /api/v1/vendor-invoices/{id}/payments** — `MANAGE_VENDOR_PAYMENTS` → `InvoicePaymentView[]`:
`{ id, paidOn, amount, method, reference, note, recordedByName, reverses, reversedBy, reverseReason, receivedByName, attachments: AttachmentView[], createdAt }` (attachments ordered PAYMENT_PROOF, CASH_SIGNED_NOTE, CASH_RECEIVER_PHOTO).

**GET /api/v1/payables** — `MANAGE_VENDOR_PAYMENTS` → `PayableView[] { invoiceId, invoiceNumber, vendorName, amount, paidToDate, outstanding, dueDate, agingBucket }`.

### 4c. `payment` package (gateway plumbing — not vendor payments)
The `payment` package is **donation-gateway/webhook infrastructure**, not the vendor payment API:
- **POST /api/v1/public/webhooks/razorpay** — unauthenticated, signature-verified (`PaymentWebhookController`).
- **POST /api/v1/public/webhooks/payments/{token}** — unauthenticated, per-tenant webhook (`TenantPaymentWebhookController`).
- **GET /api/v1/settings/payments**, **PUT /api/v1/settings/payments** (`SavePaymentSettingsRequest`), **GET /api/v1/settings/payments/providers**, **GET /api/v1/settings/payments/events**, **POST /api/v1/settings/payments/test**, **POST /api/v1/settings/payments/webhook-secret** — all `MANAGE_TEMPLE_SETTINGS`.
- **GET /api/v1/ops/payment-events/dead-letter**, **POST /api/v1/ops/payment-events/{id}/replay**, **GET /api/v1/ops/payment-events/reconciliation** — `VIEW_PLATFORM_OPERATIONS` (platform ops, not a temple).
Codes: `KMS-400082 PAYMENT_CREDENTIALS_REJECTED`, `KMS-400083 PAYMENT_PROVIDER_UNSUPPORTED`, `KMS-400084 PAYMENT_NOT_CONFIGURED`, `KMS-500005 PAYMENT_GATEWAY_ERROR`.

---

## 5. attachment / document — the upload flow

**It is multipart/form-data. There is no signed URL and no base64 body.** Two POSTs, both returning `AttachmentView`:

1. **POST /api/v1/vendor-invoices/bill-uploads** — `MANAGE_PURCHASE_ORDERS` — `multipart/form-data`, single part named **`file`**. Kind is fixed to `INVOICE_BILL`.
2. **POST /api/v1/vendor-invoices/payment-uploads?kind=<KIND>** — `MANAGE_VENDOR_PAYMENTS` — same multipart part **`file`**; `kind` is a **query/form parameter read as text**, one of `PAYMENT_PROOF`, `CASH_SIGNED_NOTE`, `CASH_RECEIVER_PHOTO`. `INVOICE_BILL` is rejected here with `KMS-400001` and a `kind` field error listing the three allowed names.

`AttachmentView` (201): `{ id: UUID, kind, contentType, sizeBytes: long, originalName: string|null, uploadedAt }`. **`id` is what you put in `billAttachmentId` / `proofAttachmentId` / `signedNoteAttachmentId` / `receiverPhotoAttachmentId`.**

Rules (`AttachmentService`):
- Upload **first**, claim **on save**. The row is written with no parent; `POST /vendor-invoices` or `POST …/payments` claims it in the same transaction. A failed save leaves the upload unclaimed (harmless waste; nothing sweeps them).
- Missing/empty part → `KMS-400001` with field error `file` ("Choose a file to upload.").
- **Max 10 MB** (`MAX_BYTES = 10 * 1024 * 1024`) → `KMS-400166 ATTACHMENT_TOO_LARGE` (HTTP 413). Spring's multipart limits are set to 32MB so the service, not the container, answers.
- **Content type is sniffed from the first bytes, never from the declared type or extension.** Allowed: `image/jpeg`, `image/png`, `image/webp`, `image/heic`, `image/heif`, `application/pdf`. Anything else → `KMS-400165 ATTACHMENT_TYPE_NOT_ALLOWED` (400). AVIF is refused.
- A claim refuses a missing / already-claimed / wrong-kind / other-temple upload with `KMS-400167 ATTACHMENT_NOT_USABLE` (409). One bill per invoice; at most one file of each kind per payment.
- Reading back (streamed, never a public URL, `Content-Disposition: inline`, `Cache-Control: no-store, private`):
  - **GET /api/v1/vendor-invoices/{invoiceId}/bill** — `MANAGE_PURCHASE_ORDERS`
  - **GET /api/v1/vendor-invoices/{invoiceId}/payments/{paymentId}/attachments/{attachmentId}** — `MANAGE_VENDOR_PAYMENTS`

**Storage with `DOCUMENTS_STORAGE=local`** (`kms.documents.storage`, default `local`, `LocalDocumentStorage`): bytes are written to the local filesystem under `kms.documents.local-dir` = `${DOCUMENTS_LOCAL_DIR:${java.io.tmpdir}/kms-documents}` — i.e. by default `/tmp/kms-documents`. The storage key is **`tenants/<tenantId>/attachments/<attachmentId>`**, so a bill uploaded locally lands at `/tmp/kms-documents/tenants/<tenantId>/attachments/<attachmentId>` with no extension. `store()` creates parent directories; a write failure rolls the DB row back (`KMS-500…DOCUMENT_GENERATION_FAILED`); `open()` on a missing key is `KMS-400030`. With `DOCUMENTS_STORAGE=gcs` the same keys go to `kms.documents.bucket`.

**Generated documents** (a different mechanism from attachments — server-rendered PDFs): `DocumentView { id, kind, recipeId, purchaseOrderId, version, language, targetYield, status, error, createdAt, readyAt }`, `DocumentStatus` = `PENDING` | `READY` | `FAILED`. Request → poll → download. Endpoints: recipes `/api/v1/recipes/{recipeId}/pdf` + `/api/v1/documents/{id}[/download]` (`MANAGE_RECIPES`); PO sheets (above); work orders `/api/v1/work-orders` POST, `/languages`, `/documents?requestId=`, `/documents/{id}`, `/documents/{id}/download`, `/print` (all `ISSUE_INGREDIENTS`); job cards `/api/v1/job-cards` … (`MANAGE_MEAL_PLANS`); donation receipts (below). With `DOCUMENTS_RENDERER=stub` (default) a stub PDF is produced; `playwright` needs a browser.

---

## 6. ingredientrequest — `/api/v1/ingredient-requests`

**`IngredientRequestStatus` exact strings: `DRAFT`, `SUBMITTED`, `APPROVED`, `DENIED`, `ISSUED`.**
Machine: `DRAFT --submit--> SUBMITTED --approve--> APPROVED --issue--> ISSUED`; `SUBMITTED --deny--> DENIED`; `SUBMITTED --withdraw--> DRAFT`. **`DENIED` and `ISSUED` are terminal. There is no `PARTIALLY_ISSUED`, no `CANCELLED`, and — important for your simulation — there is NO "receive at the sister kitchen" step.** Issuing is the last event; it is what moves stock out of the store. A kitchen that gets only part of what it asked for raises a second request.

**GET /api/v1/ingredient-requests?status=** — `REQUEST_INGREDIENTS` → `IngredientRequestSummary[]`:
`{ id, reference ("IR-2026-0041"), kitchenId, kitchenName, neededOn, purpose, status, requestedBy, requestedByName, submittedAt, decidedByName, decidedAt, issuedAt, lineCount, dishCount }`.

**GET /api/v1/ingredient-requests/{id}** — `REQUEST_INGREDIENTS` → `IngredientRequestView { request: IngredientRequestSummary, lines: IngredientRequestLineView[], dishes: IngredientRequestDishView[], events: IngredientRequestEventView[] }`.
`IngredientRequestLineView`: `id, lineNo, ingredientId, ingredientName, quantity, unit, issuedQuantity (null until issued, may be 0), issuedUnit, note`. **`id` is the `lineId` the issue request uses.**
`IngredientRequestEventView`: `id, eventType, detail, actorName, at` — event types seen: `CREATED`, `SUBMITTED`, `WITHDRAWN`, `APPROVED`, `DENIED`, plus the issue event.

**POST /api/v1/ingredient-requests** — `REQUEST_INGREDIENTS` — `CreateIngredientRequest`:
- `kitchenId` UUID **required**
- `neededOn` date **required**
- `purpose` string ≤2000, nullable
- `lines` `IngredientRequestLineInput[]`, **nullable/empty allowed at draft**: `ingredientId` **required**, `quantity` **required ≥ 0.001**, `unit` **required** (`Unit` enum: `KG|GM|L|ML|PIECES`, must be the same family as the ingredient's canonical unit → else `KMS-400013 INCOMPATIBLE_UNIT`), `note` ≤500 nullable
- `dishes` `IngredientRequestDishInput[]`, nullable at draft: `dishName` **required non-blank** ≤200, `quantity` **required ≥ 0.001**, `unit` **required** (a food measure; servings is not allowed)
→ **201 `{ "id": UUID }`**. Errors: `KMS-400108 KITCHEN_NOT_FOUND`, `KMS-400109 KITCHEN_ARCHIVED`, `KMS-400110 KITCHEN_PLANS_ITS_OWN_MEALS` (409 — a kitchen on the meal planner does not request).

**PUT /api/v1/ingredient-requests/{id}** — `REQUEST_INGREDIENTS` — `UpdateIngredientRequest` = same fields as create (full replacement; line ids are not part of the shape). DRAFT or SUBMITTED only → `KMS-400113 INGREDIENT_REQUEST_NOT_EDITABLE`; author-only in DRAFT → `KMS-400112 NOT_YOUR_INGREDIENT_REQUEST` (403). → 204.

**DELETE /api/v1/ingredient-requests/{id}** — `REQUEST_INGREDIENTS` — DRAFT only, author only. → 204.

**POST /api/v1/ingredient-requests/{id}/submit** — `REQUEST_INGREDIENTS`, author only — no body → 204. Refused: `KMS-400117 INGREDIENT_REQUEST_EMPTY` (no lines), `KMS-400121 INGREDIENT_REQUEST_NEEDS_DISHES` (no dishes), `KMS-400113`.

**POST /api/v1/ingredient-requests/{id}/approve** — **`APPROVE_INGREDIENT_REQUESTS`** — body **optional** `DecisionNote { note: string ≤2000, optional }` → 204. Refused: `KMS-400119 INGREDIENT_REQUEST_NOT_SUBMITTED`, `KMS-400114 INGREDIENT_REQUEST_ALREADY_DECIDED`.

**POST /api/v1/ingredient-requests/{id}/deny** — **`APPROVE_INGREDIENT_REQUESTS`** — same optional `DecisionNote` → 204. Terminal.

**POST /api/v1/ingredient-requests/{id}/withdraw** — `REQUEST_INGREDIENTS` (author, or anyone who could approve) — no body → 204. SUBMITTED → DRAFT.

**POST /api/v1/ingredient-requests/{id}/issue** — **`ISSUE_INGREDIENTS`** — body **optional** (empty body issues every line at the approved quantity) — `RecordIssueRequest`:
- `lines` `IssuedLineInput[]`, optional — only lines that differ need appear: `lineId` **required**, `quantity` **required ≥ 0** (0 is legitimate and writes no movement), `unit` **required** (same family)
- `batchOverrides` `BatchOverride[]`, optional: `{ ingredientId: required, batchId: required }` — pins a batch to the front of FEFO
- `note` string ≤2000, optional
→ 204. Refused: `KMS-400115 INGREDIENT_REQUEST_NOT_APPROVED`, `KMS-400116 INGREDIENT_REQUEST_ALREADY_ISSUED`, **`KMS-400118 INSUFFICIENT_STOCK_TO_ISSUE`** (409), `KMS-400001` for an unknown `lineId`/unit. Writes stock movements with `reference_type = INGREDIENT_REQUEST`; status becomes `ISSUED`.

Work order sheet for the storekeeper: **POST /api/v1/work-orders** (`ISSUE_INGREDIENTS`) then `GET /api/v1/work-orders/documents?requestId=…`, `…/documents/{documentId}/download`, or `GET /api/v1/work-orders/print`.

---

## 7. donation and wishlist

### 7a. Wish list — `/api/v1/wishlist`, all `MANAGE_WISHLIST` (Temple Admin only)
- **GET /api/v1/wishlist?includeArchived=false** → `WishlistItemView[]`
- **GET /api/v1/wishlist/{id}** → `WishlistItemView`
- **POST /api/v1/wishlist** — `CreateWishlistItemRequest`: `title` **required non-blank** ≤200, `description` ≤2000 nullable, `imageRef` ≤500 nullable, `priceInr` **required, > 0**, `category` **required non-blank** — **DB CHECK allows only `CONSUMABLE`, `EQUIPMENT`, `OTHER`** (V41), `quantityWanted` int **required, > 0**, `note` ≤500 nullable → **201 `{ "id": UUID }`**
- **PUT /api/v1/wishlist/{id}** — `UpdateWishlistItemRequest`, identical fields → 204
- **DELETE /api/v1/wishlist/{id}** → 204 (archives, does not delete)
- **POST /api/v1/wishlist/reorder** — `ReorderWishlistRequest { itemIds: UUID[] required non-empty }` → 204

`WishlistItemView`: `{ id, title, description, imageRef, priceInr, category, quantityWanted, paidInr, sortOrder, status, note, createdAt }`. **Wish-list `status` strings: `ACTIVE`, `FULFILLED`, `ARCHIVED`.** Progress is rupees (`paidInr` against `priceInr × quantityWanted`), never units.

### 7b. Recording a donation
**POST /api/v1/donations** — **`MANAGE_INVENTORY`** (so kitchen staff at the gate can record one) — `RecordDonationRequest`:
- `anonymous` boolean (primitive)
- `donorName` string ≤200 — **required unless `anonymous`** (service check, field `donorName`)
- `donorPhone` string ≤120, nullable — normalised to `+91XXXXXXXXXX` when it can only be an Indian mobile (`CounterPhone`), otherwise stored as typed. Nulled out when `anonymous`.
- `donorEmail` string ≤200, nullable (nulled when anonymous)
- `cashAmountInr` decimal, nullable, **> 0 when present** — **the cash-in-person field**
- `estimatedValueInr` decimal, nullable, ≥ 0 — the temple's estimate of the *goods*
- `donatedOn` date **required**
- `notes` string ≤1000, nullable
- `wishlistItemId` UUID, nullable — **only valid with cash**
- `ingredients` `IngredientDonationLine[]`: `ingredientId` **required**, `quantity` **required > 0**, `unit` **required** string, `expiryDate` nullable
- `equipment` `EquipmentDonationLine[]`: `name` **required non-blank** ≤200, `notes` ≤1000 nullable
→ **201 `{ "id": UUID }`**.

Exclusivity, enforced in `DonationRecorder.validate` (all `KMS-400001` with a `field`/`reason` detail):
- must be cash **or** at least one item (field `items`)
- **cash and goods together are refused** — record them as two donations (field `cashAmountInr`)
- `wishlistItemId` without cash is refused (field `wishlistItemId`)
- the wish-list item must be `ACTIVE` → else `KMS-400068 WISHLIST_ITEM_UNAVAILABLE` (409)
- ingredient unit family → `KMS-400013 INCOMPATIBLE_UNIT`; unknown ingredient → `KMS-400030`

What gets written: cash ⇒ `donations.type = 'ONE_TIME'`, `payment_mode = 'CASH'`, `amount_inr = cashAmountInr`; goods ⇒ `type = 'IN_KIND'`, `estimated_value_inr`, one `DONATION_IN_KIND` stock movement + batch per ingredient line, one `DONATED` equipment register row per equipment line. Cash against a wish-list item sets `wishlist_item_id` and can flip the item to `FULFILLED`.
**Donation type strings (V38 CHECK): `IN_KIND`, `ONE_TIME`, `RECURRING`. Donation status strings: `PENDING`, `COMPLETED`, `FAILED`, `EXPIRED`** (a counter donation is written `COMPLETED` by default). Ledger `category` (derived): `ONE_TIME` | `WISHLIST` | `IN_KIND` | `MANUAL`.

**POST /api/v1/donations/{id}/void** — **`VOID_DONATION`** (Temple Admin only) — `VoidDonationRequest { reason: required non-blank ≤500 }` → 204. `KMS-400134 DONATION_ALREADY_VOIDED` (409). Reverses the in-kind stock in the same transaction; the row stays in the ledger, marked.

### 7c. Donor-facing (online) giving — "is there a public endpoint?"
**No unauthenticated donor endpoint exists any more.** The public, slug-addressed controller was withdrawn on 2026-08-29. What remains is `@PreAuthorize("isAuthenticated()")` — any signed-in member of the temple, no permission needed:
- **GET /api/v1/donations/page** → `{ templeName, is80gApproved, presets: [500,1100,2500,5000], platesToday, costPerPlateInr, spendShares }`
- **GET /api/v1/donations/wishlist** → `WishlistItemView[]` (active + recently fulfilled)
- **POST /api/v1/donations/one-time** — `AccountDonationRequest`: `amountInr` **required > 0**, `wants80g` boolean, `address` ≤500 nullable, `pan` ≤10 nullable (both only needed for 80G; `KMS-400004 INVALID_PAN`). The donor's name/phone/email come from the token — do **not** send them. → **201 `DonationCheckout { donationId, orderId, publicKey, amountInr, currency, provider }`**; the donation is `PENDING` until the gateway webhook confirms it (`COMPLETED`), expires after 30 min (`EXPIRED`).
- **POST /api/v1/donations/wishlist/{itemId}** — same `AccountDonationRequest` → same `DonationCheckout`, money applied to the item. `KMS-400068 WISHLIST_ITEM_UNAVAILABLE`.
For a simulation without a real gateway you will not get a `COMPLETED` online donation; **use `POST /api/v1/donations` with `cashAmountInr` (+ optional `wishlistItemId`) for anything that must land completed.**

### 7d. Reading donations
- **GET /api/v1/donations/ledger?from=&to=&type=&status=** — `VIEW_DONATIONS` → `LedgerRow[] { id, donatedOn, category, donorDisplay, amountInr, currency, paymentMode, providerRef, status, linkedTo, voided, voidReason }`
- **GET /api/v1/donations/ledger/period-summary?period=&financialYear=** — `VIEW_DONATIONS` → `PeriodSummary`
- **GET /api/v1/donations/ledger/donor/{donationId}** — `VIEW_DONATIONS` → that donor's history
- **GET /api/v1/donations/ledger/export?from=&to=&type=&status=** — `VIEW_DONATIONS` → CSV
- **GET /api/v1/donations/{donationId}** — `VIEW_DONATIONS` → `DonationDetail` (incl. `receiptNumber`, `hasPan`, `canBeReceipted`, `temple80gApproved`)
- **GET /api/v1/donations/{id}/pan** — `VIEW_DONATIONS` → `{ "pan": "…" }`, every read audited
- **POST /api/v1/donations/{donationId}/receipt** — `VIEW_DONATIONS` → 200 `{ documentId, status, receiptNumber }`, idempotent (same document and number on every press)
- **GET /api/v1/donations/{donationId}/receipt** — `VIEW_DONATIONS` → `DocumentView`, or **204** when none issued
- **GET /api/v1/donations/{donationId}/receipt/download** — `VIEW_DONATIONS` → PDF (404 until READY)
- **POST /api/v1/donations/{donationId}/receipt/send** — `VIEW_DONATIONS` → `{ "sent": boolean }` (false, not an error, when there is nobody to send to)
- **GET /api/v1/my-donations** and **GET /api/v1/my-donations/{donationId}/receipt/download** — **`VIEW_OWN_DONATIONS`** (volunteers). `MyDonation { id, kind ("MONEY"|"GOODS"), receivedOn, amount, description, receiptNumber }`. Ownership is matched on the account plus the token's *verified* phone/email, so a counter gift only appears if `donorPhone` was stored in exact E.164 form.

---

## 8. equipment — `/api/v1/equipment`
There is **no "service request" entity and no payment endpoint for a service**. A service is recorded after the fact, with its cost on the record. The schedule (who services it, how often) is set separately.

- **GET /api/v1/equipment?includeScrapped=false&location=&serviceStatus=** — `MANAGE_INVENTORY` → `EquipmentView[]`
- **GET /api/v1/equipment/{id}** — `MANAGE_INVENTORY` → `EquipmentDetailView { equipment: EquipmentView, history: EquipmentStateChange[], services: EquipmentServiceRecord[] }`
- **POST /api/v1/equipment** — `MANAGE_INVENTORY` — `CreateEquipmentRequest`: `name` **required non-blank** ≤200, `storageLocation` ≤120 nullable, `condition` enum nullable (defaults `GOOD`), `acquisitionDate` nullable, `source` enum nullable, `notes` ≤1000 nullable, `serialNumber` ≤120 nullable, `purchaseCostInr` ≥0 nullable (10.2 digits), `warrantyExpiry` nullable → **201 `{ "id": UUID }`**. `KMS-400015 EQUIPMENT_SERIAL_ALREADY_USED` (409).
- **PUT /api/v1/equipment/{id}** — `MANAGE_INVENTORY` — `UpdateEquipmentRequest` = same minus `condition` → 204.
- **POST /api/v1/equipment/{id}/condition** — `MANAGE_INVENTORY` — `ChangeConditionRequest { condition: required enum, reason: required non-blank ≤500 }` → 204. **Refused unconditionally once scrapped: `KMS-400043 EQUIPMENT_SCRAPPED` (409).**
- **POST /api/v1/equipment/{id}/reinstate** — **`REINSTATE_SCRAPPED_EQUIPMENT`** — `ReinstateEquipmentRequest { condition: required, reason: required non-blank ≤500 }` → 204. `KMS-400124 EQUIPMENT_NOT_SCRAPPED` (409) when it was not scrapped.
- **PUT /api/v1/equipment/{id}/service-schedule** — **`MANAGE_EQUIPMENT_SERVICING`** — `ServiceScheduleRequest`: `intervalCount` Integer nullable, 1–100; `intervalUnit` `ServiceInterval` nullable; **both or neither** (both null clears the schedule); `neverNeedsServicing` boolean primitive — **refused alongside a count**; `serviceCompany` ≤200 nullable (blank clears); `serviceCompanyPhone` ≤40 nullable → 204.
- **POST /api/v1/equipment/{id}/services** — **`MANAGE_EQUIPMENT_SERVICING`** — `RecordServiceRequest`: `servicedOn` date **required** (a future date, against the *temple's* today, → 400 `KMS-400016 SERVICE_DATE_IN_FUTURE`), `serviceCompany` ≤200 nullable, `workDone` ≤1000 nullable, **`costInr` ≥0 nullable, 10 integer digits / 2 fraction** → **201 `{ "id": UUID }`**. Append-only: no edit, no delete, no "last serviced" field. **This `costInr` is the whole of "paying for a service" — it is a recorded cost, not a payable, and it never enters the vendor invoice/payment tables.**

Enums: `EquipmentCondition` = `GOOD`, `NEEDS_REPAIR`, `IN_REPAIR`, `SCRAPPED`. `EquipmentSource` = `PURCHASED`, `DONATED`. `ServiceStatus` (derived, never stored) = `OK`, `DUE_SOON`, `OVERDUE`, `NOT_SCHEDULED`, `NOT_SERVICED`. `ServiceInterval` = `DAYS`, `WEEKS`, `MONTHS`, `YEARS`. `NextServiceBasis` = `SERVICED`, `PURCHASED`, `NONE`.
`EquipmentView`: `id, name, storageLocation, condition, acquisitionDate, source, notes, createdAt, serialNumber, purchaseCostInr, warrantyExpiry, neverNeedsServicing, serviceIntervalDays, serviceIntervalUnit, serviceIntervalCount, serviceCompany, serviceCompanyPhone, lastServicedOn, nextServiceOn, nextServiceBasis, serviceStatus`.
`EquipmentServiceRecord`: `id, servicedOn, serviceCompany, workDone, costInr, actorUserId, actorName, createdAt`. `EquipmentStateChange`: `id, fromCondition, toCondition, reason, actorUserId, actorName, createdAt`.

---

## 9. Answers to the specific questions

### Meal plan → shopping list → purchase order
1. **Plan the meals.** `POST /api/v1/meals` (`MANAGE_MEAL_PLANS`) with `SaveMealRequest`: `planDate` (required), `mealKindId` (required), `readyBy`, `eventName`, head counts `adults`/`children`/`seniors`, `kitchens: MealKitchenDraft[]`, and **`dishes: DishDraft[]` (required non-empty)** where each is `{ id?, recipeId (required), targetYield (required, >0, ≤50000), kitchenId? }`. → `{ id, … }`.
   Optional check: `GET /api/v1/meal-plans/shortfall` and `GET /api/v1/meal-plans/sufficiency` show the demand this creates (`ShortfallItem { ingredientId, ingredientName, shortBy, unit }`).
2. **Read the list.** `GET /api/v1/shopping-list`. Nothing to regenerate — the meal-plan shortfall, the reorder-level top-up (× 1.2 safety factor) and any closed-order balance are merged per ingredient on every read, rounded up to a buyable amount, and **any ingredient already covered by a live PO (DRAFT/SENT/PARTIALLY_RECEIVED with a balance) is omitted**. Take `ingredientId`, `suggestedQty`, `unit`, `suggestedVendorId`, `neededBy` per line.
3. **Optionally adjust.** `PATCH /api/v1/shopping-list/{ingredientId}` with `{suggestedQty, included}`, or `POST /api/v1/shopping-list` with `{ingredientId, suggestedQty}` for something nothing suggested.
4. **Raise the order — one POST per vendor.** Group the included lines by `suggestedVendorId` and send `POST /api/v1/purchase-orders` with `{ vendorId: <suggestedVendorId>, neededBy: <earliest line's neededBy>, deliveryLocation?, notes?, lines: [{ ingredientId: <line.ingredientId>, quantity: <line.suggestedQty>, unit: <line.unit>, expectedPrice?, packSizeId?, packCount? }] }`. Read back **`{ id, poNumber }`**. The created order removes those ingredients from the next `GET /api/v1/shopping-list`.
5. **Send it.** `POST /api/v1/purchase-orders/{id}/send` (empty body). If refused with `KMS-400148`, repeat with `{"sendAnyway": true}`.

### Recording a delivery — full, partial, late, rejected quantity
Two doors, one code path (`ReceivingService.receive`); both need `RECEIVE_DELIVERIES`.
- **Per order:** `POST /api/v1/purchase-orders/{poId}/receipts` with `{idempotencyKey, deliveryNoteRef?, note?, lines:[{poLineId, receivedQty, rejectedQty, rejectReason?, expiryDate?, receivedDate?}]}`.
- **Per van (several orders):** `POST /api/v1/deliveries` with `{vendorId, idempotencyKey, lines:[{poLineId, receivedQty, rejectedQty, rejectReason?, expiryDate?}]}` → `{receiptIds:[…]}`.
- **Full:** every line's `receivedQty` equals the ordered `quantity`; order flips to **`RECEIVED`**.
- **Partial:** send less than ordered, or omit lines. Order flips to **`PARTIALLY_RECEIVED`** and the balance stays with the vendor. A second delivery posts another receipt with a *new* `idempotencyKey`. To stop waiting: `POST /api/v1/purchase-orders/{id}/close` with an outcome → **`CLOSED`**, which releases the remainder back to the shopping list.
- **Late:** lateness is not a field on the receipt. Two distinct things: (a) *we ordered late* — `POST …/send` refuses with `KMS-400148` and, when forced with `sendAnyway: true`, stamps `sentAfterLeadTime = true` so the delay is not held against the vendor; (b) *they delivered late* — derived by comparing `goods_receipts.received_at` / `receivedDate` to `neededBy`. To simulate a delivery that arrived on a past day, use the **per-order** endpoint and set `receivedDate` on the line; the `/deliveries` endpoint always dates the receipt the temple's today and has no such field. Described (non-catalogue) lines are "arrived" via `POST /api/v1/purchase-orders/{id}/arrivals`, which also has no date field.
- **Rejecting for poor quality:** on the same line set `rejectedQty > 0` and **`rejectReason`** to one of `DAMAGED` | `SPOILED` | `WRONG_ITEM` | `OTHER`. A rejection without a reason, a reason without a rejection, and a line with both quantities zero are all `KMS-400053 RECEIPT_LINE_EMPTY`. Rejected goods never enter stock and the rejected quantity is **still owed** (it does not count toward completion).

### A return after the fact
`POST /api/v1/goods-receipts/{receiptId}/returns` with **`MANAGE_INVENTORY`** (note: not the receiving permission), body `{ idempotencyKey, receiptLineId, quantity (positive), reason, note? }`, reason ∈ `DAMAGED|SPOILED|WRONG_ITEM|NOT_DELIVERED|OTHER`. One line per request. It books a **negative `RETURN_TO_VENDOR` stock movement against the original batch**; the receipt row is never altered. `KMS-400140 RETURN_EXCEEDS_RECEIVED` tells you the `remaining` when you overshoot.

### Invoice against a delivery, credit note, void
1. `GET /api/v1/vendor-invoices/billable-deliveries?vendorId=…` → the receipts and their `goodsReceiptLineId`s.
2. `POST /api/v1/vendor-invoices/bill-uploads` (multipart `file`) → `{id}` = `billAttachmentId`.
3. `POST /api/v1/vendor-invoices` with `receiptIds` and **exactly one line per delivered line** (each naming its `goodsReceiptLineId`, with `billedQty`, `unit`, `amount`), plus `gstAmount`, `otherCharges`, `discount`, `grandTotal` that **add up exactly**, `invoiceNumber`, `invoiceDate`, `billAttachmentId`. → 201 `{invoice, duplicateWarning}`; `invoice.id` is what you pay against. A *direct* invoice is the same call with `receiptIds` omitted and hand-typed lines (`ingredientId` alone, or `description` alone).
4. **Credit note:** `POST /api/v1/vendor-invoices/{id}/credit` `{amount, reason}` — `amount` is the reduction, accumulates in `creditedAmount`, cannot take what's owed below what's paid, may flip the status to `PAID`. The invoice stays in the pay cycle.
5. **Void:** `POST /api/v1/vendor-invoices/{id}/void` `{reason}` — status `VOIDED` (terminal), the billed deliveries are released and become billable again. **Reverse every payment first** or you get `KMS-400154`.

### Payment, methods, proof for cash
`POST /api/v1/vendor-invoices/{id}/payments` (**`MANAGE_VENDOR_PAYMENTS`**) with `{paidOn, amount (>0), method, reference?, note?, …proof}`.
**Methods (exact): `BANK_TRANSFER`, `UPI`, `CHEQUE`, `CASH`.**
- UPI / BANK_TRANSFER / CHEQUE: upload the screenshot or bank confirmation via `POST /api/v1/vendor-invoices/payment-uploads?kind=PAYMENT_PROOF` and send the returned id as **`proofAttachmentId`**.
- CASH: **three things are required** — `receivedByName` (who took the cash), `signedNoteAttachmentId` (upload with `kind=CASH_SIGNED_NOTE`) and `receiverPhotoAttachmentId` (upload with `kind=CASH_RECEIVER_PHOTO`). There is deliberately no field for the kind of ID shown.
Missing proof → `KMS-400001` with field errors on exactly those names. Amount must not exceed `amount − creditedAmount − paidToDate` (`KMS-400069`), the invoice must not be VOIDED (`KMS-400132`) or already fully paid (`KMS-400070`). Undo with `POST …/payments/{paymentId}/reverse {reason}`.

### Attachment upload end to end
`POST …/bill-uploads` or `…/payment-uploads?kind=…` as **multipart/form-data with a single part named `file`** → 201 `AttachmentView{id,…}` (row written with no parent, bytes written to storage in the same transaction) → send the `id` in the invoice or payment body → the saving service **claims** it (sets `invoice_id` or `payment_id`) inside the same transaction; a refused save leaves the upload unclaimed → read back only through `GET …/bill` or `GET …/payments/{paymentId}/attachments/{attachmentId}`, which stream the bytes with the permission checked. Limits: 10 MB, and jpeg/png/webp/heic/heif/pdf detected **from the bytes**. With `DOCUMENTS_STORAGE=local` the bytes go to `${DOCUMENTS_LOCAL_DIR:-$TMPDIR/kms-documents}/tenants/<tenantId>/attachments/<attachmentId>` (extensionless files, directories created on demand); nothing is ever served from a URL to that directory.

### Ingredient-request lifecycle
`POST /api/v1/ingredient-requests` (DRAFT) → `POST …/{id}/submit` (SUBMITTED) → `POST …/{id}/approve` or `…/deny` (APPROVED / DENIED) → `POST …/{id}/issue` (ISSUED, stock leaves the store). `POST …/{id}/withdraw` takes SUBMITTED back to DRAFT; `DELETE` only from DRAFT. Statuses: `DRAFT`, `SUBMITTED`, `APPROVED`, `DENIED`, `ISSUED`. **There is no receive-at-the-sister-kitchen endpoint** — issuing is the terminal event and the only one that moves stock; the printed work order (`/api/v1/work-orders`) is what travels with the goods.

### Donations — who can record, and is there a donor endpoint
Recording (`POST /api/v1/donations`) is **`MANAGE_INVENTORY`** — Temple Admin, Kitchen Manager and Kitchen Staff, because a sack of rice arrives at the gate. Reading the ledger and issuing receipts is **`VIEW_DONATIONS`** (Temple Admin only). Voiding is **`VOID_DONATION`** (Temple Admin only). A general/cash-in-person gift is the same endpoint: cash sets `cashAmountInr`; goods set `ingredients`/`equipment` + `estimatedValueInr`; **never both**. Against a wish-list item: cash plus `wishlistItemId` (the item must be `ACTIVE`). The donor-facing surface is **authenticated-only** (`isAuthenticated()`): `GET /api/v1/donations/page`, `GET /api/v1/donations/wishlist`, `POST /api/v1/donations/one-time`, `POST /api/v1/donations/wishlist/{itemId}` — all gateway checkouts that stay `PENDING` until a webhook confirms them. **No public/anonymous donation endpoint exists.**

---

## 10. Things that will bite a seeding script
- `POST /purchase-orders/generate` and `POST /shopping-list/regenerate` **do not exist** (asserted absent by tests).
- A PO line is either `ingredientId` or `description`, never both — and a described line can never be received, only "arrived".
- Every receipt, delivery and return needs a fresh `idempotencyKey`; reusing one returns the earlier record instead of creating a new one (per `(po_id, idempotency_key)`, and per `<key>:<poId>` on `/deliveries`).
- An invoice for deliveries must carry **all** their delivered lines and nothing else, and the totals must balance to the paisa.
- `MANAGE_VENDOR_PAYMENTS` is Temple Admin only — a Kitchen Manager can record an invoice but gets 403 on `/payments`, `/void`, `/credit`, `/payables`, and on `payment-uploads`.
- Dates that matter (arrivals, deliveries via `/deliveries`, service "future" checks) are computed against the **temple's** timezone via `TempleClock`, not the server's.
