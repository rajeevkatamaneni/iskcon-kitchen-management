import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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
        { id: "i1", name: "Rice", category: "Grains", unit: "KG", ekadashiProhibited: false, supply: false, aliases: [], createdAt: "" },
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

  /*
    D-18 deleted the override the form used to collect and the marker the ingredient picker used to
    print beside a forbidden name. Asserted against what the form shows and what it sends, not
    against the props it passes: a field that is no longer rendered has no label to find, and a key
    that is no longer built does not appear among the payload\u2019s own keys.

    `Object.keys` rather than `objectContaining` for the second half, because `objectContaining`
    cannot tell a missing property from one explicitly set to undefined.
  */
  it("collects no override reason, and marks no ingredient forbidden", async () => {
    render(<NewRecipePage />);

    expect(screen.queryByLabelText(/override/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/prohibited/i)).not.toBeInTheDocument();
    expect(
      within(screen.getByLabelText(/^ingredient 1$/i)).getByRole("option", { name: /rice/i }),
    ).toHaveTextContent(/^Rice$/);

    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Khichdi" } });
    fireEvent.change(screen.getByLabelText(/^category$/i), { target: { value: "c1" } });
    fireEvent.change(screen.getByLabelText(/^ingredient 1$/i), { target: { value: "i1" } });
    fireEvent.change(screen.getByLabelText(/^quantity 1$/i), { target: { value: "2" } });
    fireEvent.click(screen.getByRole("button", { name: /create recipe/i }));

    await waitFor(() => expect(createMock).toHaveBeenCalled());
    expect(Object.keys(createMock.mock.calls[0][0]).filter((k) => /override/i.test(k))).toEqual([]);
  });

  it("refuses a role without recipe access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", fullName: "Test Person" } };
    render(<NewRecipePage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
