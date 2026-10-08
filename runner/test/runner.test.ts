// Runner tests (E10-T9). A fake `claude` stands in for the real one: it is a small node script whose
// behaviour is chosen by the prompt it reads on stdin, because its environment is allow-listed and
// cannot carry test switches. It reports back what it was given (argv, env, cwd, files), which is
// how the lockdown is checked from the outside rather than by re-reading our own code.

import assert from "node:assert/strict";
import { chmodSync, existsSync, mkdtempSync, writeFileSync } from "node:fs";
import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, describe, it } from "node:test";
import { buildArgs, buildChildEnv } from "../src/child.js";
import { loadConfig, pickCredential, type RunnerConfig } from "../src/config.js";
import { loadProfiles, NAME_FILL_SCHEMA } from "../src/profiles.js";
import { createRunnerServer } from "../src/server.js";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.SECRET-AGENT-TOKEN-SIG";
const API_KEY = "sk-ant-api-TEST-CREDENTIAL-0123456789";
const PROMPT_MARK = "PRIVATE-QUESTION-TEXT";

const FAKE = String.raw`
const fs = require("fs");
let input = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (c) => (input += c));
process.stdin.on("end", () => {
  const out = (o) => process.stdout.write(JSON.stringify(o) + "\n");
  const mode = (input.match(/MODE:(\w+)/) || [])[1] || "echo";
  if (mode === "echo") {
    out({ type: "fake_report", argv: process.argv.slice(2), env: process.env, cwd: process.cwd(),
          files: fs.readdirSync(process.cwd()),
          mcp: fs.existsSync("mcp.json") ? JSON.parse(fs.readFileSync("mcp.json", "utf8")) : null, stdin: input });
    out({ type: "result", subtype: "success", is_error: false });
  } else if (mode === "sleep") {
    out({ type: "fake_pid", pid: process.pid });
    setInterval(() => {}, 1000);
  } else if (mode === "stream") {
    out({ type: "line", n: 1, at: Date.now() });
    setTimeout(() => out({ type: "line", n: 2, at: Date.now() }), 400);
    setTimeout(() => { process.stdout.write(JSON.stringify({ type: "line", n: 3, at: Date.now() })); process.exit(0); }, 800);
  } else if (mode === "hold") {
    setTimeout(() => { out({ type: "result" }); process.exit(0); }, 700);
  } else if (mode === "fail") {
    const mcp = fs.readFileSync("mcp.json", "utf8");
    process.stderr.write("boom, the config was " + mcp + " and key " + (process.env.ANTHROPIC_API_KEY || ""));
    process.exit(3);
  }
});
`;

let dir: string;
let fakeBin: string;

before(() => {
  dir = mkdtempSync(join(tmpdir(), "runner-test-"));
  fakeBin = join(dir, "claude");
  writeFileSync(fakeBin, `#!${process.execPath}\n${FAKE}`);
  chmodSync(fakeBin, 0o755);
});

function config(overrides: Partial<RunnerConfig> = {}, env: NodeJS.ProcessEnv = {}): RunnerConfig {
  return {
    ...loadConfig({ CLAUDE_BIN: fakeBin, HOME: "/Users/someone", USER: "someone", ...env }),
    ...overrides,
  };
}

interface Started { url: string; server: Server; logs: Array<Record<string, unknown>> }

async function start(cfg: RunnerConfig): Promise<Started> {
  const logs: Array<Record<string, unknown>> = [];
  const server = createRunnerServer(cfg, loadProfiles(cfg.promptsDir), (e) => logs.push(e));
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  return { url: `http://127.0.0.1:${(server.address() as AddressInfo).port}`, server, logs };
}

function body(prompt: string, extra: Record<string, unknown> = {}) {
  return JSON.stringify({ profile: "kitchen-assistant", prompt, mcpUrl: "http://localhost:8095/mcp", agentToken: TOKEN, ...extra });
}

