"use client";

import { useParams, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { PageHeader } from "@/components/ds/PageHeader";
import { Screen } from "@/components/ds/Screen";
import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { DayView } from "@/components/planner/DayView";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { dayLabel } from "@/lib/calendar-names";
import { longDay } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * One day of the plan (E4-S7), at its own address.
 *
 * <p>This was a modal over the calendar grid. The browser knows nothing about a modal, so the back
 * button closed the planner instead of closing the day, and the day could not be reloaded or sent to
 * anybody (item 22). It is a route now, and all three of those work by themselves.
 */
export default function PlannerDayPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* The confirmation the edit screen sends back arrives in the query string. */}
      <Suspense>
        <PlannerDayScreen />
      </Suspense>
    </RequireRole>
  );
}

function PlannerDayScreen() {
  const params = useParams<{ date: string }>();
  const { appUser } = useAuth();
  const raw = Array.isArray(params?.date) ? params.date[0] : params?.date;
  const date = asDate(raw ?? null);

  const router = useRouter();
  const search = useSearchParams();
  const [saved, setSaved] = useState<string | null>(null);
  // Guarded by a ref so it fires exactly once: setting the flash re-renders, and a router object
  // that is fresh on each render would otherwise re-trigger this into a loop.
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current) return;
    const kind = search.get("saved");
    if (!kind) return;
    captured.current = true;
    setSaved(kind);
    if (date) router.replace(`/planner/${date}`);
  }, [search, router, date]);

  const calQ = useAuthedQuery(
    useCallback((t?: string) => (date ? api.calendarRange(date, date, t) : Promise.resolve([])), [date])
  );
  const day = calQ.data?.[0];

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/planner" />
      <main className="min-w-0 flex-1">
        <Screen>
          {date ? (
            <>
              <PageHeader
                title={longDay(date)}
                subtitle={
                  [day ? dayLabel(day) : null, appUser?.tenantName ?? null].filter(Boolean).join(" · ") ||
                  undefined
                }
                actions={
                  <>
                    <ButtonLink href={`/planner?view=week&date=${date}`} variant="secondary">
                      The week around it
                    </ButtonLink>
                    <ButtonLink href="/calendar" variant="secondary">
                      Open the calendar
                    </ButtonLink>
                  </>
                }
              />
              {saved && (
                <div className="mb-6">
                  <InlineNotice tone="success" autoDismiss title={`${saved} was saved.`} />
                </div>
              )}
              <DayView date={date} />
            </>
          ) : (
            <EmptyState
              title="That is not a date"
              action={<ButtonLink href="/planner">Open the planner</ButtonLink>}
            >
              A day of the plan is addressed by its date, written as 2026-08-21.
            </EmptyState>
          )}
        </Screen>
      </main>
    </div>
  );
}

/** A date out of the path, or null. Checked rather than trusted — every sum on the screen uses it. */
function asDate(raw: string | null): string | null {
  if (!raw || !/^\d{4}-\d{2}-\d{2}$/.test(raw)) return null;
  return Number.isNaN(new Date(raw + "T00:00:00").getTime()) ? null : raw;
}
