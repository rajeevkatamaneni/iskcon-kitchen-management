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
  withAddressBreaks,
  withLongTokenBreaks,
  withWholeWords,
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
    // 12px either side of a cell (§5 rule 4); a variable only so the fitter can close the gaps on a
    // table that would otherwise run past its box (T-304). It defaults to the same 12px.
    expect(css).toMatch(/\.kms-table :is\(th, td\) \{ padding: 12px var\(--kms-cell-pad, 12px\); text-align: start; \}/);
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

/**
 * T-269: when a table is short of room, a short column's heading wraps before any text column gives
 * up width (conductor's ruling, 2026-09-19, from §5 rule 2), on two lines where two are enough, and
 * the name column is never squeezed below its longest word. The figures are the ones measured on a
 * purchase order in headless Chrome (docs/work/proof/T-269.md): Item needs 561 unwrapped and 105 at
 * its longest word ("Cardamom"); Ordered 124 (a pack line), Delivered 86, "Rejected on delivery"
 * 156 on one line, 98 on two and 80 one word to a line; Returned 84; the Return button 182.
 */
describe("sharing a table's width when a heading is what is wide", () => {
  const need = [561, 124, 86, 156, 84, 182];
  const shrink = {
    min: [105, 62, 54, 39, 39, 182],
    flexible: [true, false, false, false, false, false],
    wrapHead: [561, 124, 86, 98, 84, 182],
    wrapHeadFully: [561, 124, 86, 80, 84, 182],
  };

  it("wraps a short column's heading before it narrows the name column", () => {
    // A name needing 300 and a short column whose heading needs 150 but whose figures need 80, in
    // 400px. Before T-269 the name gave up 50px; now the heading wraps and the name keeps it all,
    // with the 20px left over going to the gap between them.
    const widths = shareTableWidth([300, 150], 400, { min: [100, 60], flexible: [true, false], wrapHead: [300, 80] });
    expect(widths).toEqual([320, 80]);
  });

  it("gives the purchase order's Item column room at 1060px with the heading on two lines", () => {
    // The card is 714px wide at 1060. Two-line "Rejected on delivery" (98) leaves Item 140.
    expect(shareTableWidth(need, 714, shrink)).toEqual([140, 124, 86, 98, 84, 182]);
  });

  it("at 1024px wraps the heading further rather than squeeze Item below its longest word", () => {
    // 678px: on two lines the short columns take 574 and leave 104, 1px under Item's 105, so the
    // heading goes one word to a line (80) and Item gets 122. Before T-269 Item got 46.
    const widths = shareTableWidth(need, 678, shrink)!;
    expect(widths).toEqual([122, 124, 86, 80, 84, 182]);
    expect(widths[0]).toBeGreaterThanOrEqual(shrink.min[0]);
    expect(widths.reduce((a, b) => a + b, 0)).toBe(678);
  });

  it("only wraps as many headings as it has to, the biggest saving first", () => {
    // Two wide headings (200 over 60, and 150 over 100), 50px short: only the first wraps, and the
    // 90px it frees beyond the 50 is shared between the gaps.
    const widths = shareTableWidth([300, 200, 150], 600, {
      min: [100, 60, 100],
      flexible: [true, false, false],
      wrapHead: [300, 60, 100],
    });
    expect(widths).toEqual([345, 105, 150]);
  });

  it("still gives up when even the wrapped headings and the name's longest word cannot fit", () => {
    expect(shareTableWidth(need, 600, shrink)).toBeNull();
  });

  it("measures every text column at its longest whole word, not at a single letter", () => {
    // T-269 did this for the name column; T-278 for every text column (`kms-flex`).
    const css = fs.readFileSync(path.join(ROOT, "app/globals.css"), "utf8");
    expect(css).toMatch(/\.table\.kms-measuring-min > \* > tr > \.kms-flex \{ overflow-wrap: normal !important; \}/);
  });
});

/**
 * T-278. The figures are the ones the fitter measured on the local app, signed in as the Temple
 * Admin, at 1024px (docs/work/proof/T-278.md). Before T-278 every one of these returned `null`, and
 * the browser's own layout squeezed the name column to 36–45px and broke its words mid-letter.
 */
