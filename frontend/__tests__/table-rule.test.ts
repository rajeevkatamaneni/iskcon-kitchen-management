import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import {
  RULED_TABLE,
  TD_ACTIONS_FIXED,
  TD_FIXED,
  TD_FIXED_NUM,
  TD_PRIMARY,
  TD_SECOND,
  TH_ACTIONS_FIXED,
  TH_FIXED,
  TH_PRIMARY,
  TH_SECOND,
  TH_LEAD,
  TD_LEAD,
  RULED_TABLE_EVEN,
  shareTableWidth,
} from "@/components/ds/table";

/**
 * The table rule (DESIGN_SYSTEM §5 *Tables*), as Rajeev set it on 2026-09-18 (T-236): every column
 * reads left, none is narrower than its content, and spare width is shared evenly between the gaps.
 * It replaced the rule of earlier that day (T-228) that grouped short columns on the right.
 *
 * <p>jsdom has no layout engine, so nothing here measures a column. What these assert is the shape
 * that decides the layout and is what actually drifts: which classes a ruled table's cells carry,
 * the arithmetic that shares the width out, and that the stylesheet still says what the rule says.
 * The layout itself was measured in a browser and is recorded in `docs/work/proof/T-236.md`.
 */

const ROOT = path.resolve(__dirname, "..");

function tsxUnder(dir: string): string[] {
  const out: string[] = [];
  for (const entry of fs.readdirSync(path.join(ROOT, dir), { withFileTypes: true })) {
    const rel = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...tsxUnder(rel));
    else if (entry.name.endsWith(".tsx")) out.push(rel);
  }
  return out;
}

/** Every `<table …RULED_TABLE…> … </table>` in app/ and components/, with the file it came from. */
function ruledTables(): { file: string; text: string }[] {
  const tables: { file: string; text: string }[] = [];
  for (const file of [...tsxUnder("app"), ...tsxUnder("components")]) {
    const source = fs.readFileSync(path.join(ROOT, file), "utf8");
    const re = /<table className=\{[^}]*RULED_TABLE(?:_EVEN)?[\s\S]*?<\/table>/g;
    for (const m of source.matchAll(re)) tables.push({ file, text: m[0] });
  }
  return tables;
}

const TABLES = ruledTables();

