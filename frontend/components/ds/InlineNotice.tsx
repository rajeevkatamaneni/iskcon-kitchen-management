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
      {title && <p className="font-medium">{title}</p>}
      {children && <div className={title ? "mt-1" : ""}>{children}</div>}
      {/*
        Pulled left so the words in the action line up with the words above them, rather than
        sitting a few pixels in. `field-inset` is not a spacing choice and is not on the spacing
        scale on purpose — it is the sum of exactly what stands between a ghost button's box and its
        text: `px-3` (12px) and the 1px transparent border every Button variant carries so the ghost
        and the bordered ones are the same height. Cancelling only the padding leaves it 1px out,
        which is what the first attempt did and what measuring on the running page caught.

        The button keeps its whole hit area; only the text moves. A bordered or filled action would
        want the opposite — its *box* aligned, not its text — so one passed here should cancel this
        with `ml-field-inset`.
      */}
      {action && <div className="mt-3 -ml-field-inset">{action}</div>}
    </div>
  );
}
