"use client";

import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Badge } from "@/components/ds/Badge";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { Loading } from "@/components/Loading";
import { PageHeader } from "@/components/ds/PageHeader";
import { PeriodNav, periodHeading, stepPeriod } from "@/components/ds/PeriodNav";
import { Screen } from "@/components/ds/Screen";
import { api, type VendorPerformance, type VendorPerformanceRow } from "@/lib/api";
import { todayIso } from "@/lib/format";
import { TABLE, THEAD, TR, TH_TEXT, TH_NUM, TD_TEXT, TD_NUM, WRAP } from "@/components/ds/table";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * How the temple's suppliers have performed (E5-S9).
 *
 * <p>Nothing here is captured for the report. The needed-by date on a purchase order, the receipts
 * booked against it and the reasons anything was refused are all already recorded, and both receipt
 * tables are append-only — so the history behind every figure on this screen cannot have been
 * tidied up afterwards.
 *
 * <p><strong>Every percentage shows its denominator.</strong> "50% on time" is a different statement
 * about a supplier with two orders and one with forty, and the counts are what tell them apart. A
 * supplier with fewer than five judged orders is marked and sorted below the ranked ones rather than
 * ranked on a figure that is really about the sample size.
 *
 * <p><strong>No colour on the percentages.</strong> Semantic colour is for status, and "82% on time"
 * is not a status until somebody sets the number at which a supplier is failing — which is a temple's
 * policy and not this screen's to invent. The one thing coloured is an open order that is genuinely
 * past the day it was wanted, which is a fact and not a threshold.
 */

type View = "week" | "month" | "year";

const VIEWS = [
  { value: "week" as const, label: "Week" },
  { value: "month" as const, label: "Month" },
  { value: "year" as const, label: "Year" },
];

/** The receiving screen's own words for a refusal, in sentence case (DESIGN_SYSTEM). */
const REASON_LABEL: Record<string, string> = {
  DAMAGED: "Damaged",
  SPOILED: "Spoiled",
  WRONG_ITEM: "Wrong item",
  OTHER: "Other",
};

export default function VendorPerformancePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <VendorPerformanceView />
    </RequireRole>
  );
}

function VendorPerformanceView() {
  // A month at a time. A week holds too few orders from any one supplier to say anything, and a
  // year hides the supplier who has gone off in the last six weeks.
  const [view, setView] = useState<View>("month");
  const [anchor, setAnchor] = useState(todayIso());
  const { from, to } = rangeFor(view, anchor);

  const fetcher = useCallback(
    (token: string | undefined) => api.vendorPerformance(from, to, token),
    [from, to]
  );
  const { data, error, loading } = useAuthedQuery(fetcher);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/vendor-performance" />

      <main className="min-w-0 flex-1">
        <Screen>
          <PageHeader
            title="Vendor performance"
            subtitle="Whether each supplier delivers when they said, brings what was ordered, and what is still outstanding with them."
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
            <Loading label="Reading what each supplier delivered…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : !data || data.vendors.length === 0 ? (
            <EmptyState title="No orders with any supplier in this period">
              Send a purchase order and record what arrives, and this will say who delivers on time.
            </EmptyState>
          ) : (
            <>
              <InlineNotice tone="info" title="What these figures count">
                {caveat(data)}
              </InlineNotice>
              <VendorTable report={data} />
            </>
          )}
        </Screen>
      </main>
    </div>
  );
}

/**
 * The scorecard, worst on-time first — the ordering the server sends, because the report exists to
 * find the supplier letting the kitchen down and reading the column downwards should be the answer.
 */
