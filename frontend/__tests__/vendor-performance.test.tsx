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
    ordersWithoutNeededBy: 0,
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
    ordersWithoutNeededBy: 0,
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
    expect(within(row).getByText("9 of 11")).toBeInTheDocument();
  });

  it("says the fill rate is what tells you a punctual lorry came half empty", () => {
    queryRef.current.data = report({
      vendors: [vendor({ vendorName: "Half Load Traders", onTimePercent: 100, fillRatePercent: 25 })],
    });
    render(<VendorPerformancePage />);

    const row = rowFor("Half Load Traders");
    expect(within(row).getByText("100%")).toBeInTheDocument();
    expect(within(row).getByText("25%")).toBeInTheDocument();
    expect(within(row).getByText("across 24 lines")).toBeInTheDocument();
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
          enoughToRank: false,
        }),
      ],
    });
    render(<VendorPerformancePage />);

    const row = rowFor("Amba Traders");
    expect(within(row).getByText("Too few orders to rank")).toBeInTheDocument();
    expect(within(row).getByText("50%")).toBeInTheDocument();
    expect(within(row).getByText("1 of 2")).toBeInTheDocument();
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

    expect(within(rowFor("Govind Wholesale")).getByText("9 of 11 · 2 with no date")).toBeInTheDocument();
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

    expect(screen.getByText(/this counts whole orders/)).toBeInTheDocument();
    expect(screen.getByText(/measured at the first delivery, so it says the lorry turned up/))
      .toBeInTheDocument();
    expect(screen.getByText(/Drafts and cancelled orders are left out/)).toBeInTheDocument();
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
});
