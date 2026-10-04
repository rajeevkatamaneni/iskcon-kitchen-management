# E10-S2 — build contract (agent token + MCP server, two read tools)

Plan: `docs/work/E10-KITCHEN-ASSISTANT-PLAN.md`, §1 rows A–C, §3, §4 S2, §5. Branch `agentic-mvp`.
Rajeev decided 2026-10-03: build S1 + S2, then stop; §9 proposals stand (Temple Admin + Kitchen
Manager, sonnet, 100/500 daily runs, `runner/` and `evals/` as top-level folders).

Local only. Nothing is committed, pushed or deployed by a builder. Each builder leaves
`docs/work/proof/<task>.md` with real command output.

## Running backend tests here

`export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` then
`cd backend && ./gradlew test --tests '<your classes>'`. Docker Desktop must be up (Testcontainers).
Run only your own test classes plus anything you touched; the conductor runs the full suite on merge.

## Reservations (allocated once — do not take anything else shared)

- Migration **`V160__kitchen_assistant.sql`** (T4 only). V156–V158 are free but lower than V159,
  which staging has applied, so they are unusable.
- Package roots: `org.iskcon.kms.assistant.token` (T5), `org.iskcon.kms.assistant.mcp` (T6).
- No `KMS-` codes, permissions or `AuditAction`s are added in S2. An invalid/expired agent token is a
  plain 401 from the filter with no body detail (the caller is `claude -p`, not a person).

## Path ownership (builders run in parallel; a file belongs to exactly one of them)

| Task | Owns | May not touch |
|---|---|---|
| T4 | `V160__kitchen_assistant.sql`, `RowLevelSecurityIT.java` | everything else |
| T5 | `assistant/token/**`, `auth/SecurityConfiguration.java`, `application*.yml` keys under `kms.assistant.token.*`, its tests, one dependency line in `build.gradle.kts` if needed | `assistant/mcp/**`, migrations |
| T6 | `assistant/mcp/**`, its tests, MCP SDK dependency line(s) in `build.gradle.kts`, `application*.yml` keys under `kms.assistant.mcp.*` | `SecurityConfiguration.java`, `assistant/token/**`, migrations |

`build.gradle.kts` is shared by T5 and T6: add your lines in the dependencies block, nothing else.
The conductor merges.

## The seam between T5 and T6

T5's `AgentTokenFilter` runs on `/mcp/**` only (its own `SecurityFilterChain` with
`securityMatcher("/mcp/**")`, ordered before the Firebase chain; the Firebase chain must not see
`/mcp`). For a valid token it:

1. Loads the user by `sub` from our `users` table; a disabled or missing user, or a `tenant` claim
   that does not match the user's tenant, is a 401.
2. Sets `TenantContext` exactly as `AuthenticationFilter` does for a Firebase token, and clears it
   afterwards the same way.
3. Sets an `Authentication` whose principal is `AgentPrincipal(UUID userId, UUID tenantId,
   UUID conversationId, Set<String> scopes)` and whose authorities are the scopes
   (`agent:read`, `agent:propose`) **plus the user's own permissions from `RolePermissions`** — so
   the agent can never do more than the person it acts for, and existing `@PreAuthorize` checks on
   services still hold.
4. Requires authority `agent:read` for any `/mcp/**` request.

Token: JWT HS256, 5-minute expiry, claims `sub`, `tenant`, `conv`, `scope` (space-separated), `iat`,
`exp`. Key from config `kms.assistant.token.key` (base64, ≥ 256 bits); tests and local use a
generated in-memory default when unset; production will mount it from Secret Manager in S6.
`AgentTokenService.mint(userId, tenantId, conversationId, scopes)` and `verify(token)`.

T6 never reads the token. Its tool handlers rely only on `TenantContext` (and the principal, if
needed) being set by the time they run. T6's tests set `TenantContext` directly.

## T6 tools (S2 scope: two)

- `get_inventory(item_name?)` → `StockFactsService`: on hand, unit, reorder level, cover.
- `list_low_stock()` → `LowStockAlertService`: items below reorder level.
- No tool takes `tenant_id`. Results capped at 50 rows. Return item names in English **and Kannada**
  where the catalogue has them (S1 finding). Descriptions written as API docs for a model.
- Transport: Streamable HTTP at `/mcp` if the MCP Java SDK version supports it with Spring WebMVC on
  Boot 3.3; otherwise SSE. Record which in the proof.
