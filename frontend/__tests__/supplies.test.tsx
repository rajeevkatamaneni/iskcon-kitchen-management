import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { IngredientView, RecipeCategory } from "@/lib/api";

/*
  T-089 — Supplies is a screen of its own, over the catalogue D-1 already built.

  Rajeev ruled on 2026-09-08 that Supplies gets a menu item under Kitchen, after Ingredients, and
  drew the line himself against a proposal of "repaired versus replaced", which he rejected: a
  plastic stool is not repairable and that does not make it a supply. The rule is CONSUMPTION.
  LPG, kerosene, cleaning liquid, bulbs and brooms are used up by use and are supplies; ladders,
  extension boxes and plastic stools are not consumed and are equipment, whatever they cost.

  Nothing about a row moved to build that. D-1 (V99) had already settled that a supply is an
  `ingredients` row carrying `is_supply`, because a leaf plate is bought from a vendor, received,
  stored, used up and reordered exactly as rice is — a parallel `supply_items` table would have
  duplicated the entire inventory chain to express one boolean. A menu item is a fact about
  navigation, not about storage, so the split is two screens over one endpoint and there is no
  migration behind it. The consequence worth asserting is the third test below: an ingredient a
  temple flagged as a supply months ago appears on the new screen with nothing re-entered, because
  nothing about how it is stored changed.

  The consequence this file has existed to hold down since T-023 is still here and still NEGATIVE.
  Exactly one picker filters — the recipe picker, because a mop is not an ingredient of anything —
  and every other picker must keep offering supplies, or the flag has quietly turned into the
  second catalogue D-1 refused. Splitting the SCREENS is not splitting the pickers, and the two
  tests at the bottom are what says so. The ingredient-request picker (which reaches the same
  endpoint by a different code path) is asserted in ingredient-request-new.test.tsx.

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
// and hands all of them the same list. Ingredients and Supplies pass the SAME fetcher and take
// different halves of the answer, which is the whole shape of this task.
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
      // The stock-value pre-fill (R-ING-3). Nothing to suggest, and never a real request from jsdom.
      getStockValueSuggestion: vi.fn().mockResolvedValue({ pricePerUnit: null, source: null }),
    },
  };
});

import IngredientsPage from "@/app/ingredients/page";
import NewIngredientPage from "@/app/ingredients/new/page";
import SuppliesPage from "@/app/supplies/page";
import NewSupplyPage from "@/app/supplies/new/page";
import NewRecipePage from "@/app/recipes/new/page";
import NewInventoryItemPage from "@/app/inventory/new/page";

function ingredient(o: Partial<IngredientView> = {}): IngredientView {
  return {
    id: "i-rice",
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

const LEAF_PLATES = ingredient({
  id: "i-plates",
  name: "Leaf Plates",
  category: "Disposables",
  unit: "PIECES",
  supply: true,
});

/**
 * A row exactly as V99 left it, months before this screen existed: flagged a supply through the
 * checkbox that used to sit on the ingredient form, with no alias and nothing else set.
 *
 * <p>It carries no field this task added, because this task added none. That is the point of the
 * "no manual step" test below — if it ever needs a property invented here to render, the split has
 * quietly become a schema change and every row already in a temple's catalogue needs revisiting.
 */
const ALREADY_FLAGGED = ingredient({
  id: "i-lpg",
  name: "LPG",
  category: "Fuel",
  unit: "PIECES",
  supply: true,
  createdAt: "2026-08-14T00:00:00Z",
});

