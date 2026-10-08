"use client";

import Markdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkBreaks from "remark-breaks";
import type { Element, ElementContent, Root, RootContent } from "hast";
import { RULED_TABLE, THEAD, TR, TH_PRIMARY, TD_PRIMARY, TH_SECOND, TD_SECOND, TH_FIXED, TD_FIXED_NUM } from "@/components/ds/table";

/*
 * The assistant's reply, read as Markdown (E10-MD). The model writes Markdown — a table of stock, a
 * list of what is low, a word in bold — and before this the panel printed it as it came, so a table
 * arrived as rows of pipes and dashes and bold as asterisks (Rajeev, testing locally, with a Telugu
 * reply).
 *
 * <p><strong>Why react-markdown with remark-gfm,</strong> not a parser of our own: it is maintained,
 * it builds React elements rather than an HTML string (so there is no `dangerouslySetInnerHTML`
 * anywhere), and it does not render raw HTML unless a plugin is added to make it, which this file
 * never does. GFM is what brings tables. `remark-breaks` keeps a single line break a line break, as
 * the panel's old `whitespace-pre-wrap` did: a model that writes "Ajwain: 0 gm" and "Asafoetida: 0 gm"
 * on two lines without list markers still gets two lines, not one run-on sentence.
 *
 * <p><strong>Why it is strict about what it draws.</strong> The text comes from a model that reads
 * what people typed into the app (an ingredient's notes, a vendor's name), so it is treated as
 * untrusted. HTML in it is shown as the literal text it is, never parsed. An image is never fetched:
 * a Markdown image whose address carries data is a known way of smuggling it out of a chat, so only
 * its description is shown. A link shows its words and goes nowhere, for the same reason and because
 * the assistant has no business sending a cook to another site.
 */

/** A figure, possibly with its unit or a currency sign: "0 gm", "₹1,200", "42.5 Kg", "12%", "-3". */
const FIGURE = /^[-−+]?(₹\s?)?\d[\d,]*(\.\d+)?\s?(%|[^\s\d]{1,8}\.?)?$/u;
/** What a model writes for "nothing here" in a figure column. */
const BLANK = /^(|-|—|–|n\/a)$/i;

function textOf(node: ElementContent | RootContent): string {
  if (node.type === "text") return node.value;
  if (node.type === "element") return node.children.map(textOf).join("");
  return "";
}

function children(el: Element, tag: string): Element[] {
  return el.children.filter((c): c is Element => c.type === "element" && c.tagName === tag);
}

/**
 * Gives every table the app's table classes, from what is in each column (a rehype plugin: it edits
 * the tree react-markdown is about to draw).
 *
 * <p>The first column is the row's name (TH_PRIMARY: wraps when short of room, and line one of the
 * phone card). A column whose every filled cell is a figure is a short value on a fixed pitch
 * (TD_FIXED_NUM: one line, line two of the card). Anything else is secondary text, which may wrap.
 *
 * <p>Every cell after the first carries its heading as `data-label`. Below 1024px a ruled table is a
 * list of cards with the headings hidden, and the card prints the label before the value; a model's
 * columns can be anything, so a value is never left on a card without what it is ("On hand 0 gm",
 * not "0 gm"). Above 1024px the label is not shown.
 *
 * <p>The alignment a model may ask for with `|---:|` is dropped. The design system's table rule is
 * that every column reads left, figures included, and keeps tabular figures (DESIGN_SYSTEM §Tables,
 * rule 1, Rajeev 2026-09-18).
 */
function rehypeAppTables() {
  return (tree: Root) => {
    const walk = (node: Root | Element) => {
      for (const child of node.children) {
        if (child.type !== "element") continue;
        if (child.tagName === "table") styleTable(child);
        else walk(child);
      }
    };
    walk(tree);
  };
}

/** A class string as the list hast keeps it in. */
const classes = (s: string) => s.split(/\s+/).filter(Boolean);

function styleTable(table: Element) {
  const head = children(table, "thead")[0];
  const body = children(table, "tbody")[0];
  const headCells = head ? children(children(head, "tr")[0] ?? head, "th") : [];
  const rows = body ? children(body, "tr") : [];
  const labels = headCells.map((th) => textOf(th).trim());
  const figures = labels.map((_, i) => {
    const filled = rows
      .map((r) => children(r, "td")[i])
      .filter((td): td is Element => !!td)
      .map((td) => textOf(td).trim())
      .filter((t) => !BLANK.test(t));
    return i > 0 && filled.length > 0 && filled.every((t) => FIGURE.test(t));
  });

  table.properties = { className: classes(RULED_TABLE) };
  if (head) head.properties = { className: classes(`${THEAD} lg:!static`) };
  headCells.forEach((th, i) => {
    th.properties = { className: classes(i === 0 ? TH_PRIMARY : figures[i] ? TH_FIXED : TH_SECOND) };
  });
  for (const row of rows) {
    row.properties = { className: classes(TR) };
    children(row, "td").forEach((td, i) => {
      const kind = classes(i === 0 ? TD_PRIMARY : figures[i] ? TD_FIXED_NUM : TD_SECOND);
      td.properties = i > 0 && labels[i] ? { className: kind, dataLabel: labels[i] } : { className: kind };
    });
  }
}

/** A delimiter row, the line that makes the lines above and below it a table: `|---|:--:|`. */
const DELIMITER = /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/;
const ROW = /^\s*\|/;

