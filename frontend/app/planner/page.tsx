"use client";

import { useCallback, useMemo, useState } from "react";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { Card } from "@/components/ds/Card";
import { PageHeader } from "@/components/ds/PageHeader";
import { Screen } from "@/components/ds/Screen";
import { SegmentedControl } from "@/components/ds/SegmentedControl";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { DayView } from "@/components/planner/DayView";
import { api, type CalendarDayView, type MealPlanView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { hhmm, longDate, todayIso } from "@/lib/format";

/**
 * The meal plan (E4-S7).
 *
 * <p>The screen shows the plan; the Vaishnava calendar is an input to planning, not the content of
 * the screen. So a day carries the meals cooked on it, and the calendar appears as a single mark on
 * days that constrain what may be cooked — a fasting day, a festival — with the names inside the day
 * itself. Before this, the grid was full of calendar text and contained no meals at all.
 *
 * <p>Clicking anywhere on a day opens it. One gesture, one target: the previous screen had two
 * unlabelled ones, only one of which looked clickable, and its panel opened off-screen (UAT031-1).
 */

type View = "day" | "week" | "month";

const VIEWS = [
  { value: "day" as const, label: "Day" },
  { value: "week" as const, label: "Week" },
  { value: "month" as const, label: "Month" },
];

export default function PlannerPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF"]}>
      <PlannerView />
    </RequireRole>
  );
}

function PlannerView() {
  const { appUser } = useAuth();
  const [view, setView] = useState<View>("month");
  const [anchor, setAnchor] = useState(todayIso());
  const [open, setOpen] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);

  const { from, to } = rangeFor(view, anchor);

  const calQ = useAuthedQuery(
    useCallback((t?: string) => { void nonce; return api.calendarRange(from, to, t); }, [from, to, nonce])
  );
  const mealsQ = useAuthedQuery(
    useCallback((t?: string) => { void nonce; return api.listMealPlans({ from, to }, t); }, [from, to, nonce])
  );
  const suffQ = useAuthedQuery(
    useCallback((t?: string) => { void nonce; return api.mealSufficiency(from, to, t); }, [from, to, nonce])
  );
  const { data: recipes } = useAuthedQuery(useCallback((t?: string) => api.listRecipes({}, t), []));
  const { data: mealKinds } = useAuthedQuery(api.listMealKinds);

  const calendar = useMemo(() => index(calQ.data ?? [], (d) => d.date), [calQ.data]);
  const meals = useMemo(() => group(mealsQ.data ?? [], (m) => m.planDate), [mealsQ.data]);
  const sufficiency = useMemo(() => index(suffQ.data ?? [], (s) => s.mealPlanId), [suffQ.data]);

  const today = todayIso();

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Your temple" activeHref="/planner" />

      <main className="min-w-0 flex-1">
        <Screen>
          <PageHeader
            title="Meal plan"
            actions={
              <>
                <Button variant="secondary" onClick={() => setAnchor(today)}>
                  Today
                </Button>
                <Button onClick={() => setOpen(today)}>Plan a meal</Button>
              </>
            }
            tabs={
              <div className="flex flex-wrap items-center justify-between gap-4">
                <SegmentedControl label="Calendar view" options={VIEWS} value={view} onChange={setView} />
                <div className="flex items-center gap-3">
                  <Button variant="ghost" aria-label="Previous" onClick={() => setAnchor(step(view, anchor, -1))}>
                    &larr;
                  </Button>
                  <span className="min-w-[12rem] text-center font-medium">{periodLabel(view, anchor)}</span>
                  <Button variant="ghost" aria-label="Next" onClick={() => setAnchor(step(view, anchor, 1))}>
                    &rarr;
                  </Button>
                </div>
              </div>
            }
          />

          {calQ.error && <ErrorNotice error={calQ.error} />}

          {view === "month" && (
            <MonthGrid anchor={anchor} today={today} calendar={calendar} meals={meals} onOpen={setOpen} />
          )}
          {view === "week" && (
            <WeekStrip from={from} today={today} calendar={calendar} meals={meals} onOpen={setOpen} />
          )}
          {view === "day" && (
            <DayList date={anchor} calendar={calendar} meals={meals.get(anchor) ?? []} onOpen={setOpen} />
          )}
        </Screen>
      </main>

      {open && (
        <DayView
          date={open}
          day={calendar.get(open)}
          meals={meals.get(open) ?? []}
          sufficiency={sufficiency}
          recipes={recipes ?? []}
          mealKinds={mealKinds ?? []}
          canCorrect={appUser?.role === "TEMPLE_ADMIN"}
          readOnly={open < today}
          onClose={() => setOpen(null)}
          onChanged={() => setNonce((n) => n + 1)}
        />
      )}
    </div>
  );
}

