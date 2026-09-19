import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { api } from "@/lib/api";
import type { ApiError, IngredientSupplyView, IngredientView, ShoppingListLineView, VendorView } from "@/lib/api";

/**
 * T-264, R-SL-4: "No vendor yet" lines. Each line gets a Choose vendor dropdown and, beside it, a
 * "Use this vendor next time" tick, on by default; the tile gets "Order these from [vendor ▾]" for
 * every ticked line (conductor's rulings 3 and 4, 2026-09-19).
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
    ingredientId: "ghee",
    ingredientName: "Ghee",
    currentStock: 0,
    unit: "KG",
    suggestedQty: 5,
    neededBy: null,
    orderBy: null,
    leadTimeDays: null,
    orderUrgency: null,
    suggestedVendorId: null,
    suggestedVendorName: null,
    shortfall: 5,
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

function vendor(id: string, name: string): VendorView {
  return {
    id, name, contactPerson: null, phone: null, email: null, address: null, gstin: null,
    preferredLanguage: "en", notes: null, contractEndDate: null, contractEndingSoon: false,
    active: true, whatsappReachable: false, createdAt: "2026-08-01T00:00:00Z",
  };
}

function existingSupply(o: Partial<IngredientSupplyView>): IngredientSupplyView {
  return {
    vendorId: "v2", vendorName: "Heritage Fresh Dairy",
    ingredientId: "ghee", ingredientName: "Ghee", lastPrice: null, unit: "KG",
    packSizeId: null, packLabel: null, pricePerPack: null,
    previousPrice: null, previousPriceOn: null, leadTimeDays: null, preferred: false,
    ...o,
  };
}

const GOVIND = vendor("v1", "Govind Wholesale");
const HERITAGE = vendor("v2", "Heritage Fresh Dairy");
const RICE = line({ ingredientId: "rice", ingredientName: "Rice", suggestedVendorId: "v1", suggestedVendorName: "Govind Wholesale" });
const GHEE = line({});
const JAGGERY = line({ ingredientId: "jaggery", ingredientName: "Jaggery" });
const SALT = line({ ingredientId: "salt", ingredientName: "Salt", included: false, excludedSince: "2026-09-01" });

let supplies: MockInstance<typeof api.listIngredientSupplies>;
let setSupply: MockInstance<typeof api.setVendorSupply>;

beforeEach(() => {
  reloadMock.mockReset();
  vi.spyOn(api, "listVendors").mockResolvedValue([GOVIND, HERITAGE]);
  vi.spyOn(api, "getVendor").mockResolvedValue({ vendor: {} as never, supplies: [], statusHistory: [] });
  supplies = vi.spyOn(api, "listIngredientSupplies").mockResolvedValue([]);
  setSupply = vi.spyOn(api, "setVendorSupply").mockResolvedValue(undefined);
});

afterEach(() => {
  vi.restoreAllMocks();
});

function withLines(rows: ShoppingListLineView[]) {
  queryRef.current = { data: rows, error: null, loading: false };
}

/** Waits for the vendors to arrive in the dropdown, which is read after the first render. */
async function ready() {
  await screen.findAllByRole("option", { name: "Heritage Fresh Dairy" });
}

async function choose(select: HTMLElement, vendorId: string) {
  await act(async () => { fireEvent.change(select, { target: { value: vendorId } }); });
}

function tile(name: RegExp) {
  return screen.getByRole("table", { name });
}

