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
        {/* The actions may shrink (`min-w-0`, not `flex-none`) so that they wrap onto a second line
            when, and only when, they do not fit the width left to them. With `flex-none` the box was
            as wide as all its buttons in one line, whatever the screen: three actions on an invoice
            ran 91px past a 390px phone (T-274, measured). Nothing changes where they fit: the box
            only shrinks when its line is short of room, and a line with room to spare is left
            alone, so the buttons stay side by side, at their own size (T-277, measured on every
            page that uses this at 1280 and 390). */}
        {actions && <div className="flex min-w-0 flex-wrap gap-2">{actions}</div>}
      </div>
      {tabs}
    </header>
  );
}