/** A month of days, each carrying its meals. The calendar is one mark, not a paragraph. */
function MonthGrid({
  anchor, today, calendar, meals, onOpen,
}: {
  anchor: string;
  today: string;
  calendar: Map<string, CalendarDayView>;
  meals: Map<string, MealPlanView[]>;
  onOpen: (date: string) => void;
}) {
  const month = Number(anchor.slice(5, 7));
  const weeks = monthGrid(anchor);

  return (
    <div className="overflow-x-auto">
      <div className="grid min-w-[720px] grid-cols-7 gap-px rounded-lg bg-hairline">
        {["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"].map((d) => (
          <div key={d} className="bg-sunken px-2 py-2 text-center text-xs font-medium text-ink-secondary">
            {d}
          </div>
        ))}
        {weeks.flat().map((date) => (
          <DayCell
            key={date}
            date={date}
            inMonth={Number(date.slice(5, 7)) === month}
            isToday={date === today}
            day={calendar.get(date)}
            meals={meals.get(date) ?? []}
            onOpen={onOpen}
          />
        ))}
      </div>
    </div>
  );
}

function DayCell({
  date, inMonth, isToday, day, meals, onOpen,
}: {
  date: string;
  inMonth: boolean;
  isToday: boolean;
  day: CalendarDayView | undefined;
  meals: MealPlanView[];
  onOpen: (date: string) => void;
}) {
  const planned = meals.filter((m) => m.status !== "CANCELLED");
  const festival = day?.festivals?.[0]?.text;

  return (
    <button
      type="button"
      onClick={() => onOpen(date)}
      aria-label={planned.length
        ? `${longDate(date)}, ${planned.length} meals planned`
        : `${longDate(date)}, nothing planned`}
      className={[
        "min-h-[7.5rem] bg-canvas p-2 text-left align-top transition-colors duration-state",
        "hover:bg-accent-bg/40 focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-border",
        inMonth ? "" : "opacity-45",
      ].join(" ")}
    >
      <span className="flex items-center justify-between">
        <span
          className={[
            "inline-flex h-6 min-w-6 items-center justify-center rounded-full px-1 text-sm",
            isToday ? "bg-accent font-semibold text-ink-inverse" : "font-medium text-ink",
          ].join(" ")}
        >
          {Number(date.slice(8, 10))}
        </span>
        {/* The calendar as a signal, not a sentence: a fasting day or festival is visible without
            reading, and named inside the day itself (E4-S7, D10). */}
        <span className="flex gap-1">
          {day?.isEkadashi && <span className="h-2 w-2 rounded-full bg-accent" title="Fasting day" />}
          {festival && <span className="h-2 w-2 rounded-full bg-warning" title={festival} />}
        </span>
      </span>

      <span className="mt-1 grid gap-1">
        {planned.slice(0, 3).map((m) => (
          <span key={m.id} className="grid rounded-sm bg-sunken px-1.5 py-1">
            <span className="truncate text-xs font-medium text-ink">{m.recipeName}</span>
            <span className="text-[11px] tabular-nums text-ink-muted">
              {hhmm(m.readyBy)} &middot; {m.mealKind}
            </span>
          </span>
        ))}
        {planned.length > 3 && (
          <span className="px-1.5 text-[11px] text-ink-muted">+{planned.length - 3} more</span>
        )}
      </span>
    </button>
  );
}

