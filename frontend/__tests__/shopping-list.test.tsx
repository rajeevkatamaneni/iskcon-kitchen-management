import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ApiError, IngredientView, ShoppingListLineView } from "@/lib/api";

const { authRef, queryRef, catalogueRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: { current: { data: [] as ShoppingListLineView[] | null, error: null as ApiError | null, loading: false } },
  catalogueRef: { current: [] as IngredientView[] | null },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
// The screen now runs two queries — the list itself, and the ingredient catalogue behind the
// add-a-line picker (T-027) — so the stub answers by which api method it was handed rather than
// giving both the same rows. `api.listIngredients` is a named property on the api object, so its
// function name is exactly that, which is what makes this readable rather than positional.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: { name?: string }) =>
    fetcher?.name === "listIngredients"
      ? { data: catalogueRef.current, error: null, loading: false, reload: reloadMock }
      : { ...queryRef.current, reload: reloadMock },
}));

import ShoppingListPage from "@/app/shopping-list/page";

function line(o: Partial<ShoppingListLineView>): ShoppingListLineView {
  return {
    ingredientId: "ing1",
    ingredientName: "Rice",
    currentStock: 3,
    unit: "KG",
    suggestedQty: 9,
    neededBy: "2026-08-20",
    suggestedVendorId: "v1",
    suggestedVendorName: "Govind Wholesale",
    shortfall: 7,
    thresholdTopUp: 0,
    poOutstanding: 0,
    shortPurchaseOrders: [],
    included: true,
    edited: false,
    ...o,
  };
}

describe("shopping list", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    queryRef.current = { data: [line({})], error: null, loading: false };
    catalogueRef.current = [];
    reloadMock.mockReset();
  });

  it("shows suggested lines with provenance and the button that builds them", () => {
    render(<ShoppingListPage />);
    expect(screen.getByRole("heading", { name: /shopping list/i })).toBeInTheDocument();
    expect(screen.getByText("Rice")).toBeInTheDocument();
    expect(screen.getByText(/shortfall 7/i)).toBeInTheDocument();
    // Named for what it does since 2026-09-05. "Regenerate" was the only thing in the product that
    // actually built this list, while the planner carried an accent button called "Generate shopping
    // list" that merely navigated here — so the real action was the one wearing the smaller word.
    expect(screen.getByRole("button", { name: /^generate shopping list$/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /generate purchase orders/i })).toBeInTheDocument();
  });

  it("surfaces a short purchase order as provenance", () => {
    queryRef.current = {
      data: [line({ shortfall: 0, poOutstanding: 6, shortPurchaseOrders: ["PO-2026-0042"] })],
      error: null,
      loading: false,
    };
    render(<ShoppingListPage />);
    expect(screen.getByText("PO-2026-0042")).toBeInTheDocument();
    expect(screen.getByText(/PO short 6/i)).toBeInTheDocument();
  });

  it("shows an empty state when nothing needs ordering", () => {
    queryRef.current = { data: [], error: null, loading: false };
    render(<ShoppingListPage />);
    expect(screen.getByText(/nothing to order/i)).toBeInTheDocument();
  });
});
