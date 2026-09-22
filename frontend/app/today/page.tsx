"use client";

import Link from "next/link";
import { useCallback, type ReactNode } from "react";
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
  type OutsideCommitment,
  type TodayDelivery,
  type TodayDish,
  type TodayMaterialsCost,
  type TodayMeal,
  type TodayView,
  type TodayWorkforce,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { plannerRefused } from "@/lib/nav";
import { dayLabel } from "@/lib/calendar-names";
import { ekadashiSpelling } from "@/lib/vaishnava-day";
import { cooksQuantity, hhmm, longDay, money, shortDate } from "@/lib/format";
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

  // The staff schedule admits an administrator and a kitchen manager and nobody else, so for a
  // cook the "Working today" tile is a figure and not a door. The count itself is theirs to see —
  // how many people are in is the answer to "is there enough of a kitchen to cook with", which is
  // one of the four questions this screen exists to answer — but a tile that lands them on "Not
  // your page" teaches them that the tiles lie, and they stop pressing the three that do work.
  //
  // The same shape the server already uses for the equipment line below: the reader who cannot act
  // is given the fact without the act, rather than the fact withheld.
  const maySeeSchedule = appUser?.role === "TEMPLE_ADMIN" || appUser?.role === "KITCHEN_MANAGER";

  /**
   * Whether to offer this reader a way into the meal planner at all (T-363, from T-359 §8).
   *
   * <p>Epic 12 made the planner a kitchen's screen as well as a role's: somebody whose kitchen does
   * not plan its meals here is refused it whatever their role, and the menu already leaves it out for
   * them. Today did not, and offered seven doors into it — the header button, the catch-up nudge,
   * three meal rows and two stat tiles — every one of which landed on "Not your page". Counted on
   * staging as Gopal Das, Kitchen Staff in the Deity Kitchen.
   *
   * <p>The fact stays and the act goes, which is the shape this screen already uses twice: the
   * workforce tile shows a cook the count without the link to the schedule, and the server sends a
   * null equipment count rather than a zero to somebody who does not book the engineer. What a person
   * cannot do should not be dangled in front of them; what is true of their kitchen's day is still
   * theirs to read.
   *
   * <p>`plannerRefused` is the menu's own test — `whoami.canPlanMeals === false` — so the menu and
   * this screen cannot come to disagree about who is offered the planner. It is not the protection;
   * the guard on the planner and the server behind it are.
   */
  const mayPlan = !plannerRefused(appUser);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/today" />
      <main className="flex-1">
        <Screen>
          <PageHeader
            title={data ? longDay(data.date) : "Today"}
            subtitle={data ? summarise(data) : undefined}
          />

          {error && <ErrorNotice error={error} />}
          {loading && !data && <Loading />}

          {data && (
            <>
              {/* Undismissed platform notices sit above everything: a supplier recall is not a
                  thing to scroll past (E9-S1). The component fetches its own feed. */}
              <PlatformNotices />

              {fastingNotice(data, mayPlan)}
              {aheadNotice(data)}
              {approvalNotices(data)}
              {unrecordedNotice(data, mayPlan)}
              {equipmentNotice(data)}
              {/* Drafts nobody has sent that are at or past their order-by date (T-137, D-24a).
                  Fetches its own list, like PlatformNotices above — see the component. */}
              <DraftsAtRiskNotice />

              <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
                <StatTile
                  label="Servings today"
                  value={data.platesToday.toLocaleString("en-IN")}
                  icon="bowl"
                  // A figure, not a door, for somebody whose kitchen does not plan here (T-363) —
                  // the same treatment the workforce tile gives a cook below.
                  href={mayPlan ? "/planner" : undefined}
                  note={
                    data.meals.length
                      ? // Per meal, from each meal's head count — never a sum of dish servings,
                        // which read a three-dish lunch as three lunches (A4). Each named by
                        // mealName, so two events on one day are not "Event 20 · Event 40".
                        data.meals
                          .map((m) => `${mealName(m)} ${m.plates.toLocaleString("en-IN")}`)
                          .join(" · ")
                      : "Nothing planned yet — plan a meal"
                  }
                />
                <StatTile
                  label="Items below reorder level"
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
                  href={maySeeSchedule ? "/staff-schedule" : undefined}
                  note={<WorkforceNote workforce={data.workforce} meals={data.meals} />}
                />
                <StatTile
                  label="Cost of materials"
                  value={inr(data.materialsCost.estimatedTotal)}
                  icon="receipt"
                  href={mayPlan ? "/planner" : undefined}
                  note={<MaterialsNote cost={data.materialsCost} />}
                />
              </div>

              <div className="grid items-start gap-4 xl:grid-cols-[1.4fr_1fr]">
                <MealsCard meals={data.meals} date={data.date} mayPlan={mayPlan} />
                {/* The narrow column stacks what is coming: today's deliveries in, and then what the
                    temple has promised to send out in the days ahead. Both are lists of things
                    arriving or leaving that nobody has to act on this minute, and the space under
                    Deliveries was empty on every temple whose morning has fewer than a dozen of
                    them. */}
                <div className="grid gap-4">
                  <DeliveriesCard deliveries={data.deliveries} />
                  <GoingOutCard commitments={data.upcomingOutside} mayPlan={mayPlan} />
                </div>
              </div>
            </>
          )}
        </Screen>
      </main>
    </div>
  );
}

