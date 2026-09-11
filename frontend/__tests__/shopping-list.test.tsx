import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { api } from "@/lib/api";
import { todayIso } from "@/lib/format";
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
    excludedSince: null,
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

  it("shows suggested lines with their provenance", () => {
    render(<ShoppingListPage />);
    expect(screen.getByRole("heading", { name: /shopping list/i })).toBeInTheDocument();
    expect(screen.getByText("Rice")).toBeInTheDocument();
    expect(screen.getByText(/shortfall 7/i)).toBeInTheDocument();
    // One button, on this line's own vendor tile (T-134). Singular: it raises that vendor's order
    // and nobody else's.
    expect(screen.getByRole("button", { name: /generate purchase order$/i })).toBeInTheDocument();
  });

  // T-132. There is no button that builds this list, because there is nothing to build: the server
  // works it out from the meal plan, the store room and the live orders every time the page is read.
  // Rajeev asked why the screen needed one at all — and it turned out there were two doors onto the
  // same write, the button and a job at 04:30, so the answer was that neither should exist.
  //
  // Asserted as an absence AND as a presence, deliberately. `queryByRole` alone would pass just as
  // happily if the whole header had failed to render, which is exactly the failure a reader would
  // take this test as ruling out.
  it("has no button that builds the list, and says the list builds itself", () => {
    render(<ShoppingListPage />);
    expect(screen.queryByRole("button", { name: /generate shopping list/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /regenerate/i })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /generate purchase order$/i })).toBeInTheDocument();
    expect(screen.getByText(/worked out from the meal plan and the store room/i)).toBeInTheDocument();
  });

  // The untick persists, and its cost was named rather than hidden: one made in September suppresses
  // a January shortfall, and in between the line is not on the screen for anybody to notice. This is
  // the mitigation — when the ingredient is needed again the line comes back saying when somebody
  // decided against it, so a stale decision announces itself at the moment it starts to matter.
  it("says when an unticked line was last decided against", () => {
    queryRef.current = {
      data: [line({ included: false, excludedSince: "2026-08-20" })],
      error: null, loading: false,
    };
    render(<ShoppingListPage />);
    expect(screen.getByText(/Not ordering — since 20 Aug 2026/)).toBeInTheDocument();
  });

  it("says nothing about a date on a line that is still included", () => {
    queryRef.current = {
      data: [line({ included: true, excludedSince: null })],
      error: null, loading: false,
    };
    render(<ShoppingListPage />);
    expect(screen.queryByText(/Not ordering/)).not.toBeInTheDocument();
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
    // The old copy told the reader to generate the shopping list, which stopped being possible the
    // moment the button went. An empty state that names an action nobody can take is worse than one
    // that says nothing.
    expect(screen.queryByText(/generate the shopping list/i)).not.toBeInTheDocument();
    expect(screen.getByText(/nothing is running short/i)).toBeInTheDocument();
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

  /**
   * A tile per vendor, each with its own button, and the purchase-order edit screen opening over
   * the list as a panel (T-134, D-24 §6).
   *
   * <p>Rajeev, 2026-09-10, driving the deployed application: one flat list "does not make it clear
   * and obvious that these ingredients are going to be ordered from different vendors via separate
   * PO's", and "one Generate button at the top tied to several vendors at once is the wrong shape".
   */
  describe("a tile per vendor", () => {
    // Relative to the temple's today, not a date written out. The panel raises an order that does
    // not exist yet, so its needed-by floor is today — a fixture with a date in 2026-08 would have
    // been testing the refusal below rather than the ordinary case, and would have started doing so
    // silently on whatever day the calendar passed it.
    function inDays(n: number): string {
      const d = new Date(`${todayIso()}T12:00:00Z`);
      d.setUTCDate(d.getUTCDate() + n);
      return d.toISOString().slice(0, 10);
    }

    const RICE = line({});
    const CURD = line({
      ingredientId: "ing2", ingredientName: "Curd", suggestedQty: 4, unit: "KG",
      suggestedVendorId: "v2", suggestedVendorName: "Heritage Fresh Dairy",
      neededBy: inDays(6),
    });
    const OIL = line({
      ingredientId: "ing3", ingredientName: "Groundnut oil", suggestedQty: 5, unit: "L",
      suggestedVendorId: "v2", suggestedVendorName: "Heritage Fresh Dairy",
      neededBy: inDays(10),
    });

    afterEach(() => {
      vi.restoreAllMocks();
    });

    function withLines(rows: ShoppingListLineView[]) {
      queryRef.current = { data: rows, error: null, loading: false };
    }

    it("puts each vendor's ingredients in their own tile, with a button each", () => {
      withLines([RICE, CURD, OIL]);
      render(<ShoppingListPage />);

      const govind = screen.getByRole("table", { name: /ingredients from Govind Wholesale/i });
      const heritage = screen.getByRole("table", { name: /ingredients from Heritage Fresh Dairy/i });
      expect(within(govind).getByText("Rice")).toBeInTheDocument();
      expect(within(govind).queryByText("Curd")).not.toBeInTheDocument();
      expect(within(heritage).getByText("Curd")).toBeInTheDocument();
      expect(within(heritage).getByText("Groundnut oil")).toBeInTheDocument();

      // One button per vendor, and no button anywhere that orders from more than one of them.
      expect(screen.getAllByRole("button", { name: /generate purchase order$/i })).toHaveLength(2);
      expect(screen.queryByRole("button", { name: /generate purchase orders/i })).not.toBeInTheDocument();
    });

    // A line with no preferred supplier cannot be ordered from here, so the tile says so instead of
    // offering a button that would have nobody to send the order to.
    it("gives lines with no vendor a tile with no button", () => {
      withLines([RICE, line({ ingredientId: "ing9", ingredientName: "Ghee", suggestedVendorId: null, suggestedVendorName: null })]);
      render(<ShoppingListPage />);

      const tile = screen.getByRole("table", { name: /ingredients from no vendor/i });
      expect(within(tile).getByText("Ghee")).toBeInTheDocument();
      expect(screen.getByText(/no preferred supplier/i)).toBeInTheDocument();
      expect(screen.getAllByRole("button", { name: /generate purchase order$/i })).toHaveLength(1);
    });

    // Refused rather than hidden: the tile is still worth reading, and a button that disappeared
    // would leave somebody hunting for it. Re-ticking a line is what brings it back.
    it("refuses the button when every line in the tile is unticked", () => {
      withLines([{ ...RICE, included: false, excludedSince: "2026-08-20" }, CURD]);
      render(<ShoppingListPage />);

      const buttons = screen.getAllByRole("button", { name: /generate purchase order$/i });
      expect(buttons[0]).toBeDisabled();
      expect(buttons[1]).toBeEnabled();
    });

    /**
     * The panel is the edit screen, and this is the assertion that says so: the same fields, by the
     * same names, over the shopping list.
     */
    it("opens the purchase-order editor as a panel holding only that vendor's lines", () => {
      withLines([RICE, CURD, OIL]);
      render(<ShoppingListPage />);

      fireEvent.click(screen.getAllByRole("button", { name: /generate purchase order$/i })[1]);

      const panel = screen.getByRole("dialog", { name: /purchase order for Heritage Fresh Dairy/i });
      expect(within(panel).getByLabelText("Quantity of Curd")).toHaveValue(4);
      expect(within(panel).getByLabelText("Quantity of Groundnut oil")).toHaveValue(5);
      expect(within(panel).queryByLabelText("Quantity of Rice")).not.toBeInTheDocument();

      // One needed-by date for the whole order, and it is the earliest the vendor's lines carry —
      // the order is only useful if it arrives in time for the first meal that wants any of it.
      expect(within(panel).getByLabelText("Needed by")).toHaveValue(inDays(6));

      // The two ways of adding a line the edit screen offers, both here, because it is that screen.
      expect(within(panel).getByLabelText(/add an ingredient/i)).toBeInTheDocument();
      expect(within(panel).getByLabelText(/an item not in the catalogue/i, { selector: "input" }))
        .toBeInTheDocument();
    });

    /**
     * Rajeev: "The Cancel this PO control must not appear in that panel... Show it only when there
     * is a purchase-order number."
     *
     * <p>Nothing in this file asks for that behaviour — the gate is on the order having a number,
     * which it cannot have before it exists — and this test is what proves the gate holds where it
     * matters. The tick box goes with it: it is part of the same block.
     */
    it("offers no way to cancel a purchase order that does not exist yet", () => {
      withLines([CURD]);
      render(<ShoppingListPage />);
      fireEvent.click(screen.getByRole("button", { name: /generate purchase order$/i }));

      expect(screen.queryByRole("heading", { name: /cancel this purchase order/i })).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /cancel order/i })).not.toBeInTheDocument();
      expect(screen.queryByLabelText("Reason")).not.toBeInTheDocument();
      expect(screen.queryByText(/never delivered/i)).not.toBeInTheDocument();
      // The way out of the panel is Cancel, which cancels the panel and nothing else.
      expect(screen.getByRole("button", { name: /^cancel$/i })).toBeInTheDocument();
    });

    /**
     * An untick is the one decision this screen carries into the order, and it has to survive the
     * trip: the line stays visible in the tile — it is still that vendor's, and re-ticking is how it
     * comes back — but it is not on the order the panel raises.
     */
    it("leaves an unticked line out of the order, while keeping it on the list", async () => {
      const create = vi.spyOn(api, "createPurchaseOrder")
        .mockResolvedValue({ id: "po-new", poNumber: "PO-2026-0042" });
      withLines([CURD, { ...OIL, included: false, excludedSince: "2026-08-20" }]);
      render(<ShoppingListPage />);

      // Still in the tile, and still says when somebody decided against it.
      expect(screen.getByText("Groundnut oil")).toBeInTheDocument();
      expect(screen.getByText(/Not ordering — since 20 Aug 2026/)).toBeInTheDocument();

      fireEvent.click(screen.getByRole("button", { name: /generate purchase order$/i }));
      const panel = screen.getByRole("dialog", { name: /purchase order for Heritage Fresh Dairy/i });
      expect(within(panel).queryByLabelText("Quantity of Groundnut oil")).not.toBeInTheDocument();

      await act(async () => {
        fireEvent.submit(screen.getByRole("form", { name: /purchase order for Heritage Fresh Dairy/i }));
      });
      expect(create.mock.calls[0][0].lines).toHaveLength(1);
      expect(create.mock.calls[0][0].lines[0].ingredientId).toBe("ing2");
    });

    it("creates one order for that vendor on save, and says so by name", async () => {
      const create = vi.spyOn(api, "createPurchaseOrder")
        .mockResolvedValue({ id: "po-new", poNumber: "PO-2026-0041" });
      withLines([RICE, CURD]);
      render(<ShoppingListPage />);

      fireEvent.click(screen.getAllByRole("button", { name: /generate purchase order$/i })[1]);
      // An adjusted quantity, because carrying the edit through is the whole reason the panel is a
      // form rather than a confirmation.
      fireEvent.change(screen.getByLabelText("Quantity of Curd"), { target: { value: "6" } });
      await act(async () => {
        fireEvent.submit(screen.getByRole("form", { name: /purchase order for Heritage Fresh Dairy/i }));
      });

      expect(create).toHaveBeenCalledTimes(1);
      const sent = create.mock.calls[0][0];
      expect(sent.vendorId).toBe("v2");
      expect(sent.neededBy).toBe(inDays(6));
      expect(sent.lines).toEqual([
        { ingredientId: "ing2", description: null, quantity: 6, unit: "KG", expectedPrice: null },
      ]);

      // Back on the shopping list, with the order named in the green confirmation — "PO-2026-0041",
      // the thing a person can say out loud, and not a uuid.
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(screen.getByText(/PO-2026-0041 raised for Heritage Fresh Dairy/)).toBeInTheDocument();
      expect(reloadMock).toHaveBeenCalled();
    });

    it("creates nothing when the panel is cancelled", () => {
      const create = vi.spyOn(api, "createPurchaseOrder");
      withLines([CURD]);
      render(<ShoppingListPage />);

      fireEvent.click(screen.getByRole("button", { name: /generate purchase order$/i }));
      fireEvent.click(screen.getByRole("button", { name: /^cancel$/i }));

      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(create).not.toHaveBeenCalled();
      expect(screen.queryByText(/raised for/i)).not.toBeInTheDocument();
    });

    // The refusal has to be inside the panel. Rendered on the page behind it, it would be a message
    // nobody can see.
    it("shows a refusal inside the panel, where the person is looking", async () => {
      const create = vi.spyOn(api, "createPurchaseOrder");
      withLines([CURD]);
      render(<ShoppingListPage />);

      fireEvent.click(screen.getByRole("button", { name: /generate purchase order$/i }));
      fireEvent.click(screen.getByRole("button", { name: /remove curd/i }));
      await act(async () => {
        fireEvent.submit(screen.getByRole("form", { name: /purchase order for Heritage Fresh Dairy/i }));
      });

      expect(create).not.toHaveBeenCalled();
      const panel = screen.getByRole("dialog", { name: /purchase order for Heritage Fresh Dairy/i });
      expect(within(panel).getByText(/An order needs at least one line/)).toBeInTheDocument();
    });

    /**
     * A behaviour change worth having a test of its own, because it is not what the old bulk button
     * did (see docs/work/proof/T-134.md).
     *
     * <p>The list's needed-by date is the first meal that wants the ingredient, and that day can
     * already have gone — a meal planned for last Tuesday that is still short. The old button
     * generated such an order anyway, because generation is not a person typing. The panel is a
     * person typing: the server measures a hand-raised order's date against today and refuses
     * anything behind it (KMS-400014), so the panel says so before the round trip and leaves the
     * date in the box to be corrected.
     */
    it("refuses a date that has already gone, and leaves the panel open to fix it", async () => {
      const create = vi.spyOn(api, "createPurchaseOrder");
      withLines([{ ...CURD, neededBy: "2026-08-24" }]);
      render(<ShoppingListPage />);

      fireEvent.click(screen.getByRole("button", { name: /generate purchase order$/i }));
      const panel = screen.getByRole("dialog", { name: /purchase order for Heritage Fresh Dairy/i });
      // Said out loud before anybody presses anything, by the same warning the order screen shows.
      expect(within(panel).getByText("That day has already gone")).toBeInTheDocument();

      await act(async () => {
        fireEvent.submit(screen.getByRole("form", { name: /purchase order for Heritage Fresh Dairy/i }));
      });
      expect(create).not.toHaveBeenCalled();
      expect(within(panel).getByText(/already passed/i)).toBeInTheDocument();
      expect(within(panel).getByLabelText("Needed by")).toHaveValue("2026-08-24");
    });
  });
});
