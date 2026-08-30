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
  // `--others --exclude-standard` as well as the index, because a screen that has just been written
  // and not yet committed is exactly the screen these rules most need to reach. Without it every
  // check here silently skipped every new file, which is how it passed while a whole set of new
  // routes went unread.
  const list = execFileSync(
    "git",
    ["ls-files", "--cached", "--others", "--exclude-standard", "app", "components"],
    { cwd: ROOT, encoding: "utf8" },
  )
    .trim()
    .split("\n")
    .filter((f) => f.endsWith(".tsx"))
    .filter((f) => fs.existsSync(path.join(ROOT, f)));
  return [...new Set(list)].map((file) => ({ file, text: fs.readFileSync(path.join(ROOT, file), "utf8") }));
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

  it("uses tokens rather than hand-typed values", () => {
    // A measurement that means something — the inset where an input's text begins, the tracking an
    // eyebrow is set at — gets typed three different ways on four pages the moment it has no name.
    // That is exactly what happened to both: the eyebrow was 0.06em on the calendar and the
    // planner and 0.08em on the sidebar and Settings, and nobody chose it.
    //
    // Scoped to tracking on purpose. Around thirty one-off `w-[6rem]` and `h-[3rem]` values predate
    // this, and one `leading-[18px]` that exists to centre an 18px calendar cell — all local layout
    // decisions rather than tokens waiting for a name. A check that failed on all of them would
    // either force an unplanned refactor or be switched off, and a switched-off check is worth less
    // than no check. The 13px inset is covered above, by name.
    const offenders: string[] = [];
    for (const { file, text } of FILES) {
      text.split("\n").forEach((line, i) => {
        const m = line.match(/\btracking-\[[\d.]/);
        if (m) offenders.push(`${file}:${i + 1} ${m[0]}`);
      });
    }
    expect(offenders).toEqual([]);
  });
});

