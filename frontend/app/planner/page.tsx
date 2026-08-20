"use client";

import { useCallback, useMemo, useState } from "react";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Card } from "@/components/ds/Card";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { PageHeader } from "@/components/ds/PageHeader";
import { Screen } from "@/components/ds/Screen";
import { SegmentedControl } from "@/components/ds/SegmentedControl";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { DayView } from "@/components/planner/DayView";
import { MealComposer } from "@/components/planner/MealComposer";
import { api, type CalendarDayView, type MealPlanView, type MealSufficiency, toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { dayLabel } from "@/lib/calendar-names";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { hhmm, longDate, longDay, todayIso } from "@/lib/format";

/**
 * The meal plan (E4-S7).
 *
 * <p>The screen shows the plan; the Vaishnava calendar is an input to planning, not the content of
 * the screen. So a day carries the meals cooked on it, and the calendar appears as a mark on days
 * that constrain what may be cooked — a fasting day, a festival — with the names inside the day
 * itself.
 *
 * <p>A day is where the work is, so a day is what opens: Week and Month are for finding one, and a
 * click on either lands on that date in Day rather than opening a panel over the grid. Only Day
 * carries the date navigator, because only Day shows a single date to move through.
 */

type View = "day" | "week" | "month";

const VIEWS = [
  { value: "day" as const, label: "Day" },
  { value: "week" as const, label: "Week" },
  { value: "month" as const, label: "Month" },
];

const WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

export default function PlannerPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <PlannerView />
    </RequireRole>
  );
}

