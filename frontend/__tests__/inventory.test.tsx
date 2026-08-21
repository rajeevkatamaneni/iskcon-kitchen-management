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

import InventoryPage from "@/app/inventory/page";
import NewInventoryItemPage from "@/app/inventory/new/page";

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
    paramsRef.current = new URLSearchParams();
    pushMock.mockReset();
    replaceMock.mockReset();
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

describe("tracking a new item", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    queryRef.current = { data: [], error: null, loading: false };
    pushMock.mockReset();
  });

  // Four fields is exactly the threshold Q1 settled, and the rule is what decides — not a judgement
  // made form by form. So this one is a screen, on the line rather than over it.
  it("is a screen of its own, reached from the list", () => {
    queryRef.current = { data: [item({})], error: null, loading: false };
    render(<InventoryPage />);
    expect(screen.getByRole("link", { name: /track an item/i })).toHaveAttribute("href", "/inventory/new");
  });

  it("commits from the header, with Cancel beside it and no back-link", () => {
    render(<NewInventoryItemPage />);
    expect(screen.getByRole("heading", { name: /track an item/i })).toBeInTheDocument();
    expect(screen.getByRole("form", { name: /track an item/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /start tracking/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "/inventory");
    expect(screen.queryByText(/←/)).not.toBeInTheDocument();
  });

  it("shows the confirmation a newly tracked item comes back with", () => {
    queryRef.current = { data: [item({})], error: null, loading: false };
    paramsRef.current = new URLSearchParams("tracking=Toor%20Dal");
    render(<InventoryPage />);
    expect(screen.getByText(/Toor Dal is now tracked\./i)).toBeInTheDocument();
    expect(replaceMock).toHaveBeenCalledWith("/inventory");
  });
});
