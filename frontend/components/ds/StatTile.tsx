"use client";

import Link from "next/link";
import type { ReactNode } from "react";

/**
 * One number worth glancing at.
 *
 * <p>Ported from the ISKCON Kitchen Design System (Claude Design, 2026-08-14), with one addition:
 * a tile takes an `href`. A number nobody can act on is decoration, so every tile on Today is a way
 * into the screen that acts on it (E4-S8 D2).
 *
 * <p>Tone is status, not decoration — a figure goes amber or red because it needs attention, never
 * because the screen wanted colour.
 */
export function StatTile({
  label,
  value,
  note,
  tone = "neutral",
  icon,
  href,
}: {
  label: string;
  value: ReactNode;
  note?: ReactNode;
  tone?: "neutral" | "success" | "warning" | "danger";
  /** Tabler icon name without the `ti-` prefix. */
  icon?: string;
  href?: string;
}) {
  const tones = {
    neutral: "text-ink",
    success: "text-success",
    warning: "text-warning",
    danger: "text-danger",
  } as const;

  const body = (
    <>
      <span className="inline-flex items-center gap-2 text-sm text-ink-secondary">
        {icon && <i className={`ti ti-${icon} text-base text-ink-muted`} aria-hidden="true" />}
        {label}
      </span>
      <span className={["text-2xl font-semibold tabular-nums", tones[tone]].join(" ")}>{value}</span>
      {note && <span className="text-xs text-ink-muted">{note}</span>}
    </>
  );

  const shell = "grid gap-1 rounded-lg bg-raised px-6 py-4";

  if (!href) {
    return <div className={shell}>{body}</div>;
  }

  return (
    <Link href={href} className={[shell, "transition-colors duration-state hover:bg-sunken"].join(" ")}>
      {body}
    </Link>
  );
}
