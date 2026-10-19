#!/usr/bin/env bash
# Ask the Kitchen Assistant one question against a LOCAL api's /mcp, the way the runner will (E10-T8).
# LOCAL DEVELOPMENT ONLY.
#
#   KMS_ASSISTANT_TOKEN_KEY=<base64> KMS_DEV_USER=<users.id> KMS_DEV_TENANT=<tenants.id> \
#     MCP_URL=http://localhost:8091/mcp  tools/assistant-dev/run-question.sh "how much ghee do we have?"
#
# Writes the raw stream-json to stdout. Each call:
#   - mints a fresh agent token (they last five minutes; one per question, as the API will),
#   - makes an empty working directory, so no CLAUDE.md or project settings can be picked up,
#   - writes mcp.json there with the token in an Authorization header,
#   - runs `claude -p` with the plan's flags (E10 plan §2 step 4) and an allow-listed environment.
#
# Why `env -i`: the E10-S1 spike found that inherited variables leaked the developer's own context
# past the lockdown flags. So claude gets PATH, HOME (its login lives in the macOS keychain, found
# through HOME/USER), USER, LANG for UTF-8 Kannada, and CLAUDE_CODE_OAUTH_TOKEN only if the caller
# set one. Nothing else from this shell reaches it.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
QUESTION="${1:?usage: run-question.sh \"question\"}"
: "${KMS_ASSISTANT_TOKEN_KEY:?set KMS_ASSISTANT_TOKEN_KEY to the key the local api was started with}"
: "${KMS_DEV_USER:?set KMS_DEV_USER to a users.id at the seeded temple}"
: "${KMS_DEV_TENANT:?set KMS_DEV_TENANT to that user tenant id}"
MCP_URL="${MCP_URL:-http://localhost:8080/mcp}"
MODEL="${MODEL:-sonnet}"
# PROMPT_FILE overrides the system prompt, for controls (e.g. the prompt without its word list).

TOKEN="$(KMS_ASSISTANT_TOKEN_KEY="$KMS_ASSISTANT_TOKEN_KEY" python3 "$HERE/mint-token.py" \
  --user "$KMS_DEV_USER" --tenant "$KMS_DEV_TENANT")"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/kms-assistant.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
cat > "$WORK/mcp.json" <<JSON
{
  "mcpServers": {
    "ikms": {
      "type": "http",
      "url": "$MCP_URL",
      "headers": { "Authorization": "Bearer $TOKEN" }
    }
  }
}
JSON

SYSTEM_PROMPT="$(cat "${PROMPT_FILE:-$HERE/kitchen-assistant.md}")"
CLAUDE_BIN="$(command -v claude)"

ENV_ARGS=(PATH="/usr/bin:/bin:/usr/sbin:/sbin:$(dirname "$CLAUDE_BIN")" HOME="$HOME" USER="${USER:-$(id -un)}" LANG="en_US.UTF-8")
if [[ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]]; then ENV_ARGS+=(CLAUDE_CODE_OAUTH_TOKEN="$CLAUDE_CODE_OAUTH_TOKEN"); fi

cd "$WORK"
exec env -i "${ENV_ARGS[@]}" "$CLAUDE_BIN" -p \
  --restricted \
  --setting-sources "" \
  --disable-slash-commands \
  --system-prompt "$SYSTEM_PROMPT" \
  --tools "" \
  --strict-mcp-config \
  --mcp-config "$WORK/mcp.json" \
  --allowedTools "mcp__ikms__*" \
  --permission-mode dontAsk \
  --model "$MODEL" \
  --no-session-persistence \
  --output-format stream-json \
  --verbose \
  --include-partial-messages \
  "$QUESTION"
