import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { api } from "@/lib/api";
import type { ApiError, IngredientView, ShoppingListLineView, VendorSupplyView } from "@/lib/api";

/**
 * T-264: the shopping list reads in units a person says (R-SL-1), shows the server's buying amount
 * (R-SL-2), and orders in the vendor's pack (R-SL-3) — on the list and on the purchase order the
 * list creates.
 */

const { queryRef, reloadMock } = vi.hoisted(() => ({
  queryRef: { current: { data: [] as ShoppingListLineView[] | null, error: null as ApiError | null, loading: false } },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    status: "signed-in",
    appUser: { role: "KITCHEN_STAFF", userId: "me" },
    getToken: async () => "test-token",
  }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: { name?: string }) =>
    fetcher?.name === "listIngredients"
      ? { data: [] as IngredientView[], error: null, loading: false, reload: reloadMock }
      : { ...queryRef.current, reload: reloadMock },
}));

import ShoppingListPage from "@/app/shopping-list/page";

function line(o: Partial<ShoppingListLineView>): ShoppingListLineView {
  return {
    ingredientId: "ing1",
    ingredientName: "Rice",
    currentStock: 3,
    unit: "KG",
    suggestedQty: 9,
    neededBy: null,
    orderBy: null,
    leadTimeDays: 2,
    orderUrgency: null,
    suggestedVendorId: "v1",
    suggestedVendorName: "Kalasipalya Market",
    shortfall: 7,
    thresholdTopUp: 0,
    poOutstanding: 0,
    shortPurchaseOrders: [],
    included: true,
    edited: false,
    excludedSince: null,
    buyPacks: [],
    packFromVendor: false,
    ...o,
  };
}

function supply(o: Partial<VendorSupplyView>): VendorSupplyView {
  return {
    ingredientId: "ing1",
    ingredientName: "Rice",
    lastPrice: null,
    unit: "KG",
    packSizeId: null,
    packLabel: null,
    pricePerPack: null,
    previousPrice: null,
    previousPriceOn: null,
    leadTimeDays: null,
    preferred: true,
    ...o,
  };
}

/** Kalasipalya's curry leaves: 2792 gm short, which the server buys as 3000 gm (T-259). */
const CURRY = line({
  ingredientId: "curry", ingredientName: "Curry leaves", unit: "GM",
  currentStock: 0, shortfall: 2792, suggestedQty: 3000,
});

/** 100 Kg short, and the vendor sells rice as Bag = 25 Kg: 4 bags (R-SL-3). */
const RICE = line({
  ingredientId: "rice", ingredientName: "Rice", unit: "KG", shortfall: 100, suggestedQty: 100,
  buyPacks: [{ packSizeId: "ps-bag", label: "Bag (25 Kg)", perPackQty: 25, count: 4 }],
  packFromVendor: true,
});

/** 1,200 gm with packs of 250 gm, 500 gm and 1 Kg, sizes mixed: 1 × 1 Kg + 1 × 250 gm. */
const TEA = line({
  ingredientId: "tea", ingredientName: "Tea", unit: "GM", shortfall: 1200, suggestedQty: 1250,
  buyPacks: [
    { packSizeId: "ps-1kg", label: "1 Kg", perPackQty: 1000, count: 1 },
    { packSizeId: "ps-250", label: "250 gm", perPackQty: 250, count: 1 },
  ],
  packFromVendor: false,
});

/**
 * Every "N gm" or "N ml" in the rendered page, with every box's value read beside the unit printed
 * next to it — the AC is that no quantity of 1,000 or more is shown in gm or ml anywhere.
 */
function smallUnitFiguresAtOrAbove1000(root: HTMLElement): string[] {
  const found: string[] = [];
  const text = root.textContent ?? "";
  for (const m of text.matchAll(/(\d[\d,]*(?:\.\d+)?)\s*(gm|ml)\b/g)) {
    if (Number(m[1].replace(/,/g, "")) >= 1000) found.push(m[0]);
  }
  for (const input of Array.from(root.querySelectorAll<HTMLInputElement>('input[type="number"]'))) {
    const beside = input.nextElementSibling?.textContent?.trim() ?? "";
    if ((beside === "gm" || beside === "ml") && Number(input.value) >= 1000) found.push(`${input.value} ${beside}`);
  }
  return found;
}

let update: MockInstance<typeof api.updateShoppingListLine>;
let create: MockInstance<typeof api.createPurchaseOrder>;

beforeEach(() => {
  reloadMock.mockReset();
  update = vi.spyOn(api, "updateShoppingListLine").mockResolvedValue(undefined);
  create = vi.spyOn(api, "createPurchaseOrder").mockResolvedValue({ id: "po1", poNumber: "PO-2026-0050" });
  vi.spyOn(api, "listVendors").mockResolvedValue([]);
  vi.spyOn(api, "getVendor").mockResolvedValue({ vendor: {} as never, supplies: [], statusHistory: [] });
});

afterEach(() => {
  vi.restoreAllMocks();
});

function withLines(rows: ShoppingListLineView[]) {
  queryRef.current = { data: rows, error: null, loading: false };
}