/** Reads the NDJSON response, recording when each line arrived. */
async function run(url: string, prompt: string, raw: string = body(prompt)): Promise<{ status: number; type: string | null; lines: Array<{ at: number; v: any }> }> {
  const res = await fetch(`${url}/run`, { method: "POST", headers: { "content-type": "application/json" }, body: raw });
  const lines: Array<{ at: number; v: any }> = [];
  const reader = res.body!.getReader();
  const dec = new TextDecoder();
  let buf = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let i;
    while ((i = buf.indexOf("\n")) !== -1) {
      lines.push({ at: Date.now(), v: JSON.parse(buf.slice(0, i)) });
      buf = buf.slice(i + 1);
    }
  }
  assert.equal(buf, "", "every line ends with a newline");
  return { status: res.status, type: res.headers.get("content-type"), lines };
}

describe("credential and environment", () => {
  it("prefers ANTHROPIC_API_KEY, then CLAUDE_CODE_OAUTH_TOKEN, then the stored login", () => {
    assert.deepEqual(pickCredential({ ANTHROPIC_API_KEY: "k", CLAUDE_CODE_OAUTH_TOKEN: "o" }), { kind: "api-key", name: "ANTHROPIC_API_KEY", value: "k" });
    assert.deepEqual(pickCredential({ CLAUDE_CODE_OAUTH_TOKEN: "o" }), { kind: "oauth-token", name: "CLAUDE_CODE_OAUTH_TOKEN", value: "o" });
    assert.deepEqual(pickCredential({ ANTHROPIC_API_KEY: "  ", CLAUDE_CODE_OAUTH_TOKEN: "o" }).kind, "oauth-token");
    assert.deepEqual(pickCredential({}), { kind: "stored-login" });
  });

  it("passes exactly one credential and nothing else from the runner's environment", () => {
    const both = config({}, { ANTHROPIC_API_KEY: API_KEY, CLAUDE_CODE_OAUTH_TOKEN: "oauth-x", SECRET_DB_PASSWORD: "p" });
    const env = buildChildEnv(both, "/tmp/run/home");
    assert.deepEqual(Object.keys(env).sort(), ["ANTHROPIC_API_KEY", "HOME", "LANG", "PATH"]);
    assert.equal(env.ANTHROPIC_API_KEY, API_KEY);
    assert.equal(env.HOME, "/tmp/run/home", "with a secret, HOME is the run's own empty home");

    const oauth = buildChildEnv(config({}, { CLAUDE_CODE_OAUTH_TOKEN: "oauth-x" }), "/tmp/run/home");
    assert.deepEqual(Object.keys(oauth).sort(), ["CLAUDE_CODE_OAUTH_TOKEN", "HOME", "LANG", "PATH"]);

    const login = buildChildEnv(config(), "/tmp/run/home");
    assert.deepEqual(Object.keys(login).sort(), ["HOME", "LANG", "PATH", "USER"]);
    assert.equal(login.HOME, "/Users/someone", "stored login is found through the real HOME");
  });

  it("builds the plan's flags and keeps the prompt out of argv", () => {
    const cfg = config();
    const args = buildArgs(loadProfiles(cfg.promptsDir).get("kitchen-assistant")!, "/w/mcp.json", "sonnet");
    const flag = (f: string) => args[args.indexOf(f) + 1];
    for (const f of ["-p", "--restricted", "--disable-slash-commands", "--strict-mcp-config", "--no-session-persistence", "--verbose", "--include-partial-messages"]) {
      assert.ok(args.includes(f), f);
    }
    assert.equal(flag("--tools"), "");
    assert.equal(flag("--setting-sources"), "");
    assert.equal(flag("--allowedTools"), "mcp__ikms__*");
    assert.equal(flag("--permission-mode"), "dontAsk");
    assert.equal(flag("--output-format"), "stream-json");
    assert.equal(flag("--mcp-config"), "/w/mcp.json");
    assert.match(flag("--system-prompt")!, /name_kn/);
  });

  it("gives name-fill no tools at all, the schema, and its own model", () => {
    const cfg = config();
    const args = buildArgs(loadProfiles(cfg.promptsDir).get("name-fill")!, null, "sonnet");
    const flag = (f: string) => args[args.indexOf(f) + 1];
    for (const f of ["-p", "--restricted", "--disable-slash-commands", "--strict-mcp-config", "--no-session-persistence"]) {
      assert.ok(args.includes(f), f);
    }
    assert.equal(flag("--tools"), "", "no built-in tools");
    assert.equal(flag("--setting-sources"), "");
    assert.ok(!args.includes("--mcp-config"), "no MCP server is configured");
    assert.ok(!args.includes("--allowedTools"), "nothing is allowed because nothing exists");
    assert.equal(flag("--output-format"), "json");
    assert.deepEqual(JSON.parse(flag("--json-schema")!), NAME_FILL_SCHEMA);
    assert.equal(flag("--model"), "opus", "the profile's model wins over the runner's");
    assert.match(flag("--system-prompt")!, /store items/);
    assert.ok(!args.includes("--include-partial-messages"));
  });
});

