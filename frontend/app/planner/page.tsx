"use client";

import { Suspense, useCallback, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Card } from "@/components/ds/Card";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { PageHeader } from "@/components/ds/PageHeader";
import { Screen } from "@/components/ds/Screen";
import { PeriodNav, periodHeading, stepPeriod } from "@/components/ds/PeriodNav";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { MealComposer } from "@/components/planner/MealComposer";
import { MealServices } from "@/components/planner/MealServices";
import {
  api,
  type ApiError,
  type CalendarDayView,
  type MealServiceView,
  type MealSufficiency,
  type RecipeSummary,
  type WorkforceCount,
  toApiError,
} from "@/lib/api";
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
 * <p><b>A meal is the unit, in all three views</b> (item 15). A lunch of three preparations is one
 * lunch: one job card, one recording, one head count. The day and the week used to draw one row per
 * preparation, which read a normal Tuesday as nine meals.
 *
 * <p><b>What you are looking at is in the address</b> (item 22). The view and the date are query
 * parameters rather than React state, so `/planner?view=week&date=2026-09-15` is a real place,
 * reload keeps you where you were, and the back button moves within the screen instead of throwing
 * you out of it.
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
      {/* The view and the date are read from the query string, and that needs a boundary. */}
      <Suspense>
        <PlannerView />
      </Suspense>
    </RequireRole>
  );
}

function PlannerView() {
  const { appUser, getToken } = useAuth();
  const router = useRouter();
  const params = useSearchParams();

  // Both read from the address rather than held beside it. A second copy in `useState` is how the
  // planner came to change its whole screen without the URL ever moving.
  const view = asView(params.get("view"));
  const anchor = asDate(params.get("date")) ?? todayIso();

  const [composing, setComposing] = useState(false);
  const [nonce, setNonce] = useState(0);
  const [error, setError] = useState<ApiError | null>(null);

  /**
   * Moving to another view, or another date, is a change of what is on screen — so it is a `push`
   * and the back button undoes it. A filter narrowing the same thing would be a `replace`; the
   * planner has none.
   */
  const go = useCallback(
    (next: { view?: View; date?: string }) => {
      const q = new URLSearchParams();
      q.set("view", next.view ?? view);
      q.set("date", next.date ?? anchor);
      router.push(`/planner?${q.toString()}`);
    },
    [router, view, anchor]
  );

  const { from, to } = rangeFor(view, anchor);

  const calQ = useAuthedQuery(
    useCallback((t?: string) => { void nonce; return api.calendarRange(from, to, t); }, [from, to, nonce])
  );
  // Meals, not preparations. The same call the day itself reads, so no two views of the planner can
  // disagree about how many lunches a Thursday holds.
  const mealsQ = useAuthedQuery(
    useCallback((t?: string) => { void nonce; return api.mealServices(from, to, t); }, [from, to, nonce])
  );
  const suffQ = useAuthedQuery(
    useCallback((t?: string) => { void nonce; return api.mealSufficiency(from, to, t); }, [from, to, nonce])
  );
  const { data: recipes } = useAuthedQuery(useCallback((t?: string) => api.listRecipes({}, t), []));
  const { data: mealKinds } = useAuthedQuery(api.listMealKinds);
  // Who is actually in, per day (B3). The same count the week grid's column totals and the Today
  // tile read — one source, so three screens cannot disagree by one about the same Thursday.
  const workforceQ = useAuthedQuery(
    useCallback((t?: string) => { void nonce; return api.workforce(from, to, t); }, [from, to, nonce])
  );

  const calendar = useMemo(() => index(calQ.data ?? [], (d) => d.date), [calQ.data]);
  const meals = useMemo(() => group(livePlans(mealsQ.data ?? []), (m) => m.planDate), [mealsQ.data]);
  const sufficiency = useMemo(() => index(suffQ.data ?? [], (s) => s.mealPlanId), [suffQ.data]);
  const workforce = useMemo(() => index(workforceQ.data ?? [], (w) => w.date), [workforceQ.data]);

  const today = todayIso();
  const isToday = anchor === today;

  /** Landing on a date from Week or Month means opening that day, not a panel over the grid. */
  function pick(date: string) {
    setComposing(false);
    go({ date, view: "day" });
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
          `${r.mealsRefusedOnFast} not copied — they fall on a fast day their recipe doesn’t suit`
        );
      }
      setDuplicated(parts.join(" · ") + ".");
    } catch (e) {
      setDuplicated(toApiError(e, "We couldn’t copy last week.").message);
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
              // The acts of the screen live here, and only here (Rajeev, 2026-08-23). "Plan a meal"
              // used to sit here and was redundant — in Week and Month you plan by pressing the day
              // you mean, and the Day view carries its own control. Copying last week belongs with
              // them rather than beside the view switcher: it is something you do to the plan, not
              // a way of looking at it. Generating the purchase list is what this screen is finally
              // for, so it stays the one accent button.
              <>
                {view === "week" && (
                  <Button variant="secondary" disabled={duplicating} onClick={duplicateLastWeek}>
                    {duplicating ? "Copying…" : "Duplicate last week"}
                  </Button>
                )}
                <ButtonLink href="/order-list">Generate purchase list</ButtonLink>
              </>
            }
            tabs={
              <PeriodNav
                label="Planner view"
                views={VIEWS}
                view={view}
                onView={(v) => go({ view: v })}
                heading={periodHeading(view, anchor)}
                onStep={(delta) => go({ date: stepPeriod(view, anchor, delta) })}
              />
            }
          />

          {calQ.error && <ErrorNotice error={calQ.error} />}
          {error && <ErrorNotice error={error} />}

          {view === "day" && (
            <DayPanel
              date={anchor}
              isToday={isToday}
              workforce={workforce.get(anchor)}
              day={calendar.get(anchor)}
              sufficiency={sufficiency}
              recipes={recipes ?? []}
              readOnly={anchor < today}
              composing={composing}
              nonce={nonce}
              onCompose={() => setComposing(true)}
              onChanged={() => setNonce((n) => n + 1)}
              onError={setError}
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
              <WeekGrid
                from={from}
                today={today}
                calendar={calendar}
                meals={meals}
                workforce={workforce}
                onPick={pick}
              />
            </>
          )}
          {view === "month" && (
            <MonthGrid anchor={anchor} today={today} calendar={calendar} meals={meals} onPick={pick} />
          )}
        </Screen>
      </main>
    </div>
  );
}