async function openPanel() {
  fireEvent.click(screen.getByRole("button", { name: /generate purchase order$/i }));
  return screen.findByRole("dialog");
}

async function save() {
  await act(async () => {
    fireEvent.submit(screen.getByRole("form", { name: /purchase order for/i }));
  });
}

describe("R-SL-1 and R-SL-2: readable amounts, and the server's buying amount", () => {
  it("shows Kalasipalya's curry leaves as 3 Kg, with no 2792 anywhere", () => {
    withLines([CURRY]);
    const { container } = render(<ShoppingListPage />);

    const box = screen.getByLabelText("Quantity for Curry leaves");
    expect(box).toHaveValue(3);
    expect(box.nextElementSibling).toHaveTextContent(/^Kg$/);
    expect(container.textContent).not.toMatch(/2792|2,792/);
    expect(smallUnitFiguresAtOrAbove1000(container)).toEqual([]);
  });

  it("reads what is typed in the unit shown beside the box: 2.5 beside Kg saves 2500 gm", async () => {
    withLines([CURRY]);
    render(<ShoppingListPage />);

    const box = screen.getByLabelText("Quantity for Curry leaves");
    fireEvent.change(box, { target: { value: "2.5" } });
    await act(async () => { fireEvent.blur(box); });

    expect(update).toHaveBeenCalledWith("curry", { suggestedQty: 2500, included: true }, "test-token");
  });

  it("keeps a small amount in gm, and reads a typed figure in gm too", async () => {
    withLines([line({ ingredientId: "salt", ingredientName: "Salt", unit: "GM", suggestedQty: 800, shortfall: 780 })]);
    render(<ShoppingListPage />);

    const box = screen.getByLabelText("Quantity for Salt");
    expect(box).toHaveValue(800);
    expect(box.nextElementSibling).toHaveTextContent(/^gm$/);
    fireEvent.change(box, { target: { value: "950" } });
    await act(async () => { fireEvent.blur(box); });
    expect(update).toHaveBeenCalledWith("salt", { suggestedQty: 950, included: true }, "test-token");
  });

  it("sends a hand-typed figure exactly as typed, never rounded to a step", async () => {
    withLines([CURRY]);
    render(<ShoppingListPage />);

    const box = screen.getByLabelText("Quantity for Curry leaves");
    fireEvent.change(box, { target: { value: "2.792" } });
    await act(async () => { fireEvent.blur(box); });
    expect(update).toHaveBeenCalledWith("curry", { suggestedQty: 2792, included: true }, "test-token");
  });

  it("does not write when the box is left as it was", async () => {
    withLines([CURRY]);
    render(<ShoppingListPage />);
    await act(async () => { fireEvent.blur(screen.getByLabelText("Quantity for Curry leaves")); });
    expect(update).not.toHaveBeenCalled();
  });

  it("orders a readable amount: the panel says 3 Kg and the order line goes in Kg", async () => {
    vi.spyOn(api, "getVendor").mockResolvedValue({
      vendor: {} as never,
      supplies: [supply({ ingredientId: "curry", ingredientName: "Curry leaves", unit: "GM", lastPrice: 0.0712 })],
      statusHistory: [],
    });
    withLines([CURRY]);
    render(<ShoppingListPage />);

    const panel = await openPanel();
    const box = within(panel).getByLabelText("Quantity of Curry leaves");
    expect(box).toHaveValue(3);
    expect(box.nextElementSibling).toHaveTextContent(/^Kg$/);
    // The rate follows the unit the quantity is shown in: "3 Kg" goes with "₹71.20 / Kg".
    expect(within(panel).getByText("List price ₹71.20 / Kg")).toBeInTheDocument();
    expect(smallUnitFiguresAtOrAbove1000(panel)).toEqual([]);

    await save();
    expect(create.mock.calls[0][0].lines).toEqual([
      { ingredientId: "curry", description: null, quantity: 3, unit: "KG", expectedPrice: 71.2 },
    ]);
  });
});

