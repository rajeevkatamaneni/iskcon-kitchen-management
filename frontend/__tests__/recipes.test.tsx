import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import type { RecipeCategory, RecipeSummary } from "@/lib/api";

// The list makes two authed queries (categories, recipes). Replace listRecipeCategories with a
// sentinel so the useAuthedQuery mock can tell them apart by the fetcher's identity.
const { catFn, authRef, catRef, recipesRef } = vi.hoisted(() => ({
  catFn: () => {},
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", fullName: "Test Person" } } as {
      status: string;
      appUser: { role: string; fullName?: string } | null;
    },
  },
  catRef: { current: { data: [] as RecipeCategory[] | null, error: null, loading: false } },
  recipesRef: { current: { data: [] as RecipeSummary[] | null, error: null, loading: false } },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listRecipeCategories: catFn } };
});
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: unknown) => (fetcher === catFn ? catRef.current : recipesRef.current),
}));

import RecipesPage from "@/app/recipes/page";

function recipe(overrides: Partial<RecipeSummary>): RecipeSummary {
  return {
    id: "r1",
    name: "Khichdi",
    categoryName: "Rice",
    fastingCompatible: false,
    baseYieldQty: 100,
    baseYieldUnit: "SERVINGS",
    status: "ACTIVE",
    sattvicOverridden: false,
    ...overrides,
  };
}

describe("recipe browse", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", fullName: "Test Person" } };
    catRef.current = {
      data: [
        { id: "c1", name: "Rice", fastingCompatible: false },
        { id: "c2", name: "Ekadashi", fastingCompatible: true },
      ],
      error: null,
      loading: false,
    };
    recipesRef.current = {
      data: [recipe({ id: "r1", name: "Khichdi" }), recipe({ id: "r2", name: "Aam Ras", categoryName: "Sweets" })],
      error: null,
      loading: false,
    };
  });

  it("lists recipes and the category chips", () => {
    render(<RecipesPage />);
    expect(screen.getByRole("heading", { name: /recipes/i })).toBeInTheDocument();
    expect(screen.getByText("Khichdi")).toBeInTheDocument();
    expect(screen.getByText("Aam Ras")).toBeInTheDocument();
    const chips = screen.getByRole("group", { name: /filter by category/i });
    expect(within(chips).getByRole("button", { name: /^all$/i })).toBeInTheDocument();
    expect(within(chips).getByRole("button", { name: /ekadashi/i })).toBeInTheDocument();
  });

  it("filters by name as you type", () => {
    render(<RecipesPage />);
    fireEvent.change(screen.getByLabelText(/search recipes by name/i), { target: { value: "aam" } });
    expect(screen.getByText("Aam Ras")).toBeInTheDocument();
    expect(screen.queryByText("Khichdi")).not.toBeInTheDocument();
  });

  it("shows an empty state when a search matches nothing", () => {
    render(<RecipesPage />);
    fireEvent.change(screen.getByLabelText(/search recipes by name/i), { target: { value: "zzz" } });
    expect(screen.getByText(/no recipes found/i)).toBeInTheDocument();
  });

  it("refuses a role without recipe access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", fullName: "Test Person" } };
    render(<RecipesPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /^recipes$/i })).not.toBeInTheDocument();
  });
});
