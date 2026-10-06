// Runner configuration, read once from the environment at start-up.
//
// Everything that differs between Rajeev's Mac and Cloud Run is here, so the code paths are the same
// in both places. The credential in particular is configuration, not code: the plan (§1 row E, §6)
// starts on his subscription login and moves to a KMS API key at go-live, and that swap must be an
// environment change, not a release.

import { existsSync } from "node:fs";
import { delimiter, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export type Credential =
  | { kind: "api-key"; name: "ANTHROPIC_API_KEY"; value: string }
  | { kind: "oauth-token"; name: "CLAUDE_CODE_OAUTH_TOKEN"; value: string }
  // Local development only: no secret is passed at all, and `claude` finds the developer's own
  // stored login (the macOS keychain) through HOME and USER. In the container there is no stored
  // login (nothing under $HOME/.claude is baked in), so this mode fails there, loudly, on first run.
  | { kind: "stored-login" };

export interface RunnerConfig {
  port: number;
  timeoutMs: number;
  maxConcurrent: number;
  model: string;
  claudeBin: string;
  promptsDir: string;
  credential: Credential;
  /** The runner's own HOME and USER, used only in stored-login mode. */
  home: string;
  user: string;
}

/** Where this package lives: dist/src/config.js -> package root, or src/config.ts under a loader. */
export const PACKAGE_ROOT = (() => {
  let dir = dirname(fileURLToPath(import.meta.url));
  for (let i = 0; i < 4; i++) {
    if (existsSync(join(dir, "package.json"))) return dir;
    dir = dirname(dir);
  }
  throw new Error("runner: could not find package.json above " + fileURLToPath(import.meta.url));
})();

/**
 * Credential precedence, as the task fixes it: an API key wins, then a subscription OAuth token, then
 * the stored login. Exactly one is ever passed to the child; passing both would leave which one
 * `claude` uses up to the CLI, and a run billed to the wrong account is the kind of thing nobody
 * notices for a month.
 */
export function pickCredential(env: NodeJS.ProcessEnv): Credential {
  const apiKey = env.ANTHROPIC_API_KEY?.trim();
  if (apiKey) return { kind: "api-key", name: "ANTHROPIC_API_KEY", value: apiKey };
  const oauth = env.CLAUDE_CODE_OAUTH_TOKEN?.trim();
  if (oauth) return { kind: "oauth-token", name: "CLAUDE_CODE_OAUTH_TOKEN", value: oauth };
  return { kind: "stored-login" };
}

/**
 * The pinned CLI from this package's node_modules first, so the version in package.json is the
 * version that runs; CLAUDE_BIN overrides it (tests point it at a fake); a `claude` on PATH is the
 * last resort, for a checkout where `npm install` has not been run.
 */
function findClaude(env: NodeJS.ProcessEnv): string {
  if (env.CLAUDE_BIN?.trim()) return resolve(env.CLAUDE_BIN.trim());
  const pinned = join(PACKAGE_ROOT, "node_modules", ".bin", "claude");
  if (existsSync(pinned)) return pinned;
  for (const dir of (env.PATH ?? "").split(delimiter)) {
    if (dir && existsSync(join(dir, "claude"))) return join(dir, "claude");
  }
  return "claude"; // spawn will fail with ENOENT and the run reports spawn_failed
}

function int(env: NodeJS.ProcessEnv, name: string, fallback: number, min: number): number {
  const raw = env[name]?.trim();
  if (!raw) return fallback;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < min) throw new Error(`runner: ${name} must be an integer >= ${min}, got "${raw}"`);
  return n;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): RunnerConfig {
  return {
    port: int(env, "PORT", 8090, 1),
    timeoutMs: int(env, "RUNNER_TIMEOUT_MS", 60_000, 1),
    maxConcurrent: int(env, "RUNNER_MAX_CONCURRENT", 4, 1),
    model: env.RUNNER_MODEL?.trim() || "sonnet",
    claudeBin: findClaude(env),
    promptsDir: env.RUNNER_PROMPTS_DIR?.trim() || join(PACKAGE_ROOT, "prompts"),
    credential: pickCredential(env),
    home: env.HOME ?? "",
    user: env.USER ?? "",
  };
}
