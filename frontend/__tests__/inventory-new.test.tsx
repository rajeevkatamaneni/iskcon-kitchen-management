import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError, type IngredientView, type StockItemView } from "@/lib/api";

// The screen makes two authed queries — every ingredient, and what is already tracked — so the
// stub tells them apart by the fetcher it is handed.
const { ingFn, authRef, ingRef, trackedRef, createItemMock, adjustMock, pushMock, suggestMock } = vi.hoisted(() => ({
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
  suggestMock: vi.fn(),
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
      getStockValueSuggestion: suggestMock,
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
    packSizes: [],
    marketRate: null,
    marketRateOn: null,
    marketRateSource: null,
    ekadashiProhibited: false,
    supply: false,
    notBought: false,
    libraryDerived: false,
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
    // No vendor price and no market rate: the case R-ING-3's acceptance criterion names.
    suggestMock.mockReset().mockResolvedValue({ pricePerUnit: null, source: null });
  });

  it("opens the item with what is on the shelf, and returns to the list with the confirmation", async () => {
    render(<NewInventoryItemPage />);
    expect(screen.getByRole("heading", { name: "Add to inventory" })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/ingredient or supply/i), { target: { value: "ing-rice" } });
    fireEvent.change(screen.getByPlaceholderText("e.g. 40"), { target: { value: "40" } });
    fireEvent.change(screen.getByLabelText(/where is it stored/i), { target: { value: "Main store" } });
    fireEvent.change(valueBox(), { target: { value: "62" } });

    // The commit button is in the sticky header, outside the form, and reaches it by name.
    fireEvent.click(screen.getByRole("button", { name: /add to inventory/i }));

    // ONE request (T-294, VERIFY-A defect 5). The count travels with the item and the server writes
    // both or neither, so a failure can no longer leave an item with no stock and no value. The
    // count opens the item's first lot, so nothing sits at zero badged "below reorder level".
    await waitFor(() => expect(createItemMock).toHaveBeenCalledTimes(1));
    expect(createItemMock.mock.calls[0][0]).toEqual({
      ingredientId: "ing-rice",
      storageLocation: "Main store",
      reorderThreshold: null,
      // No level was typed, so there is no unit for one either (T-432).
      reorderThresholdUnit: null,
      notes: null,
      openingCount: { quantity: 40, unit: "KG", pricePerUnit: 62 },
    });
    expect(adjustMock).not.toHaveBeenCalled();

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

    // The field carries an "i" now, and its accessible name is "More about Tell me when stock
    // drops below" — which the same pattern matches. The selector narrows it to the box itself
    // rather than loosening the query.
    const level = screen.getByLabelText(/tell me when stock drops below/i, { selector: "input" });
    expect(screen.queryByText(/reorder threshold/i)).not.toBeInTheDocument();

    // The ingredient names the unit it is kept in, in the list and then on the field itself. The
    // level has a picker of its OWN since T-432: it used to have none, and borrowed the factor from
    // the opening count's picker two fields away.
    fireEvent.change(screen.getByLabelText(/ingredient or supply/i), { target: { value: "ing-rice" } });
    const unit = screen.getByLabelText("Unit the level is in");
    expect(unit).toHaveValue("KG");

    // And the level may be typed in either unit of that family — "warn me at 500 grams" of a thing
    // the store keeps in kilograms. The browser sends what was typed and the unit it was typed in;
    // the conversion is the server's, against the canonical unit it reads for itself.
    fireEvent.change(unit, { target: { value: "GM" } });
    fireEvent.change(level, { target: { value: "500" } });
    // The header button, as a person presses it (T-161). This used to fire a synthetic submit at
    // the form, which skips every check a click is subject to — the same checks Form now answers.
    fireEvent.click(screen.getByRole("button", { name: /add to inventory/i }));

    await waitFor(() => expect(createItemMock).toHaveBeenCalledTimes(1));
    expect(createItemMock.mock.calls[0][0]).toMatchObject({
      ingredientId: "ing-rice",
      reorderThreshold: 500,
      reorderThresholdUnit: "GM",
    });
    // Nothing was typed into the count, so no lot is opened.
    expect(createItemMock.mock.calls[0][0].openingCount).toBeNull();
    expect(adjustMock).not.toHaveBeenCalled();
  });

  /**
   * The thousandfold bug this field had all along (T-432).
   *
   * <p>The level's box had no unit picker of its own and its value was multiplied by the factor
   * belonging to the **opening count's** picker, two fields above it. So "tell me when ghee drops
   * below 500", typed with the count in grams, stored 0.5 — and with the count in litres, 500. The
   * same keystrokes, a thousandfold apart, decided by a box about something else. Nothing went wrong
   * in practice only because the ingredient anybody tested it on was counted in pieces, where the
   * factor is 1.
   */
  it("does not let the count's unit picker change what the level means", async () => {
    render(<NewInventoryItemPage />);
    fireEvent.change(screen.getByLabelText(/ingredient or supply/i), { target: { value: "ing-rice" } });

    // The count goes in grams; the level stays in kilograms and is untouched by that.
    fireEvent.change(screen.getByLabelText("Unit the count is in"), { target: { value: "GM" } });
    fireEvent.change(screen.getByPlaceholderText("e.g. 40"), { target: { value: "800" } });
    fireEvent.change(
      screen.getByLabelText(/tell me when stock drops below/i, { selector: "input" }),
      { target: { value: "5" } }
    );
    fireEvent.change(screen.getByLabelText(/what it would cost to buy today/i, { selector: "input" }), {
      target: { value: "62" },
    });
    fireEvent.click(screen.getByRole("button", { name: /add to inventory/i }));

    await waitFor(() => expect(createItemMock).toHaveBeenCalledTimes(1));
    expect(createItemMock.mock.calls[0][0]).toMatchObject({
      reorderThreshold: 5,
      reorderThresholdUnit: "KG",
      openingCount: { quantity: 800, unit: "GM", pricePerUnit: 62 },
    });
  });

  /* Rajeev, 2026-09-20: the picker "shows ingredients and supplies merged into one list". They
     always were, and on purpose — only the recipe picker leaves supplies out — but nothing on the
     screen said so, so a storekeeper hunting for leaf plates had no reason to think they were in
     there. The list is now grouped under the two words the menu already uses. */
  it("names both halves of the catalogue, and offers a supply beside the food", () => {
    ingRef.current = [
      ingredient({ id: "ing-rice", name: "Rice", unit: "KG" }),
      ingredient({ id: "ing-plate", name: "Leaf plates", unit: "PIECES", supply: true }),
    ];
    render(<NewInventoryItemPage />);

    expect(screen.getByLabelText(/ingredient or supply/i)).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Ingredients" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Supplies" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: /^Leaf plates/ })).toBeInTheDocument();
  });

  /**
   * T-161: the sentence Form puts beside a refused box. Checked three ways so that "beside" means
   * something: the box is marked invalid, it is described by that very sentence, and the sentence's
   * slot sits straight after the box, or after the label wrapping it.
   */
  function expectSaidBeside(box: HTMLElement, sentence: string) {
    const said = screen.getByText(sentence);
    expect(box).toHaveAttribute("aria-invalid", "true");
    expect(box.getAttribute("aria-describedby")?.split(" ")).toContain(said.id);
    expect((box.closest("label") ?? box).nextElementSibling).toBe(said.parentElement);
  }

  it("names a blank ingredient in red beside its box, and adds nothing (T-161)", () => {
    render(<NewInventoryItemPage />);

    fireEvent.click(screen.getByRole("button", { name: /add to inventory/i }));

    expectSaidBeside(screen.getByLabelText(/ingredient or supply/i), "Ingredient or supply is required");
    expect(createItemMock).not.toHaveBeenCalled();
    expect(adjustMock).not.toHaveBeenCalled();
  });

  it("says a count below nothing must be at least 0, and adds nothing (T-161)", () => {
    render(<NewInventoryItemPage />);
    fireEvent.change(screen.getByLabelText(/ingredient or supply/i), { target: { value: "ing-rice" } });
    fireEvent.change(screen.getByPlaceholderText("e.g. 40"), { target: { value: "-1" } });

    fireEvent.click(screen.getByRole("button", { name: /add to inventory/i }));

    expectSaidBeside(screen.getByPlaceholderText("e.g. 40"), "How much is on the shelf now must be at least 0");
    expect(createItemMock).not.toHaveBeenCalled();
    expect(adjustMock).not.toHaveBeenCalled();
  });

  /** The stock-value box, by its field rather than its "i" (whose name also contains the label). */
  function valueBox() {
    return screen.getByLabelText(/what it would cost to buy today/i, { selector: "input" });
  }

  describe("what it would cost to buy today (R-ING-3)", () => {
    it("asks for the value of rice with no vendor price, and adds nothing until it is given", async () => {
      render(<NewInventoryItemPage />);

      // Before an ingredient is chosen there is no unit to name, and the box waits (conductor's ruling).
      expect(valueBox()).toBeDisabled();
      expect(screen.getByText("What it would cost to buy today (₹)")).toBeInTheDocument();

      fireEvent.change(screen.getByLabelText(/ingredient or supply/i), { target: { value: "ing-rice" } });
      await waitFor(() => expect(suggestMock).toHaveBeenCalledWith("ing-rice", "test-token"));
      // The unit is the ingredient's stock unit, in the app's own label.
      expect(screen.getByText("What it would cost to buy today (₹ per Kg)")).toBeInTheDocument();
      expect(valueBox()).toBeEnabled();
      expect(valueBox()).toHaveValue(null);

      fireEvent.change(screen.getByPlaceholderText("e.g. 40"), { target: { value: "40" } });
      fireEvent.click(screen.getByRole("button", { name: /add to inventory/i }));

      expectSaidBeside(valueBox(), "What it would cost to buy today (₹ per Kg) is required");
      expect(valueBox()).toBeRequired();
      expect(createItemMock).not.toHaveBeenCalled();
      expect(adjustMock).not.toHaveBeenCalled();
    });

    it("refuses 0 in red beside the box", () => {
      render(<NewInventoryItemPage />);
      fireEvent.change(screen.getByLabelText(/ingredient or supply/i), { target: { value: "ing-rice" } });
      fireEvent.change(screen.getByPlaceholderText("e.g. 40"), { target: { value: "40" } });
      fireEvent.change(valueBox(), { target: { value: "0" } });

      fireEvent.click(screen.getByRole("button", { name: /add to inventory/i }));

      expectSaidBeside(valueBox(), "What it would cost to buy today (₹ per Kg) must be more than 0");
      expect(createItemMock).not.toHaveBeenCalled();
    });

    it("pre-fills the suggestion, and sends it with the count", async () => {
      suggestMock.mockResolvedValue({ pricePerUnit: 58.5, source: "PREFERRED_VENDOR" });
      render(<NewInventoryItemPage />);
      fireEvent.change(screen.getByLabelText(/ingredient or supply/i), { target: { value: "ing-rice" } });
      await waitFor(() => expect(valueBox()).toHaveValue(58.5));

      fireEvent.change(screen.getByPlaceholderText("e.g. 40"), { target: { value: "40" } });
      fireEvent.click(screen.getByRole("button", { name: /add to inventory/i }));

      await waitFor(() => expect(createItemMock).toHaveBeenCalledTimes(1));
      expect(createItemMock.mock.calls[0][0].openingCount).toEqual({
        quantity: 40,
        unit: "KG",
        pricePerUnit: 58.5,
      });
      expect(adjustMock).not.toHaveBeenCalled();
    });

    it("names the stock unit even when the count is typed in another unit of it", async () => {
      render(<NewInventoryItemPage />);
      fireEvent.change(screen.getByLabelText(/ingredient or supply/i), { target: { value: "ing-hing" } });
      await waitFor(() => expect(suggestMock).toHaveBeenCalledWith("ing-hing", "test-token"));
      fireEvent.change(screen.getByLabelText("Unit the count is in"), { target: { value: "KG" } });
      // Asafoetida is kept in gm, so its value is per gm whatever the count is typed in.
      expect(screen.getByText("What it would cost to buy today (₹ per gm)")).toBeInTheDocument();
    });

    it("does not ask for a value when no count is typed, because no stock is added", async () => {
      render(<NewInventoryItemPage />);
      fireEvent.change(screen.getByLabelText(/ingredient or supply/i), { target: { value: "ing-rice" } });
      expect(valueBox()).not.toBeRequired();

      fireEvent.click(screen.getByRole("button", { name: /add to inventory/i }));

      await waitFor(() => expect(createItemMock).toHaveBeenCalledTimes(1));
      expect(createItemMock.mock.calls[0][0].openingCount).toBeNull();
      expect(adjustMock).not.toHaveBeenCalled();
    });

    it("shows the server's refusal the usual way (KMS-400161)", async () => {
      // The refusal now comes back on the one request that carries the count; the server has
      // written nothing, so the page stays put with everything still typed in.
      createItemMock.mockRejectedValue(
        new ApiError(
          {
            code: "KMS-400161",
            message: "Enter what it would cost to buy this today.",
            action: "Type the price per unit. It can't be blank or 0, and it becomes the ingredient's market rate.",
            fieldErrors: [],
          },
          400
        )
      );
      render(<NewInventoryItemPage />);
      fireEvent.change(screen.getByLabelText(/ingredient or supply/i), { target: { value: "ing-rice" } });
      fireEvent.change(screen.getByPlaceholderText("e.g. 40"), { target: { value: "40" } });
      fireEvent.change(valueBox(), { target: { value: "62" } });
      fireEvent.click(screen.getByRole("button", { name: /add to inventory/i }));

      expect(await screen.findByText("Enter what it would cost to buy this today.")).toBeInTheDocument();
      expect(screen.getByText(/KMS-400161/)).toBeInTheDocument();
      expect(pushMock).not.toHaveBeenCalled();
      expect(createItemMock).toHaveBeenCalledTimes(1);
      expect(adjustMock).not.toHaveBeenCalled();
    });
  });

  /**
   * VERIFY-A defect 4: at 390 wide the form stayed two columns, and the ingredient select showed
   * "VERIFY-A Rice — kept" of "VERIFY-A Rice — kept in Kg" (157px of text in a 125px box). jsdom
   * has no layout, so this pins the class that decides it — one column below `md`, two from `md` up —
   * and the widths themselves were measured on the running app (docs/work/proof/T-294.md).
   */
  it("puts each field on its own row on a phone, and pairs them from md up", () => {
    render(<NewInventoryItemPage />);
    const form = screen.getByRole("form", { name: "Add to inventory" });
    const classes = form.className.split(/\s+/);
    expect(classes).toContain("grid-cols-1");
    expect(classes).toContain("md:grid-cols-2");
    expect(classes).not.toContain("grid-cols-2");
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
        committed: 0,
        available: 2,
        reorderThreshold: null,
        belowThreshold: false,
        expiringSoon: false,
        soonestExpiry: null,
        notes: null,
        lastCounted: null,
        onOrder: null,
        lastsFor: null,
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
