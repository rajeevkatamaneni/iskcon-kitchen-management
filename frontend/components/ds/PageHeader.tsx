"use client";

import type { ReactNode } from "react";

/** Page title, one line of context, and the screen's single primary action. */
export function PageHeader({
  title,
  subtitle,
  actions,
  tabs,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  /** View switcher or filters, sitting under the title rather than competing with it. */
  tabs?: ReactNode;
}) {
  return (
    <header className="grid gap-4">
      <div className="flex items-start justify-between gap-6">
        <div className="grid gap-1">
          <h1 className="text-2xl font-semibold text-ink">{title}</h1>
          {subtitle && <p className="text-ink-secondary">{subtitle}</p>}
        </div>
        {actions && <div className="flex flex-none gap-2">{actions}</div>}
      </div>
      {tabs}
    </header>
  );
}
