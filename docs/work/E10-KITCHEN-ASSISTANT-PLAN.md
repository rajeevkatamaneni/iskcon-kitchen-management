# Kitchen Assistant (IKMS agents, Phase 1) — Implementation Plan

**Status:** Draft v0.1 for Rajeev's review — 2026-10-03
**Design it implements:** `claude/agents-phase1-design-options.md` (Option A) and the Agent Ladder page, steps 1–5.
**Scope:** web only, one agent profile ("Kitchen Assistant"), `claude -p` on Rajeev's Claude subscription login. No OCR, no documents, no mobile, no vector search. Those are Phase 2 (ladder steps 6–8).
**Grounded in the repo at `D:\IKMS_Code`:** package `org.iskcon.kms`, Flyway migrations up to `V159`, `enable_tenant_rls()` in `V1`, Firebase `AuthenticationFilter`, `RolePermissions.java`, `frontend/lib/api.ts`, Cloud Run services `kms-<env>-api|worker|web` deployed by `infra/deploy.sh`, environment owned by `infra/environment/main.tf`.

---

## Progress

- **2026-10-03 — S1 built** as `tools/assistant-spike/` (proof: `docs/work/proof/E10-S1.md`). Cloud-workspace run: 10/10 schema-valid and correct (5 EN, 5 KN), p50 3.2 s, p95 6.2 s, process overhead median 981 ms. Lockdown: shell refused; working-folder CLAUDE.md not loaded; token and home-folder checks still need Rajeev's laptop run. Findings: pass `claude` an allow-listed environment; return Kannada item names from tools.
- Decisions taken (Rajeev, 2026-10-03): build S1 + S2 first and stop; use the §9 proposals as given.
- **2026-10-04 — S2 built** on branch `agentic-mvp` (T4–T8, proofs `docs/work/proof/E10-T4..T8.md`). 20/20 correct tool, EN + KN; p50 5.6 s, p95 7.6 s wall clock. Agent authorities = scopes + the user's own permissions.
- **2026-10-04 — Kannada names fixed at the source** (T8b–T8d). Baseline drift 12 of 28 items; after storing one Claude-made name per item in the glossary (V161, marked machine), 0 of 28. ~10 names await a human check. Fill is a one-off script for now (Rajeev's choice); a terminal chat (`tools/assistant-dev/try.sh`, `chat.py`) lets Rajeev try it locally.
- **2026-10-05 — Scope widened by Rajeev.** Goal: a chat (web, later mobile, typed or dictated) that both answers questions and does tasks with the user, asking for missing details, chaining tool calls. Rajeev chose six capabilities rather than the whole API (§11). Running on his subscription is deliberate: this is a learning test bed; a KMS API key comes at go-live.

## 0. Where this sits in the project

`CLAUDE.md` says `docs/OUTSTANDING_BUILD_LIST.md` and `docs/WORK_QUEUE.md` items 1–3 come before anything new, and all three queue items are still "awaiting Rajeev's test". So this plan does **not** jump the queue by itself. It is proposed as a new epic, **E10 — Kitchen Assistant**, to be slotted into `WORK_QUEUE.md` where Rajeev says. The one thing that can start regardless is Story S1, because it touches no IKMS code.

Commandment 1 asks for requirements and a wireframe before build. For E10 that means: this plan, the system prompt, the tool list, and one wireframe of the chat page with a confirmation card. Rajeev approves those, then stories start.

---

## 1. What gets built (components and where they live)

| # | Component | Lives in | New or change |
|---|---|---|---|
| A | **Assistant tables** — conversations, messages, runs (one row per `claude -p` run), pending actions. All call `enable_tenant_rls()`. | `backend/src/main/resources/db/migration/V160__kitchen_assistant.sql` (reserve; check `V160` is still free when the story starts) | new |
| B | **MCP server** `/mcp` — Streamable HTTP endpoint exposing the 9 tools in §3. Authenticated by the agent token, not Firebase. | `org.iskcon.kms.assistant.mcp` | new |
| C | **Agent token** — 5-minute JWT minted per message: `sub` = user id, `tenant`, `conv`, `scope`. Signed with a key from Secret Manager. Validated by a filter that sets `TenantContext` exactly as `AuthenticationFilter` does for Firebase tokens. | `org.iskcon.kms.assistant.token` | new |
| D | **Assistant API** — `POST /api/v1/assistant/conversations`, `POST …/{id}/messages` (returns `text/event-stream`), `GET …/{id}`, `POST …/actions/{id}/confirm`, `POST …/actions/{id}/reject`. Behind new permissions. Enforces per-user and per-tenant daily run budgets. Relays the runner's stream to the browser as SSE. | `org.iskcon.kms.assistant.api` | new |
| E | **Runner** — small Node service, pinned `@anthropic-ai/claude-code` CLI, `POST /run` that spawns `claude -p …` and streams `stream-json` lines back. Kills the process at 60 s. Holds `CLAUDE_CODE_OAUTH_TOKEN` from Secret Manager. Starts `claude` with an **allow-listed environment** (S1 finding: inherited variables leaked user context past the flags). | `runner/` (new top-level folder, own Dockerfile and `cloudbuild.yaml`) | new |
| F | **Prompts and evals** — `kitchen-assistant.md` system prompt; `evals/golden.jsonl` (50 questions, EN + KN); `evals/run.sh`. | `runner/prompts/`, `evals/` | new |
| G | **Chat page** `/assistant` — message list, streaming reply, confirmation card, "working…" state, error with `KMS-` code. Menu entry. | `frontend/app/assistant/`, `frontend/lib/api.ts`, `nav.ts`, `routes.ts` | new + 3 shared-file changes |
| H | **Infra** — Cloud Run service `kms-<env>-assistant` (internal ingress, invoked only by the api service account), two secrets (`claude-oauth-token`, `assistant-token-key`), env vars on the api. | `infra/environment/main.tf`, `infra/deploy.sh` (third image) | change |
| I | **Observability** — every run row carries tokens, notional cost, duration, tool calls; Micrometer counters `assistant.runs`, `assistant.tool_calls`, `assistant.budget_refusals`; per-tenant view on `/audit` via existing `AuditService`. | `org.iskcon.kms.assistant`, `AuditAction.java` | change |

---

## 2. Request path (what one message does)

1. Browser `POST /api/v1/assistant/conversations/{id}/messages` with Firebase token and the text.
2. Api: `AuthenticationFilter` → user, tenant, permissions. Check `USE_KITCHEN_ASSISTANT`. Check budgets (per user/day, per tenant/day). Save the user message. Mint agent token. Build `prompt.txt` = last N turns + new message.
3. Api → runner `POST /run` (Cloud Run IAM identity token) with `{ profile: "kitchen-assistant", prompt, mcpUrl, agentToken }`.
4. Runner writes `mcp.json`, spawns:
   `claude -p --restricted --setting-sources "" --disable-slash-commands --system-prompt "$(cat prompts/kitchen-assistant.md)" --tools "" --strict-mcp-config --mcp-config mcp.json --allowedTools "mcp__ikms__*" --permission-mode dontAsk --model sonnet --no-session-persistence --output-format stream-json --verbose --include-partial-messages`
   in an empty working directory, and pipes stdout back as NDJSON.
5. `claude -p` calls `/mcp` tools with the agent token. The token filter sets the tenant; RLS applies.
6. Api relays events to the browser as SSE (`text`, `tool_call`, `proposal`, `done`, `error`), saves the assistant message and the run row.
7. For a proposal: browser shows a card. **Confirm** calls `POST …/actions/{id}/confirm` with the user's own Firebase token; the api re-checks the payload hash and calls the existing service (e.g. `ShoppingListService`) under the existing permission.

---

## 3. Tools (MCP server `ikms`)

Read, scope `agent:read`:

| Tool | Backed by | Returns |
|---|---|---|
| `get_inventory(item_name?)` | `StockFactsService` | on hand, unit, reorder level, cover |
| `list_low_stock()` | `LowStockAlertService` | items below reorder level |
| `get_meal_plan(date)` | meal module | meals, head counts, kitchen |
| `search_recipes(query)` | recipe module + Postgres FTS/`pg_trgm` (add index if missing) | top 10 names + ids |
| `get_recipe(id, servings)` | recipe module; **scaling in Java** | scaled ingredient list |
| `get_calendar(from, to)` | calendar module | Ekadashi and festival days |
| `get_shifts(date)` | shift module | names on duty, no phone numbers |

Propose, scope `agent:propose` (write a `pending_action` row, return its id, change nothing):

| Tool | Backed by | Note |
|---|---|---|
| `propose_shopping_list(date)` | `ShoppingListService` (the list is already derived, `V121`) | quantities computed by the service |
| `propose_stock_adjustment(item, qty, reason)` | `AdjustStockRequest` shape | `reason` must be an `AdjustmentReason` |

Rules: no tool takes `tenant_id`; tool descriptions are written as API docs for the model; every tool result is capped (e.g. 50 rows) so a run cannot blow the context.

---

## 4. Stories and tasks

Each task is sized at ≤ 2 hours per `CLAUDE.md`. "Proof" = `docs/work/proof/<task>.md` with real command output, as the builder agent already requires.

### S1 — Headless spike (ladder step 1). No IKMS code. Can start now.
- T1 Install/verify `claude` CLI on the laptop; run `claude -p --restricted --tools "" --json-schema …` with a pasted `inventory.json`; capture `result`, `total_cost_usd`, tokens. (1 h)
- T2 Ten questions, 5 EN + 5 KN, against the schema; record a table of pass/fail and tokens. (1 h)
- T3 Confirm the three lockdown checks: OAuth token accepted with `--restricted`; a planted `~/.claude/CLAUDE.md` is not loaded; "run `ls`" is refused. (1 h)
- **Done when:** 10/10 schema-valid answers and the three checks pass. Output: `docs/work/proof/E10-S1.md`.

### S2 — Agent token and MCP server with two read tools (step 2)
- T4 `V160__kitchen_assistant.sql`: `assistant_conversations`, `assistant_messages`, `assistant_runs`, `assistant_pending_actions`; each `enable_tenant_rls()`; RLS IT test added to `RowLevelSecurityIT`'s table list. (2 h)
- T5 Agent token: mint + verify (JWT, HS256 key from Secret Manager, in-memory default for tests); `AgentTokenFilter` sets `TenantContext` and a Spring `Authentication` with authorities `agent:read` / `agent:propose`; registered in `SecurityConfiguration` for `/mcp/**` only. (2 h)
- T6 MCP server: MCP Java SDK (`io.modelcontextprotocol.sdk`, Spring WebMVC transport) mounted at `/mcp`; `get_inventory`, `list_low_stock`. **Check first** that the SDK version used supports Streamable HTTP; if not, use its SSE transport and `"type": "sse"` in `mcp.json` — `claude -p` supports both. (2 h)
- T7 Integration test: two tenants, tool call with tenant A's token returns only A's rows. (1 h)
- T8 Manual: run `claude -p` from the laptop against local api with `--mcp-config`, 20 questions (10 EN, 10 KN), record which tool was chosen. (1 h)
- **Done when:** 20/20 correct tool choice; IT proves A cannot read B.

### S3 — Runner and streaming chat page (step 3)
- T9 `runner/`: Node 22 image, pinned CLI version, `/run` endpoint, spawn + stream, 60 s kill, empty working dir per run, no `$HOME/.claude` in image. Runs locally with Rajeev's own login. (2 h)
- T10 Assistant API: create conversation, post message, SSE relay, save messages and run row, history window (last 10 turns). (2 h)
- T11 Budgets: per-user/day and per-tenant/day run counts in settings with defaults; refusal gets a new `KMS-` code with plain text ("The assistant has done enough for today…"). (1 h)
- T12 Permissions: `USE_KITCHEN_ASSISTANT` and `CONFIRM_ASSISTANT_ACTION` added to `Permission.java` / `RolePermissions.java` for Temple Admin and Kitchen Manager (Rajeev to confirm roles). (1 h)
- T13 Frontend `/assistant`: message list, fetch-with-ReadableStream for SSE (EventSource cannot send the Authorization header), streaming text, error box with code; `api.ts` methods; `nav.ts` + `routes.ts` entries. (2 h)
- T14 Vitest for the page (streaming render, error state); tsc clean. (1 h)
- T15 Latency table: 20 messages on local, p50/p95 time-to-first-token and total; spawn overhead measured separately. (1 h)
- **Done when:** Rajeev chats on local for a week; latency table exists; a budget refusal shows its code.

### S4 — Propose and confirm (step 4)
- T16 `propose_shopping_list`, `propose_stock_adjustment` tools: compute via existing services, insert `pending_action` with payload + SHA-256 hash + idempotency key, return id. (2 h)
- T17 Confirm/reject endpoints: re-verify hash, call the existing service under the existing permission (`ShoppingListService`, `StockMovementService`), mark `confirmed`/`rejected`, audit event via `AuditService` with a new `AuditAction`. Second confirm is a no-op. (2 h)
- T18 Confirmation card in the chat: shows the list, Confirm / Reject, result state. (2 h)
- T19 Tests: confirm creates exactly one list; tampered payload refused with a `KMS-` code; expired (24 h) proposal refused. (1 h)
- **Done when:** a proposal becomes a real shopping list; double confirm creates one; tampering is refused.

### S5 — Evals and traces (step 5)
- T20 `evals/golden.jsonl`: 50 questions (25 EN, 25 KN) with expected tools and a short rubric; `evals/run.sh` runs each through the runner against a seeded local database and writes a report. (2 h)
- T21 Judge: second `claude -p --tools ""` call grades each answer against the rubric; report shows tool accuracy and answer score. (1 h)
- T22 Run rows → Micrometer counters; `/audit` shows assistant runs per tenant with tokens and tool calls. (2 h)
- T23 Deliberately break the prompt (rename a tool in it), run evals, confirm red; restore. (0.5 h)
- **Done when:** the broken prompt is caught by the eval run.

### S6 — Staging (deploy)
- T24 Terraform: `kms-<env>-assistant` Cloud Run service, internal ingress, api SA as invoker, secrets, env vars (`ASSISTANT_RUNNER_URL`, `ASSISTANT_TOKEN_KEY`). (2 h)
- T25 `infra/deploy.sh`: build and roll out the third image in parallel with web. (1 h)
- T26 Drive it on staging as Temple Admin; repeat the latency table; proof. (1 h)

Total: 26 tasks, about 36 hours of build time. At a few tasks per sitting this is roughly five to six weeks.

---

## 5. Reservations needed before S2 starts (shared files, allocated once)

- Migration `V160` (next free after `V159`; re-check at the time).
- Permissions `USE_KITCHEN_ASSISTANT`, `CONFIRM_ASSISTANT_ACTION`.
- `KMS-` codes: budget exhausted, assistant unavailable, proposal expired, proposal tampered, proposal already handled, runner timeout. Numbers from `ErrorCode.java`.
- `AuditAction`: `ASSISTANT_RUN`, `ASSISTANT_ACTION_CONFIRMED`, `ASSISTANT_ACTION_REJECTED`.
- `api.ts` signatures for the five endpoints; `nav.ts` entry "Assistant"; `routes.ts` `/assistant`.

---

## 6. Environments

Local first, deliberately. S1–S5 run on the laptop: Docker Postgres from `docker-compose.yml`, api on 8080, runner on 8090 using Rajeev's own `claude` login, Next.js dev server. Nothing reaches GCP until S6. The eval set runs locally too.

Staging adds the runner as a third Cloud Run service. The subscription token lives in Secret Manager and is mounted only on the runner.

---

## 7. Go / no-go numbers

| After | Measure | Proceed if |
|---|---|---|
| S1 | schema-valid answers | 10/10 |
| S2 | correct tool choice (EN + KN) | ≥ 19/20, and RLS IT green |
| S3 | p95 total latency on local | Rajeev decides; if unacceptable, evaluate long-lived process / Agent SDK before S4 |
| S4 | tamper + double-confirm tests | both refused / idempotent |
| S5 | eval regression | broken prompt caught |

---

## 8. Risks and what we do about them

- **Subscription usage limits.** Testing and evals share Rajeev's plan limits. Evals run at most nightly; per-run `--max-budget-usd` is informational on a subscription, so budgets are enforced by run counts in the api.
- **MCP transport.** If the Java SDK's Streamable HTTP is not ready, SSE transport is the fallback; `mcp.json` changes one word.
- **Process start latency** (≈1.3 s measured before any model time). Known; measured properly in T15; escape hatch is the long-lived `--input-format stream-json` process, later.
- **Chat content leaves the project.** Messages and tool results go to Anthropic's models. Fine for a personal learning project; tool outputs still omit phone numbers and addresses by design.
- **Kannada tool choice.** The system prompt tells the model to search recipes by English or transliterated name; T8 and the eval set measure it.

---

## 9. Decisions needed from Rajeev before S2

1. **Where E10 sits in `WORK_QUEUE.md`** — after items 1–3 are tested, or interleaved. S1 can start now either way.
2. **Who gets the assistant** — proposal: Temple Admin and Kitchen Manager; Kitchen Staff read-only later.
3. **Model** — `sonnet` by default; `opus` only for the eval judge.
4. **Daily budgets** — proposal: 100 runs per user, 500 per tenant, as settings.
5. **Repo placement of `runner/` and `evals/`** — new top-level folders, or under `backend/`?

---

## 10. What the plan does not do, on purpose

No LangGraph, no vector store, no OCR, no second agent, no mobile. Each of those is a later ladder step with its own plan, and each reuses everything built here (tools, token, propose/confirm, evals).

---

## 11. Phase 1b — the six things the assistant must do (added 2026-10-05)

Rajeev's words: the assistant should "chain together multiple tool calls and accomplish the user's request. If there is missing information … interactively chat with the user and get the information, and when it has everything it needs, then do the necessary tool call/s." He then chose six capabilities instead of exposing the whole API (350 endpoints): create a meal plan; check stock; create a shopping list; create a purchase order; give me the PO to send to my vendor; raise a request for volunteers for a day's meal preparation.

Endpoint research (2026-10-05, read-only) is summarised per stage below. All six writes need permissions every cook role has (Temple Admin, Kitchen Manager, Kitchen Staff) and no volunteer has; the agent token carries the user's own permissions, so this holds automatically.

**How writes happen (pending Rajeev's confirmation):** every write is a draft card the server runs only when the user presses Confirm (S4, generalised: a pending action stores the exact call and its hash; Confirm runs it through the existing service with the user's own login). Asking questions and gathering details happens in the conversation; nothing is saved until Confirm.

### Order

S3 (runner + chat page) and S4 (draft/confirm, generalised) and S5 (evals) stay as written and come first: every capability below needs them. Then:

### S7 — Stock and sufficiency (read only)
- Existing `get_inventory`, `list_low_stock`; fix the 50-row cut on the full list (paging or filter).
- Add `check_meal_sufficiency(from, to)` → `GET /meal-plans/sufficiency` (per dish: short by how much, order by when) and `get_shortfall()` → `GET /meal-plans/shortfall`.

### S8 — Meal planning
- Lookups: meal kinds, kitchens (fetch list, match by name — no search endpoint), recipe search, day context (Ekadashi, existing meals).
- `propose_meal(date, meal kind, dishes, head counts, ready-by?)` → draft of `POST /meals`.
- **Gap:** head counts → dish amounts is calculated only in the browser today (`MealComposer.tsx`: adults + 0.6 × children + 0.8 × seniors, × recipe per-head quantity, unit conversion, round up for pieces). The tool needs the same rule (decision D-b below).
- Second-step confirmations to carry through the card: Ekadashi (KMS-400048), adding dishes to a meal that already exists.

### S9 — Shopping list, purchase order, sending it
- There is no "create shopping list": the list is worked out fresh each time. `get_shopping_list()` reads it grouped by vendor; `propose_shopping_list_change` adds, unticks or changes a line.
- `propose_purchase_order(vendor, lines | from the vendor's shopping-list lines, needed by?)` → draft of `POST /purchase-orders` (starts as DRAFT). Vendors: fetch list, match by name.
- `propose_send_po(po)` → draft of `POST /purchase-orders/{id}/whatsapp` (sends the PDF to the vendor on WhatsApp in the vendor's language). The PDF itself is a download behind login, so chat gives a link to the order's page rather than the file. No email sending exists for POs.
- **Possible defect to check first:** the WhatsApp route sends a draft without the lead-time check (KMS-400148) the Send button applies.

### S10 — Volunteers for a meal
- A volunteer shift lives inside its meal. `propose_volunteer_shift(meal, title?, start, end, people needed)` → draft of `PUT /meals/{id}` with `volunteerShift`, using the UI's defaults (title "{meal} preparation on {date}", ends at ready-by, people still needed from meal crew).
- Creating a shift tells nobody; volunteers see it in their available shifts. Announcing it is a separate communication that only the Temple Admin can send (decision D-c below).

### S6 — Staging (unchanged, last)

### Later stages (agreed in principle, not scheduled)
- Dictation (microphone on the chat page; browser speech or Bhashini, EN + KN).
- Mobile app on the same chat API.
- Not chosen now: leave requests, notices/Today summary, the rest of the API.

### Decisions open for Rajeev
- D-a: writes by Confirm button (recommended) vs a typed "yes".
- D-b: head counts → dish amounts: move the rule to the server so the screen and the assistant share one copy (recommended) vs a second copy in the tool.
- D-c: "raise a request for volunteers": create the shift only (volunteers see it) vs also send an announcement (Temple Admin only).
