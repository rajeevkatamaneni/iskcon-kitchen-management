import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { api } from "@/lib/api";
import type {
  GoodsReceiptView, IngredientView, PurchaseOrderDetailView,
} from "@/lib/api";

/**
 * A purchase-order line that names something the catalogue has never heard of (T-024, D-1).
 *
 * <p>Four plastic stools from a furniture shop. The order carries them, the vendor sheet prints
 * them, the bill pays for them — and the store room never sees them, because it counts ingredients
 * and a stool is not one.
 *
 * <p>This file is about the screen. The server half is DescribedPurchaseLineIT.
 */

// Same three-query shape as order-detail.test.tsx: PO detail, receipts, then the ingredient
// catalogue the draft-edit picker reads. Returned by call index modulo length so it survives any
// number of re-renders.
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

/**
 * One ingredient line and TWO described lines.
 *
 * <p>Two rather than one, deliberately. One described line would pass a table keyed on
 * `ingredientId`; two is what makes the old key collide, because both carry null and React would
 * treat them as the same row.
 */
const MIXED: PurchaseOrderDetailView = {
  order: {
    id: "po1",
    poNumber: "PO-2026-0077",
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
    { id: "l1", ingredientId: "ing1", ingredientName: "Rice", description: null, quantity: 30, unit: "KG", expectedPrice: 45 },
    { id: "l2", ingredientId: null, ingredientName: null, description: "Plastic stool", quantity: 4, unit: "PIECES", expectedPrice: 250 },
    { id: "l3", ingredientId: null, ingredientName: null, description: "Extension cord", quantity: 2, unit: "PIECES", expectedPrice: 180 },
  ],
  events: [],
};

const RECEIPTS: GoodsReceiptView[] = [];
const INGREDIENTS: IngredientView[] = [
  { id: "ing1", name: "Rice", category: "Grains", unit: "KG", ekadashiProhibited: false, supply: false, aliases: [], createdAt: "2026-01-01T00:00:00Z" },
  { id: "ing2", name: "Toor Dal", category: "Pulses", unit: "KG", ekadashiProhibited: false, supply: false, aliases: [], createdAt: "2026-01-01T00:00:00Z" },
];

function withDetail(detail: PurchaseOrderDetailView) {
  returnsRef.current = [
    { data: detail, error: null, loading: false },
    { data: RECEIPTS, error: null, loading: false },
    { data: INGREDIENTS, error: null, loading: false },
  ];
  returnsRef.i = 0;
}

const MIXED_DRAFT: PurchaseOrderDetailView = {
  ...MIXED,
  order: { ...MIXED.order, status: "DRAFT" },
};

