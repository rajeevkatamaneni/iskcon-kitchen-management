import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ApiError, WishlistItemView } from "@/lib/api";

const { authRef, queryRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: { current: { data: [] as WishlistItemView[] | null, error: null as ApiError | null, loading: false } },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: reloadMock }),
}));

import WishlistAdminPage from "@/app/wishlist/page";

function item(o: Partial<WishlistItemView>): WishlistItemView {
  return {
    id: "w1", title: "Rice sacks", description: null, imageRef: null, priceInr: 1000,
    category: "CONSUMABLE", quantityWanted: 10, sponsoredQuantity: 3,
    paidInr: 0, sortOrder: 0,
    status: "ACTIVE", note: null, createdAt: "2026-08-01T00:00:00Z", ...o,
  };
}

describe("wish-list admin", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    queryRef.current = { data: [item({})], error: null, loading: false };
    reloadMock.mockReset();
  });

  it("lists items with sponsorship progress and an add control", () => {
    render(<WishlistAdminPage />);
    expect(screen.getByRole("heading", { name: /wish list/i })).toBeInTheDocument();
    expect(screen.getByText("Rice sacks")).toBeInTheDocument();
    expect(screen.getByText("3/10")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /add an item/i })).toBeInTheDocument();
  });

  it("refuses a non-admin", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<WishlistAdminPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
