"use client";

import { useId, useRef, useState } from "react";

/**
 * A short explanation attached to a control, reachable by mouse, keyboard and touch.
 *
 * <p>Written for the one place that needed it first: the Edit button on a library recipe, which is
 * unavailable until the temple has taken its own copy. A genuinely `disabled` button was the obvious
 * way to show that and is the wrong one — it fires no pointer events in Safari and takes no focus
 * anywhere, so the explanation exists in the markup and never reaches the person who needs it. The
 * button that wears this is `aria-disabled` instead: it looks the same, still refuses the press, and
 * stays focusable and hoverable.
 *
 * <p>Touch gets an answer too. There is no hover on a phone, so the first tap shows the tip rather
 * than doing nothing, and a tap anywhere else dismisses it.
 */
export function Tooltip({
  text,
  children,
}: {
  text: string;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  function show() {
    if (timer.current) clearTimeout(timer.current);
    setOpen(true);
  }

  function hide() {
    // A short grace period, so moving the pointer from the control onto the tip itself — or between
    // two controls that each have one — does not flicker.
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setOpen(false), 80);
  }

  return (
    <span
      className="relative inline-flex"
      onMouseEnter={show}
      onMouseLeave={hide}
      onFocus={show}
      onBlur={hide}
      onTouchStart={() => setOpen((was) => !was)}
      onKeyDown={(e) => {
        if (e.key === "Escape") setOpen(false);
      }}
    >
      <span aria-describedby={open ? id : undefined}>{children}</span>

      {open && (
        <span
          id={id}
          role="tooltip"
          className="absolute left-1/2 top-full z-10 mt-2 w-max max-w-xs -translate-x-1/2 rounded bg-ink px-3 py-2 text-sm text-ink-inverse shadow-lg"
        >
          {text}
        </span>
      )}
    </span>
  );
}
