# E10-MEAL-READ — six read-only meal tools for the Kitchen Assistant

Worktree `/Users/Rajeev/Workspace/kms-e10-names`, branch `e10-meal-lookup` (on `agentic-mvp` 97b59fd3). Not committed.

**Files changed:** all inside the contract (`assistant/mcp/**`, the prompt, tests, this proof).

- `backend/src/main/java/org/iskcon/kms/assistant/mcp/MealTools.java` (new): `get_meals`, `get_day_context`,
  `check_meal_sufficiency`, `get_shortfall`, `list_meal_kinds`, `search_recipes`. Each calls the service the
  screen's endpoint calls (`MealPlanService.meals/dayContext`, `SufficiencyService.sufficiency/shortfallFeed`,
  `MealKindService.list`, `RecipeService.list`, plus `CalendarService.day` for the Ekadashi name, fast and
  festivals). `@PreAuthorize` `MANAGE_MEAL_PLANS` on the five meal tools, `MANAGE_RECIPES` on recipe search.
  Today is `TempleClock.today()` (temple's zone); every answer carries `today` and weekdays.
  Caps: range 14 days (cut, `range_shortened: true`), 60 meals, 80 dish rows, 50 shortfall items, 10 recipes.
- `backend/src/main/java/org/iskcon/kms/assistant/mcp/McpServerConfiguration.java`: the six tool
  descriptions and schemas, registration, two refusal texts (meal planner, recipes screen).
  `FAILED_MESSAGE` changed from "The stock could not be read…" to "That could not be read…", since every tool shares it.
- `backend/src/test/java/org/iskcon/kms/assistant/mcp/MealToolsIT.java` (new, 7 tests).
- `backend/src/test/java/org/iskcon/kms/assistant/mcp/InventoryToolsIT.java`: `toolSpecifications` takes `MealTools`.
- `backend/src/test/java/org/iskcon/kms/assistant/mcp/McpEndToEndIT.java`: the `tools/list` assertion names the six new tools.
- `runner/prompts/kitchen-assistant.md`: a Dates section and a Meals section. The old "you cannot see
  tomorrow's menu" paragraph is gone. Language, names and ≤3-column table rules are unchanged.

Dish names: the stock-name fill only names inventory items, and `recipe_translations` is a per-version cache
filled only when someone opens a translated recipe. So a dish gets `name_local` only where the temple's
glossary has an entry. Otherwise it gets the English name, which the prompt says to use as written. Dishes
never get `name_pending`, because nothing would ever fill it. Ingredient names in shortfalls follow
`ConversationNames` exactly as the stock tools do.

**Commands run and what came back:**

```
$ tools/work-lock.sh run verify "export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; cd backend && ./gradlew test --tests 'org.iskcon.kms.assistant.*' --tests '*UnitLabelAgreementTest' --tests '*RowLevelSecurityIT'"
  Total:    144
  Passed:   144
  Failed:   0
  Skipped:  0
  Result:   SUCCESS
BUILD SUCCESSFUL in 51s
```
The run before that one had 2 failures:
- `McpEndToEndIT`'s tool list, which I then fixed.
- `AgentTokenServiceTest.tamperedSignature` ("Expecting code to raise a throwable"). It is outside my
  contract and passed on the rerun. It looks flaky: it swaps the token's last base64url character between
  `A` and `B`, which differ only in a padding bit of the 32-byte signature, so the "tampered" token can
  decode to the same signature.

```
MealToolsIT > a person without the meal planner or the recipes screen is refused in words PASSED
MealToolsIT > a date the model wrote wrongly, or backwards, is refused with what to do instead PASSED
MealToolsIT > get_meals returns only the calling temple's meal, with head counts, amounts and the dish's stored name PASSED
MealToolsIT > get_day_context flags an Ekadashi and not an ordinary day, and lists the day's meals PASSED
MealToolsIT > check_meal_sufficiency reports temple A's dish short of its own dal, and nothing of temple B's PASSED
MealToolsIT > a range longer than 14 days is cut and says so; meals stop at 60 and recipes at 10 PASSED
MealToolsIT > search_recipes and list_meal_kinds read only the calling temple's rows PASSED
```

```
$ cd runner && npm test
ℹ tests 15
ℹ pass 15
ℹ fail 0
```

### Real run (real model, real MCP)

Setup:
- `kms_mealread`, a `pg_dump | pg_restore` copy of `kms`. Grants were kept; `kms` was only read.
- The local data stops at 30 Sept, so in the copy only, 30 Sept's Breakfast, Lunch and Dinner were cloned
  as PLANNED onto 9, 10 and 11 Oct.
- API from this worktree on 8096 (local-backend.sh safety exports), runner from this worktree on 8097.
- A conversation row per language, and an agent token minted with `mint-token.py`.
- The prompt was `AssistantConversationStore.languageLine` plus the question, exactly what the API sends.
  The chat HTTP API itself (Firebase sign-in) was not used.
- Temple time was 06:15 on Friday 9 Oct, so "tomorrow" is Saturday 10 Oct.
- Rajeev's 8080, 8090 and 3000 were still listening at the end.

```
"what's planned for lunch tomorrow?"   (English)
[tool_call] get_meals {"from": "2026-10-09"}
[tool_call] get_meals {"from": "2026-10-10"}
Lunch tomorrow, Saturday 2026-10-10, is planned for 102 plates (50 adults, 20 children, 50 seniors). It should be
ready by 12:00 in the Main Kitchen, and it needs 6 people.
Dishes:
- Arbi ki Sabzi (Haryana): 18.36 L
- Chitranna: 30.6 L
- Kosu Palya: 10.2 L
- Paruppu Rasam: 25.5 L
```
The model's first guess at today was the machine's date, not the temple's. The `today` in the answer
corrected it.

```
"do we have enough for Sunday's meals?"   (English)
[tool_call] get_day_context {}
[tool_call] check_meal_sufficiency {"from": "2026-10-11"}
No, not everything is covered for Sunday 2026-10-11. 5 dishes are enough and 2 are short. Both need ordering
**today (2026-10-09)**.
**Lunch: Arbi ki Sabzi (Haryana)** is short of: Colocasia 10.2 Kg, Ajwain 122.4 gm, Red chilli powder 183.6 gm, ...
**Dinner: Kabuli Chana Masala (Bihar)** is short of: White chana 1.2 Kg, Ginger, paste 96 gm, ...
The other dishes are enough: Idli (Karnataka), Chitranna, Paruppu Rasam, Kosu Palya, Chapati (Karnataka)
```

```
"ఆదివారం భోజనాలకు సరిపడా సరుకులు ఉన్నాయా?"   (Telugu conversation)
[tool_call] get_day_context {}
[tool_call] check_meal_sufficiency {"from": "2026-10-11"}
ఆదివారం (2026-10-11) భోజనాలకు అన్నీ సరిపోవు. 7 వంటకాల్లో 5కి సరుకులు సరిపోతాయి, 2కి తక్కువ ఉన్నాయి. రెండింటికీ ఈరోజే (2026-10-09) ఆర్డర్ చేయాలి.
**సరిపోయేవి:** Idli (Karnataka), Chitranna, Paruppu Rasam, Kosu Palya, Chapati (Karnataka)
మధ్యాహ్న భోజనం – Arbi ki Sabzi (Haryana):
- కొలొకేషియా (Colocasia): 10.2 Kg తక్కువ
- వాము (Ajwain): 122.4 gm తక్కువ
...
```
I called the same tool directly for the Telugu conversation:
```
{"name": "Colocasia", "name_pending": true, "needed": 10.2, "available": 0, "short_by": 10.2, "unit": "Kg"}
{"name": "Ajwain", "name_local": "వాము", "needed": 122.4, "available": 0, "short_by": 122.4, "unit": "gm"}
glossary: te|119 rows; Ajwain|వాము
```
In that answer the dish names stayed in English as written, `వాము` is the stored name letter for letter,
and the pending names are the model's own with the English name in brackets.

Runner log: 3 `kitchen-assistant` runs, no name-fill run, and `grep -c eyJ runner.log` = 0.
Cleanup: 8096 and 8097 stopped, `DROP DATABASE kms_mealread` done, dev token key deleted.

**Acceptance criteria:**

- **Six tools with scope `agent:read` and the screen's permission.** Done: `/mcp` checks the scope (unchanged),
  and each method declares its own `@PreAuthorize`. Tested in `permissionIsEnforced`. That test also shows
  `MANAGE_MEAL_PLANS` alone does not open `search_recipes`.
- **Only the temple's own meals (RLS).** Done. Two temples each have a Lunch on the same day. `getMealsIsTenantScoped`,
  `sufficiencyIsTenantScoped` and `lookupsAreTenantScoped` all read through the unprivileged connection.
- **Caps.** `resultsAreCapped` covers the 14-day cut, 60 meals out of 61, and 10 recipes out of 12.
- **Ekadashi flag.** `dayContextFlagsEkadashi` checks 2025-01-10 is true, 2025-01-13 is false, and that no date means the temple's today.
- **Dates in the temple's timezone.** `TempleClock.today()`, asserted against `LocalDate.now(Asia/Kolkata)`.
  The real run checked it at 06:15 IST, when the UTC date was still 9 Oct.
- **Model-friendly descriptions, prompt updated.** Done. The three real questions were answered from the tools.

**Not done / needs Rajeev:**

1. **The API's prompt does not include today's date.** `AssistantConversationStore.languageLine` / `promptWithHistory`
   (outside my paths) send only the language line. I worked around it: every meal tool returns `today`, and the
   prompt says to call `get_day_context` with no date when it needs today. In the real run the model still guessed
   first from the machine's date and spent one extra call. Suggested fix for the conductor, one line in
   `languageLine`: "Today is Friday 2026-10-09 in the temple's time zone." from `TempleClock`.
2. **Ingredients that are short but have no inventory item never get a stored name.** The stock-name fill
   (`StockNameFillService`) only names `inventory_items`. A recipe ingredient the temple has never stocked, such as
   Colocasia here, stays `name_pending` forever. The model then makes up its own Telugu word each time, which is the
   instability E10-T8b measured. The fix belongs in the fill's query (outside my paths): name recipe ingredients too.
3. **Dish names stay English in other languages** unless the temple typed a glossary entry (see above). Whether
   dishes should get a fill like stock is Rajeev's call.
4. **Recipe search matches only part of the English name**, as the screen's `GET /recipes?q=` does, so "idly" will
   not find "Idli". The description tells the model to try other spellings. A fuzzy search would be a
   `recipe/**` change.
5. Not a UI change, so there was no hand smoke test in the browser. The chat page was not driven. The real run
   went runner → MCP with the same prompt the API builds.
