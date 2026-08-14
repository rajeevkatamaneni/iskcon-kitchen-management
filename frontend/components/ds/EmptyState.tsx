"use client";

import type { ReactNode } from "react";

/**
 * What a screen says when it holds nothing yet.
 *
 * <p>No illustration, deliberately — the design system says so, and a drawing of an empty box tells
 * a cook nothing. What helps is a sentence saying why it is empty and the one action that fills it.
 */
export function EmptyState({
  title,
  children,
  action,
}: {
  title: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="rounded-lg bg-raised px-6 py-14 text-center">
      <p className="text-lg text-ink">{title}</p>
      {children && <p className="mx-auto mt-2 max-w-prose text-ink-secondary">{children}</p>}
      {action && <div className="mt-6 flex justify-center">{action}</div>}
    </div>
  );
}
