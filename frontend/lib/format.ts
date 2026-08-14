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

/** Today in the browser's own timezone, as "YYYY-MM-DD". */
export function todayIso(): string {
  const d = new Date();
  return [
    d.getFullYear(),
    String(d.getMonth() + 1).padStart(2, "0"),
    String(d.getDate()).padStart(2, "0"),
  ].join("-");
}
