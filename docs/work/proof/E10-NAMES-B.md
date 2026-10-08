# E10-NAMES-B — the assistant names items in the conversation's language, from stored names

Worktree `/Users/Rajeev/Workspace/kms-e10-names`, branch `e10-names-b` (from `agentic-mvp` 5a0a557f).
Nothing committed, pushed or deployed.

Rajeev (2026-10-08): item names must appear in the conversation's language and be stable. He asked
for Telugu and got English names, because the tools only carried `name_kn` and the prompt said
"English names otherwise".

## Files changed (all inside the contract: backend/**, runner prompt, runner README, tests, this proof)

- New `backend/.../assistant/names/ConversationNames.java`: reads the conversation's language
  (`assistant_conversations.language` for the token's `conv` and person, under RLS; English if not
  found or the caller is not an agent), looks up the glossary for that language, and gives each item
  `name_local` or `name_pending: true`. `ensure(tenant, language)` calls `StockNameFillService.ensureNames`
  for any non-English language and swallows failures (a tool answer is never lost to the fill).
- `assistant/mcp/InventoryTools.java`: `name_kn` replaced by `name_local` + `name_pending`; search
  matches the stored local name; asks for the fill when an item is unnamed.
- `assistant/mcp/PurchaseOrderTools.java`: `get_shopping_list` the same; `propose_purchase_order`'s
  lines carry `name_local` / `name_pending`.
- `assistant/action/PurchaseOrderAction.java`: the card's `localName` is the conversation's language
  (`names.forAgent(agent)`); `KANNADA` constant gone; `Drafted` gains `english`; `lineSummary(line, english)`.
- `assistant/mcp/ConversationTools.java`: `set_conversation_language` calls `ensure` after a switch.
- `assistant/api/AssistantConversationStore.java`: `create` calls `ensure` for the new conversation's language.
- `assistant/mcp/McpServerConfiguration.java`: the four tool descriptions say `name_local` /
  `name_pending` and the bracket rule; no `name_kn` left.
- `runner/prompts/kitchen-assistant.md`: Kannada-only rule and "English names otherwise" removed;
  new rule: use `name_local` exactly; when `name_pending`, own translation + English in brackets, e.g.
  "పెరుగు (Curd)"; never replace a stored name. Also the conductor's mid-task addition under Style:
  table only for three or more items, at most three columns, else bullets; no alignment markers, no
  links or images. Everything else unchanged.
