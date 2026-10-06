// One JSON object per line on stderr, which Cloud Logging reads as structured entries.
//
// What is never logged: the prompt (the user's words, and whatever history the api put in front of
// them), the agent token, and the credential. Anything that could carry them, such as the child's
// stderr, goes through redact() first. The tests check this by running with known secrets and
// searching every log line for them.

export type Logger = (entry: Record<string, unknown>) => void;

export const stderrLogger: Logger = (entry) => {
  process.stderr.write(JSON.stringify({ time: new Date().toISOString(), ...entry }) + "\n");
};

export function redact(text: string, secrets: Array<string | undefined>): string {
  let out = text;
  for (const s of secrets) {
    if (s && s.length >= 4) out = out.split(s).join("[redacted]");
  }
  return out;
}
