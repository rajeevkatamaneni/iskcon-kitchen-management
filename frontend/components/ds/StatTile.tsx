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
      {/* The figure at `text-lg`, down from `text-2xl`, and the tile's side padding at 20px, down
          from 24px (Rajeev, 2026-09-18, T-237). He asked for the workforce tile's figure to hold
          "120 staff · 1400 volunteers" on one line at 1280 and 1920, with the number smaller and
          the text given more room, and for the four Today tiles to stay alike. Measured in Chrome
          at 1280, where four tiles share a row and the text box is narrowest: that string needs
          213px at the old size against a 172px box, 186px at `text-xl` against 188px even with
          16px padding, and 167px at `text-lg` against 180px here. `text-lg` is also the figure
          size the donations page's own tiles already use. */}
      <span className={["text-lg font-semibold tabular-nums", tones[tone]].join(" ")}>{value}</span>
      {note && <span className="text-pretty text-xs text-ink-muted">{note}</span>}
    </>
  );

  const shell = "card grid gap-1 px-5 py-4";

  if (!href) {
    return <div className={shell}>{body}</div>;
  }

  return (
    <Link
      href={href}
      className={[
        shell,
        // Lifts under the pointer. Two pixels and a step of tone — small enough to read as "this
        // one is live" rather than as an effect, which is the whole budget a hover gets when it
        // happens dozens of times a day.
        //
        // Deliberately no shadow. It used to take the pack's `shadow-raised`, and that token is
        // not a lift shadow — it is whatever a pack means by "a raised surface". Graphite means
        // `0 0 0 1px #87562F`: a hard copper ring, no blur. So on that theme the lift drew a
        // border round the tile instead of a shadow under it, and any future pack that expresses
        // depth as an outline would do the same. Two pixels and a tone step behave identically in
        // all fifteen.
        "transition-[transform,box-shadow,background-color] duration-state ease-out",
        "hover:-translate-y-0.5 hover:bg-sunken hover:shadow-lift",
      ].join(" ")}
    >
      {body}
    </Link>
  );
}