describe("sharing a table's width when the short columns alone are too wide (T-278)", () => {
  it("(a) keeps every heading on one line when the table has room", () => {
    // Nothing is short, so every column gets at least what it needs with its heading unwrapped,
    // however much a `shrink` offers: a heading wraps only when the table genuinely lacks room.
    const need = [266, 107, 168, 166, 106];
    const shrink = {
      min: [148, 107, 168, 166, 106],
      flexible: [true, false, false, false, false],
      wrapHead: [266, 107, 168, 104, 106],
      stack: [266, 107, 120, 104, 106],
      wrapHeadFully: [266, 107, 120, 104, 106],
    };
    const widths = shareTableWidth(need, 886, shrink)!;
    widths.forEach((w, i) => expect(w).toBeGreaterThanOrEqual(need[i]));
    expect(widths.reduce((a, b) => a + b, 0)).toBe(886);
  });

  it("(a) sizes a dropdown in a table to the option it shows, which is what wrapped headings at 1280", () => {
    // On a vendor's Other ingredients grid at 1280 the "Sells it as" dropdown was 183px, the width
    // of "Add a pack size…", while showing "gm". That made the grid 19px short, and "LEAD TIME
    // (DAYS)" wrapped with room to spare.
    const css = fs.readFileSync(path.join(ROOT, "app/globals.css"), "utf8");
    expect(css).toMatch(/\.table td select \{ field-sizing: content; width: auto; min-width: max-content; \}/);
  });

  it("(b) never gives a text column less than its longest word while short cells can still break", () => {
    // Shopping list, Kalasipalya group, 628px: Include, Ingredient, Why, On hand, Suggested, Order by.
    // Short columns alone need 482 of it; Ingredient's longest word is 92, Why's badge 124.
    const widths = shareTableWidth([80, 171, 124, 75, 124, 203], 628, {
      min: [80, 92, 124, 75, 124, 203],
      flexible: [false, true, true, false, false, false],
      wrapHead: [80, 171, 124, 58, 124, 203],
      stack: [80, 109, 124, 58, 104, 150],
      wrapHeadFully: [80, 109, 124, 58, 104, 150],
    })!;
    // "On hand" on two lines, "Won't arrive in time" above "assumed", Suggested left whole: the
    // text columns get their longest word and the short columns narrow no further than that needs.
    expect(widths).toEqual([80, 92, 124, 58, 124, 150]);
    expect(widths[1]).toBeGreaterThanOrEqual(92);
    expect(widths[2]).toBeGreaterThanOrEqual(124);
  });

  it("(c) fits a vendor's Supplies at 1024 by putting the two buttons one above the other", () => {
    // VERIFY-B Grains, 630px: Ingredient, Sells it as, List price, Lead time, Preferred, actions.
    const widths = shareTableWidth([190, 101, 174, 84, 91, 168], 630, {
      min: [100, 101, 174, 84, 91, 168],
      flexible: [true, false, false, false, false, false],
      wrapHead: [190, 101, 174, 53, 91, 168],
      stack: [190, 101, 174, 53, 91, 106],
      wrapHeadFully: [190, 101, 174, 53, 91, 106],
    });
    expect(widths).toEqual([105, 101, 174, 53, 91, 106]);
  });

  it("(c) fits the Sri Balaji group of the Shopping list at 1024", () => {
    const widths = shareTableWidth([80, 107, 116, 75, 121, 203], 628, {
      min: [80, 92, 116, 75, 121, 203],
      flexible: [false, true, true, false, false, false],
      wrapHead: [80, 107, 116, 56, 121, 203],
      stack: [80, 107, 116, 56, 104, 150],
      wrapHeadFully: [80, 107, 116, 56, 104, 150],
    });
    expect(widths).toEqual([80, 105, 116, 56, 121, 150]);
  });

  it("(c) fits the Invoices list at 1024 with a short cell wrapped at its spaces, words whole", () => {
    // Invoice, Vendor, Against, Amount, Due, Status in 678px. Broken between pieces only, the short
    // columns still leave Vendor under "Kalasipalya" (98); wrapped at any space, Amount's
    // "less ₹3,000 credited" goes to 91 and Vendor gets 106.
    const widths = shareTableWidth([160, 216, 223, 187, 172, 85], 678, {
      min: [160, 98, 223, 187, 172, 85],
      flexible: [false, true, false, false, false, false],
      wrapHead: [160, 216, 223, 187, 172, 85],
      stack: [160, 216, 126, 133, 110, 85],
      wrapHeadFully: [160, 216, 126, 133, 110, 85],
      words: [140, 98, 93, 91, 92, 85],
    });
    expect(widths).toEqual([160, 106, 126, 91, 110, 85]);
  });

  it("(c) in the last resort takes the width from the widest text column first, down to its floor", () => {
    // The arithmetic, on /inventory's figures at 1024 (seven columns, the short ones 476px at their
    // narrowest, leaving 202 for Item, longest word 127, and Location, 79): Location keeps its 79
    // and Item takes 123. The fitter itself passes floors at which every word is still whole, so on
    // the page this step narrows a column only as far as a badge in it can take two lines.
    const need = [299, 123, 132, 108, 132, 80, 82];
    const shrink = {
      min: [127, 79, 132, 108, 132, 80, 82],
      flexible: [true, true, false, false, false, false, false],
      wrapHead: need,
      stack: need,
      wrapHeadFully: need,
      words: [127, 79, 112, 90, 112, 80, 82],
    };
    expect(shareTableWidth(need, 678, shrink)).toBeNull();
    const widths = shareTableWidth(need, 678, { ...shrink, lastResort: { floor: [40, 40, 0, 0, 0, 0, 0], force: false } });
    expect(widths).toEqual([123, 79, 112, 90, 112, 80, 82]);
  });

  it("(c) in the last resort never takes a text column below what can never break", () => {
    // A dropdown needing 164 cannot give way; without `force` it gives up, with it the table keeps
    // the floors and runs past its box rather than cut the dropdown.
    const shrink = {
      min: [92, 164],
      flexible: [true, true],
      lastResort: { floor: [40, 164], force: false },
    };
    expect(shareTableWidth([172, 348], 190, shrink)).toBeNull();
    expect(shareTableWidth([172, 348], 190, { ...shrink, lastResort: { floor: [40, 164], force: true } })).toEqual([40, 164]);
  });

  it("lets an address break after its @ and full stops, and leaves every other word alone", () => {
    const zw = "\u200B";
    expect(withAddressBreaks("ikms.volunteer.5@trading4good.org")).toBe(`ikms.${zw}volunteer.${zw}5@${zw}trading4good.${zw}org`);
    expect(withAddressBreaks("https://example.org/a")).toContain(`example.${zw}org/${zw}a`);
    for (const plain of ["Coconut, fresh grated", "VERIFY-B Sugar 100.2kg", "Dr.Rao", "1,07,817.147 Kg"]) {
      expect(withAddressBreaks(plain)).toBe(plain);
    }
  });

  it("says in the stylesheet how a short cell breaks between its pieces", () => {
    const css = fs.readFileSync(path.join(ROOT, "app/globals.css"), "utf8");
    expect(css).toMatch(/\.table td\[data-kms-stack\] \{ white-space: normal !important; \}/);
    expect(css).toMatch(/\.table td\[data-kms-stack\] > \* \{ white-space: nowrap; \}/);
    expect(css).toMatch(/\.table td\[data-kms-stack="words"\] > :not\(\.whitespace-nowrap\) \{ white-space: normal; \}/);
    // An entry grid has the same edges as every other table.
    expect(css).toMatch(/\.kms-entry-grid :is\(th, td\) \{ padding-inline: var\(--kms-cell-pad, 12px\); \}/);
  });
});