describe("a line with no vendor yet", () => {
  it("has a Choose vendor dropdown of the active vendors, with the tick beside it, on by default", async () => {
    withLines([GHEE]);
    render(<ShoppingListPage />);
    await ready();

    const dropdown = screen.getByLabelText("Choose vendor for Ghee");
    expect(within(dropdown).getAllByRole("option").map((o) => o.textContent))
      .toEqual(["Choose vendor…", "Govind Wholesale", "Heritage Fresh Dairy"]);
    expect(screen.getByLabelText("Use this vendor next time for Ghee")).toBeChecked();
    // No Generate button on the No vendor yet tile: there is nobody to send it to.
    expect(screen.queryByRole("button", { name: /generate purchase order$/i })).not.toBeInTheDocument();
  });

  it("moves to the vendor's tile with a Generate purchase order button, and saves the vendor as preferred", async () => {
    withLines([GHEE]);
    render(<ShoppingListPage />);
    await ready();

    await choose(screen.getByLabelText("Choose vendor for Ghee"), "v2");

    expect(within(tile(/ingredients from Heritage Fresh Dairy/i)).getByText("Ghee")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: /ingredients from no vendor/i })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /generate purchase order$/i })).toBeEnabled();

    // A new link: nothing to keep, so every value but Preferred is empty — and all six keys are
    // sent, because a key left out is a column the server writes as empty (T-131).
    expect(supplies).toHaveBeenCalledWith("ghee", "test-token");
    expect(setSupply).toHaveBeenCalledTimes(1);
    const [vendorId, input] = setSupply.mock.calls[0];
    expect(vendorId).toBe("v2");
    expect(input).toEqual({
      ingredientId: "ghee", lastPrice: null, leadTimeDays: null, preferred: true, packSizeId: null, pricePerPack: null,
    });
    expect(Object.keys(input as object).sort()).toEqual(
      ["ingredientId", "lastPrice", "leadTimeDays", "packSizeId", "preferred", "pricePerPack"]
    );
    expect(reloadMock).toHaveBeenCalled();
  });

  it("with the tick on, is still under the vendor after a reload", async () => {
    withLines([GHEE]);
    const first = render(<ShoppingListPage />);
    await ready();
    await choose(screen.getByLabelText("Choose vendor for Ghee"), "v2");
    first.unmount();

    // A fresh visit, answered by the server as it answers once the vendor is preferred.
    withLines([{ ...GHEE, suggestedVendorId: "v2", suggestedVendorName: "Heritage Fresh Dairy" }]);
    render(<ShoppingListPage />);
    expect(within(tile(/ingredients from Heritage Fresh Dairy/i)).getByText("Ghee")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /generate purchase order$/i })).toBeInTheDocument();
  });

  it("keeps an existing link's list price and lead time, and only turns Preferred on", async () => {
    supplies.mockResolvedValue([
      existingSupply({ vendorId: "v1", vendorName: "Govind Wholesale", lastPrice: 999, leadTimeDays: 9 }),
      existingSupply({ lastPrice: 480, leadTimeDays: 3, preferred: false }),
    ]);
    withLines([GHEE]);
    render(<ShoppingListPage />);
    await ready();
    await choose(screen.getByLabelText("Choose vendor for Ghee"), "v2");

    expect(setSupply.mock.calls[0][1]).toEqual({
      ingredientId: "ghee", lastPrice: 480, leadTimeDays: 3, preferred: true, packSizeId: null, pricePerPack: null,
    });
  });

  it("keeps an existing pack and its price per pack, sent the way the vendor page sends it", async () => {
    supplies.mockResolvedValue([
      existingSupply({ lastPrice: 480, leadTimeDays: 2, packSizeId: "ps-tin", packLabel: "Tin = 15 Kg", pricePerPack: 7200 }),
    ]);
    withLines([GHEE]);
    render(<ShoppingListPage />);
    await ready();
    await choose(screen.getByLabelText("Choose vendor for Ghee"), "v2");

    // The per-unit price goes as null beside a pack: the server derives it from the pack price and
    // refuses both at once (KMS-400163). Nothing is cleared — the price is the pack price.
    expect(setSupply.mock.calls[0][1]).toEqual({
      ingredientId: "ghee", lastPrice: null, leadTimeDays: 2, preferred: true, packSizeId: "ps-tin", pricePerPack: 7200,
    });
  });

  it("writes nothing when the existing link cannot be read, and says the move is for this visit", async () => {
    supplies.mockRejectedValue(new Error("offline"));
    withLines([GHEE]);
    render(<ShoppingListPage />);
    await ready();
    await choose(screen.getByLabelText("Choose vendor for Ghee"), "v2");

    expect(setSupply).not.toHaveBeenCalled();
    expect(within(tile(/ingredients from Heritage Fresh Dairy/i)).getByText("Ghee")).toBeInTheDocument();
    expect(screen.getByText(/for this visit only/)).toBeInTheDocument();
  });

  it("with the tick off, moves for this visit only and saves nothing", async () => {
    withLines([GHEE]);
    const first = render(<ShoppingListPage />);
    await ready();

    fireEvent.click(screen.getByLabelText("Use this vendor next time for Ghee"));
    await choose(screen.getByLabelText("Choose vendor for Ghee"), "v2");

    expect(within(tile(/ingredients from Heritage Fresh Dairy/i)).getByText("Ghee")).toBeInTheDocument();
    expect(supplies).not.toHaveBeenCalled();
    expect(setSupply).not.toHaveBeenCalled();
    expect(reloadMock).not.toHaveBeenCalled();

    // It can be ordered from there on this visit.
    fireEvent.click(screen.getByRole("button", { name: /generate purchase order$/i }));
    const panel = await screen.findByRole("dialog", { name: /purchase order for Heritage Fresh Dairy/i });
    expect(within(panel).getByLabelText("Quantity of Ghee")).toHaveValue(5);
    fireEvent.click(within(panel).getByRole("button", { name: /^cancel$/i }));

    // The next visit: the server still has no vendor for it, and nothing here remembered one.
    first.unmount();
    render(<ShoppingListPage />);
    await ready();
    expect(within(tile(/ingredients from no vendor/i)).getByText("Ghee")).toBeInTheDocument();
  });
});

