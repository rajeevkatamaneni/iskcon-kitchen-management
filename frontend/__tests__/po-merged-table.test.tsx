import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { api } from "@/lib/api";
import type {
  GoodsReceiptLineView, GoodsReceiptView, GoodsReturnView, IngredientView, PurchaseOrderDetailView,
  PurchaseOrderLineView,
} from "@/lib/api";

/**
 * An existing purchase order's page as one merged table (T-265, R-PO-4 in
 * docs/work/PROCUREMENT-REQUIREMENTS.md, mock dev-po design E).
 *
 * <p>What is pinned here: one table, with the document's five columns, where each item says what
 * was ordered, delivered, rejected on delivery and returned, with its deliveries folded under the
 * shared "▸ N deliveries" history; no separate "Deliveries received" table; no delivery form on the
 * page at all (deliveries are recorded on the Deliveries screen, and a price belongs to the invoice,
 * R-DEL-5); a link across for the two roles that can record; and "Return to vendor" kept, one per
 * item, asking which delivery when there is more than one (conductor's ruling, 2026-09-19).
 *
 * <p>The page's three `useAuthedQuery` calls are answered by index — order, receipts, ingredient
 * catalogue — as in `order-detail.test.tsx`.
 */
const { authRef, returnsRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_MANAGER", userId: "me" } } as {
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

const NO_PACK = { packSizeId: null, packLabel: null, packQuantity: null, packCount: null };

const RICE: PurchaseOrderLineView = {
  id: "l1", ingredientId: "ing1", ingredientName: "Rice", description: null,
  quantity: 50, unit: "KG", expectedPrice: 60, arrivedOn: null, ...NO_PACK,
};

const DETAIL: PurchaseOrderDetailView = {
  order: {
    id: "po1",
    poNumber: "PO-2026-0142",
    vendorId: "v1",
    vendorName: "Kalasipalya Vegetables",
    status: "PARTIALLY_RECEIVED",
    orderDate: "2026-09-10",
    neededBy: "2026-09-12",
    deliveryLocation: "Main store",
    notes: null,
    cancelReason: null,
    vendorAbandoned: false,
    sentAt: "2026-09-10T05:00:00Z",
    cancelledAt: null,
    createdAt: "2026-09-10T04:00:00Z",
  },
  lines: [RICE],
  events: [],
  templeWhatsappEverSent: false,
  // Required since T-365, so every fixture states it. This test is about the merged line table and
  // never reads the score; null is "nothing to show".
  deliveryScore: null,
};

function receiptLine(over: Partial<GoodsReceiptLineView>): GoodsReceiptLineView {
  return {
    id: "grl", poLineId: "l1", ingredientId: "ing1", ingredientName: "Rice",
    receivedQty: 0, rejectedQty: 0, rejectReason: null, unit: "KG", batchId: null,
    expiryDate: null, receivedDate: null, unitPrice: null, returnedQty: 0,
    ...over,
  };
}

/**
 * Rajeev's rice, delivered in two parts: 30 Kg on 12 Sept with 2 Kg refused as spoiled, then 18 Kg
 * on 14 Sept. Given newest first, so the page has to put them in date order itself.
 */
const TWO_PARTS: GoodsReceiptView[] = [
  {
    id: "gr2", purchaseOrderId: "po1", deliveryNoteRef: null, note: null,
    receivedByName: "Govinda Das", receivedAt: "2026-09-14T05:00:00Z",
    lines: [receiptLine({ id: "grl2", receivedQty: 18, receivedDate: "2026-09-14" })],
  },
  {
    id: "gr1", purchaseOrderId: "po1", deliveryNoteRef: null, note: null,
    receivedByName: "Karuna Murti Das", receivedAt: "2026-09-12T05:00:00Z",
    lines: [receiptLine({ id: "grl1", receivedQty: 30, rejectedQty: 2, rejectReason: "SPOILED", receivedDate: "2026-09-12", returnedQty: 12 })],
  },
];

const INGREDIENTS: IngredientView[] = [];

function show(detail: PurchaseOrderDetailView, receipts: GoodsReceiptView[] = TWO_PARTS) {
  returnsRef.current = [
    { data: detail, error: null, loading: false },
    { data: receipts, error: null, loading: false },
    { data: INGREDIENTS, error: null, loading: false },
  ];
  returnsRef.i = 0;
  return render(<PurchaseOrderDetailPage />);
}

function as(role: string) {
  authRef.current = { status: "signed-in", appUser: { role, userId: "me" } };
}

const cellsOf = (row: HTMLElement) => within(row).getAllByRole("cell").map((c) => c.textContent);

/** The item's own row: the one whose first cell is the item's name. */
function itemRow(name: string): HTMLElement {
  const row = screen.getAllByRole("row").find((r) => r.querySelector("td")?.textContent === name);
  if (!row) throw new Error(`no row for ${name}`);
  return row;
}

/** The row under an item that holds its "▸ N deliveries" history, spanning the table (mock design E). */
function historyRow(name: string): HTMLElement {
  const next = itemRow(name).nextElementSibling as HTMLElement | null;
  if (!next || next.querySelector("td")?.getAttribute("colspan") !== "6") throw new Error(`no history row for ${name}`);
  return next;
}

describe("an order's page is one table of what was ordered and what happened to it (R-PO-4)", () => {
  beforeEach(() => {
    as("KITCHEN_MANAGER");
    reloadMock.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows two partial deliveries as the right totals and two history lines, and no second table", () => {
    show(DETAIL);

    // One table on the page, and no "Deliveries received" anywhere.
    expect(screen.getAllByRole("table")).toHaveLength(1);
    expect(screen.queryByText(/deliveries received/i)).toBeNull();

    const row = itemRow("Rice");
    // Delivered is what was kept, summed (30 + 18); rejected and returned are their own columns.
    expect(cellsOf(row)).toEqual(["Rice", "50 Kg", "48 Kg", "2 Kg", "12 Kg", "Return to vendor: Rice"]);

    // The history sits in its own row under the item, across the whole table, as the mock draws it.
    const below = historyRow("Rice");
    const history = within(below).getByRole("button", { name: "2 deliveries of Rice" });
    expect(history).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(history);
    expect(history).toHaveAttribute("aria-expanded", "true");
    expect(within(below).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "12 Sept · 30 Kg received · 2 Kg rejected (spoiled) · Received by: Karuna Murti Das",
      "14 Sept · 18 Kg received · Received by: Govinda Das",
      "Received 48 of 50 Kg ordered",
    ]);
  });

  it("says when the line completed, from the delivery that completed it", () => {
    show(DETAIL, [
      ...TWO_PARTS,
      {
        id: "gr3", purchaseOrderId: "po1", deliveryNoteRef: null, note: null,
        receivedByName: null, receivedAt: "2026-09-15T05:00:00Z",
        lines: [receiptLine({ id: "grl3", receivedQty: 2, receivedDate: "2026-09-15" })],
      },
    ]);
    const below = historyRow("Rice");
    fireEvent.click(within(below).getByRole("button", { name: /3 deliveries/ }));
    expect(within(below).getAllByRole("listitem").at(-1)?.textContent).toBe(
      "Received 50 of 50 Kg ordered · complete 15 Sept"
    );
  });

  it("has exactly the document's column headings", () => {
    show(DETAIL);
    expect(screen.getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Item", "Ordered", "Delivered", "Rejected on delivery", "Returned", "Actions",
    ]);
  });

  it("says 'Rejected on delivery', and never 'at the gate'", () => {
    show(DETAIL);
    expect(screen.getByRole("columnheader", { name: "Rejected on delivery" })).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/at the gate/i);
  });

  it("calls a part-delivered order 'Part delivered', in the neutral chip, as mock E does", () => {
    show(DETAIL);
    const chip = screen.getByText("Part delivered");
    expect(chip.className).toContain("bg-sunken");
    expect(chip.className).not.toMatch(/warning/);
    expect(document.body.textContent).not.toMatch(/partially received/i);
  });

  // D-5 (T-289). jsdom has no layout, so these pin the classes that give the mock's measurements;
  // the pixels themselves were measured on the running app (docs/work/proof/T-289.md).
  it("sets the status chip as mock E's badge: semibold and 20px high, on this page", () => {
    show(DETAIL);
    const chip = screen.getByText("Part delivered");
    const wrapper = chip.parentElement as HTMLElement;
    // The shared chip keeps its own classes (the list and the invoice picker are unchanged); the
    // wrapper on this page overrides its padding and weight.
    expect(chip.className).toContain("py-1");
    expect(wrapper.className).toContain("[&>span]:py-0.5");
    expect(wrapper.className).toContain("[&>span]:font-semibold");
  });

  it("gives 'Items' the mock's medium weight and insets the table inside the card from 1024px", () => {
    show(DETAIL);
    const heading = screen.getByRole("heading", { name: "Items" });
    expect(heading.className).toContain("font-medium");
    const card = heading.closest("section") as HTMLElement;
    expect(card.className.split(" ")).toEqual(expect.arrayContaining(["card", "py-5", "lg:px-6"]));
    expect(within(card).getByRole("table").parentElement?.className).toContain("mt-3");
  });

  it("calls creating an order 'create', never 'raise', when it offers a new one", () => {
    show({ ...DETAIL, order: { ...DETAIL.order, status: "SENT" } });
    expect(screen.getByText(/It cannot be undone — create a new order if it is needed again\./)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/\braise/i);
  });

  it("no longer says the order was fixed when it was sent", () => {
    show(DETAIL);
    expect(document.body.textContent).not.toMatch(/fixed when the order was sent/i);
  });

  // All three kitchen roles hold RECEIVE_DELIVERIES since Rajeev gave it to Kitchen Staff (Q-1,
  // 2026-09-19), so all three are offered the way across (T-289).
  it("links across to the Deliveries screen, on this order, for every kitchen role", () => {
    for (const role of ["KITCHEN_MANAGER", "TEMPLE_ADMIN", "KITCHEN_STAFF"]) {
      as(role);
      const { unmount } = show(DETAIL);
      const link = screen.getByRole("link", { name: "Record a delivery on the Deliveries screen →" });
      expect(link).toHaveAttribute("href", "/deliveries?order=po1");
      unmount();
    }
  });

  it("shows the link to Kitchen Staff, who record deliveries since Rajeev's answer to Q-1", () => {
    as("KITCHEN_STAFF");
    show(DETAIL);
    expect(screen.getByRole("link", { name: "Record a delivery on the Deliveries screen →" }))
      .toHaveAttribute("href", "/deliveries?order=po1");
    // The rest of the page is theirs to read as before.
    expect(itemRow("Rice")).toBeInTheDocument();
    expect(historyRow("Rice")).toBeInTheDocument();
  });

  it("does not show the link where nothing can be delivered: a draft, or a finished order", () => {
    for (const status of ["DRAFT", "RECEIVED", "CLOSED", "CANCELLED"] as const) {
      const { unmount } = show({ ...DETAIL, order: { ...DETAIL.order, status } });
      expect(screen.queryByRole("link", { name: /record a delivery/i })).toBeNull();
      unmount();
    }
  });

  it("has no way to record a delivery on this page, and no price anywhere in it (R-DEL-5)", () => {
    const receive = vi.spyOn(api, "receiveDelivery");
    show(DETAIL);
    expect(screen.queryByRole("button", { name: /receive delivery/i })).toBeNull();
    expect(screen.queryByRole("form", { name: /record a delivery/i })).toBeNull();
    // Not one number box on the page until somebody opens a return: no "Received now", no
    // "Price paid".
    expect(screen.queryAllByRole("spinbutton")).toHaveLength(0);
    expect(document.body.textContent).not.toMatch(/price paid/i);
    // And no price column or total on the table (conductor's ruling: the mock has neither).
    expect(within(screen.getByRole("table", { name: "Items" })).queryByText(/₹/)).toBeNull();
    expect(receive).not.toHaveBeenCalled();
  });

  it("reads a pack line as the vendor was asked for it, keeping the stock amount", () => {
    show({
      ...DETAIL,
      lines: [{ ...RICE, quantity: 100, packSizeId: "p1", packLabel: "Bag (25 Kg)", packQuantity: 25, packCount: 4 }],
    }, []);
    const row = itemRow("Rice");
    const ordered = within(row).getAllByRole("cell")[1];
    expect(ordered.textContent).toBe("4 × Bag (25 Kg)100 Kg");
    expect(within(ordered).getByText("100 Kg")).toBeInTheDocument();
  });

  // T-302, C-2 as T-299 fixed it on Deliveries: one pack with no name is its own size, so the stock
  // line under it would say the amount twice. Counted in packs, not by comparing text, so a "0.5 Kg"
  // label over 500 gm is a repeat too. Two packs keep their total, and a named pack keeps its size.
  it("says one unnamed pack once, keeping the amount under two packs and under a named pack (T-302)", () => {
    show({
      ...DETAIL,
      lines: [
        { ...RICE, id: "t1", ingredientName: "Tea", quantity: 500, unit: "GM", packSizeId: "p5", packLabel: "500 gm", packQuantity: 500, packCount: 1 },
        { ...RICE, id: "t2", ingredientName: "Ghee", quantity: 500, unit: "GM", packSizeId: "p6", packLabel: "0.5 Kg", packQuantity: 500, packCount: 1 },
        { ...RICE, id: "t3", ingredientName: "Cardamom", quantity: 1000, unit: "GM", packSizeId: "p7", packLabel: "500 gm", packQuantity: 500, packCount: 2 },
        { ...RICE, id: "t4", quantity: 25, packSizeId: "p1", packLabel: "Bag (25 Kg)", packQuantity: 25, packCount: 1 },
      ],
    }, []);
    const ordered = (name: string) => within(itemRow(name)).getAllByRole("cell")[1].textContent;
    expect(ordered("Tea")).toBe("1 × 500 gm");
    expect(ordered("Ghee")).toBe("1 × 0.5 Kg");
    expect(ordered("Cardamom")).toBe("2 × 500 gm1 Kg");
    expect(ordered("Rice")).toBe("1 × Bag (25 Kg)25 Kg");
  });

  it("adds no history row under an item nothing has arrived for", () => {
    show(DETAIL, []);
    expect(itemRow("Rice").nextElementSibling).toBeNull();
    expect(screen.queryByRole("button", { name: /deliver/ })).toBeNull();
  });

  it("says a gram line of 1,000 or more in Kg", () => {
    show({ ...DETAIL, lines: [{ ...RICE, ingredientName: "Cardamom", quantity: 1500, unit: "GM" }] }, []);
    const row = itemRow("Cardamom");
    expect(cellsOf(row).slice(1, 5)).toEqual(["1.5 Kg", "0 gm", "—", "—"]);
  });

  it("keeps Return to vendor, one per item, and asks which delivery when there were two", async () => {
    const send = vi.spyOn(api, "returnReceivedGoods").mockResolvedValue({} as GoodsReturnView);
    show(DETAIL);
    const row = itemRow("Rice");
    expect(within(row).getAllByRole("button", { name: /return to vendor/i })).toHaveLength(1);
    fireEvent.click(within(row).getByRole("button", { name: /return to vendor/i }));

    // Both deliveries can still give something back: 30 − 12 from the first, all 18 of the second.
    const which = screen.getByLabelText("Delivery of Rice to return from") as HTMLSelectElement;
    expect(Array.from(which.options).map((o) => o.textContent)).toEqual([
      "12 Sept · 18 Kg can go back",
      "14 Sept · 18 Kg can go back",
    ]);
    expect(screen.getByText(/18 Kg of this delivery can still go back/)).toBeInTheDocument();

    fireEvent.change(which, { target: { value: "gr2" } });
    fireEvent.change(screen.getByLabelText(/quantity of Rice to return/i), { target: { value: "5" } });
    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /return goods to the vendor/i }));
    });
    expect(send).toHaveBeenCalledTimes(1);
    expect(send.mock.calls[0][0]).toBe("gr2");
    expect(send.mock.calls[0][1].receiptLineId).toBe("grl2");
  });

  it("does not ask which delivery when there is only one to return from", () => {
    show(DETAIL, [TWO_PARTS[1]]);
    fireEvent.click(screen.getByRole("button", { name: /return to vendor/i }));
    expect(screen.queryByLabelText(/delivery of rice to return from/i)).toBeNull();
    expect(screen.getByText(/18 Kg of this delivery can still go back/)).toBeInTheDocument();
  });

  it("returns at most what the chosen delivery received less what already went back", async () => {
    const send = vi.spyOn(api, "returnReceivedGoods").mockResolvedValue({} as GoodsReturnView);
    show(DETAIL);
    fireEvent.click(screen.getByRole("button", { name: /return to vendor/i }));
    const box = screen.getByLabelText(/quantity of Rice to return/i);
    expect(box).toHaveAttribute("max", "18");
    fireEvent.change(box, { target: { value: "19" } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /record return/i }));
    });
    expect(screen.getByText(/can be at most 18/)).toBeInTheDocument();
    expect(send).not.toHaveBeenCalled();
  });

  it("offers no return on an item that has nothing left to send back", () => {
    show(DETAIL, [{ ...TWO_PARTS[1], lines: [receiptLine({ id: "grl2", receivedQty: 18, receivedDate: "2026-09-14", returnedQty: 18 })] }]);
    expect(screen.queryByRole("button", { name: /return to vendor/i })).toBeNull();
    // Returned in full still reads as returned.
    expect(cellsOf(itemRow("Rice"))[4]).toBe("18 Kg");
  });
});
