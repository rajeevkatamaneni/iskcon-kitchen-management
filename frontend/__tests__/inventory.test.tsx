import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ApiError, StockItemView } from "@/lib/api";

const { authRef, queryRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: { current: { data: [] as StockItemView[] | null, error: null as ApiError | null, loading: false } },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: reloadMock }),
}));

import InventoryPage from "@/app/inventory/page";

function item(o: Partial<StockItemView>): StockItemView {
  return {
    itemId: "it1",
    ingredientId: "ing1",
    ingredientName: "Toor Dal",
    category: "Pulses",
    storageLocation: "Main store",
    unit: "KG",
    onHand: 2,
    reorderThreshold: 5,
    belowThreshold: true,
    expiringSoon: false,
    soonestExpiry: null,
    notes: null,
    ...o,
  };
}

describe("inventory stock view", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    queryRef.current = { data: [item({})], error: null, loading: false };
    reloadMock.mockReset();
  });

  it("shows stock with a low badge and a low-stock summary", () => {
    render(<InventoryPage />);
    expect(screen.getByRole("heading", { name: /inventory/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Toor Dal" })).toBeInTheDocument();
    expect(screen.getByText("Low")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /below reorder level/i })).toBeInTheDocument();
  });

  it("shows an empty state when nothing is tracked", () => {
    queryRef.current = { data: [], error: null, loading: false };
    render(<InventoryPage />);
    expect(screen.getByText(/nothing tracked yet/i)).toBeInTheDocument();
  });

  it("refuses a role without inventory access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<InventoryPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
