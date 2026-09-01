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
import { money, todayIso } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { TABLE, TD_NUM, TD_TEXT, THEAD, TH_NUM, TH_TEXT, TR } from "@/components/ds/table";

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
    <div className="overflow-x-auto rounded-lg bg-raised">
      <table className={TABLE}>
        <caption className="sr-only">
          Estimated materials cost per serving by kind of meal, {report.from} to {report.to}
        </caption>
        <thead className={THEAD}>
          <tr>
            <th scope="col" className={TH_TEXT}>
              Kind of meal
            </th>
            <th scope="col" className={TH_NUM}>
              Meals
            </th>
            <th scope="col" className={TH_NUM}>
              Servings
            </th>
            <th scope="col" className={TH_NUM}>
              Estimated materials
            </th>
            <th scope="col" className={TH_NUM}>
              Cost per serving
            </th>
          </tr>
        </thead>
        <tbody>
          {report.kinds.map((kind) => (
            <tr key={kind.mealKind} className={TR}>
              <th scope="row" className={`${TD_TEXT} font-normal text-ink`}>
                {kind.mealKind}
                {kind.ingredientsWithoutPrice > 0 && (
                  <span className="mt-1 block text-xs text-ink-muted">{noPriceNote(kind)}</span>
                )}
              </th>
              <td className={TD_NUM}>{kind.meals.toLocaleString("en-IN")}</td>
              <td className={TD_NUM}>
                {kind.servings.toLocaleString("en-IN")}
                {/* The figure above refuses to wrap; its note is a sentence and must be allowed to. */}
                {kind.mealsWithoutServings > 0 && (
                  <span className="mt-1 block max-w-[10rem] whitespace-normal text-xs text-ink-muted">
                    {noHeadCountNote(kind)}
                  </span>
                )}
              </td>
              <td className={TD_NUM}>
                {money(kind.estimatedTotal, "INR")}
              </td>
              {/*
                A dash, never a zero and never a figure carried over from another kind. Where nobody
                counted the people at any meal of this kind there is no denominator, and inventing
                one would put a number under this heading that is not a cost per serving.
              */}
              <td className={`${TD_NUM} font-medium`}>
                {kind.costPerServing === null ? "—" : money(kind.costPerServing, "INR")}
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr className="border-t border-hairline bg-sunken">
            <th scope="row" className={`${TD_TEXT} font-medium text-ink`}>
              All meals
            </th>
            <td className={TD_NUM}>{report.meals.toLocaleString("en-IN")}</td>
            <td className={TD_NUM}>
              {report.servings.toLocaleString("en-IN")}
            </td>
            <td className={`${TD_NUM} font-medium`}>
              {money(report.estimatedTotal, "INR")}
            </td>
            {/*
              Deliberately blank. A cost per serving across every kind would average a feast plate
              with a breakfast one and read as a fact about neither, which is exactly the number the
              daily total already is and the reason this screen exists.
            */}
            <td className={TD_NUM} />
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
