"use client";

import Link from "next/link";
import { useCallback } from "react";
import { Badge } from "@/components/ds/Badge";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Card } from "@/components/ds/Card";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { PageHeader } from "@/components/ds/PageHeader";
import { Screen } from "@/components/ds/Screen";
import { StatTile } from "@/components/ds/StatTile";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { api, type TodayDelivery, type TodayMeal, type TodayView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { dayLabel } from "@/lib/calendar-names";
import { hhmm, longDay, shortDate } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

/**
 * Today — the temple's morning screen (E4-S8).
 *
 * <p>Where a temple admin or kitchen staff member lands after signing in. It answers four questions
 * in a line each: how much are we cooking, what are we about to run out of, who is missing from a
 * shift, and what has come in. Each is a way into the screen that acts on it.
 *
 * <p>It reads and never writes. Every action on it is a link — a dashboard that also mutates is how
 * two screens end up disagreeing about the same fact.
 */
export default function TodayPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <TodayScreen />
    </RequireRole>
  );
}

function TodayScreen() {
  const { appUser } = useAuth();
  const load = useCallback((token?: string) => api.today(token), []);
  const { data, error, loading } = useAuthedQuery<TodayView>(load);

  return (
    <div className="flex min-h-screen bg-canvas">
      <Sidebar activeHref="/today" />
      <main className="flex-1">
        <Screen>
          <PageHeader
            title={data ? longDay(data.date) : "Today"}
            subtitle={data ? summarise(data) : undefined}
            actions={
              <>
                <ButtonLink href="/planner" variant="secondary">
                  Open planner
                </ButtonLink>
                <ButtonLink href="/orders">Record a delivery</ButtonLink>
              </>
            }
          />

          {error && <ErrorNotice error={error} />}
          {loading && !data && <Loading />}

          {data && (
            <>
              {fastingNotice(data)}
              {aheadNotice(data)}

              <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
                <StatTile
                  label="Plates today"
                  value={data.platesToday.toLocaleString("en-IN")}
                  icon="bowl"
                  href="/planner"
                  note={
                    data.meals.length
                      ? data.meals.map((m) => `${m.mealKind} ${m.targetServings}`).join(" · ")
                      : "Nothing planned yet — plan a meal"
                  }
                />
                <StatTile
                  label="Items below par"
                  value={data.itemsBelowThreshold}
                  tone={data.itemsBelowThreshold > 0 ? "warning" : "neutral"}
                  icon="package"
                  href="/inventory"
                  note={
                    data.itemsTracked === 0
                      ? "Nothing is tracked yet — add what the store holds"
                      : data.itemsBelowThreshold > 0
                        ? "Order these before they run out"
                        : "Everything tracked is above its reorder level"
                  }
                />
                <StatTile
                  label="Shifts unfilled"
                  value={data.unfilledShiftSpots}
                  tone={data.unfilledShiftSpots > 0 ? "danger" : "neutral"}
                  icon="users"
                  href="/volunteers"
                  note={
                    data.nextUnfilledShift ??
                    (data.shiftsAhead === 0
                      ? "No shifts posted for today or tomorrow"
                      : "Today and tomorrow are covered")
                  }
                />
                {data.giving && (
                  <StatTile
                    label="Given this month"
                    value={inr(data.giving.monthToDate)}
                    tone={data.giving.monthToDate > 0 ? "success" : "neutral"}
                    icon="heart-handshake"
                    href="/donations"
                    note={`Since ${shortDate(data.giving.since)}`}
                  />
                )}
              </div>

              <div className="grid items-start gap-4 xl:grid-cols-[1.4fr_1fr]">
                <MealsCard meals={data.meals} />
                <DeliveriesCard deliveries={data.deliveries} />
              </div>
            </>
          )}
        </Screen>
      </main>
    </div>
  );
}

/** One line under the date: what the day holds, and how much of it there is. */
function summarise(data: TodayView): string {
  const parts: string[] = [];
  // The day named as a pujari reads it — tithi, naksatra, masa — with the festival or fast, when the
  // day has one, in front of it because that is what the kitchen has to cook for.
  if (data.calendar?.todayName) parts.push(data.calendar.todayName);
  if (data.calendar) parts.push(dayLabel(data.calendar));
  parts.push(
    data.meals.length
      ? `${data.platesToday.toLocaleString("en-IN")} plates across ${data.meals.length} ${
          data.meals.length === 1 ? "meal" : "meals"
        }`
      : "Nothing planned yet"
  );
  return parts.join(" · ");
}

/**
 * A fasting day changes every menu on it, so it is a banner rather than a tile (E4-S8 D3) — and
 * tomorrow matters as much as today, because menus are settled the day before.
 */
