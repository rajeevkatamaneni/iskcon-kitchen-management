// The runner's HTTP surface: POST /run and GET /health, on node:http with no framework.
//
// Who may call it is not decided here. On Cloud Run the service has internal ingress and only the
// api's service account may invoke it (plan §1 row H); locally it listens on localhost. So the
// checks below are about the request being well formed, not about the caller.

import { randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { pathToFileURL } from "node:url";
import { loadConfig, type RunnerConfig } from "./config.js";
import { stderrLogger, type Logger } from "./log.js";
import { loadProfiles, type Profile } from "./profiles.js";
import { runClaude } from "./run.js";

const MAX_BODY_BYTES = 1_000_000;
const MAX_PROMPT_CHARS = 200_000;
const MAX_TOKEN_CHARS = 8_000;

function sendJson(res: ServerResponse, status: number, body: unknown, headers: Record<string, string> = {}): void {
  const text = JSON.stringify(body);
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Content-Length": Buffer.byteLength(text), ...headers });
  res.end(text);
}

/** Errors before a run starts are ordinary JSON responses, with the same shape as the stream's last line. */
function refuse(res: ServerResponse, status: number, code: string, message: string, headers: Record<string, string> = {}): void {
  sendJson(res, status, { type: "runner_error", code, message }, headers);
}

function readBody(req: IncomingMessage): Promise<string | null> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on("data", (c: Buffer) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) {
        resolve(null);
        req.resume();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => resolve(size > MAX_BODY_BYTES ? null : Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

type Parsed = { ok: true; profile: Profile; prompt: string; mcpUrl: string | null; agentToken: string | null } | { ok: false; status: number; code: string; message: string };

export function parseRunBody(raw: string, profiles: Map<string, Profile>): Parsed {
  let body: unknown;
  try {
    body = JSON.parse(raw);
  } catch {
    return { ok: false, status: 400, code: "invalid_request", message: "The body is not JSON." };
  }
  if (typeof body !== "object" || body === null) return { ok: false, status: 400, code: "invalid_request", message: "The body must be a JSON object." };
  const b = body as Record<string, unknown>;
  const profile = typeof b.profile === "string" ? profiles.get(b.profile) : undefined;
  if (!profile) return { ok: false, status: 400, code: "unknown_profile", message: "No such profile." };
  if (typeof b.prompt !== "string" || !b.prompt.trim() || b.prompt.length > MAX_PROMPT_CHARS) {
    return { ok: false, status: 400, code: "invalid_request", message: `prompt must be a non-empty string of at most ${MAX_PROMPT_CHARS} characters.` };
  }
  // A profile with no MCP server takes neither field. Refused rather than ignored: an api that sends
  // a token to a run with no use for it has a bug worth hearing about.
  if (profile.mcpServer === null) {
    if (b.mcpUrl !== undefined || b.agentToken !== undefined) {
      return { ok: false, status: 400, code: "invalid_request", message: "This profile takes no mcpUrl or agentToken." };
    }
    return { ok: true, profile, prompt: b.prompt, mcpUrl: null, agentToken: null };
  }
  let url: URL | null = null;
  try { url = typeof b.mcpUrl === "string" ? new URL(b.mcpUrl) : null; } catch { url = null; }
  if (!url || (url.protocol !== "http:" && url.protocol !== "https:")) {
    return { ok: false, status: 400, code: "invalid_request", message: "mcpUrl must be an http or https URL." };
  }
  // A JWT is three base64url parts; anything with whitespace or quotes in it is not one, and would
  // only be trouble in a header.
  if (typeof b.agentToken !== "string" || !/^[A-Za-z0-9._-]+$/.test(b.agentToken) || b.agentToken.length > MAX_TOKEN_CHARS) {
    return { ok: false, status: 400, code: "invalid_request", message: "agentToken is missing or malformed." };
  }
  return { ok: true, profile, prompt: b.prompt, mcpUrl: url.toString(), agentToken: b.agentToken };
}

export function createRunnerServer(config: RunnerConfig, profiles: Map<string, Profile>, log: Logger = stderrLogger): Server {
  let running = 0;

  return createServer(async (req, res) => {
    const path = (req.url ?? "/").split("?")[0];
    try {
      if (req.method === "GET" && path === "/health") {
        sendJson(res, 200, { status: "UP", running, maxConcurrent: config.maxConcurrent, credential: config.credential.kind });
        return;
      }
      if (path !== "/run") {
        refuse(res, 404, "not_found", "Not found.");
        return;
      }
      if (req.method !== "POST") {
        refuse(res, 405, "method_not_allowed", "Use POST.", { Allow: "POST" });
        return;
      }
      const raw = await readBody(req);
      if (raw === null) {
        refuse(res, 413, "invalid_request", "The body is too large.");
        return;
      }
      const parsed = parseRunBody(raw, profiles);
      if (!parsed.ok) {
        refuse(res, parsed.status, parsed.code, parsed.message);
        return;
      }
      // Each run is a whole `claude` process (a few hundred MB). Past the limit, say so at once
      // rather than queue: the api has a user waiting and is better placed to decide what to tell them.
      if (running >= config.maxConcurrent) {
        log({ event: "run_refused_busy", running, maxConcurrent: config.maxConcurrent });
        refuse(res, 503, "busy", "The assistant is busy with other requests. Try again in a moment.", { "Retry-After": "2" });
        return;
      }
      running++;
      try {
        await runClaude(config, parsed, res, randomUUID(), log);
      } finally {
        running--;
      }
    } catch (err) {
      log({ event: "request_failed", error: err instanceof Error ? err.name : "unknown" });
      if (!res.headersSent) refuse(res, 500, "internal", "The runner failed.");
      else res.end();
    }
  });
}

function main(): void {
  const config = loadConfig();
  const profiles = loadProfiles(config.promptsDir);
  const server = createRunnerServer(config, profiles);
  server.listen(config.port, () => {
    stderrLogger({
      event: "listening",
      port: config.port,
      credential: config.credential.kind,
      claudeBin: config.claudeBin,
      timeoutMs: config.timeoutMs,
      maxConcurrent: config.maxConcurrent,
      model: config.model,
      profiles: [...profiles.keys()],
    });
  });
  const stop = () => server.close(() => process.exit(0));
  process.on("SIGTERM", stop);
  process.on("SIGINT", stop);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