describe("a purchase-order line that isn't in the catalogue", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    withDetail(MIXED);
    reloadMock.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows described lines on the order beside the ingredient ones", () => {
    render(<PurchaseOrderDetailPage />);

    // The read-only table is the order as issued. All three lines are on it, with their own
    // quantities and prices — a described line is an ordinary line to everybody except the store.
    expect(screen.getByText("Rice")).toBeInTheDocument();
    expect(screen.getByText("Plastic stool")).toBeInTheDocument();
    expect(screen.getByText("Extension cord")).toBeInTheDocument();
  });

  it("offers no boxes for a described line in the receiving table, and says why", () => {
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /receive delivery/i }));

    // The ingredient line takes a delivery.
    expect(screen.getByLabelText("Received Rice")).toBeInTheDocument();

    // The described ones do not, and the row says so rather than being blank or absent. Absent
    // would be worse than either: the storekeeper is holding a delivery note that lists stools.
    expect(screen.queryByLabelText("Received Plastic stool")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Received Extension cord")).not.toBeInTheDocument();
    expect(screen.getAllByText(/record it as delivered on the order/i)).toHaveLength(2);
  });

  it("submits only the ingredient lines when a delivery is recorded", async () => {
    const receive = vi.spyOn(api, "receiveDelivery").mockResolvedValue({} as GoodsReceiptView);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /receive delivery/i }));

    fireEvent.change(screen.getByLabelText("Received Rice"), { target: { value: "30" } });
    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /record a delivery/i }));
    });

    expect(receive).toHaveBeenCalledTimes(1);
    const submitted = receive.mock.calls[0][1].lines;
    // Exactly one line, and it is the rice. A described line reaching the server would be refused
    // with KMS-400129 — this is the offer being absent rather than the refusal being caught.
    expect(submitted).toHaveLength(1);
    expect(submitted[0].poLineId).toBe("l1");
  });

  it("gives two described lines distinct React keys", () => {
    // Asserted on React's own duplicate-key warning, and that choice is worth explaining, because
    // the obvious test does not work.
    //
    // The obvious test — type into one described row, check the other did not change — PASSES with
    // the broken `key={l.ingredientId}` still in place. Measured, not assumed: with two rows keyed
    // `null` React renders both rows and both quantities correctly, because every input here is
    // controlled from `draftLines` and gets its value re-supplied on each render. So a value-based
    // assertion proves nothing about the key.
    //
    // What React actually does is log, via console.error: "Encountered two children with the same
    // key, `null`. Keys should be unique so that components maintain their identity across updates.
    // Non-unique keys may cause children to be duplicated and/or omitted." That is the defect —
    // undefined reconciliation behaviour on a form somebody is typing into — and it is the one
    // deterministic signal available, so it is what this asserts.
    const errors = vi.spyOn(console, "error").mockImplementation(() => {});
    withDetail(MIXED_DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /edit lines/i }));

    const duplicateKeyWarnings = errors.mock.calls
      .map((c) => String(c[0]))
      .filter((m) => m.includes("two children with the same key"));
    expect(duplicateKeyWarnings).toEqual([]);

    // And the rows really are both there, with their own quantities, however they are keyed.
    expect((screen.getByLabelText("Quantity of Plastic stool") as HTMLInputElement).value).toBe("4");
    expect((screen.getByLabelText("Quantity of Extension cord") as HTMLInputElement).value).toBe("2");
  });

  it("sends description on every line, and never both a description and an ingredient", async () => {
    const update = vi.spyOn(api, "updatePurchaseOrder").mockResolvedValue(undefined);
    withDetail(MIXED_DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /edit lines/i }));

    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /edit the draft order/i }));
    });

    const sent = update.mock.calls[0][1].lines;
    expect(sent).toHaveLength(3);

    // `objectContaining` cannot test an absence here: it would pass just as happily on a line that
    // omitted `description` entirely as on one that sent null, and those two are the whole point of
    // the required-and-nullable convention. So inspect the keys, then the values.
    for (const line of sent) {
      expect(Object.keys(line)).toContain("description");
      expect(Object.keys(line)).toContain("ingredientId");
    }
    expect(sent[0].ingredientId).toBe("ing1");
    expect(sent[0].description).toBeNull();
    expect(sent[1].ingredientId).toBeNull();
    expect(sent[1].description).toBe("Plastic stool");
    expect(sent[2].ingredientId).toBeNull();
    expect(sent[2].description).toBe("Extension cord");
  });

  it("adds a described line to a draft, which is the only way to create one", async () => {
    const update = vi.spyOn(api, "updatePurchaseOrder").mockResolvedValue(undefined);
    withDetail(MIXED_DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /edit lines/i }));

    // `{ selector }` because the field is hinted: InfoHint's "i" button carries the accessible
    // name "More about <label>", so a bare getByLabelText matches the input and the button both.
    const box = screen.getByLabelText(/describe something not in the catalogue/i, { selector: "input" });
    fireEvent.change(box, { target: { value: "  Steel trolley  " } });
    fireEvent.click(screen.getByRole("button", { name: /add described line/i }));

    // It arrives as its own row, trimmed, counted in pieces by default.
    const quantity = screen.getByLabelText("Quantity of Steel trolley") as HTMLInputElement;
    fireEvent.change(quantity, { target: { value: "1" } });

    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /edit the draft order/i }));
    });

    const sent = update.mock.calls[0][1].lines;
    expect(sent).toHaveLength(4);
    expect(sent[3]).toEqual({
      ingredientId: null,
      description: "Steel trolley",
      quantity: 1,
      unit: "PIECES",
      expectedPrice: null,
    });
  });

  it("cannot build a line that names both an ingredient and a description", () => {
    withDetail(MIXED_DRAFT);
    render(<PurchaseOrderDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /edit lines/i }));

    // The two adders are separate controls with separate buttons, so the exclusivity the server
    // enforces with KMS-400128 cannot be violated from this form by accident. The ingredient
    // picker offers no description box and the description box offers no picker.
    const picker = screen.getByLabelText(/add an ingredient/i).closest("div");
    expect(picker).not.toBeNull();
    expect(within(picker as HTMLElement).queryByLabelText(/describe something/i)).toBeNull();

    // And an empty description cannot be added at all — the button stays disabled.
    expect(screen.getByRole("button", { name: /add described line/i })).toBeDisabled();
  });
});
