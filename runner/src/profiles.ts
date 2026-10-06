// Agent profiles. A profile is everything about a run that the caller must NOT choose: the system
// prompt, the MCP server's name and which of its tools may be called. The api picks a profile by
// name and supplies only the user's words and where /mcp is; it cannot widen what the agent may do.
//
// One profile today (plan §0: one agent, the Kitchen Assistant). An unknown name is refused rather
// than defaulted, so a typo in the api cannot quietly run the wrong agent.

import { readFileSync } from "node:fs";
import { join } from "node:path";

export interface Profile {
  name: string;
  systemPrompt: string;
  /** The key under mcpServers in mcp.json; tool names become mcp__<server>__<tool>. */
  mcpServer: string;
  allowedTools: string;
}

const DEFINITIONS = [
  { name: "kitchen-assistant", promptFile: "kitchen-assistant.md", mcpServer: "ikms", allowedTools: "mcp__ikms__*" },
] as const;

/** Reads every prompt at start-up, so a missing file stops the runner instead of failing run one. */
export function loadProfiles(promptsDir: string): Map<string, Profile> {
  const profiles = new Map<string, Profile>();
  for (const d of DEFINITIONS) {
    const systemPrompt = readFileSync(join(promptsDir, d.promptFile), "utf8");
    if (!systemPrompt.trim()) throw new Error(`runner: prompt ${d.promptFile} is empty`);
    profiles.set(d.name, { name: d.name, systemPrompt, mcpServer: d.mcpServer, allowedTools: d.allowedTools });
  }
  return profiles;
}
