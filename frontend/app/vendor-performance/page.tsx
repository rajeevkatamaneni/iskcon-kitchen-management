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
 * <p><strong>On-time carries two counts and not one</strong> (T-124). It is scored per item now, so
 * a bare 80% could be two items missing or ten items all a fifth short — the "eight of ten items"
 * beneath the percentage is what tells those apart. A vendor who never turned up at all is named
 * separately again, because a zero from a supplier who abandoned an order is a different fact from
 * a zero from one who came a fortnight late, and averaging them into one figure would lose it.
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
    <div className="table-wrap overflow-x-auto">
      <table className={TABLE}>
        <caption className="sr-only">
          Supplier delivery record for orders placed {report.from} to {report.to}, with what is open
          with each of them today
        </caption>
        {/* Fixed shares rather than letting the browser size to content.
            "Open now" was so narrow that its overdue pill dropped onto a second line under the
            count — the one column where the number and its warning belong together — so it takes
            what it needs to keep them on one line. The vendor column keeps the room its names
            actually want; a first pass cut it to 22% and Rajeev asked for half as much again
            (2026-09-05), which is this. What is left over is split evenly across the three
            measures, because nothing about them makes one wider than another. */}
        <colgroup>
          <col style={{ width: "33%" }} />
          <col style={{ width: "16%" }} />
          <col style={{ width: "16%" }} />
          <col style={{ width: "16%" }} />
          <col style={{ width: "19%" }} />
        </colgroup>
        <thead className={THEAD}>
          <tr>
            <th scope="col" className={`${TH_TEXT} ${WRAP}`}>
              Vendor
            </th>
            {/* "Orders on time" until T-124, and renamed with the arithmetic: the figure is no
                longer a count of orders but the average of how much of each order was there in
                time, and a header that says "orders" would send a reader looking for a fraction
                that is not on the screen. */}
            <th scope="col" className={TH_NUM}>
              On time
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
                {/* Its own line and its own words, never folded into the note above. A supplier who
                    never came is the finding this column exists to surface, and it is the one thing
                    here that is a fact rather than a threshold — so it is allowed a pill. */}
                {vendor.abandonedOrders > 0 && (
                  <span className="mt-1 flex justify-end">
                    <Badge tone="warning">{abandonedNote(vendor.abandonedOrders)}</Badge>
                  </span>
                )}
                {/*
                  What this percentage leaves out, and whose doing it was (T-137, D-25).

                  Rajeev: "That is a FAVOR we are asking." An order we submitted after this
                  supplier's agreed lead time asked for something their notice period could not
                  deliver, so a delay on it is not counted towards their performance — and the
                  figure above therefore excludes it.

                  It has to be visible. He was explicit that the exclusions belong on the screen
                  rather than quietly changing a percentage, which is the same standard the
                  abandoned count above already meets: a reader who cannot see what was left out
                  cannot check the number.

                  A neutral pill and not a warning one. The order says nothing bad about this
                  vendor — if anything it says something about us — and colouring it amber beside
                  "never delivered" would read as a second black mark against them.
                */}
                {vendor.ordersSentLate > 0 && (
                  <span className="mt-1 flex justify-end">
                    <Badge tone="neutral">{sentLateNote(vendor.ordersSentLate)}</Badge>
                  </span>
                )}
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
                    {/* On one line with the count, which is what the extra width bought. A pill on
                        its own line under a number reads as a second fact about the vendor rather
                        than as the warning attached to that number. */}
                    <span className="inline-flex flex-wrap items-center justify-end gap-2">
                      {vendor.openOrders.toLocaleString("en-IN")}
                      {overdue(vendor) > 0 && (
                        <Badge tone="warning">{overdueNote(vendor)}</Badge>
                      )}
                    </span>
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
                {itemsNote(report.itemsOnTime, report.itemsScored)} across{" "}
                {report.ordersJudged.toLocaleString("en-IN")}{" "}
                {report.ordersJudged === 1 ? "order" : "orders"}
              </span>
              {report.abandonedOrders > 0 && (
                <span className="mt-1 block text-xs font-normal text-ink-muted">
                  {abandonedNote(report.abandonedOrders)}
                </span>
              )}
              {report.ordersSentLate > 0 && (
                <span className="mt-1 block text-xs font-normal text-ink-muted">
                  {sentLateNote(report.ordersSentLate)}
                </span>
              )}
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
    "On time is scored item by item. Each thing on an order counts how much of it was there on or before the day it was needed, so eight of ten items in time is 80% — and bringing more than was ordered does not make up for something that never came.",
    "An order split across two days is still fully on time if both days were inside the window. What is measured is whether the goods were there in time, not how many deliveries brought them.",
    "The fill rate beside it is a different question: how much of the order turned up in the end, whenever it turned up, and how much of that the temple kept.",
    "Drafts are left out, and so is a cancellation nobody has marked against the vendor. An order that was sent and then cancelled because the vendor never delivered it does count, and scores nothing; an order that was never sent cannot be marked that way at all, so nothing a vendor was never told about reaches these figures. On time and the fill rate cover orders placed in this period whose needed-by date has passed; open orders are whatever is open today, whenever it was ordered.",
  ];
  if (report.ordersSentLate > 0) {
    parts.push(
      `${report.ordersSentLate.toLocaleString("en-IN")} ${
        report.ordersSentLate === 1 ? "order was" : "orders were"
      } sent after the vendor had asked to be given — later than the notice period they agreed at onboarding — so ${
        report.ordersSentLate === 1 ? "it is" : "they are"
      } left out of the on-time figure. We asked for something their lead time could not deliver, so a delay on ${
        report.ordersSentLate === 1 ? "it" : "them"
      } is not theirs to answer for. The fill rate still counts ${
        report.ordersSentLate === 1 ? "it" : "them"
      }: ordering late excuses lateness, not a half-empty delivery.`
    );
  }
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

