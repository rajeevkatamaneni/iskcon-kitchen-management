import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { ApiError, api } from "@/lib/api";
import type {
  GoodsReceiptView, GoodsReturnView, IngredientView, PurchaseOrderDetailView,
} from "@/lib/api";

/**
 * Returning goods to a vendor after they were accepted, from the order screen (T-013).
 *
 * <p>Rejection only ever worked at the gate: the rejected quantity is a field of the receiving
 * submission itself, so weevils found the next morning had nowhere to go. What is checked here is
 * the screen half of the fix — that a recorded delivery can be read back at all, that the offer to
 * return is present exactly where there is something left to send back, and that what the form
 * sends is what the person typed.
 *
 * <p>The mock returns the page's three `useAuthedQuery` calls by index — PO detail, receipts, the
 * ingredient catalogue — exactly as `order-detail.test.tsx` does, and for the same reason: opening a
 * form re-renders the page and the order has to keep mapping.
 */
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
    status: "PARTIALLY_RECEIVED",
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
    { id: "l1", ingredientId: "ing1", ingredientName: "Rice", description: null, quantity: 36, unit: "KG", expectedPrice: 45, arrivedOn: null },
  ],
  events: [],
};

/**
 * One delivery: thirty kilos of rice in, two refused at the gate, nothing yet returned.
 *
 * <p>`returnedQty` is stated rather than left off. It is required-and-not-optional on the client
 * type on purpose — a screen that shows what has gone back must not be able to render an absent
 * field as a silent blank — so a fixture that omits it does not compile.
 */
function receipt(returnedQty: number): GoodsReceiptView[] {
  return [{
    id: "gr1",
    purchaseOrderId: "po1",
    deliveryNoteRef: null,
    note: null,
    receivedByName: "Staff A",
    receivedAt: "2026-08-18T06:30:00Z",
    lines: [{
      id: "grl1",
      poLineId: "l1",
      ingredientId: "ing1",
      ingredientName: "Rice",
      receivedQty: 30,
      rejectedQty: 2,
      rejectReason: "SPOILED",
      unit: "KG",
      batchId: "b1",
      expiryDate: null,
      receivedDate: "2026-08-18",
      unitPrice: 45,
      returnedQty,
    }],
  }];
}

const INGREDIENTS: IngredientView[] = [
  { id: "ing1", name: "Rice", category: "Grains", unit: "KG", ekadashiProhibited: false, supply: false, aliases: [], createdAt: "2026-01-01T00:00:00Z" },
];

function withReceipts(receipts: GoodsReceiptView[]) {
  returnsRef.current = [
    { data: DETAIL, error: null, loading: false },
    { data: receipts, error: null, loading: false },
    { data: INGREDIENTS, error: null, loading: false },
  ];
  returnsRef.i = 0;
}

const RECORDED: GoodsReturnView = {
  id: "ret1",
  receiptId: "gr1",
  receiptLineId: "grl1",
  ingredientId: "ing1",
  ingredientName: "Rice",
  quantity: 5,
  unit: "KG",
  reason: "SPOILED",
  note: null,
  returnedByName: "Staff A",
  returnedAt: "2026-08-19T04:00:00Z",
  stockMovementId: "m1",
};

