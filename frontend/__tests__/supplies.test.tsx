import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { IngredientView, RecipeCategory } from "@/lib/api";

/*
  T-023 / D-1 — supplies are a flag on the ingredient catalogue, not a second catalogue.

  LPG, single-use leaf plates, cleaning and dishwashing supplies, hand soap and first-aid kits are
  bought from a vendor, received, stored, used up and wanted back when they run low. That is the
  ingredient lifecycle exactly, so D-1 rejected a parallel `supply_items` table with a stock ledger
  of its own: it duplicates the entire inventory chain to express a difference that is one boolean.

  The consequence this file exists to hold down is a NEGATIVE one. Exactly one picker filters — the
  recipe picker, because a mop is not an ingredient of anything — and every other picker must keep
  offering supplies, or the flag has quietly turned into the second catalogue D-1 refused. So both
  halves are asserted here, and the ingredient-request picker (which reaches the same endpoint by a
  different code path) is asserted in ingredient-request-new.test.tsx.

  The picker is not the guard either way: `RecipeService` refuses a supply on a recipe line with
  KMS-400127, asserted in SupplyIngredientIT.
*/

const { catFn, ingFn, authRef, catRef, ingRef, updateMock, createMock, createItemMock, adjustMock, pushMock } =
  vi.hoisted(() => ({
    catFn: () => {},
    ingFn: () => {},
    authRef: {
      current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
        status: string;
        appUser: { role: string; userId?: string; fullName?: string } | null;
      },
    },
    catRef: { current: { data: [] as RecipeCategory[] | null, error: null, loading: false } },
    ingRef: { current: { data: [] as IngredientView[] | null, error: null, loading: false } },
    updateMock: vi.fn(),
    createMock: vi.fn(),
    createItemMock: vi.fn(),
    adjustMock: vi.fn(),
    pushMock: vi.fn(),
  }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
// Every screen under test loads the whole catalogue through the same `api.listIngredients`, which
// is the fact that makes one flag enough — so the stub tells the queries apart by fetcher identity
// and hands all of them the same list.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: unknown) =>
    fetcher === catFn
      ? { ...catRef.current, reload: vi.fn() }
      : fetcher === ingFn
        ? { ...ingRef.current, reload: vi.fn() }
        : { data: [], error: null, loading: false, reload: vi.fn() },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      listIngredients: ingFn,
      listRecipeCategories: catFn,
      updateIngredient: updateMock,
      createIngredient: createMock,
      createInventoryItem: createItemMock,
      adjustStock: adjustMock,
    },
  };
});

import IngredientsPage from "@/app/ingredients/page";
import NewIngredientPage from "@/app/ingredients/new/page";
import NewRecipePage from "@/app/recipes/new/page";
import NewInventoryItemPage from "@/app/inventory/new/page";

function ingredient(o: Partial<IngredientView> = {}): IngredientView {
  return {
    id: "i-rice",
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

const LEAF_PLATES = ingredient({
  id: "i-plates",
  name: "Leaf Plates",
  category: "Disposables",
  unit: "PIECES",
  supply: true,
});

/** The cell under a named column header, found by the header rather than by a fixed index. */
function cellUnder(column: string, rowIndex = 1) {
  const headers = screen.getAllByRole("columnheader").map((h) => h.textContent);
  const index = headers.indexOf(column);
  expect(index, `no "${column}" column on the ingredients table`).toBeGreaterThan(-1);
  return within((screen.getAllByRole("row")[rowIndex] as HTMLTableRowElement).cells[index]);
}

beforeEach(() => {
  authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
  catRef.current = { data: [{ id: "c1", name: "Rice", fastingCompatible: false }], error: null, loading: false };
  ingRef.current = { data: [ingredient()], error: null, loading: false };
  updateMock.mockReset().mockResolvedValue(undefined);
  createMock.mockReset().mockResolvedValue({ id: "ing-new" });
  createItemMock.mockReset().mockResolvedValue("item-1");
  adjustMock.mockReset().mockResolvedValue(undefined);
  pushMock.mockReset();
});

describe("the catalogue says which rows are supplies", () => {
  it("marks a supply, and leaves food unmarked", () => {
    ingRef.current = { data: [LEAF_PLATES, ingredient()], error: null, loading: false };
    render(<IngredientsPage />);

    // Row 1 is Leaf Plates, row 2 is Rice — the list arrives ordered by name from the server and
    // the screen does not re-sort it.
    expect(cellUnder("Type", 1).getByText("Supply")).toBeInTheDocument();
    expect(cellUnder("Type", 2).getByText("Food")).toBeInTheDocument();
  });

  it("does not badge a supply as a dietary matter — Type is not the Ekadashi column", () => {
    ingRef.current = { data: [LEAF_PLATES], error: null, loading: false };
    render(<IngredientsPage />);

    // The two columns were kept distinct on purpose. Being a supply says what a thing IS; Ekadashi
    // says what a fasting rule makes of it, and a leaf plate is not prohibited on Ekadashi — it is
    // simply not food.
    expect(cellUnder("Type").getByText("Supply")).toBeInTheDocument();
    expect(cellUnder("Ekadashi").getByRole("button", { name: /allowed/i })).toBeInTheDocument();
  });
});

describe("the flag can be changed after the fact", () => {
  it("sends the flag on an edit, seeded from the row rather than defaulted", async () => {
    ingRef.current = { data: [LEAF_PLATES], error: null, loading: false };
    render(<IngredientsPage />);

    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));
    // The box opens ticked. `UpdateIngredientInput.supply` is required and the server field is a
    // primitive, so an unseeded box would turn every edited supply back into food on Save without
    // anybody touching it.
    expect(screen.getByLabelText("Supply")).toBeChecked();

    fireEvent.click(screen.getByRole("button", { name: /^save$/i }));
    await waitFor(() =>
      expect(updateMock).toHaveBeenCalledWith(
        "i-plates",
        expect.objectContaining({ supply: true }),
        "test-token"
      )
    );
  });

  it("lets a thing stop being a supply", async () => {
    ingRef.current = { data: [LEAF_PLATES], error: null, loading: false };
    render(<IngredientsPage />);

    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));
    fireEvent.click(screen.getByLabelText("Supply"));
    fireEvent.click(screen.getByRole("button", { name: /^save$/i }));

    await waitFor(() => expect(updateMock).toHaveBeenCalled());
    const [, payload] = updateMock.mock.calls[0];
    // Read out rather than matched with `objectContaining`, which cannot tell an explicit `false`
    // from a key that was never sent — and a key never sent is how this flag would go missing.
    expect(Object.keys(payload)).toContain("supply");
    expect(payload.supply).toBe(false);
  });
});

