"use client";

import type { ReactNode } from "react";

/**
 * A row of fields whose boxes line up.
 *
 * <p>A field is three stacked parts — label, control, hint — and a row of them only looks like a
 * row if all three parts line up across it. `align-items` cannot do that: it lines up the outer
 * edges of each child, and the outer edges are not what anybody is looking at. The boxes are.
 * Bottom-aligning puts one field's box level with its neighbour's hint text; top-aligning floats a
 * field that has no label a whole line above the rest. Both have shipped here as the fix, and both
 * were wrong — see item 23 of the 2026-08-21 build brief.
 *
 * <p>So the row owns the tracks. It declares three of them and every child takes its rows from the
 * row rather than from its own content, via `grid-rows-subgrid`. The label track is as tall as the
 * tallest label, the control track is one track, the hint track is one track. A field with no hint
 * leaves an empty cell rather than shortening itself, which is what the hand-typed `&nbsp;` spacers
 * used to buy — and those only worked until somebody added a field and did not know to type one.
 *
 * <p>The row wraps its own children. It does not ask them to carry the subgrid classes, because a
 * caller who can forget them is a caller who can break the row again.
 */
export function FieldRow({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <div
      data-field-row=""
      className={["grid grid-flow-col grid-rows-field-row justify-start gap-x-4 gap-y-1", className].join(" ")}
    >
      {toArray(children).map((child, i) => (
        <div key={i} data-field-row-cell="" className="row-span-3 grid grid-rows-subgrid gap-1">
          {child}
        </div>
      ))}
    </div>
  );
}

/**
 * Children as a flat list, with `false`, `null` and `undefined` dropped.
 *
 * <p>Fields in these rows are routinely conditional — `{kind?.needsVenue && <Field …/>}` — and a
 * falsy child must not take a column, or a row of three fields would silently leave gaps where the
 * ones that did not render would have been.
 */
function toArray(children: ReactNode): ReactNode[] {
  const flat: ReactNode[] = [];
  const walk = (node: ReactNode) => {
    if (Array.isArray(node)) {
      node.forEach(walk);
      return;
    }
    if (node === null || node === undefined || node === false || node === true || node === "") return;
    flat.push(node);
  };
  walk(children);
  return flat;
}
