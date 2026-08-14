"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ds/Button";
import { useAuth } from "@/lib/auth-context";
import {
  ACTIVITY_EVENTS,
  CHECK_EVERY_MS,
  idleState,
  lastActivityAt,
  noteAutomaticSignOut,
  recordActivity,
  secondsUntilSignOut,
} from "@/lib/session-timeout";

/**
 * Signs an idle person out, after warning them (E1-S16).
 *
 * <p>Mounted once, above every screen, so the rule holds everywhere rather than on the pages
 * somebody remembered. It does nothing at all until someone is actually signed in.
 *
 * <p>The clock lives in `localStorage`, so all tabs of the browser agree: working in one does not
 * get you signed out because another sat idle. Signing out propagates on its own — Firebase
 * broadcasts auth state between tabs, so the other tabs land on the sign-in screen by themselves.
 */
export function SessionGuard() {
  const { status, signOut } = useAuth();
  const signedIn = status === "signed-in";

  const [warning, setWarning] = useState<number | null>(null);
  // Signing out is asynchronous; without this a slow sign-out gets asked for on every tick.
  const signingOut = useRef(false);

  const markActive = useCallback(() => {
    recordActivity(Date.now());
    setWarning(null);
  }, []);

  useEffect(() => {
    if (!signedIn) {
      setWarning(null);
      return;
    }

    // Signing in is itself activity: start the clock rather than inherit an old tab's.
    signingOut.current = false;
    recordActivity(Date.now());

    for (const event of ACTIVITY_EVENTS) {
      window.addEventListener(event, markActive, { passive: true });
    }

    const timer = window.setInterval(() => {
      const now = Date.now();
      const since = lastActivityAt(now);

      switch (idleState(now, since)) {
        case "expired":
          if (!signingOut.current) {
            signingOut.current = true;
            noteAutomaticSignOut(now);
            void signOut();
          }
          break;
        case "warning":
          setWarning(secondsUntilSignOut(now, since));
          break;
        default:
          setWarning(null);
      }
    }, CHECK_EVERY_MS);

    return () => {
      window.clearInterval(timer);
      for (const event of ACTIVITY_EVENTS) {
        window.removeEventListener(event, markActive);
      }
    };
  }, [signedIn, markActive, signOut]);

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
          You&rsquo;ve been inactive for a while, so we&rsquo;ll sign you out in{" "}
          <span className="font-medium text-ink">
            {warning} second{warning === 1 ? "" : "s"}
          </span>{" "}
          to keep this device safe for the next person.
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
