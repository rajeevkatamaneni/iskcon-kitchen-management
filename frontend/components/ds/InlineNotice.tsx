"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";

/** How long a confirmation stands before it fades. */
const DISMISS_AFTER_MS = 5_000;

/**
 * Something the person needs to know before they act, shown where they will act — not as a toast.
 *
 * <p>A warning, an error or a piece of standing context stays until it is dealt with. A message
 * that disappears on its own is a message somebody misses, and every one of those still has
 * something in it for the reader to do.
 *
 * <p>A confirmation is the exception, and only when it is asked for. "Saved", "Recorded", "Sent" —
 * the thing it is confirming has already happened, there is nothing left to act on, and it goes on
 * sitting at the top of a list somebody is now working down. Pass `autoDismiss` on those and they
 * clear themselves after five seconds — on `success`, and on the neutral `info` used by a
 * confirmation with nothing to celebrate, such as a temple having been deleted.
 *
 * <p>`autoDismiss` is ignored on `warning` and `danger`, so the rule cannot be broken by passing it
 * in the wrong place. Those two always have something left in them for the reader to do.
 */
export function InlineNotice({
  tone = "info",
  title,
  children,
  action,
  autoDismiss = false,
}: {
  tone?: "info" | "success" | "warning" | "danger";
  title?: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
  /** Fade this notice out after five seconds. Honoured on `success` and `info` only. */
  autoDismiss?: boolean;
}) {
  const dismissible = autoDismiss && (tone === "success" || tone === "info");
  const [gone, setGone] = useState(false);
  const [fading, setFading] = useState(false);

  // Keyed on the words, not on the component's lifetime: recording two payments in a row re-renders
  // the same notice with new text and the person deserves the full five seconds on the second one.
  const key = dismissible ? `${String(title ?? "")}|${String(children ?? "")}` : "";
  const shown = useRef(key);

  useEffect(() => {
    if (!dismissible) return;
    if (shown.current !== key) {
      shown.current = key;
      setGone(false);
      setFading(false);
    }
  }, [dismissible, key]);

  useEffect(() => {
    if (!dismissible || gone) return;
    const fade = setTimeout(() => setFading(true), DISMISS_AFTER_MS);
    const drop = setTimeout(() => setGone(true), DISMISS_AFTER_MS + 200);
    return () => {
      clearTimeout(fade);
      clearTimeout(drop);
    };
  }, [dismissible, gone, key]);

  if (gone) return null;

  const tones = {
    info: "bg-sunken text-ink",
    success: "bg-success-bg text-success",
    warning: "bg-warning-bg text-warning",
    danger: "bg-danger-bg text-danger",
  } as const;

  return (
    <div
      role="status"
      className={[
        "rounded-sm px-4 py-3 text-sm transition-opacity duration-enter",
        tones[tone],
        fading ? "opacity-0" : "opacity-100",
      ].join(" ")}
    >
      {/*
        The words take the width they need and the action sits beside them, on the right, in the
        space that was empty. It used to sit underneath, which cost a whole line of height on every
        notice on the screen while a third of the row stayed blank — three stacked notices on Today
        pushed the tiles below the fold for nothing.

        `flex-wrap` is what makes that safe: on a narrow screen the action drops under the words
        rather than squeezing them, which is the same answer the page header gives.
      */}
      <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
        <div className="min-w-0 flex-1">
          {title && <p className="font-medium">{title}</p>}
          {children && <div className={title ? "mt-1" : ""}>{children}</div>}
        </div>

        {/*
          No `-ml-field-inset` any more, and its reasoning is worth keeping in view rather than
          deleting: while the action sat under the words, a ghost button's *text* had to line up
          with the text above it, so the button was pulled left by exactly what stands between its
          box and its glyphs. Beside the words there is nothing above to line up with, and what the
          eye follows is the right edge — so the box aligns, which is what the old comment said a
          bordered or filled action would want.
        */}
        {action && <div className="flex-none">{action}</div>}
      </div>
    </div>
  );
}
