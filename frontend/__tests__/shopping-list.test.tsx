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
    orderBy: "2026-08-20",
    leadTimeDays: 2,
    orderUrgency: "IN_TIME",
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

  // T-090. The column that used to say "Needed by" now answers the question somebody reading this
  // list is actually asking: when does this have to go out? The old column showed the delivery date
  // written on the purchase order, which is a question for the order screen.
  describe("the order-by date", () => {
    it("shows the date while there is still slack", () => {
      queryRef.current = {
        data: [line({ orderBy: "2026-08-20", orderUrgency: "IN_TIME", leadTimeDays: 3 })],
        error: null, loading: false,
      };
      render(<ShoppingListPage />);
      expect(screen.getByText(/^Order by 20 Aug 2026$/)).toBeInTheDocument();
      expect(screen.queryByText("assumed")).not.toBeInTheDocument();
    });

    it("says the date was our assumption when no lead time is recorded", () => {
      queryRef.current = {
        data: [line({ orderBy: "2026-08-20", orderUrgency: "IN_TIME", leadTimeDays: null })],
        error: null, loading: false,
      };
      render(<ShoppingListPage />);
      expect(screen.getByText("assumed")).toBeInTheDocument();
    });

    it("asks for the order on the day itself", () => {
      queryRef.current = {
        data: [line({ orderBy: "2026-08-20", orderUrgency: "ORDER_TODAY", leadTimeDays: 2 })],
        error: null, loading: false,
      };
      render(<ShoppingListPage />);
      expect(screen.getByText("Order today")).toBeInTheDocument();
    });

    // The state that must not be built as a darker red: past the date, advice is useless, so the
    // cell states what is now true instead of repeating the instruction.
    it("stops advising once the date has gone", () => {
      queryRef.current = {
        data: [line({ orderBy: "2026-08-01", orderUrgency: "TOO_LATE", leadTimeDays: 2 })],
        error: null, loading: false,
      };
      render(<ShoppingListPage />);
      expect(screen.getByText("Won’t arrive in time")).toBeInTheDocument();
      // `/^Order by \d/` and not `/^Order by/`: the column's own header reads "Order by", so the
      // looser pattern matches the table heading and fails for a reason that has nothing to do with
      // the badge under test.
      expect(screen.queryByText(/^Order by \d/)).not.toBeInTheDocument();
      expect(screen.queryByText("Order today")).not.toBeInTheDocument();
    });

    // A hand-added line: nothing demanded it by a date, so no deadline is invented for it. An em
    // dash — deliberately not today, which would put a red badge on a line nobody is late for.
    it("prints an em dash for a line no meal demanded", () => {
      queryRef.current = {
        data: [line({ orderBy: null, orderUrgency: null, leadTimeDays: null, edited: true })],
        error: null, loading: false,
      };
      render(<ShoppingListPage />);
      expect(screen.getByText("—")).toBeInTheDocument();
    });
  });
});
