/**
 * How a table is aligned and sized, in one place — the same reasoning as `nav.ts` and
 * `RolePermissions`: a rule retyped in thirty files is a rule that drifts, and this one had.
 *
 * <h2>The rule, as Rajeev set it on 2026-09-18 (T-236)</h2>
 *
 * <p>It replaces the rule approved earlier the same day (T-228/T-233), which grouped the short
 * columns on the right, read them right, and parked the leftover width between the two groups. On
 * the Shopping list that put Include / Ingredient / Why on the left, which he called perfect, then a
 * wide block of nothing, then On hand / Suggested / Order by squeezed together so tightly that the
 * Suggested box showed "2792" cut off. He asked for the left-hand part's spacing, carried across the
 * whole table. So, for every table in the application:
 *
 * <ol>
 *   <li><strong>Everything reads left.</strong> Every column, figures included, and every heading
 *       aligned like its cells. (On Vendor performance he asked for the percentages to line up with
 *       the Vendor column, not against the right edge.)</li>
 *   <li><strong>Nothing is cut off.</strong> Every column is at least as wide as its widest content,
 *       an input box included — a box must be wide enough to show the figure in it. Text wraps only
 *       when the table genuinely has no room for it on one line.</li>
 *   <li><strong>No dead block.</strong> Width the columns do not need is shared out evenly between
 *       the gaps separating them, so the columns spread across the whole table with the same
 *       generous space between each pair.</li>
 *   <li>The 20px inset at both ends of every row stays, the actions column still has no visible
 *       heading (its `sr-only` label stays for a screen reader), and below 1024px each row is
 *       still a compact card.</li>
 * </ol>
 *
 * <p>The classes below still say what kind of column each is, but the kind no longer decides
 * where a column sits or which way it reads. It decides two things only: whether the column may
 * wrap when the table is short of room ({@link TH_PRIMARY}/{@link TH_SECOND} may, being text of no
 * fixed length; {@link TH_FIXED}, {@link TH_LEAD} and the actions may not), and where a cell goes
 * in the card layout (the flexible ones on line one, the fixed ones on line two, the buttons at the
 * far edge). A fixed cell whose value would be a bare number on a card without its heading takes
 * `data-label="On hand"`, which the card prints before it. The card mechanics are CSS, in the
 * `.kms-table` block at the end of `app/globals.css`, because a table cannot change its own layout
 * at a breakpoint from class names alone.
 *
 * <p>A table follows the rule by using {@link RULED_TABLE} and the constants in this block. Two
 * tables are still on {@link TABLE}. A purchase order's delivery entry grid ({@link ENTRY_GRID})
 * gets the same spacing from the fitter below; it keeps its own padding and phone layout. The
 * staff schedule's week grid does not: Rajeev exempted it on 2026-09-18 (T-233) as a calendar
 * whose seven days have to be one even scale, each 12% of the table, and nothing in the new rule
 * mentions it — so it keeps those equal days until he says otherwise (flagged in
 * `docs/work/proof/T-236.md`). Its columns already read left and it has no blank block.
 */

/** A table following the rule. */
export const RULED_TABLE = "table kms-table w-full text-left";

/** The primary text column (usually the name): reads left, wraps only when the table is short of room. */
export const TH_PRIMARY = "kms-flex kms-primary";
export const TD_PRIMARY = "kms-flex kms-primary";

/** A secondary text column (a category, a location, a person): the same, and not bold on a card. */
export const TH_SECOND = "kms-flex kms-second";
export const TD_SECOND = "kms-flex kms-second";

/** A short value — a unit, a flag, a status, a date: reads left, one line, never wraps. */
export const TH_FIXED = "kms-fixed";
export const TD_FIXED = "kms-fixed";

/** A short value that is a figure — a quantity, money, a count — on a fixed pitch. */
export const TD_FIXED_NUM = "kms-fixed kms-num";

/**
 * The actions column: one line, and marked so that in the card layout the buttons go to the far
 * edge of the second line. Its heading's label is `sr-only`: write it as
 * `<th className={TH_ACTIONS_FIXED}><span className="sr-only">Actions</span></th>`.
 */
export const TH_ACTIONS_FIXED = "kms-fixed kms-actions";
export const TD_ACTIONS_FIXED = "kms-fixed kms-actions";

/**
 * A short value that comes first — the row's reference number when it is also the row's link, or a
 * selection tick box (the two cases Rajeev accepted on 2026-09-18, T-233). One line like any fixed
 * column; in the card layout it opens line one, before the text columns. Put it only on a table's
 * first column.
 */
export const TH_LEAD = "kms-lead";
export const TD_LEAD = "kms-lead";

/**
 * Kept so its one caller (Inventory item — Lots) needs no change: a table of short values only.
 * Under the 2026-09-18 rule it was given its own layout (the lead column left, the rest sharing the
 * width); the current rule already shares spare width evenly between every column, so it is now
 * exactly {@link RULED_TABLE}.
 */
export const RULED_TABLE_EVEN = RULED_TABLE;

/**
 * An entry grid — a table whose rows are rows of input boxes (Purchase order — Record a delivery).
 * Add it beside {@link TABLE}. On a wide screen it is spaced like every other table. Below `lg`
 * each row becomes a card: the first cell (what the line is) across the top, then the boxes in a
 * wrapping grid, each under a small label printed from its cell's `data-label` — so every cell
 * after the first needs one. Without the labels a box on a phone is a blank rectangle whose
 * heading is off-screen. The CSS is the `.kms-entry-grid` block in `app/globals.css`.
 */
export const ENTRY_GRID = "kms-entry-grid";

