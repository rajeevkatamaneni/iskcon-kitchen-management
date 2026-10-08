# E10-LANG — a per-person language preference, and the assistant's language rule

Worktree `/Users/Rajeev/Workspace/kms-e10-s3-runner`, branch `e10-lang` (from `agentic-mvp` 20dcfffa). Not committed.

Rajeev's rule (2026-10-06): start in English, or in the person's preferred language if set; stay in it until the person asks to switch. He chose (2026-10-07) a preference per person, not per temple. Writing in another language without asking does not switch.

**Files changed** (all in this worktree; none of the forbidden shared files; nothing under `frontend/components/assistant/**`, the app layout or the assistant section of `api.ts`):

New:
- `backend/src/main/resources/db/migration/V162__a_person_chooses_their_language.sql`: `users.preferred_language` and `assistant_conversations.language`, both `NOT NULL DEFAULT 'en'` with a CHECK listing the 23 codes. V162 was confirmed free in every worktree and branch. There is no backfill: ADD COLUMN with a constant default is not an UPDATE, so RLS does not come into it.
- `backend/src/main/java/org/iskcon/kms/assistant/mcp/ConversationTools.java`: `set_conversation_language`.
- `backend/src/main/java/org/iskcon/kms/profile/UpdateLanguageRequest.java`
- `backend/src/test/java/org/iskcon/kms/assistant/mcp/ConversationToolsIT.java`
- `docs/work/proof/E10-LANG.md`

Changed:
- `backend/.../translation/Languages.java`: English names for the codes, plus `isSupported` and `englishName`. The prompt says "Kannada (kn)", and nothing in the backend had the names until now.
- `backend/.../profile/ProfileView.java`, `ProfileService.java`, `ProfileController.java`:
  - `preferredLanguage` is now on GET.
  - New `PATCH /api/v1/profile/language {preferredLanguage}`. It is a separate path because the existing PATCH requires a channel, and making that optional would change what callers that send an empty body are told.
  - Same rule as the rest of the profile: authenticated, and acts on the caller's own row only.
- `backend/.../assistant/api/AssistantConversationStore.java`:
  - `create` takes the conversation's language from the person's preference in the same INSERT.
  - `begin` puts the language line at the head of every prompt.
  - New `language()` reads the current value.
- `backend/.../assistant/api/AssistantViews.java`: `language` on the conversation summary and detail.
- `backend/.../assistant/api/AssistantChatService.java`: after a run, it re-reads the conversation's language. If the language changed, it sends `event: language {language, languageName}` just before `done`/`error`. It re-reads the database rather than trusting the relayed tool_call, so a refused call (an unknown code) is never announced.
- `backend/.../assistant/api/AssistantController.java`: javadoc lists the events, including `language`.
- `backend/.../assistant/mcp/McpServerConfiguration.java`:
  - Registers the tool and its description for the model.
  - Adds `ToolRefusal`, a refusal written in plain words, returned as `isError: true` with no incident id.
- `runner/prompts/kitchen-assistant.md`: the Language section now states the rule:
  - Reply only in the language the prompt line names.
  - Do not switch because the person writes in another language.
  - Switch only when asked, by calling `set_conversation_language`.
  - The name_kn rule is kept: name_kn when replying in Kannada, English names otherwise.
