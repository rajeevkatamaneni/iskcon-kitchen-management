"use client";

import { useCallback, useEffect, useState } from "react";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { Card } from "@/components/ds/Card";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { ErrorNotice } from "@/components/ErrorNotice";
import {
  api,
  toApiError,
  type ApiError,
  type CalendarDayView,
  type MealKindView,
  type MealPlanView,
  type MealSufficiency,
  type RecipeSummary,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { fullTithiName, masaName } from "@/lib/calendar-names";
import { hhmm, longDate } from "@/lib/format";
import { BusyPot } from "@/components/Loading";

/**
 * One day, full screen (E4-S7).
 *
 * <p>Three panels, in the order a person needs them: what kind of day this is, what is already
 * planned, and the tool to plan another. The calendar sits at the top because it constrains what may
 * be cooked — but it is context, not the content: the meals are the point of the screen.
 *
 * <p>It opens over the calendar rather than below it. The first version rendered a panel a thousand
 * pixels under the grid, so clicking a day appeared to do nothing at all (UAT031-1).
 */
export function DayView({
  date,
  day,
  meals,
  sufficiency,
  recipes,
  mealKinds,
  canCorrect,
  readOnly,
  onClose,
  onChanged,
}: {
  date: string;
  day: CalendarDayView | undefined;
  meals: MealPlanView[];
  sufficiency: Map<string, MealSufficiency>;
  recipes: RecipeSummary[];
  mealKinds: MealKindView[];
  canCorrect: boolean;
  /** A day that has been and gone: it can be read, not changed. */
  readOnly: boolean;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [error, setError] = useState<ApiError | null>(null);

  // Escape closes, as it does in every dialog people have ever used.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const planned = meals.filter((m) => m.status !== "CANCELLED");

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={longDate(date)}
      className="fixed inset-0 z-50 overflow-y-auto bg-ink/40"
    >
      <div className="mx-auto my-0 min-h-full w-full max-w-content bg-canvas sm:my-8 sm:min-h-0 sm:rounded-lg">
        <header className="flex items-start justify-between gap-6 border-b border-hairline px-8 py-6">
          <div className="grid gap-1">
            <h2 className="text-2xl font-semibold text-ink">{longDate(date)}</h2>
            <p className="text-ink-secondary">
              {planned.length === 0
                ? "Nothing planned yet"
                : `${planned.length} ${planned.length === 1 ? "meal" : "meals"} planned`}
            </p>
          </div>
          <Button variant="ghost" onClick={onClose} icon="x">
            Close
          </Button>
        </header>

        <div className="grid gap-6 px-8 py-6">
          {error && <ErrorNotice error={error} />}

          <DayContextPanel
            date={date}
            day={day}
            canCorrect={canCorrect && !readOnly}
            onChanged={onChanged}
            onError={setError}
          />

          <MealStack
            meals={planned}
            sufficiency={sufficiency}
            readOnly={readOnly}
            onChanged={onChanged}
            onError={setError}
          />

          {readOnly ? (
            <InlineNotice tone="info">
              This day has passed, so its plan can be read but not changed.
            </InlineNotice>
          ) : (
            <PlanAMeal
              date={date}
              recipes={recipes}
              mealKinds={mealKinds}
              isEkadashi={day?.isEkadashi ?? false}
              onChanged={onChanged}
              onError={setError}
            />
          )}
        </div>
      </div>
    </div>
  );
}

/** What the engine worked out for this day — and, for a Temple Admin, the correction to it. */
function DayContextPanel({
  date,
  day,
  canCorrect,
  onChanged,
  onError,
}: {
  date: string;
  day: CalendarDayView | undefined;
  canCorrect: boolean;
  onChanged: () => void;
  onError: (e: ApiError) => void;
}) {
  const { getToken } = useAuth();
  const [correcting, setCorrecting] = useState(false);
  const [busy, setBusy] = useState(false);

  if (!day) {
    return (
      <Card tone="sunken">
        <p className="text-sm text-ink-secondary">
          The Vaishnava calendar hasn&rsquo;t been worked out for this date yet. It is built about
          eighteen months ahead.
        </p>
      </Card>
    );
  }

  async function run(fn: (t: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    try {
      await fn(await getToken());
      setCorrecting(false);
      onChanged();
    } catch (e) {
      onError(toApiError(e, failure));
    } finally {
      setBusy(false);
    }
  }

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    await run(
      (t) =>
        api.setCalendarOverride(
          date,
          {
            isEkadashi: f.get("isEkadashi") === "on",
            ekadashiName: String(f.get("ekadashiName") ?? "").trim() || null,
            tithi: Number(f.get("tithi")),
            festivalNote: String(f.get("festivalNote") ?? "").trim() || null,
            reason: String(f.get("reason") ?? "").trim(),
          },
          t
        ),
      "We couldn't correct that date."
    );
  }

  return (
    <Card tone="sunken">
      <div className="flex flex-wrap items-center gap-x-8 gap-y-3">
        <Fact label="Tithi" value={fullTithiName(day.tithi, day.paksa)} />
        <Fact label="Month" value={masaName(day.masa)} />
        <Fact label="Sunrise" value={hhmm(day.sunrise)} />
        {day.isEkadashi && (
          <Badge tone="accent">{day.ekadashiName || "Ekadashi"} — fasting day</Badge>
        )}
        {day.fastType && <Fact label="Fast" value={day.fastType} />}
        {day.festivals.length > 0 && (
          <Fact label="Festivals" value={day.festivals.map((f) => f.text).join(" · ")} />
        )}
      </div>

      {day.overridden && (
        <InlineNotice tone="warning" title="This date was corrected by hand">
          {day.overrideReason}
          {canCorrect && (
            <div className="mt-3">
              <Button
                size="sm"
                variant="ghost"
                disabled={busy}
                onClick={() => run((t) => api.revertCalendarOverride(date, t), "We couldn't undo that.")}
              >
                {busy ? (<span className="inline-flex items-center gap-2"><BusyPot />Undoing…</span>) : "Undo the correction"}
              </Button>
            </div>
          )}
        </InlineNotice>
      )}

      {canCorrect && !correcting && (
        <div className="mt-4">
          <Button size="sm" variant="secondary" onClick={() => setCorrecting(true)}>
            Correct this date
          </Button>
        </div>
      )}

      {canCorrect && correcting && (
        <form onSubmit={save} aria-label="Correct this date" className="mt-4 grid gap-4 border-t border-hairline pt-4">
          <p className="text-sm text-ink-secondary">
            The calendar is worked out from this temple&rsquo;s own location. Correct it only when you
            know it to be wrong here. Everyone will see that it was corrected, and why.
          </p>
          <label className="flex items-start gap-3 text-sm">
            <input
              type="checkbox"
              name="isEkadashi"
              defaultChecked={day.isEkadashi}
              className="mt-1 h-5 w-5 rounded-sm border-hairline-strong"
            />
            <span className="font-medium text-ink">This is an Ekadashi fasting day</span>
          </label>
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="grid gap-1 text-sm text-ink-secondary">
              Ekadashi name
              <input
                name="ekadashiName"
                defaultValue={day.ekadashiName ?? ""}
                className="min-h-touch rounded border border-hairline bg-canvas px-3"
              />
            </label>
            <label className="grid gap-1 text-sm text-ink-secondary">
              Tithi
              <select
                name="tithi"
                defaultValue={day.tithi}
                className="min-h-touch rounded border border-hairline bg-canvas px-3"
              >
                {Array.from({ length: 30 }, (_, i) => (
                  <option key={i} value={i}>
                    {fullTithiName(i, i < 15 ? 0 : 1)}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <label className="grid gap-1 text-sm text-ink-secondary">
            Festival note
            <input name="festivalNote" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
          </label>
          <label className="grid gap-1 text-sm text-ink-secondary">
            Why are you correcting this? (required)
            <textarea
              name="reason"
              required
              rows={2}
              className="rounded border border-hairline bg-canvas px-3 py-2"
            />
          </label>
          <div className="flex items-center gap-3">
            <Button type="submit" size="sm" disabled={busy}>
              {busy ? (<span className="inline-flex items-center gap-2"><BusyPot />Saving…</span>) : "Save correction"}
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => setCorrecting(false)}>
              Cancel
            </Button>
          </div>
        </form>
      )}
    </Card>
  );
}

/** The day's meals, in the order the kitchen works: by when each has to be ready. */
function MealStack({
  meals,
  sufficiency,
  readOnly,
  onChanged,
  onError,
}: {
  meals: MealPlanView[];
  sufficiency: Map<string, MealSufficiency>;
  readOnly: boolean;
  onChanged: () => void;
  onError: (e: ApiError) => void;
}) {
  const { getToken } = useAuth();
  const [busy, setBusy] = useState<string | null>(null);

  async function act(id: string, fn: (t: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(id);
    try {
      await fn(await getToken());
      onChanged();
    } catch (e) {
      onError(toApiError(e, failure));
    } finally {
      setBusy(null);
    }
  }

  if (meals.length === 0) {
    return (
      <EmptyState title="Nothing planned for this day">
        {readOnly
          ? "No meals were planned for this day."
          : "Add the day's meals below — each one needs a recipe, how many it serves, and the time it must be ready."}
      </EmptyState>
    );
  }

  return (
    <Card title="Planned for this day" meta="In the order they have to be ready" padding="p-6">
      <div className="grid">
        {meals.map((m) => {
          const suff = sufficiency.get(m.id);
          return (
            <div
              key={m.id}
              className="flex flex-wrap items-center gap-4 border-t border-hairline py-3 first:border-t-0"
            >
              <span className="w-16 flex-none tabular-nums text-sm font-medium text-ink">
                {hhmm(m.readyBy)}
              </span>
              <span className="grid flex-1">
                <span className="text-lg font-medium text-ink">
                  {m.mealKind} — {m.recipeName}
                </span>
                <span className="text-xs text-ink-muted">
                  {Number(m.targetServings).toLocaleString()} servings
                  {m.clientName ? ` · for ${m.clientName}` : ""}
                  {m.venue ? ` · ${m.venue}` : ""}
                  {m.ekadashiAcknowledged ? " · grains on a fasting day, acknowledged" : ""}
                </span>
              </span>

              {m.status === "COOKED" ? (
                <Badge tone="success">Cooked</Badge>
              ) : suff?.status === "SHORT" ? (
                <Badge tone="warning">Short of ingredients</Badge>
              ) : suff?.status === "SUFFICIENT" ? (
                <Badge tone="success">Ingredients ready</Badge>
              ) : (
                <Badge>Planned</Badge>
              )}

              {!readOnly && m.status === "PLANNED" && (
                <span className="flex gap-2">
                  <Button
                    size="sm"
                    variant="secondary"
                    disabled={busy === m.id}
                    onClick={() => act(m.id, (t) => api.markMealCooked(m.id, {}, t), "We couldn't mark it cooked — check stock.")}
                  >
                    {busy === m.id ? (<span className="inline-flex items-center gap-2"><BusyPot />Working…</span>) : "Mark cooked"}
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={busy === m.id}
                    onClick={() => act(m.id, (t) => api.cancelMealPlan(m.id, t), "We couldn't cancel that meal.")}
                  >
                    Cancel
                  </Button>
                </span>
              )}
            </div>
          );
        })}
      </div>
    </Card>
  );
}

/** The planning tool. Never a dead end: with no recipes it says so and points at Recipes. */
function PlanAMeal({
  date,
  recipes,
  mealKinds,
  isEkadashi,
  onChanged,
  onError,
}: {
  date: string;
  recipes: RecipeSummary[];
  mealKinds: MealKindView[];
  isEkadashi: boolean;
  onChanged: () => void;
  onError: (e: ApiError) => void;
}) {
  const { getToken } = useAuth();
  const [kindName, setKindName] = useState(mealKinds[0]?.name ?? "");
  const [busy, setBusy] = useState(false);
  const [confirmGrain, setConfirmGrain] = useState<string[] | null>(null);

  const kind = mealKinds.find((k) => k.name === kindName);

  const submit = useCallback(
    async (acknowledge: boolean) => {
      const form = document.getElementById("plan-a-meal") as HTMLFormElement | null;
      if (!form) return;
      const f = new FormData(form);
      setBusy(true);
      try {
        await api.createMealPlan(
          {
            planDate: date,
            mealKind: String(f.get("mealKind") ?? ""),
            recipeId: String(f.get("recipeId") ?? ""),
            targetServings: Number(f.get("targetServings") ?? 0),
            readyBy: String(f.get("readyBy") ?? "") || null,
            clientName: String(f.get("clientName") ?? "").trim() || null,
            clientContact: String(f.get("clientContact") ?? "").trim() || null,
            venue: String(f.get("venue") ?? "").trim() || null,
            ekadashiAcknowledged: acknowledge,
          },
          await getToken()
        );
        setConfirmGrain(null);
        form.reset();
        onChanged();
      } catch (e) {
        const err = toApiError(e, "We couldn't plan that meal.");
        if (err.code === "KMS-4917" && !acknowledge) {
          const check = await api
            .ekadashiCheck(date, String(f.get("recipeId") ?? ""), await getToken())
            .catch(() => null);
          setConfirmGrain(check?.offendingIngredients ?? []);
        } else {
          onError(err);
        }
      } finally {
        setBusy(false);
      }
    },
    [date, getToken, onChanged, onError]
  );

  if (recipes.length === 0) {
    return (
      <EmptyState
        title="No recipes yet"
        action={
          <Button onClick={() => (window.location.href = "/recipes")}>Add a recipe</Button>
        }
      >
        A meal is a recipe cooked for a number of people, so the temple needs at least one recipe
        before anything can be planned.
      </EmptyState>
    );
  }

  return (
    <Card title="Plan another meal" meta={isEkadashi ? "This is a fasting day — grain and bean dishes will ask you to confirm" : undefined}>
      <form
        id="plan-a-meal"
        aria-label="Plan a meal"
        className="grid gap-4 sm:grid-cols-2"
        onSubmit={(e) => {
          e.preventDefault();
          submit(false);
        }}
      >
        <label className="grid gap-1 text-sm text-ink-secondary">
          Meal
          <select
            name="mealKind"
            required
            value={kindName}
            onChange={(e) => setKindName(e.target.value)}
            className="min-h-touch rounded border border-hairline bg-canvas px-3"
          >
            {mealKinds.map((k) => (
              <option key={k.id} value={k.name}>
                {k.name}
              </option>
            ))}
          </select>
        </label>

        <label className="grid gap-1 text-sm text-ink-secondary">
          Ready by{kind && !kind.defaultReadyTime ? " (required)" : ""}
          <input
            type="time"
            name="readyBy"
            required={!kind?.defaultReadyTime}
            defaultValue={kind?.defaultReadyTime?.slice(0, 5) ?? ""}
            key={kind?.id ?? "none"}
            className="min-h-touch rounded border border-hairline bg-canvas px-3"
          />
        </label>

        <label className="grid gap-1 text-sm text-ink-secondary">
          Recipe
          <select name="recipeId" required className="min-h-touch rounded border border-hairline bg-canvas px-3">
            <option value="">Choose a recipe…</option>
            {recipes.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </label>

        <label className="grid gap-1 text-sm text-ink-secondary">
          Servings
          <input
            name="targetServings"
            type="number"
            min="1"
            step="any"
            required
            defaultValue={100}
            className="min-h-touch rounded border border-hairline bg-canvas px-3 tabular-nums"
          />
        </label>

        {kind?.needsClient && (
          <>
            <label className="grid gap-1 text-sm text-ink-secondary">
              Who is it for? (required)
              <input name="clientName" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
            </label>
            <label className="grid gap-1 text-sm text-ink-secondary">
              Their contact
              <input name="clientContact" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
            </label>
          </>
        )}

        {kind?.needsVenue && (
          <label className="grid gap-1 text-sm text-ink-secondary sm:col-span-2">
            Where is it going? (required)
            <input name="venue" required className="min-h-touch rounded border border-hairline bg-canvas px-3" />
          </label>
        )}

        {confirmGrain ? (
          <div className="sm:col-span-2">
            <InlineNotice
              tone="warning"
              title="This recipe has grains or beans, and this is a fasting day"
              action={
                <span className="flex gap-3">
                  <Button size="sm" disabled={busy} onClick={() => submit(true)}>
                    Plan it anyway
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => setConfirmGrain(null)}>
                    Choose another recipe
                  </Button>
                </span>
              }
            >
              {confirmGrain.length > 0 && <>Contains {confirmGrain.join(", ")}.</>}
            </InlineNotice>
          </div>
        ) : (
          <div className="sm:col-span-2">
            <Button type="submit" disabled={busy}>
              {busy ? (<span className="inline-flex items-center gap-2"><BusyPot />Adding…</span>) : "Add to the plan"}
            </Button>
          </div>
        )}
      </form>
    </Card>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <span className="grid">
      <span className="text-xs text-ink-muted">{label}</span>
      <span className="text-sm text-ink">{value}</span>
    </span>
  );
}
