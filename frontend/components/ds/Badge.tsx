"use client";

import type { ReactNode } from "react";

/**
 * A status marker. Semantic colour is reserved strictly for status: if one of these is coloured,
 * something is genuinely low, wrong, overdue or complete. A badge is never decoration.
 *
 * <p>Set in semibold since 2026-08-20. A badge carries a whole fact in one or two words on a
 * coloured ground, at the smallest size in the type scale — the reader has to take it in at a
 * glance or it has failed at the one job it has. Every tone/ground pair here clears WCAG AA;
 * the weight is what makes it land instantly rather than merely be legible.
 */
export function Badge({
  tone = "neutral",
  shape = "pill",
  children,
}: {
  tone?: "neutral" | "success" | "warning" | "danger" | "accent";
  shape?: "pill" | "square";
  children: ReactNode;
}) {
  const tones = {
    neutral: "bg-sunken text-ink-secondary",
    success: "bg-success-bg text-success",
    warning: "bg-warning-bg text-warning",
    danger: "bg-danger-bg text-danger",
    accent: "bg-accent-bg text-accent-text",
  } as const;

  return (
    <span
      className={[
        "inline-flex items-center px-2 py-0.5 text-xs font-semibold",
        shape === "pill" ? "rounded-full" : "rounded-sm",
        tones[tone],
      ].join(" ")}
    >
      {children}
    </span>
  );
}
