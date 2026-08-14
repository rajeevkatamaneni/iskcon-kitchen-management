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
  return (
    <div role="tablist" aria-label={label} className="inline-flex gap-0.5 rounded bg-sunken p-[3px]">
      {options.map((o) => {
        const on = o.value === value;
        return (
          <button
            key={o.value}
            role="tab"
            aria-selected={on}
            onClick={() => onChange(o.value)}
            className={[
              "min-h-[38px] rounded-sm px-4 text-sm transition-colors duration-state",
              on ? "bg-canvas font-semibold text-ink" : "text-ink-secondary hover:text-ink",
            ].join(" ")}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