describe("Order these from [vendor]", () => {
  it("moves every ticked line to the chosen vendor, and leaves an unticked one", async () => {
    withLines([RICE, GHEE, JAGGERY, SALT]);
    render(<ShoppingListPage />);
    await ready();

    await choose(screen.getByLabelText("Order these from"), "v1");

    const govind = tile(/ingredients from Govind Wholesale/i);
    expect(within(govind).getByText("Rice")).toBeInTheDocument();
    expect(within(govind).getByText("Ghee")).toBeInTheDocument();
    expect(within(govind).getByText("Jaggery")).toBeInTheDocument();
    expect(within(tile(/ingredients from no vendor/i)).getByText("Salt")).toBeInTheDocument();

    // The tick in the bar, on by default, applies to all of them.
    expect(setSupply).toHaveBeenCalledTimes(2);
    expect(setSupply.mock.calls.map((c) => [c[0], (c[1] as { ingredientId: string }).ingredientId]))
      .toEqual([["v1", "ghee"], ["v1", "jaggery"]]);
    expect(setSupply.mock.calls.every((c) => (c[1] as { preferred: boolean }).preferred)).toBe(true);
  });

  it("with the bar's tick off, moves them for this visit only", async () => {
    withLines([GHEE, JAGGERY]);
    render(<ShoppingListPage />);
    await ready();

    const barTick = screen.getByLabelText("Use this vendor next time for the ticked lines");
    expect(barTick).toBeChecked();
    fireEvent.click(barTick);
    await choose(screen.getByLabelText("Order these from"), "v2");

    const heritage = tile(/ingredients from Heritage Fresh Dairy/i);
    expect(within(heritage).getByText("Ghee")).toBeInTheDocument();
    expect(within(heritage).getByText("Jaggery")).toBeInTheDocument();
    expect(setSupply).not.toHaveBeenCalled();
  });

  it("is refused while nothing is ticked", async () => {
    withLines([SALT]);
    render(<ShoppingListPage />);
    await ready();
    expect(screen.getByLabelText("Order these from")).toBeDisabled();
  });
});
