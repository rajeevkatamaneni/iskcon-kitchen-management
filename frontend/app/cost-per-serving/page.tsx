"use client";

import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { Loading } from "@/components/Loading";
import { PageHeader } from "@/components/ds/PageHeader";
import { PeriodNav, periodHeading, periodRange, stepPeriod } from "@/components/ds/PeriodNav";
import { Screen } from "@/components/ds/Screen";
import { api, type CostByMealKind, type MealKindCost } from "@/lib/api";
import { dayRange, todayIso } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { RULED_TABLE, THEAD, TR, TH_PRIMARY, TD_PRIMARY, TH_FIXED, TD_FIXED_NUM } from "@/components/ds/table";

/**
 * What a serving costs, compared across the kinds of meal a temple cooks (E3-S9).
 *
 * <p>The reviewers asked what a public-prasadam plate costs against a Sunday feast plate. Today's
 * *Cost of materials* tile cannot answer it and was never meant to: E3-S8 D3 settled that the
 * estimate was for the day, and it was right about the question it was asked — "what is today's
 * food costing us" is a headline, and a daily total is exactly that. A comparison between kinds is
 * a different question of the same data, and this screen is where it is asked.
 *
 * <p>Everything the daily figure says about itself is said here with the same force. It is an
 * estimate; it covers materials and nothing else; and where an ingredient has no known price it is
 * counted out loud rather than quietly costed at zero.
 *
 * <p>It also says what each figure was worked out from (T-212). A recorded meal is costed at what its
 * job card says was cooked, and a meal not yet recorded at what was planned. A month's row mixes the
 * two, so every row and the total say how many meals are of each, beneath the meal count.
 */

type View = "week" | "month" | "year";

const VIEWS = [
  { value: "week" as const, label: "Week" },
  { value: "month" as const, label: "Month" },
  { value: "year" as const, label: "Year" },
];

export default function CostPerServingPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <CostPerServingView />
    </RequireRole>
  );
}

function CostPerServingView() {
  // A month at a time by default: a week holds too few feasts to compare against the lunches, and a
  // year flattens the seasons a temple actually cooks in.
  const [view, setView] = useState<View>("month");
  const [anchor, setAnchor] = useState(todayIso());
  const { from, to } = periodRange(view, anchor);

  const fetcher = useCallback(
    (token: string | undefined) => api.costByMealKind(from, to, token),
    [from, to]
  );
  const { data, error, loading } = useAuthedQuery(fetcher);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/cost-per-serving" />

      <main className="min-w-0 flex-1">
        <Screen>
          <PageHeader
            title="Cost per serving"
            subtitle="What a serving costs at each kind of meal, so a prasadam plate can be read against a feast plate."
            tabs={
              <PeriodNav
                label="Period"
                views={VIEWS}
                view={view}
                onView={(next) => setView(next)}
                heading={periodHeading(view, anchor)}
                onStep={(delta) => setAnchor(stepPeriod(view, anchor, delta))}
              />
            }
          />

          {loading ? (
            <Loading label="Working out what each meal cost…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : !data || data.kinds.length === 0 ? (
            <EmptyState title="Nothing was cooked in this period">
              Plan and record some meals, and this will say what a serving of each kind cost.
            </EmptyState>
          ) : (
            <>
              <InlineNotice tone="info" title={caveatTitle(data)}>
                {caveatDetail(data)}
              </InlineNotice>
              <KindTable report={data} />
            </>
          )}
        </Screen>
      </main>
    </div>
  );
}

/**
 * The comparison, dearest serving first — which is the ordering the server sends, because the point
 * of the screen is that reading it top to bottom is the answer.
 */
