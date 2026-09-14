"use client";

import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ds/Button";

/**
 * The planner's one shape for "are you sure?" (D-27): a question, what pressing on will do, and two
 * buttons — the way back first, the act second.
 *
 * <p>A layer and not a line at the top of the form, for a reason the composer makes obvious: the form
 * is 2,700 pixels tall and its commit button sits in a sticky header. Somebody pressing *Update this
 * meal* from section 4 is nowhere near the top of the page, and a warning drawn there would be a
 * warning they never see. The layer comes to where they are.
 *
 * <p>Drawn like the kitchen and meal-kind dialogs already in the app — the same scrim, the same
 * `modal` card, `[Cancel] [Act]` at the foot — so the planner does not invent a third look for a
 * question. `alertdialog`, because it interrupts to ask something, and Escape answers with the way
 * back, never with the act.
 */
export function ConfirmLayer({
  title,
  children,
  confirmLabel,
  dismissLabel,
  danger = false,
  busy = false,
  onConfirm,
  onDismiss,
}: {
  title: string;
  /** What pressing on will do, in a sentence or two. */
  children?: ReactNode;
  confirmLabel: string;
  dismissLabel: string;
  /** The act cannot be taken back — cancelling a meal, throwing away what was typed. */
  danger?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onDismiss: () => void;
}) {
  const titleId = useId();
  const bodyId = useId();
  const card = useRef<HTMLDivElement>(null);

  // Focus lands on the way back — the first button — so an Enter pressed out of habit does not throw
  // anything away. Found in the card rather than by a ref on it, because `Button` does not forward one.
  useEffect(() => {
    card.current?.querySelector<HTMLButtonElement>("button")?.focus();
  }, []);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") onDismiss();
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onDismiss]);

  return (
    <div
      role="alertdialog"
      aria-modal="true"
      aria-labelledby={titleId}
      aria-describedby={children ? bodyId : undefined}
      className="fixed inset-0 z-[60] flex items-center justify-center bg-ink/40 px-4"
    >
      <div ref={card} className="modal w-full max-w-prose px-8 py-7">
        <h2 id={titleId} className={`text-lg ${danger ? "text-danger" : "text-ink"}`}>
          {title}
        </h2>
        {children && (
          <div id={bodyId} className="mt-2 grid gap-2 text-sm text-ink-secondary">
            {children}
          </div>
        )}
        <div className="mt-6 flex flex-wrap items-center justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onDismiss} disabled={busy}>
            {dismissLabel}
          </Button>
          <Button type="button" variant={danger ? "danger" : "primary"} onClick={onConfirm} busy={busy}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}

/**
 * "Leave without saving?" (D-27 answer 7), for a planner page holding changes nothing has saved.
 *
 * <p>Since D-27 nothing in the planner saves until *Save this meal* or *Update this meal* — a
 * volunteer shift asked for from the meal included. So walking away from a changed meal is the one
 * way to lose work, and, for a new meal, the one way to end up with nothing where the planner thought
 * they had asked for volunteers. It has to ask.
 *
 * <p>Two ways out are covered, and they need two mechanisms:
 *
 * <ul>
 *   <li><strong>Inside the app</strong> — the sidebar, the Cancel link in the header, any link on
 *       the page. The App Router has no event to veto a navigation, so this listens for a click on
 *       a same-origin link in the capture phase at the document, ahead of the root React listens at,
 *       and holds it. Leaving then goes through the router, so it is still a client navigation.</li>
 *   <li><strong>The browser's own</strong> — reload, closing the tab, typing an address. That is
 *       `beforeunload`, and the browser draws its own question there; no page may word it.</li>
 * </ul>
 *
 * <p>What it does not catch, said rather than discovered: the browser's Back button inside the app,
 * which the App Router handles as a history pop with no hook before it. Covering it would mean pushing
 * a fake history entry and replaying it, which fights the router's own history state.
 *
 * <p>A navigation the page makes itself — closing onto the day after a save — goes through
 * `router.push`, not a link click, so it is never held.
 */
export function useLeaveGuard(dirty: boolean): ReactNode {
  const router = useRouter();
  const [pending, setPending] = useState<string | null>(null);
  // Read inside the listeners rather than re-registering them on every change of heart.
  const armed = useRef(dirty);
  armed.current = dirty;

  useEffect(() => {
    if (!dirty) return;

    function onBeforeUnload(event: BeforeUnloadEvent) {
      if (!armed.current) return;
      event.preventDefault();
      // Some browsers still want this set before they will ask.
      event.returnValue = "";
    }

    function onClick(event: MouseEvent) {
      if (!armed.current || event.defaultPrevented || event.button !== 0) return;
      // A new tab or window leaves this page exactly where it is.
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const target = event.target instanceof Element ? event.target : null;
      const link = target?.closest("a[href]");
      if (!(link instanceof HTMLAnchorElement)) return;
      if (link.target && link.target !== "_self") return;
      if (link.hasAttribute("download")) return;
      const to = new URL(link.href, window.location.href);
      // Another site is the browser's to ask about, through beforeunload.
      if (to.origin !== window.location.origin) return;
      // A jump within the page goes nowhere.
      if (to.pathname === window.location.pathname && to.search === window.location.search) return;

      event.preventDefault();
      event.stopPropagation();
      setPending(to.pathname + to.search + to.hash);
    }

    window.addEventListener("beforeunload", onBeforeUnload);
    document.addEventListener("click", onClick, true);
    return () => {
      window.removeEventListener("beforeunload", onBeforeUnload);
      document.removeEventListener("click", onClick, true);
    };
  }, [dirty]);

  const stay = useCallback(() => setPending(null), []);

  if (!pending) return null;
  return (
    <ConfirmLayer
      title="Leave without saving?"
      confirmLabel="Leave without saving"
      dismissLabel="Stay on this page"
      danger
      onDismiss={stay}
      onConfirm={() => {
        const to = pending;
        armed.current = false;
        setPending(null);
        router.push(to);
      }}
    >
      <p>Nothing you changed here has been saved, including any volunteer shift.</p>
    </ConfirmLayer>
  );
}
