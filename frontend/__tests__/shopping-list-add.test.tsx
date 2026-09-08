import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError, type IngredientView, type ShoppingListLineView } from "@/lib/api";

const { authRef, queryRef, catalogueRef, reloadMock, addMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: { current: { data: [] as ShoppingListLineView[] | null, error: null as ApiError | null, loading: false } },
  catalogueRef: { current: [] as IngredientView[] | null },
  reloadMock: vi.fn(),
  addMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
// Two queries on this screen — the list, and the catalogue behind the picker — answered by which
// api method the hook was handed. `api.listIngredients` is a named property, so its function name
// is exactly that.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: { name?: string }) =>
    fetcher?.name === "listIngredients"
      ? { data: catalogueRef.current, error: null, loading: false, reload: reloadMock }
      : { ...queryRef.current, reload: reloadMock },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, addShoppingListLine: addMock } };
});

import ShoppingListPage from "@/app/shopping-list/page";

function ingredient(o: Partial<IngredientView>): IngredientView {
  return {
    id: "i1",
    name: "Rice",
    category: "Grains",
    unit: "KG",
    ekadashiProhibited: false,
    supply: false,
    aliases: [],
    createdAt: "2026-08-01T00:00:00Z",
    ...o,
  };
}

function line(o: Partial<ShoppingListLineView>): ShoppingListLineView {
  return {
    ingredientId: "i1",
    ingredientName: "Rice",
    currentStock: 3,
    unit: "KG",
    suggestedQty: 9,
    neededBy: "2026-08-20",
    suggestedVendorId: "v1",
    suggestedVendorName: "Govind Wholesale",
    shortfall: 7,
    thresholdTopUp: 0,
    poOutstanding: 0,
    shortPurchaseOrders: [],
    included: true,
    edited: false,
    ...o,
  };
}

const GAS = ingredient({ id: "i2", name: "Cooking gas cylinder", category: "Supplies", unit: "PIECES", supply: true });

/**
 * Adding a line to the shopping list by hand (T-027) — the control that did not exist on this
 * screen at all until now, so a cook who could see the list was missing something had no way to
 * say so.
 */
describe("adding a shopping-list line by hand", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    queryRef.current = { data: [], error: null, loading: false };
    catalogueRef.current = [ingredient({}), GAS];
    reloadMock.mockReset();
    addMock.mockReset();
    addMock.mockResolvedValue(undefined);
  });

  function choose(name: string) {
    fireEvent.change(screen.getByLabelText(/^item$/i), {
      target: { value: catalogueRef.current!.find((i) => i.name === name)!.id },
    });
  }

  it("posts the chosen ingredient and quantity, then reloads the list", async () => {
    render(<ShoppingListPage />);
    choose("Rice");
    fireEvent.change(screen.getByLabelText(/quantity to add/i), { target: { value: "4" } });
    fireEvent.click(screen.getByRole("button", { name: /add to list/i }));

    // No unit travels with it. The server writes the ingredient's own canonical unit, which is the
    // decision that makes "five litres of rice" unrepresentable rather than merely unlikely.
    await waitFor(() =>
      expect(addMock).toHaveBeenCalledWith({ ingredientId: "i1", suggestedQty: 4 }, "test-token")
    );
    expect(reloadMock).toHaveBeenCalled();
  });

  it("offers a supply exactly as it offers food", async () => {
    // T-023 made a supply a flag on the catalogue rather than a table of its own (D-1), so a gas
    // cylinder reaches this picker with everything else and needs no case of its own to add.
    render(<ShoppingListPage />);
    expect(screen.getByRole("option", { name: "Cooking gas cylinder" })).toBeInTheDocument();

    choose("Cooking gas cylinder");
    fireEvent.change(screen.getByLabelText(/quantity to add/i), { target: { value: "2" } });
    fireEvent.click(screen.getByRole("button", { name: /add to list/i }));

    await waitFor(() =>
      expect(addMock).toHaveBeenCalledWith({ ingredientId: "i2", suggestedQty: 2 }, "test-token")
    );
  });

  it("names the unit the line will be written in once an item is chosen", () => {
    render(<ShoppingListPage />);
    choose("Cooking gas cylinder");
    // Stated, not offered: there is no unit control, so this is the ingredient's own unit read back.
    expect(screen.getByText("pieces")).toBeInTheDocument();
  });

  it("does not offer something that is already on the list", () => {
    queryRef.current = { data: [line({})], error: null, loading: false };
    render(<ShoppingListPage />);
    // Rice has a row of its own with a quantity box on it, and the server refuses a second line for
    // it (KMS-400131), so offering it here would be offering an action that cannot succeed.
    expect(screen.queryByRole("option", { name: "Rice" })).not.toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Cooking gas cylinder" })).toBeInTheDocument();
  });

  it("refuses to add without an item, or with a quantity of zero", () => {
    render(<ShoppingListPage />);
    const add = screen.getByRole("button", { name: /add to list/i });
    expect(add).toBeDisabled();

    choose("Rice");
    expect(add).toBeDisabled();   // an item but no quantity

    fireEvent.change(screen.getByLabelText(/quantity to add/i), { target: { value: "0" } });
    expect(add).toBeDisabled();   // zero is not a placeholder; the column's CHECK says so too

    fireEvent.change(screen.getByLabelText(/quantity to add/i), { target: { value: "4" } });
    expect(add).toBeEnabled();
  });

  it("shows the server's refusal and keeps what was typed", async () => {
    addMock.mockRejectedValue(
      new ApiError(
        {
          code: "KMS-400131",
          message: "That's already on the shopping list.",
          action: "Change the quantity on the line that's there.",
          fieldErrors: [],
        },
        409
      )
    );

    render(<ShoppingListPage />);
    choose("Rice");
    fireEvent.change(screen.getByLabelText(/quantity to add/i), { target: { value: "4" } });
    fireEvent.click(screen.getByRole("button", { name: /add to list/i }));

    expect(await screen.findByText("KMS-400131")).toBeInTheDocument();
    // Not cleared on a refusal: the cook corrects what is there rather than typing it again.
    expect(screen.getByLabelText(/quantity to add/i)).toHaveValue(4);
  });

  it("offers the control under the empty state too", () => {
    queryRef.current = { data: [], error: null, loading: false };
    render(<ShoppingListPage />);
    expect(screen.getByText(/nothing to order/i)).toBeInTheDocument();
    // An empty list is not evidence that nothing is needed, only that nothing was computed.
    expect(screen.getByRole("button", { name: /add to list/i })).toBeInTheDocument();
  });
});
