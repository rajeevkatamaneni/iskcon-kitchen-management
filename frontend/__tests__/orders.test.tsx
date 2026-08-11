import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ApiError, PurchaseOrderView } from "@/lib/api";

const { authRef, queryRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: { current: { data: [] as PurchaseOrderView[] | null, error: null as ApiError | null, loading: false } },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
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
    sentAt: "2026-08-01T10:00:00Z",
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
  });

  it("lists purchase orders with a status filter", () => {
    render(<PurchaseOrdersPage />);
    expect(screen.getByRole("heading", { name: /purchase orders/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "PO-2026-0042" })).toBeInTheDocument();
    // "Sent" appears both as a status chip and as a filter option.
    expect(screen.getAllByText("Sent").length).toBeGreaterThan(0);
    expect(screen.getByRole("combobox")).toBeInTheDocument();
  });

  it("shows an empty state with no orders", () => {
    queryRef.current = { data: [], error: null, loading: false };
    render(<PurchaseOrdersPage />);
    expect(screen.getByText(/no purchase orders/i)).toBeInTheDocument();
  });
});
