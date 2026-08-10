"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { TEMPLE_NAV } from "@/lib/nav";
import { fullTithiName } from "@/lib/calendar-names";
import {
  api,
  toApiError,
  type ApiError,
  type CalendarDayView,
  type CreateMealPlanInput,
  type DayContext,
  type MealPlanView,
  type MealSufficiency,
  type RecipeSummary,
  type MealSlotView,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
const WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

export default function PlannerPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF"]}>
      <PlannerView />
    </RequireRole>
  );
}

function PlannerView() {
  const { getToken } = useAuth();
  const today = new Date();
  const [month, setMonth] = useState({ year: today.getFullYear(), m: today.getMonth() });

  // The visible grid: the Sunday on/before the 1st, through the Saturday on/after the last day.
  const { gridStart, gridEnd, weeks } = useMemo(() => buildGrid(month.year, month.m), [month]);
  const from = iso(gridStart);
  const to = iso(gridEnd);

  const fetchCalendar = useCallback((t?: string) => api.calendarRange(from, to, t), [from, to]);
  const fetchMeals = useCallback((t?: string) => api.listMealPlans({ from, to }, t), [from, to]);
  const fetchSuff = useCallback((t?: string) => api.mealSufficiency(from, to, t), [from, to]);

  const [nonce, setNonce] = useState(0);
  const calQ = useAuthedQuery(useCallback((t?: string) => { void nonce; return fetchCalendar(t); }, [fetchCalendar, nonce]));
  const mealsQ = useAuthedQuery(useCallback((t?: string) => { void nonce; return fetchMeals(t); }, [fetchMeals, nonce]));
  const suffQ = useAuthedQuery(useCallback((t?: string) => { void nonce; return fetchSuff(t); }, [fetchSuff, nonce]));
  const { data: recipes } = useAuthedQuery(useCallback((t?: string) => api.listRecipes({}, t), []));
  const { data: slots } = useAuthedQuery(api.listMealSlots);

  const calByDate = useMemo(() => index(calQ.data ?? [], (d) => d.date), [calQ.data]);
  const mealsByDate = useMemo(() => group(mealsQ.data ?? [], (m) => m.planDate), [mealsQ.data]);
  const suffByMeal = useMemo(() => index(suffQ.data ?? [], (s) => s.mealPlanId), [suffQ.data]);

  const [selected, setSelected] = useState<string | null>(null);
  const [actionError, setActionError] = useState<ApiError | null>(null);

  function reloadAll() {
    setNonce((n) => n + 1);
  }

  async function act(fn: (t?: string) => Promise<unknown>, failure: string) {
    setActionError(null);
    try {
      await fn(await getToken());
      reloadAll();
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Your temple" items={TEMPLE_NAV} activeHref="/planner" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-center justify-between gap-4">
            <div>
              <h1>Meal plan</h1>
              <p className="mt-1 text-ink-secondary">The week&rsquo;s cooking on the Vaishnava calendar.</p>
            </div>
            <div className="flex items-center gap-3">
              <button type="button" onClick={() => setMonth(step(month, -1))} className="min-h-touch rounded border border-hairline-strong px-3 hover:bg-sunken" aria-label="Previous month">←</button>
              <span className="min-w-[10rem] text-center text-lg font-medium">{MONTHS[month.m]} {month.year}</span>
              <button type="button" onClick={() => setMonth(step(month, 1))} className="min-h-touch rounded border border-hairline-strong px-3 hover:bg-sunken" aria-label="Next month">→</button>
            </div>
          </header>

          {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}
          {calQ.error && <div className="mb-6"><ErrorNotice error={calQ.error} /></div>}

          <div className="overflow-x-auto">
            <div className="grid grid-cols-7 gap-px rounded-lg bg-hairline" style={{ minWidth: 720 }}>
              {WEEKDAYS.map((w) => (
                <div key={w} className="bg-sunken px-2 py-2 text-center text-xs font-medium text-ink-secondary">{w}</div>
              ))}
              {weeks.flat().map((day) => {
                const key = iso(day);
                const inMonth = day.getMonth() === month.m;
                const cal = calByDate.get(key);
                const meals = mealsByDate.get(key) ?? [];
                return (
                  <DayCell
                    key={key}
                    date={day}
                    inMonth={inMonth}
                    cal={cal}
                    meals={meals}
                    suffByMeal={suffByMeal}
                    onAdd={() => { setSelected(key); setActionError(null); }}
                    onCancel={(id) => act((t) => api.cancelMealPlan(id, t), "We couldn't cancel that meal.")}
                    onCook={(id) => act((t) => api.markMealCooked(id, {}, t), "We couldn't mark it cooked — check stock.")}
                  />
                );
              })}
            </div>
          </div>

          <CateringList reloadKey={nonce} />

          {selected && (
            <AddMealPanel
              date={selected}
              recipes={recipes ?? []}
              slots={slots ?? []}
              isEkadashi={calByDate.get(selected)?.isEkadashi ?? false}
              onClose={() => setSelected(null)}
              onCreated={() => { setSelected(null); reloadAll(); }}
              onError={setActionError}
            />
          )}
        </div>
      </main>
    </div>
  );
}

function DayCell({
  date, inMonth, cal, meals, suffByMeal, onAdd, onCancel, onCook,
}: {
  date: Date;
  inMonth: boolean;
  cal?: CalendarDayView;
  meals: MealPlanView[];
  suffByMeal: Map<string, MealSufficiency>;
  onAdd: () => void;
  onCancel: (id: string) => void;
  onCook: (id: string) => void;
}) {
  const festival = cal?.festivals?.[0]?.text;
  return (
    <div className={`min-h-[7rem] bg-canvas p-1.5 ${inMonth ? "" : "opacity-45"} ${cal?.isEkadashi ? "ring-1 ring-inset ring-accent-border bg-accent-bg/40" : ""}`}>
      <div className="flex items-start justify-between">
        <span className="text-sm font-medium">{date.getDate()}</span>
        <button type="button" onClick={onAdd} className="rounded px-1 text-ink-muted hover:bg-sunken hover:text-accent-text" aria-label={`Add meal on ${iso(date)}`}>＋</button>
      </div>
      {cal && <span className="block truncate text-[10px] text-ink-muted" title={fullTithiName(cal.tithi, cal.paksa)}>{fullTithiName(cal.tithi, cal.paksa)}</span>}
      {cal?.isEkadashi && <span className="mt-0.5 block rounded-sm bg-accent-bg px-1 text-[10px] font-medium text-accent-text">Ekadashi{cal.overridden ? " ·override" : ""}</span>}
      {festival && <span className="mt-0.5 block truncate text-[10px] text-warning" title={festival}>{festival}</span>}
      <ul className="mt-1 space-y-1">
        {meals.map((m) => {
          const suff = suffByMeal.get(m.id);
          return (
            <li key={m.id} className="rounded-sm bg-raised px-1.5 py-1 text-[11px]">
              <div className="flex items-center justify-between gap-1">
                <span className="truncate font-medium" title={`${m.slot}: ${m.recipeName}`}>{m.recipeName}</span>
                <StatusDot status={m.status} suff={suff?.status} />
              </div>
              <div className="text-[10px] text-ink-muted">{m.slot} · {m.targetServings}{m.dayType === "CATERING" ? " · catering" : ""}{m.ekadashiAcknowledged ? " · grain-ack" : ""}</div>
              {m.status === "PLANNED" && (
                <div className="mt-0.5 flex gap-2 text-[10px]">
                  <button type="button" onClick={() => onCook(m.id)} className="text-accent-text hover:underline">Cook</button>
                  <button type="button" onClick={() => onCancel(m.id)} className="text-danger hover:underline">Cancel</button>
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function CateringList({ reloadKey }: { reloadKey: number }) {
  const today = iso(new Date());
  const fetcher = useCallback(
    (t?: string) => { void reloadKey; return api.listMealPlans({ dayType: "CATERING", from: today }, t); },
    [today, reloadKey]
  );
  const { data } = useAuthedQuery(fetcher);
  const commitments = (data ?? []).filter((m) => m.status !== "CANCELLED");

  if (commitments.length === 0) return null;

  return (
    <section className="mt-10">
      <h2 className="mb-3 text-lg">Upcoming catering</h2>
      <div className="overflow-hidden rounded-lg bg-raised">
        <table className="w-full text-left text-sm">
          <thead className="bg-sunken text-ink-secondary">
            <tr>
              <th className="px-5 py-3 font-medium">Date</th>
              <th className="px-5 py-3 font-medium">Client</th>
              <th className="px-5 py-3 font-medium">Recipe</th>
              <th className="px-5 py-3 font-medium text-right">Servings</th>
              <th className="px-5 py-3 font-medium">Venue</th>
            </tr>
          </thead>
          <tbody>
            {commitments.map((m) => (
              <tr key={m.id} className="border-t border-hairline">
                <td className="px-5 py-3 text-ink-secondary">{m.planDate}</td>
                <td className="px-5 py-3">{m.clientName ?? "—"}</td>
                <td className="px-5 py-3 text-ink-secondary">{m.recipeName}</td>
                <td className="px-5 py-3 text-right tabular-nums">{m.targetServings}</td>
                <td className="px-5 py-3 text-ink-secondary">{m.venue ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function StatusDot({ status, suff }: { status: string; suff?: string }) {
  if (status === "COOKED") return <span className="rounded-sm bg-success-bg px-1 text-[9px] text-success">cooked</span>;
  if (status === "CANCELLED") return <span className="rounded-sm bg-sunken px-1 text-[9px] text-ink-muted">cancelled</span>;
  if (suff === "SHORT") return <span className="rounded-sm bg-warning-bg px-1 text-[9px] text-warning">short</span>;
  if (suff === "SUFFICIENT") return <span className="rounded-sm bg-success-bg px-1 text-[9px] text-success">ok</span>;
  return <span className="rounded-sm bg-sunken px-1 text-[9px] text-ink-muted">planning</span>;
}

function AddMealPanel({
  date, recipes, slots, isEkadashi, onClose, onCreated, onError,
}: {
  date: string;
  recipes: RecipeSummary[];
  slots: MealSlotView[];
  isEkadashi: boolean;
  onClose: () => void;
  onCreated: () => void;
  onError: (e: ApiError) => void;
}) {
  const { getToken } = useAuth();
  const [ctx, setCtx] = useState<DayContext | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmGrain, setConfirmGrain] = useState<null | string[]>(null);

  // Pre-fill day-type + servings from the calendar.
  useEffect(() => {
    let cancelled = false;
    getToken().then((t) => api.mealDayContext(date, t)).then((c) => { if (!cancelled) setCtx(c); }).catch(() => {});
    return () => { cancelled = true; };
  }, [date, getToken]);

  async function submit(acknowledge: boolean) {
    const form = document.getElementById("add-meal-form") as HTMLFormElement;
    const f = new FormData(form);
    const dayType = String(f.get("dayType") || "");
    setBusy(true);
    try {
      await api.createMealPlan(
        {
          planDate: date,
          slot: String(f.get("slot") ?? ""),
          recipeId: String(f.get("recipeId") ?? ""),
          targetServings: Number(f.get("targetServings") ?? 0),
          dayType: (dayType || null) as CreateMealPlanInput["dayType"],
          clientName: emptyToNull(String(f.get("clientName") ?? "")),
          venue: emptyToNull(String(f.get("venue") ?? "")),
          ekadashiAcknowledged: acknowledge,
        },
        await getToken()
      );
      onCreated();
    } catch (e) {
      const err = toApiError(e, "We couldn't plan that meal.");
      if (err.code === "KMS-4917" && !acknowledge) {
        // Grain/bean recipe on Ekadashi — confirm before proceeding.
        const recipeId = String(f.get("recipeId") ?? "");
        const check = await api.ekadashiCheck(date, recipeId, await getToken()).catch(() => null);
        setConfirmGrain(check?.offendingIngredients ?? []);
      } else {
        onError(err);
        onClose();
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="mt-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="add-meal-heading">
      <div className="flex items-center justify-between">
        <h2 id="add-meal-heading" className="text-lg">Plan a meal — {date}{isEkadashi ? " (Ekadashi)" : ""}</h2>
        <button type="button" onClick={onClose} className="text-sm text-ink-secondary hover:underline">Close</button>
      </div>

      <form id="add-meal-form" className="mt-4 grid grid-cols-2 gap-4" aria-label="Plan a meal" onSubmit={(e) => { e.preventDefault(); submit(false); }}>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Slot
          <select name="slot" required className="min-h-touch rounded border border-hairline bg-canvas px-3">
            {slots.map((s) => <option key={s.id} value={s.name}>{s.name}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Recipe
          <select name="recipeId" required className="min-h-touch rounded border border-hairline bg-canvas px-3">
            <option value="">Choose a recipe…</option>
            {recipes.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Servings
          <input name="targetServings" type="number" min="1" step="any" required defaultValue={ctx?.suggestedServings ?? 100} key={ctx?.suggestedServings ?? "s"} className="min-h-touch rounded border border-hairline bg-canvas px-3" />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Day type
          <select name="dayType" defaultValue={ctx?.suggestedDayType ?? "REGULAR"} key={ctx?.suggestedDayType ?? "d"} className="min-h-touch rounded border border-hairline bg-canvas px-3">
            <option value="REGULAR">Regular</option>
            <option value="WEEKEND">Weekend</option>
            <option value="FESTIVAL">Festival{ctx?.occasionName ? ` (${ctx.occasionName})` : ""}</option>
            <option value="CATERING">Catering</option>
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Catering client (if catering)
          <input name="clientName" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          Venue
          <input name="venue" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
        </label>

        {confirmGrain ? (
          <div className="col-span-2 rounded border border-warning/30 bg-warning-bg p-3 text-sm text-warning" role="alert">
            <p className="font-medium">This recipe has grains or beans, and {date} is Ekadashi.</p>
            {confirmGrain.length > 0 && <p className="mt-1">Contains: {confirmGrain.join(", ")}.</p>}
            <div className="mt-2 flex gap-3">
              <button type="button" disabled={busy} onClick={() => submit(true)} className="min-h-touch rounded bg-accent px-4 text-ink-inverse hover:bg-accent-hover">Plan it anyway</button>
              <button type="button" onClick={() => setConfirmGrain(null)} className="min-h-touch rounded px-3 text-ink-secondary hover:underline">Choose another recipe</button>
            </div>
          </div>
        ) : (
          <div className="col-span-2">
            <button type="submit" disabled={busy} className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
              Add to plan
            </button>
          </div>
        )}
      </form>
    </section>
  );
}

// ---------------------------------------------------------------------

function buildGrid(year: number, m: number) {
  const first = new Date(year, m, 1);
  const gridStart = new Date(first);
  gridStart.setDate(1 - first.getDay()); // back up to Sunday
  const last = new Date(year, m + 1, 0);
  const gridEnd = new Date(last);
  gridEnd.setDate(last.getDate() + (6 - last.getDay())); // forward to Saturday

  const weeks: Date[][] = [];
  const cursor = new Date(gridStart);
  while (cursor <= gridEnd) {
    const week: Date[] = [];
    for (let i = 0; i < 7; i++) {
      week.push(new Date(cursor));
      cursor.setDate(cursor.getDate() + 1);
    }
    weeks.push(week);
  }
  return { gridStart, gridEnd, weeks };
}

function step(month: { year: number; m: number }, by: number) {
  const d = new Date(month.year, month.m + by, 1);
  return { year: d.getFullYear(), m: d.getMonth() };
}

function index<T>(items: T[], key: (t: T) => string): Map<string, T> {
  const map = new Map<string, T>();
  items.forEach((i) => map.set(key(i), i));
  return map;
}

function group<T>(items: T[], key: (t: T) => string): Map<string, T[]> {
  const map = new Map<string, T[]>();
  items.forEach((i) => {
    const k = key(i);
    (map.get(k) ?? map.set(k, []).get(k)!).push(i);
  });
  return map;
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
