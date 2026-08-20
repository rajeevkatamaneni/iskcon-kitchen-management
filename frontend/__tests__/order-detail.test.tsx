import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type {
  GoodsReceiptView, IngredientView, PurchaseOrderDetailView,
} from "@/lib/api";

// The detail page issues three useAuthedQuery calls in a fixed order: PO detail, receipts, and the
// ingredient catalogue the draft-edit picker chooses from. The mock returns them by call index
// modulo length, so it maps correctly on any number of renders — which matters here, because
// opening the edit form re-renders the page.
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
const INGREDIENTS: IngredientView[] = [
  { id: "ing1", name: "Rice", category: "Grains", unit: "KG", sattvicProhibited: false, aliases: [], createdAt: "2026-01-01T00:00:00Z" },
  { id: "ing2", name: "Toor Dal", category: "Pulses", unit: "KG", sattvicProhibited: false, aliases: [], createdAt: "2026-01-01T00:00:00Z" },
];

function withDetail(detail: PurchaseOrderDetailView) {
  returnsRef.current = [
    { data: detail, error: null, loading: false },
    { data: RECEIPTS, error: null, loading: false },
    { data: INGREDIENTS, error: null, loading: false },
  ];
  returnsRef.i = 0;
}

const DRAFT: PurchaseOrderDetailView = { ...DETAIL, order: { ...DETAIL.order, status: "DRAFT" } };

describe("purchase order detail", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    withDetail(DETAIL);
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
    // And it cannot be edited: the offer is absent, not merely refused when pressed (A9).
    expect(screen.queryByRole("button", { name: /edit lines/i })).not.toBeInTheDocument();
    // The screen is the order itself: no event trail, no list of generated sheets to come back to.
    expect(screen.queryByRole("heading", { name: /activity/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /documents/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/sent to vendor/i)).not.toBeInTheDocument();
  });

  it("offers Mark sent and Edit lines on a draft, and no receiving", () => {
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);
    expect(screen.getByRole("button", { name: /mark sent/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /edit lines/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /receive delivery/i })).not.toBeInTheDocument();
  });

  it("edits a draft's quantities and lines, but never its vendor", () => {
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /edit lines/i }));

    // The quantity is editable, and the picker offers the ingredients not already on the order.
    const quantity = screen.getByLabelText("Quantity of Rice") as HTMLInputElement;
    expect(quantity.value).toBe("30");
    fireEvent.change(quantity, { target: { value: "45" } });
    expect((screen.getByLabelText("Quantity of Rice") as HTMLInputElement).value).toBe("45");

    const picker = screen.getByLabelText(/add an ingredient/i) as HTMLSelectElement;
    expect(screen.getByRole("option", { name: "Toor Dal" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Rice" })).not.toBeInTheDocument();
    fireEvent.change(picker, { target: { value: "ing2" } });
    fireEvent.click(screen.getByRole("button", { name: /add line/i }));
    expect(screen.getByLabelText("Quantity of Toor Dal")).toBeInTheDocument();

    // The vendor is not among what can be changed — the form offers no way to choose another.
    expect(screen.queryByLabelText(/vendor/i)).not.toBeInTheDocument();
  });

  it("keeps the last line, because an order with nothing on it is a cancellation", () => {
    withDetail(DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /edit lines/i }));
    expect(screen.getByRole("button", { name: /^remove$/i })).toBeDisabled();
  });
});
