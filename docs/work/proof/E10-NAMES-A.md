# E10-NAMES-A — stock names in any language, filled by Claude through the runner, once per temple per language

Worktree `/Users/Rajeev/Workspace/kms-e10-names`, branch `e10-names-fill` (from `agentic-mvp` dfbb32bd).
Nothing committed, pushed or deployed.

**Real run, Telugu, ISKCON South Bengaluru (copy of local `kms`):** 119 stock items, 119 names stored
(`machine_provider='claude'`), 0 refused. One runner call; it took 65 s, and the whole thing took 70 s
from the request to READY. A second call came back READY with no new run.

## Files changed (all inside the contract)

- `backend/.../translation/StockNameFillService.java`: adds `ensureNames(tenant, language)` and
  `fillWithClaude(tenant, language)`. The Google `fillStockNames` is unchanged in behaviour. Its two
  SQL queries are now shared helpers.
- `backend/.../translation/NameFillRunnerClient.java`: new. A plain POST to the runner's `/run` with
  `{profile:"name-fill", prompt}`, with no token and no mcpUrl. It reads the NDJSON and returns
  `structured_output`. It reuses `RunnerCredentials` (read only, not changed). It does not use
  `RunnerClient`, which belongs to S4 and is a streaming client.
- `backend/.../translation/LanguageScripts.java`: new. The script for each of the 22 languages, and
  the check that a name is written wholly in that script.
- `backend/.../translation/GlossaryController.java`: `POST /api/v1/translation-glossary/ensure-names?language=xx`,
  behind `MANAGE_RECIPES`. It returns `{language, status, stockItems, named}`. Tenant from `TenantContext`.
- `backend/src/test/.../translation/ClaudeNameFillIT.java`: new, 9 tests, with a fake runner on a real port.
- `backend/src/test/.../translation/LanguageScriptsTest.java`: new, 4 tests.
- `runner/prompts/name-fill.md`: new. The T8d instruction text, made general for any language.
- `runner/src/profiles.ts`: the `name-fill` profile (no MCP server, JSON schema, model `opus`, 300 s timeout).
- `runner/src/child.ts`, `runner/src/run.ts`, `runner/src/server.ts`: the minimal change so that a profile
  with no MCP server gets no `--mcp-config`, no `--allowedTools` and no `mcp.json`, does not need an
  agent token or mcpUrl (and refuses them if sent), and uses its own model and timeout.
  The brief named only `profiles.ts`, but "the runner must not require an agent token" cannot be done
  in that file alone. None of these files is S4's.
- `runner/test/runner.test.ts`: 3 new tests; the fake `claude` tolerates a missing `mcp.json`.

Not touched: `assistant/mcp/**`, `assistant/action/**`, `AssistantChatService`, `kitchen-assistant.md`.
`runner/README.md` does not yet describe `name-fill`. That belongs in Part B or the merge.