describe("the table rule", () => {
  it("is followed by the tables that were classified", () => {
    // A floor, so that a refactor which quietly drops the constant from a screen is noticed.
    expect(TABLES.length).toBeGreaterThanOrEqual(35);
  });

  it("never mixes the old left-aligned constants into a ruled table", () => {
    // One `TD_TEXT` left in a ruled row is a cell with the old padding and nowrap, sitting under a
    // right-aligned heading — the exact disagreement between heading and column the rule exists
    // to end.
    const old = /\b(TH_TEXT|TH_NUM|TH_DATE|TH_ACTIONS|TD_TEXT|TD_NUM|TD_DATE|TD_ACTIONS|WRAP)\b(?!_)/;
    const offenders = TABLES.filter((t) => old.test(t.text)).map((t) => t.file);
    expect(offenders).toEqual([]);
  });

  it("lets a lead column (a row's link, a tick box) stand only first", () => {
    // The two exceptions Rajeev accepted on 2026-09-18 (T-233): anywhere but first, a fixed column
    // on the left is just the old layout back.
    const offenders: string[] = [];
    for (const t of TABLES) {
      const head = t.text.slice(0, t.text.indexOf("</thead>"));
      const kinds = [...head.matchAll(/<th className=\{(TH_[A-Z_]+)\}/g)].map((m) => m[1]);
      if (kinds.indexOf("TH_LEAD") > 0) offenders.push(`${t.file}: ${kinds.join(", ")}`);
    }
    expect(offenders).toEqual([]);
    expect(TH_LEAD).toBe(TD_LEAD);
    // Kept for its one caller; the even sharing it used to add is now every table's.
    expect(RULED_TABLE_EVEN).toBe(RULED_TABLE);
  });

  it("hides the actions heading from sight and keeps it for a screen reader", () => {
    const offenders: string[] = [];
    for (const t of TABLES) {
      for (const m of t.text.matchAll(/<th className=\{TH_ACTIONS_FIXED\}>([\s\S]*?)<\/th>/g)) {
        if (!/^<span className="sr-only">[^<]+<\/span>$/.test(m[1].trim())) offenders.push(`${t.file}: ${m[1].trim()}`);
      }
    }
    expect(offenders).toEqual([]);
  });

  it("gives the flexible and fixed constants the classes the stylesheet lays out", () => {
    expect(RULED_TABLE).toContain("kms-table");
    expect(TH_PRIMARY).toBe(TD_PRIMARY);
    expect(TH_SECOND).toBe(TD_SECOND);
    expect(TH_PRIMARY).toMatch(/\bkms-flex\b.*\bkms-primary\b/);
    expect(TH_SECOND).toMatch(/\bkms-flex\b.*\bkms-second\b/);
    expect(TH_FIXED).toBe("kms-fixed");
    expect(TD_FIXED).toBe("kms-fixed");
    expect(TD_FIXED_NUM).toContain("kms-num");
    expect(TH_ACTIONS_FIXED).toContain("kms-actions");
    expect(TD_ACTIONS_FIXED).toContain("kms-fixed");
  });

  it("says in the stylesheet what the rule says", () => {
    const css = fs.readFileSync(path.join(ROOT, "app/globals.css"), "utf8");
    // Everything reads left: no column kind is aligned to the end any more.
    expect(css).toMatch(/\.kms-table :is\(th, td\) \{ padding: 12px; text-align: start; \}/);
    expect(css).not.toMatch(/\.kms-table \.kms-[a-z]+\s*\{[^}]*text-align:\s*(end|right)/);
    // No caps and no one-pixel columns: those are what parked the spare width in one block.
    expect(css).not.toMatch(/--kms-cap/);
    expect(css).not.toMatch(/\.kms-table \.kms-[a-z]+[^{]*\{[^}]*width:\s*1px/);
    // The measuring state has to beat every width the layout sets, or it measures the layout.
    expect(css).toMatch(/\.table\.kms-measuring > \* > tr > \* \{ display: block !important; width: max-content !important; \}/);
    expect(css).toMatch(/\.table\.kms-measuring-min > \* > tr > \* \{ display: block !important; width: min-content !important; \}/);
    // A number box grows to the figure in it.
    expect(css).toMatch(/\.table td input\[type="number"\] \{ field-sizing: content; \}/);
    // Text wraps rather than truncates, short values stay on one line.
    expect(css).toMatch(/\.kms-table \.kms-flex\s*\{[^}]*white-space:\s*normal/);
    expect(css).toMatch(/\.kms-table \.kms-fixed\s*\{\s*white-space:\s*nowrap/);
    expect(css).not.toMatch(/\.kms-[a-z-]+[^{]*\{[^}]*text-overflow:\s*ellipsis/);
    // The card layout switches where the sidebar becomes a drawer.
    expect(css).toMatch(/@media \(max-width: 1023\.98px\) \{\s*\.kms-table,/);
  });

  it("gives no number box in a table a fixed width, which would cut a long figure off", () => {
    const offenders: string[] = [];
    for (const t of TABLES) {
      for (const m of t.text.matchAll(/<input[^>]*type="number"[^>]*>/g)) {
        if (/className="[^"]*(?<![-\w])w-\d/.test(m[0])) offenders.push(`${t.file}: ${m[0].slice(0, 80)}`);
      }
    }
    expect(offenders).toEqual([]);
  });
});

describe("sharing a table's width", () => {
  it("gives every column its content and the spare evenly to the gaps, none to the last column", () => {
    // Four columns needing 100, 200, 50, 50 in a 700px table: 300 spare over three gaps.
    expect(shareTableWidth([100, 200, 50, 50], 700)).toEqual([200, 300, 150, 50]);
  });

  it("hands pixels that do not divide evenly to the first gaps, so the total is exact", () => {
    // 103 spare over two gaps: 51 each, and the odd pixel to the first.
    const widths = shareTableWidth([100, 100, 100], 403)!;
    expect(widths).toEqual([152, 151, 100]);
    expect(widths.reduce((a, b) => a + b, 0)).toBe(403);
  });

  it("leaves the layout to the browser when short of room and told nothing about wrapping", () => {
    expect(shareTableWidth([500, 400], 800)).toBeNull();
  });

  it("when short of room, narrows the widest text column first and leaves short values whole", () => {
    // Needs 100 (short value), 300 and 200 (text) in 580: 20px short. Only the 300 column gives way.
    const shrink = { min: [100, 80, 80], flexible: [false, true, true] };
    expect(shareTableWidth([100, 300, 200], 580, shrink)).toEqual([100, 280, 200]);
  });

  it("brings the text columns down to a common width when one alone cannot give enough", () => {
    // Text needs 300 and 260 in a 480px budget after the 100px short value: both held to 240.
    const shrink = { min: [100, 80, 80], flexible: [false, true, true] };
    const widths = shareTableWidth([100, 300, 260], 580, shrink)!;
    expect(widths).toEqual([100, 240, 240]);
  });

  it("never narrows a column below its minimum, and gives up when even the minimums do not fit", () => {
    const shrink = { min: [100, 250, 80], flexible: [false, true, true] };
    // The first text column cannot go below 250, so the second takes the rest of the cut.
    expect(shareTableWidth([100, 300, 260], 580, shrink)).toEqual([100, 250, 230]);
    expect(shareTableWidth([100, 300, 260], 400, shrink)).toBeNull();
  });

  it("gives a one-column table the whole width", () => {
    expect(shareTableWidth([120], 600)).toEqual([600]);
  });

  it("changes nothing when the content fills the table exactly", () => {
    expect(shareTableWidth([300, 300], 600)).toEqual([300, 300]);
  });
});
