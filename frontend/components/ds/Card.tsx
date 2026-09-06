"use client";

import { useId, type HTMLAttributes, type ReactNode } from "react";

/**
 * A raised surface.
 *
 * <p>A `raised` card is painted by the theme's own card material — `.card` in `globals.css`, which
 * is THEME-TOKENS §4 quoted verbatim. That is a background, a sheen layer, a border shorthand, a
 * radius, a shadow and a backdrop filter, and between them they are most of what tells a glossy
 * pack from a frosted one from a flat one. It cannot be expressed as Tailwind colour utilities,
 * which is exactly the split §8.2 draws: materials are component classes, everything else is
 * utilities.
 *
 * <p>The other two tones are not cards in that sense. `sunken` is §3.1's recessed band — a well
 * inside a card — and `canvas` is a panel deliberately the same tone as the page, carrying a
 * hairline so a white surface on a white page still has an edge. Neither takes the sheen, and
 * neither may be nested inside a `raised` card: §6 is clear that glass over glass kills the effect,
 * and §3.1 that a design wanting a fourth surface level wants less nesting.
 */
export function Card({
  title,
  meta,
  action,
  tone = "raised",
  padding = "p-6",
  className = "",
  children,
  ...rest
}: {
  title?: ReactNode;
  meta?: ReactNode;
  action?: ReactNode;
  tone?: "raised" | "sunken" | "canvas";
  /** Tailwind padding class; pass "p-0" for a card whose content bleeds to the edge. */
  padding?: string;
  children?: ReactNode;
} & HTMLAttributes<HTMLElement>) {
  // A titled card is a landmark worth navigating to, but a <section> only becomes one once it has
  // an accessible name — so the heading names it rather than leaving an anonymous region behind.
  const headingId = useId();
  const tones = {
    raised: "card",
    sunken: "rounded-card border border-transparent bg-sunken",
    canvas: "rounded-card border border-hairline bg-canvas",
  } as const;

  return (
    <section
      aria-labelledby={title ? headingId : undefined}
      className={[tones[tone], padding, className].join(" ")}
      {...rest}
    >
      {(title || action) && (
        <header className="mb-4 flex items-baseline justify-between gap-4">
          <div>
            {title && (
              <h3 id={headingId} className="text-lg font-medium text-ink">
                {title}
              </h3>
            )}
            {meta && <p className="mt-1 text-xs text-ink-muted">{meta}</p>}
          </div>
          {action}
        </header>
      )}
      {children}
    </section>
  );
}
