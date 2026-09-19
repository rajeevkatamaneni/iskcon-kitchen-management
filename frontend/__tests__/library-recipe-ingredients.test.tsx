import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type { MasterRecipeDetail } from "@/lib/api";

/**
 * A library recipe's ingredients sit in the same ruled table as a temple's own recipe (T-234).
 *
 * <p>They were a plain list, so one dish read two different ways depending on whether the temple
 * had added it yet (Rajeev, Decisions Desk, 2026-09-18). What is guarded here is the structure: a
 * table with the same two headings, one row per line, and the ruled-table classes that give it the
 * same column rules and the same card layout on a phone.
 */

const { recipeRef } = vi.hoisted(() => ({
  recipeRef: { current: { data: null as MasterRecipeDetail | null, error: null, loading: false } },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
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
      { name: "Rice", qty: "8 Kg", qtyValue: 8, qtyUnit: "KG", scaled: null },
      { name: "Toor dal", qty: "4 Kg", qtyValue: 4, qtyUnit: "KG", scaled: null },
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
});