/**
 * A dish's figure, said beside the words "cooked" or "planned".
 *
 * <p>Always names the unit now. It used to leave it out where the yield was counted in servings,
 * because "395 servings served" says the same thing twice — and servings has since stopped being a
 * unit at all (V80). A yield measures food, and 12 without a unit beside it means nothing.
 */
function dishAmount(dish: TodayDish, value: number): string {
  return cooksQuantity(value, dish.targetYieldUnit);
}

/**
 * What a meal is called wherever Today names it: an event by its own name, anything else by its
 * kind (T-214).
 *
 * <p>The same rule as `derivedTitle` in the planner's shift layer, which names an event "by its own
 * name rather than as another 'Event'". Today used to print the kind everywhere, so an event planned
 * as "UAT-test record" read "09:00 Event, 20 servings" here while the planner and the job card both
 * called it by name — and two events on one day could not be told apart at all.
 *
 * <p>`||` rather than `??`, as `derivedTitle` has it: an empty name is no name, and a meal row with
 * a blank heading is worse than one that says "Event".
 */
function mealName(meal: Pick<TodayMeal, "mealKind" | "eventName">): string {
  return meal.eventName || meal.mealKind;
}

/** One line under the date: what the day holds, and how much of it there is. */
function summarise(data: TodayView): string {
  const parts: string[] = [];
  // The day named as a pujari reads it — tithi, naksatra, masa — with the festival or fast, when the
  // day has one, in front of it because that is what the kitchen has to cook for.
  if (data.calendar?.todayName) parts.push(ekadashiSpelling(data.calendar.todayName));
  if (data.calendar) parts.push(dayLabel(data.calendar));
  parts.push(
    data.meals.length
      ? `${data.platesToday.toLocaleString("en-IN")} servings across ${data.meals.length} ${
          data.meals.length === 1 ? "meal" : "meals"
        }`
      : "Nothing planned yet"
  );
  return parts.join(" · ");
}

/**
 * A fasting day changes every menu on it, so it is a banner rather than a tile (E4-S8 D3) — and
 * tomorrow matters as much as today, because menus are settled the day before.
 *
 * Blue, not amber (Rajeev, 2026-09-18, T-227): amber is kept for something the user should act on
 * or take care over, and a fasting day is information the temple already lives by. The banner's
 * place at the top of the page carries the weight; the colour does not need to.
 */
