import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError } from "@/lib/api";
import type { RecipeDetail, TranslatedRecipe } from "@/lib/api";

const { authRef, recipeRef, translateMock, deleteMock, archiveMock, restoreMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" } } as {
      status: string;
      appUser: { role: string; fullName?: string } | null;
    },
  },
  recipeRef: { current: { data: null as RecipeDetail | null, error: null, loading: false } },
  translateMock: vi.fn(),
  deleteMock: vi.fn(),
  archiveMock: vi.fn(),
  restoreMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn() }),
  useParams: () => ({ id: "r1" }),
  // The back link reads the search out of the address so it can return to it.
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => recipeRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      translateRecipe: translateMock,
      deleteRecipe: deleteMock,
      archiveRecipe: archiveMock,
      restoreRecipe: restoreMock,
    },
  };
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
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" } };
    recipeRef.current = { data: detail(), error: null, loading: false };
    translateMock.mockReset();
    deleteMock.mockReset();
    archiveMock.mockReset();
    restoreMock.mockReset();
    // jsdom refuses a real navigation; the delete path ends in one, and what it navigates *to* is
    // the assertion, so the location is replaced with something writable.
    Object.defineProperty(window, "location", {
      configurable: true,
      writable: true,
      value: { href: "", reload: vi.fn() },
    });
  });

  it("asks before deleting, and says plainly that it cannot be undone", async () => {
    render(<RecipeDetailPage />);

    fireEvent.click(screen.getByRole("button", { name: /^delete$/i }));

    const confirm = screen.getByRole("alertdialog", { name: /delete this recipe/i });
    expect(confirm).toHaveTextContent(/Delete Khichdi\?/);
    expect(confirm).toHaveTextContent(/cannot be undone/i);
    expect(deleteMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: /delete recipe/i }));
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith("r1", "test-token"));
    // Nothing to go back to once it is gone.
    await waitFor(() => expect(window.location.href).toBe("/recipes"));
  });

  it("offers archiving when the recipe has been cooked, rather than leaving a refusal", async () => {
    // KMS-4967 is the server saying "archive it instead" — the screen has to carry that through
    // to something the person can press, or they are simply stuck.
    deleteMock.mockRejectedValue(
      new ApiError({
        code: "KMS-4967",
        message: "This recipe has been cooked, so it can't be deleted.",
        action: "Archive it instead.",
        fieldErrors: [],
      })
    );
    render(<RecipeDetailPage />);

    fireEvent.click(screen.getByRole("button", { name: /^delete$/i }));
    fireEvent.click(screen.getByRole("button", { name: /delete recipe/i }));

    const archive = await screen.findByRole("button", { name: /archive it instead/i });
    expect(screen.getByText(/has been cooked/i)).toBeInTheDocument();

    fireEvent.click(archive);
    await waitFor(() => expect(archiveMock).toHaveBeenCalledWith("r1", "test-token"));
  });

  it("an archived recipe says so, and offers the way back rather than a delete", async () => {
    recipeRef.current = { data: detail({ status: "ARCHIVED" }), error: null, loading: false };
    render(<RecipeDetailPage />);

    expect(screen.getByText(/this recipe is archived/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^delete$/i })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /restore/i }));
    await waitFor(() => expect(restoreMock).toHaveBeenCalledWith("r1", "test-token"));
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
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", fullName: "Test Person" } };
    render(<RecipeDetailPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});

describe("what a recipe makes, and what one person eats", () => {
  it("shows both as labelled figures, with their units", async () => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    recipeRef.current = {
      data: detail({ baseYieldQty: 20, baseYieldUnit: "LITRES", perHeadQty: 0.2, perHeadUnit: "LITRES" }),
      error: null,
      loading: false,
    };
    render(<RecipeDetailPage />);
    // "0.2 litres a head" used to sit at the end of one grey sentence, and nobody found it there
    // (Rajeev, 2026-08-23).
    expect(await screen.findByText("Makes")).toBeInTheDocument();
    expect(screen.getByText("20 litres")).toBeInTheDocument();
    expect(screen.getByText("Per person")).toBeInTheDocument();
    // 0.2 of a litre is not how anybody serves rasam — it is 200 ml (Rajeev, 2026-08-23).
    expect(screen.getByText("200 ml")).toBeInTheDocument();
    expect(screen.queryByText("0.2 litres")).not.toBeInTheDocument();
  });
});
