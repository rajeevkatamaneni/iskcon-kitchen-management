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

// The screen reads its own address bar (item 22, and E10-S12's confirmation), so the stub has to
// answer both halves of next/navigation: what the URL says, and what a click asks of the router.
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

function item(o: Partial<StockItemView>): StockItemView {
  return {
    itemId: "it1",
    ingredientId: "ing1",
    ingredientName: "Toor Dal",
    category: "Pulses",
    storageLocation: "Main store",
    unit: "KG",
    onHand: 2,
    committed: 0,
    available: 2,
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
    expect(screen.getByRole("heading", { level: 1, name: /^inventory$/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Toor Dal" })).toBeInTheDocument();
    expect(screen.getByText("Low")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /below reorder level/i })).toBeInTheDocument();
  });

  /*
   * T-086. The table shows its working: on hand, committed, available — and Status judges the
   * third of those. `Reorder at` came off, because a setting is not a state.
   */
  it("shows on hand, committed and available, and no reorder column", () => {
    queryRef.current = {
      data: [item({ onHand: 50, committed: 30, available: 20, reorderThreshold: 25, belowThreshold: true })],
      error: null,
      loading: false,
    };
    render(<InventoryPage />);

    expect(screen.getByRole("columnheader", { name: "On hand" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Committed" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Available" })).toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: /reorder at/i })).not.toBeInTheDocument();

    expect(screen.getByText("50 Kg")).toBeInTheDocument();
    expect(screen.getByText("30 Kg")).toBeInTheDocument();
    expect(screen.getByText("20 Kg")).toBeInTheDocument();
  });

  it("writes nothing rather than a zero where no meal has claimed any of it", () => {
    queryRef.current = {
      data: [item({ onHand: 1.8, committed: 0, available: 1.8, reorderThreshold: null, belowThreshold: false })],
      error: null,
      loading: false,
    };
    render(<InventoryPage />);

    expect(screen.getByText("Fine")).toBeInTheDocument();
    // An em dash, not "0 Gm": the column is scanned down, and a column of zeroes hides the one row
    // that is not zero. The location cell is filled, so this dash can only be the committed one.
    expect(screen.getByText("\u2014")).toBeInTheDocument();
    expect(screen.queryByText("0 Gm")).not.toBeInTheDocument();
  });

  it("reads Low on plenty on hand that is nearly all committed — the defect this column exists for", () => {
    // 415.41 kg of ash gourd with 410 of it promised to Sunday. Judged on hand this said Fine.
    queryRef.current = {
      data: [
        item({
          ingredientName: "Ash gourd",
          onHand: 415.41,
          committed: 410,
          available: 5.41,
          reorderThreshold: 25,
          belowThreshold: true,
        }),
      ],
      error: null,
      loading: false,
    };
    render(<InventoryPage />);

    expect(screen.getByText("Low")).toBeInTheDocument();
    expect(screen.queryByText("Fine")).not.toBeInTheDocument();
  });

  it("shows an empty state when nothing is tracked", () => {
    queryRef.current = { data: [], error: null, loading: false };
    render(<InventoryPage />);
    expect(screen.getByText(/nothing in your inventory yet/i)).toBeInTheDocument();
  });

  it("refuses a role without inventory access", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<InventoryPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});

describe("adding an item", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    queryRef.current = { data: [item({})], error: null, loading: false };
    paramsRef.current = new URLSearchParams();
    pushMock.mockReset();
    replaceMock.mockReset();
  });

  // Five fields, which DESIGN_SYSTEM.md puts over the threshold: a form of four fields or more
  // becomes a screen. The panel that used to sit here sat on top of the very list somebody was
  // checking the item was not already in (E10-S12).
  it("sends adding to a screen of its own rather than a panel above the list", () => {
    render(<InventoryPage />);
    expect(screen.queryByRole("form", { name: /add to inventory/i })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /add to inventory/i })).toHaveAttribute(
      "href",
      "/inventory/new"
    );
  });

  it("points an empty list at the add screen rather than at a panel above it", () => {
    queryRef.current = { data: [], error: null, loading: false };
    render(<InventoryPage />);
    expect(screen.getByText(/nothing in your inventory yet/i)).toBeInTheDocument();
    expect(screen.queryByText(/above/i)).not.toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: /add to inventory/i })[0]).toHaveAttribute(
      "href",
      "/inventory/new"
    );
  });

  it("shows the confirmation a newly added item comes back with, and strips the param", () => {
    paramsRef.current = new URLSearchParams("added=Toor%20Dal");
    render(<InventoryPage />);
    expect(screen.getByText(/Toor Dal is now in your inventory/i)).toBeInTheDocument();
    expect(replaceMock).toHaveBeenCalledWith("/inventory");
  });
});