**No migration.** The glossary table already accepts any language: `language TEXT`, unique on
`(tenant_id, language, lower(source_term))`, and the V161 machine columns. Nothing in it is
Kannada-only. The "Kannada only" was the old `fill-stock-names` endpoint, which is left as it is. The
concurrency guard is a PostgreSQL advisory lock, not a table, so V163/V164 were not used. (V163 has
since been taken by S4's `V163__assistant_drafts_purchase_order.sql`.)

## How it works

- `ensureNames` counts the temple's stock names and how many have no glossary entry in the language.
  For English, or when nothing is missing, it returns READY. If a fill from this instance is running,
  it returns FILLING. If the last fill ended short less than 10 minutes ago, it returns FAILED (no
  answer at all) or READY (some items refused), so an unnameable item does not cost a paid call on
  every message. Otherwise it starts a fill on a 2-thread daemon pool and returns FILLING.
- On the background thread, `TenantContext` is set to the caller's tenant before any connection is
  taken, and cleared in `finally`. The tenant passed in must equal the current `TenantContext`, or it
  throws.
- `fillWithClaude` takes one connection and runs `pg_try_advisory_lock(hashtextextended('glossary-name-fill:<tenant>:<lang>',0))`.
  If it does not get the lock it returns ALREADY_FILLING without calling the runner. With the lock it
  re-reads what is missing, sends batches of up to 150 items (read-aloud form via `IngredientNames.readable`),
  and checks each answer. An answer is stored only if: its number was asked; its copied-back English
  matches that item (this catches an answer shifted by a line); it is the only answer for that number;
  and the name, after NFC and spacing clean-up, is wholly in the language's script. Each batch's
  passing names are inserted in one transaction, `ON CONFLICT DO NOTHING`, so nothing is ever
  overwritten. No transaction is open during the runner call. The lock is released in `finally`, and
  by PostgreSQL if the process dies.
- Schema deviation from T8d: `{n, kannada}` became `{n, english, name}`. `kannada` became `name` so it
  works for any language. `english` was added for the shift check above.

## Commands run and what came back

Backend (through the worktree's lock):

```
$ tools/work-lock.sh run verify "export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; cd backend && ./gradlew test --tests 'org.iskcon.kms.translation.*' --tests '*RowLevelSecurityIT' --tests '*ErrorCode*'"
ClaudeNameFillIT > two fills at once, past any in-memory guard (two api instances): the database lock lets one call the runner PASSED
ClaudeNameFillIT > ten ensureNames at once: one runner call, all FILLING, then READY; the background thread had the temple PASSED
ClaudeNameFillIT > the tenant must be the caller's own; English and unknown languages are handled PASSED
ClaudeNameFillIT > running it again asks nobody and changes nothing PASSED
ClaudeNameFillIT > asks only for items with no name, read aloud, and stores them marked claude; never touches a person's or Google's PASSED
ClaudeNameFillIT > a bad answer stores only the items that pass: no blanks, no wrong script, no shifted or doubled answers PASSED
ClaudeNameFillIT > one temple's fill reads and writes only its own glossary PASSED
ClaudeNameFillIT > the endpoint: a Temple Admin starts it and sees it finish; a Volunteer is refused PASSED
ClaudeNameFillIT > a runner that fails stores nothing, says FAILED, and is not called again on the next message PASSED
LanguageScriptsTest > every scheduled language has a script PASSED
LanguageScriptsTest > English, another script, digits, punctuation and blanks fail PASSED
LanguageScriptsTest > tidy collapses spacing and normalises to NFC PASSED
LanguageScriptsTest > a name in the language's own script passes, joiners and spaces included PASSED
StockNameFillIT > (all 6) PASSED
RowLevelSecurityIT > every table with a tenant_id is covered by row-level security PASSED
GoogleTranslationSmokeIT > translates English to Kannada via the real Cloud Translation API SKIPPED
  Total:    1079
  Passed:   1078
  Failed:   0
  Skipped:  1
BUILD SUCCESSFUL in 28s
work-lock: 'verify' released; command exited 0
```

(The 1079 is mostly the parameterised `ErrorCode*` tests. The skip is the Google smoke test, which
needs `TRANSLATION_SMOKE`.)

Runner:

```
$ cd runner && npm test
  ✔ gives name-fill no tools at all, the schema, and its own model
  ✔ runs name-fill with no mcpUrl or token: an empty directory, no mcp.json, the items on stdin
  ✔ refuses name-fill sent an agent token or mcpUrl, and the assistant sent none
ℹ tests 15
ℹ pass 15
ℹ fail 0
```

Real run. My own API on 8096 and runner on 8097, from the worktree; Rajeev's 8080/8090 were not
touched and are still listening. `kms_names` was a `pg_dump | pg_restore` copy of `kms`, with grants
kept. `CREATE DATABASE … TEMPLATE kms` is impossible while his API is connected to `kms`. `kms` is at
V163 (S4's migration), which this branch does not have, so I deleted that one history row in the copy
only. The API ran with `local-backend.sh`'s safety environment plus `KMS_ASSISTANT_RUNNER_URL=http://localhost:8097`.
The Temple Admin's ID token was minted through the Firebase custom-token route, and deleted after.

```
19:57:11 start
{"language":"te","status":"FILLING","stockItems":119,"named":0}
{"language":"te","status":"FILLING","stockItems":119,"named":0}      <- second call, 1 s later
19:58:21 end
{"language":"te","status":"READY","stockItems":119,"named":119}
elapsed 70s

api:    StockNameFillService - name fill for te: asked 119, stored 119, refused 0 in 65423 ms
runner: {"event":"run_start","profile":"name-fill","promptChars":2032,"mcpUrl":null}
runner: {"event":"run_end","result":"ok","exitCode":0,"ms":65336,"lines":1}

 language | machine_provider | count | count(machine_translated_at)
 te       | claude           |   119 |   119

     source_term     | target_term
 Ajwain              | వాము
 Amla (gooseberry)   | ఉసిరికాయ
 Beaten rice         | అటుకులు
 Coconut, grated     | కొబ్బరికాయ
 Copra (dry coconut) | ఎండు కొబ్బరి
 Curd, whisked       | పెరుగు
 Ghee                | నెయ్యి
 Ghee, for finishing | నెయ్యి
 Jaggery             | బెల్లం
 Mace                | జాపత్రి
 Moong Dal           | పెసరపప్పు
 Rice                | బియ్యం
 Rice (sona masoori) | సోనా మసూరి బియ్యం
 Rice, broken        | నూకలు
 Toor Dal            | కందిపప్పు

then again: {"language":"te","status":"READY","stockItems":119,"named":119}   run_start count in runner log: 1
grep -c eyJ runner.log: 0
```

Cleanup: both of my processes stopped (8096 and 8097 free), `DROP DATABASE kms_names` done, token file deleted.

## Acceptance criteria

- Glossary supports any language: confirmed from the V13/V161 schema, and the Telugu run stored 119 rows. No migration.
- `name-fill` profile with no tools, a JSON schema, and no token or mcpUrl: covered by the runner tests
  (args, empty working directory, refuses a token). The real run logged `mcpUrl:null`.
- Fills only the missing items and never overwrites a person's or Google's row: `fillsOnlyTheMissing`.
- Idempotent: `isIdempotent`, plus the second real call (READY, no run).
- Tenant-scoped: `isTenantScoped`. The tenant must be the caller's own: `tenantAndLanguageRules`.
- Ten concurrent `ensureNames` make one runner call: `concurrentEnsureNamesCallsOnce`. Two direct
  fills (as from two instances) make one call, through the database lock: `advisoryLockStopsASecondFill`.
- Bad output (blank, Latin, wrong English, duplicate, out-of-range, missing) stores only the good item: `badAnswersAreNotStored`.
- A runner failure stores nothing, returns FAILED and is not called again within 10 minutes: `runnerFailureStoresNothing`.
- The background thread had the tenant: its inserts take `tenant_id` from `app.tenant_id`, so they
  succeed only with the tenant set, and they land in temple A only (`concurrentEnsureNamesCallsOnce`).
- English needs no fill: READY with no call.
- Google path kept: the `StockNameFillIT` tests are all green.

## Not done / needs Rajeev

- No Telugu speaker has checked the names. The 15 above read plausibly to me. Doubtful:
  "Coconut, grated" → కొబ్బరికాయ (plain "coconut"; "grated" dropped), and "Curd, whisked" →
  plain పెరుగు. The prompt allows dropping preparation words, so these follow it. Whether they should is your call.
- Part B (not this task) is calling `ensureNames` from the assistant at the start of a conversation,
  and the tool and prompt changes to use names for any language. `runner/README.md` also needs the new profile.
- The retry timer is kept in memory, per instance. A restart allows one more try; the advisory lock
  still prevents two at once.
- No hand smoke test in a browser: there is no screen. The endpoint was driven by curl as a real Temple Admin.
- `AccessControlEnforcementIT` lists its endpoints by hand, and the new endpoint was not added there.
  Its permission is tested in `ClaudeNameFillIT`.
