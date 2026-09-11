import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type { ApiError, VendorPerformance, VendorPerformanceRow } from "@/lib/api";

/**
 * The vendor performance report (E5-S9).
 *
 * <p>What these guard is the honesty of the screen rather than its arithmetic, which the backend's
 * `VendorPerformanceIT` owns: that no percentage is ever shown without the counts behind it, that a
 * supplier with too few orders is marked rather than quietly ranked, that a dropped vendor keeps
 * their history and is labelled, and that the screen says what "on time" actually measured before
 * anybody reads a number and assumes it measured something else.
 */

const { authRef, queryRef } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Radha Devi", tenantName: "ISKCON Bengaluru" },
    } as { status: string; appUser: { role: string; fullName?: string; tenantName?: string } | null },
  },
  queryRef: {
    current: { data: null as VendorPerformance | null, error: null as ApiError | null, loading: false },
  },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token", signOut: vi.fn() }),
}));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => queryRef.current }));

import VendorPerformancePage from "@/app/vendor-performance/page";

function vendor(overrides: Partial<VendorPerformanceRow> = {}): VendorPerformanceRow {
  return {
    vendorId: "v1",
    vendorName: "Govind Wholesale",
    active: true,
    ordersPlaced: 11,
    ordersJudged: 11,
    onTimeOrders: 9,
    abandonedOrders: 0,
    ordersWithoutNeededBy: 0,
    ordersSentLate: 0,
    // T-142, D-26: nothing excused by default, so the pill and the caveat sentence are absent
    // unless a test asks for them.
    ordersExcused: 0,
    itemsScored: 24,
    itemsOnTime: 20,
    onTimePercent: 82,
    linesJudged: 24,
    fillRatePercent: 96,
    rejectedLines: 0,
    rejections: [],
    openOrders: 0,
    openCurrent: 0,
    openDue1To30: 0,
    openOverdue31Plus: 0,
    enoughToRank: true,
    ...overrides,
  };
}

function report(overrides: Partial<VendorPerformance> = {}): VendorPerformance {
  return {
    from: "2026-08-01",
    to: "2026-08-31",
    ordersPlaced: 11,
    ordersJudged: 11,
    onTimeOrders: 9,
    abandonedOrders: 0,
    ordersWithoutNeededBy: 0,
    ordersSentLate: 0,
    // T-142, D-26: nothing excused by default, so the pill and the caveat sentence are absent
    // unless a test asks for them.
    ordersExcused: 0,
    itemsScored: 24,
    itemsOnTime: 20,
    onTimePercent: 82,
    linesJudged: 24,
    fillRatePercent: 96,
    rejectedLines: 0,
    openOrders: 0,
    openCurrent: 0,
    openDue1To30: 0,
    openOverdue31Plus: 0,
    vendors: [vendor()],
    ...overrides,
  };
}

function rowFor(name: string) {
  return screen.getByRole("row", { name: new RegExp(name) });
}