/** The names in a table body, in the order the screen renders them. */
function rowNames(): string[] {
  return screen
    .getAllByRole("row")
    .slice(1)
    .map((r) => ((r as HTMLTableRowElement).cells[0]?.textContent ?? "").trim());
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

describe("one catalogue, split across two screens", () => {
  it("puts a supply on Supplies and not among Ingredients", () => {
    ingRef.current = { data: [LEAF_PLATES, ingredient()], error: null, loading: false };

    const { unmount } = render(<SuppliesPage />);
    expect(rowNames()).toEqual(["Leaf Plates"]);
    unmount();

    render(<IngredientsPage />);
    // Read as a whole list rather than as a `queryByText` miss: a negative that only says "not
    // found" passes just as happily against a screen that rendered nothing at all.
    expect(rowNames()).toEqual(["Rice"]);
  });

  it("puts a food ingredient among Ingredients and not on Supplies", () => {
    ingRef.current = { data: [LEAF_PLATES, ingredient()], error: null, loading: false };

    const { unmount } = render(<IngredientsPage />);
    expect(rowNames()).toEqual(["Rice"]);
    unmount();

    render(<SuppliesPage />);
    expect(rowNames()).toEqual(["Leaf Plates"]);
  });

  it("shows an ingredient flagged a supply months ago, with no manual step", () => {
    // The fixture is a V99-shaped row and nothing more — see ALREADY_FLAGGED. It renders here
    // because this screen reads `is_supply`, which that row has carried since the day somebody
    // ticked the box, and because no migration moved it anywhere.
    ingRef.current = { data: [ALREADY_FLAGGED], error: null, loading: false };
    render(<SuppliesPage />);

    expect(rowNames()).toEqual(["LPG"]);
    expect(screen.getByText("Fuel")).toBeInTheDocument();
    expect(screen.queryByText(/no supplies yet/i)).not.toBeInTheDocument();
  });

  it("tells the empty screens apart — a temple with only supplies has no ingredients", () => {
    // The failure this catches is a header over no rows: /ingredients counting the WHOLE catalogue
    // to decide whether it is empty would draw its table for a temple whose every row is a supply.
    ingRef.current = { data: [ALREADY_FLAGGED], error: null, loading: false };
    render(<IngredientsPage />);

    expect(screen.getByText(/no ingredients yet/i)).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  /*
    T-121 checked this screen for the same redundancy Rajeev found on /ingredients — a Type column
    printing "Supply" on every row — and there was none to remove. `/supplies` has been Name,
    Category, Unit, "Also called", Actions since T-089 built it. Asserted rather than reported,
    because "I looked and it was not there" is not a fact anybody can re-check in six months.

    Note what is NOT asserted here: that the `supply` field is gone from the payload. It is not, and
    it must not be. This screen exists BECAUSE of that flag — it is the `supply` half of the one
    catalogue — so removing the field to match the removed column would delete the screen.
  */
  it("has no Type column either, and never did", () => {
    ingRef.current = { data: [LEAF_PLATES], error: null, loading: false };
    render(<SuppliesPage />);
    expect(screen.getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      // Flexible columns first, fixed ones grouped on the right (the table rule, T-228). The
      // actions heading is still there for a screen reader, only hidden from sight.
      "Name",
      "Category",
      "Also called",
      "Unit",
      "Actions",
    ]);
    expect(screen.queryByText(/^supply$/i)).not.toBeInTheDocument();
  });

  it("says on Supplies where a stool goes, because that is the rule people get wrong", () => {
    // Rajeev's line, and it is not the intuitive one: a plastic stool is cheap, breakable and
    // unrepairable, and is still equipment, because using it does not consume it.
    ingRef.current = { data: [], error: null, loading: false };
    render(<SuppliesPage />);

    expect(screen.getByText(/belong under Equipment/i)).toBeInTheDocument();
  });
});

/*
  T-441 — the row no longer moves, and no longer opens either.

  Rajeev on 2026-09-20, asked about the "Move to Supplies" box the Ingredients editing row carried:
  "Not needed. they can delete and recreate as a supply." Its mirror here ("Move to Ingredients")
  went with it — the argument reads the same in both directions — and the editing row that held both
  went with the Edit button, because he also asked for the catalogue to follow Staff: the name opens
  the thing in view mode, and Edit is on that page.

  So the four tests that used to live here (a supply moving to Ingredients and back, each with the
  `supply` key read out of the payload rather than matched loosely) are gone with the feature. What
  replaces them is below, plus the same `supply` assertion on the edit screen's save — see "keeps a
  supply a supply on save" in `__tests__/ingredient-edit.test.tsx`, which is where a payload is now
  built. The flag itself is untouched and still decides which of the two screens a row appears on,
  which the first describe in this file is what holds down.
*/
describe("changing a supply starts at its name", () => {
  it("links the name to the supply's own page, which is the ingredient page (D-1)", () => {
    ingRef.current = { data: [LEAF_PLATES], error: null, loading: false };
    render(<SuppliesPage />);
    expect(screen.getByRole("link", { name: "Leaf Plates" }).getAttribute("href")).toBe(
      "/ingredients/i-plates"
    );
  });

  it("offers no Edit button on the row", () => {
    ingRef.current = { data: [LEAF_PLATES], error: null, loading: false };
    render(<SuppliesPage />);
    const row = screen.getAllByRole("row")[1] as HTMLTableRowElement;
    expect(within(row).queryByRole("button", { name: /^edit$/i })).not.toBeInTheDocument();
    // Delete stays: removing a supply is not a change to it. It is a trash can rather than the
    // word since 2026-09-21, so the row carries no visible text for it and the accessible name —
    // which names the supply — is what is asserted.
    expect(within(row).getAllByRole("button").map((b) => b.getAttribute("aria-label"))).toEqual([
      "Delete Leaf Plates",
    ]);
  });

  it("offers no move box on either screen, in either direction", () => {
    ingRef.current = { data: [LEAF_PLATES, ingredient()], error: null, loading: false };
    render(<SuppliesPage />);
    expect(screen.queryByLabelText(/Move to Ingredients/i)).not.toBeInTheDocument();
    expect(screen.queryAllByRole("checkbox")).toHaveLength(0);
  });

  it("changes nothing from the supplies table itself", () => {
    ingRef.current = { data: [LEAF_PLATES], error: null, loading: false };
    render(<SuppliesPage />);
    expect(screen.queryAllByRole("textbox")).toHaveLength(0);
    expect(screen.queryAllByRole("combobox")).toHaveLength(0);
    expect(updateMock).not.toHaveBeenCalled();
  });
});

describe("the two create forms", () => {
  it("sends supply:true from /supplies/new, with no box to tick", async () => {
    render(<NewSupplyPage />);
    // The checkbox that used to carry this is gone (T-089): the screen the person chose is the
    // answer, so there is nothing here to get wrong and nothing to leave unticked by accident.
    expect(screen.queryByRole("checkbox", { name: /supply/i })).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Dishwashing Liquid" } });
    fireEvent.change(screen.getByLabelText(/^category$/i), { target: { value: "Cleaning" } });
    fireEvent.click(screen.getByRole("button", { name: /add supply/i }));

    await waitFor(() => expect(createMock).toHaveBeenCalled());
    const [payload] = createMock.mock.calls[0];
    expect(Object.keys(payload)).toContain("supply");
    expect(payload.supply).toBe(true);
    expect(payload.name).toBe("Dishwashing Liquid");
  });

  it("lands back on Supplies afterwards, not on Ingredients", async () => {
    render(<NewSupplyPage />);
    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Kerosene" } });
    fireEvent.change(screen.getByLabelText(/^category$/i), { target: { value: "Fuel" } });
    fireEvent.click(screen.getByRole("button", { name: /add supply/i }));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/supplies?added=Kerosene"));
  });

  it("offers a supply to anyone who may add an ingredient, not only an admin", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<NewSupplyPage />);

    // No new permission (D-1): prohibiting an ingredient is religious policy and is admin-only,
    // saying a thing is a mop is not. What kitchen staff must NOT see here is the Ekadashi box,
    // and neither must an admin — a fasting rule has nothing to say about hand soap.
    expect(screen.getByRole("form", { name: /add a supply/i })).toBeInTheDocument();
    expect(screen.queryByLabelText(/ekadashi-prohibited/i)).not.toBeInTheDocument();
  });

  it("keeps the Ekadashi box off the supply form even for an admin", () => {
    const { unmount } = render(<NewSupplyPage />);
    expect(screen.queryByLabelText(/ekadashi-prohibited/i)).not.toBeInTheDocument();
    unmount();

    // And is still on the food form, so the absence above is this screen's doing rather than the
    // admin check having quietly broken for everybody.
    render(<NewIngredientPage />);
    expect(screen.getByLabelText(/ekadashi-prohibited/i)).toBeInTheDocument();
  });

  it("says supply:false out loud from /ingredients/new rather than leaving the key off", async () => {
    render(<NewIngredientPage />);
    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Ghee" } });
    fireEvent.change(screen.getByLabelText(/^category$/i), { target: { value: "Oils" } });
    fireEvent.click(screen.getByRole("button", { name: /add ingredient/i }));

    await waitFor(() => expect(createMock).toHaveBeenCalled());
    const [payload] = createMock.mock.calls[0];
    // The Java field is a primitive, so an omitted key deserialises to `false` — which here is the
    // PERMISSIVE answer: unflagged means food, and food is what reaches the recipe picker. Right
    // by luck is not right, so the key is asserted present as well as false.
    expect(Object.keys(payload)).toContain("supply");
    expect(payload.supply).toBe(false);
  });
});

