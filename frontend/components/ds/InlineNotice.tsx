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
        Pulled left so the words in the action line up with the words above them, rather than
        sitting a few pixels in. The 13px is not a spacing choice and is not on the spacing scale on
        purpose — it is the sum of exactly what stands between a ghost button's box and its text:
        `px-3` (12px) and the 1px transparent border every Button variant carries so the ghost and
        the bordered ones are the same height. Cancelling only the padding leaves it 1px out, which
        is what the first attempt did and what measuring on the running page caught.

        The button keeps its whole hit area; only the text moves. A bordered or filled action would
        want the opposite — its *box* aligned, not its text — so one passed here should cancel this
        with `ml-[13px]`.
      */}
      {action && <div className="mt-3 -ml-[13px]">{action}</div>}
    </div>
  );
}