- `runner/README.md`: a section on the `name-fill` profile.
- Tests: new `ConversationLanguageNamesIT` (7); `AssistantActionIT` (+2, and the first test checks the
  tool's lines); `InventoryToolsIT`, `McpEndToEndIT`, `StockNameFillIT` and `McpStockFixture` now use a
  Kannada conversation behind the token; `runner/test/runner.test.ts` checks the prompt for
  `name_local`/`name_pending` and no `name_kn`.
- `backend/src/test/.../AbstractIntegrationTest.java`: `kms.assistant.runner.url=http://127.0.0.1:9`
  for every test. Reason: the default is `localhost:8090`, which on this Mac is Rajeev's own logged-in
  runner, and any test that now reads stock in a non-English conversation starts a background fill.
  Without this, the suite would spend paid Claude calls on his runner. Tests that want a runner already
  set a fake one and restore the value.

## Commands run and what came back

```
$ tools/work-lock.sh run verify "export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; cd backend && ./gradlew test --tests 'org.iskcon.kms.assistant.*' --tests 'org.iskcon.kms.translation.*' --tests '*UnitLabelAgreementTest' --tests '*RowLevelSecurityIT'"
ConversationLanguageNamesIT > when every item asked about is named, nothing is pending and the fill is not asked for PASSED
ConversationLanguageNamesIT > a conversation started in Telugu asks for the Telugu names; one started in English does not PASSED
ConversationLanguageNamesIT > an English conversation carries no local name and nothing pending, and asks for no fill PASSED
ConversationLanguageNamesIT > switching the conversation to Telugu asks for the Telugu names; switching to English asks for nothing PASSED
ConversationLanguageNamesIT > a Telugu conversation gets the stored Telugu name, never the Kannada one; an item with no Telugu name is pending and the fill is asked for PASSED
ConversationLanguageNamesIT > a fill that cannot start never fails the tool PASSED
ConversationLanguageNamesIT > a token naming no conversation of this person's is treated as English, never another conversation's language PASSED
AssistantActionIT > the card, the tool's lines and the shopping list name items in the conversation's language: Telugu here, not the Kannada also stored PASSED
AssistantActionIT > in an English conversation the card and the tool carry no local name and nothing pending PASSED
AssistantActionIT > propose: the vendor's ticked shopping-list lines, the card built server-side, nothing ordered PASSED
McpEndToEndIT > initialize, tools/list and tools/call over HTTP with a real agent token, each temple seeing only its own stock PASSED
StockNameFillIT > get_inventory and list_low_stock hand the model the machine names as name_local in a Kannada conversation PASSED
  Total:    170
  Passed:   169
  Failed:   0
  Skipped:  1
  Result:   SUCCESS
BUILD SUCCESSFUL in 57s
work-lock: 'verify' released; command exited 0
```
(The skip is the Google smoke test. The two earlier runs failed on my own new test's assertions, a
verify count and the 50-row cap hiding Toor Dal; both were test mistakes, fixed, and this is the run after.)

```
$ cd runner && npm test
ℹ tests 15
ℹ pass 15
ℹ fail 0
```

### Real run (real model, real fill)

Setup: `kms_namesb`, a `pg_dump | pg_restore` copy of `kms` (owners kept; `kms` only read; it had no
glossary rows). API from this worktree on 8098 with `local-backend.sh`'s safety exports and
`KMS_ASSISTANT_RUNNER_URL=http://localhost:8099`; runner from this worktree on 8099. Rajeev's 8080 and
8090 were not touched and were still listening at the end. Temple Admin signed in with a Firebase ID
token minted through the custom-token route; everything below went through the real HTTP API.

```
PATCH /api/v1/profile/language {"preferredLanguage":"te"}  -> 200
POST /api/v1/assistant/conversations -> {"id":"0c4d8314-...","language":"te", ...}   20:15:18
runner: {"event":"run_start","profile":"name-fill","promptChars":2032,"mcpUrl":null}  03:15:18Z   <- started by create

20:15:22 "what is running low?"   (fill still running, glossary empty)
[tool_call] list_low_stock
| వాము (Ajwain) | 0 gm | 1000 gm |
| బాదం, ముక్కలు (Almond, chopped) | 0 gm | 1 gm |     <- the model's own stand-in
| ఉసిరికాయ (Amla (gooseberry)) | 0 Kg | 0.5 Kg |
| పెరుగు (Curd) | 6 L | 15 L |
| జాపత్రి (Mace) | 0 Kg | 0.5 Kg |

api: name fill for te: asked 119, stored 119, refused 0 in 58193 ms
stored: Ajwain|వాము  Almond, chopped|తరిగిన బాదం  Amla (gooseberry)|ఉసిరికాయ  Curd|పెరుగు  Mace|జాపత్రి

20:16:24 "what is running low?"   (after the fill)
| వాము (Ajwain) | 0 gm | 1000 gm |
| తరిగిన బాదం (Almond, chopped) | 0 gm | 1 gm |        <- now the stored name, exactly
| ఉసిరికాయ (Amla (gooseberry)) | 0 Kg | 0.5 Kg |
| పెరుగు (Curd) | 6 L | 15 L |
| జాపత్రి (Mace) | 0 Kg | 0.5 Kg |
```
All five names in the second answer equal the stored Telugu rows.

Kannada, same conversation:
```
20:16:34 "Please reply in Kannada from now on. How much curd do we have?"
[tool_call] set_conversation_language language=kn
[tool_call] get_inventory item_name=curd
[language] {"language": "kn", "languageName": "Kannada"}
runner: run_start profile name-fill 03:16:38Z   <- started by the switch
api: name fill for kn: asked 119, stored 119, refused 0 in 70795 ms
stored: Ajwain|ಓಮ  Almond, chopped|ಹೆಚ್ಚಿದ ಬಾದಾಮಿ  Amla (gooseberry)|ನೆಲ್ಲಿಕಾಯಿ  Curd|ಮೊಸರು  Mace|ಜಾಪತ್ರೆ

"what is running low?"
| ಓಮ (Ajwain) | 0 gm | 1000 gm |
| ಹೆಚ್ಚಿದ ಬಾದಾಮಿ (Almond, chopped) | 0 gm | 1 gm |
| ನೆಲ್ಲಿಕಾಯಿ (Amla (gooseberry)) | 0 Kg | 0.5 Kg |
| ಮೊಸರು (Curd) | 6 L | 15 L |
| ಜಾಪತ್ರೆ (Mace) | 0 Kg | 0.5 Kg |
```
All five equal the stored Kannada rows.

Runner log: 2 name-fill runs in total (one per language; the tool calls during the Telugu fill did not
start a second), 6 runs overall, `grep -c eyJ runner.log` = 0.

Cleanup: my API (8098) and runner (8099) stopped, `DROP DATABASE kms_namesb` done, ID token file deleted.

## Acceptance criteria

1. Tools return the conversation-language name, `name_kn` gone everywhere: `teluguNamesAndPending`
   (Telugu, not the Kannada also stored), the PO tests, `McpEndToEndIT` over HTTP; `grep name_kn`
   finds nothing in backend main or the prompt.
2. English conversation has none: `englishHasNone`, `englishConversationHasNoLocalNames`.
3. Fill triggered on create and on switch, non-blocking: `creatingAsksForNames`, `switchingAsksForNames`,
   and the real run's runner log (fill started at create and at switch). On missing names from a tool:
   `teluguNamesAndPending`; `allNamedAsksNothing` shows no call when nothing is missing; a failing fill
   does not fail the tool: `fillFailureDoesNotFailTheTool`.
4. Missing names marked pending: `name_pending: true` with no `name_local` (tests above, and the wire
   JSON is checked).
5. Card `localName` follows the language: `namesFollowTheConversationLanguage`.
6. Prompt updated; real run shows bracketed stand-ins before the fill and stored names exactly after.

## Not done / needs Rajeev

- Pending items are omitted `name_local` rather than sent as `name_local: null`; same meaning, smaller payload.
- An item on the shopping list or a named PO line that is not a stock item is never filled (the fill
  covers `inventory_items` only), so it stays pending and gets a bracketed stand-in each time.
- With the history carried in the prompt, the model could copy an earlier stand-in. In the run it did
  not: the second Telugu answer replaced its own "బాదం, ముక్కలు" with the stored "తరిగిన బాదం".
- The model still shows "Amla (gooseberry)" in brackets, the filed English, as told.
- Markdown rule: in the Kannada curd answer the model made a two-row table, against the new "three or
  more items" rule. One sample; not chased.
- No Telugu or Kannada speaker has checked the names.
- No hand smoke test in a browser (no screen changed; the frontend is another builder's). Driven
  through the real HTTP API instead.
- `AccessControlEnforcementIT` and the full suite were not run (not asked; release agent's job).
