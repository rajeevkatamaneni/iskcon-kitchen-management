/**
 * The idle session clock (E1-S16).
 *
 * <p>A temple kitchen runs on shared devices — a tablet by the store room, a phone passed between
 * cooks. Whoever signed in first would otherwise stay signed in, and everything the next person
 * does is recorded as them. This signs an idle person out so the device does not carry them.
 *
 * <p><b>It is a courtesy, not the security boundary, and the server enforces no idle limit at
 * all.</b> E1-S16's D7 says so on purpose. Spring is {@code STATELESS}: the only per-request checks
 * are the Firebase token's signature and expiry, and that {@code users.status} is still active
 * (E1-S4). A Firebase ID token lives an hour, but the SDK refreshes it silently and indefinitely
 * from a long-lived refresh token — so token expiry is not a bound on session length, and it was a
 * mistake to have written here that it was. Everything that ends an idle session ends it from this
 * file.
 *
 * <p>The threat this answers is physical: a tablet left on a counter in a temple kitchen, picked up
 * by whoever walks past. Recorded so it is not re-argued (Q9, 2026-08-21): somebody who takes the
 * device <em>and</em> can read the browser profile still holds a valid refresh token, and no
 * client-side timeout has ever prevented that. Device passcodes do. If that ever needs answering,
 * the step is re-authentication before money moves — a later build, deliberately not this one.
 *
 * <p>Everything here is deliberately plain values and pure functions, so the rules can be tested
 * without a clock, a browser or a rendered tree.
 */

/** Sixty minutes of inactivity ends the session. One constant — a temple wanting a different figure changes it here. */
export const IDLE_LIMIT_MS = 60 * 60 * 1000;

/** The warning appears a minute before, so nobody loses a half-typed delivery mid-sentence. */
export const WARN_BEFORE_MS = 60 * 1000;

/** How often the clock is checked. Cheap: it reads one number and compares it. */
export const CHECK_EVERY_MS = 5 * 1000;

/**
 * Written to `localStorage` so every tab of the browser shares one clock: working in one tab must
 * not get you signed out because another sat idle.
 */
export const ACTIVITY_KEY = "kms.lastActivity";

/** Why the person is looking at the sign-in screen. Set on an automatic sign-out, read once, cleared. */
export const SIGNED_OUT_REASON_KEY = "kms.signedOutReason";

/**
 * A stale reason must not explain today's sign-in with last week's timeout, so the note expires.
 * Long enough to survive a slow redirect and a page load.
 */
export const REASON_VALID_FOR_MS = 5 * 60 * 1000;

export type IdleState = "active" | "warning" | "expired";

/** Where the session stands, given the last time the person actually did something. */
export function idleState(now: number, lastActivity: number): IdleState {
  const idleFor = now - lastActivity;
  if (idleFor >= IDLE_LIMIT_MS) return "expired";
  if (idleFor >= IDLE_LIMIT_MS - WARN_BEFORE_MS) return "warning";
  return "active";
}

/** Whole seconds left before the sign-out, for the countdown in the warning. Never negative. */
export function secondsUntilSignOut(now: number, lastActivity: number): number {
  return Math.max(0, Math.ceil((lastActivity + IDLE_LIMIT_MS - now) / 1000));
}

/**
 * The events that count as activity.
 *
 * <p>Intentional input only — a press, a key, a touch, a scroll. Deliberately not `mousemove`: a
 * nudged desk or a passing sleeve would hold a session open in an empty room, which is the exact
 * situation this exists to close. Nor is network traffic counted, so a screen polling on a timer
 * cannot keep the device signed in by itself.
 */
export const ACTIVITY_EVENTS = ["pointerdown", "keydown", "touchstart", "wheel"] as const;

/**
 * The moments that are not activity but are a reason to look at the clock.
 *
 * <p>A sleeping laptop suspends `setInterval` with the lid, so on waking there is a window in which
 * no tick has run and the first thing in it is the returning person's click. These three events fire
 * before that click, so the clock is judged the instant the tab is shown rather than up to
 * {@link CHECK_EVERY_MS} later. They live on two different targets — `visibilitychange` on the
 * document, `focus` and `pageshow` on the window — so the guard binds them one at a time rather than
 * looping this list.
 */
export const PRESENCE_EVENTS = ["visibilitychange", "focus", "pageshow"] as const;

/** Records that something happened. Tolerates storage being unavailable — private mode, quota, an embedded browser. */
export function recordActivity(at: number): void {
  try {
    window.localStorage.setItem(ACTIVITY_KEY, String(at));
  } catch {
    // A device that cannot share the clock between tabs still gets a per-tab one from the caller.
  }
}

/**
 * The last activity any tab saw. Falls back to the given moment when there is nothing readable —
 * a fresh sign-in, or storage the browser refuses — which starts the clock rather than expiring it.
 */
export function lastActivityAt(fallback: number): number {
  try {
    const raw = window.localStorage.getItem(ACTIVITY_KEY);
    const parsed = raw === null ? NaN : Number(raw);
    return Number.isFinite(parsed) ? parsed : fallback;
  } catch {
    return fallback;
  }
}

/** Leaves a note for the sign-in screen: this was automatic, not something you did. */
export function noteAutomaticSignOut(at: number): void {
  try {
    window.localStorage.setItem(SIGNED_OUT_REASON_KEY, JSON.stringify({ reason: "idle", at }));
  } catch {
    // Without the note the sign-in screen simply says nothing, which is the pre-existing behaviour.
  }
}

/**
 * Reads that note and clears it, so it explains one sign-in and not the next. Returns false for
 * anything stale, unparseable or absent.
 */
export function takeAutomaticSignOutNote(now: number): boolean {
  try {
    const raw = window.localStorage.getItem(SIGNED_OUT_REASON_KEY);
    if (raw === null) return false;
    window.localStorage.removeItem(SIGNED_OUT_REASON_KEY);

    const note = JSON.parse(raw) as { reason?: string; at?: number };
    return note.reason === "idle" && typeof note.at === "number" && now - note.at < REASON_VALID_FOR_MS;
  } catch {
    return false;
  }
}