/**
 * The counts behind the on-time percentage — the whole point of the column.
 *
 * <p>Items and then orders, in that order, because items are the grain the percentage is made at
 * (T-124) and orders are the context. "8 of 10 items across 3 orders" says two things a bare 80%
 * does not: that two whole items were missing rather than every item being a fifth short, and that
 * the figure rests on three orders rather than thirty.
 */
function onTimeNote(vendor: VendorPerformanceRow): string {
  if (vendor.ordersJudged === 0) {
    return vendor.ordersPlaced === 1 ? "1 order, not yet due" : `${vendor.ordersPlaced} orders, none yet due`;
  }
  const orders = `${vendor.ordersJudged.toLocaleString("en-IN")} ${
    vendor.ordersJudged === 1 ? "order" : "orders"
  }`;
  const counted = `${itemsNote(vendor.itemsOnTime, vendor.itemsScored)} across ${orders}`;
  return vendor.ordersWithoutNeededBy > 0
    ? `${counted} · ${vendor.ordersWithoutNeededBy} with no date`
    : counted;
}

/**
 * "8 of 10 items". Never a bare fraction: the word is what stops a reader taking it for orders,
 * which is exactly what this line meant until T-124 — the same shape of sentence about a different
 * thing.
 */
function itemsNote(onTime: number, scored: number): string {
  return `${onTime.toLocaleString("en-IN")} of ${scored.toLocaleString("en-IN")} ${
    scored === 1 ? "item" : "items"
  }`;
}

/**
 * The supplier who never came, in the words a person would use. Not "abandoned": that is the
 * column's name in the data and it reads as something the temple did rather than something the
 * vendor did.
 */
function abandonedNote(count: number): string {
  return count === 1 ? "1 order never delivered" : `${count.toLocaleString("en-IN")} orders never delivered`;
}

/**
 * "1 order we sent late" — ours, and said so.
 *
 * <p>The word "we" is the whole sentence. This count is the one figure on this screen that is about
 * the temple rather than about the supplier, and a reader skimming a column of judgements needs to
 * see that immediately or it looks like one more thing the vendor did.
 */
function sentLateNote(count: number): string {
  return count === 1
    ? "1 order we sent late — not counted"
    : `${count.toLocaleString("en-IN")} orders we sent late — not counted`;
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
