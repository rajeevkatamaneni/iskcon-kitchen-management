import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { RecipeDetail, TranslatedRecipe } from "@/lib/api";

const { authRef, recipeRef, translateMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN" } } as {
      status: string;
      appUser: { role: string } | null;
    },
  },
  recipeRef: { current: { data: null as RecipeDetail | null, error: null, loading: false } },
  translateMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn() }),
  useParams: () => ({ id: "r1" }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => recipeRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, translateRecipe: translateMock } };
});

import RecipeDetailPage from "@/app/recipes/[id]/page";

function detail(overrides: Partial<RecipeDetail> = {}): RecipeDetail {
  return {
    id: "r1",
    name: "Khichdi",
    categoryId: "c1",
    categoryName: "Rice",
    fastingCompatible: true,
    baseYieldQty: 100,
    baseYieldUnit: "SERVINGS",
    method: "Wash the rice.\nCook until soft.",
    notes: "The default lunch.",
    regionTag: "Karnataka",
    status: "ACTIVE",
    sattvicOverrideReason: null,
    version: 1,
    ingredients: [
      { ingredientId: "i1", ingredientName: "Rice", quantity: 2, unit: "KG", sattvicProhibited: false },
      { ingredientId: "i2", ingredientName: "Toor Dal", quantity: 1, unit: "KG", sattvicProhibited: false },
    ],
    createdAt: "2026-08-10T00:00:00Z",
    ...overrides,
  };
}

describe("recipe detail", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN" } };
    recipeRef.current = { data: detail(), error: null, loading: false };
    translateMock.mockReset();
  });

  it("shows the recipe, ingredients, method, badges, and the actions", () => {
    render(<RecipeDetailPage />);
    expect(screen.getByRole("heading", { name: "Khichdi" })).toBeInTheDocument();
    // "Rice" is both the category and an ingredient; assert the ingredient cell specifically.
    expect(screen.getByRole("cell", { name: "Rice" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "Toor Dal" })).toBeInTheDocument();
    expect(screen.getByText("Wash the rice.")).toBeInTheDocument();
    expect(screen.getByText(/ekadashi-friendly/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^scale$/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^translate$/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /download pdf/i })).toBeInTheDocument();
  });

  it("translates the recipe and shows the translated names", async () => {
    const translated: TranslatedRecipe = {
      recipeId: "r1",
      language: "hi",
      provider: "google",
      name: "खिचड़ी",
      categoryName: "चावल",
      ingredients: [
        { name: "चावल", quantity: 2, unit: "KG" },
        { name: "तूर दाल", quantity: 1, unit: "KG" },
      ],
      method: ["चावल धो लें।"],
    };
    translateMock.mockResolvedValue(translated);

    render(<RecipeDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /^translate$/i }));

    expect(await screen.findByText("तूर दाल")).toBeInTheDocument();
    expect(translateMock).toHaveBeenCalledWith("r1", "hi", "test-token");
    expect(screen.getByRole("heading", { name: "खिचड़ी" })).toBeInTheDocument();
  });

  it("refuses a role without recipe access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER" } };
    render(<RecipeDetailPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