function PlannerView() {
  const { appUser, getToken } = useAuth();
  const [view, setView] = useState<View>("day");
  const [anchor, setAnchor] = useState(todayIso());
  const [open, setOpen] = useState<string | null>(null);
  // Planning happens in place, under the day it belongs to; opening an existing meal still uses the
  // panel, because that is where a meal is corrected, cooked and checked against the store room.
  const [composing, setComposing] = useState(false);
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
  const isToday = anchor === today;

  /** Landing on a date from Week or Month means opening that day, not a panel over the grid. */
  function pick(date: string) {
    setAnchor(date);
    setView("day");
    setComposing(false);
  }

  const [duplicating, setDuplicating] = useState(false);
  const [duplicated, setDuplicated] = useState<string | null>(null);

  /**
   * Copies last week into this one. It only ever adds, so the result has to say what it left alone
   * — otherwise a planner who had already filled in Thursday sees "done" and cannot tell whether
   * their Thursday survived.
   */
  async function duplicateLastWeek() {
    setDuplicating(true);
    setDuplicated(null);
    try {
      const r = await api.duplicateWeek(startOfWeek(anchor), await getToken());
      setNonce((n) => n + 1);
      if (r.sourceWasEmpty) {
        setDuplicated("Nothing was planned last week, so there was nothing to copy.");
        return;
      }
      const parts = [`${r.copied} ${r.copied === 1 ? "meal" : "meals"} copied`];
      if (r.daysAlreadyPlanned > 0) {
        parts.push(
          `${r.daysAlreadyPlanned} ${r.daysAlreadyPlanned === 1 ? "day" : "days"} already had meals and were left alone`
        );
      }
      if (r.mealsRefusedOnFast > 0) {
        parts.push(
          `${r.mealsRefusedOnFast} not copied — they fall on a fast day their recipe doesn't suit`
        );
      }
      setDuplicated(parts.join(" · ") + ".");
    } catch (e) {
      setDuplicated(toApiError(e, "We couldn't copy last week.").message);
    } finally {
      setDuplicating(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/planner" />

      <main className="min-w-0 flex-1">
        <Screen>
          <PageHeader
            title="Meal planner"
            subtitle={subtitle(view, anchor, appUser?.tenantName ?? null)}
            actions={
              // "Plan a meal" used to sit here and was redundant: in Week and Month you plan by
              // pressing the day you mean, and the Day view carries its own control. Generating the
              // purchase list is what this screen is finally for, so it is the one accent button,
              // and it sits last.
              <>
                {view === "week" && (
                  <Button
                    variant="secondary"
                    disabled={duplicating}
                    onClick={duplicateLastWeek}
                  >
                    {duplicating ? "Copying…" : "Duplicate last week"}
                  </Button>
                )}
                <ButtonLink href="/order-list">Generate purchase list</ButtonLink>
              </>
            }
            tabs={
              <div className="flex flex-wrap items-center gap-4">
                <SegmentedControl label="Calendar view" options={VIEWS} value={view} onChange={setView} />
                {/* Only Day moves through dates one at a time; Week and Month name their own period
                    in the subtitle, so arrows there would be a second way to say the same thing. */}
                {view === "day" && (
                  <div className="flex items-center gap-2">
                    <Button variant="ghost" aria-label="Previous day" onClick={() => setAnchor(addDays(anchor, -1))}>
                      &lsaquo;
                    </Button>
                    <Button
                      size="sm"
                      variant="secondary"
                      aria-label={isToday ? "Today" : `${longDate(anchor)} — back to today`}
                      onClick={() => setAnchor(today)}
                    >
                      {isToday ? "Today" : shortDay(anchor)}
                    </Button>
                    <Button variant="ghost" aria-label="Next day" onClick={() => setAnchor(addDays(anchor, 1))}>
                      &rsaquo;
                    </Button>
                  </div>
                )}
              </div>
            }
          />

          {calQ.error && <ErrorNotice error={calQ.error} />}

          {view === "day" && (
            <DayPanel
              date={anchor}
              isToday={isToday}
              day={calendar.get(anchor)}
              meals={meals.get(anchor) ?? []}
              sufficiency={sufficiency}
              readOnly={anchor < today}
              composing={composing}
              onCompose={() => setComposing(true)}
              onOpen={() => setOpen(anchor)}
              composer={
                <MealComposer
                  date={anchor}
                  recipes={recipes ?? []}
                  mealKinds={mealKinds ?? []}
                  isEkadashi={Boolean(calendar.get(anchor)?.isEkadashi)}
                  onClose={() => setComposing(false)}
                  onPlanned={() => setNonce((n) => n + 1)}
                />
              }
            />
          )}
          {view === "week" && (
            <>
              {duplicated && (
                <div className="mb-4">
                  <InlineNotice tone="info">{duplicated}</InlineNotice>
                </div>
              )}
              <WeekGrid from={from} today={today} calendar={calendar} meals={meals} onPick={pick} />
            </>
          )}
          {view === "month" && (
            <MonthGrid anchor={anchor} today={today} calendar={calendar} meals={meals} onPick={pick} />
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

// ---- Day ----------------------------------------------------------------

/**
 * One day, as the kitchen reads it: what day it is on both calendars, what the day forbids, and then
 * the meals in the order they must be ready.
 */
function DayPanel({
  date, isToday, day, meals, sufficiency, readOnly, composing, composer, onCompose, onOpen,
}: {
  date: string;
  isToday: boolean;
  day: CalendarDayView | undefined;
  meals: MealPlanView[];
  sufficiency: Map<string, MealSufficiency>;
  readOnly: boolean;
  composing: boolean;
  composer: React.ReactNode;
  onCompose: () => void;
  onOpen: () => void;
}) {
  const planned = meals.filter((m) => m.status !== "CANCELLED");
  const festivals = day?.festivals ?? [];

  return (
    <>
      <Card tone="canvas">
        <div className="flex flex-wrap items-start gap-6">
          <span className="grid min-w-[16rem] flex-1 gap-1">
            <span className="flex items-center gap-3">
              {isToday && <Badge tone="accent">Today</Badge>}
              <span className="text-2xl font-semibold text-ink">{longDay(date)}</span>
            </span>
            {day && <span className="text-ink-secondary">{dayLabel(day)}</span>}
            {day?.sunrise && day?.sunset && (
              <span className="text-xs tabular-nums text-ink-muted">
                Sunrise {hhmm(day.sunrise)} &middot; Sunset {hhmm(day.sunset)}
              </span>
            )}
          </span>

          <span className="grid max-w-[26rem] justify-items-end gap-2">
            {day?.isEkadashi && <Badge tone="warning">{day.ekadashiName || "Ekadashi"}</Badge>}
            {festivals.map((f) => (
              <Badge key={f.text} tone="success">
                {f.text}
              </Badge>
            ))}
            {!day?.isEkadashi && festivals.length === 0 && (
              <span className="text-xs text-ink-muted">No festival or fast on this day</span>
            )}
            <ButtonLink href="/calendar" size="sm" variant="ghost">
              Open the calendar
            </ButtonLink>
          </span>
        </div>
      </Card>

      {day?.isEkadashi && (
        <InlineNotice
          tone="warning"
          action={
            <ButtonLink href="/calendar" size="sm" variant="ghost">
              Calendar
            </ButtonLink>
          }
        >
          Grains, dal and beans come off every menu on {day.ekadashiName || "this fasting day"}.
        </InlineNotice>
      )}

      <div className="grid gap-3">
        {planned.map((meal) => (
          <MealRow key={meal.id} meal={meal} sufficiency={sufficiency.get(meal.id)} onOpen={onOpen} />
        ))}

        {planned.length === 0 && !composing && (
          <EmptyState title="Nothing planned for this day yet">
            Add the first meal and the recipes will scale to the head count you give.
          </EmptyState>
        )}

        {composing && composer}

        {!readOnly && !composing && (
          <button
            type="button"
            onClick={onCompose}
            className="flex min-h-[3.5rem] items-center justify-center gap-2 rounded-lg border border-dashed border-hairline-strong text-ink-secondary transition-colors duration-state hover:bg-raised"
          >
            <span aria-hidden className="text-lg leading-none">+</span>
            Add a meal
          </button>
        )}
      </div>
    </>
  );
}

/** A planned meal at a glance, opened by the time it has to be ready — the kitchen's own order. */
function MealRow({
  meal, sufficiency, onOpen,
}: {
  meal: MealPlanView;
  sufficiency: MealSufficiency | undefined;
  onOpen: () => void;
}) {
  const short = sufficiency?.shortfalls?.length ?? 0;

  return (
    <Card padding="px-6 py-4">
      <div className="flex flex-wrap items-center gap-4">
        <span className="grid min-w-[5.75rem]">
          <span className="text-lg font-medium tabular-nums text-ink">{hhmm(meal.readyBy)}</span>
          <span className="text-xs text-ink-muted">ready by</span>
        </span>

        <span className="grid min-w-[15rem] flex-1 gap-1">
          <span className="flex flex-wrap items-center gap-3">
            <Badge tone="neutral">{meal.mealKind}</Badge>
            <span className="font-medium text-ink">{meal.recipeName}</span>
            {meal.status === "COOKED" && <Badge tone="success">Cooked</Badge>}
            {short > 0 && (
              <Badge tone="danger">
                {short} ingredient{short === 1 ? "" : "s"} short
              </Badge>
            )}
          </span>
          <span className="text-xs text-ink-muted">
            {Number(meal.targetServings).toLocaleString("en-IN")} servings
            {headCount(meal) ? ` · ${headCount(meal)}` : ""}
            {meal.occasionName ? ` · ${meal.occasionName}` : ""}
          </span>
          {meal.kitchenNotes && (
            <span className="text-xs text-ink-secondary">{meal.kitchenNotes}</span>
          )}
        </span>

        <Button size="sm" variant="secondary" onClick={onOpen}>
          Open
        </Button>
      </div>
    </Card>
  );
}

// ---- Week ---------------------------------------------------------------

/** Seven days side by side, each a way into that day. Today is the one with the outline. */
function WeekGrid({
  from, today, calendar, meals, onPick,
}: {
  from: string;
  today: string;
  calendar: Map<string, CalendarDayView>;
  meals: Map<string, MealPlanView[]>;
  onPick: (date: string) => void;
}) {
  const days = Array.from({ length: 7 }, (_, i) => addDays(from, i));

  return (
    <div className="overflow-x-auto">
      <div className="grid min-w-[900px] grid-cols-7 gap-3">
        {days.map((date) => {
          const day = calendar.get(date);
          const planned = (meals.get(date) ?? []).filter((m) => m.status !== "CANCELLED");
          const festival = day?.festivals?.[0]?.text;

          return (
            <button
              key={date}
              type="button"
              onClick={() => onPick(date)}
              aria-label={planned.length
                ? `${longDate(date)}, ${planned.length} meals planned`
                : `${longDate(date)}, nothing planned`}
              className={[
                // Radius, padding and gap are the prototype's, read from it rather than guessed.
                "grid content-start gap-3 rounded-2xl border border-hairline bg-canvas p-4 text-left",
                "transition-colors duration-state hover:bg-raised",
                "focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-border",
                date === today ? "ring-2 ring-ink ring-inset" : "",
              ].join(" ")}
            >
              <span className="grid">
                <span className="text-xs uppercase tracking-[0.06em] text-ink-muted">
                  {WEEKDAYS[new Date(date + "T00:00:00").getDay()]}
                </span>
                {/* The date is quiet in the prototype — the same size as the body text and not
                    bolded. It is a label on the cell, not the thing you read the cell for. */}
                <span className="text-base text-ink">{Number(date.slice(8, 10))}</span>
              </span>

              {/* A shade larger than a badge elsewhere in the app, as the prototype draws it: on a
                  narrow cell of small print, the fast is the one thing that must not be missed.

                  Kept to one line, which the prototype never had to think about — it only ever shows
                  "Ekadasi". Real festival names run to "Sri Raghunandana Thakura -- Disappearance",
                  and a name that wraps turns a pill into a four-line blob. Truncated with the whole
                  name on hover, and the day itself opens to read it properly. */}
              {day?.isEkadashi && (
                <span
                  title={day.ekadashiName || "Ekadashi"}
                  // Blue, matching the calendar. These two screens had disagreed about the colour of the same
                  // day since they were built \u2014 the calendar said terracotta, this said gold.
                  className="w-fit max-w-full truncate rounded-full bg-info-bg px-2 py-0.5 text-sm font-medium text-info"
                >
                  {day.ekadashiName || "Ekadashi"}
                </span>
              )}
              {!day?.isEkadashi && festival && (
                <span
                  title={festival}
                  className="w-fit max-w-full truncate rounded-full bg-success-bg px-2 py-0.5 text-sm font-medium text-success"
                >
                  {festival}
                </span>
              )}

              {planned.length === 0 ? (
                <span className="text-xs text-ink-muted">Nothing planned</span>
              ) : (
                planned.map((m) => (
                  <span key={m.id} className="grid gap-px border-l-2 border-accent pl-2">
                    <span className="text-xs tabular-nums text-ink">
                      {hhmm(m.readyBy)} {m.mealKind}
                    </span>
                    <span className="text-xs text-ink-muted">
                      {Number(m.targetServings).toLocaleString("en-IN")} servings
                    </span>
                  </span>
                ))
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

// ---- Month --------------------------------------------------------------

/** A month at a glance: which days are spoken for, which days the calendar constrains. */
function MonthGrid({
  anchor, today, calendar, meals, onPick,
}: {
  anchor: string;
  today: string;
  calendar: Map<string, CalendarDayView>;
  meals: Map<string, MealPlanView[]>;
  onPick: (date: string) => void;
}) {
  const month = Number(anchor.slice(5, 7));
  const start = startOfWeek(anchor.slice(0, 7) + "-01");
  const cells = Array.from({ length: 42 }, (_, i) => addDays(start, i));

  return (
    <div className="overflow-x-auto">
      <div className="min-w-[720px] overflow-hidden rounded-lg border border-hairline bg-canvas">
        <div className="grid grid-cols-7 border-b border-hairline">
          {WEEKDAYS.map((d) => (
            <div key={d} className="px-3 py-3 text-xs uppercase tracking-[0.06em] text-ink-muted">
              {d}
            </div>
          ))}
        </div>

        <div className="grid grid-cols-7">
          {cells.map((date) => {
            const day = calendar.get(date);
            const planned = (meals.get(date) ?? []).filter((m) => m.status !== "CANCELLED");
            const festival = day?.festivals?.[0]?.text;

            return (
              <button
                key={date}
                type="button"
                onClick={() => onPick(date)}
                aria-label={planned.length
                  ? `${longDate(date)}, ${planned.length} meals planned`
                  : `${longDate(date)}, nothing planned`}
                className={[
                  "grid min-h-[5.75rem] content-start gap-[3px] border-b border-r border-hairline p-3 text-left",
                  "transition-colors duration-state hover:bg-raised",
                  "focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-border",
                  date === today ? "bg-sunken" : "",
                  Number(date.slice(5, 7)) === month ? "" : "opacity-40",
                ].join(" ")}
              >
                {/* Truncated the way the week grid does it, and for the same reason: real festival
                    names run to "Sri Raghunandana Thakura -- Disappearance" and used to push
                    straight out of the cell. `truncate` alone could not bite here — a flex item's
                    min-width is auto, so the name grew to its content whatever the overflow said.
                    The cap plus min-w-0 is what actually stops it, and the day number holds its
                    ground so the one thing a calendar must always show is never the thing that
                    disappears. The whole name is on hover, as the week grid has always had it. */}
                <span className="flex items-center justify-between gap-1">
                  <span className="flex-none text-sm font-medium text-ink">
                    {Number(date.slice(8, 10))}
                  </span>
                  {day?.isEkadashi ? (
                    <span
                      title={day.ekadashiName || "Ekadashi"}
                      className="w-fit min-w-0 max-w-full truncate text-xs text-warning"
                    >
                      {day.ekadashiName || "Ekadashi"}
                    </span>
                  ) : festival ? (
                    <span
                      title={festival}
                      className="w-fit min-w-0 max-w-full truncate text-xs text-success"
                    >
                      {festival}
                    </span>
                  ) : null}
                </span>

                {planned.slice(0, 3).map((m) => (
                  <span key={m.id} className="truncate text-xs text-ink-secondary">
                    {hhmm(m.readyBy)} {m.mealKind}
                  </span>
                ))}
                {planned.length > 3 && (
                  <span className="text-xs text-ink-muted">+{planned.length - 3} more</span>
                )}
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}

/** "200 adults, 40 children, 30 seniors" — the count the servings were worked out from. */
function headCount(meal: MealPlanView): string {
  const parts: string[] = [];
  if (meal.adults) parts.push(`${meal.adults} adults`);
  if (meal.children) parts.push(`${meal.children} children`);
  if (meal.seniors) parts.push(`${meal.seniors} seniors`);
  return parts.join(", ");
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

function rangeFor(view: View, anchor: string): { from: string; to: string } {
  if (view === "day") return { from: anchor, to: anchor };
  if (view === "week") {
    const from = startOfWeek(anchor);
    return { from, to: addDays(from, 6) };
  }
  const start = startOfWeek(anchor.slice(0, 7) + "-01");
  return { from: start, to: addDays(start, 41) };
}

/** "Tue 12 Aug" — the pill's label on any day that isn't today. */
function shortDay(iso: string): string {
  return new Date(iso + "T00:00:00").toLocaleDateString(undefined, {
    weekday: "short",
    day: "numeric",
    month: "short",
  });
}

/** The period, then whose kitchen it is. Day names itself in its own panel, so it only says whose. */
function subtitle(view: View, anchor: string, temple: string | null): string {
  const parts: string[] = [];
  if (view === "week") {
    const from = startOfWeek(anchor);
    const fmt = (iso: string) =>
      new Date(iso + "T00:00:00").toLocaleDateString(undefined, { day: "numeric", month: "short" });
    parts.push(`Week of ${fmt(from)} – ${fmt(addDays(from, 6))}`);
  } else if (view === "month") {
    parts.push(
      new Date(anchor + "T00:00:00").toLocaleDateString(undefined, { month: "long", year: "numeric" })
    );
  }
  if (temple) parts.push(temple);
  return parts.join(" · ");
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