/**
 * How a table's width is shared between its columns, given what each column's content needs
 * (padding included) and the table's width, both in whole pixels. Exported for the test.
 *
 * <p><strong>When the table has room,</strong> every column gets what it needs, and what is left is
 * split evenly between the <em>gaps</em>: each column but the last gets an equal share, so the space
 * between any two neighbouring columns is the same. The last column gets none, so its content ends
 * the same 20px from the table's right edge as the first column's starts from the left. Split in
 * proportion to each column's width instead — what a browser does on its own — and a long name
 * column takes most of it, leaving one wide gap after the name and the short columns bunched at the
 * end: a smaller version of the dead block this rule exists to remove. Pixels that do not divide
 * evenly go one each to the first gaps, so the widths sum to exactly the table's width and the
 * browser has nothing to redistribute.
 *
 * <p><strong>When it is short of room</strong> — the one case the rule lets text wrap — `shrink` says
 * which columns may wrap (`flexible`) and how narrow each can go without cutting a word or a box
 * (`min`). It gives way in steps, in this order (conductor's ruling for T-269, 2026-09-19, read
 * from §5 rule 2: "Text wraps onto a second line only when the table genuinely lacks room"):
 *
 * <ol>
 *   <li><strong>The headings of the short columns wrap first.</strong> A heading like "Rejected on
 *       delivery" is often far wider than the figures under it ("2 Kg"), and it is text like any
 *       other. `wrapHead` says how narrow each short column can go with its heading on two lines
 *       and every one of its cells still on one line. The headings that save the most go
 *       first, each all the way down, until the table fits; what is then left over is shared
 *       between the gaps exactly as when the table has room. The cells never wrap.</li>
 *   <li><strong>Only then do the text columns give up width</strong>, the widest first, each down
 *       to a common width and never below its minimum, so the fewest wrap and by the least. The
 *       browser's own answer is worse: measured on the Shopping list at 1280, a table 3px short
 *       wrapped a column while leaving 17px and 37px of extra space beside two others.</li>
 *   <li><strong>Only if the text columns still cannot fit at their longest word</strong> (T-278,
 *       conductor's ruling of 2026-09-19) does a short cell break <em>between its pieces</em>
 *       (`stack`): the figure stays whole on the first line and the note written beside it goes to
 *       the next ("₹87,600" above "less ₹3,000 credited", "27 Aug 2026" above "Overdue", a pair of
 *       buttons one above the other). The short columns narrow only as far as the text columns
 *       need to reach their longest word, and the text columns take whatever is left.</li>
 *   <li>Then the headings go one word to a line (`wrapHeadFully`). Measured on a purchase order at
 *       1024 (T-269): with "Rejected on delivery" on two lines the table was 1px short of giving
 *       the Item column its longest word ("Cardamom").</li>
 *   <li>Then a short cell wraps at any space, each word whole (`words`): "1,07,817.147" above its
 *       "Kg" on /inventory at 1024, where nothing short of that leaves the Item column its words.</li>
 *   <li><strong>The last resort</strong> (`lastResort`, T-278; Rajeev on 2026-09-19, relayed by the
 *       conductor: the rule is "a guideline to think smart … pick the lesser of the evils"): every
 *       short column as narrow as it goes, and the text columns, already at their longest word,
 *       held to a common width below it, down to `lastResort.floor`. The caller first lets an
 *       address with no spaces break after its "@" and full stops, then a badge onto two lines; a
 *       word is never broken mid-letter, nor a dropdown or a box cut. With `force`, a table that
 *       cannot fit even so keeps those floors and runs past its box, by an amount the fitter then
 *       takes back by closing the gaps between columns or breaking a long pasted token (T-304,
 *       see `fitTable`).</li>
 * </ol>
 *
 * <p>Before T-269 there was only the second step, and the short columns kept their headings on one
 * line whatever it cost. On a purchase order at 1024px (the menu leaves the card 680px) the five
 * short columns took 632px of it, and the Item column was given the 46px that was left — a name
 * broken every few letters, because a text column's minimum under `overflow-wrap: anywhere` is a
 * single letter. So a {@link TH_PRIMARY} column's minimum is now its longest word (the fitter
 * measures it with ordinary wrapping; since T-278 every text column's is, {@link TH_SECOND}
 * included), and the headings wrap before it is touched. Squeezed, it is held to the same common
 * width as every other text column, so it is never the narrowest of them.
 *
 * <p>Returns `null` when even that cannot fit (without `lastResort.force`), or when `shrink` is not
 * given. Cards above 1024px are not an option: §5 rule 6 puts the card layout below 1024px only
 * (conductor's ruling, 2026-09-19).
 */
export function shareTableWidth(
  need: number[],
  tableWidth: number,
  shrink?: {
    min: number[];
    flexible: boolean[];
    wrapHead?: number[];
    stack?: number[];
    wrapHeadFully?: number[];
    words?: number[];
    lastResort?: { floor: number[]; force: boolean };
  },
): number[] | null {
  if (need.length === 0) return null;
  if (sum(need) <= tableWidth) {
    if (need.length === 1) return [tableWidth];
    const gaps = need.length - 1;
    const spare = tableWidth - sum(need);
    const share = Math.floor(spare / gaps);
    const extra = spare - share * gaps;
    return need.map((n, i) => (i < gaps ? n + share + (i < extra ? 1 : 0) : n));
  }
  if (!shrink) return null;
  const tiers = (...xs: (number[] | undefined)[]) => xs.filter((x): x is number[] => !!x);
  // The last resort: every short column as narrow as it goes, and the text columns, already at
  // their longest word, held to a common width below it: the longest words break and no others.
  // `lastResort.floor` is how narrow each can go even so. With `force`, a table that cannot fit
  // even then keeps its columns at those floors and runs past its box by the difference, rather
  // than cut a dropdown or a box.
  if (shrink.lastResort) {
    const atWords = need.map((n, i) => (shrink.flexible[i] ? Math.min(n, shrink.min[i]) : n));
    const all = tiers(shrink.wrapHead, shrink.stack, shrink.wrapHeadFully, shrink.words);
    const { floor, force } = shrink.lastResort;
    return giveWay(atWords, tableWidth, { ...shrink, min: floor }, all, true, force);
  }
  return (
    // Headings on two lines, then the text columns.
    giveWay(need, tableWidth, shrink, tiers(shrink.wrapHead), true) ??
    // Then short cells broken between their pieces, only as far as the text needs.
    (shrink.stack ? giveWay(need, tableWidth, shrink, tiers(shrink.wrapHead, shrink.stack), false) : null) ??
    // Then the headings one word to a line.
    (shrink.wrapHeadFully
      ? giveWay(need, tableWidth, shrink, tiers(shrink.wrapHead, shrink.stack, shrink.wrapHeadFully), false)
      : null) ??
    // Then short cells wrapped at any space, each word whole ("1,07,817.147" above its "Kg").
    (shrink.words
      ? giveWay(need, tableWidth, shrink, tiers(shrink.wrapHead, shrink.stack, shrink.wrapHeadFully, shrink.words), false)
      : null)
  );
}

