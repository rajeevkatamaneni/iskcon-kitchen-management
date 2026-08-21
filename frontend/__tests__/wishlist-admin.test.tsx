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

// The screen reads its own address bar now (item 22), so the stub has to answer both halves of
// next/navigation: what the URL says, and what a click asks the router to do with it.
const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({ id: "id-1" }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: reloadMock }),
}));

import WishlistAdminPage from "@/app/wishlist/page";
import NewWishlistItemPage from "@/app/wishlist/new/page";

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
    paramsRef.current = new URLSearchParams();
    pushMock.mockReset();
    replaceMock.mockReset();
  });

  it("lists items with sponsorship progress, and sends adding one to its own screen", () => {
    render(<WishlistAdminPage />);
    expect(screen.getByRole("heading", { name: /wish list/i })).toBeInTheDocument();
    expect(screen.getByText("Rice sacks")).toBeInTheDocument();
    expect(screen.getByText("3/10")).toBeInTheDocument();
    // Five fields, so the form is a screen of its own rather than a panel over this list.
    expect(screen.getByRole("link", { name: /add an item/i })).toHaveAttribute("href", "/wishlist/new");
  });

  it("shows the confirmation a newly added item comes back with", () => {
    paramsRef.current = new URLSearchParams("added=Rice%20sacks");
    render(<WishlistAdminPage />);
    expect(screen.getByText(/Rice sacks is on the wish list\./i)).toBeInTheDocument();
    expect(replaceMock).toHaveBeenCalledWith("/wishlist");
  });

  it("refuses a non-admin", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<WishlistAdminPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});

describe("adding a wish-list item", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    queryRef.current = { data: [], error: null, loading: false };
    pushMock.mockReset();
  });

  it("commits from the header, with Cancel beside it and no back-link", () => {
    render(<NewWishlistItemPage />);
    expect(screen.getByRole("form", { name: /add wish-list item/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /add item/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "/wishlist");
    expect(screen.queryByText(/←/)).not.toBeInTheDocument();
  });

  it("refuses a non-admin", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<NewWishlistItemPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
