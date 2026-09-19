import type { CalendarDayView } from "./api";

/**
 * Reading a calendar day the way a kitchen reads it (E4-S9).
 *
 * <p>The engine answers in astronomy — a tithi number, a fast code, a list of festival texts. What a
 * cook needs to know is narrower and more useful: is today a fast, is it a feast, and what does that
 * do to the menu. This is that translation, kept apart from the screens so both the calendar and the
 * planner say the same thing about the same day.
 *
 * <p>Ported from the ISKCON Kitchen Design System's `CalendarScreen`, which is where the kitchen
 * note and the day colouring were designed.
 */

export type DayKind = "ekadasi" | "fast" | "festival" | null;

/**
 * A feast the kitchen cooks differently for, as against a day it merely notes.
 *
 * <p>The engine numbers festivals by GCAL's own classes — 100 for the most prominent, rising to 600
 * for the least. The appearance and disappearance days of the acaryas sit at the quiet end: they
 * are read out at the morning programme, but they do not change a single pot. Colouring them as
 * feasts, which is what a looser threshold did, made half of August look like a festival month.
 */
const FEAST_PRIORITY = 200;

function isFeast(priority: number): boolean {
  return priority <= FEAST_PRIORITY;
}

/**
 * The engine writes "Sri Vamsidasa Babaji -- Disappearance", GCAL's own separator. Nobody reading a
 * calendar should see a double hyphen (INT-6).
 */
function clean(text: string): string {
  return ekadashiSpelling(text.replace(/\s+--\s+/g, " — ")).trim();
}

/**
 * "Ekadashi", never "Ekadasi", in anything a person reads (Rajeev, 2026-09-18).
 *
 * <p>The calendar engine is a port of GCAL and stores GCAL's own transliteration — "Pavitraropana
 * Ekadasi", "(Fasting for Ekadasi)" — while every screen the kitchen wrote says "Ekadashi". Two
 * spellings of the one day the planner is built around read as two different things. The stored
 * names are left exactly as the engine wrote them, so a regenerated year and an old one still match
 * row for row; the spelling is settled here, on the way to the screen, in the one function every
 * calendar name passes through. The server does the same for the names it composes into Today and
 * the job card, so the two never disagree. Internal identifiers (`"ekadasi"`, `EKADASI`) are not
 * words anybody reads and stay as they are.
 */
export function ekadashiSpelling(text: string): string {
  return text.replace(/\b([Ee])kadasi/g, "$1kadashi");
}

export interface DayEvent {
  kind: "ekadasi" | "fast" | "festival" | "observance";
  label: string;
  note?: string;
}

/**
 * What kind of day this is, for colour and for sorting the kitchen's attention. Ekadashi outranks a
 * festival: when a feast falls on a fast, the fast is what changes the cooking.
 */
export function dayKind(day: CalendarDayView | undefined): DayKind {
  if (!day) return null;
  if (day.isEkadashi) return "ekadasi";
  if (day.fastType) return "fast";
  if (day.festivals.some((f) => isFeast(f.priority))) return "festival";
  return null;
}

/**
 * Everything worth naming on a day, most prominent first.
 *
 * <p>The fast comes first when there is one, because it is the line that changes what may be cooked.
 * Festivals keep the engine's own priority order (lower is more prominent in GCAL).
 */
export function dayEvents(day: CalendarDayView | undefined): DayEvent[] {
  if (!day) return [];
  const events: DayEvent[] = [];

  if (day.isEkadashi) {
    events.push({
      kind: "ekadasi",
      // The engine already stores the whole name — "Pavitraropana Ekadasi" — so appending the word
      // again read as "Pavitraropana Ekadashi Ekadashi" on every Ekadashi the calendar has ever shown.
      label: ekadashiLabel(day.ekadashiName),
      // A Mahadvadashi was only ever named on the non-Ekadashi branch, so a Vyanjuli day never said
      // it was one — and the parana window on those is minutes long, which is exactly the day
      // somebody needs telling.
      note: day.mahadvadashi
        ? `${titleCase(day.mahadvadashi)} Mahadvadashi. Break the fast early; the window is short.`
        : "Fasting from grains and beans",
    });
  } else if (day.fastType) {
    events.push({
      kind: "fast",
      label: fastLabel(day.fastType),
      note: day.mahadvadashi ? `${titleCase(day.mahadvadashi)} Mahadvadashi` : undefined,
    });
  }

  for (const festival of [...day.festivals].sort((a, b) => a.priority - b.priority)) {
    events.push({ kind: isFeast(festival.priority) ? "festival" : "observance", label: clean(festival.text) });
  }

  return events;
}