const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0);

/**
 * The steps of {@link shareTableWidth} for a table short of room. `tiers` are the successively
 * narrower widths each short column may go to (heading on two lines; then its cells broken between
 * their pieces; then its heading one word to a line), taken a tier at a time and, within a tier,
 * the biggest saving first. With `untilFits` the short columns narrow until the whole table fits
 * and the text columns keep everything (the ruling for headings); without it they narrow only
 * until the text columns fit at their narrowest, so a short cell is broken only when the text
 * would otherwise be cut, and the text columns then take whatever is left.
 */
function giveWay(
  need: number[],
  tableWidth: number,
  shrink: { min: number[]; flexible: boolean[] },
  tiers: number[][],
  untilFits: boolean,
  atFloorsIfShort = false,
): number[] | null {
  const narrowed = [...need];
  const floor = (i: number) => Math.min(shrink.min[i], narrowed[i]);
  const flex = need.map((_, i) => i).filter((i) => shrink.flexible[i]);
  const enough = () =>
    untilFits
      ? sum(narrowed) <= tableWidth
      : sum(narrowed.filter((_, i) => !shrink.flexible[i])) + sum(flex.map(floor)) <= tableWidth;
  for (const tier of tiers) {
    const saving = (i: number) => (shrink.flexible[i] ? 0 : Math.max(0, narrowed[i] - tier[i]));
    const order = need.map((_, i) => i).filter((i) => saving(i) > 0).sort((a, b) => saving(b) - saving(a) || a - b);
    for (const i of order) {
      if (enough()) break;
      narrowed[i] -= saving(i);
    }
  }
  // Every column has what it needs now: the rest is shared between the gaps.
  if (sum(narrowed) <= tableWidth) return shareTableWidth(narrowed, tableWidth);

  // The text columns give way, each down to a common width and never below its minimum.
  const budget = tableWidth - sum(narrowed.filter((_, i) => !shrink.flexible[i]));
  if (flex.length === 0) return null;
  if (budget < sum(flex.map(floor))) {
    return atFloorsIfShort ? narrowed.map((n, i) => (shrink.flexible[i] ? floor(i) : n)) : null;
  }
  // The highest common width the text columns can be held to and still fit.
  const at = (level: number) => sum(flex.map((i) => Math.max(floor(i), Math.min(narrowed[i], level))));
  let lo = 0;
  let hi = Math.max(...flex.map((i) => narrowed[i]));
  while (hi - lo > 1) {
    const mid = Math.floor((lo + hi) / 2);
    if (at(mid) <= budget) lo = mid;
    else hi = mid;
  }
  const width = narrowed.map((n, i) => (shrink.flexible[i] ? Math.max(floor(i), Math.min(n, lo)) : n));
  let left = tableWidth - sum(width);
  for (const i of flex) {
    if (left <= 0) break;
    const add = Math.min(left, narrowed[i] - width[i]);
    width[i] += add;
    left -= add;
  }
  return width;
}

/**
 * <h2>Why this needs a few lines of script</h2>
 *
 * <p>An auto-layout table already gives each column at least what its content needs; what it gets
 * wrong is the rest. It hands spare width out in proportion to each column's width, so the widest
 * column takes the most and the gaps come out uneven. CSS has no way to say "add the same to every
 * column". So, once, for every fitted table:
 *
 * <ol>
 *   <li>The table is put briefly into a measuring state (`kms-measuring`, in the stylesheet) where
 *       every cell is laid out on its own at its unwrapped width (`width: max-content`), and the
 *       widest cell in each column, heading included, is what that column needs. The class comes
 *       off in the same task, before anything is painted.</li>
 *   <li>{@link shareTableWidth} turns those into widths, written as inline pixel widths on the
 *       heading cells. When the table is short of room it measures again, each cell at its
 *       narrowest (`kms-measuring-min`: a text column's cells at their longest word; a short
 *       column's heading at its best break onto two lines, worked out from where its words fall;
 *       a short column's cells broken between their pieces, and at any space), and gives way in
 *       the order {@link shareTableWidth} sets out. A wrapped heading gets an inline
 *       `white-space: normal`, a short cell allowed to break gets `data-kms-stack`. The last resort
 *       (addresses, then badges) is measured only when everything before it has failed.</li>
 * </ol>
 *
 * <p>A ruled table ({@link RULED_TABLE}) or an entry grid is fitted from 1024px up only; below that
 * it is a list of cards with no columns. The staff schedule's week grid is not fitted at all (see
 * the top of this file).
 *
 * <p>It runs just after any change to the page's content (before the browser paints it) and
 * whenever a table's width changes, and does nothing where the browser lacks `ResizeObserver` or
 * `matchMedia` (jsdom, in the tests). It writes only what React does not manage on these elements,
 * so a re-render never fights it: inline `width` and (since T-269) `white-space` on a heading; since
 * T-278 a `data-kms-stack` attribute on a body cell it lets break, and, in the last resort only, a
 * zero-width space after the "@" and full stops of an address (React sets a text node's value only
 * when its own text changes, and then the fitter runs again). All of it is cleared at the start of
 * every fit, and an address copied out of a table is copied without the spaces. Without it (before
 * the first frame, or with script off) the table falls back to an ordinary auto layout: nothing is
 * cut off, the spacing is just uneven.
 *
 * <p>It lives in this module, as a side effect of importing it, because this is the one file every
 * table already imports: there is no second place a new table could forget to call.
 */
/** The tables the fitter spaces: every ruled table and the delivery entry grid. */
const FITTED = "table.kms-table, table.kms-entry-grid";

/** Marks a body cell of a short column whose pieces may go onto lines of their own (see `.kms-table` in globals.css). */
const STACK = "data-kms-stack";
/**
 * A zero-width space: where an address with no spaces in it may break, in the last resort only.
 * Written into the text itself because CSS has no way to say "break only after @ or a full stop",
 * and taken out again at the start of every fit and when the text is copied.
 */
const BREAK = "\u200B";

/**
 * A word joiner: written after the hyphen of a code or reference ("PO-2026-0054", "VERIFY2-C") so the
 * browser cannot break the line there (VERIFY2-D, T-299). Invisible, and taken out again when the
 * text is copied. See {@link withWholeWords}.
 */
const JOIN = "\u2060";

