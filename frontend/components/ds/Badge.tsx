"use client";

import type { ReactNode } from "react";

/**
 * A status marker. Semantic colour is reserved strictly for status: if one of these is coloured,
 * something is genuinely low, wrong, overdue or complete. A badge is never decoration.
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
        "inline-flex items-center px-2 py-0.5 text-xs font-medium",
        shape === "pill" ? "rounded-full" : "rounded-sm",
        tones[tone],
      ].join(" ")}
    >
      {children}
    </span>
  );
}
