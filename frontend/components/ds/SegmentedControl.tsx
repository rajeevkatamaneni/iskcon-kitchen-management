"use client";

/**
 * Switches the view of one screen — day, week, month. Deliberately not top-level navigation: that
 * lives in the sidebar, and a control that looks like a tab set but moves you between screens is
 * how people lose their place.
 */
export function SegmentedControl<T extends string>({
  options,
  value,
  onChange,
  label,
}: {
  options: readonly { value: T; label: string }[];
  value: T;
  onChange: (value: T) => void;
  /** Names the group for screen readers — "Calendar view", not "tabs". */
  label: string;
}) {
  // `flex-wrap` and `max-w-full`: a six-way filter (the ingredient requests' statuses) is 456px
  // wide, and on a 390 phone a control that could not wrap pushed the whole page sideways (T-232).
  // It wraps onto a second row inside the same band instead.
  return (
    <div role="tablist" aria-label={label} className="inline-flex max-w-full flex-wrap gap-0.5 rounded-control bg-sunken p-[3px]">
      {options.map((o) => {
        const on = o.value === value;
        return (
          <button
            key={o.value}
            role="tab"
            aria-selected={on}
            onClick={() => onChange(o.value)}
            className={[
              "min-h-[38px] rounded-control px-4 text-sm transition-colors duration-state",
              on ? "bg-raised font-semibold text-ink" : "text-ink-secondary hover:text-ink",
            ].join(" ")}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