function fastingNotice(data: TodayView, mayPlan: boolean) {
  const calendar = data.calendar;
  if (!calendar) return null;

  // No "Review menu" for somebody the planner is shut to (T-363): it is the menu they would be
  // reviewing it in. The fast itself is still theirs to know about — they are cooking for it.
  const review = mayPlan ? (
    <ButtonLink href="/planner" size="sm" variant="ghost">
      Review menu
    </ButtonLink>
  ) : undefined;

  if (calendar.fastingToday) {
    return (
      <InlineNotice tone="info" action={review}>
        Today is a fasting day{calendar.todayName ? ` (${ekadashiSpelling(calendar.todayName)})` : ""}. No grains, dal
        or beans today.
      </InlineNotice>
    );
  }

  if (calendar.fastingTomorrow) {
    return (
      <InlineNotice tone="info" action={review}>
        Tomorrow is a fasting day{calendar.tomorrowName ? ` (${ekadashiSpelling(calendar.tomorrowName)})` : ""}. No
        grains, dal or beans tomorrow.
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
      // Information either way: a fast is no more a warning than a festival is (T-227).
      tone="info"
      action={
        <ButtonLink href="/calendar" size="sm" variant="ghost">
          Open the calendar
        </ButtonLink>
      }
    >
      {ekadashiSpelling(ahead.name)} on {shortDate(ahead.date)}, in {ahead.daysAway} days.{" "}
      {fast
        ? "No grains, dal or beans on that day."
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
function MealsCard({
  meals,
  date,
  mayPlan,
}: {
  meals: TodayMeal[];
  date: string;
  /** Whether this reader may open the planner at all (T-363). A row is a link only if they may. */
  mayPlan: boolean;
}) {
  return (
    <Card title="Meals planned for today" meta="In the order they are due">
      {meals.length === 0 ? (
        <EmptyState
          title="Nothing planned for today"
          action={mayPlan ? <ButtonLink href="/planner">Open planner</ButtonLink> : undefined}
        >
          {mayPlan
            ? "Plan a meal and it will appear here."
            : "Meals appear here once they are planned."}
        </EmptyState>
      ) : (
        <div className="grid">
          {meals.map((meal) => (
            // The divider lives on a square wrapper, not on the rounded link: a top border on a
            // rounded box bends down at both ends, which drew every divider as a shallow bracket.
            <div key={meal.mealId} className="border-t border-hairline first:border-t-0">
            {/* A row is a link to the day's plan for anybody who can open it, and plain text for
                anybody who cannot (T-363). `MealRow` is one body drawn either way, rather than the
                whole block written twice and drifting. */}
            <MealRow
              href={mayPlan ? `/planner?date=${date}` : null}
              // An event is announced by its name, the same as the heading it stands for.
              label={`${mealName(meal)} at ${hhmm(meal.readyBy)}`}
            >
              <span className="flex items-center gap-4">
                <span className="w-14 flex-none text-sm tabular-nums text-ink-secondary">
                  {hhmm(meal.readyBy)}
                </span>
                <span className="grid flex-1">
                  <span className="text-base font-medium text-ink">{mealName(meal)}</span>
                  <span className="text-xs text-ink-muted">
                    {meal.plates.toLocaleString("en-IN")} servings
                    {meal.occasionName ? ` · ${meal.occasionName}` : ""}
                    {/* Who is cooking it (Epic 12), in the planner's order for this person and in
                        the same quiet words as the occasion: a fact about the meal, not an alert. */}
                    {meal.kitchenNames?.length ? ` · ${meal.kitchenNames.join(" and ")}` : ""}
                  </span>
                </span>
                {/* The truth, not a badge (§2): a meal nobody has recorded is stock that never
                    left the store room, and saying so is more use than colouring it. */}
                {meal.recorded ? (
                  // Neutral: green is kept for the moment the user's own action succeeds (the
                  // recording toast), not a standing state seen every morning (T-227).
                  <Badge>
                    Recorded
                  </Badge>
                ) : (
                  <span className="text-xs text-ink-muted">Not yet recorded</span>
                )}
              </span>

              <span className="grid gap-0.5 pl-[4.5rem]">
                {meal.dishes.map((dish) => (
                  <span key={dish.id} className="flex flex-wrap items-baseline gap-x-2 text-sm">
                    <span className={dish.notMade ? "text-ink-muted line-through" : "text-ink-secondary"}>
                      {dish.recipeName}
                    </span>
                    <span className="whitespace-nowrap tabular-nums text-xs text-ink-muted">
                      {dish.notMade
                        ? "not made"
                        : dish.actualServings != null
                          ? `${dishAmount(dish, dish.actualServings)} cooked`
                          : `${dishAmount(dish, dish.targetYield)} planned`}
                    </span>
                  </span>
                ))}
              </span>
            </MealRow>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}

/**
 * One meal's row on Today: a link into that day's plan, or the same row as plain text.
 *
 * <p>The hover tone and the pulled-out padding belong to the link and are dropped with it, because a
 * block that lights up under the pointer and then does nothing is the same lie as a link that refuses
 * you. The `aria-label` goes too: without a link there is nothing for a screen reader to announce a
 * destination for, and the row's own words already say what it is.
 */
function MealRow({
  href,
  label,
  children,
}: {
  href: string | null;
  label: string;
  children: ReactNode;
}) {
  const body = "grid gap-2 py-3";
  if (!href) {
    return <div className={body}>{children}</div>;
  }
  return (
    <Link
      href={href}
      // Named for what it is, so a screen reader announces "Lunch at 12:00" rather than reading the
      // whole block of dishes before saying where the link goes.
      aria-label={label}
      // Item 14. Pulled out and padded back, so the hover tone gains 12px each side and a radius
      // rather than hugging the words. Nothing on the row moves: the negative margin and the padding
      // cancel, and only the highlight is bigger.
      className={`-mx-3 rounded px-3 transition-colors duration-state hover:bg-sunken ${body}`}
    >
      {children}
    </Link>
  );
}

/**
 * What the temple has promised to send out of the building in the days ahead (T-363).
 *
 * <p>This is what is left of the planner's *Upcoming outside commitments* section, which Rajeev
 * removed on 2026-09-19: the events themselves are ordinary meals and sit in the planner's day list
 * with everything else, sorted by ready-by. The one thing a day view cannot do is look across dates —
 * somebody reading Monday cannot see Saturday's delivery — and that is a heads-up rather than a
 * section, so it is here, small, with the temple's other heads-ups.
 *
 * <p>Deliberately a nudge and not a table. The old section listed the contact, the address, the hour
 * and the number of preparations in six columns and linked to none of it; all of that is on the
 * meal's own card, which is where a row here goes.
 *
 * <p><strong>Nothing at all when there is nothing.</strong> An empty card every morning is furniture
 * saying nothing, and a temple that does no outside cooking would carry it for ever.
 */
function GoingOutCard({
  commitments,
  mayPlan,
}: {
  commitments: OutsideCommitment[];
  /** A row opens the day's plan; for a reader the planner is shut to it is plain text (T-363). */
  mayPlan: boolean;
}) {
  if (commitments.length === 0) return null;

  return (
    <Card title="Going out of the temple" meta="In the days ahead, soonest first">
      <div className="grid">
        {commitments.map((c) => {
          const name = c.eventName || c.mealKind;
          const row = (
            <>
              {/* The name over its date, with the pill to the right — the same three-part row the
                  Deliveries card directly above this one uses for a vendor, its order and its state.
                  Two cards in one column reading two different ways would make the reader learn the
                  column twice.

                  It is also the only arrangement that fits, and that was measured rather than
                  guessed. In a column and a date beside it, the name had 184px at 1280 and all
                  three real names were clipped: "Children's Bhagavad-gita Reading" — Rajeev's own
                  event, the one he could not open — needs 192px, and the other two 210 and 217.
                  Letting them wrap in that column took one of them to three lines. Over the date
                  the name has 242px and every one of them is a single line. The name is the whole
                  of what the row says, so it is the last thing that may be cut or cramped.

                  The weekday stays, because which Saturday it is decides whether anybody is
                  rostered, and a bare "26 Sept" makes the reader count. */}
              <span className="grid min-w-0">
                <span className="text-sm font-medium text-ink">{name}</span>
                <span className="text-xs text-ink-muted">{dayAndDate(c.planDate)}</span>
              </span>
              <HandoverBadge handover={c.handover} />
            </>
          );
          return (
            <div key={c.mealId} className="border-t border-hairline first:border-t-0">
              {mayPlan ? (
                <Link
                  href={`/planner?date=${c.planDate}`}
                  aria-label={`${name} on ${dayAndDate(c.planDate)}`}
                  className="-mx-3 flex items-start justify-between gap-3 rounded px-3 py-2.5 transition-colors duration-state hover:bg-sunken"
                >
                  {row}
                </Link>
              ) : (
                <div className="flex items-start justify-between gap-3 py-2.5">{row}</div>
              )}
            </div>
          );
        })}
      </div>
    </Card>
  );
}

/**
 * Who moves the food — the same pill, in the same words and the same blue, as the meal's own card in
 * the planner (T-363).
 *
 * <p>Blue for both readings, on Rajeev's choice. Colour in this product is severity and never
 * category (DESIGN_SYSTEM v1.14): amber belongs to something to act on or take care over, and these
 * meals already carry the amber loading-time warning on the form that plans them. One colour for a
 * delivery and another for a collection would say one of the two matters more, and it does not.
 *
 * <p>Nothing where the handover was never asked — the plans V88 carried over from the old catering
 * kinds. A pill reading "Not set" on a morning screen is a question mark nobody can answer from here.
 */
function HandoverBadge({ handover }: { handover: OutsideCommitment["handover"] }) {
  if (!handover) return null;
  // `flex-none whitespace-nowrap`, and both were measured. As an ordinary flex child in this row the
  // pill shrank at 390 and "They collect it" broke over two lines inside its own rounded box — a
  // three-word label folded in half, which is the cramped pill Rajeev has had to point out before.
  // It holds its width and the name wraps instead, which is the right way round: a name can run to
  // two lines and still read, a three-word pill cannot.
  return (
    <span className="flex-none whitespace-nowrap">
      <Badge tone="info">{handover === "DELIVERY" ? "We deliver it" : "They collect it"}</Badge>
    </span>
  );
}

/**
 * "2026-09-26" → "Sat 26 Sept", for a row that has to say which day of the week it is in a narrow
 * column.
 *
 * <p>Built here rather than in `lib/format.ts` because this screen is the only place that needs the
 * weekday abbreviated beside the date; `shortDate` ("26 Sept") and `longDay` ("Saturday, 26
 * September") are the two shapes the rest of the product uses, and neither fits a 1fr column. Day
 * first and the month named, like every other date here. A wall date built at `T00:00:00` and
 * rendered with no timeZone, which is the correct handling for a calendar day — see the rule in
 * `design-system.test.ts`.
 */
function dayAndDate(iso: string): string {
  const weekday = new Date(`${iso}T00:00:00`).toLocaleDateString("en-GB", { weekday: "short" });
  return `${weekday} ${shortDate(iso)}`;
}

/** What is expected from vendors — the store keeper's first question of the morning. */
function DeliveriesCard({ deliveries }: { deliveries: TodayDelivery[] }) {
  return (
    <Card
      title="Deliveries"
      meta="From orders you have sent"
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
              <Badge tone={delivery.state === "AWAITED" ? "neutral" : "danger"}>
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
 *
 * <p>The numbers at the tile's figure size and the words beside them in small type, so the figure
 * stays on one line (Rajeev, Decisions Desk, 2026-09-18). Set whole at the figure size, "6 staff · 0
 * volunteers" is 254px wide, and a tile's text box is 172px at 1280 and 222px at 1920 when the four
 * tiles share a row, so it broke onto a second line under three single-line figures. Measured in
 * Chrome with the app's own stylesheet and font (T-234): with the words in small type it is 152px, and
 * with one two-digit count ("12 staff · 4 volunteers") 167px, both one line at 1280.
 *
 * <p>That still broke at 1280 for "12 staff · 14 volunteers", and Rajeev asked (2026-09-18, T-237)
 * for room for "120 staff · 1400 volunteers" on one line at 1280 and 1920. The tile's figure is now
 * smaller and its padding narrower (see `StatTile`), and the words here are `text-xs`: measured, that
 * string is 167px wide in a 180px box at 1280. The halves are still kept whole, so if a larger count
 * ever does break, it breaks at the dot and never between a number and its word. The words inherit
 * the figure's colour, so an empty day still reads amber from end to end.
 */
function workforceValue(workforce: TodayWorkforce): ReactNode {
  const word = "text-xs font-medium";
  return (
    <>
      <span className="whitespace-nowrap">
        {workforce.staffIn}
        <span className={word}> staff</span> ·
      </span>{" "}
      <span className="whitespace-nowrap">
        {workforce.volunteers}
        <span className={word}> {workforce.volunteers === 1 ? "volunteer" : "volunteers"}</span>
      </span>
    </>
  );
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
 *
 * <p>Each readout is named by `mealName`, like every other place Today names a meal. The crew
 * readout does not carry the event's name itself, so it is found through the meal's id among
 * today's meals — the id, never the kind, which is exactly what two events on one day share. A crew
 * row with no meal beside it (a meal whose every dish was called off is not in today's list) falls
 * back to its kind rather than to nothing.
 */
function WorkforceNote({ workforce, meals }: { workforce: TodayWorkforce; meals: TodayMeal[] }) {
  const counted = workforce.meals.filter((m) => m.crewRequired != null);
  const eventNames = new Map(meals.map((m) => [m.mealId, m.eventName]));

  if (counted.length === 0) {
    if (workforce.staffIn === 0 && workforce.volunteers === 0) {
      return <>Nobody is rostered today</>;
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
        <span key={meal.mealId} className="flex items-center gap-1.5">
          {i > 0 && <span aria-hidden="true">·</span>}
          <span className={meal.shortOfCrew ? "font-semibold text-warning" : undefined}>
            {mealName({ mealKind: meal.mealKind, eventName: eventNames.get(meal.mealId) ?? null })}{" "}
            {meal.rostered} of {meal.crewRequired}
          </span>
        </span>
      ))}
    </span>
  );
}

/**
 * The cost tile's note: what the estimate leaves out, and then what it was worked out from (T-212).
 *
 * <p>A recorded meal is in the figure at what its job card says was cooked, and a meal not yet
 * recorded at what was planned. In the morning the figure is all plan and by evening mostly cooking,
 * and a number that quietly changes what it means through the day needs to say so beneath it.
 */
function MaterialsNote({ cost }: { cost: TodayMaterialsCost }) {
  const basis = costBasis(cost);
  return (
    <>
      {materialsNote(cost)}
      {basis && <span className="block">{basis}</span>}
    </>
  );
}

/**
 * "2 meals from what was cooked, 1 from the plan" — Rajeev's wording (T-212), said the same way on
 * Cost per serving. Only the half that has meals is said, and nothing when neither does. The same
 * lines live in `app/cost-per-serving/page.tsx`, because a page file may export nothing but its page.
 */
function costBasis(cost: TodayMaterialsCost): string {
  const cooked = cost.mealsCostedAsCooked ?? 0;
  const planned = cost.mealsCostedAsPlanned ?? 0;
  const meals = (n: number) => `${n.toLocaleString("en-IN")} ${n === 1 ? "meal" : "meals"}`;
  if (cooked > 0 && planned > 0) {
    return `${meals(cooked)} from what was cooked, ${planned.toLocaleString("en-IN")} from the plan`;
  }
  if (cooked > 0) return `${meals(cooked)} from what was cooked`;
  if (planned > 0) return `${meals(planned)} from the plan`;
  return "";
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
  // Says what the costing reads now (R-ING-3): the list price (R-VEN-1's name for it, in every
  // view), and the market rate where no vendor has one. It said "vendors’ last-known prices", from
  // before either existed (T-311). The words are Cost per serving's, so the two never disagree about
  // where a figure came from; "materials only" is left off here because the tile's own title,
  // Cost of materials, already says it.
  return "Estimated from list prices, or the market rate";
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
 * <p>Where some of it is needed today or tomorrow the words lead with that, because three requests
 * waiting is a fact and one of them needed this afternoon is the reason to stop reading and go and
 * answer it. Two separate notices rather than one combined: they are answered on different screens
 * by different acts, and a single line offering two destinations makes the reader choose before
 * they have understood.
 *
 * <p><strong>Urgency is carried by the sentence, not by the colour.</strong> These first shipped
 * amber whenever anything in them was due today or tomorrow, which was wrong twice over. §2 admits
 * a status colour only where "something is genuinely low, wrong, overdue, or complete" and forbids
 * it as decoration — and in a working temple something is due tomorrow most days, so amber would
 * have been on almost always, and a colour that is always on has stopped directing attention. It
 * also left three identically shaped rows in two colours with no rule a reader could learn, beside
 * a meals notice that is quiet on purpose (§2: a nudge, not an alarm).
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
              variant="ghost"
            >
              Review them
            </ButtonLink>
          }
        >
          {urgency(a.ingredientRequests, a.ingredientRequestsSoon, {
            noneOne: "It is not needed before the day after tomorrow.",
            noneMany: "None of them is needed before the day after tomorrow.",
            one: "1 is needed today or tomorrow.",
            all: "All are needed today or tomorrow.",
            some: (n) =>
              `${n} ${n === 1 ? "is" : "are"} needed today or tomorrow.`,
          })}
        </InlineNotice>
      )}

      {a.leaveRequests > 0 && (
        <InlineNotice
          title={
            <>
              <span className="font-semibold">
                {a.leaveRequests === 1 ? "1 leave request" : `${a.leaveRequests} leave requests`}
              </span>{" "}
              {a.leaveRequests === 1 ? "is" : "are"} waiting for an answer.
            </>
          }
          action={
            <ButtonLink href="/leave" size="sm" variant="ghost">
              Open the leave queue
            </ButtonLink>
          }
        >
          {urgency(a.leaveRequests, a.leaveRequestsSoon, {
            noneOne: "It does not start before the day after tomorrow.",
            noneMany: "None of it starts before the day after tomorrow.",
            one: "1 starts today or tomorrow, or has started.",
            all: "All start today or tomorrow, or have started.",
            some: (n) =>
              `${n} ${n === 1 ? "starts" : "start"} today or tomorrow, or ${n === 1 ? "has" : "have"} started.`,
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
function unrecordedNotice(data: TodayView, mayPlan: boolean) {
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
      // Catching up is a planner screen, so it is not offered to a reader the planner is shut to
      // (T-363). The nudge itself stays: the store room overstating itself is a fact about the
      // temple, and a cook who reads it can tell whoever does the recording.
      action={
        mayPlan ? (
          <ButtonLink href="/planner/catch-up" size="sm" variant="ghost">
            Record them
          </ButtonLink>
        ) : undefined
      }
    >
      Until they are, the store room still shows their ingredients as on hand.
    </InlineNotice>
  );
}

/**
 * Machines past their service date (E3-S11 D4).
 *
 * <p>Nothing at all when the count is null or zero, and the two are different silences. Null is the
 * server saying this reader does not book the engineer — kitchen staff hold no
 * `MANAGE_EQUIPMENT_SERVICING` — and zero is the temple saying nothing is late. *0 machines are
 * past their service date* would be a line that teaches its reader to skip the panel, and then the
 * panel is worth nothing on the morning something genuinely is.
 *
 * <p>Only overdue is counted. The amber due-soon machines stay on the Equipment screen: a morning
 * screen that warns a month early, every month, is one an admin learns to scroll past — the same
 * argument E4-S14 D5 made about the unrecorded-meal nudge above.
 *
 * <p>`danger`, unlike every other notice here, and deliberately. Amber is what the Equipment screen
 * uses for a service that is coming; this line only ever appears once the date has gone by, which
 * is the state the design system reserves red for. It links already filtered, because sending
 * somebody to find them among everything else is not much of a nudge.
 */
function equipmentNotice(data: TodayView) {
  const overdue = data.equipmentOverdue;
  if (overdue == null || overdue === 0) return null;
  const one = overdue === 1;

  return (
    <InlineNotice
      tone="danger"
      title={
        <>
          <span className="font-semibold">{one ? "1 machine" : `${overdue} machines`}</span>{" "}
          {one ? "is" : "are"} past {one ? "its" : "their"} service date.
        </>
      }
      action={
        <ButtonLink href="/equipment?serviceStatus=OVERDUE" size="sm" variant="ghost">
          See which
        </ButtonLink>
      }
    >
      Book a service visit.
    </InlineNotice>
  );
}

/**
 * Drafts that have reached, or passed, the last day they could be ordered (T-137, D-24a).
 *
 * <h2>Why this is on the morning screen at all</h2>
 *
 * <p>D-24a took a line off the shopping list the moment a purchase order is created rather than
 * when it is sent, because otherwise "they will be there in the shopping list begging to be
 * ordered, someone else will take pity and generate another PO. Same ingredients, 2 PO's." The cost
 * of that decision is a draft nobody ever sends: it holds its ingredients off the list and orders
 * nothing. Rajeev closed the hole in the same breath — a warning here and at the top of the
 * purchase-orders page, and a nightly sweep that cancels a draft once the day it was needed has
 * gone.
 *
 * <h2>It fetches its own list</h2>
 *
 * <p>Like `PlatformNotices` above. The alternative was a count on the `/today` payload, which would
 * have meant editing the dashboard's own service for a fact that belongs to purchase orders — and
 * the order list already answers this question, with the same lead-time arithmetic as every other
 * screen, because the server works it out in one place.
 *
 * <p><strong>A failure here shows nothing at all.</strong> This is the morning screen and the
 * notice is a nudge; a red box about purchase orders on a cook's dashboard, because a list failed
 * to load, is worse than the nudge is good. Every role that can open this screen holds
 * `MANAGE_PURCHASE_ORDERS`, so a 403 is not the expected case — it is simply not worth shouting
 * about if it ever happens.
 */
function DraftsAtRiskNotice() {
  const load = useCallback((token?: string) => api.listPurchaseOrders("DRAFT", token), []);
  const { data } = useAuthedQuery(load);
  const drafts = (data ?? []).filter(
    (po) => po.orderUrgency === "ORDER_TODAY" || po.orderUrgency === "TOO_LATE"
  );
  if (drafts.length === 0) return null;

  const past = drafts.filter((po) => po.orderUrgency === "TOO_LATE").length;
  const today = drafts.length - past;

  return (
    <InlineNotice
      // Amber and not red, unlike the equipment line above. Every one of these is still something
      // somebody can do something about this morning — send it, or raise it again for a day that
      // works — which is the distinction the design system draws between the two tones.
      tone="warning"
      title={
        <>
          <span className="font-semibold">
            {plural(drafts.length, "draft order", "draft orders")}
          </span>{" "}
          {past > 0 && today > 0
            ? `need sending: ${past} past ${past === 1 ? "its" : "their"} order date, ${today} due today`
            : past > 0
              ? `${drafts.length === 1 ? "is past its" : "are past their"} order date`
              : "must be sent today"}
          .
        </>
      }
      action={
        <ButtonLink href="/orders?status=DRAFT" size="sm" variant="ghost">
          Open them
        </ButtonLink>
      }
    >
      {drafts.length === 1
        ? "Its items stay off the shopping list until you send it."
        : "Their items stay off the shopping list until you send them."}
    </InlineNotice>
  );
}

function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

/** A tile's figure, without the paise nobody wants on a tile. */
function inr(amount: number): string {
  return money(Math.round(amount), "INR");
}

