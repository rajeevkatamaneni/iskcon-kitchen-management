"use client";

import { useId, type HTMLAttributes, type ReactNode } from "react";

/**
 * A raised surface.
 *
 * <p>Depth here is tone, never shadow — the only shadow in the system is the focus ring, which is
 * functional. A `canvas` card is the exception that carries a hairline, for when a white surface
 * sits on a white page and needs an edge.
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
    raised: "bg-raised border-transparent",
    sunken: "bg-sunken border-transparent",
    canvas: "bg-canvas border-hairline",
  } as const;

  return (
    <section
      aria-labelledby={title ? headingId : undefined}
      className={["rounded-lg border", tones[tone], padding, className].join(" ")}
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
