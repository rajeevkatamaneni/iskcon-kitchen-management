"use client";

import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Badge } from "@/components/ds/Badge";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { Loading } from "@/components/Loading";
import { PageHeader } from "@/components/ds/PageHeader";
import { PeriodNav, periodHeading, stepPeriod } from "@/components/ds/PeriodNav";
import { Screen } from "@/components/ds/Screen";
import { api, type VendorPerformance, type VendorPerformanceRow } from "@/lib/api";
import { dayRange, todayIso } from "@/lib/format";
import { RULED_TABLE, THEAD, TR, TH_PRIMARY, TD_PRIMARY, TH_FIXED, TD_FIXED_NUM } from "@/components/ds/table";
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
            subtitle="Whether each vendor delivers when they said, brings what was ordered, and what is still outstanding with them."
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
            <Loading label="Reading what each vendor delivered…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : !data || data.vendors.length === 0 ? (
            <EmptyState
              title="No orders in this period"
              action={<ButtonLink href="/orders">Open purchase orders</ButtonLink>}
            >
              Figures appear once orders are sent and received.
            </EmptyState>
          ) : (
            <>
              <InlineNotice tone="info" title="How these are worked out">
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
      <table className={RULED_TABLE}>
        <caption className="sr-only">
          {/* Read aloud as the table's name, so it is said the way the screen writes a date (T-194). */}
          Vendor delivery record for orders placed {dayRange(report.from, report.to, false)}, with what is open
          with each of them today
        </caption>
        {/* No column widths here. There used to be a colgroup of fixed shares (33% for the vendor,
            16–19% for each measure), which held the columns apart whatever they held; since
            2026-09-18 (T-236) every table is sized by the one rule in components/ds/table.ts —
            each column as wide as its content, the spare width shared evenly between them — and
            everything reads left, the percentages lined up the way the Vendor column is (Rajeev). */}
        <thead className={THEAD}>
          <tr>
            <th scope="col" className={TH_PRIMARY}>
              Vendor
            </th>
            {/* "Orders on time" until T-124, and renamed with the arithmetic: the figure is no
                longer a count of orders but the average of how much of each order was there in
                time, and a header that says "orders" would send a reader looking for a fraction
                that is not on the screen. */}
            <th scope="col" className={TH_FIXED}>
              On time
            </th>
            <th scope="col" className={TH_FIXED}>
              Filled
            </th>
            <th scope="col" className={TH_FIXED}>
              Rejected
            </th>
            <th scope="col" className={TH_FIXED}>
              Open now
            </th>
          </tr>
        </thead>
        <tbody>
          {report.vendors.map((vendor) => (
            <tr key={vendor.vendorId} className={TR}>
              <th scope="row" className={`${TD_PRIMARY} font-normal text-ink`}>
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
              <td data-label="On time" className={TD_FIXED_NUM}>
                <span className="font-medium">{asPercent(vendor.onTimePercent)}</span>
                <span className="mt-1 block text-xs text-ink-muted">{onTimeNote(vendor)}</span>
                {/* Its own line and its own words, never folded into the note above. A supplier who
                    never came is the finding this column exists to surface, and it is the one thing
                    here that is a fact rather than a threshold — so it is allowed a pill. A neutral
                    one: it is a historical finding in a report, not something to act on today, and
                    amber is kept for that (Rajeev, 2026-09-18, T-227). */}
                {vendor.abandonedOrders > 0 && (
                  <span className="mt-1 flex">
                    <Badge>{abandonedNote(vendor.abandonedOrders)}</Badge>
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
                  <span className="mt-1 flex">
                    <Badge tone="neutral">{sentLateNote(vendor.ordersSentLate)}</Badge>
                  </span>
                )}
                {/*
                  The other exclusion, and the same standard (T-142, D-26).

                  An order closed part-delivered where the vendor rang, apologised and made it right
                  is left out of this percentage AND out of the fill rate beside it. Rajeev was
                  explicit that the exclusions belong on the screen: "a number whose exclusions are
                  invisible cannot be checked". So a count sits here, exactly as the late-sent one
                  does, and the caveat above the table says what was excused and from what.

                  Neutral, not warning. Nothing about it is a black mark — it is the temple saying
                  it accepted an apology — and amber beside "never delivered" would read as a second
                  thing the vendor did wrong.
                */}
                {vendor.ordersExcused > 0 && (
                  <span className="mt-1 flex">
                    <Badge tone="neutral">{excusedNote(vendor.ordersExcused)}</Badge>
                  </span>
                )}
              </td>

              <td data-label="Filled" className={TD_FIXED_NUM}>
                {asPercent(vendor.fillRatePercent)}
                {vendor.linesJudged > 0 && (
                  <span className="mt-1 block text-xs text-ink-muted">
                    across {vendor.linesJudged.toLocaleString("en-IN")}{" "}
                    {vendor.linesJudged === 1 ? "item" : "items"}
                  </span>
                )}
                {/*
                  This count appears in BOTH percentage columns and only this count does, which is
                  the whole difference between the two exclusions (T-142, D-26 against T-137, D-25).

                  An order we sent late is left out of on-time and still counted in the fill rate:
                  ordering late excuses our timing, not a half-empty lorry. An order closed with the
                  shortfall excused is left out of both, because on a part-delivery the black mark
                  IS the half-empty lorry — waiving only the lateness would waive almost nothing.
                  So the fill rate has an exclusion now, and the standard the ruling set says it has
                  to be visible right here rather than inferred from the column next door.
                */}
                {vendor.ordersExcused > 0 && (
                  <span className="mt-1 flex">
                    <Badge tone="neutral">{excusedNote(vendor.ordersExcused)}</Badge>
                  </span>
                )}
              </td>

              <td data-label="Rejected" className={TD_FIXED_NUM}>
                {vendor.rejectedLines === 0 ? (
                  "—"
                ) : (
                  <>
                    {vendor.rejectedLines.toLocaleString("en-IN")}
                    {/* The breakdown by reason, on one line when the table has room for it and
                        wrapping under the count only when it has not (the table rule, T-236). It
                        used to be held to 11rem and so always stacked, beside empty space. */}
                    <span className="mt-1 block text-xs text-ink-muted">
                      {rejectionNote(vendor)}
                    </span>
                  </>
                )}
              </td>

              <td data-label="Open now" className={TD_FIXED_NUM}>
                {vendor.openOrders === 0 ? (
                  "—"
                ) : (
                  <>
                    {/* On one line with the count, which is what the extra width bought. A pill on
                        its own line under a number reads as a second fact about the vendor rather
                        than as the warning attached to that number. */}
                    <span className="inline-flex flex-wrap items-center gap-2">
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
            <th scope="row" className={`${TD_PRIMARY} font-medium text-ink`}>
              All vendors
            </th>
            <td data-label="On time" className={`${TD_FIXED_NUM} font-medium`}>
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
              {report.ordersExcused > 0 && (
                <span className="mt-1 block text-xs font-normal text-ink-muted">
                  {excusedNote(report.ordersExcused)}
                </span>
              )}
            </td>
            <td data-label="Filled" className={`${TD_FIXED_NUM} font-medium`}>
              {asPercent(report.fillRatePercent)}
            </td>
            <td data-label="Rejected" className={TD_FIXED_NUM}>
              {report.rejectedLines === 0 ? "—" : report.rejectedLines.toLocaleString("en-IN")}
            </td>
            <td data-label="Open now" className={TD_FIXED_NUM}>
              {report.openOrders === 0 ? "—" : report.openOrders.toLocaleString("en-IN")}
            </td>
          </tr>
        </tfoot>
      </table>
    </div>
  );
}

/**
 * How the figures are worked out, said before anybody reads one — in four short lines.
 *
 * <p>This used to be a 330-word paragraph that argued for each rule as well as stating it (T-224).
 * Nobody reads that in a hot kitchen, so the screen now states the rules and the reasons live here:
 *
 * <ul>
 * <li>On time is scored item by item, not order by order: eight of ten items there by the
 * needed-by date is 80%. Bringing more of one item does not make up for another that never came,
 * and an order split across two days is still fully on time if both days were inside the window —
 * what is measured is whether the goods were there, not how many deliveries brought them.</li>
 * <li>Filled is a different question: how much of the order arrived in the end, whenever it came.
 * The two sit side by side because on time alone hides a half-empty lorry.</li>
 * <li>Drafts are left out, and so is a cancellation nobody marked against the vendor; an order that
 * was never sent cannot reach these figures at all. Open orders are today's position, whenever they
 * were ordered.</li>
 * <li>An order the temple sent inside the vendor's notice period is left out of on time (we asked
 * for something their lead time could not deliver) but still counts in filled — ordering late
 * excuses lateness, not a short delivery.</li>
 * <li>An order the vendor made right is left out of both, because what an apology waives on a short
 * delivery is mostly the shortfall itself. Nobody can change a percentage by hand; an admin closing
 * an order chooses what the shortfall meant, and the figures follow.</li>
 * <li>An order with no needed-by date has nothing to be late against.</li>
 * </ul>
 *
 * <p>The "Not counted" line names each part only when its count is above zero, so a clean period
 * shows no such line at all. The wording is Rajeev's (2026-09-18, T-236), replacing "Left out: 1
 * order sent inside the vendor's notice period · 1 with no needed-by date", which he found
 * unreadable: every part now says "order" or "orders" in full, and they are joined as a sentence.
 * The made-right part was not in his example (his data had none); its wording follows the same
 * pattern.
 */
function caveat(report: VendorPerformance) {
  const orders = (n: number) => `${n.toLocaleString("en-IN")} ${n === 1 ? "order" : "orders"}`;
  const notCounted: string[] = [];
  if (report.ordersSentLate > 0) {
    notCounted.push(`${orders(report.ordersSentLate)} sent too late for the vendor to meet the date`);
  }
  if (report.ordersExcused > 0) {
    notCounted.push(`${orders(report.ordersExcused)} the vendor made right`);
  }
  if (report.ordersWithoutNeededBy > 0) {
    notCounted.push(`${orders(report.ordersWithoutNeededBy)} with no needed-by date`);
  }
  return (
    <ul className="grid list-disc gap-1 pl-5">
      <li>
        <span className="font-medium">On time:</span> items that arrived by the needed-by date. 8 of
        10 = 80%.
      </li>
      <li>
        <span className="font-medium">Filled:</span> how much of the order arrived in the end,
        whenever it came.
      </li>
      <li>Only orders that were sent and are now past their needed-by date are counted.</li>
      {notCounted.length > 0 && <li>Not counted: {joinAsSentence(notCounted)}.</li>}
    </ul>
  );
}

/**
 * "A", "A, and B", "A, B, and C" — the comma before "and" even with two parts, as in Rajeev's own
 * wording of the line ("…to meet the date, and 1 order with no needed-by date").
 */
function joinAsSentence(parts: string[]): string {
  if (parts.length <= 1) return parts.join("");
  return `${parts.slice(0, -1).join(", ")}, and ${parts[parts.length - 1]}`;
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
 * "1 sent late (not counted)".
 *
 * <p>This count is the one figure on this screen that is about the temple rather than the vendor.
 * It used to say "1 order we sent late — not counted"; the content audit (T-223) cut it to fit a
 * badge, and "sent" still says who did it — the temple sends, the vendor delivers.
 */
function sentLateNote(count: number): string {
  return count === 1
    ? "1 sent late (not counted)"
    : `${count.toLocaleString("en-IN")} sent late (not counted)`;
}

/**
 * "1 made right (not counted)".
 *
 * <p>Deliberately not "excused" or "waived", which are the words the data uses and read as
 * paperwork. What happened is that a supplier rang up, apologised and put it right, and the temple
 * accepted — so the sentence says what the vendor did, in the way somebody would repeat it.
 */
function excusedNote(count: number): string {
  return count === 1
    ? "1 made right (not counted)"
    : `${count.toLocaleString("en-IN")} made right (not counted)`;
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
 * How late the open orders are. This used to borrow the payables screen's aging words ("2 1–30
 * days overdue"), but in a badge the count and the range ran together into one number. The content
 * audit (T-223) changed it to words; the payables screen still says it its own way.
 */
function overdueNote(vendor: VendorPerformanceRow): string {
  if (vendor.openOverdue31Plus > 0 && vendor.openDue1To30 > 0) {
    return `${vendor.openDue1To30} up to 30 days late · ${vendor.openOverdue31Plus} over 30 days late`;
  }
  if (vendor.openOverdue31Plus > 0) {
    return `${vendor.openOverdue31Plus} over 30 days late`;
  }
  return `${vendor.openDue1To30} up to 30 days late`;
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