describe("a hyphenated word in a table stays whole (T-299)", () => {
  const wj = "\u2060";

  it("joins a PO number or any code with a figure in it at its hyphens, so it cannot break there", () => {
    // VERIFY2-D: "PO-2026-0026" read "PO-" / "2026-" / "0026" in an 85px Against column at 1024.
    expect(withWholeWords("PO-2026-0026")).toBe(`PO-${wj}2026-${wj}0026`);
    expect(withWholeWords("VERIFY2-C Rice")).toBe(`VERIFY2-${wj}C Rice`);
    expect(withWholeWords("Against PO-2026-0054, INV-E")).toBe(`Against PO-${wj}2026-${wj}0054, INV-E`);
  });

  it("changes nothing else: an address, a minus sign, a dash between words, a compound of words, text with no hyphen", () => {
    // A compound of words still wraps at its hyphen like any text: joined, "VERIFY-A" held the
    // Location column on /inventory at 1024 and the table ran 4px past its box.
    for (const plain of ["ikms.kitchen-staff.1@trading4good.org", "https://kms-app.example.org/a-b", "-5 Kg", "rice - dal", "Top-up", "VERIFY-A Store", "Coconut, fresh grated"]) {
      expect(withWholeWords(plain)).toBe(plain);
    }
  });

  it("is safe to run on every fit: text already joined is left as it is", () => {
    const once = withWholeWords("PO-2026-0054");
    expect(withWholeWords(once)).toBe(once);
  });

  it("joins a table's hyphens before it measures anything, at every width, and copies them out again", () => {
    const source = fs.readFileSync(path.join(ROOT, "components/ds/table.ts"), "utf8");
    const fit = source.slice(source.indexOf("function fitTable("));
    // Before the 1024 check, so a phone card gets it too, and before the first measurement.
    expect(fit.indexOf("joinHyphens(table);")).toBeGreaterThan(0);
    expect(fit.indexOf("joinHyphens(table);")).toBeLessThan(fit.indexOf('matchMedia("(min-width: 1024px)")'));
    expect(source).toMatch(/split\(BREAK\)\.join\(""\)\.split\(JOIN\)\.join\(""\)/);
  });
});

