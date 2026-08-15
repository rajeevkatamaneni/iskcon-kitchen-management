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
  if (day.festivals.length > 0) return "festival";
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
      label: day.ekadashiName ? `${day.ekadashiName} Ekadasi` : "Ekadasi",
      note: "Fasting from grains and beans",
    });
  } else if (day.fastType) {
    events.push({ kind: "fast", label: fastLabel(day.fastType), note: day.mahadvadashi ?? undefined });
  }

  for (const festival of [...day.festivals].sort((a, b) => a.priority - b.priority)) {
    // GCAL's own priority marks the difference between a feast the kitchen cooks for and a day it
    // merely notes. Anything at the top of the list is a festival; the rest are observances.
    events.push({ kind: festival.priority <= 2 ? "festival" : "observance", label: festival.text });
  }

  return events;
}

/** What the kitchen actually has to do differently on this day, or null on an ordinary one. */
export function kitchenNote(
  day: CalendarDayView | undefined
): { tone: "info" | "warning"; text: string } | null {
  if (!day) return null;
  const kind = dayKind(day);

  if (kind === "ekadasi" || kind === "fast") {
    return {
      tone: "warning",
      text:
        "Fasting day: no grains, no dal, no beans. Cook sabudana, potato, peanut, fruit and " +
        "buckwheat, and plan roughly a third of the usual plate count.",
    };
  }
  if (kind === "festival") {
    return {
      tone: "info",
      text:
        "Feast day. Plan for three to four times the usual plates, a full sweet, and extra " +
        "volunteers on serving.",
    };
  }
  if (isFullOrNewMoon(day)) {
    return {
      tone: "info",
      text: "Higher darshan attendance than an ordinary day. Add about a fifth to the lunch count.",
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
      return "Ekadasi fast";
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
