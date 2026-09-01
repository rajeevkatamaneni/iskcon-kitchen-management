import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type { ApiError, IssuedFromStore, KitchenIssueCost } from "@/lib/api";

/**
 * What the store issued to each kitchen, costed (E10-S13).
 *
 * <p>The arithmetic belongs to the backend's `IssuedFromStoreIT`. What these guard is the honesty of
 * the screen, and above all the one sentence the report cannot be read safely without: this is what
 * the temple store issued, a kitchen may buy food itself, and every figure here is therefore a floor
 * and not a total (INV5). If somebody later re-words the heading into "Deity kitchen food cost",
 * these tests are what should stop them.
 */

const { authRef, queryRef } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Radha Devi", tenantName: "ISKCON Bengaluru" },
    } as { status: string; appUser: { role: string; fullName?: string; tenantName?: string } | null },
  },
  queryRef: {
    current: { data: null as IssuedFromStore | null, error: null as ApiError | null, loading: false },
  },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token", signOut: vi.fn() }),
}));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => queryRef.current }));

import IssuedFromStorePage from "@/app/issued-from-store/page";

function kitchen(overrides: Partial<KitchenIssueCost> = {}): KitchenIssueCost {
  return {
    kitchenId: "k-1",
    kitchen: "Deity kitchen",
    usesMealPlanner: false,
    requests: 12,
    ingredients: 18,
    estimatedTotal: 24800,
    ingredientsPriced: 18,
    ingredientsWithoutPrice: 0,
    unpriced: [],
    ...overrides,
  };
}

function report(overrides: Partial<IssuedFromStore> = {}): IssuedFromStore {
  return {
    from: "2026-08-01",
    to: "2026-08-31",
    requests: 20,
    estimatedTotal: 31400,
    ingredientsWithoutPrice: 0,
    unpriced: [],
    kitchens: [kitchen()],
    ...overrides,
  };
}

function rowFor(name: string) {
  return screen.getByRole("row", { name: new RegExp(name) });
}

describe("Issued from the temple store", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Radha Devi", tenantName: "ISKCON Bengaluru" },
    };
    queryRef.current = { data: null, error: null, loading: false };
  });

  /**
   * The INV5 condition, pinned. The heading names the store rather than the kitchen, and the line
   * under it says in as many words that a kitchen's own buying is not in the figure.
   */
  it("says it is what the store issued, and that the figures are a floor", () => {
    queryRef.current.data = report();
    render(<IssuedFromStorePage />);

    expect(
      screen.getByRole("heading", { name: "Issued from the temple store" })
    ).toBeInTheDocument();
    expect(screen.getByText(/A kitchen may also buy food itself/)).toBeInTheDocument();
    expect(screen.getByText(/a floor, not a total/)).toBeInTheDocument();
    expect(screen.getByText(/its real food cost is higher/)).toBeInTheDocument();
  });

  it("costs each kitchen, and keeps the order the server sent", () => {
    queryRef.current.data = report({
      kitchens: [
        kitchen({ kitchenId: "k-1", kitchen: "Deity kitchen", estimatedTotal: 24800 }),
        kitchen({ kitchenId: "k-2", kitchen: "Food for Life", estimatedTotal: 6600, requests: 8 }),
      ],
    });
    render(<IssuedFromStorePage />);

    const names = screen
      .getAllByRole("rowheader")
      .map((cell) => cell.textContent?.split("\n")[0]?.trim());
    expect(names).toEqual(["Deity kitchen", "Food for Life", "All kitchens"]);

    expect(within(rowFor("Deity kitchen")).getByText("₹24,800")).toBeInTheDocument();
    expect(within(rowFor("Food for Life")).getByText("₹6,600")).toBeInTheDocument();
    expect(within(rowFor("All kitchens")).getByText("₹31,400")).toBeInTheDocument();
  });

  /** The estimate never appears without the hole in it being said out loud (E3-S8 D2). */
  it("declares the unpriced ingredients above the table and against the kitchen", () => {
    queryRef.current.data = report({
      ingredientsWithoutPrice: 2,
      unpriced: [
        { ingredientId: "i-1", name: "Rock Salt", quantity: 0.4, unit: "KG" },
        { ingredientId: "i-2", name: "Curry Leaves", quantity: 0.2, unit: "KG" },
      ],
      kitchens: [kitchen({ ingredientsWithoutPrice: 2, ingredientsPriced: 16 })],
    });
    render(<IssuedFromStorePage />);

    expect(
      screen.getByText(/Estimated, materials only · 2 ingredients have no known price/)
    ).toBeInTheDocument();
    expect(screen.getByText(/Rock Salt, Curry Leaves are left out/)).toBeInTheDocument();
    expect(
      within(rowFor("Deity kitchen")).getByText("2 ingredients have no known price")
    ).toBeInTheDocument();
  });

  it("still says the figure is an estimate when every ingredient has a price", () => {
    queryRef.current.data = report();
    render(<IssuedFromStorePage />);

    expect(screen.getByText(/Estimated, materials only/)).toBeInTheDocument();
    expect(screen.getByText(/Labour, fuel and the rest/)).toBeInTheDocument();
  });

  /**
   * One kitchen, one door. A kitchen that has since moved to the meal planner draws consumption, so
   * its row here is history and the screen has to say so rather than let it read as current.
   */
  it("marks a kitchen that now plans its meals here", () => {
    queryRef.current.data = report({
      kitchens: [kitchen({ kitchen: "Guest house kitchen", usesMealPlanner: true })],
    });
    render(<IssuedFromStorePage />);

    expect(
      within(rowFor("Guest house kitchen")).getByText(
        "Now plans its meals here, so its newer food is counted as consumption."
      )
    ).toBeInTheDocument();
  });

  it("points a temple at donations in kind when a kitchen buys its own food", () => {
    queryRef.current.data = report();
    render(<IssuedFromStorePage />);

    expect(screen.getByText(/record them as a donation in kind/)).toBeInTheDocument();
  });

  it("says nothing was issued rather than showing a table of zeroes", () => {
    queryRef.current.data = report({ kitchens: [], requests: 0, estimatedTotal: 0 });
    render(<IssuedFromStorePage />);

    expect(screen.getByText("The store issued nothing in this period")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});