function fastingNotice(data: TodayView) {
  const calendar = data.calendar;
  if (!calendar) return null;

  if (calendar.fastingToday) {
    return (
      <InlineNotice
        tone="warning"
        action={
          <ButtonLink href="/planner" size="sm" variant="ghost">
            Review menu
          </ButtonLink>
        }
      >
        Today is a fasting day{calendar.todayName ? ` (${calendar.todayName})` : ""}. Grains and beans
        are left out of every meal cooked on it.
      </InlineNotice>
    );
  }

  if (calendar.fastingTomorrow) {
    return (
      <InlineNotice
        tone="warning"
        action={
          <ButtonLink href="/planner" size="sm" variant="ghost">
            Review menu
          </ButtonLink>
        }
      >
        Tomorrow is a fasting day{calendar.tomorrowName ? ` (${calendar.tomorrowName})` : ""}. Grains
        and beans are left out of every meal on it — worth checking tomorrow&rsquo;s plan today.
      </InlineNotice>
    );
  }

  return null;
}

/**
 * The next day that changes the kitchen's work, and how far off it is. A fast has to be ordered
 * around and a festival has to be rostered for, and both are decided well before the morning they
 * arrive — so the screen says it a month out rather than the night before.
 */
function aheadNotice(data: TodayView) {
  const ahead = data.calendar?.ahead;
  if (!ahead) return null;

  const fast = ahead.kind === "FAST";
  return (
    <InlineNotice
      tone={fast ? "warning" : "info"}
      action={
        <ButtonLink href="/calendar" size="sm" variant="ghost">
          Open the calendar
        </ButtonLink>
      }
    >
      {ahead.name} on {shortDate(ahead.date)}, in {ahead.daysAway} days.{" "}
      {fast
        ? "Grains, dal and beans come off every menu on that day."
        : "Plan a feast and extra volunteers."}
    </InlineNotice>
  );
}

/** Today's meals, in the order the kitchen works: by the time the food has to be ready. */
function MealsCard({ meals }: { meals: TodayMeal[] }) {
  return (
    <Card title="Meals in the kitchen" meta="In the order they are due">
      {meals.length === 0 ? (
        <EmptyState
          title="Nothing planned for today"
          action={
            <ButtonLink href="/planner">Open the planner</ButtonLink>
          }
        >
          Plan a meal and it will appear here, in the order the kitchen has to have it ready.
        </EmptyState>
      ) : (
        <div className="grid">
          {meals.map((meal) => (
            <div
              key={meal.id}
              className="flex items-center gap-4 border-t border-hairline py-3 first:border-t-0"
            >
              <span className="w-14 flex-none text-sm tabular-nums text-ink-secondary">
                {hhmm(meal.readyBy)}
              </span>
              <span className="grid flex-1">
                <span className="text-base font-medium text-ink">
                  {meal.mealKind} — {meal.recipeName}
                </span>
                <span className="text-xs text-ink-muted">
                  {Number(meal.targetServings).toLocaleString("en-IN")} servings
                  {meal.occasionName ? ` · ${meal.occasionName}` : ""}
                </span>
              </span>
              <Badge tone={MEAL_TONES[meal.status]} shape="pill">
                {MEAL_LABELS[meal.status]}
              </Badge>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}

/** What is expected from vendors — the store keeper's first question of the morning. */
function DeliveriesCard({ deliveries }: { deliveries: TodayDelivery[] }) {
  return (
    <Card
      title="Deliveries"
      meta="Against open purchase orders"
      action={
        <ButtonLink href="/orders" size="sm" variant="ghost">
          All orders
        </ButtonLink>
      }
    >
      {deliveries.length === 0 ? (
        <EmptyState title="Nothing due today">
          Orders you have sent appear here on the day they are due, and stay until they arrive.
        </EmptyState>
      ) : (
        <div className="grid gap-3">
          {deliveries.map((delivery, index) => (
            <div
              key={`${delivery.purchaseOrderId ?? delivery.vendorName}-${index}`}
              className="flex items-center justify-between gap-3"
            >
              <span className="grid">
                <span className="text-sm font-medium text-ink">{delivery.vendorName}</span>
                <span className="text-xs text-ink-muted">
                  {[delivery.poNumber, delivery.neededBy ? shortDate(delivery.neededBy) : null]
                    .filter(Boolean)
                    .join(" · ")}
                </span>
              </span>
              <Badge tone={delivery.state === "AWAITED" ? "neutral" : "danger"} shape="pill">
                {delivery.state === "AWAITED" ? "Awaited" : "Invoice overdue"}
              </Badge>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}

/** Rupees the way an Indian reader expects to see them, without the paise nobody wants on a tile. */
function inr(amount: number): string {
  return `₹${Math.round(amount).toLocaleString("en-IN")}`;
}

const MEAL_LABELS: Record<TodayMeal["status"], string> = {
  PLANNED: "Planned",
  COOKED: "Cooked",
  CANCELLED: "Cancelled",
};

const MEAL_TONES: Record<TodayMeal["status"], "neutral" | "success" | "warning" | "danger" | "accent"> = {
  PLANNED: "neutral",
  COOKED: "success",
  CANCELLED: "danger",
};
