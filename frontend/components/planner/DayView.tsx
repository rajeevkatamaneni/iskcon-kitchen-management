"use client";

import Link from "next/link";

import { useCallback, useState } from "react";
import { Badge } from "@/components/ds/Badge";
import { Card } from "@/components/ds/Card";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { ErrorNotice } from "@/components/ErrorNotice";
import { api, type ApiError, type CalendarDayView } from "@/lib/api";
import { fullTithiName, masaName } from "@/lib/calendar-names";
import { ekadashiLabel, ekadashiSpelling } from "@/lib/vaishnava-day";
import { hhmm, todayIso } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { MealServices } from "@/components/planner/MealServices";
import { withReturn } from "@/components/planner/plannerAddress";

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
export function DayView({ date, returnTo }: { date: string; returnTo?: string }) {
  const [error, setError] = useState<ApiError | null>(null);
  const [nonce, setNonce] = useState(0);

  const calQ = useAuthedQuery(
    useCallback((t?: string) => { void nonce; return api.calendarRange(date, date, t); }, [date, nonce])
  );
  const suffQ = useAuthedQuery(
    useCallback((t?: string) => { void nonce; return api.mealSufficiency(date, date, t); }, [date, nonce])
  );
  const { data: recipes } = useAuthedQuery(useCallback((t?: string) => api.listRecipes({}, t), []));
  const { data: mealKinds } = useAuthedQuery(api.listMealKinds);

  const day = calQ.data?.[0];
  const sufficiency = new Map((suffQ.data ?? []).map((s) => [s.dishId, s]));
  const readOnly = date < todayIso();

  return (
    <div className="grid gap-6">
      {error && <ErrorNotice error={error} />}

      <DayContextPanel day={day} />

      {/* One block per meal kind, not one row per preparation. The brief means a meal every time it
          says one — one job card per meal kind, recording per meal — so this reads the day the
          same way. */}
      <MealServices
        date={date}
        refreshKey={nonce}
        sufficiency={sufficiency}
        recipes={recipes ?? []}
        readOnly={readOnly}
        returnTo={returnTo}
        onChanged={() => setNonce((n) => n + 1)}
        onError={setError}
      />

      {readOnly ? (
        <InlineNotice tone="info">
          This day has passed, so its plan can be read but not changed.
        </InlineNotice>
      ) : (
        // A link rather than an expand since 2026-09-05: planning a meal is the same screen as
        // correcting one, and it is that screen. See app/planner/compose/page.tsx.
        <Link
          href={withReturn(`/planner/compose?date=${date}`, returnTo)}
          className="flex min-h-[3.5rem] items-center justify-center gap-2 rounded-lg border border-dashed border-hairline-strong text-ink-secondary transition-colors duration-state hover:bg-raised"
        >
          <span aria-hidden className="text-lg leading-none">+</span>
          Add a meal
        </Link>
      )}
    </div>
  );
}

/**
 * What the engine worked out for this day, read-only.
 *
 * <p>Correcting it lived here until T-219: a Temple Admin saw "Correct this date" and "Undo the
 * correction" on this page and nowhere else, so it was reached only by landing here by accident from
 * a meal's Cancel. Rajeev, 2026-09-17, moved it to the Vaishnava calendar, which is the screen about
 * what day it is (see `DateCorrection` in app/calendar/page.tsx). What stays is what a cook planning
 * the day needs to know — including that a person, not the engine, said what this day is.
 */
function DayContextPanel({ day }: { day: CalendarDayView | undefined }) {
  if (!day) {
    return (
      <Card tone="sunken">
        <p className="text-sm text-ink-secondary">
          The Vaishnava calendar has not reached this date yet. It is built eighteen months ahead.
        </p>
      </Card>
    );
  }

  return (
    <Card tone="sunken">
      {/* A grid so the note about a hand correction stands 16px off the facts; as plain blocks
          it sat flush against them. */}
      <div className="grid gap-4">
        <div className="flex flex-wrap items-center gap-x-8 gap-y-3">
          <Fact label="Tithi" value={fullTithiName(day.tithi, day.paksa)} />
          <Fact label="Month" value={masaName(day.masa)} />
          <Fact label="Sunrise" value={hhmm(day.sunrise)} />
          {day.isEkadashi && (
            // Blue, as Ekadashi is on the planner and the calendar; accent is not a day-kind colour.
            <Badge tone="info">{ekadashiLabel(day.ekadashiName)} — fasting day</Badge>
          )}
          {day.fastType && <Fact label="Fast" value={day.fastType} />}
          {day.festivals.length > 0 && (
            <Fact label="Festivals" value={day.festivals.map((f) => ekadashiSpelling(f.text)).join(" · ")} />
          )}
        </div>

        {day.overridden && (
          // A record of an admin's correction, nothing for the reader to do, so information (T-227).
          <InlineNotice tone="info" title="This date was corrected by hand">
            {day.overrideReason}
          </InlineNotice>
        )}
      </div>
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
