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

/**
 * Every date this application writes is written the Indian way — "1 Sep 2026", never "Sep 1, 2026"
 * — whoever is reading it. The locale is pinned rather than left to the browser because the temple
 * is the subject: a screen that reformats itself for a reader in California is describing the same
 * Thursday in a way the people running the kitchen do not use, and two screens then disagree on one
 * machine (this file already pinned `longDay` and left its neighbours to drift).
 */
export function longDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-GB", {
    weekday: "long",
    day: "numeric",
    month: "long",
    year: "numeric",
  });
}

/** "2026-08-14" → "14 Aug", for tight spaces. */
export function shortDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-GB", {
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
 * An amount in the temple's own currency (B8) — "₹18,000", or whatever its currency's symbol is.
 *
 * <p>The currency comes from the temple rather than from a hard-coded rupee sign, so a screen
 * written now needs no edit if a temple outside India is ever taken on.
 *
 * <p>The **grouping** is not the reader's, and that is the same decision the dates in this file
 * made. Left to the browser, eleven lakh fifty thousand renders as "₹1,150,000" on an en-US
 * machine while the wish list, which pinned `en-IN`, printed "₹11,50,000" one screen away: two
 * ways of saying the same rupees, on one machine, a click apart. Money is grouped in lakhs and
 * crores because that is how the people spending it say the number out loud, and a screen that
 * reformats itself for somebody in California is describing the temple's money in a way the temple
 * does not use.
 *
 * <p>Null is "—" and never "₹0". Money we have no figure for and money that is genuinely nothing are
 * different facts, and the screens that show a salary depend on the difference.
 */
