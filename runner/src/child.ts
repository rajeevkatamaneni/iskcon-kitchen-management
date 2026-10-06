// What the `claude` child process is given: its arguments, its environment and its mcp.json.
// Pure functions, so the lockdown can be tested without running anything.

import { dirname } from "node:path";
import type { RunnerConfig } from "./config.js";
import type { Profile } from "./profiles.js";

/**
 * The plan's flags (§2 step 4), proven by E10-T8 and tools/assistant-dev/run-question.sh. Why each:
 *   --restricted, --setting-sources "", --disable-slash-commands: no settings, hooks, CLAUDE.md or
 *       commands from anywhere on disk;
 *   --tools "": none of the built-in tools (no shell, no file access);
 *   --strict-mcp-config + --mcp-config: the IKMS server is the only MCP server, and
 *   --allowedTools + --permission-mode dontAsk: its tools run without a prompt, anything else is
 *       refused rather than asked about (there is nobody to ask);
 *   --no-session-persistence: nothing is written for a later run to resume;
 *   stream-json + --verbose + --include-partial-messages: one JSON event per line, text as it is
 *       generated, which is what lets the chat page show the answer while it is being written.
 *
 * The user's words are NOT an argument: they go in on stdin. An argument would be parsed as a flag
 * if it began with "-", and a long history could pass the OS argument-size limit.
 */
export function buildArgs(profile: Profile, mcpConfigPath: string, model: string): string[] {
  return [
    "-p",
    "--restricted",
    "--setting-sources", "",
    "--disable-slash-commands",
    "--system-prompt", profile.systemPrompt,
    "--tools", "",
    "--strict-mcp-config",
    "--mcp-config", mcpConfigPath,
    "--allowedTools", profile.allowedTools,
    "--permission-mode", "dontAsk",
    "--model", model,
    "--no-session-persistence",
    "--output-format", "stream-json",
    "--verbose",
    "--include-partial-messages",
  ];
}

/**
 * The child's whole environment. Built from nothing, never from process.env: the E10-S1 spike found
 * inherited variables carrying the developer's own context past the lockdown flags. So:
 *   PATH: the system directories and the directory `claude` itself is in, nothing of the runner's;
 *   HOME: the run's own fresh, empty home when a secret is configured, so no run sees another's
 *       files and nothing of the runner's account is visible; the runner's real HOME only in
 *       stored-login mode, because that is where (with USER) the CLI finds the developer's login;
 *   LANG: UTF-8, or Kannada arrives as question marks;
 *   the one credential, under its own name, and no other.
 */
export function buildChildEnv(config: RunnerConfig, runHome: string): Record<string, string> {
  const env: Record<string, string> = {
    PATH: ["/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin", dirname(config.claudeBin)].join(":"),
    LANG: "en_US.UTF-8",
  };
  const c = config.credential;
  if (c.kind === "stored-login") {
    env.HOME = config.home;
    if (config.user) env.USER = config.user;
  } else {
    env.HOME = runHome;
    env[c.name] = c.value;
  }
  return env;
}

/** mcp.json as T8 proved it: one HTTP server, the agent token as a Bearer header. */
export function buildMcpConfig(profile: Profile, mcpUrl: string, agentToken: string): string {
  return JSON.stringify(
    { mcpServers: { [profile.mcpServer]: { type: "http", url: mcpUrl, headers: { Authorization: `Bearer ${agentToken}` } } } },
    null,
    2,
  );
}