describe("returning received goods to the vendor", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    withReceipts(receipt(0));
    reloadMock.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows each delivery that was recorded, with what was received and what has gone back", () => {
    withReceipts(receipt(5));
    render(<PurchaseOrderDetailPage />);

    // Until T-013 this screen fetched the receipts only to total "received so far" inside the
    // receiving form: a delivery could be recorded and then never read again anywhere.
    expect(screen.getByRole("heading", { name: /deliveries received/i })).toBeInTheDocument();
    expect(screen.getByText(/Staff A/)).toBeInTheDocument();
    expect(screen.getByText("30 Kg")).toBeInTheDocument();
    // Rejected at the gate and returned afterwards are two different facts about two different
    // quantities, and the screen says which is which.
    expect(screen.getByText("2 Kg · spoiled")).toBeInTheDocument();
    expect(screen.getByText("5 Kg")).toBeInTheDocument();
  });

  it("offers the return only while something is left to send back", () => {
    const first = render(<PurchaseOrderDetailPage />);
    expect(screen.getByRole("button", { name: /return to vendor/i })).toBeInTheDocument();
    // Unmounted rather than rendered over: two renders share one document, and the second screen's
    // absence would be hidden by the first screen's button still standing in it.
    first.unmount();

    // All thirty already back. The server would refuse a further return with KMS-400140, and an
    // offer that is refused when pressed is worse than no offer at all (A9).
    withReceipts(receipt(30));
    render(<PurchaseOrderDetailPage />);
    expect(screen.queryByRole("button", { name: /return to vendor/i })).not.toBeInTheDocument();
  });

  it("sends the quantity, the reason and the note, against the line that was chosen", async () => {
    const send = vi.spyOn(api, "returnReceivedGoods").mockResolvedValue(RECORDED);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /return to vendor/i }));

    fireEvent.change(screen.getByLabelText(/quantity of Rice to return/i), { target: { value: "5" } });
    fireEvent.change(screen.getByLabelText(/reason for the return/i), { target: { value: "SPOILED" } });
    fireEvent.change(screen.getByLabelText(/note about the return/i), { target: { value: "Weevils in two sacks" } });
    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /return goods to the vendor/i }));
    });

    expect(send).toHaveBeenCalledTimes(1);
    // The receipt is in the path and the line in the body, which is how the server checks that the
    // two belong together rather than assuming it.
    expect(send.mock.calls[0][0]).toBe("gr1");
    const input = send.mock.calls[0][1];
    expect(input.receiptLineId).toBe("grl1");
    expect(input.quantity).toBe(5);
    expect(input.reason).toBe("SPOILED");
    expect(input.note).toBe("Weevils in two sacks");
    // The key is what makes a double-press a no-op rather than a second withdrawal from a ledger
    // that cannot be edited afterwards. Its presence is the thing being asserted, not its value.
    expect(Object.keys(input)).toContain("idempotencyKey");
    expect(input.idempotencyKey).toBeTruthy();
  });

  it("says how much of the delivery can still go back, net of what already has", () => {
    withReceipts(receipt(12));
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /return to vendor/i }));

    // 30 received less 12 already returned. "You can't return more than was received" is not
    // actionable on a line that has been returned against before — the number wanted is what is
    // left, and the server caps on the same sum (KMS-400140).
    //
    // Visible on the screen, deliberately, and not inside the field's "i": a hint holds guidance
    // somebody may want, and this is the figure the form is about to be judged against.
    expect(screen.getByText(/18 Kg of this delivery can still go back/)).toBeInTheDocument();
  });

  it("sends an empty note as null, and never troubles the server with a blank quantity", async () => {
    const send = vi.spyOn(api, "returnReceivedGoods").mockResolvedValue(RECORDED);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /return to vendor/i }));

    // Nothing typed at all. A blank box coerces to 0, and a zero-quantity return is not a return.
    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /return goods to the vendor/i }));
    });
    expect(send).not.toHaveBeenCalled();
    expect(screen.getByText(/how much went back/i)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/quantity of Rice to return/i), { target: { value: "5" } });
    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /return goods to the vendor/i }));
    });
    // A note nobody wrote is an absent note. `null` rather than "" so the column stores nothing.
    expect(send.mock.calls[0][1].note).toBeNull();
  });

  it("shows the server's refusal in its own words, and leaves the form open", async () => {
    // A real ApiError, not a plain object: toApiError keeps the server's own words only for one
    // of these, and a bare object would come back as the caller's fallback message — which would
    // make this test pass against a screen that never showed the refusal at all.
    vi.spyOn(api, "returnReceivedGoods").mockRejectedValue(new ApiError({
      code: "KMS-400140",
      message: "You can’t return more than was received.",
      action: "Check the quantity against the goods receipt.",
      fieldErrors: [],
    }, 400));
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /return to vendor/i }));

    fireEvent.change(screen.getByLabelText(/quantity of Rice to return/i), { target: { value: "40" } });
    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /return goods to the vendor/i }));
    });

    expect(screen.getByText(/return more than was received/i)).toBeInTheDocument();
    // Still open, with what was typed still in it: the person has to change one number, and a form
    // that closes on a refusal makes them start again.
    expect(screen.getByRole("form", { name: /return goods to the vendor/i })).toBeInTheDocument();
  });
});