describe("a table never runs past its box (T-304)", () => {
  const zw = "\u200B";
  const wj = "\u2060";

  it("breaks a long pasted token after its separators first", () => {
    // An email address, a reference, a name typed without spaces: 21 characters or more.
    expect(withLongTokenBreaks("accounts.receivable@sri-lakshmi-traders.co.in", "separators")).toBe(
      `accounts.${zw}receivable@${zw}sri-${zw}lakshmi-${zw}traders.${zw}co.${zw}in`,
    );
    expect(withLongTokenBreaks("Paid by NEFT ref/2026/09/SBIN0226019876543210", "separators")).toBe(
      `Paid by NEFT ref/${zw}2026/${zw}09/${zw}SBIN0226019876543210`,
    );
  });

  it("breaks one with no separators anywhere, as the last resort", () => {
    const token = "SBIN000000000000000000000000";
    expect(withLongTokenBreaks(token, "anywhere")).toBe(Array.from(token).join(zw));
    expect(withLongTokenBreaks(token, "anywhere").split(zw).join("")).toBe(token);
  });

  it("never touches a word shorter than a pasted token: a PO number, a UTR-sized code, a name", () => {
    for (const plain of ["PO-2026-0054", `PO-${wj}2026-${wj}0054`, "VERIFY2-B Methi", "ikms.ta@kms.org", "Coconut, fresh grated", "UTR SBIN02260198765432"]) {
      expect(withLongTokenBreaks(plain, "separators")).toBe(plain);
      expect(withLongTokenBreaks(plain, "anywhere")).toBe(plain);
    }
  });

  it("drops the word joiner inside a long token so its hyphens can break", () => {
    const joined = withWholeWords("ORDER-2026-0000000054-REPLACEMENT");
    expect(joined).toContain(wj);
    expect(withLongTokenBreaks(joined, "separators")).toBe(`ORDER-${zw}2026-${zw}0000000054-${zw}REPLACEMENT`);
  });

  it("closes the gaps first, then breaks long tokens at separators, then anywhere, and never forces the table past its box", () => {
    const source = fs.readFileSync(path.join(ROOT, "components/ds/table.ts"), "utf8");
    const fit = source.slice(source.indexOf("function fitTable("), source.indexOf("export function withLongTokenBreaks"));
    const order = ["fitOnce(table, {})", "fitOnce(table, { pad })", 'fitOnce(table, { breaks: "separators" })', 'fitOnce(table, { breaks: "separators", pad })', 'fitOnce(table, { breaks: "anywhere" })'];
    const at = order.map((step) => fit.indexOf(step));
    expect(at.every((i) => i >= 0)).toBe(true);
    expect([...at].sort((a, b) => a - b)).toEqual(at);
    // The gaps close to no less than 6px a side (a 12px gap), from the 12px of §5 rule 4.
    expect(source).toMatch(/const CELL_PAD = 12;/);
    expect(source).toMatch(/const CELL_PAD_MIN = 6;/);
    // Every fit starts from the stylesheet's own gaps.
    expect(source).toMatch(/table\.style\.removeProperty\("--kms-cell-pad"\)/);
  });
});
