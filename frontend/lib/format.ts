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
/**
 * "Saturday, 15 August" — a day the way it is said aloud in a kitchen. No year: the planner and the
 * Today screen are always about the days around now, and the year on every heading is noise.
 */
export function longDay(iso: string): string {
  const d = new Date(`${iso}T00:00:00`);
  const weekday = d.toLocaleDateString("en-GB", { weekday: "long" });
  const rest = d.toLocaleDateString("en-GB", { day: "numeric", month: "long" });
  return `${weekday}, ${rest}`;
}

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

/**
 * An amount in the temple's own currency (B8) — "₹18,000.00", or whatever its currency's symbol is.
 *
 * <p>The currency comes from the temple rather than from a hard-coded rupee sign, so a screen
 * written now needs no edit if a temple outside India is ever taken on. The reader's own locale
 * decides the grouping and the symbol's placement, which is the one part of this that belongs to
 * whoever is looking at the screen rather than to the temple.
 *
 * <p>Null is "—" and never "₹0". Money we have no figure for and money that is genuinely nothing are
 * different facts, and the screens that show a salary depend on the difference.
 */
export function money(amount: number | null | undefined, currency: string): string {
  if (amount === null || amount === undefined) return "—";
  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency,
    // Paise are shown only when there are any: a salary of 18,000 reads better than 18,000.00,
    // and a settlement of 18,432.50 must not be rounded away.
    minimumFractionDigits: Number.isInteger(amount) ? 0 : 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

const UNIT_LABEL: Record<string, string> = { KG: "Kg", GM: "gm", L: "L", ML: "ml", PIECES: "pieces" };

/**
 * How a quantity is actually said.
 *
 * <p>Stock is held in the ingredient's own unit, and for something kept in millilitres that means
 * the store room reads "173542 ml of ghee". Nobody says that. Where a unit has a bigger sibling and
 * the number has grown past it, the number is promoted for display only — the stored value never
 * changes, which is the whole reason the unit families carry a factor.
 */
export function quantity(value: number | null | undefined, unit: string): string {
  // A quantity nobody has is not a zero — an em dash says "no answer" where 0 would say "none left".
  if (value == null || !Number.isFinite(value)) {
    return "—";
  }
  const promote: Record<string, { to: string; per: number }> = {
    GM: { to: "KG", per: 1000 },
    ML: { to: "L", per: 1000 },
  };
  const bigger = promote[unit];
  if (bigger && Math.abs(value) >= bigger.per) {
    const promoted = value / bigger.per;
    // Two decimals at most, and no trailing zeros: 173.54 L, 5 Kg, 1.5 L.
    return `${Number(promoted.toFixed(2)).toLocaleString("en-IN")} ${UNIT_LABEL[bigger.to]}`;
  }
  return `${Number(value.toLocaleString("en-IN", { maximumFractionDigits: 3 }).replace(/,/g, "")).toLocaleString("en-IN")} ${UNIT_LABEL[unit] ?? unit}`;
}

/**
 * What to call a date that has already gone.
 *
 * <p>The server flags anything at or inside the expiry horizon as "expiring soon", which is right
 * for a sack that goes off on Friday and wrong for one that went off last week — and last week is
 * the case that actually matters, because it is already in the store being cooked from. The stored
 * flag says "act on this"; only the date can say which of the two it is.
 */
export function expiryWord(expiry: string | null | undefined, today = todayIso()): "expired" | "soon" {
  return expiry != null && expiry < today ? "expired" : "soon";
}
