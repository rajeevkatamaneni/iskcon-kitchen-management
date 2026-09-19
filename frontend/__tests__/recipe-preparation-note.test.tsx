import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { IngredientView, RecipeCategory, RecipeDetail } from "@/lib/api";

/*
 * R-DUP-1 (docs/work/PROCUREMENT-REQUIREMENTS.md §9A): the ingredient is separate from its
 * preparation. A recipe line carries a preparation note ("slit", "halved"), the recipe form edits it,
 * and every screen that shows the line shows it as "Green chilli · slit".
 *
 * Two screens are read here: the form, rendered on its own with a Save button outside it as both
 * recipe screens do, and the recipe page. The recipe peek is covered by the same join and checked on
 * the local stack (see docs/work/proof/T-250.md).
 */
const { catFn, ingFn, recipeRef } = vi.hoisted(() => ({
  catFn: () => {},
  ingFn: () => {},
  recipeRef: { current: null as unknown },
}));

const CATEGORIES: RecipeCategory[] = [{ id: "c1", name: "Rasam", fastingCompatible: false }];
const INGREDIENTS: IngredientView[] = [
  { id: "i1", name: "Green chilli", category: "Vegetables", unit: "GM", ekadashiProhibited: false, supply: false, libraryDerived: false, aliases: [], createdAt: "", packSizes: [], marketRate: null, marketRateOn: null, marketRateSource: null },
  { id: "i2", name: "Tamarind", category: "Spices", unit: "KG", ekadashiProhibited: false, supply: false, libraryDerived: false, aliases: [], createdAt: "", packSizes: [], marketRate: null, marketRateOn: null, marketRateSource: null },
];

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn() }),
  useParams: () => ({ id: "r1" }),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    status: "signed-in",
    appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" },
    getToken: async () => "test-token",
  }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listRecipeCategories: catFn, listIngredients: ingFn } };
});
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: unknown) =>
    fetcher === catFn
      ? { data: CATEGORIES, error: null, loading: false }
      : fetcher === ingFn
        ? { data: INGREDIENTS, error: null, loading: false }
        : recipeRef.current,
}));

import { RecipeForm } from "@/components/RecipeForm";
import RecipeDetailPage from "@/app/recipes/[id]/page";

const submitMock = vi.fn();

function recipe(overrides: Partial<RecipeDetail> = {}): RecipeDetail {
  return {
    id: "r1", name: "Rasam", categoryId: "c1", categoryName: "Rasam", fastingCompatible: false,
    baseYieldQty: 10, baseYieldUnit: "L", method: null, notes: null, regionTag: null, yieldNote: null,
    perHeadQty: null, perHeadUnit: null, subtitle: null, badge: null, indicativeCost: null, why: null,
    cateringNote: null, subRegion: null, noteStart: null, noteVessel: null, noteSeason: null, tags: [],
    serveWith: [], masterRecipeId: null, status: "ACTIVE", version: 1,
    ingredients: [
      { ingredientId: "i1", ingredientName: "Green chilli", quantity: 20, unit: "GM", preparationNote: "slit" },
      { ingredientId: "i2", ingredientName: "Tamarind", quantity: 2, unit: "KG", preparationNote: null },
    ],
    createdAt: "2026-09-01T00:00:00Z",
    ...overrides,
  };
}

function renderForm(initial?: RecipeDetail) {
  return render(
    <>
      <RecipeForm initial={initial} formId="recipe" busy={false} error={null} onSubmit={submitMock} />
      <button type="submit" form="recipe">Save</button>
    </>,
  );
}

const note = (n: number) => screen.getByRole("textbox", { name: `Preparation ${n}` }) as HTMLInputElement;

beforeEach(() => {
  submitMock.mockReset();
  recipeRef.current = { data: recipe(), error: null, loading: false };
});

describe("the recipe form's preparation note (R-DUP-1)", () => {
  it("gives every line a preparation box, straight after the ingredient", () => {
    renderForm();
    expect(note(1)).toHaveValue("");
    expect(note(1)).toHaveAttribute("placeholder", "Preparation, e.g. slit");
    // Reads in the order it prints: ingredient, then how it is prepared, then how much.
    const ingredient = screen.getByRole("combobox", { name: "Ingredient 1" });
    const quantity = screen.getByRole("spinbutton", { name: "Quantity 1" });
    expect(ingredient.compareDocumentPosition(note(1)) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(note(1).compareDocumentPosition(quantity) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "+ Add ingredient" }));
    expect(note(2)).toHaveValue("");
  });

  it("opens an existing recipe with each line's note in its box", () => {
    renderForm(recipe());
    expect(note(1)).toHaveValue("slit");
    expect(note(2)).toHaveValue("");
  });

  it("sends the note trimmed, and leaves a blank one out", () => {
    renderForm(recipe());
    fireEvent.change(note(1), { target: { value: "  chopped fine " } });
    fireEvent.change(note(2), { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(submitMock).toHaveBeenCalledTimes(1);
    const lines = submitMock.mock.calls[0][0].ingredients;
    expect(lines[0]).toEqual({ ingredientId: "i1", quantity: 20, unit: "GM", preparationNote: "chopped fine" });
    // Blank is "no note": sent as nothing, which the server stores as null.
    expect(lines[1].preparationNote).toBeUndefined();
  });
});

describe("the recipe page (R-DUP-1)", () => {
  it("shows a line with its note as \"Green chilli · slit\", and a line without one as its name alone", () => {
    render(<RecipeDetailPage />);
    expect(screen.getByRole("cell", { name: "Green chilli · slit" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Tamarind" })).toHaveTextContent(/^Tamarind$/);
  });

  it("keeps two lines of one ingredient apart when they are prepared differently", () => {
    recipeRef.current = {
      data: recipe({
        ingredients: [
          { ingredientId: "i1", ingredientName: "Coconut", quantity: 2, unit: "KG", preparationNote: "grated" },
          { ingredientId: "i1", ingredientName: "Coconut", quantity: 1, unit: "KG", preparationNote: "fresh grated" },
        ],
      }),
      error: null,
      loading: false,
    };
    render(<RecipeDetailPage />);
    expect(screen.getByRole("cell", { name: "Coconut · grated" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Coconut · fresh grated" })).toBeInTheDocument();
  });
});