/**
 * Staff in, and volunteers signed up, for one day (B3).
 *
 * <p>Two pebbles, never one number. A full-time cook and a two-hour evening volunteer are not
 * interchangeable, and adding them would hide which of the two a day is short of. Deliberately
 * quiet — this is context for the planning, not a warning about it.
 */
function WorkforcePebbles({
  workforce,
  size = "md",
}: {
  workforce: WorkforceCount | undefined;
  size?: "sm" | "md";
}) {
  if (!workforce) return null;

  // Set in the ink colour at semibold, not the muted grey these started in. A pebble carries a
  // whole fact in one glyph and one digit, so if it cannot be read at a glance it is doing nothing
  // — and muted-on-sunken measured 2.57:1, the worst pair in the product, which is exactly how it
  // looked. Ink on sunken is 12.9:1. The icon stays a step back so the number leads.
  const text = size === "sm" ? "text-xs" : "text-sm";
  return (
    <span className={`flex flex-wrap items-center gap-1.5 ${text}`}>
      <span
        className="inline-flex items-center gap-1.5 rounded-full bg-sunken px-2.5 py-1 font-semibold tabular-nums text-ink"
        title={`${workforce.staffIn} staff in`}
      >
        <i aria-hidden="true" className="ti ti-id-badge-2 text-ink-secondary" />
        {workforce.staffIn}
        <span className="sr-only"> staff in</span>
      </span>
      <span
        className="inline-flex items-center gap-1.5 rounded-full bg-sunken px-2.5 py-1 font-semibold tabular-nums text-ink"
        title={`${workforce.volunteers} volunteers signed up`}
      >
        <i aria-hidden="true" className="ti ti-users text-ink-secondary" />
        {workforce.volunteers}
        <span className="sr-only"> volunteers signed up</span>
      </span>
    </span>
  );
}

// ---- Day ----------------------------------------------------------------

/**
 * One day, as the kitchen reads it: what day it is on both calendars, what the day forbids, and then
 * the meals in the order they must be ready.
 *
 * <p>The meals are drawn by {@link MealServices} — one block per meal kind with its preparations
 * beneath, exactly as the day's own screen and the job card read them. This tab used to draw a card
 * per preparation with an `Open` button on each, so a three-preparation lunch was three lunches.
 */
