import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import type { MasterRecipeDetail } from "@/lib/api";

/**
 * A library recipe's ingredients sit in the same ruled table as a temple's own recipe (T-234).
 *
 * <p>They were a plain list, so one dish read two different ways depending on whether the temple
 * had added it yet (Rajeev, Decisions Desk, 2026-09-18). What is guarded here is the structure: a
 * table with the same two headings, one row per line, and the ruled-table classes that give it the
 * same column rules and the same card layout on a phone.
 *
 * <p>And, since T-401, that a line's name carries its preparation the same way too — "Green chilli ·
 * slit". The library has always held one; this screen printed the bare name.
 */

const { recipeRef, pushMock, importMock, closeMatchesMock } = vi.hoisted(() => ({
  recipeRef: { current: { data: null as MasterRecipeDetail | null, error: null, loading: false } },
  pushMock: vi.fn(),
  importMock: vi.fn(),
  closeMatchesMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: pushMock }),
  useParams: () => ({ id: "m1" }),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    status: "signed-in",
    appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" },
    getToken: async () => "test-token",
    signOut: vi.fn(),
  }),
}));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => recipeRef.current }));
// T-287: "Add to my recipes" asks for close matches before it copies.
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, importRecipe: importMock, importCloseMatches: closeMatchesMock },
  };
});

import LibraryRecipePage from "@/app/recipes/library/[id]/page";

function recipe(): MasterRecipeDetail {
  return {
    id: "m1",
    name: "bisibele-bath",
    displayName: "Bisibele Bath",
    subtitle: null,
    categoryKey: "rice",
    categoryName: "Rice",
    state: "Karnataka",
    region: null,
    badge: "Traditional",
    yieldText: "100 servings",
    yieldQty: 100,
    yieldUnit: "KG",
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
      { name: "Rice", qty: "8 Kg", qtyValue: 8, qtyUnit: "KG", scaled: null, prep: null, notBought: false },
      { name: "Toor dal", qty: "4 Kg", qtyValue: 4, qtyUnit: "KG", scaled: null, prep: null, notBought: false },
    ],
    method: ["Cook the rice."],
    sourceRef: "",
    alreadyAdded: false,
  };
}

describe("a library recipe's ingredients", () => {
  it("are a ruled table with the temple recipe's headings, one row per line", () => {
    recipeRef.current = { data: recipe(), error: null, loading: false };
    render(<LibraryRecipePage />);

    const table = screen.getByRole("table");
    expect(table.className).toContain("kms-table");
    expect(within(table).getAllByRole("columnheader").map((th) => th.textContent)).toEqual([
      "Ingredient",
      "Quantity",
    ]);

    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText("Rice").className).toContain("kms-primary");
    expect(within(rows[0]).getByText("8 Kg").className).toContain("kms-num");
    expect(within(rows[1]).getByText("Toor dal")).toBeInTheDocument();
    expect(within(rows[1]).getByText("4 Kg")).toBeInTheDocument();
  });

  it("print their preparation the way a temple's own recipe does, and nothing extra without one", () => {
    const withPrep = recipe();
    withPrep.ingredients = [
      { name: "Green chilli", qty: "250 gm", qtyValue: 250, qtyUnit: "GM", scaled: null, prep: "slit", notBought: false },
      { name: "Rice", qty: "8 Kg", qtyValue: 8, qtyUnit: "KG", scaled: null, prep: null, notBought: false },
    ];
    recipeRef.current = { data: withPrep, error: null, loading: false };
    render(<LibraryRecipePage />);

    const rows = within(screen.getByRole("table")).getAllByRole("row").slice(1);
    // The rendered text, not a prop: a middle dot U+00B7 with a space either side, which is the
    // separator the recipe page, the job card and the printed card all use (R-DUP-1).
    expect(within(rows[0]).getAllByRole("cell")[0].textContent).toBe("Green chilli · slit");
    // And a line with no preparation gains no separator, no trailing space, nothing.
    expect(within(rows[1]).getAllByRole("cell")[0].textContent).toBe("Rice");
  });

  it("asks \"Did you mean …?\" before copying, and a confirmed different ingredient is sent as one (T-287)", async () => {
    recipeRef.current = { data: recipe(), error: null, loading: false };
    closeMatchesMock.mockReset().mockResolvedValue([
      { libraryName: "Toor dal", note: null, existingIngredientId: "ing-tur", existingIngredientName: "Tur dal" },
    ]);
    // `notBoughtNotApplied: []` — the ordinary copy, which withheld nothing and therefore still
    // navigates straight to the temple's new recipe (T-427).
    importMock.mockReset().mockResolvedValue({
      id: "mine-1",
      name: "Bisibele Bath",
      ingredientsCreated: 1,
      categoryCreated: false,
      notBoughtNotApplied: [],
    });
    pushMock.mockReset();
    render(<LibraryRecipePage />);

    fireEvent.click(screen.getByRole("button", { name: "Add to my recipes" }));
    const dialog = await screen.findByRole("dialog", { name: "Did you mean this ingredient?" });
    expect(importMock).not.toHaveBeenCalled();

    fireEvent.click(within(dialog).getByRole("button", { name: "It’s a different ingredient" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Keep it separate" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Add to my recipes" }));

    await vi.waitFor(() =>
      expect(importMock).toHaveBeenCalledWith("m1", "test-token", [
        { libraryName: "Toor dal", useIngredientId: null, confirmDifferent: true },
      ])
    );
    await vi.waitFor(() => expect(pushMock).toHaveBeenCalledWith("/recipes/mine-1"));
  });
});
