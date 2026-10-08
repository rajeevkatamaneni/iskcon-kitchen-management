// Agent profiles. A profile is everything about a run that the caller must NOT choose: the system
// prompt, the MCP server's name and which of its tools may be called. The api picks a profile by
// name and supplies only the user's words and where /mcp is; it cannot widen what the agent may do.
//
// Two profiles. The Kitchen Assistant (plan §0) talks to the IKMS MCP server and streams its answer.
// name-fill (E10-NAMES-A) has no tools of any kind: it is handed a list of store items and a
// language, and returns one JSON object of names, shaped by a schema fixed here. It needs no MCP
// server, so no mcpUrl or agent token either; the api sends neither, and the runner refuses them if
// sent, so a mistake on the api side shows up as a 400 rather than as a token handled for nothing.
// An unknown name is refused rather than defaulted, so a typo in the api cannot quietly run the
// wrong agent.

import { readFileSync } from "node:fs";
import { join } from "node:path";

export interface Profile {
  name: string;
  systemPrompt: string;
  /** The key under mcpServers in mcp.json; tool names become mcp__<server>__<tool>. Null: no MCP at all. */
  mcpServer: string | null;
  /** The --allowedTools pattern. Null exactly when mcpServer is null. */
  allowedTools: string | null;
  /** A JSON schema the answer must match (--json-schema, --output-format json). Null: streamed events. */
  jsonSchema: string | null;
  /** Pins the model for this profile over the runner's RUNNER_MODEL. Null: the runner's model. */
  model: string | null;
  /** Overrides RUNNER_TIMEOUT_MS for this profile. Null: the runner's timeout. */
  timeoutMs: number | null;
}

/**
 * name-fill's answer. "english" is the item copied back, which is how the api checks the numbering
 * held: a name is only stored against an item whose English came back letter for letter, so an
 * answer shifted by one line is caught instead of filing every name under its neighbour. The rest is
 * the schema E10-T8d used for Kannada, with "kannada" generalised to "name".
 */
export const NAME_FILL_SCHEMA = {
  type: "object",
  properties: {
    names: {
      type: "array",
      items: {
        type: "object",
        properties: { n: { type: "integer" }, english: { type: "string" }, name: { type: "string" } },
        required: ["n", "english", "name"],
        additionalProperties: false,
      },
    },
  },
  required: ["names"],
  additionalProperties: false,
};

interface Definition {
  name: string;
  promptFile: string;
  mcpServer: string | null;
  allowedTools: string | null;
  jsonSchema: string | null;
  model: string | null;
  timeoutMs: number | null;
}

const DEFINITIONS: Definition[] = [
  {
    name: "kitchen-assistant", promptFile: "kitchen-assistant.md", mcpServer: "ikms", allowedTools: "mcp__ikms__*",
    jsonSchema: null, model: null, timeoutMs: null,
  },
  // Opus, as E10-T8d measured it: 119 Kannada names, none drifting afterwards. That single call took
  // 64 to 79 seconds, past the runner's 60-second default, and the api sends up to 150 items in one
  // call, hence five minutes. It runs once per temple per language, never per message.
  {
    name: "name-fill", promptFile: "name-fill.md", mcpServer: null, allowedTools: null,
    jsonSchema: JSON.stringify(NAME_FILL_SCHEMA), model: "opus", timeoutMs: 300_000,
  },
];

/** Reads every prompt at start-up, so a missing file stops the runner instead of failing run one. */
export function loadProfiles(promptsDir: string): Map<string, Profile> {
  const profiles = new Map<string, Profile>();
  for (const d of DEFINITIONS) {
    const systemPrompt = readFileSync(join(promptsDir, d.promptFile), "utf8");
    if (!systemPrompt.trim()) throw new Error(`runner: prompt ${d.promptFile} is empty`);
    profiles.set(d.name, {
      name: d.name, systemPrompt, mcpServer: d.mcpServer, allowedTools: d.allowedTools,
      jsonSchema: d.jsonSchema, model: d.model, timeoutMs: d.timeoutMs,
    });
  }
  return profiles;
}