describe("Vendor performance", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Radha Devi", tenantName: "ISKCON Bengaluru" },
    };
    queryRef.current = { data: null, error: null, loading: false };
  });

  it("never shows an on-time percentage without the counts behind it", () => {
    queryRef.current.data = report();
    render(<VendorPerformancePage />);

    const row = rowFor("Govind Wholesale");
    expect(within(row).getByText("82%")).toBeInTheDocument();
    // Items and then orders (T-124). The percentage is made at item grain now, so a note counting
    // orders would send a reader looking for a fraction that is not on the screen.
    expect(within(row).getByText("20 of 24 items across 11 orders")).toBeInTheDocument();
  });

  it("shows on time and fill rate as two different figures about the same supplier", () => {
    // Before T-124 this fixture was 100% on time beside 25% fill, and the test was named for that
    // pairing. A quarter of the order on the day is now a quarter on time, so the two figures no
    // longer have to disagree to be useful — but they are still separate populations, and the fill
    // rate's own denominator is still printed under it.
    queryRef.current.data = report({
      vendors: [vendor({ vendorName: "Half Load Traders", onTimePercent: 25, fillRatePercent: 25 })],
    });
    render(<VendorPerformancePage />);

    const row = rowFor("Half Load Traders");
    expect(within(row).getAllByText("25%")).toHaveLength(2);
    expect(within(row).getByText("across 24 lines")).toBeInTheDocument();
  });

  it("names the supplier who never turned up, separately from the one who turned up late", () => {
    // Rajeev's fifth delivery scenario. A zero from a vendor who abandoned two orders is a
    // different fact from a zero from one who came a fortnight late, and folding them into one
    // percentage would lose it — so the count gets its own line and its own words.
    queryRef.current.data = report({
      abandonedOrders: 2,
      vendors: [
        vendor({ vendorName: "Silent Supplies", onTimePercent: 0, abandonedOrders: 2 }),
      ],
    });
    render(<VendorPerformancePage />);

    const row = rowFor("Silent Supplies");
    expect(within(row).getByText("2 orders never delivered")).toBeInTheDocument();
  });

  it("says nothing about a no-show when there has not been one", () => {
    // The absence is asserted deliberately: a pill that appears on every vendor would say a supplier
    // abandoned an order when nobody has ticked anything at all.
    queryRef.current.data = report();
    render(<VendorPerformancePage />);

    // Scoped to the vendor's own row: the standing caveat above the table explains what a
    // never-delivered cancellation does, and it says so whether or not there has been one.
    expect(
      within(rowFor("Govind Wholesale")).queryByText(/never delivered/)
    ).not.toBeInTheDocument();
  });

  it("keeps the order the server sent, worst on time first", () => {
    queryRef.current.data = report({
      vendors: [
        vendor({ vendorId: "v1", vendorName: "Late Traders", onTimePercent: 40 }),
        vendor({ vendorId: "v2", vendorName: "Govind Wholesale", onTimePercent: 82 }),
        vendor({ vendorId: "v3", vendorName: "Amba Traders", onTimePercent: 98 }),
      ],
    });
    render(<VendorPerformancePage />);

    const names = screen
      .getAllByRole("rowheader")
      .map((cell) => cell.textContent?.split("\n")[0]?.trim());
    expect(names).toEqual(["Late Traders", "Govind Wholesale", "Amba Traders", "All vendors"]);
  });

  it("marks a supplier with too few orders rather than hiding the figure", () => {
    queryRef.current.data = report({
      vendors: [
        vendor({
          vendorName: "Amba Traders",
          ordersPlaced: 2,
          ordersJudged: 2,
          onTimeOrders: 1,
          onTimePercent: 50,
          itemsScored: 4,
          itemsOnTime: 2,
          enoughToRank: false,
        }),
      ],
    });
    render(<VendorPerformancePage />);

    const row = rowFor("Amba Traders");
    expect(within(row).getByText("Too few orders to rank")).toBeInTheDocument();
    expect(within(row).getByText("50%")).toBeInTheDocument();
    expect(within(row).getByText("2 of 4 items across 2 orders")).toBeInTheDocument();
  });

  it("keeps a dropped vendor on the report, marked — it is what you read before taking them back", () => {
    queryRef.current.data = report({
      vendors: [vendor({ vendorName: "Dropped Traders", active: false })],
    });
    render(<VendorPerformancePage />);

    expect(within(rowFor("Dropped Traders")).getByText("No longer used")).toBeInTheDocument();
  });

  it("shows a dash rather than a zero where nothing has been judged yet", () => {
    queryRef.current.data = report({
      ordersJudged: 0,
      onTimeOrders: 0,
      onTimePercent: null,
      itemsScored: 0,
      itemsOnTime: 0,
      linesJudged: 0,
      fillRatePercent: null,
      openOrders: 1,
      openCurrent: 1,
      vendors: [
        vendor({
          ordersPlaced: 1,
          ordersJudged: 0,
          onTimeOrders: 0,
          onTimePercent: null,
          itemsScored: 0,
          itemsOnTime: 0,
          linesJudged: 0,
          fillRatePercent: null,
          openOrders: 1,
          openCurrent: 1,
        }),
      ],
    });
    render(<VendorPerformancePage />);

    const row = rowFor("Govind Wholesale");
    expect(within(row).getAllByText("—").length).toBeGreaterThan(0);
    expect(within(row).getByText("1 order, not yet due")).toBeInTheDocument();
  });

  it("counts the orders nobody put a needed-by date on, instead of scoring them on time", () => {
    queryRef.current.data = report({
      ordersWithoutNeededBy: 2,
      vendors: [vendor({ ordersWithoutNeededBy: 2 })],
    });
    render(<VendorPerformancePage />);

    expect(
      within(rowFor("Govind Wholesale")).getByText("20 of 24 items across 11 orders · 2 with no date")
    ).toBeInTheDocument();
    expect(
      screen.getByText(/2 orders have no needed-by date, so there is nothing to be late against/)
    ).toBeInTheDocument();
  });

  it("names the reasons goods were refused, commonest first", () => {
    queryRef.current.data = report({
      rejectedLines: 3,
      vendors: [
        vendor({
          rejectedLines: 3,
          rejections: [
            { reason: "SPOILED", lines: 2 },
            { reason: "DAMAGED", lines: 1 },
          ],
        }),
      ],
    });
    render(<VendorPerformancePage />);

    expect(within(rowFor("Govind Wholesale")).getByText("Spoiled 2 · Damaged 1")).toBeInTheDocument();
  });

  it("flags an open order that is past the day it was wanted, in the payables screen's words", () => {
    queryRef.current.data = report({
      openOrders: 3,
      openCurrent: 1,
      openDue1To30: 1,
      openOverdue31Plus: 1,
      vendors: [vendor({ openOrders: 3, openCurrent: 1, openDue1To30: 1, openOverdue31Plus: 1 })],
    });
    render(<VendorPerformancePage />);

    expect(
      within(rowFor("Govind Wholesale")).getByText("1 1–30 days overdue · 1 31+ days overdue")
    ).toBeInTheDocument();
  });

  it("says what on time actually measured before anybody reads a number", () => {
    queryRef.current.data = report();
    render(<VendorPerformancePage />);

    expect(screen.getByText(/On time is scored item by item/)).toBeInTheDocument();
    expect(screen.getByText(/eight of ten items in time is 80%/)).toBeInTheDocument();
    expect(screen.getByText(/An order split across two days is still fully on time/))
      .toBeInTheDocument();
    // And what a cancellation does and does not say, which is the new thing on this screen.
    expect(
      screen.getByText(/a cancellation nobody has marked against the vendor/)
    ).toBeInTheDocument();
  });

  it("says there were no orders rather than showing a table of dashes", () => {
    queryRef.current.data = report({ vendors: [] });
    render(<VendorPerformancePage />);

    expect(screen.getByText("No orders with any supplier in this period")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("is not offered to a volunteer", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER" } };
    queryRef.current.data = report();
    render(<VendorPerformancePage />);

    expect(screen.getByText("Not your page")).toBeInTheDocument();
  });

  /**
   * N2 — this report shares the planner's stepper, and must not share its Today.
   *
   * <p>`PeriodNav` has five consumers, and only two of them are screens somebody opens every
   * morning wanting to get home. A way back to today was added to that shared component for the
   * planner and the calendar, and it is opt-in for exactly this reason: a report is read a period
   * at a time, nobody asked for a Today on it, and "it appeared when they fixed the planner" is not
   * a reason for a control to exist. The count is asserted, not the absence, and the stepper is
   * asserted present alongside it — a zero that came from the page failing to render would prove
   * nothing at all.
   */
  it("takes the shared stepper without taking its way back to today", () => {
    queryRef.current.data = report();
    render(<VendorPerformancePage />);

    expect(screen.getByRole("tablist", { name: /period/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /next month/i })).toBeInTheDocument();
    expect(screen.queryAllByRole("button", { name: /^today$/i })).toHaveLength(0);
  });
});
