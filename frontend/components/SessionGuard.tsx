"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ds/Button";
import { useAuth } from "@/lib/auth-context";
import {
  ACTIVITY_EVENTS,
  CHECK_EVERY_MS,
  idleState,
  PRESENCE_EVENTS,
  lastActivityAt,
  noteAutomaticSignOut,
  recordActivity,
  secondsUntilSignOut,
} from "@/lib/session-timeout";

// The three moments a tab comes back to a person. They sit on two different targets, so they are
// named here rather than looped over: `visibilitychange` is the document's, the other two the
// window's.
const [TAB_SHOWN, WINDOW_FOCUSED, PAGE_RESTORED] = PRESENCE_EVENTS;

/**
 * Signs an idle person out, after warning them (E1-S16).
 *
 * <p>Mounted once, above every screen, so the rule holds everywhere rather than on the pages
 * somebody remembered. It does nothing at all until someone is actually signed in.
 *
 * <p>The clock lives in `localStorage`, so all tabs of the browser agree: working in one does not
 * get you signed out because another sat idle. Signing out propagates on its own — Firebase
 * broadcasts auth state between tabs, so the other tabs land on the sign-in screen by themselves.
 *
 * <p><b>The order matters, and getting it wrong is what made this rule useless for its first
 * release.</b> Activity used to stamp the clock fresh without looking at it, and the expiry check
 * ran only on a five-second interval. `pointerdown` fires as the mouse goes down, before the next
 * tick and before the click has navigated, so the very first act of returning to a device renewed a
 * session that had been idle for hours. A sleeping laptop made it certain rather than likely: the
 * operating system suspends `setInterval` with the lid, so on waking there is a window with no tick
 * in it at all, and the user's click is the first thing in that window. The rule therefore fired
 * only on a tab left visibly open and untouched — the one case where somebody is standing there to
 * see it — and never in the case it exists for, a device picked up later.
 *
 * <p>So every path now decides before it records. A press is judged, not obeyed; the listeners sit
 * in the <b>capture</b> phase so nothing in the app acts on the press first; and the clock is read
 * again on {@link PRESENCE_EVENTS}, which reach a woken tab before any click does. The interval
 * stays as belt and braces.
 */
export function SessionGuard() {
  const { status, signOut } = useAuth();
  const signedIn = status === "signed-in";

  const [warning, setWarning] = useState<number | null>(null);
  // Signing out is asynchronous; without this a slow sign-out gets asked for on every tick.
  const signingOut = useRef(false);

  /** Ends the session once, leaving the note that lets the sign-in screen explain why. */
  const signOutIdle = useCallback(
    (now: number) => {
      if (signingOut.current) return;
      signingOut.current = true;
      noteAutomaticSignOut(now);
      void signOut();
    },
    [signOut],
  );

  /**
   * Reads the shared clock and acts on what it says. Called on the interval, and on every moment a
   * tab comes back into view, so an expiry that happened while the timer was suspended is caught the
   * instant anybody could act on it.
   */
  const evaluate = useCallback(() => {
    const now = Date.now();
    const since = lastActivityAt(now);

    switch (idleState(now, since)) {
      case "expired":
        signOutIdle(now);
        break;
      case "warning":
        setWarning(secondsUntilSignOut(now, since));
        break;
      default:
        setWarning(null);
    }
  }, [signOutIdle]);

  /**
   * What a press, a key, a touch or a scroll means. It decides before it records: a session that has
   * already run out is ended rather than renewed, so the click that wakes a device is judged and not
   * obeyed. Also the handler behind "Stay signed in", where the same rule holds — if the countdown
   * reached zero before the person reached the button, the session is over.
   */
  const markActive = useCallback(() => {
    const now = Date.now();
    if (idleState(now, lastActivityAt(now)) === "expired") {
      signOutIdle(now);
      return;
    }
    recordActivity(now);
    setWarning(null);
  }, [signOutIdle]);

  useEffect(() => {
    if (!signedIn) {
      setWarning(null);
      return;
    }

    // Signing in is itself activity: start the clock rather than inherit an old tab's.
    signingOut.current = false;
    recordActivity(Date.now());

    // Capture, so this runs before whatever the press was aimed at. A menu item that navigates on
    // the same press must not carry an expired session to the next screen.
    for (const event of ACTIVITY_EVENTS) {
      window.addEventListener(event, markActive, { capture: true, passive: true });
    }
    document.addEventListener(TAB_SHOWN, evaluate);
    window.addEventListener(WINDOW_FOCUSED, evaluate);
    window.addEventListener(PAGE_RESTORED, evaluate);

    const timer = window.setInterval(evaluate, CHECK_EVERY_MS);

    return () => {
      window.clearInterval(timer);
      for (const event of ACTIVITY_EVENTS) {
        window.removeEventListener(event, markActive, { capture: true });
      }
      document.removeEventListener(TAB_SHOWN, evaluate);
      window.removeEventListener(WINDOW_FOCUSED, evaluate);
      window.removeEventListener(PAGE_RESTORED, evaluate);
    };
  }, [signedIn, markActive, evaluate]);

  if (!signedIn || warning === null) return null;

  return (
    <div
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="idle-title"
      className="fixed inset-0 z-50 grid place-items-center bg-ink/40 p-6"
    >
      <div className="grid w-full max-w-sm gap-4 rounded-lg bg-canvas p-6 shadow-lg">
        <h2 id="idle-title" className="text-lg font-medium text-ink">
          Still there?
        </h2>
        <p className="text-ink-secondary">
          You’ve been inactive, so we’ll sign you out in{" "}
          <span className="font-medium text-ink">
            {warning} second{warning === 1 ? "" : "s"}
          </span>
          .
        </p>
        <div className="flex flex-wrap gap-3">
          <Button onClick={markActive}>Stay signed in</Button>
          <Button variant="ghost" onClick={() => void signOut()}>
            Sign out now
          </Button>
        </div>
      </div>
    </div>
  );
}