describe("R-SL-3: ordering in the vendor's pack", () => {
  it("suggests 4 bags, and keeps the 100 Kg beside it", () => {
    withLines([RICE]);
    render(<ShoppingListPage />);

    // The count in the box, the pack beside it — together "4 × Bag (25 Kg)" — and the stock-unit
    // amount the line keeps for stock and costing under it.
    const box = screen.getByLabelText("Quantity for Rice, in Bag (25 Kg)");
    expect(box).toHaveValue(4);
    expect(box.nextElementSibling).toHaveTextContent(/^× Bag \(25 Kg\)$/);
    expect(screen.getByText("= 100 Kg")).toBeInTheDocument();
  });

  it("reads a changed count as bags: 5 bags saves 125 Kg", async () => {
    withLines([RICE]);
    render(<ShoppingListPage />);

    const box = screen.getByLabelText("Quantity for Rice, in Bag (25 Kg)");
    fireEvent.change(box, { target: { value: "5" } });
    await act(async () => { fireEvent.blur(box); });
    expect(update).toHaveBeenCalledWith("rice", { suggestedQty: 125, included: true }, "test-token");
  });

  it("raises the order line as 4 × Bag (25 Kg), with the price per bag and per Kg", async () => {
    vi.spyOn(api, "getVendor").mockResolvedValue({
      vendor: {} as never,
      supplies: [supply({
        ingredientId: "rice", unit: "KG", lastPrice: 60,
        packSizeId: "ps-bag", packLabel: "Bag = 25 Kg", pricePerPack: 1500,
      })],
      statusHistory: [],
    });
    withLines([RICE]);
    render(<ShoppingListPage />);

    const panel = await openPanel();
    const box = within(panel).getByLabelText("Quantity of Rice, in Bag (25 Kg)");
    expect(box).toHaveValue(4);
    expect(within(panel).getByText("× Bag (25 Kg)")).toBeInTheDocument();
    expect(within(panel).getByText("= 100 Kg")).toBeInTheDocument();
    expect(within(panel).getByText("List price ₹1,500 / bag · ₹60 / Kg")).toBeInTheDocument();

    await save();
    expect(create).toHaveBeenCalledTimes(1);
    expect(create.mock.calls[0][0].lines).toEqual([
      {
        ingredientId: "rice", description: null, quantity: 100, unit: "KG", expectedPrice: 60,
        packSizeId: "ps-bag", packCount: 4,
      },
    ]);
  });

  it("sends a count changed in the panel as that many bags", async () => {
    withLines([RICE]);
    render(<ShoppingListPage />);
    const panel = await openPanel();
    fireEvent.change(within(panel).getByLabelText("Quantity of Rice, in Bag (25 Kg)"), { target: { value: "6" } });
    expect(within(panel).getByText("= 150 Kg")).toBeInTheDocument();

    await save();
    expect(create.mock.calls[0][0].lines[0]).toMatchObject({ quantity: 150, unit: "KG", packSizeId: "ps-bag", packCount: 6 });
  });

  it("refuses part of a bag", async () => {
    withLines([RICE]);
    render(<ShoppingListPage />);
    const panel = await openPanel();
    fireEvent.change(within(panel).getByLabelText("Quantity of Rice, in Bag (25 Kg)"), { target: { value: "2.5" } });
    await save();
    expect(create).not.toHaveBeenCalled();
    // The box's own step refuses it first, beside the box (the shared Form); the editor's own
    // "Order whole packs" is the second guard, for a browser that lets it through.
    expect(within(panel).getAllByText(/whole number/).length).toBeGreaterThan(0);
  });

  it("makes a mixed suggestion one order line per pack size", async () => {
    vi.spyOn(api, "getVendor").mockResolvedValue({
      vendor: {} as never,
      supplies: [supply({ ingredientId: "tea", ingredientName: "Tea", unit: "GM", lastPrice: 0.4 })],
      statusHistory: [],
    });
    withLines([TEA]);
    const { container } = render(<ShoppingListPage />);

    // On the list: the amount, readable, and the packs it is made of.
    expect(screen.getByLabelText("Quantity for Tea")).toHaveValue(1.25);
    expect(screen.getByText("1 × 1 Kg + 1 × 250 gm")).toBeInTheDocument();
    expect(smallUnitFiguresAtOrAbove1000(container)).toEqual([]);

    const panel = await openPanel();
    expect(within(panel).getByLabelText("Quantity of Tea, in 1 Kg")).toHaveValue(1);
    expect(within(panel).getByLabelText("Quantity of Tea, in 250 gm")).toHaveValue(1);
    expect(within(panel).getByText("List price ₹400 / 1 Kg · ₹400 / Kg")).toBeInTheDocument();
    // The rate after the dot is per Kg even for a 250 gm pack: the shared readableRate (T-293),
    // so the same price reads "₹400 / Kg" on both lines, on the vendor page and on the sheet.
    expect(within(panel).getByText("List price ₹100 / 250 gm · ₹400 / Kg")).toBeInTheDocument();
    expect(panel.textContent).not.toMatch(/\/ ?(gm|ml)\b/);
    expect(smallUnitFiguresAtOrAbove1000(panel)).toEqual([]);

    await save();
    expect(create.mock.calls[0][0].lines).toEqual([
      { ingredientId: "tea", description: null, quantity: 1000, unit: "GM", expectedPrice: 0.4, packSizeId: "ps-1kg", packCount: 1 },
      { ingredientId: "tea", description: null, quantity: 250, unit: "GM", expectedPrice: 0.4, packSizeId: "ps-250", packCount: 1 },
    ]);
  });

  it("opens the panel with no price where the vendor's supplies cannot be read", async () => {
    vi.spyOn(api, "getVendor").mockRejectedValue(new Error("offline"));
    withLines([RICE]);
    render(<ShoppingListPage />);
    const panel = await openPanel();
    expect(within(panel).queryByText(/List price/)).not.toBeInTheDocument();
    await save();
    expect(create.mock.calls[0][0].lines[0]).toMatchObject({ expectedPrice: null, packSizeId: "ps-bag", packCount: 4 });
  });
});