- Tests: `AssistantApiIT.java`, `FakeRunner.java` (a `duringRun` hook that stands in for a tool's mid-run write), `InventoryToolsIT.java` and `McpEndToEndIT.java` (tool list now has three tools), `ProfileIT.java`.
- `frontend/lib/api.ts`: only `Profile.preferredLanguage` and `updatePreferredLanguage`, next to the existing profile code.
- `frontend/app/profile/page.tsx`: a "Language" card with a "Your language" select and an "i" hint. It saves on pick, like the channel radios on the same screen.
- `frontend/__tests__/profile.test.tsx`, `frontend/__tests__/blank-submit-slice-f.test.tsx` (fixture gains the new field).

**How the language reaches the model.** Every prompt the API builds starts like this:

`Conversation language: Kannada (kn). Reply only in Kannada, whatever language the message is written in. Change language only if the person asks you to, by calling set_conversation_language.`

It goes on every message because each run is a fresh `claude -p` that remembers nothing between messages.

`set_conversation_language(language)`:
- It works on the conversation named in the token's `conv` claim. The model cannot name one in an argument.
- The UPDATE is `WHERE id = conv AND user_id = token user`, and RLS fixes the temple.
- If no row matches, it refuses in words.
- It checks the code against `Languages`.
- Scope is `agent:read`, checked at the endpoint.

## Commands run and what came back

Backend, through the lock in this worktree:
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; tools/work-lock.sh run verify "cd backend && ./gradlew test --tests 'org.iskcon.kms.assistant.*' --tests 'org.iskcon.kms.profile.*' --tests '*RowLevelSecurityIT' --tests '*ErrorCode*' --tests '*AccessControlEnforcementIT' --tests '*PermissionBeforeValidationIT'"
  Total:    1242
  Passed:   1242
  Failed:   0
  Result:   SUCCESS
BUILD SUCCESSFUL in 23m 33s
```
Per class (build/test-results):
```
assistant.mcp.ConversationToolsIT" tests="5" skipped="0" failures="0" errors="0"
assistant.api.AssistantApiIT" tests="15" skipped="0" failures="0" errors="0"
profile.ProfileIT" tests="9" skipped="0" failures="0" errors="0"
assistant.mcp.McpEndToEndIT" tests="7" skipped="0" failures="0" errors="0"
assistant.mcp.InventoryToolsIT" tests="8" skipped="0" failures="0" errors="0"
tenancy.ProcurementRowLevelSecurityIT" tests="6" skipped="0" failures="0" errors="0"
tenancy.RowLevelSecurityIT" tests="33" skipped="0" failures="0" errors="0"
auth.AccessControlEnforcementIT" tests="16" skipped="0" failures="0" errors="0"
auth.PermissionBeforeValidationIT" tests="97" skipped="0" failures="0" errors="0"
error.ErrorCodeTest" tests="1007" skipped="0" failures="0" errors="0"
```

Frontend (node_modules symlinked from the main checkout, as `kms-e10-s3-perms` does, and removed afterwards):
```
tools/work-lock.sh run verify-fe "cd frontend && npx tsc --noEmit && npm test"
 Test Files  192 passed (192)
      Tests  2807 passed (2807)
work-lock: 'verify-fe' released; command exited 0
```
(tsc printed nothing.)

Runner prompt load: the runner was rebuilt (`npm run build`). Its unit tests were not re-run, because no runner code changed, only the prompt file.

### Real check: runner, local API from this worktree, real model

Setup:
- `kms_lang` was created as a copy of `kms_try`, which already holds the Kannada names.
- `bootRun` used `tools/local-backend.sh`'s safety exports, port 8097, and its own token key:
  ```
  Migrating schema "public" to version "162 - a person chooses their language"
  Successfully applied 1 migration to schema "public", now at version v162 (execution time 00:00.009s)
  Started KmsApplication in 3.449 seconds
  ```
- The runner was `node dist/src/server.js` on 18091 with the stored login, as in T9.

The script (`scratchpad/langcheck.py`):
- It set the seeded Temple Admin's `preferred_language = 'kn'` by SQL. The profile PATCH needs a Firebase token; see "Not done".
- It created the conversation with the store's own INSERT.
- For each turn, it minted a token with `mint-token.py`, built the prompt exactly as `promptWithHistory` does (language line plus history), and POSTed it to `/run`.
- After each turn it read the conversation's language back from the database.

```
person fb91ece2-... preference kn
conversation 7f8928da-... starts in kn

=== USER: how much toor dal do we have?
    prompt head: Conversation language: Kannada (kn). Reply only in Kannada, ...
    tools offered: ['mcp__ikms__get_inventory', 'mcp__ikms__list_low_stock', 'mcp__ikms__set_conversation_language']
    5.7s tool calls: [["get_inventory", {"item_name": "toor dal"}]]
    ASSISTANT: ಉಗ್ರಾಣದಲ್ಲಿ ತೊಗರಿ ಬೇಳೆ (Toor Dal) 806.088 Kg ಇದೆ. ...
    conversation language now: kn

=== USER: Please reply in English from now on.
    TOOL RESULT: {"language":"en","language_name":"English","instruction":"From now on reply only in English, including the rest of this answer."}
    4.0s tool calls: [["set_conversation_language", {"language": "en"}]]
    ASSISTANT: Sure, I'll reply in English from now on. For your earlier question, we have 806.088 Kg of Toor Dal ...
    conversation language now: en

=== USER: ತುಪ್ಪ ಎಷ್ಟು ಇದೆ?
    prompt head: Conversation language: English (en). Reply only in English, ...
    3.9s tool calls: [["get_inventory", {"item_name": "ghee"}]]
    ASSISTANT: We have two ghee entries in the store: - **Ghee:** 80424.907 ml on hand. ...
    conversation language now: en
```
The runner log shows 3 `run_end … "result":"ok","exitCode":0`, and 0 occurrences of the token key.

Cleanup: API and runner stopped, `kms_lang` dropped, kms-postgres stopped again (it was stopped when I started), `kms_try` untouched.

### Profile screen measured (headless Chrome over CDP, real page)

Temporary harness: a stub sign-in behind an env flag, plus a catch-all `app/api` route stub. Both were removed afterwards, and `git status` shows neither.

```
375  docW 375 (no sideways scroll)  section [32,743,311,210]  select [57,888,261,44]  label "Your language" [70,860,96,24]  i [172,864,20,20]
1280 docW 1280                      section [312,663,936,186] select [337,784,448,44] label [350,756,96,24]          i [452,760,20,20]
after pick: select "hi", GET /api/v1/profile preferredLanguage "hi"
```
- The select is 44 px tall at both widths (touch target).
- The label sits 13 px in from the select edge, which is the field inset HintedField uses everywhere.
- At 1280 the select is `max-w-md` (448 px), the same as the temple Language section in Settings.

## Acceptance criteria

1. **Migration V162.**
   - Applied cleanly on a copy of real local data (log above).
   - Both CHECKs refuse an unknown code: `ProfileIT.databaseRefusesUnknownLanguage` and `ConversationToolsIT.databaseRefusesUnknownLanguage`.
   - RLS: no new table, so the existing policies cover the new columns. `RowLevelSecurityIT` passes (33/33).
2. **Profile API and screen.**
   - `ProfileIT`:
     - The language defaults to `en`.
     - PATCH to `kn`, and to `mni-Mtei`, persists on the caller's row only (a colleague's row stays `en`).
     - `klingon`, `KN`, blank and `{}` are each refused 400 KMS-400001, and nothing changes.
   - `profile.test.tsx`:
     - Shows the saved value, with 23 options.
     - Picking calls `updatePreferredLanguage("hi")`.
     - Picking the same language again sends nothing.
     - Hidden for Kitchen Staff and Volunteer.
   - Measured at 375 and 1280 (above).
3. **A new conversation takes the preference.** `AssistantApiIT.startsInPreferredLanguage`:
   - A `kn` person's conversation is `kn`; a colleague with no preference gets `en`.
   - Changing the preference later does not move the open conversation.
   - The detail endpoint shows `language`.
4. **Prompt and system prompt.**
   - `oneMessage` asserts the exact English line plus the question.
   - `startsInPreferredLanguage` asserts the Kannada line.
   - `historyWindow` asserts the line comes before the history.
   - The system prompt was updated, and the real run above shows the model following it.
5. **The tool.** `ConversationToolsIT`:
   - Switches its own conversation and returns `{language, language_name}`; a colleague's conversation is untouched.
   - Unknown codes are refused in words, with the list of codes.
   - A token naming a colleague's conversation is refused with NOT_FOUND_MESSAGE, and the row is unchanged.
   - A token naming another temple's conversation is refused the same way.
   - `language` SSE event: `AssistantApiIT.languageEventWhenSwitched` gets `text, text, tool_call, text, text, language, done` with `{kn, Kannada}`. On the next message there is no event, and the prompt is now in Kannada.
6. **tools/assistant-dev.** Not touched (it is uncommitted in the main checkout). Its `chat.py` will still run, but its prompts carry no language line, so the model has no conversation language to follow and the rule is not tested there. Adding the line to `chat.py` is a two-line change for whoever owns that folder.

## Not done / needs Rajeev

- **Field shown only to Temple Admin and Kitchen Manager.** These are the roles with the assistant. The API accepts the preference from anyone, for their own account. I hid it for Kitchen Staff and Volunteers because today nothing else reads it, and a setting that does nothing would confuse them. If you want it on everyone's account now, remove one line (`ASSISTANT_ROLES`).
- **Saves when picked, with no Edit/Save step.** That is how the rest of the profile screen works (the channel radios). Settings uses read-only-until-Edit, but this is a different screen.
- **Copy, for your eye:**
  - Card: "Language".
  - Line under it: "The language the Kitchen Assistant uses with you."
  - Field: "Your language".
  - "i" hint: "New conversations start in this language. To switch during a conversation, ask the assistant."
- **The real check set the preference by SQL and built the prompt with a copy of the store's code.** It did not go through the chat API. The chat API still needs a real Firebase token locally, as noted in E10-T10's proof. The API's own prompt building and the `language` event are covered by `AssistantApiIT` against a fake runner.
- **No hand smoke test while signed in.** The screen was driven and measured in headless Chrome against a stubbed API, not in your Chrome against a real backend.
- **The language event is sent at the end of the run, not at the moment of the switch.** The reply text after the switch is already in the new language, so the UI only needs it to update a label.
- **The backend run took 23 m 33 s.** I did not look into why; it was much longer than T10's 2 m 22 s for a similar set.
