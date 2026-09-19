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
        {/* Opaque, and `sunken` rather than `canvas`. §6's rule for anything sticky is that it has to
            be opaque or it smears what scrolls under it — and `canvas` is only the page's *flat*
            colour, so over a `canvas-bg` gradient or bloom it read as an almost-matching patch
            rather than as a band. `sunken` is the recessed band §3.1 describes, and it is the
            background §6 names for this case. */}
        {/* Below lg the shell has its own sticky bar (56px, the drawer's Menu button) at the top of
            the viewport, so this header sticks just under it rather than sliding beneath it: at 390
            with `top-0` the task's title and Cancel sat under the Menu bar on every form (T-232).

            On a phone the header is compacted, because it rides along over the form for the whole
            scroll and at 145px it took a sixth of a 390×844 screen. Tighter padding, a smaller
            title, and the buttons one size of text and padding smaller so three fit on one row —
            never shorter than the 44px touch height. The "whose record" line stays in the band on a
            phone too: on Terminate it is the person's name, and keeping it beside the button is
            the reason this header is sticky at all (see "Why sticky" above). That is what holds the
            phone header at about 115px rather than under 100.

            The actions wrap rather than refuse to: three or four buttons (the message composer has
            four) otherwise pushed the page sideways, to 610px on a 390 phone.
            The side gutter matches the list screens and the menu bar: 16px on a phone, 32 above. */}
        <header className="sticky top-14 z-10 border-b border-hairline bg-sunken px-4 py-2 sm:px-8 sm:py-4 lg:top-0">
          <div className="mx-auto flex max-w-content flex-wrap items-start justify-between gap-x-4 gap-y-2 sm:gap-4">
            <div className="min-w-0">
              <h1 className="text-lg font-semibold leading-6 text-ink sm:text-xl">{task}</h1>
              {who && <p className="mt-0.5 text-sm text-ink-secondary">{who}</p>}
            </div>
            {actions && (
              <div className="flex flex-wrap gap-2 max-sm:[&_.btn]:px-3 max-sm:[&_.btn]:text-sm">{actions}</div>
            )}
          </div>
        </header>
        <div className="mx-auto grid max-w-content gap-6 px-4 pb-16 pt-6 sm:px-8">{children}</div>
      </main>
    </div>
  );
}
