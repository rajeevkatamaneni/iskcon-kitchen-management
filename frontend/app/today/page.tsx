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
import { PlatformNotices } from "@/components/PlatformNotices";
import {
  api,
  type TodayDelivery,
  type TodayDish,
  type TodayMaterialsCost,
  type TodayMeal,
  type TodayView,
  type TodayWorkforce,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { dayLabel } from "@/lib/calendar-names";
import { cooksQuantity, hhmm, longDay, shortDate } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

/**
 * Today — the temple's morning screen (E4-S8).
 *
 * <p>Where a temple admin or kitchen staff member lands after signing in. It answers four questions
 * in a line each: how much are we cooking, what are we about to run out of, is there enough of a
 * kitchen to cook with, and what is today's food costing. Each is a way into the screen that acts
 * on it.
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
              {/* Undismissed platform notices sit above everything: a supplier recall is not a
                  thing to scroll past (E9-S1). The component fetches its own feed. */}
              <PlatformNotices />

              {fastingNotice(data)}
              {aheadNotice(data)}
              {approvalNotices(data)}
              {unrecordedNotice(data)}

              <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
                <StatTile
                  label="Plates today"
                  value={data.platesToday.toLocaleString("en-IN")}
                  icon="bowl"
                  href="/planner"
                  note={
                    data.meals.length
                      ? // Per meal kind, from each meal's head count — never a sum of dish
                        // servings, which read a three-dish lunch as three lunches (A4).
                        data.meals
                          .map((m) => `${m.mealKind} ${m.plates.toLocaleString("en-IN")}`)
                          .join(" · ")
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
                {/* Who is actually in, today. This replaced "Shifts unfilled", which warned about
                    a shift on an unnamed date and gave an admin nothing they could act on. */}
                <StatTile
                  label="Working today"
                  value={workforceValue(data.workforce)}
                  tone={
                    data.workforce.staffIn + data.workforce.volunteers > 0 ? "neutral" : "warning"
                  }
                  icon="users"
                  href="/staff-schedule"
                  note={<WorkforceNote workforce={data.workforce} />}
                />
                <StatTile
                  label="Cost of materials"
                  value={inr(data.materialsCost.estimatedTotal)}
                  icon="receipt"
                  href="/planner"
                  note={materialsNote(data.materialsCost)}
                />
              </div>

              <div className="grid items-start gap-4 xl:grid-cols-[1.4fr_1fr]">
                <MealsCard meals={data.meals} date={data.date} />
                <DeliveriesCard deliveries={data.deliveries} />
              </div>
            </>
          )}
        </Screen>
      </main>
    </div>
  );
}

/**
 * A dish's figure, said beside the words "served" or "planned".
 *
 * <p>The unit is named where it has to be — 12 Kg of halwa means nothing as a bare "12" — and left
 * out where the verb has already said it. "395 servings served" says the same thing twice, and this
 * screen is a glance, not a document.
 */
