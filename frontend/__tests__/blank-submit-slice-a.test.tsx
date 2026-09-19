import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { IngredientView, RecipeCategory, RecipeDetail } from "@/lib/api";

/*
 * T-161, slice A of the blank-required-fields wave. Most forms in the slice have a screen test of
 * their own and their blank-submit test lives there. Editing a recipe had none, so its test is here.
 *
 * Rajeev's ruling, 2026-09-11: "Required fields should carry `required` on the element and if left
 * unfilled, we should at least show 'Required' in red on form submit. Ideally, we should say
 * 'Quantity is required' OR 'Note is required'."
 *
 * The screen makes three authed queries: the recipe itself (a callback made on the page, so a new
 * function each render), the categories and the ingredients. The stub tells them apart by the two
 * fetchers it can name and treats anything else as the recipe.
 */
const { catFn, ingFn, authRef, recipeRef, updateMock, pushMock } = vi.hoisted(() => ({
  catFn: () => {},
  ingFn: () => {},
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", fullName: "Test Person" } } as {
      status: string;
      appUser: { role: string; fullName?: string } | null;
    },
  },
  recipeRef: { current: null as RecipeDetail | null },
  updateMock: vi.fn(),
  pushMock: vi.fn(),
}));

const CATEGORIES: RecipeCategory[] = [{ id: "c1", name: "Rice", fastingCompatible: false }];
const INGREDIENTS: IngredientView[] = [
  { id: "i1", name: "Rice", category: "Grains", unit: "KG", ekadashiProhibited: false, supply: false, libraryDerived: false, aliases: [], createdAt: "" },
];

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn() }),
  useParams: () => ({ id: "r1" }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, listRecipeCategories: catFn, listIngredients: ingFn, updateRecipe: updateMock },
  };
});
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: unknown) =>
    fetcher === catFn
      ? { data: CATEGORIES, error: null, loading: false }
      : fetcher === ingFn
        ? { data: INGREDIENTS, error: null, loading: false }
        : { data: recipeRef.current, error: null, loading: false },
}));

import EditRecipePage from "@/app/recipes/[id]/edit/page";

function recipe(): RecipeDetail {
  return {
    id: "r1",
    name: "Khichdi",
    categoryId: "c1",
    categoryName: "Rice",
    fastingCompatible: true,
    baseYieldQty: 100,
    baseYieldUnit: "KG",
    method: null,
    notes: null,
    regionTag: null,
    yieldNote: null,
    perHeadQty: null,
    perHeadUnit: null,
    subtitle: null,
    badge: null,
    indicativeCost: null,
    why: null,
    cateringNote: null,
    subRegion: null,
    noteStart: null,
    noteVessel: null,
    noteSeason: null,
    tags: [],
    serveWith: [],
    masterRecipeId: null,
    status: "ACTIVE",
    version: 1,
    ingredients: [{ ingredientId: "i1", ingredientName: "Rice", quantity: 2, unit: "KG" }],
    createdAt: "2026-08-10T00:00:00Z",
  };
}

/**
 * The sentence Form puts beside a refused box. Checked three ways so that "beside" means something:
 * the box is marked invalid, it is described by that very sentence, and the sentence's slot sits
 * straight after the box, or after the label wrapping it.
 */
function expectSaidBeside(box: HTMLElement, sentence: string) {
  const said = screen.getByText(sentence);
  expect(box).toHaveAttribute("aria-invalid", "true");
  expect(box.getAttribute("aria-describedby")?.split(" ")).toContain(said.id);
  expect((box.closest("label") ?? box).nextElementSibling).toBe(said.parentElement);
}

describe("editing a recipe", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", fullName: "Test Person" } };
    recipeRef.current = recipe();
    updateMock.mockReset().mockResolvedValue(undefined);
    pushMock.mockReset();
  });

  it("names a cleared name and a cleared amount made in red beside their boxes, and saves nothing", () => {
    render(<EditRecipePage />);
    expect(screen.getByLabelText(/^name$/i)).toHaveValue("Khichdi");

    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "" } });
    fireEvent.change(screen.getByLabelText(/^how much this recipe makes$/i), { target: { value: "" } });
    // The header button, outside the form, which reaches it by form="edit-recipe".
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    expectSaidBeside(screen.getByLabelText(/^name$/i), "Name is required");
    expectSaidBeside(screen.getByLabelText(/^how much this recipe makes$/i), "How much this recipe makes is required");
    expect(updateMock).not.toHaveBeenCalled();
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("still saves once the boxes are put right, so the refusal is not a dead end", async () => {
    render(<EditRecipePage />);

    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));
    expect(screen.getByText("Name is required")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Sunday Khichdi" } });
    expect(screen.queryByText("Name is required")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await vi.waitFor(() => expect(updateMock).toHaveBeenCalledTimes(1));
    expect(updateMock.mock.calls[0][0]).toBe("r1");
    expect(updateMock.mock.calls[0][1]).toMatchObject({ name: "Sunday Khichdi", categoryId: "c1", baseYieldQty: 100 });
  });
});