/** A word that is an address: it has an "@" in it, or starts "http://", "https://" or "www.". */
const ADDRESS = /@|^(https?:\/\/|www\.)/;

/** The text nodes in `el` that are words to read, not a screen reader's label or a list's options. */
function textNodes(el: Element): Text[] {
  const out: Text[] = [];
  const walk = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  for (let node = walk.nextNode(); node; node = walk.nextNode()) {
    if (node.parentElement?.closest(".sr-only, select, option, textarea, button")) continue;
    out.push(node as Text);
  }
  return out;
}

/**
 * Keeps every hyphenated word in a table whole (VERIFY2-D and VERIFY2-C, T-299).
 *
 * <p>The rule since T-278 is that a word never breaks, and that only an address (an email, a web
 * address) may break, after its "@", full stops and slashes. A browser does not agree: it treats the
 * hyphen of "PO-2026-0026" as a place to end a line, exactly as it would the hyphen of a compound
 * word in a paragraph. So wherever a table let a cell wrap, a PO number came apart: "PO-" / "2026-" /
 * "0026" in an 85px Against column on the Invoices list at 1024, and in a 63px Order column on
 * Deliveries once a delivery history was opened. Worse, the fitter measured each column's narrowest
 * width with the browser's own line breaking, so it believed "PO-" was the widest thing the column
 * had to hold and narrowed it accordingly.
 *
 * <p>CSS has no switch for this (`hyphens` governs soft hyphens only), so the fix is in the text: a
 * word joiner after each hyphen that sits inside a word. It applies to every fitted table, at every
 * width, because a PO number is one identifier wherever it is shown, and the fitter measures after
 * it, so every floor it works out already holds each identifier whole.
 *
 * <p>Only a word with a figure in it is joined: a code or a reference ("PO-2026-0054",
 * "INV-2026-17", "VERIFY2-C"), which is read as one thing and means nothing in halves. A compound
 * of words ("Top-up", "Store-room") still wraps at its hyphen, as ordinary text does; that is a line
 * ending, not a broken word. Joining those too was measured first and was the worse choice: on
 * /inventory at 1024 the Location column could no longer give way at "VERIFY-A", and the table ran
 * 4px past its box (682 in 678) with the box scrolling sideways. A hyphen at either end of a word
 * (a minus sign, a dash between phrases) is left alone, and so is an address, which may still break
 * where {@link withAddressBreaks} says. Exported for the test.
 */
export function withWholeWords(text: string): string {
  return text.replace(/\S+/g, (word) =>
    // Only a word with a figure in it; a hyphen with something on each side, and no joiner yet.
    ADDRESS.test(word) || !/\d/.test(word) ? word : word.replace(/(?<=\S)-(?=[^\s\u2060])/g, `-${JOIN}`),
  );
}

/** Puts {@link withWholeWords}' joiners into a table's text; already-joined text is left as it is. */
function joinHyphens(table: HTMLTableElement): void {
  for (const node of textNodes(table)) {
    if (!node.data.includes("-")) continue;
    const next = withWholeWords(node.data);
    if (next !== node.data) node.data = next;
  }
}

/** Takes the fitter's break points out of a table's text again. */
function unbreak(table: HTMLTableElement): void {
  for (const node of textNodes(table)) if (node.data.includes(BREAK)) node.data = node.data.split(BREAK).join("");
}

/**
 * The padding either side of a cell that is not at the end of a row, from 1024px up (§5 rule 4),
 * and how far the fitter may take it in when nothing else is left (T-304, below). The stylesheet
 * reads it as `--kms-cell-pad`, defaulting to the same 12px.
 */
const CELL_PAD = 12;
const CELL_PAD_MIN = 6;

/**
 * A word this long is a pasted token (an email address, a bank reference, a name typed with no
 * spaces), not a word to be read, and in the fitter's last step it may break inside itself (T-304).
 * Every identifier the application writes itself is shorter: a PO number is 12 characters, an
 * invoice number about the same, so none of them is ever broken this way. A real UTR is 16 to 22
 * and fits its column at 1024 (T-300 measured the 22-character one in 182px), so it only gets break
 * points in a table that could not fit it anyway.
 */
const LONG_TOKEN = 21;

/**
 * Brings the cells' padding to its new value at once, before anything is measured (T-343).
 *
 * <p>With "reduce motion" switched on, the stylesheet gives every element a 0.01ms transition
 * (`transition-duration` under `prefers-reduced-motion`, with the default `transition-property` of
 * `all`), so padding animates too. A change of `--kms-cell-pad` then starts a transition, and a
 * measurement taken in the same task reads the padding it is leaving, not the one it is going to.
 * Measured on the Shopping list's "No vendor yet" group at 1024 with reduce motion on (VERIFY3,
 * F-2): the gaps were closed to 10px, every column measured as though they were still 12px, the
 * step was judged to have failed, and the table ran 10px past its box (638 in 628) with the box
 * scrolling sideways. With motion allowed the same step fitted it exactly. So any padding
 * transition in the table is finished here; nothing else is touched, and a browser without
 * `getAnimations` is left as it was.
 */
function settlePadding(table: HTMLTableElement): void {
  if (typeof table.getAnimations !== "function" || typeof CSSTransition === "undefined") return;
  for (const a of table.getAnimations({ subtree: true })) {
    if (a instanceof CSSTransition && a.transitionProperty.startsWith("padding")) a.finish();
  }
}