describe("the create form", () => {
  it("offers the supply box to anyone who may add an ingredient, not only an admin", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<NewIngredientPage />);

    // No new permission (D-1): prohibiting an ingredient is religious policy and is admin-only,
    // saying a thing is a mop is not. Kitchen staff see the supply box and not the Ekadashi one.
    expect(screen.getByRole("checkbox", { name: /supply/i })).toBeInTheDocument();
    expect(screen.queryByLabelText(/ekadashi-prohibited/i)).not.toBeInTheDocument();
  });

  it("sends supply:true when the box is ticked", async () => {
    render(<NewIngredientPage />);
    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Leaf Plates" } });
    fireEvent.change(screen.getByLabelText(/^category$/i), { target: { value: "Disposables" } });
    fireEvent.click(screen.getByRole("checkbox", { name: /supply/i }));
    fireEvent.click(screen.getByRole("button", { name: /add ingredient/i }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith(
        expect.objectContaining({ name: "Leaf Plates", supply: true }),
        "test-token"
      )
    );
  });

  it("says supply:false out loud rather than leaving the key off", async () => {
    render(<NewIngredientPage />);
    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Ghee" } });
    fireEvent.change(screen.getByLabelText(/^category$/i), { target: { value: "Oils" } });
    fireEvent.click(screen.getByRole("button", { name: /add ingredient/i }));

    await waitFor(() => expect(createMock).toHaveBeenCalled());
    const [payload] = createMock.mock.calls[0];
    // An unticked checkbox puts no key in the FormData at all, and the Java field is a primitive,
    // so an omitted key deserialises to `false` — which here is the PERMISSIVE answer: unflagged
    // means food, and food is what reaches the recipe picker.
    expect(Object.keys(payload)).toContain("supply");
    expect(payload.supply).toBe(false);
  });
});

describe("only the recipe picker filters", () => {
  it("does not offer a supply as a recipe ingredient", () => {
    ingRef.current = { data: [ingredient(), LEAF_PLATES], error: null, loading: false };
    render(<NewRecipePage />);

    // Queried by option role and accessible name rather than by text, because an option's label is
    // not always one text node — the inventory picker below writes "Leaf Plates — kept in pieces"
    // across three of them, and a `getByText` there finds nothing while the option is plainly on
    // screen. A negative assertion made that way would pass for the wrong reason.
    const picker = within(screen.getByLabelText(/^ingredient 1$/i));
    expect(picker.getByRole("option", { name: /^rice$/i })).toBeInTheDocument();
    expect(picker.queryByRole("option", { name: /leaf plates/i })).not.toBeInTheDocument();
  });

  it("still offers a supply to the inventory picker, which is the point of D-1", () => {
    ingRef.current = { data: [ingredient(), LEAF_PLATES], error: null, loading: false };
    render(<NewInventoryItemPage />);

    // A temple stocks its leaf plates, counts them, and wants telling when they run low. If this
    // ever goes red because "supplies are filtered out of pickers", the flag has become the
    // parallel catalogue D-1 rejected.
    const picker = within(screen.getByLabelText(/^ingredient$/i));
    expect(picker.getByRole("option", { name: /leaf plates/i })).toBeInTheDocument();
    expect(picker.getByRole("option", { name: /rice/i })).toBeInTheDocument();
  });

  it("puts a supply into inventory through the same call food goes through", async () => {
    ingRef.current = { data: [LEAF_PLATES], error: null, loading: false };
    render(<NewInventoryItemPage />);

    fireEvent.change(screen.getByLabelText(/^ingredient$/i), { target: { value: "i-plates" } });
    fireEvent.click(screen.getByRole("button", { name: /add to inventory/i }));

    await waitFor(() =>
      expect(createItemMock).toHaveBeenCalledWith(
        expect.objectContaining({ ingredientId: "i-plates" }),
        "test-token"
      )
    );
  });
});