/**
 * The part of a reply still arriving that is safe to draw. Exported for its test.
 *
 * <p>The reply comes a few words at a time, and some Markdown means nothing until its line is
 * finished. A table's heading row on its own is a paragraph of pipes until the dashes under it
 * arrive, and the dashes half-arrived are not yet a table either; drawn as they come, the panel
 * would flash "| Item | On hand |" and "|---|-" as text and then snap into a table. So while a
 * reply streams, three things are held back:
 *
 * <ul>
 *   <li>an unfinished last line that is a table row, until its line ends, so a row appears whole;</li>
 *   <li>a table at the end of the text that does not have its dashes yet;</li>
 *   <li>on the line still arriving, a bold or a code span whose closing marks have not come yet:
 *       from its opening `**` or backtick on. Measured in the harness before this, "**reorder"
 *       showed with its asterisks for five samples (100ms) before turning bold.</li>
 * </ul>
 *
 * <p>Everything else is drawn as it comes: an unfinished sentence is still a sentence.
 */
export function settledMarkdown(text: string, streaming: boolean): string {
  if (!streaming) return text;
  const lines = text.split("\n");
  const tail = lines.pop() ?? "";
  if (!ROW.test(tail)) lines.push(unopened(tail));
  else lines.push("");
  // Lines are complete from here, bar a non-table tail. Find a table run at the end (ignoring the
  // empty tail line) and drop it if it has no delimiter row under its heading yet.
  let end = lines.length;
  if (lines[end - 1] === "") end -= 1;
  let start = end;
  while (start > 0 && ROW.test(lines[start - 1])) start -= 1;
  if (start < end && !(end - start >= 2 && DELIMITER.test(lines[start + 1]))) {
    return lines.slice(0, start).join("\n");
  }
  return lines.join("\n");
}

/** A line still arriving, cut where a bold or a code span opens and has not yet closed. */
function unopened(line: string): string {
  let out = line;
  if ((out.split("**").length - 1) % 2 === 1) out = out.slice(0, out.lastIndexOf("**"));
  if (!out.includes("```") && (out.split("`").length - 1) % 2 === 1) out = out.slice(0, out.lastIndexOf("`"));
  return out;
}

const COMPONENTS: Components = {
  // A table scrolls sideways inside its own frame when it is wider than the panel, never the page
  // (CLAUDE.md layout rules). The frame is the hairline edge every app table has from the card it
  // sits in; the rows' own 20px inset (24px as phone cards) sits inside it.
  //
  // `lg:!overflow-x-auto` because from 1024 up globals.css turns every table's box into `overflow:
  // clip`, so a sticky header sticks to the window, trusting the fitter to make every table fit. In a
  // 448px panel it cannot always: a five-column table measured 476px in a 412px frame at 1280 and
  // its last column was cut off. Scrolling is the lesser evil, and the header here is not sticky
  // (`lg:!static` below), so nothing is lost by making the frame a scrolling box. Written as an
  // arbitrary property, not `lg:!overflow-x-auto`: Tailwind gives that class an important copy of
  // every rule that names `.overflow-x-auto`, globals.css's clip rule included, so it clipped too.
  table: ({ node: _node, ...props }) => (
    <div className="my-1 overflow-x-auto rounded-card border border-hairline lg:![overflow-x:auto]">
      <table {...props} />
    </div>
  ),
  p: ({ node: _node, ...props }) => <p {...props} />,
  ul: ({ node: _node, ...props }) => <ul className="grid list-disc gap-1 ps-5" {...props} />,
  ol: ({ node: _node, ...props }) => <ol className="grid list-decimal gap-1 ps-5" {...props} />,
  li: ({ node: _node, ...props }) => <li className="ps-1" {...props} />,
  strong: ({ node: _node, ...props }) => <strong className="font-semibold" {...props} />,
  // A heading in a chat reply is a label for what follows, not a page title: body size, heavier.
  h1: ({ children }) => <p className="font-semibold">{children}</p>,
  h2: ({ children }) => <p className="font-semibold">{children}</p>,
  h3: ({ children }) => <p className="font-semibold">{children}</p>,
  h4: ({ children }) => <p className="font-semibold">{children}</p>,
  h5: ({ children }) => <p className="font-semibold">{children}</p>,
  h6: ({ children }) => <p className="font-semibold">{children}</p>,
  blockquote: ({ node: _node, ...props }) => (
    <blockquote className="grid gap-3 border-s-2 border-hairline-strong ps-3 text-ink-secondary" {...props} />
  ),
  hr: () => <hr className="border-hairline" />,
  // Inline code: the same quiet mono the app uses for an error code. A fenced block keeps its lines
  // and scrolls inside itself.
  code: ({ node: _node, className: _lang, ...props }) => <code className="font-mono text-[0.9em]" {...props} />,
  pre: ({ node: _node, ...props }) => (
    <pre className="overflow-x-auto whitespace-pre rounded-control bg-sunken p-3 text-sm" {...props} />
  ),
  // See the top of the file: no link is followed and no image is fetched.
  a: ({ children }) => <>{children}</>,
  img: ({ alt }) => (alt ? <>{alt}</> : null),
};

/** A reply (or the part of it that has arrived), drawn as Markdown in the app's own styles. */
export function AssistantMarkdown({ text, streaming }: { text: string; streaming: boolean }) {
  return (
    // One column of `minmax(0, 1fr)`, not a bare `grid`: an auto column grows to the widest
    // thing in it, and a five-column table pushed the whole reply 64px past the 448px panel at 1280
    // (measured: 478 in 414), where the panel then cut it off.
    <div className="grid grid-cols-1 gap-3 break-words">
      <Markdown remarkPlugins={[remarkGfm, remarkBreaks]} rehypePlugins={[rehypeAppTables]} components={COMPONENTS}>
        {settledMarkdown(text, streaming)}
      </Markdown>
    </div>
  );
}
