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
 *
 * <p>Squared off since 2026-09-17: the corner is the theme's control corner, the same one the buttons
 * and inputs use. It used to be a pill (fully round), with a `shape` prop for a squarer variant.
 * Rajeev approved squaring every pill after seeing them side by side with the buttons on the meal
 * card, where a round status beside a square button read as two different design languages. With one
 * shape left the prop meant nothing, so it went, and a theme pack with softer buttons gets softer
 * badges to match without anyone editing this file.
 */
export function Badge({
  tone = "neutral",
  children,
}: {
  tone?: "neutral" | "success" | "warning" | "danger" | "accent" | "info" | "festival";
  children: ReactNode;
}) {
  const tones = {
    neutral: "bg-sunken text-ink-secondary",
    success: "bg-success-bg text-success",
    warning: "bg-warning-bg text-warning",
    danger: "bg-danger-bg text-danger",
    accent: "bg-accent-bg text-accent-text",
    // Ekadashi's own family (DESIGN_SYSTEM v1.2), the pair the planner's week and the calendar use.
    info: "bg-info-bg text-info",
    // A festival's own saffron (T-229). It wore `success` until green was kept for "what you did
    // worked" and nothing else; a festival is not an outcome.
    festival: "bg-festival-bg text-festival-text",
  } as const;

  return (
    <span
      className={[
        "inline-flex items-center rounded-control px-2 py-0.5 text-xs font-semibold",
        tones[tone],
      ].join(" ")}
    >
      {children}
    </span>
  );
}
