/**
 * Small shared formatters.
 *
 * <p>Times arrive from the API as "HH:mm:ss". Seconds are noise on a screen — a meal is due at 12:00,
 * not 12:00:00 — so nothing renders them (INT-8).
 */

/** "12:00:00" or "12:00" → "12:00". Anything unparseable comes back as it arrived. */
export function hhmm(time: string | null | undefined): string {
  if (!time) return "—";
  const m = /^(\d{1,2}):(\d{2})/.exec(time);
  return m ? `${m[1].padStart(2, "0")}:${m[2]}` : time;
}

/** "2026-08-14" → "Friday, 14 August 2026", in the reader's own locale. */
export function longDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, {
    weekday: "long",
    day: "numeric",
    month: "long",
    year: "numeric",
  });
}

/** "2026-08-14" → "14 Aug", for tight spaces. */
export function shortDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, {
    day: "numeric",
    month: "short",
  });
}

/**
 * The temple's today, as "YYYY-MM-DD".
 *
 * <p>Deliberately not the browser's. Every date the server works in — a meal plan, a shift, the
 * Today screen — is the temple's own day in India, so a screen reading the device clock disagrees
 * with the server for anyone testing or travelling outside IST: the planner would mark one day as
 * today while Today called it another. The kitchen's day is the operational day.
 */
export const TEMPLE_TIME_ZONE = "Asia/Kolkata";

export function todayIso(): string {
  // en-CA renders as "YYYY-MM-DD", which is the format the API speaks.
  return new Intl.DateTimeFormat("en-CA", { timeZone: TEMPLE_TIME_ZONE }).format(new Date());
}
