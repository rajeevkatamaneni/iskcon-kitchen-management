/**
 * How a table is aligned and sized, in one place — the same reasoning as `nav.ts` and
 * `RolePermissions`: a rule retyped in thirty files is a rule that drifts, and this one had.
 *
 * <p><strong>Alignment</strong> (Rajeev, 2026-09-01): everything reads left. Names, counts,
 * amounts, measures and dates all start at the same edge. Only a row's controls sit right, at the
 * end of the row, where the hand goes after reading it.
 *
 * <p><strong>Width, which is the part that goes wrong.</strong> Two rules, in order:
 *
 * <ol>
 *   <li><strong>A column wraps to a second line only when there is no other option.</strong> Every
 *       constant here carries `whitespace-nowrap`, so a column is never squeezed below the width its
 *       content actually needs. "06:00–20:00" stays one reading, "PO-2026-0011" stays one code,
 *       "Edit · Delete" stays one row of controls.</li>
 *   <li><strong>Spare width is shared out, not pooled.</strong> A table with room to spare should
 *       breathe everywhere rather than leave one column crowded and another holding a meaningless
 *       gap. That is the default and it needs no class: a full-width table with every column
 *       refusing to wrap hands each column its natural width and then shares the surplus among them
 *       in proportion. Do nothing and you get it.</li>
 * </ol>
 *
 * <p>So <strong>{@link WRAP} is the exception — but a required one wherever a column has no upper
 * bound.</strong> Put it on the column holding free text somebody typed: an item name, a vendor
 * name, a note, a bundle of chips. Those have no maximum length, and a column that may neither wrap
 * nor be squeezed can only push the table wider than the page, carrying the last column off the
 * edge with it — which is what a 68-character ingredient name did to Inventory. Columns of bounded
 * values — a date, a code, a status, a figure, a unit — never need it. Most tables want it on one
 * column; a few genuinely want it on two, where no single column could absorb the deficit without
 * collapsing to a word a line. A table whose every value is bounded wants it nowhere.
 *
 * <p>Never pair {@link WRAP} with a `max-w-*` on the same column: the cell takes width its contents
 * then refuse to use, and the difference shows as a block of blank space. That was the first
 * complaint this file caused.
 */

/** The table itself. */
export const TABLE = "w-full text-left";

/** The header row's band. */
export const THEAD = "bg-sunken text-sm text-ink-secondary";

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
export const TH_TEXT = `${CELL} font-medium`;

/** A header over counts, amounts or measures. */
export const TH_NUM = `${CELL} font-medium`;

/** A header over dates. */
export const TH_DATE = `${CELL} font-medium`;

/**
 * A header over the row's controls. Left, like every other heading.
 *
 * <p>It was right-aligned to sit at the end of the row, which put the word "Actions" hard against
 * the table's edge while the pair of buttons beneath it read as a block starting further in — the
 * heading and the thing it labelled visibly disagreeing (Rajeev, 2026-09-01). A heading belongs
 * over its column, and this column reads left like the rest.
 */
export const TH_ACTIONS = `${CELL} font-medium`;

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
export const TH_GRID = `${CELL_TIGHT} font-medium`;

/** A cell in a dense grid column. */
export const TD_GRID = `${CELL_TIGHT} tabular-nums`;
