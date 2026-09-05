"use client";

import type { ReactNode } from "react";
import { SegmentedControl } from "@/components/ds/SegmentedControl";

/**
 * Moving through time on a screen that shows a period of it — the calendar and the meal planner.
 *
 * <p><strong>One component, because two copies is what kept breaking.</strong> The planner and the
 * calendar each grew their own stepper, and the planner's drifted four times: its middle control was
 * a button that named the period when you were away from today and said "Today" when you were on it,
 * so the one view a planner uses most — this month — never said which month it was, and the label
 * and the way back to today were the same widget fighting over one slot. The calendar had it right:
 * the middle names where you are and nothing else, and "Today" is an action beside the title. This is
 * that arrangement, used by both, so neither can drift from the other again.
 *
 * @param views  the periods this screen offers. The planner has day/week/month; the calendar
 *               month/week/year — the same control either way.
 * @param heading what the middle says. Always the period on screen, never a state.
 * @param current whether that period is the one the clock is in — today, this week, this month.
 *               Drawn as a pill around the heading rather than by swapping the heading for the
 *               word "Today": Rajeev's objection (2026-09-05) is that a stepper which says "Today"
 *               has stopped telling you the date, and the date is what you came to it for. The
 *               heading never changes; only its ground does.
 * @param onStep  one period back or forward, in the unit of the current view.
 * @param children anything that belongs beside the stepper on this screen alone — the calendar's
 *               legend, the planner's "Duplicate last week".
 */
export function PeriodNav<T extends string>({
  label,
  views,
  view,
  onView,
  heading,
  current = false,
  onStep,
  children,
}: {
  label: string;
  views: readonly { value: T; label: string }[];
  view: T;
  onView: (view: T) => void;
  heading: string;
  current?: boolean;
  onStep: (delta: -1 | 1) => void;
  children?: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-center gap-4">
      <SegmentedControl label={label} options={views} value={view} onChange={onView} />
      <div className="flex items-center gap-2">
        <IconButton label={`Previous ${view}`} icon="chevron-left" onClick={() => onStep(-1)} />
        {/* Wide enough for the longest heading either screen produces, so the arrows do not shuffle
            sideways as you step from "September" to "23 Aug – 29 Aug 2026". The pill sits inside
            that width and shrinks to its words, so it reads as a mark on the period rather than as a
            band across the control — and the padding is on the inner span in both states, so the
            heading does not jump a few pixels as you step off today and back onto it. */}
        <span className="flex min-w-44 justify-center">
          <span
            aria-current={current ? "date" : undefined}
            className={[
              "rounded-full px-3 py-1 text-center text-sm transition-colors duration-state",
              current ? "bg-accent-bg font-semibold text-accent-text" : "font-medium text-ink",
            ].join(" ")}
          >
            {heading}
            {/* Colour alone cannot carry a fact. A screen reader hears the date and then what is
                special about it. */}
            {current && <span className="sr-only"> — {currentLabel(view)}</span>}
          </span>
        </span>
        <IconButton label={`Next ${view}`} icon="chevron-right" onClick={() => onStep(1)} />
      </div>
      {children}
    </div>
  );
}

/**
 * What the pill means, said in words for anyone who cannot see that it is coloured. The views are
 * named after the unit they step in, so the unit is the word: "This week", "This month".
 */
function currentLabel(view: string): string {
  return view === "day" ? "Today" : `This ${view}`;
}

/** A control that is only an icon still has to say what it is, and still has to be touchable. */
export function IconButton({
  label,
  icon,
  onClick,
}: {
  label: string;
  icon: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      className="flex min-h-touch min-w-touch items-center justify-center rounded text-ink-secondary transition-colors duration-state hover:bg-sunken hover:text-ink"
    >
      <i className={`ti ti-${icon} text-lg`} aria-hidden="true" />
    </button>
  );
}

const MONTHS = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December",
];

