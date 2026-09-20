import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import type { MasterRecipeDetail, RecipeSearchResult } from "@/lib/api";

/**
 * A copy that keeps the temple's own buying setting says so (T-427).
 *
 * <p><strong>What was wrong.</strong> Copying a library recipe marks an ingredient "Not bought"
 * where it creates that ingredient, and deliberately never where the temple already holds its own
 * row — copying is `MANAGE_RECIPES`, which a Kitchen Manager holds, and setting a buying policy is
 * `MANAGE_BUYING_POLICY`, the Temple Admin's alone, so a copy may not set a flag the copier could
 * not set themselves. That refusal is right and `RecipeImportNotBoughtIT` pins it down. What was
 * wrong is that it was silent: the names went into the import's audit entry as a raw JSON key and
 * nowhere a person would look. Fifteen such entries on staging in one week. A Kitchen Manager
 * copied a recipe whose water the book never buys, the temple's water stayed on the shopping list,
 * and nothing on screen ever said so.
 *
 * <p><strong>What is guarded here.</strong> Both screens that copy a recipe, because the two behave
 * differently on success and the message has to survive both: the Recipes list stays put and keeps
 * the row, while the library recipe's own screen navigates to the temple's new copy. The words
 * themselves are asserted character for character, and asserted to be the same words on both
 * screens, because they are written out twice — a shared module for them is a file outside T-427's
 * contract, so this test is what stops the two drifting.
 *
 * <p><strong>It is not a failure and must not be coloured as one.</strong> The copy worked. The
 * colour assertions below are on the rendered class, not on a prop: neutral `bg-sunken`, and none
 * of the three semantic washes.
 */

const { authRef, searchMock, importMock, countMock, closeMatchesMock, recipeRef, pushMock } =
  vi.hoisted(() => ({
    authRef: {
      current: {
        status: "signed-in",
        appUser: { role: "KITCHEN_MANAGER", fullName: "Test Person" },
        getToken: async () => "token",
      },
    },
    searchMock: vi.fn(),
    importMock: vi.fn(),
    countMock: vi.fn(),
    closeMatchesMock: vi.fn(),
    recipeRef: { current: { data: null as MasterRecipeDetail | null, error: null, loading: false } },
    pushMock: vi.fn(),
  }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ id: "m1" }),
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => recipeRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      searchRecipes: searchMock,
      importRecipe: importMock,
      importCloseMatches: closeMatchesMock,
      countIngredientsAddedByImport: countMock,
    },
  };
});

import RecipesPage from "@/app/recipes/page";
import LibraryRecipePage from "@/app/recipes/library/[id]/page";

/** The three washes this message must never wear, as `InlineNotice` writes them. */
const SEMANTIC = ["bg-success-bg", "bg-warning-bg", "bg-danger-bg"];

function copied(notBoughtNotApplied: string[]) {
  return {
    id: "mine-1",
    name: "Majjige",
    ingredientsCreated: 2,
    categoryCreated: false,
    notBoughtNotApplied,
  };
}

function libraryRow(overrides: Partial<RecipeSearchResult> = {}): RecipeSearchResult {
  return {
    origin: "LIBRARY",
    id: "m1",
    name: "Majjige",
    subtitle: null,
    categoryName: "Beverages",
    state: "Karnataka",
    showState: true,
    badge: "Everyday",
    alreadyAdded: false,
    status: null,
    ...overrides,
  };
}

function libraryRecipe(): MasterRecipeDetail {
  return {
    id: "m1",
    name: "majjige",
    displayName: "Majjige",
    subtitle: null,
    categoryKey: "beverages",
    categoryName: "Beverages",
    state: "Karnataka",
    region: null,
    badge: "Everyday",
    yieldText: "100 servings",
    yieldQty: 100,
    yieldUnit: "L",
    perHeadText: null,
    perHeadQty: null,
    perHeadUnit: null,
    indicativeCost: null,
    why: "",
    cateringNote: null,
    noteStart: null,
    noteVessel: null,
    noteSeason: null,
    tags: [],
    serveWith: [],
    ingredients: [
      { name: "Curd", qty: "20 Kg", qtyValue: 20, qtyUnit: "KG", scaled: null, prep: null, notBought: false },
      { name: "Water", qty: "60 L", qtyValue: 60, qtyUnit: "L", scaled: null, prep: null, notBought: true },
    ],
    method: ["Whisk."],
    sourceRef: "",
    alreadyAdded: false,
  };
}

