"use client";

import type { ReactNode } from "react";

/**
 * Something the person needs to know before they act, shown where they will act — not as a toast.
 * A message that disappears on its own is a message somebody misses.
 */
export function InlineNotice({
  tone = "info",
  title,
  children,
  action,
}: {
  tone?: "info" | "success" | "warning" | "danger";
  title?: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
}) {
  const tones = {
    info: "bg-sunken text-ink",
    success: "bg-success-bg text-success",
    warning: "bg-warning-bg text-warning",
    danger: "bg-danger-bg text-danger",
  } as const;

  return (
    <div role="status" className={["rounded-sm px-4 py-3 text-sm", tones[tone]].join(" ")}>
      {title && <p className="font-medium">{title}</p>}
      {children && <div className={title ? "mt-1" : ""}>{children}</div>}
      {/*
        Pulled left by the action's own horizontal padding, so the words in the button line up with
        the words above them rather than sitting a few pixels in. Every caller passes a ghost `sm`
        button, whose `px-3` this cancels exactly; the button keeps its full hit area and only the
        text moves onto the message's own left edge.

        A bordered or filled action would want the opposite — its *box* aligned, not its text — so
        one passed here should cancel this with `ml-3`.
      */}
      {action && <div className="mt-3 -ml-3">{action}</div>}
    </div>
  );
}