function DayPanel({
  date, isToday, workforce, day, sufficiency, recipes, readOnly, composing, composer, nonce,
  onCompose, onChanged, onError,
}: {
  date: string;
  isToday: boolean;
  workforce: WorkforceCount | undefined;
  day: CalendarDayView | undefined;
  sufficiency: Map<string, MealSufficiency>;
  recipes: RecipeSummary[];
  readOnly: boolean;
  composing: boolean;
  composer: React.ReactNode;
  /** Bumped whenever anything on the day changes, so the meal blocks re-read themselves. */
  nonce: number;
  onCompose: () => void;
  onChanged: () => void;
  onError: (e: ApiError) => void;
}) {
  const festivals = day?.festivals ?? [];

  return (
    <>
      <Card tone="canvas">
        <div className="flex flex-wrap items-start gap-6">
          {/* Two columns, and each holds one kind of thing. On the left, which day this is and who
              is in to cook it; on the right, everything the Vaishnava calendar says about it, read
              downwards from the widest fact to the narrowest: where the day falls, when its light
              begins and ends, and what it asks of the kitchen.

              The two "Open this day" and "Open the calendar" links that used to sit under the
              festival line are gone. They read as plain text until the pointer touched them and
              then grew a box, which is a button pretending not to be one, and neither went
              anywhere this screen does not already reach. */}
          <span className="grid min-w-[16rem] flex-1 gap-1">
            <span className="flex items-center gap-3">
              {isToday && <Badge tone="accent">Today</Badge>}
              <span className="text-2xl font-semibold text-ink">{longDay(date)}</span>
            </span>
            {/* Directly under the date, because "is there anyone to cook this?" is the question a
                planner asks straight after "what day is it?" (B3). */}
            <WorkforcePebbles workforce={workforce} />
          </span>

          <span className="grid max-w-[26rem] justify-items-end gap-2 text-right">
            {day && <span className="text-ink-secondary">{dayLabel(day)}</span>}
            {day?.sunrise && day?.sunset && (
              <span className="text-xs tabular-nums text-ink-muted">
                Sunrise {hhmm(day.sunrise)} &middot; Sunset {hhmm(day.sunset)}
              </span>
            )}
            {day?.isEkadashi && <Badge tone="warning">{day.ekadashiName || "Ekadashi"}</Badge>}
            {festivals.map((f) => (
              <Badge key={f.text} tone="success">
                {f.text}
              </Badge>
            ))}
            {!day?.isEkadashi && festivals.length === 0 && (
              <span className="text-xs text-ink-muted">No festival or fast on this day</span>
            )}
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
        <MealServices
          date={date}
          refreshKey={nonce}
          sufficiency={sufficiency}
          recipes={recipes}
          readOnly={readOnly}
          onChanged={onChanged}
          onError={onError}
        />

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

// ---- Week ---------------------------------------------------------------

/** Seven days side by side, each a way into that day. Today is the one with the outline. */
function WeekGrid({
  from, today, calendar, meals, workforce, onPick,
}: {
  from: string;
  today: string;
  calendar: Map<string, CalendarDayView>;
  meals: Map<string, MealServiceView[]>;
  workforce: Map<string, WorkforceCount>;
  onPick: (date: string) => void;
}) {
  const days = Array.from({ length: 7 }, (_, i) => addDays(from, i));

  return (
    <div className="overflow-x-auto">
      <div className="grid min-w-[900px] grid-cols-7 gap-3">
        {days.map((date) => {
          const day = calendar.get(date);
          const planned = meals.get(date) ?? [];
          const festival = day?.festivals?.[0]?.text;

          return (
            <button
              key={date}
              type="button"
              onClick={() => onPick(date)}
              aria-label={planned.length
                ? `${longDate(date)}, ${planned.length} ${planned.length === 1 ? "meal" : "meals"} planned`
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
                <span className="text-xs uppercase tracking-eyebrow text-ink-muted">
                  {WEEKDAYS[new Date(date + "T00:00:00").getDay()]}
                </span>
                {/* The date is quiet in the prototype — the same size as the body text and not
                    bolded. It is a label on the cell, not the thing you read the cell for. */}
                <span className="text-base text-ink">{Number(date.slice(8, 10))}</span>
              </span>

              {/* The same two pebbles the daily view carries. Not on the monthly grid, which is
                  already fighting for room — clicking a day there opens the daily view (B3). */}
              <WorkforcePebbles workforce={workforce.get(date)} size="sm" />

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
                  // day since they were built — the calendar said terracotta, this said gold.
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
                planned.map((m) => {
                  const dishes = livePreparations(m);
                  return (
                    <span key={m.mealKind} className="grid gap-px border-l-2 border-accent pl-2">
                      <span className="text-xs tabular-nums text-ink">
                        {hhmm(m.readyBy)} {m.mealKind}
                      </span>
                      {/* One meal, one line. The plates are the meal’s own head count and never
                          the sum of its preparations — three preparations at 250 is 250 plates. */}
                      <span className="text-xs text-ink-muted">
                        {dishes.length} {dishes.length === 1 ? "preparation" : "preparations"} ·{" "}
                        {Number(m.plates).toLocaleString("en-IN")} plates
                      </span>
                      {dishes.map((d) => (
                        <span key={d.id} className="truncate text-xs text-ink-secondary">
                          {d.recipeName}
                        </span>
                      ))}
                    </span>
                  );
                })
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
  meals: Map<string, MealServiceView[]>;
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
            <div key={d} className="px-3 py-3 text-xs uppercase tracking-eyebrow text-ink-muted">
              {d}
            </div>
          ))}
        </div>

        <div className="grid grid-cols-7">
          {cells.map((date) => {
            const day = calendar.get(date);
            const planned = meals.get(date) ?? [];
            const festival = day?.festivals?.[0]?.text;

            return (
              <button
                key={date}
                type="button"
                onClick={() => onPick(date)}
                aria-label={planned.length
                  ? `${longDate(date)}, ${planned.length} ${planned.length === 1 ? "meal" : "meals"} planned`
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
                    straight out of the cell. `truncate` alone could not bite here — a flex item’s
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

                {/* One line per meal kind, and no preparation names — a month cell has no room for
                    them, and the day is one press away for anybody who wants them. */}
                {planned.slice(0, 3).map((m) => (
                  <span key={m.mealKind} className="truncate text-xs text-ink-secondary">
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

// --- meals ---------------------------------------------------------------

/**
 * The meals worth drawing: a meal every one of whose preparations was cancelled and never cooked is
 * a meal that did not happen, and the grids say nothing about it rather than leaving a ghost row.
 */
function livePlans(meals: MealServiceView[]): MealServiceView[] {
  return meals.filter((meal) => !meal.dishes.every((d) => d.status === "CANCELLED" && !d.notMade));
}

/** The preparations still part of a meal — a cancelled one counts only if it was cooked anyway. */
function livePreparations(meal: MealServiceView) {
  return meal.dishes.filter((d) => d.status !== "CANCELLED" || d.notMade);
}

// --- dates ---------------------------------------------------------------

function addDays(iso: string, days: number): string {
  const d = new Date(iso + "T00:00:00");
  d.setDate(d.getDate() + days);
  return toIso(d);
}

/**
 * The same day of another month, clamped to the last day where that month is shorter. Stepping
 * from the 31st must land in February rather than skidding into March.
 */
function addMonths(iso: string, months: number): string {
  const d = new Date(iso + "T00:00:00");
  const day = d.getDate();
  d.setDate(1);
  d.setMonth(d.getMonth() + months);
  const lastDay = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
  d.setDate(Math.min(day, lastDay));
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

/** One step of whatever the view is made of: a day in Day, a week in Week, a month in Month. */
function step(view: View, anchor: string, delta: number): string {
  if (view === "day") return addDays(anchor, delta);
  if (view === "week") return addDays(anchor, 7 * delta);
  return addMonths(anchor, delta);
}

/** Whether the anchor is inside the period the kitchen is actually living in. */
function inCurrentPeriod(view: View, anchor: string, today: string): boolean {
  if (view === "day") return anchor === today;
  if (view === "week") return startOfWeek(anchor) === startOfWeek(today);
  return anchor.slice(0, 7) === today.slice(0, 7);
}

/** What the middle button says when it is not saying "Today" — "Tue 12 Aug", "Aug 17–23", "September". */
function periodName(view: View, anchor: string): string {
  if (view === "day") {
    return new Date(anchor + "T00:00:00").toLocaleDateString(undefined, {
      weekday: "short",
      day: "numeric",
      month: "short",
    });
  }
  if (view === "week") {
    const from = startOfWeek(anchor);
    const to = addDays(from, 6);
    const month = (iso: string) =>
      new Date(iso + "T00:00:00").toLocaleDateString(undefined, { month: "short" });
    const day = (iso: string) => Number(iso.slice(8, 10));
    // A week that crosses a month names both — "Aug 31 – Sep 6" — because "Aug 31–6" says nothing.
    return month(from) === month(to)
      ? `${month(from)} ${day(from)}–${day(to)}`
      : `${month(from)} ${day(from)} – ${month(to)} ${day(to)}`;
  }
  const d = new Date(anchor + "T00:00:00");
  const thisYear = new Date().getFullYear();
  return d.toLocaleDateString(
    undefined,
    d.getFullYear() === thisYear ? { month: "long" } : { month: "long", year: "numeric" }
  );
}

/**
 * Whose kitchen this is, and nothing else.
 *
 * <p>The period used to be repeated here — "August 2026 · ISKCON South Bengaluru" over a control
 * that already said August 2026 an inch below. The navigation names where you are; the title says
 * whose plan it is.
 */
function subtitle(view: View, anchor: string, temple: string | null): string {
  return temple ?? "";
}

// --- the address ---------------------------------------------------------

/** Anything that is not one of the three views is the day, which is where the work is. */
function asView(raw: string | null): View {
  return raw === "week" || raw === "month" ? raw : "day";
}

/**
 * A date out of the query string, or null.
 *
 * <p>Checked rather than trusted: `/planner?date=yesterday` would otherwise become an anchor every
 * date calculation on the screen then works from, and every one of them would produce `NaN`.
 */
function asDate(raw: string | null): string | null {
  if (!raw || !/^\d{4}-\d{2}-\d{2}$/.test(raw)) return null;
  return Number.isNaN(new Date(raw + "T00:00:00").getTime()) ? null : raw;
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