/** The notice this task adds, found by its own words rather than by a test id. */
function keptNotice(): HTMLElement | null {
  return screen.queryByText(/stays on your shopping list|stay on your shopping list/)?.closest("div[role=status]")
    ?? null;
}

beforeEach(() => {
  authRef.current = {
    status: "signed-in",
    appUser: { role: "KITCHEN_MANAGER", fullName: "Test Person" },
    getToken: async () => "token",
  };
  searchMock.mockReset().mockResolvedValue([libraryRow()]);
  importMock.mockReset().mockResolvedValue(copied([]));
  countMock.mockReset().mockResolvedValue({ count: 0 });
  closeMatchesMock.mockReset().mockResolvedValue([]);
  pushMock.mockReset();
  recipeRef.current = { data: libraryRecipe(), error: null, loading: false };
});

describe("the Recipes list, after a copy", () => {
  it("says nothing extra when the copy withheld nothing", async () => {
    render(<RecipesPage />);
    fireEvent.click(await screen.findByRole("button", { name: /add majjige to your recipes/i }));

    // Not vacuous: wait for the copy itself to have gone through and the row to have lost its plus,
    // so "no message" is being asserted about a finished copy and not about a page mid-flight.
    await vi.waitFor(() => expect(importMock).toHaveBeenCalledWith("m1", "token"));
    await vi.waitFor(() =>
      expect(screen.queryByRole("button", { name: /add majjige to your recipes/i })).not.toBeInTheDocument()
    );
    expect(keptNotice()).toBeNull();
    // Nothing of the message's wording anywhere. Matched on the sentence rather than on the words
    // "shopping list", which the sidebar's own nav link carries on every screen in the app.
    expect(screen.queryByText(/on your shopping list/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Not bought/)).not.toBeInTheDocument();
  });

  it("names the one ingredient it kept, in the neutral wash, under the tile it was copied from", async () => {
    importMock.mockResolvedValue(copied(["Water"]));
    render(<RecipesPage />);
    fireEvent.click(await screen.findByRole("button", { name: /add majjige to your recipes/i }));

    const notice = await screen.findByText("Water stays on your shopping list");
    expect(notice.textContent).toBe("Water stays on your shopping list");
    expect(screen.getByText("The library marks it “Not bought”. A Temple Admin can change that in Ingredients."))
      .toBeInTheDocument();

    // The copy worked, so the message is not dressed as a failure: the neutral wash, and none of
    // the three semantic ones.
    const box = notice.closest("div[role=status]") as HTMLElement;
    expect(box.className).toContain("bg-sunken");
    SEMANTIC.forEach((wash) => expect(box.className).not.toContain(wash));

    // It is a cell of the results grid, spanning it, directly after the tile whose plus made the
    // copy — not a banner at the top of a four-hundred-recipe list that a scrolled reader misses.
    const cell = box.closest("li") as HTMLElement;
    expect(cell.className).toContain("col-span-full");
    const tile = screen.getByText("Majjige").closest("li") as HTMLElement;
    expect(tile.nextElementSibling).toBe(cell);
    expect(tile.parentElement).toBe(cell.parentElement);
  });

  it("names three, joined, and the tile keeps its place", async () => {
    importMock.mockResolvedValue(copied(["Ghee", "Rock salt", "Water"]));
    render(<RecipesPage />);
    fireEvent.click(await screen.findByRole("button", { name: /add majjige to your recipes/i }));

    expect(await screen.findByText("Ghee, Rock salt and Water stay on your shopping list")).toBeInTheDocument();
    expect(screen.getByText("The library marks them “Not bought”. A Temple Admin can change that in Ingredients."))
      .toBeInTheDocument();
    expect(screen.getByText("Majjige")).toBeInTheDocument();
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("does not leave the last copy's message standing over a copy that kept nothing", async () => {
    searchMock.mockResolvedValue([libraryRow(), libraryRow({ id: "m2", name: "Panaka" })]);
    importMock.mockResolvedValueOnce(copied(["Water"])).mockResolvedValueOnce(copied([]));
    render(<RecipesPage />);

    fireEvent.click(await screen.findByRole("button", { name: /add majjige to your recipes/i }));
    await screen.findByText("Water stays on your shopping list");

    fireEvent.click(screen.getByRole("button", { name: /add panaka to your recipes/i }));
    await vi.waitFor(() => expect(importMock).toHaveBeenCalledTimes(2));
    await vi.waitFor(() => expect(keptNotice()).toBeNull());
  });
});

describe("a library recipe's own screen, after a copy", () => {
  it("still opens the temple's new recipe when the copy withheld nothing", async () => {
    render(<LibraryRecipePage />);
    fireEvent.click(screen.getByRole("button", { name: "Add to my recipes" }));

    await vi.waitFor(() => expect(pushMock).toHaveBeenCalledWith("/recipes/mine-1"));
    expect(keptNotice()).toBeNull();
  });

  it("holds still and says what it kept, offering the way on", async () => {
    importMock.mockResolvedValue(copied(["Water"]));
    render(<LibraryRecipePage />);
    fireEvent.click(screen.getByRole("button", { name: "Add to my recipes" }));

    const notice = await screen.findByText("Water stays on your shopping list");
    expect(screen.getByText("The library marks it “Not bought”. A Temple Admin can change that in Ingredients."))
      .toBeInTheDocument();

    // The navigation is held, not lost: the message carries it as a link the person chooses.
    expect(pushMock).not.toHaveBeenCalled();
    const box = notice.closest("div[role=status]") as HTMLElement;
    expect(within(box).getByRole("link", { name: "Open the recipe" })).toHaveAttribute(
      "href",
      "/recipes/mine-1"
    );

    // And the copy having gone through, the button stops offering one the server would refuse.
    expect(screen.queryByRole("button", { name: "Add to my recipes" })).not.toBeInTheDocument();
    expect(screen.getByText("Already in your recipes")).toBeInTheDocument();

    expect(box.className).toContain("bg-sunken");
    SEMANTIC.forEach((wash) => expect(box.className).not.toContain(wash));
  });

  it("says it after the close-match answers too, and closes the question", async () => {
    closeMatchesMock.mockResolvedValue([
      { libraryName: "Curd", note: null, existingIngredientId: "ing-c", existingIngredientName: "Curd, fresh" },
    ]);
    importMock.mockResolvedValue(copied(["Water"]));
    render(<LibraryRecipePage />);

    fireEvent.click(screen.getByRole("button", { name: "Add to my recipes" }));
    const dialog = await screen.findByRole("dialog", { name: "Did you mean this ingredient?" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Use Curd, fresh" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Add to my recipes" }));

    await screen.findByText("Water stays on your shopping list");
    // The question used to be unmounted by the navigation, which no longer happens here.
    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(pushMock).not.toHaveBeenCalled();
  });
});

describe("the two screens", () => {
  /*
    The words live twice, once in each page, because a module of their own would be a file outside
    this task's contract. This is what stops the two copies drifting: render both, for the same
    names, and compare the strings rather than eyeballing them.
  */
  it("say the same words for the same kept ingredients", async () => {
    importMock.mockResolvedValue(copied(["Ghee", "Water"]));

    const list = render(<RecipesPage />);
    fireEvent.click(await screen.findByRole("button", { name: /add majjige to your recipes/i }));
    const fromList = (await screen.findByText(/stay on your shopping list/)).closest(
      "div[role=status]"
    ) as HTMLElement;
    const listWords = fromList.textContent;
    list.unmount();

    const detail = render(<LibraryRecipePage />);
    fireEvent.click(screen.getByRole("button", { name: "Add to my recipes" }));
    const fromDetail = (await screen.findByText(/stay on your shopping list/)).closest(
      "div[role=status]"
    ) as HTMLElement;
    // The detail screen's notice carries the "Open the recipe" link the list's does not, so the
    // link's own text is taken off before the words are compared.
    const detailWords = fromDetail.textContent?.replace("Open the recipe", "");
    detail.unmount();

    expect(listWords).toBe(
      "Ghee and Water stay on your shopping list" +
        "The library marks them “Not bought”. A Temple Admin can change that in Ingredients."
    );
    expect(detailWords).toBe(listWords);
  });
});
