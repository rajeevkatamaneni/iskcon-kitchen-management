import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

/**
 * The rules from `docs/DESIGN_SYSTEM.md` that a component cannot enforce on its own.
 *
 * <p>Everything here is a rule that was settled once and has to hold on all fifty pages, not only
 * on the screens whose build introduced it. Two of these have already regressed twice — the field
 * row's alignment was "fixed" in `cd5f46f` and again in `e7e4b67`, each time with a comment saying
 * why it was right — which is the whole argument for checking them here rather than remembering
 * them. jsdom has no layout engine, so a pixel assertion would prove nothing; what these assert is
 * the shape of the source, which is what actually drifts.
 */

const ROOT = path.resolve(__dirname, "..");

function sources(): { file: string; text: string }[] {
  const list = execFileSync("git", ["ls-files", "app", "components"], { cwd: ROOT, encoding: "utf8" })
    .trim()
    .split("\n")
    .filter((f) => f.endsWith(".tsx"));
  return list.map((file) => ({ file, text: fs.readFileSync(path.join(ROOT, file), "utf8") }));
}

const FILES = sources();

describe("item 23 — a row of fields keeps its three shared tracks", () => {
  it("has no hand-typed spacer standing in for a missing hint", () => {
    // `<span className="text-xs">&nbsp;</span>` under a field reserved the hint's line by hand. It
    // worked until somebody added a field and did not know to type one. FieldRow reserves the track.
    const offenders = FILES.filter(({ text }) => /&nbsp;<\/span>/.test(text)).map((f) => f.file);
    expect(offenders).toEqual([]);
  });

  it("does not try to line a field row up with align-items", () => {
    // Both settings have shipped here as the fix and both are wrong: `items-end` puts one field's
    // box level with its neighbour's hint text, `items-start` floats a field with no label a whole
    // line above the rest. Only shared row tracks can line the boxes up.
    const offenders: string[] = [];
    for (const { file, text } of FILES) {
      text.split("\n").forEach((line, i) => {
        if (!/items-(start|end)/.test(line)) return;
        if (!/flex-wrap|grid/.test(line)) return;
        // A row of fields is a row that holds a label, a counter or a readout.
        if (/Counter|Readout|RowField|<Field\b/.test(text.split("\n").slice(i, i + 6).join("\n"))) {
          offenders.push(`${file}:${i + 1}`);
        }
      });
    }
    expect(offenders).toEqual([]);
  });
});

describe("items 10 and 11 — a label lines up with the words in its box", () => {
  it("indents every field label to the inset, and sets it in the one label style", () => {
    // The wrapping-label shape the app uses everywhere: <label>Text<input/></label>. The text has
    // to be in a span of its own, because a bare text node cannot carry padding.
    const offenders: string[] = [];
    for (const { file, text } of FILES) {
      const lines = text.split("\n");
      lines.forEach((line, i) => {
        const m = line.match(/<label[^>]*>\s*$/);
        if (!m || /<\/label>/.test(line)) return;
        const next = (lines[i + 1] ?? "").trim();
        // A bare word after the opening tag is a label that never got its span.
        if (/^[A-Za-z(₹]/.test(next) && !next.startsWith("<")) offenders.push(`${file}:${i + 2} ${next}`);
      });
      // …and the same shape written on one line.
      lines.forEach((line, i) => {
        if (/<label className="[^"]*">[A-Za-z(₹]/.test(line)) offenders.push(`${file}:${i + 1}`);
      });
    }
    expect(offenders).toEqual([]);
  });

  it("uses the token rather than a hand-typed 13px", () => {
    const offenders = FILES.filter(({ text }) => /\[13px\]/.test(text)).map((f) => f.file);
    expect(offenders).toEqual([]);
  });
});

describe("item 12 — a table row answers the pointer", () => {
  it("puts one step of tone on every row of every table body", () => {
    const offenders: string[] = [];
    for (const { file, text } of FILES) {
      const lines = text.split("\n");
      let inBody = false;
      lines.forEach((line, i) => {
        if (line.includes("<tbody")) inBody = true;
        if (line.includes("</tbody")) inBody = false;
        if (!inBody || !/<tr[\s>]/.test(line)) return;
        if (!line.includes("hover:bg-raised/60")) offenders.push(`${file}:${i + 1}`);
      });
    }
    expect(offenders).toEqual([]);
  });
});

describe("item 5 — nothing is reached by a back-link", () => {
  it("has no “← Back to” link left anywhere", () => {
    const offenders = FILES.filter(({ text }) => /←\s*Back|&larr;\s*Back/.test(text)).map((f) => f.file);
    expect(offenders).toEqual([]);
  });
});
