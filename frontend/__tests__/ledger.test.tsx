import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { LedgerRow, LedgerSummary } from "@/lib/api";

const { authRef, returnsRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  returnsRef: { current: [] as Array<{ data: unknown; error: null; loading: boolean }>, i: 0 },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => {
    const list = returnsRef.current;
    const value = list[returnsRef.i % list.length];
    returnsRef.i += 1;
    return { ...value, reload: reloadMock };
  },
}));

import LedgerPage from "@/app/ledger/page";

const ROW: LedgerRow = {
  id: "d1", donatedOn: "2026-08-01", category: "WISHLIST", donorDisplay: "Anonymous",
  amountInr: 501, currency: "INR", paymentMode: "UPI", providerRef: "pay_1", status: "COMPLETED",
  linkedTo: "Wish list: Rice sacks",
};

const SUMMARY: LedgerSummary = {
  financialYearStart: "2026-04-01",
  monthToDateByCategory: { ONE_TIME: 500 },
  financialYearToDateByCategory: { ONE_TIME: 1200, WISHLIST: 501 },
};

describe("donations ledger", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    returnsRef.current = [
      { data: [ROW], error: null, loading: false },
      { data: SUMMARY, error: null, loading: false },
    ];
    returnsRef.i = 0;
    reloadMock.mockReset();
  });

  it("shows the ledger rows, summary cards, and a CSV export link", () => {
    render(<LedgerPage />);
    expect(screen.getByRole("heading", { name: /donations ledger/i })).toBeInTheDocument();
    expect(screen.getByText("Anonymous")).toBeInTheDocument();
    expect(screen.getByText("Wish list: Rice sacks")).toBeInTheDocument();
    expect(screen.getByText("₹1200")).toBeInTheDocument(); // FY one-time card
    expect(screen.getByRole("link", { name: /export csv/i })).toBeInTheDocument();
  });

  it("refuses a non-admin", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<LedgerPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
