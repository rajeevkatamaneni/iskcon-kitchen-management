"use client";

import type { ReactNode } from "react";
import { Sidebar } from "@/components/Sidebar";

/**
 * One screen, one task.
 *
 * <p>The pattern settled in Q1 of the 2026-08-21 brief, for every place a button used to open a
 * form on top of a list. Eight rules, and this component is where seven of them live so a screen
 * cannot half-follow them:
 *
 * <ol>
 *   <li>Its own URL. Linkable, reloadable, and the back button does the obvious thing.
 *   <li>The sidebar stays. Trapping someone on a form is worse than the distraction.
 *   <li>The task is the h1; one line under it says whose record this is.
 *   <li>Actions top right, together, secondary first: {@code [Cancel] [Primary]}.
 *   <li>The header is sticky.
 *   <li>No second copy of the buttons at the foot. One place to commit.
 *   <li>No back-link. Cancel is the way out.
 * </ol>
 *
 * <p>The eighth — committing returns to the list with the confirmation waiting there — belongs to
 * the caller, because only it knows which list.
 *
 * <p><b>Why sticky.</b> Measured on live, terminating a member of staff with a ban ticked: the
 * heading sits at 180px, the Terminate button ends at 1232px, and the window is 836px tall. There
 * is no scroll position where the person's name and the button that ends their employment are both
 * on screen. Hire is the same shape. A header that stays is the difference between reading a name
 * and remembering one.
 */
export function FocusScreen({
  task,
  who,
  actions,
  activeHref,
  children,
}: {
  /** What this screen does. The h1 — "Terminate employment", not the person's name. */
  task: ReactNode;
  /** Whose record it is, in one line. */
  who?: ReactNode;
  /** `[Cancel] [Primary]` in that order. Rendered top right and nowhere else. */
  actions?: ReactNode;
  /** The list this screen came from, so the menu goes on saying where you are. */
  activeHref: string;
  children: ReactNode;
}) {
  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref={activeHref} />
      <main className="min-w-0 flex-1">
        <header className="sticky top-0 z-10 border-b border-hairline bg-canvas px-8 py-4">
          <div className="mx-auto flex max-w-content flex-wrap items-start justify-between gap-4">
            <div className="min-w-0">
              <h1 className="text-xl font-semibold text-ink">{task}</h1>
              {who && <p className="mt-0.5 text-sm text-ink-secondary">{who}</p>}
            </div>
            {actions && <div className="flex flex-none gap-2">{actions}</div>}
          </div>
        </header>
        <div className="mx-auto grid max-w-content gap-6 px-8 pb-16 pt-6">{children}</div>
      </main>
    </div>
  );
}
