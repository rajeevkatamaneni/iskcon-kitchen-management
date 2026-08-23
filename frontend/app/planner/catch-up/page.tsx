"use client";

import { useCallback, useMemo, useState } from "react";
import { Card } from "@/components/ds/Card";
import { PageHeader } from "@/components/ds/PageHeader";
import { Screen } from "@/components/ds/Screen";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { MealServices } from "@/components/planner/MealServices";
import { api, type ApiError, type MealSufficiency } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { longDay, todayIso } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Catching up on the meals nobody wrote down.
 *
 * <p>Today's nudge says ten meals have gone unrecorded, and pressing it used to open the planner on
 * today — a screen showing the one day that is certainly not the problem. The person then had to
 * remember which ten, and step back through the week a day at a time to find them. So this screen
 * is the answer to the nudge rather than a place near it: every day that still owes a recording,
 * oldest first, and inside each day only the meals that owe one.
 *
 * <p>It has no Day / Week / Month — those move you around a calendar, and this is not a view of the
 * calendar. It is a queue, and it empties. A day recorded leaves the screen; when the last one
 * goes, the screen says so and stops asking.
 *
 * <p>Reachable only from that nudge, which is why it is not in the menu: it is a way of clearing a
 * backlog, not a place to work.
 */

/** The same seven days back the nudge on Today counts over. */
const NUDGE_DAYS = 7;

export default function CatchUpPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <CatchUpView />
    </RequireRole>
  );
}

function CatchUpView() {
  const { appUser } = useAuth();
  const today = todayIso();
  const from = addDays(today, -NUDGE_DAYS);
  const to = addDays(today, -1);

  const [nonce, setNonce] = useState(0);
  const [error, setError] = useState<ApiError | null>(null);
  /** What was just cleared, said out loud before its card goes. */
  const [cleared, setCleared] = useState<string | null>(null);

  const { data, error: loadError, loading } = useAuthedQuery(
    useCallback(
      (t?: string) => {
        void nonce;
        return api.mealServices(from, to, t);
      },
      [from, to, nonce]
    )
  );

  // Sufficiency has nothing to say about a meal that has already been cooked, and this screen is
  // only ever about days that have been. Passed empty rather than fetched.
  const sufficiency = useMemo(() => new Map<string, MealSufficiency>(), []);
  const { data: recipes } = useAuthedQuery(useCallback((t?: string) => api.listRecipes({}, t), []));

  const owing = useMemo(() => {
    const dates = new Set<string>();
    for (const meal of data ?? []) {
      if (!meal.recorded && meal.dishes.some((dish) => dish.status === "PLANNED")) {
        dates.add(meal.planDate);
      }
    }
    // Oldest first: the one that has been wrong in the store room the longest is the one to clear.
    return Array.from(dates).sort();
  }, [data]);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/today" />

      <main className="min-w-0 flex-1">
        <Screen>
          <PageHeader
            title="Meals that were not recorded"
            subtitle={appUser?.tenantName ?? ""}
            actions={
              <ButtonLink href="/today" variant="secondary">
                Back to Today
              </ButtonLink>
            }
          />

          {error && <ErrorNotice error={error} />}
          {loadError && <ErrorNotice error={loadError} />}

          {cleared && (
            <InlineNotice tone="success" autoDismiss title={cleared}>
              The store room now knows what those preparations actually drew.
            </InlineNotice>
          )}

          {loading && !data && <Loading label="Finding what is outstanding…" />}

          {data && owing.length === 0 && (
            <Card tone="canvas">
              <div className="grid justify-items-center gap-3 py-10 text-center">
                <p className="text-lg font-semibold text-ink">You are all caught up.</p>
                <p className="max-w-prose text-ink-secondary">
                  Every meal of the last week has been written down, and the store room agrees with
                  the kitchen. Thank you — this is the part nobody sees and everything else rests on.
                </p>
                <ButtonLink href="/today">Back to Today</ButtonLink>
              </div>
            </Card>
          )}

          {owing.map((date) => (
            <section key={date} className="grid gap-3">
              <h2 className="text-lg font-semibold text-ink">{longDay(date)}</h2>
              <MealServices
                date={date}
                refreshKey={nonce}
                only="unrecorded"
                sufficiency={sufficiency}
                recipes={recipes ?? []}
                // The day has been and gone: it can still be written down, never re-planned.
                readOnly
                onChanged={() => {
                  setCleared(`${longDay(date)} is recorded.`);
                  setNonce((n) => n + 1);
                }}
                onError={setError}
              />
            </section>
          ))}
        </Screen>
      </main>
    </div>
  );
}

function addDays(iso: string, days: number): string {
  const d = new Date(iso + "T00:00:00");
  d.setDate(d.getDate() + days);
  return [
    d.getFullYear(),
    String(d.getMonth() + 1).padStart(2, "0"),
    String(d.getDate()).padStart(2, "0"),
  ].join("-");
}
