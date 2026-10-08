// One run: a fresh directory, a `claude -p` in it, its stdout streamed back line by line, and the
// directory gone afterwards whatever happened.

import { spawn } from "node:child_process";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import type { ServerResponse } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { buildArgs, buildChildEnv, buildMcpConfig } from "./child.js";
import type { RunnerConfig } from "./config.js";
import { redact, type Logger } from "./log.js";
import type { Profile } from "./profiles.js";

export interface RunRequest {
  profile: Profile;
  prompt: string;
  /** Both null for a profile with no MCP server (name-fill), and only then. */
  mcpUrl: string | null;
  agentToken: string | null;
}

/**
 * The last line of a stream that did not end well. The api tells these apart by `code`, and turns
 * each into its own KMS- code for the user; nothing here is meant to be shown as it is.
 */
export type RunnerErrorCode = "timeout" | "spawn_failed" | "exit_nonzero" | "client_closed";

export function runnerErrorLine(code: RunnerErrorCode, message: string, extra: Record<string, unknown> = {}): string {
  return JSON.stringify({ type: "runner_error", code, message, ...extra }) + "\n";
}

/**
 * Runs and streams. Resolves when the response has ended and the directory is deleted; never
 * rejects, because every failure is reported in the stream itself.
 */
export function runClaude(config: RunnerConfig, req: RunRequest, res: ServerResponse, runId: string, log: Logger): Promise<void> {
  const secrets = [req.agentToken ?? undefined, config.credential.kind === "stored-login" ? undefined : config.credential.value];
  const started = Date.now();
  const timeoutMs = req.profile.timeoutMs ?? config.timeoutMs;

  // 0700 by mkdtemp. work/ is the child's cwd: empty but for mcp.json (and empty outright for a
  // profile with no MCP server), so there is no CLAUDE.md or .claude/ for it to find. home/ is its HOME when a secret is configured (see buildChildEnv).
  const root = mkdtempSync(join(tmpdir(), "kms-run-"));
  const work = join(root, "work");
  const home = join(root, "home");
  mkdirSync(work, { mode: 0o700 });
  mkdirSync(home, { mode: 0o700 });
  let mcpPath: string | null = null;
  if (req.profile.mcpServer !== null && req.mcpUrl !== null && req.agentToken !== null) {
    mcpPath = join(work, "mcp.json");
    writeFileSync(mcpPath, buildMcpConfig(req.profile, req.mcpUrl, req.agentToken), { mode: 0o600 });
  }

  res.writeHead(200, {
    "Content-Type": "application/x-ndjson; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Run-Id": runId,
  });
  res.flushHeaders();

  log({ event: "run_start", runId, profile: req.profile.name, promptChars: req.prompt.length, mcpUrl: req.mcpUrl });

  return new Promise<void>((resolveRun) => {
    let finished = false;
    let timedOut = false;
    let clientGone = false;
    let lines = 0;
    let partial = "";
    let stderrTail = "";

    // detached: the child leads its own process group, so a kill reaches anything it started too.
    const child = spawn(config.claudeBin, buildArgs(req.profile, mcpPath, config.model), {
      cwd: work,
      env: buildChildEnv(config, home),
      stdio: ["pipe", "pipe", "pipe"],
      detached: true,
    });

    const killGroup = () => {
      if (child.pid === undefined || child.exitCode !== null || child.signalCode !== null) return;
      try {
        process.kill(-child.pid, "SIGKILL");
      } catch {
        try { child.kill("SIGKILL"); } catch { /* already gone */ }
      }
    };

    const timer = setTimeout(() => {
      timedOut = true;
      killGroup();
    }, timeoutMs);

    // The api went away (the user closed the page, or the api's own timeout fired): there is nobody
    // to stream to, so stop spending the subscription on it.
    res.on("close", () => {
      if (!finished) {
        clientGone = true;
        killGroup();
      }
    });

    const finish = (outcome: { code: number | null; signal: NodeJS.Signals | null; spawnError?: Error }) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      if (partial) {
        // A last line without its newline still goes out whole, before any error line.
        res.write(partial + "\n");
        lines++;
        partial = "";
      }
      let result: string;
      if (outcome.spawnError) {
        result = "spawn_failed";
        res.write(runnerErrorLine("spawn_failed", "The assistant could not be started."));
      } else if (timedOut) {
        result = "timeout";
        res.write(runnerErrorLine("timeout", `The assistant took longer than ${Math.round(timeoutMs / 1000)} seconds and was stopped.`));
      } else if (clientGone) {
        result = "client_closed";
      } else if (outcome.code !== 0) {
        result = "exit_nonzero";
        res.write(runnerErrorLine("exit_nonzero", "The assistant stopped with an error.", { exitCode: outcome.code, signal: outcome.signal }));
      } else {
        result = "ok";
      }
      res.end();
      try { rmSync(root, { recursive: true, force: true }); } catch { /* best effort; tmp is cleaned anyway */ }
      log({
        event: "run_end",
        runId,
        result,
        exitCode: outcome.code,
        signal: outcome.signal,
        ms: Date.now() - started,
        lines,
        ...(outcome.spawnError ? { spawnError: redact(outcome.spawnError.message, secrets) } : {}),
        ...(result !== "ok" && stderrTail ? { stderrTail: redact(stderrTail, secrets) } : {}),
      });
      resolveRun();
    };

    child.on("error", (err) => {
      // Fires without a pid when the binary could not be started at all; 'close' may not follow.
      if (child.pid === undefined) finish({ code: null, signal: null, spawnError: err });
    });
    child.on("close", (code, signal) => finish({ code, signal }));

    // Pass stdout through a whole line at a time, as each arrives. Whole lines, so the error line
    // written at the end can never be glued onto half an event.
    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk: string) => {
      if (clientGone) return;
      const text = partial + chunk;
      const cut = text.lastIndexOf("\n");
      if (cut === -1) {
        partial = text;
        return;
      }
      const ready = text.slice(0, cut + 1);
      partial = text.slice(cut + 1);
      for (const ch of ready) if (ch === "\n") lines++;
      res.write(ready);
    });

    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk: string) => {
      stderrTail = (stderrTail + chunk).slice(-4000);
    });

    // The prompt goes in on stdin (see buildArgs). EPIPE if the child dies first is not news: its
    // exit is what gets reported.
    child.stdin.on("error", () => {});
    child.stdin.end(req.prompt, "utf8");
  });
}
