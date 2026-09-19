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
 * (`min`, its min-content width). The short values keep what they need; the text columns give up
 * the difference, the widest first, each down to a common width and never below its minimum, so
 * the fewest columns wrap and by the least. The browser's own answer is worse: measured on the
 * Shopping list at 1280, a table 3px short wrapped a column while leaving 17px and 37px of extra
 * space beside two others. Returns `null` when even that cannot fit, or when `shrink` is not
 * given; the caller then leaves the browser's own layout alone.
 */
export function shareTableWidth(
  need: number[],
  tableWidth: number,
  shrink?: { min: number[]; flexible: boolean[] },
): number[] | null {
  const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0);
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
  const flex = need.map((_, i) => i).filter((i) => shrink.flexible[i]);
  const floor = (i: number) => Math.min(shrink.min[i], need[i]);
  const budget = tableWidth - sum(need.filter((_, i) => !shrink.flexible[i]));
  if (flex.length === 0 || budget < sum(flex.map(floor))) return null;
  // The highest common width the text columns can be held to and still fit.
  const at = (level: number) => sum(flex.map((i) => Math.max(floor(i), Math.min(need[i], level))));
  let lo = 0;
  let hi = Math.max(...flex.map((i) => need[i]));
  while (hi - lo > 1) {
    const mid = Math.floor((lo + hi) / 2);
    if (at(mid) <= budget) lo = mid;
    else hi = mid;
  }
  const width = need.map((n, i) => (shrink.flexible[i] ? Math.max(floor(i), Math.min(n, lo)) : n));
  let left = tableWidth - sum(width);
  for (const i of flex) {
    if (left <= 0) break;
    const add = Math.min(left, need[i] - width[i]);
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
 *       heading cells. When the table is short of room it measures once more, each cell at its
 *       narrowest (`kms-measuring-min`), and narrows the widest text columns just enough. Only
 *       when even that cannot fit are the widths cleared and the browser left to it.</li>
 * </ol>
 *
 * <p>A ruled table ({@link RULED_TABLE}) or an entry grid is fitted from 1024px up only; below that
 * it is a list of cards with no columns. The staff schedule's week grid is not fitted at all (see
 * the top of this file).
 *
 * <p>It runs just after any change to the page's content (before the browser paints it) and
 * whenever a table's width changes, and does nothing where the browser lacks `ResizeObserver` or
 * `matchMedia` (jsdom, in the tests). It writes only inline `style` on heading cells, which React
 * does not manage on these elements, so a re-render never fights it. Without it (before the first
 * frame, or with script off) the table falls back to an ordinary auto layout: nothing is cut off,
 * the spacing is just uneven.
 *
 * <p>It lives in this module, as a side effect of importing it, because this is the one file every
 * table already imports: there is no second place a new table could forget to call.
 */
/** The tables the fitter spaces: every ruled table and the delivery entry grid. */
const FITTED = "table.kms-table, table.kms-entry-grid";

function fitTable(table: HTMLTableElement): void {
  const heads = Array.from(table.tHead?.rows[0]?.cells ?? []);
  heads.forEach((th) => (th.style.width = ""));
  table.style.tableLayout = "";
  if (heads.length === 0) return;
  if (!window.matchMedia("(min-width: 1024px)").matches) return;
  const tableWidth = Math.floor(table.getBoundingClientRect().width);
  if (tableWidth === 0) return; // hidden (a closed panel, a background tab's iframe): nothing to measure

  // Where each heading's column starts, counting spans, so a body cell can be matched to it.
  const starts: number[] = [];
  heads.reduce((at, th) => (starts.push(at), at + th.colSpan), 0);

  // The widest cell in each column, laid out on its own in the given measuring state.
  const measure = (state: string) => {
    const widest = heads.map(() => 0);
    table.classList.add(state);
    for (const row of Array.from(table.rows)) {
      let at = 0;
      for (const cell of Array.from(row.cells)) {
        const i = starts.indexOf(at);
        // A cell spanning columns (an empty-state line, an editing row) says nothing about one column.
        if (i >= 0 && cell.colSpan === heads[i].colSpan) widest[i] = Math.max(widest[i], cell.getBoundingClientRect().width);
        at += cell.colSpan;
      }
    }
    table.classList.remove(state);
    // Rounded up, so a column is never a fraction of a pixel short of its content: measured on
    // /staff at 1280 (T-233), 0.6px short was enough to break "Nitai Chandra Das" onto two lines.
    return widest.map((n) => Math.ceil(n));
  };

  const need = measure("kms-measuring");
  let width = shareTableWidth(need, tableWidth);
  if (!width) {
    // Short of room: measured again, narrowest, only now, because most tables never get here.
    // A column may wrap when its heading may — `kms-flex` on a ruled table, `WRAP` on the grid.
    const flexible = heads.map((th) => getComputedStyle(th).whiteSpace !== "nowrap");
    width = shareTableWidth(need, tableWidth, { min: measure("kms-measuring-min"), flexible });
  }
  if (width) heads.forEach((th, i) => (th.style.width = `${width![i]}px`));
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
  new MutationObserver(schedule).observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true,
  });
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
