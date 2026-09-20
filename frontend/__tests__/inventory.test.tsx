import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
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
    // T-432: the three facts the stock row now carries. The defaults are the honest ones for a
    // fixture that says nothing about them — nobody counted it, nothing is on order, and there is
    // not enough history to judge how long it lasts.
    lastCounted: null,
    onOrder: null,
    lastsFor: null,
    ...o,
  };
}

/**
 * One cell of the one row on screen, by the label the card layout prints before it below 1024.
 *
 * <p>Read by `data-label` rather than by text, because three columns now legitimately hold the same
 * em dash — committed and on order both say "nothing" that way — and `getByText` on a dash would
 * find whichever came first and pass for the wrong reason.
 */
function cell(container: HTMLElement, label: string): HTMLElement | null {
  return container.querySelector(`tbody [data-label="${label}"]`);
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
   * T-086. The table shows its working: on hand, committed, available. `Reorder at` came off,
   * because a setting is not a state — and in T-432 so did Location, which is now a line under the
   * name, and Status, whose commonest value was the word "Fine" printed down a column a hundred
   * times. The badges it held sit beside the name they are about.
   */
  it("shows on hand and available, and no reorder, status, location or committed column", () => {
    queryRef.current = {
      data: [item({ onHand: 50, committed: 30, available: 20, reorderThreshold: 25, belowThreshold: true })],
      error: null,
      loading: false,
    };
    render(<InventoryPage />);

    expect(screen.getByRole("columnheader", { name: "On hand" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Available" })).toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: /reorder at/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: "Status" })).not.toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: "Location" })).not.toBeInTheDocument();
    // T-432, and measured rather than argued: eight columns needed about 1060px at 1280 with the
    // menu open and had 1000, so "Approximately 20 days" broke across two lines. Committed is the
    // one of the three figures that is never itself an action, the row still shows the gap it
    // describes, and the item's own page lists it meal by meal.
    expect(screen.queryByRole("columnheader", { name: "Committed" })).not.toBeInTheDocument();

    expect(screen.getByText("50 Kg")).toBeInTheDocument();
    expect(screen.getByText("20 Kg")).toBeInTheDocument();
    expect(screen.queryByText("30 Kg")).not.toBeInTheDocument();
  });

  /* T-432: the three facts Rajeev asked for, each with its own column. */
  it("shows what is on order, how long it lasts and when it was last counted", () => {
    queryRef.current = {
      data: [
        item({
          onHand: 50,
          committed: 0,
          available: 50,
          onOrder: 200,
          lastCounted: "2026-09-14",
          lastsFor: { days: 12, beyondWindow: false, runsOutOn: "2026-10-02", perDay: 4 },
        }),
      ],
      error: null,
      loading: false,
    };
    const { container } = render(<InventoryPage />);

    expect(cell(container, "On order")).toHaveTextContent("200 Kg");
    expect(cell(container, "Lasts")).toHaveTextContent("Approximately 12 days");
    expect(cell(container, "Last counted")).toHaveTextContent("14 Sept 2026");
  });

  /*
   * The honesty requirement (Rajeev, 2026-09-20): where there is not enough history to judge, the
   * screen says so rather than guessing. `lastsFor` is null and the cell is neutral, never amber —
   * "we cannot tell" is the absence of a judgement, not a warning.
   */
  it("says so plainly when there is not enough history to judge, and does not colour it", () => {
    queryRef.current = {
      data: [item({ lastsFor: null, lastCounted: null })],
      error: null,
      loading: false,
    };
    const { container } = render(<InventoryPage />);

    expect(cell(container, "Lasts")).toHaveTextContent("Not enough history");
    expect(cell(container, "Lasts")?.querySelector(".text-warning")).toBeNull();
    expect(cell(container, "Last counted")).toHaveTextContent("Never counted");
  });

  it("warns in amber only when it runs out inside a week", () => {
    queryRef.current = {
      data: [
        item({
          onHand: 12,
          available: 12,
          lastsFor: { days: 3, beyondWindow: false, runsOutOn: "2026-09-23", perDay: 4 },
        }),
      ],
      error: null,
      loading: false,
    };
    const { container } = render(<InventoryPage />);

    expect(cell(container, "Lasts")?.querySelector(".text-warning")).not.toBeNull();
  });

  /*
   * One row, one unit (T-432).
   *
   * <p>Rajeev found this exact row on staging: 2.06 Kg on hand, 2.04 Kg committed, 20 gm available.
   * Every figure was right on its own — `quantity()` promotes from 1,000 up and is asked one figure
   * at a time — and the row was unreadable, because a subtraction changed scale in the middle of
   * itself. The assertion that matters is the negative one: nothing in the row says "gm".
   */
  it("says every figure in a row in one unit, so the subtraction does not change scale", () => {
    queryRef.current = {
      data: [item({ ingredientName: "Almond", onHand: 2.06, committed: 2.04, available: 0.02, onOrder: 0.5 })],
      error: null,
      loading: false,
    };
    const { container } = render(<InventoryPage />);

    expect(cell(container, "On hand")).toHaveTextContent("2.06 Kg");
    expect(cell(container, "Available")).toHaveTextContent("0.02 Kg");
    expect(cell(container, "On order")).toHaveTextContent("0.5 Kg");
    expect(container.querySelector("tbody")?.textContent).not.toContain("gm");
  });

  it("shows a dash where nothing is on order — a draft is not on order", () => {
    queryRef.current = { data: [item({ onOrder: null })], error: null, loading: false };
    const { container } = render(<InventoryPage />);

    expect(cell(container, "On order")).toHaveTextContent("\u2014");
  });

  /*
   * 114 consumables on the seeded temple and no way to reach one but scrolling. The category is
   * searched as well as the name, because a storekeeper thinks in shelves as often as in names —
   * and a supply is in this list beside the food, which is the thing Rajeev asked be made findable.
   */
  it("finds one consumable among many, by name or by category", () => {
    queryRef.current = {
      data: [
        item({ itemId: "a", ingredientName: "Toor Dal", category: "Pulses" }),
        item({ itemId: "b", ingredientId: "ing2", ingredientName: "Leaf plates", category: "Serving" }),
      ],
      error: null,
      loading: false,
    };
    render(<InventoryPage />);

    fireEvent.change(screen.getByRole("searchbox", { name: /search inventory/i }), {
      target: { value: "leaf" },
    });
    expect(screen.getByRole("link", { name: "Leaf plates" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Toor Dal" })).not.toBeInTheDocument();

    fireEvent.change(screen.getByRole("searchbox", { name: /search inventory/i }), {
      target: { value: "pulses" },
    });
    expect(screen.getByRole("link", { name: "Toor Dal" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Leaf plates" })).not.toBeInTheDocument();
  });

  it("says nothing matched rather than offering to add a second one", () => {
    render(<InventoryPage />);
    fireEvent.change(screen.getByRole("searchbox", { name: /search inventory/i }), {
      target: { value: "zzzz" },
    });
    expect(screen.getByText(/nothing here matches/i)).toBeInTheDocument();
    expect(screen.queryByText(/nothing in your inventory yet/i)).not.toBeInTheDocument();
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

/**
 * The reorder level edited in place on the row (T-424).
 *
 * <p>This box is **outside** the shared `<Form>`, so nothing says a word about it on its behalf. It
 * says the sentence itself, from `formMessages.wholeNumberProblem` — the same function `Form` words
 * its own refusals with — so the two screens that ask for a reorder level say the same thing.
 */
describe("the reorder level of a counted ingredient", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    paramsRef.current = new URLSearchParams();
  });

  const edit = () => fireEvent.click(screen.getByRole("button", { name: "Edit" }));
  const level = (name: string) => screen.getByRole("spinbutton", { name: `Tell me when ${name} drops below` });

  it("steps by 1 for a counted ingredient and by any for a weighed one", () => {
    queryRef.current = { data: [item({ ingredientName: "Apron", unit: "PIECES" })], error: null, loading: false };
    render(<InventoryPage />);
    edit();
    expect(level("Apron")).toHaveAttribute("step", "1");
    expect(level("Apron")).toHaveAttribute("inputmode", "numeric");
  });

  it("refuses a fractional level and does not save it", () => {
    queryRef.current = { data: [item({ ingredientName: "Apron", unit: "PIECES" })], error: null, loading: false };
    render(<InventoryPage />);
    edit();
    fireEvent.change(level("Apron"), { target: { value: "3.6" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(screen.getByText("Apron is counted in whole pieces")).toHaveClass("text-danger");
    expect(level("Apron")).toHaveAttribute("aria-invalid", "true");
    // Still on screen holding what was typed, to be corrected rather than retyped.
    expect(level("Apron")).toHaveValue(3.6);
  });

  it("still takes a fractional level on a weighed ingredient", () => {
    queryRef.current = { data: [item({ ingredientName: "Toor Dal", unit: "KG" })], error: null, loading: false };
    render(<InventoryPage />);
    edit();
    expect(level("Toor Dal")).toHaveAttribute("step", "any");
    fireEvent.change(level("Toor Dal"), { target: { value: "3.6" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(screen.queryByText(/whole/)).toBeNull();
  });

  it("still shows a fractional level that is already on file", () => {
    queryRef.current = {
      data: [item({ ingredientName: "Apron", unit: "PIECES", reorderThreshold: 3.6 })],
      error: null, loading: false,
    };
    render(<InventoryPage />);
    edit();
    expect(level("Apron")).toHaveValue(3.6);
  });
});