function VendorTable({ report }: { report: VendorPerformance }) {
  return (
    <div className="overflow-x-auto rounded-lg bg-raised">
      <table className={TABLE}>
        <caption className="sr-only">
          Supplier delivery record for orders placed {report.from} to {report.to}, with what is open
          with each of them today
        </caption>
        <thead className={THEAD}>
          <tr>
            <th scope="col" className={`${TH_TEXT} ${WRAP}`}>
              Vendor
            </th>
            <th scope="col" className={TH_NUM}>
              Orders on time
            </th>
            <th scope="col" className={TH_NUM}>
              Fill rate
            </th>
            <th scope="col" className={TH_NUM}>
              Rejected
            </th>
            <th scope="col" className={TH_NUM}>
              Open now
            </th>
          </tr>
        </thead>
        <tbody>
          {report.vendors.map((vendor) => (
            <tr key={vendor.vendorId} className={TR}>
              <th scope="row" className={`${TD_TEXT} ${WRAP} font-normal text-ink`}>
                {vendor.vendorName}
                <span className="mt-1 flex flex-wrap gap-1">
                  {!vendor.active && <Badge tone="neutral">No longer used</Badge>}
                  {!vendor.enoughToRank && <Badge tone="neutral">Too few orders to rank</Badge>}
                </span>
              </th>

              {/*
                The percentage and the counts it was made from, always together. A percentage with no
                denominator is a lie, and this is the column somebody would quote in a meeting.
              */}
              <td className={TD_NUM}>
                <span className="font-medium">{asPercent(vendor.onTimePercent)}</span>
                <span className="mt-1 block text-xs text-ink-muted">{onTimeNote(vendor)}</span>
              </td>

              <td className={TD_NUM}>
                {asPercent(vendor.fillRatePercent)}
                {vendor.linesJudged > 0 && (
                  <span className="mt-1 block text-xs text-ink-muted">
                    across {vendor.linesJudged.toLocaleString("en-IN")}{" "}
                    {vendor.linesJudged === 1 ? "line" : "lines"}
                  </span>
                )}
              </td>

              <td className={TD_NUM}>
                {vendor.rejectedLines === 0 ? (
                  "—"
                ) : (
                  <>
                    {vendor.rejectedLines.toLocaleString("en-IN")}
                    {/* Four reasons and their counts would run the column the width of the table,
                        so the breakdown is bounded and stacks downwards instead. */}
                    <span className="mt-1 block max-w-[11rem] whitespace-normal text-xs text-ink-muted">
                      {rejectionNote(vendor)}
                    </span>
                  </>
                )}
              </td>

              <td className={TD_NUM}>
                {vendor.openOrders === 0 ? (
                  "—"
                ) : (
                  <>
                    {vendor.openOrders.toLocaleString("en-IN")}
                    {overdue(vendor) > 0 && (
                      <span className="mt-1 block">
                        <Badge tone="warning">{overdueNote(vendor)}</Badge>
                      </span>
                    )}
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr className="border-t border-hairline bg-sunken">
            <th scope="row" className={`${TD_TEXT} font-medium text-ink`}>
              All vendors
            </th>
            <td className={`${TD_NUM} font-medium`}>
              {asPercent(report.onTimePercent)}
              <span className="mt-1 block text-xs font-normal text-ink-muted">
                {report.onTimeOrders.toLocaleString("en-IN")} of{" "}
                {report.ordersJudged.toLocaleString("en-IN")}
              </span>
            </td>
            <td className={`${TD_NUM} font-medium`}>
              {asPercent(report.fillRatePercent)}
            </td>
            <td className={TD_NUM}>
              {report.rejectedLines === 0 ? "—" : report.rejectedLines.toLocaleString("en-IN")}
            </td>
            <td className={TD_NUM}>
              {report.openOrders === 0 ? "—" : report.openOrders.toLocaleString("en-IN")}
            </td>
          </tr>
        </tfoot>
      </table>
    </div>
  );
}

/**
 * What the figures count, said before anybody reads one.
 *
 * <p>Three things a reader would otherwise get wrong: that on-time is per order and not per
 * ingredient, that it is measured at the first delivery and so needs the fill rate beside it, and
 * that the open column is today's position rather than the period's.
 */
function caveat(report: VendorPerformance): string {
  const parts = [
    "An order is on time if something arrived on or before the day it was needed. The needed-by date is on the order, not on each ingredient, so this counts whole orders — an order of eight things is one late order, whichever of the eight was late.",
    "It is measured at the first delivery, so it says the lorry turned up. The fill rate beside it is what says whether it brought everything.",
    "Drafts and cancelled orders are left out. Orders on time and the fill rate cover orders placed in this period whose needed-by date has passed; open orders are whatever is open today, whenever it was ordered.",
  ];
  if (report.ordersWithoutNeededBy > 0) {
    parts.push(
      `${report.ordersWithoutNeededBy.toLocaleString("en-IN")} ${
        report.ordersWithoutNeededBy === 1 ? "order has" : "orders have"
      } no needed-by date, so there is nothing to be late against and ${
        report.ordersWithoutNeededBy === 1 ? "it is" : "they are"
      } outside these figures.`
    );
  }
  return parts.join(" ");
}

/** The counts behind the on-time percentage — the whole point of the column. */
function onTimeNote(vendor: VendorPerformanceRow): string {
  if (vendor.ordersJudged === 0) {
    return vendor.ordersPlaced === 1 ? "1 order, not yet due" : `${vendor.ordersPlaced} orders, none yet due`;
  }
  const counted = `${vendor.onTimeOrders.toLocaleString("en-IN")} of ${vendor.ordersJudged.toLocaleString(
    "en-IN"
  )}`;
  return vendor.ordersWithoutNeededBy > 0
    ? `${counted} · ${vendor.ordersWithoutNeededBy} with no date`
    : counted;
}

function rejectionNote(vendor: VendorPerformanceRow): string {
  return vendor.rejections
    .map((r) => `${REASON_LABEL[r.reason] ?? r.reason} ${r.lines}`)
    .join(" · ");
}

function overdue(vendor: VendorPerformanceRow): number {
  return vendor.openDue1To30 + vendor.openOverdue31Plus;
}

/**
 * The payables screen's own aging words, unchanged. A second vocabulary for "late" in one
 * application is something a person has to learn rather than read.
 */
function overdueNote(vendor: VendorPerformanceRow): string {
  if (vendor.openOverdue31Plus > 0 && vendor.openDue1To30 > 0) {
    return `${vendor.openDue1To30} 1–30 days overdue · ${vendor.openOverdue31Plus} 31+ days overdue`;
  }
  if (vendor.openOverdue31Plus > 0) {
    return `${vendor.openOverdue31Plus} 31+ days overdue`;
  }
  return `${vendor.openDue1To30} 1–30 days overdue`;
}

/** A dash, never a zero: nothing judged is not the same statement as nothing delivered. */
function asPercent(value: number | null): string {
  return value === null ? "—" : `${value.toLocaleString("en-IN")}%`;
}

/**
 * The dates behind the period on screen — whole calendar periods, as the cost-per-serving report
 * uses, so a supplier's August can be read against their July a week later and still say the same.
 */
function rangeFor(view: View, anchor: string): { from: string; to: string } {
  if (view === "year") {
    return { from: `${anchor.slice(0, 4)}-01-01`, to: `${anchor.slice(0, 4)}-12-31` };
  }
  if (view === "week") {
    const start = addDays(anchor, -new Date(`${anchor}T00:00:00`).getDay());
    return { from: start, to: addDays(start, 6) };
  }
  const first = `${anchor.slice(0, 7)}-01`;
  const last = new Date(Number(anchor.slice(0, 4)), Number(anchor.slice(5, 7)), 0);
  return { from: first, to: `${anchor.slice(0, 7)}-${String(last.getDate()).padStart(2, "0")}` };
}

function addDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00`);
  d.setDate(d.getDate() + days);
  return [
    d.getFullYear(),
    String(d.getMonth() + 1).padStart(2, "0"),
    String(d.getDate()).padStart(2, "0"),
  ].join("-");
}