/**
 * Fits one table, and makes sure it never runs past its box (T-304).
 *
 * <p>Until T-304 a table that could not fit even in {@link shareTableWidth}'s last resort ran past
 * its box by the difference, on the reasoning that breaking a word mid-letter was worse. Rajeev's
 * rule is that nothing overflows its container and the page never scrolls sideways (§5 rule 6, read
 * with Q-18: the lesser evil). Measured before this: the Shopping list's "No vendor yet" group at
 * 1024 ran 10px past its box, and its box scrolled sideways; an invoice's Payments table at 1024,
 * given a 60-character reference with no spaces, widened the whole page to 1349px (T-300).
 *
 * <p>So when the ordinary fit still runs over, three more steps, each only if the one before it
 * fails, and each measured again rather than predicted:
 *
 * <ol>
 *   <li><strong>The gaps between columns close up</strong>, evenly, by as little as fits, never
 *       below {@link CELL_PAD_MIN} a side (a 12px gap). No word is touched and nothing is hidden;
 *       the columns are a little closer together. It is tried only when closing every gap to that
 *       floor would cover the shortfall: the "No vendor yet" group needs 2px a side of 12. The
 *       20px at the ends of a row stay.</li>
 *   <li><strong>A long token breaks at its separators</strong>: after an "@", a full stop, a
 *       hyphen, a slash or an underscore, as an email address already could. Only a token of
 *       {@link LONG_TOKEN} characters or more, so a PO number never breaks at its hyphens. The gaps
 *       close up again on top of this if that alone is not quite enough.</li>
 *   <li><strong>A long token breaks anywhere</strong>, the true last resort: a reference with no
 *       separators at all ("SBIN0000…") goes onto as many lines as it needs, inside its cell. A
 *       reference is an identifier to be copied, not a word to be read, and copying still gives it
 *       back whole (the break points are taken out on copy).</li>
 * </ol>
 */
function fitTable(table: HTMLTableElement): void {
  let over = fitOnce(table, {});
  if (over <= 0) return;
  const tighter = (shortBy: number, columns: number) => {
    const sides = 2 * (columns - 1);
    const pad = sides > 0 ? CELL_PAD - Math.ceil(shortBy / sides) : -1;
    return pad >= CELL_PAD_MIN ? pad : null;
  };
  const columns = table.tHead?.rows[0]?.cells.length ?? 0;
  let pad = tighter(over, columns);
  if (pad !== null && (over = fitOnce(table, { pad })) <= 0) return;
  over = fitOnce(table, { breaks: "separators" });
  if (over <= 0) return;
  pad = tighter(over, columns);
  if (pad !== null && (over = fitOnce(table, { breaks: "separators", pad })) <= 0) return;
  fitOnce(table, { breaks: "anywhere" });
}

/**
 * Break points inside a long token (see {@link LONG_TOKEN}): after its separators, or between every
 * character. Shorter words are left alone, and so is the word joiner {@link withWholeWords} put in a
 * long token, which would otherwise hold its hyphens together. Exported for the test.
 */
export function withLongTokenBreaks(text: string, at: "separators" | "anywhere"): string {
  return text.replace(/\S+/g, (word) => {
    const plain = word.split(JOIN).join("");
    if (Array.from(plain).length < LONG_TOKEN) return word;
    return at === "separators"
      ? plain.replace(/([@./_-])(?=[^@./_-])/g, `$1${BREAK}`)
      : Array.from(plain).join(BREAK);
  });
}

/**
 * One fit of `table` (the steps {@link shareTableWidth} sets out), with the gaps and the long tokens
 * as {@link fitTable} asks. Returns how many pixels the table still runs past its box: 0 when it
 * fits.
 */