describe("POST /run against a fake claude", () => {
  let s: Started;
  const cfg = () => config({ timeoutMs: 1000, maxConcurrent: 1 }, { ANTHROPIC_API_KEY: API_KEY });
  before(async () => { s = await start(cfg()); });
  after(() => s.server.close());

  it("runs in an empty temp dir with mcp.json, allow-listed env, prompt on stdin, and deletes the dir", async () => {
    const r = await run(s.url, `MODE:echo ${PROMPT_MARK}`);
    assert.equal(r.status, 200);
    assert.match(r.type!, /^application\/x-ndjson/);
    const rep = r.lines[0]!.v;
    assert.equal(rep.type, "fake_report");
    assert.deepEqual(rep.files, ["mcp.json"]);
    assert.deepEqual(rep.mcp, { mcpServers: { ikms: { type: "http", url: "http://localhost:8095/mcp", headers: { Authorization: `Bearer ${TOKEN}` } } } });
    assert.equal(rep.stdin, `MODE:echo ${PROMPT_MARK}`);
    assert.ok(!rep.argv.some((a: string) => a.includes(PROMPT_MARK)), "prompt not in argv");
    const keys = Object.keys(rep.env).filter((k) => k !== "__CF_USER_TEXT_ENCODING"); // macOS adds this in-process
    assert.deepEqual(keys.sort(), ["ANTHROPIC_API_KEY", "HOME", "LANG", "PATH"]);
    assert.ok(rep.env.HOME.startsWith(join(rep.cwd, "..")) || rep.env.HOME.endsWith("/home"));
    assert.equal(r.lines.at(-1)!.v.type, "result", "a clean run has no runner_error line");
    assert.equal(existsSync(rep.cwd), false, "run directory deleted");
  });

  it("streams lines as they arrive, not at the end", async () => {
    const r = await run(s.url, "MODE:stream");
    assert.deepEqual(r.lines.map((l) => l.v.n), [1, 2, 3], "a last line without a newline still arrives whole");
    // Line 1 was written ~800 ms before line 3; it must have reached us well before line 3 did.
    assert.ok(r.lines[2]!.at - r.lines[0]!.at >= 500, `line 1 arrived ${r.lines[2]!.at - r.lines[0]!.at} ms before line 3`);
  });

  it("kills the process at the timeout and ends with runner_error timeout", async () => {
    const t0 = Date.now();
    const r = await run(s.url, "MODE:sleep");
    const elapsed = Date.now() - t0;
    const pid = r.lines[0]!.v.pid as number;
    assert.deepEqual(r.lines.at(-1)!.v.type, "runner_error");
    assert.equal(r.lines.at(-1)!.v.code, "timeout");
    assert.ok(elapsed >= 1000 && elapsed < 4000, `ended after ${elapsed} ms`);
    assert.throws(() => process.kill(pid, 0), "the fake claude is gone");
  });

  it("reports a non-zero exit and redacts secrets from the logged stderr", async () => {
    const r = await run(s.url, "MODE:fail");
    assert.equal(r.lines.at(-1)!.v.code, "exit_nonzero");
    assert.equal(r.lines.at(-1)!.v.exitCode, 3);
    const end = s.logs.filter((l) => l.event === "run_end").at(-1)!;
    assert.match(String(end.stderrTail), /\[redacted\]/);
  });

  it("refuses an unknown profile and a malformed request", async () => {
    let res = await fetch(`${s.url}/run`, { method: "POST", body: body("hi", { profile: "root-shell" }) });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).code, "unknown_profile");
    res = await fetch(`${s.url}/run`, { method: "POST", body: body("hi", { agentToken: "a b" }) });
    assert.equal(res.status, 400);
    res = await fetch(`${s.url}/run`, { method: "POST", body: body("hi", { mcpUrl: "file:///etc/passwd" }) });
    assert.equal(res.status, 400);
    res = await fetch(`${s.url}/run`, { method: "POST", body: "{" });
    assert.equal(res.status, 400);
  });

  it("refuses with busy past the concurrency limit, then accepts again", async () => {
    const first = run(s.url, "MODE:hold");
    await new Promise((r) => setTimeout(r, 300));
    const res = await fetch(`${s.url}/run`, { method: "POST", body: body("MODE:echo") });
    assert.equal(res.status, 503);
    assert.equal(res.headers.get("retry-after"), "2");
    assert.deepEqual(await res.json(), { type: "runner_error", code: "busy", message: "The assistant is busy with other requests. Try again in a moment." });
    assert.equal((await first).lines.at(-1)!.v.type, "result");
    assert.equal((await run(s.url, "MODE:echo")).status, 200);
  });

  it("runs name-fill with no mcpUrl or token: an empty directory, no mcp.json, the items on stdin", async () => {
    const raw = JSON.stringify({ profile: "name-fill", prompt: `MODE:echo Language: Telugu (te)\n1. Rice ${PROMPT_MARK}` });
    const r = await run(s.url, "", raw);
    assert.equal(r.status, 200);
    const rep = r.lines[0]!.v;
    assert.deepEqual(rep.files, [], "nothing in the working directory at all");
    assert.equal(rep.mcp, null);
    assert.match(rep.stdin, /Language: Telugu/);
    assert.ok(!rep.argv.includes("--mcp-config"));
    assert.equal(r.lines.at(-1)!.v.type, "result");
    assert.equal(existsSync(rep.cwd), false, "run directory deleted");
  });

  it("refuses name-fill sent an agent token or mcpUrl, and the assistant sent none", async () => {
    let res = await fetch(`${s.url}/run`, { method: "POST", body: JSON.stringify({ profile: "name-fill", prompt: "x", agentToken: TOKEN }) });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).message, "This profile takes no mcpUrl or agentToken.");
    res = await fetch(`${s.url}/run`, { method: "POST", body: JSON.stringify({ profile: "name-fill", prompt: "x", mcpUrl: "http://localhost:8095/mcp" }) });
    assert.equal(res.status, 400);
    res = await fetch(`${s.url}/run`, { method: "POST", body: JSON.stringify({ profile: "kitchen-assistant", prompt: "x" }) });
    assert.equal(res.status, 400, "the assistant still needs both");
  });

  it("serves /health", async () => {
    const res = await fetch(`${s.url}/health`);
    assert.deepEqual(await res.json(), { status: "UP", running: 0, maxConcurrent: 1, credential: "api-key" });
  });

  it("never logged the agent token, the credential or the prompt", () => {
    const all = JSON.stringify(s.logs);
    assert.ok(s.logs.length > 5);
    assert.ok(!all.includes(TOKEN), "token");
    assert.ok(!all.includes("SECRET-AGENT-TOKEN-SIG"), "token signature");
    assert.ok(!all.includes(API_KEY), "credential");
    assert.ok(!all.includes(PROMPT_MARK), "prompt");
  });
});

describe("spawn failure", () => {
  it("ends the stream with runner_error spawn_failed", async () => {
    const s = await start(config({ claudeBin: join(tmpdir(), "no-such-claude-binary") }));
    try {
      const r = await run(s.url, "hello");
      assert.equal(r.status, 200);
      assert.deepEqual(r.lines.map((l) => l.v.code), ["spawn_failed"]);
    } finally {
      s.server.close();
    }
  });
});