export function money(amount: number | null | undefined, currency: string): string {
  if (amount === null || amount === undefined) return "—";
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency,
    // Paise are shown only when there are any: a salary of 18,000 reads better than 18,000.00,
    // and a settlement of 18,432.50 must not be rounded away.
    minimumFractionDigits: Number.isInteger(amount) ? 0 : 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

/**
 * How a unit is written where a person reads it. The one copy — there used to be eight, and the one
 * in RecipePeek had drifted to lowercase "kg" while this one said "Kg".
 */
export const UNIT_LABEL: Record<string, string> = {
  KG: "Kg",
  GM: "gm",
  L: "L",
  ML: "ml",
  PIECES: "pieces",
};

/**
 * How a unit is written, or nothing at all where there is no unit to name.
 *
 * <p>The label on its own, for the places a quantity is not being said: a column that names an
 * ingredient's unit, and the adornment beside a box somebody types into. That second case is the
 * reason this exists next to {@link quantity} rather than being replaced by it — a readout may be
 * promoted from 0.6 Kg to 600 gm, but the box beside it still submits kilograms, and labelling it
 * "gm" would invite a thousandfold error.
 */
export function unitLabel(unit: string | null | undefined): string {
  if (!unit) return "";
  return UNIT_LABEL[unit] ?? unit.toLowerCase();
}

/**
 * The one vocabulary (E11-S2), and the part of it that can be true in each place.
 *
 * <p>An ingredient, a stock level, a donation and a purchase-order line are all quantities of food,
 * so they take the five physical units. Everything in this application measures food.
 *
 * <p>`SERVINGS` was briefly a sixth, admitted on a recipe's yield and on a dish. It is gone (V80):
 * it counts the people fed rather than the food made, so "Kheer · 40 servings" told an approver
 * nothing about how much kheer and a storekeeper reading it off a work order even less. The idea
 * survives where it belongs — the planner asks for adults, children and seniors and shows a rough
 * plate count — as a head count on a screen, never a unit anybody picks.
 */
export const FOOD_UNITS: readonly string[] = ["KG", "GM", "L", "ML", "PIECES"];

/**
 * What a recipe's yield may be measured in — the same five, because a yield is an amount of food.
 *
 * <p>Kept as its own name rather than folded into {@link FOOD_UNITS} at every call site: the two
 * meant different things until 2026-08-31 and the places that ask for a yield are still asking a
 * different question from the places that ask for a quantity.
 */
export const YIELD_UNITS: readonly string[] = FOOD_UNITS;

/** How many base-family units one of each unit is. Mirrors Unit.baseFactor() and to_base_qty(). */
const BASE_FACTOR: Record<string, number> = { KG: 1000, GM: 1, L: 1000, ML: 1, PIECES: 1 };

/** The bigger and smaller unit of each family. A count has neither. */
const FAMILY: Record<string, { large: string; small: string }> = {
  KG: { large: "KG", small: "GM" },
  GM: { large: "KG", small: "GM" },
  L: { large: "L", small: "ML" },
  ML: { large: "L", small: "ML" },
};

/**
 * A quantity rounded the way a person rounds it — to a step that grows with the size of the number.
 *
 * <p>Nobody weighs 134.4 gm of cardamom; they weigh 135. Nobody measures 10.08 Kg of rice; they
 * measure 10. But 4.7 gm of camphor is not 5 — at that size half a gram is the honest step. So the
 * step is not fixed, it climbs: tenths below one, halves to ten, ones to a hundred, fives to a
 * thousand, tens above.
 *
 * <p>Bounded by the step and never compounding, because this runs once, last, on a value that has
 * already been through every calculation it is going to. Round then compute and the errors stack;
 * compute then round and they cannot.
 */
function roundAsAPersonWould(value: number): number {
  const size = Math.abs(value);
  const step = size < 1 ? 0.1 : size < 10 ? 0.5 : size < 100 ? 1 : size < 1000 ? 5 : 10;
  // toFixed mops up the float dust a division and multiplication leave behind: without it
  // 0.3 comes back as 0.30000000000000004.
  return Number((Math.round(value / step) * step).toFixed(3));
}

function say(value: number, unit: string, maxDecimals: number): string {
  return `${value.toLocaleString("en-IN", { maximumFractionDigits: maxDecimals })} ${UNIT_LABEL[unit] ?? unit}`;
}

/**
 * A quantity, in whichever unit of its family a person would actually say it in.
 *
 * <p>Stock is held in the ingredient's own unit, and for something kept in millilitres that means
 * the store room reads "173542 ml of ghee". Nobody says that. Equally, a scaled recipe asking for
 * 0.6 Kg of rice is asking for 600 grams, and writing it the first way makes a cook do arithmetic
 * over a hot stove. So the number is converted to its family's base and then shown in the large
 * unit from 1000 up and the small unit below — in both directions, which is the half that was
 * missing. The stored value never changes.
 *
 * <p>This is the **ledger** form: the figure is exact. Use it wherever somebody reconciles or is
 * audited against the number — a stock balance, a movement row, a batch, a goods receipt, an
 * invoice line. Rounding those would stop the rows visibly adding up to the balance above them,
 * which is the one thing the inventory screen exists to show (E3-S1).
 *
 * <p>For anything somebody weighs or buys against, use {@link cooksQuantity}.
 */
export function quantity(value: number | null | undefined, unit: string): string {
  return render(value, unit, false);
}

/**
 * The same quantity, rounded the way a cook would round it — see {@link roundAsAPersonWould}.
 *
 * <p>The **cook's** form. Use it wherever the number is something a person acts on with their
 * hands: a recipe line, a scaled recipe, a planner target, a job card, a work order, a shopping list,
 * a shortfall. 10.08 Kg and 10 Kg are the same sack of rice, and the second is the one to print.
 */
export function cooksQuantity(value: number | null | undefined, unit: string): string {
  return render(value, unit, true);
}

function render(value: number | null | undefined, unit: string, forCooking: boolean): string {
  // A quantity nobody has is not a zero — an em dash says "no answer" where 0 would say "none left".
  if (value == null || !Number.isFinite(value)) {
    return "—";
  }

  const code = (unit ?? "").toUpperCase();
  const family = FAMILY[code];

  // Pieces are whole things counted in themselves — three idlis is three idlis, with no larger
  // sibling to be promoted into.
  if (!family) {
    return say(forCooking ? Math.round(value) : value, code, 3);
  }

  const inBase = value * (BASE_FACTOR[code] ?? 1);
  let display = Math.abs(inBase) >= 1000 ? family.large : family.small;
  let shown = inBase / BASE_FACTOR[display];

  if (forCooking) {
    shown = roundAsAPersonWould(shown);
    // Rounding can carry a figure up over the line it was just measured against: 999.6 gm rounds
    // to 1000 gm, which is a kilo and should say so.
    if (display === family.small && Math.abs(shown) >= 1000) {
      display = family.large;
      shown = shown / 1000;
    }
  }

  return say(shown, display, forCooking ? 2 : 3);
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


/**
 * The warning on a vendor whose contract is running out — "Contract ended 12 Mar 2026",
 * "Contract ends in 5 days".
 *
 * <p>Written the same way as an expiring batch: the server decides *whether* this is worth saying,
 * using the temple's own contract horizon (Settings → Warnings, thirty days unless it has been
 * changed), and this decides only the words. That way the number lives in exactly one place, and a
 * screen cannot quietly disagree with the flag it was handed.
 *
 * <p>It warns and nothing more. A vendor past their contract end date is still active, still in
 * every picker, and still the preferred source for whatever they supply — a date somebody set months
 * ago should never be what silently changes tomorrow's shopping.
 */
export function contractWarning(contractEndDate: string, today = todayIso()): string {
  const days = wholeDaysBetween(today, contractEndDate);
  if (days < 0) return `Contract ended ${dateWithYear(contractEndDate)}`;
  if (days === 0) return "Contract ends today";
  return `Contract ends in ${days} ${days === 1 ? "day" : "days"}`;
}

/**
 * The notice a vendor is assumed to need, in days.
 *
 * <p>The same figure the shopping list plans with — `ShoppingListService.LEAD_BUFFER_DAYS` — which
 * subtracts it from the first meal that needs an ingredient to get the date it suggests. Held on
 * both sides for the same reason `TEMPLE_TIME_ZONE` is: it is a fact about how the temple works,
 * and this side has to be able to say something about a date *before* it is submitted.
 */
export const LEAD_BUFFER_DAYS = 2;

/**
 * What to say about a needed-by date that leaves a vendor little or no notice — or none at all.
 *
 * <p>It warns and never refuses, and the difference is the point. The buffer is a planning default,
 * not a statement about what a supplier can do: a temple that genuinely needs a sack of rice
 * tomorrow should be able to ask for it tomorrow, and a rule that made that impossible would only
 * teach people to write a date they do not mean. Only a date behind the order itself is refused,
 * and that is refused on the server, because it is not a request anybody can act on.
 *
 * <p>Null when there is nothing to say, so a caller renders the ordinary hint instead.
 */
export function leadTimeWarning(neededBy: string, today = todayIso()): string | null {
  const days = wholeDaysBetween(today, neededBy);
  if (days < 0) return "That day has already gone";
  if (days < LEAD_BUFFER_DAYS) return `Sooner than the ${LEAD_BUFFER_DAYS} days a vendor usually gets`;
  return null;
}

/**
 * "2026-03-12" → "12 Mar 2026". Short enough for a badge, and carrying the year, which
 * {@link shortDate} leaves off — a contract that ended is often one that ended a year ago.
 */
export function dateWithYear(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

/**
 * An instant off the server — "20 Aug 2026, 14:30" — in the temple's own clock.
 *
 * <p>The one moment formatter. Four screens each carried a private copy of this and a fifth was
 * borrowing the platform board's, which spelled the month out in full and put the word "at" in the
 * middle: "1 September 2026 at 11:09" sitting a column away from "1 Sept 2026". A date is written
 * one way in this application, so a moment is that same date with a clock on the end of it.
 *
 * <p>The clock is the temple's and not the reader's, for the reason {@link todayIso} gives: an
 * entry made at 09:20 in Bengaluru reads as the previous evening to anybody looking from further
 * west, which puts it on the wrong day and out of order against everything else the temple dates.
 * Seconds are left off, as everywhere else (INT-8) — a note was written at 18:08, not at 18:08:41.
 */
export function moment(iso: string): string {
  return new Date(iso).toLocaleString("en-GB", {
    timeZone: TEMPLE_TIME_ZONE,
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/**
 * The same instant reduced to the day it happened on in the temple — "20 Aug 2026", written like
 * {@link dateWithYear}, because it is one.
 *
 * <p>Deliberately not `iso.slice(0, 10)`. An `Instant` is UTC, so the first ten characters are the
 * UTC day: everything between midnight and 05:30 IST lands on the date before. This codebase has
 * warned about that fault in three separate comments and fixed it twice, which is reason enough for
 * it to exist in one place where it can only be got right once.
 */
export function templeDay(iso: string): string {
  return new Date(iso).toLocaleDateString("en-GB", {
    timeZone: TEMPLE_TIME_ZONE,
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

/** Calendar days from one "YYYY-MM-DD" to another, counted in UTC so no time zone can shift one. */
function wholeDaysBetween(fromIso: string, toIso: string): number {
  const day = 24 * 60 * 60 * 1000;
  return Math.round((Date.parse(`${toIso}T00:00:00Z`) - Date.parse(`${fromIso}T00:00:00Z`)) / day);
}