function KindTable({ report }: { report: CostByMealKind }) {
  return (
    <div className="table-wrap overflow-x-auto">
      <table className={RULED_TABLE}>
        <caption className="sr-only">
          {/* Read aloud as the table's name, so it is said the way the screen writes a date (T-194). */}
          Estimated materials cost per serving by kind of meal, {dayRange(report.from, report.to, false)}
        </caption>
        <thead className={THEAD}>
          <tr>
            <th scope="col" className={TH_PRIMARY}>
              Kind of meal
            </th>
            <th scope="col" className={TH_FIXED}>
              Meals
            </th>
            <th scope="col" className={TH_FIXED}>
              Servings
            </th>
            <th scope="col" className={TH_FIXED}>
              Estimated materials
            </th>
            <th scope="col" className={TH_FIXED}>
              Cost per serving
            </th>
          </tr>
        </thead>
        <tbody>
          {report.kinds.map((kind) => (
            <tr key={kind.mealKind} className={TR}>
              <th scope="row" className={`${TD_PRIMARY} font-normal text-ink`}>
                {kind.mealKind}
                {kind.ingredientsWithoutPrice > 0 && (
                  <span className="mt-1 block text-xs text-ink-muted">{noPriceNote(kind)}</span>
                )}
              </th>
              <td data-label="Meals" className={TD_FIXED_NUM}>
                {kind.meals.toLocaleString("en-IN")}
                <BasisNote cost={kind} />
              </td>
              <td data-label="Servings" className={TD_FIXED_NUM}>
                {kind.servings.toLocaleString("en-IN")}
                {/* The figure above refuses to wrap; its note is a sentence and may, but only when
                    the table is short of room (the table rule, T-236). It was held to 10rem, which
                    wrapped it beside empty space. */}
                {kind.mealsWithoutServings > 0 && (
                  <span className="mt-1 block text-xs text-ink-muted">
                    {noHeadCountNote(kind)}
                  </span>
                )}
              </td>
              <td data-label="Estimated materials" className={TD_FIXED_NUM}>
                {rupees(kind.estimatedTotal)}
              </td>
              {/*
                A dash, never a zero and never a figure carried over from another kind. Where nobody
                counted the people at any meal of this kind there is no denominator, and inventing
                one would put a number under this heading that is not a cost per serving.
              */}
              <td data-label="Cost per serving" className={`${TD_FIXED_NUM} font-medium`}>
                {kind.costPerServing === null ? "—" : rupees(kind.costPerServing)}
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr className="border-t border-hairline bg-sunken">
            <th scope="row" className={`${TD_PRIMARY} font-medium text-ink`}>
              All meals
            </th>
            <td data-label="Meals" className={TD_FIXED_NUM}>
              {report.meals.toLocaleString("en-IN")}
              <BasisNote cost={report} />
            </td>
            <td data-label="Servings" className={TD_FIXED_NUM}>
              {report.servings.toLocaleString("en-IN")}
            </td>
            <td data-label="Estimated materials" className={`${TD_FIXED_NUM} font-medium`}>
              {rupees(report.estimatedTotal)}
            </td>
            {/*
              Deliberately blank. A cost per serving across every kind would average a feast plate
              with a breakfast one and read as a fact about neither, which is exactly the number the
              daily total already is and the reason this screen exists.
            */}
            <td data-label="Cost per serving" className={TD_FIXED_NUM} />
          </tr>
        </tfoot>
      </table>
    </div>
  );
}

/**
 * What the figures say about themselves, in the words the Today tile already uses.
 *
 * <p>"Estimated" is the tile's word and stays. What is added is "materials only", because a figure
 * headed *cost per serving* invites being read as what a plate costs the temple, and labour is
 * deliberately absent from it (E3-S8 D4): a cook on a 6am–2pm shift is making breakfast and lunch,
 * so their pay can only be allocated across those meals, never measured.
 */
function caveatTitle(report: CostByMealKind): string {
  if (report.ingredientsWithoutPrice > 0) {
    return `Estimated, materials only · ${report.ingredientsWithoutPrice} ${
      report.ingredientsWithoutPrice === 1 ? "ingredient has" : "ingredients have"
    } no known price`;
  }
  return "Estimated, materials only — from vendors’ last-known prices";
}

function caveatDetail(report: CostByMealKind): string {
  const parts = [
    "Labour, fuel and the rest of what a meal costs are not in these figures.",
    // The rule, once, in words (T-212). The rows say how many meals each half applies to.
    "Recorded meals are costed at what was cooked, the rest at the plan.",
  ];
  if (report.ingredientsWithoutPrice > 0) {
    parts.push(
      `${report.unpriced
        .slice(0, 6)
        .map((ingredient) => ingredient.name)
        .join(", ")}${report.unpriced.length > 6 ? " and others" : ""} ${
        report.ingredientsWithoutPrice === 1 ? "is" : "are"
      } left out until a vendor price is recorded.`
    );
  }
  if (report.mealsWithoutServings > 0) {
    parts.push(
      `${report.mealsWithoutServings} ${
        report.mealsWithoutServings === 1 ? "meal has" : "meals have"
      } no head count, so ${
        report.mealsWithoutServings === 1 ? "it is" : "they are"
      } in the totals but not in the cost per serving.`
    );
  }
  return parts.join(" ");
}

/**
 * How many of a figure's meals are costed at what was cooked and how many at the plan, beneath the
 * meal count (T-212). Allowed to wrap, like the head-count note beside it, because it is a phrase —
 * but only when the table is short of room (T-236), so it carries no width cap of its own.
 */
function BasisNote({ cost }: { cost: Pick<CostByMealKind, "mealsCostedAsCooked" | "mealsCostedAsPlanned"> }) {
  const text = costBasis(cost);
  if (!text) return null;
  return (
    <span className="mt-1 block text-xs text-ink-muted">{text}</span>
  );
}

/**
 * "2 meals from what was cooked, 1 from the plan" — the wording Rajeev gave (T-212), said the same
 * way on Today's tile. Only the half that has meals is said, and nothing at all when neither does.
 * The same few lines live in `app/today/page.tsx`: a page file may export nothing but its page, and
 * the shared formatting module is outside this change, so the two copies are kept word for word.
 */
function costBasis(cost: {
  mealsCostedAsCooked?: number;
  mealsCostedAsPlanned?: number;
}): string {
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
 * Every money figure on this screen, in whole rupees: "₹12,186", never "₹12,185.65" (Rajeev,
 * Decisions Desk, 2026-09-18).
 *
 * <p>The shared `money` helper shows paise only where there are any, which is right for a
 * settlement that must not be rounded away, and wrong here. A column read top to bottom mixed
 * "₹12,185.65" with "₹20,375", so the eye compared lengths rather than amounts; and every figure on
 * this screen is an estimate built from vendors' last-known prices, so the paise were precision the
 * number does not have.
 *
 * <p>Half up, which is `Math.round` for the positive amounts a cost can be ("₹9.50" reads "₹10").
 * Indian grouping comes from the `en-IN` locale, as everywhere else ("₹1,20,375").
 *
 * <p>A cost above nothing that rounds to nothing reads "under ₹1", not "₹0" (Rajeev, 2026-09-18,
 * T-237). Whole rupees turned a 30-paise serving of rice water into "₹0", which says the food was
 * free. Only a figure that really is zero — nothing priced, nothing cooked — keeps "₹0". Tested on
 * `Math.round` itself rather than on "less than 0.5", so the line sits exactly where the rounding
 * does and the two cannot drift apart.
 */
function rupees(amount: number): string {
  if (amount > 0 && Math.round(amount) === 0) return "under ₹1";
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
  }).format(Math.round(amount));
}

function noPriceNote(kind: MealKindCost): string {
  return `${kind.ingredientsWithoutPrice} ${
    kind.ingredientsWithoutPrice === 1 ? "ingredient has" : "ingredients have"
  } no known price`;
}

function noHeadCountNote(kind: MealKindCost): string {
  return `${kind.mealsWithoutServings} ${
    kind.mealsWithoutServings === 1 ? "meal" : "meals"
  } not counted`;
}
