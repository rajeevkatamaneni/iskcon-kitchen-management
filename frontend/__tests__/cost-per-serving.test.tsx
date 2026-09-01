import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type { ApiError, CostByMealKind, MealKindCost } from "@/lib/api";

/**
 * Cost per serving, compared across the kinds of meal a temple cooks (E3-S9).
 *
 * <p>What these guard is the honesty of the screen rather than its arithmetic, which the backend's
 * `MaterialsCostIT` owns: that a kind nobody counted shows a dash instead of a number, that the
 * meals left out of the division are named, and that no figure appears without the estimate and the
 * unpriced ingredients being said out loud beside it.
 */

const { authRef, queryRef } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Radha Devi", tenantName: "ISKCON Bengaluru" },
    } as { status: string; appUser: { role: string; fullName?: string; tenantName?: string } | null },
  },
  queryRef: {
    current: { data: null as CostByMealKind | null, error: null as ApiError | null, loading: false },
  },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token", signOut: vi.fn() }),
}));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => queryRef.current }));

import CostPerServingPage from "@/app/cost-per-serving/page";

function kind(overrides: Partial<MealKindCost> = {}): MealKindCost {
  return {
    mealKind: "Lunch",
    meals: 30,
    servings: 6000,
    mealsWithoutServings: 0,
    estimatedTotal: 18400,
    costPerServing: 9.3,
    ingredientsPriced: 22,
    ingredientsWithoutPrice: 0,
    unpriced: [],
    ...overrides,
  };
}

function report(overrides: Partial<CostByMealKind> = {}): CostByMealKind {
  return {
    from: "2026-08-01",
    to: "2026-08-31",
    meals: 34,
    servings: 6800,
    mealsWithoutServings: 0,
    estimatedTotal: 23800,
    ingredientsWithoutPrice: 0,
    unpriced: [],
    kinds: [kind()],
    ...overrides,
  };
}

function rowFor(name: string) {
  return screen.getByRole("row", { name: new RegExp(name) });
}

describe("Cost per serving", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Radha Devi", tenantName: "ISKCON Bengaluru" },
    };
    queryRef.current = { data: null, error: null, loading: false };
  });

  it("puts each kind's own figure per serving beside its total", () => {
    queryRef.current.data = report({
      kinds: [
        kind({ mealKind: "Festival feast", meals: 2, servings: 1200, estimatedTotal: 21600, costPerServing: 18 }),
        kind({ mealKind: "Lunch" }),
      ],
    });
    render(<CostPerServingPage />);

    const feast = rowFor("Festival feast");
    // Whole rupees where the figure is whole, paise where there are any — the shared `money`
    // helper's rule, so this column reads like every other money column in the application.
    expect(within(feast).getByText("₹18")).toBeInTheDocument();
    expect(within(feast).getByText("₹21,600")).toBeInTheDocument();

    const lunch = rowFor("Lunch");
    expect(within(lunch).getByText("₹9.30")).toBeInTheDocument();
  });

  /**
   * The kinds arrive dearest first and the screen must not re-sort them: the comparison the
   * reviewers asked for is the one you get by reading the column downwards.
   */
  it("keeps the order the server sent, dearest serving first", () => {
    queryRef.current.data = report({
      kinds: [
        kind({ mealKind: "Festival feast", costPerServing: 18 }),
        kind({ mealKind: "Lunch", costPerServing: 9.3 }),
        kind({ mealKind: "Breakfast", costPerServing: 3 }),
      ],
    });
    render(<CostPerServingPage />);

    const names = screen
      .getAllByRole("rowheader")
      .map((cell) => cell.textContent?.split("\n")[0]?.trim());
    expect(names).toEqual(["Festival feast", "Lunch", "Breakfast", "All meals"]);
  });

  it("shows a dash rather than a number for a kind nobody counted", () => {
    queryRef.current.data = report({
      meals: 4,
      servings: 0,
      mealsWithoutServings: 4,
      estimatedTotal: 3720,
      kinds: [
        kind({
          mealKind: "Deity Offering",
          meals: 4,
          servings: 0,
          mealsWithoutServings: 4,
          estimatedTotal: 3720,
          costPerServing: null,
        }),
      ],
    });
    render(<CostPerServingPage />);

    const row = rowFor("Deity Offering");
    expect(within(row).getByText("—")).toBeInTheDocument();
    expect(within(row).getByText("4 meals not counted")).toBeInTheDocument();
  });

  it("says how many meals were left out of the division, and why the total still holds them", () => {
    queryRef.current.data = report({ mealsWithoutServings: 3 });
    render(<CostPerServingPage />);

    expect(
      screen.getByText(/3 meals have no head count, so they are in the totals but not in the cost per serving/)
    ).toBeInTheDocument();
  });

  it("never shows a figure without saying it is an estimate of materials alone", () => {
    queryRef.current.data = report();
    render(<CostPerServingPage />);

    expect(screen.getByText(/Estimated, materials only/)).toBeInTheDocument();
    expect(screen.getByText(/Labour, fuel and the rest of what a meal costs are not in these figures/))
      .toBeInTheDocument();
  });

  it("counts and names the ingredients the estimate could not cover", () => {
    queryRef.current.data = report({
      ingredientsWithoutPrice: 2,
      unpriced: [
        { ingredientId: "i1", name: "Rock Salt", quantity: 0.4, unit: "KG" },
        { ingredientId: "i2", name: "Curry Leaves", quantity: 1, unit: "KG" },
      ],
      kinds: [kind({ ingredientsWithoutPrice: 2 })],
    });
    render(<CostPerServingPage />);

    // Once at the head of the report, and again on the row it actually bites — a reader comparing
    // two kinds needs to know which of them the gap is in.
    expect(screen.getAllByText(/2 ingredients have no known price/)).toHaveLength(2);
    expect(screen.getByText(/Rock Salt, Curry Leaves/)).toBeInTheDocument();
  });

  it("says nothing was cooked rather than showing a table of zeroes", () => {
    queryRef.current.data = report({ meals: 0, servings: 0, estimatedTotal: 0, kinds: [] });
    render(<CostPerServingPage />);

    expect(screen.getByText("Nothing was cooked in this period")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("is not offered to a volunteer", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER" } };
    queryRef.current.data = report();
    render(<CostPerServingPage />);

    expect(screen.getByText("Not your page")).toBeInTheDocument();
  });
});