function fitOnce(table: HTMLTableElement, opts: { pad?: number; breaks?: "separators" | "anywhere" }): number {
  const heads = Array.from(table.tHead?.rows[0]?.cells ?? []);
  heads.forEach((th) => {
    th.style.width = "";
    th.style.whiteSpace = "";
    th.style.overflowWrap = "";
  });
  table.querySelectorAll(`[${STACK}]`).forEach((cell) => cell.removeAttribute(STACK));
  unbreak(table);
  table.style.removeProperty("--kms-cell-pad");
  // Before anything is measured, and below 1024 too, where there is nothing to fit but a PO number
  // should no more break on a phone card than on a wide table.
  joinHyphens(table);
  table.style.tableLayout = "";
  if (heads.length === 0) return 0;
  if (!window.matchMedia("(min-width: 1024px)").matches) return 0;
  if (opts.pad !== undefined) table.style.setProperty("--kms-cell-pad", `${opts.pad}px`);
  settlePadding(table);
  // The width the table has to fill: its own, or, when its content has already pushed it wider
  // than the box it sits in, the box's. Measured on the Shopping list at 1024 (T-278): a table 641
  // wide in a 630 box, which the fitter took as 641 of room and so never made fit.
  // A table pulled out over its box's padding by negative margins (the delivery grid in
  // RecordDeliveryPanel, `lg:-mx-5`) has that much more.
  const box = table.parentElement;
  const boxStyle = box ? getComputedStyle(box) : null;
  const own = getComputedStyle(table);
  const room =
    box && boxStyle
      ? box.clientWidth -
        parseFloat(boxStyle.paddingLeft) -
        parseFloat(boxStyle.paddingRight) -
        parseFloat(own.marginLeft) -
        parseFloat(own.marginRight)
      : Infinity;
  const tableWidth = Math.floor(Math.min(table.getBoundingClientRect().width, room));
  if (tableWidth <= 0) return 0; // hidden (a closed panel, a background tab's iframe): nothing to measure

  // Where each heading's column starts, counting spans, so a body cell can be matched to it.
  const starts: number[] = [];
  heads.reduce((at, th) => (starts.push(at), at + th.colSpan), 0);
  /** Every cell that belongs to one column, with that column's index; `body` leaves out the heading row. */
  const cells = (body = false) => {
    const out: { cell: HTMLTableCellElement; i: number; head: boolean }[] = [];
    for (const row of Array.from(table.rows)) {
      const head = row.parentElement === table.tHead;
      if (body && head) continue;
      let at = 0;
      for (const cell of Array.from(row.cells)) {
        const i = starts.indexOf(at);
        // A cell spanning columns (an empty-state line, an editing row) says nothing about one column.
        if (i >= 0 && cell.colSpan === heads[i].colSpan) out.push({ cell, i, head });
        at += cell.colSpan;
      }
    }
    return out;
  };

  // T-304: the long tokens in the text columns broken where fitTable has asked, before anything is
  // measured, so every floor below already allows for them. Text columns only: a short value
  // (a figure, a date, a status) is never a pasted token.
  if (opts.breaks) {
    const text = heads.map((th) => getComputedStyle(th).whiteSpace !== "nowrap");
    for (const { cell, i } of cells(true)) {
      if (!text[i]) continue;
      for (const node of textNodes(cell)) {
        const next = withLongTokenBreaks(node.data, opts.breaks);
        if (next !== node.data) node.data = next;
      }
    }
  }

  // The widest cell in each column, laid out on its own in the given measuring state — the
  // heading apart from the cells under it, because since T-269 a short column's heading may wrap
  // while its cells may not, so the two are floors of different kinds.
  const measure = (state: string) => {
    const head = heads.map(() => 0);
    const body = heads.map(() => 0);
    table.classList.add(state);
    for (const { cell, i, head: inHead } of cells()) {
      const into = inHead ? head : body;
      into[i] = Math.max(into[i], cell.getBoundingClientRect().width);
    }
    table.classList.remove(state);
    // Rounded up, so a column is never a fraction of a pixel short of its content: measured on
    // /staff at 1280 (T-233), 0.6px short was enough to break "Nitai Chandra Das" onto two lines.
    const up = (xs: number[]) => xs.map((n) => Math.ceil(n));
    return { head: up(head), body: up(body) };
  };

  const widest = measure("kms-measuring");
  // How narrow each heading can be on two lines, measured from where its words fall when it is on
  // one: the best place to break it is where the longer of the two halves is shortest. Two lines,
  // not three: the conductor's ruling (2026-09-19) lets a long heading wrap "to two lines", and
  // "REJECTED / ON / DELIVERY" stacked three high reads as three headings. A heading of one word
  // cannot wrap, so its two-line width is its whole width.
  table.classList.add("kms-measuring");
  const twoLines = heads.map((th) => {
    const whole = th.getBoundingClientRect().width;
    const pieces: { left: number; right: number }[] = [];
    for (const node of textNodes(th)) {
      for (const m of node.data.matchAll(/\S+/g)) {
        const range = document.createRange();
        range.setStart(node, m.index!);
        range.setEnd(node, m.index! + m[0].length);
        const r = range.getBoundingClientRect();
        if (r.width > 0) pieces.push({ left: r.left, right: r.right });
      }
    }
    // Anything else in the heading that is not text (the "i" of a hint) is a piece of its own.
    th.querySelectorAll("button, svg, img").forEach((el) => {
      const r = el.getBoundingClientRect();
      if (r.width > 0 && !el.closest(".sr-only")) pieces.push({ left: r.left, right: r.right });
    });
    pieces.sort((a, b) => a.left - b.left);
    if (pieces.length < 2) return { two: Math.ceil(whole), fully: Math.ceil(whole) };
    const inset = whole - (pieces[pieces.length - 1].right - pieces[0].left);
    let best = Infinity;
    for (let k = 1; k < pieces.length; k++) {
      const first = pieces[k - 1].right - pieces[0].left;
      const second = pieces[pieces.length - 1].right - pieces[k].left;
      best = Math.min(best, Math.max(first, second));
    }
    // And, for the last resort, one word to a line: the heading's longest word.
    const longest = Math.max(...pieces.map((p) => p.right - p.left));
    return { two: Math.ceil(best + inset), fully: Math.ceil(longest + inset) };
  });
  // T-278. The widest loose run of text directly in each body cell ("27 Aug 2026" beside an
  // "Overdue" badge), padding included: a short cell broken between its pieces is never narrower
  // than this, so a piece that starts a line is never itself broken.
  const loose = heads.map(() => 0);
  for (const { cell, i } of cells(true)) {
    const style = getComputedStyle(cell);
    const inset = parseFloat(style.paddingLeft) + parseFloat(style.paddingRight);
    for (const node of Array.from(cell.childNodes)) {
      if (node.nodeType !== Node.TEXT_NODE) continue;
      // A run of text is itself in pieces where a comma or a middle dot separates two values
      // ("19 Aug 2026, 14:08"), and each piece stays whole.
      const text = node.textContent ?? "";
      const piece = (from: number, to: number) => {
        while (from < to && /\s/.test(text[from])) from++;
        while (to > from && /\s/.test(text[to - 1])) to--;
        if (from === to) return;
        const range = document.createRange();
        range.setStart(node, from);
        range.setEnd(node, to);
        loose[i] = Math.max(loose[i], Math.ceil(range.getBoundingClientRect().width + inset));
      };
      let from = 0;
      for (const m of text.matchAll(/[,·]\s+/g)) {
        piece(from, m.index! + 1);
        from = m.index! + m[0].length;
      }
      piece(from, text.length);
    }
  }
  table.classList.remove("kms-measuring");
  const need = heads.map((_, i) => Math.max(widest.head[i], widest.body[i]));
  let width = shareTableWidth(need, tableWidth);
  if (!width) {
    // Short of room: measured again, narrowest, only now, because most tables never get here.
    // A column may wrap when its heading may — `kms-flex` on a ruled table, `WRAP` on the grid.
    const flexible = heads.map((th) => getComputedStyle(th).whiteSpace !== "nowrap");
    const narrowest = measure("kms-measuring-min");
    // And each short column with its cells broken between their pieces (T-278).
    const short = cells(true).filter(({ i }) => !flexible[i]);
    short.forEach(({ cell }) => cell.setAttribute(STACK, ""));
    const stacked = measure("kms-measuring-min");
    // And with them wrapped at any space, every word whole.
    short.forEach(({ cell }) => cell.setAttribute(STACK, "words"));
    const worded = measure("kms-measuring-min");
    short.forEach(({ cell }) => cell.removeAttribute(STACK));
    const stackBody = heads.map((_, i) => Math.min(widest.body[i], Math.max(stacked.body[i], loose[i])));
    const wordBody = heads.map((_, i) => Math.min(stackBody[i], worded.body[i]));
    const shrink = (min: number[]) => ({
      min,
      flexible,
      // A short column with its heading on two lines and every cell under it whole.
      wrapHead: heads.map((_, i) => Math.max(twoLines[i].two, widest.body[i])),
      // Then with its cells broken between their pieces as well.
      stack: heads.map((_, i) => Math.max(twoLines[i].two, stackBody[i])),
      // Then the heading one word to a line.
      wrapHeadFully: heads.map((_, i) => Math.max(twoLines[i].fully, stackBody[i])),
      // Then the cells wrapped at any space.
      words: heads.map((_, i) => Math.max(twoLines[i].fully, wordBody[i])),
    });
    const min = heads.map((_, i) => Math.max(narrowest.head[i], narrowest.body[i]));
    width = shareTableWidth(need, tableWidth, shrink(min));
    // The last resort (conductor's provisional ruling for T-278, 2026-09-19, going to Rajeev): an
    // address with no spaces in it (an email address, a web address) may break after its "@" and
    // its full stops, and the text columns then share what is left. An ordinary word still never
    // breaks. Measured on /users at 1024 before this: every other column at its narrowest left the
    // e-mail column less than its longest address, and the browser's own layout broke the addresses
    // mid-letter.
    let floors = min;
    if (!width && breakAddresses(table, cells(true).filter(({ i }) => flexible[i]).map(({ cell }) => cell))) {
      const again = measure("kms-measuring-min");
      floors = heads.map((_, i) => Math.max(again.head[i], again.body[i]));
      width = shareTableWidth(need, tableWidth, shrink(floors));
    }
    // And if even that cannot fit (Rajeev, 2026-09-19, relayed by the conductor: the rule is "a
    // guideline to think smart … pick the lesser of the evils"): the text columns, already at their
    // longest word, go below it only as far as a badge in them can take two lines ("shortfall /
    // 900 gm"), held to a common width. A word is never broken mid-letter, and a dropdown or a box
    // never cut: if the table still cannot fit, its columns stay at those floors and it runs past
    // its box by the difference. Measured on the Shopping list's "No vendor yet" group at 1024
    // (seven columns in 628px), letting words break instead split "INGREDIENT" and "shortfall"
    // mid-word. Before T-278 the browser's own layout was left to it here, and it gave one column
    // 36px while the others kept all of theirs (the Invoices list's Vendor column at 1024, broken
    // every two or three letters).
    const text = cells(true).filter(({ i }) => flexible[i]);
    if (!width) {
      text.forEach(({ cell }) => cell.setAttribute(STACK, "all"));
      const loosened = measure("kms-measuring-min");
      text.forEach(({ cell }) => cell.removeAttribute(STACK));
      const floor = heads.map((_, i) => Math.max(loosened.head[i], loosened.body[i]));
      width = shareTableWidth(need, tableWidth, { ...shrink(floors), lastResort: { floor, force: true } });
      if (width) text.forEach(({ cell, i }) => width![i] < floors[i] && cell.setAttribute(STACK, "all"));
      // Run past the box: the text columns' words are made unbreakable, or the browser would
      // squeeze the table back into its box by breaking them after all. Since T-304 this is not
      // where it ends: the amount it runs over is returned, and fitTable closes the gaps or breaks
      // a long token until it fits.
      if (width && width.reduce((a, b) => a + b, 0) > tableWidth) {
        text.forEach(({ cell }) => cell.setAttribute(STACK, "all"));
        heads.forEach((th, i) => flexible[i] && (th.style.overflowWrap = "normal"));
      }
    }
    if (width) {
      // A short column given less than its heading needs on one line gets its heading wrapped, and
      // one given less than its cells need on one line gets them broken between their pieces.
      heads.forEach((th, i) => !flexible[i] && width![i] < widest.head[i] && (th.style.whiteSpace = "normal"));
      short.forEach(({ cell, i }) => {
        if (width![i] < stackBody[i]) cell.setAttribute(STACK, "words");
        else if (width![i] < widest.body[i]) cell.setAttribute(STACK, "");
      });
    }
  }
  if (width) heads.forEach((th, i) => (th.style.width = `${width![i]}px`));
  return width ? Math.max(0, sum(width) - tableWidth) : 0;
}