/**
 * What the stepper says you are looking at: a day, a week, a month, a year.
 *
 * <p>Written the same way on every screen that steps through time. A week that crosses a month names
 * both ends — "30 Aug – 5 Sep 2026" — because "30 Aug – 5" says nothing.
 */
export function periodHeading(view: string, anchor: string): string {
  const short = (iso: string) => MONTHS[Number(iso.slice(5, 7)) - 1].slice(0, 3);
  const dayOf = (iso: string) => Number(iso.slice(8, 10));

  if (view === "year") {
    return anchor.slice(0, 4);
  }
  if (view === "day") {
    const d = new Date(anchor + "T00:00:00");
    return d.toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", year: "numeric" });
  }
  if (view === "week") {
    const start = startOfWeek(anchor);
    const end = addDays(start, 6);
    return `${dayOf(start)} ${short(start)} – ${dayOf(end)} ${short(end)} ${end.slice(0, 4)}`;
  }
  return `${MONTHS[Number(anchor.slice(5, 7)) - 1]} ${anchor.slice(0, 4)}`;
}

/** One period in whatever unit the view is made of — a day, a week, a month, a year. */
export function stepPeriod(view: string, anchor: string, delta: number): string {
  if (view === "day") return addDays(anchor, delta);
  if (view === "week") return addDays(anchor, 7 * delta);

  const d = new Date(anchor + "T00:00:00");
  if (view === "year") {
    d.setFullYear(d.getFullYear() + delta);
    return toIso(d);
  }
  // Month: the same day of another month, clamped where that month is shorter, so stepping from the
  // 31st lands in February rather than skidding into March.
  const day = d.getDate();
  d.setDate(1);
  d.setMonth(d.getMonth() + delta);
  d.setDate(Math.min(day, new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()));
  return toIso(d);
}

/**
 * The two dates behind the period on screen: whole calendar periods, never a rolling window.
 *
 * <p>A temple comparing August against July means the months. A report whose edges moved with the
 * clock could not be read against itself a week later, and two reports that chose their edges
 * differently could not be read against each other at all — which is why this lives beside the
 * stepper rather than in each screen that uses one.
 */
export function periodRange(view: string, anchor: string): { from: string; to: string } {
  if (view === "year") {
    return { from: `${anchor.slice(0, 4)}-01-01`, to: `${anchor.slice(0, 4)}-12-31` };
  }
  if (view === "day") {
    return { from: anchor, to: anchor };
  }
  if (view === "week") {
    const start = startOfWeek(anchor);
    return { from: start, to: addDays(start, 6) };
  }
  const last = new Date(Number(anchor.slice(0, 4)), Number(anchor.slice(5, 7)), 0);
  return {
    from: `${anchor.slice(0, 7)}-01`,
    to: `${anchor.slice(0, 7)}-${String(last.getDate()).padStart(2, "0")}`,
  };
}

/**
 * Whether the period on screen is the one the clock is in — the fact the pill draws.
 *
 * <p>Beside the heading and the stepper rather than in each screen, for the reason the whole file
 * exists: three screens working out "is this this month?" three ways is how they came to disagree.
 */
export function isCurrentPeriod(view: string, anchor: string, today: string): boolean {
  if (view === "day") return anchor === today;
  if (view === "week") return startOfWeek(anchor) === startOfWeek(today);
  if (view === "year") return anchor.slice(0, 4) === today.slice(0, 4);
  return anchor.slice(0, 7) === today.slice(0, 7);
}

function addDays(iso: string, days: number): string {
  const d = new Date(iso + "T00:00:00");
  d.setDate(d.getDate() + days);
  return toIso(d);
}

function startOfWeek(iso: string): string {
  return addDays(iso, -new Date(iso + "T00:00:00").getDay());
}

function toIso(d: Date): string {
  return [
    d.getFullYear(),
    String(d.getMonth() + 1).padStart(2, "0"),
    String(d.getDate()).padStart(2, "0"),
  ].join("-");
}
