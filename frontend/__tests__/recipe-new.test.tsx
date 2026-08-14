import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { IngredientView, RecipeCategory } from "@/lib/api";

// RecipeForm makes two authed queries (categories, ingredients); discriminate by fetcher identity.
const { catFn, ingFn, authRef, catRef, ingRef, createMock, pushMock } = vi.hoisted(() => ({
  catFn: () => {},
  ingFn: () => {},
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", fullName: "Test Person" } } as {
      status: string;
      appUser: { role: string; fullName?: string } | null;
    },
  },
  catRef: { current: { data: [] as RecipeCategory[] | null, error: null, loading: false } },
  ingRef: { current: { data: [] as IngredientView[] | null, error: null, loading: false } },
  createMock: vi.fn(),
  pushMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: pushMock, replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, listRecipeCategories: catFn, listIngredients: ingFn, createRecipe: createMock },
  };
});
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: unknown) =>
    fetcher === catFn ? catRef.current : fetcher === ingFn ? ingRef.current : { data: null, error: null, loading: false },
}));

import NewRecipePage from "@/app/recipes/new/page";

describe("new recipe", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", fullName: "Test Person" } };
    catRef.current = { data: [{ id: "c1", name: "Rice", fastingCompatible: false }], error: null, loading: false };
    ingRef.current = {
      data: [
        { id: "i1", name: "Rice", category: "Grains", unit: "KG", sattvicProhibited: false, aliases: [], createdAt: "" },
      ],
      error: null,
      loading: false,
    };
    createMock.mockReset().mockResolvedValue({ id: "r-new" });
    pushMock.mockReset();
  });

  it("creates a recipe from the form and navigates to it", async () => {
    render(<NewRecipePage />);
    expect(screen.getByRole("heading", { name: /new recipe/i })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Khichdi" } });
    fireEvent.change(screen.getByLabelText(/^category$/i), { target: { value: "c1" } });
    fireEvent.change(screen.getByLabelText(/^ingredient 1$/i), { target: { value: "i1" } });
    fireEvent.change(screen.getByLabelText(/^quantity 1$/i), { target: { value: "2" } });

    fireEvent.click(screen.getByRole("button", { name: /create recipe/i }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "Khichdi",
          categoryId: "c1",
          baseYieldQty: 100,
          ingredients: [{ ingredientId: "i1", quantity: 2, unit: "KG" }],
        }),
        "test-token"
      )
    );
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/recipes/r-new"));
  });

  it("refuses a role without recipe access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", fullName: "Test Person" } };
    render(<NewRecipePage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