/**
 * The text with a break point after every "@", full stop and slash inside an address: a word with
 * an "@" in it, or one starting "http://", "https://" or "www.". Every other word is left alone, so
 * "100.2kg" and "Dr.Rao" never break. Exported for the test.
 */
export function withAddressBreaks(text: string): string {
  return text.replace(/\S+/g, (word) =>
    ADDRESS.test(word) ? word.replace(/([@./])(?=[^@./])/g, `$1${BREAK}`) : word,
  );
}

/**
 * Puts {@link withAddressBreaks}' break points into the given cells (the text columns: a figure
 * is never in them), so that "ikms.volunteer.5@trading4good.org" may run onto a second line after
 * "@". Returns whether it changed anything.
 */
function breakAddresses(table: HTMLTableElement, within: Element[]): boolean {
  let changed = false;
  for (const cell of within) {
    for (const node of textNodes(cell)) {
      const next = withAddressBreaks(node.data);
      if (next !== node.data) {
        node.data = next;
        changed = true;
      }
    }
  }
  return changed && table.isConnected;
}

function installTableFitter(): void {
  const w = window as typeof window & { __kmsTableFitter?: boolean };
  // Installed once per page, even when a dev-server hot reload evaluates this module again.
  if (w.__kmsTableFitter) return;
  if (typeof ResizeObserver === "undefined" || typeof MutationObserver === "undefined" || typeof w.matchMedia !== "function") return;
  w.__kmsTableFitter = true;

  const widths = new WeakMap<Element, number>();
  let pending = false;
  const fitAll = () => {
    pending = false;
    document.querySelectorAll<HTMLTableElement>(FITTED).forEach((table) => {
      if (!widths.has(table)) {
        widths.set(table, -1);
        resized.observe(table);
      }
      fitTable(table);
    });
    // The fitter's own edits to the text (an address's break points, put in and taken out) are
    // not a change to the page: dropped here, or every fit would schedule the next one.
    changed.takeRecords();
  };
  const schedule = () => {
    if (pending) return;
    pending = true;
    // A microtask, not a frame: it still runs before the next paint, so nobody sees the columns
    // move, and unlike requestAnimationFrame it is not paused in a tab the browser has hidden.
    queueMicrotask(fitAll);
  };
  // Only a change of *width* matters. Fitting can change a table's height (a column narrows and
  // its text wraps), and reacting to that would refit for nothing, every time.
  const resized = new ResizeObserver((entries) => {
    for (const e of entries) {
      const width = Math.round(e.contentRect.width);
      if (widths.get(e.target) !== width) {
        widths.set(e.target, width);
        schedule();
      }
    }
  });
  // Rows arriving, a filter changing what is listed, a name being edited in place. Attributes are
  // deliberately not watched: the fitter's own writes are attributes, and watching them would loop.
  const changed = new MutationObserver(schedule);
  changed.observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true,
  });
  // Text copied out of a table is copied without the break points and joiners the fitter put in it:
  // "PO-2026-0054" pasted into a search box has to find PO-2026-0054.
  document.addEventListener("copy", (e) => {
    const text = window.getSelection()?.toString() ?? "";
    if (!(text.includes(BREAK) || text.includes(JOIN)) || !e.clipboardData) return;
    e.clipboardData.setData("text/plain", text.split(BREAK).join("").split(JOIN).join(""));
    e.preventDefault();
  });
  // A web font arriving after the first fit changes every width the fit measured (T-278: on a
  // first visit the Shopping list's name column was fitted with the fallback font and then broke
  // "Coriander" when Anek Latin came in). Nothing else would notice, since the table's own width
  // does not change.
  document.fonts?.addEventListener?.("loadingdone", schedule);
  // A figure typed into a box in a table can outgrow its column; refit when the person leaves it.
  document.addEventListener("focusout", (e) => {
    if ((e.target as Element | null)?.closest?.(FITTED)) schedule();
  });
  schedule();
}

