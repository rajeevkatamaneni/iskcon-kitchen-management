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
import { api, type IssuedFromStore, type KitchenIssueCost } from "@/lib/api";
import { money, todayIso } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { TABLE, THEAD, TR, TH_TEXT, TH_NUM, TD_TEXT, TD_NUM, WRAP } from "@/components/ds/table";

/**
 * What the temple store issued to each kitchen, costed (E10-S13).
 *
 * <p>An issue already records which kitchen the food went to, so the attribution has been in the
 * ledger since E10-S7 and nobody had asked it a question. There is no new noun here: the kitchen is
 * the cost centre, because a cost centre alongside it would map one-to-one onto the kitchens on the
 * day it was built.
 *
 * <p><strong>The name of this screen is the point of it</strong> (INV5). The Deity kitchen's mathajis
 * sometimes buy things themselves, and E10 D2 accepts that on purpose — issuing takes food off the
 * temple's books and what the kitchen does next is its own business. So every figure here is what
 * the store issued and can never be what the kitchen spent. Called "Deity kitchen food cost" it
 * would be quoted as one inside a week, which is why it is called what it is in the heading, in the
 * sentence under it, and in the notice above the table.
 */

type View = "week" | "month" | "year";

const VIEWS = [
  { value: "week" as const, label: "Week" },
  { value: "month" as const, label: "Month" },
  { value: "year" as const, label: "Year" },
];

export default function IssuedFromStorePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <IssuedFromStoreView />
    </RequireRole>
  );
}

function IssuedFromStoreView() {
  // A month at a time, like the other costing screen. A week holds too few requests to compare one
  // kitchen against another, and a year flattens the festivals a store actually issues for.
  const [view, setView] = useState<View>("month");
  const [anchor, setAnchor] = useState(todayIso());
  const { from, to } = periodRange(view, anchor);

  const fetcher = useCallback(
    (token: string | undefined) => api.issuedFromStore(from, to, token),
    [from, to]
  );
  const { data, error, loading } = useAuthedQuery(fetcher);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/issued-from-store" />

      <main className="min-w-0 flex-1">
        <Screen>
          <PageHeader
            title="Issued from the temple store"
            subtitle="What the store issued to each kitchen, costed. A kitchen may also buy food itself, and that never reaches these figures. Each one is a floor, not a total."
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
            <Loading label="Working out what the store issued…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : !data || data.kitchens.length === 0 ? (
            <EmptyState title="The store issued nothing in this period">
              Record an issue against an approved ingredient request, and it appears here.
            </EmptyState>
          ) : (
            <>
              <InlineNotice tone="info" title={caveatTitle(data)}>
                {caveatDetail(data)}
              </InlineNotice>
              <KitchenTable report={data} />
              <p className="mt-4 text-xs text-ink-muted">
                If a kitchen carries its own purchases into the temple store, record them as a
                donation in kind. They are then issued back out like anything else, and they reach
                this report.
              </p>
            </>
          )}
        </Screen>
      </main>
    </div>
  );
}

/** The kitchens, dearest first — which is the order the server sends and the answer to the question. */
function KitchenTable({ report }: { report: IssuedFromStore }) {
  return (
    <div className="overflow-x-auto rounded-lg bg-raised">
      <table className={TABLE}>
        <caption className="sr-only">
          Estimated materials cost of what the temple store issued to each kitchen, {report.from} to{" "}
          {report.to}
        </caption>
        <thead className={THEAD}>
          <tr>
            <th scope="col" className={`${TH_TEXT} ${WRAP}`}>
              Kitchen
            </th>
            <th scope="col" className={TH_NUM}>
              Requests
            </th>
            <th scope="col" className={TH_NUM}>
              Ingredients
            </th>
            <th scope="col" className={TH_NUM}>
              Estimated materials
            </th>
          </tr>
        </thead>
        <tbody>
          {report.kitchens.map((kitchen) => (
            <tr key={kitchen.kitchenId} className={TR}>
              <th scope="row" className={`${TD_TEXT} ${WRAP} font-normal text-ink`}>
                {kitchen.kitchen}
                {kitchen.ingredientsWithoutPrice > 0 && (
                  <span className="mt-1 block text-xs text-ink-muted">{noPriceNote(kitchen)}</span>
                )}
                {/*
                  One kitchen, one door. A kitchen on the meal planner draws its stock as consumption,
                  so anything on its row here is from before it opted in, and a reader must not take
                  the figure for a current one.
                */}
                {kitchen.usesMealPlanner && (
                  <span className="mt-1 block text-xs text-ink-muted">
                    Now plans its meals here, so its newer food is counted as consumption.
                  </span>
                )}
              </th>
              <td className={TD_NUM}>
                {kitchen.requests.toLocaleString("en-IN")}
              </td>
              <td className={TD_NUM}>
                {kitchen.ingredients.toLocaleString("en-IN")}
              </td>
              <td className={`${TD_NUM} font-medium`}>
                {money(kitchen.estimatedTotal, "INR")}
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr className="border-t border-hairline bg-sunken">
            <th scope="row" className={`${TD_TEXT} font-medium text-ink`}>
              All kitchens
            </th>
            <td className={TD_NUM}>
              {report.requests.toLocaleString("en-IN")}
            </td>
            {/*
              Deliberately blank. Two kitchens issued the same rice have been issued one ingredient
              between them, and adding the columns would say two.
            */}
            <td className={TD_NUM} />
            <td className={`${TD_NUM} font-medium`}>
              {money(report.estimatedTotal, "INR")}
            </td>
          </tr>
        </tfoot>
      </table>
    </div>
  );
}

/**
 * What the figures say about themselves, in the words the rest of the costing already uses.
 *
 * <p>"Estimated, materials only" is the cost-per-serving screen's wording and is kept verbatim: much
 * of the store is donated and has no purchase price (E3-S8 D1), and labour and utilities are
 * deliberately absent (E3-S8 D4). What this screen adds is the floor: a figure headed with a
 * kitchen's name invites being read as what that kitchen's food cost, and it is not that.
 */
function caveatTitle(report: IssuedFromStore): string {
  if (report.ingredientsWithoutPrice > 0) {
    return `Estimated, materials only · ${report.ingredientsWithoutPrice} ${
      report.ingredientsWithoutPrice === 1 ? "ingredient has" : "ingredients have"
    } no known price`;
  }
  return "Estimated, materials only — from vendors’ last-known prices";
}

function caveatDetail(report: IssuedFromStore): string {
  const parts = [
    "Labour, fuel and the rest of what a meal costs are not in these figures.",
    "This is what left the temple store, and nothing else. A kitchen that buys food itself keeps no record of it here, so its real food cost is higher than the figure beside its name.",
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
  return parts.join(" ");
}

function noPriceNote(kitchen: KitchenIssueCost): string {
  return `${kitchen.ingredientsWithoutPrice} ${
    kitchen.ingredientsWithoutPrice === 1 ? "ingredient has" : "ingredients have"
  } no known price`;
}
