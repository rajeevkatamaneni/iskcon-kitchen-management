# Kitchen Assistant runner

A small Node service (E10-T9, plan `docs/work/E10-KITCHEN-ASSISTANT-PLAN.md` §1 row E). The api
sends it one message; it runs one locked-down `claude -p` and streams the CLI's `stream-json`
events back, a line at a time, as they happen.

## API

`POST /run` with JSON:

```json
{ "profile": "kitchen-assistant", "prompt": "how much toor dal do we have?",
  "mcpUrl": "http://localhost:8095/mcp", "agentToken": "<five-minute agent JWT>" }
```

Reply: `200`, `Content-Type: application/x-ndjson`, one `claude` stream-json event per line. If the
run did not end cleanly, the last line is

```json
{"type":"runner_error","code":"timeout | spawn_failed | exit_nonzero","message":"..."}
```

Refused before a run starts, as a plain JSON body of the same shape:
`400 unknown_profile`, `400 invalid_request`, `413 invalid_request` (body over 1 MB),
`503 busy` with `Retry-After: 2` when the concurrency limit is reached.

`GET /health` returns `{"status":"UP","running":0,"maxConcurrent":4,"credential":"stored-login"}`.

## What each run gets

- A fresh temp directory as its working directory, holding only `mcp.json` (the IKMS server, with
  the agent token as a Bearer header). Deleted when the run ends, however it ends.
- The plan's flags (§2 step 4): no built-in tools, no settings or CLAUDE.md from anywhere, only the
  `ikms` MCP tools, no session saved. The system prompt is `prompts/kitchen-assistant.md`.
- The prompt on stdin, not on the command line.
- An environment built from nothing: `PATH`, `HOME`, `LANG` and one credential. Nothing else of the
  runner's environment is passed.
- Killed (with anything it started) after `RUNNER_TIMEOUT_MS`, or when the caller disconnects.

The prompt, the agent token and the credential are never logged.

## Configuration

| Variable | Default | |
|---|---|---|
| `PORT` | `8090` | |
| `RUNNER_TIMEOUT_MS` | `60000` | per run |
| `RUNNER_MAX_CONCURRENT` | `4` | runs at once; more get `503 busy` |
| `RUNNER_MODEL` | `sonnet` | |
| `CLAUDE_BIN` | the pinned CLI in `node_modules` | |
| `ANTHROPIC_API_KEY` | | used if set |
| `CLAUDE_CODE_OAUTH_TOKEN` | | used if set and there is no API key |

With neither credential set the CLI's own stored login is used, found through the runner's `HOME`
and `USER`. That is for local development on a Mac where `claude` is logged in. The container has
no stored login, so there a credential must be set.

With a credential set, each run's `HOME` is an empty directory of its own, so runs cannot see each
other or the runner's account.

## Run it locally

```bash
cd runner
npm install
npm run build
npm start                       # listens on 8090, uses your own `claude` login
```

Against the local api started by `tools/assistant-dev/try.sh start`:

```bash
KEY_DIR=tools/assistant-dev/.local      # in the main checkout
TOKEN=$(KMS_ASSISTANT_TOKEN_KEY=$(cat $KEY_DIR/token-key) python3 tools/assistant-dev/mint-token.py \
          --user $(cat $KEY_DIR/user) --tenant $(cat $KEY_DIR/tenant))
curl -N localhost:8090/run -H 'content-type: application/json' -d "{\"profile\":\"kitchen-assistant\",
  \"prompt\":\"how much toor dal do we have?\",\"mcpUrl\":\"http://localhost:$(cat $KEY_DIR/port)/mcp\",
  \"agentToken\":\"$TOKEN\"}"
```

## Tests

```bash
npm test
```

A fake `claude` script stands in for the CLI, so the tests need no login and no network. They
check the environment allow-list, credential precedence, that no secret or prompt reaches the logs,
streaming, the timeout kill, the busy limit and unknown profiles.

## Container

```bash
docker build -t kms-runner .
docker run --rm -p 8090:8090 -e CLAUDE_CODE_OAUTH_TOKEN=... kms-runner
```

Node 22 slim, non-root user `runner`, CLI pinned in `package.json` (`@anthropic-ai/claude-code`
2.1.289). Deploying it is S6 (`infra/`), not this folder.