describe("item 13 — a stored value is not a word", () => {
  it("never prints an upper-case stored value straight into the page", () => {
    // Found by driving the live site, not by reading the source: the donations ledger rendered
    // `{r.paymentMode}` and a row read CASH, with a bank transfer reading BANK_TRANSFER, underscore
    // and all. A copy pass cannot find that — the string is in the database, not in the file — so
    // what is checked instead is the shape that produces it.
    //
    // Only fields whose values are genuinely stored in upper case are listed. `mealKind` is not one
    // of them: a kind is a row a temple named, and it already reads "Lunch". And only a text
    // position counts — passing a value to a component whose job is to label it is the fix, not the
    // defect, so `<StatusBadge status={x.status} />` is not an offender.
    const RAW = ["paymentMode", "settlementMode", "dayType", "leaveType", "employmentType", "systemAccess"];
    const offenders: string[] = [];
    for (const { file, text } of FILES) {
      text.split("\n").forEach((line, i) => {
        for (const field of RAW) {
          const inTextPosition = new RegExp(`[>}]\\s*\\{\\s*\\w+\\.${field}\\b[^}]*\\}`);
          if (!inTextPosition.test(line)) continue;
          if (/_LABEL|Label\b|label\(/.test(line)) continue;
          offenders.push(`${file}:${i + 1} ${line.trim().slice(0, 90)}`);
        }
      });
    }
    expect(offenders).toEqual([]);
  });
});

describe("item 12 — a table row answers the pointer", () => {
  it("puts one step of tone on every row of every table body", () => {
    // `hover:bg-sunken`, not the `hover:bg-raised/60` this started as. That rule assumed `raised`
    // was a step *darker* than the surface behind it, which was true of the palette the design
    // system was written in and is not true of a theme pack a temple chooses: Terracotta's raised
    // is #FEFEFF against a #FFF7F4 page, so the hover painted a lighter near-white over a warmer
    // one — a few units per channel — and inside a card it was raised over raised, which is no
    // change whatever. Rajeev, on 2026-08-30: "None of the tables have a hover highliting for the
    // row." `sunken` is the recessed tone every pack has to supply and is distinct from both of
    // the other two by construction.
    const offenders: string[] = [];
    for (const { file, text } of FILES) {
      const lines = text.split("\n");
      let inBody = false;
      lines.forEach((line, i) => {
        if (line.includes("<tbody")) inBody = true;
        if (line.includes("</tbody")) inBody = false;
        if (!inBody || !/<tr[\s>]/.test(line)) return;
        if (!line.includes("hover:bg-sunken")) offenders.push(`${file}:${i + 1}`);
      });
    }
    expect(offenders).toEqual([]);
  });
});

describe("items 5, 6 and 7 — one screen, one task", () => {
  it("has no “← Back to” link left anywhere", () => {
    const offenders = FILES.filter(({ text }) => /←\s*Back|&larr;\s*Back/.test(text)).map((f) => f.file);
    expect(offenders).toEqual([]);
  });

  it("puts the commit buttons in the header and nowhere else", () => {
    // Rule 6. A focus screen that also renders a Cancel or a primary at the foot has two answers to
    // "where do I press". The header pair reaches the form with form={id}, so a second copy is never
    // needed — and a foot copy is how this pattern quietly comes undone one screen at a time.
    const offenders: string[] = [];
    for (const { file, text } of FILES) {
      const screen = text.indexOf("<FocusScreen");
      if (screen < 0) continue;
      const body = text.slice(screen);
      const submits = [...body.matchAll(/type="submit"/g)].length;
      // The ones inside the actions prop are the header pair, and are the only ones allowed.
      const inHeader = [...body.matchAll(/actions=\{[\s\S]{0,800}?type="submit"/g)].length;
      if (submits > inHeader) {
        offenders.push(`${file} — ${submits} submit controls, ${inHeader} of them in the header`);
      }
    }
    expect(offenders).toEqual([]);
  });

  it("says Cancel on a screen that commits", () => {
    // Rule from Q1: Cancel says what happens to what you typed. Close survives only on a read-only
    // record, where there is nothing to cancel.
    const offenders: string[] = [];
    for (const { file, text } of FILES) {
      if (!text.includes("<FocusScreen")) continue;
      const commits = /type="submit"|onSubmit=/.test(text);
      if (commits && />\s*Close\s*</.test(text)) offenders.push(file);
    }
    expect(offenders).toEqual([]);
  });
});

/**
 * `DESIGN_SYSTEM.md` §9 "Words", the rules item 13 settled and swept the site for.
 *
 * <p>These read the source rather than a rendered screen, for the same reason the rules above do:
 * what drifts is what somebody types into the next page, and a check that only sees the pages that
 * happen to have a test would never see it. A copy rule is exactly the kind that comes undone one
 * screen at a time, because every single instance looks harmless on its own.
 */

/**
 * The strings a person actually reads: the text between two tags, and the props that carry copy.
 *
 * <p>Comments come out first — a doc comment is written for whoever maintains the file and is not
 * held to the twelve words or the semicolon rule. TypeScript generics come out next, because
 * `useState<Foo | null>(null)` ends in a `>` and begins with a `<`, and without that pass every
 * `useState` in the app reads as a line of prose containing a semicolon. What survives both is then
 * filtered on code punctuation, which catches the rest: an arrow, a `return`, a bracket.
 */
const COPY_PROPS =
  "title|label|hint|placeholder|aria-label|detail|meta|who|task|empty|caption|subtitle|heading|okLabel|waitLabel|submitLabel|pickerLabel|note";
const CODE = /[=[\]$`]|\breturn\b|\bconst\b|\bfunction\b|\bawait\b|=>|\?\?|\)\s*[;,)]/;
const ENTITY = /&[a-zA-Z]+;|&#\d+;/g;

function prose({ file, text }: { file: string; text: string }): { at: string; said: string }[] {
  let src = text.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^[ \t]*\/\/.*$/gm, "");
  // Three passes, because generics nest: Record<string, Map<string, X>>.
  for (let i = 0; i < 3; i++) src = src.replace(/(?<=[A-Za-z0-9_)\]])<[^<>]*>/g, "");

  const found: { at: string; said: string }[] = [];
  const lineAt = (index: number) => src.slice(0, index).split("\n").length;
  const keep = (index: number, said: string) => {
    if (!/[A-Za-z]{2}/.test(said) || CODE.test(said)) return;
    found.push({ at: `${file}:${lineAt(index)}`, said: said.replace(/\s+/g, " ").trim() });
  };

  for (const m of src.matchAll(/(?<!=)>([^<>{}]+)</g)) keep(m.index!, m[1]);
  for (const m of src.matchAll(new RegExp(`\\b(?:${COPY_PROPS})=\\{?["\`]([^"\`]+)["\`]`, "g"))) {
    keep(m.index!, m[1]);
  }
  return found;
}

const COPY = FILES.flatMap(prose);

describe("item 13 — the words", () => {
  it("splices no two sentences together with a semicolon", () => {
    // "Concrete needs devotees can fund; fulfilled items retire automatically." A semicolon in a
    // hint is two sentences wearing one, and the second one is always the one nobody reads.
    const offenders = COPY.filter((c) => c.said.replace(ENTITY, "").includes(";"))
      .map((c) => `${c.at} ${c.said}`);
    expect(offenders).toEqual([]);
  });

  it("has no emoji anywhere", () => {
    // There was exactly one — "All vendor invoices are paid. 🙏" — which made every other empty
    // state look like it had forgotten something.
    //
    // `Emoji_Presentation` rather than a block range, because the app draws its own marks with
    // typographic characters — ✓ beside a consent already given, ✕ to close a panel, · between two
    // facts — and those render as text, not as a colour picture. A variation selector is caught
    // too: it is how a text mark is turned into an emoji one.
    const offenders: string[] = [];
    for (const { file, text } of FILES) {
      text.split("\n").forEach((line, i) => {
        if (/\p{Emoji_Presentation}|\uFE0F/u.test(line)) offenders.push(`${file}:${i + 1}`);
      });
    }
    expect(offenders).toEqual([]);
  });

  it("writes an apostrophe one way", () => {
    // Three encodings were in use at once — `&rsquo;`, `&apos;` and a literal `’` — so `they&rsquo;re`
    // and `haven&apos;t` could sit in the same sentence. The literal character is the one.
    const offenders: string[] = [];
    for (const { file, text } of FILES) {
      text.split("\n").forEach((line, i) => {
        if (/&rsquo;|&apos;|&#39;|&lsquo;/.test(line)) offenders.push(`${file}:${i + 1} entity`);
      });
    }
    for (const c of COPY) {
      if (/[A-Za-z]'[A-Za-z]|[A-Za-z]'(\s|$)/.test(c.said)) offenders.push(`${c.at} ${c.said}`);
    }
    expect(offenders).toEqual([]);
  });

  it("puts nothing in ALL CAPS that a person has to read", () => {
    // The sidebar eyebrows are `uppercase` in CSS and stay. What this catches is capitals typed
    // into the markup — "OK" in a status column beside "Low" and "Expiring soon".
    //
    // The list is initials and currencies, and it is meant to be short: adding to it is how a new
    // acronym gets agreed rather than assumed.
    const ACRONYMS = new Set([
      "API", "CSV", "GBP", "GST", "GSTIN", "ID", "INR", "ISKCON", "IST", "KMS",
      "OTP", "PAN", "PDF", "PO", "SMS", "UPI", "URL", "US", "USD",
    ]);
    const offenders: string[] = [];
    for (const c of COPY) {
      for (const word of c.said.replace(ENTITY, "").match(/\b[A-Z]{2,}\b/g) ?? []) {
        if (!ACRONYMS.has(word)) offenders.push(`${c.at} ${word} — ${c.said}`);
      }
    }
    expect(offenders).toEqual([]);
  });
});