function dishAmount(dish: TodayDish, value: number): string {
  return dish.targetYieldUnit === "SERVINGS"
    ? Number(value).toLocaleString("en-IN")
    : cooksQuantity(value, dish.targetYieldUnit);
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
        and beans come off every meal on it.
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

/**
 * Today's meals, in the order the kitchen works: by the time the food has to be ready.
 *
 * Grouped by meal kind, with the dishes beneath (A3). A lunch of three preparations is one lunch,
 * and listing it as three rows made the screen say the kitchen had nine meals on a normal Tuesday.
 * Each meal is a link through to that day's planner (A2) — a number nobody can act on is
 * decoration, and the planner is where the acting happens.
 */
function MealsCard({ meals, date }: { meals: TodayMeal[]; date: string }) {
  return (
    <Card title="Meals planned for today" meta="In the order they are due">
      {meals.length === 0 ? (
        <EmptyState
          title="Nothing planned for today"
          action={
            <ButtonLink href="/planner">Open the planner</ButtonLink>
          }
        >
          Plan a meal and it will appear here.
        </EmptyState>
      ) : (
        <div className="grid">
          {meals.map((meal) => (
            <Link
              key={`${meal.mealKind}-${meal.readyBy}`}
              href={`/planner?date=${date}`}
              // Named for what it is, so a screen reader announces "Lunch at 12:00" rather than
              // reading the whole block of dishes before saying where the link goes.
              aria-label={`${meal.mealKind} at ${hhmm(meal.readyBy)}`}
              // Item 14. Pulled out and padded back, so the hover tone gains 12px each side and a
              // radius rather than hugging the words. Nothing on the row moves: the negative margin
              // and the padding cancel, and only the highlight is bigger.
              className="-mx-3 grid gap-2 rounded border-t border-hairline px-3 py-3 transition-colors duration-state first:border-t-0 hover:bg-sunken"
            >
              <span className="flex items-center gap-4">
                <span className="w-14 flex-none text-sm tabular-nums text-ink-secondary">
                  {hhmm(meal.readyBy)}
                </span>
                <span className="grid flex-1">
                  <span className="text-base font-medium text-ink">{meal.mealKind}</span>
                  <span className="text-xs text-ink-muted">
                    {meal.plates.toLocaleString("en-IN")} plates
                    {meal.occasionName ? ` · ${meal.occasionName}` : ""}
                  </span>
                </span>
                {/* The truth, not a badge (§2): a meal nobody has recorded is stock that never
                    left the store room, and saying so is more use than colouring it. */}
                {meal.recorded ? (
                  <Badge tone="success" shape="pill">
                    Recorded
                  </Badge>
                ) : (
                  <span className="text-xs text-ink-muted">Not yet recorded</span>
                )}
              </span>

              <span className="grid gap-0.5 pl-[4.5rem]">
                {meal.dishes.map((dish) => (
                  <span key={dish.id} className="flex items-baseline gap-2 text-sm">
                    <span className={dish.notMade ? "text-ink-muted line-through" : "text-ink-secondary"}>
                      {dish.recipeName}
                    </span>
                    <span className="tabular-nums text-xs text-ink-muted">
                      {dish.notMade
                        ? "not made"
                        : dish.actualServings != null
                          ? `${dishAmount(dish, dish.actualServings)} served`
                          : `${dishAmount(dish, dish.targetYield)} planned`}
                    </span>
                  </span>
                ))}
              </span>
            </Link>
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
          Orders you have sent appear here on the day they are due.
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

/**
 * The workforce tile's figure. Two numbers, not one: a cook and a two-hour evening volunteer are
 * not interchangeable, and adding them would hide which of the two is missing.
 */
function workforceValue(workforce: TodayWorkforce): string {
  return `${workforce.staffIn} · ${workforce.volunteers}`;
}

/**
 * What the workforce tile says beneath the figure — one readout per meal today (item 24).
 *
 * <p>"Working today · 7" could not answer the question it looked like it was answering. The seven
 * are not all there at midday, and lunch may take eight. So the line reads meal by meal —
 * "Breakfast 4 of 4 · Lunch 5 of 8 · Dinner 6 of 6" — and the short one is the one that stands out,
 * because it is the only part of the line anybody has to do anything about.
 *
 * <p>A meal nobody has said a number for is left out rather than drawn as short of nothing.
 */
function WorkforceNote({ workforce }: { workforce: TodayWorkforce }) {
  const counted = workforce.meals.filter((m) => m.crewRequired != null);

  if (counted.length === 0) {
    if (workforce.staffIn === 0 && workforce.volunteers === 0) {
      return <>Nobody is down to work today</>;
    }
    return (
      <>
        {plural(workforce.staffIn, "staff member", "staff")} ·{" "}
        {plural(workforce.volunteers, "volunteer", "volunteers")}
      </>
    );
  }

  return (
    <span className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5">
      {counted.map((meal, i) => (
        <span key={`${meal.mealKind}-${meal.readyBy}`} className="flex items-center gap-1.5">
          {i > 0 && <span aria-hidden="true">·</span>}
          <span className={meal.shortOfCrew ? "font-semibold text-warning" : undefined}>
            {meal.mealKind} {meal.rostered} of {meal.crewRequired}
          </span>
        </span>
      ))}
    </span>
  );
}

/**
 * What the cost tile says beneath the figure. Where a price is unknown it says so, rather than
 * quietly under-reporting: a total that omits a third of the basket is worse than one that admits
 * the gap (§9).
 */
function materialsNote(cost: TodayMaterialsCost): string {
  if (cost.withoutPrice > 0) {
    return `Estimated · ${cost.withoutPrice} ${
      cost.withoutPrice === 1 ? "ingredient has" : "ingredients have"
    } no known price`;
  }
  if (cost.estimatedTotal === 0) {
    return "Nothing planned to cost yet";
  }
  return "Estimated from vendors’ last-known prices";
}

/**
 * What is waiting for this person to answer.
 *
 * <p>Both queues used to be invisible until somebody opened their own screen, which is how an
 * approval queue stops being worked: the cook whose ghee request is unanswered finds out at the
 * stove, and the staff member who asked for Friday off finds out on Friday.
 *
 * <p>The server counts only what this person may actually act on, so a kitchen staff member gets
 * zeroes and nothing renders. A nudge about something you cannot do is noise you learn to scroll
 * past — and once you have learned that, you scroll past the ones you can.
 *
 * <p>Where some of it is needed today or tomorrow the count leads with that, because three requests
 * waiting is a fact and one of them needed this afternoon is the reason to stop reading and go and
 * answer it. Two separate notices rather than one combined: they are answered on different screens
 * by different acts, and a single line offering two destinations makes the reader choose before
 * they have understood.
 */
/**
 * How much of a waiting queue cannot wait, said in the right number.
 *
 * <p>Worth the small function: the first version read "1 leave request is waiting. **Some of it**
 * starts today or tomorrow", which is what happens when a plural sentence meets a single row. The
 * second tried to share one scaffold between the two queues and produced "It **is** today or
 * tomorrow", which is what happens when two sentences with different verbs are made to share one.
 * So each case carries its own finished sentence. A nudge that cannot count is read once and then
 * distrusted.
 */
function urgency(
  total: number,
  soon: number,
  say: { noneOne: string; noneMany: string; one: string; all: string; some: (n: number) => string }
): string {
  if (soon === 0) {
    return total === 1 ? say.noneOne : say.noneMany;
  }
  if (soon === total) {
    return total === 1 ? say.one : say.all;
  }
  return say.some(soon);
}

function approvalNotices(data: TodayView) {
  const a = data.approvals;
  if (!a || (a.ingredientRequests === 0 && a.leaveRequests === 0)) return null;

  return (
    <>
      {a.ingredientRequests > 0 && (
        <InlineNotice
          tone={a.ingredientRequestsSoon > 0 ? "warning" : "info"}
          title={
            <>
              <span className="font-semibold">
                {a.ingredientRequests === 1
                  ? "1 ingredient request"
                  : `${a.ingredientRequests} ingredient requests`}
              </span>{" "}
              {a.ingredientRequests === 1 ? "is" : "are"} waiting for an answer.
            </>
          }
          action={
            <ButtonLink
              href="/ingredient-requests?status=SUBMITTED"
              size="sm"
              variant="secondary"
            >
              Review them
            </ButtonLink>
          }
        >
          {urgency(a.ingredientRequests, a.ingredientRequestsSoon, {
            noneOne: "It is not needed before the day after tomorrow.",
            noneMany: "None of them is needed before the day after tomorrow.",
            one: "It is needed today or tomorrow, so the store has little time to get it ready.",
            all: "They are all needed today or tomorrow, so the store has little time to get them ready.",
            some: (n) =>
              `${n} of them ${n === 1 ? "is" : "are"} needed today or tomorrow, so the store has little time to get them ready.`,
          })}
        </InlineNotice>
      )}

      {a.leaveRequests > 0 && (
        <InlineNotice
          tone={a.leaveRequestsSoon > 0 ? "warning" : "info"}
          title={
            <>
              <span className="font-semibold">
                {a.leaveRequests === 1 ? "1 leave request" : `${a.leaveRequests} leave requests`}
              </span>{" "}
              {a.leaveRequests === 1 ? "is" : "are"} waiting for an answer.
            </>
          }
          action={
            <ButtonLink href="/leave" size="sm" variant="secondary">
              Open the leave queue
            </ButtonLink>
          }
        >
          {urgency(a.leaveRequests, a.leaveRequestsSoon, {
            noneOne: "It does not start before the day after tomorrow.",
            noneMany: "None of it starts before the day after tomorrow.",
            one: "It starts today or tomorrow, or has already started — the roster cannot bend around an answer that comes later.",
            all: "They all start today or tomorrow, or have already started — the roster cannot bend around an answer that comes later.",
            some: (n) =>
              `${n} of them ${n === 1 ? "starts" : "start"} today or tomorrow, or ${n === 1 ? "has" : "have"} already started — the roster cannot bend around an answer that comes later.`,
          })}
        </InlineNotice>
      )}
    </>
  );
}

/**
 * A nudge, not an alarm (§2). Stock only leaves the store room when a meal is recorded, so meals
 * nobody has typed back in are the reason the inventory quietly overstates itself.
 */
function unrecordedNotice(data: TodayView) {
  if (data.unrecordedMeals === 0) return null;
  const one = data.unrecordedMeals === 1;
  return (
    <InlineNotice
      tone="info"
      // The count leads, in the heavier weight, because the number is the thing to react to. The
      // consequence follows in the body and the way out is a control rather than a word buried in
      // a sentence. Deliberately still the quiet tone: §2 asks for "a nudge, not an alarm", and
      // stock that overstates itself by a day is not an emergency — it is something to clear up.
      title={
        <>
          <span className="font-semibold">
            {one ? "1 meal" : `${data.unrecordedMeals} meals`}
          </span>{" "}
          from earlier this week {one ? "hasn’t" : "haven’t"} been recorded yet.
        </>
      }
      action={
        <ButtonLink href="/planner/catch-up" size="sm" variant="secondary">
          Record them
        </ButtonLink>
      }
    >
      Until they are, the store room still shows their ingredients as on hand.
    </InlineNotice>
  );
}

function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

/** Rupees the way an Indian reader expects to see them, without the paise nobody wants on a tile. */
function inr(amount: number): string {
  return `₹${Math.round(amount).toLocaleString("en-IN")}`;
}