describe("splitting the screens did not split the pickers", () => {
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
    // parallel catalogue D-1 rejected — and T-089 giving them a menu item of their own is exactly
    // the change most likely to be mistaken for permission to do that.
    //
    // The field is "Ingredient or supply" since T-432, and the list is grouped under Ingredients
    // and Supplies. Rajeev's note on that screen was that the picker "shows ingredients and
    // supplies merged into one list" — which it always did, and on purpose; what was missing was
    // anything on the screen saying so. **Which rows are offered did not change**, and that is what
    // this test guards. The group is asserted as well as the option, because the naming is now the
    // only thing telling a storekeeper the leaf plates are in there at all.
    const picker = within(screen.getByLabelText(/ingredient or supply/i));
    expect(picker.getByRole("option", { name: /leaf plates/i })).toBeInTheDocument();
    expect(picker.getByRole("option", { name: /rice/i })).toBeInTheDocument();
    expect(within(picker.getByRole("group", { name: "Supplies" }))
        .getByRole("option", { name: /leaf plates/i })).toBeInTheDocument();
  });

  it("puts a supply into inventory through the same call food goes through", async () => {
    ingRef.current = { data: [LEAF_PLATES], error: null, loading: false };
    render(<NewInventoryItemPage />);

    // "Ingredient or supply" since T-432 — the field was renamed, not narrowed. A catalogue holding
    // only supplies is offered as one flat list rather than under a heading with nothing beside it,
    // so there is no group to reach through here.
    fireEvent.change(screen.getByLabelText(/ingredient or supply/i), { target: { value: "i-plates" } });
    fireEvent.click(screen.getByRole("button", { name: /add to inventory/i }));

    await waitFor(() =>
      expect(createItemMock).toHaveBeenCalledWith(
        expect.objectContaining({ ingredientId: "i-plates" }),
        "test-token"
      )
    );
  });
});
