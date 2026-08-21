"use client";

import { useCallback, useState } from "react";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { Card } from "@/components/ds/Card";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { ErrorNotice } from "@/components/ErrorNotice";
import {
  api,
  toApiError,
  type ApiError,
  type CalendarDayView,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { fullTithiName, masaName } from "@/lib/calendar-names";
import { hhmm, todayIso } from "@/lib/format";
import { BusyPot } from "@/components/Loading";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { MealComposer } from "@/components/planner/MealComposer";
import { MealServices } from "@/components/planner/MealServices";

/**
 * One day of the plan, at its own address — `/planner/2026-08-21`.
 *
 * <p>Three panels, in the order a person needs them: what kind of day this is, what is already
 * planned, and the tool to plan another. The calendar sits at the top because it constrains what may
 * be cooked — but it is context, not the content: the meals are the point of the screen.
 *
 * <p>It was a modal over the calendar until 2026-08-21. A full-screen overlay that the browser knows
 * nothing about is a screen the back button cannot close, so pressing back left the planner
 * altogether instead of closing the day (item 22). A route closes on back, reloads, and can be sent
 * to somebody.
 */
export function DayView({ date }: { date: string }) {
  const { appUser } = useAuth();
  const [error, setError] = useState<ApiError | null>(null);
  const [nonce, setNonce] = useState(0);
  const [composing, setComposing] = useState(false);

  const calQ = useAuthedQuery(
    useCallback((t?: string) => { void nonce; return api.calendarRange(date, date, t); }, [date, nonce])
  );
  const suffQ = useAuthedQuery(
    useCallback((t?: string) => { void nonce; return api.mealSufficiency(date, date, t); }, [date, nonce])
  );
  const { data: recipes } = useAuthedQuery(useCallback((t?: string) => api.listRecipes({}, t), []));
  const { data: mealKinds } = useAuthedQuery(api.listMealKinds);

  const day = calQ.data?.[0];
  const sufficiency = new Map((suffQ.data ?? []).map((s) => [s.mealPlanId, s]));
  const readOnly = date < todayIso();
  const canCorrect = appUser?.role === "TEMPLE_ADMIN" && !readOnly;

  return (
    <div className="grid gap-6">
      {error && <ErrorNotice error={error} />}

      <DayContextPanel
        date={date}
        day={day}
        canCorrect={canCorrect}
        onChanged={() => setNonce((n) => n + 1)}
        onError={setError}
      />

      {/* One block per meal kind, not one row per preparation. The brief means a meal every time it
          says one — one job card per meal kind, recording per meal — so this reads the day the
          same way. */}
      <MealServices
        date={date}
        refreshKey={nonce}
        sufficiency={sufficiency}
        recipes={recipes ?? []}
        readOnly={readOnly}
        onChanged={() => setNonce((n) => n + 1)}
        onError={setError}
      />

      {readOnly ? (
        <InlineNotice tone="info">
          This day has passed, so its plan can be read but not changed.
        </InlineNotice>
      ) : composing ? (
        <MealComposer
          date={date}
          recipes={recipes ?? []}
          mealKinds={mealKinds ?? []}
          isEkadashi={day?.isEkadashi ?? false}
          onClose={() => setComposing(false)}
          onPlanned={() => setNonce((n) => n + 1)}
        />
      ) : (
        <button
          type="button"
          onClick={() => setComposing(true)}
          className="flex min-h-[3.5rem] items-center justify-center gap-2 rounded-lg border border-dashed border-hairline-strong text-ink-secondary transition-colors duration-state hover:bg-raised"
        >
          <span aria-hidden className="text-lg leading-none">+</span>
          Add a meal
        </button>
      )}
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
              <span className="pl-field-inset font-medium text-ink">Ekadashi name</span>
              <input
                name="ekadashiName"
                defaultValue={day.ekadashiName ?? ""}
                className="min-h-touch rounded border border-hairline bg-canvas px-3"
              />
            </label>
            <label className="grid gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Tithi</span>
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
            <span className="pl-field-inset font-medium text-ink">Festival note</span>
            <input name="festivalNote" className="min-h-touch rounded border border-hairline bg-canvas px-3" />
          </label>
          <label className="grid gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Why are you correcting this?</span>
            <textarea
              name="reason"
              required
              rows={2}
              className="rounded border border-hairline bg-canvas px-3 py-2"
            />
            <span className="pl-field-inset text-xs text-ink-muted">Required</span>
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

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <span className="grid">
      <span className="text-xs text-ink-muted">{label}</span>
      <span className="text-sm text-ink">{value}</span>
    </span>
  );
}
