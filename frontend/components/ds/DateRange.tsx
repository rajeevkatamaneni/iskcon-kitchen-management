"use client";

import { useState } from "react";

/**
 * A pair of dates where the second cannot come before the first.
 *
 * <p>Every screen that asked for a span asked for two independent dates and hoped. Leave could be
 * recorded from the 20th to the 14th; an audit could be filtered over a window that ran backwards;
 * an invoice could fall due before it was issued. Nothing stopped any of it, and the mistake is a
 * single mis-click in a date picker.
 *
 * <p>Two things stop it here. The second input carries a `min` of whatever the first says, so the
 * browser refuses the impossible date itself, in every language and on a phone. And moving the
 * first date forward past the second carries the second with it, rather than leaving a range that
 * is quietly invalid until the form is submitted — a person who moves the start of a leave has
 * moved the leave, not created a contradiction.
 *
 * <p>The inputs keep their `name`, so the forms that read themselves with `FormData` are unchanged.
 */
export function DateRange({
  from,
  to,
  className = "min-h-touch rounded border border-hairline bg-canvas px-3",
  wrapper = "flex flex-col gap-1 text-sm text-ink-secondary",
}: {
  from: { name: string; label: string; defaultValue?: string; required?: boolean };
  to: { name: string; label: string; defaultValue?: string; required?: boolean };
  /** The input's classes, so a screen keeps its own field styling. */
  className?: string;
  /** The label wrapper's classes, for the same reason. */
  wrapper?: string;
}) {
  const [start, setStart] = useState(from.defaultValue ?? "");
  const [end, setEnd] = useState(to.defaultValue ?? "");

  return (
    <>
      <label className={wrapper}>
        <span className="pl-field-inset font-medium text-ink">{from.label}</span>
        <input
          type="date"
          name={from.name}
          required={from.required}
          value={start}
          onChange={(event) => {
            const next = event.target.value;
            setStart(next);
            // The end follows the start rather than being left behind it.
            if (next && end && end < next) {
              setEnd(next);
            }
          }}
          className={className}
        />
      </label>

      <label className={wrapper}>
        <span className="pl-field-inset font-medium text-ink">{to.label}</span>
        <input
          type="date"
          name={to.name}
          required={to.required}
          value={end}
          min={start || undefined}
          onChange={(event) => setEnd(event.target.value)}
          className={className}
        />
      </label>
    </>
  );
}