if (typeof window !== "undefined") installTableFitter();

/**
 * <h2>The constants of the 2026-09-01 rule, still used by the two tables on {@link TABLE}</h2>
 *
 * <p>The staff schedule's week grid and the delivery entry grid. Everything in them reads left
 * already, as the current rule wants; the fitter above spaces the entry grid's columns. Every constant carries
 * `whitespace-nowrap`, so a column is never squeezed below the width its content needs, and
 * {@link WRAP} is put on the one column holding free text of no maximum length (a person's name),
 * which is the only one allowed to wrap when the grid is short of room.
 *
 * <p>Never pair {@link WRAP} with a `max-w-*` on the same column: the cell takes width its contents
 * then refuse to use, and the difference shows as a block of blank space.
 */

/**
 * The table itself.
 *
 * <p>The `table` class is what hands the header band to the theme: THEME-TOKENS §4 styles
 * `.table thead th`, and §3.1 is emphatic that the band is painted with `table-header-bg` and not
 * with `sunken`. A warm beige header on a cool blue page was, in its own words, "most of what
 * looked broken" in the first version of these packs.
 */
export const TABLE = "table w-full text-left";

/**
 * The header row's band — now empty, and deliberately so.
 *
 * <p>It used to carry `bg-sunken text-sm text-ink-secondary`. All three now come from §4's
 * `.table thead th`: the pack's own `table-header-bg`, `ink-secondary` (§3.3 — *not* `ink-muted`,
 * whose 4.61 is the floor rather than comfortable at this size), and 11px semibold uppercase with
 * tracking. Kept as a constant rather than deleted from thirty call sites, so that where the band
 * next changes there is still one place to change it.
 */
export const THEAD = "";

/**
 * A body row. Add `opacity-50` for a row the person has excluded, never a different colour.
 *
 * <p>`align-top`, so that when a wrapping column runs to three lines the name beside it stays
 * level with the first of them rather than drifting to the middle of a tall row.
 */
export const TR = "border-t border-hairline align-top hover:bg-sunken";

/** Shared by every cell: one padding, one rhythm, and the refusal to wrap. */
const CELL = "px-5 py-3 whitespace-nowrap";

/**
 * Lets one column wrap — put it on **both** the `<th>` and the `<td>`, alongside the usual constant.
 *
 * <p><strong>The `!` is load-bearing.</strong> Tailwind emits `.whitespace-nowrap` after
 * `.whitespace-normal`, so without the important flag this class loses to the `whitespace-nowrap`
 * in {@link CELL} and silently does nothing at all — which is exactly what it did on first writing,
 * caught only by reading `getComputedStyle().whiteSpace` off a live page rather than trusting the
 * markup to mean what it says.
 *
 * <p>There is deliberately **no width class here**. An earlier version paired this with `w-full` to
 * make one column "absorb the slack"; `width: 100%` does not take the leftover room, it takes
 * everything above the other columns' *minimum*. Measured on Inventory: with `w-full` the Item
 * column ran to 602px, mostly blank, while Location collapsed to 107px and broke "Office cupboard,
 * locked" over three lines; without it, 455px and 254px, each on one line. Sharing the surplus is
 * what a full-width table already does on its own.
 */
export const WRAP = "!whitespace-normal";

/** A header over words. */
export const TH_TEXT = CELL;

/** A header over counts, amounts or measures. */
export const TH_NUM = CELL;

/** A header over dates. */
export const TH_DATE = CELL;

/**
 * A header over the row's controls. Left, like every other heading.
 *
 * <p>It was right-aligned to sit at the end of the row, which put the word "Actions" hard against
 * the table's edge while the pair of buttons beneath it read as a block starting further in — the
 * heading and the thing it labelled visibly disagreeing (Rajeev, 2026-09-01). A heading belongs
 * over its column, and this column reads left like the rest.
 */
export const TH_ACTIONS = CELL;

/** Words: an ingredient, a vendor, a person, a status. */
export const TD_TEXT = CELL;

/**
 * A count, an amount, or a measure — and its unit, which stays on the same line as the figure it
 * belongs to. `tabular-nums` keeps the digits on a fixed pitch so a column does not shimmer as
 * values change.
 */
export const TD_NUM = `${CELL} tabular-nums`;

/** A date. Written out rather than left as an ISO string, and kept whole. */
export const TD_DATE = CELL;

/**
 * The row's controls, kept on one line so a pair of buttons never stacks, and starting at the same
 * edge as the "Actions" heading above them.
 */
export const TD_ACTIONS = CELL;

/**
 * The container *inside* an actions cell, wrapping two or more controls.
 *
 * <p>`gap-3` rather than the `gap-2` used between things that belong together: two buttons in a row
 * are two different decisions, one of which is often destructive, and set close they read as one
 * control and invite the wrong press. This is the same reasoning as the design system's spacing
 * between adjacent bordered controls — separate actions get separation.
 */
export const ACTIONS_ROW = "inline-flex items-center gap-3";

/**
 * A column in a dense grid — the staff schedule's seven days, and anything else repeating a column
 * once per day of the week. The shared padding is right for a table of a few columns and wrong for
 * one of ten: seven days at {@link CELL} add close to three hundred pixels, which is the difference
 * between seeing the week at once and scrolling for it.
 *
 * <p>Not a licence to tighten a table that merely feels roomy.
 */
const CELL_TIGHT = "px-2 py-2 whitespace-nowrap";

/** A header over a dense grid column. */
export const TH_GRID = CELL_TIGHT;

/** A cell in a dense grid column. */
export const TD_GRID = `${CELL_TIGHT} tabular-nums`;