/** Seven days with room to breathe: every meal shown, none hidden behind a "+2 more". */
function WeekStrip({
  from, today, calendar, meals, onOpen,
}: {
  from: string;
  today: string;
  calendar: Map<string, CalendarDayView>;
  meals: Map<string, MealPlanView[]>;
  onOpen: (date: string) => void;
}) {
  const days = Array.from({ length: 7 }, (_, i) => addDays(from, i));
  return (
    <div className="overflow-x-auto">
      <div className="grid min-w-[900px] grid-cols-7 gap-px rounded-lg bg-hairline">
        {days.map((date) => {
          const day = calendar.get(date);
          const planned = (meals.get(date) ?? []).filter((m) => m.status !== "CANCELLED");
          return (
            <button
              key={date}
              type="button"
              onClick={() => onOpen(date)}
              className="min-h-[16rem] bg-canvas p-3 text-left align-top transition-colors duration-state hover:bg-accent-bg/40"
            >
              <span className="flex items-baseline justify-between gap-2">
                <span className={date === today ? "font-semibold text-accent-text" : "font-medium text-ink"}>
                  {new Date(date + "T00:00:00").toLocaleDateString(undefined, { weekday: "short", day: "numeric" })}
                </span>
                {day?.isEkadashi && <Badge tone="accent">Fasting</Badge>}
              </span>
              {day?.festivals?.[0] && (
                <span className="mt-1 block truncate text-xs text-warning" title={day.festivals[0].text}>
                  {day.festivals[0].text}
                </span>
              )}
              <span className="mt-2 grid gap-2">
                {planned.map((m) => (
                  <span key={m.id} className="grid rounded-sm bg-sunken px-2 py-1.5">
                    <span className="text-xs tabular-nums text-ink-muted">{hhmm(m.readyBy)} &middot; {m.mealKind}</span>
                    <span className="truncate text-sm font-medium text-ink">{m.recipeName}</span>
                    <span className="text-xs text-ink-muted">{Number(m.targetServings).toLocaleString()} servings</span>
                  </span>
                ))}
                {planned.length === 0 && <span className="text-xs text-ink-muted">Nothing planned</span>}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

/** One day, in the order the kitchen works. */
function DayList({
  date, calendar, meals, onOpen,
}: {
  date: string;
  calendar: Map<string, CalendarDayView>;
  meals: MealPlanView[];
  onOpen: (date: string) => void;
}) {
  const day = calendar.get(date);
  const planned = meals.filter((m) => m.status !== "CANCELLED");

  return (
    <Card
      title={longDate(date)}
      meta={day?.isEkadashi ? (day.ekadashiName || "Ekadashi") + " — a fasting day" : undefined}
      action={<Button size="sm" variant="secondary" onClick={() => onOpen(date)}>Open the day</Button>}
    >
      {planned.length === 0 ? (
        <p className="text-ink-secondary">Nothing planned for this day yet.</p>
      ) : (
        <div className="grid">
          {planned.map((m) => (
            <div key={m.id} className="flex items-center gap-4 border-t border-hairline py-3 first:border-t-0">
              <span className="w-16 flex-none tabular-nums text-sm font-medium text-ink">{hhmm(m.readyBy)}</span>
              <span className="grid flex-1">
                <span className="font-medium text-ink">{m.mealKind} — {m.recipeName}</span>
                <span className="text-xs text-ink-muted">{Number(m.targetServings).toLocaleString()} servings</span>
              </span>
              {m.status === "COOKED" && <Badge tone="success">Cooked</Badge>}
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}

// --- dates ---------------------------------------------------------------

function addDays(iso: string, days: number): string {
  const d = new Date(iso + "T00:00:00");
  d.setDate(d.getDate() + days);
  return toIso(d);
}

function toIso(d: Date): string {
  return [d.getFullYear(), String(d.getMonth() + 1).padStart(2, "0"), String(d.getDate()).padStart(2, "0")].join("-");
}

function startOfWeek(iso: string): string {
  return addDays(iso, -new Date(iso + "T00:00:00").getDay());
}

/** The Sunday on or before the 1st, through the Saturday on or after the last day. */
function monthGrid(anchor: string): string[][] {
  const first = anchor.slice(0, 7) + "-01";
  const d = new Date(first + "T00:00:00");
  const lastDay = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
  const start = startOfWeek(first);
  const end = startOfWeek(anchor.slice(0, 7) + "-" + String(lastDay).padStart(2, "0"));
  const weeks: string[][] = [];
  for (let w = start; w <= end; w = addDays(w, 7)) {
    weeks.push(Array.from({ length: 7 }, (_, i) => addDays(w, i)));
  }
  return weeks;
}

function rangeFor(view: View, anchor: string): { from: string; to: string } {
  if (view === "day") return { from: anchor, to: anchor };
  if (view === "week") {
    const from = startOfWeek(anchor);
    return { from, to: addDays(from, 6) };
  }
  const weeks = monthGrid(anchor);
  return { from: weeks[0][0], to: weeks[weeks.length - 1][6] };
}

function step(view: View, anchor: string, by: number): string {
  if (view === "day") return addDays(anchor, by);
  if (view === "week") return addDays(anchor, by * 7);
  const d = new Date(anchor.slice(0, 7) + "-01T00:00:00");
  d.setMonth(d.getMonth() + by);
  return toIso(d);
}

function periodLabel(view: View, anchor: string): string {
  const d = new Date(anchor + "T00:00:00");
  if (view === "month") return d.toLocaleDateString(undefined, { month: "long", year: "numeric" });
  if (view === "day") return longDate(anchor);
  const from = startOfWeek(anchor);
  const to = addDays(from, 6);
  const fmt = (iso: string) =>
    new Date(iso + "T00:00:00").toLocaleDateString(undefined, { day: "numeric", month: "short" });
  return fmt(from) + " – " + fmt(to);
}

function index<T>(items: T[], key: (t: T) => string): Map<string, T> {
  return new Map(items.map((i) => [key(i), i]));
}

function group<T>(items: T[], key: (t: T) => string): Map<string, T[]> {
  const map = new Map<string, T[]>();
  for (const item of items) {
    const k = key(item);
    const list = map.get(k);
    if (list) list.push(item);
    else map.set(k, [item]);
  }
  return map;
}
