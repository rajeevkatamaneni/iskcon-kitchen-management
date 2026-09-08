import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ApiError, PurchaseOrderView } from "@/lib/api";

const { authRef, queryRef, reloadMock, paramsRef } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: { current: { data: [] as PurchaseOrderView[] | null, error: null as ApiError | null, loading: false } },
  reloadMock: vi.fn(),
  // The screen reads its own address bar since T-026 — an order raised on /orders/new comes back
  // here with its confirmation in the URL, the way /vendors has taken one since /vendors/new was
  // built. A ref rather than a fixed empty value, following vendors.test.tsx, so a test that wants
  // to drive that parameter can set it without touching the mock.
  paramsRef: { current: new URLSearchParams() },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useSearchParams: () => paramsRef.current,
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: reloadMock }),
}));

import PurchaseOrdersPage from "@/app/orders/page";

function po(o: Partial<PurchaseOrderView>): PurchaseOrderView {
  return {
    id: "po1",
    poNumber: "PO-2026-0042",
    vendorId: "v1",
    vendorName: "Govind Wholesale",
    status: "SENT",
    orderDate: "2026-08-01",
    neededBy: "2026-08-20",
    deliveryLocation: null,
    notes: null,
    cancelReason: null,
    // 20:30 UTC on the 1st is 02:00 on the 2nd in Bengaluru — chosen so the UTC day and the
    // temple's day differ, which is the only way this fixture can prove the zone is honoured.
    sentAt: "2026-08-01T20:30:00Z",
    cancelledAt: null,
    createdAt: "2026-08-01T09:00:00Z",
    ...o,
  };
}

describe("purchase orders", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    queryRef.current = { data: [po({})], error: null, loading: false };
    reloadMock.mockReset();
    paramsRef.current = new URLSearchParams();
  });

  it("lists purchase orders with a status filter", () => {
    render(<PurchaseOrdersPage />);
    expect(screen.getByRole("heading", { name: /purchase orders/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "PO-2026-0042" })).toBeInTheDocument();
    // "Sent" appears both as a status chip and as a filter option.
    expect(screen.getAllByText("Sent").length).toBeGreaterThan(0);
    expect(screen.getByRole("combobox")).toBeInTheDocument();
  });

  it("says when the order was generated, beside its status", () => {
    render(<PurchaseOrdersPage />);

    // order_date is stamped when the PO is created, while it is still a DRAFT — so "Ordered", which
    // is what this column used to be called, was the one thing it did not mean. Rajeev, 2026-09-05:
    // the generated date was on no screen at all.
    // Three dates, in the order they happen. Generated → Sent is whether we were late; Sent →
    // Needed by is whether the vendor was. One column could never have answered both, and until
    // 2026-09-05 there was one column, called "Ordered", holding the date of neither.
    const headers = screen.getAllByRole("columnheader").map((h) => h.textContent);
    expect(headers).toEqual(["PO", "Vendor", "Status", "Generated", "Sent", "Needed by"]);
    expect(headers).not.toContain("Ordered");
    expect(screen.getAllByText("1 Aug 2026").length).toBeGreaterThan(0);

    // sentAt is an Instant, and dateWithYear appends "T00:00:00" — on a timestamp that yields
    // "Invalid Date", which is what this column showed until Rajeev reported it on 2026-09-05. It
    // also has to be read in the temple's zone: 20:30 UTC is 02:00 on the 2nd in Bengaluru, so a
    // naive render would date the send to the day before, in the very column that exists to say
    // which day it went out.
    expect(screen.getByText("2 Aug 2026")).toBeInTheDocument();
    expect(screen.queryByText(/invalid date/i)).not.toBeInTheDocument();
  });

  it("shows an empty state with no orders", () => {
    queryRef.current = { data: [], error: null, loading: false };
    render(<PurchaseOrdersPage />);
    expect(screen.getByText(/no purchase orders/i)).toBeInTheDocument();
  });
});
