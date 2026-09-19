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
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-4">
        <div className="grid min-w-0 grow basis-60 gap-1">
          <h1 className="text-2xl font-semibold text-ink">{title}</h1>
          {subtitle && <p className="text-ink-secondary">{subtitle}</p>}
        </div>
        {actions && <div className="flex flex-none flex-wrap gap-2">{actions}</div>}
      </div>
      {tabs}
    </header>
  );
}
