# E10-S4-API — assistant drafts with a working Confirm, backend: the general mechanism and the purchase-order kind

Worktree `/Users/Rajeev/Workspace/kms-e10-s3-runner`, branch `e10-s4-api` (from `agentic-mvp` dfbb32bd). Not committed.

**Files changed** (all inside `backend/**`, `runner/prompts/kitchen-assistant.md` and this file):

New
- `backend/src/main/resources/db/migration/V163__assistant_drafts_purchase_order.sql` — kind may be `PURCHASE_ORDER`; `result_label` / `result_href` columns, both-or-neither and only on CONFIRMED. **V163 was not reserved for me; see "Needs Rajeev".**
- `backend/src/main/java/org/iskcon/kms/assistant/action/AssistantProposal.java` — the card, field for field the browser's `AssistantProposal`, plus `result {label, href}`.
- `.../assistant/action/PendingActionHandler.java` — one interface per kind: `kind()`, `permission()`, `execute(actor, request)`.
- `.../assistant/action/PendingActionService.java` — propose / confirm / reject / forRun / forConversation.
- `.../assistant/action/PurchaseOrderAction.java` — the first handler, and its drafting (vendor match, shopping-list lines with the UI's pack and price logic, named lines, the card).
- `.../assistant/action/AssistantActionController.java` — `POST /api/v1/assistant/actions/{id}/confirm|reject`, `hasAuthority('CONFIRM_ASSISTANT_ACTION')`.
- `.../assistant/mcp/PurchaseOrderTools.java` — `get_shopping_list`, `find_vendor`, `propose_purchase_order`.
- `backend/src/test/java/org/iskcon/kms/assistant/action/AssistantActionIT.java` — 11 tests.

Changed
- `.../assistant/mcp/McpServerConfiguration.java` — the three tools' documentation and registration; a per-tool "not allowed" message (the old one names the stock screen).
- `.../assistant/api/AssistantChatService.java` — `proposal` events before `language` and `done`.
- `.../assistant/api/AssistantConversationStore.java`, `AssistantViews.java` — `MessageView.proposals`.
- `.../assistant/api/AssistantController.java` — comments only.
- `backend/src/main/java/org/iskcon/kms/audit/AuditEntityType.java` — `ASSISTANT_ACTION`. Not one of the reserved shared files, but shared; no frontend or migration lists entity types (grepped).
- Tests: `AssistantApiIT` (+1 test, SSE order), `InventoryToolsIT` (new `toolSpecifications` argument), `McpEndToEndIT` (tool list now six; it used `findValuesAsText("name")`, which also caught `find_vendor`'s argument called `name`).
- `runner/prompts/kitchen-assistant.md` — a "Shopping list and purchase orders" section; "What you cannot know" updated.

## How it works, in short

- Payload stored is `{request, card}`: the `CreatePurchaseOrderRequest` exactly as `POST /purchase-orders` takes it, and the card exactly as shown. `payload_sha256` is computed by the database from `payload::text` in the INSERT; Confirm reads that same text, hashes it in Java, refuses on mismatch, then parses and runs that text. Editing the card is refused like editing the request.
- Idempotency key: run id + kind + hash of the payload, `ON CONFLICT DO NOTHING`, then read back. A retried tool call returns the same draft.
- The run is not in the agent token, so propose finds the conversation's `RUNNING` run for that person. No run, refused in words.
- Confirm: row locked `FOR UPDATE` via a join on the conversation's `user_id` (404 otherwise; RLS removes other temples). Order: CONFIRMED → return the same card and result (idempotent, no error, nothing run); REJECTED → 400196; past `expires_at` → 400194; then the handler's own permission from the caller's authentication (403 otherwise); then the hash → 400195; then `PurchaseOrderService.createManual` as the person, with the request bean-validated the way `@Valid` does on the endpoint. Then CONFIRMED + `result_label`/`result_href`, audit `ASSISTANT_ACTION_CONFIRMED`. One transaction, so a refusal from the order service leaves the draft PROPOSED.
- Reject: PROPOSED and unexpired → REJECTED + audit `ASSISTANT_ACTION_REJECTED`; already decided → 400196; expired → 400194.
- EXPIRED is computed on read (`status = 'PROPOSED' AND expires_at <= now()`); nothing writes it.
- Default lines: the vendor's ticked shopping-list lines, the shopping page's `panelLines` logic (packs as one line per pack size with `packSizeId`/`packCount`, quantity = count × size; otherwise the readable unit, 3000 gm as 3 Kg; price per that unit, vendor's pack price where quoted). Needed by = earliest line date; if that has passed, today, and the tool says so.
- Card: quantities in Kg/L for anything weighed or measured, so `rate` is always per Kg/L/piece/pack; facts are Vendor, Needed by ("20 October 2026"), Total (Indian grouping, hint "At the vendor's list prices" or how many lines have none). `confirmSays` = "Confirm saves a draft order. Nothing is sent." (45 characters).

## Commands run and what came back

The brief's command, run directly (this worktree has its own `build/`, as in E10-T12):

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; cd backend && ./gradlew test --tests 'org.iskcon.kms.assistant.*' --tests '*ErrorCode*' --tests '*NextStepPermission*' --tests '*AccessControlEnforcementIT' --tests '*PermissionBeforeValidationIT' --tests '*RowLevelSecurityIT' --tests '*UnitLabelAgreementTest' --tests 'org.iskcon.kms.purchaseorder.*'
```

```
  Test summary
────────────────────────────────────────────────────────
  Total:    1361
  Passed:   1361
  Failed:   0
  Skipped:  0
  Result:   SUCCESS
────────────────────────────────────────────────────────

BUILD SUCCESSFUL in 2m 13s
5 actionable tasks: 3 executed, 2 up-to-date
```

Per class from `build/test-results/test/*.xml` (tests failures errors skipped):

```
AssistantActionIT 11 0 0 0
AssistantApiIT 17 0 0 0
ConversationToolsIT 5 0 0 0
InventoryToolsIT 8 0 0 0
McpEndToEndIT 7 0 0 0
AccessControlEnforcementIT 16 0 0 0
PermissionBeforeValidationIT 97 0 0 0
ErrorCodeTest 1007 0 0 0
NextStepPermissionTest 6 0 0 0
UnitLabelAgreementTest 2 0 0 0
PurchaseOrderIT 26 0 0 0
PurchaseOrderPackLineIT 13 0 0 0
RowLevelSecurityIT 33 0 0 0
TOTAL tests 1361 failures 0 errors 0 skipped 0
```

The previous run of the same command was red, and is why the last code change exists: `UnitLabelAgreementTest` 2 1 0 0, flagging every `.label()` in my new files (it reads every no-argument `label()` in a file that uses `Unit`). Fixed by using `label(count)` where a figure is beside the unit, a method reference for the pack name, and rewording one refusal; nothing added to its ALLOWED list.

### Real run: runner, local API from this worktree, real model

Setup as in E10-LANG: `kms_s4` created as a copy of `kms_try`; API `bootRun` on 8097 with `tools/local-backend.sh`'s safety exports and its own token key; runner `node dist/src/server.js` on 18091 with the stored login. Flyway on the copy:

```
Migrating schema "public" to version "162 - a person chooses their language"
Migrating schema "public" to version "163 - assistant drafts purchase order"
Successfully applied 2 migrations to schema "public", now at version v163 (execution time 00:00.016s)
Started KmsApplication in 3.715 seconds (process running for 3.862)
```

The script (`scratchpad/s4check.py`) did what `AssistantConversationStore.begin`/`finish` do by SQL (question saved, run `RUNNING`; reply saved under the run, run `SUCCEEDED`), minted an agent token with `mint-token.py`, and POSTed the prompt (language line + history, as `promptWithHistory` builds it) to `/run`. The seeded shopping list has one vendor with a line, Anand Masala Depot.

```
=== USER: make a purchase order for Anand Masala   (run f7ae64aa-...)
    4.7s tool calls: [["find_vendor", {"name": "Anand Masala"}], ["propose_purchase_order", {"vendor": "Anand Masala Depot"}]]
    ASSISTANT: A draft purchase order for Anand Masala Depot is ready for you to check and confirm below. It has 1 line: Almond, chopped, 0.05 Kg. The vendor has no list prices for this item, so there is no total.
    drafts stored by this run: a6856441-4086-4bcb-a2ab-b9b3362cc573 PURCHASE_ORDER PROPOSED

=== USER: I need to raise a purchase order   (run 2c1d987c-...)
    2.8s tool calls: []
    ASSISTANT: Which vendor is the order for? If you'd like, I can list the active vendors.
Also, should it use the vendor's lines from the shopping list, or do you have specific items and amounts in mind?
    drafts stored by this run: none

=== USER: Anand Masala Depot. 2 kg of chopped almonds, needed by 2026-10-20   (run 5f1652fb-...)
    5.4s tool calls: [["find_vendor", {"name": "Anand Masala Depot"}], ["get_inventory", {"item_name": "almond"}], ["propose_purchase_order", {"vendor": "Anand Masala Depot", "lines": [{"item": "Almond, chopped", "quantity": 2, "unit": "Kg"}], "needed_by": "2026-10-20"}]]
    ASSISTANT: A draft purchase order for Anand Masala Depot is ready for you to check and confirm below. It has 1 line: 2 Kg of Almond, chopped, needed by 2026-10-20. ...
    drafts stored by this run: fe0e8e4b-6003-4367-ac26-5b64cb6ebe2a PURCHASE_ORDER PROPOSED
```

Then over HTTP to the local API as the seeded Temple Admin with a real Firebase ID token (custom token signed by the Firebase admin service account, exchanged for an ID token; the files were deleted afterwards):

```
--- GET conversation (proposals under the reply)
ASSISTANT [{"actionId": "fe0e8e4b-...", "kind": "PURCHASE_ORDER", "status": "PROPOSED", "title": "Purchase order for Anand Masala Depot", "facts": [{"label": "Vendor", "value": "Anand Masala Depot", "hint": null}, {"label": "Needed by", "value": "20 October 2026", "hint": null}, {"label": "Total", "value": "—", "hint": "This vendor has no list prices for these items"}], "lines": [{"name": "Almond, chopped", "localName": "ಹೆಚ್ಚಿದ ಬಾದಾಮಿ", "quantity": 2, "unit": "Kg", "rate": null, "amount": null}], "confirmSays": "Confirm saves a draft order. Nothing is sent.", "expiresAt": "2026-10-09T02:43:18.863559Z", "result": null}]
--- confirm
{..."status":"CONFIRMED",...,"result":{"label":"PO-2026-0048","href":"/orders/101db003-363c-4712-bb8b-7649852135f9"}} HTTP 200
--- confirm again
{..."status":"CONFIRMED",...,"result":{"label":"PO-2026-0048","href":"/orders/101db003-363c-4712-bb8b-7649852135f9"}} HTTP 200
--- reject the other draft
{"actionId":"a6856441-...","status":"REJECTED",...,"result":null} HTTP 200
--- confirm the rejected one
{"code":"KMS-400196","message":"This draft has already been confirmed or rejected.","action":"Open the conversation again to see what happened to it. Nothing more was saved.","fieldErrors":[]} HTTP 409
--- the order in the database
  po_number   | status |       vendor       | needed_by  |              created_by              | quantity | unit |      item
 PO-2026-0048 | DRAFT  | Anand Masala Depot | 2026-10-20 | ikms.temple-admin.1@trading4good.org |    2.000 | KG   | Almond, chopped
(1 row)
 ASSISTANT_ACTION_CONFIRMED | ASSISTANT_ACTION |     1
 ASSISTANT_ACTION_REJECTED  | ASSISTANT_ACTION |     1
```

Cleanup: API and runner stopped (8097 and 18091 free), `kms_s4` dropped (`kms`, `kms_seed`, `kms_try` remain, untouched), token files deleted. The runner log has 0 occurrences of the token key. The real run was before the `UnitLabelAgreementTest` fix; that fix changed only a refusal's wording, where the tool's `total` string is read from, and `label()` to `label(count)`, which gives the same "Kg" here. The tests above cover the code as it now stands.

## Acceptance criteria

- Confirm creates exactly one DRAFT PO with the right lines: `AssistantActionIT.confirmCreatesOneOrder` (rice 25 Kg at 45; sugar kept in gm, 2500 gm, ordered as 2.5 Kg at 50 / Kg), and the real run (PO-2026-0048).
- Double confirm creates one, and is idempotent (200, same body, not an error): same test, body compared equal, one PO, one audit row; real run.
- Tampered payload refused 400195, for the request and for the card: `tamperedRefused`.
- Expired refused 400194, and reads back EXPIRED under the reply: `expiredRefused`.
- Reject then confirm refused 400196 (and reject again 400196): `rejectThenConfirm`; real run.
- Another person at the temple, another temple: 404 KMS-400030 for both endpoints, body names nothing: `notYourDraft`.
- Volunteer refused: `volunteerRefused` (403 both).
- CONFIRM_ASSISTANT_ACTION without MANAGE_PURCHASE_ORDERS: no role has that combination (all three roles with CONFIRM also have MANAGE_PURCHASE_ORDERS), so tested on the service with exactly that authentication: `confirmNeedsTheThingsOwnPermission` (AccessDeniedException, no PO, still PROPOSED).
- Propose needs `agent:propose` and MANAGE_PURCHASE_ORDERS: `proposeNeedsTheProposeScope`. Retried propose makes one draft: `proposeIsIdempotentWithinARun`. Card built server-side with Kannada name, rates per Kg, total: `proposeFromShoppingList`. Named lines, unit refusal, unknown vendor, past date: `proposeNamedLines`.
- SSE `proposal` after the text and before `done`, none when no draft, and the same card under the reply on `GET conversation`: `AssistantApiIT.proposalEventBeforeDone`.
- Prompt: ask for missing details, one draft per request, never claim it is saved, in the conversation's language. Real run turn 2 shows it asking; turns 1 and 3 say "ready for you to check and confirm below".

## Not done / needs Rajeev

- **Migration V163 was taken by me, not reserved.** The brief gave no migration slot, and the work cannot be done without one (V160's CHECK allows only SHOPPING_LIST and STOCK_ADJUSTMENT). In this worktree nothing else uses V163. The conductor must check no other branch has a V163 before merging.
- `AuditEntityType.ASSISTANT_ACTION` added (shared enum, not on the reserved list).
- Not built: `MEAL_PLAN` and the other kinds (one handler + its tool + a migration line each, as designed); `propose_shopping_list_change`; `propose_send_po`.
- A draft made during a run that then fails is stored but never shown (no reply to hang it under); it expires unconfirmed.
- The UI is the parallel builder's. No hand smoke test in a browser by me; the API was driven over HTTP with a real Firebase token as above.
- Rajeev's mid-task messages (tables, pop-out, missing meal-plan tools) are frontend or later-stage work outside this contract; answered in my report, not built.
