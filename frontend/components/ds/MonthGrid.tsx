"use client";

import type { ReactNode } from "react";

/**
 * A month as seven columns of day cells — the calendar's and the planner's, once.
 *
 * <p><strong>This exists because the same overflow bug kept coming back.</strong> Rajeev has
 * reported text spilling out of the planner's month cells at least five times; it was fixed each
 * time and returned each time. The Vaishnava calendar draws the same grid with the same kind of
 * content and has never once overflowed. He asked why the two could not share the layout and differ
 * only in colour and content, and he was right — but the more useful half of the answer is *why* one
 * of them was immune and the other was not.
 *
 * <p><strong>The two used different ways of shortening text, and only one of them is safe.</strong>
 * The planner used {@code truncate} — {@code white-space: nowrap} plus an ellipsis. Nowrap text has
 * a min-content width of the entire string, so a cell holding "Srimati Sita Thakurani (Sri Advaita's
 * consort)" wants to be 300px wide, and <em>every</em> ancestor between that text and the cell has to
 * carry {@code min-width: 0} to let it shrink. Miss one and it overflows. Measured on staging on
 * 2026-09-05: the chip carried {@code min-w-0} and the flex row wrapping it did not, so the row sat
 * at 314px inside a 161px cell and pushed 165px into its neighbour. The fix had been applied one
 * level too low, which is exactly the shape of a bug that keeps coming back — the rule is "put
 * min-w-0 on every ancestor", and nobody can remember a rule like that while adding a line to a cell.
 *
 * <p>The calendar used {@code line-clamp-2}, which lets the text <em>wrap</em> and then clips it
 * after two lines. Wrapped text has a min-content width of its longest word. There is nothing to
 * remember and no ancestor to annotate: it cannot overflow. So this component does what the calendar
 * did, and does not offer the other option — {@link MonthCellLine} is the only way to put text in a
 * cell, and the cell itself clips as a backstop.
 */
export function MonthGrid({
  weekdays,
  cells,
  cell,
  children,
}: {
  /** Column headings, starting on whichever day the caller's week starts. */
  weekdays: readonly string[];
  /** The cells to draw, in order — six weeks of ISO dates for both screens today. */
  cells: readonly string[];
  /** What this screen wants on one cell: its own colours, its own labels, its own click. */
  cell: (iso: string) => {
    className?: string;
    ariaLabel?: string;
    ariaCurrent?: "date" | undefined;
    onClick?: () => void;
  };
  /** What goes inside one cell. Use {@link MonthCellLine} for anything that is text. */
  children: (iso: string) => ReactNode;
}) {
  return (
    // `overflow-hidden` on the frame as well as on each cell: the cells stop their own content
    // escaping, and this stops a cell escaping the card if one ever manages it.
    <div className="overflow-hidden rounded-lg border border-hairline bg-canvas">
      <div className="grid grid-cols-7 border-b border-hairline">
        {weekdays.map((d) => (
          <div key={d} className="px-3 py-3 text-xs uppercase tracking-eyebrow text-ink-muted">
            {d}
          </div>
        ))}
      </div>

      <div className="grid grid-cols-7">
        {cells.map((iso) => {
          const own = cell(iso);
          return (
            <button
              key={iso}
              type="button"
              onClick={own.onClick}
              aria-label={own.ariaLabel}
              aria-current={own.ariaCurrent}
              className={[
                // The geometry, owned here so the two screens cannot drift apart on it.
                //
                // `min-w-0` is the one that matters: a grid item defaults to `min-width: auto`,
                // which means it refuses to shrink below its content. With it, the cell always
                // takes the width of its column and never more. `overflow-hidden` then guarantees
                // the rest — whatever a caller puts inside, it is clipped at the cell's edge rather
                // than drawn over the next day.
                "grid min-h-[6.5rem] min-w-0 content-start gap-1 overflow-hidden",
                "border-b border-r border-hairline p-3 text-left",
                "transition-[transform,box-shadow,background-color] duration-state ease-out",
                "hover:-translate-y-0.5 hover:bg-raised hover:shadow-lift",
                own.className ?? "",
              ].join(" ")}
            >
              {children(iso)}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/**
 * One line of text in a month cell, shortened the only way that cannot overflow.
 *
 * <p>It wraps and then clips after {@code lines} lines. Deliberately not {@code truncate}: an
 * ellipsis on one nowrap line looks tidier in a mockup and is the reason this bug has been fixed and
 * reintroduced repeatedly, because it only holds while every ancestor carries {@code min-width: 0}.
 * This holds unconditionally, so nobody adding a line to a cell has to know any of that.
 *
 * <p>The full text is on the element, so the whole of "Sri Raghunandana Thakura — Disappearance" is
 * a hover away, and the day itself is one press away for reading it properly.
 */
export function MonthCellLine({
  children,
  lines = 2,
  className = "",
  title,
}: {
  children: ReactNode;
  /** How many lines before it clips. Two is the calendar's, and reads well at this size. */
  lines?: 1 | 2;
  className?: string;
  title?: string;
}) {
  return (
    <span
      title={title}
      className={[
        lines === 1 ? "line-clamp-1" : "line-clamp-2",
        // `break-words` for the pathological case: a single unbroken token longer than the column
        // has no wrap opportunity, and line-clamp alone would let it push the cell wide.
        "min-w-0 break-words text-xs leading-tight",
        className,
      ].join(" ")}
    >
      {children}
    </span>
  );
}
