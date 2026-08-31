import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { IngredientView, StockItemView } from "@/lib/api";

// The screen makes two authed queries — every ingredient, and what is already tracked — so the
// stub tells them apart by the fetcher it is handed.
const { ingFn, authRef, ingRef, trackedRef, createItemMock, adjustMock, pushMock } = vi.hoisted(() => ({
  ingFn: () => {},
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  ingRef: { current: [] as IngredientView[] },
  trackedRef: { current: [] as StockItemView[] },
  createItemMock: vi.fn(),
  adjustMock: vi.fn(),
  pushMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: pushMock, replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: unknown) => ({
    data: fetcher === ingFn ? ingRef.current : trackedRef.current,
    error: null,
    loading: false,
    reload: vi.fn(),
  }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      listIngredients: ingFn,
      createInventoryItem: createItemMock,
      adjustStock: adjustMock,
    },
  };
});

import NewInventoryItemPage from "@/app/inventory/new/page";

function ingredient(o: Partial<IngredientView>): IngredientView {
  return {
    id: "ing-rice",
    name: "Rice",
    category: "Grains",
    unit: "KG",
    sattvicProhibited: false,
    aliases: [],
    createdAt: "2026-08-01T00:00:00Z",
    ...o,
  };
}

describe("adding to inventory", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    ingRef.current = [ingredient({}), ingredient({ id: "ing-hing", name: "Asafoetida", unit: "GM" })];
    trackedRef.current = [];
    createItemMock.mockReset().mockResolvedValue("new-item");
    adjustMock.mockReset().mockResolvedValue(undefined);
    pushMock.mockReset();
  });

  it("opens the item with what is on the shelf, and returns to the list with the confirmation", async () => {
    render(<NewInventoryItemPage />);
    expect(screen.getByRole("heading", { name: "Add to inventory" })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^ingredient$/i), { target: { value: "ing-rice" } });
    fireEvent.change(screen.getByPlaceholderText("e.g. 40"), { target: { value: "40" } });
    fireEvent.change(screen.getByLabelText(/where it lives/i), { target: { value: "Main store" } });

    // The commit button is in the sticky header, outside the form, and reaches it by name.
    fireEvent.click(screen.getByRole("button", { name: /add to inventory/i }));

    await waitFor(() => expect(createItemMock).toHaveBeenCalledTimes(1));
    expect(createItemMock.mock.calls[0][0]).toMatchObject({
      ingredientId: "ing-rice",
      storageLocation: "Main store",
    });
    // The count opens the item's first lot, so nothing sits at zero badged "below reorder level".
    await waitFor(() => expect(adjustMock).toHaveBeenCalledTimes(1));
    expect(adjustMock.mock.calls[0][1]).toMatchObject({ quantity: 40, unit: "KG", reason: "COUNT_CORRECTION" });

    // Rule 8: the confirmation waits on the list, not here.
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/inventory?added=Rice"));
  });

  /**
   * The field that caused the mess. It read "Reorder threshold", named no unit, and the first
   * person to use it took it for how much they had on the shelf, typed 100, and got an item
   * claiming 652 kg on hand with a warning level of 100.
   */
  it("asks for the warning level in words, in a unit it names, and stores it in the ingredient's own", async () => {
    render(<NewInventoryItemPage />);

    const level = screen.getByLabelText(/tell me when stock drops below/i);
    expect(screen.queryByText(/reorder threshold/i)).not.toBeInTheDocument();

    // The ingredient names the unit it is kept in, in the list and then on the field itself.
    fireEvent.change(screen.getByLabelText(/^ingredient$/i), { target: { value: "ing-rice" } });
    const unit = screen.getByLabelText("Unit");
    expect(unit).toHaveValue("KG");

    // And the level may be typed in either unit of that family — "warn me at 500 grams" of a thing
    // the store keeps in kilograms. What is stored is always the ingredient's own.
    fireEvent.change(unit, { target: { value: "GM" } });
    fireEvent.change(level, { target: { value: "500" } });
    fireEvent.submit(screen.getByRole("form", { name: /add to inventory/i }));

    await waitFor(() => expect(createItemMock).toHaveBeenCalledTimes(1));
    expect(createItemMock.mock.calls[0][0]).toMatchObject({
      ingredientId: "ing-rice",
      reorderThreshold: 0.5,
    });
    // Nothing was typed into the count, so no lot is opened.
    expect(adjustMock).not.toHaveBeenCalled();
  });

  it("leaves out an ingredient the inventory already holds", () => {
    trackedRef.current = [
      {
        itemId: "it1",
        ingredientId: "ing-rice",
        ingredientName: "Rice",
        category: "Grains",
        storageLocation: null,
        unit: "KG",
        onHand: 2,
        reorderThreshold: null,
        belowThreshold: false,
        expiringSoon: false,
        soonestExpiry: null,
        notes: null,
      },
    ];
    render(<NewInventoryItemPage />);
    expect(screen.queryByRole("option", { name: /^Rice/ })).not.toBeInTheDocument();
    expect(screen.getByRole("option", { name: /^Asafoetida/ })).toBeInTheDocument();
  });

  it("offers Cancel back to the list, and no way out that is not Cancel", () => {
    render(<NewInventoryItemPage />);
    expect(screen.getByRole("link", { name: /^cancel$/i })).toHaveAttribute("href", "/inventory");
    expect(screen.queryByRole("button", { name: /close/i })).not.toBeInTheDocument();
  });

  it("refuses a role without inventory access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<NewInventoryItemPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