/**
 * The Ekadashi's name as it should be read.
 *
 * <p>The stored name already ends in "Ekadasi", so it is used as it stands, respelt "Ekadashi" on
 * the way out (see `ekadashiSpelling`); only a day with no name at all needs the bare word. Written
 * as a check rather than a strip so a future engine that stores "Pavitraropana" alone still reads
 * correctly.
 */
export function ekadashiLabel(name: string | null | undefined): string {
  const trimmed = ekadashiSpelling((name ?? "").trim());
  if (!trimmed) return "Ekadashi";
  return /ekadashi$/i.test(trimmed) ? trimmed : `${trimmed} Ekadashi`;
}

/** The engine stores these shouting — VYANJULI — and a calendar should not shout back. */
function titleCase(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1).toLowerCase();
}

/** What the kitchen actually has to do differently on this day, or null on an ordinary one. */
export function kitchenNote(
  day: CalendarDayView | undefined
): { tone: "info" | "warning"; text: string } | null {
  if (!day) return null;
  const kind = dayKind(day);

  // Only an Ekadashi-type fast (the Ekadashi itself, or a full-day fast) changes what may be cooked
  // and how much. A fast until noon, sunset or moonrise is an appearance day that ends in a feast, so
  // it says only its own line (Rajeev, 2026-09-18).
  //
  // Blue, not amber: amber is for something the user should act on or take care over, and a fast is
  // a day the temple already knows about, not a problem (Rajeev, 2026-09-18, T-227). The grain
  // confirm in the meal composer is the warning, when someone actually picks a grain dish.
  if (kind === "ekadasi" || (kind === "fast" && (day.fastType === "EKADASI" || day.fastType === "FULL_DAY"))) {
    return {
      tone: "info",
      text:
        "Fasting day: no grains, dal or beans. Cook sabudana, potato, peanut, fruit or buckwheat. " +
        "Plan about a third of the usual servings.",
    };
  }
  if (kind === "fast" && day.fastType) {
    return { tone: "info", text: `${fastLabel(day.fastType)}. Plan the feast for after.` };
  }
  if (kind === "festival") {
    return {
      tone: "info",
      text:
        "Feast day. Plan for three to four times the usual servings, a full sweet, and extra " +
        "volunteers on serving.",
    };
  }
  if (isFullOrNewMoon(day)) {
    return {
      tone: "info",
      text: "More people come for darshan. Add about a fifth to lunch.",
    };
  }
  return null;
}

/** Purnima (full) and Amavasya (new) both draw a bigger crowd than an ordinary day. */
export function isFullOrNewMoon(day: CalendarDayView): boolean {
  return day.tithi % 15 === 14;
}

/** The GCAL fast codes, in words a cook can act on. */
function fastLabel(fastType: string): string {
  switch (fastType) {
    case "EKADASI":
      return "Ekadashi fast";
    case "FULL_DAY":
      return "Full-day fast";
    case "NOON":
      return "Fast until noon";
    case "SUNSET":
      return "Fast until sunset";
    case "MOONRISE":
      return "Fast until moonrise";
    case "DUSK":
      return "Fast until dusk";
    case "MIDNIGHT":
      return "Fast until midnight";
    default:
      return "Fasting day";
  }
}

/**
 * The one name for a recipe that may be cooked on a fasting day (Rajeev, 2026-09-18).
 *
 * <p>It had four: "Ekadashi-friendly" on the recipe page, "Suits a fasting day" in the peek, the
 * "Ekadashi flag" in a notice and "fasting-compatible" in the field name. Four names for one fact
 * read as four facts. The field and API keep their names; only what a person reads changes.
 */
export const EKADASHI_FRIENDLY = "Ekadashi-friendly";

/**
 * A recipe tag as it should be read. The library data carries "Ekadashi-safe" as a free tag on
 * about a hundred recipes, copied onto a temple's own recipe when it is imported; it is shown under
 * the one name above rather than rewritten in the stored data, which is the temple's and the
 * library's to edit.
 */
export function recipeTagLabel(tag: string): string {
  return /^ekadas(h)?i[- ]?(safe|friendly|compatible)$/i.test(tag.trim())
    ? EKADASHI_FRIENDLY
    : ekadashiSpelling(tag);
}

/** Tags as they should be read, without repeating the Ekadashi-friendly badge a recipe already shows. */
export function recipeTagLabels(tags: readonly string[], badges: readonly string[] = []): string[] {
  const seen = new Set(badges);
  const out: string[] = [];
  for (const tag of tags) {
    const label = recipeTagLabel(tag);
    if (!seen.has(label)) {
      seen.add(label);
      out.push(label);
    }
  }
  return out;
}
