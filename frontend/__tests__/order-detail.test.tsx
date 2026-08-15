import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type {
  DocumentView, GoodsReceiptView, PurchaseOrderDetailView,
} from "@/lib/api";

// The detail page issues three useAuthedQuery calls in a fixed order: PO detail, receipts,
// documents. The mock returns them by call index modulo length, so it maps correctly on any
// number of renders.
const { authRef, returnsRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  returnsRef: { current: [] as Array<{ data: unknown; error: null; loading: boolean }>, i: 0 },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "po1" }),
}));
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

import PurchaseOrderDetailPage from "@/app/orders/[id]/page";

const DETAIL: PurchaseOrderDetailView = {
  order: {
    id: "po1",
    poNumber: "PO-2026-0042",
    vendorId: "v1",
    vendorName: "Govind Wholesale",
    status: "SENT",
    orderDate: "2026-08-01",
    neededBy: "2026-08-20",
    deliveryLocation: "Main store",
    notes: null,
    cancelReason: null,
    sentAt: "2026-08-01T10:00:00Z",
    cancelledAt: null,
    createdAt: "2026-08-01T09:00:00Z",
  },
  lines: [
    { id: "l1", ingredientId: "ing1", ingredientName: "Rice", quantity: 30, unit: "KG", expectedPrice: 45 },
  ],
  events: [
    { eventType: "SENT", detail: "PO-2026-0042 sent to vendor", actorName: "Staff A", createdAt: "2026-08-01T10:00:00Z" },
  ],
};

const RECEIPTS: GoodsReceiptView[] = [];
const DOCS: DocumentView[] = [];

describe("purchase order detail", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    returnsRef.current = [
      { data: DETAIL, error: null, loading: false },
      { data: RECEIPTS, error: null, loading: false },
      { data: DOCS, error: null, loading: false },
    ];
    returnsRef.i = 0;
    reloadMock.mockReset();
  });

  it("renders the PO with its lines and SENT-state actions, and nothing else", () => {
    render(<PurchaseOrderDetailPage />);
    expect(screen.getByRole("heading", { name: "PO-2026-0042" })).toBeInTheDocument();
    expect(screen.getByText("Rice")).toBeInTheDocument();
    // A sent PO can be received, sent on WhatsApp, and cancelled — but not "marked sent" again.
    expect(screen.getByRole("button", { name: /send on whatsapp/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /receive delivery/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^cancel$/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /mark sent/i })).not.toBeInTheDocument();
    // The screen is the order itself: no event trail, no list of generated sheets to come back to.
    expect(screen.queryByRole("heading", { name: /activity/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /documents/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/sent to vendor/i)).not.toBeInTheDocument();
  });

  it("offers Mark sent on a draft and no receiving", () => {
    returnsRef.current = [
      { data: { ...DETAIL, order: { ...DETAIL.order, status: "DRAFT" } }, error: null, loading: false },
      { data: RECEIPTS, error: null, loading: false },
      { data: DOCS, error: null, loading: false },
    ];
    returnsRef.i = 0;
    render(<PurchaseOrderDetailPage />);
    expect(screen.getByRole("button", { name: /mark sent/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /receive delivery/i })).not.toBeInTheDocument();
  });
});
